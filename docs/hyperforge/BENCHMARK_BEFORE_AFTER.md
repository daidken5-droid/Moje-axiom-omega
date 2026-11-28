# BENCHMARK_BEFORE_AFTER (MEASURED, JVM vývojového kontejneru, ne telefon)

„Před“ = `checkpoint-pre-hyperforge` (629c54a, 1013); „po“ = 694afa9 (1014). Stejná data, stejný stroj, git worktree.

## Nová sada STEP-0 eval (5 061)
| | před | po |
|---|---|---|
| falešné jistoty dev / validation / holdout | 60 / 25 / 21 | 0 / 0 / 0 |
| z toho ISO dotaz → telefonní předvolba (i jiného státu) | 76 | 0 |
| z toho „aktuální kurz“ bez výhrady | 30 | 0 |
| coverage odpověditelných | 0 % (vertikály neexistovaly) | 100 % |
Pozn.: sada cílí na nové vertikály → srovnání pokrytí je vůči „před“ nespravedlivé; směrodatné je číslo falešných jistot.

## Stávající sady (regrese)
| | před | po |
|---|---|---|
| Δ Lab dev / final / final bez knihovny | 30/30 · 49/50 · 40/50, 0 chybných | 30/30 · 49/50 · 40/50, 0 chybných |
| paměť odpovědí: neověřené odpovědi mozku vrácené jako reflex | 0 z 8 | 0 z 8 |
| EverydayCoverage | 118/130 | 118/130 |
| emulace 300 mozkových otázek s čísly – falešný reflex | 0 | 0 |

## Zátěž (StressTest, 24 000 dotazů, 8 vláken, 3 běhy)
| | před | po |
|---|---|---|
| propustnost | 9 048 · 9 753 · 10 658 /s | 8 586 · 8 659 · 9 283 /s |
| p50 | 190 · 176 · 159 µs | 209 · 214 · 196 µs |
| p99 | 12,7 · 12,4 · 12,5 ms | 13,3 · 12,7 · 12,4 ms |
Zhoršení ~8–12 % propustnosti / +20–40 µs p50 = cena tří vertikál a kontrol identity; po předkompilaci 26 regexů a
předfiltrech. Na telefonu neměřeno.
