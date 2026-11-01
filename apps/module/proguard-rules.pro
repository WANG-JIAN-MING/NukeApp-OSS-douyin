# All three entry points belong to the same APK. Optional framework APIs are compileOnly in the loader module only.
-keep class io.github.nuke.oss.loader.zygisk.ZygiskEntry { public static void bootstrap(java.lang.String); }
-keep class io.github.nuke.oss.backend.lsplant.JniLsplantDriver { *; }
-keep class io.github.nuke.oss.backend.lsplant.JniLsplantDriver$NativeCallback { *; }
-keep class io.github.nuke.oss.loader.xposed82.Xposed82Entry { *; }
-keep class io.github.nuke.oss.loader.libxposed102.Libxposed102Entry { *; }
# The application never sees the framework APIs, so R8 meets their types only as unresolved references from the loader.
-dontwarn de.robv.android.xposed.**
-dontwarn io.github.libxposed.**
# The loader finds the application profile by name.
-keep class io.github.nuke.oss.assembly.ApplicationLoaderProfile { public static final ** INSTANCE; }
# The XP82 entry reports the framework into the module's own process by name.
-keep class io.github.nuke.oss.status.ModuleStatus { public static void reportLegacyFramework(java.lang.String); }
# Keep the small adapters identifiable in the final archive for backend-presence checks.
-keep,allowoptimization class io.github.nuke.oss.backend.xposed82.Xposed82Backend { *; }
-keep,allowoptimization class io.github.nuke.oss.backend.libxposed102.Libxposed102Backend { *; }
-keep,allowoptimization class io.github.nuke.oss.backend.lsplant.LsplantBackend { *; }
# The compileOnly framework APIs are absent from R8's library classpath, so R8 cannot see that these callbacks override
# framework methods and strips them from synthetic or anonymous classes. Keep the named implementations whole.
-keep class io.github.nuke.oss.backend.libxposed102.Libxposed102Backend$Hooker { *; }
-keep class io.github.nuke.oss.backend.xposed82.FrameworkXposed82Driver$MethodHook { *; }
# The Rust protocol core binds by JNI name and throws NativeProtocolException by constructor signature.
-keep class io.github.nuke.oss.app.tiktok.protocol.NativeProtocol { native <methods>; }
-keep class io.github.nuke.oss.app.tiktok.protocol.NativeProtocolException { <init>(int, java.lang.String); }
# The generated request-handler subclass calls dexmaker's ProxyBuilder helpers by name.
-keep class com.android.dx.stock.ProxyBuilder { *; }
-dontwarn com.android.dx.**
# HostSettingsActivity is in no manifest: SettingsEntry creates it by name inside the host process.
-keep class io.github.nuke.oss.ui.hostsettings.HostSettingsActivity { <init>(); }
