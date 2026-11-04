# L5/I1 — Temps observés

Durées murales mesurées par les propriétaires de campagne ; les reprises suivent des corrections identifiées et conservent les reçus initiaux.

| Campagne locale | Secondes | État terminal exact |
|---|---:|---|
| pocoma-l5-i1-b1-correction-01 | 9.167600 | B1_LOCAL_PASS |
| pocoma-l5-i1-cold-01 | 19.579062 | T1_NON_PASS_B1_NOT_RUN |
| pocoma-l5-i1-completion-01 | 14.916233 | T1_NON_PASS_B1_NOT_RUN |
| pocoma-l5-i1-completion-02 | 46.290861 | T1_NON_PASS_B1_NOT_RUN |
| pocoma-l5-i1-completion-03 | 29.653664 | T1_NON_PASS_B1_NOT_RUN |
| pocoma-l5-i1-completion-04 | 16.521369 | T1_NON_PASS_B1_NOT_RUN |
| pocoma-l5-i1-completion-05 | 23.655558 | T1_NON_PASS_B1_NOT_RUN |
| pocoma-l5-i1-completion-06 | 166.249539 | B1_NON_PASS |
| pocoma-l5-i1-completion-07 | 39.433799 | B1_NON_PASS |
| pocoma-l5-i1-composition-split-01 | 52.766855 | PASS |
| pocoma-l5-i1-correction-01 | 121.308420 | T1_NON_PASS_B1_NOT_RUN |
| pocoma-l5-i1-final-delta | 29.232388 | LOCAL_CONTRIBUTION_PASS |
| pocoma-l5-i1-formal-01 | 14.794836 | T1_NON_PASS_B1_NOT_RUN |
| pocoma-l5-i1-http-boundary-01 | 151.130027 | LOCAL_CONTRIBUTION_PASS |
| pocoma-l5-i1-isolated-01 | 25.872182 | T1_NON_PASS_B1_NOT_RUN |
| pocoma-l5-i1-isolated-02 | 23.678881 | T1_NON_PASS_B1_NOT_RUN |
| pocoma-l5-i1-optional-port-01 | 537.468959 | LOCAL_CONTRIBUTION_PASS |
| pocoma-l5-i1-pool-initialization-01 | 37.865341 | PASS |
| pocoma-l5-i1-worker-isolation-01 | 25.538351 | FAIL |
| pocoma-l5-i1-worker-isolation-02 | 40.913917 | PASS |

**Cumul contrôlé T1+B1 et corrections ciblées : 1426.037842 s**, estimation 900 s non interruptive. Ce cumul ne décrit pas une exécution initiale entièrement PASS. Développement, préparation antérieure et archivage durable de publication restent hors de ces horloges ; leur durée fine non instrumentée n’est pas inventée.

| Gate Architecture | Collecteur monotone (s) | État | Tests / failure / error / skip |
|---|---:|---|---|
| pocoma-l5-i1-architecture-01 | 2.458498874970246 | INCONCLUSIVE | 0 / 0 / 0 / 0 |
| pocoma-l5-i1-architecture-02 | 342.17874712502817 | FAIL | 1046 / 0 / 0 / 0 |
| pocoma-l5-i1-architecture-03 | 1206.622395665967 | FAIL | 1612 / 1 / 14 / 0 |
| pocoma-l5-i1-architecture-04 | 1255.0815342909773 | FAIL | 1657 / 2 / 0 / 0 |
| pocoma-l5-i1-architecture-05 | 1076.9993975000107 | FAIL | 1528 / 0 / 0 / 0 |
| pocoma-l5-i1-architecture-06 | 1937.6312769159558 | FAIL | 1630 / 1 / 0 / 0 |
| pocoma-l5-i1-architecture-07 | 3792.0693083329825 | FAIL | 2064 / 0 / 23 / 0 |
| pocoma-l5-i1-architecture-08 | 4281.1263409999665 | PASS | 2231 / 0 / 0 / 0 |

Feedback Architecture filtré, distinct des gates canoniques : pocoma-l5-i1-architecture-feedback-01 / PASS / {'tests': 167, 'failures': 0, 'errors': 0, 'skipped': 0}.

Les gates sont séparées de T1+B1. Les horloges monotone et civile de la dernière gate divergent ; voir [analyse des horloges](Clock_Measurement_Analysis.json), sans réécriture des reçus. Aucun dépassement budgétaire ne constitue un échec. Une interruption technique ne vaut jamais PASS. Découverte/Maven/collecte sont détaillés dans les JSON ; les temps JUnit comprennent parfois fixtures/PG et ne forment pas une ventilation exclusive compilation/PG/JUnit. Aucun timing non instrumenté inventé.
