import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val releaseProperties =
    Properties().apply {
        rootProject.file("../release.properties").inputStream().use(::load)
    }
val foremanVersionCode =
    providers.gradleProperty("foremanVersionCode").orNull?.toIntOrNull()
        ?: releaseProperties.getProperty("androidVersionCode").toInt()
val foremanVersionName =
    providers.gradleProperty("foremanVersionName").orNull
        ?: releaseProperties.getProperty("foremanVersion")
val foremanProtocolVersion = releaseProperties.getProperty("protocolVersion").toInt()
val foremanAndroidSigningCertificateSha256 =
    releaseProperties.getProperty("androidSigningCertificateSha256")
        ?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
        ?: error("release.properties: invalid androidSigningCertificateSha256")
val foremanReleaseBuild =
    releaseProperties.getProperty("releaseBuild")?.toBooleanStrictOrNull()
        ?: error("release.properties: releaseBuild must be true or false")
val foremanBuildCommit =
    providers.environmentVariable("FOREMAN_BUILD_COMMIT").orNull?.trim()?.takeIf {
        it.matches(Regex("[0-9A-Za-z._-]{1,64}"))
    } ?: runCatching {
        providers.exec {
            commandLine("git", "-C", rootProject.projectDir.parent, "rev-parse", "--short=12", "HEAD")
        }.standardOutput.asText.get().trim()
    }.getOrDefault("unknown").takeIf { it.matches(Regex("[0-9a-f]{7,40}")) } ?: "unknown"
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
        buildConfigField("int", "FOREMAN_PROTOCOL_VERSION", foremanProtocolVersion.toString())
        buildConfigField("String", "FOREMAN_BUILD_COMMIT", "\"$foremanBuildCommit\"")
        buildConfigField("boolean", "FOREMAN_RELEASE_BUILD", foremanReleaseBuild.toString())
        buildConfigField(
            "String",
            "FOREMAN_ANDROID_SIGNING_CERTIFICATE_SHA256",
            "\"$foremanAndroidSigningCertificateSha256\"",
        )
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
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
