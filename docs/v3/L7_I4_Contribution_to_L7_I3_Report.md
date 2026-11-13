# L7.4 → L7.3 — Contribution aux 15 obligations résiduelles

**Une obligation proposée à la clôture technique,14 autres encore incomplètes ; les 15 restent officiellement CURRENT_PARTIAL avant revue.** Aucun changement de la matrice522 lignes ni du verdict `L7_I3_NOT_CERTIFIED`. Ce rapport n’attribue pas un PASS de performance à une garantie de sûreté.

Source des15 IDs et critères : [dernier rapport L7.3](L7_I3_Final_Certification_Report.md). [Données détaillées](../testing/evidence/L7-I4-technical/l73-contribution.json).

| Obligation | Preuve L7.4 | Couverture obtenue | Complément nécessaire |
|---|---|---|---|
| `L2M-C04-K` | E1 + E3, comptages Command/READ exacts après reprise | PARTIAL | Le corpus composite répété avec takeover/rollback/crash pendant calcul manque. |
| `L2M-E01-E` | E1 : compteurs/positions denses sur commandes CHANGED | INSUFFICIENT | Aucun scénario UNCHANGED/REJECTED ni Identity rejetée. |
| `L2M-C01-A-REPORT` | Snapshots K et slots des partitions ; deux Registrations dans le run final | PARTIAL | Pas de peuplement Identity multipartition entremêlé ni ensemble exact des slots en fenêtre. |
| `L2M-C01-D-REPORT` | Collecteur pg_blocking_pids + K, E1/E3 sans régression | INSUFFICIENT | Pas de vraie publication retenue couplée à Discovery READ avant/après commit/rollback. |
| `L2M-C04-L-REPORT` | writer-inventory-final/writer-candidates.csv.gz, références SQL dynamiques et outils | PARTIAL | Revue sémantique exhaustive et refus hors reset READ coordonné restent dus. |
| `L2M-C06-M-REPORT` | writer-inventory-final/writer-candidates.csv.gz, transactions/compositions/tests inclus | PARTIAL | Attribuer chaque autorité physique à garde/poisoning/ownership et preuve ; corpus indirect non fermé. |
| `L2M-E01-H-REPORT` | Charge E1 CreateExpense + Registration initiale ; E3 après admission | INSUFFICIENT | Pas de charge mixte concurrente producteurs avec transactions lentes/aborts/reprises de connexions. |
| `L2M-E02-J-REPORT` | e1-e3-06/receipt.json : PENDING extérieur, SIGKILL exit137/PID0, nouvelle génération ; Command et Registration uniques | CLOSURE_CANDIDATE | Revue humaine de la contribution ; aucun crash pendant calcul revendiqué, aucune promotion automatique. |
| `L2M-E01-G-INV-COMP` | Collecteur arêtes PostgreSQL disponible ; graphe échantillonné conservé | INSUFFICIENT | Forcer l’attente INVITATION puis COMMAND_COMPLETION, commit/rollback et exclusion arête inverse. |
| `L2M-E01-G-ID-IDCOMP` | Registration réelle et compteurs finaux denses | INSUFFICIENT | Pas de contention contrôlée simultanée sur les deux compteurs ni preuve de tous les chemins inverses. |
| `L2M-CON-05-CRASH` | SIGKILL réel après admission sans claim acquis ; échéance lease et reprise observées | PARTIAL | Crash avec claim en cours, sans cession, attemptCount inchangé puis reprise après expiration à démontrer. |
| `L2M-CON-05-NOPURGE` | Aucune purge introduite ; inventaire candidats et label de propriété du collecteur | PARTIAL | Refus effectif du DELETE isolé hors succès et inventaire administratif exhaustif non exercés. |
| `L2M-C03-J-CRASH` | Processus effectivement tué, SQL initial/final et génération distinguées | PARTIAL | Le processus était suspendu avant calcul ; coupure pendant calcul/finalisation Command et READ reste due. |
| `L2M-R8-03` | writer-inventory-final/writer-candidates.csv.gz : SQL/transactions/DDL/compositions | PARTIAL | L’absence de match ne démontre pas l’absence de writer ; revue des canaux et refus physiques nécessaires. |
| `L2M-R8-05` | writer-inventory-final/writer-candidates.csv.gz + conservation des anciens outils désactivés | PARTIAL | Auditer SQL indirect et autorités administratives, définir pouvoirs admis ; pas de prétention d’exhaustivité. |

## Attribution E02-J

Le [reçu E1/E3-06](../testing/evidence/L7-I4-technical/e1-e3-06/receipt.json) au SHA `230feb05` conserve les deux HTTP202, les IDs, deux observations PENDING sans résultat/événement/complétion avant kill, l’état réel du conteneur tué (exit137/PID0), puis la nouvelle génération de processus. Le conteneur avait été suspendu avant les admissions : aucune finalisation n’a pu précéder la barrière SQL. Les deux demandes reprennent après expiration des leases persistées, sans modification administrative de leur échéance.

Command : version29 du Pot initial22 puis six commandes E1, ajout d’une Expense et variation exacte des soldes, un résultat/événement Pot/complétion et concordance READ. Registration : un nouveau User, un résultat, deux événements Identity et une complétion ; même User matérialisé dans READ et navigation vide autorisée avec le nouveau binding. Les slots sont drainés et les compteurs/positions sont denses après la reprise. La demande Command est observée complète après238,522 s depuis l’envoi, pour238,173 s de bail restant mesuré avant kill. Ce délai n’est pas comparé aux SLO de performance.

Le run E1/E3-05 avait arrêté le suivi à60 s, avant cette échéance. Son FAIL brut demeure ; il ne démontre pas une perte de travail ni un effet double. Le nouveau protocole se fonde sur l’échéance SQL réelle et une enveloppe fixe de60 s après expiration, sans raccourcir la lease et sans retry de la demande.

Cette preuve satisfait le stimulus précis de la ligne E02-J après admission, sur Command et Identity réels, et peut être revue sans rejouer ces demandes. Elle ne satisfait pas C04-K, CON-05-CRASH ou C03-J-CRASH pendant calcul ; READ n’a pas été tué. Une campagne locale ne certifie pas les autres variantes ou configurations.

## Inventaire des writers et limites

Le corpus de candidats (sources productives, tests, Monolith, opérations et scripts SQL) conserve fichier/ligne/empreinte/catégorie. Il est publié compressé avec hash des octets originaux. Les opérations construites indirectement, appels transitifs et pouvoirs administratifs nécessitent une revue sémantique ; aucune prétention d’inventaire exhaustif ou de preuve universelle de non-purge. Les références sont un point de départ réutilisable pour quatre lignes d’inventaire, pas une clôture de celles-ci.

Pour toute réutilisation : comparer SHA et hashes des entrées, contrats, SQL, versionPG/JVM, paramètres de lease/partitions/pools et topologie ; conserver les limites physiques locales. Aucun réacteur/Bruno historique n’est recompté comme exécuté ici. Les 338 contributions partielles hors du sous-ensemble initial ne sont pas promues.
