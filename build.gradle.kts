import java.util.Properties

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}

allprojects {
    group = "io.github.nuke.oss"
    version = "0.1.0-dev"
}

// The release key both applications sign with, named by signing.storeFile, storePassword, keyAlias and keyPassword in
// the ignored local.properties; the keystore itself stays outside the repository. Without all four the applications
// keep the default debug key.
extra["releaseSigning"] = Properties().apply {
    file("local.properties").takeIf { it.isFile }?.inputStream()?.use(::load)
}.let { properties ->
    fun value(name: String) = properties.getProperty("signing.$name")?.trim()?.takeIf { it.isNotEmpty() }
    val storeFile = value("storeFile")?.let { path -> File(path).let { if (it.isAbsolute) it else file(path) } }?.takeIf { it.isFile }
    val storePassword = value("storePassword")
    val keyAlias = value("keyAlias")
    val keyPassword = value("keyPassword")
    if (storeFile == null || storePassword == null || keyAlias == null || keyPassword == null) null
    else mapOf("storeFile" to storeFile, "storePassword" to storePassword, "keyAlias" to keyAlias, "keyPassword" to keyPassword)
}

tasks.register<Exec>("checkBoundaries") {
    group = "verification"
    description = "Check module boundaries and framework API isolation."
    commandLine("python3", layout.projectDirectory.file("tools/check_boundaries.py").asFile)
}

tasks.register<Exec>("checkZygiskInstaller") {
    group = "verification"
    commandLine("python3", layout.projectDirectory.file("tools/check_zygisk_installer.py").asFile)
}

tasks.register<Exec>("checkSplitBoundaries") {
    group = "verification"
    description = "Reject module and patcher dependency or APK payload leakage."
    commandLine("python3", layout.projectDirectory.file("tools/test_split_boundaries.py").asFile)
}

tasks.register<Exec>("checkScriptSdk") {
    group = "verification"
    description = "Run the script SDK's tests: manifest rules, packaging and its command line."
    workingDir = layout.projectDirectory.dir("sdk/script-sdk").asFile
    // A pattern, not a directory: from Node 24 on, a directory argument is no longer searched for tests.
    commandLine("node", "--test", "test/**/*.test.js")
}

tasks.register("verify") {
    group = "verification"
    description = "Run Hook and patching contracts and build both applications."
    dependsOn(
        "checkBoundaries",
        "checkSplitBoundaries",
        "checkScriptSdk",
        ":apps:patcher:checkRust",
        "checkZygiskInstaller",
        "checkApks",
        ":core:runtime:test",
        ":core:script:test",
        ":core:loader:testDebugUnitTest",
        ":apps:module:assembleDebug",
        ":apps:module:testDebugUnitTest",
        ":apps:patcher:assembleDebug",
        ":apps:patcher:testDebugUnitTest",
        ":fixtures:host:assembleDebug",
    )
}

tasks.register("checkApks") {
    group = "verification"
    dependsOn("checkDebugApk")
}

for ((buildType, taskBuildType) in listOf("debug" to "Debug", "release" to "Release")) {
    tasks.register<Exec>("check${taskBuildType}Apk") {
        group = "verification"
        dependsOn(":apps:module:assemble$taskBuildType", ":apps:patcher:assemble$taskBuildType", ":fixtures:host:assemble$taskBuildType")
        val suffix = if (buildType == "release") "-unsigned" else ""
        commandLine(
            "python3", layout.projectDirectory.file("tools/check_apks.py").asFile,
            layout.projectDirectory.file("apps/module/build/outputs/apk/$buildType/module-$buildType$suffix.apk").asFile,
            layout.projectDirectory.file("fixtures/host/build/outputs/apk/$buildType/host-$buildType$suffix.apk").asFile,
            "--patcher", layout.projectDirectory.file("apps/patcher/build/outputs/apk/$buildType/patcher-$buildType$suffix.apk").asFile,
            "--report", layout.buildDirectory.file("reports/apks/$buildType.json").get().asFile,
        )
    }
}

tasks.register("verifyRelease") {
    group = "verification"
    description = "Run contracts and inspect both applications in Debug and R8 Release."
    dependsOn("verify", "checkReleaseApk")
}
