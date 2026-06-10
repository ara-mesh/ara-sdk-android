plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.aramesh.sdk.v1"
    compileSdk = 35

    ndkVersion = "26.3.11579264"

    defaultConfig {
        minSdk = 29
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake { arguments("-DANDROID_STL=c++_shared") }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
            version = "3.22.1"
        }
    }

    sourceSets["main"].jniLibs.srcDirs("src/main/jniLibs")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    compileOnly(libs.otel.api)
}
