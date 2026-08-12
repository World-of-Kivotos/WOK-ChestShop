package com.wokchestshop;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 告示牌商店的全部玩家交互入口 (服务端权威)。
 *
 * 交互约定 (沿用 ChestShop): <b>右键买入, 左键卖出</b>。
 * 本 mod 额外统一一条规则: <b>潜行 = 原版行为</b> —— 潜行右键照常编辑告示牌、潜行左键照常破坏方块。
 * 但"潜行破坏"只对 OP 开放: 已激活的商店牌对普通玩家一律不可破坏, 否则任何人 Shift+左键一秒就能
 * 拆掉全服的商店 (原版侧不会兜底, 生存玩家挖木牌完全合法)。
 *
 * 安全边界一览 (每一条都对应一个被复核证实过的攻击面):
 *  1. 内容指纹 —— 交易前用当前牌面与激活快照比对, 不符即注销并拒绝。这是防"幽灵登记"的主防线,
 *     见 {@link AdminShopRegistry} 的类注释。
 *  2. 上蜡 —— 辅助闸, 冻结文本编辑。不能单独承重: 蜜脾任何玩家都能自己用, 上蜡不代表被授权。
 *  3. 距离与区块校验 —— 左键路径跑在原版 canReach/mayInteract 之前, 必须自行补齐, 否则改包客户端
 *     可隔墙交易, 更严重的是乱填坐标会让 getBlockEntity 同步强制加载任意区块拖垮主线程。
 *  4. 交易防抖 —— 客户端会按 tick 节律重发交互包, 只过滤 Action.START 挡不住连刷。
 */
public final class ShopInteractionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger("wokchestshop");

    /** Level.sendBlockUpdated 的 flag: 2 = 通知客户端 (上蜡状态要立刻同步, 否则客户端仍以为能编辑)。 */
    private static final int BLOCK_UPDATE_CLIENTS = 2;

    /**
     * 交互距离平方上限 (6 格)。原版对 use/attack 包的服务端校验也是这个量级, 取 6 而非精确的 4.5/5
     * 是给网络延迟留余量, 只要能挡住"隔着几百格交易"就达到目的。
     */
    private static final double MAX_REACH_SQ = 36.0D;

    /**
     * 同一玩家对同一块牌的成交冷却 (tick)。
     *
     * 为什么必须有: 只过滤 {@code Action.START} 仅仅挡住了"一次按下产生 START+STOP+ABORT 三连发",
     * 挡不住客户端反复重发 START —— 创造模式每 6 tick 重发一次, 生存瞬破工具几乎每 tick 回落一次。
     * 服务端 setCanceled 只让原版提前 return, 客户端下一 tick 照发。没有这层, 玩家拿效率斧对着收购店
     * 按住左键一秒就会把整包库存连续扣空。右键侧同理 (原版 rightClickDelay=4, 按住即 4 次/秒),
     * 且一次右键可能对主手与副手各触发一次事件, 也一并被这张表吸收。
     */
    private static final long TRADE_COOLDOWN_TICKS = 10L;

    /** (玩家 UUID -> 上次成交的 tick 与位置)。仅服务端主线程访问。 */
    private final Map<UUID, LastTrade> lastTrades = new HashMap<>();

    private record LastTrade(long gameTime, long posKey) {
    }

    // ============================================================
    // 右键 = 买入 / 激活
    // ============================================================

    @SubscribeEvent
    public void onRightClick(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || event.getLevel().isClientSide()) {
            return;
        }
        if (player.isSpectator()) {
            // 原版客户端确实会为旁观者发出 ServerboundUseItemOnPacket (旁观者的早退发生在包已发出之后,
            // 这也是"旁观者右键能打开箱子界面"的由来), 而 Forge 把本事件派发在 SPECTATOR 判定之前。
            // 不挡的话, 旁观者可以穿墙飞进封闭的商店房间真实扣款发货, 切回生存物品还在。
            return;
        }
        if (player.isShiftKeyDown()) {
            return; // 潜行 = 原版编辑, 不干预。
        }
        BlockPos pos = event.getPos();
        if (!canInteractAt(player, event.getLevel(), pos)) {
            return;
        }
        SignBlockEntity sign = signAt(event.getLevel(), pos);
        if (sign == null || !isAdminShopSign(sign)) {
            // 不是商店牌: 完全不介入, 普通告示牌该怎么用怎么用。但若该坐标还挂着登记, 说明牌已经没了
            // 或被换成了别的东西 —— 惰性清理掉, 免得它继续挂在表里等人来捡。
            sweepStaleRegistration(player.serverLevel(), pos, sign);
            return;
        }

        ServerLevel level = player.serverLevel();
        AdminShopRegistry registry = AdminShopRegistry.get(level);
        AdminShopRegistry.ShopRecord record = registry.recordAt(pos);

        if (record == null) {
            // 未激活: 只有 OP 能激活, 普通玩家看到的就是一块还没生效的牌。
            if (isOp(player)) {
                activate(player, level, registry, sign, pos);
                event.setCanceled(true);
            }
            return;
        }

        event.setCanceled(true); // 已激活的牌一律不再进入原版编辑/交互流程。
        ShopSignSpec spec = verifiedSpec(player, level, registry, sign, pos, record);
        if (spec == null) {
            return;
        }
        if (!passCooldown(player, pos)) {
            return;
        }
        report(player, ShopTransaction.buy(player, spec), spec, true);
    }

    // ============================================================
    // 左键 = 卖出
    // ============================================================

    @SubscribeEvent
    public void onLeftClick(PlayerInteractEvent.LeftClickBlock event) {
        // 一次按下服务端会收到 START/STOP/ABORT, 只认按下那一次。注意这【不足以】防连点,
        // 真正的防抖在 passCooldown (客户端会按 tick 反复重发 START)。
        if (event.getAction() != PlayerInteractEvent.LeftClickBlock.Action.START) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player) || event.getLevel().isClientSide()) {
            return;
        }
        if (player.isSpectator()) {
            return; // 与右键侧同纪律 (左键侧原版另有 blockActionRestricted 兜底, 这里是显式对齐)。
        }
        BlockPos pos = event.getPos();
        // 本事件跑在 ServerPlayerGameMode.handleBlockBreakAction 的第一行, 早于原版的 canReach 与
        // mayInteract, 且包层对坐标无任何前置校验。不自行补齐的话: 改包客户端可隔墙远程交易,
        // 更糟的是下面的 signAt 会对未加载区块触发主线程同步加载, 乱填坐标即可远程卡服。
        if (!canInteractAt(player, event.getLevel(), pos)) {
            return;
        }
        SignBlockEntity sign = signAt(event.getLevel(), pos);
        ServerLevel level = player.serverLevel();
        if (sign == null || !isAdminShopSign(sign)) {
            sweepStaleRegistration(level, pos, sign);
            return;
        }

        AdminShopRegistry registry = AdminShopRegistry.get(level);
        AdminShopRegistry.ShopRecord record = registry.recordAt(pos);
        if (record == null) {
            return; // 未激活的牌左键就是正常破坏。
        }

        if (player.isShiftKeyDown()) {
            // 潜行左键 = 拆店。只有 OP 可以; 普通玩家一律拦住, 否则一秒一块牌能扫掉整条商业街。
            if (!isOp(player)) {
                event.setCanceled(true);
                player.sendSystemMessage(Component.translatable("message.wokchestshop.err.protected"));
            }
            return; // OP 潜行左键: 放行原版破坏流程, 由 onBlockBreak 负责注销。
        }

        // 拦住破坏: 已激活的店不能被随手左键拆掉 (创造模式左键是瞬间破坏)。
        event.setCanceled(true);

        ShopSignSpec spec = verifiedSpec(player, level, registry, sign, pos, record);
        if (spec == null) {
            return;
        }
        if (!passCooldown(player, pos)) {
            return;
        }
        report(player, ShopTransaction.sell(player, spec), spec, false);
    }

    // ============================================================
    // 破坏 = 注销登记 (且只有 OP 能破坏已激活的店)
    // ============================================================

    // LOWEST: 注销是不可回滚的落盘写入 (deactivate 会 setDirty), 而 Forge 在所有监听器 post 完之后
    // 才判 isCanceled。若用默认优先级, 任何排在本 mod 之后的领地/保护类监听器取消破坏时, 我们已经把
    // 登记抹掉了 —— 牌还在原地但商店静默失效。排到最后, 让注销发生在所有可能取消该事件的监听器之后。
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.isCanceled() || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        BlockPos pos = event.getPos();
        AdminShopRegistry registry = AdminShopRegistry.get(level);
        if (!registry.isActivated(pos)) {
            return;
        }
        if (!(event.getPlayer() instanceof ServerPlayer player) || !isOp(player)) {
            // 非 OP 破坏已激活的商店牌: 拦住。左键路径已经拦过一次, 这里是兜底 (其它 mod、
            // 自动化工具、非左键途径的玩家破坏都会经过 BreakEvent)。
            event.setCanceled(true);
            if (event.getPlayer() instanceof ServerPlayer p) {
                p.sendSystemMessage(Component.translatable("message.wokchestshop.err.protected"));
            }
            return;
        }
        registry.deactivate(pos);
        LOGGER.info("[wokchestshop] admin shop deactivated by op break: dim={} pos={} by={}",
                level.dimension().location(), pos, player.getGameProfile().getName());
    }

    // ============================================================
    // 激活
    // ============================================================

    private void activate(ServerPlayer player, ServerLevel level, AdminShopRegistry registry,
                          SignBlockEntity sign, BlockPos pos) {
        ShopSignSpec.Result parsed = ShopSignSpec.parse(readLines(sign));
        if (!parsed.ok()) {
            player.sendSystemMessage(Component.translatable(errorKey(parsed.error())));
            return;
        }

        ShopSignSpec spec = parsed.spec();
        registry.activate(pos, spec);

        // 上蜡: 冻结文本编辑, 免得 OP 自己或别人顺手把买价改成 1。这是辅助闸不是主防线 ——
        // 蜜脾任何玩家都能用, "已上蜡"不等于"被授权", 真正承重的是上面那条内容快照。
        if (!sign.isWaxed()) {
            sign.setWaxed(true);
            sign.setChanged();
            BlockState state = level.getBlockState(pos);
            level.sendBlockUpdated(pos, state, state, BLOCK_UPDATE_CLIENTS);
        }

        LOGGER.info("[wokchestshop] admin shop activated: dim={} pos={} item={} qty={} buy={} sell={} by={}",
                level.dimension().location(), pos, spec.item(), spec.quantity(),
                spec.price().buyPrice(), spec.price().sellPrice(), player.getGameProfile().getName());

        player.sendSystemMessage(Component.translatable(
                "message.wokchestshop.activated",
                spec.quantity(),
                Component.translatable(spec.item().getDescriptionId()),
                describePrice(spec.price())));
    }

    // ============================================================
    // 安全校验
    // ============================================================

    /**
     * 解析当前牌面并与激活快照比对。这是防"幽灵登记"的主防线: 只要内容与当初被 OP 授权的那块牌
     * 对不上 (被撬蜡改价, 或牌早已不在、玩家在残留坐标重新立了一块自己写的), 立即注销并拒绝交易。
     *
     * @return 校验通过的规格; 任何一步不过返回 null (已就地发过提示)
     */
    private ShopSignSpec verifiedSpec(ServerPlayer player, ServerLevel level, AdminShopRegistry registry,
                                      SignBlockEntity sign, BlockPos pos, AdminShopRegistry.ShopRecord record) {
        ShopSignSpec.Result parsed = ShopSignSpec.parse(readLines(sign));
        if (!parsed.ok()) {
            registry.deactivate(pos);
            LOGGER.warn("[wokchestshop] deactivated unparsable shop sign: dim={} pos={} error={}",
                    level.dimension().location(), pos, parsed.error());
            player.sendSystemMessage(Component.translatable(errorKey(parsed.error())));
            return null;
        }
        if (!record.matches(parsed.spec())) {
            registry.deactivate(pos);
            LOGGER.error("[wokchestshop] shop content mismatch, deactivated: dim={} pos={} expected={} actual={} by={}",
                    level.dimension().location(), pos, record,
                    AdminShopRegistry.ShopRecord.of(parsed.spec()), player.getGameProfile().getName());
            player.sendSystemMessage(Component.translatable("message.wokchestshop.err.tampered"));
            return null;
        }
        return parsed.spec();
    }

    /**
     * 惰性清理: 某坐标还挂着登记, 但那里已经不是一块商店牌了 (被爆炸/活塞/支撑方块脱落/setblock 等
     * 不触发 BreakEvent 的途径毁掉, 或被换成了别的方块)。
     *
     * 这只是让残留项尽早消失, 不是安全边界 —— 真正拦住"在残留坐标重新立牌白捡商店"的是内容快照比对。
     */
    private void sweepStaleRegistration(ServerLevel level, BlockPos pos, SignBlockEntity sign) {
        AdminShopRegistry registry = AdminShopRegistry.get(level);
        if (!registry.isActivated(pos)) {
            return;
        }
        registry.deactivate(pos);
        LOGGER.info("[wokchestshop] swept stale registration (no admin shop sign there anymore): dim={} pos={} present={}",
                level.dimension().location(), pos, sign == null ? "no-sign" : "sign-without-header");
    }

    /**
     * 距离与区块校验。必须在任何 {@code getBlockEntity} 之前调用。
     *
     * hasChunkAt 那条不只是防御性检查: {@code Level.getBlockEntity} 走 getChunk(FULL, true), 对未加载
     * 区块是主线程同步阻塞加载甚至生成。左键路径的坐标完全由客户端携带且原版不做前置校验, 少了这条,
     * 每秒几十个乱填坐标的包就能把服务端主线程拖进区块生成。
     */
    private static boolean canInteractAt(ServerPlayer player, Level level, BlockPos pos) {
        // 用 LevelReader.hasChunk(chunkX, chunkZ) 这个未过时的基础重载, 而非已过时的 hasChunkAt(BlockPos)。
        if (!level.hasChunk(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()))) {
            return false;
        }
        double dx = pos.getX() + 0.5D;
        double dy = pos.getY() + 0.5D;
        double dz = pos.getZ() + 0.5D;
        if (player.distanceToSqr(dx, dy, dz) > MAX_REACH_SQ) {
            return false;
        }
        return level.mayInteract(player, pos);
    }

    /**
     * 成交防抖。同一玩家对同一坐标在冷却内的重复触发直接吞掉 (事件仍已 setCanceled, 方块安全)。
     *
     * @return true 表示可以成交
     */
    private boolean passCooldown(ServerPlayer player, BlockPos pos) {
        long now = player.serverLevel().getGameTime();
        long key = pos.asLong();
        LastTrade last = lastTrades.get(player.getUUID());
        if (last != null && last.posKey() == key && now - last.gameTime() < TRADE_COOLDOWN_TICKS) {
            return false;
        }
        lastTrades.put(player.getUUID(), new LastTrade(now, key));
        return true;
    }

    // ============================================================
    // 工具
    // ============================================================

    private static SignBlockEntity signAt(Level level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        return be instanceof SignBlockEntity sign ? sign : null;
    }

    /** 只看正面第 1 行: 背面另有文本时不参与判定, 避免一块牌两个身份。 */
    private static boolean isAdminShopSign(SignBlockEntity sign) {
        return ShopSignSpec.isAdminShopHeader(lineText(sign.getFrontText(), ShopConstants.LINE_OWNER));
    }

    private static String[] readLines(SignBlockEntity sign) {
        SignText text = sign.getFrontText();
        String[] lines = new String[ShopConstants.SIGN_LINES];
        for (int i = 0; i < ShopConstants.SIGN_LINES; i++) {
            lines[i] = lineText(text, i);
        }
        return lines;
    }

    /** filtered=false 取原文: 服务端权威判定不能用被聊天过滤器改写过的文本, 否则价格会被过滤器篡改。 */
    private static String lineText(SignText text, int line) {
        Component component = text.getMessage(line, false);
        return component == null ? "" : component.getString();
    }

    /**
     * OP 判定。与主 mod MarketAdminActions.requireOp 同款: PlayerList.isOp(GameProfile) 是确定的公开 API,
     * hasPermissions(int) 在 ServerPlayer 上的语义跨版本不一。
     *
     * 注意本判定是"是否在 ops.json 里"而非"权限等级 >= 2", 两者在原版默认配置下等价 (op 默认给 level 4),
     * 但服主若手工把某人的 level 调到 1, 这里仍会放行。这是刻意与主 mod 保持一致, 不另立第二套口径。
     */
    private static boolean isOp(Player player) {
        if (!(player instanceof ServerPlayer serverPlayer) || serverPlayer.getServer() == null) {
            return false;
        }
        return serverPlayer.getServer().getPlayerList().isOp(serverPlayer.getGameProfile());
    }

    private void report(ServerPlayer player, ShopTransaction.Outcome outcome, ShopSignSpec spec, boolean buying) {
        if (!outcome.ok()) {
            player.sendSystemMessage(Component.translatable(reasonKey(outcome.reason())));
            return;
        }
        Component itemName = Component.translatable(spec.item().getDescriptionId());
        if (buying) {
            player.sendSystemMessage(Component.translatable(
                    "message.wokchestshop.bought", spec.quantity(), itemName, outcome.settled()));
            return;
        }
        // 卖出实发额低于标价 = 撞上了主 mod 的每日 faucet 衰减主闸。必须明示, 否则玩家会以为商店少给钱。
        if (outcome.settled() < outcome.listed()) {
            player.sendSystemMessage(Component.translatable(
                    "message.wokchestshop.sold_decayed",
                    spec.quantity(), itemName, outcome.settled(), outcome.listed()));
        } else {
            player.sendSystemMessage(Component.translatable(
                    "message.wokchestshop.sold", spec.quantity(), itemName, outcome.settled()));
        }
    }

    /** 把价格行渲染成给人看的一句话 (激活回执用)。 */
    private static Component describePrice(ShopPriceLine price) {
        if (price.allowsBuy() && price.allowsSell()) {
            return Component.translatable("message.wokchestshop.price_both", price.buyPrice(), price.sellPrice());
        }
        return price.allowsBuy()
                ? Component.translatable("message.wokchestshop.price_buy_only", price.buyPrice())
                : Component.translatable("message.wokchestshop.price_sell_only", price.sellPrice());
    }

    static String errorKey(ShopSignSpec.Error error) {
        return switch (error) {
            case NOT_ADMIN_SHOP -> "message.wokchestshop.err.not_admin_shop";
            case BAD_QUANTITY -> "message.wokchestshop.err.bad_quantity";
            case BAD_ITEM -> "message.wokchestshop.err.bad_item";
            case BAD_PRICE -> "message.wokchestshop.err.bad_price";
            case INVERTED_SPREAD -> "message.wokchestshop.err.inverted_spread";
        };
    }

    static String reasonKey(ShopTransaction.Reason reason) {
        return switch (reason) {
            case OK -> "message.wokchestshop.ok";
            case DIRECTION_UNSUPPORTED -> "message.wokchestshop.err.direction";
            case INSUFFICIENT_FUNDS -> "message.wokchestshop.err.funds";
            case INSUFFICIENT_ITEMS -> "message.wokchestshop.err.items";
            case ECONOMY_UNAVAILABLE -> "message.wokchestshop.err.economy";
        };
    }
}
