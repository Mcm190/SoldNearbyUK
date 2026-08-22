import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose.compiler)
}

// Release signing credentials, if set up — kept out of the repo entirely (local.properties is
// gitignored). See "Play Store release signing" in README.md for how to generate the keystore
// and populate these. Deliberately falls back to no signing config (an unsigned, unpublishable
// bundle) rather than failing the build outright, so a checkout without the keystore — a fresh
// clone, CI, another machine — still builds debug/release for local testing.
val releaseSigningProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val hasReleaseSigning = releaseSigningProps.getProperty("RELEASE_STORE_FILE") != null

android {
    namespace = "com.soldnearby.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.soldnearby.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 7
        versionName = "0.1.6"
    }

    // seed_prices.db lives in the :seed_data install-time asset pack, not app/src/main/assets —
    // see seed_data/build.gradle.kts. Google Play caps a base module's compressed download at
    // 200MB; the bundled dataset alone is well past that on its own.
    assetPacks += setOf(":seed_data")

    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(releaseSigningProps.getProperty("RELEASE_STORE_FILE"))
                storePassword = releaseSigningProps.getProperty("RELEASE_STORE_PASSWORD")
                keyAlias = releaseSigningProps.getProperty("RELEASE_KEY_ALIAS")
                keyPassword = releaseSigningProps.getProperty("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // kotlin.compilerOptions.jvmTarget isn't set explicitly: with AGP's built-in
        // Kotlin it defaults to this targetCompatibility value.
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.play.services.location)
    implementation(libs.maplibre)
}
