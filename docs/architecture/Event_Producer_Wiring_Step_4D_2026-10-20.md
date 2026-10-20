# Lot 4D — Raccordement des producteurs réels en PREPARED

Date : 2026-10-20. Branche : `feat/consumption-transaction-protocol`.
HEAD de départ : `4defd5a63af267656f2feee92aed8daafef2175f`.
**4D CLOSED / PASS — 4E NEXT, NOT STARTED. Runtime PREPARED ; aucune activation ni backfill réalisés.**

L'[audit préalable](Event_Producer_Wiring_Step_4D_Readiness_2026-10-20.md) conserve l'inventaire exact,
les écarts CURRENT → TARGET, les owners et la matrice A/B/C déclarés avant modification productive.
Depuis `de5e4c373d21c1409f6e75a4faac327926e01f92`, seul le commit documentaire `4defd5a6`
était intervenu avant ce lot. Les décisions du [canon](../steps/current/DISCOVERY/Step_Canon.md),
de la [spécification](Event_Transactional_Sequencing_Step_4_Implementation_Plan_2026-10-19.md)
et de la [politique de vérification](../testing/Reactor_Verification_Policy.md) restent normatives.

## Changements livrés

L'amendement ciblé approuvé le 2026-10-20 est consigné dans le [rapport 4A](Event_Publication_Contracts_Step_4A_2026-10-19.md).
`BusinessEventAppendPort` dans `engine-write-pot` retourne uniquement l'UUID réellement persisté.
La nouvelle façade applicative `AppendBusinessEventService`, dans la composition Pot de l'application,
ouvre ou rejoint le propriétaire PRIMARY, construit l'EventRef BUSINESS_EVENT à partir de cet UUID
et le déclare après acceptation. Le writer auxiliaire devient MANDATORY ; aucune dépendance Maven
ni référence EventRef n'entre dans le métier ou `engine-write-pot`.

Les autres retours durables exposent les acceptations typées livrées en 4A. Command déclare les
BusinessEvents puis son terminal APPLIED/REJECTED ; TH ne déclare FAILED que lorsque le callback
terminal est effectivement exécuté ; TR déclare son REJECTED après rollback TE. Registration déclare
UserCreated dans l'initializer gagnant puis ATTACHED après retour de l'autorité. Acquire/detach
retournent une référence uniquement pour leur mutation gagnante. L'append fact auxiliaire restitue
l'identité du fact reçu. Le repository commun Binding reste void et ne collecte aucune publication.

La garde de corps `PrimaryEventTransactionRunner.guardedWrite` précède toute validation/écriture
des writers couverts, lie leur datasource ou EMF réel au propriétaire OPEN et empoisonne
irréversiblement son statut sur erreur RuntimeException/Error. Elle s'applique également aux objets
créés par `new`, au mapping du fact auxiliaire, à toute l'opération acquire/initializer/detach
et à l'outcome Registration. Une exception interceptée localement ne rend pas le commit possible.
Les deux writers JPA utilisent `persist` insert-only ; le flush Command avant B reste à sa place.
Le flush terminal matérialise aussi l'append auxiliaire différé avant fermeture du registre.

`PrimaryEventProtocol` vérifie l'identité du manager JPA PRIMARY, de son EMF et de la datasource JDBC,
ainsi que version/mode V30 au démarrage. Une composition indépendante est refusée. Les imports
explicites Web, Command et Registration partagent la même composition dans le monolithe ; les autres
runtimes ne reçoivent pas implicitement un nouveau TransactionRunner. Les wrappers propriétaires
Consumption et leurs résolutions COMMITTED/ROLLED_BACK/UNKNOWN restent en place.

Aucune migration, aucun POM, aucune frontière Maven, aucun use case métier pur, aucune règle de
retry/CAS/fence, aucun comportement Discovery ou READ n'est modifié. Ordre P → B → S, quatre familles
et ordre canonique E conservés. Aucun framework universel ni capture cachée de publications.

## Matrice des intégrations et conditions A/B/C

Les assertions V-D utilisent les vrais ports et INSERT, les services applicatifs réels et les roots
runtime. Les spies d'observation appellent les méthodes réelles ; les injections de faute sont
identifiées ci-dessous. Une fixture synthétique 4B/4C n'est pas une preuve d'intégration V-D.

| Chemin / propriétaire | Acceptation et déclaration applicative | Preuves A/B/C |
|---|---|---|
| Command APPLIED, TE | BusinessEventsAccepted ordonnés puis EventRef terminal ; ExecuteRecordedCommandService et CommandConsumptionExecution | `RealProducerEventProtocolPostgresTest.realCommandAppliedBusinessAndTerminalAfterClaim`, PREPARED/ACTIVE ; égalités exactes, PID/XID unique, Claim/S avant E ; Pot/Expense et CAS dans `PotCasConsumptionPostgresTest` |
| Command REJECTED et TH FAILED | Terminal déclaré uniquement après publish accepté, dans leur transaction respective | `realCommandRejectedAndThFailedDeclareOnlyTheirTerminals`, deux modes ; tests Runtime et MultiWorker pour résolution/fencing |
| TA et TH retry | Aucune publication | `realTaAndThRetryHaveNoPublicationAndNeverWaitForLockedE` : E bloquée par une autre connexion, retour borné sans Event/outcome ; classifications existantes conservées |
| TR après rollback TE | Recovery Binding réelle, terminal REJECTED ; aucun Business restant | `BindingSharedFencePostgresTest`, recovery dans les deux modes, référence terminale exacte déclarée une fois ; conflits encore présents, Claims et fences refusés conservés |
| Registration gagnante/existing/rejetée | UserCreated déclaré dans initializer ; ATTACHED après acquire gagné ; aucune référence sur no-op/rejet | `realRegistrationWinningExistingAndRejected` ; `RegistrationRuntimePostgresTest.actualDistributedRegistrationRootUsesOnePrimaryTransaction`, deux modes, orchestrateur réel avec S/Claim ; `RegistrationAuthorityPostgresTest` pour concurrence et erreur initializer |
| Binding acquire/detach/auxiliaire | Acceptation optionnelle après mutation gagnante ; append fact après mutation B auxiliaire gagnante | `bindingAcquireDetachNoopsAndNewRepositoryAuxiliary`, deux modes, plusieurs facts ordonnés ; repository construit par new et injecté ; tests lifecycle et Binding runtime |
| Business auxiliaire participant/autonome | UUID exact → EventRef par la façade sous propriétaire explicite | `multipleRealBusinessAppendsAndAuxiliaryOwnership`, deux modes ; append multiple + façade participante puis propriétaire autonome ; PID/XID et ordre des positions |
| Writers réels et outcome Registration | Aucun mécanisme de déclaration dans les writers ; poisoning même si erreur interceptée | `eachWriterCaughtJavaErrorIrreversiblyPoisonsPhysicalOwner` : chaque corps direct new refuse hors owner et CLOSED ; rollback de marqueurs précédents et lot suivant propre ; quatre cas `eachJdbcWriterCaughtJavaFailureAfterSuccessfulInsertRollsBackPhysically` |
| INSERT outcome réussi puis échec terminal | Les deux INSERT du writer terminal sont atomiques | `terminalJavaFailureAfterFirstInsertCannotCommitWhenCaught` : outcome provisoire visible avant retour puis zéro après rollback |
| Composition incompatible / ambient inconnu | Aucun fallback ni transaction autonome | `ownerMismatchAndUnknownAmbientCannotCommitEvenWhenCaught`, `incompatibleManagerOrEmfCompositionIsRefusedBeforeAnyEffect` ; tests de refus des entrées publiques ; guards réels non remplacés |
| Doublon, omission, référence fictive, tardif | Registre ordonné, guard CLOSED ; complétude ACTIVE | `realWriterDuplicateLateOmittedAndFictitiousReferences` : rollback et E/positions zéro ; `preparedOmissionAndFictitiousAreDetectedByOracleWithoutClaimingSqlCompleteness` expose explicitement la limite PREPARED |
| Échec après S/Claim et associations réelles | Suffixe exécuté une seule fois après fermeture ; aucune reprise métier après E | `failureAfterRealSequencingRestoresCommandSlotClaimAndEveryEffect` : faute après vrai séquenceur, résultat ROLLED_BACK, Pot/outbox/outcome/terminal/positions/E annulés, S et Claim restaurés |
| Monolithe | Mêmes services et owners réutilisés, aucun second manager | `RuntimeMonolithPostgresTest`, Command et Registration PREPARED/ACTIVE ; manager/EMF/datasource uniques, traces PID/XID et ordre Claim → E |
| Droits / densité / accès à E | Producteurs sans appel de réservation ; seul suffixe fermé accède au séquenceur | Gate `EventPublicationContractArchitectureTest` et `HexagonalArchitectureTest` ; `actualBusinessCompositionRunsUnderRestrictedRuntimeRole`, vrai writer sous rôle runtime restreint ; tests V30 de droits/contraintes maintenus |

### PostgreSQL : lecture des preuves

[Preuve structurée](../testing/evidence/Event_Producer_Wiring_Step_4D_2026-10-20.json) et
[extraits d'exécution](../testing/evidence/Event_Producer_Wiring_Step_4D_2026-10-20.txt) donnent
les commandes, sorties, empreintes des logs complets et traces réellement émises.
Les triggers de test enregistrent `pg_backend_pid()` et `txid_current()` sur mutations métier,
quatre sources, outcome Registration, S/Claim, compteurs et associations. Les tests comparent aussi
les valeurs lues via EntityManager transactionnel et JdbcTemplate, sous le même manager/EMF/datasource.
Les phases d'admission/TA ou d'un propriétaire précédent sont exclues explicitement de la trace TE.

**A.** Pour les quatre writers JDBC, une faute Java est injectée **après** l'exécution réelle d'un
INSERT. Le test relit la ligne provisoire et le xid avant de rendre normalement le callback après
avoir intercepté l'exception. PostgreSQL reste utilisable : le rollback ne peut pas être attribué
simplement à une transaction SQL déjà abortée. À la résolution, le propriétaire refuse le commit ;
ligne et User marqueur sont physiquement absents. Les erreurs de mapping et initializer, les deux
writers JPA et le repository Binding commun passent aussi par les gardes réelles et sont couverts.

**B.** Les effets attendus atomiques ont un seul couple PID/XID, y compris les chemins auxiliaires,
Registration distribuée et les deux producteurs du monolithe. Les lectures via JPA et JDBC confirment
la même connexion physique ; les identités manager/EMF/datasource sont vérifiées en composition.
Les variantes avec autre datasource, EMF, manager JDBC ou ambient non autorisé sont refusées.

**C.** Pour chaque famille : références retournées acceptées = déclarations observées = UUID des
Events matérialisés, sans doublon ni omission. En ACTIVE jetable, la liste des UUID ordonnée par
position est exactement la liste déclarée de la famille, les positions numériques sont `1..N`, et le compteur est sa cardinalité. En
PREPARED, aucune position ni avance E ; les chemins autorisés restent exacts. Le test adversarial
PREPARED montre qu'une omission/fictive contournant l'orchestration n'est **pas** interdite par une
contrainte ACTIVE inactive. La densité n'est pas promise par le seul SQL : composition, séquenceur
et droits constituent la frontière de confiance R1.

## Vérification et adaptations des fixtures

Scope déclaré **avant code** : COMMAND primaire ; BINDING et WEB secondaires ; compléments réels
Registration et monolithe. Gate architecture global **REQUIRED, exécuté PASS** ; full reactor
**NOT REQUIRED, non exécuté**, conformément à V-D (pas de clôture majeure ni intégration main).
DB CURRENT V30 et ACTIVE uniquement sur PostgreSQL 17 jetable. Trusted baseline NONE : bootstrap
par migrations existantes, sans modification de l'historique. Les ancres et commandes sont celles
de la politique ; aucun `-amd` ni dépendance ajouté.

Les anciennes fixtures productrices directes ont été migrées vers le vrai propriétaire et leurs
déclarations explicites, y compris celles du runtime Binding et Registration Result. Il s'agit de
préparation de leurs données, pas de changement du comportement de ces consommateurs. Les fixtures LKV et Event/Projection utilisent également la façade auxiliaire ; la fixture LKV compose localement le vrai owner et la façade, sans ajouter de dépendance au runtime de composition. Le contrôle source Binding conserve l’ordre stream → autorité avec les nouveaux noms de retour. Les tests de
démarrage Web/Command utilisent désormais CURRENT PostgreSQL, car le contrôle de version/mode est réel.
Les doublures unitaires restent des preuves unitaires. L'expérience 3C conserve un writer historique
exclusivement de test pour son gestionnaire expérimental ; elle ne certifie pas V-D. La fixture
reader historique à schéma minimal garde un suffixe vide de test, également exclu des preuves V-D.
La façade synthétique 4B est explicitement prioritaire dans son unique configuration de test. Les restrictions Command autorisent exactement les types d’acceptation et la garde pour le seul writer terminal ; un contrôle supplémentaire interdit aux writers les méthodes d’ownership/déclaration du runner.
Aucun constructeur permissif ni fallback productif n'a été ajouté pour faire passer ces fixtures.
Franchissement de slice productif inattendu : **NONE**.

Les premières exécutions ont trouvé des fixtures aux anciennes signatures, hors owner ou sur H2
sans V30, et une ambiguïté de façade dans la composition synthétique 4B. Elles ont été corrigées dans
les tests concernés. Un test de retry injectait une exception Spring sans cause SQL ; il injecte
désormais SQLTransientConnectionException/08006, sans modifier le classifieur. Le premier lancement
sandbox n'avait pas accès à Docker/à l'attachement Mockito ; la reprise autorisée a exécuté ces tests.
Un échec SSL transitoire d'un test PRIMARY est distingué des défauts corrigés ; les gates finaux ont
repassé entièrement. Les exécutions intermédiaires ne sont pas présentées comme des PASS de clôture.

## Gates effectivement exécutés et verdict

Toutes les commandes ci-dessous ont terminé avec sortie **0**. Commandes Maven depuis `app/` ;
script depuis la racine. Les totaux se recouvrent et ne sont pas un nombre de tests uniques.

| Preuve | Tests déclarés | Failures / errors / skips |
|---|---:|---|
| `vd-certified` | 1136 | 0 / 0 / 1 |
| `compositions-certified` | 930 | 0 / 0 / 0 |
| `architecture-closed` | 1353 | 0 / 0 / 1 |
| `physical-certified` | 38 | 0 / 0 / 0 |
| `runtime-return-certified` | 4 | 0 / 0 / 0 |
| `architecture-targeted-closure` | 80 | 0 / 0 / 0 |

```bash
./mvnw -pl runtime-command-consumption-worker,runtime-binding-consumption-worker,runtime-web-api -am test
./mvnw -pl runtime-registration-consumption-worker,runtime-monolith -am test
./mvnw -pl architecture-tests -am test
./mvnw -pl runtime-command-consumption-worker,runtime-registration-consumption-worker,runtime-monolith,runtime-registration-result-consumption-worker -am test -Dtest=RealProducerEventProtocolPostgresTest,RegistrationRuntimePostgresTest,RuntimeMonolithPostgresTest,RegistrationResultRuntimePostgresTest -Dsurefire.failIfNoSpecifiedTests=false
./scripts/testing/verify-4d-previous-runtime.sh
./mvnw -pl architecture-tests -am test -Dtest=LatestKnownVersionRuntimePostgresTest,CommandCompletionE2EPostgresTest,EventPublicationContractArchitectureTest,HexagonalArchitectureTest,Wa67BindingArchitectureTest,DurableEventToProjectionPostgresTest,HistoricalEventRouteEvolutionPostgresTest -Dsurefire.failIfNoSpecifiedTests=false
```

Les commandes de compilation, test-compile et diagnostics ciblés supplémentaires effectivement
lancées sont listées dans la preuve JSON (`other_executed_commands`) ; les logs intermédiaires, y
compris leurs échecs, portent une empreinte distincte. Le seul skip est celui de l'expérience 3B
historique, sans skip V-D. Les 24 scénarios des producteurs réels, les 38 tests physiques renforcés
et les 80 tests ciblés du complément global sont verts. Le module architecture termine ses
139 tests sans failure/error/skip. Full reactor non requis/non exécuté ; aucun crossing productif inattendu.

**Verdict : 4D CLOSED / PASS — 4E NEXT.** Conditions A/B/C, familles, owners et V-D couverts dans
les limites publiées. PREPARED est livré, avec zéro activation d'environnement déployé. Les
conditions 4E (historique, droits/comptes déployés, répétition du cutover, charge, restauration et
autorisation d'activation) restent à traiter avant tout flip.

## Retour PREPARED à l'ancien runtime

Le [script reproductible](../../scripts/testing/verify-4d-previous-runtime.sh) exporte l'ancien HEAD
`4defd5a6` et copie les sources courantes dans des répertoires temporaires, sans changer les worktrees.
Il crée son propre conteneur PostgreSQL, enchaîne **nouveau → ancien → nouveau → ancien**, publie
réellement un BusinessEvent à chaque étape et conserve les 1/2/3/4 événements déjà committés.
Chaque contexte/pool est fermé ; une connexion indépendante vérifie l'absence de transaction
productrice dans pg_stat_activity avant le binaire suivant. Chaque étape exige PREPARED/version 1,
E=0 et aucune position. Le conteneur est supprimé par le script. Aucun retour à chaud ou déploiement.

## Limites et état Git

Les comptes, configurations et données d'un environnement déployé ne sont pas audités à distance.
Le test sous rôle runtime fournit les seuls privilèges de source/trace requis sur sa base jetable ;
les comptes réels restent à vérifier dans le runbook 4E avant activation. Aucun test ne transforme
la preuve bornée en police SQL universelle ni en garantie de transport externe. La classification
UNKNOWN reste inchangée ; aucune panne réseau nouvelle n'est annoncée comme injectée par V-D.
La contention, les seuils opératoires, la sauvegarde, l'historique et le cutover appartiennent à 4E.

Runtime livré : **PREPARED**. Aucun ACTIVE déployé, aucun backfill, aucun merge main ni tag.
Le checkout initial feat/runtime-monolith et tous ses changements restent intacts. Dans le worktree
cible, le fichier non suivi préexistant `Architecture_Last_Skip_Audit_2026-10-20.md` est conservé
et exclu de la livraison, empreinte initiale inchangée. Commit/push uniquement après certification.
État Git à certification : HEAD de départ inchangé, sources/proofs ci-dessus validés, fetch de la branche cible à divergence 0/0 ; commit/push de livraison en cours après certification. Le bilan de publication sera complété après le premier push.
