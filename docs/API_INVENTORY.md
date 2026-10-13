# API 清单与安全分析设计

> v2.1.0：入口为「应用工作台 → 接口与通信」，默认突出有请求依据的 API，背景候选可恢复。提供「实际流量」与 Burp 历史关联、证据保存、Repeater 转交，schemaVersion 4 导出保留原值；下面的调用路径与客户端分析范围继续适用。详见 [Burp 联动说明](BURP_WORKFLOW.md)。

> **v2.0.0 正式版：** 入口改为「应用调查 → API 地图」，新增无需模型的「一键梳理接口」。对 Retrofit 声明按需解析最多 12 个使用方顶层类、每类最多 80 个方法，单类源码上限 200,000 字符，最多保留 8 条独立客户端关系与 16 份来源。支持直线 Retrofit / OkHttp Builder 构造与局部常量，区分多个客户端和 build 时的配置快照；动态地址、分支、未知辅助函数、匿名拦截器等明确列为未解关系。JSON 导出升级为 schemaVersion 3，并保留原有 endpoints 字段，新增 clientRelations。以下旧清单说明继续适用于基本提取。


v2.0.0 已加入内置「调用路径」视图：从接口声明或网络请求构造方法向上追查业务调用者，点击节点可查看调用依据或打开精确源码。每条连线都核对原始字节码中的调用目标，保留指令类型与偏移；仅有索引引用、反射或延迟方法引用时不会推断为直接调用。不同重载使用完整原始签名区分，循环与多分支共用方法保留连线但不重复展开。

每次最多向上 4 层、48 个方法、96 条关系；每个方法最多核对 32 个索引候选，最多读取 128 份方法字节码、累计 1,000,000 个输入单元，单方法 200,000 单元 / 256 个调用目标。路径阶段设置 15 秒预算，在指令访问与候选之间检查；输入插件单次读取并非硬实时中断。未展开节点保留具体原因；“当前索引没有调用者”不代表应用入口或没有实际调用。反射、native、动态分派和运行条件仍需额外证据。

调用路径与客户端关系一起保存到 `.jadx`，进入 JSON 的 `clientRelations[].callPath` 和 Markdown 报告。AI 的 `inspect_api` 使用同一内置能力，把找到的方法注册到调查目录，后续可继续读取源码。基础追查无需模型、REA 或其他运行环境。


适用范围：v2.1.0 正式版；基础清单能力从 beta.4 开始提供。研究与实现核对日期：2026-10-13。

## 使用方式

打开应用后自动生成本地报告，随后进入“API 地图”。无需连接模型即可提取、搜索和导出；“让 AI 追踪这个请求”会沿用审计连接，读取该条目及关联源码。

表格显示证据类型、HTTP 方法、路径或地址、主机。详情显示参数所在位置与声明类型、请求体声明、认证线索和来源。文本搜索支持域名、路径、方法、参数及源码位置；类型筛选区分下列三种结果。

| 结果类型 | 当前提取范围 | 如何理解 |
| --- | --- | --- |
| 接口声明 | Retrofit 1 / 2 的 GET、POST、PUT、PATCH、DELETE、HEAD、OPTIONS、HTTP；含自定义 HTTP 方法和动态 Url 参数 | 注解明确声明了请求结构，但不证明代码被执行或服务可达 |
| 地址线索 | 字节码常量和支持的文本资源中的 HTTP(S) URL；保留主机、路径与查询参数名称 | 也可能来自文档、SDK 或废弃代码，HTTP 方法未知 |
| 请求构造入口 | 对 OkHttp 2 / 3 Request.Builder、HttpURLConnection、HttpsURLConnection 方法的调用 | 提供源码追踪入口，尚未还原变量、分支或最终请求 |

Retrofit 参数支持 Path、Query、QueryName、QueryMap、Field、FieldMap、Part、PartMap、Header、HeaderMap、Body 和 Url。请求体记录 FormUrlEncoded、Multipart 或 Body 的声明类型；不会根据 Java 类型名虚构 JSON 字段。静态 Headers 保留名称，并可识别 Authorization 中明确声明的 Bearer / Basic 形式；参数值、URL 用户信息、片段和固定头值不进入清单。

认证线索只表示出现了相关请求头或参数，不能证明凭据有效或服务端已校验。未发现认证声明时显示未知，因为拦截器、Cookie 和会话代码可能补充信息。相对路径不与全项目任意域名拼接；基础地址关联、动态字符串拼接和运行时实际参数仍待追踪。

## 保存、导出与性能

- API 清单随 `.jadx` 项目保存，也包含在 Markdown 分析报告中；独立导出为 JSON，含 schemaVersion、静态观察范围及原值保留标记。导出不受当前筛选影响。
- 清单来自本地结构化提取，模型输出不会改写为“已确定”的 HTTP 参数。AI 针对网络目标优先选择接口声明，并沿用最多 60 项候选、6 项选择、16 份证据和 36,000 字符预算。
- 地址线索最多 750 项，接口声明最多 750 项，请求构造入口最多 250 项，分开预算，避免大量网址挤掉接口声明。达到上限会记录覆盖缺口。每项最多保存 80 条参数信息、5 个来源位置，地址最多 2,000 字符，长信息会标记截断。
- 相同原始地址及参数身份可聚合来源；不同方法或不同源码方法上的接口声明仍独立保留。条目数量不是漏洞数量。
- 规则版本已更新，旧版本完整扫描会在下一次主动分析时重建；打开历史项目不会自动联网。
- 不涵盖被重命名或移除的 Retrofit 注解、自定义封装的全程序数据流、原生网络代码、运行时解密地址或真实响应结构。缺失信息会保留未知状态。

## 研究依据与后续优先级

以下属于设计方向，除上文列明的功能外尚未实现自动验证。

1. **把请求关联到实际构造过程。** 后续需要追踪 Retrofit.Builder 的 baseUrl / create、OkHttp 请求对象与拦截器的绑定关系。只有关联明确时再组合基础地址与路径，无法确认时保留独立线索。Retrofit 的方法、参数和头均通过注解声明，统一请求头还可由拦截器添加。[Retrofit 声明文档](https://github.com/lysine-dev/retrofit/blob/trunk/website/src/content/docs/declarations.md)、[HTTP 注解定义](https://raw.githubusercontent.com/square/retrofit/trunk/retrofit/src/main/java/retrofit2/http/HTTP.java)、[OkHttp Request 实现](https://raw.githubusercontent.com/square/okhttp/master/okhttp/src/commonJvmAndroid/kotlin/okhttp3/Request.kt)
2. **将网络配置关联到主机。** 提取 Manifest 的配置引用、域名规则、明文流量设置及调试信任配置，结合适用范围解释影响。当前普通 URL 扫描不能代替这些检查。[OWASP 网络信息收集](https://mas.owasp.org/MASTG/techniques/android/MASTG-TECH-0022/)、[Android 网络安全配置](https://mas.owasp.org/MASTG/knowledge/android/MASVS-NETWORK/MASTG-KNOW-0014/)
3. **梳理组件访问控制。** 将组件导出状态、权限、入口参数和敏感操作关联，区分“可进入”和“可执行敏感操作”。当前的导出属性线索不能证明授权缺陷。[OWASP 组件认证与授权](https://mas.owasp.org/MASWE/MASVS-AUTH/MASWE-0018/)
4. **把服务端风险转换为验证任务。** 客户端的对象 ID、会话和角色线索用于说明需要核对什么；越权结论需要服务端授权行为的证据。v2.0.0-beta.4 已加入[核查队列与人工记录](ASSESSMENT_WORKFLOW.md)，区分静态发现、待运行验证和人工复现；自动接入测试环境中的请求记录与行为验证仍待实现。[OWASP API1 对象级授权](https://api-security.owasp.org/editions/2023/en/0xa1-broken-object-level-authorization/)

面向新手的结果应始终说明：发现了什么、依据在哪里、尚未确定什么、下一步如何取得证据。潜在影响和证据可信程度分别展示；扫描结果不能直接充当完整渗透测试报告。
