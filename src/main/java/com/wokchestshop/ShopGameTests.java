package com.wokchestshop;

import com.miningdim.economy.Currency;
import com.miningdim.economy.EconomyConstants;
import com.miningdim.economy.EconomyServices;
import com.miningdim.economy.IEconomyService;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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

    // ============================================================
    // 登记表
    // ============================================================

    @GameTest(templateNamespace = TEMPLATE_NS, template = EMPTY, batch = BATCH)
    public static void registryActivationRoundTrip(GameTestHelper helper) {
        AdminShopRegistry registry = new AdminShopRegistry();
        BlockPos a = new BlockPos(10, 64, -20);
        BlockPos b = new BlockPos(11, 64, -20);

        helper.assertTrue(!registry.isActivated(a), "a fresh registry activates nothing (no sign is a shop by default)");
        helper.assertTrue(registry.activate(a), "first activation returns true");
        helper.assertTrue(!registry.activate(a), "re-activating the same pos is idempotent");
        helper.assertTrue(registry.isActivated(a), "activated pos reads back as activated");
        helper.assertTrue(!registry.isActivated(b), "an unrelated pos stays inactive");

        registry.activate(b);
        helper.assertTrue(registry.size() == 2, "two distinct shops registered");

        // NBT round-trip: 重启后店必须还在, 否则每次重启全服的店都要 OP 重新点一遍。
        AdminShopRegistry reloaded = AdminShopRegistry.load(registry.save(new CompoundTag()));
        helper.assertTrue(reloaded.isActivated(a) && reloaded.isActivated(b),
                "activations survive save/load");
        helper.assertTrue(reloaded.size() == 2, "no entries lost or duplicated across persistence");

        // 破坏注销必须真的把位置摘掉, 否则同坐标新立的牌会继承"已激活"身份 = 免 OP 开店的提权漏洞。
        helper.assertTrue(reloaded.deactivate(a), "deactivate removes the entry");
        helper.assertTrue(!reloaded.isActivated(a), "deactivated pos no longer counts as a shop");
        helper.assertTrue(!reloaded.deactivate(a), "deactivating twice is a no-op");

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
    // helper
    // ============================================================

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
