plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.menouer.capitalalgiers"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.menouer.capitalalgiers"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(project(":protocol"))
    implementation(project(":networking"))
    implementation(project(":persistence"))

    // M3 (Local Hotseat Prototype) talks directly to the rules engine in-process,
    // deliberately bypassing :protocol/:networking per DevelopmentRoadmap.md's M3
    // goal. This is a temporary addition to the module graph vs.
    // TechnicalSpecification.md §2's app -> networking/persistence -> protocol ->
    // rules-engine chain; recorded here rather than silently absorbed.
    implementation(project(":rules-engine"))
    implementation(project(":economy-data"))
}