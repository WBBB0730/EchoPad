import groovy.json.JsonSlurper
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}
dependencies {
    implementation(project(":shared"))

    implementation(libs.androidx.activity.compose)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)
}

// 两端共用一个版本号，只写在 desktop/package.json（ADR-0008）。
val appVersion = providers.fileContents(rootProject.layout.projectDirectory.file("../desktop/package.json")).asText
    .map { (JsonSlurper().parseText(it) as Map<*, *>)["version"] as String }
    .get()
val (major, minor, patch) = appVersion.split(".").map(String::toInt)
check(minor < 100 && patch < 100) { "versionCode 由版本号算出，次版本号和补丁号都不能超过 99：$appVersion" }

// 发版签名只在 CI 上提供（docs/prd/release.md），本机的 release 构建不签名。
val releaseKeystore = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull

android {
    namespace = "com.wbbb.echopad.mobile"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.wbbb.echopad.mobile"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        // 加 1：versionCode 必须是正整数，而还没发版时版本号是 0.0.0。
        versionCode = major * 10000 + minor * 100 + patch + 1
        versionName = appVersion
    }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").get()
            }
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}