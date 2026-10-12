plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.lmnaide.calendar"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.lmnaide.calendar"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.0.3-alpha"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "edition"
    productFlavors {
        create("offline") {
            dimension = "edition"
            buildConfigField("boolean", "CLOUD_ENABLED", "false")
        }
        create("cloud") {
            dimension = "edition"
            applicationIdSuffix = ".cloud"
            versionNameSuffix = "-cloud"
            resValue("string", "app_name", "Calendar Cloud")
            buildConfigField("boolean", "CLOUD_ENABLED", "true")
        }
    }

    buildTypes {
        debug {
            // Device checks install separately from the user’s Cloud app and data.
            if (providers.gradleProperty("cloudDeviceTest").orNull == "true") {
                applicationIdSuffix = ".devicetest"
                resValue("string", "app_name", "Calendar Cloud Test")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }
}

ksp {
    arg("room.generateKotlin", "true")
}

dependencies {
    "androidTestCloudImplementation"("androidx.test:runner:1.7.0")
    "androidTestCloudImplementation"("androidx.test.ext:junit:1.3.0")
    "cloudImplementation"("androidx.work:work-runtime-ktx:2.11.1")
    testImplementation("junit:junit:4.13.2")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
}
