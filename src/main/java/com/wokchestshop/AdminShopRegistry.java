package com.wokchestshop;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.Map;

/**
 * 已激活的管理员商店登记表 (每维度一份, 随该维度存档落盘)。
 *
 * 为什么需要这张表 —— 权限模型的承重墙:
 * Forge 1.20.1 没有 Bukkit 的 SignChangeEvent, 无法在"玩家写完告示牌"那一刻校验他是不是 OP。
 * 若只凭告示牌第 1 行的 "Admin Shop" 字样就认店, 任何玩家随手立一块牌写上关键词 + 自定价格,
 * 就是一台无限印钞机。故本 mod 采用"写牌 + 激活"两步: 文本任何人都能写, 但只有 OP 右键激活过的
 * 告示牌才真正参与交易。权限校验因此收敛在一次 OP 右键上, 不需要 mixin 拦截告示牌编辑包。
 *
 * 为什么登记的是【内容快照】而不是光秃秃的坐标 —— 一条被复核揪出来的 Critical:
 * 早先版本只存 BlockPos.asLong(), 靠 BlockEvent.BreakEvent 在告示牌被破坏时注销。但该事件只在
 * 玩家亲手挖掘时由 ServerPlayerGameMode.destroyBlock 抛出 —— 拆掉墙牌背后的支撑方块导致其自然
 * 脱落、TNT 爆炸、活塞推动、水火冲毁、/setblock、以及其它 mod 直接调 Level.destroyBlock, 统统
 * 不触发 BreakEvent。于是登记项永久残留, 任何普通玩家只要在那个坐标重新立一块自己写的牌, 就白捡
 * 一间完全生效的管理员商店 (写 "B free" 无限白拿物品, 写 "S 1000000000" 每次左键印上千万信用点)。
 *
 * 现在改为: 激活时把当时的规格 (数量/物品/买价/卖价) 一并存下, 每次交易前用当前牌面重新解析并比对。
 * 内容对不上 = 这不是当初那块被授权的牌, 立即注销并拒绝交易。这条防线不依赖任何破坏事件的可靠性,
 * 破坏事件退化成一个"尽早清理"的优化而非安全边界。
 *
 * 线程: 仅服务端主线程读写 (交互事件与 SavedData 都在主线程)。任何变更后必须 setDirty 否则不落盘。
 */
public final class AdminShopRegistry extends SavedData {

    /** DimensionDataStorage 数据文件名。 */
    public static final String DATA_NAME = "wokchestshop_admin_shops";

    private static final String K_SHOPS = "shops";
    private static final String K_POS = "pos";
    private static final String K_QUANTITY = "quantity";
    private static final String K_ITEM = "item";
    private static final String K_BUY = "buy";
    private static final String K_SELL = "sell";

    /** 价格字段的"该方向不营业"哨兵值。价格恒非负, 故 -1 不会与真实价格冲突。 */
    public static final long ABSENT_PRICE = -1L;

    /**
     * 激活那一刻的商店规格快照。交易前用它与当前牌面比对, 任何一项对不上都说明牌被换过或被撬蜡改过。
     *
     * @param quantity  每次交易数量
     * @param itemId    物品注册名 (存字符串而非 Item 引用: 要随存档持久化, 且 mod 卸载后仍可读出来排查)
     * @param buyPrice  买价; {@link #ABSENT_PRICE} 表示不出售
     * @param sellPrice 卖价; {@link #ABSENT_PRICE} 表示不收购
     */
    public record ShopRecord(int quantity, String itemId, long buyPrice, long sellPrice) {

        /** 从解析好的告示牌规格生成快照。 */
        public static ShopRecord of(ShopSignSpec spec) {
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(spec.item());
            return new ShopRecord(
                    spec.quantity(),
                    id == null ? "" : id.toString(),
                    spec.price().allowsBuy() ? spec.price().buyPrice() : ABSENT_PRICE,
                    spec.price().allowsSell() ? spec.price().sellPrice() : ABSENT_PRICE);
        }

        /** 当前牌面是否与本快照完全一致 (逐项相等, 不做任何容差)。 */
        public boolean matches(ShopSignSpec spec) {
            return this.equals(of(spec));
        }
    }

    /** 已激活告示牌 BlockPos.asLong() -> 激活时的规格快照。商店规模是数十量级, HashMap 足够。 */
    private final Map<Long, ShopRecord> activated = new HashMap<>();

    public AdminShopRegistry() {
    }

    /**
     * 取/建该维度的登记表。SavedData.Factory 是 1.20.2+ 才有, 本目标版本用三参 computeIfAbsent
     * (load, create, name), 与主 mod 的 EconomyWalletData 同范式。
     */
    public static AdminShopRegistry get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                AdminShopRegistry::load, AdminShopRegistry::new, DATA_NAME);
    }

    /** 该位置的激活快照; 未激活返回 null。 */
    public ShopRecord recordAt(BlockPos pos) {
        return activated.get(pos.asLong());
    }

    public boolean isActivated(BlockPos pos) {
        return activated.containsKey(pos.asLong());
    }

    /**
     * 激活 (或以新规格重新激活) 某位置。OP 改价后重新右键即可刷新快照。
     *
     * @return true 表示这是一次新增激活; false 表示该位置已激活且规格未变 (幂等, 重复右键不刷屏)
     */
    public boolean activate(BlockPos pos, ShopSignSpec spec) {
        ShopRecord next = ShopRecord.of(spec);
        ShopRecord prev = activated.put(pos.asLong(), next);
        if (prev == null || !prev.equals(next)) {
            setDirty();
            return prev == null;
        }
        return false;
    }

    /**
     * 注销某位置 (告示牌被破坏 / 内容与快照不符 / OP 主动停用时调用)。
     *
     * @return true 表示确实移除了一条登记
     */
    public boolean deactivate(BlockPos pos) {
        boolean removed = activated.remove(pos.asLong()) != null;
        if (removed) {
            setDirty();
        }
        return removed;
    }

    /** 当前维度已激活的商店数。 */
    public int size() {
        return activated.size();
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Map.Entry<Long, ShopRecord> e : activated.entrySet()) {
            ShopRecord rec = e.getValue();
            CompoundTag entry = new CompoundTag();
            entry.putLong(K_POS, e.getKey());
            entry.putInt(K_QUANTITY, rec.quantity());
            entry.putString(K_ITEM, rec.itemId());
            entry.putLong(K_BUY, rec.buyPrice());
            entry.putLong(K_SELL, rec.sellPrice());
            list.add(entry);
        }
        tag.put(K_SHOPS, list);
        return tag;
    }

    public static AdminShopRegistry load(CompoundTag tag) {
        AdminShopRegistry data = new AdminShopRegistry();
        ListTag list = tag.getList(K_SHOPS, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            data.activated.put(entry.getLong(K_POS), new ShopRecord(
                    entry.getInt(K_QUANTITY),
                    entry.getString(K_ITEM),
                    entry.getLong(K_BUY),
                    entry.getLong(K_SELL)));
        }
        return data;
    }
}
