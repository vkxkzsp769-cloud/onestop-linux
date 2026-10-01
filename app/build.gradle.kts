plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.onestop.linux"
    compileSdk = 34
    ndkVersion = project.properties["ndkVersion"] as String

    defaultConfig {
        applicationId = "com.onestop.linux"
        // Android 10+（方案 §4.2）
        minSdk = 29
        // 关键：沿用 Termux 策略，保证应用私有目录可执行原生二进制（方案 ADR-003）
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0-mvp"
        ndk { abiFilters += "arm64-v8a" }          // 固定仅 ARM64
    }

    // 关键：不要压缩 .so，且安装时解压到 nativeLibraryDir（方案 §4.3）
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "META-INF/*.kotlin_module",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
            )
        }
    }
    androidResources {
        // ★ 真机踩坑：曾把 "tar" 放进 noCompress，结果 AGP 对 assets 里的 .tar.gz 做内容嗅探，
        //   在 APK 里把它**解压**成纯 .tar（101.8 MB）并改名，导致运行时按原名找不到资源。
        //   现在只排除本身已是压缩格式、且不参与嗅探重命名的扩展名。
        noCompress += listOf("zst", "zip")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
        }
        debug {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":terminal-emulator"))
    implementation(project(":terminal-view"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.webkit:webkit:1.12.1")          // addDocumentStartJavaScript
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")    // ServiceDetector 探测
}
