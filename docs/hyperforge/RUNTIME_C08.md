# RUNTIME_C08 – zánik emulátoru v CI

| Běh | Zjištění |
|---|---|
| #1, #4, #5, #7, #8, #9, #10 | emulátor (qemu, API 34 x86_64, emulator 37.2.13) zmizí 45–120 s po PRVNÍM spuštění AVIORu |
| #7, #9, #10 | kontrola: bez AVIORu přežije 180 s |
| #5 | `-gpu guest` nepomohl; #8 `-feature -Vulkan` nepomohl |
| #9 | data AVIORu v emulátoru 1 MB, AVD neroste; −7,4 GB na hostiteli při zániku = výpis paměti |
| **#10** | **`coredumpctl`: SIGSEGV (11) v `qemu-system-x86_64-headless`, core 1,4 GB**; jádro hostitele krátce před tím: `kvm: vcpu0 … Unhandled WRMSR(0xc0010007)` (AMD PerfCtr MSR); runner AMD s vnořenou KVM (Hyper-V) |

**Závěr (MEASURED):** padá hostitelská binárka emulátoru (segfault), spouštěčem je start aplikace (WebView) – ne proces AVIORu,
ne paměť, ne disk, ne Vulkan. Symboly binárky nejsou → přesná funkce pádu UNKNOWN.
**Experiment (běží):** připnutá starší binárka `emulator-build: 12414864`. Výsledek UNKNOWN do jeho doběhnutí.
**Smoke skript:** zmizelé zařízení = exit 2 „NEOVĚŘEN“, nikdy PASS (`tools/crucible/emulator_smoke.sh`).
Emulátor ≠ telefon: na telefonu tento mechanismus (qemu) neexistuje – ale to je úvaha, ne měření.
