package com.wokchestshop;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 告示牌商店的全部玩家交互入口 (服务端权威)。
 *
 * 交互约定 (沿用 ChestShop): <b>右键买入, 左键卖出</b>。
 * 本 mod 额外统一一条规则: <b>潜行 = 原版行为</b> —— 潜行右键照常编辑告示牌、潜行左键照常破坏方块,
 * 不潜行才走商店逻辑。这样 OP 永远有一条不与商店冲突的路去维护牌子, 无需再造一套命令。
 *
 * 激活模型见 {@link AdminShopRegistry}: 写牌任何人都能写, 但只有 OP 不潜行右键激活过的牌才参与交易。
 * 激活时连带上蜡, 之后连 OP 自己都改不了文本 (要改先撬蜡), 杜绝"激活后偷偷改价"。
 */
public final class ShopInteractionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger("wokchestshop");

    /** 与主 mod 的 admin 动作同级 (原版 OP/gamemaster 级)。 */
    private static final int OP_LEVEL = 2;

    /** Level.sendBlockUpdated 的 flag: 2 = 通知客户端 (上蜡状态要立刻同步, 否则客户端仍以为能编辑)。 */
    private static final int BLOCK_UPDATE_CLIENTS = 2;

    // ============================================================
    // 右键 = 买入 / 激活
    // ============================================================

    @SubscribeEvent
    public void onRightClick(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || event.getLevel().isClientSide()) {
            return;
        }
        if (player.isShiftKeyDown()) {
            return; // 潜行 = 原版编辑, 不干预。
        }
        SignBlockEntity sign = signAt(event.getLevel(), event.getPos());
        if (sign == null || !isAdminShopSign(sign)) {
            return; // 不是商店牌: 完全不介入, 普通告示牌该怎么用怎么用。
        }

        ServerLevel level = player.serverLevel();
        AdminShopRegistry registry = AdminShopRegistry.get(level);
        BlockPos pos = event.getPos();

        if (!registry.isActivated(pos)) {
            // 未激活: 只有 OP 能激活, 普通玩家看到的就是一块还没生效的牌。
            if (isOp(player)) {
                activate(player, level, registry, sign, pos);
                event.setCanceled(true);
            }
            return;
        }

        event.setCanceled(true); // 已激活的牌一律不再进入原版编辑/交互流程。
        ShopSignSpec.Result parsed = ShopSignSpec.parse(readLines(sign));
        if (!parsed.ok()) {
            // 已激活却解析不了: 牌被撬蜡改坏了。报错而非静默无视, 否则运营方会以为店还在正常营业。
            player.sendSystemMessage(Component.translatable(errorKey(parsed.error())));
            return;
        }
        report(player, ShopTransaction.buy(player, parsed.spec()), parsed.spec(), true);
    }

    // ============================================================
    // 左键 = 卖出
    // ============================================================

    @SubscribeEvent
    public void onLeftClick(PlayerInteractEvent.LeftClickBlock event) {
        // 左键会连续触发 START/CLIENT_HOLD/STOP/ABORT, 只认按下那一次, 否则长按会连刷交易。
        if (event.getAction() != PlayerInteractEvent.LeftClickBlock.Action.START) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player) || event.getLevel().isClientSide()) {
            return;
        }
        if (player.isShiftKeyDown()) {
            return; // 潜行 = 原版破坏, 留给 OP 拆店。
        }
        SignBlockEntity sign = signAt(event.getLevel(), event.getPos());
        if (sign == null || !isAdminShopSign(sign)) {
            return;
        }
        ServerLevel level = player.serverLevel();
        if (!AdminShopRegistry.get(level).isActivated(event.getPos())) {
            return; // 未激活的牌左键就是正常破坏。
        }

        // 拦住破坏: 已激活的店不能被随手左键拆掉 (创造模式左键是瞬间破坏)。
        event.setCanceled(true);

        ShopSignSpec.Result parsed = ShopSignSpec.parse(readLines(sign));
        if (!parsed.ok()) {
            player.sendSystemMessage(Component.translatable(errorKey(parsed.error())));
            return;
        }
        report(player, ShopTransaction.sell(player, parsed.spec()), parsed.spec(), false);
    }

    // ============================================================
    // 破坏 = 注销登记
    // ============================================================

    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.isCanceled() || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        BlockPos pos = event.getPos();
        AdminShopRegistry registry = AdminShopRegistry.get(level);
        if (!registry.isActivated(pos)) {
            return;
        }
        // 不清登记的话, 同坐标以后立起的任何一块牌都会自动继承"已激活"身份 = 免 OP 开店的提权漏洞。
        registry.deactivate(pos);
        LOGGER.info("[wokchestshop] admin shop deactivated by break: dim={} pos={} by={}",
                level.dimension().location(), pos, event.getPlayer().getGameProfile().getName());
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

        registry.activate(pos);

        // 上蜡: 激活后文本必须冻结, 否则任何玩家右键就能把买价改成 1 (未上蜡的告示牌所有人可编辑)。
        // 登记与上蜡缺一不可 —— 只登记不上蜡则内容可篡改, 只上蜡不登记则任何人上蜡的牌都成店。
        if (!sign.isWaxed()) {
            sign.setWaxed(true);
            sign.setChanged();
            BlockState state = level.getBlockState(pos);
            level.sendBlockUpdated(pos, state, state, BLOCK_UPDATE_CLIENTS);
        }

        ShopSignSpec spec = parsed.spec();
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

    private static boolean isOp(ServerPlayer player) {
        // 与主 mod MarketAdminActions.requireOp 同款: PlayerList.isOp(GameProfile) 是确定的公开 API,
        // hasPermissions(int) 在 ServerPlayer 上的语义跨版本不一。
        return player.getServer() != null
                && player.getServer().getPlayerList().isOp(player.getGameProfile());
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
