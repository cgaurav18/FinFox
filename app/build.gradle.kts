import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Firebase: apply google-services only when the project's google-services.json is present (not committed).
if (file("google-services.json").exists()) apply(plugin = libs.plugins.google.services.get().pluginId)

// Google/Firebase identifiers come from local.properties, -P flags or env vars — never from source control.
// They are public client identifiers (no client secrets or private keys are ever embedded). See README.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun cfg(key: String): String =
    (project.findProperty(key) as String?) ?: localProps.getProperty(key) ?: System.getenv(key.uppercase().replace('.', '_')) ?: ""
fun quoted(v: String) = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

// Release signing is read from keystore.properties (not committed). See README.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.mymoney.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mymoney.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", quoted(cfg("mymoney.googleWebClientId")))
        buildConfigField("String", "FIREBASE_API_KEY", quoted(cfg("mymoney.firebaseApiKey")))
        buildConfigField("String", "FIREBASE_APP_ID", quoted(cfg("mymoney.firebaseAppId")))
        buildConfigField("String", "FIREBASE_PROJECT_ID", quoted(cfg("mymoney.firebaseProjectId")))
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystorePropsFile.exists()) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets {
        // Exported Room schemas are used by MigrationTestHelper.
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
        getByName("test").assets.srcDir("$projectDir/schemas")
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/LICENSE*")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.animation)
    implementation(libs.navigation.compose)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.work.runtime.ktx)
    implementation(libs.datastore.preferences)
    implementation(libs.biometric)
    implementation(libs.splashscreen)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    // Home screen widgets
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    // Google Sign-In (Credential Manager), Drive authorization, Firebase Auth
    implementation(libs.credentials)
    implementation(libs.credentials.play.services)
    implementation(libs.googleid)
    implementation(libs.play.services.auth)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    // Google Drive REST
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    // Argon2id for backup recovery passphrases
    implementation(libs.bouncycastle)
    implementation(libs.coil.compose)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.room.testing)
    testImplementation(libs.work.testing)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.glance.appwidget.testing)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
}
