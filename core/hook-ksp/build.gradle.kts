// Build-time only: the KSP processors that generate each host's feature registry and Dex query catalog. They never reach an APK.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(libs.ksp.api)
}
