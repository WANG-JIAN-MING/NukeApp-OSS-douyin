#!/usr/bin/env python3
# Inspect module/patcher DEX isolation, three Hook entries and APK resources.
import argparse
import hashlib
import json
import re
from pathlib import Path
import struct
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
RESOURCES = ROOT / "apps/module/src/main/resources"
LSPATCH_LOCK = ROOT / "apps/patcher/lspatch.lock.json"
LSPATCH_ENGINE = ROOT / "build/lspatch/lspatch-engine.jar"
LSPATCH_RESOURCES = ROOT / "build/lspatch/resources"
LICENSES = {
    "DexBuilder-LGPL-3.0.txt", "Dobby-Apache-2.0.txt", "GPL-3.0.txt",
    "LSPlant-LGPL-3.0.txt", "Zygisk-ISC.txt",
    "parallel-hashmap-Apache-2.0.txt", "quickjs-kt-Apache-2.0.txt", "Silk-Codec-Android-Apache-2.0.txt", "xDL-MIT.txt",
}
NATIVE_ARCHITECTURES = {
    "armeabi-v7a": (1, 40), "arm64-v8a": (2, 183), "x86": (1, 3), "x86_64": (2, 62),
}
MODULE_ABIS = ("arm64-v8a",)
PATCHER_ABIS = ("arm64-v8a", "x86_64")


def host_scope():
    # HostScope.kt is the authority; the native filter and Xposed resources must list the same packages.
    source = (ROOT / "apps/module/src/main/kotlin/io/github/nuke/oss/assembly/HostScope.kt").read_text()
    packages = re.findall(r'const val \w+ = "([^"]+)"', source)
    assert packages, "HostScope declares no packages"
    native = (ROOT / "apps/module/src/main/cpp/module.cpp").read_text()
    listed = re.search(r"kHostProcesses\[\] = \{([^}]*)\}", native)
    assert listed and re.findall(r'"([^"]+)"', listed.group(1)) == packages, "Native host filter differs from HostScope"
    # The child process rule: Douyin's non-isolated children, the same package and sandbox prefix as HostScope.processOf.
    child = re.search(r'kChildHost\[\] = "([^"]+)"', native)
    sandbox = re.search(r'kSandboxPrefix\[\] = "([^"]+)"', native)
    assert child and child.group(1) == "com.ss.android.ugc.aweme" and child.group(1) in packages, "Native child process host differs from HostScope"
    assert sandbox and f'SANDBOX_PREFIX = "{sandbox.group(1)}"' in source, "Native sandbox prefix differs from HostScope"
    array = (ROOT / "apps/module/src/main/res/values/arrays.xml").read_text()
    assert re.findall(r"<item>([^<]+)</item>", array.split('name="xposed_scope"')[1].split("</string-array>")[0]) == packages, "XP82 scope array differs from HostScope"
    return packages


HOST_SCOPE = host_scope()
PATCH_CONFIG_FIELDS = {
    "Lorg/lsposed/lspatch/share/PatchConfig;": {
        "useManager", "debuggable", "versionCode", "sigBypassLevel", "originalSignature",
        "appComponentFactory", "injectDex", "addedPermissions", "injectDocumentsProvider",
        "managerPackageName", "lspConfig",
    },
    "Lorg/lsposed/lspatch/share/LSPConfig;": {
        "instance", "API_CODE", "VERSION_CODE", "VERSION_NAME", "CORE_VERSION_CODE",
        "CORE_VERSION_NAME", "CORE_VERSION_HASH",
    },
}


def check_elf(library, abi):
    elf_class, machine = NATIVE_ARCHITECTURES[abi]
    header_size = 64 if elf_class == 2 else 52
    assert len(library) >= header_size, f"Truncated ELF library: {abi}"
    assert library[:7] == b"\x7fELF" + bytes((elf_class, 1, 1)), f"Incorrect ELF format: {abi}"
    assert struct.unpack_from("<HHI", library, 16) == (3, machine, 1), f"Incorrect ELF type or architecture: {abi}"
    if elf_class == 2:
        program_offset = struct.unpack_from("<Q", library, 32)[0]
        entry_size, count = struct.unpack_from("<HH", library, 54)
        minimum_entry_size = 56
    else:
        program_offset = struct.unpack_from("<I", library, 28)[0]
        entry_size, count = struct.unpack_from("<HH", library, 42)
        minimum_entry_size = 32
    assert entry_size >= minimum_entry_size and 0 < count <= 1024, f"Invalid ELF program headers: {abi}"
    assert program_offset + entry_size * count <= len(library), f"ELF program headers out of bounds: {abi}"
    loads = []
    for index in range(count):
        offset = program_offset + index * entry_size
        if struct.unpack_from("<I", library, offset)[0] != 1:
            continue
        if elf_class == 2:
            file_offset, address = struct.unpack_from("<QQ", library, offset + 8)
            file_size = struct.unpack_from("<Q", library, offset + 32)[0]
            alignment = struct.unpack_from("<Q", library, offset + 48)[0]
        else:
            file_offset, address = struct.unpack_from("<II", library, offset + 4)
            file_size = struct.unpack_from("<I", library, offset + 16)[0]
            alignment = struct.unpack_from("<I", library, offset + 28)[0]
        assert file_offset + file_size <= len(library), f"ELF load segment out of bounds: {abi}"
        assert alignment >= 16384 and alignment & (alignment - 1) == 0, f"Native ELF must support 16 KiB pages: {abi}"
        assert file_offset % alignment == address % alignment, f"ELF load alignment mismatch: {abi}"
        loads.append(alignment)
    assert loads, f"ELF has no load segments: {abi}"
    return loads


def check_patch_engine(archive):
    lock = json.loads(LSPATCH_LOCK.read_text())
    assert lock["schema"] == 1 and isinstance(lock["version"], str) and lock["version"], "Invalid LSPatch dependency lock"
    assert re.fullmatch(r"[0-9a-f]{64}", lock["asset"]["sha256"]), "LSPatch upstream SHA-256 is not locked"
    assert LSPATCH_ENGINE.is_file(), "Prepared LSPatch engine JAR is missing"
    expected = {
        "assets/lspatch/metaloader.dex", "assets/lspatch/loader.dex",
        *(f"assets/lspatch/so/{abi}/liblspatch.so" for abi in NATIVE_ARCHITECTURES),
    }
    assert set(lock["payloads"]) == expected, "LSPatch payload lock does not describe exactly the two loaders and four ABIs"
    names = set(archive.namelist())
    assert {name for name in names if name.startswith("assets/lspatch/") and not name.endswith("/")} == expected, "Unexpected or missing patcher engine payloads"
    forbidden = ("assets/keystore", "assets/new_keystore")
    assert not any(name.lower().startswith(forbidden) for name in names), "A bundled LSPatch test signing key leaked into the patcher"
    verified = {}
    with ZipFile(LSPATCH_ENGINE) as engine:
        engine_names = engine.namelist()
        assert len(engine_names) == len(set(engine_names)), "Prepared LSPatch JAR contains duplicate entries"
        assert not any(name.lower().startswith(forbidden) for name in engine_names), "Prepared LSPatch JAR contains a test signing key"
        assert not any(name.endswith(".dex") for name in engine_names), "DEX payloads must not enter R8 as Java library inputs"
    source_names = {path.relative_to(LSPATCH_RESOURCES).as_posix()
                    for path in LSPATCH_RESOURCES.rglob("*") if path.is_file()}
    assert {name for name in source_names if name.startswith("assets/lspatch/")} == expected, "Generated loader resources are inconsistent"
    assert not any(name.lower().startswith(forbidden) for name in source_names), "Generated loader resources contain a test signing key"
    for name in sorted(expected):
        payload = (LSPATCH_RESOURCES / name).read_bytes()
        payload_hash = hashlib.sha256(payload).hexdigest()
        assert len(payload) == lock["payloads"][name]["size"], f"Prepared LSPatch payload size differs from its lock: {name}"
        assert payload_hash == lock["payloads"][name]["sha256"], f"Prepared LSPatch payload hash differs from its lock: {name}"
        assert archive.read(name) == payload, f"Packaged engine payload differs from the locked generated resource: {name}"
        if name.endswith(".dex"):
            assert payload.startswith(b"dex\n"), f"Engine payload is not DEX: {name}"
        else:
            check_elf(payload, name.split("/")[3])
        verified[name] = payload_hash
    return {
        "version": lock["version"], "upstream_sha256": lock["asset"]["sha256"],
        "prepared_jar_sha256": hashlib.sha256(LSPATCH_ENGINE.read_bytes()).hexdigest(),
        "payload_sha256": verified, "bundled_test_signing_keys": False,
    }


def check_resources(archive, resources=RESOURCES):
    sources = {path.relative_to(resources).as_posix(): path for path in resources.rglob("*") if path.is_file()}
    expected = {
        "module.prop", "customize.sh", "META-INF/com/google/android/update-binary",
        "META-INF/com/google/android/updater-script",
        *(f"third-party/{name}" for name in LICENSES),
    }
    assert expected.issubset(sources), "Installation scripts or license sources are missing from main/resources"
    packaged = set(archive.namelist())
    licenses = {f"third-party/{name}" for name in LICENSES}
    assert {name for name in packaged if name.startswith("third-party/") and not name.endswith("/")} == licenses, "Unexpected packaged third-party licenses"
    verified = {}
    for name, source in sorted(sources.items()):
        assert name in packaged, f"Application resource missing from APK: {name}"
        original = source.read_bytes()
        assert archive.read(name) == original, f"Packaged resource differs from main/resources: {name}"
        verified[name] = hashlib.sha256(original).hexdigest()
    return verified


def dex_tables(data):
    if not data.startswith(b"dex\n"):
        raise ValueError("Unsupported DEX format")
    string_count, string_offset = struct.unpack_from("<II", data, 56)
    type_count, type_offset = struct.unpack_from("<II", data, 64)
    strings = []
    for i in range(string_count):
        offset = struct.unpack_from("<I", data, string_offset + i * 4)[0]
        while data[offset] & 0x80:
            offset += 1
        offset += 1
        end = data.index(0, offset)
        strings.append(data[offset:end].decode("utf-8", errors="replace"))
    types = [strings[struct.unpack_from("<I", data, type_offset + i * 4)[0]] for i in range(type_count)]
    return strings, types


def dex_types(data):
    _, types = dex_tables(data)
    class_count, class_offset = struct.unpack_from("<II", data, 96)
    defined = {types[struct.unpack_from("<I", data, class_offset + i * 32)[0]] for i in range(class_count)}
    return defined, set(types)


def dex_fields(data):
    strings, types = dex_tables(data)
    field_count, field_offset = struct.unpack_from("<II", data, 80)
    fields = {}
    for index in range(field_count):
        owner, _, name = struct.unpack_from("<HHI", data, field_offset + index * 8)
        fields.setdefault(types[owner], set()).add(strings[name])
    return fields


def dex_methods(data):
    strings, types = dex_tables(data)
    method_count, method_offset = struct.unpack_from("<II", data, 88)
    methods = {}
    for index in range(method_count):
        owner, _, name = struct.unpack_from("<HHI", data, method_offset + index * 8)
        methods.setdefault(types[owner], set()).add(strings[name])
    return methods


# The framework APIs are compileOnly, so R8 cannot see the overrides they declare and strips them unless the
# callbacks are kept by name. Check the release DEX for the methods each framework dispatches to.
FRAMEWORK_CALLBACKS = {
    "Lio/github/nuke/oss/backend/libxposed102/Libxposed102Backend$Hooker;": {"intercept"},
    "Lio/github/nuke/oss/backend/xposed82/FrameworkXposed82Driver$MethodHook;": {"beforeHookedMethod", "afterHookedMethod"},
    "Lio/github/nuke/oss/loader/libxposed102/Libxposed102Entry;": {"onModuleLoaded", "onPackageReady"},
    "Lio/github/nuke/oss/loader/xposed82/Xposed82Entry;": {"handleLoadPackage"},
}


def check_framework_callbacks(path):
    found = {}
    with ZipFile(path) as archive:
        for name in archive.namelist():
            if not re.fullmatch(r"classes(?:[2-9]|[1-9][0-9]+)?\.dex", name):
                continue
            for owner, methods in dex_methods(archive.read(name)).items():
                if owner in FRAMEWORK_CALLBACKS:
                    found.setdefault(owner, set()).update(methods)
    for owner, expected in FRAMEWORK_CALLBACKS.items():
        missing = expected - found.get(owner, set())
        assert not missing, f"Framework callback stripped or renamed after R8: {owner} {sorted(missing)}"
    return {owner: sorted(found.get(owner, set()) & expected) for owner, expected in FRAMEWORK_CALLBACKS.items()}


def check_patch_config_fields(path, defined):
    fields = {}
    with ZipFile(path) as archive:
        for name in archive.namelist():
            if not re.fullmatch(r"classes(?:[2-9]|[1-9][0-9]+)?\.dex", name):
                continue
            for owner, names in dex_fields(archive.read(name)).items():
                if owner in PATCH_CONFIG_FIELDS:
                    fields.setdefault(owner, set()).update(names)
    for owner, expected in PATCH_CONFIG_FIELDS.items():
        assert owner in defined, f"Reflective loader configuration class stripped or renamed: {owner}"
        missing = expected - fields.get(owner, set())
        assert not missing, f"Reflective loader configuration fields stripped or renamed on {owner}: {sorted(missing)}"
    return {owner: sorted(names) for owner, names in PATCH_CONFIG_FIELDS.items()}


def inspect(path):
    with ZipFile(path) as archive:
        defined = set()
        referenced = set()
        for name in archive.namelist():
            if name.startswith("classes") and name.endswith(".dex"):
                classes, types = dex_types(archive.read(name))
                defined.update(classes)
                referenced.update(types)
        if not defined:
            raise ValueError(f"No DEX definitions in {path}")
        return archive.namelist(), defined, referenced


def properties(data):
    result = {}
    for line in data.decode().splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        key, value = line.split("=", 1)
        assert key not in result, f"Duplicate module property: {key}"
        result[key] = value
    return result


def manifest_elements(data):
    # Read typed attributes instead of accepting a metadata name elsewhere in the string pool.
    assert struct.unpack_from("<H", data)[0] == 3, "Expected binary Android manifest"
    strings = []
    elements = []
    offset = struct.unpack_from("<H", data, 2)[0]
    while offset < len(data):
        kind, header_size, size = struct.unpack_from("<HHI", data, offset)
        assert size >= header_size >= 8 and offset + size <= len(data), "Invalid manifest chunk"
        if kind == 1:
            count, _, flags, strings_start = struct.unpack_from("<IIII", data, offset + 8)
            utf8 = bool(flags & 0x100)
            for index in range(count):
                cursor = offset + strings_start + struct.unpack_from("<I", data, offset + header_size + index * 4)[0]
                if utf8:
                    # UTF-8 pool strings start with UTF-16 length followed by byte length.
                    cursor += 2 if data[cursor] & 0x80 else 1
                    length = data[cursor]
                    cursor += 1
                    if length & 0x80:
                        length = ((length & 0x7f) << 8) | data[cursor]
                        cursor += 1
                    strings.append(data[cursor:cursor + length].decode("utf-8"))
                else:
                    length = struct.unpack_from("<H", data, cursor)[0]
                    cursor += 2
                    if length & 0x8000:
                        length = ((length & 0x7fff) << 16) | struct.unpack_from("<H", data, cursor)[0]
                        cursor += 2
                    strings.append(data[cursor:cursor + length * 2].decode("utf-16le"))
        elif kind == 0x102:
            extension = offset + header_size
            name = struct.unpack_from("<I", data, extension + 4)[0]
            attribute_start, attribute_size, count = struct.unpack_from("<HHH", data, extension + 8)
            attrs = {}
            for index in range(count):
                attribute = extension + attribute_start + index * attribute_size
                attr_name = struct.unpack_from("<I", data, attribute + 4)[0]
                value_type = data[attribute + 15]
                value = struct.unpack_from("<I", data, attribute + 16)[0]
                key = strings[attr_name]
                assert key not in attrs, f"Duplicate manifest attribute: {key}"
                attrs[key] = (value_type, strings[value] if value_type == 3 else value)
            elements.append((strings[name], attrs))
        offset += size
    return elements


def check_launcher(archive, classes, package, version_code):
    manifest = manifest_elements(archive.read("AndroidManifest.xml"))
    identity = next(attrs for tag, attrs in manifest if tag == "manifest")
    assert identity["package"] == (3, package), f"Wrong application ID: {package}"
    assert identity["versionCode"] == (16, version_code), f"Wrong application version code: {package}"
    application = next(attrs for tag, attrs in manifest if tag == "application")
    assert application.get("allowBackup") == (18, 0), f"Application backup must stay disabled: {package}"
    activities = []
    for tag, attrs in manifest:
        if tag not in ("activity", "activity-alias"):
            continue
        key = "targetActivity" if tag == "activity-alias" else "name"
        component = attrs[key][1]
        component = package + component if component.startswith(".") else component
        component = package + "." + component if "." not in component else component
        assert "L" + component.replace(".", "/") + ";" in classes, f"Activity has no DEX definition: {component}"
        activities.append(component)
    assert activities, f"UI activity is missing: {package}"
    assert any(tag == "action" and attrs.get("name") == (3, "android.intent.action.MAIN")
               for tag, attrs in manifest), f"Launch action is missing: {package}"
    assert any(tag == "category" and attrs.get("name") == (3, "android.intent.category.LAUNCHER")
               for tag, attrs in manifest), f"Launcher category is missing: {package}"
    return manifest, sorted(set(activities))


def check_native_libraries(archive, abis, libraries):
    expected = {f"lib/{abi}/{name}" for abi in abis for name in libraries}
    actual = {name for name in archive.namelist() if name.startswith("lib/") and name.endswith(".so")}
    assert actual == expected, f"Unexpected native libraries/ABIs: {sorted(actual ^ expected)}"
    return {name: check_elf(archive.read(name), name.split("/")[1]) for name in sorted(expected)}


def check_patcher_resources(archive):
    expected = {
        "third-party/LSPatch-NOTICE.txt": ROOT / "apps/patcher/src/main/resources/third-party/LSPatch-NOTICE.txt",
        "third-party/GPL-3.0.txt": RESOURCES / "third-party/GPL-3.0.txt",
    }
    actual = {name for name in archive.namelist() if name.startswith("third-party/") and not name.endswith("/")}
    assert actual == set(expected), "Unexpected or missing patcher license notices"
    result = {}
    for name, source in expected.items():
        content = source.read_bytes()
        assert archive.read(name) == content, f"Patcher license differs from its canonical source: {name}"
        result[name] = hashlib.sha256(content).hexdigest()
    return result


def check_split_boundaries(module_names, module_types, patcher_names, patcher_types):
    forbidden_module = ("Lio/github/nuke/oss/patch/", "Lio/github/nuke/oss/patcher/", "Lorg/lsposed/lspatch/", "Lorg/lsposed/patch/",
                        "Lcom/android/apksig/", "Lcom/android/tools/build/apkzlib/")
    assert not any(name.startswith(forbidden_module) for name in module_types), "Patcher implementation leaked into module DEX"
    assert not any(name.startswith(("assets/lspatch/", "assets/keystore", "assets/new_keystore"))
                   for name in module_names), "Patcher loader payload or signing key leaked into module APK"
    assert "third-party/LSPatch-NOTICE.txt" not in module_names, "Patcher notice leaked into module APK"
    forbidden_patcher = ("Lio/github/nuke/oss/runtime/", "Lio/github/nuke/oss/hook/", "Lio/github/nuke/oss/backend/", "Lio/github/nuke/oss/loader/",
                         "Lio/github/nuke/oss/assembly/", "Lio/github/nuke/oss/app/", "Lio/github/nuke/oss/feature/",
                         "Lde/robv/android/xposed/", "Lio/github/libxposed/")
    assert not any(name.startswith(forbidden_patcher) for name in patcher_types), "Module runtime, backend or framework dependency leaked into patcher DEX"
    # Every Nuke production class in the patcher belongs to its own application namespace.
    assert not any(name.startswith("Lio/github/nuke/oss/") and not name.startswith("Lio/github/nuke/oss/patcher/")
                   for name in patcher_types), "Patcher depends on module application classes"
    forbidden_resources = ("META-INF/xposed/", "META-INF/com/google/android/", "zygisk/")
    assert not any(name.startswith(forbidden_resources) for name in patcher_names), "Hook or Root metadata leaked into patcher APK"
    assert not {"assets/xposed_init", "module.prop", "customize.sh", "assets/lspatch/origin.apk",
                "assets/lspatch/config.json"}.intersection(patcher_names), "Module installation files or patched target leaked into patcher APK"
    assert not any(name.startswith("assets/lspatch/modules/") or name.lower().endswith(".apk")
                   for name in patcher_names), "Patcher must select external APKs, never bundle a module or target APK"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("module", type=Path)
    parser.add_argument("fixture", type=Path)
    parser.add_argument("--patcher", type=Path, required=True)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    names, module, referenced = inspect(args.module)
    patcher_names, patcher, patcher_references = inspect(args.patcher)
    fixture_names, fixture, fixture_references = inspect(args.fixture)
    for label, entries in (("module", names), ("patcher", patcher_names), ("fixture", fixture_names)):
        assert len(entries) == len(set(entries)), f"Duplicate APK entries are not allowed: {label}"
    check_split_boundaries(names, module | referenced, patcher_names, patcher | patcher_references)
    expected_entries = {
        "assets/xposed_init": "io.github.nuke.oss.loader.xposed82.Xposed82Entry",
        "META-INF/xposed/java_init.list": "io.github.nuke.oss.loader.libxposed102.Libxposed102Entry",
    }
    with ZipFile(args.module) as archive:
        verified_resources = check_resources(archive)
        metadata = [name for name in names if name.startswith("META-INF/xposed/") and not name.endswith("/")]
        assert sorted(metadata) == ["META-INF/xposed/java_init.list", "META-INF/xposed/module.prop", "META-INF/xposed/scope.list"], "Modern metadata missing or duplicated"
        for path, expected in expected_entries.items():
            assert path in names, f"Missing entry metadata: {path}"
            entry = archive.read(path).decode().strip()
            assert entry == expected, f"Incorrect entry: {entry}"
            assert "L" + expected.replace(".", "/") + ";" in module, f"Entry stripped or renamed: {entry}"
        assert properties(archive.read("META-INF/xposed/module.prop")) == {
            "minApiVersion": "102", "targetApiVersion": "102", "staticScope": "true", "autoHotReload": "false",
        }, "Unexpected modern module properties"
        assert archive.read("META-INF/xposed/scope.list").decode().split() == HOST_SCOPE, "Unexpected modern scope"
        manifest, activities = check_launcher(archive, module, "io.github.nuke.oss", 4)
        legacy = {attrs["name"][1]: attrs for tag, attrs in manifest if tag == "meta-data" and "name" in attrs and str(attrs["name"][1]).startswith("xposed")}
        assert legacy.get("xposedmodule", {}).get("value") == (18, 0xffffffff), "XP82 module flag is missing or disabled"
        assert legacy.get("xposedminversion", {}).get("value") == (16, 82), "Incorrect XP82 minimum API"
        scope = legacy.get("xposedscope", {}).get("resource")
        assert scope and scope[0] == 1 and scope[1] != 0, "XP82 scope must reference the packaged scope array"
        assert legacy.get("xposeddescription", {}).get("value", (0, ""))[1], "XP82 description is missing"
        dex_files = [name for name in names if name.startswith("classes") and name.endswith(".dex")]
        assert "classes.dex" in dex_files and all(re.fullmatch(r"classes(?:[2-9]|[1-9][0-9]+)?\.dex", name) for name in dex_files), "Invalid DEX payload layout"
        indexes = sorted(1 if name == "classes.dex" else int(name[7:-4]) for name in dex_files)
        assert indexes == list(range(1, len(indexes) + 1)) and len(indexes) <= 128, "DEX payload numbering must be continuous and bounded"
        assert sum(archive.getinfo(name).file_size for name in dex_files) <= 64 * 1024 * 1024, "DEX payload exceeds native memory limit"
        props = properties(archive.read("module.prop"))
        assert props["id"] == "nukeapp-oss" and props["versionCode"] == "4", "Wrong Root module identity"
        verified_native = check_native_libraries(
            archive, MODULE_ABIS,
            ("libnuke_zygisk.so", "libnuke_protocol_core.so", "libdexkit.so", "libandroidx.graphics.path.so",
             "libquickjs.so", "libsilk_codec.so"),
        )
    framework_callbacks = check_framework_callbacks(args.module)
    for required in ("Lio/github/nuke/oss/backend/xposed82/Xposed82Backend;",
                     "Lio/github/nuke/oss/backend/libxposed102/Libxposed102Backend;",
                     "Lio/github/nuke/oss/backend/lsplant/LsplantBackend;",
                     "Lio/github/nuke/oss/loader/zygisk/ZygiskEntry;",
                     "Lio/github/nuke/oss/backend/lsplant/JniLsplantDriver;",
                     "Lio/github/nuke/oss/backend/lsplant/JniLsplantDriver$NativeCallback;",
                     "Lio/github/nuke/oss/app/tiktok/protocol/NativeProtocol;",
                     "Lio/github/nuke/oss/app/tiktok/protocol/NativeProtocolException;"):
        assert required in module, f"Hook backend or JNI class stripped or renamed: {required}"
    with ZipFile(args.patcher) as archive:
        patch_manifest, patch_activities = check_launcher(archive, patcher, "io.github.nuke.oss.patcher", 1)
        assert not any(tag == "meta-data" and str(attrs.get("name", (0, ""))[1]).startswith("xposed")
                       for tag, attrs in patch_manifest), "Patcher must not contain Xposed manifest metadata"
        patch_resources = check_patcher_resources(archive)
        verified_engine = check_patch_engine(archive)
        patch_native = check_native_libraries(archive, PATCHER_ABIS, ("libnuke_patch_core.so", "libandroidx.graphics.path.so"))
        for abi in PATCHER_ABIS:
            library = archive.read(f"lib/{abi}/libnuke_patch_core.so")
            for method in ("stageApk", "inspectApk", "verifyPatched"):
                symbol = f"Java_io_github_nuke_oss_patcher_patch_NativePatchFiles_{method}".encode()
                assert symbol + b"\0" in library, f"Independent patcher JNI export missing: {abi}: {method}"
            assert b"Java_io_github_nuke_oss_patch_NativePatchFiles_" not in library, "Obsolete module JNI namespace remains in patcher library"
    assert "Lio/github/nuke/oss/patcher/patch/NativePatchFiles;" in patcher, "Patcher JNI class stripped or renamed"
    verified_config_fields = check_patch_config_fields(args.patcher, patcher)
    forbidden = ("Lde/robv/android/xposed/", "Lio/github/libxposed/", "Lio/github/nuke/oss/hook/testing/")
    # The API 102 service client is packaged for the module screen; the framework API itself never is.
    service = "Lio/github/libxposed/service/"
    assert not any(name.startswith(forbidden) and not name.startswith(service) for name in module), "API stub or test framework leaked into module APK"
    assert any(name.startswith("Lde/robv/android/xposed/") for name in referenced), "XP82 framework references are missing"
    assert any(name.startswith("Lio/github/libxposed/api/") for name in referenced), "Modern framework references are missing"
    assert not any(name.startswith(forbidden) for name in fixture_references), "Framework API or testing transport leaked into fixture"
    assert "Lio/github/nuke/oss/fixture/ProbeTarget;" in fixture, "Fixture target stripped or renamed"
    assert "assets/xposed_init" not in fixture_names and not any(name.startswith("META-INF/xposed/") for name in fixture_names), "Fixture must remain an ordinary Android host"
    with ZipFile(args.fixture) as archive:
        fixture_manifest = manifest_elements(archive.read("AndroidManifest.xml"))
    fixture_identity = next(attrs for tag, attrs in fixture_manifest if tag == "manifest")
    assert fixture_identity["package"] == (3, "io.github.nuke.oss.fixture"), "Wrong fixture application ID"
    assert not any(tag == "meta-data" and str(attrs.get("name", (0, ""))[1]).startswith("xposed")
                   for tag, attrs in fixture_manifest), "Fixture must not contain Xposed manifest metadata"
    result = {
        "status": "passed", "module": str(args.module), "patcher": str(args.patcher), "fixture": str(args.fixture),
        "module_class_count": len(module), "patcher_class_count": len(patcher), "fixture_class_count": len(fixture),
        "modes": ["xp82", "libxposed102", "zygisk"], "entries": expected_entries,
        "framework_stubs_packaged": False, "apps_isolated": True, "module_embedded_in_patcher": False,
        "zygisk_payload_packaged": True, "payload_dex_count": len(dex_files), "native_abis": list(MODULE_ABIS), "patcher_native_abis": list(PATCHER_ABIS),
        "source_resource_sha256": verified_resources, "third_party_license_count": len(LICENSES),
        "manager_activities": activities, "native_load_alignments": verified_native,
        "patcher_activities": patch_activities, "patcher_resource_sha256": patch_resources,
        "patcher_native_load_alignments": patch_native, "patch_jni_class_preserved": True,
        "patch_engine": verified_engine, "patch_config_fields": verified_config_fields,
        "framework_callbacks": framework_callbacks,
    }
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
