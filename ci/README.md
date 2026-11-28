# Podpis sestavení z GitHub Actions

Android nainstaluje aktualizaci jen tehdy, když je podepsaná **stejným klíčem** jako nainstalovaná verze.

1. **Doporučeno – tvůj vlastní klíč (GitHub Secrets).** V repozitáři: *Settings → Secrets and variables → Actions →
   New repository secret* a přidej:
   * `OMEGA_KEYSTORE_B64` – obsah souboru `axiom-omega-release.jks.base64.txt` (z balíčku „podpis“, který jsi dostal),
   * `OMEGA_KEYSTORE_PASSWORD`, `OMEGA_KEY_PASSWORD` – heslo ze souboru `HESLA-A-NAVOD.txt`,
   * `OMEGA_KEY_ALIAS` – `axiom-omega`.
2. **Bez Secrets** si CI při prvním sestavení vygeneruje náhradní klíč a uloží ho do své **soukromé cache**
   (není v repozitáři ani v artefaktech) a dál ho používá. Cache ale GitHub smaže po 7 dnech bez sestavení –
   pak vznikne nový klíč a novou verzi bude potřeba nainstalovat po odinstalaci (zálohuj data: Paměť → Záloha).

Otisk podpisu každého sestavení je v Releases v souboru `SIGNING.txt`.
