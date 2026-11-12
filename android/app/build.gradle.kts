plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.aicall.assistant"
    compileSdk = 35

    val backendWsUrl = providers.gradleProperty("BACKEND_WS_URL")
        .orElse("wss://configure-your-backend.example/call-session")
        .get()
    val backendAuthToken = providers.gradleProperty("BACKEND_AUTH_TOKEN")
        .orElse("")
        .get()
    val backendHttpUrl = providers.gradleProperty("BACKEND_HTTP_URL")
        .orElse("https://configure-your-backend.example")
        .get()
    val escapedBackendHttpUrl = backendHttpUrl.replace("\"", "\\\"")
    val escapedBackendAuthToken = backendAuthToken.replace("\"", "\\\"")

    buildFeatures {
        compose = true
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.aicall.assistant"
        minSdk = 34
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        getByName("debug") {
            buildConfigField("String", "BACKEND_WS_URL", "\"ws://127.0.0.1:8000/call-session\"")
            buildConfigField("String", "BACKEND_AUTH_TOKEN", "\"$escapedBackendAuthToken\"")
            buildConfigField("String", "TWILIO_CONTROL_BASE_URL", "\"$escapedBackendHttpUrl\"")
        }
        getByName("release") {
            buildConfigField("String", "BACKEND_WS_URL", "\"${backendWsUrl.replace("\"", "\\\"")}\"")
            buildConfigField("String", "BACKEND_AUTH_TOKEN", "\"$escapedBackendAuthToken\"")
            buildConfigField("String", "TWILIO_CONTROL_BASE_URL", "\"$escapedBackendHttpUrl\"")
        }
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.8.0")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.datastore:datastore-preferences:1.1.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0") // WebSocket client
}
