plugins { alias(libs.plugins.android.application) }

android {
    namespace = "io.github.nuke.oss.fixture"
    compileSdk = 37
    defaultConfig { minSdk = 27 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    defaultConfig {
        applicationId = "io.github.nuke.oss.fixture"
        targetSdk = 37
        versionCode = 1
        versionName = rootProject.version.toString()
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

kotlin { jvmToolchain(21) }

dependencies {

    testImplementation(libs.junit)
}
