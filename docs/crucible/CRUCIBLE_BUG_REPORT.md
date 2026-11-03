# CRUCIBLE – nalezené chyby

| ID | Závažnost | Kde | Popis | Stav |
|---|---|---|---|---|
| C-01 | vysoká (pravdivost testů) | tools/crucible/emulator_smoke.sh | Po zmizení emulátoru kroky hlásily „✅ bez pádu“ (logcat bez zařízení je prázdný) → smoke falešně „prošel“ (běh #5) | opraveno 23c2038; ověřeno falešným adb (zmizelé → exit 2 „NEOVĚŘEN“, živé → 0) |
| C-02 | střední | UsableSpace (7 míst: BrainManager, OmegaService, OmegaBridge) | Kontrola místa ignorovala mazatelnou cache → mohla odmítnout stažení mozku/knihovny/RAG, které by se vešlo | opraveno 276230c (StorageManager.getAllocatableBytes/allocateBytes) |
| C-03 | nízká | OmegaBridge.reportLastExit | Chyběl důvod REASON_EXCESSIVE_RESOURCE_USAGE (systém zabije LLM na pozadí) → neurčité hlášení | opraveno 276230c |
| C-04 | nízká (lint error) | Assist.kt | Lint chyba u větve API < 34 (správně oddělené) | potlačeno s odůvodněním, 276230c |
| C-05 | CI | settings/app build | Robolectric/Gradle závislé na Maven Central (HTTP 429) → pád sestavení | opraveno f63ad33 (zrcadlo Google) |
| C-06 | CI | skripty emulátoru | adb bez časového limitu → „waiting for device“ do limitu jobu | opraveno f63ad33 |
| C-07 | CI | instrumentace | Dialog POST_NOTIFICATIONS pozastavil aktivitu | opraveno fbcb69b (GrantPermissionRule + pm grant) |
| C-08 | otevřené | emulátor v CI | běh #10: **SIGSEGV v hostitelském `qemu-system-x86_64-headless` 37.2.13** (coredumpctl, core 1,4 GB) při startu AVIORu na AMD runneru s vnořenou KVM; −8 GB disku = ten core dump; Vulkan a `-gpu guest` vyloučeny – viz docs/hyperforge/RUNTIME_C08.md | běh #16: i emulátor 35.2.10 padá stejně → BLOCKED infrastrukturou CI (AMD + vnořená KVM); ověřit na telefonu |
