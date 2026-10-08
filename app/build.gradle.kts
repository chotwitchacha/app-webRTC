// app/build.gradle.kts — ตัวอย่างส่วนที่ต้องเพิ่ม/แก้ (คง plugins, namespace, applicationId ของคุณไว้)
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// อ่านค่าจาก local.properties (บนคอมคุณ) หรือ environment variable (บน GitHub Actions)
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(name: String): String? =
    localProps.getProperty(name)?.takeIf { it.isNotBlank() }
        ?: System.getenv(name)?.takeIf { it.isNotBlank() }

android {
    namespace = "com.example.deeplink_webrtc"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.deeplink_webrtc"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        // ใช้ใน Config.java: BuildConfig.CREATE_ROOM_KEY
        buildConfigField("String", "CREATE_ROOM_KEY", "\"${secret("CREATE_ROOM_KEY") ?: ""}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            val storePath = secret("RELEASE_STORE_FILE")
            if (storePath != null) {
                storeFile = file(storePath)
                storePassword = secret("RELEASE_STORE_PASSWORD")
                keyAlias = secret("RELEASE_KEY_ALIAS")
                keyPassword = secret("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // ถ้าไม่มี keystore (เช่นยังไม่ได้ตั้งค่า) จะได้ APK ที่ยังไม่ sign ซึ่งติดตั้งไม่ได้
            if (secret("RELEASE_STORE_FILE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("io.getstream:stream-webrtc-android:1.3.8")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
