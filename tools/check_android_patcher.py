#!/usr/bin/env python3
# Exercise production patching on Android ART without installing or launching an application.
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import tempfile
import time
import uuid
import zipfile
from build_native import sdk_directory


ROOT = Path(__file__).resolve().parents[1]
MAIN_CLASS = "io.github.nuke.oss.tools.AndroidPatchProbe"
BUILD_TOOLS = "37.0.0"
SDK_API = 37
ABI = "arm64-v8a"


def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def run(command, **options):
    return subprocess.run(command, check=True, text=True, capture_output=True, **options).stdout.strip()


def patcher_classes():
    candidates = [
        ROOT / "apps/patcher/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes",
        ROOT / "apps/patcher/build/tmp/kotlin-classes/debug",
    ]
    for candidate in candidates:
        if (candidate / "io/github/nuke/oss/patcher/patch/PatchEngine.class").is_file():
            return candidate
    raise FileNotFoundError("Build :apps:patcher:assembleDebug before running the Android probe")


def kotlin_stdlib():
    catalog = (ROOT / "gradle/libs.versions.toml").read_text()
    version = re.search(r'^kotlin\s*=\s*"([^"]+)"', catalog, re.MULTILINE).group(1)
    cache = Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle")))
    candidates = sorted((cache / f"caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/{version}").glob("*/*.jar"))
    if len(candidates) != 1:
        raise FileNotFoundError(f"Expected one cached Kotlin stdlib {version}; resolve the Debug build first")
    return candidates[0]


def compile_probe(sdk, directory):
    javac = shutil.which("javac")
    if not javac:
        raise FileNotFoundError("A JDK 21 javac is required")
    javac = Path(javac).resolve()
    version = run([str(javac), "-version"])
    if not version.startswith("javac 21"):
        raise ValueError(f"Expected JDK 21, found {version}")
    android = next((path for path in (
        sdk / f"platforms/android-{SDK_API}/android.jar",
        sdk / f"platforms/android-{SDK_API}.0/android.jar",
    ) if path.is_file()), sdk / f"platforms/android-{SDK_API}/android.jar")
    d8 = sdk / f"build-tools/{BUILD_TOOLS}/d8"
    engine = ROOT / "build/lspatch/lspatch-engine.jar"
    for path in (android, d8, engine):
        if not path.is_file():
            raise FileNotFoundError(path)
    classes = patcher_classes()
    stdlib = kotlin_stdlib()
    classpath = os.pathsep.join(str(path) for path in (classes, android, stdlib, engine))
    compiled = directory / "classes"
    compiled.mkdir()
    run([str(javac), "--release", "8", "-cp", classpath, "-d", str(compiled), str(ROOT / "tools/AndroidPatchProbe.java")])
    # Only the helper's classfiles are program inputs; production classes and Android stubs are library inputs.
    helper_classes = sorted(compiled.rglob("*.class"))
    if not helper_classes or any("AndroidPatchProbe" not in path.name for path in helper_classes):
        raise ValueError("Unexpected Java program input while compiling Android probe")
    archive = directory / "probe.jar"
    environment = dict(os.environ)
    environment["JAVA_HOME"] = str(javac.parent.parent)
    run([str(d8), "--release", "--min-api", "28", "--lib", str(android),
         "--classpath", str(classes), "--classpath", str(stdlib), "--classpath", str(engine),
         "--output", str(archive), *map(str, helper_classes)], env=environment)
    with zipfile.ZipFile(archive) as helper:
        if helper.namelist() != ["classes.dex"]:
            raise ValueError("Probe archive must contain only its helper DEX")
    return archive, {"javac": version, "build_tools": BUILD_TOOLS, "compile_sdk": SDK_API,
                     "production_classes": str(classes.relative_to(ROOT)), "kotlin_stdlib": stdlib.name,
                     "engine_sha256": digest(engine)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path)
    parser.add_argument("--patcher", type=Path, default=ROOT / "apps/patcher/build/outputs/apk/debug/patcher-debug.apk")
    parser.add_argument("--module", type=Path, default=ROOT / "apps/module/build/outputs/apk/debug/module-debug.apk")
    parser.add_argument("--fixture", type=Path, default=ROOT / "fixtures/host/build/outputs/apk/debug/host-debug.apk")
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts/split-patcher/device")
    args = parser.parse_args()
    sdk = sdk_directory(args.sdk).resolve()
    patcher = args.patcher.resolve()
    module = args.module.resolve()
    fixture = args.fixture.resolve()
    output = args.output.resolve()
    for path in (patcher, module, fixture):
        if not path.is_file():
            raise FileNotFoundError(path)
    adb = sdk / "platform-tools/adb"
    if not adb.is_file():
        raise FileNotFoundError(adb)
    devices = [line.split() for line in run([str(adb), "devices", "-l"]).splitlines()[1:] if line.strip()]
    if len(devices) != 1 or devices[0][1] != "device":
        raise ValueError("Exactly one authorized Android device must be connected")
    serial = devices[0][0]
    base = [str(adb), "-s", serial]

    def shell(command, **options):
        return run([*base, "shell", command], **options)

    device = {"api": int(shell("getprop ro.build.version.sdk")), "abi": shell("getprop ro.product.cpu.abi"),
              "page_size": int(shell("getconf PAGE_SIZE")), "fingerprint": shell("getprop ro.build.fingerprint")}
    if device["api"] < 28 or device["abi"] != ABI or device["page_size"] != 4096:
        raise ValueError("This probe requires Android API 28+, arm64-v8a and a 4096-byte page size")
    remote = f"/data/local/tmp/nukeapp-patch-probe-{uuid.uuid4().hex}"
    if not re.fullmatch(r"/data/local/tmp/nukeapp-patch-probe-[0-9a-f]{32}", remote):
        raise ValueError("Refusing an unexpected remote directory")
    output.mkdir(parents=True, exist_ok=True)
    created = False
    report = {"schema": 2, "device": device, "patcher_sha256": digest(patcher), "module_sha256": digest(module), "fixture_sha256": digest(fixture),
              "installed_app_touched": False, "remote_directory_removed": False, "ok": False}
    started = time.monotonic()
    try:
        with tempfile.TemporaryDirectory(prefix="nukeapp-android-patcher-") as temporary:
            local = Path(temporary)
            probe, compiler = compile_probe(sdk, local)
            report["compiler"] = compiler
            native = local / "libnuke_patch_core.so"
            with zipfile.ZipFile(patcher) as apk:
                native.write_bytes(apk.read(f"lib/{ABI}/{native.name}"))
            report["native_sha256"] = digest(native)
            shell(f"umask 077; mkdir -m 700 {shlex.quote(remote)}")
            created = True
            for source, name in ((probe, "probe.jar"), (patcher, "patcher.apk"), (module, "module.apk"), (fixture, "fixture.apk"), (native, native.name)):
                run([*base, "push", str(source), f"{remote}/{name}"])
            shell(f"chmod 0444 {remote}/probe.jar {remote}/patcher.apk {remote}/module.apk {remote}/fixture.apk && chmod 0500 {remote}/{native.name}")
            # Android 14+ rejects writable DEX containers. No APK is installed and no package is started.
            command = (f"CLASSPATH={shlex.quote(remote + '/probe.jar:' + remote + '/patcher.apk')} "
                       f"timeout -k 10 240 /system/bin/app_process /system/bin --nice-name=nukeapp-patch-probe {MAIN_CLASS} {shlex.quote(remote)}")
            try:
                result = subprocess.run([*base, "shell", command], text=True, capture_output=True, timeout=270)
            except subprocess.TimeoutExpired as failure:
                (output / "device.log").write_text((failure.stdout or b"").decode() if isinstance(failure.stdout, bytes) else (failure.stdout or ""))
                raise
            (output / "device.log").write_text(result.stdout + result.stderr)
            if result.returncode:
                raise RuntimeError(f"Android patcher probe failed with status {result.returncode}; see {output / 'device.log'}")
            run([*base, "pull", f"{remote}/verification.json", str(output / "verification.json")])
            verification = json.loads((output / "verification.json").read_text())
            if verification.get("ok") is not True or verification.get("key_store_type", "").upper() != "BKS":
                raise ValueError("Android probe did not verify a BKS-signed patched APK")
            filename = verification["output_file"]
            if not re.fullmatch(r"[A-Za-z0-9._-]+\.apk", filename):
                raise ValueError("Unexpected probe output filename")
            patched = output / "fixture-android-patched.apk"
            run([*base, "pull", f"{remote}/output/{filename}", str(patched)])
            if digest(patched) != verification["patched"]["sha256"]:
                raise ValueError("Pulled APK does not match the on-device Rust verification hash")
            report["patched_sha256"] = digest(patched)
            report["ok"] = True
    finally:
        cleanup_failure = None
        if created:
            # Only our exact randomized directory is removed, including its transient private signing material.
            try:
                shell(f"rm -rf -- {shlex.quote(remote)}")
                if shell(f"if test -e {shlex.quote(remote)}; then echo retained; else echo removed; fi") != "removed":
                    raise RuntimeError("The probe directory was not removed")
                report["remote_directory_removed"] = True
            except (OSError, subprocess.SubprocessError, RuntimeError) as failure:
                cleanup_failure = failure
                report["ok"] = False
                report["cleanup_error"] = "Remote cleanup failed; reconnect the same device and remove this exact path: " + remote
        report["elapsed_seconds"] = round(time.monotonic() - started, 3)
        (output / "device-run.json").write_text(json.dumps(report, indent=2) + "\n")
        if cleanup_failure:
            raise RuntimeError(report["cleanup_error"]) from cleanup_failure
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
