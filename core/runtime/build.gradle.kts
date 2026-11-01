plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    `java-test-fixtures`
}

kotlin { jvmToolchain(21) }

dependencies {
    testImplementation(libs.junit)
}
