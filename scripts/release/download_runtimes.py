"""Download pinned Temurin archives; verify every file before it is packaged."""

import argparse
import concurrent.futures
import hashlib
import json
import pathlib
import shutil
import subprocess

HERE = pathlib.Path(__file__).resolve().parent


def sha256(path):
    with pathlib.Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def download_all(cache, platforms=None):
    manifest = json.loads((HERE / "runtimes.json").read_text(encoding="utf-8"))
    cache = pathlib.Path(cache).resolve()
    cache.mkdir(parents=True, exist_ok=True)
    curl = shutil.which("curl.exe") or shutil.which("curl")
    if not curl:
        raise RuntimeError("curl is required to download the pinned runtimes")

    def fetch(item):
        platform, asset = item
        target = cache / asset["file"]
        if target.exists() and sha256(target) == asset["sha256"]:
            print(f"Verified cache: {platform}", flush=True)
            return platform, target
        partial = target.with_suffix(target.suffix + ".part")
        print(f"Downloading: {platform}", flush=True)
        subprocess.run([curl, "--fail", "--location", "--retry", "3", "--retry-all-errors", "--connect-timeout", "20",
                        "--max-time", "600", "--silent", "--show-error", "--output", str(partial), asset["url"]], check=True)
        if partial.stat().st_size != asset["size"] or sha256(partial) != asset["sha256"]:
            raise RuntimeError(f"Runtime checksum or size mismatch: {platform}")
        partial.replace(target)
        print(f"SHA-256 verified: {platform}", flush=True)
        return platform, target

    items = [(key, value) for key, value in manifest["runtimes"].items() if platforms is None or key in platforms]
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        paths = dict(pool.map(fetch, items))
    return manifest, paths


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=pathlib.Path, default=HERE.parents[1] / ".gradle/release-runtimes")
    arguments = parser.parse_args()
    download_all(arguments.cache)
