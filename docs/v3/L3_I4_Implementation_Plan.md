# Pocoma V3 — L3/I4 — Plan d'implémentation R-ID-POT

**31 octobre 2026 — READY FOR PLAN AUDIT. PLAN UNIQUEMENT, NON EXÉCUTÉ.**

Un audit du plan, un GO humain explicite, puis un seul mandat d'implémentation
de bout en bout. L'arbitrage **A1 (§16)** doit être tranché avant ce GO ; sa
recommandation n'est pas une modification acquise du plan L3. Aucun résultat
prospectif ci-dessous ne vaut test exécuté, PASS, acceptation ou certification I4.

## 1. Références Git, statut et autorité de progression

Dépôt `kartaguez/pocoma`, checkout
`/Users/julien.guezennec/Dev/projects/pocoma`, branche réellement consultée
`feat/v3-architecture`. HEAD initial et tête distante après
`git fetch origin feat/v3-architecture` :
**`a336d2b77d6c7727d565ea2b61a02f59603b6149`**, identique à la référence du mandat.
Divergence `HEAD...origin/feat/v3-architecture` : **0/0**. Worktree et index propres
au départ ; aucun travail préexistant dans ce checkout. Les quatre autres
worktrees détachés inventoriés par Git restent intacts. Aucun checkout, reset,
stash, clean ou écrasement n'est nécessaire. Recontrôler ces faits avant commit
et push ; une avancée distante ne doit jamais être forcée.

| Étape | Autorité documentaire actuelle | Décision exacte et limite |
|---|---|---|
| I1 | [Arbitrage I1 / GO I2](L3_I1_Arbitration_and_I2_GO.md), 30-10 | **ACCEPTED WITH EXPLICIT RESERVATIONS** ; pas de I1 LOCAL BOUNDED PASS ; gate composée FAIL, notamment `i5_r05`/`i5_r12` |
| I2 | [Acceptation I2](L3_I2_Acceptance_Decision_2026-10-30.md), 30-10 | **ACCEPTED WITH EXPLICIT RESERVATIONS** ; 27 tests PG et 21 contrôles ciblés rapportés PASS ; gate COMMAND + Architecture FAIL sur `i5_r12`, Architecture SKIPPED |
| I3 | [Acceptation I3](L3_I3_Acceptance_Decision.md) §§4–8, 31-10 | **ACCEPTED WITH EXPLICIT RESERVATIONS** ; findings clos dans leur périmètre qualifié ; GO **cadrage/préparation I4 seulement** |

Le [rapport de résolution](L3_I3_Findings_Resolution_Report.md) et le
[ré-audit indépendant](L3_I3_Independent_Reaudit_Report.md) étayent la décision
I3, sans requalifier ses gates. R1 : conserver la composition JPA livrée, le
contre-exemple JDBC manuel n'est pas universellement corrigé. R2 : profil SQL DEV
fermé, administrateurs/propriétaires de confiance, pas de surveillance continue
des ACL. R3 : confidentialité qualifiée sur serveur PostgreSQL/journaux Docker,
pas sur tous les collecteurs externes. R4 : FAIL historiques, réserves `i5_r05`,
`i5_r12` et Architecture canonique non acquise restent documentés. Les 232 tests
ciblés frais de résolution ne remplacent aucune gate canonique.

Les [réserves L2 R-L2-01…05](L2_Documentary_Closure.md) et G1/G2 INCONCLUSIVE,
G3 NOT PASSED / NOT COMPLETED restent inchangés. Aucun nouvel audit I1–I3 n'est
ouvert sans contradiction concrète nouvelle ; A1 concerne le raccord I4 et une
lecture technique effectivement présente, pas la réouverture générale des audits.

Autorités à relire à la reprise : [canon](V3_Canon_and_Roadmap.md) §§1–3,
[plan directeur](V3_Implementation_Master_Plan.md) §§3–5 et reprise courante,
[plan L3](L3_Identity_Inventory_and_Implementation_Plan.md) §§3.2–3.3, 5.3, 6/I4,
7–8 et AUD-L3-004 ; décisions ci-dessus ;
[politique Reactor](../testing/Reactor_Verification_Policy.md) §§3, 6, 14–22,
29–30, 34–35. Le présent document détaille I4 sans remplacer ces autorités.

## 2. Objectif fonctionnel et périmètre I4

Livrer **Registration réelle → Binding réel → Command Pot réelle** : le Worker
consomme une Command durable portant l'EUI issue de l'admission et son token,
revalide Identity dans WRITE et applique le protocole Pot existant. Link/Unlink
et les Commands partagent les protections physiques EUI, y compris sous course.

L'admission des tests demeure interne contrôlée (`CONTROLLED_INTERNAL` pour
IdentityRequest). Elle ne certifie ni AuthN externe ni consentement Link. Une
Command n'a aucune exception au token obligatoire ; l'exception Registration
concerne IdentityRequest, et la découverte de son propre CurrentBinding est une
lecture distincte ([SPEC-02](SPEC-02_Identity_Domain.md) §§5–6).

Inclus : un adaptateur, sélection explicite dans la composition partagée,
preuves PostgreSQL et racines Worker/Monolithe, reçus et rapport I4. Exclus :
projections L4, HTTP/consentement/invitations L5, retrait général V2/L7,
distribution L8, nouvelles capacités de scheduling, correction des réserves
temporelles L2, clôture I5/L3 ou certification globale. Les autorisations Pot
restent celles de [SPEC-01](SPEC-01_Pot_Domain.md) §§5–6, pas celles du seul Binding.

## 3. Inventaire ciblé du code réellement présent

Les chemins complets des fichiers à changer figurent au §8. Les constats suivants
proviennent des sources au HEAD initial, non des anciens états « à créer » du plan.

| Composant présent | Constat utile / décision |
|---|---|
| `PotCommand` | Intention durable, requester Eui, token non vide, PotId et observedVersion ; aucun UserId entrant. **REUSE inchangé** |
| `PotActorAuthority` | `Witness(long revision, Optional<UserId> actor)`, `observe`, `protect` ; contrat suffisant pour témoins positifs/négatifs. **REUSE inchangé** |
| `ExecutePotCommand` | Observe avant histoire Pot ; acteur absent → INVALID_BINDING. En finalisation : recharge source, protège Identity, puis Pot si acteur présent, applique, terminalise, complete, publie. `protect=false` → `Collision`. **REUSE inchangé** |
| `V3PotCommandComposition` | Reçoit déjà le port explicitement, dont une surcharge Business/Control avec owners injectés ; classification Collision et priorité NON_COUNTABLE déjà présentes. **REUSE inchangé**, pas de factory « real » supplémentaire |
| `JdbcIdentityAuthority` | Unique INSERT d'ancre + FOR UPDATE EUI ; tri complet par `ExternalUserIdentity.compareKeys`, `current` sous protection, `at`/`user` ; `guarded`, source identique et erreurs SQL expurgées. **REUSE inchangé** |
| Domaine Identity | EUI@version positive, paire User/token, ordre UTF-8 exact ; transitions strictes, User persistant, nouveau token via `SecureBindingTokenGenerator`. **REUSE inchangé** |
| V6 Identity | Ancre zéro distincte d'un état métier, clé unique COLLATE C, histoire immuable, FK et contraintes structurelles/terminales. **REUSE sans migration** |
| `SpringAttemptTransactions` | Owner L2 : préparation RR RO, finale RC RW, guards, poison, fermeture/clear JPA, classification COMMITTED/ROLLED_BACK/UNKNOWN. **REUSE inchangé** |
| `V3ConsumptionWorkerConfiguration` | WRITE Business possède `JpaTransactionManager` sur son EMF/source exacts ; quatre pools existants. Command construit encore la fixture ; préflight Identity appelé seulement si `identityRequest`. **ADAPT** ces deux points |
| `V3ConsumptionWorkerSettings` | Mode fixture/real explicite, couples/limites/partitions fermés ; refuse encore Command réel. **ADAPT** la matrice de modes, conserver les budgets |
| `V3BootstrapGuard` | `validateIdentitySource` contrôle déjà V6, droits, collation/encodage, objets indirects et logging ; refuse fixture. **REUSE inchangé** : élargir ses appelants au mode real, pas ses permissions |
| `V3FixturePotActorAuthority` | Fixture I5 `i5-dev/actor`, témoin/verrou distincts. **RETAIN** pour le banc historique fixture uniquement ; aucune instanciation dans real |
| Racines Worker et Monolithe | Importent la même composition ; Legacy et migrations automatiques exclus sous V3. **REUSE** imports/POM/profils ; qualifier les deux racines |
| `JdbcPotCommandStore.requireCompletionReady` | Contrôle anti-effets sur `v3_pot_version` même pour INVALID_BINDING ; **écart précis A1**, ne pas promettre zéro SELECT Pot en le cachant |

Les tests existants L1 `ExecutePotCommandTest`, `CommandExecutionPostgresTest`
(`c06/c07/c09…c18/c20/c22/c23`), L2 Consumption/Discovery/Worker et composition,
I1 contrats/transitions, I2 autorité/concurrence/schéma, I3 exécution/publication/
privilèges/deux racines restent leurs contributions propres. Le harness I2
`IdentityPostgresFixture` se trouve dans `IdentityAuthorityPostgresTest.java` et
utilise un manager JDBC : il ne qualifie pas à lui seul la réserve R1 runtime.

## 4. Architecture retenue et justification de sa simplicité

Créer `JdbcIdentityPotActorAuthority implements PotActorAuthority` dans le package
technique `infra.persistence.primary.jpa.v3.identity`. Constructeur explicite
`(DataSource writeBusiness, SpringAttemptTransactions writeEffects)` : vérifier
`requireSource`, construire un `JdbcTemplate` et un `JdbcIdentityAuthority` avec
ces mêmes objets. La composition crée cet adaptateur uniquement en mode real et
l'injecte dans la surcharge existante de `V3PotCommandComposition.assemble`.

Un adaptateur suffit : lecture préparatoire SQL locale, calcul privé du témoin,
délégation de protection EUI à l'adaptateur existant. Pas de nouveau port de
lecture, service d'autorité, cache, manager, owner ou interface de verrouillage.
Les deux instances éventuelles de `JdbcIdentityAuthority` des participants
IdentityRequest et Command utilisent le même protocole SQL et le même owner
Business ; partager une instance Java n'est pas nécessaire à leur sérialisation.

Conversions uniquement ici : `PotCommand.Eui` → `ExternalIdentity` exact et
`PocomaUserId.value()` → `UserId` Pot. Ni domaine ni moteur Identity ne dépendent
de Pot/Spring/JDBC. Les dépendances Maven requises existent déjà dans PRIMARY et
la composition ; aucun POM ni module n'est prévu.

La règle actuelle `V3IdentityAuthorityArchitectureTest` interdit le domaine Pot
à **tout** le package technique Identity. La préciser nominativement pour ce
seul adaptateur et les seules valeurs Pot nécessaires au port (`UserId`), tout
en conservant l'interdiction pour les autres classes et pour les transitions/
révisions Pot. Ajouter la preuve de délégation au verrouillage unique. Ce n'est
pas une dépendance domaine Identity→Pot ; c'est l'adaptation technique prévue
par L3 §3.2/§6-I4. Cette modification de règle impose la gate globale (§11).

## 5. Comportement précis de l'adaptateur Identity/Pot

### 5.1 Observation et calcul commun du témoin

`observe(c)` exige la transaction préparatoire WRITE Business RR RO et sa
ressource Spring active ; n'ouvre aucune transaction. Valider la clé complète
avec `ExternalUserIdentity.compareKeys(key,key)` avant SQL : aucune normalisation,
trim/casse, substitution Unicode ou résolution par token seul.

Lire l'ancre par **issuer ET subject** et son état exact à `current_version`
dans cette photographie. Une lecture avec jointure externe permet de distinguer
ancre zéro et incohérence curseur positif/état manquant. Aucune insertion ni
clause de verrouillage. Calcul privé commun aux deux chemins :

| État métier exact pour cette EUI et ce token déclaré | Witness |
|---|---|
| Aucun état, y compris ancre technique à zéro | `(0, empty)` |
| EUI libre à N | `(N, empty)` |
| EUI liée à N, token présenté inventé, ancien ou d'une autre EUI | `(N, empty)` |
| EUI liée à N, token présenté exactement courant | `(N, UserId(U))` |

Le User vient exclusivement de l'état EUI exact vérifié. Sa présence est garantie
par `v3_identity_eui_user_fk` immédiate et l'immuabilité/persistance du User ;
aucune recherche inverse par token ou UserId n'est nécessaire. Une incohérence
structurelle, une erreur SQL ou une transaction incorrecte produit une faute
technique, jamais un faux témoin absent/INVALID_BINDING.

Ne stocker aucun token dans Witness, cache ou message. Égalité opaque exacte,
sans journaliser les valeurs. Expurger les `DataAccessException` comme dans
`JdbcIdentityAuthority` : conserver SQLSTATE utile, jamais DETAIL, paramètres,
cause/suppressed sensibles. Aucun échec technique converti en rejet métier.

### 5.2 Protection finale

`protect(c,observed)` est entièrement sous `SpringAttemptTransactions.guarded`
sur la source Business. Après validation de la clé et du témoin :

1. Appeler **une fois** `JdbcIdentityAuthority.lockInCanonicalOrder(List.of(key))`.
2. Lire `current(key)` sous cette protection et calculer le même Witness à partir
   de la même Command durable et de son token inchangé.
3. Forcer **avant le retour** les contraintes nommées
   `v3_identity_anchor_state,v3_identity_version_state IMMEDIATE`. La création
   d'une ancre zéro par Command ne passe pas par `JdbcIdentityRequestStore.publish` :
   il faut donc purger ses contrôles structurels ici, avant le suffixe Command
   (AUD-L3-001, plan L3 §7.2). Ne pas utiliser ALL IMMEDIATE ni forcer les
   contraintes terminales de publication.
4. Renvoyer uniquement `currentWitness.equals(observed)`, même si actor est vide.

`true` signifie témoin stable, pas nécessairement autorité valide. `false`
déclenche la Collision existante dans `ExecutePotCommand`, sans résultat terminal
ni completion de cette tentative. Aucun retry dans l'adaptateur. Une ancre zéro
peut persister après rejet stable sans User, version métier ou événement Identity.
Lors d'une collision, toute ancre créée par la tentative est annulée avec elle.

### 5.3 Sélection runtime explicite

| Mode / flags | Comportement après I4 |
|---|---|
| `fixture`, `fixture-identity=true`, `identity-request=false` | Banc historique, Command optionnelle explicitement activée ; protections fixture inchangées |
| `real`, `fixture-identity=false`, `identity-request=true`, Command false/true | Identity seule ou Identity + Command réelles |
| `real`, fixture false, IdentityRequest false, Command true | Command réelle sur une autorité déjà alimentée ; **mêmes contrôles Identity complets** |
| fixture en real ; IdentityRequest en fixture ; Command fixture sans installation autorisée ; mode inconnu | Refus avant ressources actives/boucles |

Les autres combinaisons inertes ne permettent aucune exécution non déclarée ;
au moins un couple livré reste requis, éventuellement une probe technique
explicitement autorisée selon les règles existantes. Limites et partitions
doivent correspondre exactement aux couples activés.

Dans le préflight de `V3ConsumptionWorkerConfiguration`, appeler
`guard.validateIdentitySource(w)` lorsque `identityAuthority.equals("real")`,
indépendamment d'IdentityRequest. Valider aussi la source Business réelle avant
assemblage/exécution Command réelle, en réutilisant cette garde. Ne pas ajouter
de bean adaptateur ni élargir les allowlists : construction locale explicite.
Pas de fallback fixture si Identity est indisponible.

## 6. Transactions, protections EUI, ordre des verrous et concurrence

Réutiliser [SPEC-05](SPEC-05_Consumption_Discovery.md) §§4–5/11,
[R1/R5/R9 L2](L2_R1_R10_Normative_Consolidation.md),
[plan L2/I2](L2_I2_Implementation_Plan.md),
[plan L2/I3](L2_I3_Implementation_Plan.md) et
[contrat C](L1_C_Transaction_Contract.md), avec les amendements L2 qui prévalent.

```text
Engine : préparation RR RO (source + témoin + éventuels faits Pot), fermeture
Engine : finale RC RW Business, slot FOR UPDATE, claim/fencing et horloge DB fraîche
Command : recharge source → protection EUI → compare Witness
          → si acteur valide : protection Pot existante/CAS de workingVersion
          → effets éventuels → résultat/TERMINAL → completion.complete()
Store   : préfixe événements + complétion → flush/contraintes métier → fermeture
          → compteur POT si mutation → compteur COMMAND_COMPLETION → positions
Spring  : résolution physique commit/rollback ; R9 si issue inconnue
```

Les verrous EUI sont acquis dans `lockInCanonicalOrder` : pour chaque clé triée,
INSERT avec conflit ciblé `(issuer,subject)`, puis SELECT de **cette même clé**
FOR UPDATE avant la suivante. L'INSERT conflictuel peut déjà attendre. Command
déclare un singleton ; Link/Unlink déclarent leur jeu complet E1/E2 dédoublonné,
trié UTF-8/C avant tout INSERT. Ne pas ajouter de verrou plus tard, ni d'advisory
lock Identity, ni de verrou User ou partition final. Les protections sont
retenues jusqu'au **commit/rollback physique**, pas seulement jusqu'au retour
de protect/complete/closePublication. La garde Pot existante reste distincte.

Entrelacements à imposer avec deux connexions/transactions :

- Registration committée entre observe absent et protect : 0→1, Collision même
  si le token présenté reste invalide. Si Command protège d'abord l'absence,
  Registration attend sa résolution ; le rejet stable peut précéder Registration.
  Refaire sur EUI libre et ancre zéro préexistante, avec commit et rollback du
  détenteur. Un rollback Identity ne doit pas inventer une modification durable.
- Command préparée valide, Unlink commit avant protect : Collision, zéro effet
  Pot final ; nouvelle acquisition et rejet stable avec ancien token. Dans
  l'ordre inverse, Unlink attend le verrou EUI détenu par Command jusqu'à sa
  résolution, y compris Command UNCHANGED ou REJECTED métier Pot.
- Unlink puis re-Link au même U : version N+2 et nouveau token ; ancien Witness
  collisionne malgré le même User. Une nouvelle Command avec ancien token est
  INVALID_BINDING stable ; celle avec nouveau token peut agir selon les droits Pot.
- Transition N→N+1 entre deux témoins tous deux négatifs : Collision malgré
  actor vide aux deux lectures. Token faux avec N inchangé : aucune Collision.
- Link/Unlink à deux EUI avec entrées opposées/non-ASCII et Command singleton :
  traces INSERT/FOR UPDATE dans l'ordre canonique ; aucune deuxième acquisition
  de protection EUI dans la tentative, aucun cycle ajouté via Pot/compteurs.

Une Collision confirmée suit la cession technique unique L2 (R5-03) : rollback,
comptabilisation conditionnelle une fois, retryAfter ou quarantaine selon N,
puis nouvelle acquisition/nouveau claim et préparation entière. Ne pas la
transformer directement en INVALID_BINDING. Claim perdu, transport/commit
UNKNOWN et rollback non confirmé conservent la priorité NON_COUNTABLE ; ne pas
modifier le classifier, R9 ou les protections contre les commits incertains.

## 7. Matrice des invariants et oracles

Toutes les lignes sont **prospectives / NOT RUN**. A = nouveau test adaptateur,
R = nouveau test Command/runtime, C = extension composition Command,
W/M = tests racines existants, X = contrôle Architecture (§8).

| Invariant / références exactes | Variantes décisives et oracle indépendant | Allocation |
|---|---|---|
| Témoins négatifs, AUD-L3-004, L3 §§3.2/7.2 | `l3_absent_stable`, avec/sans ancre zéro ; `l3_free_stable` ; `l3_invalid_token_same_version` : Witness exact, protect=true, INVALID_BINDING terminal unique, zéro retry artificiel. Aucun appel histoire/protection/révisions Pot ; contrôle technique A1 identifié séparément | A + R |
| Autorité valide, SPEC-02 §§5–6, I01-M, S02-C WRITE | `l3_valid_binding_stable`, `l3_foreign_token` : même User à deux EUI mais tokens croisés refusés ; même subject/deux issuers, différences de casse/Unicode distinctes. User résolu uniquement d'EUI+token exact | A + R |
| Changement réel, AUD-L3-004, C05-C/E | `l3_observation_change` : 0→1, N→N+1 négatif/négatif, positif→libre, N→N+2 même U. protect=false ; tentative PENDING, pas de résultat/complétion/effet Pot durable ; retry avec intention et token d'origine | A + R |
| R-ID-POT, I01-A/B/E/H/L, I02-H/I/K internes | `l3_registration_command`, `l3_relink_old_token` : vrais producteurs IdentityRequest et Command ; User créateur Pot égal à U autoritatif, v1 exacte, re-Link renouvelle, ancien token refusé sans dépendre de READ | R + C + M |
| Sérialisation, SPEC-02 §7, SPEC-05 §4, I01-K | `l3_registration_vs_command`, `l3_command_vs_unlink`, `l3_command_lock_until_resolution` : ordres gagnants inversés, commit/rollback, CHANGED/UNCHANGED/REJECTED ; PID/bloqueur, attente puis visibilité après résolution | A + R |
| Ordre unique, AUD-L3-003, L3 §7.2 | `l3_canonical_pot_path` : singleton passe par la primitive unique ; courses avec deux EUI, permutation/Unicode, même clé dédoublonnée. Réexécuter les vecteurs/courses I2 existants ; aucune copie de l'algorithme | A + R + X |
| Ancre zéro et suffixe, AUD-L3-001/002, CON-06/R1 | `l3_anchor_constraints_before_suffix` : ancre technique seule autorisée, zéro version/User/Identity event ; contraintes structurelles exécutées avant retour protect et avant compteur ; aucune acquisition métier tardive | A + C |
| Atomicité Command, E02-A/C/F/H/I, C06-A/E/H/I/L/M | `l3_atomic_command`, `l3_rollback_boundaries`, `l3_unknown_commit` : connexion/PID/XID communs, rollback à chaque coupure, effet nul ou complet, completion unique, R9 sans nouvelle mutation | R + C |
| Histoire/User, SPEC-02 §§2–4/8, I01-H/L/O | `l3_last_unlink_command` : User initial reste, aucune récupération par UserId, nouvelle Registration donne un nouveau U ; historique/tokens exacts conservés ; rejouer une demande terminale ne produit aucun effet | R + M |
| Isolation et secrets, R1–R3 I3, SPEC-08 §5 | `l3_real_command_modes`, `l3_command_only_preflight`, `l3_real_authority_architecture` : deux racines JPA, holder prélié refusé avant callback, fixture/V6 absent/droits excessifs/objets indirects/logging dangereux refusés avant boucle | W + M + X |

Les témoignages négatifs stables n'effectuent pas de préparation métier Pot.
Une tentative initialement positive peut avoir lu Pot en préparation ; après
refus de protect, elle n'acquiert aucun verrou Pot et n'écrit/terminalise rien.
Ne pas appliquer le même oracle SQL à ces deux cas. L'exception technique
proposée A1 ne doit jamais être utilisée pour lire les droits/états métier Pot.

## 8. Liste exacte des fichiers à créer et modifier

Préfixes exacts, relatifs à la racine (concaténer avec le nom de fichier) :

| Code | Préfixe |
|---|---|
| P | `app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/identity/` |
| S | `app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/v3/` |
| PT | `app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/identity/` |
| RT | `app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/v3/` |
| MT | `app/runtime-monolith/src/test/java/com/kartaguez/pocoma/` |
| XT | `app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/v3/` |

**Production : 3 fichiers, 1 création et 2 modifications**, sous réserve de
l'adoption de la recommandation A1 :

| Opération / fichier | Responsabilité exacte |
|---|---|
| CREATE P:`JdbcIdentityPotActorAuthority.java` | §§4–5 : RO, Witness, protection déléguée, contraintes avant suffixe, mapping et confidentialité |
| MODIFY S:`V3ConsumptionWorkerSettings.java` | Matrice real/fixture, Command réelle indépendante du flag fixture, ensembles de couples fermés |
| MODIFY S:`V3ConsumptionWorkerConfiguration.java` | Choix exclusif de l'adaptateur réel, validation Identity en mode real même Command seule, owner/pools existants inchangés |

**Tests : 6 fichiers, 2 créations et 4 modifications** :

| Allocation / opération / fichier | Responsabilité |
|---|---|
| A — CREATE PT:`IdentityPotAuthorityPostgresTest.java` | Témoins, tokens tiers, clé exacte, protections, ancre zéro, ordre et contraintes (§§5–7) ; setup JPA local au test pour les preuves R1 |
| R — CREATE RT:`V3IdentityPotCommandPostgresTest.java` | Vrais producteurs, courses/retry, effets persistés, atomicité/commit incertain et bootstrap Worker réel |
| C — MODIFY RT:`V3PotCommandCompositionPostgresTest.java` | Nouveau parcours factory réelle séparé du banc fixture ; classifier/owner et oracles L1/L2 conservés |
| W — MODIFY RT:`V3IdentityRequestCompositionPostgresTest.java` | Réutiliser sa Fixture dans le même package, modes et refus Command seule, guards I3 conservés ; refus ancien « Command=true » doit viser une configuration réellement invalide (limites/partitions incomplètes), plus un nouveau cas positif complet |
| M — MODIFY MT:`V3IdentityRequestMonolithPostgresTest.java` | Ajouter Registration→Command et ancien token refusé sous vraie racine Monolithe ; même précision des anciens cas négatifs ; garder tests I3 et réserves JPA/logging |
| X — MODIFY XT:`V3IdentityAuthorityArchitectureTest.java` | Exception technique nominative UserId, pas de seconde autorité/transaction ni contournement EUI, domaine Identity toujours pur |

Réutiliser les helpers locaux existants ; barrières/décorateurs de ports sont
des instruments de test déléguant aux vrais adaptateurs, jamais des hooks
productifs. Pas de nouvelle hiérarchie de fixtures ni copie des deux gros tests
racines. Les tests de verrous ne se contentent pas de la Fixture I2 JDBC.

**Vérification : 1 fichier à modifier** :
`scripts/testing/verification-campaigns.json`, ajouter A et R à CHANGE Identity
(donc INTEGRATION cumulative), conserver les classes existantes et les deux
quarantaines nominatives ; ne pas ajouter Architecture à une recette filtrée.
FAST demeure le feedback pur existant, sans prétendre tester le nouvel adaptateur.

**Livrables après implémentation seulement** :
`docs/v3/L3_I4_Execution_Report.md`,
`docs/v3/L3_I4_Proof_Manifest.json`,
`docs/testing/evidence/L3-I4/README.md` et reçus/archives référencés selon la
politique de rétention ; actualisation factuelle de
`docs/v3/V3_Implementation_Master_Plan.md`. Pas de clôture anticipée I5/L3.
Dans la présente intervention, **seul ce plan est créé**.

`V3BootstrapGuard`, `V3PotCommandComposition`, les profils des deux racines,
`V3FixturePotActorAuthority`, les outils DEV, scripts SQL, POM, moteurs/ports et
migrations ne sont pas à modifier. Le runbook I3 et `inject-command` restent un
banc Identity seule / Command fixture historique ; ne pas les présenter comme
une démonstration CLI R-ID-POT. I4 prouve le raccord actif par les deux racines
Spring et les véritables Discovery/Engine, sans imposer un nouvel outil de démo.

## 9. Migrations et permissions : aucune nouvelle requise

**Hypothèse zéro migration confirmée par inspection statique**, sous le choix A1
recommandé. WRITE utilise déjà V1/V3/V4 Pot, V2 Command, V5 Consumption et V6
Identity ; READ technique V1. Aucun checksum V1–V6 ne doit changer.

Les objets suffisants sont : `v3_identity_anchor_key` unique `(issuer,subject)`
COLLATE C, ancre `current_version>=0`, PK `(eui_id,version)` positive,
`v3_identity_token_history` unique, paire User/token et FK User immédiate,
guards d'immuabilité/successeur et contraintes `v3_identity_anchor_state` /
`v3_identity_version_state`. L'adaptateur n'écrit que la réservation d'ancre
via la primitive existante ; il ne produit ni histoire ni événement Identity.

`grant-l2-lifecycle.sql` donne déjà au rôle WRITE real les SELECT Identity,
INSERT d'ancre et UPDATE(current_version) requis par les protections existantes.
Le profil real possède les droits des deux participants même lorsqu'un seul
couple est activé ; I4 n'ajoute ni rôle ni grant. Les scripts Discovery/Worker
DEV contrôlent déjà les rôles auxiliaires et l'exposition indirecte ; leurs
contrôles administratifs R2 restent obligatoires. Vérifier avec des rôles
non propriétaires que les opérations légitimes passent et les excès sont refusés.

La garde Java est réutilisée intégralement, y compris logging et objets indirects.
Pas de SELECT token accordé à READ, résultat public ou compte injecteur nouveau.
Toute nécessité démontrée d'un DDL/grant additionnel impose l'arrêt §14 avant
code : nommer l'invariant impossible, proposer la migration minimale et analyser
les locations/profils V1–V6, sans réécrire l'histoire publiée.

## 10. Plan de tests PostgreSQL et runtime

Employer PostgreSQL réel jetable avec image/digest et version serveur enregistrés
(fixtures actuelles sur PostgreSQL 17.11), schémas isolés, connexions distinctes
et timeouts bornés. Les setups préparent CURRENT WRITE V1–V6 et READ technique
V1 ; aucune migration n'est exécutée par cette préparation documentaire.

Pour chaque variante §7 : snapshot SQL avant/après depuis une connexion
indépendante **après résolution physique**, attendus littéraux indépendants des
transitions productives ; enregistrer requestId, phases/barrières, PID/XID,
SQLSTATE et bloqueurs sans token. Vérifier User, ancre/curseur/états exacts,
Pot versions/révisions/effets, source/resultat, événements/composants,
compteurs/positions et slot canonique. Comparer les identités, pas seulement
les comptes. Un rejet produit une seule complétion Command et zéro événement
de mutation Pot ; aucune mutation ni complétion Identity créée par Command.

Déterminisme : barrières après observe et après protection EUI, avant fermeture
et avant résolution ; constater l'attente serveur par PID/`pg_blocking_pids`,
puis libérer explicitement le détenteur. Probes NOWAIT/lock_timeout depuis une
autre connexion pour démontrer le verrou jusqu'à résolution ; `pg_locks` seul
et sleeps ne sont pas des preuves. Les traces SQL de test doivent masquer les
binds sensibles et inclure les fonctions/contraintes pertinentes, pas seulement
les appels Java. Les événements persistés ne contiennent aucun token.

Les scénarios de reprise font tourner le **vrai Engine et le classifier injecté**
de la factory. Après Collision : PENDING, aucun résultat/événement/effet, slot
conservé puis cession/count/retryAfter exacts ; attendre l'échéance DB et acquérir
un nouveau claim, sans appeler deux fois finalizeIn sous l'ancien. Tester le
passage négatif→négatif autant que positif→négatif. Une répétition terminale
retourne le résultat original sans seconde completion.

Atomicité : coupures après protection, après révision, résultat/TERMINAL,
complete, préfixe et positions ; erreurs SQL/Java interceptées doivent empoisonner
l'unité. Réutiliser le proxy PostgreSQL de faute COMMIT des tests L1/L2 pour
deux issues : rollback certain et commit avec ACK perdu. Constater zéro ou un
effet durable, puis R9/relecture avant réacquisition ; aucun budget inventé et
aucune nouvelle consultation Identity susceptible de rejouer un résultat terminal.
Conserver les checks L1/L2 de claim expiré/takeover et priorité NON_COUNTABLE.

Pour R-ID-POT, démarrer le vrai Worker avec real, fixture=false,
IdentityRequest+Command et leurs quatre partitions/limites exactes ; insérer
PENDING sans slot sous admission interne contrôlée, laisser Discovery et Engine
produire les effets. Récupérer le propre token uniquement dans le harness
confidentiel, jamais par un nouveau endpoint. Démontrer CreatePot puis mutation
sur Pot existant, re-Link et refus de l'ancien token. Refaire le parcours minimal
sous la racine Monolithe partagée, avec restart du contexte sans reset des tables
et sans double effet ; ce n'est pas une certification de distribution L8.

Le retard READ est représenté par le maintien du token ancien côté appelant et
l'absence de toute consultation READ pour l'autorité Command. Ne pas créer une
projection CurrentBinding de substitution et prétendre qualifier I01-Q/SEC-02.
Les exclusions L3 §7.1 restent : ID-02 externe, API/Bruno, READ L4, SLO/charge
SPEC-09 et [SPEC-10](SPEC-10_Test_Certification.md) §§8–12 non certifiés par I4.

## 11. Déclaration de portée Maven et campagnes de vérification

### 11.1 Portée prospective avant tout code

| Élément | Déclaration I4 |
|---|---|
| Risques concernés | Identity, Security/Authorization, Transaction/Atomicity, Concurrency/Recovery, Runtime/Assembly, Architecture ; publication Command en non-régression |
| Impact productif autorisé | PRIMARY adaptateur et composition Spring partagée, exactement les trois fichiers §8 |
| Slice primaire | **COMMAND**, ancre `runtime-command-consumption-worker` |
| Slices secondaires canoniques | **NONE** ; consommation du contrat Identity existant, sans changement du runtime BINDING V2 |
| Preuve d'une autre racine | `runtime-monolith`, ancre complémentaire, pas une nouvelle slice canonique |
| Modules modifiés | `infra-persistence-primary-jpa`, `composition-runtime-spring`, tests `runtime-command-consumption-worker`, `runtime-monolith`, `architecture-tests` ; registre hors Maven |
| Amont réutilisé | Domaines Pot/Identity, contrats Registration, engines Registration/Pot/Consumption, ports/infra transaction, Discovery/Worker ; fermeture exacte calculée par Maven `-am` |
| Impact interdit | Contrats Pot cités, domaine/transitions, owner/managers/leases/claims/retry/R9, routing/UUID/P=4, budgets/pools, publication/suffixe Pot, READ/HTTP/Legacy, migrations/grants |
| Gate Architecture | **REQUIRED**, règle de dépendance interdite précisée et raccord d'isolation runtime ; politique §17 |
| Full reactor | **NOT REQUIRED**, étape bornée, aucun module/POM/contrat restructuré, aucune clôture de Wave majeure ou intégration main ; politique §§18–21 |
| FOUNDATION | Preuves transactionnelles existantes incluses en INTEGRATION Identity ; campagne autonome non requise tant qu'aucune infrastructure FOUNDATION ne change |
| DB | **CURRENT** PostgreSQL réel ; baseline TRUSTED actuellement **NONE** selon politique §§24/34–35 ; journaliser les migrations transitives des tests |
| Prochaine gate globale | Architecture fraîche à la fin I4 ; certification L3/I5 puis jalons L6–L8 restent distincts, sans full reactor implicite ici |

### 11.2 Commandes à exécuter après GO

Depuis la racine, après ajout des deux nouvelles classes à CHANGE dans le registre
et revue du plan de sélection (`--dry-run`) :

```sh
python3 -B scripts/testing/verify.py FAST identity --dry-run
python3 -B scripts/testing/verify.py FAST identity
python3 -B scripts/testing/verify.py CHANGE identity --dry-run
python3 -B scripts/testing/verify.py CHANGE identity
python3 -B scripts/testing/verify.py INTEGRATION identity --dry-run
python3 -B scripts/testing/verify.py INTEGRATION identity
```

FAST pendant les itérations pures ; CHANGE pour adaptateur/courses/guards ciblés ;
INTEGRATION finale avec Monolithe, compositions voisines et contrats transactionnels.
Ce sont des **recettes filtrées**, pas les gates canoniques. Si le registre change,
exécuter ses contrôles significatifs :

```sh
python3 -B -m unittest discover -s scripts/testing -p 'test_verification*.py'
```

Gates obligatoires non filtrées, depuis `app/`, en série :

```sh
./mvnw -pl runtime-command-consumption-worker,runtime-monolith -am test
./mvnw -pl architecture-tests -am test
```

La première compose la commande canonique COMMAND
`./mvnw -pl runtime-command-consumption-worker -am test` et la preuve Monolithe.
Garder Architecture séparée pour qu'un FAIL amont COMMAND n'empêche pas son
exécution fraîche. Ni `-Dtest`, ni skip, ni exclusion des quarantaines dans ces
deux commandes. Les options explicites de provenance §11.3 ne filtrent aucun test.
`python3 -B scripts/testing/verify.py INTEGRATION COMMAND` peut collecter COMMAND
seule avec le producteur isolé validé ; elle ne remplace pas la preuve Monolithe.

Ne pas lancer `./mvnw test` ou CERTIFICATION par défaut. FOUNDATION autonome
`./mvnw -pl infra-tx-spring -am test` devient requise si l'infrastructure de test/
transaction est réellement modifiée, après redéclaration. Cela ne donne pas
autorisation de modifier l'owner : §14 s'applique. Toute restructuration large,
intégration main ou portée non bornable exige un arbitrage de périmètre et les
gates §§17/19 ; une erreur locale de test n'est pas une raison de lancer FULL.

### 11.3 Fraîcheur, sélection et attribution

Suivre intégralement la politique §35 et la
[correction de provenance AUD-VERIFY-001](Verification_AUD_VERIFY_001_Provenance_Resolution.md),
avec les limites de son
[contre-audit final PASS WITH RESERVATIONS](Verification_AUD_VERIFY_001_Final_Provenance_Counter_Audit.md)
§§6/8 : adoption des recettes actuellement revues, aucune généralisation à tout
JUnit/Maven. Employer des méthodes `@Test` ordinaires pour les nouveaux tests ;
pas d'annotations qualifiées/composées ni de providers nouveaux sans revue de
leur découverte et attribution.
Exécutions Maven/PG sérielles avec propriétaire unique du checkout/targets,
aucune campagne concurrente. Refuser options implicites d'environnement/Maven,
conserver l'effective POM avec la même sélection et l'inventaire attendu des
invocations depuis les sources actuelles, y compris fixtures et SQL externes.

Pour les gates natives, employer le profil existant `verification-provenance`
avec `-Pverification-provenance -Dpocoma.verification.id=<ID_unique>` et
`-Dpocoma.verification.reports=<dossier_absolu_neuf>/reports`. Archiver modèle,
commande exacte, environnement non secret et XML de ces producteurs seulement ;
contrôler ID/module/producteur et inventaire attendu comme le sélecteur validé.
Ne jamais attribuer une gate PASS à partir des anciens `target/*-reports` ou du
seul exit code. Discovery non supportée/ambiguë par le collecteur actuel ⇒
INCONCLUSIVE, revue de collecte bornée avant toute qualification, sans masquer
un test ni étendre un framework de vérification par commodité.

Enregistrer SHA testé, dirty state/diff exact, hashes des sources/POM/registre/
SQL/configuration/outils, JDK/Maven/settings et PG image/version. Geler le code
avant les preuves finales ; comparer les entrées avant/après et code testé→commit
publié. Si seuls rapports documentaires sont ajoutés ensuite, l'attester
explicitement ; toute modification exécutable invalide les preuves concernées.
Une sortie obsolète après déplacement/suppression/changement de branche impose
une compilation propre de la fermeture affectée, pas un clean/install général.

Chaque variante §7 a son allocation dans le manifeste I4 ; archiver traces
physiques, XML frais, logs expurgés, reçus et SHA-256 vérifiés. Une variante interne
en boucle doit avoir son reçu nominatif ; le seul nombre JUnit n'en prouve pas
la couverture. Réutilisation historique : SHA/hypothèses/SQL/config/outils
comparés et artefacts accessibles vérifiés, attribution explicite à l'ancien SHA ;
elle ne satisfait pas une gate finale fraîche I4. Aucun FAIL/SKIP/NOT RUN/
INCONCLUSIVE transformé en PASS, y compris le skip historique exploratoire L2.

Une récidive `i5_r12`/`i5_r05` dans la gate non filtrée reste un FAIL. L'isoler
par preuves positives I4 et diagnostic causal, sans changement de timeout,
exclusion canonique ou relance inchangée pour fabriquer un succès. Rapporter
la gate exacte et ses limites pour l'audit final. Si le défaut empêche réellement
les preuves I4 et ne peut être isolé, appliquer §14 ; sinon terminer les travaux
et livrer les preuves avec réserves, sans auto-prononcer l'acceptation.

## 12. Risques et mesures de maîtrise

| Risque concret | Mesure / limite |
|---|---|
| `protect` confondu avec « token valide » | Égalité des Witness complets, cinq catégories de témoins testées séparément |
| ABA même User / token tiers | Version EUI monotone et token propre à la clé exacte ; deux EUI même U dans les tests |
| Command seule évite le préflight I3 | Validation real indépendante d'IdentityRequest sur Control puis Business ; cas négatifs dédiés |
| Ancre zéro laisse un trigger structurel au commit sous compteur | SET CONSTRAINTS nommé dans protect ; trace avant suffixe, pas de migration ni ALL IMMEDIATE |
| Holder JDBC prélié | Owner Business JPA conservé ; tests R1 des deux racines, aucune substitution technique |
| Fuite par exception/trace SQL ou permissions accrues | Diagnostics expurgés, R2/R3 et scripts inchangés ; tokens privés dans assertions uniquement |
| Coût/verrous prolongés EUI | Préparation RO, protection finale minimale ; aucun nouveau budget/performance promis, aucune modification des marges L2 |
| Ancien test de refus Command real devient trompeur | Tester configuration incomplète et configuration complète positive, sans supprimer les refus de sécurité |
| Garde d'architecture package trop large | Exception exacte du seul adaptateur aux valeurs nécessaires, interdictions sur le reste et gate globale fraîche |
| A1 / faux « zéro accès Pot » | Arbitrage écrit avant GO, Oracle distinguant contrôle anti-effets et accès métier ; garde d'atomicité conservée |
| Gates historiques / collecte incomplète | Statuts séparés, reçus frais, aucune certification globale déduite |

## 13. Critères de sortie R-ID-POT

1. L'adaptateur et la composition réelle sont livrés dans le périmètre §8 ; aucun
   contrat Pot, owner, moteur, protocole EUI ou migration modifié pour commodité.
2. Registration→Command et Link/Unlink→Command démontrés par vrais producteurs,
   PostgreSQL et les deux racines ; ancien token et token tiers refusés.
3. Tous les oracles §7 obligatoires acquis sur le code testé : témoins stables,
   changements réels, ordre/verrous jusqu'à résolution, collisions sans effets,
   reprise L2, histoire/User et atomicité/suffixe conservés. Portée A1 explicitée.
4. FAST/CHANGE/INTEGRATION et les deux gates non filtrées exécutés avec verdicts
   exacts, preuves fraîches et limites. Tout échec imputable à I4 est corrigé et
   revérifié ; un échec historique isolé reste FAIL et ne donne pas de PASS
   canonique. Audit final et éventuelle acceptation avec réserves restent humains.
5. Rapport/manifeste I4 autoporteurs : SHA initial/testé/final, fichiers et impact
   réel, slices/commandes effectivement exécutées, franchissements éventuels,
   Architecture/full/DB, chaque allocation et preuve, réserves et synchronisation.

Ces critères attestent la réalisation interne R-ID-POT dans le périmètre démontré,
pas la clôture L3/I5, ID-02 externe, READ ni un GO production.

## 14. Conditions d'arrêt exceptionnel

Après le GO unique, interrompre et demander arbitrage **seulement** pour :

1. contradiction normative réelle nouvellement établie ;
2. nécessité de changer contrat métier ou frontière interdite ;
3. migration imprévue indispensable ;
4. impossibilité technique démontrée de respecter un invariant ;
5. défaut préexistant empêchant réellement de qualifier I4, impossible à isoler.

Fournir la preuve précise, les fichiers/clauses concernés et la solution minimale.
Erreurs ordinaires compilation/test/intégration dans le périmètre : corriger
autonomement. Une redéclaration bornée de vérification se documente avant
élargissement ; elle ne crée pas par principe un nouveau checkpoint humain.
Un invariant interdit ne devient pas autorisé par cette redéclaration.

## 15. Séquence interne d'exécution en une seule campagne

Après audit, résolution A1 et **un seul GO d'implémentation** :

1. Recontrôler branche/HEAD/distant/worktrees/delta depuis ce plan ; préserver les
   travaux étrangers, relire les autorités et déclarer le scope §11 avant code.
2. Réaliser l'adaptateur et ses oracles PG, puis les raccords de modes/préflight
   dans la même campagne ; maintenir le banc fixture historique séparé.
3. Compléter courses, reprises, atomicité, deux racines et contrôles Architecture ;
   mettre à jour uniquement la sélection de recette nécessaire. Corriger les
   défauts locaux et itérer avec FAST/CHANGE sans validation humaine intermédiaire.
4. Geler les entrées exécutables ; INTEGRATION Identity puis gates canoniques
   COMMAND+Monolithe et Architecture en série, avec preuves et qualification
   source/bytecode. Expliquer les FAIL historiques sans élargir leur chantier.
5. Relire le diff et les preuves, compléter rapport/manifeste et état directeur,
   publier selon le mandat de développement futur, puis remettre le dossier à
   l'audit final. Aucune acceptation ni clôture prononcée par le développeur.

Les étapes sont une organisation interne, **pas des sous-livraisons** : aucun GO
après adaptateur, concurrence ou composition. Seul un arrêt §14 justifie de
solliciter un nouvel arbitrage pendant l'exécution.

## 16. Arbitrages bloquants avant GO

### A1 — Portée de « zéro accès Pot » et garde anti-effets existante

**Fait vérifiable au HEAD initial :**
`JdbcPotCommandStore.requireCompletionReady` exécute pour tout résultat non
CHANGED, donc pour INVALID_BINDING, un SELECT de `v3_pot_version` filtré par
`pot_id`, ordonné par version, comparant `writer_xid=pg_current_xact_id()`. Il
refuse une completion sans effet déclarée lorsqu'une version Pot a été produite
dans cette unité. Ce contrôle précède `completion.complete` et le suffixe ; il
ne prend aucun verrou de ligne Pot et ne décide d'aucune autorisation métier.

Le plan L3 §3.2/§7.2 exige simultanément, pour les témoins négatifs stables,
« aucun accès à l'histoire, au curseur, au verrou ou aux révisions Pot » et
« zéro accès Pot » par capture SQL. La garde ci-dessus lit bien une table
d'histoire Pot. Les seuls espions des ports de préparation ne révèlent pas ce
SELECT : promettre leur oracle comme preuve d'absence SQL serait inexact.

**Recommandation soumise à décision avant GO :** préserver intégralement cette
garde L1/L2 et préciser pour I4 que l'interdiction porte sur l'accès **métier**
(histoire décisionnelle, droits, curseur, protection et révisions), avec une
exception explicite pour ce contrôle technique pré-completion d'absence d'effet.
Les contrôles existants de cohérence Command/publication, y compris ceux des
triggers, restent également requis. Les tests tracent séparément ce SELECT,
vérifient qu'il ne résout aucune autorité et qu'aucune lecture métier/verrou/
mutation Pot ne survient ; un test adverse démontre que la garde interdit
toujours l'effet partiel injecté. **Aucun changement de code du store proposé.**

Cette précision n'est **pas acquise** par ce plan et n'est pas introduite dans
le canon/SPEC ou le plan L3 pendant ce mandat. Le GO doit l'adopter explicitement
ou renvoyer le plan pour amendement. Si l'absence absolue de SELECT Pot est
confirmée, il faut d'abord concevoir/auditer une garantie anti-effets équivalente
sans lecture Pot ; ne pas supprimer la garde, ajouter un état/manager ou une
migration en cours d'implémentation. La liste de fichiers et le zéro migration
devront alors être réévalués avant GO. Aucun autre arbitrage métier/contrat ou
besoin de migration n'a été identifié dans le périmètre inspecté.

**Verdict : READY FOR PLAN AUDIT, avec A1 bloquant le GO d'implémentation tant
qu'il n'est pas tranché.** I4 n'est ni commencé ni validé.

Vérification du présent mandat documentaire : slices primaire/secondaires
**NONE**, impact productif **NONE**, DB/migrations **NOT RUN**, Architecture et
full reactor **NOT REQUIRED / NOT RUN**. Contrôles autorisés : lectures sources,
Git/distant, chemins/liens, cohérence des références et `git diff --check` ;
aucun Maven, test applicatif ou démarrage PostgreSQL. Seul ce document est
destiné au commit documentaire ; le SHA de publication est communiqué après
push, sans auto-référence dans son contenu.

Commandes de contrôle effectivement utilisées pour cette préparation :
`git status --short`, `git branch --show-current`, `git rev-parse HEAD`,
`git fetch origin feat/v3-architecture`,
`git rev-list --left-right --count HEAD...origin/feat/v3-architecture`,
`git worktree list --porcelain`, `git log`, `git show`,
`git ls-remote origin refs/heads/feat/v3-architecture`, lectures `rg`/`sed`/`cat`,
`python3 -B -` pour les 16 sections, liens et inventaire exact des fichiers,
`git diff --no-index --check /dev/null docs/v3/L3_I4_Implementation_Plan.md` et
`git diff --check`. Franchissement inattendu de slice : **NONE**.
