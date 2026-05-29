plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val mobileCamSdkReleaseDir = rootProject.file("sdk/MobileCamSDK_post/integration")

android {
    namespace = "top.tinyai.camvideoplayback"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "top.tinyai.camvideoplayback"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }

    sourceSets {
        getByName("main") {
            // Point directly to the external MobileCamSDK post release bundle under ../MobileCamSDK/dist/android-release-post/integration.
            jniLibs.setSrcDirs(listOf(mobileCamSdkReleaseDir.resolve("jniLibs").path))
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    // MobileCamSDK (JAR + .so) from the external android-release-post bundle.
    implementation(files(mobileCamSdkReleaseDir.resolve("libs/ICatchtekReliant.jar")))
    implementation(files(mobileCamSdkReleaseDir.resolve("libs/ICatchtekVR.jar")))
    implementation(files(mobileCamSdkReleaseDir.resolve("libs/ICatchtekControl.jar")))

    // AppCompat for traditional Activity playback
    implementation("androidx.appcompat:appcompat:1.6.1")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
