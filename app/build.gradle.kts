plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "dev.frakw.ftmsbridge"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.frakw.ftmsbridge"
        minSdk = 34
        targetSdk = 37
        versionCode =
            providers
                .environmentVariable("VERSION_CODE")
                .orElse("1")
                .get()
                .toInt()
        versionName = providers.environmentVariable("VERSION_NAME").orElse("0.1.0").get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions { unitTests.isIncludeAndroidResources = true }

    val signingStoreFile = providers.environmentVariable("SIGNING_STORE_FILE")
    val signingStorePassword = providers.environmentVariable("SIGNING_STORE_PASSWORD")
    val signingKeyAlias = providers.environmentVariable("SIGNING_KEY_ALIAS")
    val signingKeyPassword = providers.environmentVariable("SIGNING_KEY_PASSWORD")
    if (listOf(signingStoreFile, signingStorePassword, signingKeyAlias, signingKeyPassword).all { it.isPresent }) {
        signingConfigs {
            create("release") {
                storeFile = file(signingStoreFile.get())
                storePassword = signingStorePassword.get()
                keyAlias = signingKeyAlias.get()
                keyPassword = signingKeyPassword.get()
            }
        }
        buildTypes.named("release") { signingConfig = signingConfigs.getByName("release") }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-service:2.9.4")
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    implementation("androidx.work:work-runtime-ktx:2.11.0")
    implementation("androidx.health.connect:connect-client:1.1.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}
