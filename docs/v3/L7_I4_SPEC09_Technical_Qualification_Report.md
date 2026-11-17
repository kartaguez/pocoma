# L7.4 — Qualification technique SPEC-09 et préparation de certification

Mandat `GO_L7_I4_TECHNICAL_EXECUTION_WITH_I3_CERTIFICATION_DEFERRED`, reçu le 12 novembre 2026 ; bilan finalisé le 13 novembre 2026.
**Verdict technique : `L7_I4_PARTIAL_EXECUTION_ENVIRONMENT_LIMITED`.** Le banc E1 et
les collecteurs sont livrés ; aucune campagne contractuelle A/B ne peut être qualifiée
sur l'environnement disponible. L'utilisateur a confirmé l'absence de cible distante.
Aucun seuil, profil ni contrat n'est assoupli. Ce rapport ne certifie ni L7.3 ni L7.4.

## G1 — Qualification technique et campagnes

La [matrice contractuelle](L7_I4_SPEC09_Contract_Matrix.csv) donne 31 lignes : sources,
exigences, statut normatif/proposé/paramètre du banc, scénarios, oracles, seuils et limites.
Les 13 familles F01-A…J, A11-L/M, ENDURANCE et SATURATION restent **NOT_RUN** dans leurs
conditions contractuelles. La comparaison de refactoring historique reste NOT_RUN.
Configuration A exige deux machines/VM distinctes de4 vCPU/8 Go ; Docker Desktop offre
un unique hôte de 10 CPU et8 319 504 384 octets au total, partagé avec la pile antérieure.
B ajoute un Keycloak isolé à ce socle absent. Des limites CPU/RAM sur des conteneurs
colocalisés ne remplacent pas ces deux machines.

Les campagnes E1 locales constituent un contrôle de banc : PostgreSQL neuf, cinq
processus réels, JWT valides et AuthZ active, deux petits Pots5/20 puis12 offres à1/s.
Les générations, états Git, hashes sources/JAR/configuration, images et versions
figurent dans chaque reçu. [Architecture et instrumentation](L7_I4_Bench_Architecture_and_Instrumentation.md).
La campagne complémentaire E3 est séparée des populations de latence et des SLO.

| Exécution | SHA testé | Statut brut | E1 observées | Résultat / limite |
|---|---|---|---:|---|
| [e1-01](../testing/evidence/L7-I4-technical/e1-01/receipt.json) | `ef90dd6f` | INCONCLUSIVE | 0 | JWT de test sans auth_time : HTTP401, aucune charge métier. |
| [e1-02](../testing/evidence/L7-I4-technical/e1-02/receipt.json) | `ef90dd6f` | PASS | 12 | E1 passe ; premiers scripts non committés, hashes dirty conservés. |
| [e1-03](../testing/evidence/L7-I4-technical/e1-03/receipt.json) | `3f2c8f71` | PASS | 12 | E1 passe avec seed fixe au SHA committé3f2c8f71. |
| [e1-e3-04](../testing/evidence/L7-I4-technical/e1-e3-04/receipt.json) | `3f2c8f71` | INCONCLUSIVE | 0 | Bootstrap interrompu pendant bascule du serveur temporaire PostgreSQL. |
| [e1-e3-05](../testing/evidence/L7-I4-technical/e1-e3-05/receipt.json) | `f0fe128d` | FAIL | 12 | E1 passe ; suivi E3 censuré à60 s avant lease240 s ; FAIL brut conservé. |
| [e1-e3-06](../testing/evidence/L7-I4-technical/e1-e3-06/receipt.json) | `230feb05` | PASS | 12 | E1 et E3 passent ; suivi dérivé de la lease SQL sans modification productive. |


Aucun échec n'est caché par une moyenne ni par suppression de reçu. Chaque tentative
utilise un nouveau projet, réseau et état PostgreSQL ; chacun est supprimé ensuite.
Le seed d'E1-02 dépendait du projet, ceux d'E1-03 et suivants sont identiques avant
matérialisation. Le bootstrap est CURRENT V3 ; pas de migration V2 historique, pas de
baseline TRUSTED revendiquée. Aucun conteneur préexistant n'est arrêté/réinitialisé.

## Métrologie et limites d'interprétation

| Population E1 | p50 Pot borne haute | p95=p99 Pot borne haute | p95 Balance borne haute retardée |
|---|---:|---:|---:|
| e1-02 (12) | 113.317 ms | 202.174 ms | 207.406 ms |
| e1-03 (12) | 149.189 ms | 337.620 ms | 343.424 ms |
| e1-e3-05 (12) | 159.759 ms | 302.359 ms | 305.738 ms |
| e1-e3-06 (12) | 120.656 ms | 324.987 ms | 329.213 ms |

Quatre populations E1 de 12 observations (48 au total), publiées séparément ; offre planifiée1/s, sans offre abandonnée dans ces populations. Les48 ne sont pas regroupées en un percentile de certification. [Données par demande](../testing/evidence/L7-I4-technical/e1-measurements.csv.gz), [toolchain](../testing/evidence/L7-I4-technical/toolchain.json).

Run final : PostgreSQL17.11, JVM conteneurs21.0.13 ; 66 snapshots ; coût de collecte murale min/p50/max 86.9/112.7/654.8 ms. La fenêtre E3 conserve les quatre Runtimes non ciblés et PostgreSQL ; l’endpoint Command est volontairement absent pendant son crash.

| Conteneur | CPU max échantillonné (docker %) | Mémoire max échantillonnée (% de sa limite) |
|---|---:|---:|
| postgres | 101.35% | 41.83% |
| http-write | 1.11% | 58.80% |
| http-read | 22.46% | 66.68% |
| command-worker | 51.87% | 77.30% |
| results-worker | 29.83% | 68.75% |
| business-read-worker | 43.63% | 71.89% |


Le premier Pot complet est vérifié à W=V puisque chaque Pot n'a qu'un writer dans E1.
Le contrat W>V n'est pas exercé. La balance exacteV est contrôlée après cette première
lecture : son horodatage est une borne supérieure retardée, insuffisante pour une
qualification indépendante du SLO Balance. Aucun p95 d'étapes n'est additionné.
La Registration initiale est une unique EUI sans warmup ; son délai n'est pas un
échantillon F01-E. Les latences E3 comportent redémarrage et attente de lease : elles
ne sont pas comparées aux budgets SPEC-09.

Les séries brutes Micrometer contiennent admission, Discovery/Engine, claims,
attempts/phases, physical_pending, pools, CPU/JVM/GC et READ/rebuild. SQL conserve
connexions/attentes/PIDs bloquants, transactions/deadlocks, WAL, tailles et réglages
durables. `docker stats` conserve CPU/mémoire/IO/réseau/PIDs des six conteneurs.
Les coûts `collectorMs` sont muraux ; ils ne prouvent pas une absence d'effet causal
sur l'application. Aucune calibration contrôlée avec/sans collecte n'est acquise.
Les échantillons de ressources ne mesurent ni pic continu ni intégrale CPU. Aucune
stabilité soutenue, endurance, capacité50/s ou résorption10000 ne peut être inférée.

## Sûreté, incidents et réserves

Oracles E1 : version suivante exacte, IDs de composants/Expense, valeurs de balances
calculées indépendamment par fractions, résultats/publications WRITE et effets READ
rapprochés par IDs et versions, sources terminales, slots drainés, densité des positions,
compteurs conformes, checkpoints sans régression, maintenance READY et zéro nouveau
deadlock observé. Le collecteur refuse les cibles dépourvues du label de propriété et
exécute les observations SQL en READ ONLY avec timeout3 s.

E3 final : les deux demandes sont PENDING sans effet/publication avant kill, même processus réellement tué (exit137/PID0), nouveau StartedAt après redémarrage. La lease restante observée est238,173 s ; Command disponible après238,522 s depuis envoi. Registration produit exactement un User, un résultat, deux événements Identity et une complétion ; le même User est dans READ et `/me/pots` répond200 avec le nouveau binding. Command produit une version/Expense et une variation exacte des soldes, avec une publication Pot et une complétion. Les deux demandes sont rapprochées en SQL et les slots finaux sont vides. Aucune purge, réécriture de claim ou expiration artificielle ; pas de crash READ/pendant calcul revendiqué.

Le [registre](L7_I4_Anomalies_and_Reservations_Register.md) conserve les défauts
d'outillage JWT/readiness, la fenêtre E3 insuffisante et la correction de classification
des interruptions. Aucun défaut productif n'est démontré par ces incidents. Les
limites de collecte/protocole restent bloquantes pour E2. Aucune modification Java,
POM, migration, grant productif ou moteur n'a été nécessaire.

## G2 — Contribution à L7.3

Les 15 lignes exactes du dernier rapport L7.3 sont rapprochées dans le
[rapport de contribution](L7_I4_Contribution_to_L7_I3_Report.md). La matrice de référence conserve ses 522 IDs et ses statuts historiques.
Aucune colonne de couverture L7.3 ni verdict n'est modifiée automatiquement.
Le corpus de candidats writers/SQL/transactions comprend des références et empreintes
par fichier ; il n'est pas un audit exhaustif des autorités, du SQL indirect ni du
poisoning transactionnel. Une absence de match n'est jamais une preuve de non-writer.

**Une candidate à clôture technique : L2M-E02-J-REPORT. Quatorze autres restent incomplètes ; les 15 lignes officielles demeurent CURRENT_PARTIAL avant revue. Zéro promotion automatique.** Le précédent bilan de 38 clôtures parmi les 53 initiales reste historique ; une acceptation ultérieure de cette seule contribution pourrait le porter à 39/14, sans certifier globalement L7.3.

## G3 — Décisions proposées

1. Recevoir la livraison de banc et ses preuves locales avec les limites déclarées ;
   maintenir L7.4 TECHNICAL_EXECUTION_AUTHORIZED sans certification.
2. Pour reprendre E2, fournir les deux machines A puis B isolée, snapshots vérifiés,
   calibrer les collecteurs et compléter leurs manques : Balance indépendante,
   DeletePot interne, âges source/slot, grands profils, coûts JDBC/SQL/JSON et stabilité.
   Figer avant mesure les modalités proposées du protocole §24.3/24.6 et le mix réel.
3. Exécuter chaque profil applicable avec fenêtres/populations/répétitions complètes,
   charge ouverte réconciliée, sûreté indépendante et chaque FAIL conservé ; produire
   les oracles manquants plutôt que transformer E1 en preuve contractuelle.
4. Réexaminer les 15 réserves L7.3 et les autres contributions critiques partielles ;
   certification L7.3 par décision distincte, jamais par performance seule.
5. L7.5 reste BLOCKED. Son ouverture future nécessite les certifications amont et un
   mandat humain propre. L7 global reste OPEN ; gates globaux de clôture restent dus.

## Vérification et Git

- Slices déclarées : **NONE**, primaire/secondaires NONE ; outillage externe à app seulement. Frontières observées WEB/COMMAND/PROJECTION.
- Commande de packaging réellement exécutée depuis app : `./mvnw --batch-mode --no-transfer-progress -pl runtime-web-write,runtime-web-read,runtime-worker-process,runtime-worker-materialize -am package -DskipTests` : BUILD SUCCESS,2,504 s ; aucun PASS de tests Maven.
- Python : `python3 -B -m unittest discover -s scripts/performance -p 'test_spec09_bench.py' -v` : **11 PASS** à la version finale ;17 PASS `test_l7_spec09_*.py` ;8 PASS `test_five_runtime_smoke.py`. Total36 contrôles finaux, les replays intermédiaires ne s’ajoutent pas à ce total.
- `node scripts/performance/test-spec09-test-issuer.mjs` : signatures/claims de deux sujets, permission0600 et absence de route token vérifiés PASS ; un premier essai sandbox EPERM de loopback est conservé séparément.
- `python3 -B scripts/performance/plan-l7-spec09.py`, contrôles AST Python, `node --input-type=module --check` sur k6 et son client : statiques seulement.
- Six commandes du runner E1/E3 avec workspace/output neufs, images épinglées et option E3 pour04/05/06 : [commandes exactes](../testing/evidence/L7-I4-technical/commands.txt). Inventaire `python3 -B scripts/testing/inventory-l74-writer-candidates.py --output docs/testing/evidence/L7-I4-technical/writer-inventory-final` :2704 références candidates dans 239 fichiers.
- Intégrité : `python3 -B scripts/testing/check-l74-evidence.py` :405 artefacts et135 entrées du run06 comparées PASS ; contrôle négatif sur une copie altérée PASS (octet corrompu rejeté). Liens, syntaxe,31 lignes contractuelles et absence de JWT exporté vérifiés.
- Franchissement de slice imprévu : **NONE** ; impact productif observé **NONE** (`git diff ef90dd6f -- app` vide).
- Gate Architecture : **NOT REQUIRED**, aucun déclencheur §17. Full reactor : **NOT RUN / NOT REQUIRED**, aucune clôture ou intégration globale §19. Aucune tranche canonique n’est revendiquée PASS.
- Base : **CURRENT V3**, pas de migrations historiques V2 ni de baseline TRUSTED inventée. Aucun Bruno supplémentaire. Gates de clôture globale conservés pour certification ultérieure.


Départ `ef90dd6f5b5a36c0ec6ae9477fa5b810cb515941`, branche `feat/v3-architecture`.
Commits du banc : `3f2c8f71` (banc/contrat/JWT), `f0fe128d` (scénario de crash et readiness), `230feb05` (suivi du bail réel et classification des interruptions). Les commits du banc identifient les changements testés ; le commit documentaire final
n'est pas présenté comme une nouvelle exécution. Les preuves sont attribuées à leurs
SHA/dirty et hashes propres ; l'absence de changement productif est vérifiée. La
synchronisation distante après push sans force et le worktree final sont consignés
au rapport de livraison de la session. Aucun rattachement d'une preuve à un nouveau
SHA sans comparaison explicite des entrées n'est effectué.
