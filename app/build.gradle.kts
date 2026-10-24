import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 版本号统一放在根目录的 version.properties，打包脚本每次自动递增
// 注意：这里必须用上面显式 import 的 Properties，
// 直接写 java.util.Properties 会被 Gradle 的 java 扩展遮蔽，报 Unresolved reference
val versionProps = Properties().apply {
    val file = rootProject.file("version.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.huyan.tvguard"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.huyan.tvguard"
        minSdk = 24
        targetSdk = 34
        versionCode = versionProps.getProperty("versionCode", "1").toInt()
        versionName = versionProps.getProperty("versionName", "1.0.0")

        ndk {
            // MediaPipe 的 native 库四个架构各带一份，只留手机用得上的两个，APK 体积减半
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    androidResources {
        // MediaPipe 通过文件描述符直接读取 .task 模型，压缩后会读取失败
        noCompress += "task"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    val cameraxVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    implementation("com.google.mediapipe:tasks-vision:0.10.14")

    testImplementation("junit:junit:4.13.2")
}
