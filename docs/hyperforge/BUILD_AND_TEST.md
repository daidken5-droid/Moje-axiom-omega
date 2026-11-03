# BUILD_AND_TEST (1014)

    ./gradlew :core:test                                   # 245 testů jádra vč. Step0EvalTest (dev + validation)
    ./gradlew :core:test --tests '*Step0Eval*' -Pstep0.holdout=true   # + holdout (jen pro finální měření)
    ./gradlew :app:testDebugUnitTest                       # Robolectric: migrace DB, integrita putAnswer (5 testů)
    ./gradlew :app:lintDebug                               # 0 chyb, 37 varování
    ./gradlew :app:assembleRelease                         # podpis v CI (tajemství OMEGA_*), lokálně debug
    # data
    tools/factc/fetch_cldr.sh /tmp/cldr && python3 -I tools/factc/compile_cldr_iso.py /tmp/cldr core/src/main/resources/factstore
    python3 -I tools/step0eval/generate.py /tmp/cldr core/src/test/resources/step0eval
    # emulátor / telefon
    tools/crucible/emulator_smoke.sh app/build/outputs/apk/debug/*.apk smoke.md   # 0 prošel · 1 selhal · 2 neověřen
