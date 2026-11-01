plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.nuke.oss.designsystem"
    compileSdk = 37
    defaultConfig { minSdk = 27 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures { compose = true }
}

kotlin { jvmToolchain(21) }

dependencies {
    // The components expose Compose types in their signatures, so consumers see them through this module.
    api(platform(libs.compose.bom))
    api(libs.compose.material3)
    // Backdrop blur behind cards and the blurred home background.
    implementation(libs.haze)
    implementation(libs.haze.blur)
}
