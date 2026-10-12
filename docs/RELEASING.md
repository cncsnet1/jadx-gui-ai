# 发布指南

版本：`2.0.0-beta.2`。发布包使用同一份 GUI shadow JAR（包含 CLI），分别附带 Windows x64、macOS x64 / ARM64、Linux x64 / ARM64 的 Eclipse Temurin 21 JRE。

## 本地构建

在仓库根目录运行，需要 JDK 17+（流水线使用 JDK 21）、Python 3.11+、curl。Windows 原生启动器由仓库现有 Launch4j 任务生成。

```powershell
$env:JADX_VERSION = '2.0.0-beta.2'
.\gradlew.bat -PbundleJRE :jadx-plugins:jadx-java-deobf:test :jadx-gui:test :jadx-gui:shadowJar :jadx-gui:createExe
python -m unittest discover -s scripts/release -p "test_*.py"
python scripts/release/build_release.py --version $env:JADX_VERSION
python scripts/release/verify_release.py build/releases/2.0.0-beta.2
```

构建脚本使用 `scripts/release/runtimes.json` 中锁定的官方下载地址、文件大小与 SHA-256；缓存位于 `.gradle/release-runtimes`。不会自动升级运行环境，也不会关闭 TLS 验证。需要更新 Java 时，应先更新锁定文件并重新验证所有平台。

输出位于 `build/releases/2.0.0-beta.2/`，包含六个平台附件、校验和、构建清单与发布说明。`--platform` 可重复指定，以仅构建某些平台，例如：

```bash
export JADX_VERSION=2.0.0-beta.2
./gradlew :jadx-gui:test :jadx-gui:shadowJar
python scripts/release/build_release.py --version "$JADX_VERSION" --platform linux-x64 --platform portable
```

只有 Windows 包需要预先生成 `createExe`；其他目标不需要 EXE。跨平台包装直接复制运行环境归档中的文件和元数据，保留 Unix 执行位与符号链接，不会尝试在当前主机上执行其他系统的程序。

## 验证范围

`verify_release.py` 检查 SHA-256、包内 JAR 一致性、目标运行环境的二进制架构、启动入口、Unix 权限、许可证及 macOS bundle 元数据。Windows 上还检查原生 EXE、CLI 启动与附带 Java 的实际运行情况。其他平台需在对应系统上继续执行下列检查，归档校验不能替代实机启动。

```bash
# Linux 包
./bin/jadx --version
./bin/jadx-gui-ai

# macOS 包
./bin/jadx --version
open "JADX GUI AI.app"
```

GUI 检查至少覆盖：打开演示类或 APK、配置连接、预览上下文、分析与停止、调查台保存复核、保存并重开项目。不要使用真实密钥或私有源码制作发布截图。

## 上传 GitHub

将源码改动推送到自己的仓库，创建版本标签后，可使用 `.github/workflows/release.yml` 自动完成测试、构建、打包、校验并生成 **草稿 Release**。手动触发工作流只上传 Actions 构建附件。标签必须尚未创建 Release；同一版本的后续构建可手动触发，避免重复创建发布。

```bash
git tag v2.0.0-beta.2
git push origin v2.0.0-beta.2
```

这些命令会触发远程流水线，按实际准备情况执行。本地打包不会替你提交、推送、创建标签或发布 GitHub Release。

也可以手动创建 GitHub Release，将 `build/releases/2.0.0-beta.2/` 内全部文件作为附件上传，并把 `RELEASE-NOTES.md` 内容作为发布说明。预发布版本请勾选 Pre-release，确认草稿后再公开。

## 发布包说明

- `windows-x64.zip`：原生 EXE、备用 BAT、CLI、JAR、JRE 与文档。
- `macos-*.zip`：`.app`、CLI、JRE 与文档，未签名且未公证。
- `linux-*.tar.gz`：GUI / CLI 启动脚本、JAR、JRE 与文档，面向 glibc 图形桌面环境。
- `portable.zip`：GUI / CLI 启动脚本与 JAR，需自备 64 位 Java 17+。
- `release-manifest.json`：平台、大小、摘要、来源提交、构建时间及工作区是否有未提交改动。
- `SHA256SUMS.txt`：附件与清单的 SHA-256。Windows 可用 `Get-FileHash -Algorithm SHA256`，Linux 可用 `sha256sum -c SHA256SUMS.txt`。

应用许可证和第三方声明保留在 `LICENSE` 与 `NOTICE`，Temurin 自带的 `legal`、许可证及 release 元数据随 JRE 一起保留。macOS 图标由仓库原有 JADX 图标转换为 ICNS。
