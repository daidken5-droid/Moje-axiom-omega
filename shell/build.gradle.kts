// Šablona aplikací, které AXIOM Ω vyrábí přímo v telefonu (nástroj build_apk).
// Žádné knihovny (ani AndroidX) → manifest obsahuje jen naše řetězce a dá se bezpečně
// přepsat na jiný balíček a název stejné délky (core/forge/ApkForge.kt).
plugins {
    id("com.android.application")
}

android {
    namespace = "cz.axiom.shell"
    compileSdk = 35

    defaultConfig {
        // PLACEHOLDER (26 znaků) – ApkForge ho nahradí unikátním balíčkem každé vygenerované aplikace.
        applicationId = "cz.axiom.gen.app0000000000"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = null // nepodepsaná šablona; podepisuje se v telefonu klíčem z Android Keystore
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint { abortOnError = false; checkReleaseBuilds = false }
}
