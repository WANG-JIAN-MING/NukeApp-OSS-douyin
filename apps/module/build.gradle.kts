import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import java.security.KeyStore
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

abstract class PrepareNativeLibraries : DefaultTask() {
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

// Loaded by the root build from local.properties; null keeps the default debug key.
@Suppress("UNCHECKED_CAST")
val releaseSigning = rootProject.extra["releaseSigning"] as Map<String, Any>?

// The SHA-256 of the release certificate, compiled into the native library for the module signer check. Without a
// release key there is none, and the check reports the build as unconfigured instead of enforcing anything.
val releaseSignerDigest: String? = releaseSigning?.let { key ->
    val store = KeyStore.getInstance(KeyStore.getDefaultType())
    (key["storeFile"] as File).inputStream().use { store.load(it, (key["storePassword"] as String).toCharArray()) }
    val certificate = checkNotNull(store.getCertificate(key["keyAlias"] as String)) { "The release key alias has no certificate" }
    MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }
}

// The only ABIs the module's native code is built for; the module screen compares hosts against the same list.
val nativeAbis = listOf("arm64-v8a")

val buildZygiskNative = tasks.register<Exec>("buildZygiskNative") {
    inputs.files(fileTree("src/main/cpp"))
    inputs.file(rootProject.file("tools/build_native.py"))
    outputs.files(nativeAbis.map { rootProject.file("build/native/$it/libnuke_zygisk.so") })
    commandLine("python3", rootProject.file("tools/build_native.py"))
}

val buildProtocolNative = tasks.register<Exec>("buildProtocolNative") {
    inputs.files(fileTree("src/main/rust") { exclude("target/**") })
    inputs.files(rootProject.file("tools/build_rust.py"), rootProject.file("tools/build_native.py"))
    inputs.file(rootProject.file("apps/module/src/main/cpp/dependencies.lock.json"))
    outputs.files(nativeAbis.map { rootProject.file("build/rust/$it/libnuke_protocol_core.so") })
    inputs.property("signerDigest", releaseSignerDigest.orEmpty())
    releaseSignerDigest?.let { environment("NUKE_MODULE_SIGNER_SHA256", it) }
    commandLine("python3", rootProject.file("tools/build_rust.py"), "--crate", "module")
}

val checkProtocolRust = tasks.register<Exec>("checkProtocolRust") {
    group = "verification"
    inputs.files(fileTree("src/main/rust") { exclude("target/**") })
    // The package reader's tests read the SDK's shared manifests and its sample package.
    inputs.dir(rootProject.file("sdk/script-sdk/test/fixtures"))
    inputs.file(rootProject.file("tools/build_rust.py"))
    outputs.file(rootProject.file("build/rust/target/debug/libnuke_protocol_core.so"))
    commandLine("python3", rootProject.file("tools/build_rust.py"), "--crate", "module", "--check")
}

val prepareNativeLibraries = tasks.register<PrepareNativeLibraries>("prepareNativeLibraries") {
    dependsOn(buildZygiskNative, buildProtocolNative)
    outputDirectory.set(layout.buildDirectory.dir("generated/native/jniLibs"))
    libraries.from(nativeAbis.flatMap { abi ->
        listOf(rootProject.file("build/native/$abi/libnuke_zygisk.so"), rootProject.file("build/rust/$abi/libnuke_protocol_core.so"))
    })
}

android {
    namespace = "io.github.nuke.oss"
    compileSdk = 37
    // The NDK tools/build_native.py builds with, so AGP finds its strip tool: without it a machine that has only this NDK ships
    // every library with its debug symbols.
    ndkVersion = "29.0.13113456"
    defaultConfig {
        minSdk = 27
        ndk { abiFilters += nativeAbis }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    defaultConfig {
        applicationId = "io.github.nuke.oss"
        targetSdk = 37
        versionCode = 4
        versionName = rootProject.version.toString()
        buildConfigField("String", "BUILD_ID", "\"${rootProject.version}\"")
        buildConfigField("String[]", "NATIVE_ABIS", nativeAbis.joinToString(prefix = "{", postfix = "}") { "\"$it\"" })
    }
    buildFeatures { buildConfig = true; compose = true }
    // Module resources run inside host processes, whose own IDs use package 0x7f; a separate package ID keeps a module
    // ID from ever resolving to a host resource if the two tables meet.
    androidResources {
        additionalParameters +=
            arrayOf(
                "--allow-reserved-package-id",
                "--package-id",
                "0x79",
            )
    }
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
    variant.sources.jniLibs?.addGeneratedSourceDirectory(prepareNativeLibraries) { it.outputDirectory }
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":core:runtime"))
    implementation(project(":core:loader"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:script"))
    // Generates each host's generatedHookEntries and generatedDexQueries at build time; nothing of it is packaged.
    ksp(project(":core:hook-ksp"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coroutines.android)
    implementation(libs.dexkit)
    implementation(libs.kavaref.core)
    implementation(libs.kavaref.extension)
    // The 1.1 core leaves its logger and member accessors to a platform artifact.
    implementation(libs.kavaref.android)
    // The client of the API 102 framework service only; the framework APIs themselves stay compileOnly in :core:loader.
    implementation(libs.libxposed.service)
    implementation(libs.fastkv)
    implementation(libs.gifkt)
    // Audio conversion for chosen voice files; its native library exists for arm64-v8a only.
    implementation(libs.silk.codec)
    implementation(libs.dexmaker)
    // Renders a script's README on the scripts page, with highlighted code blocks, as the legacy page did.
    implementation(libs.markdown.renderer.m3)
    implementation(libs.markdown.renderer.code)
    testImplementation(libs.junit)
    testImplementation(testFixtures(project(":core:runtime")))
}

android.sourceSets.getByName("test").java.srcDir(rootProject.file("fixtures/host/src/main/java"))

// The JVM tests run real QuickJS: the Android variant's libraries cannot load on the desktop, so unit tests get the
// JVM variant of the same release, which carries desktop binaries.
configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("io.github.dokar3:quickjs-kt-android"))
            .using(module("io.github.dokar3:quickjs-kt-jvm:${libs.versions.quickjsKt.get()}"))
    }
}

// The JVM tests load the host-built Rust core to exercise the real JNI bindings.
tasks.withType<Test>().configureEach {
    dependsOn(checkProtocolRust)
    // ScriptApiContractTest compares the sessions' modules with the SDK's declarations.
    inputs.file(rootProject.file("sdk/script-sdk/types/index.d.ts"))
    systemProperty("java.library.path", rootProject.file("build/rust/target/debug"))
}
