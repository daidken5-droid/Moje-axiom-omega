# SECURITY_AUDIT – AVIOR OMEGA (1016)

Rozsah: vlastní kód projektu, autorizovaně, bez útoků na cizí infrastrukturu. Principy OWASP MASVS (STORAGE, CRYPTO, NETWORK, PLATFORM, CODE).
Automatický skener dependencies v tomto prostředí neproběhl (NOT VERIFIED).

| ID | Závažnost | Popis | Dopad | Důkaz | Oprava | Test | Zbytkové riziko |
|---|---|---|---|---|---|---|---|
| S-01 | Vysoká (finanční) | Neautorizovaná placená volání (FreeCloud fallback, Claude/Kimi auto) | útrata kreditu bez souhlasu | STATIC (FreeCloud.generate, availableEngines) | CostGuard + GuardedEngine | CostGuardTest | odhad ceny je horní mez, skutečný ceník se může změnit |
| S-02 | Střední | SSRF přes HTTP přesměrování v `Web.get` | agent mohl číst služby v LAN telefonu | STATIC | ruční redirect + revalidace | RedirectSsrfTest | DNS rebinding mezi kontrolou a spojením (nízké) |
| S-03 | Střední (z dřívějška, potvrzeno) | Root povel / nevratné akce schvalitelné z WebView | XSS → root příkaz | STATIC | nativní AlertDialog | — (zařízení) | závislost na absenci XSS jinde v UI |
| S-04 | Střední (z dřívějška) | Cloudová záloha nešifrovaná | únik dat z datasetu | STATIC | E2EE AES-GCM | BackupCryptoTest | slabé heslo uživatele |
| S-05 | Info | Prompt injection z webu | model by mohl plnit pokyny ze stránky | STATIC | InjectionGuard na podklady i výstupy nástrojů | existující testy | heuristika, ne záruka |
| S-06 | Info | Klíče: Android Keystore (Vault), do JS se nevrací; air-gap centrálně | — | STATIC | — | InvariantsTest.e | — |

Doporučení dalšího přezkumu: dependency scan (OWASP Dependency-Check), dynamická analýza WebView na zařízení, revize JNI (llama.cpp) a validace GGUF.
