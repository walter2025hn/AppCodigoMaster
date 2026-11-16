plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.codigomaster.iptv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.codigomaster.iptv"
        minSdk = 24
        manifestPlaceholders["admobAppId"] = providers.gradleProperty("ADMOB_APP_ID")
            .orElse("ca-app-pub-3940256099942544~3347511713").get()
        targetSdk = 35
        // libVLC incluye código nativo: solo las ABI de TV Box, Fire TV y móviles actuales.
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
        versionCode = 18
        versionName = "4.9.0"
    }

    buildTypes {
        release {
            // El APK "debug" de Compose es MUY lento (sin optimizar). Release + R8 = desplazamiento fluido.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Se firma con la clave de depuración para poder instalarlo (sideload) sin crear un keystore.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true; buildConfig = true }
    buildTypes {
        debug {
            buildConfigField("String", "ADMOB_BANNER_ID", "\"ca-app-pub-3940256099942544/9214589741\"")
            buildConfigField("String", "ADMOB_NATIVE_ID", "\"ca-app-pub-3940256099942544/2247696110\"")
            buildConfigField("String", "ADMOB_INTERSTITIAL_ID", "\"ca-app-pub-3940256099942544/1033173712\"")
        }
        release {
            buildConfigField("String", "ADMOB_BANNER_ID", "\"\${providers.gradleProperty("ADMOB_BANNER_ID").orElse("").get()}\"")
            buildConfigField("String", "ADMOB_NATIVE_ID", "\"\${providers.gradleProperty("ADMOB_NATIVE_ID").orElse("").get()}\"")
            buildConfigField("String", "ADMOB_INTERSTITIAL_ID", "\"\${providers.gradleProperty("ADMOB_INTERSTITIAL_ID").orElse("").get()}\"")
        }
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.core:core-ktx:1.15.0")

    // Motor 1 · ExoPlayer (Media3): HLS, DASH, SmoothStreaming, RTSP, MPEG-TS, MP4, MKV.
    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-exoplayer-smoothstreaming:$media3")
    implementation("androidx.media3:media3-exoplayer-rtsp:$media3")
    implementation("androidx.media3:media3-datasource:$media3")
    implementation("androidx.media3:media3-ui:$media3")

    // Motor 2 · libVLC: respaldo universal (RTMP, UDP, códecs y contenedores poco comunes).
    implementation("org.videolan.android:libvlc-all:3.6.0")

    implementation("io.coil-kt:coil-compose:2.7.0")

    // AdMob: banner, native, interstitial and consent.
    implementation("com.google.android.gms:play-services-ads:25.5.0")
    implementation("com.google.android.ump:user-messaging-platform:4.0.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
