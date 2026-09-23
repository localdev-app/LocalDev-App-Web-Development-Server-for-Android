#!/usr/bin/env python3
"""Prepare the LocalDev PHP 8.4 Android ARM64 runtime for manual builds.

Usage:
  python tools/prepare_php_runtime.py
  python tools/prepare_php_runtime.py /path/PHP-8.4-Android-arm64-PM5.tar.gz

The normal Gradle build already runs an equivalent verified build-time fetch.
This helper is kept for offline/manual preparation.
"""
from __future__ import annotations
import hashlib, os, shutil, sys, tarfile, tempfile, urllib.request
from pathlib import Path

URL = "https://github.com/pmmp/PHP-Binaries/releases/download/pm5-php-8.4-latest/PHP-8.4-Android-arm64-PM5.tar.gz"
EXPECTED_SHA256 = "d8867966340121f821591b9bb29c80a58ad77abd6cc8e0e44d1cbbb3aaedd70c"
ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / ".runtime-cache/PHP-8.4-Android-arm64-PM5.tar.gz"
OUT = ROOT / "app/src/main/jniLibs/arm64-v8a/liblocaldev_php.so"
INFO = ROOT / "app/src/main/assets/php-runtime-source.txt"


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def safe_extract(tf: tarfile.TarFile, dest: Path) -> None:
    base = dest.resolve()
    for member in tf.getmembers():
        target = (dest / member.name).resolve()
        if not str(target).startswith(str(base) + os.sep) and target != base:
            raise RuntimeError(f"Unsafe archive member: {member.name}")
        if member.issym() or member.islnk():
            raise RuntimeError(f"Symlink/hardlink not allowed: {member.name}")
    tf.extractall(dest)


def download() -> Path:
    CACHE.parent.mkdir(parents=True, exist_ok=True)
    print(f"Downloading: {URL}")
    req = urllib.request.Request(URL, headers={"User-Agent":"LocalDev-build/0.4"})
    with urllib.request.urlopen(req, timeout=120) as r, CACHE.open("wb") as out:
        shutil.copyfileobj(r, out)
    return CACHE


def main() -> int:
    archive = Path(sys.argv[1]).expanduser().resolve() if len(sys.argv) == 2 else CACHE
    if len(sys.argv) > 2:
        print("Usage: python tools/prepare_php_runtime.py [PHP-8.4-Android-arm64-PM5.tar.gz]")
        return 2
    if not archive.is_file():
        archive = download()

    digest = sha256(archive)
    print(f"SHA256: {digest}")
    if digest != EXPECTED_SHA256:
        raise SystemExit("Checksum mismatch. Runtime archive was not accepted.")

    with tempfile.TemporaryDirectory(prefix="localdev-php-") as td:
        temp = Path(td)
        with tarfile.open(archive, "r:gz") as tf:
            safe_extract(tf, temp)
        candidates = [p for p in temp.rglob("php") if p.is_file()]
        preferred = [p for p in candidates if "/bin/php7/bin/php" in p.as_posix()]
        php = (preferred or candidates)[0] if candidates else None
        if php is None:
            raise SystemExit("Could not find PHP executable inside archive.")
        OUT.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(php, OUT)
        OUT.chmod(0o755)
        INFO.parent.mkdir(parents=True, exist_ok=True)
        INFO.write_text(
            "source=pmmp/PHP-Binaries\n"
            f"archive={archive.name}\nsha256={digest}\n"
            "target=arm64-v8a\nbrand=LocalDev\n",
            encoding="utf-8"
        )
    print(f"Prepared: {OUT}")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
