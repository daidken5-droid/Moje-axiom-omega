# Celodenní plán (21. 11. 2026) – VEGA jako živá aplikace na Google Play

Přístup: aplikace je „v produkci“, uživatelé ji mají nainstalovanou. Každou změnu dělám zpětně kompatibilně
(zachovat podpis/certifikát, formát uložených dat, jména metod mostu), s migrací a bez ztráty dat. Vše commituju
po částech, s testy. Rozlišuji ověřeno (test/Chromium) × NEOVĚŘENO (telefon).

## Fáze A – audit po obrazovkách (jako revize na Play)
Projít jednotlivě: úvod, chat, přílohy, výsledky/karty, projekty, nástroje, Hardware, Nastavení, menu, paměť.
U každé: co vidí laik, co se může pokazit, přístupnost, soukromí. Nálezy → opravit.

## Fáze B – přílohy proudem do souboru + stabilní ID + index podle ID (odložené, největší)
- Příloha se ukládá do dočasného souboru s limitem, v paměti se nedrží celý ByteArray.
- Každá příloha a dokument má stabilní ID; index klíčuje podle ID (dva „faktura.pdf“ se nepřepíšou).
- Odebrání dokumentu z vyhledávání. Migrace starého indexu (zná jen jméno).

## Fáze C – rozpracovaný vstup (draft) podle konverzace
Draft a seznam příloh patří konkrétnímu chatu; přepnutí chatu nepřepíše adresáta ani text.

## Fáze D – reorganizace nastavení/menu podle úkolů (laik)
Běžná cesta „Zeptat se / Pracovat se souborem / Najít v dokumentech“; odborné do Pokročilých.

## Fáze E – offline diktování: ověření dostupnosti lokálního rozpoznávání
V režimu Bez sítě nabídnout jen ověřené místní rozpoznávání; jinak vysvětlit a nechat psaní.

## Fáze F – připravenost na Play + CI/podpis (konzervativně, jen VEGA)
Oprávnění a jejich zdůvodnění, Data safety přehled, cílení SDK, podpis odděleně od buildu s kódem (bez změny certifikátu).

## Fáze G – finální regrese, APK, poznámky k vydání
Celý běh testů, sestavení, shrnutí změn po souborech.

Výsledky každé fáze zapisuji sem + commit.

## Výsledky

### Fáze B – hotovo
- Lokální index klíčuje podle stabilního ID (`uid`), ne podle jména → dva „faktura.pdf“ se nepřepíšou.
- Odebrání dokumentu z vyhledávání (`indexRemove`), správa dokumentů v Nastavení.
- Migrace starého indexu (schéma v2, `uid = jméno`). Opraven pád FTS4 na rezervovaném názvu sloupce (`docid` → `uid`).
- Ověřeno: `:vegacore:test`, `:vega:testDebugUnitTest`, offline UI testy.

### Fáze C – hotovo
- Rozpracovaný vstup (text + přílohy) je vázaný na konkrétní konverzaci (`S.drafts` podle `spaceKey`).
- Při odchodu z chatu se draft uloží, při návratu obnoví; po odeslání se draft zahodí.
- Přepnutí chatu už nepřenese text ani přílohy k jinému adresátovi (dřív byly `#input` a `S.files` globální).
- Ověřeno: offline UI test „draft podle konverzace“ (9 scénářů zeleně), kontrola syntaxe app.js.
