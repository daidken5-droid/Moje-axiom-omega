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
- [ ] 24. Doručit APK 1.4.3 (CI), SHA-256
- [ ] 25. Red-team kolo 5: Memory / Evidence / Econ / Grounding (paměť a její zapomínání, důkazy a nezávislost zdrojů, výpočty, ověřovač citací)

## Problémy
- DNS rebinding: Http ověří adresy jména, ale HttpURLConnection jméno překládá znovu (TOCTOU). Plné řešení = připojení na ověřenou IP s SNI; zatím nízké riziko (útočník musí ovládat DNS a VEGA musí stáhnout jeho stránku).
