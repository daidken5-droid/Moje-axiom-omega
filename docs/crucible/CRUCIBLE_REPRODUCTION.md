# CRUCIBLE – reprodukce

    ./gradlew :core:test :app:testDebugUnitTest :app:lintDebug          # jednotkové testy + lint
    ./gradlew :app:assembleDebug -Pomega.abis=x86_64                   # APK pro emulátor
    # běžící emulátor/telefon s adb:
    tools/crucible/emulator_smoke.sh app/build/outputs/apk/debug/*.apk smoke.md   # exit 0 prošel, 1 selhal, 2 neověřen
    ./gradlew :app:connectedDebugAndroidTest -Pomega.abis=x86_64       # instrumentace (Orchestrator)
    # v CI: .github/workflows/emulator.yml → tools/crucible/run_emulator_ci.sh (kontrola bez aplikace, smoke, instrumentace)

Na telefonu (doporučeno, protože emulátor v CI padá): zapnout ladění USB, `adb devices`, pak stejný smoke
a instrumentace s ABI arm64-v8a (bez `-Pomega.abis`). Debug APK má applicationId `cz.axiom.omega.debug`
a instaluje se vedle release verze – data uživatele se nemažou.

Podrobnosti sestavení: docs/REPRODUCE.md, docs/audit/BUILD_INSTRUCTIONS.md.
