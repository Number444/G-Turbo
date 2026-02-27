plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.myapplication1"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.gemini.turbo.assistant"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        getByName("debug") {
            // 调试版包名后缀，安装后包名为 com.example.myapplication1.debug
            applicationIdSuffix = ".debug"
        }

        getByName("release") {
            isMinifyEnabled = false
            // 正式版保持原样 com.example.myapplication1
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation("androidx.core:core-splashscreen:1.1.0-rc01")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}