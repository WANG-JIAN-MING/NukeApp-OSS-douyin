pluginManagement {
    // KSP publishes no plugin marker for this version, so the plugin ID is mapped to its artifact, as in NukeApp.
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "com.google.devtools.ksp") {
                useModule("com.google.devtools.ksp:symbol-processing-gradle-plugin:${requested.version}")
            }
        }
    }
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        google()
        mavenCentral()
        maven("https://api.xposed.info/") {
            content { includeGroup("de.robv.android.xposed") }
        }
        maven("https://jitpack.io") {
            content { includeModule("com.github.YunJavaPro", "Silk-Codec-Android") }
        }
    }
}
rootProject.name = "NukeApp-OSS"
include(":apps:module", ":apps:patcher", ":core:runtime", ":core:script", ":core:loader", ":core:designsystem", ":fixtures:host", ":core:hook-ksp")
