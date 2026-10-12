import io
import pathlib
import re
import plistlib
import struct
import tempfile
import unittest

from build_release import Archive, add_docs, add_runtime, safe_name, shell_launcher, windows_launcher
from verify_release import Reader, binary_arch


class ReleaseTests(unittest.TestCase):
    def test_packaged_readme_local_links_and_new_guides_exist(self):
        with tempfile.TemporaryDirectory() as temp:
            path = pathlib.Path(temp) / "docs.zip"
            archive = Archive(path, 1700000000)
            add_docs(archive, "app", "2.0.0-beta.4", "portable", {})
            archive.close()
            reader = Reader(path)
            try:
                readme = reader.read("app/README.md").decode("utf-8")
                links = re.findall(r'\]\(([^)]+)\)|src="([^"]+)"', readme)
                for markdown, html in links:
                    link = markdown or html
                    if "://" not in link and not link.startswith("#"):
                        self.assertIn("app/" + link.split("#")[0], reader.names)
                self.assertIn("app/docs/JAVA_DEOBFUSCATION.md", reader.names)
                self.assertIn("app/docs/API_INVENTORY.md", reader.names)
                self.assertIn("app/docs/ASSESSMENT_WORKFLOW.md", reader.names)
                self.assertIn("应用调查", reader.read("app/START-HERE.txt").decode("utf-8"))
                self.assertIn("app/docs/images/workspace-home-graphite.png", reader.names)
                self.assertIn("app/docs/images/workbench-graphite.png", reader.names)
            finally:
                reader.close()

    def test_unsafe_archive_paths_are_rejected(self):
        for path in ("../escape", "/absolute", "a/../../escape", "C:/windows", "a\\b"):
            with self.assertRaises(ValueError):
                safe_name(path)

    def test_tar_to_zip_preserves_executables_and_symlinks(self):
        import tarfile
        with tempfile.TemporaryDirectory() as temp:
            base = pathlib.Path(temp)
            with tarfile.open(base / "runtime.tar.gz", "w:gz") as original:
                executable = tarfile.TarInfo("jdk/Contents/Home/bin/java")
                executable.mode, executable.size = 0o755, 4
                original.addfile(executable, io.BytesIO(b"java"))
                link = tarfile.TarInfo("jdk/Contents/Home/lib/current")
                link.type, link.linkname, link.mode = tarfile.SYMTYPE, "../bin/java", 0o777
                original.addfile(link)
            target = Archive(base / "out.zip", 1700000000)
            add_runtime(target, base / "runtime.tar.gz", "App/runtime")
            target.close()
            reader = Reader(base / "out.zip")
            try:
                self.assertEqual(reader.read("App/runtime/Contents/Home/bin/java"), b"java")
                self.assertTrue(reader.mode("App/runtime/Contents/Home/bin/java") & 0o111)
                import stat
                self.assertTrue(stat.S_ISLNK(reader.mode("App/runtime/Contents/Home/lib/current")))
                self.assertEqual(reader.read("App/runtime/Contents/Home/lib/current"), b"../bin/java")
            finally:
                reader.close()

    def test_escaping_runtime_symlink_is_rejected(self):
        import tarfile
        with tempfile.TemporaryDirectory() as temp:
            base = pathlib.Path(temp)
            with tarfile.open(base / "bad.tar.gz", "w:gz") as original:
                link = tarfile.TarInfo("jdk/bin/java")
                link.type, link.linkname = tarfile.SYMTYPE, "../../outside"
                original.addfile(link)
            target = Archive(base / "out.zip", 1700000000)
            try:
                with self.assertRaises(ValueError):
                    add_runtime(target, base / "bad.tar.gz", "runtime")
            finally:
                target.close()

    def test_platform_launchers_keep_spaces_and_arguments(self):
        linux = shell_launcher('JAVA="$ROOT/jre/bin/java"', "lib/app.jar", True)
        mac = shell_launcher('JAVA="$ROOT/PlugIns/temurin.jre/Contents/Home/bin/java"', "Resources/lib/app.jar", True, mac=True)
        self.assertNotIn("-Xdock:", linux)
        self.assertIn('-Xdock:name="JADX GUI AI"', mac)
        self.assertIn('-cp "$ROOT/Resources/lib/app.jar" jadx.gui.JadxGUI "$@"', mac)
        self.assertIn('"%APP_HOME%lib\\app.jar" jadx.cli.JadxCLI %*', windows_launcher("app.jar", False, True))
        self.assertIn("%APP_HOME%jre\\bin\\java.exe", windows_launcher("app.jar", False, True))

    def test_native_architecture_detection(self):
        elf = bytearray(64)
        elf[:6] = b"\x7fELF\x02\x01"
        struct.pack_into("<H", elf, 18, 183)
        self.assertEqual(binary_arch(elf), "arm64")
        macho = b"\xcf\xfa\xed\xfe" + struct.pack("<I", 0x01000007) + bytes(24)
        self.assertEqual(binary_arch(macho), "x64")
        with self.assertRaises(ValueError):
            binary_arch(b"not a binary")


if __name__ == "__main__":
    unittest.main()
