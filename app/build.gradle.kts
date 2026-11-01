import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// -Pomega.native=false → sestaví aplikaci bez llama.cpp (rychlá kontrola UI/Kotlinu; lokální mozek pak hlásí "nedostupný")
val withNative = (findProperty("omega.native") as String?)?.toBoolean() ?: true
// -Pomega.abis=arm64-v8a,x86_64
val abis = ((findProperty("omega.abis") as String?) ?: "arm64-v8a").split(',').map { it.trim() }.filter { it.isNotEmpty() }

// Podepisování release: keystore z proměnných prostředí (GitHub Secrets) nebo keystore.properties
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
    namespace = "cz.axiom.omega"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "cz.axiom.omega"
        minSdk = 26
        targetSdk = 35
        versionCode = 421
        versionName = "4.2.1"
        buildConfigField("boolean", "WITH_NATIVE", withNative.toString())

        if (withNative) {
            ndk { abiFilters += abis }
            externalNativeBuild {
                cmake {
                    arguments += listOf(
                        "-DCMAKE_BUILD_TYPE=Release",
                        "-DANDROID_STL=c++_shared",
                        "-DANDROID_CPP_FEATURES=rtti exceptions",
                    )
                    (findProperty("omega.llamaDir") as String?)?.let { arguments += "-DFETCHCONTENT_SOURCE_DIR_LLAMA=$it" }
                    cppFlags += listOf("-O3", "-fexceptions", "-frtti")
                }
            }
        }
    }

    if (withNative) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
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
            // Bez vlastního keystore se release podepíše debug klíčem (instalovatelné, ale bez aktualizací mezi stroji).
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // katalog mozků + offline znalostní balíčky z kořene repozitáře → assets/brains, assets/packs
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/omegaAssets"))
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/omegaShell"))

    androidResources { noCompress += "apk" }

    packaging {
        // llama.cpp backendy se načítají z nativeLibraryDir → knihovny musí být rozbalené
        jniLibs.useLegacyPackaging = true
        resources.excludes += listOf(
            "META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*",
            "META-INF/*.kotlin_module", "META-INF/versions/9/previous-compilation-data.bin",
        )
    }

    testOptions { unitTests.isReturnDefaultValues = true }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

val omegaAssets by tasks.registering(Sync::class) {
    from(rootProject.file("brains")) { into("brains") }
    from(rootProject.file("packs")) { into("packs"); include("*.md", "catalog.json") }
    from(rootProject.file("finetune")) { into("finetune"); include("skills.jsonl") }
    into(layout.buildDirectory.dir("generated/omegaAssets"))
}
// Šablona pro aplikace vyráběné v telefonu (modul :shell) → assets/forge/shell.apk
val omegaShell by tasks.registering(Copy::class) {
    dependsOn(":shell:assembleRelease")
    from(project(":shell").layout.buildDirectory.file("outputs/apk/release/shell-release-unsigned.apk"))
    rename { "shell.apk" }
    into(layout.buildDirectory.dir("generated/omegaShell/forge"))
}
tasks.named("preBuild") { dependsOn(omegaAssets, omegaShell) }

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.webkit:webkit:1.12.1")

    testImplementation("junit:junit:4.13.2")
}
