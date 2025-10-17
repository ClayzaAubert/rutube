plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.rutube.clayza"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.rutube.clayza"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "3.2.11"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val keystorePath = project.properties["RELEASE_STORE_FILE"] as String
            val keystorePassword = project.properties["RELEASE_STORE_PASSWORD"] as String
            val keyAliasValue = project.properties["RELEASE_KEY_ALIAS"] as String
            val keyPasswordValue = project.properties["RELEASE_KEY_PASSWORD"] as String

            storeFile = file(keystorePath)
            storePassword = keystorePassword
            keyAlias = keyAliasValue
            keyPassword = keyPasswordValue
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    applicationVariants.all {
        outputs.all {
            val appName = "RuTube"
            val buildType = name
            val version = versionName
            val newApkName = "${appName}-${buildType}-v${version}.apk"
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName = newApkName
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.webkit:webkit:1.11.0")
}
