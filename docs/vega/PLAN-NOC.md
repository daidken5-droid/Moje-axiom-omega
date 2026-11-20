# Noční plán – deep scan a vylepšení VEGA

Pravidla pro každé probuzení:

1. Vezmi první nezaškrtnutý bod.
2. Udělej ho celý: kód, test, `./gradlew :vegacore:test :vega:testDebugUnitTest`.
3. Commitni a pushni na větev `claude/reverse-engineer-ai-app-o5jhyd`.
4. Bod zaškrtni a připiš krátký výsledek a commit.

Další pravidla:

- AVIOR (`app/`, `core/`) neměnit.
- Žádné placené volání, nic nefalšovat.
- Neprošlo-li něco: zapsat do „Problémy“ a pokračovat dalším bodem.
- **Kontrola Android regexů:** žádné `(?U)` ani `\b` za českým písmenem. Hlídá to AndroidRegexTest.

## Body
- [x] 1. Nové jádro (`cz.axiom.vega.base/net/llm/web/calc/files`): hloubková kontrola 3 úhly (bezpečnost, správnost, mrtvý kód) → opravy + testy
  - **Výsledek:** CoreScanTest (3 testy), celkem 39 + 21 testů zelených.
  - **Bezpečnost:**
    - SSRF přes jméno (DNS na 127.0.0.1 / LAN) – veřejný web teď překládá jméno a kontroluje všechny adresy;
    - IPv4 v IPv6 (`::ffff:127.0.0.1`), `localhost.`, `*.localhost`, multicast;
    - vlastní hlavičky se neposílají na cíl přesměrování jinam.
  - **Rozpočet:** `check` + `reserve` byly dvě operace, takže dvě souběžná placená volání mohla přečerpat strop. Nově je to atomické `tryReserve`.
  - **Správnost:**
    - „dnes je hezky“ už nevrací datum;
    - unce po odstranění diakritiky;
    - „dnes“ v počtu dní bere čas z parametru.
  - **Mrtvý kód:** nic, všechny veřejné funkce jádra se používají.
  - **Zbývá:** DNS rebinding (překlad ověřený zvlášť před spojením) jde plně řešit jen vlastním resolverem. Je to zapsané v Problémech.
- [x] 2. Úklid úložiště podle stáří a velikosti: `resume/`, `taskfile/`, `cache/long`, `evidence/`, staré `run/` (velké přílohy uložit jednou podle otisku)
  - **Výsledek:** `Housekeeping` běží při startu na výkonném vlákně. HousekeepingTest (2 testy) prošel.
  - **Nalezený únik:** seznam chatů se ořezával na 50, ale zprávy, podklady, návrhy, rozpracované dotazy a porovnání vypadlých chatů zůstávaly navždy. Teď se mažou.
  - **Mazání podle stáří:**
    - dočasný chat po 1 dni;
    - „Pokračovat“ po 7 dnech;
    - mezivýsledky dlouhých zadání po 3 dnech;
    - úlohy bez odkazu po 30 dnech;
    - osiřelé přílohy úloh (ukládají se jednou podle otisku).
  - **Bezpečnost úklidu:** chybějící nebo rozbitý seznam chatů = nic se nemaže.
  - `Kv.modified()` je nová funkce. Verze 1.4.0 (versionCode 6).
- [x] 3. Web: proč DuckDuckGo z kontejneru nic nevrací; záložní vyhledávání (Wikipedie search už je); test parseru na uloženém HTML
  - **Příčina:** parser je v pořádku, na skutečné stránce našel 10/10 výsledků. Selhávalo spojení: tunel proxy v kontejneru padal uprostřed přenosu. DDG navíc botům občas vrací stránku „anomaly“ (HTTP 202).
  - **Oprava:**
    - jeden opakovaný pokus;
    - stránka „anomaly“ = prázdný výsledek bez chyby;
    - `duckParse` je oddělený kvůli testu.
  - **Test:** WebParseTest na uloženém vzorku skutečného HTML (3 výsledky, dekódování uddg, odstranění `<b>`).
  - **NEOVĚŘENO:** lite.duckduckgo.com z kontejneru neodpověděl, takže parser pro něj jsem nenapsal naslepo.
- [x] 4. Kontrola všech regexů na Android (ICU): u každého `Regex("…")` ve VEGA ověřit konstrukce; rozšířit AndroidRegexTest o `\b` za ne-ASCII
  - **Výsledek:**
    - `Ops.kt` „najdi v mých souborech/souborů X“: `souborů)\b` se na telefonu a v testu chovalo různě. Opraveno na `(?!\p{L})`.
    - AndroidRegexTest hlídá `\b` vedle českého písmene, i přes skupiny `(…|souborů)\b`. Sám detektor má vlastní test.
    - Ostatní nálezy jsou `\b` jen u ASCII písmen, tedy v pořádku. CANON_EQ, COMMENTS ani neomezený lookbehind se nepoužívají.
    - Testy: 44 + 21 zelených.
- [x] 5. Autonomie krok 1: `SelfTune` deník signálů slabin (chyby, opravy uživatele, Stop, špatný záměr, pomalé kroky) + test
  - **Výsledek:** `core/SelfTune.kt`. SelfTuneTest (2 testy) prošel.
  - **Signály se zapisují do deníku po dnech:**
    - chyba (výpadky modelu zvlášť jako oblast „model“);
    - Stop během práce;
    - krok delší než 45 s;
    - oprava od uživatele („ne, myslel jsem…“);
    - přeformulování téhož dotazu do 90 s.
  - **Připisování:** signál se připíše oblasti, která předchozí zprávu zpracovala (záměr routeru / nástroj / postup).
  - **Soukromí:** klíče, e-maily, URL (zůstane jen doména), IBAN a delší čísla se nahrazují značkou. Věta má max. 160 znaků, bez obsahu souborů. Deník drží 30 dní a nejvýš 400 záznamů denně.
  - **Bezpečnost:** zápis nikdy neshodí práci.
- [x] 6. Autonomie krok 2: návrh příkladů routeru z deníku → ověření na held-out sadě → karta „Vylepšení“ s historií a návratem
  - **Výsledek:** `RouterTuning` + `Router.Learned` + karta Menu → Vylepšení. RouterTuningTest (2 testy) prošel.
  - **Odkud se pravidlo bere:** z deníku, když zpráva skončila v obecném rozhovoru / rešerši a uživatel ji hned opravil nebo přeformuloval. Navazující zpráva ukáže správnou akci.
  - **Kdy se zapne:**
    - samo po 2 dokladech;
    - jen když nezmění žádný z 41 případů kontrolní sady a své příklady pošle správně;
    - jinak zůstane jako návrh na klepnutí.
  - **Bezpečnost:**
    - pravidlo smí jen nahradit obecný rozhovor / rešerši, pevná pravidla nikdy nepřebije;
    - vrácené pravidlo se samo znovu nezapne;
    - každá změna má historii.
  - **Karta:**
    - naučená pravidla (stav, výrazy, doklady, historie, Vrátit / Zapnout / Zamítnout);
    - souhrn slabin za 30 dní;
    - po samozapnutí přijde oznámení.
  - **NEOVĚŘENO:** vzhled karty na telefonu (zkontroluje se v bodě 8).
- [x] 7. Autonomie krok 3: rozšíření jako JS nástroje v sandboxu (registr, oprávnění, testy, zapnout/vrátit)
  - **Výsledek:** `core/Extensions.kt` + `runExtension` ve Workeru + karta Vylepšení → Rozšíření.
  - **Postup:**
    1. Uživatel napíše „napiš rozšíření, které…“.
    2. VEGA založí projekt s pevným tvarem: `tool.js` bez require + `tool.test.js`.
    3. Na „ulož jako rozšíření“ proběhnou testy ve Workeru nad PŘESNĚ těmito soubory.
    4. Nová verze se uloží jen s otiskem, který testy prošel.
    5. Rozšíření se objeví v nástrojích pod „Rozšíření“.
  - **Správa verzí:** nové rozšíření je vypnuté. Zapnout jde jen otestovaný otisk, předchozích 5 verzí jde vrátit, vše má historii.
  - **Sandbox (ověřeno v Chromiu na hostiteli, NE v telefonu):**
    - `fetch`, `XHR` a `importScripts` jsou nedostupné;
    - `require` zablokovaný;
    - nekonečná smyčka skončí na limitu;
    - podvržený výsledek z kódu rozšíření se ignoruje (nonce).
  - **Testy:** ExtensionsTest (2 testy) prošel.
- [x] 8. UI: dotaz na souhlas vs. jiné listy (už hlídáno), spinner NFC po idle, přístupnost (aria, kontrast), kontrola na 360 px šířky
  - **Výsledek (snímky z Chromia na hostiteli, 360 px, světlý i tmavý režim, bez chyb JS a bez přetečení):**
    - Karta Vylepšení: s 2–3 tlačítky se text smrskl na jedno slovo na řádek. Tlačítka jsou teď pod textem (`.row.stack`).
    - Technické štítky (`záměr:Talk`, `ChainException`) se zobrazují česky.
    - Spinner NFC zmizel hned po idle, i když telefon poslouchal. Teď se řídí skutečným stavem poslechu (`nfc.listening` → zpráva).
    - Kontrast: bílý text na modré #5b8cff měl 3,16:1. Nově `--signal-strong` #3567e8 (4,93:1, WCAG AA) pro tlačítka a odeslání. Šedý text na listu má 5,7:1.
- [x] 9. Výkon: profil `Ops.home()` a vykreslování chatu se 300 zprávami; měření před/po
  - **Výsledek (Chromium na hostiteli, 300 zpráv):**
    - Plné vykreslení chatu: 50–70 ms → 14–15 ms, uzly 5 558 → 1 489. Vykresluje se posledních 80 zpráv, starší jsou na tlačítku „Zobrazit starší“.
    - Stream 600 průběžných částí: 0,99 s → 0,83 s celkem (už předtím rychlá cesta přes `#live`).
  - **NEOVĚŘENO:** `Ops.home()` na telefonu. Bez zařízení nejde změřit pravdivě, odhad z hostitele by byl zavádějící.
- [x] 10. Finální: CI APK, SHA-256, shrnutí noci v `docs/vega/README.md`
  - **Výsledek:** APK `VEGA-b2e0b4a.apk` (1.4.0, versionCode 6), SHA-256 `0cde2a3a45ae1db8896cdd3865ed1b70f31d21be51e7fed8b55c8a060a452b46`. Certifikát `4557a2ce…cdb4` je stejný jako dřív, APK jde nainstalovat přes 1.3.1. Doručeno uživateli.
- [x] 11. Red-team konzilium nad nočním diffem. Opraveno:
  - Placené volání přerušené Stopem nebo selhané po zaúčtování se nezapsalo do útraty (opakovaným Stop + restart šlo přečerpat strop). Teď se zapíše odhad podle přijatého textu.
  - Naučené pravidlo routeru mohlo pohltit běžný rozhovor (stačila 2 slova kdekoli). Nově musí jít o krátkou zprávu (≤ 10 slov) nebo větu podobnou příkladu. Doklad = jiný den. Akce s vedlejšími účinky (projekt, kód, výzva, NFC, rozšíření, postup) se nikdy nezapínají samy.
  - Rozšíření z jiného projektu se stejným jménem mohlo přepsat zapnuté rozšíření. Nově je id podle projektu, cizí zdroj se odmítne a nová verze se nikdy nezapne sama.
  - „vytvoř rozšíření“ s otevřeným projektem ukládalo místo založení. Uloží jen „ulož / publikuj“.
  - Test bez `expect` už neprojde (Worker i dvojník v Node). Rozšíření nepozná, jestli běží v testu.
  - Anonymizace deníku nově pokrývá hesla/PIN/CVV, tokeny JWT a Bearer, e-maily s diakritikou a čísla od 3 číslic.
  - `\w` → `\p{L}` (ICU vs. JVM).
  - Spinner NFC se vypne po Stop / chybě.
  - **Ponecháno (zapsáno v Problémech):** DNS rebinding.

## Další kolo (pro další probuzení)
- [x] 12. Hloubková kontrola `Ops.kt` (přes 1 400 řádků): mrtvé větve, duplicity, chyby v souběhu (`@Volatile` vs. složené operace), rozdělení na menší soubory bez změny chování + testy
  - **Mrtvý kód:** žádný. Všechny funkce aplikace mají volajícího, čtyři nálezy byly jen přepsání systémových metod.
  - **Souběh – dočasný chat:** `clearTemp()` mazal `ArrayList` historie z vlákna mostu, zatímco ji výkonné vlákno procházelo (hrozila ConcurrentModificationException). Teď maže na výkonném vlákně.
  - **Souběh – úlohy s revizemi:** „zkontroluj, pak přidej“ u běžících úloh šlo ve dvou krocích, takže dvě souběžné revize mohly spustit dva výpočty téže úlohy. Teď atomické `add()`.
  - **Rozdělení na soubory:** záměrně NEPROVEDENO. Přesun 1 500 řádků v noci bez možnosti vyzkoušet na telefonu je riziko bez přínosu pro uživatele. Testy 51 + 21 zelené.
- [x] 13. DNS rebinding: připojit se na ověřenou IP. Buď vlastní `SocketFactory` + SNI / `HostnameVerifier`, nebo zdokumentovaný důvod, proč ne. Test s lokálním serverem.
  - **Výsledek:** zdokumentováno, NEOPRAVENO. `HttpURLConnection` neumí vlastní resolver ani vlastní tovární třídu pro prostý socket a TLS vrstva dostane už připojený socket.
  - **Plná oprava:** přechod síťové vrstvy na OkHttp s `Dns`, který vrátí jen ověřené adresy. Přestavba 7 míst volání, kterou je nutné vyzkoušet na telefonu, proto ne v noci bez zařízení.
  - **Dopad mezery:** jen GET veřejného webu, žádné klíče ani vlastní hlavičky, odpověď se jen čte jako text.
  - **Doporučení:** samostatný blok s testem na Pixelu.
- [x] 14. Vylepšení → u opakované chyby nástroje nabídnout „Navrhnout rozšíření“ (zadání z anonymizovaných příkladů → nový projekt). Jen na klepnutí.
  - **Výsledek:** u slabiny „chyba · nástroj X“ s aspoň 2 výskyty je tlačítko „Navrhnout rozšíření“ (vzhled ověřen v Chromiu na 360 px).
  - **Co se stane po klepnutí:**
    1. VEGA založí projekt se zadáním z anonymizovaných chyb z deníku (bez obsahu souborů).
    2. Napíše `tool.js` + testy.
    3. Model a souhlas jdou běžnou cestou projektu.
    4. Uložení a zapnutí dál potvrzuje uživatel.
  - **Testy:** 51 + 21 zelené.
- [x] 15. Red-team kolo 2 nad body 12–14
  - **Výsledek:** nic kritického. Opraveno:
    - Odmítnutý souhlas nebo výpadek u návrhu se zapisoval jako další chyba nástroje (tlačítko by nezmizelo a chyba „Neodesláno“ by šla do dalšího zadání).
    - Souhlas teď výslovně uvádí „zadání z deníku chyb (N záznamů)“. Do zadání jde jen text chyb nástroje, u obecných výjimek jen jejich druh, protože zpráva výjimky může citovat obsah souboru.
    - Podmínky návrhu se kontrolují i na straně aplikace a dvojité klepnutí nezaloží dva projekty.
    - Název projektu je „Rozšíření <nástroj>“.
  - **Ověřeno beze změny:** pořadí mazání dočasného chatu, opakování úloh s revizemi, escapování v UI, komentář k DNS rebindingu.

## Další kolo 3 (pro další probuzení)
- [x] 16. Doručit APK s body 12–18 (CI), SHA-256.
  - **Výsledek:** `VEGA-f9e0363.apk` (1.4.1, versionCode 7), SHA-256 `f1d2dcc36fb382d5c3215daa993a0b94308c0142bd8c912bf8290bff318cc901`, certifikát `4557a2ce…cdb4`. Doručeno uživateli.
- [x] 17. Testy jednotek pro Ops: extPropose (podmínky, dvojité klepnutí), selftune hooky v submit/stop (Robolectric jako OpsSpecTest)
  - **Výsledek:** OpsAutonomyTest (2 testy, Robolectric, air-gap):
    - Oprava v chatu („ne, spočítej sha256…“) se zapíše jako CORRECTION u rozhovoru s výsledkem „nástroj:hash“.
    - Návrh rozšíření odmítne málo chyb i rozšíření a dvojité klepnutí ohlásí „už běží“. Selhání návrhu (bez modelu) nepřidá nástroji další chybu.
  - Hook pro Stop zůstává bez testu, protože vyžaduje běžící úlohu s modelem. Testy 51 + 23 zelené.
- [x] 18. Red-team kolo 3: Engines.kt a MainActivity.kt (most JS ↔ Kotlin, oprávnění, intenty, sdílení souborů)
  - **Výsledek:** cestu, jak se cizí kód dostane k mostu, kontrola nenašla (CSP v hlavičce i v meta, žádné inline skripty, vše escapované). Opraveno 8 bodů:
    - Po pádu rendereru se mrtvý WebView dál používal. Teď se odpojí a zničí a `extrun` se po obnově znovu nespouští, takže se pád neopakuje dokola.
    - Sdílené soubory se po obnově aktivity přidávaly znovu.
    - Dočasná chyba Keystore smazala uložený klíč. Teď se klíč maže jen u poškozených dat nebo trvale zneplatněného klíče.
    - `activate("../..")` mohlo udělat z celého úložiště VEGY „projekt“ přístupný nástrojům modelu. Id se teď kontroluje v mostu i v Ops.
    - Normalizace klíče rozbíjela tokeny `abc:def` a base64 `…==` (nové testy).
    - Klíč vlastního serveru šel po změně adresy na jiný server. Při změně hostu se smaže.
    - Sdílení 8 × 60 MB mohlo vyčerpat paměť. Strop je 120 MB celkem.
    - Nedokončený zápis do Stažených po sobě nechával záznam. Teď se smaže.
  - **Testy:** 51 + 23 zelené.

## Další kolo 4 (pro další probuzení)
- [x] 19. Robolectric test na Vault: dočasná chyba ≠ smazání klíče; most `activate` s neplatným id
  - **Výsledek:** `Vault.dropOn()` je samostatná funkce, protože Robolectric neumí Android Keystore.
  - **Kdy se klíč smaže:** jen při AEADBadTag, KeyPermanentlyInvalidated nebo poškozeném Base64. KeyStore, Provider a Unrecoverable výjimky klíč nechají.
  - **Kontrola id projektu:** jedna funkce `validProjectId` pro most, `workspace()` i `activeWorkspace()`. Podvržené `../..` = žádný aktivní projekt.
  - **Testy:** VaultAndProjectIdTest (2 testy), celkem 51 + 25 zelené.
- [x] 20. Red-team kolo 4: Workspace / CodeAgent (zápisy souborů projektu, isSecret, diff/undo, ZIP export, limity velikosti)
  - **Výsledek – VÁŽNÉ:** klíče služebního účtu Google / Firebase (`serviceAccountKey.json`, `client_secret_*.json`, `*-firebase-adminsdk-*.json`, `token.json`) prošly filtrem podle jména. V malém projektu tak šly celé do zadání modelu a do testů.
    - Filtr jmen je rozšířený.
    - Nově se kontroluje i OBSAH: privátní klíč, `private_key`, `client_secret`, `refresh_token` a vzory API klíčů. Platí pro kontext modelu, testy, export i konfiguraci.
  - **Další opravy:**
    - Obejití přes mezeru: `.env ` nebo ` .env` přes most vrátilo skutečný `.env`. Normalizace je teď stejná jako u cest.
    - Diff se 3 000 změněnými řádky potřeboval asi 290 MB a shodil aplikaci. Nad 32 MB se ukáže „celý obsah nahrazen“.
    - Úprava souboru ve windows-1250 by nevratně zničila diakritiku. Teď se odmítne s vysvětlením.
    - CSV s uvozovkami uprostřed polí se zpracovávalo kvadraticky. Teď lineárně, s nejvýš 100 hlášeními.
    - Soubor větší než limit zabral celý kontext modelu. Teď se vynechá.
    - Souběžná ruční úprava a úprava agentem šly bez zámku, takže hrozila ztracená změna nebo duplicitní id. Teď zámek na projekt.
    - Soubory s CRLF nešly upravit. Teď ano.
    - Název projektu mohl v ZIPu vytvořit cestu `../`.
  - **Testy:** WorkspaceScanTest (4 testy), celkem 55 + 25 zelené. Verze 1.4.2 (versionCode 8).
  - **Ponecháno:** číslo `1,500` (americký zápis tisíců) se čte jako 1,5. Rozhodnutí podle celého sloupce je samostatná změna parseru.

## Další kolo 5 (pro další probuzení)
- [x] 21. Doručit APK 1.4.2 (CI), SHA-256
  - **Výsledek:** `VEGA-f6b87e9.apk` (1.4.2, versionCode 8), SHA-256 `6aa5b63acb7a1b847b23a0c8f4a34c9786604281afc6124b542987242b11e0eb`, certifikát `4557a2ce…cdb4`. Doručeno uživateli.
- [x] 22. CzNum: desetinná čárka vs. americké tisíce rozhodnout podle celého sloupce (`1,500` vs. `1,5`) + testy
  - **Výsledek:** `usesDecimalComma` počítá i tvar celých čísel.
    - Skupiny po třech za čárkou (`1,500`, `12,300.50`) = americké tisíce.
    - Skupiny po třech za tečkou (`1.500`, `1.234,50`) = české tisíce.
    - Bez důkazů česky.
  - **Dopad:** sloupec `1,500; 2,300; 12,000` se dřív četl jako 1,5 / 2,3 / 12, teď 1 500 / 2 300 / 12 000.
  - **Testy:** CzNumColumnTest prošel, celkem 56 + 25 zelené.
- [x] 23. CodeAgent: nespouštět úpravy uvnitř bloku `### NOVÝ` (dvojí aplikace) + test
  - **Výsledek:** značka `### UPRAVIT` uvnitř obsahu nového souboru (např. dokumentace formátu) je text souboru, ne další úprava jiného souboru.
  - **Testy:** CodeAgentParseTest prošel, celkem 57 + 25 zelené. Verze 1.4.3 (versionCode 9).

## Další kolo 6 (pro další probuzení)
- [x] 24. Doručit APK 1.4.3 (CI), SHA-256
  - **Výsledek:** `VEGA-fef8147.apk` (1.4.3, versionCode 9), SHA-256 `eae025c75e2ea8668912f349badaef21903144e989e041b9f291ce802872dd98`, certifikát `4557a2ce…cdb4`. Doručeno uživateli.
- [x] 25. Red-team kolo 5: Memory / Evidence / Econ / Grounding (paměť a její zapomínání, důkazy a nezávislost zdrojů, výpočty, ověřovač citací)
  - **Výsledek – chybné výpočty, opraveno:**
    - Tarif nad 12 měsíců: roční se účtoval jen jednou (24 měsíců: „o 1 680 Kč levněji“ místo správných 480 Kč). Teď se počítá každý započatý rok, u vratného poměrná část.
    - „Roční 1200 Kč / měsíční 120 Kč“: měsíční cena převzala roční číslo.
    - Neúplné „a kdyby cena byla 150?“ přepsalo uloženou kalkulaci neúplnými daty. Teď se doplní z uložené kalkulace.
  - **Další opravy:**
    - Kopie téže URL (tečka na konci, `#kotva`, `www`) se počítaly jako nezávislé zdroje. Naopak různá videa YouTube splynula v jeden zdroj.
    - Zkratka „ČTK“ se v testu (JVM) četla jako „TK“, takže se lišila od telefonu.
    - Datum s pevnou mezerou.
    - Zapomenutí bez otevřeného projektu mazalo ve VŠECH projektech. Teď jen v globální paměti a podle celých slov.
    - Tajné údaje v paměti: „heslo k wifi je…“, „password is…“, číslo účtu, IBAN a rodné číslo se neuloží.
  - **Testy:** Round5Test (3 testy), celkem 60 + 25 zelené. Verze 1.4.4 (versionCode 10).

## Další kolo 7 (pro další probuzení)
- [x] 26. Doručit APK 1.4.4 (CI), SHA-256
  - **Výsledek:** sestavení 1.4.4 nahradila 1.4.5 (obsahuje i bod 27). Doručeno `VEGA-4140644.apk` (1.4.5, versionCode 11), SHA-256 `3e4e0f62a0f02ff1071ac187369f8f2ed96f2432f99ac6275e2fa5e2b71cfd98`, certifikát `4557a2ce…cdb4`.
- [x] 27. Zbytek kola 5:
  - po zapomenutí nenechat kopii v historii chatu (neopakovat zapomenutý text, vyřadit ho z kontextu modelu);
  - smazat navázané `econ` / `tariff`;
  - Grounding: když okno vynechá větu s negací nebo s obsahovými slovy tvrzení, vrátit NEVYHODNOCENO;
  - dělič vět nesmí být kvadratický;
  - Econ: porovnávat zaokrouhlené hodnoty.
  - **Výsledek:**
    - Zapomenutí zapomenutý text neopakuje a v historii chatu (kontext modelu) ho nahradí „[zapomenuto]“. Navázaná kalkulace (`econ/*.json`) se smaže.
    - Ověřovač: když mimo okno modelu zůstane věta s negací a aspoň 2 slovy tvrzení, výsledek je NEVYHODNOCENO, ne PODPORUJE.
    - Dělič vět je lineární: dřív kopíroval zbytek textu a pak ho prohledával u každé tečky, teď 80 000 teček pod 3 s.
    - Econ počítá v haléřích: 0,3 − 0,1 už nedá „<“ u nulového zisku ani falešný zbytek u bodu zvratu.
  - **Testy:** Round5bTest prošel, celkem 61 + 25 zelené. Verze 1.4.5 (versionCode 11).
  - **NEOVĚŘENO:** okno ověřovače s modelem, protože skutečný model v testech nemám (logika je pokrytá jen čtením kódu).

## Další kolo 8 (pro další probuzení)
- [x] 28. Red-team kolo 6: Router + Statement + Procedure Runner (záměry na okrajových větách, zpracování výpisů různých bank, ukládané postupy)
  - **Výsledek – TIŠE ŠPATNÉ SOUČTY, opraveno:**
    - Uložený postup na souboru se změněnou hlavičkou četl ze staré pozice třeba zůstatek. Teď se postup zastaví s vysvětlením.
    - Různé měny se sčítaly dohromady (−100 EUR + −2 000 CZK = −2 100). Teď jsou součty po měnách, hlavní měna zvlášť a měsíce po měnách.
    - Stejný soubor dvakrát v porovnání zdvojil CELKEM. Teď se počítá podle otisku jen jednou.
    - KB: „Originální měna“ se brala jako měna účtu.
  - **Router a postupy:**
    - „porovnej ceny elektřiny“ se už nespouští jako porovnání textů;
    - „hashtag“ už nespustí kontrolní součet;
    - „cenový štítek“ už nespustí NFC;
    - „Zpět k receptu“ už nevrací změnu;
    - „případ“ / „padesát“ už není „pád“;
    - uložené postupy se hledají podle celých slov („fakt dobrý nápad“ ≠ „Faktury“, „nezpracovávej“ ≠ „zpracovat“).
  - **Další opravy:**
    - Kontrolní součet bez souboru se zeptá na soubor (dřív hlásil „soubor se liší“).
    - Regex rozboru pádu přetékal zásobník a Runner teď chytá i Error.
    - Tlačítko s výchozím názvem přepsalo cizí postup. Teď dostane nový název.
    - Nový je návrat postupu na předchozí verzi (`ProcedureStore.revert`).
  - **Testy:** Round6Test (4 testy), celkem 66 + 25 zelené. Verze 1.4.6 (versionCode 12).
- [x] 29. Ranní shrnutí noci pro uživatele v `docs/vega/README.md` (všechny verze 1.4.0–1.4.x, co je NEOVĚŘENO na telefonu, co čeká na rozhodnutí)
  - **Výsledek:** tabulka verzí s otisky, seznam toho, co je NEOVĚŘENO na telefonu, a otevřená rozhodnutí na začátku README.

## Uživatel: celkem 9 kol kontroly + opravdová vylepšení, shrnutí až potom
Kola 1–5 jsou hotová, kolo 6 běží (bod 28). Každé kolo = kontrola, opravy s testy a aspoň jedno vylepšení, které uživatel pozná.
- [x] 30. Vylepšení: rychlé odpovědi bez modelu
  - DPH (s / bez, 21 / 12 %), procentní změna, „o kolik %“;
  - datum za N dní / týdnů, den v týdnu, věk / výročí;
  - převody (dávky, plocha, objem, data).
  - **Výsledek:** nové odpovědi přesně, offline, bez modelu a bez kvóty:
    - DPH: „1000 bez DPH“ → 1 210 Kč, „1 210 s DPH“ → 1 000 Kč + DPH, „DPH z 2000“, sazba 21 / 12 / 0 % podle dotazu;
    - procenta: „o kolik % je 120 víc než 100“, „změna z 200 na 150 v %“, „kolik procent je 30 ze 120“;
    - data: „za 30 dní“ / „před 2 týdny“ / „za 3 měsíce“ s dnem v týdnu, „jaký den v týdnu je 24. 12. 2026“, „kolik let je od 5. 3. 1990“ včetně dalšího výročí.
  - **Hranice:** běžné věty („pojedu za babičkou na 3 dny“, „jaké je DPH na knihy“) se nechytí. Jdou modelu, tam je potřeba úsudek.
  - **Testy:** QuickPlusTest prošel, celkem 62 + 25 zelené.
  - **Doplněno později:** plocha (m², ha, ar, km², ft², akr), kuchyně (dl, cl, hrnek, lžíce, lžička, m³), data (kB–TB i KiB–TiB), čas (s, min, h, dny, týdny), energie (kcal, kJ, kWh). QuickUnitsTest prošel.
- [ ] 31. Red-team kolo 7: skutečné toky laika end-to-end. Robolectric scénáře „první spuštění → klíč → dotaz → soubor → export → paměť → zapomenutí“: hledat místa, kde VEGA odpoví špatně nebo nesrozumitelně. Opravy a vylepšení odpovědí.
- [ ] 32. Red-team kolo 8: výkon, baterie, úložiště a odolnost (velké soubory, dlouhé chaty, výpadky sítě uprostřed, restart procesu uprostřed úlohy). Měření před a po.
- [ ] 33. Red-team kolo 9: závěrečná kontrola celého nočního diffu (bezpečnost, správnost, mrtvý kód), regresní testy.
- [ ] 34. Teprve teď: finální APK + shrnutí všech 9 kol pro uživatele

## Vrstva C (uživatel: „chci, aby se aplikace mohla kompletně sama upravovat“)
- [x] 35. Samoúpravy aplikace 1.5.0
  - **Postup:** GitHub klient → pravidla cest → úprava modelem → commit `vega/auto-…` + PR → CI bez klíčů s odděleným podpisem → APK s ověřeným otiskem → instalace s kontrolou balíčku, certifikátu a verze.
  - **Spuštění:** z chatu, z karty, nebo samo 1× denně z deníku slabin.
  - **Testy:** SelfUpdateTest (4 testy: pravidla, výběr souborů, sled API a pojistka větve, stav), celkem 70 + 25 zelené.
  - **NEOVĚŘENO:** celý tok proti skutečnému GitHubu a instalace na telefonu (chybí token; CI `vega-auto.yml` poběží až s prvním PR).

- [x] 36. Red-team kolo 7 (samoúpravy) – opraveno:
  - **KRITICKÉ – nepřezkoumaný kód modelu by se podepsal a nainstaloval.** Nově:
    - hlídač citlivých API v aplikaci i v CI;
    - chráněné soubory (trezor, most, síť, samoúpravy);
    - instalace až z obrazovky se změnami (diff).
  - **KRITICKÉ – podpisový klíč dosažitelný z běhu s kódem modelu.** Nově:
    - CI na push, ne pull request (bez cache pracovní větve a bez workflow AVIORu);
    - podpis jen z prostředí `vega-sign`, které nastaví uživatel;
    - návod na ruleset.
  - **Další opravy:**
    - běh CI podle workflow a commitu;
    - povolené cesty místo zakázaných (`res/xml-v26`);
    - hlídač bez přejmenování a jen pro běžné soubory;
    - automatické zlepšování ve výchozím stavu vypnuté;
    - ukázky z deníku označené jako data;
    - APK proudem se stropem;
    - kontrola rozpracovaných úprav po startu, po 48 h jako selhané;
    - spouštěcí věta podle textu bez diakritiky.
  - **Ověřeno:** hlídač CI lokálně na zkušebním git repozitáři (symlink, přejmenování gradle, citlivé API a chráněný soubor odmítnuty, povolená změna prošla).
  - **Testy:** 71 + 25 zelené.
  - **Zablokováno bezpečnostní kontrolou prostředí:** workflow pro export náhradního klíče jako artefakt. Klíč do prostředí `vega-sign` musí nastavit uživatel (návod v AUTONOMIE.md).

- [x] 37. Red-team kolo 8 (toky laika, odolnost) – opraveno (1.5.1):
  - **REGRESE z mé noční změny:** úklid při startu mazal konverzace projektů (`proj-*` nejsou v seznamu chatů). Teď se drží, dokud projekt existuje. Regresní test přidán.
  - **Ztráta práce:**
    - Záznam „Pokračovat“ se ukládá PŘEDEM, takže zabití procesu nebo aplikace na pozadí uprostřed odpovědi nevadí. Po startu přijde karta „Pokračovat“ do chatu, kde chybí odpověď.
    - „pokračuj, kde jsme skončili“ najde rozpracovaný požadavek.
    - Jakákoli síťová chyba (reset, přerušení) = výpadek s kartou „Pokračovat“, ne anglický toast.
    - Stream bez [DONE] se už nevydává za celou odpověď.
  - **Chyby:**
    - Chyby se ukládají i do chatu, ne jen 7 s toastu.
    - Přihlašovací stránka Wi-Fi (SSL) a plné úložiště mají srozumitelné české hlášky.
  - **Poškozená data:** poškozený `chats.json` nebo chat se odloží jako `.bad` a aplikace neuvízne. Poškozená paměť se odloží a příští „pamatuj si“ ji nepřepíše.
  - **Velké soubory:** soubor nad 15 MB dostane v chatu srozumitelné odmítnutí místo pádu paměti. Neuložitelný kontrolní bod neshodí výsledek. Úklid běhů podle času souboru, bez parsování všech.
  - **Výpočty:** „kolik je 15 % z 2400“ i s klíčem počítá telefon (dřív šlo modelu).
  - **FileKv:** nedokončený zápis už nepřepíše cíl napůl a dočasný soubor se uklidí.
  - **Testy:** 72 + 25 zelené.

## Problémy
- DNS rebinding: Http ověří adresy jména, ale HttpURLConnection jméno překládá znovu (TOCTOU). Plné řešení = připojení na ověřenou IP s SNI; zatím nízké riziko (útočník musí ovládat DNS a VEGA musí stáhnout jeho stránku).
