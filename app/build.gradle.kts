plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ---- Reklama ID lari: release uchun CI Secrets yoki -P parametr orqali beriladi.
// Berilmasa Google TEST ID lari ishlatiladi (daromad bo'lmaydi, lekin xavfsiz).
val adTestApp = "ca-app-pub-3940256099942544~3347511713"
val adTestBanner = "ca-app-pub-3940256099942544/6300978111"
val adTestInter = "ca-app-pub-3940256099942544/1033173712"
fun adProp(name: String, env: String): String? =
    ((project.findProperty(name) as String?) ?: System.getenv(env))?.trim()?.takeIf { it.isNotEmpty() }
val admobAppId = adProp("admobAppId", "ADMOB_APP_ID") ?: adTestApp
val admobBanner = adProp("admobBanner", "ADMOB_BANNER_ID") ?: adTestBanner
val admobInter = adProp("admobInterstitial", "ADMOB_INTERSTITIAL_ID") ?: adTestInter

android {
    namespace = "uz.dublaj"
    compileSdk = 36

    defaultConfig {
        applicationId = "uz.dublaj"
        minSdk = 26
        targetSdk = 36
        // CI: -PbuildNumber=<run_number>; versionCode har build'da oshib boradi (Play talabi)
        val buildNumber = (project.findProperty("buildNumber") as String?)?.toIntOrNull() ?: 4
        versionCode = buildNumber
        versionName = "0.4.$buildNumber"
        // CI: -PgithubRepo=<owner/repo> — faqat debug (test) APK yangilanish tekshiruvi uchun
        val githubRepo = (project.findProperty("githubRepo") as String?)?.takeIf { it.isNotBlank() }
            ?: "mamazokirov1/DublajUz-new"
        buildConfigField("String", "GITHUB_REPO", "\"$githubRepo\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // Prebuilt whisper AAR is arm64-v8a only (free tier)
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        // FAQAT debug (test) APK uchun doimiy kalit: har build bir xil imzo -> APK ustidan yangilanadi.
        // Bu kalit repoda ochiq turadi, shuning uchun Play Market uchun HECH QACHON ishlatilmaydi.
        create("dublaj") {
            storeFile = file("dublaj-sign.keystore")
            storePassword = "android"
            keyAlias = "dublaj"
            keyPassword = "android"
        }
        // Play Market uchun upload key: CI Secrets (KEYSTORE_B64 ...) orqali beriladi.
        create("release") {
            val ks = System.getenv("KEYSTORE_FILE")
            if (!ks.isNullOrBlank() && file(ks).exists()) {
                storeFile = file(ks)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 JNI/ONNX bilan muammo chiqarmasligi uchun o'chiq (ishonchlilik > hajm)
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Upload key berilmasa release imzosiz chiqadi (ochiq kalit bilan imzolanmaydi)
            val ks = System.getenv("KEYSTORE_FILE")
            if (!ks.isNullOrBlank() && file(ks).exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            manifestPlaceholders["admobAppId"] = admobAppId
            buildConfigField("String", "ADMOB_BANNER_ID", "\"$admobBanner\"")
            buildConfigField("String", "ADMOB_INTERSTITIAL_ID", "\"$admobInter\"")
            // Play uchun: o'zini yangilash, bepul "test Pro" va server ovozi O'CHIQ
            buildConfigField("boolean", "SELF_UPDATE", "false")
            buildConfigField("boolean", "ALLOW_TEST_UNLOCK", "false")
            buildConfigField("boolean", "CLOUD_TTS", "false")
        }
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("dublaj")
            // Test APK: har doim Google test reklamalari
            manifestPlaceholders["admobAppId"] = adTestApp
            buildConfigField("String", "ADMOB_BANNER_ID", "\"$adTestBanner\"")
            buildConfigField("String", "ADMOB_INTERSTITIAL_ID", "\"$adTestInter\"")
            buildConfigField("boolean", "SELF_UPDATE", "true")
            buildConfigField("boolean", "ALLOW_TEST_UNLOCK", "true")
            buildConfigField("boolean", "CLOUD_TTS", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("androidx.documentfile:documentfile:1.0.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // ONNX Runtime — VITS TTS (1.22.0+ : 16 KB sahifa hajmiga mos .so)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")

    // Whisper.cpp prebuilt AAR (no NDK)
    implementation("dev.ffmpegkit-maintained:whisper-android:1.0.0")

    // Model download
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("com.jakewharton.timber:timber:5.0.1")

    // ---- Monetizatsiya (Play Market)
    // Billing 8.x — Play 2026-08-31 dan boshlab 7.x ni qabul qilmaydi
    implementation("com.android.billingclient:billing:8.0.0")
    implementation("com.google.android.gms:play-services-ads:23.5.0")
    // Reklama roziligi (GDPR / EEA)
    implementation("com.google.android.ump:user-messaging-platform:3.1.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
