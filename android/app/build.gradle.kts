plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val foremanVersionCode =
    providers.gradleProperty("foremanVersionCode").orNull?.toIntOrNull() ?: 1
val foremanVersionName =
    providers.gradleProperty("foremanVersionName").orNull ?: "0.1.0-alpha.2"
val releaseKeystorePath = System.getenv("FOREMAN_ANDROID_KEYSTORE")

android {
    namespace = "net.kaltner.foreman"
    compileSdk = 37

    defaultConfig {
        applicationId = "net.kaltner.foreman"
        minSdk = 23
        targetSdk = 37
        versionCode = foremanVersionCode
        versionName = foremanVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
    }

    signingConfigs {
        if (!releaseKeystorePath.isNullOrBlank()) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = System.getenv("FOREMAN_ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("FOREMAN_ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("FOREMAN_ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-splashscreen:1.2.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
