package com.wokchestshop;

/**
 * 告示牌商店的全部硬约束常量 (单一来源)。
 *
 * 命名与语义对齐 ChestShop (Acrobot) 的四行告示牌约定, 因为玩家/运营方对那套格式已有肌肉记忆:
 *   第 1 行 店主 (本 mod 只做管理员商店, 故固定为 {@link #ADMIN_SHOP_KEYWORDS} 之一)
 *   第 2 行 每次交易数量
 *   第 3 行 价格 (B 买价 : 卖价 S)
 *   第 4 行 物品 id, 或 {@link #TEMPLATE_PREFIX} + 显示名 (样品商店, 见 {@link ShopSignSpec})
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

    /**
     * 第 4 行以此开头 = 样品商店: 商品不是"某个物品 id 的素材", 而是 OP 激活时主手拿着的那件样品 (连同 NBT)。
     *
     * 为什么需要: TaCZ 的所有枪共用一个物品 id (tacz:modern_kinetic_gun), 型号写在 NBT 的 GunId 里; 子弹、
     * 枪匠图纸同理。只认物品 id 的告示牌卖出去的是一把没有型号的空壳枪。样品随激活快照存进登记表,
     * 牌面只写给人看的显示名。
     */
    public static final char TEMPLATE_PREFIX = '@';

    /**
     * 样品商店显示名的最大字数。告示牌一行本来就放不下太长的字, 更要紧的是显示名参与内容快照比对,
     * 限长让它保持"一眼能核对"的短标签, 而不是塞进一段描述。
     */
    public static final int MAX_TEMPLATE_LABEL_LENGTH = 15;

    /**
     * 激活确认窗口 (tick)。OP 第一次右键只预览, 窗口内对同一块牌、同样内容 (样品店还要同一件样品)
     * 再右键一次才真正激活。
     *
     * 为什么要两步: 激活只要一次 OP 右键, 而牌面文字任何玩家都能写。玩家立一块 "B free" 的牌再骗 OP
     * 过来右键一下, 就白得一间免费管理员商店。两步确认让 OP 在生效前必定先看到一遍完整条款。
     */
    public static final long ACTIVATION_CONFIRM_TICKS = 200L;
}
