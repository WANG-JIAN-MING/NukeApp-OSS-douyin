#!/usr/bin/env python3
# Check the module, independent patcher, runtime and fixture boundaries without a device.
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
PREFIX = "io.github.nuke.oss"
MODULES = {
    ":apps:module": "apps/module",
    ":apps:patcher": "apps/patcher",
    ":core:runtime": "core/runtime",
    ":core:script": "core/script",
    ":core:loader": "core/loader",
    ":core:designsystem": "core/designsystem",
    ":fixtures:host": "fixtures/host",
    ":core:hook-ksp": "core/hook-ksp",
}
BACKENDS = {
    "xposed82": f"{PREFIX}.backend.xposed82",
    "libxposed102": f"{PREFIX}.backend.libxposed102",
    "zygisk": f"{PREFIX}.backend.lsplant",
}
LOADERS = {mode: f"{PREFIX}.loader.{mode}" for mode in BACKENDS}
# Each host lives in its own package under HOSTS; HOSTS.shared holds host-independent helpers.
HOSTS = f"{PREFIX}.app"
SHARED_HOST = f"{HOSTS}.shared"
FRAMEWORKS = {
    "xposed82": "de.robv.android.xposed",
    "libxposed102": "io.github.libxposed.api",
}
# The client of the API 102 framework service is not the framework API: only the module's status package reads it.
FRAMEWORK_SERVICE = "io.github.libxposed.service"
MODULE_STATUS = f"{PREFIX}.status"


def host_scope(app):
    # HostScope.kt is the authority for the scoped host packages.
    source = (app / "src/main/kotlin/io/github/nuke/oss/assembly/HostScope.kt").read_text()
    return re.findall(r'const val \w+ = "([^"]+)"', source)


def within(name, parent):
    return name == parent or name.startswith(parent + ".")


def references(content, namespace):
    return re.search(r"\b" + re.escape(namespace) + r"(?:\b|\.)", content) is not None


def translations(app):
    # values/ is the English default; every values-<language>/ must translate exactly its translatable strings,
    # and the per-app language list must name exactly those languages.
    errors = []
    res = app / "src/main/res"

    def strings(folder, translatable_only):
        found = set()
        for path in sorted(folder.glob("*.xml")):
            for item in ET.parse(path).getroot().iter("string"):
                if not translatable_only or item.get("translatable") != "false":
                    found.add(item.get("name"))
        return found

    default = strings(res / "values", True)
    languages = sorted(path.name.removeprefix("values-") for path in res.glob("values-*")
                       if re.fullmatch(r"values-[a-z]{2}(-r[A-Z]{2})?", path.name))
    for language in languages:
        translated = strings(res / f"values-{language}", False)
        if translated != default:
            errors.append(f"values-{language} strings differ from values/: missing {sorted(default - translated)}, "
                          f"extra {sorted(translated - default)}")
    android = "{http://schemas.android.com/apk/res/android}name"
    config = res / "xml/locales_config.xml"
    listed = [item.get(android) for item in ET.parse(config).getroot().iter("locale")] if config.is_file() else []
    expected = ["en", *(language.replace("-r", "-") for language in languages)]
    if sorted(listed) != sorted(expected):
        errors.append(f"locales_config.xml lists {listed}, expected {expected}")
    return errors


# The repository is licensed per directory: NukePatcher is GPL-3.0 because it embeds LSPatch, and everything else is
# Apache-2.0. Each license text has one source, and the other copies must stay byte for byte the same.
def licensing(root, app, patcher):
    errors = []
    gpl_source = app / "src/main/resources/third-party/GPL-3.0.txt"
    copies = (
        (patcher / "LICENSE", gpl_source, "NukePatcher's LICENSE must be the module's GPL-3.0 text"),
        (root / "sdk/script-sdk/LICENSE", root / "LICENSE", "The script SDK's LICENSE must be the root Apache-2.0 text"),
    )
    for copy, source, message in copies:
        if not copy.is_file() or not source.is_file() or copy.read_bytes() != source.read_bytes():
            errors.append(message)
    root_license = root / "LICENSE"
    if not root_license.is_file() or not root_license.read_text().lstrip().startswith("Apache License"):
        errors.append("The root LICENSE must be the Apache License 2.0")
    if not (root / "NOTICE").is_file():
        errors.append("Missing the root NOTICE with the copyright statement")
    package = root / "sdk/script-sdk/package.json"
    if not package.is_file() or '"license": "Apache-2.0"' not in package.read_text():
        errors.append("The script SDK package must declare the Apache-2.0 license")
    # The Apache-2.0 side may not use the GPL side: no source outside the patcher may name its package.
    for module in (app, root / "core", root / "sdk", root / "fixtures"):
        for path in module.rglob("*"):
            if path.suffix not in (".kt", ".java", ".rs", ".kts") or "build" in path.parts or "test" in path.parts:
                continue
            if "io.github.nuke.oss.patcher" in path.read_text(errors="ignore"):
                errors.append(f"Apache-2.0 code must not use the GPL-3.0 patcher: {path.relative_to(root)}")
    return errors


def check(root):
    errors = []
    settings = (root / "settings.gradle.kts").read_text()
    included = []
    for group in re.findall(r"\binclude\s*\(([^)]*)\)", settings):
        included.extend(re.findall(r'["\'](:[^"\']+)["\']', group))
    if sorted(included) != sorted(MODULES):
        errors.append(f"Expected exactly the modules {sorted(MODULES)}, found: {included}")

    for module, directory in MODULES.items():
        build = root / directory / "build.gradle.kts"
        if not build.is_file():
            errors.append(f"Missing module build file: {build.relative_to(root)}")
            continue
        content = build.read_text()
        dependencies = re.findall(r'\bproject\(\s*["\'](:[^"\']+)["\']\s*\)', content)
        allowed = {
            ":apps:module": {":core:runtime", ":core:loader", ":core:designsystem", ":core:script", ":core:hook-ksp"},
            ":core:loader": {":core:runtime"},
            ":core:script": {":core:runtime"},
        }.get(module, set())
        unexpected = sorted(set(dependencies) - allowed)
        if unexpected:
            errors.append(f"Unexpected project dependencies in {module}: {unexpected}")
        if module == ":apps:module":
            for dependency in (":core:runtime", ":core:loader", ":core:designsystem", ":core:script"):
                if not re.search(r'\bimplementation\(project\(\s*["\']' + dependency + r'["\']\s*\)\)', content):
                    errors.append(f"The application must implement {dependency}")
            # The registry processor runs at build time only and must never become a packaged dependency.
            for configuration in re.findall(r'(?m)^\s*(\w+)\(project\(\s*["\']:core:hook-ksp["\']', content):
                if configuration != "ksp":
                    errors.append(f"The hook entry processor may only be a ksp dependency: {configuration}")
        if module == ":core:loader" and not re.search(r'\bapi\(project\(\s*["\']:core:runtime["\']\s*\)\)', content):
            errors.append("The loader must expose the pure JVM runtime contracts it implements")
        if module == ":core:runtime":
            if "libs.plugins.kotlin.jvm" not in content or re.search(r"com\.android|plugins\.android", content):
                errors.append("The runtime must remain a Kotlin/JVM build unit")
        if module == ":core:script":
            # The engine tests run the desktop QuickJS binaries, which only a JVM build unit resolves.
            if "libs.plugins.kotlin.jvm" not in content or re.search(r"com\.android|plugins\.android", content):
                errors.append("The script engine must remain a Kotlin/JVM build unit")
        for quoted, plain, library in re.findall(
            r'(?m)^\s*(?:"([^"\n]+)"|([a-zA-Z]+))\(libs\.(xposed\.api|libxposed\.api)\)', content
        ):
            configuration = quoted or plain
            if module != ":core:loader" or configuration not in {"compileOnly", "testImplementation"}:
                errors.append(f"Framework API dependency must be loader compileOnly or testImplementation: {module} ({configuration})")

    host_root = root / MODULES[":apps:module"] / "src/main/kotlin" / HOSTS.replace(".", "/")
    hosts = sorted(f"{HOSTS}.{path.name}" for path in host_root.iterdir() if path.is_dir() and path.name != "shared")

    for directory in MODULES.values():
        base = root / directory / "src/main"
        for path in sorted(base.rglob("*")):
            if path.suffix not in {".kt", ".java"}:
                continue
            content = path.read_text()
            declaration = re.search(r"(?m)^\s*package\s+([\w.]+)", content)
            if not declaration:
                errors.append(f"Missing explicit source package: {path.relative_to(root)}")
                continue
            package = declaration[1]
            relative = path.relative_to(root)
            if not within(package, PREFIX):
                errors.append(f"Unexpected production package: {relative}: {package}")
            for mode, framework in FRAMEWORKS.items():
                if references(content, framework) and not any(
                    within(package, permitted) for permitted in (LOADERS[mode], BACKENDS[mode])
                ):
                    errors.append(f"Framework API outside its own loader/backend: {relative}: {framework}")
            if references(content, FRAMEWORK_SERVICE) and not (directory == "apps/module" and within(package, MODULE_STATUS)):
                errors.append(f"Framework service client outside the module status package: {relative}")
            if directory == "core/runtime":
                forbidden = ("android", "androidx", *FRAMEWORKS.values(), f"{PREFIX}.assembly",
                             f"{PREFIX}.fixture", HOSTS, f"{PREFIX}.loader", f"{PREFIX}.ui",
                             f"{PREFIX}.BuildConfig", f"{PREFIX}.R", f"{PREFIX}.patch",
                             f"{PREFIX}.patcher", *BACKENDS.values())
                if any(references(content, namespace) for namespace in forbidden):
                    errors.append(f"Platform, concrete backend or host dependency in the pure JVM runtime: {relative}")
                continue
            if directory == "core/script":
                # Script engine: it sees the shared runtime contracts and nothing of Android, a host, a loader or an app.
                imports = re.findall(r"(?m)^\s*import\s+([\w.*]+)", content)
                runtime = tuple(f"{PREFIX}.{name}" for name in ("common", "core", "hook", "runtime", "script"))
                if not within(package, f"{PREFIX}.script"):
                    errors.append(f"Script engine package must be {PREFIX}.script: {relative}: {package}")
                if any(within(name, "android") or within(name, "androidx") for name in imports) or any(
                    within(name, PREFIX) and not any(within(name, allowed) for allowed in runtime) for name in imports
                ):
                    errors.append(f"Script engine must depend only on the runtime contracts: {relative}")
                continue
            if directory == "core/designsystem":
                # Pure UI library: it knows Compose and nothing of the runtime, the loaders, any host or any app.
                imports = re.findall(r"(?m)^\s*import\s+([\w.*]+)", content)
                if not within(package, f"{PREFIX}.designsystem"):
                    errors.append(f"Design system package must be {PREFIX}.designsystem: {relative}: {package}")
                if any(within(name, PREFIX) and not within(name, f"{PREFIX}.designsystem") for name in imports):
                    errors.append(f"Design system must not depend on application, runtime or loader classes: {relative}")
                continue
            if directory == "core/hook-ksp":
                # Build-time processor: it names module types only as strings and links nothing of the project.
                imports = re.findall(r"(?m)^\s*import\s+([\w.*]+)", content)
                if not within(package, f"{PREFIX}.ksp"):
                    errors.append(f"Hook entry processor package must be {PREFIX}.ksp: {relative}: {package}")
                if any(within(name, PREFIX) or within(name, "android") or within(name, "androidx") for name in imports):
                    errors.append(f"Hook entry processor must not depend on project or Android classes: {relative}")
                continue
            if directory == "fixtures/host":
                imports = re.findall(r"(?m)^\s*import\s+([\w.*]+)", content)
                if any(within(name, PREFIX) and not within(name, f"{PREFIX}.fixture") for name in imports):
                    errors.append(f"Fixture must remain independent of the module runtime: {relative}")
                continue
            imports = re.findall(r"(?m)^\s*import\s+([\w.*]+)", content)
            if directory == "apps/patcher":
                if not within(package, f"{PREFIX}.patcher"):
                    errors.append(f"Patcher production package must belong to its own application: {relative}")
                if any(within(name, PREFIX) and not within(name, f"{PREFIX}.patcher") for name in imports):
                    errors.append(f"Patcher must not depend on module or runtime classes: {relative}")
                for namespace in (*BACKENDS.values(), *LOADERS.values(), f"{PREFIX}.assembly",
                                  f"{PREFIX}.runtime", f"{PREFIX}.feature", f"{PREFIX}.app"):
                    # Quoted entry names may be checked as external APK metadata without linking those classes.
                    code = re.sub(r'"(?:[^"\\]|\\.)*"', '""', content)
                    if references(code, namespace):
                        errors.append(f"Patcher must not link a concrete module implementation: {relative}: {namespace}")
                continue
            if directory == "core/loader":
                # The application profile is found by name, so quoted names are not links.
                code = re.sub(r'"(?:[^"\\]|\\.)*"', '""', content)
                for namespace in (f"{PREFIX}.assembly", f"{PREFIX}.fixture", HOSTS, f"{PREFIX}.ui", f"{PREFIX}.patch",
                                  f"{PREFIX}.patcher", f"{PREFIX}.BuildConfig", f"{PREFIX}.R"):
                    if references(code, namespace):
                        errors.append(f"Loader must not link application code: {relative}: {namespace}")
            if directory == "apps/module":
                for mode in BACKENDS:
                    for namespace in (BACKENDS[mode], LOADERS[mode]):
                        if references(content, namespace):
                            errors.append(f"The application must not link concrete loaders or backends: {relative}: {namespace}")
                for namespace in (f"{PREFIX}.patch", f"{PREFIX}.patcher", "org.lsposed.lspatch", "org.lsposed.patch",
                                  "com.android.apksig", "com.android.tools.build.apkzlib"):
                    if references(content, namespace):
                        errors.append(f"Patching implementation must stay in the independent patcher: {relative}: {namespace}")
            own_host = next((host for host in hosts if within(package, host)), None)
            for host in hosts:
                if host == own_host or not references(content, host):
                    continue
                if own_host or within(package, SHARED_HOST):
                    errors.append(f"Host packages may share code only through {SHARED_HOST}: {relative}: {host}")
                elif not within(package, f"{PREFIX}.assembly"):
                    errors.append(f"Only the assembly may reference a concrete host: {relative}: {host}")
            if within(package, HOSTS) and not own_host and not within(package, SHARED_HOST):
                errors.append(f"Host code must live in {SHARED_HOST} or one host package: {relative}")
            own_backend = next((mode for mode, namespace in BACKENDS.items() if within(package, namespace)), None)
            own_loader = next((mode for mode, namespace in LOADERS.items() if within(package, namespace)), None)
            for mode in BACKENDS:
                if references(content, BACKENDS[mode]) and mode not in {own_backend, own_loader}:
                    errors.append(f"Concrete backend outside its own loader/backend: {relative}: {mode}")
                if references(content, LOADERS[mode]) and mode != own_loader:
                    errors.append(f"Optional entry dependency outside its own loader: {relative}: {mode}")
            if own_backend and any(references(content, namespace) for namespace in (
                f"{PREFIX}.assembly", f"{PREFIX}.fixture", HOSTS, f"{PREFIX}.ui"
            )):
                errors.append(f"Backend depends on application assembly, host or UI: {relative}")

    app = root / MODULES[":apps:module"]
    app_build = (app / "build.gradle.kts").read_text()
    for directory in ("native", "packaging"):
        if (root / directory).exists():
            errors.append(f"Application sources must not use the old top-level directory: {directory}")
    for filename in ("CMakeLists.txt", "module.cpp", "dependencies.lock.json", "exports.map"):
        if not (app / "src/main/cpp" / filename).is_file():
            errors.append(f"Missing application Native source: src/main/cpp/{filename}")
    if (app / "src/main/cpp/build").exists():
        errors.append("Native build output and dependency cache must stay outside the source set")
    if (app / "src/main/cpp/licenses").exists():
        errors.append("Packaged license texts must have one source in src/main/resources/third-party")
    if re.search(r"\bresources\s*\??\.\s*(?:addStaticSourceDirectory|addGeneratedSourceDirectory|srcDir|srcDirs|setSrcDirs)\s*(?:\(|=)", app_build):
        errors.append("Static application resources must use the default src/main/resources source set")
    if re.search(r"\bresources\s*\{[^}]*\b(?:srcDir|srcDirs|setSrcDirs)\s*(?:\(|=)", app_build, re.DOTALL):
        errors.append("Static application resources must not override the default source directory")
    for filename in (
        "module.prop", "customize.sh", "META-INF/com/google/android/update-binary",
        "META-INF/com/google/android/updater-script", "third-party/DexBuilder-LGPL-3.0.txt",
        "third-party/Dobby-Apache-2.0.txt", "third-party/GPL-3.0.txt",
        "third-party/LSPlant-LGPL-3.0.txt", "third-party/Zygisk-ISC.txt",
        "third-party/parallel-hashmap-Apache-2.0.txt", "third-party/Silk-Codec-Android-Apache-2.0.txt",
        "third-party/xDL-MIT.txt",
    ):
        if not (app / "src/main/resources" / filename).is_file():
            errors.append(f"Missing application installation resource: src/main/resources/{filename}")
    if re.search(r"\b(productFlavors|flavorDimensions)\b", app_build):
        errors.append("The application must produce one universal APK without loader flavors")
    patcher = root / MODULES[":apps:patcher"]
    patcher_build_path = patcher / "build.gradle.kts"
    if patcher_build_path.is_file():
        patcher_build = patcher_build_path.read_text()
        if re.search(r"\b(productFlavors|flavorDimensions)\b", patcher_build):
            errors.append("The patcher must not introduce backend flavors")
        for name, expected in (("applicationId", f"{PREFIX}.patcher"), ("namespace", f"{PREFIX}.patcher")):
            if not re.search(r'\b' + name + r'\s*=\s*"' + re.escape(expected) + '"', patcher_build):
                errors.append(f"Independent patcher {name} must be {expected}")
    if re.search(r"(?:prepareLspatch|buildRustNative|lspatch-engine|nuke_patch_core)", app_build):
        errors.append("Module must not build or package the independent patch engine")
    # The module owns only the protocol core crate; the patcher's file core must not live next to it.
    module_manifest = app / "src/main/rust/Cargo.toml"
    if not module_manifest.is_file() or "nuke-protocol-core" not in module_manifest.read_text():
        errors.append("Module Rust crate must be nuke-protocol-core")
    for path in ("src/main/rust/src/apk.rs", "lspatch.lock.json", "src/main/resources/third-party/LSPatch-NOTICE.txt"):
        if (app / path).exists():
            errors.append(f"Patcher source must leave the module: {path}")
    for path in ("src/main/rust", "lspatch.lock.json", "src/main/resources/third-party/LSPatch-NOTICE.txt"):
        if not (patcher / path).exists():
            errors.append(f"Missing independent patcher source: {path}")
    for path in ("src/main/assets/xposed_init", "src/main/resources/META-INF/xposed",
                 "src/main/resources/module.prop", "src/main/resources/customize.sh", "src/main/cpp"):
        if (patcher / path).exists():
            errors.append(f"Hook/Root entry must not enter the independent patcher: {path}")
    patch_manifest = patcher / "src/main/AndroidManifest.xml"
    if not patch_manifest.is_file() or "xposed" in patch_manifest.read_text().lower():
        errors.append("Patcher manifest must exist without Xposed metadata")
    loader = root / MODULES[":core:loader"]
    loader_build_path = loader / "build.gradle.kts"
    loader_build = loader_build_path.read_text() if loader_build_path.is_file() else ""
    for library in ("xposed.api", "libxposed.api"):
        if not re.search(r'(?m)^\s*compileOnly\(libs\.' + re.escape(library) + r'\)', loader_build):
            errors.append(f"Loader is missing provided framework API: {library}")
        if re.search(r'libs\.' + re.escape(library), app_build):
            errors.append(f"Framework API must not reach the application build: {library}")
    # The module screen asks the package manager about exactly the scoped hosts.
    queried = [item.get("{http://schemas.android.com/apk/res/android}name")
               for item in ET.parse(app / "src/main/AndroidManifest.xml").getroot().iter("package")]
    if queried != host_scope(app):
        errors.append(f"Module manifest queries must list the HostScope packages: {queried}")
    for path, expected in {
        "src/main/assets/xposed_init": f"{LOADERS['xposed82']}.Xposed82Entry",
        "src/main/resources/META-INF/xposed/java_init.list": f"{LOADERS['libxposed102']}.Libxposed102Entry",
        "src/main/resources/META-INF/xposed/scope.list": "\n".join(host_scope(app)),
    }.items():
        entry = app / path
        if not entry.is_file() or entry.read_text().strip() != expected:
            errors.append(f"Unexpected unified module metadata: {path}")
    for namespace, filename in (
        (LOADERS["xposed82"], "Xposed82Entry.kt"),
        (LOADERS["libxposed102"], "Libxposed102Entry.kt"),
        (LOADERS["zygisk"], "ZygiskEntry.kt"),
    ):
        entry = loader / "src/main/kotlin" / namespace.replace(".", "/") / filename
        if not entry.is_file():
            errors.append(f"Missing universal APK entry: {entry.relative_to(root)}")
    errors.extend(translations(app))
    errors.extend(licensing(root, app, patcher))
    return errors


if __name__ == "__main__":
    failures = check(ROOT)
    if failures:
        raise SystemExit("\n".join(failures))
    print("Gradle modules, independent APK source layout, package boundaries, framework isolation and translations: PASS")
