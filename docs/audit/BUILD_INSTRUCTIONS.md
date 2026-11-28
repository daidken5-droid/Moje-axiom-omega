# Sestavení AVIORu

## Nástroje (připnuté verze)
- **JDK 21** (CI: `actions/setup-java@v4`, Temurin).
- **Gradle 8.14.3** (wrapper `gradle/wrapper/gradle-wrapper.properties`), Android Gradle Plugin 8.7.3, Kotlin 2.1.0.
- **Android:** compileSdk/targetSdk 35, minSdk 26, NDK 27.2.12479018, CMake 3.22.1, build-tools 35.0.0.
- **llama.cpp:** tag `b12032`, commit `dde879bc4f954b809892fd0d660515f42536394a` (`app/src/main/cpp/CMakeLists.txt`, FetchContent – stahuje se při sestavení).

## Příkazy
```sh
./gradlew :core:test                      # 210 testů jádra vč. Δ Lab (bez Android SDK)
./gradlew :app:testDebugUnitTest          # Robolectric (Android SDK + přístup k Maven Central)
./gradlew :app:assembleDebug -Pomega.abis=arm64-v8a
./gradlew :app:assembleRelease -Pomega.abis=arm64-v8a   # vyžaduje podpisové proměnné (viz níže)
```
Celé CI: `.github/workflows/build-apk.yml` (testy jádra → testy aplikace → APK → GitHub Release `avior-<verze>-c<versionCode>`).

## Podpis
- Release se podepisuje klíčem z proměnných `OMEGA_KEYSTORE_PATH`, `OMEGA_KEYSTORE_PASSWORD`, `OMEGA_KEY_ALIAS`, `OMEGA_KEY_PASSWORD` (v CI ze secrets).
- **Klíč ani hesla nejsou v repozitáři ani v auditním balíčku.**
- Kdo sestaví sám, dostane APK s jiným podpisem. Nelze ho nainstalovat přes existující AVIOR; nejdřív ho odinstaluj.
- Otisk podpisu oficiálního APK: SHA-256 certifikátu `66a2e14cb9b3e779ed596bcdeeb4d94d20a42b56197ff970494ac219f55b6230`.

## Modely (nejsou v balíčku)
- Katalog s URL a SHA-256 GGUF souborů: `brains/catalog.json` (např. Gemma 4 E4B, sha256 `85a896a0…`).
- Aplikace je stahuje a ověřuje sama (`core/.../brain/Downloader.kt`).
- Offline knihovny a RAG indexy: GitHub Releases `library-*`, s manifestem a SHA-256 částí.
