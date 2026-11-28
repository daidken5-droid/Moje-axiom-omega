package cz.axiom.vega.calc

import cz.axiom.vega.base.Text

/**
 * Jmeniny v Česku bez modelu (sobota 28. 11.). Data: cs.wikipedia.org/wiki/Jmeniny_v_Česku (staženo 28. 11. 2026, převzato doslova;
 * u dnů bez jména na stránce státní svátek / Tři králové). Wikipedie u části dnů uvádí víc jmen z různých kalendářů – vypisují se všechna.
 */
object NameDays {
    const val SOURCE = "seznam jmenin v Česku (Wikipedie)"
    private val DATA: Map<String, String> = """
01-01|Nový rok, Den obnovy samostatného českého státu (státní svátek)
01-02|Karina, Vasil, Ábel, Dětmar
01-03|Radmila, Radomil, Jennifer, Jenovéfa
01-04|Diana
01-05|Dalimil a Teofan
01-06|Tři králové
01-07|Vilma, Wilhelmina, Atila
01-08|Čestmír a Erhard
01-09|Vladan a Walter
01-10|Břetislav
01-11|Bohdana a Teodora
01-12|Pravoslav a Arkád
01-13|Edita, Edyta, Leontýna
01-14|Radovan a Radúz
01-15|Alice, Severín, Alyson
01-16|Ctirad a Ctislav
01-17|Drahoslav
01-18|Vladislav a Vladislava
01-19|Doubravka
01-20|Ilona a Sebastián
01-21|Běla a Bianka
01-22|Slavomír, Slavomíra, Mnislav
01-23|Zdeněk
01-24|Milena
01-25|Miloš a Milota
01-26|Zora, Danica
01-27|Ingrid
01-28|Otýlie
01-29|Zdislava a Zdeslava
01-30|Robin, Robyn, Erna
01-31|Marika a Spytihněv
02-01|Hynek
02-02|Nela
02-03|Blažej
02-04|Jarmila
02-05|Dobromila a Dobromíra
02-06|Vanda a Danuše
02-07|Veronika, Bereniké, Verona
02-08|Milada a Mlada
02-09|Apolena a Apolonie
02-10|Mojmír
02-11|Božena a Dezider
02-12|Slavěna a Slávka
02-13|Věnceslav, Věnceslava, Věnka
02-14|Valentin, Valentýn, Valentýna
02-15|Jiřina a Jorga
02-16|Ljuba
02-17|Miloslava
02-18|Gizela a Gisel
02-19|Patrik
02-20|Oldřich
02-21|Lenka a Eleonora
02-22|Petr a Etel
02-23|Svatopluk
02-24|Matěj a Matyáš
02-25|Liliana a Lilie
02-26|Dorota a Dorothea
02-27|Alexandr a Saša
02-28|Lumír
02-29|Horymír
03-01|Bedřich, Bedřiška, Frederik
03-02|Anežka
03-03|Kamil a Vladivoj
03-04|Stella
03-05|Kazimír
03-06|Miroslav
03-07|Tomáš
03-08|Gabriela a Zoltán
03-09|Františka, Rebeka, Franka
03-10|Viktorie
03-11|Anděla a Angelika
03-12|Řehoř
03-13|Růžena, Rosalie, Rosa
03-14|Rut, Matylda, Vlastibor
03-15|Ida a Idunn
03-16|Elena a Herbert
03-17|Vlastimil a Vlastimila
03-18|Eduard
03-19|Josef a Josefa
03-20|Světlana a Světla
03-21|Radek a Vesna
03-22|Leona, Leontina, Lea
03-23|Ivona
03-24|Gabriel a Zbyslav
03-25|Marian
03-26|Emanuel
03-27|Dita
03-28|Soňa
03-29|Taťána, Slavomil, Táňa
03-30|Arnošt a Ernest
03-31|Kvido
04-01|Hugo
04-02|Erika a Rosalind
04-03|Richard
04-04|Ivana
04-05|Miroslava
04-06|Vendula a Venuše
04-07|Heřman a Hermína
04-08|Ema
04-09|Dušan
04-10|Darja
04-11|Izabela, Izabel, Ratmír
04-12|Julius a Julian
04-13|Aleš
04-14|Vincent
04-15|Anastázie a Stáza
04-16|Irena a Bernadeta
04-17|Rudolf
04-18|Valérie
04-19|Rostislav
04-20|Marcela
04-21|Alexandra a Selma
04-22|Evženie
04-23|Vojtěch
04-24|Jiří
04-25|Marek a Markus
04-26|Oto
04-27|Jaroslav
04-28|Vlastislav
04-29|Robert
04-30|Blahoslav
05-01|Svátek práce (ostatní svátek, den volna)
05-02|Zikmund
05-03|Alexej, Alexie, Timea
05-04|Květoslav a Florián
05-05|Klaudie a Claudie
05-06|Radoslav a Radovít
05-07|Stanislav a Stanimír
05-08|Den vítězství (státní svátek)
05-09|Ctibor
05-10|Blažena
05-11|Svatava
05-12|Pankrác
05-13|Servác
05-14|Bonifác a Dina
05-15|Žofie, Sofie, Selena
05-16|Přemysl
05-17|Aneta
05-18|Nataša
05-19|Ivo
05-20|Zbyšek
05-21|Monika
05-22|Emil a Emilian
05-23|Vladimír
05-24|Jana a Vanesa
05-25|Viola, Violeta, Violína
05-26|Filip a Filippa
05-27|Valdemar a Vojslav
05-28|Vilém a Wilem
05-29|Maxmilián a Maxim
05-30|Ferdinand
05-31|Kamila a Camilla
06-01|Laura
06-02|Jarmil a Jaromil
06-03|Tamara a Kevin
06-04|Dalibor
06-05|Dobroslav
06-06|Norbert
06-07|Iveta a Slavoj
06-08|Medard
06-09|Stanislava
06-10|Gita
06-11|Bruno, Amabel, Mabel
06-12|Antonie
06-13|Antonín
06-14|Roland
06-15|Vít, Svantovít, Isolde
06-16|Zbyněk
06-17|Adolf
06-18|Milan
06-19|Leoš a Leon
06-20|Květa
06-21|Alois
06-22|Pavla
06-23|Zdeňka a Zdenka
06-24|Jan
06-25|Ivan
06-26|Adrian
06-27|Ladislav
06-28|Lubomír
06-29|Petr a Pavel
06-30|Šárka a Vlastimír
07-01|Jaroslava
07-02|Patricie
07-03|Radomír
07-04|Prokop
07-05|Cyril a Metoděj
07-06|Den upálení mistra Jana Husa (státní svátek)
07-07|Bohuslava
07-08|Nora, Edgar, Kilian
07-09|Drahoslava, Berenika, Leticie
07-10|Libuše, Amálie, Ludoslav
07-11|Olga
07-12|Bořek
07-13|Markéta
07-14|Karolína
07-15|Jindřich a Egon
07-16|Luboš
07-17|Martina
07-18|Drahomíra, Drahuše, Drahomila
07-19|Čeněk
07-20|Eliáš, Ilja, Elias
07-21|Vítězslav a Vitoslav
07-22|Magdalena
07-23|Libor a Larisa
07-24|Kristýna a Kristin
07-25|Jakub
07-26|Anna
07-27|Věroslav a Heidi
07-28|Viktor
07-29|Marta a Beatris
07-30|Bořivoj
07-31|Ignác a Elin
08-01|Oskar
08-02|Gustav
08-03|Miluše
08-04|Dominik a Dominika
08-05|Kristián a Oswald
08-06|Oldřiška
08-07|Lada
08-08|Soběslav
08-09|Roman, Ratibor, Ratislav
08-10|Vavřinec
08-11|Zuzana
08-12|Klára
08-13|Alena
08-14|Alan a Lorelai
08-15|Hana a Mirabel
08-16|Jáchym
08-17|Petra
08-18|Helena
08-19|Ludvík
08-20|Bernard
08-21|Johana
08-22|Bohuslav
08-23|Sandra
08-24|Bartoloměj
08-25|Radim
08-26|Luděk a Ludiše
08-27|Otakar a Otokar
08-28|Augustýn a Augustin
08-29|Evelína a Evelin
08-30|Vladěna a Vlaďka
08-31|Pavlína
09-01|Linda a Samuel
09-02|Adéla
09-03|Bronislav
09-04|Jindřiška a Henrieta
09-05|Boris
09-06|Boleslav a Bolemír
09-07|Regína
09-08|Mariana a Marion
09-09|Daniela
09-10|Irma
09-11|Denisa a Denys
09-12|Marie
09-13|Lubor
09-14|Radka
09-15|Jolana a Jolantha
09-16|Ludmila
09-17|Naděžda a Naděje
09-18|Kryštof
09-19|Zita
09-20|Oleg
09-21|Matouš
09-22|Darina a Jimram
09-23|Berta
09-24|Jaromír
09-25|Zlata
09-26|Andrea
09-27|Jonáš
09-28|Václav
09-29|Michal a Michael
09-30|Jeroným a Ráchel
10-01|Igor
10-02|Olivie a Oliver
10-03|Bohumil
10-04|František a Francis
10-05|Eliška
10-06|Hanuš
10-07|Justina
10-08|Věra
10-09|Sára
10-10|Marina
10-11|Andrej
10-12|Marcel
10-13|Renáta
10-14|Agáta
10-15|Tereza
10-16|Havel
10-17|Hedvika a Hedvig
10-18|Lukáš
10-19|Michaela, Michala, Edwin
10-20|Vendelín
10-21|Brigita a Voršila
10-22|Sabina
10-23|Theodor
10-24|Nina
10-25|Beáta a Krispin
10-26|Erik
10-27|Šarlota a Zoe
10-28|Jidáš a Alfred
10-29|Silvie a Sylva
10-30|Tadeáš
10-31|Štěpánka
11-01|Felix
11-02|Tobiáš
11-03|Hubert
11-04|Karel a Karla
11-05|Miriam
11-06|Liběna a Leonard
11-07|Saskie
11-08|Bohumír
11-09|Bohdan
11-10|Evžen
11-11|Martin
11-12|Benedikt
11-13|Tibor
11-14|Sáva
11-15|Leopold
11-16|Otmar a Otomar
11-17|Mahulena
11-18|Romana
11-19|Alžběta
11-20|Nikola, Niké, Nikita
11-21|Albert
11-22|Cecilie
11-23|Klement
11-24|Emilie
11-25|Kateřina
11-26|Artur a Artuš
11-27|Xenie
11-28|René
11-29|Zina
11-30|Ondřej a Luna
12-01|Iva
12-02|Blanka
12-03|Svatoslav
12-04|Barbora a Bára
12-05|Jitka
12-06|Mikuláš
12-07|Benjamín
12-08|Květoslava
12-09|Vratislav
12-10|Julie a Eulalie
12-11|Dana
12-12|Simona
12-13|Lucie
12-14|Lydie
12-15|Radana
12-16|Albína
12-17|Daniel
12-18|Miloslav
12-19|Ester
12-20|Dagmar
12-21|Natálie
12-22|Šimon
12-23|Vlasta
12-24|Adam a Eva
12-25|1. svátek vánoční (ostatní svátek, den volna)
12-26|Štěpán a Štefan
12-27|Žaneta
12-28|Bohumila
12-29|Judita
12-30|David
12-31|Silvestr
""".trim().lines().associate { it.substringBefore('|') to it.substringAfter('|') }

    fun size() = DATA.size
    fun on(month: Int, day: Int): String? = DATA["%02d-%02d".format(month, day)]

    private fun names(v: String) = v.split(Regex(",\\s*|\\s+a\\s+")).map { it.trim() }.filter { it.isNotEmpty() }

    /** Jméno tak, jak je zapsané v kalendáři („rene“ → „René“), nebo null. */
    fun spelled(name: String): String? { val q = Text.fold(name).lowercase().trim()
        return DATA.values.asSequence().flatMap { names(it).asSequence() }.firstOrNull { Text.fold(it).lowercase() == q } }

    /** Dny, kdy má svátek dané jméno (porovnání bez diakritiky a velikosti písmen). */
    fun daysOf(name: String): List<Pair<Int, Int>> { val q = Text.fold(name).lowercase().trim()
        return DATA.filter { (_, v) -> names(v).any { Text.fold(it).lowercase() == q } }.keys.map { it.substring(0, 2).toInt() to it.substring(3).toInt() } }
}
