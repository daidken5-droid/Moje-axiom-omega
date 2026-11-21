# VEGA – kontrola „jako by ji používal laik z obchodu“ (21. 11. 2026)

**Aplikace se na Google Play nevydává.** Kontrola probíhala tak, jako by ji používal laik z obchodu, ale bez omezení funkcí kvůli
pravidlům Play. Dočasný build „play“ (bez instalace samoúprav) a cílení na API 36 byly **vráceny zpět**:
samoúprava zase stahuje a nabízí instalaci podepsaného APK, `targetSdk = 35` (testovaná kombinace s AGP 8.7.3).

## Skutečné opravy, které zůstávají (platí pro každého uživatele)
- **Poloha pro „Sítě v okolí“ na Androidu 12+:** žádost jen o přesnou polohu systém ignoroval → teď se žádá o přesnou i přibližnou;
  při povolení jen přibližné srozumitelný návod.
- **Bluetooth na Androidu 8–11:** doplněna `BLUETOOTH`/`BLUETOOTH_ADMIN` (`maxSdkVersion=30`) a žádost o polohu.
- **Vysvětlení před žádostí o polohu** („Proč WiFi potřebuje polohu“) – jednou, aby laik věděl, co povoluje.
- **Diktování v režimu Bez sítě** jen s místním rozpoznáváním v telefonu (režim slibuje, že nic neodejde).

## Poznámka
- Knihovna BouncyCastle (`bcpkix`, závislost PDF) obsahuje třídu s TrustAll správcem certifikátů; VEGA ji nepoužívá.
