#!/usr/bin/env python3
# Regression checks for accidentally crossing the independent application boundaries.
import unittest

from check_apks import check_split_boundaries


class SplitApkBoundariesTest(unittest.TestCase):
    def check(self, module_names=(), module_types=(), patcher_names=(), patcher_types=()):
        check_split_boundaries(module_names, set(module_types), patcher_names, set(patcher_types))

    def test_disjoint_apps_allow_external_engine_payloads(self):
        self.check(
            module_names=("assets/xposed_init", "META-INF/xposed/module.prop", "module.prop"),
            module_types=("Lio/github/nuke/oss/backend/lsplant/LsplantBackend;", "Lio/github/libxposed/api/XposedModule;"),
            patcher_names=("assets/lspatch/metaloader.dex", "assets/lspatch/loader.dex"),
            patcher_types=("Lio/github/nuke/oss/patcher/patch/NativePatchFiles;", "Lorg/lsposed/lspatch/share/PatchConfig;"),
        )

    def test_module_rejects_patcher_implementations(self):
        for name in (
            "Lio/github/nuke/oss/patch/NativePatchFiles;", "Lio/github/nuke/oss/patcher/patch/NativePatchFiles;",
            "Lorg/lsposed/patch/ApkPatcher;", "Lorg/lsposed/lspatch/share/PatchConfig;", "Lcom/android/apksig/ApkSigner;",
            "Lcom/android/tools/build/apkzlib/zip/ZFile;",
        ):
            with self.subTest(name=name), self.assertRaises(AssertionError):
                self.check(module_types=(name,))

    def test_module_rejects_loader_payloads_and_signing_keys(self):
        for name in (
            "assets/lspatch/loader.dex", "assets/lspatch/so/arm64-v8a/liblspatch.so",
            "assets/keystore", "assets/new_keystore", "third-party/LSPatch-NOTICE.txt",
        ):
            with self.subTest(name=name), self.assertRaises(AssertionError):
                self.check(module_names=(name,))

    def test_patcher_rejects_hook_types_even_when_only_referenced(self):
        for name in (
            "Lio/github/nuke/oss/hook/HookService;", "Lio/github/nuke/oss/loader/zygisk/ZygiskEntry;",
            "Lio/github/nuke/oss/assembly/ModuleAssembly;", "Lio/github/nuke/oss/runtime/Runtime;",
            "Lio/github/nuke/oss/app/Host;", "Lio/github/nuke/oss/feature/Feature;",
            "Lio/github/nuke/oss/patch/NativePatchFiles;", "Lio/github/nuke/oss/MainActivity;",
            "Lde/robv/android/xposed/XposedBridge;", "Lio/github/libxposed/api/XposedModule;",
        ):
            with self.subTest(name=name), self.assertRaises(AssertionError):
                self.check(patcher_types=(name,))

    def test_patcher_rejects_module_installation_entries(self):
        for name in (
            "assets/xposed_init", "META-INF/xposed/module.prop", "META-INF/xposed/java_init.list",
            "META-INF/com/google/android/update-binary", "module.prop", "customize.sh", "zygisk/arm64-v8a.so",
        ):
            with self.subTest(name=name), self.assertRaises(AssertionError):
                self.check(patcher_names=(name,))

    def test_patcher_rejects_bundled_targets_and_modules(self):
        for name in (
            "assets/lspatch/origin.apk", "assets/lspatch/config.json",
            "assets/lspatch/modules/io.github.nuke.oss.apk", "assets/module.apk", "assets/target.APK",
        ):
            with self.subTest(name=name), self.assertRaises(AssertionError):
                self.check(patcher_names=(name,))


if __name__ == "__main__":
    unittest.main()
