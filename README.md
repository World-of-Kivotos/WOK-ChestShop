# WOK ChestShop

告示牌管理员商店，接入 [Mining Dimension System](https://github.com/World-of-Kivotos/Wok-Project)（`miningdim`）的信用点账本。

沿用 ChestShop (Acrobot) 的四行告示牌定价格式，玩家右键买入、左键卖出，系统侧无限库存。

- 平台：Minecraft 1.20.1 + Forge 47.3.0
- 硬依赖：`miningdim`（缺失则拒绝加载）

---

## 一、告示牌格式

```
第 1 行   Admin Shop          管理员商店抬头（也可写 adminshop / 管理员商店）
第 2 行   64                  每次交易的物品个数（1 - 2304）
第 3 行   B 250:200 S         价格：玩家花 250 买，卖得 200
第 4 行   diamond             物品 ID（可省略 minecraft: 前缀）
```

第 3 行的写法与 ChestShop 一致：

| 写法 | 含义 |
|---|---|
| `B 250:200 S` | 买价 250，卖价 200 |
| `B 250` | 只出售，不收购 |
| `S 200` | 只收购，不出售 |
| `B free` | 免费领取 |

价格是**每次交易总价**（对应第 2 行的数量），不是单价。大小写与空格不敏感，`S 200:250 B` 与 `B 250:200 S` 等价。

## 二、开店流程

1. 立一块告示牌，按上面四行写好。
2. **OP 不潜行右键**该告示牌 → 激活。
3. 激活时告示牌会自动上蜡，之后任何人（包括 OP）都改不了文本，要改价先撬蜡。

未激活的告示牌就是一块普通牌，不参与任何交易。

## 三、交互约定

| 操作 | 行为 |
|---|---|
| 右键（不潜行） | 买入 |
| 左键（不潜行） | 卖出 |
| 潜行右键 / 潜行左键 | 原版行为（编辑 / 破坏），不走商店逻辑 |

破坏告示牌会自动注销登记。

## 四、经济接线

- **买入**走主 mod 的 `IEconomyService.tryCharge` —— 钱真正离开经济体，是一个 sink。
- **卖出**走 `IEconomyService.grantDaily`，并入主 mod 的**每人每日信用点衰减主闸**，与挖矿、卖菜共用同一个 `credit_faucet` 上限。当日卖得越多单价越低，实发额会低于告示牌标价，此时会明确提示玩家。

这条纪律不能松：若改成 `grant` 全额发放，等于在衰减主闸之外另开一个 faucet，玩家可以绕开挖矿上限、靠刷可再生资源无限变现。`ShopGameTests.sellGoesThroughDailyFaucetGate` 就是钉死这一点的回归网。

**带附魔、损伤或自定义 NBT 的物品不可出售**，否则玩家能把高价值物品按素材价卖给系统，差价直接铸成信用点。

## 五、本版范围

本版**只做管理员商店，不做玩家对玩家的箱子挂牌**。

原因是主 mod 的经济审计（`docs/Economy_Completeness_Audit.md`）指出 P2P 通道有四条前置门槛尚未满足：偏离手续费只覆盖 4 个预设物品、无成交中位数兜底、`fee = 0` 会撞 `requirePositive` 抛异常、无资金流水。在这些补齐前开 P2P 箱子挂牌，等于把现有反洗钱设计里唯一的摩擦点作废。

## 六、构建

需要 JDK 17（ForgeGradle 6 与 MC 1.20.1 的硬要求，系统默认的高版本 JDK 会在配置阶段就失败）。

```powershell
# 1. 生成主 mod 依赖 jar（首次或主 mod 更新后执行）
powershell -ExecutionPolicy Bypass -File tools\prepare-miningdim-dep.ps1 -Rebuild

# 2. 构建与测试
$env:JAVA_HOME = "C:\Users\<你>\.gradle\jdks\eclipse_adoptium-17-amd64-windows\jdk-17.0.18+8"
.\gradlew compileJava
.\gradlew runGameTestServer
```

`tools/prepare-miningdim-dep.ps1` 做的事以及**为什么必须这么做**（两个坑：`fg.deobf` 不还原注解元素名、PowerShell 的 ZipArchive 与 Java 的 ZipInputStream 不兼容）写在该脚本头部注释里，改动前务必先读。

### 测试

`gradlew runGameTestServer` 应输出 `All 14 required tests passed`。

判定成功必须看这一行，不能只看 Gradle 退出码 —— 服务端启动阶段崩溃时 `runGameTestServer` 仍可能报 BUILD SUCCESSFUL。另外若看到 `0 tests are now running` 也是失败信号：那通常意味着 GameTest 的 structure 用了别的 mod 的命名空间，被 Forge 按 namespace 静默过滤掉了。

## 七、许可

AGPL-3.0-or-later，见 [LICENSE](LICENSE)。
