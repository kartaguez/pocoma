# Pocoma V3 — L4 — Décision humaine définitive de clôture READ

**Décision humaine du 3 novembre 2026**, transmise par le mandat de clôture documentaire définitive du responsable du programme.

**L4 — READ Projections : CLOSED WITH EXPLICIT RESERVATIONS — COMPOSITE INTERNAL QUALIFICATION ACCEPTED.**

Branche : `feat/v3-architecture`. Dossier de livraison I8 accepté :
`70ff3e53526d902cfba141cf11385e340138503d`.
La décision clôt définitivement L4 dans son périmètre interne. Elle accepte les preuves composites et leurs réserves ; **aucun full reactor natif PASS ni aucune certification SPEC-10 globale ne sont revendiqués**.

## 1. Autorités et références

Le présent acte enregistre la décision humaine acquise et la synthèse de l’audit final fournie dans la conversation par le mandat. Il ne constitue ni un nouvel audit indépendant, ni une nouvelle qualification technique. La décision de clôture est distincte de la [dispense humaine du quatrième full](../testing/evidence/L4-I8/Human_Composite_Qualification_Decision.json), reçue le 3 novembre 2026 à `19:18:43.991967 UTC` : ce reçu historique portait encore `L4_closed: false` avant audit et clôture ; il reste inchangé.

Références de réalisation : [plan global L4](L4_Read_Inventory_and_Implementation_Plan.md), [plan I8](L4_I8_Implementation_and_Qualification_Plan.md), [rapport I8](L4_I8_Execution_Report.md), [manifeste I8](L4_I8_Proof_Manifest.json) et [dossier de preuves](../testing/evidence/L4-I8/README.md). Les anciennes mentions « L4 ouvert », « audit/clôture à venir » et les obligations de lancement dans ces pièces décrivent leur état de livraison antérieur ; le présent acte prévaut pour le statut courant sans réécrire leurs résultats.

Autorités normatives conservées : [SPEC-04 — READ](SPEC-04_Read_Projections.md), [SPEC-05 — Consumption/Discovery](SPEC-05_Consumption_Discovery.md), [SPEC-06 — Supras](SPEC-06_Supra_Contracts.md), [SPEC-10 — certification](SPEC-10_Test_Certification.md), [canon V3](V3_Canon_and_Roadmap.md) et [Master Plan](V3_Implementation_Master_Plan.md). Aucune SPEC n’est modifiée.

## 2. Périmètre interne accepté

- Six participants READ indépendants, sélectionnables explicitement dans PROJECTION et le Monolithe : `MaterializePotVersion`, `MaterializePotBalance`, `MaterializePotVisibility`, `MaterializeCurrentBinding`, `MaterializeCommandResult`, `MaterializeIdentityRequestResult`. PotVersion inclut SubobjectVersion et CurrentPot ; Visibility inclut CurrentUserPotList. L’activation reste soumise aux préflights et paramètres explicites qualifiés.
- Réutilisation de Discovery/Consumption L2, des identifiants canoniques, slots, claims, leases, checkpoints et propriétaires transactionnels existants ; effets et coordination dans leur autorité physique, sans transaction distribuée WRITE/READ.
- Façades internes `current`, `at`, `balance`, `expenses`, `visible`, `binding`, `commandResult`, `identityResult` ; capture et consultation cohérentes à V, dépendances exactes, navigation non autorisante et résultats soumis à leur propre autorité.
- Onze scénarios I8 **T01–T11 PASS**, sans failure, error ni skip, dans l’invocation complète au code productif `a3470df662f4552d05785ab3ec191951c4cc9d56` : configuration, assemblage/droits, six progressions, courant/histoire/V, disponibilité, lots Expense, Binding/Results, navigation, drainage/restart, ressources/renewal/UNKNOWN et coexistence des deux racines.
- Qualification composite interne acceptée : 2 024 tests applicatifs hors Architecture PASS et 167 tests Architecture PASS, selon les deux contributions attribuées ci-dessous.
- Avis indépendant final favorable, **aucun BLOCKER ni MAJOR**, avec le MINOR non bloquant `AUD-FINAL-I8-01` conservé en suivi.

L’acceptation interne ne vaut pas homologation de production, des volumes ou de la disponibilité, ni qualification des parcours externes L5.

## 3. Composition exacte de la qualification

Les nombres sont exprimés en tests/failures/errors/skips. Les 2 191 identités distinctes sont qualifiées par composition autorisée ; aucune exécution native unique `2191/0/0/0` n’est déclarée.

| Contribution | SHA réellement testé | Invocation | Résultat et preuves |
|---|---|---|---|
| Tous les tests hors Architecture du troisième full exécuté | `a3470df662f4552d05785ab3ec191951c4cc9d56` | `6b091895d103489b857c7027a3481e45` | **2024/0/0/0 PASS**, extrait du full qui reste FAIL ; [reçu](../testing/evidence/L4-I8/runs/FULL-04-FAIL/result.json), [archive brute](../testing/evidence/L4-I8/runs/FULL-04-FAIL/raw.tar.gz) |
| Module Architecture complet, campagne ciblée après correction des oracles | `8a007768c23021153764473f6c6686042168df00` | `9a633445391845689c07678d040e440f` | **167/0/0/0 PASS** ; [reçu](../testing/evidence/L4-I8/runs/Architecture-correction-PASS/result.json), [archive brute](../testing/evidence/L4-I8/runs/Architecture-correction-PASS/raw.tar.gz) |

La [comparaison test-only](../testing/evidence/L4-I8/Architecture_Test_Only_Comparison.json) établit que seuls `HexagonalArchitectureTest.java` et `V3ConsumptionFoundationArchitectureTest.java` changent entre ces SHAs : 1 371 des 1 373 entrées qualifiées restent identiques, sans modification de source productive, POM, ressource ou SQL. La campagne Architecture demeure techniquement ciblée ; son emploi comme contribution finale résulte de la dispense humaine explicite.

Les identités T01–T11, SHAs, invocations et artefacts exacts sont attribués dans le manifeste I8. Le [registre des commandes historiques](../testing/evidence/L4-I8/Commands_Actually_Executed.json), la [matrice SPEC-10 bornée](../testing/evidence/L4-I8/SPEC10_Final_Matrix.json), la [comparaison de réutilisation historique](../testing/evidence/L4-I8/Historical_Reuse_Comparison.json) et l’[index d’archives figé](../testing/evidence/L4-I8/Archive_Index.json) restent les pièces de livraison au SHA `70ff3e53…`. Leurs empreintes décrivent cette livraison antérieure, sans mise à jour des archives pour les documents de clôture.

G-I3…G-I8 et la contribution Architecture différée/I8 sont acceptées dans cette portée composite autorisée. F-L4 a été exécuté avec son verdict natif FAIL conservé ; la dispense clôt l’obligation d’un quatrième full, sans convertir ce verdict en PASS.

## 4. Campagnes full historiques inchangées

Le [registre des campagnes](../testing/evidence/L4-I8/Campaigns.json) et les reçus sous [runs](../testing/evidence/L4-I8/runs/) conservent :

| Campagne | SHA testé | Verdict historique |
|---|---|---|
| FULL-preflight-01-INCONCLUSIVE | Aucun SHA applicatif testé | **INCONCLUSIVE**, zéro test ; refus du collecteur avant exécution, hors des quatre full démarrés |
| FULL-02-FAIL — premier full exécuté | `03c629d6af52474bda5e5ba56a4268264a8df024` | **FAIL 1619/0/3/0**, 2 191 attendus ; Architecture non atteinte |
| FULL-03-FAIL — deuxième full exécuté | `090b5327cd2a4e974bb8907c62c4e65b82a17e62` | **FAIL 2024/1/1/0**, 2 191 attendus ; Architecture non atteinte |
| FULL-04-FAIL — troisième et dernier full complet | `a3470df662f4552d05785ab3ec191951c4cc9d56` | **FAIL 2191/4/0/0** ; quatre assertions Architecture, 2 024 tests hors Architecture PASS |
| FULL-05-INTERRUPTED-BY-DISPENSATION — quatrième full démarré | `8a007768c23021153764473f6c6686042168df00` | **Interrompu par dispense humaine / INCONCLUSIVE comme preuve full** ; collecteur brut FAIL/INCOMPLETE, 1 046 tests rapportés sans failure/error/skip ; exclu de la qualification composite |

Il y a donc **quatre full démarrés, trois terminés FAIL, aucun full natif PASS**. Les corrections, rejeux ciblés PASS et échecs F-I8 restent attribués à leurs propres campagnes dans le rapport. Aucun PASS local historique n’est transformé en certification nouvelle et aucun rejeu supplémentaire n’est demandé par cette clôture.

## 5. Synthèse de l’audit indépendant final

Source de cette synthèse : constats de l’audit indépendant final transmis dans la conversation par le mandat humain, sur le dossier de livraison I8 `70ff3e53526d902cfba141cf11385e340138503d`. L’avis est **favorable**, sans **BLOCKER** ni **MAJOR**. Aucun auteur, commande de test ou rapport autonome supplémentaire n’est attribué à cet audit par le présent acte.

**AUD-FINAL-I8-01 — MINOR non bloquant, suivi maintenu :** placer la prévalidation pure des entrées de `expenses` et `visible` avant l’acquisition du quota READ. La clôture accepte cette réserve ; elle ne déclare ni correction ni nouvelle preuve de comportement. Ce point est à reprendre dans le cadrage des consultations L5, sans réouverture d’I8.

Les constats de ressources `AUD-I8-01`, d’équivalence/dispense `AUD-I8-02` et de couverture `AUD-I8-03` gardent les attributions du rapport I8. L’avis favorable ne lève pas les limites de qualification composite, de lease, de volume, de disponibilité ou d’environnement.

## 6. Réserves explicitement acceptées

1. **Qualification composite.** Deux contributions PASS à deux SHAs, équivalence productive bornée et dispense explicite ; aucun full natif PASS, aucune certification SPEC-10 globale.
2. **Dimensionnement et lease 180 secondes non homologués en production.** Le calcul READ `164,24 s < 180 s` et les observations T09/T10/T11 restent des preuves locales dans les hypothèses et variantes qualifiées. Le défaut L2 historique de 30 secondes n’est pas modifié. Les omissions arbitraires, transports non résolus et charges hors hypothèse restent soumis aux mécanismes conservateurs L2. [Mesures](../testing/evidence/L4-I8/Resource_Observations.json), [capacité observée](../testing/evidence/L4-I8/Server_Capacity_Observation.json).
3. **AUD-FINAL-I8-01 maintenu**, MINOR non bloquant, sans correction dans cette mission.
4. **I1/I2/I3–I7 et recovery conservés sans réouverture.** Les quatre réserves de l’[acte I1](L4_I1_Acceptance_Decision.md) restent applicables, notamment mémoire/volumes, attributions des preuves et préservation du poisoning tardif. [I2](L4_I2_Execution_Report.md#clôture-définitive-du-3-novembre-2026) reste CLOSED — LOCAL BOUNDED PASS et `I2-QUAL-01 RESOLVED` ; ses limites et campagnes antérieures sont inchangées. Les [preuves locales I3–I7](L4_I3_I7_Execution_Report.md) et la [résolution recovery](L4_Recovery_Audit_Resolution.md) conservent leurs limites : hypothèses L2 d’administration/enrôlement/suppression, aucune protection exhaustive contre une corruption administrative arbitraire, aucune preuve nouvelle des branches UNAVAILABLE non rejouées localement. Leurs reports de gates sont traités par I8 dans la portée composite ci-dessus, sans réattribution de leurs anciens PASS.
5. **Réserves historiques L2/L3 inchangées.** [Clôture L2](L2_Documentary_Closure.md), [clôture L3](L3_Identity_Closure.md) et [correctif waiterPid](L3_I2_WaiterPid_Fix_Report.md) restent les autorités de leurs décisions, disponibilité, FAIL/INCONCLUSIVE et limites ; les PASS I8, dont `i5_r05`/`i5_r12`, ne les lèvent pas rétroactivement.
6. **Volumes, disponibilité et environnements bornés.** Les EUI internes sont fournies par des appelants de confiance. Les grands manifestes installés administrativement prouvent le lecteur ; la chaîne réelle WRITE → Discovery/Consumption → READ est démontrée séparément. Deux autorités PostgreSQL distinctes sur un serveur de fixture ne prouvent pas la résilience de deux hébergements de production. Aucun SLO global, volume maximal, disponibilité générale ou coût froid n’est homologué.

## 7. Obligations différées et conditions d’entrée de L5

| Lot | Obligations maintenues |
|---|---|
| **L5** | HTTP/OIDC et AuthN réelle, consentement Link et fournisseur réellement qualifiés, sessions, admission/idempotence/rétention, enveloppes/codes/polling, curseurs et plafonds publics, sécurité et parcours Bruno ; CurrentInvitation et parcours Invitation complets ; suivi `AUD-FINAL-I8-01` |
| **L6** | Reconstruction administrative READ complète et exclusive, maintenance/reset/rejeu, instrumentation et protocoles de mesure ratifiés, charge et volumes, stockage/WAL/histoire longue, performance/SLO et disponibilité sur environnements déclarés |
| **L7** | Convergence physique et retrait exhaustif V2, rationalisation Maven/compositions/runtimes après clôture fonctionnelle et qualification de L6, analyse de validité des preuves et certification du code unifié selon le Master Plan |

Le prérequis d’acceptation interne L4 pour le cadrage L5 est satisfait par cette décision. **L5 peut entrer en cadrage après publication et vérification du présent acte**, avec les acceptations et réserves L2/L3/L4 reprises explicitement. Avant implémentation L5, établir et faire approuver son propre plan : périmètre et contrats HTTP, secrets, consentement/fournisseur, sessions, idempotence/rétention, limites publiques et CurrentInvitation ; matrice de preuves et frontières de vérification selon la politique Reactor, WEB primaire et consommateurs effectivement affectés. La qualification réelle du fournisseur est nécessaire avant acceptation des parcours Link.

Cette clôture ne constitue pas un GO d’implémentation L5, une réduction de ses preuves, ni une autorisation de production. Aucun nouveau full L4 n’est une condition d’entrée. Les obligations L5/L6/L7 et le jalon de certification global restent dus ; **SPEC-10 n’est pas globalement certifiée**.

## 8. Contrôles documentaires et publication

État initial : HEAD local et distant `70ff3e53526d902cfba141cf11385e340138503d`, branche `feat/v3-architecture`, worktree courant propre sans modification préexistante ; cinq autres worktrees inventoriés et préservés.

Frontière déclarée avant écriture : **slices NONE**, impact documentaire uniquement. Traversée inattendue : **NONE**. Gate globale Architecture **NOT REQUIRED / NOT RUN** ; full reactor **NOT REQUIRED / NOT RUN** pour cette mission. Aucun Maven, PostgreSQL, Docker ou rejeu de tests. Les preuves historiques, manifestes, SPEC et verdicts de campagnes sont inchangés.

Commandes de contrôle employées : `pwd`, `git status --short`, `git status --porcelain=v1 --untracked-files=all`, `git branch --show-current`, `git rev-parse HEAD`, `git remote -v`, `git worktree list`, `git log -5 --oneline`, `git ls-remote origin refs/heads/feat/v3-architecture`, lectures `rg`/`sed`/`cat` et Python pour lecture des reçus et contrôle des liens, SHAs et contributions, puis `git diff --check` et revue du diff.

Publication documentaire demandée sur `feat/v3-architecture` : `git add` des seuls cinq documents de clôture, `git commit`, `git push origin feat/v3-architecture` sans force, puis `git rev-parse HEAD`, `git ls-remote origin refs/heads/feat/v3-architecture`, `git rev-list --left-right --count HEAD...origin/feat/v3-architecture` et `git status --short --branch`. Le SHA publié et le résultat effectif de synchronisation sont communiqués après push dans le rapport de livraison, sans SHA autoréférent inventé dans cet acte.
