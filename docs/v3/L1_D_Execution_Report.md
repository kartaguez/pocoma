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
