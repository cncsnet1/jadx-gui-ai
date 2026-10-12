"""Verify archive checksums, payloads, native architectures and launcher metadata."""

import argparse
import hashlib
import io
import json
import pathlib
import plistlib
import stat
import struct
import tarfile
import zipfile

from download_runtimes import sha256
from build_release import safe_name


def binary_arch(data):
    if data[:2] == b"MZ":
        offset = struct.unpack_from("<I", data, 0x3C)[0]
        if data[offset:offset + 4] != b"PE\x00\x00":
            raise ValueError("Invalid PE executable")
        return {0x8664: "x64", 0xAA64: "arm64", 0x14C: "x86"}.get(struct.unpack_from("<H", data, offset + 4)[0])
    if data[:4] == b"\x7fELF":
        order = "<" if data[5] == 1 else ">"
        return {62: "x64", 183: "arm64"}.get(struct.unpack_from(order + "H", data, 18)[0])
    if data[:4] in (b"\xcf\xfa\xed\xfe", b"\xfe\xed\xfa\xcf"):
        order = "<" if data[:4] == b"\xcf\xfa\xed\xfe" else ">"
        return {0x01000007: "x64", 0x0100000C: "arm64"}.get(struct.unpack_from(order + "I", data, 4)[0])
    raise ValueError("Unrecognized native executable")


class Reader:
    def __init__(self, path):
        self.zip = zipfile.ZipFile(path) if path.suffix == ".zip" else None
        self.tar = None if self.zip else tarfile.open(path, "r:gz")
        self.entries = (self.zip.infolist() if self.zip else self.tar.getmembers())
        self.names = [e.filename if self.zip else e.name for e in self.entries]
        if len(set(self.names)) != len(self.names):
            raise ValueError(f"Duplicate entries in {path}")
        for name in self.names:
            safe_name(name)

    def read(self, name):
        return self.zip.read(name) if self.zip else self.tar.extractfile(name).read()

    def mode(self, name):
        return self.zip.getinfo(name).external_attr >> 16 if self.zip else self.tar.getmember(name).mode

    def close(self):
        (self.zip or self.tar).close()


def verify(directory):
    directory = pathlib.Path(directory)
    manifest = json.loads((directory / "release-manifest.json").read_text(encoding="utf-8"))
    for line in (directory / "SHA256SUMS.txt").read_text(encoding="utf-8").splitlines():
        digest, name = line.split("  ", 1)
        if pathlib.PurePath(name).name != name or sha256(directory / name) != digest:
            raise ValueError(f"Checksum mismatch: {name}")
    jar_hash = None
    results = []
    for asset in manifest["artifacts"]:
        path = directory / asset["file"]
        if path.stat().st_size != asset["size"] or sha256(path) != asset["sha256"]:
            raise ValueError(f"Manifest mismatch: {path.name}")
        reader = Reader(path)
        try:
            root = reader.names[0].split("/")[0]
            platform = asset["platform"]
            info = json.loads(reader.read(f"{root}/BUILD-INFO.json"))
            assert info["version"] == manifest["version"] and info["platform"] == platform
            for name in ("README.md", "LICENSE", "NOTICE", "START-HERE.txt", "docs/images/readme-hero.svg",
                         "docs/JAVA_DEOBFUSCATION.md", "docs/MCP.md", "docs/images/workspace-home-graphite.png",
                         "docs/images/workbench-graphite.png"):
                assert f"{root}/{name}" in reader.names, (platform, name)
            jars = [name for name in reader.names if name.endswith("-all.jar")]
            assert len(jars) == 1, platform
            payload = reader.read(jars[0])
            with zipfile.ZipFile(io.BytesIO(payload)) as application:
                for required in ("jadx/gui/ui/workspace/WorkspaceChrome.class",
                                 "jadx/gui/ui/workspace/ProjectLoadPanel.class",
                                 "jadx/gui/ui/workspace/ProjectReadyPanel.class",
                                 "jadx/gui/ui/FileDropSupport.class",
                                 "jadx/gui/ai/AiClassSearch.class",
                                 "jadx/gui/mcp/McpServer.class",
                                 "jadx/gui/mcp/McpClient.class",
                                 "mcp/messages_zh.properties",
                                 "jadx/plugins/deobf/JavaDeobfPlugin.class",
                                 "themes/jadx-graphite.xml"):
                    assert required in application.namelist(), (platform, required)
                assert b"jadx.plugins.deobf.JavaDeobfPlugin" in application.read("META-INF/services/jadx.api.plugins.JadxPlugin")
            current = hashlib.sha256(payload).hexdigest()
            assert current == info["jar_sha256"] and (jar_hash is None or current == jar_hash)
            jar_hash = current
            with zipfile.ZipFile(io.BytesIO(payload)) as jar:
                assert "jadx/gui/ai/AiWorkbenchPanel.class" in jar.namelist()
                assert "jadx/gui/ui/dialog/AiInvestigationDialog.class" in jar.namelist()
                assert "jadx/cli/JadxCLI.class" in jar.namelist()
            if platform.startswith("macos-"):
                app = f"{root}/JADX GUI AI.app/Contents"
                plist = plistlib.loads(reader.read(f"{app}/Info.plist"))
                assert plist["CFBundleExecutable"] == "jadx-gui-ai"
                executable = f"{app}/MacOS/jadx-gui-ai"
                runtime = f"{app}/PlugIns/temurin.jre/Contents/Home"
                assert reader.mode(executable) & 0o111
                assert reader.read(executable).startswith(b"#!/bin/sh\n")
                assert binary_arch(reader.read(f"{runtime}/bin/java")) == platform.split("-")[1]
                assert reader.mode(f"{runtime}/bin/java") & 0o111
                assert reader.read(f"{app}/Resources/jadx.icns")[:4] == b"icns"
            elif platform == "windows-x64":
                runtime = f"{root}/jre"
                assert binary_arch(reader.read(f"{runtime}/bin/java.exe")) == "x64"
                assert binary_arch(reader.read(f"{root}/jadx-gui-ai.exe")) in ("x86", "x64")
                assert b"jre\\bin\\java.exe" in reader.read(f"{root}/bin/jadx.bat")
            elif platform.startswith("linux-"):
                runtime = f"{root}/jre"
                assert binary_arch(reader.read(f"{runtime}/bin/java")) == platform.split("-")[1]
                assert reader.mode(f"{runtime}/bin/java") & 0o111
                assert reader.mode(f"{root}/bin/jadx-gui-ai") & 0o111
                assert b"-Xdock:" not in reader.read(f"{root}/bin/jadx-gui-ai")
            else:
                runtime = None
                assert not any("/jre/" in name for name in reader.names)
                assert reader.mode(f"{root}/bin/jadx-gui-ai") & 0o111
                assert f"{root}/bin/jadx.bat" in reader.names
                assert info["runtime"] is None
            if runtime:
                assert any(name.startswith(f"{runtime}/legal/") for name in reader.names)
                assert f"{runtime}/release" in reader.names
                assert info["runtime"]["version"] == manifest["runtime_version"]
            print(f"OK: {path.name} — SHA-256, payload, launchers, architecture, permissions, licenses", flush=True)
            results.append(platform)
        finally:
            reader.close()
    return results


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=pathlib.Path)
    verify(parser.parse_args().directory)
