# CRUCIBLE – výkon (co je změřeno a kde)

| Metrika | Hodnota | Prostředí | Pozn. |
|---|---|---|---|
| Studený start (am start -W TotalTime) | 6776 / 2672 / 2810 ms (#5), 1637 / 1785 ms (#7) | emulátor CI | 1. start zahrnuje první spuštění WebView |
| Teplý start | 716 ms | emulátor CI | 1 měření |
| Odpověď bez mozku přes UI a službu | 575 ms | emulátor CI (#4) | jeden dotaz |
| Paměť odpovědí – vyhledání p95 | 3–7 ms (dříve 139 ms) | JVM (AnswerMemoryPerfTest) | ne zařízení |
| PSS paměť aplikace | **neměřeno** | – | emulátor zmizel dřív |
| „Skipped 69 frames“ při startu | pozorováno | emulátor | na telefonu nepotvrzeno |

Nic z tabulky není měření na telefonu. Čísla z emulátoru (softwarové vykreslování, sdílený CI stroj) nejsou
odhadem výkonu na telefonu.
