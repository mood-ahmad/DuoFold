plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.duofold"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.duofold"
        minSdk = 29
        targetSdk = 37
        versionCode = 6
        versionName = "0.5.0"
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
        externalNativeBuild {
            cmake { cppFlags += "-std=c++17" }
        }
        ndk { abiFilters.add("arm64-v8a") }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    ndkVersion = "25.2.9519653"

    flavorDimensions += "api"
    productFlavors {
        create("compat") {
            dimension = "api"
            minSdk = 29
        }
        create("standard") {
            dimension = "api"
            minSdk = 34
        }
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        create("release") {
            storeFile = file("duofold.keystore")
            storePassword = "duofold2026"
            keyAlias = "duofold"
            keyPassword = "duofold2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
}

dependencies {
    implementation(project(":motion"))
    implementation(project(":backdrop"))
    implementation(project(":miuix:miuix-core"))
    implementation(project(":miuix:miuix-squircle"))
    implementation(project(":miuix:miuix-shader"))
    implementation(project(":miuix:miuix-ui"))
    implementation(project(":miuix:miuix-preference"))
    implementation(project(":miuix:miuix-blur"))
    implementation(project(":miuix:miuix-navigation3-ui"))

    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.runtime)
    implementation(libs.androidx.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("androidx.annotation:annotation:1.9.1")

    testImplementation("junit:junit:4.13.2")
}