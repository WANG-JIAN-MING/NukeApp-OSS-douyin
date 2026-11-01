#!/usr/bin/env python3
"""Build locked Zygisk/LSPlant sources for the two supported Android ABIs."""

import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "apps/module/src/main/cpp"
BUILD_ROOT = ROOT / "build/native"
DEPS_ROOT = BUILD_ROOT / "deps"
LOCK_FILE = SOURCE / "dependencies.lock.json"
ABIS = ("arm64-v8a",)


def run(*args, **kwargs):
    subprocess.run([str(value) for value in args], check=True, **kwargs)


def fetch_sources(lock, offline):
    for name, repository in lock["repositories"].items():
        destination = DEPS_ROOT / name
        revision = repository["revision"]
        if not destination.joinpath(".git").exists():
            if offline:
                raise SystemExit(f"Missing source cache: {name}; run without --offline once")
            destination.mkdir(parents=True, exist_ok=True)
            run("git", "init", "-q", destination)
        actual = subprocess.run(["git", "-C", str(destination), "rev-parse", "HEAD"],
                                capture_output=True, text=True)
        if actual.returncode != 0 or actual.stdout.strip() != revision:
            if offline:
                raise SystemExit(f"Source revision mismatch in offline cache: {name}")
            # Only this ignored, tool-owned dependency cache is checked out.
            run("git", "-C", destination, "fetch", "--depth=1", repository["url"], revision)
            run("git", "-C", destination, "checkout", "--detach", revision)
    # A repository that nests another one records that one's revision, so it only looks clean once every nested
    # source is at its own locked revision; check after all of them are in place.
    for name in lock["repositories"]:
        dirty = subprocess.check_output(
            ["git", "-C", str(DEPS_ROOT / name), "diff", "--name-only", "HEAD"], text=True)
        if dirty.strip():
            raise SystemExit(f"Refusing modified dependency source: {name}\n{dirty}")


def sdk_directory(explicit):
    if explicit:
        return Path(explicit).expanduser().resolve()
    for variable in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(variable):
            return Path(os.environ[variable]).expanduser().resolve()
    properties = ROOT / "local.properties"
    if properties.exists():
        for line in properties.read_text().splitlines():
            if line.startswith("sdk.dir="):
                return Path(line.partition("=")[2].replace("\\:", ":").replace("\\\\", "\\"))
    raise SystemExit("Set sdk.dir in local.properties, ANDROID_HOME, or --sdk")


def prepare_output(output):
    cache = output / "CMakeCache.txt"
    if cache.is_file():
        source = next((line.partition("=")[2] for line in cache.read_text().splitlines()
                       if line.startswith("CMAKE_HOME_DIRECTORY:INTERNAL=")), None)
        # This tool owns each ABI output; CMake cannot reuse it after a source move.
        if source and Path(source).resolve() != SOURCE:
            shutil.rmtree(output)


def build(args, lock):
    sdk = sdk_directory(args.sdk)
    toolchain = lock["toolchain"]
    ndk = sdk / "ndk" / toolchain["ndk"]
    cmake = sdk / "cmake" / toolchain["cmake"] / "bin/cmake"
    ninja = cmake.with_name("ninja")
    readelf = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
    for needed in (ndk / "build/cmake/android.toolchain.cmake", cmake, ninja, readelf):
        if not needed.exists():
            raise SystemExit(f"Missing locked toolchain component: {needed}")
    report = {"toolchain": toolchain, "dependencies": lock["repositories"], "libraries": {}}
    for abi in args.abis:
        output = BUILD_ROOT / abi
        prepare_output(output)
        run(cmake, "-S", SOURCE, "-B", output, "-G", "Ninja",
            f"-DDEPS_ROOT={DEPS_ROOT}",
            f"-DCMAKE_MAKE_PROGRAM={ninja}",
            f"-DCMAKE_TOOLCHAIN_FILE={ndk}/build/cmake/android.toolchain.cmake",
            f"-DANDROID_ABI={abi}", f"-DANDROID_PLATFORM=android-{toolchain['api']}",
            "-DANDROID_STL=c++_static", "-DCMAKE_BUILD_TYPE=Release")
        run(cmake, "--build", output, "--target", "nuke_zygisk", "--parallel", args.jobs)
        library = output / "libnuke_zygisk.so"
        dynamic = subprocess.check_output([str(readelf), "-d", str(library)], text=True)
        if "libc++_shared" in dynamic:
            raise SystemExit(f"Unexpected shared C++ runtime: {library}")
        headers = subprocess.check_output([str(readelf), "-lW", str(library)], text=True)
        alignments = [int(line.split()[-1], 16) for line in headers.splitlines()
                      if line.lstrip().startswith("LOAD ")]
        if not alignments or any(value < 16384 for value in alignments):
            raise SystemExit(f"ELF load segments are not 16 KiB aligned: {library}")
        symbols = subprocess.check_output([str(readelf), "--dyn-syms", str(library)], text=True)
        if "zygisk_module_entry" not in symbols:
            raise SystemExit(f"Missing Zygisk entry symbol: {library}")
        report["libraries"][abi] = {
            "path": str(library.relative_to(ROOT)),
            "sha256": hashlib.sha256(library.read_bytes()).hexdigest(),
            "load_alignments": alignments,
            "needed": [line.split("[")[1].split("]")[0] for line in dynamic.splitlines()
                       if "(NEEDED)" in line],
        }
        print(f"Verified {abi}: {library}", flush=True)
        if args.export_relink:
            export_relink(Path(args.export_relink), abi, output, ninja, lock)
    report_path = BUILD_ROOT / "verification.json"
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")


def export_relink(destination, abi, output, ninja, lock):
    destination = destination.resolve() / abi
    destination.mkdir(parents=True, exist_ok=True)
    commands = subprocess.check_output([str(ninja), "-C", str(output), "-t", "commands", "nuke_zygisk"], text=True)
    command = next(line for line in reversed(commands.splitlines()) if "-o libnuke_zygisk.so" in line)
    arguments = shlex.split(command)
    if arguments[:2] == [":", "&&"]:
        arguments = arguments[2:]
    if arguments[-2:] == ["&&", ":"]:
        arguments = arguments[:-2]
    for index, argument in enumerate(arguments):
        candidate = output / argument
        if argument.endswith((".a", ".o")) and candidate.is_file():
            shutil.copy2(candidate, destination / candidate.name)
            arguments[index] = candidate.name
        elif argument.startswith("-Wl,--version-script="):
            shutil.copy2(SOURCE / "exports.map", destination / "exports.map")
            arguments[index] = "-Wl,--version-script=exports.map"
    (destination / "link.json").write_text(json.dumps(arguments, indent=2) + "\n")
    shutil.copy2(LOCK_FILE, destination / LOCK_FILE.name)
    (destination / "relink.py").write_text(
        "#!/usr/bin/env python3\n"
        "import json, os, pathlib, subprocess, sys\n"
        "root = pathlib.Path(__file__).resolve().parent\n"
        "os.chdir(root)\n"
        "args = json.loads((root / 'link.json').read_text())\n"
        "if len(sys.argv) > 1:\n"
        f"    toolchain = pathlib.Path(sys.argv[1]) / 'ndk/{lock['toolchain']['ndk']}/toolchains/llvm/prebuilt/linux-x86_64'\n"
        "    args[0] = str(toolchain / 'bin/clang++')\n"
        "    args = ['--sysroot=' + str(toolchain / 'sysroot') if item.startswith('--sysroot=') else item for item in args]\n"
        "subprocess.run(args, check=True)\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", help="Android SDK root; defaults to local.properties or Android environment")
    parser.add_argument("--abis", nargs="+", choices=ABIS, default=list(ABIS))
    parser.add_argument("--jobs", type=int, default=min(os.cpu_count() or 1, 8))
    parser.add_argument("--offline", action="store_true", help="Require all locked dependency sources in cache")
    parser.add_argument("--export-relink", help="Copy linkable object/archive inputs and a relink script into this directory")
    args = parser.parse_args()
    if args.jobs < 1:
        parser.error("--jobs must be positive")
    lock = json.loads(LOCK_FILE.read_text())
    BUILD_ROOT.mkdir(parents=True, exist_ok=True)
    with (BUILD_ROOT / "compile.lock").open("w") as native_lock:
        fcntl.flock(native_lock, fcntl.LOCK_EX)
        fetch_sources(lock, args.offline)
        build(args, lock)


if __name__ == "__main__":
    main()
