plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.family.tvtimer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.family.tvtimer"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "1.4.3"
    }

    // 签名文件放在仓库里（私有仓库），任何电脑编译出来的签名都一样，可以直接覆盖安装。
    // 这是 Android 标准 debug 证书格式，密码固定为 android。
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 家用自签：直接用 debug 证书签名，方便侧载安装
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/versions/**")
    }
}

dependencies {
    implementation("dev.mobile:dadb:1.2.10")
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
