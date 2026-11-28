# ASTRAEA CORE 3.0 ve VEGĚ – poznámky k vydání

## Pro uživatele
- **Rešerše odpovídá jen tím, co zdroje skutečně dokládají** (když je nainstalovaný ověřovač citací). Nepodložená
  tvrzení se vynechají, rozpory mezi zdroji se ukážou, a když nic doložit nejde, VEGA to řekne.
- Pod odpovědí je jasný štítek: **doloženo / doloženo částečně / nedoloženo / rozbor / neověřený koncept**
  a kolik nezávislých původů zdroje mají (dva články přebírající jednu zprávu = jeden původ).
- Bez ověřovače se nic nemění k horšímu: odpověď uvidíš, jen výslovně jako neověřený koncept.
- Když kontrola najde vady, VEGA odpověď **jednou** opraví – nikdy víckrát, a horší verzi nepoužije.
- Diktování v režimu Bez sítě se spustí jen s místním rozpoznáváním v telefonu.

## Pro vývoj
- Nové: `vegacore/.../astraea/AstraeaCore.kt`, `VegaAdapters.kt`; testy `AstraeaCoreTest` (20), `AstraeaVegaAdapterTest` (6),
  `EconTest.astraeaAcceptanceCalculations`.
- Změněno: `Ops.research`, `Ops.hypothesis` → `Ops.governed`; `Ops.groundChecks(…, checker)`; `ui/app.js : auditHtml`.
- Zachováno: podpis, package ID, formát chatů (karta má navíc pole `audit`, staré karty fungují), jména metod mostu
  (kromě `voice(data)` – volá ho jen vlastní UI ve stejném APK).
- Návrat: revert commitu; data se nemigrují.
