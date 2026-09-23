#!/usr/bin/env python3
"""Prepare a LocalDev-compatible MariaDB Android ARM64 runtime archive.

This does NOT convert Linux/Termux MariaDB into an Android runtime.
Input must already contain Android ARM64 executables that are compatible with
being executed from an Android app's nativeLibraryDir.

Expected input directory/archive:
  bin/mariadbd
  bin/mariadb
  seed/                 # initialized MariaDB data directory seed

Output:
  .runtime-cache/LocalDev-MariaDB-Android-arm64.tar.gz

Usage:
  python tools/prepare_mariadb_runtime.py /path/to/runtime-dir
  python tools/prepare_mariadb_runtime.py /path/to/runtime.tar.gz
"""
from __future__ import annotations
import os, shutil, sys, tarfile, tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / ".runtime-cache/LocalDev-MariaDB-Android-arm64.tar.gz"


def safe_extract(tf: tarfile.TarFile, dest: Path) -> None:
    base = dest.resolve()
    for m in tf.getmembers():
        target = (dest / m.name).resolve()
        if target != base and not str(target).startswith(str(base) + os.sep):
            raise RuntimeError(f"Unsafe archive member: {m.name}")
        if m.issym() or m.islnk():
            raise RuntimeError(f"Links are not accepted: {m.name}")
    tf.extractall(dest)


def find_runtime(root: Path):
    servers = [p for p in root.rglob("mariadbd") if p.is_file()]
    clients = [p for p in root.rglob("mariadb") if p.is_file()]
    seeds = [p for p in root.rglob("seed") if p.is_dir()]
    if not servers:
        raise SystemExit("mariadbd not found")
    if not clients:
        raise SystemExit("mariadb client not found")
    if not seeds:
        raise SystemExit("seed/ initialized datadir not found")
    return servers[0], clients[0], seeds[0]


def make_bundle(server: Path, client: Path, seed: Path) -> None:
    OUT.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="localdev-mariadb-bundle-") as td:
        staging = Path(td) / "runtime"
        (staging / "bin").mkdir(parents=True)
        shutil.copy2(server, staging / "bin/mariadbd")
        shutil.copy2(client, staging / "bin/mariadb")
        shutil.copytree(seed, staging / "seed")
        (staging / "RUNTIME.txt").write_text(
            "LocalDev MariaDB Android ARM64 runtime bundle\n"
            "Required: native Android ARM64 mariadbd + mariadb client + initialized seed datadir.\n",
            encoding="utf-8",
        )
        with tarfile.open(OUT, "w:gz") as tf:
            for p in staging.rglob("*"):
                tf.add(p, arcname=p.relative_to(staging).as_posix(), recursive=False)
    print(f"Prepared: {OUT}")


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    src = Path(sys.argv[1]).expanduser().resolve()
    if not src.exists():
        raise SystemExit(f"Input not found: {src}")

    if src.is_dir():
        server, client, seed = find_runtime(src)
        make_bundle(server, client, seed)
        return 0

    with tempfile.TemporaryDirectory(prefix="localdev-mariadb-input-") as td:
        temp = Path(td)
        with tarfile.open(src, "r:*") as tf:
            safe_extract(tf, temp)
        server, client, seed = find_runtime(temp)
        make_bundle(server, client, seed)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
