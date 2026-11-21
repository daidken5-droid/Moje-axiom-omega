package cz.axiom.omega.core
import cz.axiom.omega.core.agent.*
import org.junit.Test
/** Pokrytí běžných jednoduchých otázek bez mozku (zbytek: počasí/kurz kryptoměn = internet, povely = modul telefonu). */
class EverydayCoverageTest {
    @Test fun probe() {
        val now = java.time.LocalDateTime.of(2026, 11, 2, 14, 30).atZone(java.time.ZoneId.of("Europe/Prague"))
        val c = Reflex.Ctx(Instant.Ctx(battery = 70, charging = false, now = now), today = now.toLocalDate())
        val qs = listOf(
            "kolik je hodin", "jaký je den", "kdy je velký pátek", "kdy jsou vánoce", "kdy je štědrý den", "kdy je nový rok", "kdy je den matek", "kdy je den otců",
            "kdy se mění čas", "kdy se posouvá čas", "kdy je letní čas", "kolik dní do silvestra", "kolik dní do nového roku", "kolik týdnů do vánoc",
            "kdy je příští státní svátek", "je zítra svátek", "jaký je zítra svátek", "kdo má zítra svátek", "kdo má svátek 24. 12.", "kdy má svátek eliška",
            "kolik je 18 % z 250", "kolik je 1/4 z 100", "kolik je čtvrtina ze 100", "kolik je 15 krát 15", "kolik je druhá mocnina 12", "kolik je 12 na druhou",
            "kolik je 100 mph v km/h", "kolik je 1 akr v m2", "kolik je 1 hektar v m2", "kolik je 1 kWh v joulech", "kolik je 1 libra v kg", "kolik je 5 stop v cm",
            "kolik je 1 bitcoin", "kurz eura dnes", "kurz eura k dolaru", "kolik je 100 dolarů v eurech", "kolik je 1000 jenů v korunách",
            "hlavní město norska", "hlavní město švédska", "hlavní město ukrajiny", "hlavní město chorvatska", "hlavní město čech", "jaké je hlavní město moravy",
            "kolik obyvatel má slovensko", "kolik obyvatel má německo", "kolik obyvatel má brno", "jak velká je praha", "nejvyšší hora slovenska", "nejvyšší hora evropy",
            "jaká je rychlost světla", "kolik váží litr vody", "kolik je absolutní nula", "při kolika stupních vaří voda", "kolik kalorií má banán", "kolik kalorií má vejce",
            "co znamená ok", "co znamená lol", "co znamená asap", "co znamená aka", "co znamená btw", "co znamená fyi", "co je to ai", "co je umělá inteligence",
            "co je to dph", "co je to inflace", "co je hdp", "co je to bitcoin", "co je to blockchain", "co je to cloud", "co je fotosyntéza", "co je gravitace",
            "kdo je elon musk", "kdo byl karel iv", "kdo byl jan hus", "kdo byl tomáš baťa", "kdo je petr pavel", "kdo je andrej babiš", "kdo je donald trump",
            "kdo vymyslel internet", "kdo objevil elektřinu", "kdo napsal hamleta", "kdo namaloval monu lisu", "kdo složil rusalku",
            "kolik má litr mililitrů", "kolik je metrů v kilometru", "kolik gramů je dekagram", "kolik minut je hodina a půl", "kolik je 90 minut v hodinách",
            "kolik vteřin má den", "kolik hodin má rok", "kolik dní má leden", "kolik dní má srpen", "kolik týdnů má rok",
            "jak se řekne děkuji anglicky", "jak se řekne pes německy", "přelož ahoj do angličtiny", "co je anglicky stůl",
            "kolik je mi let", "jaké je číslo na záchranku", "číslo na policii", "číslo na hasiče", "tísňová linka", "linka důvěry",
            "jak se máš", "co děláš", "jsi tam", "haló", "ahoj avior", "dobrý den avior", "děkuju moc", "super díky", "nashledanou", "měj se",
            "hoď kostkou", "hoď mincí", "vyber náhodné číslo", "řekni vtip", "řekni mi něco zajímavého", "nuda",
            "jaké je počasí", "bude zítra pršet", "jaká bude teplota", "kolik je stupňů venku",
            "kolik baterie mám", "zapni baterku", "vypni baterku", "ztiš telefon", "nastav budík na 7", "minutka 5 minut", "zapni wifi",
        )
        var miss = 0
        qs.forEach { q -> val a = Reflex.answer(q, c); if (a == null) { miss++; println("PROBE ∅  $q") } }
        println("PROBE HIT ${qs.size - miss}/${qs.size}")
        org.junit.Assert.assertTrue("pokrytí ${qs.size - miss}/${qs.size}", qs.size - miss >= 117)
    }
}
