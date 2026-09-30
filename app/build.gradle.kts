import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    // AGP 9.0 起 Kotlin 内置，无需 kotlin.android；Compose 编译器插件仍需显式应用
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "cn.tealc.wwtcompanion"
    compileSdk = 37

    defaultConfig {
        applicationId = "cn.tealc.wwtcompanion"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
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

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // 网络 + 协程（JSON 用 Android 内置的 org.json，不额外引依赖）
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // 单元测试。org.json 在 JVM 测试里是空壳，必须补一个真实实现，
    // 否则解析相关的测试会抛 "not mocked"。
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
    }
}
