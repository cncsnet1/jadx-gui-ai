<p align="center">
  <img src="docs/images/readme-hero.svg" alt="JADX GUI AI — 让每一次逆向分析，都有据可循" width="100%">
</p>

<p align="center">
  <strong>打开应用，告诉 AI 你想了解什么，让每条结论都有据可查。</strong><br>
  Goal-driven app analysis, with evidence you can inspect.
</p>

<p align="center">
  <a href="https://github.com/cncsnet1/jadx-gui-ai/releases"><img src="https://img.shields.io/badge/preview-v2.0.0--beta.4-38b2ac?style=flat-square" alt="v2.0.0-beta.4"></a>
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

不懂逆向、不知道该搜哪个类，也可以先从“这个应用里有什么”“登录怎么实现”“有哪些接口和敏感信息线索”开始。

**v2.0.0-beta.4 新增面向渗透路径探索的应用调查工作区**：先梳理 API、入口和有用材料，再由 AI 读取相关代码、比较值得先尝试的入口，解释已有依据与待追踪环节。可以导出线索包，到其他工具继续调查；需要评估问题时再记录人工核查。未连接模型也能使用本地发现、API 清单与路线指南。原有源码助手、AI 搜索、人工复核与 MCP 继续保留。

> 下载 **v2.0.0-beta.4** 即可使用这套流程。本地发现无需配置模型；AI 深入调查使用你配置的连接。分析基于有限静态证据，不代表已确认漏洞或攻击成功率。

<table>
  <tr>
    <td width="33%" valign="top"><b>01 / 找到起点</b><br><br>默认寻找渗透路径，整理 API、应用入口、身份流程和有用材料。</td>
    <td width="33%" valign="top"><b>02 / 自动追查</b><br><br>AI 选择已有线索，读取关联方法与调用者，解释依据和未知条件。</td>
    <td width="33%" valign="top"><b>03 / 继续调查</b><br><br>导出线索包带到下一工具，或切换核查视图记录验证结果。</td>
  </tr>
</table>

## 看见完整工作流

### 从调查路径开始

加载后默认选择“寻找渗透路径”。扫描完成后收起设置，展示敏感材料、API 声明、观察到的主机和入口线索；下方按调查方向组织起点。选中一条，即可查看建议追踪顺序、已有证据、未知条件与下一阶段材料。可以只找方向，无需先评级或提交漏洞结论。

![找路径：调查起点、建议路线与下一阶段材料](docs/images/exploration-graphite.png)

[查看浅色主题](docs/images/exploration-light.png)。以上为应用实际 Swing 组件的渲染快照，使用演示代码与本机模拟模型连接。路线指南表示搜索顺序，尚不是已还原的调用链或可利用攻击链；“做核查”保留独立的人工验证记录。完整流程见[应用调查说明](docs/ASSESSMENT_WORKFLOW.md)。

### 先从哪里入手

点击 **“AI 筛选优先入口”**，把已读线索整理成“优先尝试 / 先补证据 / 条件较多”卡片。每张卡说明进入条件、验证成本、已有防护、可能影响、排序理由和下一步，并能跳回引用源码。纯地址、抽象声明、未知条件或截断代码不会被直接推荐为优先尝试；排序是 AI 对本轮证据的推断，不代表攻击成功率。

![优先尝试：条件、依据与下一步](docs/images/opportunity-graphite.png)

[浅色主题](docs/images/opportunity-light.png)。上述演示使用本机模拟模型验证交互，真实推理质量取决于连接的模型和提供的代码范围。

### 自动梳理 API

“API 清单”把 **接口声明、地址线索、请求构造入口** 分开呈现，可搜索域名、路径、请求方法、参数和源码位置。Retrofit 声明可提取 Path / Query / Header / Body、表单与上传字段；OkHttp 和 HttpURLConnection 提供待追踪的构造入口。支持源码跳转、AI 继续追踪、随项目保存及脱敏 JSON / Markdown 导出。

相对路径尚未关联基础地址时会明确标记；未发现认证头也不会判定为匿名接口。完整覆盖范围及后续安全检查设计见 [API 清单说明](docs/API_INVENTORY.md)。

![API 清单：声明、参数、认证线索和来源](docs/images/api-inventory-graphite.png)

[查看浅色界面](docs/images/api-inventory-light.png)。截图由实际应用渲染，数据来自专门编译的演示接口；AI 交互使用本机模拟服务。

### 一个重新组织的分析空间

左侧导航连接开始页、工作区和代码助手；顶部 `Ctrl / ⌘ + K` 命令面板可以快速找到菜单功能。专注模式临时收起两侧面板，再次点击即可恢复布局。开始页、编辑器和 AI 侧栏使用统一的石墨灰与青绿色风格，同时提供浅色主题。

“开始”页支持整页拖入文件。加载成功后默认进入 **应用调查**，可一键开始调查；左侧 **源码** 随时进入文件树和编辑器。打开文件本身不会调用模型，执行分析前可以关闭“自动用 AI 深入分析”，仅在本机收集线索。以下旧版截图展示 beta.3 的文件入口；新流程说明见[使用手册](docs/AI_WORKBENCH.md#面向目标的自动分析)。

![全新工作空间首页](docs/images/workspace-home-graphite.png)

![加载成功后自动进入工作区](docs/images/workspace-ready-graphite.png)

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

> **同时提供双向 MCP。** 外部 AI 可以查询当前 JADX 项目的类、源码与引用；内置 AI 也可以连接 stdio / HTTP MCP 工具，逐次确认调用。入口为「工具 → MCP 连接中心」，详见 [MCP 使用说明](docs/MCP.md)。

前往 [GitHub Releases](https://github.com/cncsnet1/jadx-gui-ai/releases) 下载 **v2.0.0-beta.4** 对应的平台包。前五种包均内置 **Eclipse Temurin 21**，无需另装 Java；AI 模型需自行连接。

| 你的环境 | 发布包 | 启动入口 |
| --- | --- | --- |
| Windows x64 | `jadx-gui-ai-2.0.0-beta.4-windows-x64.zip` | 解压后双击 `jadx-gui-ai.exe` |
| macOS · Apple Silicon | `jadx-gui-ai-2.0.0-beta.4-macos-arm64.zip` | 解压后打开 `JADX GUI AI.app` |
| macOS · Intel | `jadx-gui-ai-2.0.0-beta.4-macos-x64.zip` | 解压后打开 `JADX GUI AI.app` |
| Linux x64 | `jadx-gui-ai-2.0.0-beta.4-linux-x64.tar.gz` | `./bin/jadx-gui-ai` |
| Linux ARM64 | `jadx-gui-ai-2.0.0-beta.4-linux-arm64.tar.gz` | `./bin/jadx-gui-ai` |
| 自备 Java · 通用包 | `jadx-gui-ai-2.0.0-beta.4-portable.zip` | `bin/jadx-gui-ai` 或 `bin\jadx-gui-ai.bat` |

通用包使用 64 位 Java 17+，推荐 Java 21。Linux 桌面包面向 glibc 环境，需要图形桌面；macOS 包尚未签名、公证，首次打开请按系统提示操作。各平台的实际验证范围见 [发布说明](docs/releases/v2.0.0-beta.4.md)。

包内同时保留 JADX CLI：`bin/jadx`，Windows 使用 `bin\jadx.bat`。发布附件提供 `SHA256SUMS.txt` 和 `release-manifest.json`，用于核对下载内容与内置运行环境。

## 三分钟开始第一次分析

以下流程随 v2.0.0-beta.4 发布；建议将新包完整解压到独立目录后启动。

1. **打开文件**：拖入 APK、DEX、JAR 或 CLASS，加载后进入应用调查。
2. **选择目标**：默认“寻找渗透路径”，也可选择“寻找敏感信息”“整理接口和请求”“核查潜在问题”等目标、补充具体问题，然后点“帮我分析这个应用”。
3. **先看本地发现**：无需模型即可查看疑似敏感常量、地址、接口声明、配置与行为线索；结果按类别整理并保留证据位置。
4. **让 AI 深入**：配置一次兼容 Chat Completions 的连接，之后可以自动挑选线索、读取定义和调用者；“AI 筛选优先入口”按条件、防护与成本整理值得先尝试的候选。项目分析复用“审计”任务连接；请求失败不会改用其他服务。
5. **继续追踪与保存**：选中线索让 AI 追查，沿引用核对代码；导出线索包，到抓包、设备或后端测试环境继续调查。也可切换“做核查”记录结果，保存 `.jadx` 项目或导出报告。

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
| 目标分析 | 本地字节码/资源扫描、去重与分类；AI 选择最多 6 条线索，自动读取定义和调用者；可停止、复用同文件扫描、保存和导出 |
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
