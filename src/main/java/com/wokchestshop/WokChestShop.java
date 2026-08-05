package com.wokchestshop;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 告示牌管理员商店 mod 入口。
 *
 * 职责边界: 本 mod 只做"告示牌 -> 交易意图"的翻译与执行, 不持有任何货币状态 ——
 * 余额、每日 faucet 计数、衰减主闸全部是主 mod (miningdim) 账本的职责, 本 mod 经
 * {@code IEconomyService} 门面调用。这条边界一旦破掉 (比如本 mod 自己记一份余额),
 * 就会出现两套账本对不上的经典事故。
 *
 * 依赖 miningdim 是 mandatory (见 mods.toml): 缺了主 mod 本 mod 没有任何可降级的功能,
 * 直接拒绝加载优于运行期 ModList 探测的松散接线。
 */
@Mod(WokChestShop.MODID)
public final class WokChestShop {

    public static final String MODID = "wokchestshop";

    private static final Logger LOGGER = LoggerFactory.getLogger(MODID);

    public WokChestShop() {
        // 全部是运行期玩家交互事件 (右键/左键/破坏), 只上 forge 总线; 本 mod 无任何注册表内容, 不碰 mod 总线。
        MinecraftForge.EVENT_BUS.register(new ShopInteractionHandler());
        LOGGER.info("[wokchestshop] admin sign shop registered (right-click buy / left-click sell, credit-backed)");
    }
}
