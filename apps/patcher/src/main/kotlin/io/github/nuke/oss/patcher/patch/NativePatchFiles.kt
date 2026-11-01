package io.github.nuke.oss.patcher.patch

// This library is loaded only by the manager, never by a Hook entry.
internal object NativePatchFiles {
    private var loaded = false

    @Synchronized fun ensureLoaded() {
        if (!loaded) {
            System.loadLibrary("nuke_patch_core")
            loaded = true
        }
    }

    external fun stageApk(fd: Int, destination: String): String
    external fun inspectApk(path: String): String
    external fun verifyPatched(original: String, module: String, patched: String): String
}
