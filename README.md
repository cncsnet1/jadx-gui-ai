<p align="center">
  <img src="docs/images/readme-hero.svg" alt="JADX GUI AI — 让每一次逆向分析，都有据可循" width="100%">
</p>

<p align="center">
  <strong>把反编译、AI 分析和证据复核，放进同一个工作空间。</strong><br>
  A source-first reverse engineering workbench, with AI you can inspect.
</p>

<p align="center">
  <a href="https://github.com/cncsnet1/jadx-gui-ai/releases"><img src="https://img.shields.io/badge/preview-v2.0.0--beta.2-38b2ac?style=flat-square" alt="v2.0.0-beta.2"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache--2.0-64748b?style=flat-square" alt="Apache-2.0"></a>
  <img src="https://img.shields.io/badge/desktop-Windows%20%7C%20macOS%20%7C%20Linux-6085cc?style=flat-square" alt="Windows, macOS, Linux">
  <img src="https://img.shields.io/badge/AI-cloud%20%2B%20local-8b72cf?style=flat-square" alt="Cloud and local models">
</p>

<p align="center">
  <a href="#下载与启动"><b>下载与启动</b></a> ·
  <a href="#看见完整工作流"><b>界面预览</b></a> ·
  <a href="#三分钟开始第一次分析"><b>快速上手</b></a> ·
  <a href="docs/AI_WORKBENCH.md"><b>使用手册</b></a> ·
  <a href="https://github.com/cncsnet1/jadx-gui-ai/issues"><b>反馈建议</b></a>
</p>

---

你找到一个可疑方法，接下来通常还要追调用、补上下文、判断模型有没有看错，再把有用的结论记下来。

**JADX GUI AI 把这条分析路径接进了 JADX。** 光标所在的方法就是起点，发送的上下文可以预览，回答中的引用可以回到源码，重要线索可以逐条复核、随项目保存。云端与本地模型可以并存，也可以按解释、追踪、审计、命名分别分配。

<table>
  <tr>
    <td width="33%" valign="top"><b>01 / 从源码出发</b><br><br>方法、选区、调用者。只把明确选定的上下文交给模型。</td>
    <td width="33%" valign="top"><b>02 / 让依据可见</b><br><br>看清发送内容，追溯引用片段，区分观察、假设和问题。</td>
    <td width="33%" valign="top"><b>03 / 留下判断过程</b><br><br>记录人工复核与反证，把一次回答变成可以回看的调查记录。</td>
  </tr>
</table>

## 看见完整工作流

### 一个重新组织的分析空间

左侧导航连接首页、项目和代码助手；顶部 `Ctrl / ⌘ + K` 命令面板可以快速找到菜单功能。专注模式临时收起两侧面板，再次点击即可恢复布局。首页、编辑器和 AI 侧栏使用统一的石墨灰与青绿色风格，同时提供浅色主题。

![全新工作空间首页](docs/images/workspace-home-graphite.png)

### 用自然语言找到代码入口

新版 AI 搜索提供包范围、模型连接、批次进度、匹配理由与源码跳转。搜索前即可看到发送范围，支持取消与本地结果筛选。类名匹配用于发现线索，实际行为仍需核对源码。

![AI 搜索：石墨灰与青绿色的语义搜索工作区](docs/images/ai-search-graphite.png)

界面提供 **Jadx Graphite / Jadx Light** 两套主题。已有用户可在“首选项 → 外观”中切换，详见[使用手册](docs/AI_WORKBENCH.md)。上图为应用自身渲染的组件快照，数据来自演示代码与本机测试服务。

### 代码在左，问题和线索在右

源码工作台跟随当前方法或选区。选择任务后即可分析，也可以继续追问；引用编号支持跳转，注释与命名建议先预览再应用。

![源码工作台：围绕当前代码分析、追问和追溯证据](docs/images/workbench-graphite.png)

> 以下界面均来自实际程序，使用演示代码与本地模拟服务；展示的是交互流程，不代表某个模型的真实推理效果。首屏头图为功能示意。

### 多套模型连接，一次配置，各司其职

将常用云端、本地服务或兼容网关保存为独立连接。可以读取模型列表、测试流式响应、使用环境变量提供密钥，并为不同分析任务指定连接。

![AI 连接中心：独立连接、模型选择与响应诊断](docs/images/ai-connections-v2.png)

**例如：** 本地模型负责快速解释和命名，指定云端模型负责审计；工作台中仍可临时切换。请求失败时会显示错误，不会自动把代码转发到其他服务。

### 发出去之前，先看清楚

“上下文”窗口展示本次源码快照、关联调用者、截断标识和近期对话。“发送这份快照”使用当前预览的同一份消息。

![上下文预检：明确看到将发送给模型的内容](docs/images/ai-context-preview.png)

### 把长回答，变成可复核的调查线索

调查台将模型给出的线索分为 **源码观察 / 待证假设 / 开放问题**。每项都能查看分析依据、缺失条件、可能的反证和建议验证步骤；你的复核状态与笔记独立保存。

![调查台：逐条检查线索、记录判断与反证](docs/images/ai-investigation.png)

<details>
<summary><b>展开查看：如何核对一条源码引用</b></summary>

“源码证据”会在分析时的快照中查找引用片段，并高亮精确匹配的位置。编号不存在或片段未匹配时会明确提示。**文字匹配只证明引用存在，结论是否成立仍需要人工判断。**

![源码证据：核对引用并返回当前代码](docs/images/ai-investigation-evidence.png)

</details>

## 下载与启动

前往 [GitHub Releases](https://github.com/cncsnet1/jadx-gui-ai/releases) 下载 **v2.0.0-beta.2** 对应的平台包。前五种包均内置 **Eclipse Temurin 21**，无需另装 Java；AI 模型需自行连接。

| 你的环境 | 发布包 | 启动入口 |
| --- | --- | --- |
| Windows x64 | `jadx-gui-ai-2.0.0-beta.2-windows-x64.zip` | 解压后双击 `jadx-gui-ai.exe` |
| macOS · Apple Silicon | `jadx-gui-ai-2.0.0-beta.2-macos-arm64.zip` | 解压后打开 `JADX GUI AI.app` |
| macOS · Intel | `jadx-gui-ai-2.0.0-beta.2-macos-x64.zip` | 解压后打开 `JADX GUI AI.app` |
| Linux x64 | `jadx-gui-ai-2.0.0-beta.2-linux-x64.tar.gz` | `./bin/jadx-gui-ai` |
| Linux ARM64 | `jadx-gui-ai-2.0.0-beta.2-linux-arm64.tar.gz` | `./bin/jadx-gui-ai` |
| 自备 Java · 通用包 | `jadx-gui-ai-2.0.0-beta.2-portable.zip` | `bin/jadx-gui-ai` 或 `bin\jadx-gui-ai.bat` |

通用包使用 64 位 Java 17+，推荐 Java 21。Linux 桌面包面向 glibc 环境，需要图形桌面；macOS 包尚未签名、公证，首次打开请按系统提示操作。各平台的实际验证范围见 [发布说明](docs/releases/v2.0.0-beta.2.md)。

包内同时保留 JADX CLI：`bin/jadx`，Windows 使用 `bin\jadx.bat`。发布附件提供 `SHA256SUMS.txt` 和 `release-manifest.json`，用于核对下载内容与内置运行环境。

## 三分钟开始第一次分析

1. **打开文件**：载入 APK、DEX、JAR 或 CLASS，找到你关心的方法。
2. **连接模型**：打开“AI 工作台 → AI 设置”，填写兼容 Chat Completions 的服务地址、模型 ID 和认证信息。点击“测试模型响应”，成功后保存。
3. **选定上下文**：把光标放在方法中，或直接选中代码；按需补充最多 3 个调用者。首次使用可先点“上下文”检查消息。
4. **提出问题**：选择解释、追踪、审计或命名，也可以输入具体问题，例如“这个参数从哪里进入，现有代码还缺哪些证据？”
5. **复核与保存**：沿 `[S1]` 等引用返回源码，打开调查台记录判断，再保存 `.jadx` 项目或导出报告。

<details>
<summary><b>连接信息应该怎么填？</b></summary>

| 字段 | 示例与说明 |
| --- | --- |
| 连接名称 | `本地 · 代码理解`、`云端 · 深度审计` |
| API 地址 | 服务提供的基础地址，如 `https://your-gateway.example/v1`；也可填写完整 `/chat/completions` 地址 |
| 模型 | 从服务读取列表，或手动填写服务实际支持的模型 ID |
| 认证 | 填写 API Key，或保存环境变量名；免认证的本地服务可以留空 |
| 任务分配 | 为解释、追踪、审计、命名、AI 搜索指定不同连接；未指定时跟随默认连接 |

“读取模型”需要服务支持 `/models`，不支持时仍可手动填写。厂商原生协议需要通过兼容网关接入。环境变量由应用进程读取，更改系统环境变量后通常需要重启程序。

</details>

## 能力与边界

| 已实现 | 具体行为 |
| --- | --- |
| 工作台布局 | 首页、侧边导航、命令面板、可恢复的专注模式，深浅主题与紧凑侧栏 |
| AI 搜索 | 按类名语义发现入口；包 / 当前类范围、批次进度、匹配说明、取消与本地筛选 |
| 源码上下文 | 当前方法、选区或类；主源码最多 12,000 字符，最多 3 个调用者各 3,000 字符，截断可见 |
| 多连接与任务分配 | 独立保存服务与模型，支持临时切换、环境变量密钥、响应诊断 |
| 可检查的请求 | 预览准确消息；流式输出可停止；切换项目会取消未完成请求 |
| 调查台 | 单次分析最多 8 条结构化线索，引用片段核对，独立人工复核与笔记 |
| 建议应用 | 先打开原生注释或重命名对话框；源码变化后要求重新分析 |
| 项目与报告 | 每项目最多 100 条调查记录；Markdown / HTML 保留模型来源、快照、原始回答与人工复核 |
| JADX 基础能力 | 反编译、资源查看、全文搜索、查找用法、反混淆与原有调试能力 |
| Java 自动预处理 | 配置本地 Java Deobfuscator 后，自动检测并尝试 ZKM / Allatori / Stringer 规则；查看处理报告，失败回退原输入。[配置与支持范围](docs/JAVA_DEOBFUSCATION.md) |

模型不返回可用的结构化线索时，完整回答仍保留为普通文本。当前分析基于反编译源码与静态调用关系，**不等同于完整 APK 数据流分析，也不会自动执行动态验证或证明漏洞成立。**

### 代码与凭据如何流动

只有主动发起分析时，选定的源码、关联调用者及同一上下文下的近期对话才会发送给所选服务。读取模型列表、连接测试不会发送项目源码。切换连接、账号、模型或源码上下文后，旧对话不会自动带入新分析。

连接配置保存在本地设置中，直接填写的 API Key 以明文保存；可以改用环境变量，仅保存变量名。调查记录随 `.jadx` 保存，报告包含源码快照，分享前请检查内容。[查看完整说明 →](docs/AI_WORKBENCH.md)

## 从源码构建

使用 JDK 17+；发布流水线使用 JDK 21。仓库已包含 Gradle Wrapper。

```bash
# macOS / Linux
./gradlew :jadx-gui:test :jadx-gui:installShadowDist
./jadx-gui/build/install/jadx-gui-shadow/bin/jadx-gui
```

```powershell
# Windows PowerShell
.\gradlew.bat :jadx-gui:test :jadx-gui:installShadowDist
.\jadx-gui\build\install\jadx-gui-shadow\bin\jadx-gui.bat
```

多平台打包、运行环境锁定、校验和与 GitHub 发布流程见 [发布指南](docs/RELEASING.md)。

## 接下来

- [x] 源码工作台与流式分析
- [x] 多连接、任务分配与上下文预检
- [x] 证据调查台、人工复核与报告导出
- [x] 工作台布局重构、命令面板、深浅主题与 AI 搜索
- [x] 可选本地 Java 反混淆预处理与失败回退
- [ ] 跨多次分析组织调查目标，追踪证据和结论变化
- [ ] 对同一快照做多模型复核，呈现分歧与缺失条件
- [ ] 将静态疑点转成可预览的动态观察点，关联实际验证结果

欢迎通过 [Issues](https://github.com/cncsnet1/jadx-gui-ai/issues) 提供具体场景、复现步骤或界面建议。代码贡献请先阅读 [贡献指南](CONTRIBUTING.md)。

## 致谢与许可

基于 [skylot/jadx](https://github.com/skylot/jadx) 构建，感谢 JADX 及相关开源依赖的维护者。桌面界面使用 FlatLaf 与 RSyntaxTextArea；内置运行环境来自 [Eclipse Temurin](https://adoptium.net/)。

项目使用 [Apache License 2.0](LICENSE)，第三方声明见 [NOTICE](NOTICE)。发布包保留 Java 运行环境自带的许可证。云麾安全实验室维护 AI 增强功能。
