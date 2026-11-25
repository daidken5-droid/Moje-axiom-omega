# VEGA · Doporučení pro další vývoj

**První vývojový cíl:** Uživatel vloží několik českých článků nebo dokumentů, položí spornou otázku a u každé části odpovědi může otevřít přesnou pasáž, ze které závěr vychází. Pokud tři články převzaly jedinou agenturní zprávu, VEGA to ukáže. Pokud je doložená událost a její motiv zůstává nejasný, čtenář pozná rozdíl.

Podkladem je statická revize zdrojového archivu `a1235fa` z 25. listopadu 2026. Níže uvedené scénáře jsou návrhy přejímacích testů. Běh APK na zařízení ani výsledky CI pro tento archiv nebyly při revizi ověřeny.

## 1. První sprint — opravit posuzování nezávislosti zdrojů

**Kde začít:** `vegacore/src/main/kotlin/cz/axiom/vega/core/Evidence.kt`, funkce `parse`, `lines` a `assess`. Webová rešerše používá také `astraea/VegaAdapters.kt` a jeho `originOf`.

Parser umí oběma článkům přiřadit původ `agentura:čtk`. Funkce `lines` však u značek mimo URL hledá průnik alespoň dvou slov. Samotný identifikátor agentury tvoří jediný token. U dvou různě formulovaných přepisů pak může rozbor dokumentů ohlásit dvě nezávislé linie. Test v `WorkspaceScanTest.kt` u takové dvojice ověřuje průnik rozpoznaných značek. Rozhodující je ale až výsledek, který čte uživatel.

**Doporučená změna.** Rozlišit explicitní převzetí zprávy, společný odkaz, pouhou zmínku o organizaci a neznámý původ. Shodná agentura u dvou deklarovaných přepisů stejné zprávy zakládá jednu linii pro dané tvrzení. Samotný název agentury ani společná doména nestačí ke sloučení všech jejích textů. U nedohledatelného původu zobrazit „nezávislost neurčena“. Webový adaptér dnes vybírá první rozpoznaný původ. Použít stejné testovací podklady pro web i místní dokumenty, aby jejich pořadí neměnilo závěr.

| Přejímací případ | Očekávané chování |
|---|---|
| Dva odlišně formulované články výslovně přebírají stejnou zprávu ČTK. | Dva články, jedna doložená linie. Výsledek drží i při obráceném pořadí vstupů. |
| Článek ČTK pouze zmiňuje jako účastníka události. | Zmínka sama nevytvoří vazbu převzetí. |
| Článek přebírá datum z agenturní zprávy a přidává vlastní rozhovor o příčině. | Původ lze přiřadit zvlášť k datu a k výroku z rozhovoru. Výrok je připsán konkrétnímu mluvčímu. |
| Dva weby uveřejní podobnou informaci bez uvedení původu. | Uživatel vidí dva zdroje a neurčenou nezávislost. |
| Dva samostatné prvotní dokumenty přímo dokládají stejný údaj. | Zůstanou dvě dohledatelné linie a obě pasáže lze otevřít. |

**Podmínka dokončení:** Automatický test kontroluje koncový verdikt `Evidence.assess` a výsledek webového adaptéru. Alespoň jeden případ projde i z uživatelského dotazu až k zobrazenému odůvodnění.

## 2. Druhý sprint — dokončit jednu rešerši od otázky po důkaz

V `vega/src/main/java/cz/axiom/vega/Ops.kt` už `research` shromažďuje podklady a sestavuje kartu zdrojů. Cesta přes `governed` přidává kontrolu citací. V režimu Bez sítě současný webový tok nabídne uživateli příkaz pro hledání v místních souborech. Na těchto částech se dá postavit kratší postup.

1. Uživatel vybere dokumenty nebo povolí web a položí otázku. U dotazu nad už vybranými místními soubory může rozbor rovnou začít.
2. U každého důležitého závěru VEGA ukáže původ, citovanou pasáž a rozsah podpory. Zvlášť pojmenuje, co zdroj uvádí přímo, co z něj VEGA odvozuje a co mezi zdroji zůstalo sporné.
3. Klepnutí na závěr otevře konkrétní úryvek s názvem souboru nebo článku. U webu zobrazí datum vydání, je-li známé, a odděleně datum načtení.
4. Ve výchozím přehledu stačí odpověď a nejdůležitější rozpor. Podrobný audit může uživatel otevřít podle potřeby.

**Přejímací úloha:** Nad třemi místními texty uživatel v režimu Bez sítě zjistí, zda je doloženo datum uzavření mostu a proč k uzavření došlo. Z odpovědi otevře pasáž s datem. Pokud žádná pasáž nedokládá příčinu, VEGA ji nechá otevřenou. Stejnou otázku následně zpracuje nad předem uloženými webovými podklady, aby šlo oba výsledky porovnat bez změn živého webu.

## 3. Třetí sprint — prověřit autonomii na Pixelu 9a

`Ops.kt` už propojuje signály SelfTune, návrhy pravidel, testy JS rozšíření, otisk kódu a testů, aktivaci a návrat verze. `Store.kt` má samoaktualizace ve výchozím stavu vypnuté. V `.github/workflows/build-vega.yml` jsou testy jádra a Android unit testy. Další investici bych směřoval do ověření skutečného chování přes rozhraní telefonu.

| Scénář na zařízení | Co zaznamenat |
|---|---|
| Naučené pravidlo změní směrování dotazu a uživatel ho vrátí. | Výsledek stejného dotazu před aktivací, po aktivaci a po vrácení. Vrácení přetrvá restart aplikace. |
| Uživatel aktivuje rozšíření, vyzkouší druhou verzi a vrátí první. | Verze uvedená v UI odpovídá skutečnému výstupu nástroje před restartem i po něm. |
| Test nové verze selže nebo se během testu zavře obrazovka. | Předchozí funkční verze zůstane dostupná. UI popíše stav rozpracované změny. |
| Během rešerše se aplikace přesune do pozadí nebo skončí její proces. | Po návratu lze otevřít dokončené výsledky. Nedokončený běh má srozumitelný stav a možnost opakování. |

U každého běhu uložit revizi, zařízení, verzi Androidu a výsledek. Prioritu má reprodukovatelný průchod na Pixelu uživatele. Pro regresní kontrolu následně převést stabilní scénáře do emulátorového CI. Samoaktualizace ponechat v pokročilém nastavení a samostatně prověřit obnovu po přerušení.

## 4. Průběžné technické změny

- **Rozdělovat podle ověřeného chování.** `Ops.kt` má v této revizi 2 462 řádků. Po pokrytí rešerše testy vyjmout nejdřív získání podkladů a sestavení výsledku. Zachovat chování při vypnutém webu, nedostupném modelu a zrušení běhu. Registraci a správu rozšíření vyčlenit po dokončení testů jejich životního cyklu.
- **Router měnit podle naměřených chyb.** Sestavit oddělenou sadu českých parafrází. Zahrnout i rozdíl mezi „Jak se zapíná rozšíření?“ a „Zapni rozšíření“. Vedle správně rozpoznaných záměrů vykazovat počet akcí chybně spuštěných informační otázkou. Malý offline klasifikátor má smysl nasadit až po porovnání se současnou implementací.
- **Výkon měřit na cílovém telefonu.** U stejné sady místních dokumentů sledovat čas k prvnímu výsledku, čas k úplnému rozboru a maximální využití paměti. Zaznamenat velikost vstupu a zvlášť měřit první a opakovaný běh. První měření poslouží jako základ pro rozhodnutí o optimalizaci.

**Doporučené pořadí vydávání:** Nejprve oprava původu zdrojů s regresními příklady. Poté ucelená místní a webová rešerše s otevřením konkrétní pasáže. Další výchozí rozšíření přidávat podle úloh, které uživatelé skutečně opakují, až po ověření aktivace a návratu na telefonu.
