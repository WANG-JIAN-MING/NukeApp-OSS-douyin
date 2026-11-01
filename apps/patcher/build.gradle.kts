import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

abstract class PreparePatchEngine : Exec() {
    @get:OutputDirectory abstract val assetsDirectory: DirectoryProperty
}

abstract class PreparePatchLibraries : DefaultTask() {
    @get:InputFiles abstract val libraries: ConfigurableFileCollection
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction fun prepare() {
        val destination = outputDirectory.get().asFile
        destination.deleteRecursively()
        libraries.forEach { library ->
            val target = destination.resolve("${library.parentFile.name}/${library.name}")
            target.parentFile.mkdirs()
            library.copyTo(target, overwrite = true)
        }
    }
}

abstract class PreparePatchLicense : DefaultTask() {
    @get:InputFile abstract val license: RegularFileProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction fun prepare() {
        val destination = outputDirectory.get().asFile
        destination.deleteRecursively()
        val target = destination.resolve("third-party/GPL-3.0.txt")
        target.parentFile.mkdirs()
        license.get().asFile.copyTo(target)
    }
}

val buildRustNative = tasks.register<Exec>("buildRustNative") {
    inputs.files(fileTree("src/main/rust") { exclude("target/**") })
    inputs.files(rootProject.file("tools/build_rust.py"), rootProject.file("tools/build_native.py"))
    inputs.file(rootProject.file("apps/module/src/main/cpp/dependencies.lock.json"))
    outputs.files(listOf("arm64-v8a", "x86_64").map { rootProject.file("build/rust/$it/libnuke_patch_core.so") })
    commandLine("python3", rootProject.file("tools/build_rust.py"), "--crate", "patcher")
}

val checkRust = tasks.register<Exec>("checkRust") {
    group = "verification"
    commandLine("python3", rootProject.file("tools/build_rust.py"), "--crate", "patcher", "--check")
}

val prepareLspatch = tasks.register<PreparePatchEngine>("prepareLspatch") {
    inputs.files("lspatch.lock.json", rootProject.file("tools/prepare_lspatch.py"))
    outputs.file(rootProject.file("build/lspatch/lspatch-engine.jar"))
    assetsDirectory.set(rootProject.layout.buildDirectory.dir("lspatch/resources/assets"))
    commandLine("python3", rootProject.file("tools/prepare_lspatch.py"))
}

val preparePatchLibraries = tasks.register<PreparePatchLibraries>("preparePatchLibraries") {
    dependsOn(buildRustNative)
    outputDirectory.set(layout.buildDirectory.dir("generated/patcher/jniLibs"))
    libraries.from(listOf("arm64-v8a", "x86_64").map { rootProject.file("build/rust/$it/libnuke_patch_core.so") })
}

val preparePatchLicense = tasks.register<PreparePatchLicense>("preparePatchLicense") {
    license.set(rootProject.layout.projectDirectory.file("apps/module/src/main/resources/third-party/GPL-3.0.txt"))
    outputDirectory.set(layout.buildDirectory.dir("generated/patcher/resources"))
}

// Loaded by the root build from local.properties; null keeps the default debug key. This signs the patcher APK
// itself; the key the patcher signs patched apps with is generated on the device and never comes from here.
@Suppress("UNCHECKED_CAST")
val releaseSigning = rootProject.extra["releaseSigning"] as Map<String, Any>?

android {
    namespace = "io.github.nuke.oss.patcher"
    compileSdk = 37
    defaultConfig {
        applicationId = "io.github.nuke.oss.patcher"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = rootProject.version.toString()
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures { buildConfig = true; compose = true }
    signingConfigs {
        releaseSigning?.let { key ->
            create("release") {
                storeFile = key["storeFile"] as File
                storePassword = key["storePassword"] as String
                keyAlias = key["keyAlias"] as String
                keyPassword = key["keyPassword"] as String
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseSigning != null) signingConfig = signingConfigs.getByName("release")
        }
        // Debug builds carry the same key, so a debug build installs over a release one and the reverse.
        debug {
            if (releaseSigning != null) signingConfig = signingConfigs.getByName("release")
        }
    }
}

androidComponents.onVariants { variant ->
    variant.sources.jniLibs?.addGeneratedSourceDirectory(preparePatchLibraries) { it.outputDirectory }
    variant.sources.assets?.addGeneratedSourceDirectory(prepareLspatch) { it.assetsDirectory }
    variant.sources.resources?.addGeneratedSourceDirectory(preparePatchLicense) { it.outputDirectory }
}

kotlin { jvmToolchain(21) }

// The pinned patch engine already contains Guava's identical ListenableFuture API.
configurations.configureEach { exclude(group = "com.google.guava", module = "listenablefuture") }

dependencies {
    implementation(files(rootProject.file("build/lspatch/lspatch-engine.jar")).builtBy(prepareLspatch))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
}

// The integration test embeds the separately built module APK, never this patcher APK.
tasks.withType<Test>().configureEach {
    dependsOn(prepareLspatch)
    inputs.dir(rootProject.file("build/lspatch/resources")).withPropertyName("patcherResources")
    doFirst { classpath += files(rootProject.file("build/lspatch/resources")) }
    if (name == "testDebugUnitTest") {
        dependsOn(checkRust, ":apps:module:assembleDebug", ":fixtures:host:assembleDebug")
        val fixture = rootProject.file("fixtures/host/build/outputs/apk/debug/host-debug.apk")
        val module = rootProject.file("apps/module/build/outputs/apk/debug/module-debug.apk")
        inputs.files(fixture, module, rootProject.file("build/rust/target/debug/libnuke_patch_core.so"))
            .withPropertyName("patchRoundTripInputs")
        systemProperty("nuke.patch.fixture", fixture)
        systemProperty("nuke.patch.module", module)
        systemProperty("nuke.patch.report", rootProject.file("build/patch-fixture"))
        systemProperty("java.library.path", rootProject.file("build/rust/target/debug"))
    }
}
