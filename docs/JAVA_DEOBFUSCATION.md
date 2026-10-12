# Java 自动反混淆

Java Deobfuscation 插件在 JAR / CLASS 进入 JADX 前调用本地 [Java Deobfuscator](https://github.com/java-deobfuscator/deobfuscator) 引擎。配置一次后，打开文件时自动检测、选择规则并处理，随后使用现有 Java Input 或 Java Convert 反编译。

这是反混淆引擎的集成，不是“任意版本一键恢复源码”。当前重点是 ZKM（Zelix KlassMaster）、Allatori、Stringer。尚未确认 AFT 对应的具体产品，因此没有用这个名称声明支持。此插件从 v2.0.0-beta.2 起随应用发布；外部引擎需要另行配置，未随应用打包。

## 在 GUI 中使用

1. 从 [Java Deobfuscator 官方发布页](https://github.com/java-deobfuscator/deobfuscator/releases) 获取引擎 JAR，放在固定的本地目录。应用不会自动下载或更新引擎。
2. 打开 JADX 设置 → 插件 → **Java Deobfuscation**。
3. 将 `java-deobf.engine` 对应的引擎路径设为该 JAR 的完整路径，模式保留 `auto`。
4. 如目标依赖其他库，填写 `java-deobf.classpath`。Windows 用 `;` 分隔，其他系统用 `:` 分隔。引擎支持依赖 JAR 或包含 JAR 的目录；这些依赖只提供给引擎，不会作为新的项目输入。
5. 保存设置并重新打开项目。插件菜单中的 **Java Deobfuscation Report / Java 反混淆报告** 展示检测输出、采用的规则、处理结果和失败原因，可复制保存。

未配置引擎时，保持普通反编译行为。模式默认为 `auto`，但只有设置引擎路径后才会运行。处理期间不会调用 AI 服务。

模式、依赖路径和自定义规则随 `.jadx` 项目保存；引擎路径、Java 路径、超时与内存设置全局共享，避免一个项目的手动规则影响其他项目。

## 模式

| 模式 | 行为 |
| --- | --- |
| `off` | 关闭预处理 |
| `auto` | 运行引擎检测器，从每组推荐项中选择第一项；只自动采用插件已列出的 ZKM / Allatori / Stringer 规则 |
| `detect` | 仅检测并显示报告，反编译原始输入 |
| `zkm` | 手动使用 ZKM 字符串、反射、控制流规则，按该顺序执行 |
| `allatori` | 手动使用 Allatori 字符串和控制流规则 |
| `stringer` | 手动使用传统 Stringer 字符串、访问隐藏、invokedynamic 规则；较新版本宜先检测 |
| `custom` | 按 `java-deobf.transformers` 中逗号分隔的顺序执行引擎规则 |

检测器给出的多个推荐项可能是替代方案。自动模式不会把它们全部执行，也不会在检测到多个混淆器家族时猜测执行顺序。ZKM 反射规则需要先处理字符串；没有匹配的字符串规则时会保留原输入并提示使用自定义模式。自动模式不会额外强加没有被检测器推荐的控制流规则。

自定义示例：

```text
zelix.StringEncryptionTransformer,zelix.ReflectionObfuscationTransformer,zelix.FlowObfuscationTransformer
```

自定义模式允许使用引擎自带的其他规则，仍受输出检查限制，不接受任意 YAML、命令行参数或脚本。需要特殊规则参数或类重命名/删除的处理，可先在外部引擎完成，再用 JADX 打开结果。

## 命令行

```powershell
bin\jadx.bat '-Pjava-deobf.engine=C:\Tools\deobfuscator.jar' -Pjava-deobf.mode=auto app.jar
```

```bash
bin/jadx -Pjava-deobf.engine=/opt/tools/deobfuscator.jar -Pjava-deobf.mode=detect app.jar
```

CLI 通过日志显示采用的规则和回退原因，完整检测报告可在 GUI 查看。

可选设置：

| 参数 | 默认值 | 用途 |
| --- | --- | --- |
| `java-deobf.java` | 当前 Java 运行环境 | 引擎使用的 Java 可执行文件完整路径，可为旧引擎单独指定兼容运行环境 |
| `java-deobf.timeout` | 120 秒 | 每个检测或处理进程的超时，范围 1–3600 |
| `java-deobf.memory` | 1024 MiB | 引擎最大 Java 堆，范围 128–8192；不是整个进程的内存硬上限 |

## 结果如何处理

原文件先复制到独立临时目录。引擎通过独立 Java 进程运行，参数直接传递，不经 shell。只有进程正常退出、输出可以读取、类文件可以解析，并且原有类均保留时，临时结果才会交给反编译器。关闭项目后清理临时目录。

引擎缺失、缺少依赖、超时、退出失败、未知规则、输出损坏或丢失类时，报告标记 `FALLBACK`，继续读取该文件的原始内容；其他输入仍可继续处理。取消处理会中止载入。输出类字节未变化时标记 `UNCHANGED`；未推荐受支持规则时标记 `NO SUPPORTED RULE`，并不意味着文件没有混淆。

`APPLIED` 表示经过结构检查的临时类已被采用，**不证明语义完全等价，也不证明混淆已全部解除**。代码缓存包含本次输出的类内容摘要；调用关系使用独立内存缓存，避免复用原始代码的磁盘索引。项目路径、资源和原文件保持原样。因此资源解密不在本功能范围内，导出资源仍来自原始输入，也不提供可运行 JAR 重打包。

## 支持边界

- 首版只处理直接打开的普通 JAR 和独立 CLASS。APK、DEX、嵌套 JAR、Spring Boot / WAR 布局、多版本 JAR、重复或非标准类路径不会交给引擎，请直接打开其中的目标 JAR / CLASS。独立 CLASS 缺少关联类时可能需要提供依赖，优先使用完整 JAR。
- 类文件结构检查使用 ASM 9.7.1。单类最大 16 MiB、每个归档类数据总量最大 256 MiB、归档最多 100,000 项。引擎输出日志最多保留 256 KiB，超过后回退，避免从截断检测结果生成规则。
- 自动规则由明确的类名清单管理。扩展新混淆器时，应补充检测输出、规则顺序、有效样本及失败样本测试，不能仅凭混淆器名称添加通用“成功”标签。
- ZKM 的不同版本、字符串加密强度、方法参数变换、多层混淆会影响效果。引擎的 [Zelix 兼容性说明](https://github.com/java-deobfuscator/deobfuscator/blob/master/commonerrors/Zelix.md) 明确列出限制；尤其不能据此保证新版 ZKM 完整还原。
- 本插件保守拒绝删除或重命名原有类的输出。某些引擎规则会合法删除解密辅助类，这种情况也会回退，可在外部引擎检查结果后另行打开。
- 原始名称被移除后，通常无法准确恢复。名称可读化继续使用 JADX 原有反混淆能力或映射文件。
- 引擎 JAR 是本地可执行程序，需使用可信来源。独立进程提供超时和堆限制，不是操作系统安全沙箱。

## 开发与验证

`JadxInputPreprocessor` 为插件提供载入前的扩展点，不改变原有输入插件选择。Java Deobfuscation 插件负责规则规划、进程生命周期、临时文件、检查和报告。

```powershell
.\gradlew.bat :jadx-plugins:jadx-java-deobf:test
```

测试包括规则替代项、ZKM 依赖顺序、多家族拒绝、输出损坏和类丢失回退、原文件保留、独立 CLASS、仅检测、进程超时、日志上限及资源来源。可选真实引擎测试使用自行生成的 Allatori 风格 XOR 字符串样本；它不等同于商业混淆器所有版本的兼容性测试，测试本身不会下载引擎。

```powershell
$env:JADX_DEOBF_TEST_ENGINE = 'C:\Tools\deobfuscator.jar'
.\gradlew.bat :jadx-plugins:jadx-java-deobf:test --tests '*RealEngineIntegrationTest'
```

设计参考：[引擎检测与支持列表](https://github.com/java-deobfuscator/deobfuscator)、[配置接口](https://github.com/java-deobfuscator/deobfuscator/blob/master/USAGE.md)、[ZKM 官方混淆选项](https://www.zelix.com/klassmaster/docs/obfuscateOptions.html)。

### 本次验证记录（2026-10-11）

- Windows / Java 17：新增插件 18 项测试通过，其中包含真实外部引擎测试；核心 936 项测试通过；GUI 68 项通过、2 项跳过。新增插件 Checkstyle 与相关格式检查通过。
- 构建出的应用分别使用 Java Input 和 `--use-dx` 处理自建 Allatori 风格样本，均输出 `return "HELLO";`，原始 JAR 未被覆盖。该结果不代表商业 ZKM / Allatori / Stringer 各版本均已实测。
- 本地 `installShadowDist` 构建成功。Google Maven 在本机存在 TLS 连接问题，验证时通过临时 Gradle 初始化脚本使用本机已缓存的同版本依赖，并为测试源码指定 UTF-8；未改变仓库的依赖版本和仓库配置。
- CLI 原有测试 17 项通过，`TestInput.testClassInput` 因仓库缺少 `samples/HelloWorld.class` 失败，不能据此宣称全仓测试全部通过。
- 实测引擎来自官方 `latest` 发布资产，其 SHA-256 为 `c1835bd9c9aad4cc453f8675110fc057f11cd3ca5e8f29b68c67baf086277eea`；引擎未随应用打包。
