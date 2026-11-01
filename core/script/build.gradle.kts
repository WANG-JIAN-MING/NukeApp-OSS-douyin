plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

kotlin { jvmToolchain(21) }

dependencies {
    api(project(":core:runtime"))
    // The JVM variant carries desktop QuickJS binaries for the unit tests; Android consumers resolve the
    // quickjs-kt-android variant of the same module instead.
    implementation(libs.quickjs.kt)
    implementation(libs.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(testFixtures(project(":core:runtime")))
}
