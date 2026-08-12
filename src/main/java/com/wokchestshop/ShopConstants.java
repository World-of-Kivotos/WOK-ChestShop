package com.wokchestshop;

/**
 * 告示牌商店的全部硬约束常量 (单一来源)。
 *
 * 命名与语义对齐 ChestShop (Acrobot) 的四行告示牌约定, 因为玩家/运营方对那套格式已有肌肉记忆:
 *   第 1 行 店主 (本 mod 只做管理员商店, 故固定为 {@link #ADMIN_SHOP_KEYWORDS} 之一)
 *   第 2 行 每次交易数量
 *   第 3 行 价格 (B 买价 : 卖价 S)
 *   第 4 行 物品 id
 */
public final class ShopConstants {

    private ShopConstants() {
    }

    /** 告示牌行数 (原版告示牌恒为 4 行, 与 SignText.LINES 一致)。 */
    public static final int SIGN_LINES = 4;

    public static final int LINE_OWNER = 0;
    public static final int LINE_QUANTITY = 1;
    public static final int LINE_PRICE = 2;
    public static final int LINE_ITEM = 3;

    /**
     * 第 1 行的管理员商店关键词 (大小写与前后空格不敏感)。中英各一, 因为服务器是中文环境但
     * 运营方可能沿用 ChestShop 原版的英文写法。
     */
    public static final String[] ADMIN_SHOP_KEYWORDS = {"admin shop", "adminshop", "管理员商店"};

    /**
     * 单次交易数量上限。取 2304 = 36 格 * 64, 即"一背包同种物品"的物理上限:
     * 再大也放不进玩家背包, 只会掉一地, 没有业务意义。
     */
    public static final int MAX_QUANTITY = 2304;

    /**
     * 单次交易价格上限 (信用点)。远低于 long 上界, 目的是防运营方在告示牌上手滑多打几个 0
     * 造成一次交易吃掉全服货币量 —— 主 mod 账本虽有 Math.addExact 防溢出, 但那只防溢出不防手滑。
     */
    public static final long MAX_PRICE = 1_000_000_000L;

    /** 第 3 行的免费关键词 (ChestShop 原版语义: 价格为 0)。 */
    public static final String FREE_KEYWORD = "free";

    /** 买入指示符 (玩家从商店买)。 */
    public static final char BUY_MARKER = 'B';

    /** 卖出指示符 (玩家卖给商店)。 */
    public static final char SELL_MARKER = 'S';

    /** 买卖价分隔符。 */
    public static final char PRICE_SEPARATOR = ':';
}
