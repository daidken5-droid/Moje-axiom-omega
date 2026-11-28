# TEST_REPORT – 1016

Příkaz: `./gradlew :core:test :app:testDebugUnitTest :app:lintDebug` (JDK 21, Gradle 8.14.3, Linux kontejner).

| Sada | Výsledek |
|---|---|
| core (280 testů, vč. AgentToolCallIncidentTest 8, RedirectSsrfTest 1, CostGuardTest 4, LongInputTest 4, ResearchDepthTest 6, KnowledgeGraphTest 3, TopicWebTest 6, Step0EvalTest dev+validation) | PASS |
| app Robolectric (DbMigrationTest 5) | PASS |
| Lint | PASS (0 chyb) |
| STEP-0 holdout (1 022) | PASS – 0 falešných jistot (spuštěno dříve, beze změny STEP-0 logiky mimo KG) |
| Incident Kimi na původním kódu | reprodukováno: done / 0 nástrojů / surový zápis |
| Android instrumentation / emulátor | NOT VERIFIED (C-08) |
| Skutečná cloudová API | NOT VERIFIED |
| Reálný Pixel 9a (výkon, RAM, baterie) | NOT VERIFIED |
| Test C (kvalita výzkumu Great Reset / Guidestones / Blue Beam…) | NOT VERIFIED – vyžaduje živý model a web |
