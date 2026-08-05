package com.wokchestshop;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Locale;

/**
 * 一块管理员商店告示牌的完整语义 (四行解析结果)。
 *
 * 本类是纯逻辑: 不碰世界、不碰玩家、不碰经济, 输入是四行字符串, 输出是结构化规格。
 * 这样告示牌格式的全部边界 (数量越界 / 物品不存在 / 价格语法) 都能在毫秒级 GameTest 里穷举,
 * 而不必真去世界里摆一块牌。
 *
 * @param quantity 每次交易的物品个数 (1..{@link ShopConstants#MAX_QUANTITY})
 * @param item     交易标的
 * @param price    买卖价 (见 {@link ShopPriceLine})
 */
public record ShopSignSpec(int quantity, Item item, ShopPriceLine price) {

    /** 解析失败的原因; 每个值对应一条给玩家看的 lang 提示。 */
    public enum Error {
        /** 第 1 行不是管理员商店关键词 —— 这不是一块商店牌, 调用方应完全不干预该告示牌。 */
        NOT_ADMIN_SHOP,
        BAD_QUANTITY,
        BAD_ITEM,
        BAD_PRICE
    }

    /** 解析结果: spec 与 error 恒有且仅有一个非 null。 */
    public record Result(ShopSignSpec spec, Error error) {

        public boolean ok() {
            return spec != null;
        }

        static Result of(ShopSignSpec spec) {
            return new Result(spec, null);
        }

        static Result fail(Error error) {
            return new Result(null, error);
        }
    }

    /**
     * 第 1 行是否是管理员商店抬头。独立于完整解析对外暴露, 因为交互层必须先回答"这块牌该不该归我管",
     * 再决定要不要拦截玩家的右键/左键 —— 对普通告示牌绝不能拦 (否则玩家连字都编辑不了)。
     */
    public static boolean isAdminShopHeader(String line) {
        if (line == null) {
            return false;
        }
        String normalized = line.trim().toLowerCase(Locale.ROOT);
        for (String keyword : ShopConstants.ADMIN_SHOP_KEYWORDS) {
            if (normalized.equals(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析四行告示牌。
     *
     * @param lines 四行原文 (长度必须为 {@link ShopConstants#SIGN_LINES}); 未填的行传空串而非 null
     * @return 成功则携带 spec, 失败则携带具体原因
     */
    public static Result parse(String[] lines) {
        if (lines == null || lines.length != ShopConstants.SIGN_LINES) {
            return Result.fail(Error.NOT_ADMIN_SHOP);
        }
        if (!isAdminShopHeader(lines[ShopConstants.LINE_OWNER])) {
            return Result.fail(Error.NOT_ADMIN_SHOP);
        }

        int quantity = parseQuantity(lines[ShopConstants.LINE_QUANTITY]);
        if (quantity <= 0) {
            return Result.fail(Error.BAD_QUANTITY);
        }

        Item item = parseItem(lines[ShopConstants.LINE_ITEM]);
        if (item == null) {
            return Result.fail(Error.BAD_ITEM);
        }

        ShopPriceLine price = ShopPriceLine.parse(lines[ShopConstants.LINE_PRICE]);
        if (price == null || (!price.allowsBuy() && !price.allowsSell())) {
            return Result.fail(Error.BAD_PRICE);
        }

        return Result.of(new ShopSignSpec(quantity, item, price));
    }

    /** 返回 <=0 表示非法 (含空行、非数字、越界); 调用方据此出 BAD_QUANTITY。 */
    private static int parseQuantity(String raw) {
        if (raw == null) {
            return -1;
        }
        String s = raw.trim();
        if (s.isEmpty() || s.length() > 6) {
            return -1;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return -1; // 只收纯数字: "64个" / "x64" 一律拒绝而非截取, 避免歧义。
            }
        }
        int value = Integer.parseInt(s);
        return (value >= 1 && value <= ShopConstants.MAX_QUANTITY) ? value : -1;
    }

    /**
     * 解析物品行。接受 {@code minecraft:diamond} 与省略命名空间的 {@code diamond} 两种写法
     * (ResourceLocation 对无命名空间的串默认补 minecraft), 也接受本 mod 之外任意 mod 的物品 id。
     *
     * 注意不能用 {@code ForgeRegistries.ITEMS.getValue} 的返回值判空: 注册表对未知 id 返回默认值
     * (minecraft:air) 而非 null, 直接用会把写错的 id 静默变成"卖空气"。故先 containsKey 再取值。
     */
    private static Item parseItem(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(s.contains(":") ? s : "minecraft:" + s);
        if (id == null || !ForgeRegistries.ITEMS.containsKey(id)) {
            return null;
        }
        return ForgeRegistries.ITEMS.getValue(id);
    }
}
