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
        versionCode = 1
        versionName = "0.1.0"
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

// 故意不引入任何第三方依赖：
// 1. 构建快、失败面小（只依赖 android.jar 与 kotlin-stdlib）；
// 2. JSON 用系统自带的 org.json；
// 3. 网络用 HttpURLConnection，不引 OkHttp。
dependencies {
}
