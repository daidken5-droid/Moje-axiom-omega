pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Zrcadlo Maven Central od Googlu (stejné artefakty, ověřují se kontrolní součty Gradlu) – Maven Central
        // občas vrací 429 Too Many Requests a shazuje sestavení; přímé mavenCentral() zůstává jako záloha.
        maven("https://maven-central.storage-download.googleapis.com/maven2")
        mavenCentral()
        // jitpack JEN pro libsu (volitelný root ve VEGA) – omezeno na skupinu, ať neovlivní ostatní závislosti
        maven("https://jitpack.io") { content { includeGroup("com.github.topjohnwu.libsu") } }
    }
}

rootProject.name = "AxiomOmega"
include(":core", ":app", ":shell", ":vegacore", ":vega")
