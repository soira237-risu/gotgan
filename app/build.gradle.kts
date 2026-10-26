plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "com.quickfiles.app"
    compileSdk = 36
    buildToolsVersion = "35.0.0"
    testBuildType = providers.gradleProperty("testBuildType").getOrElse("debug")

    defaultConfig {
        applicationId = "com.quickfiles.app"
        minSdk = 30
        targetSdk = 36
        versionCode = 100
        versionName = "1.0.0"
        testInstrumentationRunner = "com.quickfiles.app.GotganTestRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; aidl = true }
    signingConfigs {
        // The project's own key keeps installed builds updatable; a fresh clone falls back to the standard debug key.
        getByName("debug") { rootProject.file(".android/debug.keystore").takeIf { it.exists() }?.let { storeFile = it } }
    }
    buildTypes {
        getByName("release") {
            isDebuggable = false
            // Personal release: keep the same callable code for on-device instrumentation.
            // Debugging remains disabled; performance is measured on this exact distributed build.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.tooling.preview)
    debugImplementation(libs.androidx.compose.tooling)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    // Keep app/test futures aligned; AndroidX Test 1.7 uses SuspendToFutureAdapter.
    implementation("androidx.concurrent:concurrent-futures-ktx:1.3.0")
    implementation(libs.okhttp)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    kapt(libs.room.compiler)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.ui)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.work.runtime)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation("com.github.luben:zstd-jni:${libs.versions.zstd.get()}@aar")
    testImplementation("com.github.luben:zstd-jni:${libs.versions.zstd.get()}")
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    testImplementation(libs.mockwebserver)
    testImplementation(libs.okhttp.tls)
    testImplementation(libs.kxml)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(libs.okhttp.tls)
    androidTestImplementation(libs.mockwebserver)
    androidTestImplementation("org.codehaus.mojo:animal-sniffer-annotations:1.27")
    androidTestImplementation("com.google.errorprone:error_prone_annotations:2.50.0")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
