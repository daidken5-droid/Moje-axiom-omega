import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

// VEGA – samostatná aplikace vedle AVIORu (vlastní applicationId, ikona, úložiště). Bez llama.cpp → rychlé sestavení.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val signing = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
    System.getenv("OMEGA_KEYSTORE_PATH")?.let { setProperty("storeFile", it) }
    System.getenv("OMEGA_KEYSTORE_PASSWORD")?.let { setProperty("storePassword", it) }
    System.getenv("OMEGA_KEY_ALIAS")?.let { setProperty("keyAlias", it) }
    System.getenv("OMEGA_KEY_PASSWORD")?.let { setProperty("keyPassword", it) }
}
val hasReleaseSigning = signing.getProperty("storeFile")?.let { file(it).exists() } == true

android {
    namespace = "cz.axiom.vega"
    compileSdk = 35

    defaultConfig {
        applicationId = "cz.axiom.vega"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Pixel 9a = arm64; x86_64 jen pro emulátor (debug). Ušetří ~25 MB nativních knihoven OCR.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            ndk { abiFilters += listOf("x86_64") }
        }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += listOf(
            "META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*",
            "META-INF/*.kotlin_module", "META-INF/versions/9/previous-compilation-data.bin",
            // nepoužívané za běhu: konfigurace GraalVM native-image a tabulky post-kvantových šifer
            "META-INF/native-image/**", "org/bouncycastle/pqc/**/*.properties",
        )
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all { it.systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2") }
    }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":vegacore"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    // PDF (text i zpětná kontrola vygenerovaného PDF) a OCR v telefonu – stejné knihovny jako AVIOR
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
