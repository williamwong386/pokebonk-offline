plugins {
    id("com.android.application")
}

val generatedVersionCode = providers.gradleProperty("pokebonkVersionCode").orElse("1")
val generatedVersionName = providers.gradleProperty("pokebonkVersionName").orElse("dev")

android {
    namespace = "dev.vdustr.pokebonk.offline"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.vdustr.pokebonk.offline"
        minSdk = 26
        targetSdk = 36
        versionCode = generatedVersionCode.get().toInt()
        versionName = generatedVersionName.get()
    }

    defaultConfig { resValue("string", "app_name", "PokéBonk Offline") }

    buildTypes {
        debug {
            applicationIdSuffix = ".local"
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
        buildConfig = true
        resValues = true
    }

    androidResources {
        noCompress += setOf("m4a", "mp3", "mp4", "ogg", "wav", "webm")
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = true
        // Keep CI deterministic on the latest stable, mutually supported Android toolchain.
        // The resource checks are false positives: adaptive icons need v26 XML, while the
        // generated official logo is staged before Gradle resource processing. PokéBonk
        // stays landscape while respecting the user's normal or reverse landscape preference.
        disable += setOf(
            "AndroidGradlePluginVersion",
            "GradleDependency",
            "DiscouragedApi",
            "MonochromeLauncherIcon",
            "ObsoleteSdkInt",
            "OldTargetApi",
        )
    }
}
