# Native symbols bind these methods by class and method name.
-keep class io.github.nuke.oss.patcher.patch.NativePatchFiles { *; }
# Gson writes these names into the patched app's cross-process loader contract.
-keep class org.lsposed.lspatch.share.PatchConfig { *; }
-keep class org.lsposed.lspatch.share.LSPConfig { *; }
# apkzlib ships generated AutoValue implementations; source-only annotations are absent.
-dontwarn com.google.auto.value.AutoValue
-dontwarn com.google.auto.value.AutoValue$Builder
