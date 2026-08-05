package com.wokchestshop;

import com.miningdim.economy.Currency;
import com.miningdim.economy.EconomyConstants;
import com.miningdim.economy.EconomyServices;
import com.miningdim.economy.IEconomyService;
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
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (isSellable(stack, item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static boolean isSellable(ItemStack stack, Item item) {
        if (stack.isEmpty() || !stack.is(item)) {
            return false;
        }
        if (stack.isDamaged()) {
            return false;
        }
        return stack.getTag() == null || stack.getTag().isEmpty();
    }

    /** 从背包精确移除 count 个可卖物品; 调用前必须已用 {@link #countSellable} 确认数量足够。 */
    private static void removeSellable(ServerPlayer player, Item item, int count) {
        Inventory inv = player.getInventory();
        int remaining = count;
        for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
            ItemStack stack = inv.getItem(i);
            if (!isSellable(stack, item)) {
                continue;
            }
            int take = Math.min(remaining, stack.getCount());
            stack.shrink(take);
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

    /** 按最大堆叠分批塞进背包; 放不下的掉在玩家脚下 (玩家已付款, 绝不允许货物蒸发)。 */
    private static void giveItems(ServerPlayer player, Item item, int count) {
        int maxStack = new ItemStack(item).getMaxStackSize();
        int remaining = count;
        while (remaining > 0) {
            int batch = Math.min(remaining, maxStack);
            ItemStack stack = new ItemStack(item, batch);
            // Inventory.add 会就地扣减 stack 的 count; 返回 false 表示没放完, 此时 stack 里是剩余部分。
            if (!player.getInventory().add(stack) && !stack.isEmpty()) {
                player.drop(stack, false);
            }
            remaining -= batch;
        }
    }
}
