plugins {
    alias(mihonx.plugins.android.application)
    alias(mihonx.plugins.spotless)
}

android {
    namespace = "mihon.readingapp.compat.fixture"

    defaultConfig {
        applicationId = "mihon.readingapp.compat.fixture"
        versionCode = 1
        versionName = "1.6.1"
    }

    flavorDimensions += "sourceContract"
    productFlavors {
        create("legacy") {
            dimension = "sourceContract"
            applicationIdSuffix = ".legacy"
            versionName = "1.4.1"
            // Legacy extensions derive the supported contract from versionName, with no new metadata.
            manifestPlaceholders["sourceClass"] = "mihon.readingapp.compat.fixture.LegacyFixtureFactory"
        }
        create("modern") {
            dimension = "sourceContract"
            applicationIdSuffix = ".modern"
            versionName = "1.6.1"
            manifestPlaceholders["sourceClass"] = "mihon.readingapp.compat.fixture.ModernFixtureFactory"
        }
        create("unsupported") {
            dimension = "sourceContract"
            applicationIdSuffix = ".unsupported"
            versionName = "1.5.1"
            manifestPlaceholders["sourceClass"] = "mihon.readingapp.compat.fixture.ModernFixtureFactory"
        }
        create("malformed") {
            dimension = "sourceContract"
            applicationIdSuffix = ".malformed"
            versionName = "1.6.1"
            manifestPlaceholders["sourceClass"] = ""
        }
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
}

dependencies {
    // APKs must bind to the host's API classes; do not package a duplicate source API/runtime.
    compileOnly(projects.sourceApi)
    compileOnly(projects.core.common)
    compileOnly(libs.rxJava)
    compileOnly(libs.kotlinx.coroutines.core)
}

// Kotlin's automatic standard-library dependency must remain host-provided too.
// A delegate-last APK loader cannot share suspend interfaces with a second Continuation class.
configurations.configureEach {
    if (name.endsWith("RuntimeClasspath")) {
        exclude(group = "org.jetbrains.kotlin")
    }
}
