plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.nuke.oss.loader"
    compileSdk = 37
    defaultConfig { minSdk = 27 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin { jvmToolchain(21) }

dependencies {
    // The profile contract exposes runtime types to the application that implements it.
    api(project(":core:runtime"))
    // LSPass lifts the hidden-API restriction for the few framework classes the Zygisk route needs.
    implementation(libs.hiddenapibypass)
    // Only this module sees the framework APIs. The application links its entries without them, and
    // compileOnly keeps the API stubs out of the APK.
    compileOnly(libs.xposed.api)
    compileOnly(libs.libxposed.api)
    testImplementation(libs.junit)
    testImplementation(libs.xposed.api)
    testImplementation(libs.libxposed.api)
    testImplementation(testFixtures(project(":core:runtime")))
}

android.sourceSets.getByName("test").java.srcDir(rootProject.file("fixtures/host/src/main/java"))
