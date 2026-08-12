package com.wokchestshop;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
        BAD_PRICE,
        /** 卖价高于买价: 玩家可低买高卖套利, 系统侧无限库存无限资金, 不存在任何合法用例。 */
        INVERTED_SPREAD
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
        // 全空白规范化, 不能只 trim(): 中文输入法很容易在关键词前后甚至中间带上全角空格 U+3000,
        // 而 trim() 与正则 \\s 都不认它。抬头匹配失败的后果是 handler 完全不介入, OP 看到的只是原版
        // 编辑界面 —— 零提示零日志, 是这套流程里最难自查的一种失败。
        String normalized = normalizeWhitespace(line).toLowerCase(Locale.ROOT);
        for (String keyword : ShopConstants.ADMIN_SHOP_KEYWORDS) {
            if (normalized.equals(keyword)) {
                return true;
            }
        }
        return false;
    }

    /** 首尾空白剥离 + 内部连续空白折叠为单个半角空格, 判据统一为 {@link Character#isWhitespace}。 */
    private static String normalizeWhitespace(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        boolean pendingSpace = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isWhitespace(c)) {
                pendingSpace = sb.length() > 0; // 前导空白直接丢弃
                continue;
            }
            if (pendingSpace) {
                sb.append(' ');
                pendingSpace = false;
            }
            sb.append(c);
        }
        return sb.toString();
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

        // 卖价 > 买价即成套利循环: 玩家右键买入再左键卖出净赚差价, 而管理员店库存与资金都是无限的。
        // 最恶劣的变体是 "B free:1000 S" —— 买入零成本、卖出足额发钱, 纯粹的零成本印钞。
        // 把 B/S 两数写反比多打一个 0 更常见, 而 MAX_PRICE 只挡后者, 故这条闸门必须单独立。
        if (price.allowsBuy() && price.allowsSell() && price.sellPrice() > price.buyPrice()) {
            return Result.fail(Error.INVERTED_SPREAD);
        }

        return Result.of(new ShopSignSpec(quantity, item, price));
    }

    /** 返回 <=0 表示非法 (含空行、非数字、越界); 调用方据此出 BAD_QUANTITY。 */
    private static int parseQuantity(String raw) {
        if (raw == null) {
            return -1;
        }
        String s = raw.strip();
        if (s.isEmpty() || s.length() > 6) {
            return -1;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            // 只认 ASCII 数字: Character.isDigit 对全角数字也返回 true, 会让 Integer.parseInt 抛异常。
            if (c < '0' || c > '9') {
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
     *
     * 但 containsKey 挡不住直接写字面量 {@code air} —— minecraft:air 是注册表的正式条目, containsKey
     * 返回 true。而 AIR 的 ItemStack 恒 isEmpty, 交付阶段 {@code Inventory.add} 对空栈直接返回 false
     * 且栈本身为空, 掉落兜底分支也不会触发, 结果是"扣了钱、零交付、还提示购买成功"。故必须显式排除。
     */
    private static Item parseItem(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.strip().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(s.contains(":") ? s : "minecraft:" + s);
        if (id == null || !ForgeRegistries.ITEMS.containsKey(id)) {
            return null;
        }
        Item item = ForgeRegistries.ITEMS.getValue(id);
        if (item == null || item == Items.AIR || new ItemStack(item).isEmpty()) {
            return null;
        }
        return item;
    }
}
