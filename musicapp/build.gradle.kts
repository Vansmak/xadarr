import java.util.Properties

// Xadarr Music: the Music Assistant remote on its own, for devices that don't run full Xadarr.
// It compiles Xadarr's own `com.arflix.tv.music` package straight from the app module (that
// package depends only on DataStore/OkHttp/Hilt by design), so the two never drift apart.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.xadarr.music"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.xadarr.music"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("release") {
            val keystorePropertiesFile = rootProject.file("keystore.properties")
            if (keystorePropertiesFile.exists()) {
                val keystoreProperties = Properties()
                keystoreProperties.load(keystorePropertiesFile.inputStream())
                storeFile = rootProject.file("app/" + keystoreProperties["storeFile"] as String).takeIf { it.exists() }
                    ?: rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        val releaseSigning = signingConfigs.getByName("release").takeIf { it.storeFile?.exists() == true }
        getByName("debug") {
            if (releaseSigning != null) signingConfig = releaseSigning
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = releaseSigning ?: signingConfigs.getByName("debug")
        }
    }

    sourceSets["main"].java.srcDirs("src/main/kotlin", "../app/src/main/kotlin/com/arflix/tv/music")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.tv:tv-material:1.0.0")
    implementation("com.google.dagger:hilt-android:2.54")
    ksp("com.google.dagger:hilt-compiler:2.54")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.5.0")
    implementation("androidx.datastore:datastore-preferences:1.0.0")
}
