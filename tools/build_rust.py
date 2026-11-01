#!/usr/bin/env python3
# Build the app-owned Rust cores with a fixed Rust toolchain and Android NDK.
import argparse
import fcntl
import json
import os
from pathlib import Path
import shutil
import subprocess
from build_native import sdk_directory

ROOT = Path(__file__).resolve().parents[1]
# Each crate belongs to one app; it is built for that app's ABIs and its JNI exports are checked against the library
# after linking.
CRATES = (
    {
        "name": "patcher",
        "source": ROOT / "apps/patcher/src/main/rust",
        "library": "libnuke_patch_core.so",
        "abis": ("arm64-v8a", "x86_64"),
        "exports": [f"Java_io_github_nuke_oss_patcher_patch_NativePatchFiles_{name}" for name in ("stageApk", "inspectApk", "verifyPatched")],
    },
    {
        "name": "module",
        "source": ROOT / "apps/module/src/main/rust",
        "library": "libnuke_protocol_core.so",
        "abis": ("arm64-v8a",),
        "exports": [f"Java_io_github_nuke_oss_app_tiktok_protocol_NativeProtocol_{name}"
                    for name in ("abiVersion", "decode", "encode", "wrapPayload", "validateRequest", "correlate")]
        + [f"Java_io_github_nuke_oss_app_shared_integrity_NativeSignature_{name}" for name in ("verify", "signerDigest")]
        + ["Java_io_github_nuke_oss_app_shared_integrity_NativeRisk_scan"]
        + ["Java_io_github_nuke_oss_app_shared_script_NativeScriptPackage_install"],
    },
)
BUILD = ROOT / "build/rust"
VERSION = "1.90.0"
TARGETS = {"arm64-v8a": "aarch64-linux-android", "x86_64": "x86_64-linux-android"}


def environment(source):
    env = dict(os.environ)
    isolated = ROOT / "build/rust-toolchain"
    candidate = isolated / "cargo/bin/cargo"
    if candidate.is_file():
        env["CARGO_HOME"] = str(isolated / "cargo")
        env["RUSTUP_HOME"] = str(isolated / "rustup")
        env["PATH"] = str(candidate.parent) + os.pathsep + env.get("PATH", "")
    else:
        candidate = Path(shutil.which("cargo") or "/missing-cargo")
    if not candidate.is_file():
        raise SystemExit("Install rustup with Rust 1.90.0, rustfmt, clippy and the two Android targets; see README.md")
    # rustup selects the version in the crate's rust-toolchain.toml.
    actual = subprocess.check_output([str(candidate), "--version"], env=env, cwd=source, text=True)
    if not actual.startswith("cargo 1.90.0 "):
        raise SystemExit(f"Expected Cargo {VERSION}, found {actual.strip()}")
    env["CARGO_TARGET_DIR"] = str(BUILD / "target")
    return str(candidate), env


def run(source, cargo, env, *arguments):
    subprocess.run([cargo, *arguments], env=env, cwd=source, check=True)


def build_crate(crate, ndk_tools, report):
    source = crate["source"]
    cargo, env = environment(source)
    for abi in crate["abis"]:
        target = TARGETS[abi]
        linker = ndk_tools / f"{target}27-clang"
        if not linker.is_file():
            raise SystemExit(f"Missing Android linker: {linker}")
        target_env = dict(env)
        target_env[f"CARGO_TARGET_{target.upper().replace('-', '_')}_LINKER"] = str(linker)
        target_env["RUSTFLAGS"] = "-C link-arg=-Wl,-z,max-page-size=16384"
        run(source, cargo, target_env, "build", "--locked", "--release", "--target", target)
        built = BUILD / "target" / target / "release" / crate["library"]
        destination = BUILD / abi / built.name
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(built, destination)
        symbols = subprocess.check_output([str(ndk_tools / "llvm-readelf"), "--dyn-syms", str(destination)], text=True)
        for export in crate["exports"]:
            if export not in symbols:
                raise SystemExit(f"Missing JNI export: {export}")
        headers = subprocess.check_output([str(ndk_tools / "llvm-readelf"), "-lW", str(destination)], text=True)
        aligns = [int(line.split()[-1], 16) for line in headers.splitlines() if line.lstrip().startswith("LOAD ")]
        if not aligns or any(value < 16384 for value in aligns):
            raise SystemExit(f"Rust ELF is not 16 KiB aligned: {abi} {built.name}")
        report["libraries"].setdefault(abi, {})[built.name] = {
            "path": str(destination.relative_to(ROOT)), "load_alignments": aligns}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--sdk")
    parser.add_argument("--crate", choices=[crate["name"] for crate in CRATES], help="build only this crate")
    args = parser.parse_args()
    crates = [crate for crate in CRATES if args.crate in (None, crate["name"])]
    BUILD.mkdir(parents=True, exist_ok=True)
    with (BUILD / "compile.lock").open("w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        if args.check:
            for crate in crates:
                cargo, env = environment(crate["source"])
                source = crate["source"]
                run(source, cargo, env, "fmt", "--check")
                run(source, cargo, env, "clippy", "--locked", "--all-targets", "--", "-D", "warnings")
                run(source, cargo, env, "test", "--locked")
                run(source, cargo, env, "build", "--locked")
            return
        native_lock = json.loads((ROOT / "apps/module/src/main/cpp/dependencies.lock.json").read_text())
        ndk = sdk_directory(args.sdk) / "ndk" / native_lock["toolchain"]["ndk"]
        report = {"rust": VERSION, "ndk": ndk.name, "api": 27, "libraries": {}}
        for crate in crates:
            build_crate(crate, ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin", report)
        (BUILD / "verification.json").write_text(json.dumps(report, indent=2) + "\n")


if __name__ == "__main__":
    main()
