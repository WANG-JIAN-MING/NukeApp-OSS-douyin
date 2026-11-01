#!/usr/bin/env python3

import argparse
import fcntl
import hashlib
import json
from pathlib import Path, PurePosixPath
import shutil
import sys
import urllib.parse
import urllib.request
import zipfile


ROOT = Path(__file__).resolve().parents[1]
LOCK_FILE = ROOT / "apps/patcher/lspatch.lock.json"
CACHE = ROOT / "build/lspatch"
DEFAULT_SEED = ROOT / "artifacts/research/lspatch-maintained-release.jar"
CHUNK_SIZE = 1024 * 1024


def digest_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(CHUNK_SIZE), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify_asset(path, asset):
    if path.stat().st_size != asset["size"]:
        raise ValueError(f"Locked LSPatch size mismatch: {path}")
    if digest_file(path) != asset["sha256"]:
        raise ValueError(f"Locked LSPatch SHA-256 mismatch: {path}")


class HttpsRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, url):
        if urllib.parse.urlsplit(url).scheme != "https":
            raise ValueError("Refusing a non-HTTPS LSPatch redirect")
        return super().redirect_request(request, response, code, message, headers, url)


def fetch_asset(lock, offline, seed):
    asset = lock["asset"]
    destination = CACHE / asset["name"]
    if destination.exists():
        verify_asset(destination, asset)
        return destination
    temporary = destination.with_suffix(".download")
    try:
        if seed.is_file():
            verify_asset(seed, asset)
            shutil.copyfile(seed, temporary)
        elif offline:
            raise FileNotFoundError("Missing locked LSPatch cache; run prepare_lspatch.py online once or provide --seed")
        else:
            if urllib.parse.urlsplit(asset["url"]).scheme != "https":
                raise ValueError("The locked LSPatch asset URL must use HTTPS")
            opener = urllib.request.build_opener(HttpsRedirects())
            request = urllib.request.Request(asset["url"], headers={"User-Agent": "NukeApp-OSS-build"})
            with opener.open(request, timeout=60) as source, temporary.open("wb") as output:
                total = 0
                for chunk in iter(lambda: source.read(CHUNK_SIZE), b""):
                    total += len(chunk)
                    if total > asset["size"]:
                        raise ValueError("The LSPatch download exceeds its locked size")
                    output.write(chunk)
        verify_asset(temporary, asset)
        temporary.replace(destination)
    finally:
        temporary.unlink(missing_ok=True)
    return destination


def removed_entry(name, lock):
    if name in lock["payloads"]:
        return True
    if name in lock["remove_entries"] or any(name.startswith(prefix) for prefix in lock["remove_prefixes"]):
        return True
    if PurePosixPath(name).name == "module-info.class":
        return True
    upper = name.upper()
    return upper.startswith("META-INF/") and (
        upper.endswith((".SF", ".RSA", ".DSA", ".EC")) or PurePosixPath(upper).name.startswith("SIG-")
    )


def verify_contents(archive, lock, require_payloads=True):
    names = archive.namelist()
    if len(names) != len(set(names)):
        raise ValueError("Duplicate LSPatch archive entries")
    for name in names:
        if name.startswith("/") or "\\" in name or ".." in PurePosixPath(name).parts:
            raise ValueError("Unexpected LSPatch archive entry path")
    # Framework implementations stay inside loader.dex, never the manager's ordinary classes.
    forbidden = ("android/", "de/robv/android/xposed/", "io/github/libxposed/", "org/lsposed/lspd/")
    if any(name.endswith(".class") and name.startswith(forbidden) for name in names):
        raise ValueError("The LSPatch patcher JAR contains optional framework classes")
    if not require_payloads:
        if any(name.endswith(".dex") or name in lock["payloads"] for name in names):
            raise ValueError("Loader payloads must not be part of the patcher class JAR")
        return names
    for name, expected in lock["payloads"].items():
        entry = archive.getinfo(name)
        if entry.file_size != expected["size"]:
            raise ValueError(f"LSPatch payload size mismatch: {name}")
        digest = hashlib.sha256()
        with archive.open(entry) as source:
            for chunk in iter(lambda: source.read(CHUNK_SIZE), b""):
                digest.update(chunk)
        if digest.hexdigest() != expected["sha256"]:
            raise ValueError(f"LSPatch payload SHA-256 mismatch: {name}")
    return names


def prepare_resources(upstream, lock):
    destination = ROOT / lock["resources_output"]
    if destination != CACHE / "resources":
        raise ValueError("Unexpected locked LSPatch resource output path")
    expected_paths = set()
    for name, expected in lock["payloads"].items():
        target = destination / name
        expected_paths.add(target)
        if target.is_file() and target.stat().st_size == expected["size"] and digest_file(target) == expected["sha256"]:
            continue
        target.parent.mkdir(parents=True, exist_ok=True)
        temporary = target.with_suffix(target.suffix + ".tmp")
        try:
            with upstream.open(name) as source, temporary.open("wb") as output:
                shutil.copyfileobj(source, output, CHUNK_SIZE)
            verify_asset(temporary, expected)
            temporary.replace(target)
        finally:
            temporary.unlink(missing_ok=True)
    actual_paths = {path for path in destination.rglob("*") if path.is_file()}
    if actual_paths != expected_paths:
        raise ValueError("Unexpected generated LSPatch resources; remove build/lspatch/resources and prepare again")
    return destination


def prepare_engine(source, lock):
    destination = ROOT / lock["output"]
    if destination.parent != CACHE or destination.name != "lspatch-engine.jar":
        raise ValueError("Unexpected locked LSPatch output path")
    temporary = destination.with_suffix(".tmp")
    removed = []
    moved = []
    retained = 0
    try:
        with zipfile.ZipFile(source) as upstream, zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as output:
            names = verify_contents(upstream, lock)
            resources = prepare_resources(upstream, lock)
            for name in sorted(names):
                if removed_entry(name, lock):
                    if name in lock["payloads"]:
                        moved.append(name)
                    else:
                        removed.append(name)
                    continue
                original = upstream.getinfo(name)
                entry = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
                entry.create_system = 3
                entry.external_attr = (0o40755 if original.is_dir() else 0o100644) << 16
                entry.compress_type = original.compress_type
                with upstream.open(original) as incoming, output.open(entry, "w") as outgoing:
                    shutil.copyfileobj(incoming, outgoing, CHUNK_SIZE)
                retained += 1
        with zipfile.ZipFile(temporary) as prepared:
            verify_contents(prepared, lock, require_payloads=False)
            if any(removed_entry(name, lock) for name in prepared.namelist()):
                raise ValueError("A forbidden resource remains in the LSPatch engine")
        engine_hash = digest_file(temporary)
        if destination.is_file() and digest_file(destination) == engine_hash:
            temporary.unlink()
        else:
            temporary.replace(destination)
    finally:
        temporary.unlink(missing_ok=True)
    report = {
        "repository": lock["repository"],
        "revision": lock["revision"],
        "version": lock["version"],
        "upstream_sha256": lock["asset"]["sha256"],
        "engine_sha256": engine_hash,
        "output": str(destination.relative_to(ROOT)),
        "resources_output": str(resources.relative_to(ROOT)),
        "removed_entries": removed,
        "moved_payloads": moved,
        "retained_entries": retained,
        "classes_modified": False,
        "resource_paths_modified": False,
        "payloads": lock["payloads"],
    }
    report_path = CACHE / "verification.json"
    report_text = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if not report_path.is_file() or report_path.read_text() != report_text:
        report_path.write_text(report_text)
    print(f"Prepared {destination.relative_to(ROOT)}: {engine_hash}")


def main():
    parser = argparse.ArgumentParser(description="Prepare locked LSPatch classes and separate loader resources without test signing keys")
    parser.add_argument("--offline", action="store_true", help="Require a verified cache or local seed; never download")
    parser.add_argument("--seed", type=Path, default=DEFAULT_SEED, help="Import this existing official JAR after verifying its locked size and SHA-256")
    args = parser.parse_args()
    lock = json.loads(LOCK_FILE.read_text())
    if lock.get("schema") != 1:
        parser.error("Unsupported LSPatch lock schema")
    CACHE.mkdir(parents=True, exist_ok=True)
    with (CACHE / "prepare.lock").open("w") as prepare_lock:
        fcntl.flock(prepare_lock, fcntl.LOCK_EX)
        source = fetch_asset(lock, args.offline, args.seed.expanduser().resolve())
        prepare_engine(source, lock)


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, zipfile.BadZipFile, KeyError) as error:
        print(f"LSPatch preparation failed: {error}", file=sys.stderr)
        raise SystemExit(1) from None
