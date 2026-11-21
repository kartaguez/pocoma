# L7-C3 — Clôture technique

## État initial et périmètre

Branche `feat/v3-l7`, HEAD `d7b4b6d73963cab7efeb658ef67478d52de03c32`, synchronisée avec `origin/feat/v3-l7`. L’index était vide. Le statut détaillé recensait 41 fichiers suivis modifiés et 154 fichiers non suivis. Ces derniers comprenaient 105 fichiers de C3 et 49 fichiers du prototype Radix C2. Huit journaux C3 ignorés par Git étaient présents et référencés par le manifeste de généralisation. Le périmètre de livraison comprend la généralisation C3, les corrections de clôture, les rapports, les preuves manifestées et le banc du pilote nécessaire à leur traçabilité. Le prototype C2 est resté hors du périmètre.

## Audit et corrections

| Constat | Résolution |
| --- | --- |
| Les noms du suffixe JDBC masquaient la distinction entre preuve d’un événement, marqueur courant et fermeture de la publication. | Nommage explicite dans l’adaptateur et ses sept appelants. Les diagnostics identifient la famille, la clé métier, la version et l’état observé. |
| Le bootstrap du lanceur local chargeait dynamiquement le script d’installation sans son module frère ni la signature mise à jour de son adaptateur SQL. | Résolution du module dans le chargeur existant. L’adaptateur maintient une transaction READ unique qui comprend V5, les droits et le marqueur de bootstrap. La migration et le module entrent dans les contrôles d’entrée et d’empreinte. L’oracle d’un test existant couvre la présence de V5 avant le marqueur. |
| Les vérifications de présence et de conformité du catalogue répétaient les mêmes prédicats. Les tests d’exploitation portaient encore le seul nom de V4. | Les deux sondes composent les mêmes prédicats SQL dans un catalogue commun. Les noms de tests reflètent l’installation courante et leurs scénarios restent identiques. |
| Des instructions de conception et de pilote pouvaient être prises pour la référence livrée. La page de persistance s’arrêtait à V1. | Architecture, contrat SQL et parcours contributeur publiés en anglais. Mise à jour des index, de l’inventaire des migrations et du guide de reprise. Les documents historiques gardent leurs chemins et pointent vers la référence actuelle. |

La revue a suivi les reçus des sept participants jusqu’aux contraintes PostgreSQL. Le suffixe commun garde une responsabilité unique, l’allocation et l’écriture des métadonnées dans la transaction existante. L’entrée du callback reste gardée avant `closePublication()`. PotVersion garde l’ordre manifeste puis pointeur courant.

Les fixtures Monolith de rejeu Invitation et de perte d’ACK PotVersion passent par la composition équipée de V5. Les fixtures de requête qui fabriquent intentionnellement des états partiels peuvent toujours utiliser V4. La migration isolée du pilote, sa fabrique et son banc restent nécessaires aux tests et aux preuves historiques. Les sources de ces preuves sont conservées avec leurs empreintes.

### Suites de l’audit

| Point de l’audit | Constat sur le code local |
| --- | --- |
| 1.1 — Sentinelle de visibilité | Le contrôle de progression limite la dérogation de version zéro à la visibilité. Le test PostgreSQL vérifie le rejet au commit d’une sentinelle seule ou d’un effet dépourvu de marqueur. |
| 1.2 — Avance courante | L’écriture conserve le verrou sur l’effet avancé jusqu’au commit. `requireCurrent` exige un marqueur valide antérieur ou absent après avance, et un marqueur valide de la version observée en cas de rejeu. |
| 1.3 — Pagination | La fonction SQL est `STABLE` et partage un snapshot entre la tête et la page. Le test de concurrence vérifie le rattrapage des positions remplacées et des clés tardives. |
| 2.1 — Suffixe JDBC | Une CTE modifiable alloue et publie la position en une instruction par famille. La double publication conserve l’ordre PotVersion puis CurrentPot. Les tests couvrent le rollback du second suffixe, la perte d’ACK et deux writers concurrents. |
| 2.2 — Plan générique | Le trigger conserve la vue `UNION ALL`. Une preuve avec `force_generic_plan` et `EXPLAIN ANALYZE` vérifie que seule la branche demandée s’exécute pour les deux formes de requête, avec et sans version. |
| 2.3 — Index et maintenance | Les index d’intégrité et de pagination sont conservés. Le compteur réserve de la place aux mises à jour HOT avec un `fillfactor` de 80. Le registre courant règle le facteur d’échelle d’autovacuum à 0,1, car chaque avance modifie une position indexée. |
| 2.4 — Oracle exhaustif | Le scan de cohérence C3 est réservé à la reconstruction et à sa qualification. Les chemins Java et Python le conditionnent à la convergence des checkpoints et à l’état des slots. |
| 3.1 — Invitation JDBC | `JdbcInvitationStore` utilise `ReadJdbc` et une disponibilité activée par configuration. En écriture, le garde exige READ COMMITTED. |
| 3.2 — Fabrique des participants | La fabrique couvre les sept participants et la sélection de production n’instancie que celui demandé. |
| 3.3 — Contrats de projection | Les sept ports emploient `requireCompletionReady`. Binding et Visibility évaluent l’effet et le marqueur dans un même bloc d’observation de la transaction de recovery. |
| 3.4 — Reçus des résultats terminaux | CommandResult et IdentityRequestResult utilisent un `Boolean` et refusent tous deux la vérification avant l’écriture. |

## Documentation de référence en anglais

- [Architecture des disponibilités READ](../../architecture/read-availability.md).
- [Contrat PostgreSQL et pagination](../../reference/read-availability.md).
- [Ajouter une projection READ](../../guides/adding-read-projections.md).
- [Persistance](../../architecture/persistence.md) et [reconstruction READ](../../operations/recovery.md#read-reconstruction-and-availability).
- Navigation depuis le [portail](../../README.md), les index [architecture](../../architecture/README.md), [référence](../../reference/README.md), [guides](../../guides/README.md), [exploitation](../../operations/README.md) et [CONTRIBUTING](../../../CONTRIBUTING.md).

## Vérification

La tranche primaire déclarée est **PROJECTION**. **WEB** couvre la réouverture et l’isolation du rôle HTTP. **COMMAND** couvre le harnais de test L2 dont la fabrique a changé. Le plan C3 exige la gate globale d’architecture pour la propriété des migrations READ et la preuve Monolith. FOUNDATION est transitive. Le harnais COMMAND est le franchissement supplémentaire observé à l’audit, limité aux tests. L’audit a également étendu les contrôles Python au lanceur local, consommateur direct du bootstrap. Les contrats métier WRITE, les frontières Maven et le propriétaire de transaction partagé sont hors du périmètre.

La preuve SQL vise le schéma **CURRENT** et les cas V4 vers V5, sur bases jetables. Certaines fixtures du graphe composé exécutent leurs migrations historiques. La certification globale L7 et la commande sans sélection `./mvnw test` restent distinctes de cette qualification.

### Contrôles hors Maven

```bash
python3 -B scripts/operations/test_v3_operations.py
python3 -B -m unittest scripts.operations.test_v3_operations
python3 -B scripts/performance/test_l7_4a_baseline.py
python3 -B scripts/performance/run-l7-c2-b2-xlarge-ab.py --help
PYTHONPATH=scripts/local python3 -B -m unittest \
  scripts/local/test_s6_hardening.py scripts/local/test_recovery.py \
  scripts/local/test_prerequisites.py scripts/local/test_grafana.py \
  scripts/local/test_local.py scripts/local/test_keycloak.py \
  scripts/local/test_volume_ownership.py
PYTHONPATH=scripts/local python3 -B <preuve>/local-bootstrap-proof.py
(cd docs/testing/evidence/L7-C3/generalization && shasum -a 256 -c manifest.sha256)
shasum -a 256 -c docs/testing/evidence/L7-C3/complement/manifest.sha256
git diff --check
git diff --cached --check
```

- **Python.** Six tests d’exploitation par mode de lancement, quatre tests du banc et 125 tests du lanceur passent. Le premier essai avant correction a reproduit deux erreurs de chargement du bootstrap. Un passage du lanceur sous restriction réseau a ensuite relevé six refus d’ouverture de ports de boucle locale. L’exécution autorisée a réussi les 125 tests.
- **PostgreSQL 17.6.** Une erreur injectée dans le bootstrap du lanceur, après V5 et avant son marqueur, annule la transaction READ. Le démarrage suivant puis le rejeu retrouvent le catalogue, les huit compteurs, le marqueur correct et l’isolation des droits HTTP. Les deux formes de CTE passent avec le rôle matérialiseur, y compris le remplacement d’un marqueur. Les plans génériques versionné et sans version exécutent uniquement l’index de la famille demandée dans les cas mesurés. [Journaux, plans et script](../../testing/evidence/L7-C3/closure/local-verification.tar.gz)
- **Intégrité et navigation.** Les 42 empreintes de généralisation, les 50 empreintes du complément et les trois archives du pilote correspondent. Les contrôles Git portent aussi sur le contenu du commit préparé dans l’index isolé.

### Maven

La compilation bornée des tests a passé sur 40 modules, dans une copie distincte, avec Maven 3.9.14 et Java 21.

```sh
./mvnw --batch-mode --no-transfer-progress -Dmaven.repo.local=<dépôt-isolé> \
  -pl runtime-worker-process,runtime-worker-materialize,runtime-monolith -am test-compile
```

L’exécution composée sur l’état audité utilise la fermeture Maven des tranches et de la gate globale. Le graphe d’`architecture-tests` inclut les 43 modules de l’agrégateur. Les rapports de cette exécution portent un identifiant de provenance (`pocoma-c3-final.hLqqKB`) et sont écrits dans un répertoire dédié.

```bash
./mvnw --batch-mode --no-transfer-progress -Dmaven.repo.local=<dépôt-isolé> \
  -Dpocoma.verification.id=<invocation> -Dpocoma.verification.reports=<rapports> \
  -pl runtime-worker-materialize,runtime-web-write,runtime-web-read,runtime-worker-process,runtime-monolith,architecture-tests -am test
```

L’invocation initiale a validé les 40 premiers modules jusqu’à `runtime-worker-process` (912 tests réussis), puis a été reprise sur les mêmes 811 entrées applicatives (empreinte SHA-256 `a101ad6ff158b0cfc8c4142e55f6ec869d7000274e86c23da65e7952524ece5e` inchangée) avec le même identifiant de provenance :
1. Installation locale sans tests des artefacts de la copie (`-DskipTests install`, code de sortie 0).
2. Exécution des trois classes restantes de `runtime-worker-process` (`WorkerPostgresTest`, `WorkerCompositionPostgresTest`, `WorkerMetricsTest` : 31 tests réussis, code de sortie 0).
3. Reprise non filtrée à partir de `runtime-worker-materialize` (`-rf :pocoma-runtime-worker-materialize`), rejeu de `PotVersionAndBalanceJointIncrementalPostgresTest` après collision d’acquisition de partition entre les deux moteurs de test sur le même bail, puis reprise non filtrée de `runtime-monolith` et `architecture-tests` (`-rf :pocoma-runtime-monolith`, code de sortie 0).

La couverture de la gate composée sur l’état audité est complète : **1 170 tests exécutés sur les 43 modules du réacteur, avec 1 170 réussites, 0 échec, 0 erreur et 0 skip** (les 1 169 cas de la généralisation initiale plus le nouveau test `ReadAvailabilityPostgresTest#genericEffectLookupExecutesOnlyTheRequestedFamily`). Tous les rapports XML portent la provenance `pocoma-c3-final.hLqqKB`. [Reçu de la gate de clôture](../../testing/evidence/L7-C3/closure/maven-gate.json) · [Archive des rapports et journaux](../../testing/evidence/L7-C3/closure/maven-gate.tar.gz) · [Empreintes des 811 entrées Maven](../../testing/evidence/L7-C3/closure/maven-inputs.json)

## Réserves

| Indicateur historique, C2 → C3 | Observation |
| --- | --- |
| C-03 Medium, p95 | 173 → 175 ms |
| C-03 Large, p95 | 225 → 227 ms |
| WAL PotBalance XLARGE, situation A à contenu réutilisé | 690 → 1 245 octets/version |
| Débit mixte, quatre workers | 98,0 → 82,6 finalisations/s |
| Deadlocks | Aucun observé dans le périmètre testé |

La perte de débit sous contention demeure une réserve. Le regroupement JDBC réduit le nombre d’instructions par famille de deux à une. Le gain de débit et le coût WAL du suffixe modifié restent à mesurer en C4 ou pendant la qualification globale L7. Les mesures historiques sont attribuées aux profils et versions PostgreSQL du [rapport de généralisation](generalization-report.md#comparaison-de-performance-c2--c3). La mise en service sur un stock à conserver reste soumise à une décision d’exploitation et à C3-R2. Un reset intégral requiert une resynchronisation des futurs lecteurs.

## Verdict

**`L7_C3_TECHNICAL_CLOSURE_PASS`** — Le code audité et corrigé, les huit familles de disponibilité READ, la migration V5 canonique, les scripts d’exploitation et du lanceur local, la documentation de référence et la gate Maven composée (1 170 / 1 170 tests verts) sont qualifiés et prêts pour livraison.
