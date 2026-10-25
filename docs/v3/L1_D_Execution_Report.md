# L1-D — Droits et finances exactes

Campagne autorisée le 2026-10-25 depuis `9dfe5a8d55d95bba29bb6a07e5c387c4ae8b0164`. GO indépendant A/B/C confirmé par le responsable. Ce rapport sera complété à partir des résultats frais ; aucun PASS présumé.

## Déclaration avant réalisation

COMMAND primaire ; PROJECTION secondaire. Production : domaine pur (`PotFinancialState`, `PotBalanceCalculator`, éligibilité pure invitation), `PotHistoryPort`, `CalculatePotBalances`, lecture groupée `JdbcPotHistoryAdapter`. Aucun changement de POM, SQL, protocole C, matérialisation READ, HTTP, Identity ou Discovery. Gate architecture requis pour le contrat financier ajouté au port existant. Full reactor non requis en D, réservé au jalon E. Base CURRENT V3 V1+V2 ; aucune campagne historique séparée, chaînes transitives conservées. Escalade si une autre frontière de production doit changer ; aucun franchissement inattendu constaté.

## Algorithme et inventaire I-01

Le lecteur de faits financiers sélectionne directement les Headers, Weight et Financials historiques à V avec les primitives d'intervalles B et une lecture groupée des participations. Ni Details, ni noms/userId, ni balance précédente. Contrôle des identités, de l'existence historique des références et de l'activité des Shareholders pour les Expenses actives. Le calculateur pur reçoit cette composition immuable ; chaque Shareholder de la composition, même supprimé, conserve une clé et un zéro canonique si inactif financièrement. Une Expense supprimée contribue zéro ; un Header Pot terminal décide explicitement l'absence de balance.

Pour chaque Expense active : +amount au payeur, puis −amount×effectiveWeight/ΣeffectiveWeight aux participants. Le spécifique remplace le défaut ; toutes les opérations utilisent Fraction/BigInteger L1-A, sans arrondi. Addition exacte et map de sortie ordonnée par UUID, immuable. Aucun cache ni normalisation stockée. Un changement de défaut est résolu à V sans révision Financials ; userId n'est pas une entrée de calcul.

Les règles de droits restent dans les transitions A ; la matrice D les exerce sans seconde politique. Seule l'éligibilité pure invitation est complétée ; token/expiration/consentement et exécution invitation restent reportés. Les consommateurs historiques `PotBalancesCalculator` et `CalculatePotBalancesAtVersionService` utilisent les regroupements V2 et ne consomment pas le contrat V3 : ils restent aux lots READ. Aucun adaptateur V3→V2, aucune conversion long et aucune API différentielle dans le nouveau chemin.

Tests nouveaux : six méthodes de calcul pur (dont seed 731, 3/20/100 Shareholders, 2/100/2000 Expenses, jusqu'à 200000 participations), trois méthodes de droits tabulaires, six méthodes PostgreSQL finances via l'exécuteur C. D03-L est exercé sur une composition de primitives B à une unique V ; il ne prétend pas ajouter une Command combinée absente du contrat. La concurrence de Commands Weight/Financials est exercée séparément via C. Mesures indicatives temps/allocation sans verdict PERF.

## Résultats, commandes et limites

À compléter après gates sur commit identifié. Les diagnostics ciblés ne remplacent aucun gate. Limites héritées : autorité Identity, admission/acquisition initiales et rattachement initial en fixtures C nommées ; aucune certification E2E Identity/Invitation/Consumption/READ/Bruno/k6. Les garanties historiques A/B/C ne sont pas transférées sans réexécution.

## Clôture technique D — preuves exécutées

**PASS technique interne D**, sur le code `69acce7842364d5ee3881d41b8a70257fd09b81d`. G-D : 1255 tests / 0 failure / 0 error / 1 skip, 79 méthodes obligatoires PASS. G-ARCH D : 1490 / 0 / 0 / 1, 87 méthodes obligatoires PASS. Maven BUILD SUCCESS pour les deux ; collecteur global INCONCLUSIVE uniquement pour `TerminalSequencingPostgresTest.zExploratoryContentionSeparateFromCorrectness` (`step3b.measure` absent). Aucun skip obligatoire. Les 15 méthodes nouvelles D sont toutes exécutées. Aucun résultat n'est déduit de la compilation.

Commandes exactes depuis `app/` :

```bash
./mvnw -pl domain-pot -am test
./mvnw -pl infra-persistence-primary-jpa -am test -Dtest=PotFinancePostgresTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -pl runtime-command-consumption-worker,runtime-task-consumption-worker -am test
./mvnw -pl architecture-tests -am test
```

Les diagnostics PG ont utilisé `./app/mvnw -f app/pom.xml` depuis la racine, équivalent au projet ci-dessus. Défauts de banc corrigés avant gates : imports de valeurs V2/V3 ambigus, appel de helper mal qualifié, répertoire de sortie absent, et assertion de corruption qui devait vérifier la cause technique enveloppée par le propriétaire transactionnel C. Aucun défaut de production A/B/C, affaiblissement de test ou réouverture de checkpoint. Les diagnostics en échec sont conservés et ne sont pas requalifiés PASS. Support : `python3 -B -m unittest discover -s scripts/testing -p test_summarize_v3.py` (7 PASS) et `git diff --check`.

[Résultats détaillés D](../testing/evidence/L1-DE/D-gates.json), [archives brutes D](../testing/evidence/L1-DE/D-gates-raw.tar.gz), [diagnostics](../testing/evidence/L1-DE/D-diagnostics.tar.gz), empreintes adjacentes. Les archives contiennent commandes, SHA, environnement, XML, méthodes, inputs before/after, classes/ressources/JARs et snapshots PG. Extraction dans `docs/testing/evidence/L1-DE/` : les répertoires nommés dans l'index sont autonomes. PostgreSQL 17.11, image postgres:17-alpine/digest consigné dans chaque environment.json ; JDK21/Maven3.9.14, macOS aarch64. Le lecteur financier utilise 9 requêtes pour une comme pour 21 Expenses ; aucun Details lu. Les migrations V3 V1+V2 sont explicites ; PRIMARY V1–V31/READ V1–V14 transitives sont visibles dans les logs, sans gate historique séparé.

Aucun franchissement inattendu ; architecture globale requise et exécutée. Full reactor non exécuté pour D, dû à E. A-01 réexécutée ; A-02 D compare automatiquement les entrées avant/après, clôture L1 due à E ; I-01 finances complété, transverse futur ouvert. D satisfait ses critères internes et autorise la poursuite automatique vers E selon le mandat humain. Aucune acceptation indépendante ni certification globale DOM-03/PRJ-02/V3.

Mesure indicative G-ARCH D (un passage local, temps du seul calcul, allocation cumulée sur un second calcul ; aucune mesure mémoire résidente ni SLO) : 3 Shareholders/2 Expenses ≈0,121 ms et 30 664 octets alloués ; 20/100 ≈6,79 ms et 8 326 064 octets ; 100/2000 (200000 participations) ≈175,9 ms et 826 151 632 octets. Les valeurs brutes figurent dans `app/domain-pot/target/v3-pot-evidence/D-costs.txt` de l'archive. Le coût d'allocation Grand Pot est une limite à qualifier au jalon performance ; aucun budget n'est présumé satisfait.

Publication D/E, full reactor final et clôture A-02 L1 vérifiés : voir le [rapport E et constat distant](L1_E_Execution_Report.md#publication-et-checkpoint). Le code D reste celui testé en D puis réexécuté avec E ; aucun changement exécutable après H final. Audit indépendant final L1 requis, sans acceptation définitive.
