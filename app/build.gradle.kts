plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.openframe.gallery"
    compileSdk = 34
    defaultConfig { applicationId = "com.openframe.gallery"; minSdk = 30; targetSdk = 34; versionCode = 5; versionName = "0.2.2" }
    signingConfigs {
        create("release") {
            // The key and the passwords come from GitHub Secrets while building. They are never in the code.
            System.getenv("KEYSTORE_PATH")?.let { storeFile = file(it) }
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    lint { checkReleaseBuilds = false }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    kotlinOptions { jvmTarget = "17" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
}
