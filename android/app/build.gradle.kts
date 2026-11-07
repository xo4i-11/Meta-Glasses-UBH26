import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
}

// local.properties (gitignored): backend_url, shared_secret, mwdat_application_id, mwdat_client_token
val local = Properties().apply {
  rootDir.resolve("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
  namespace = "com.smarteyes"
  compileSdk = 36

  buildFeatures { buildConfig = true }

  defaultConfig {
    applicationId = "com.smarteyes"
    minSdk = 31
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"
    buildConfigField("String", "BACKEND_URL", "\"${local.getProperty("backend_url", "http://10.0.2.2:8000")}\"")
    buildConfigField("String", "SHARED_SECRET", "\"${local.getProperty("shared_secret", "")}\"")
    manifestPlaceholders["mwdat_application_id"] = local.getProperty("mwdat_application_id", "")
    manifestPlaceholders["mwdat_client_token"] = local.getProperty("mwdat_client_token", "")
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

dependencies {
  implementation("com.meta.wearable:mwdat-core:1.0.1")
  implementation("com.meta.wearable:mwdat-camera:1.0.1")
  implementation("androidx.activity:activity-ktx:1.13.0")
  implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
  implementation("androidx.camera:camera-camera2:1.4.2")
  implementation("androidx.camera:camera-lifecycle:1.4.2")
  implementation("com.squareup.okhttp3:okhttp:4.12.0")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
  testImplementation("junit:junit:4.13.2")
  testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
