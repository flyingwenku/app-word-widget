// ===== 应用层 =====
// 约定：不得在此文件写入流水线 / 签名 / 环境相关逻辑，
//      保证 app/ 模块可被整体替换而流水线无需改动。

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.fred.wordwidget"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fred.wordwidget"
        minSdk = 21          // Android 5.0：面向旧手机，门槛必须放低
        targetSdk = 35

        // 版本管理：每次发布都要递增，先改这里再提交。
        versionCode = 5
        versionName = "1.4"

        resourceConfigurations.add("zh")
    }

    buildFeatures {
        // AGP 8 起 BuildConfig 默认不生成，用到（如显示版本号）必须显式开启
        buildConfig = true
    }

    buildTypes {
        getByName("debug") {
            // 优化点：debug 关闭 R8 与资源缩减（构建中最耗时的一步）
            isMinifyEnabled = false
            isShrinkResources = false
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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

dependencies {
    // 依赖最小化：小组件用系统 AppWidgetProvider + RemoteViews，
    // 存储用 SharedPreferences / JSON，全程不引第三方库。
    implementation("androidx.core:core-ktx:1.15.0")

    // 只跑汇单元测试：同步的稳定 uuid 取值和合并规则必须锁死，
    // 任一处被改坏都会在 CI 上直接失败（本地不一定装了 SDK，靠云构建把关）
    testImplementation("junit:junit:4.13.2")
}
