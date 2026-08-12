package com.wokchestshop;

import com.miningdim.economy.Currency;
import com.miningdim.economy.EconomyConstants;
import com.miningdim.economy.EconomyServices;
import com.miningdim.economy.IEconomyService;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * 告示牌商店 GameTest。
 *
 * 分两层, 都断言具体业务结果 (删掉被测逻辑必挂, 无 is-not-null 弱校验):
 *  - 纯逻辑层 (价格行 / 四行规格 / 登记表): 内存对象直接构造, 穷举边界与非法输入。
 *  - 真交易层: 用主 mod 的 MockGameTestPlayers 取真 ServerPlayer, 经真实 IEconomyService 真扣钱真发钱,
 *    并钉死"卖出必须并入衰减主闸"这条经济红线。
 *
 * template 必须用本 mod 自己的 wokchestshop:empty, 不能借主 mod 的 miningdim:empty:
 * Forge 是按【structure 名的 namespace】而非 @GameTestHolder 的 modid 来过滤要跑哪些测试的
 * (forge.enabledGameTestNamespaces=wokchestshop)。借用 miningdim: 前缀的结构会让本 mod 的用例
 * 全部被静默过滤掉 —— 表现为 "0 tests are now running" 且不报任何错, 是个极难察觉的假绿。
 */
@GameTestHolder(WokChestShop.MODID)
@PrefixGameTestTemplate(false)
public final class ShopGameTests {

    private static final String TEMPLATE_NS = WokChestShop.MODID;
    private static final String EMPTY = "empty";
    private static final String BATCH = "wokchestshop";

    // ============================================================
    // 价格行解析
    // ============================================================

    @GameTest(templateNamespace = TEMPLATE_NS, template = EMPTY, batch = BATCH)
    public static void priceLineBothDirections(GameTestHelper helper) {
        ShopPriceLine both = ShopPriceLine.parse("B 250:200 S");
        helper.assertTrue(both != null, "canonical 'B 250:200 S' must parse");
        helper.assertTrue(both.buyPrice() == 250L, "buy price is 250");
        helper.assertTrue(both.sellPrice() == 200L, "sell price is 200");

        // 空格与大小写变体必须等价 (告示牌是人手打的, 不能因为多一个空格就开不了店)。
        ShopPriceLine spaced = ShopPriceLine.parse("  b250 : 200s  ");
        helper.assertTrue(spaced != null && spaced.buyPrice() == 250L && spaced.sellPrice() == 200L,
                "spacing and case variants must parse identically");

        // 指示符左右顺序颠倒 (S 在前) 同样成立: 方向由指示符决定, 不由位置决定。
        ShopPriceLine reversed = ShopPriceLine.parse("S 200:250 B");
        helper.assertTrue(reversed != null && reversed.buyPrice() == 250L && reversed.sellPrice() == 200L,
                "marker determines direction, not side of the colon");

        helper.succeed();
    }

    @GameTest(templateNamespace = TEMPLATE_NS, template = EMPTY, batch = BATCH)
    public static void priceLineSingleDirectionAndFree(GameTestHelper helper) {
        ShopPriceLine buyOnly = ShopPriceLine.parse("B 250");
        helper.assertTrue(buyOnly != null && buyOnly.allowsBuy() && !buyOnly.allowsSell(),
                "'B 250' is buy-only: shop sells to player, never buys back");
        helper.assertTrue(buyOnly.buyPrice() == 250L, "buy-only price is 250");

        ShopPriceLine sellOnly = ShopPriceLine.parse("S 200");
        helper.assertTrue(sellOnly != null && sellOnly.allowsSell() && !sellOnly.allowsBuy(),
                "'S 200' is sell-only: shop buys from player, never sells");
        helper.assertTrue(sellOnly.sellPrice() == 200L, "sell-only price is 200");

        ShopPriceLine free = ShopPriceLine.parse("B free");
        helper.assertTrue(free != null && free.allowsBuy() && free.buyPrice() == 0L,
                "'B free' means buy price 0");

        helper.succeed();
    }

    @GameTest(templateNamespace = TEMPLATE_NS, template = EMPTY, batch = BATCH)
    public static void priceLineRejectsMalformed(GameTestHelper helper) {
        // 每一条都必须拒绝而非"猜测性修复"—— 价格是钱, 猜错就是经济事故。
        helper.assertTrue(ShopPriceLine.parse("250") == null, "no marker: direction unknown, reject");
        helper.assertTrue(ShopPriceLine.parse("") == null, "empty line rejected");
        helper.assertTrue(ShopPriceLine.parse(null) == null, "null line rejected");
        helper.assertTrue(ShopPriceLine.parse("BS 250") == null, "two markers on one side rejected");
        helper.assertTrue(ShopPriceLine.parse("B 250:200 B") == null, "same marker on both sides rejected");
        helper.assertTrue(ShopPriceLine.parse("B -250") == null, "negative price rejected");
        helper.assertTrue(ShopPriceLine.parse("B 2O0") == null, "letter O inside digits rejected (would read as 20)");
        helper.assertTrue(ShopPriceLine.parse("B 250 free") == null, "digits and FREE together are contradictory");
        helper.assertTrue(ShopPriceLine.parse("B " + (ShopConstants.MAX_PRICE + 1)) == null,
                "price above MAX_PRICE rejected (guards against a slipped extra zero)");

        // 恰好等于上限必须接受 (边界不能连自己都拒)。
        ShopPriceLine atCap = ShopPriceLine.parse("B " + ShopConstants.MAX_PRICE);
        helper.assertTrue(atCap != null && atCap.buyPrice() == ShopConstants.MAX_PRICE,
                "price exactly at MAX_PRICE is accepted");

        helper.succeed();
    }

    // ============================================================
    // 四行规格解析
    // ============================================================

    @GameTest(templateNamespace = TEMPLATE_NS, template = EMPTY, batch = BATCH)
    public static void signSpecFullParse(GameTestHelper helper) {
        ShopSignSpec.Result result = ShopSignSpec.parse(
                new String[]{"Admin Shop", "64", "B 250:200 S", "diamond"});
        helper.assertTrue(result.ok(), "a well-formed admin shop sign must parse");

        ShopSignSpec spec = result.spec();
        helper.assertTrue(spec.quantity() == 64, "quantity is 64");
        helper.assertTrue(spec.item() == Items.DIAMOND, "item resolves to minecraft:diamond");
        helper.assertTrue(spec.price().buyPrice() == 250L && spec.price().sellPrice() == 200L,
                "prices carried through");

        // 带命名空间的写法等价。
        helper.assertTrue(ShopSignSpec.parse(
                        new String[]{"admin shop", "1", "S 5", "minecraft:cobblestone"}).spec().item() == Items.COBBLESTONE,
                "explicit minecraft: namespace resolves identically");

        helper.succeed();
    }

    @GameTest(templateNamespace = TEMPLATE_NS, template = EMPTY, batch = BATCH)
    public static void signSpecRejectsEachBadField(GameTestHelper helper) {
        helper.assertTrue(ShopSignSpec.parse(new String[]{"Bob", "64", "B 250", "diamond"}).error()
                        == ShopSignSpec.Error.NOT_ADMIN_SHOP,
                "a player-named sign is not an admin shop (this mod does not do P2P shops)");

        helper.assertTrue(ShopSignSpec.parse(new String[]{"Admin Shop", "0", "B 250", "diamond"}).error()
                == ShopSignSpec.Error.BAD_QUANTITY, "quantity 0 rejected");
        helper.assertTrue(ShopSignSpec.parse(new String[]{"Admin Shop", "x64", "B 250", "diamond"}).error()
                == ShopSignSpec.Error.BAD_QUANTITY, "non-numeric quantity rejected, not truncated");
        helper.assertTrue(ShopSignSpec.parse(
                        new String[]{"Admin Shop", String.valueOf(ShopConstants.MAX_QUANTITY + 1), "B 250", "diamond"})
                        .error() == ShopSignSpec.Error.BAD_QUANTITY, "quantity above cap rejected");

        // 未知物品 id 必须报错, 绝不能因为注册表对未知 id 返回 AIR 而变成"卖空气".
        helper.assertTrue(ShopSignSpec.parse(new String[]{"Admin Shop", "1", "B 250", "notarealitem"}).error()
                == ShopSignSpec.Error.BAD_ITEM, "unknown item id rejected (must not silently become AIR)");

        helper.assertTrue(ShopSignSpec.parse(new String[]{"Admin Shop", "1", "nonsense", "diamond"}).error()
                == ShopSignSpec.Error.BAD_PRICE, "malformed price rejected");

        // minecraft:air 是注册表正式条目, containsKey 挡不住它。放行的话买入会扣钱且零交付:
        // AIR 的 ItemStack 恒 isEmpty, Inventory.add 直接 return false 而掉落兜底也不触发。
        helper.assertTrue(ShopSignSpec.parse(new String[]{"Admin Shop", "1", "B 250", "air"}).error()
                == ShopSignSpec.Error.BAD_ITEM, "literal 'air' rejected (would charge money and deliver nothing)");
        helper.assertTrue(ShopSignSpec.parse(new String[]{"Admin Shop", "1", "B 250", "minecraft:air"}).error()
                == ShopSignSpec.Error.BAD_ITEM, "explicit minecraft:air rejected too");

        helper.succeed();
    }

    /**
     * 卖价高于买价 = 低买高卖套利循环, 而管理员店的库存与资金都是无限的。
     * 最恶劣的变体 "B free:1000 S" 是彻底的零成本印钞。B/S 两数写反比多打一个 0 更常见。
     */
    @GameTest(templateNamespace = TEMPLATE_NS, template = EMPTY, batch = BATCH)
    public static void signSpecRejectsInvertedSpread(GameTestHelper helper) {
        helper.assertTrue(ShopSignSpec.parse(new String[]{"Admin Shop", "1", "B 200:250 S", "diamond"}).error()
                        == ShopSignSpec.Error.INVERTED_SPREAD,
                "sell 250 > buy 200 is an arbitrage loop, must be rejected");
        helper.assertTrue(ShopSignSpec.parse(new String[]{"Admin Shop", "1", "S 250:200 B", "diamond"}).error()
                        == ShopSignSpec.Error.INVERTED_SPREAD,
                "same inversion written in the other order is also rejected");
        helper.assertTrue(ShopSignSpec.parse(new String[]{"Admin Shop", "1", "B free:1000 S", "dirt"}).error()
                        == ShopSignSpec.Error.INVERTED_SPREAD,
                "'B free:1000 S' is zero-cost money printing, must be rejected");

        // 相等是允许的 (零价差的纯周转店), 正价差当然允许。
        helper.assertTrue(ShopSignSpec.parse(new String[]{"Admin Shop", "1", "B 200:200 S", "diamond"}).ok(),
                "equal buy and sell is allowed (zero-spread shop)");
        helper.assertTrue(ShopSignSpec.parse(new String[]{"Admin Shop", "1", "B 250:200 S", "diamond"}).ok(),
                "normal positive spread is allowed");
        // 单向店没有价差可言, 不能被这条闸门误伤。
        helper.assertTrue(ShopSignSpec.parse(new String[]{"Admin Shop", "1", "S 1000", "diamond"}).ok(),
                "a sell-only shop has no spread to invert and must not be blocked");

        helper.succeed();
    }

    @GameTest(templateNamespace = TEMPLATE_NS, template = EMPTY, batch = BATCH)
    public static void adminShopHeaderRecognition(GameTestHelper helper) {
        helper.assertTrue(ShopSignSpec.isAdminShopHeader("Admin Shop"), "canonical English keyword");
        helper.assertTrue(ShopSignSpec.isAdminShopHeader("  ADMIN SHOP  "), "case and padding insensitive");
        helper.assertTrue(ShopSignSpec.isAdminShopHeader("adminshop"), "no-space variant accepted");
        helper.assertTrue(ShopSignSpec.isAdminShopHeader("管理员商店"), "Chinese keyword accepted");

        // 关键: 普通告示牌绝不能被认成商店, 否则玩家的每一块牌都会被本 mod 拦截交互。
        helper.assertTrue(!ShopSignSpec.isAdminShopHeader("Shop"), "partial word is not a shop header");
        helper.assertTrue(!ShopSignSpec.isAdminShopHeader("Admin Shop!"), "trailing punctuation is not a match");
        helper.assertTrue(!ShopSignSpec.isAdminShopHeader(""), "empty line is not a shop header");
        helper.assertTrue(!ShopSignSpec.isAdminShopHeader(null), "null line is not a shop header");

        helper.succeed();
    }

    /**
     * 中文输入法产出的全角空格 U+3000 必须被当成空白处理。
     *
     * 这条不是洁癖: trim() 与正则 \\s 都不认 U+3000, 而 Character.isWhitespace 认。早先版本两套口径
     * 混用, 结果中文玩家打出的 "B　250" 被静默判死; 抬头行带一个全角空格更糟 —— 匹配失败后 handler
     * 完全不介入, OP 只会看到原版编辑界面, 零提示零日志。
     */
    @GameTest(templateNamespace = TEMPLATE_NS, template = EMPTY, batch = BATCH)
    public static void parsingHandlesFullWidthWhitespace(GameTestHelper helper) {
        ShopPriceLine price = ShopPriceLine.parse("B　250:200　S");
        helper.assertTrue(price != null && price.buyPrice() == 250L && price.sellPrice() == 200L,
                "full-width spaces inside the price line must parse like normal spaces");

        helper.assertTrue(ShopSignSpec.isAdminShopHeader("管理员商店　"),
                "a trailing full-width space must not break the Chinese header");
        helper.assertTrue(ShopSignSpec.isAdminShopHeader("　Admin　Shop　"),
                "full-width spaces around and inside the English header are normalized");

        // 全角数字必须被干净拒绝, 而不是收进 digits 后让 parseLong 抛异常冲出解析层。
        helper.assertTrue(ShopPriceLine.parse("B ２５０") == null,
                "full-width digits are rejected, not crashed on");
        helper.assertTrue(ShopSignSpec.parse(new String[]{"Admin Shop", "６４", "B 250", "diamond"}).error()
                == ShopSignSpec.Error.BAD_QUANTITY, "full-width quantity digits rejected cleanly");

        // 整块牌走一遍中文输入法的典型产物。
        helper.assertTrue(ShopSignSpec.parse(
                        new String[]{"管理员商店　", "64", "B　250:200　S", "diamond"}).ok(),
                "a sign typed with a Chinese IME still activates");

        helper.succeed();
    }

    // ============================================================
    // 登记表
    // ============================================================

    @GameTest(templateNamespace = TEMPLATE_NS, template = EMPTY, batch = BATCH)
    public static void registryActivationRoundTrip(GameTestHelper helper) {
        AdminShopRegistry registry = new AdminShopRegistry();
        BlockPos a = new BlockPos(10, 64, -20);
        BlockPos b = new BlockPos(11, 64, -20);
        ShopSignSpec specA = specOf("Admin Shop", "64", "B 250:200 S", "diamond");
        ShopSignSpec specB = specOf("Admin Shop", "1", "S 5", "cobblestone");

        helper.assertTrue(registry.recordAt(a) == null,
                "a fresh registry activates nothing (no sign is a shop by default)");
        helper.assertTrue(registry.activate(a, specA), "first activation returns true");
        helper.assertTrue(!registry.activate(a, specA), "re-activating with the same spec is idempotent");
        helper.assertTrue(registry.isActivated(a), "activated pos reads back as activated");
        helper.assertTrue(registry.recordAt(b) == null, "an unrelated pos stays inactive");

        registry.activate(b, specB);
        helper.assertTrue(registry.size() == 2, "two distinct shops registered");

        // NBT round-trip: 重启后店必须还在, 且快照的每一项都要原样带回来 —— 快照丢失等于防线消失。
        AdminShopRegistry reloaded = AdminShopRegistry.load(registry.save(new CompoundTag()));
        helper.assertTrue(reloaded.size() == 2, "no entries lost or duplicated across persistence");
        AdminShopRegistry.ShopRecord back = reloaded.recordAt(a);
        helper.assertTrue(back != null, "activation survives save/load");
        helper.assertTrue(back.quantity() == 64, "quantity survives persistence");
        helper.assertTrue("minecraft:diamond".equals(back.itemId()), "item id survives persistence");
        helper.assertTrue(back.buyPrice() == 250L && back.sellPrice() == 200L, "both prices survive persistence");
        helper.assertTrue(back.matches(specA), "the reloaded snapshot still matches the original spec");

        // 单向店的"该方向不营业"哨兵也必须完整往返, 否则重启后 sell-only 店会被误认成 buy 价 0 的免费店。
        AdminShopRegistry.ShopRecord backB = reloaded.recordAt(b);
        helper.assertTrue(backB.buyPrice() == AdminShopRegistry.ABSENT_PRICE,
                "a sell-only shop keeps ABSENT_PRICE for buy across persistence");
        helper.assertTrue(backB.matches(specB), "sell-only snapshot round-trips");

        helper.assertTrue(reloaded.deactivate(a), "deactivate removes the entry");
        helper.assertTrue(!reloaded.isActivated(a), "deactivated pos no longer counts as a shop");
        helper.assertTrue(!reloaded.deactivate(a), "deactivating twice is a no-op");

        helper.succeed();
    }

    /**
     * 防"幽灵登记"的主防线。
     *
     * 背景: 唯一的注销入口挂在 BlockEvent.BreakEvent 上, 而该事件只在玩家亲手挖掘时触发 —— 拆支撑方块
     * 致牌脱落、TNT、活塞、水火、/setblock、其它 mod 的 Level.destroyBlock 统统不触发。登记项因此会
     * 永久残留。若交易准入只查坐标, 任何玩家在残留坐标立一块自己写的牌就白捡一间管理员商店
     * (写 "B free" 无限白拿, 写 "S 1000000000" 每次左键印上千万信用点)。
     *
     * 修法是登记内容快照, 交易前比对。本用例钉死这条比对: 删掉 ShopRecord.matches 的任何一项都会挂。
     */
    @GameTest(templateNamespace = TEMPLATE_NS, template = EMPTY, batch = BATCH)
    public static void registryDetectsSwappedSign(GameTestHelper helper) {
        AdminShopRegistry registry = new AdminShopRegistry();
        BlockPos pos = new BlockPos(3, 64, 7);
        ShopSignSpec authorized = specOf("Admin Shop", "16", "B 250:200 S", "diamond");
        registry.activate(pos, authorized);

        AdminShopRegistry.ShopRecord record = registry.recordAt(pos);
        helper.assertTrue(record.matches(authorized), "the exact authorized sign still matches");

        // 攻击者在残留坐标立的牌: 免费白拿。
        helper.assertTrue(!record.matches(specOf("Admin Shop", "16", "B free", "diamond")),
                "a free-loot sign at the same pos must NOT match the authorized snapshot");
        // 攻击者的印钞牌: 天价收购。
        helper.assertTrue(!record.matches(specOf("Admin Shop", "1", "S 1000000000", "dirt")),
                "a max-price buyback sign must NOT match");
        // 只改一项也必须被抓到 —— 逐项比对, 不是"差不多就行"。
        helper.assertTrue(!record.matches(specOf("Admin Shop", "32", "B 250:200 S", "diamond")),
                "quantity change alone breaks the match");
        helper.assertTrue(!record.matches(specOf("Admin Shop", "16", "B 250:200 S", "emerald")),
                "item change alone breaks the match");
        helper.assertTrue(!record.matches(specOf("Admin Shop", "16", "B 300:200 S", "diamond")),
                "buy price change alone breaks the match");
        helper.assertTrue(!record.matches(specOf("Admin Shop", "16", "B 250:100 S", "diamond")),
                "sell price change alone breaks the match");

        // OP 改价后重新激活: 快照必须跟着刷新, 否则改完价的店永远交易不了。
        ShopSignSpec repriced = specOf("Admin Shop", "16", "B 300:200 S", "diamond");
        registry.activate(pos, repriced);
        helper.assertTrue(registry.recordAt(pos).matches(repriced), "re-activation refreshes the snapshot");
        helper.assertTrue(!registry.recordAt(pos).matches(authorized), "the old spec no longer matches");

        helper.succeed();
    }

    // ============================================================
    // 真交易: 真扣钱 / 真发钱 / 真动背包
    // ============================================================

    @GameTest(templateNamespace = TEMPLATE_NS, template = EMPTY, batch = BATCH)
    public static void buyChargesCreditAndDeliversItems(GameTestHelper helper) {
        IEconomyService eco = requireEconomy(helper);
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);

        eco.grant(player, Currency.CREDIT, 1000L);
        long before = eco.creditBalance(player);
        helper.assertTrue(before == 1000L, "test player starts with exactly 1000 credit");

        ShopSignSpec spec = specOf("Admin Shop", "16", "B 250", "diamond");
        ShopTransaction.Outcome outcome = ShopTransaction.buy(player, spec);

        helper.assertTrue(outcome.ok(), "buy with sufficient funds succeeds");
        helper.assertTrue(outcome.settled() == 250L, "exactly the listed buy price is charged");
        helper.assertTrue(eco.creditBalance(player) == 750L,
                "balance drops by exactly 250 (this is the sink the audit says the economy lacks)");
        helper.assertTrue(countInInventory(player, Items.DIAMOND) == 16,
                "player receives exactly the sign quantity, 16 diamonds");

        helper.succeed();
    }

    @GameTest(templateNamespace = TEMPLATE_NS, template = EMPTY, batch = BATCH)
    public static void buyRejectedWhenFundsInsufficient(GameTestHelper helper) {
        IEconomyService eco = requireEconomy(helper);
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);

        eco.grant(player, Currency.CREDIT, 249L); // 差 1 点
        ShopSignSpec spec = specOf("Admin Shop", "16", "B 250", "diamond");
        ShopTransaction.Outcome outcome = ShopTransaction.buy(player, spec);

        helper.assertTrue(outcome.reason() == ShopTransaction.Reason.INSUFFICIENT_FUNDS,
                "one credit short must fail, not round down");
        helper.assertTrue(eco.creditBalance(player) == 249L, "a failed buy must not touch the balance");
        helper.assertTrue(countInInventory(player, Items.DIAMOND) == 0,
                "a failed buy must not deliver goods (no pay-later)");

        helper.succeed();
    }

    @GameTest(templateNamespace = TEMPLATE_NS, template = EMPTY, batch = BATCH)
    public static void buyRejectsWrongDirection(GameTestHelper helper) {
        requireEconomy(helper);
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);

        // 只挂 S 的店不出售; 若方向校验被删, 玩家将能以 sellPrice 白拿货。
        ShopSignSpec sellOnly = specOf("Admin Shop", "16", "S 200", "diamond");
        helper.assertTrue(ShopTransaction.buy(player, sellOnly).reason()
                == ShopTransaction.Reason.DIRECTION_UNSUPPORTED, "cannot buy from a sell-only shop");
        helper.assertTrue(countInInventory(player, Items.DIAMOND) == 0, "no goods delivered");

        ShopSignSpec buyOnly = specOf("Admin Shop", "16", "B 250", "diamond");
        player.getInventory().add(new ItemStack(Items.DIAMOND, 16));
        helper.assertTrue(ShopTransaction.sell(player, buyOnly).reason()
                == ShopTransaction.Reason.DIRECTION_UNSUPPORTED, "cannot sell to a buy-only shop");
        helper.assertTrue(countInInventory(player, Items.DIAMOND) == 16, "items stay in inventory");

        helper.succeed();
    }

    /**
     * 本 mod 最重要的一条经济断言: 卖给系统商店的收益必须经主 mod 的每日 faucet 衰减主闸,
     * 而不是直接 grant 全额。
     *
     * 手法: 先把该玩家当日 faucet 原始累计推到 120000 (= 2 个完整档), 再卖一件标价 1000 的货。
     * 主闸第 k 档系数 max(1%, 0.6^k), 累计落在第 3 档 (k=2) 故系数 0.36, 实发必须是 360。
     * 若实现被改成 grant 全额, 实发会是 1000, 本断言立刻挂 —— 这正是"系统商店成为不受约束的
     * 第二个印钞口"那条 Critical 缺口的回归网。
     */
    @GameTest(templateNamespace = TEMPLATE_NS, template = EMPTY, batch = BATCH)
    public static void sellGoesThroughDailyFaucetGate(GameTestHelper helper) {
        IEconomyService eco = requireEconomy(helper);
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);

        // 推进当日 faucet 累计到 2 个完整档 (主闸累计的是 raw 而非实发, 故这里传 raw 120000)。
        eco.grantDaily(player, 2 * EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_TIER,
                EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_KEY,
                EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_TIER);

        long balanceBeforeSale = eco.creditBalance(player);
        player.getInventory().add(new ItemStack(Items.DIAMOND, 16));

        ShopSignSpec spec = specOf("Admin Shop", "16", "S 1000", "diamond");
        ShopTransaction.Outcome outcome = ShopTransaction.sell(player, spec);

        helper.assertTrue(outcome.ok(), "sell with sufficient goods succeeds");
        helper.assertTrue(outcome.listed() == 1000L, "sign price is reported as listed");
        helper.assertTrue(outcome.settled() == 360L,
                "deep-tier payout must be 1000 * 0.6^2 = 360, proving the sale went through grantDaily "
                        + "and not a raw grant (actual: " + outcome.settled() + ")");
        helper.assertTrue(eco.creditBalance(player) - balanceBeforeSale == 360L,
                "the ledger received exactly the decayed amount");
        helper.assertTrue(countInInventory(player, Items.DIAMOND) == 0,
                "goods are consumed by the sale (unlike settleOreSale, which the audit found never takes items)");

        helper.succeed();
    }

    @GameTest(templateNamespace = TEMPLATE_NS, template = EMPTY, batch = BATCH)
    public static void sellRejectsInsufficientItemsWithoutPaying(GameTestHelper helper) {
        IEconomyService eco = requireEconomy(helper);
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);

        player.getInventory().add(new ItemStack(Items.DIAMOND, 15)); // 差一个
        long before = eco.creditBalance(player);

        ShopSignSpec spec = specOf("Admin Shop", "16", "S 200", "diamond");
        ShopTransaction.Outcome outcome = ShopTransaction.sell(player, spec);

        helper.assertTrue(outcome.reason() == ShopTransaction.Reason.INSUFFICIENT_ITEMS,
                "15 of 16 must fail rather than partially settle");
        helper.assertTrue(eco.creditBalance(player) == before, "a failed sale pays nothing");
        helper.assertTrue(countInInventory(player, Items.DIAMOND) == 15,
                "a failed sale consumes nothing (all-or-nothing)");

        helper.succeed();
    }

    /**
     * 经济安全红线: 带附魔/损伤/自定义 NBT 的物品不可按素材价卖给系统。
     * 若这条被删, 玩家可以把高价值附魔物按普通物品标价卖出, 差价直接铸成信用点。
     */
    @GameTest(templateNamespace = TEMPLATE_NS, template = EMPTY, batch = BATCH)
    public static void sellRejectsNbtTaggedItems(GameTestHelper helper) {
        IEconomyService eco = requireEconomy(helper);
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);

        ItemStack tagged = new ItemStack(Items.DIAMOND, 16);
        tagged.getOrCreateTag().putString("wokchestshop_test_marker", "enchanted-ish");
        player.getInventory().add(tagged);

        helper.assertTrue(countInInventory(player, Items.DIAMOND) == 16, "the tagged stack IS in the inventory");
        helper.assertTrue(ShopTransaction.countSellable(player, Items.DIAMOND) == 0,
                "but NBT-tagged stacks count as zero sellable (value-laundering guard)");

        long before = eco.creditBalance(player);
        ShopSignSpec spec = specOf("Admin Shop", "16", "S 200", "diamond");
        helper.assertTrue(ShopTransaction.sell(player, spec).reason()
                == ShopTransaction.Reason.INSUFFICIENT_ITEMS, "selling NBT-tagged items is refused");
        helper.assertTrue(eco.creditBalance(player) == before, "nothing paid for refused NBT items");
        helper.assertTrue(countInInventory(player, Items.DIAMOND) == 16, "the tagged stack is untouched");

        helper.succeed();
    }

    /**
     * NBT 过滤的边界: 必须放行"只有 Damage:0"的全新可损伤物品, 否则管理员永远开不出收购工具的店
     * (一切工具/武器/盔甲天生自带 {Damage:0}); 但附魔、命名、铁砧代价、损伤过的一律仍要挡住。
     */
    @GameTest(templateNamespace = TEMPLATE_NS, template = EMPTY, batch = BATCH)
    public static void sellAcceptsPristineToolsButRejectsModifiedOnes(GameTestHelper helper) {
        requireEconomy(helper);
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);

        // 全新铁镐: 自带 {Damage:0}, 必须可卖。
        player.getInventory().add(new ItemStack(Items.IRON_PICKAXE));
        helper.assertTrue(ShopTransaction.countSellable(player, Items.IRON_PICKAXE) == 1,
                "a pristine tool carries {Damage:0} and must still be sellable");

        // 用过的铁镐: isDamaged 挡掉。
        ItemStack used = new ItemStack(Items.IRON_PICKAXE);
        used.setDamageValue(5);
        player.getInventory().add(used);
        helper.assertTrue(ShopTransaction.countSellable(player, Items.IRON_PICKAXE) == 1,
                "a damaged tool is not sellable, so the count stays at the one pristine copy");

        // 附魔的铁镐: Damage 之外还有 Enchantments 键, size() > 1 被挡。
        ItemStack enchanted = new ItemStack(Items.IRON_PICKAXE);
        enchanted.getOrCreateTag().put(ItemStack.TAG_ENCH, new net.minecraft.nbt.ListTag());
        player.getInventory().add(enchanted);
        helper.assertTrue(ShopTransaction.countSellable(player, Items.IRON_PICKAXE) == 1,
                "an enchanted tool is not sellable (value laundering guard), count still 1");

        // 命名过的铁镐: display 键同理被挡。
        ItemStack named = new ItemStack(Items.IRON_PICKAXE);
        named.getOrCreateTag().put(ItemStack.TAG_DISPLAY, new net.minecraft.nbt.CompoundTag());
        player.getInventory().add(named);
        helper.assertTrue(ShopTransaction.countSellable(player, Items.IRON_PICKAXE) == 1,
                "a renamed tool is not sellable, count still 1");

        helper.succeed();
    }

    /**
     * 卖出只能动主背包 36 格, 不能碰玩家身上穿的盔甲与副手。
     *
     * Inventory.getContainerSize() 是 41 (36+4+1) 且 getItem(i) 会穿透到装备槽, 用它当上界的话,
     * 玩家戴着崭新钻石头盔去左键 diamond_helmet 收购店, 会把正戴着的头盔直接卖掉 (副手的盾牌/图腾/
     * 鞘翅同理), 扣的不是玩家以为的那份货。
     */
    @GameTest(templateNamespace = TEMPLATE_NS, template = EMPTY, batch = BATCH)
    public static void sellNeverTakesWornEquipment(GameTestHelper helper) {
        IEconomyService eco = requireEconomy(helper);
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);

        // 头上戴一顶崭新未附魔的钻石头盔, 副手拿一个崭新盾牌, 主背包里什么都没有。
        player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
        player.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));

        helper.assertTrue(ShopTransaction.countSellable(player, Items.DIAMOND_HELMET) == 0,
                "worn armor is not sellable stock");
        helper.assertTrue(ShopTransaction.countSellable(player, Items.SHIELD) == 0,
                "offhand item is not sellable stock");

        long before = eco.creditBalance(player);
        ShopSignSpec helmetShop = specOf("Admin Shop", "1", "S 500", "diamond_helmet");
        helper.assertTrue(ShopTransaction.sell(player, helmetShop).reason()
                == ShopTransaction.Reason.INSUFFICIENT_ITEMS, "selling with only worn armor must fail");
        helper.assertTrue(eco.creditBalance(player) == before, "nothing paid");
        helper.assertTrue(!player.getInventory().armor.get(3).isEmpty(),
                "the worn helmet is still on the player's head");
        helper.assertTrue(!player.getInventory().offhand.get(0).isEmpty(),
                "the offhand shield is untouched");

        // 背包里另有一顶时, 卖掉的必须是背包那顶, 头上那顶不动。
        player.getInventory().add(new ItemStack(Items.DIAMOND_HELMET));
        int stock = ShopTransaction.countSellable(player, Items.DIAMOND_HELMET);
        helper.assertTrue(stock == 1,
                "only the inventory copy counts as stock (actual=" + stock + ")");
        helper.assertTrue(ShopTransaction.sell(player, helmetShop).ok(), "selling the inventory copy succeeds");
        helper.assertTrue(!player.getInventory().armor.get(3).isEmpty(),
                "the worn helmet survives a successful sale of the spare one");

        helper.succeed();
    }

    @GameTest(templateNamespace = TEMPLATE_NS, template = EMPTY, batch = BATCH)
    public static void freeShopSkipsEconomyCalls(GameTestHelper helper) {
        IEconomyService eco = requireEconomy(helper);
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);

        // 主 mod 的 tryCharge/grantDaily 契约要求 amount > 0, 传 0 会抛 ILLEGAL_AMOUNT。
        // 免费店必须绕过经济调用而不是传 0 进去 —— 若绕过逻辑被删, 这里会抛异常而非返回 ok。
        long before = eco.creditBalance(player);
        ShopSignSpec freeBuy = specOf("Admin Shop", "8", "B free", "cobblestone");
        ShopTransaction.Outcome outcome = ShopTransaction.buy(player, freeBuy);

        helper.assertTrue(outcome.ok(), "a free shop hands out goods without throwing");
        helper.assertTrue(outcome.settled() == 0L, "free means zero charged");
        helper.assertTrue(eco.creditBalance(player) == before, "balance untouched by a free purchase");
        helper.assertTrue(countInInventory(player, Items.COBBLESTONE) == 8, "goods still delivered");

        helper.succeed();
    }

    // ============================================================
    // 交互层端到端: 真放牌、真触发事件、真验证登记与上蜡
    // ============================================================

    /**
     * 激活闸门: 非 OP 右键不能激活, OP 右键激活并上蜡。
     * 这是整套权限模型唯一的入口, 之前只有纯逻辑用例覆盖登记表本身, handler 从未被实例化过。
     */
    @GameTest(templateNamespace = TEMPLATE_NS, template = EMPTY, batch = BATCH)
    public static void handlerActivationRequiresOpAndWaxes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        BlockPos pos = placeShopSign(helper, player, "Admin Shop", "16", "B 250:200 S", "diamond");

        ShopInteractionHandler handler = new ShopInteractionHandler();
        AdminShopRegistry registry = AdminShopRegistry.get(level);
        SignBlockEntity sign = (SignBlockEntity) level.getBlockEntity(pos);

        try {
            deop(level, player);
            handler.onRightClick(rightClick(player, pos));
            helper.assertTrue(!registry.isActivated(pos), "a non-op right click must NOT activate the shop");
            helper.assertTrue(!sign.isWaxed(), "and must NOT wax the sign");

            op(level, player);
            handler.onRightClick(rightClick(player, pos));
            helper.assertTrue(registry.isActivated(pos), "an op right click activates the shop");
            helper.assertTrue(sign.isWaxed(), "activation waxes the sign so its text is frozen");
            AdminShopRegistry.ShopRecord rec = registry.recordAt(pos);
            helper.assertTrue(rec.quantity() == 16 && rec.buyPrice() == 250L && rec.sellPrice() == 200L,
                    "the snapshot captured what was on the sign at activation time");
        } finally {
            cleanup(level, player, registry, pos);
        }
        helper.succeed();
    }

    /** 激活后被撬蜡改价: 下次交易必须检出内容不符, 拒绝成交并就地注销。 */
    @GameTest(templateNamespace = TEMPLATE_NS, template = EMPTY, batch = BATCH)
    public static void handlerRejectsTamperedSign(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        BlockPos pos = placeShopSign(helper, player, "Admin Shop", "16", "B 250:200 S", "diamond");

        ShopInteractionHandler handler = new ShopInteractionHandler();
        AdminShopRegistry registry = AdminShopRegistry.get(level);
        SignBlockEntity sign = (SignBlockEntity) level.getBlockEntity(pos);

        try {
            op(level, player);
            handler.onRightClick(rightClick(player, pos));
            helper.assertTrue(registry.isActivated(pos), "shop is live before tampering");

            // 撬蜡改价 (把 250 改成 free)。
            writeSign(sign, "Admin Shop", "16", "B free", "diamond");
            deop(level, player);
            handler.onRightClick(rightClick(player, pos));

            helper.assertTrue(countInInventory(player, Items.DIAMOND) == 0,
                    "a tampered sign must not hand out goods");
            helper.assertTrue(!registry.isActivated(pos),
                    "and the shop is deactivated on detection rather than left half-trusted");
        } finally {
            cleanup(level, player, registry, pos);
        }
        helper.succeed();
    }

    /**
     * 幽灵登记的完整回归: 告示牌经【不触发 BreakEvent 的途径】消失 (这里用 setBlock 模拟爆炸/活塞/
     * 支撑方块脱落), 登记项残留下来; 攻击者在同坐标立一块自己写的免费店牌, 必须拿不到任何东西。
     *
     * 这条用例在修复前是红的 —— 那时交易准入只查坐标, 攻击者的牌会被当成被授权的商店。
     */
    @GameTest(templateNamespace = TEMPLATE_NS, template = EMPTY, batch = BATCH)
    public static void handlerDeniesShopRebuiltOnGhostRegistration(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        BlockPos pos = placeShopSign(helper, player, "Admin Shop", "16", "B 250:200 S", "diamond");

        ShopInteractionHandler handler = new ShopInteractionHandler();
        AdminShopRegistry registry = AdminShopRegistry.get(level);

        try {
            op(level, player);
            handler.onRightClick(rightClick(player, pos));
            helper.assertTrue(registry.isActivated(pos), "shop is live");

            // 非玩家途径移除告示牌: 不经 BlockEvent.BreakEvent, 所以登记不会被注销。
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            helper.assertTrue(registry.isActivated(pos),
                    "the registration does survive a non-player removal - that is exactly the ghost we defend against");

            // 攻击者在残留坐标立一块自己写的免费店。
            level.setBlock(pos, Blocks.OAK_SIGN.defaultBlockState(), 3);
            SignBlockEntity attackerSign = (SignBlockEntity) level.getBlockEntity(pos);
            writeSign(attackerSign, "Admin Shop", "64", "B free", "diamond");

            deop(level, player);
            handler.onRightClick(rightClick(player, pos));

            helper.assertTrue(countInInventory(player, Items.DIAMOND) == 0,
                    "a ghost registration must NOT let a player claim a free shop");
            helper.assertTrue(!registry.isActivated(pos), "and the stale registration is swept");
        } finally {
            cleanup(level, player, registry, pos);
        }
        helper.succeed();
    }

    /** 已激活的商店牌对普通玩家不可破坏 (潜行左键与 BreakEvent 两条路都要拦住)。 */
    @GameTest(templateNamespace = TEMPLATE_NS, template = EMPTY, batch = BATCH)
    public static void handlerProtectsActiveShopFromNonOpBreak(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        BlockPos pos = placeShopSign(helper, player, "Admin Shop", "16", "B 250:200 S", "diamond");

        ShopInteractionHandler handler = new ShopInteractionHandler();
        AdminShopRegistry registry = AdminShopRegistry.get(level);

        try {
            op(level, player);
            handler.onRightClick(rightClick(player, pos));
            helper.assertTrue(registry.isActivated(pos), "shop is live");

            deop(level, player);
            player.setShiftKeyDown(true);
            PlayerInteractEvent.LeftClickBlock sneakLeft = leftClick(player, pos);
            handler.onLeftClick(sneakLeft);
            helper.assertTrue(sneakLeft.isCanceled(),
                    "a non-op sneak-left-click on an active shop is canceled (no one-second teardown)");
            helper.assertTrue(registry.isActivated(pos), "and the shop stays registered");

            BlockEvent.BreakEvent breakEvent = new BlockEvent.BreakEvent(
                    level, pos, level.getBlockState(pos), player);
            handler.onBlockBreak(breakEvent);
            helper.assertTrue(breakEvent.isCanceled(), "a non-op break of an active shop is canceled");
            helper.assertTrue(registry.isActivated(pos), "and the registration is not dropped");

            // OP 破坏才真正注销。
            op(level, player);
            BlockEvent.BreakEvent opBreak = new BlockEvent.BreakEvent(
                    level, pos, level.getBlockState(pos), player);
            handler.onBlockBreak(opBreak);
            helper.assertTrue(!opBreak.isCanceled(), "an op break goes through");
            helper.assertTrue(!registry.isActivated(pos), "and deactivates the shop");
        } finally {
            player.setShiftKeyDown(false);
            cleanup(level, player, registry, pos);
        }
        helper.succeed();
    }

    /** 成交防抖: 同一玩家对同一块牌连点, 冷却内的重复触发不得再次扣款。 */
    @GameTest(templateNamespace = TEMPLATE_NS, template = EMPTY, batch = BATCH)
    public static void handlerDebouncesRapidRepeatTrades(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        IEconomyService eco = requireEconomy(helper);
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        BlockPos pos = placeShopSign(helper, player, "Admin Shop", "1", "B 100", "diamond");

        ShopInteractionHandler handler = new ShopInteractionHandler();
        AdminShopRegistry registry = AdminShopRegistry.get(level);

        try {
            op(level, player);
            handler.onRightClick(rightClick(player, pos));
            helper.assertTrue(registry.isActivated(pos), "shop is live");

            eco.grant(player, Currency.CREDIT, 1000L);
            long start = eco.creditBalance(player);

            // 客户端按 tick 重发交互包时, handler 会在同一 gameTime 被连续调用多次。
            for (int i = 0; i < 5; i++) {
                handler.onRightClick(rightClick(player, pos));
            }

            long spent = start - eco.creditBalance(player);
            helper.assertTrue(spent == 100L,
                    "five rapid right clicks must charge exactly one purchase, not five (actual spent=" + spent + ")");
            helper.assertTrue(countInInventory(player, Items.DIAMOND) == 1,
                    "and deliver exactly one unit");
        } finally {
            cleanup(level, player, registry, pos);
        }
        helper.succeed();
    }

    // ============================================================
    // helper
    // ============================================================

    /** 在测试结构内立一块写好四行的告示牌, 并把玩家挪到它旁边 (交互距离校验要求 6 格内)。 */
    private static BlockPos placeShopSign(GameTestHelper helper, ServerPlayer player, String... lines) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(0, 1, 0));
        level.setBlock(pos, Blocks.OAK_SIGN.defaultBlockState(), 3);
        SignBlockEntity sign = (SignBlockEntity) level.getBlockEntity(pos);
        writeSign(sign, lines);
        player.moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 1.5D);
        return pos;
    }

    private static void writeSign(SignBlockEntity sign, String... lines) {
        SignText text = sign.getFrontText();
        for (int i = 0; i < lines.length; i++) {
            text = text.setMessage(i, Component.literal(lines[i]));
        }
        sign.setText(text, true);
    }

    private static PlayerInteractEvent.RightClickBlock rightClick(ServerPlayer player, BlockPos pos) {
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        return new PlayerInteractEvent.RightClickBlock(player, InteractionHand.MAIN_HAND, pos, hit);
    }

    private static PlayerInteractEvent.LeftClickBlock leftClick(ServerPlayer player, BlockPos pos) {
        return new PlayerInteractEvent.LeftClickBlock(player, pos, Direction.UP,
                PlayerInteractEvent.LeftClickBlock.Action.START);
    }

    private static void op(ServerLevel level, ServerPlayer player) {
        level.getServer().getPlayerList().op(player.getGameProfile());
    }

    private static void deop(ServerLevel level, ServerPlayer player) {
        level.getServer().getPlayerList().deop(player.getGameProfile());
    }

    /** op 列表与登记表都是服务端全局状态, 测试必须自己收拾干净, 否则会污染同批次的其它用例。 */
    private static void cleanup(ServerLevel level, ServerPlayer player, AdminShopRegistry registry, BlockPos pos) {
        deop(level, player);
        registry.deactivate(pos);
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
    }

    /** 经济门面必须由主 mod 在服务端启动期注入; 未注入说明跨 mod 接线断了, 直接判失败而非跳过。 */
    private static IEconomyService requireEconomy(GameTestHelper helper) {
        helper.assertTrue(EconomyServices.isRegistered(),
                "miningdim IEconomyService must be registered - cross-mod wiring is this mod's whole purpose");
        return EconomyServices.economyService();
    }

    private static ShopSignSpec specOf(String owner, String quantity, String price, String item) {
        ShopSignSpec.Result result = ShopSignSpec.parse(new String[]{owner, quantity, price, item});
        if (!result.ok()) {
            throw new IllegalStateException("test fixture sign failed to parse: " + result.error());
        }
        return result.spec();
    }

    private static int countInInventory(ServerPlayer player, net.minecraft.world.item.Item item) {
        int total = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }
}
