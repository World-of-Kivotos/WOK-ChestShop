package com.wokchestshop;

import com.miningdim.economy.Currency;
import com.miningdim.economy.EconomyConstants;
import com.miningdim.economy.EconomyServices;
import com.miningdim.economy.IEconomyService;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 管理员商店的交易执行 (系统侧无限库存, 玩家侧真实背包)。
 *
 * 与主 mod 经济层的接线纪律 (来自 docs/Economy_Completeness_Audit.md 的结论):
 *  - 玩家买入 -> {@link IEconomyService#tryCharge}: 钱真正离开经济体, 这是本 mod 存在的主要理由
 *    (审计里"系统买枪弹"是文档列明却零代码的最大额 sink)。
 *  - 玩家卖出 -> {@link IEconomyService#grantDaily} 并入主 mod 的每人每日信用点衰减主闸, 与挖矿/卖菜
 *    共用同一 credit_faucet 键。绝不能图省事用 grant: 那等于在衰减主闸之外另开一个 faucet,
 *    玩家可以绕开挖矿上限、靠刷可再生资源卖给系统无限变现。
 *
 * 全部方法只在服务端主线程调用。
 */
public final class ShopTransaction {

    private ShopTransaction() {
    }

    /** 交易结果原因码; 每个值对应一条给玩家看的 lang 提示。 */
    public enum Reason {
        OK,
        /** 该店不做这个方向 (只挂了 B 却来卖, 或反之)。 */
        DIRECTION_UNSUPPORTED,
        /** 玩家信用点不足。 */
        INSUFFICIENT_FUNDS,
        /** 玩家背包里没有足够的、无损伤无 NBT 的该物品。 */
        INSUFFICIENT_ITEMS,
        /** 主 mod 经济门面尚未注入 (服务端启动中或矿山维度缺失)。 */
        ECONOMY_UNAVAILABLE
    }

    /**
     * @param reason   结果
     * @param settled  实际结算金额: 买入是实扣, 卖出是"经衰减主闸后的实发额"(可能小于告示牌标价)
     * @param listed   告示牌标价 (供与 settled 比对, 差额即当日衰减)
     */
    public record Outcome(Reason reason, long settled, long listed) {

        public boolean ok() {
            return reason == Reason.OK;
        }

        static Outcome fail(Reason reason) {
            return new Outcome(reason, 0L, 0L);
        }
    }

    /**
     * 玩家从商店买入 quantity 个物品。
     *
     * 顺序是"先扣钱再给物": 扣钱失败即整笔中止, 不会出现给了物没收到钱的情况。给物阶段若背包放不下,
     * 剩余部分掉在玩家脚下而不是静默蒸发 —— 玩家已经付过钱了, 任何情况下都必须拿到等量的货。
     */
    public static Outcome buy(ServerPlayer player, ShopSignSpec spec) {
        if (!spec.price().allowsBuy()) {
            return Outcome.fail(Reason.DIRECTION_UNSUPPORTED);
        }
        if (!EconomyServices.isRegistered()) {
            return Outcome.fail(Reason.ECONOMY_UNAVAILABLE);
        }

        long price = spec.price().buyPrice();
        // 免费店 (B free): 跳过扣款。主 mod 的 tryCharge 契约要求 amount > 0, 传 0 会抛 ILLEGAL_AMOUNT。
        if (price > 0L) {
            IEconomyService eco = EconomyServices.economyService();
            if (!eco.tryCharge(player, Currency.CREDIT, price)) {
                return Outcome.fail(Reason.INSUFFICIENT_FUNDS);
            }
        }

        giveItems(player, spec.item(), spec.quantity());
        return new Outcome(Reason.OK, price, price);
    }

    /**
     * 玩家把 quantity 个物品卖给商店。
     *
     * 顺序是"先验货再扣货再发钱": 验货不过整笔中止, 不会扣了货才发现发不出钱。
     *
     * 关于实发额可能小于标价: 那是主 mod 衰减主闸的正常语义 (当日 faucet 累计越高单价越低),
     * 不是错误, 故不回滚物品 —— 主 mod 的 carry 机制会把不足 1 点的小数留到下次入账, 不存在真正的"白送"。
     * 调用方应把 settled 与 listed 一并展示给玩家, 让衰减是可见的。
     */
    public static Outcome sell(ServerPlayer player, ShopSignSpec spec) {
        if (!spec.price().allowsSell()) {
            return Outcome.fail(Reason.DIRECTION_UNSUPPORTED);
        }
        if (!EconomyServices.isRegistered()) {
            return Outcome.fail(Reason.ECONOMY_UNAVAILABLE);
        }

        int quantity = spec.quantity();
        if (countSellable(player, spec.item()) < quantity) {
            return Outcome.fail(Reason.INSUFFICIENT_ITEMS);
        }

        long listed = spec.price().sellPrice();
        removeSellable(player, spec.item(), quantity);

        // 免费收购 (S free): 只收货不发钱。grantDaily 契约同样要求 rawCredit > 0。
        if (listed <= 0L) {
            return new Outcome(Reason.OK, 0L, 0L);
        }

        long settled = EconomyServices.economyService().grantDaily(
                player, listed,
                EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_KEY,
                EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_TIER);
        return new Outcome(Reason.OK, settled, listed);
    }

    /**
     * 数玩家背包里有多少个"可卖"的该物品。
     *
     * 只认无损伤且无 NBT 的纯净物品, 这是一条经济安全红线而非洁癖: 附魔钻石剑与素铁剑在
     * {@code stack.is(item)} 层面无法区分, 若不卡 NBT, 玩家可以把高价值附魔/命名/自定义数据物品
     * 按素材价卖给系统, 而系统按告示牌标价原样付钱 —— 那是把物品价值差直接铸成信用点。
     */
    static int countSellable(ServerPlayer player, Item item) {
        Inventory inv = player.getInventory();
        int total = 0;
        for (int i = 0; i < mainInventorySize(inv); i++) {
            ItemStack stack = inv.getItem(i);
            if (isSellable(stack, item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /**
     * 只数主背包 36 格, 不碰盔甲槽与副手。
     *
     * {@code Inventory.getContainerSize()} 返回 41 (36 主背包 + 4 盔甲 + 1 副手), 而 {@code getItem(i)}
     * 会按 compartments 顺序穿透到盔甲与副手。若用 41 作上界, 玩家戴着一顶崭新未附魔的钻石头盔去左键
     * "diamond_helmet" 收购店, 会把正戴着的头盔直接卖掉 (副手的崭新盾牌/图腾/鞘翅同理) —— 扣的不是
     * 玩家以为的那份货, 且事前无任何确认。
     *
     * 计数与扣除两处必须共用本上界, 否则会制造判定分歧从而触发 removeSellable 的 IllegalStateException。
     */
    private static int mainInventorySize(Inventory inv) {
        return inv.items.size();
    }

    /**
     * 可卖判定: 与告示牌同物品、未损伤、且不带任何"自定义数据"。
     *
     * 关于 Damage 键的例外 —— 这是被 GameTest 抓出来的一个真实缺陷:
     * 一切可损伤物品 (工具/武器/盔甲) 即使全新未附魔, {@code new ItemStack(...)} 出来就自带 {Damage:0}。
     * 若把"有 NBT"一律判为不可卖, 管理员将永远开不出"收购铁镐"这类店 —— 全世界没有一把镐子能通过校验。
     * 故这里放行"除 Damage 外别无他物"的情形; 真正的损伤已由上面的 isDamaged() 单独挡掉,
     * 附魔(Ench)、命名(display)、铁砧代价(RepairCost)、以及任何 mod 自定义键都会让 size() > 1 从而被拒。
     */
    private static boolean isSellable(ItemStack stack, Item item) {
        if (stack.isEmpty() || !stack.is(item)) {
            return false;
        }
        if (stack.isDamaged()) {
            return false;
        }
        CompoundTag tag = stack.getTag();
        if (tag == null || tag.isEmpty()) {
            return true;
        }
        return tag.size() == 1 && tag.contains(ItemStack.TAG_DAMAGE);
    }

    /** 从背包精确移除 count 个可卖物品; 调用前必须已用 {@link #countSellable} 确认数量足够。 */
    private static void removeSellable(ServerPlayer player, Item item, int count) {
        Inventory inv = player.getInventory();
        int remaining = count;
        for (int i = 0; i < mainInventorySize(inv) && remaining > 0; i++) {
            ItemStack stack = inv.getItem(i);
            if (!isSellable(stack, item)) {
                continue;
            }
            int take = Math.min(remaining, stack.getCount());
            stack.shrink(take);
            if (stack.isEmpty()) {
                // 与原版 ContainerHelper.removeItem 同纪律: 取空的槽位显式置 EMPTY,
                // 不要在物品栏里留一个 count=0 的残栈。
                inv.setItem(i, ItemStack.EMPTY);
            }
            remaining -= take;
        }
        if (remaining > 0) {
            // 到这里说明 countSellable 与本方法对"可卖"的判定发生了分歧, 是编码错误而非玩家行为,
            // 必须炸出来而不是静默少扣 (少扣 = 玩家白拿钱)。
            throw new IllegalStateException(
                    "removeSellable could not take " + count + " of " + item + ", short by " + remaining);
        }
        player.getInventory().setChanged();
    }

    /**
     * 按最大堆叠分批塞进背包; 放不下的掉在玩家脚下 (玩家已付款, 绝不允许货物蒸发)。
     *
     * 兜底判据用"背包实际增量"而不是 {@code Inventory.add} 的返回值 —— 这是一个会真吞货的坑:
     * 创造模式下 vanilla 在本轮一件都没塞进去时, 会把 stack 清零并 return true (判据是
     * {@code abilities.instabuild}), 于是"add 返回 true 且 stack 已空"同时成立, 依赖返回值的兜底
     * 分支永不触发, 已付款的货物就真的消失了。且不必背包全满 —— 只要没有空槽、现有堆叠又只能吸收
     * 一部分, 未被吸收的部分同样蒸发。
     */
    private static void giveItems(ServerPlayer player, Item item, int count) {
        int maxStack = new ItemStack(item).getMaxStackSize();
        if (maxStack <= 0) {
            // 理论上不该发生; 若某 mod 物品声明了 0 堆叠, 下面的循环会永不推进, 必须炸出来而不是挂死主线程。
            throw new IllegalStateException("item " + item + " reports maxStackSize=" + maxStack);
        }
        int remaining = count;
        while (remaining > 0) {
            int batch = Math.min(remaining, maxStack);
            int before = countAnyInMainInventory(player, item);
            player.getInventory().add(new ItemStack(item, batch));
            int delivered = countAnyInMainInventory(player, item) - before;
            if (delivered < batch) {
                player.drop(new ItemStack(item, batch - delivered), false);
            }
            remaining -= batch;
        }
    }

    /**
     * 数主背包里该物品的总数, 不论 NBT 与损伤。仅供 {@link #giveItems} 校验实际交付量,
     * 与 {@link #countSellable} 的"可卖"判定是两回事 (那个要卡 NBT, 这个只关心到没到货)。
     */
    private static int countAnyInMainInventory(ServerPlayer player, Item item) {
        Inventory inv = player.getInventory();
        int total = 0;
        for (int i = 0; i < mainInventorySize(inv); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }
}
