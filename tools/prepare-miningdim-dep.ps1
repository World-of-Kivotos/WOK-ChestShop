<#
.SYNOPSIS
    从主 mod (miningdim) 的构建产物生成本工程 libs/ 下的依赖 jar。

.DESCRIPTION
    本工程编译与 dev 运行都依赖主 mod 的 economy 门面, 依赖来源是主 mod reobf 后的 jar 经
    ForgeGradle 的 fg.deobf 还原。这中间有几个不显然的坑, 本脚本就是为了把它们固化下来:

    坑 1 —— 必须剥离主 mod 的 GameTest 类。
        fg.deobf 不还原【注解元素名】的映射。主 mod 有两处 @BeforeBatch(batch = ...),
        deobf 后 batch 元素名仍是 SRG 名, dev 环境下 Forge 在 GameTest 注册阶段读不到该元素,
        抛 IncompleteAnnotationException 并直接崩服 ("Failed to start the minecraft server"),
        本工程一个测试都跑不起来。依赖方本来也不该携带被依赖方的测试类, 故一律剥掉。
        注意只剥 *GameTests*.class, 必须保留 com/miningdim/testutil/MockGameTestPlayers,
        本工程的 GameTest 要用它造真 ServerPlayer。

    坑 2 —— 必须用 Java 的 jar 工具重打包, 不能用 PowerShell 的 ZipArchive。
        .NET 的 ZipArchive 在 Update 模式下会写出 "STORED + EXT descriptor" 组合的条目,
        而 FG 内部 deobf 用的 InstallerTools 走 java.util.zip.ZipInputStream 读取, 该组合直接抛
        "ZipException: only DEFLATED entries can have EXT descriptor", 表现为 Gradle 报
        "Could not find com.miningdim:...:x.y.z_mapped_parchment_..." 这种看不出所以然的解析失败。

    坑 3 —— 必须剥离主 mod 的 mixin 配置。
        主 mod 早先全库零 mixin, 后来加了 miningdim.mixins.json / miningdim.compat.mixins.json。
        fg.deobf 只改写 class 字节码, 不改写 refmap, SRG 态 refmap 在 dev (官方映射) 下对不上目标,
        mixin 应用阶段可能直接失败。本工程只用主 mod 的 economy 门面, 不需要它的 mixin,
        故连同 MANIFEST 的 MixinConfigs 一起去掉。META-INF/jarjar 内嵌件同理剥掉 (dev 侧走 minecraftLibrary)。

    另: 主 mod 把 GeckoLib 声明为 mandatory, dev 运行期由 build.gradle 的 runtimeOnly 提供, 不在本脚本处理。

.PARAMETER MainModRepo
    主 mod 工程根目录 (含 build/libs 产物)。

.PARAMETER JdkHome
    JDK 17 目录 (ForgeGradle 6 与 MC 1.20.1 必须用 Java 17)。默认取 JAVA_HOME。

.PARAMETER Rebuild
    先在主 mod 工程里跑一次 gradlew build 再取产物。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\prepare-miningdim-dep.ps1 -MainModRepo E:\wok\Wok-Project
    powershell -ExecutionPolicy Bypass -File tools\prepare-miningdim-dep.ps1 -MainModRepo E:\wok\Wok-Project -Rebuild
#>
param(
    [string]$MainModRepo = 'D:\Repo\Wok-Project',
    [string]$JdkHome = $env:JAVA_HOME,
    [switch]$Rebuild
)

$ErrorActionPreference = 'Stop'

$here = Split-Path -Parent $PSScriptRoot
$libs = Join-Path $here 'libs'

# gradle.properties 里的 miningdim_artifact / miningdim_version 决定 flatDir 要找的文件名。
$targetJar = Join-Path $libs 'miningdim-1.20.1-1.0.0.jar'

if ([string]::IsNullOrWhiteSpace($JdkHome) -or -not (Test-Path (Join-Path $JdkHome 'bin\jar.exe'))) {
    throw "找不到 JDK 17 (传 -JdkHome 或设置 JAVA_HOME)。ForgeGradle 6 与 MC 1.20.1 必须用 Java 17 构建。"
}
$jarExe = Join-Path $JdkHome 'bin\jar.exe'

if ($Rebuild) {
    Write-Host "[1/6] 重新构建主 mod ..."
    Push-Location $MainModRepo
    try {
        $env:JAVA_HOME = $JdkHome
        & (Join-Path $MainModRepo 'gradlew.bat') build -x test --console=plain
        if ($LASTEXITCODE -ne 0) { throw "主 mod 构建失败 (exit $LASTEXITCODE)" }
    } finally {
        Pop-Location
    }
} else {
    Write-Host "[1/6] 跳过主 mod 构建 (加 -Rebuild 可强制重建)"
}

# 主 mod 的产物名带版本号 (miningdim-1.20.1-<mod_version>[-all].jar)。优先取普通 reobf jar,
# 没有时退而取 -all (jarJar 版, 内嵌件会在下面剥掉); 同类取最新的一个。
$built = @(Get-ChildItem (Join-Path $MainModRepo 'build\libs') -File -Filter 'miningdim-1.20.1-*.jar' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch '-(sources|slim|dev)\.jar$' })
$source = ($built | Where-Object { $_.Name -notmatch '-all\.jar$' } | Sort-Object LastWriteTime -Descending | Select-Object -First 1)
if ($null -eq $source) {
    $source = ($built | Sort-Object LastWriteTime -Descending | Select-Object -First 1)
}
if ($null -eq $source) {
    throw "主 mod 产物不存在: $MainModRepo\build\libs\miningdim-1.20.1-*.jar  (先在主 mod 工程跑 gradlew build, 或本脚本加 -Rebuild)"
}
Write-Host "      使用主 mod 产物: $($source.FullName)"

$work = Join-Path $env:TEMP ("wokchestshop-dep-" + [System.Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $work -Force | Out-Null

try {
    Write-Host "[2/6] 解包主 mod jar ..."
    Push-Location $work
    try {
        & $jarExe xf $source.FullName
        if ($LASTEXITCODE -ne 0) { throw "jar 解包失败" }
    } finally {
        Pop-Location
    }

    Write-Host "[3/6] 剥离 GameTest 类 (见脚本头部坑 1) ..."
    $victims = @(Get-ChildItem -Path $work -Recurse -File -Filter '*GameTests*.class')
    $victims | Remove-Item -Force
    Write-Host "      移除 $($victims.Count) 个测试类"

    $mock = @(Get-ChildItem -Path $work -Recurse -File -Filter 'MockGameTestPlayers*.class')
    if ($mock.Count -eq 0) {
        throw "MockGameTestPlayers 被误删 —— 本工程的 GameTest 依赖它造真 ServerPlayer。"
    }

    Write-Host "[4/6] 剥离 mixin 配置与 jarJar 内嵌件 (见脚本头部坑 3) ..."
    $mixinConfigs = @(Get-ChildItem -Path $work -File -Filter '*.mixins.json')
    $mixinConfigs | Remove-Item -Force
    Write-Host "      移除 $($mixinConfigs.Count) 份 mixin 配置"
    $jarjar = Join-Path $work 'META-INF\jarjar'
    if (Test-Path $jarjar) { Remove-Item $jarjar -Recurse -Force }

    Write-Host "[5/6] 用 Java jar 工具重打包 (见脚本头部坑 2) ..."
    $manifest = Join-Path $work 'META-INF\MANIFEST.MF'
    $manifestCopy = Join-Path $env:TEMP ("wokchestshop-manifest-" + [System.Guid]::NewGuid().ToString('N') + ".mf")
    # 去掉 MixinConfigs 行; 末尾保留一个空行, jar 工具要求 manifest 以换行结束。
    $lines = @(Get-Content $manifest | Where-Object { $_ -notlike 'MixinConfigs*' -and $_.Trim() -ne '' })
    ($lines + '') | Set-Content $manifestCopy -Encoding ASCII
    Remove-Item $manifest -Force   # 避免与 -m 传入的 manifest 重复成为普通条目

    New-Item -ItemType Directory -Path $libs -Force | Out-Null
    Push-Location $work
    try {
        & $jarExe cfm $targetJar $manifestCopy .
        if ($LASTEXITCODE -ne 0) { throw "jar 重打包失败" }
    } finally {
        Pop-Location
        Remove-Item $manifestCopy -Force -ErrorAction SilentlyContinue
    }

    Write-Host "[6/6] 清理 ForgeGradle deobf 缓存 (坐标未变但内容已变, 不清会用旧产物) ..."
    $gradleHome = if ([string]::IsNullOrWhiteSpace($env:GRADLE_USER_HOME)) { Join-Path $env:USERPROFILE '.gradle' } else { $env:GRADLE_USER_HOME }
    foreach ($cache in @(
        (Join-Path $gradleHome 'caches\forge_gradle\bundled_deobf_repo\com\miningdim'),
        (Join-Path $gradleHome 'caches\forge_gradle\deobf_dependencies\com\miningdim')
    )) {
        if (Test-Path $cache) { Remove-Item $cache -Recurse -Force }
    }

    $size = (Get-Item $targetJar).Length
    Write-Host ""
    Write-Host "完成: $targetJar ($size bytes)"
    Write-Host "接着跑: gradlew runGameTestServer"
} finally {
    Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
}
