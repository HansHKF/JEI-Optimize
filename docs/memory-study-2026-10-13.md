# JEI 和 JET 加载内存调查

2026 年 10 月 13 日。复杂环境覆盖 Minecraft 1.20.1 和 26.1.2，另在 1.21.1 开发环境验证新增功能。

本次确认并修复了旧版 JEI 启动侧栏反复刷新造成的大量临时分配。在 ATM9 中，修复前后整个测量窗口的 JFR 分配估计从 **974.65 GiB 降至 112.69 GiB**，GC 从 **444 次降至 78 次**。加载后 GC 存活堆仍约 **4.09 GiB**；此次修复的主要收益是降低分配压力和等待时间。

## Spark 参考的含义

[用户提供的 Spark 报告](https://spark.lucko.me/4Pk3IWKFUz)使用 MC 1.20.1、Forge 47.4.26、JEI 15.62.0.219、JET 0.14.3 和 Java 21。原始数据是约 658 秒的 **EXECUTION CPU 采样**，只包含 Render thread。元数据记录 heap used 22.045 GiB、committed 25 GiB。

主线程的 JEI 热路径包括创造模式物品列表构建、物品匹配及 UID 处理。其中 `ItemStackListFactory.lambda$create$2` 的包含子调用时间约 43.76 秒。它能说明主线程在做什么，不能把 22 GiB 分配给某一个模组，也不能据此判断内存泄漏。后台线程未在这个采样范围内。

原始 protobuf 按 [Spark 原始数据说明](https://spark.lucko.me/docs/misc/Raw-spark-data)下载并解码，保存在本地。参考报告的 JEI 版本比 ATM9 实装版本新，因此下文发现的旧版侧栏问题不能直接作为该报告全部高内存的解释。

## 测试条件

| 环境 | Minecraft 和加载器 | JEI | Java | 存档 |
| --- | --- | --- | --- | --- |
| ATM9 | 1.20.1 Forge 47.4.0 | 15.20.0.116 | Microsoft 17.0.15 | 同一份独立平坦世界模板 |
| ATM11 | 26.1.2 NeoForge 26.1.2.109 | 29.43.0.106 | Microsoft 25.0.1 | 用户世界的独立副本 |
| 开发环境 | 1.21.1 NeoForge | 19.27.0.340 | OpenJDK 21.0.2 | 独立平坦世界和测试插件 |

复杂环境每次使用独立客户端进程：Xms 1 GiB、Xmx 8 GiB、G1、渲染距离 6、模拟距离 4、60 FPS。每个整合包内部保持相同的模组、配置和世界模板，`tooltipSearchIndex=false`。ATM9 原来没有安装 JET，测试时临时加入，结束后移除。

比较三种状态：JET 主开关关闭；修改前的 JET 0.14.4；本次修改后的工作区构建。关闭主开关的测试保留了仅用于触发侧栏查询的诊断钩子。

流程为：主菜单采集；进入测试世界；实际绘制侧栏；12 类查询各重复 10 次；再等待 20 秒；采集加载后堆；退出世界；等待 15 秒后再次采集；退出客户端。`GC.class_histogram` 和堆转储会触发 GC，均放在计时和分配统计窗口外。[jcmd 文档](https://docs.oracle.com/en/java/javase/25/docs/specs/man/jcmd.html)

同时记录 JFR 分配栈和 GC、GC 日志、250 ms 堆采样、进程 working set、NMT，以及加载后的 HPROF。堆转储由 [Eclipse MAT](https://eclipse.dev/mat/) 离线计算 dominator 和 retained heap。所有结果留在本机。

## 六次有效运行结果

| 环境和状态 | 加载后 GC 存活堆 GiB | GC 事件观测堆峰值 GiB | 窗口累计分配估计 GiB | GC 次数 | JEI 启动秒 |
| --- | ---: | ---: | ---: | ---: | ---: |
| ATM9 JET 关闭 | 4.108 | 6.817 | 110.05 | 81 | 49.04 |
| ATM9 修改前 | 4.089 | 7.187 | 974.65 | 444 | 179.34 |
| ATM9 修改后 | 4.087 | 6.872 | 112.69 | 78 | 100.74 |
| ATM11 JET 关闭 | 3.090 | 5.263 | 83.56 | 83 | 18.52 |
| ATM11 修改前 | 2.761 | 4.771 | 86.12 | 87 | 32.84 |
| ATM11 修改后 | 2.757 | 4.807 | 83.58 | 85 | 32.01 |

分配列是 `jdk.ObjectAllocationSample.weight` 的累计估计，表示整个窗口内反复创建的对象，**不是同时占用的 RAM**。窗口包含世界加载、JEI 启动、查询和等待，长度分别为 ATM9 150.169／279.731／199.114 秒、ATM11 77.508／86.635／85.149 秒。JEI 栈和 JET 栈的分配可重叠，不能相加当作模组独占量。

这些是每种状态各一次有效运行的观察值。启动时间受缓存、后台任务和线程调度影响，不能作为统计平均。修改后 JET 的 JEI 总启动时间仍比关闭 JET 长；后台启动和按 tick 构建会分散工作，单独缩短总启动时间还有优化空间。

ATM9 此次修复使累计分配估计减少 **88.4%**、GC 次数减少 **82.4%**，观测堆峰值只减少约 **4.4%**。ATM11 没有出现同类分配激增，本次修改前后数据接近；相对关闭 JET，现有配方压缩等路径使加载后存活堆降低约 **341 MiB**。

## 旧版侧栏刷新问题和修复

原有合并刷新逻辑只匹配新包名下的无参数监听器。实际 JEI 15.20 和 19.27 使用：

```text
mezz.jei.gui.overlay.IngredientGridWithNavigation
lambda$new$0(Lmezz/jei/common/config/IClientConfig;)V
```

启动时侧栏已经打开，插件在 runtime 回调中批量隐藏物品；每次变化都立即触发完整过滤和排序。原来的合并逻辑未匹配，日志为 `refreshedGrids=0`。JFR 将约 **843.73 GB 十进制字节**的分配归到 `Integer`，主要来自 `IngredientSorter` 比较器的装箱。watchdog 也持续落在 `IngredientFilter.getElements → updateLayout → lambda$new$0`。

新增 legacy Mixin 匹配实际监听器。ABI 检查同时核对类名、描述符、三个已知调用及允许的局部变量和分支指令；出现未知调用或字段写入等指令时保留原生行为。现有启动批处理范围内，每个 grid 只在回调和 runtime 发布完成后刷新一次。物品数据变更和其他监听器仍即时按原顺序运行。

| ATM9 热点 | 修改前 | 修改后 |
| --- | ---: | ---: |
| KubeJS runtime 回调 | 47.86 秒 | 974.6 毫秒 |
| 全部 Sending Runtime | 81.18 秒 | 1.071 秒 |
| 合并后的 grid 刷新数 | 0 | 1 |

26.1.2 的实际 JEI 已使用惰性／脏标记布局路径，旧版 Mixin 不适用。无需把旧版监听器逻辑强行应用到新版本。

## 加载后内存的主要持有者

下表来自 MAT 的具体对象 retained heap 或按 dominator 分组结果。Retained heap 表示该对象独占支配的可达对象规模；共享数据可能由其他 root 持有。各行可能有包含关系，**不能相加作为 JEI 总占用**。

| 快照 | 持有者 | Retained MiB |
| --- | --- | ---: |
| ATM9 修改后 | `RecipeManagerInternal` | 343.77 |
| ATM9 修改后 | `IngredientFilter` | 218.41 |
| ATM9 修改后 | `IngredientManager` | 8.18 |
| ATM9 修改后 | 最大的旧 `GeneralizedSuffixTree` 实例 | 97.24 |
| ATM9 修改后 | 另一棵较大的 suffix tree | 74.91 |
| ATM9 修改后 | dominator 中的 GTRecipe 对象组 | 373.15 |
| ATM9 修改后 | dominator 中的 ZipFileSystem 对象组 | 231.66 |
| ATM11 JET 关闭 | `RecipeManagerInternal` | 388.50 |
| ATM11 JET 关闭 | `IngredientFilter` | 38.41 |
| ATM11 JET 关闭 | 最大的 `BakedSubstringIndexSearchStorage` | 15.91 |
| ATM11 JET 关闭 | dominator 中的 CTM 模型对象组 | 366.60 |

旧版搜索结构是一个可继续缩减的明确目标。26.1.2 的整个过滤器已较小，配方反查表更值得优先研究：其配方管理器支配的数据中包含约 37.2 万个 `Object2IntOpenHashMap` 及对应数组。

两个已分析快照均无 `TooltipSearchBackend` 实例，与本次关闭实验性 tooltip 索引的设置一致。仅按 JET 包名前缀计算对象浅大小只有数 KiB，但 JET 可以通过 JEI 和集合类持有大量数据，这个数不能证明 JET 的全部占用只有数 KiB。

ATM9 修改后，存活堆约 4.09 GiB，但 NMT 记录 Java heap committed 8 GiB、总 committed 约 9.76 GiB。进程 working set 的观测峰值约 11.58 GiB。提交堆、类元数据、线程、原生分配和文件映射会使进程内存大于存活堆；NMT 也不能覆盖所有第三方原生和 GPU 分配。

## 新增内存诊断

`diagnostics.memoryDiagnostics` 默认关闭。开启后在 JEI 启动期间每 250 ms 记录整 JVM 的堆使用观测峰值，完成时输出开始／结束使用量、committed／max heap、non-heap，以及 GC 次数和耗时增量。

它不调用显式 GC，不保存世界或 Minecraft 对象；结束、取消和替换启动时停止采样线程。旧 generation 的延迟完成不会停止新 generation 的采样。日志明确标注 `scope=whole-jvm, retainedHeap=notMeasured`，250 ms 之间的尖峰可能漏采。

## 1.21.1 和正确性验证

最终三个目标均构建成功，每个目标 12 组回归入口，共 **36 组通过**。实际 JEI 15.20.0.116、19.27.0.340 的 legacy ABI 检查通过，包含未知字段写入和未知调用拒绝用例。现有批处理测试覆盖 22,140 次变更、嵌套、失败和过期 generation 清理。

1.21.1 的最终开发客户端实际运行成功，日志记录：

```text
JET memory fixture passed: changed=512, before=1686, hidden=1174, restored=1686
JEI startup layout notifications coalesced: generation=2, refreshedGrids=1
JET memory diagnostics: generation=2, outcome=started, ... scope=whole-jvm, retainedHeap=notMeasured
JET benchmark complete: queries=120, generation=2
BUILD SUCCESSFUL
```

这验证了监听器确实启用、隐藏和恢复即时影响过滤结果、最终数量恢复、侧栏完成绘制及 120 次查询。测试插件和采集代理独立放在 `scripts/memory`，未加入生产 source set，运行后已移除开发环境中的测试 JAR。

复杂环境六次运行均完成 120 次查询，单次运行内部重复查询结果稳定。ATM11 修改前后 12 类结果数量一致；ATM9 修改前后总数均为 64,425，`stone` 查询相差 1 项，其他查询数量一致。关闭 JET 与开启 JET 之间也有少量结果数量差异。本次未记录逐项 UID，尚不能解释这些差异，不能将它们作为完整搜索等价证明。

ATM9 的 UtilitiX 和 GeneratorGalore 插件错误在关闭 JET 时同样存在，三种状态均有 5 条插件错误。ATM11 三种状态均有 54 条坏配方及 2 条插件错误，来自存储和蜜蜂配方插件。新增功能未造成 JEI 启动失败或 Mixin 应用失败，但当前整合包不属于零错误环境。开发环境仍有可选依赖缺失和 Log4j 路径警告。

每个复杂运行只观察了一次卸载，不能证明是否存在跨世界泄漏。ATM9 关闭 JET 的那次卸载额外经历了诊断控制器等待并被纠正，故不用于比较卸载耗时。失败的启动和控制器试运行已排除。

## 后续优化顺序

| 优先级 | 方案 | 数据依据和验证要求 |
| --- | --- | --- |
| 已完成 | 修复旧版启动侧栏刷新匹配 | 已定位分配栈、修复并完成三版本验证 |
| 下一步 | 为 JEI 15／19 评估紧凑 substring 存储 | ATM9 过滤器 retained 约 218 MiB。可参考 JEI 新版采用的 [baked-substring-index](https://github.com/mezz/baked-substring-index)，但需覆盖 Unicode、空查询、前缀、隐藏物品和 runtime 增量更新；不能根据两个不同整合包直接承诺节省比例 |
| 下一步 | 缩减配方反查表的对象和小数组数量 | 配方管理器 retained 约 344／389 MiB；应保持 UID、焦点和插件查询语义，先针对具体映射结构验证。已有铁砧配方压缩在 ATM11 中把实例数从 57,722 降到 286，仍有其他配方索引可研究 |
| 待采集 | 同版本后台线程 allocation profile | Spark 参考只采主线程，且 JEI 版本不同；需要在实际高内存发生窗口抓 JFR 或 allocation profile，再确定 22 GiB 中的持有者 |
| 待采集 | 多次世界加载和卸载后的 retained 趋势 | 使用相同世界做至少三轮，比较 full GC 后存活量，并在增长时检查到 GC root 的路径；一次卸载后仍有对象不能单独证明泄漏 |

本次没有添加新的全局字符串缓存、强制 GC、替换原生搜索库或减少已显示的物品。稳定堆优化应以具体持有关系和语义回归为依据继续实施。

## 证据和复测

原始证据位于仓库 `build/memory-study-2026-10-13`，不进入发布 JAR。有效目录：

- `atm9-baseline-r5`、`atm9-jet-r5`、`atm9-fixed-r1`
- `atm11-baseline-r2`、`atm11-jet-r1`、`atm11-fixed-r1`

`summary.json` 汇总数字；每个目录包含 `mods.csv` 的 SHA256、配置、阶段时间、JFR、GC/NMT/直方图、日志和分配栈 CSV。两个已分析快照的 `ready_jei_memory.zip` 是 MAT 对象查询结果。`final-build.log` 和 `dev121-final.log` 保存最终构建及开发运行记录。原始日志和 HPROF 可能包含用户数据，保持本地使用。

最终三个发布 JAR 的结构检查确认诊断类和 legacy Mixin 已包含，采集代理及开发 fixture 均未打包，SHA256 保存于 `release-artifacts.json`。两个整合包的被修改配置和原 JET JAR 已按备份哈希核对恢复，临时世界副本已清理。

复测脚本需要已安装的对应整合包、关闭的 Prism、对应游戏 JDK（包含 jcmd）、一个 JDK 21 编译代理，以及实例目录之外的世界模板副本。它会临时备份／恢复 instance.cfg、options.txt、JET 配置、内存提示配置及替换的 JET JAR。世界副本固定为 `JET-Memory-Probe`；再次运行只允许替换带脚本所有权标记的副本。

```powershell
# 替换下列路径；先构建对应的 JET 发布 JAR。
.\scripts\test-jei-memory.ps1 -Pack atm9 -Profile on -Label fixed-1 `
    -WorldTemplate 'D:\memory-study\template120' `
    -LauncherPath 'D:\PrismLauncher\prismlauncher.exe' `
    -JavaHome 'D:\jdk-17' -AgentJdkHome 'D:\jdk-21' `
    -HeapDump -UseWorkspaceJet -MemoryDiagnostics

# 在 JDK 25 上读取所有测试版本的 JFR。
java scripts/AnalyzeJeiMemoryJfr.java build/jei-memory/fixed-1
java scripts/AnalyzeTooltipJfr.java build/jei-memory/fixed-1/recording.jfr

# 构建 1.21.1 专用验证插件，不会装入发布 JAR。
.\gradlew.bat :1.21.1-neoforge:memoryFixtureJar -I scripts/memory/fixture.init.gradle
```

运行 1.21.1 fixture 时，将 `build/memory-fixture/jet-memory-fixture.jar` 临时复制到开发 run/mods，启用 `memoryDiagnostics`，准备独立开发世界后使用 `-PcompatTest.benchmark=true` 运行客户端。测试完成后移除 fixture。复测脚本的参数化版本已完成语法检查和代理重建验证；表中实际运行使用同一控制流程的本地路径版本。
