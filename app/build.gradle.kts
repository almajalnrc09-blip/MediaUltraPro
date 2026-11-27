plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.ultramediapro"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.ultramediapro"
        minSdk = 26
        targetSdk = 35
        versionCode = 23
        versionName = "23.5"
    }

    buildFeatures {
        compose = true
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
    implementation(platform("androidx.compose:compose-bom:2025.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // LibVLC (সব ভিডিও ফরম্যাট, ডুয়াল অডিও ট্র্যাক, সাবটাইটেল ও ২০০% ভলিউম বুস্ট)
    implementation("org.videolan.android:libvlc-all:3.6.0-eap14")

    // Media3 ExoPlayer & Session (লো-পাওয়ার ব্যাকগ্রাউন্ড মিউজিক ও ইকুলাইজার)
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
}
