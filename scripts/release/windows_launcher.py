"""Build Windows launchers with JDK jpackage and a per-process UTF-8 code page.

The vendor runtime is copied unchanged by build_release.py. Only our generated
launchers' manifests are extended; no system locale or Java installation changes.
"""

import ctypes
from ctypes import wintypes
import os
import pathlib
import shutil
import stat
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ASM = "urn:schemas-microsoft-com:asm.v3"
CODE_PAGE = "http://schemas.microsoft.com/SMI/2019/WindowsSettings"


def utf8_manifest(original):
    root = ET.fromstring(original)
    application = root.find(f"{{{ASM}}}application")
    if application is None:
        application = ET.SubElement(root, f"{{{ASM}}}application")
    settings = application.find(f"{{{ASM}}}windowsSettings")
    if settings is None:
        settings = ET.SubElement(application, f"{{{ASM}}}windowsSettings")
    code_page = settings.find(f"{{{CODE_PAGE}}}activeCodePage")
    if code_page is None:
        code_page = ET.SubElement(settings, f"{{{CODE_PAGE}}}activeCodePage")
    code_page.text = "UTF-8"
    return ET.tostring(root, encoding="utf-8", xml_declaration=True)


def enable_utf8(executable):
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    load = kernel.LoadLibraryExW
    load.argtypes, load.restype = [wintypes.LPCWSTR, wintypes.HANDLE, wintypes.DWORD], wintypes.HMODULE
    find = kernel.FindResourceW
    find.argtypes, find.restype = [wintypes.HMODULE, ctypes.c_void_p, ctypes.c_void_p], wintypes.HANDLE
    size = kernel.SizeofResource
    size.argtypes, size.restype = [wintypes.HMODULE, wintypes.HANDLE], wintypes.DWORD
    get = kernel.LoadResource
    get.argtypes, get.restype = [wintypes.HMODULE, wintypes.HANDLE], wintypes.HANDLE
    lock = kernel.LockResource
    lock.argtypes, lock.restype = [wintypes.HANDLE], ctypes.c_void_p
    free = kernel.FreeLibrary
    free.argtypes, free.restype = [wintypes.HMODULE], wintypes.BOOL
    callback_type = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HMODULE, ctypes.c_void_p, ctypes.c_void_p,
                                      wintypes.WORD, wintypes.LPARAM)
    enumerate_languages = kernel.EnumResourceLanguagesW
    enumerate_languages.argtypes = [wintypes.HMODULE, ctypes.c_void_p, ctypes.c_void_p, callback_type, wintypes.LPARAM]
    enumerate_languages.restype = wintypes.BOOL
    begin = kernel.BeginUpdateResourceW
    begin.argtypes, begin.restype = [wintypes.LPCWSTR, wintypes.BOOL], wintypes.HANDLE
    update = kernel.UpdateResourceW
    update.argtypes = [wintypes.HANDLE, ctypes.c_void_p, ctypes.c_void_p, wintypes.WORD,
                       ctypes.c_void_p, wintypes.DWORD]
    update.restype = wintypes.BOOL
    end = kernel.EndUpdateResourceW
    end.argtypes, end.restype = [wintypes.HANDLE, wintypes.BOOL], wintypes.BOOL

    def checked(value):
        if not value:
            raise ctypes.WinError(ctypes.get_last_error())
        return value

    # Load as data, never execute the launcher while inspecting resources.
    module = checked(load(str(executable), None, 0x2 | 0x20))
    try:
        languages = []
        callback = callback_type(lambda module, kind, name, language, param: languages.append(language) or True)
        checked(enumerate_languages(module, 24, 1, callback, 0))
        if len(languages) != 1:
            raise RuntimeError("Unexpected jpackage manifest resource languages")
        resource = checked(find(module, 1, 24))  # CREATEPROCESS_MANIFEST_RESOURCE_ID, RT_MANIFEST
        data = ctypes.string_at(checked(lock(checked(get(module, resource)))), checked(size(module, resource)))
    finally:
        free(module)
    manifest = utf8_manifest(data)
    executable.chmod(stat.S_IREAD | stat.S_IWRITE)
    handle = checked(begin(str(executable), False))
    try:
        buffer = ctypes.create_string_buffer(manifest)
        checked(update(handle, 24, 1, languages[0], buffer, len(manifest)))
    except BaseException:
        end(handle, True)
        raise
    checked(end(handle, False))


def build_windows_launchers(jar, version, java_options):
    if os.name != "nt":
        raise RuntimeError("Build Windows archives on Windows with JDK 17+ (jpackage)")
    java_home = os.environ.get("JAVA_HOME")
    executable = pathlib.Path(java_home) / "bin/jpackage.exe" if java_home else pathlib.Path(shutil.which("jpackage") or "")
    if not executable.is_file():
        raise RuntimeError("The release builder needs JDK 17+ with jpackage in JAVA_HOME or PATH")
    jdk = executable.resolve().parent.parent
    with tempfile.TemporaryDirectory(prefix="jadx-launcher-") as temp:
        root = pathlib.Path(temp)
        source, runtime = root / "input", root / "runtime"
        source.mkdir()
        runtime.mkdir()  # jpackage only builds launchers; the locked runtime is added by the archive builder.
        shutil.copyfile(jar, source / jar.name)
        cli = root / "cli.properties"
        cli.write_text("main-class=jadx.cli.JadxCLI\nwin-console=true\n", encoding="utf-8")
        command = [str(executable), "--type", "app-image", "--name", "jadx-gui-ai", "--input", str(source),
                   "--main-jar", jar.name, "--main-class", "jadx.gui.JadxGUI", "--runtime-image", str(runtime),
                   "--dest", str(root / "out"), "--app-version", version.split("-")[0],
                   "--icon", str(pathlib.Path(__file__).resolve().parents[2] / "jadx-gui/src/main/resources/logos/jadx-logo.ico"),
                   "--add-launcher", f"jadx-cli={cli}"]
        for option in java_options:
            command.extend(["--java-options", option])
        subprocess.run(command, check=True)
        app = root / "out/jadx-gui-ai"
        files = {}
        for name in ("jadx-gui-ai", "jadx-cli"):
            launcher = app / f"{name}.exe"
            enable_utf8(launcher)
            files[f"{name}.exe"] = launcher.read_bytes()
            config = (app / f"app/{name}.cfg").read_text(encoding="utf-8")
            config = config.replace("[Application]", "[Application]\napp.runtime=$APPDIR\\..\\jre")
            config = config.replace(f"$APPDIR\\{jar.name}", f"$APPDIR\\..\\lib\\{jar.name}")
            if f"$APPDIR\\..\\lib\\{jar.name}" not in config:
                raise RuntimeError("Unexpected jpackage classpath format")
            files[f"app/{name}.cfg"] = config.encode("utf-8")
        licenses = jdk / "legal/jdk.jpackage"
        if not licenses.is_dir():
            raise RuntimeError("jpackage redistribution licenses are missing from the build JDK")
        for license_file in licenses.iterdir():
            if license_file.is_file():
                files[f"docs/licenses/windows-launcher/{license_file.name}"] = license_file.read_bytes()
        files["docs/licenses/windows-launcher/JDK-release.txt"] = (jdk / "release").read_bytes()
        return files
