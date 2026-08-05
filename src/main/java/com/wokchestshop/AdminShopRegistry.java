package com.wokchestshop;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashSet;
import java.util.Set;

/**
 * 已激活的管理员商店告示牌位置登记表 (每维度一份, 随该维度存档落盘)。
 *
 * 为什么需要这张表 —— 权限模型的承重墙:
 * Forge 1.20.1 没有 Bukkit 的 SignChangeEvent, 无法在"玩家写完告示牌"那一刻校验他是不是 OP。
 * 若只凭告示牌第 1 行的 "Admin Shop" 字样就认店, 任何玩家随手立一块牌写上关键词 + 自定价格,
 * 就是一台无限印钞机。故本 mod 采用"写牌 + 激活"两步: 文本任何人都能写, 但只有 OP 右键激活、
 * 位置进了本表的告示牌才真正参与交易。权限校验因此收敛在一次 OP 右键上, 不需要 mixin 拦截告示牌编辑包。
 *
 * 激活时另会把告示牌上蜡 (SignBlockEntity.setWaxed), 杜绝激活后被普通玩家改价 —— 两者缺一不可:
 * 只登记不上蜡 = 位置合法但内容可被篡改; 只上蜡不登记 = 任何人上蜡的牌都成店。
 *
 * 线程: 仅服务端主线程读写 (交互事件与 SavedData 都在主线程)。任何变更后必须 setDirty 否则不落盘。
 */
public final class AdminShopRegistry extends SavedData {

    /** DimensionDataStorage 数据文件名。 */
    public static final String DATA_NAME = "wokchestshop_admin_shops";

    private static final String K_SHOPS = "shops";

    /** 已激活告示牌的 BlockPos.asLong() 集合。商店规模是数十量级, 无需 fastutil 特化容器。 */
    private final Set<Long> activated = new HashSet<>();

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

    public boolean isActivated(BlockPos pos) {
        return activated.contains(pos.asLong());
    }

    /** @return true 表示本次新增激活; false 表示该位置早已激活 (幂等, 便于重复右键不刷屏) */
    public boolean activate(BlockPos pos) {
        boolean added = activated.add(pos.asLong());
        if (added) {
            setDirty();
        }
        return added;
    }

    /**
     * 注销某位置 (告示牌被破坏 / OP 主动停用时调用)。
     *
     * @return true 表示确实移除了一条登记
     */
    public boolean deactivate(BlockPos pos) {
        boolean removed = activated.remove(pos.asLong());
        if (removed) {
            setDirty();
        }
        return removed;
    }

    /** 当前维度已激活的商店数 (供 OP 命令统计)。 */
    public int size() {
        return activated.size();
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        long[] packed = new long[activated.size()];
        int i = 0;
        for (Long pos : activated) {
            packed[i++] = pos;
        }
        tag.putLongArray(K_SHOPS, packed);
        return tag;
    }

    public static AdminShopRegistry load(CompoundTag tag) {
        AdminShopRegistry data = new AdminShopRegistry();
        for (long pos : tag.getLongArray(K_SHOPS)) {
            data.activated.add(pos);
        }
        return data;
    }
}
