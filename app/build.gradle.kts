import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("org.jetbrains.kotlin.kapt")
}

// Release key: CI passes GOTGAN_KEYSTORE_* environment variables (README "Release signing");
// a local build may use an untracked keystore.properties with the same four keys in lower camel case.
val releaseKey: Map<String, String>? = run {
    val local = rootProject.file("keystore.properties").takeIf { it.isFile }
        ?.let { f -> Properties().apply { f.inputStream().use(::load) } }
    fun value(env: String, prop: String) = System.getenv(env)?.takeIf { it.isNotBlank() } ?: local?.getProperty(prop)?.takeIf { it.isNotBlank() }
    val entries = mapOf(
        "storeFile" to value("GOTGAN_KEYSTORE_FILE", "storeFile"),
        "storePassword" to value("GOTGAN_KEYSTORE_PASSWORD", "storePassword"),
        "keyAlias" to value("GOTGAN_KEY_ALIAS", "keyAlias"),
        "keyPassword" to value("GOTGAN_KEY_PASSWORD", "keyPassword"),
    )
    if (entries.values.all { it != null }) entries.mapValues { it.value!! } else null
}
val testsReleaseBuild = providers.gradleProperty("testBuildType").orNull == "release"

android {
    namespace = "com.quickfiles.app"
    compileSdk = 36
    buildToolsVersion = "35.0.0"
    testBuildType = if (testsReleaseBuild) "release" else "debug"

    defaultConfig {
        applicationId = "com.quickfiles.app"
        minSdk = 30
        targetSdk = 36
        // The release workflow derives both from the vX.Y.Z tag; local builds keep the defaults.
        versionCode = providers.gradleProperty("gotgan.versionCode").map(String::toInt).getOrElse(100)
        versionName = providers.gradleProperty("gotgan.versionName").getOrElse("1.0.0")
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
        if (releaseKey != null) create("release") {
            storeFile = rootProject.file(releaseKey.getValue("storeFile"))
            storePassword = releaseKey.getValue("storePassword")
            keyAlias = releaseKey.getValue("keyAlias")
            keyPassword = releaseKey.getValue("keyPassword")
        }
    }
    buildTypes {
        getByName("release") {
            isDebuggable = false
            // R8 shrinks the distributed build. Instrumenting the release build itself (-PtestBuildType=release)
            // keeps code unshrunk so the test APK can still call into the app.
            isMinifyEnabled = !testsReleaseBuild
            isShrinkResources = !testsReleaseBuild
            // Without a release key the build stays installable over existing debug-key installs.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
    // Annotation-only; androidTest pins a newer error_prone_annotations and consistent resolution would conflict.
    implementation(libs.nextlib.media3ext) { exclude(group = "com.google.errorprone", module = "error_prone_annotations") }
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.svg)
    implementation(libs.work.runtime)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.smbj)
    implementation(libs.jsch)
    implementation("com.github.luben:zstd-jni:${libs.versions.zstd.get()}@aar")
    testImplementation("com.github.luben:zstd-jni:${libs.versions.zstd.get()}")
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    testImplementation(libs.mockwebserver)
    testImplementation(libs.okhttp.tls)
    testImplementation(libs.kxml)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.sshd.sftp)
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
