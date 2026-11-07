# AVIOR Ω – CONVERGENCE (Delta IV, sestavení 1005)

## Výchozí stav (audit bez předpokladů)
- **Git:** čistý strom, poslední stabilní vydání 1004 (APK ověřeno: versionCode 1004, podpis 66a2e14c…).
- **Kód:** jádro 8,8 tis. řádků Kotlinu, aplikace 6,0 tis.; 36 testovacích souborů, `:core:test` a `:app:compileDebugKotlin` prochází.
- **Neověřitelné v tomto prostředí:**
  - běh na telefonu (llama.cpp, RAM, teplo, životní cyklus Androidu),
  - varianty A–D „inteligence s mozkem“ – vyžadují skutečný model; kontejner žádný nemá.

## A. Architektonický verdikt

| Komponenta | Verdikt | Důvod (doložený) |
|---|---|---|
| Reflex (STEP-0 bez mozku) + Δ Lab | **zachovat** | měřitelný: final 49/50, 0 chyb, medián < 1 ms |
| Strážce faktů + PROOFCORE (původ, revize) | **zachovat** | invarianty B a D testované; 0/8 vlastních neověřených odpovědí |
| WikiReflex × Strážce | **sloučit parser** | dva parsery životopisné závorky; první krok hotový: sdílený `Sentinel.UNCERTAIN` (oprava G). Úplné sloučení je další krok. |
| E-ARL × Router × RANDY Supervisor | **kandidát na sloučení** | tři vrstvy rozhodují „kolik myšlení“. Bez měření na zařízení nejde doložit, že jedna stačí – proto jen navrženo, nic neodstraněno. |
| FreeCloud řetěz | **zachovat** | invariant C: ≤ 2 pokusy na poskytovatele (test) |
| Air-gap / Http | **zachovat** | invariant E: blokuje před spojením (test) |
| Nástroj agenta `memory_save` | **přepracováno** | porušoval invariant D (viz níže) |

## B/C. Ověřené změny a odstraněné chyby

| ID | Priorita | Problém (reprodukováno testem) | Příčina | Oprava | Test |
|---|---|---|---|---|---|
| CV-1 | P1 | `memory_save` agenta ukládal text MODELU jako FAKT s vahou 1,0 → reflex by ho vracel jako jistotu o uživateli | chyběla provenience paměti | ukládá se jako EPIZODA (váha 0,7); fakta od uživatele dál ukládá SelfModel z jeho slov | `InvariantsTest.d_agentMemoryIsNotFact` |
| CV-2 | P2 | WikiReflex: „(asi 1045 – …)“ → „narodil(a) **1045**“ – falešná přesnost | vlastní parser bez detekce nejistoty | „asi 1045“, jistota 86 místo 90; sdílený `Sentinel.UNCERTAIN` | `NoFalsePrecisionTest` |

Nové testy invariantů (všechny prošly):
- **B:** bez kladného auditu nikdy původ WIKI (vlastnostní test, 2 000 vstupů).
- **C:** omezený počet pokusů cloudu.
- **E:** air-gap a http jen do lokální sítě.

## D. Výkon
Bez nové optimalizace. Δ Lab beze změny k horšímu (dev 30/30, final 49/50, 0 chyb; p50 0,6–0,8 ms, kolísá mezi běhy).

## E. Inovace
- **Value of Information** (doporučení u srovnání: „verdikt rozhodne údaj X, který chybí“) – **pouze navrženo**, neimplementováno.
  Mlčení a předání mozku je zatím bezpečnější a nemám data, že částečná odpověď pomůže.
- **Životní cyklus znalosti** (NEOVĚŘENO → OVĚŘENO → ZASTARALÉ → PŘEHODNOCENO) už pokrývá PROOFCORE:
  - NEOVĚŘENO = MODEL,
  - OVĚŘENO = USER (✓ uživatele) nebo WIKI (Strážce potvrdil konkrétní tvrzení a nic nevyvrátil),
  - ZASTARALÉ = po `reviseAt`,
  - PŘEHODNOCENO = nová odpověď přepíše záznam.

  Nový modul jsem nepřidával (pravidlo „žádné zbytečné moduly“).

## F. Zjednodušení
Sdílená definice nejistého data místo dvou. Další řádky se neodstraňovaly bez důkazu, že funkce není potřeba.

## G. Zbývající rizika
- Nic neměřeno na zařízení (paměť, teplo, rušení během načítání modelu, návrat po ukončení procesu).
- **R-01 (sestavení 1006) – implementováno, ověření běží v CI:**
  - migrace bez `runCatching`, sloupce přes PRAGMA, samooprava při otevření,
  - test `app/src/test/.../DbMigrationTest.kt` (upgrade v3, napůl migrovaná DB, čistá instalace) se zkompiloval;
  - lokálně ho nešlo spustit, protože Maven Central vrací z kontejneru 429 – spouští ho CI (`build-apk.yml`).
- E-ARL × Router × RANDY: možná redundance bez měření.
- Δ Lab měří jen režim bez mozku; kvalitu odpovědí s mozkem neměří nic.

## I. Výstupy
- **Kód a testy:** větev `claude/reverse-engineer-ai-app-o5jhyd`.
- **Konfigurace sestavení:** `app/build.gradle.kts`, `.github/workflows/build-apk.yml`.
- **APK:** release `avior-delta-iv-c1006`.
- **Dokumentace:** `CHANGELOG.md`, tato zpráva, `docs/FAULTLINE_AUDIT.md`, postup reprodukce `docs/REPRODUCE.md`.

## J / H. Další krok s největším užitkem
Instrumentační test na emulátoru (migrace DB, zrušení generování, návrat z pozadí) v CI. Je to jediná cesta, jak ověřit invarianty A a F a riziko R-01.
