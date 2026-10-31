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
        versionCode = 15
        versionName = "1.3.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
