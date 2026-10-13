"""Create self-contained desktop archives without running foreign-platform binaries.

Python 3.11+, curl and a versioned GUI shadow JAR are required.
Windows archives also require a Windows build host with JDK 17+ (jpackage).
Runtime files are copied directly between archives so Unix modes and symlinks
survive even when the release is assembled on Windows.
"""

import argparse
import datetime
import io
import json
import os
import pathlib
import plistlib
import posixpath
import re
import stat
import subprocess
import tarfile
import zipfile

from download_runtimes import download_all, sha256
from windows_launcher import build_windows_launchers

ROOT = pathlib.Path(__file__).resolve().parents[2]
JVM = ('-Xms128M -XX:MaxRAMPercentage=70.0 -Dfile.encoding=UTF-8 '
       '-Dawt.useSystemAAFontSettings=lcd -Dswing.aatext=true '
       '-Djava.util.Arrays.useLegacyMergeSort=true '
       '-Djdk.util.zip.disableZip64ExtraFieldValidation=true '
       '-XX:+IgnoreUnrecognizedVMOptions --add-opens=java.base/java.lang=ALL-UNNAMED '
       '--enable-native-access=ALL-UNNAMED')


def safe_name(name):
    path = pathlib.PurePosixPath(name)
    if path.is_absolute() or ".." in path.parts or "\\" in name or ":" in name:
        raise ValueError(f"Unsafe archive member: {name}")
    return str(path)


class Archive:
    def __init__(self, path, stamp):
        self.path = path
        self.stamp = stamp
        self.names = set()
        self.zip = zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) if path.suffix == ".zip" else None
        self.tar = None if self.zip else tarfile.open(path, "w:gz", compresslevel=6)

    def add(self, name, stream, size, mode=0o644, link=None):
        name = safe_name(name)
        if name in self.names:
            raise ValueError(f"Duplicate archive member: {name}")
        self.names.add(name)
        if self.zip:
            info = zipfile.ZipInfo(name, datetime.datetime.fromtimestamp(self.stamp, datetime.timezone.utc).timetuple()[:6])
            info.create_system = 3
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = ((stat.S_IFLNK if link is not None else stat.S_IFREG) | mode) << 16
            if link is not None:
                self.zip.writestr(info, link.encode("utf-8"))
            else:
                with self.zip.open(info, "w", force_zip64=True) as target:
                    import shutil
                    shutil.copyfileobj(stream, target, length=1024 * 1024)
        else:
            info = tarfile.TarInfo(name)
            info.mode, info.mtime, info.uid, info.gid = mode, self.stamp, 0, 0
            info.uname = info.gname = "root"
            if link is not None:
                info.type, info.linkname = tarfile.SYMTYPE, link
                self.tar.addfile(info)
            else:
                info.size = size
                self.tar.addfile(info, stream)

    def text(self, name, text, mode=0o644):
        data = text.encode("utf-8") if isinstance(text, str) else text
        self.add(name, io.BytesIO(data), len(data), mode)

    def file(self, name, path, mode=0o644):
        with path.open("rb") as source:
            self.add(name, source, path.stat().st_size, mode)

    def close(self):
        (self.zip or self.tar).close()


def add_runtime(archive, source, prefix):
    def relative(name):
        parts = pathlib.PurePosixPath(safe_name(name)).parts
        return "/".join(parts[1:])

    if source.suffix == ".zip":
        with zipfile.ZipFile(source) as original:
            for entry in original.infolist():
                name = relative(entry.filename)
                if not name or entry.is_dir():
                    continue
                mode = (entry.external_attr >> 16) & 0o777 or 0o644
                with original.open(entry) as stream:
                    archive.add(f"{prefix}/{name}", stream, entry.file_size, mode)
    else:
        with tarfile.open(source, "r:gz") as original:
            for entry in original:
                name = relative(entry.name)
                if not name or entry.isdir():
                    continue
                if entry.issym():
                    target = posixpath.normpath(posixpath.join(posixpath.dirname(name), entry.linkname))
                    if posixpath.isabs(entry.linkname) or target == ".." or target.startswith("../"):
                        raise ValueError(f"Runtime link escapes its root: {entry.name}")
                    archive.add(f"{prefix}/{name}", None, 0, entry.mode, entry.linkname)
                elif entry.isfile():
                    with original.extractfile(entry) as stream:
                        archive.add(f"{prefix}/{name}", stream, entry.size, entry.mode)
                else:
                    raise ValueError(f"Unsupported runtime entry: {entry.name}")


def shell_launcher(java, jar, gui, mac=False, location='"$(dirname "$0")/.."'):
    main = "jadx.gui.JadxGUI" if gui else "jadx.cli.JadxCLI"
    return f'''#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -P {location} && pwd)
{java}
if [ ! -x "$JAVA" ]; then
  printf '%s\\n' 'Java was not found. Install 64-bit Java 17+ or use a bundled release.' >&2
  exit 1
fi
exec "$JAVA" {JVM} {('-Dapple.awt.application.name="JADX GUI AI" -Xdock:name="JADX GUI AI"' if gui and mac else '')} -cp "$ROOT/{jar}" {main} "$@"
'''


def windows_launcher(jar, gui, bundled):
    if bundled:
        executable = "jadx-gui-ai.exe" if gui else "jadx-cli.exe"
        return (f'@echo off\nsetlocal\n"%~dp0..\\{executable}" %*\nexit /b %ERRORLEVEL%\n').replace("\n", "\r\n")
    exe = "javaw.exe" if gui else "java.exe"
    command = f'start "JADX GUI AI" /B "%JAVA_EXE%"' if gui else '"%JAVA_EXE%"'
    resolve = f'''set "JAVA_EXE={exe}"
if defined JAVA_HOME set "JAVA_EXE=%JAVA_HOME%\\bin\\{exe}"'''
    main = "jadx.gui.JadxGUI" if gui else "jadx.cli.JadxCLI"
    return f'''@echo off
setlocal
set "APP_HOME=%~dp0..\\"
{resolve}
{command} {JVM} -cp "%APP_HOME%lib\\{jar}" {main} %*
exit /b %ERRORLEVEL%
'''.replace("\n", "\r\n")


def add_docs(archive, prefix, version, platform, runtime):
    files = [ROOT / name for name in ("README.md", "README.en.md", "LICENSE", "NOTICE", "CONTRIBUTING.md")]
    files += [ROOT / "docs/AI_WORKBENCH.md", ROOT / "docs/JAVA_DEOBFUSCATION.md", ROOT / "docs/MCP.md", ROOT / "docs/RELEASING.md",
              ROOT / "docs/API_INVENTORY.md", ROOT / "docs/ASSESSMENT_WORKFLOW.md", ROOT / "docs/UPSTREAM_REA_DESIGN.md",
              ROOT / "docs/BURP_WORKFLOW.md"]
    files += sorted((ROOT / "docs/releases").glob("*.md"))
    files += [ROOT / "docs/examples/TokenStore.java"]
    files += sorted((ROOT / "docs/images").glob("*.png"))
    files += [ROOT / "docs/images/readme-hero.svg"]
    for path in files:
        archive.file(f"{prefix}/{path.relative_to(ROOT).as_posix()}", path)
    start = {
        "windows-x64": "Double-click jadx-gui-ai.exe. CLI: bin\\jadx.bat --help",
        "macos-x64": "Open JADX GUI AI.app. CLI: ./bin/jadx --help",
        "macos-arm64": "Open JADX GUI AI.app. CLI: ./bin/jadx --help",
        "linux-x64": "Run ./bin/jadx-gui-ai. CLI: ./bin/jadx --help",
        "linux-arm64": "Run ./bin/jadx-gui-ai. CLI: ./bin/jadx --help",
        "portable": "Java 17+ required. GUI: bin/jadx-gui-ai (.bat on Windows). CLI: bin/jadx (.bat on Windows).",
    }[platform]
    archive.text(f"{prefix}/START-HERE.txt", f"JADX GUI AI {version}\nPlatform: {platform}\n\n{start}\n\n"
                 "Documentation: English - README.en.md | 简体中文 - README.md\n\n"
                 "Open a file to generate a local application report. Configure a model to start or resume an AI investigation.\n"
                 "Local reports, the API map, and call paths work without a model; use Source to browse code manually.\n"
                 "This package does not include models, accounts, keys, or analysis records.\n\n"
                 "打开文件后进入应用调查并自动生成本地报告；配置 AI 后可开始或继续调查。\n"
                 "本地报告、API 地图和调用路径无需模型；左侧源码可继续手动浏览。\n"
                 "此包不包含模型、账号、密钥或分析记录。\n\n"
                 "macOS: the app is unsigned and not notarized. Follow the operating system's Open / Privacy & Security prompt.\n"
                 "Linux: a graphical desktop and glibc-based distribution are required; this is not an Alpine/musl build.\n\n"
                 "Project: https://github.com/cncsnet1/jadx-gui-ai\n"
                 "License: Apache-2.0; see LICENSE and NOTICE.\n"
                 + (f"Bundled Java: Eclipse Temurin {runtime['version']}. Its vendor licenses are preserved inside the runtime.\n"
                    f"Runtime upstream: {runtime['source']}\n" if platform != "portable" else ""))


def build_one(out, version, platform, jar, runtime, runtime_file, stamp):
    base = f"jadx-gui-ai-{version}-{platform}"
    path = out / (base + (".tar.gz" if platform.startswith("linux-") else ".zip"))
    partial = path.with_name(path.name + ".building" + path.suffix)
    archive = Archive(partial, stamp)
    try:
        jar_rel = f"lib/{jar.name}"
        if platform.startswith("macos-"):
            app = "JADX GUI AI.app/Contents"
            archive.file(f"{base}/{app}/Resources/{jar_rel}", jar)
            plist = {"CFBundleName": "JADX GUI AI", "CFBundleDisplayName": "JADX GUI AI",
                     "CFBundleIdentifier": "org.jadx.gui.ai", "CFBundlePackageType": "APPL",
                     "CFBundleExecutable": "jadx-gui-ai", "CFBundleInfoDictionaryVersion": "6.0",
                     "CFBundleShortVersionString": version.split("-")[0], "CFBundleVersion": version.split("-")[0],
                     "NSHighResolutionCapable": True, "CFBundleIconFile": "jadx.icns"}
            archive.text(f"{base}/{app}/Info.plist", plistlib.dumps(plist))
            archive.file(f"{base}/{app}/Resources/jadx.icns", ROOT / "scripts/release/jadx.icns")
            java = 'JAVA="$ROOT/PlugIns/temurin.jre/Contents/Home/bin/java"'
            launcher = shell_launcher(java, "Resources/" + jar_rel, True, mac=True)
            archive.text(f"{base}/{app}/MacOS/jadx-gui-ai", launcher, 0o755)
            add_runtime(archive, runtime_file, f"{base}/{app}/PlugIns/temurin.jre")
            cli_java = f'JAVA="$ROOT/{app}/PlugIns/temurin.jre/Contents/Home/bin/java"'
            archive.text(f"{base}/bin/jadx", shell_launcher(cli_java, f"{app}/Resources/{jar_rel}", False), 0o755)
        else:
            archive.file(f"{base}/{jar_rel}", jar)
            if platform != "portable":
                add_runtime(archive, runtime_file, f"{base}/jre")
            java = ('JAVA="$ROOT/jre/bin/java"' if platform != "portable" else
                    'if [ -n "${JAVA_HOME:-}" ]; then JAVA="$JAVA_HOME/bin/java"; else JAVA=$(command -v java || true); fi')
            if not platform.startswith("windows-"):
                archive.text(f"{base}/bin/jadx-gui-ai", shell_launcher(java, jar_rel, True), 0o755)
                archive.text(f"{base}/bin/jadx", shell_launcher(java, jar_rel, False), 0o755)
            if platform in ("windows-x64", "portable"):
                archive.text(f"{base}/bin/jadx-gui-ai.bat", windows_launcher(jar.name, True, platform != "portable"))
                archive.text(f"{base}/bin/jadx.bat", windows_launcher(jar.name, False, platform != "portable"))
            if platform == "windows-x64":
                for name, data in build_windows_launchers(jar, version, JVM.split()).items():
                    archive.text(f"{base}/{name}", data, 0o755 if name.endswith(".exe") else 0o644)
        add_docs(archive, base, version, platform, runtime)
        archive.text(f"{base}/BUILD-INFO.json", json.dumps({"version": version, "platform": platform,
                     "launcher": "jpackage-utf8" if platform == "windows-x64" else None,
                     "jar_sha256": sha256(jar), "runtime": None if platform == "portable" else {
                         "vendor": runtime["vendor"], "version": runtime["version"], **runtime["runtimes"][platform]}}, indent=2) + "\n")
    finally:
        archive.close()
    partial.replace(path)
    print(f"Built {path.name} ({path.stat().st_size / 1024 / 1024:.1f} MiB)", flush=True)
    return {"file": path.name, "platform": platform, "size": path.stat().st_size, "sha256": sha256(path)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True)
    parser.add_argument("--jar", type=pathlib.Path)
    parser.add_argument("--out", type=pathlib.Path)
    parser.add_argument("--cache", type=pathlib.Path, default=ROOT / ".gradle/release-runtimes")
    parser.add_argument("--platform", action="append", choices=["windows-x64", "macos-x64", "macos-arm64", "linux-x64", "linux-arm64", "portable"])
    args = parser.parse_args()
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[a-zA-Z0-9.-]+)?", args.version):
        parser.error("Use a semantic version without the v prefix")
    version = args.version
    jar = args.jar or ROOT / f"jadx-gui/build/libs/jadx-gui-{version}-all.jar"
    if not jar.is_file():
        parser.error(f"Build :jadx-gui:shadowJar with JADX_VERSION={version} first: {jar}")
    platforms = args.platform or ["windows-x64", "macos-x64", "macos-arm64", "linux-x64", "linux-arm64", "portable"]
    out = (args.out or ROOT / f"build/releases/{version}").resolve()
    out.mkdir(parents=True, exist_ok=True)
    runtime, paths = download_all(args.cache, platforms)
    stamp = int(os.environ.get("SOURCE_DATE_EPOCH", str(int(datetime.datetime.now(datetime.timezone.utc).timestamp()))))
    artifacts = [build_one(out, version, target, jar, runtime, paths.get(target), stamp) for target in platforms]
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    dirty = bool(subprocess.check_output(["git", "status", "--porcelain", "--untracked-files=normal"], cwd=ROOT, text=True).strip())
    metadata = {"version": version, "source_commit": commit, "source_worktree_dirty": dirty,
                "built_at": datetime.datetime.fromtimestamp(stamp, datetime.timezone.utc).isoformat(),
                "runtime_version": runtime["version"], "artifacts": artifacts}
    (out / "release-manifest.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    notes = ROOT / f"docs/releases/v{version}.md"
    (out / "RELEASE-NOTES.md").write_text(notes.read_text(encoding="utf-8") if notes.is_file()
                                        else f"# JADX GUI AI v{version}\n\nSee the repository README for usage and platform details.\n", encoding="utf-8")
    sums = [f"{item['sha256']}  {item['file']}" for item in artifacts]
    sums.append(f"{sha256(out / 'release-manifest.json')}  release-manifest.json")
    sums.append(f"{sha256(out / 'RELEASE-NOTES.md')}  RELEASE-NOTES.md")
    (out / "SHA256SUMS.txt").write_text("\n".join(sums) + "\n", encoding="utf-8")
    print(f"Release assets: {out}", flush=True)


if __name__ == "__main__":
    main()
