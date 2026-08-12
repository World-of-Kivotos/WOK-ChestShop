package com.wokchestshop;

import java.util.Locale;

/**
 * 告示牌第 3 行 (价格行) 的解析结果。沿用 ChestShop 原版语法:
 *
 * <pre>
 *   B 250:200 S   买价 250, 卖价 200
 *   B 250         只买不卖
 *   S 200         只卖不买
 *   B free        买价 0 (白送)
 * </pre>
 *
 * 语义方向以玩家为主语: {@code buyPrice} 是玩家掏出去的钱 (对经济体是 sink),
 * {@code sellPrice} 是玩家收到的钱 (对经济体是 faucet)。
 *
 * 价格是"每次交易总价"而非单价 —— 对应第 2 行的 quantity 个物品, 与 ChestShop 一致。
 *
 * @param buyPrice  玩家买入价; {@code null} 表示本店不卖 (不接受买入)
 * @param sellPrice 玩家卖出价; {@code null} 表示本店不收 (不接受卖出)
 */
public record ShopPriceLine(Long buyPrice, Long sellPrice) {

    private static final String FREE_UPPER = ShopConstants.FREE_KEYWORD.toUpperCase(Locale.ROOT);

    /** parseLong 在 18 位以内必不溢出; 超长数字串直接拒绝而非截断。 */
    private static final int MAX_DIGITS = 18;

    public boolean allowsBuy() {
        return buyPrice != null;
    }

    public boolean allowsSell() {
        return sellPrice != null;
    }

    /**
     * 解析价格行。任何不合法的写法一律返回 null (交调用方出用户可读提示), 不抛异常也不做"猜测性修复" ——
     * 价格是钱, 把 "B 2S0" 猜成 250 比直接拒绝危险得多。
     *
     * @param raw 告示牌第 3 行原文 (可含前后空格与任意大小写)
     * @return 解析结果; 语法非法 / 价格越界 / 两侧指示符重复或缺失时返回 null
     */
    public static ShopPriceLine parse(String raw) {
        if (raw == null) {
            return null;
        }
        String line = raw.strip();
        if (line.isEmpty()) {
            return null;
        }

        int sep = line.indexOf(ShopConstants.PRICE_SEPARATOR);
        if (sep < 0) {
            // 单边: 整行就是一个 (指示符, 数字) 组合。
            Part only = Part.parse(line);
            if (only == null) {
                return null;
            }
            return only.marker() == ShopConstants.BUY_MARKER
                    ? new ShopPriceLine(only.price(), null)
                    : new ShopPriceLine(null, only.price());
        }

        // 双边: 冒号左右各一个组合, 且必须一 B 一 S (同为 B 或同为 S 是歧义写法, 拒绝)。
        Part left = Part.parse(line.substring(0, sep));
        Part right = Part.parse(line.substring(sep + 1));
        if (left == null || right == null || left.marker() == right.marker()) {
            return null;
        }
        Part buy = left.marker() == ShopConstants.BUY_MARKER ? left : right;
        Part sell = left.marker() == ShopConstants.SELL_MARKER ? left : right;
        return new ShopPriceLine(buy.price(), sell.price());
    }

    /**
     * 价格行的一侧: 恰好一个 B/S 指示符 + 一个非负整数 (或 free 关键词)。
     * 指示符位置不限 (ChestShop 惯例是 "B 250" 与 "200 S" 混写), 但必须有且只有一个。
     */
    private record Part(char marker, long price) {

        static Part parse(String raw) {
            // strip() 而非 trim(): trim 只剥 <= U+0020 的字符, 剥不掉中文输入法的全角空格 U+3000,
            // 而 strip 用的正是下面循环里同一套 Character.isWhitespace 判据。全程只留这一套口径 ——
            // 早先版本混用 isWhitespace 放行、正则 \\s 清除, 两者差集里就有全角空格, 结果中文玩家打出的
            // "B　250" 会被静默判死且零提示。
            String s = raw.strip().toUpperCase(Locale.ROOT);
            if (s.isEmpty()) {
                return null;
            }

            char marker = 0;
            StringBuilder digits = new StringBuilder();
            // 去掉指示符与全部空白后剩下的内容, 与 digits 在同一次扫描里产出, 杜绝两套口径打架。
            StringBuilder body = new StringBuilder();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (Character.isWhitespace(c)) {
                    continue;
                }
                if (c == ShopConstants.BUY_MARKER || c == ShopConstants.SELL_MARKER) {
                    if (marker != 0) {
                        return null; // 一侧出现两个指示符 (如 "BS 250") 是歧义, 拒绝。
                    }
                    marker = c;
                    continue;
                }
                body.append(c);
                if (isAsciiDigit(c)) {
                    digits.append(c);
                } else if (FREE_UPPER.indexOf(c) < 0) {
                    // 既非指示符、非数字、非空白、也不属于 FREE 的字符: 拒绝而非静默忽略,
                    // 否则 "B 2O0" (字母 O) 会被读成 20。
                    return null;
                }
            }
            if (marker == 0) {
                return null; // 无指示符无法判断买卖方向。
            }

            String bodyText = body.toString();
            if (bodyText.equalsIgnoreCase(ShopConstants.FREE_KEYWORD)) {
                return new Part(marker, 0L);
            }
            if (digits.length() == 0 || digits.length() > MAX_DIGITS) {
                return null;
            }
            // 数字与 FREE 字母混写 (如 "B 250 free") 语义矛盾: body 里必须只剩数字。
            if (bodyText.length() != digits.length()) {
                return null;
            }
            long price = Long.parseLong(digits.toString());
            if (price > ShopConstants.MAX_PRICE) {
                return null;
            }
            return new Part(marker, price);
        }

        /**
         * 只认 ASCII 数字, 不能用 {@link Character#isDigit} —— 它对全角数字 (如 U+FF11) 也返回 true,
         * 那些字符会被收进 digits 却让 {@link Long#parseLong} 抛 NumberFormatException 冲出解析层。
         */
        private static boolean isAsciiDigit(char c) {
            return c >= '0' && c <= '9';
        }
    }
}
