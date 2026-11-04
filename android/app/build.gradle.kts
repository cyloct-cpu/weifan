plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.weifan.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.weifan.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 52
        versionName = "3.8.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    // 减重：Go 协议层 libgojni.so 每 ABI 约 35–39MB，全打进去 APK 149MB。
    // 按 ABI 拆分后每个约 40–45MB，用户只下自己架构的包。
    // x86(32位) 已死设备不拆；模拟器测试用 x86_64 包。
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

// 本地 aar：Go 协议层（whatsmeow + gomobile 桥），由 weifan-protocol 仓库编译产出。
// 其余依赖依旧为零：JSON 用 org.json，网络在 Go 层。
dependencies {
    implementation(files("libs/weifan-protocol.aar"))
}
