# Pocoma V3 — L4 — Cadrage détaillé et plan d’implémentation READ

> **GO humain L4/I1 accordé le 2 novembre 2026 ; exécution autorisée.**
> Le [rapport d’exécution I1](L4_I1_Execution_Report.md) et son
> [manifeste de preuves](L4_I1_Proof_Manifest.json) portent le code, les tests,
> les résultats stricts des gates et les réserves de livraison. Les mentions
> antérieures « aucun GO » et « commandes futures » ci-dessous sont historiques.
> Le périmètre I2–I8 reste inchangé ; aucune clôture humaine définitive I1 ou
> acceptation indépendante du code n’est déduite de ce GO.


**Date du cadrage :** 1er novembre 2026.

**Branche et HEAD examinés :** feat/v3-architecture — e11d3372bad9ff88e30d4645b46fe6211829c001.

**Statut consolidé au 2 novembre 2026 : READY FOR I1 IMPLEMENTATION REVIEW.**

Référence de consolidation : `e9729ae20531f688a7eb16a2642c0f3e7b82fece`, locale et distante vérifiées identiques par fetch ; worktree initial propre, quatre autres worktrees examinés et préservés. Les constats historiques ci-dessous restent attribués au HEAD initial `e11d3372…`. L’avis indépendant **GO WITH RESERVATIONS L4/I1** et les dix amendements sont transmis par le mandat humain ; son rapport original n’est pas fourni dans le dépôt sous un nom identifiable. Cette consolidation vérifie les affirmations dans les sources, sans revendiquer un nouvel audit indépendant. Le [plan autonome I1](L4_I1_Implementation_Plan.md) fixe le contrat à soumettre à la revue et au GO humain distinct.

**Aucune autorisation d’implémentation L4/I1.**

> Publication documentaire demandée après remise de ce rapport dans la conversation.
> Le corps conserve les constats et propositions du cadrage READ-ONLY ; les liens
> sont rendus relatifs au dépôt pour leur consultation hors du poste d’origine.
> Les mentions « cette mission », les commandes exécutées et l’absence de fichiers
> modifiés décrivent la mission initiale d’analyse, avant cette publication.
> L’ajout de ce seul rapport ne prononce ni acceptation du plan, ni GO I1, ni
> certification ou levée de réserve L2/L3/L4. Aucun fichier applicatif, migration,
> test, configuration, POM, script ou document normatif n’est modifié.

**Amendement humain du 1er novembre 2026 — stabilité structurelle L4–L6.**
Le plan publié au commit `5482a6a6` est amendé dans ce même document : la
rationalisation des modules Maven et des runtimes est reportée **après la clôture
fonctionnelle et la qualification de L6, idéalement en première étape de L7**.
Les décisions D1–D4 du §5 précisent ce report. Les constats de code et le bilan
de la mission initiale restent attribués à leur référence d’examen ci-dessus ;
ils ne constituent pas une nouvelle vérification applicative.
Cet amendement documentaire ne vaut ni acceptation globale du plan, ni GO L4/I1.
Cette phrase décrit l’état du 1er novembre ; la consolidation du 2 novembre ci-dessus intègre l’avis transmis et prépare la revue I1 avant décision humaine distincte.

## 1. Synthèse exécutive

**Le plan audité est consolidé pour la revue d’implémentation I1. Les réserves de préparation/recovery restent en I2 et celles d’activation en I8 ; aucune ne force I2 avant I1. Aucun démarrage d’implémentation n’est autorisé par cette mission.**

L’approche A est conservée : modules Maven actuels, classification logique Fondation/Branchement, réutilisation conditionnelle des preuves. Aucune extraction Maven ni nouvelle certification L2 n’est nécessaire au cadrage.

**L4 doit livrer les matérialisations READ dans l’organisation technique existante,
sans anticiper la rationalisation structurelle post-L6.** Le découpage Maven, les
runtimes et leurs frontières d’exécution sont conservés par défaut pendant L4,
L5 et L6. Les branchements indispensables restent dus, notamment l’évolution
contrôlée L2 en I2 et l’activation fonctionnelle en I8.

La cible de rationalisation distingue trois familles fonctionnelles de workers :
**traitement WRITE, matérialisation des résultats READ, matérialisation métier READ**.
Elles réutilisent les mécanismes Consumption/Discovery L2 ; elles ne prescrivent
ni trois modules Maven ni trois processus, et leur convergence définitive n’est
pas un prérequis de L4. Le nombre final de modules et de processus sera déterminé
par le chantier post-L6, sur inventaire et mesures.

La cible comporte **six participants READ pour huit projections logiques livrées en L4** :

- MaterializePotVersion : PotVersion, SubobjectVersion et CurrentPot ;
- MaterializePotBalance ;
- MaterializePotVisibility : CurrentUserPotList et sa garde PotVisibilityState ;
- MaterializeCurrentBinding ;
- MaterializeCommandResult ;
- MaterializeIdentityRequestResult.

CurrentInvitation, neuvième projection logique, reste à L5. Son besoin d’informations internes dans PotVersion doit cependant être anticipé conformément au contrat existant.

Trois constats structurants ressortent de l’examen :

1. **VERIFIED — F02 confirmé statiquement.** Le store historique accepte effectivement une republication divergente sans comparer son contenu ; un test caractérise explicitement ce comportement.
2. **VERIFIED — préparation actuellement enveloppée dans une transaction de l’autorité des effets.** Pour READ, cette forme ne permet pas d’enchaîner directement les snapshots WRITE et READ requis sans adapter explicitement la frontière L2.
3. **VERIFIED — runtime encore fermé aux participants READ techniques.** Les définitions métier existent, mais le Worker actif accepte Command, IdentityRequest et deux probes READ. Ajouter les matérialisations réelles nécessite une évolution contrôlée de l’assemblage et de ses gardes.

Je recommande **huit incréments**, pour isoler ces risques :

| Incrément | Objet |
|---|---|
| I1 | Stockage immuable READ minimal et preuve d’atomicité avec L2 |
| I2 | Frontière L2 de préparation et d’observation interautorités |
| I3 | PotVersion, SubobjectVersion, CurrentPot |
| I4 | PotBalance |
| I5 | Visibility et navigation interne |
| I6 | CurrentBinding |
| I7 | Résultats terminaux |
| I8 | Activation dans les runtimes existants, lectures internes, convergence fonctionnelle READ et qualification L4 |

Cette séquence évite que l’I1 réalise implicitement toutes les projections.
**Les objectifs, livrables fonctionnels et l’ordre I1–I8 restent inchangés.**
La convergence fonctionnelle I8 désigne le fonctionnement effectif des projections
et lectures L4, pas le regroupement définitif des familles de workers. Aucun
développement nécessaire au fonctionnement réel de L4 n’est reporté à L7.

## 2. État Git et sources examinées

### État effectivement examiné

**VERIFIED**, au début et à la fin de l’examen :

| Élément | Constat |
|---|---|
| Répertoire | /Users/julien.guezennec/Dev/projects/pocoma |
| Branche | feat/v3-architecture |
| HEAD | e11d3372bad9ff88e30d4645b46fe6211829c001 |
| Modifications suivies ou non suivies | Aucune signalée par git status --porcelain=v1 --untracked-files=all |
| Diff | Vide |
| Référence locale origin/feat/v3-architecture | Divergence 0 / 0 avec HEAD |
| Vérification réseau de la tête distante | Non effectuée ; la comparaison porte sur la référence locale |
| Autres worktrees | Inventoriés, sans intervention |

Les quatre autres worktrees recensés sont restés intacts : audit de vérification, dette WA6, documentation L2 et clôture I1/préparation I2.

**Commandes exécutées :** lectures avec cat, nl, sed, rg, wc, head, tail ; commandes Git de statut, références, inventaire et diff ; scripts Python ponctuels avec -B, uniquement pour lire les POM et l’inventaire JSON historique.

**Aucun Maven, PostgreSQL, bootstrap, collecteur de campagne, modification de fichier, commit ou push.** verify.py, y compris son mode dry-run, n’a pas été exécuté.

### Traçabilité de la consolidation A01–A10

| Amendement | Intégration contractuelle | Incrément |
|---|---|---|
| A01 | §6.2 : capacité PreparationReads, durée de vie, résolution irréversible | I2 |
| A02 | §6.5 : table de décision recovery checkpoint/slot/preuves | I2 puis participants I3–I7 |
| A03 | §6.2 : conservation des snapshots Command, verrous Identity et READ_COMMITTED final | I2 |
| A04 | §7.3 et plan I1 §§2–4 : clé, huit contenus, égalité, codec, SQL et insertion | I1 |
| A05 | §9/I1 et plan I1 §§5–8 : vrais slots/owner READ et oracles physiques | I1 |
| A06 | §9/I5 : ensemble visible à version égale, garde, readiness et tombstones | I5 |
| A07 | §8 : configuration et ressources des six participants, deux racines | I8 |
| A08 | §7.2 : composition typée autonome, manifeste canonique et UUID PostgreSQL | I3 ; ordre/clé réutilisés dès I1 |
| A09 | §11.5 : attribution nominative, dirty, O20/O21, limites de r05/r12 | Chaque réutilisation ; ressources I8 |
| A10 | §9/I7 : issues Command et Identity réellement définies, requesterEui et égalité | I7 |

### Sources

Le référentiel demandé a été consulté : AGENTS, canon, plan directeur, SPEC-01/02/03/04/05/06/07/09/10, clôtures L2/L3, consolidation I6/I7, politique Reactor, rapport d’optimisation et registre des campagnes.

L’examen complémentaire a porté notamment sur :

- les contrats L1-B et L1-C et les compléments L1-F/F2 ;
- le plan L2 et la consolidation normative R1–R10 ;
- le plan Identity L3 et les reports vers L4 ;
- ConsumptionEngine, ConsumptionParticipant, les transactions Spring et les ports techniques ;
- les sources Discovery, compositions, settings et préflights V3 ;
- les stores READ, matérialisateurs historiques, migrations et tests correspondants ;
- les dépendances directes des POM concernés ;
- l’inventaire historique des durées Surefire.

Le rapport autonome L4/P0 évoqué dans le mandat n’a pas été retrouvé dans les fichiers suivis sous un nom identifiable. Ses conclusions sont donc **DOCUMENTED_ONLY — transmises par le mandat** ; F02 est, lui, corroboré directement par le code.

### Convention probatoire

| Catégorie | Sens dans ce rapport |
|---|---|
| VERIFIED | Constat statique directement vérifié dans le checkout |
| DOCUMENTED_ONLY | Contrat ou résultat rapporté par un document ; campagne non rejouée |
| INFERRED | Proposition d’architecture ou conséquence déduite de l’examen |
| UNVERIFIED | Comportement, coût ou preuve restant à produire |

Une présence de test est VERIFIED ; son succès au HEAD ne l’est pas.

## 3. Contrats normatifs L4 et hiérarchie

Les SPEC fixent les invariants ; le canon porte les décisions transversales ; le plan directeur fixe le périmètre et la progression ; les contrats techniques détaillent leur réalisation. Les rapports historiques attestent leurs campagnes, sans modifier seuls les critères normatifs. Cette hiérarchie est explicitée dans le [plan directeur, lignes 106–118](V3_Implementation_Master_Plan.md#1-autorités-vision-et-périmètre).

Les limites L4/L5/L6 sont fixées dans le [tableau directeur, lignes 157–160](V3_Implementation_Master_Plan.md#3-tableau-directeur).

| Contrat | Conséquence obligatoire |
|---|---|
| Composition complète à chaque V | Aucune chaîne de deltas, dépendance à V−1 ou attente d’une Consumption précédente |
| Sous-versions immuables | Chargement groupé des seules sous-versions absentes ; conflit divergent explicite |
| CurrentPot monotone | Pointe la plus haute PotVersion matérialisée, sans attendre Balance ou Visibility |
| Balance indépendante | Calcul complet depuis WRITE@V pour chaque version active |
| Terminal D | PotVersion(D) présente ; aucune Balance(D) ; histoire antérieure conservée |
| Navigation | Créateur et rattachements actifs ; garde collective par Pot ; aucune autorité de sécurité |
| Binding | EUI@version exacte ; monotonie par EUI ; aucun annuaire inverse |
| Résultats | Demande et résultat durable WRITE ; requesterEui capturée à l’admission ; aucun PENDING/EXPIRED |
| Transactions | Effets READ et suppression du slot dans une unité physique READ possédée par L2 |
| Consultation | READ exclusivement ; version figée ; autorisation avant indisponibilité spécifique |
| Recovery | Observation seule ; aucune relance ou cession déduite d’un commit inconnu |

Références principales : [SPEC-04, §§3–8, lignes 26–93](SPEC-04_Read_Projections.md#3-potversion-currentpot-et-sous-objets), [suppression et ordre des contrôles, lignes 101–109](SPEC-04_Read_Projections.md#10-deletepot-et-version-terminale), [SPEC-05, §§3–5, lignes 27–62](SPEC-05_Consumption_Discovery.md#3-slots-et-preuve-de-complétion).

### Discordances identifiées

| Point | Traitement proposé |
|---|---|
| Anciens mécanismes ProjectionTask/BalanceTask/VisibilityTask | Supplantés explicitement par SPEC-04 §8 et SPEC-10 §1 ; aucune reprise architecturale |
| En-têtes anciens « non implémenté » des SPEC | Décrivent leur rédaction conceptuelle ; les clôtures récentes déterminent les réalisations attestées |
| Anciennes mentions de pointeur CurrentPot portant toute la composition | Supplantées : CurrentPot pointe PotVersion, qui porte les références complètes |
| Politique §23 évoquant V23 comme état historique | Ne constitue pas l’inventaire courant des migrations ; aucune baseline TRUSTED n’en découle |
| Registre r05/r12 qualifiés contre réserves historiques encore conservées | Distinguer qualification nominative récente, anciennes observations et réserves générales ; aucune levée globale déduite |

Le registre actuel indique QUALIFIED, active:false pour i5_r05/r12, avec une résolution nominative. La politique conserve une rédaction antérieure et la clôture L3 maintient les limites générales. Voir [registre, lignes 12–38](../../scripts/testing/verification-campaigns.json#L12), [contre-audit nominatif, §2](L2_I5_Counter_Audit_and_Definitive_Closure_Report.md#2-diagnostic-et-reproduction-physique-du-cas-discovery--renewal) et [réserves L3, lignes 84–104](L3_Identity_Closure.md#périmètre-et-réserves-maintenues).

**Conséquence :** ces scénarios restent exécutables et inclus dans les gates canoniques. Le présent plan ne réattribue aucun ancien FAIL et ne prononce aucune nouvelle clôture de réserve.

### Cohérence de l’amendement structurel avec les autorités supérieures

Le [plan directeur §6](V3_Implementation_Master_Plan.md#6-convergence-finale-v2--pocoma--chantier-l7-proposé)
place déjà la rationalisation Maven et le retrait exhaustif des compositions et
runtimes obsolètes en L7, après L2–L6 acceptés (§3). Son §5 maintient les ancres
existantes jusqu’à cette rationalisation ; son §7 laisse le nombre de runtimes à
cadrer. Le [canon §15](V3_Canon_and_Roadmap.md#15-livraison-finale-et-convergence)
porte une obligation finale de convergence, sans imposer cette refonte en L4.
Le retrait progressif des usages inutiles pendant L2–L6 mentionné par le plan
directeur reste compatible avec la désactivation ciblée des anciens chemins dans
les compositions V3 ; il ne crée pas une obligation de fusion ou de suppression
physique des modules et runtimes avant la qualification de L6.

Les [SPEC-04 §8](SPEC-04_Read_Projections.md#8-consumptions-des-événements) et
[SPEC-05 §1](SPEC-05_Consumption_Discovery.md#1-identité-et-composition) distinguent
Consumptions indépendantes et Workers physiques, sans nombre de services imposé.
D1–D4 préservent ces contrats, les frontières PRIMARY/READ et la politique Reactor.
**Aucune modification d’un document supérieur n’est identifiée comme nécessaire
pour cet amendement ; aucun n’est modifié.** Toute contradiction ultérieure devra
être signalée et arbitrée dans son autorité, sans modification silencieuse.

## 4. Cartographie du code actuel — REUSE / ADAPT / REPLACE

### Inventaire des neuf projections

L’inventaire normatif figure dans [SPEC-04, lignes 12–24](SPEC-04_Read_Projections.md#2-inventaire-des-neuf-projections-logiques).

| Projection logique | État actuel VERIFIED | Source et données autoritatives | Propriétaire cible / autorité | Livraison |
|---|---|---|---|---|
| PotVersion | Pas de matérialiseur métier V3 identifié ; projections historiques génériques | POT_EVENT ; composition complète WRITE@V, droits internes et références locales | MaterializePotVersion / READ | I3 |
| CurrentPot | Aucun pointeur V3 conforme livré identifié | PotVersion nouvellement insérée | Même participant / READ | I3 |
| SubobjectVersion | Pas de store V3 conforme identifié | Contenus exacts des huit composantes historiques | Même participant / READ | Socle I1, utilisation I3 |
| PotBalance | Calculateur V3 et source financière L1 présents ; pas de matérialisation V3 complète | POT_EVENT ; faits financiers groupés WRITE@V | MaterializePotBalance / READ | I4 |
| CurrentUserPotList + PotVisibilityState | Pas de réalisation V3 conforme identifiée | POT_EVENT ; créateur, Headers et rattachements Shareholders à V | MaterializePotVisibility / READ | I5 |
| CurrentBinding | Réalisation historique à base de BindingFact/BindingId/BindingRevision | IDENTITY_EVENT EUI ; état v3_eui_version exact | MaterializeCurrentBinding / READ | I6 |
| CommandResult | Store et service historiques avec propriétaire EUI ; formes et source V2 | COMMAND_COMPLETION_EVENT ; demande + résultat V3 | MaterializeCommandResult / READ | I7 |
| IdentityRequestResult | Parcours historique RegistrationResult ; périmètre plus étroit | IDENTITY_COMPLETION_EVENT ; demande + résultat des trois opérations | MaterializeIdentityRequestResult / READ | I7 |
| CurrentInvitation | Contrat normatif ; réalisation complète non identifiée | INVITATION_EVENT ; invitation historique exacte | MaterializeInvitation / READ | L5 |

### Réemploi détaillé

| Ensemble | Décision | Justification |
|---|---|---|
| ConsumptionEngine, slots, claims, ownership, cession | REUSE, adaptation bornée I2 | Moteur unique ; aucune réécriture des mécanismes techniques |
| DiscoveryEngine, fenêtre dense, admission READ | REUSE | Déjà séparés entre lecture source et transaction d’enrôlement |
| JdbcEventDiscoveryReferences | REUSE pour les quatre familles L4 supportées | POT, COMMAND_COMPLETION, IDENTITY, IDENTITY_COMPLETION réellement mappées |
| JdbcPotHistoryAdapter | REUSE/ADAPT | Source exacte/groupée existante ; contrat léger de composition encore manquant |
| PotHistoryPort.financialAtVersion | REUSE derrière un port READ dédié | Source complète avec zéros, sans Balance précédente |
| PotBalanceCalculator V3 | REUSE | Calcul pur, exact, indépendant ; D retourne volontairement vide |
| JdbcIdentityAuthority.at(id,V) | REUSE/ADAPT derrière une lecture historique étroite | Accès exact présent ; ne pas appeler current() |
| Store générique JdbcProjectionStoreAdapter | REPLACE sur le chemin V3 | F02 ; transactions et modèle génériques non suffisants pour les invariants cibles |
| CurrentBinding historique | ADAPT l’algorithme, REPLACE le modèle/source | Monotonie et divergence utiles ; BindingId/Fact ne représentent pas EUI@V |
| CommandResult historique | ADAPT les principes, remplacer source et formes V3 | Comparaison immuable et propriétaire EUI utiles ; APPLIED/FAILED ne doivent pas être repris aveuglément |
| RegistrationResult historique | REPLACE pour V3 | Doit couvrir Registration, Link et Unlink |
| ProjectionTask, locators et finaliseurs historiques | Désactiver dans les compositions V3 concernées | Consommations directes ; aucune suppression physique générale imposée en L4 |
| Suppression exhaustive et rationalisation des modules/runtimes historiques | DEFER après qualification de L6, idéalement début L7 | Chantier dédié D3, hors objectif L4 |

Repères vérifiés :

- [source financière L1, PotHistoryPort, lignes 83–93](../../app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/port/persistence/v3/PotHistoryPort.java#L83) ;
- [calculateur pur V3, lignes 23–49](../../app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/v3/PotBalanceCalculator.java#L23) ;
- [lecture Identity exacte, lignes 138–157](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/identity/JdbcIdentityAuthority.java#L138) ;
- [sources événementielles existantes, lignes 24–54](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/consumption/JdbcEventDiscoveryReferences.java#L24).

### F02

Le comportement est directement visible dans [publishInTransaction, lignes 55–68](../../app/infra-persistence-projection-jdbc/src/main/java/com/kartaguez/pocoma/infra/persistence/projection/jdbc/JdbcProjectionStoreAdapter.java#L55).

Le test [firstPublishedProjectionWinsWithoutContentComparison, lignes 167–177](../../app/infra-persistence-read-jdbc/src/test/java/com/kartaguez/pocoma/infra/persistence/read/jdbc/JdbcProjectionStoreAdapterPostgresTest.java#L167) publie deux contenus différents et attend ALREADY_EXISTS.

**Ce test prouve le contrat historique, pas l’immutabilité V3.** Il doit rester attribué à son chemin historique ; les nouveaux stores V3 nécessitent leurs propres contre-exemples.

## 5. Architecture cible READ

Les décisions D1–D4 ci-dessous consignent l’arbitrage humain complémentaire.
Les choix d’hébergement, de classes et de ports restent **INFERRED — propositions
à auditer**, sous ces décisions ; l’arbitrage ne vaut pas acceptation de ces choix.

### D1 — Stabilité structurelle pendant L4, L5 et L6

Pendant les trois lots, conserver par défaut le découpage Maven existant, les
runtimes existants et leurs frontières d’exécution. Privilégier le branchement
et l’activation des composants nécessaires ; éviter déplacements, fusions et
renommages de modules. Ne créer aucun module Maven ni runtime dédié à un
participant si une structure existante peut l’héberger correctement.

Les frontières transactionnelles et les responsabilités L2 ne doivent pas être
modifiées pour simplifier l’organisation physique. Cette stabilité n’interdit
pas les adaptations indispensables au fonctionnement de L4–L6, notamment
l’évolution contrôlée de préparation/recovery prévue en I2. Toute exception
structurelle nécessaire doit être explicitement justifiée, bornée et qualifiée :
besoin fonctionnel, insuffisance de l’hébergement existant, périmètre exact,
contrats/preuves affectés et gates à rejouer doivent être déclarés avant réalisation.

### D2 — Trois familles fonctionnelles de workers, cible après L6

| Famille cible | Participants et responsabilités |
|---|---|
| 1 — Traitement WRITE | Commands et IdentityRequests ; exécution des opérations métier ; écritures et résultats durables PRIMARY |
| 2 — Matérialisation des résultats READ | CommandResult et IdentityRequestResult ; consommation des événements de complétion ; écriture des résultats consultables côté READ |
| 3 — Matérialisation métier READ | PotVersion et SubobjectVersion, CurrentPot, PotBalance, PotVisibility et CurrentUserPotList, CurrentBinding ; CurrentInvitation lorsque livrée en L5 ; autres projections métier explicitement prévues au périmètre V3 |

Les trois familles réutilisent le protocole technique Consumption L2, **sans
duplication des moteurs Discovery/Consumption**. Chaque Consumption garde ses
propres identifiants, slots, capacités et transactions, ainsi que ses checkpoints
lorsque son contrat le prévoit. Les familles de workers ne sont pas les familles
canoniques de sources événementielles du catalogue (§8).

| Niveau à distinguer | Sens et conséquence |
|---|---|
| Participants métier | Transformations/Consumptions ; six participants READ L4, indépendamment de leur hébergement |
| Modules Maven | Unités de code et de dépendances ; plusieurs participants peuvent résider dans un module existant |
| Compositions Spring | Assemblages explicites de composants, owners et ressources ; branchement adapté aux participants activés |
| Runtimes déployables | Points d’entrée exécutables ; leur frontière reste stable par défaut pendant L4–L6 |
| Instances physiques de workers | Exécutions concrètes avec capacité et partitions configurées ; leur nombre ne se déduit ni des participants ni des modules |

Cette cible ne signifie **ni nécessairement trois modules Maven, ni trois processus
dès L4**. Le nombre final de modules et de processus sera déterminé lors du
chantier D3 selon les contraintes réelles d’isolation, capacité, exploitation et
performance. Elle n’impose aucune convergence structurelle avant la clôture
fonctionnelle et la qualification de L6.

### D3 — Chantier dédié de rationalisation après L6

Ouvrir ce chantier après la clôture fonctionnelle et la qualification de L6,
idéalement en première étape de L7, dans le chantier de convergence du plan
directeur. Son inventaire devra examiner :

- les fusions éventuelles de modules Maven fonctionnellement proches, sans préjuger des regroupements concrets ;
- la simplification des compositions Spring et la convergence vers les trois familles ;
- le retrait des compositions et runtimes historiques devenus inutiles ;
- la réduction des dépendances et configurations redondantes ;
- le maintien des frontières PRIMARY/READ et les besoins de capacité et d’isolation de chaque famille ;
- l’impact sur les gates Maven et sur les preuves acquises.

La simplification devra être mesurée et démontrée par comparaison avant/après
des dépendances, assemblages, configurations et contraintes d’exploitation et de
performance pertinentes. Le seul nombre de modules ne suffit pas à la justifier.
Aucune fusion ni topologie finale n’est décidée par le présent plan.

### D4 — Préservation des preuves L2/L3 et des invariants

Le report vise notamment à éviter de fragiliser les qualifications déjà obtenues
sur L2 et L3, avec leurs limites et réserves. La rationalisation future devra :

- identifier les contrats, hypothèses et preuves affectés ;
- distinguer refactoring physique et modification comportementale ;
- préserver les invariants transactionnels et déterminer précisément les gates à rejouer ;
- justifier la validité des preuves conservées et exclure toute réutilisation automatique de preuves devenues invalides.

**Aucune requalification anticipée de L2 ou L3 n’est demandée par ce seul amendement
documentaire.** Les requalifications réellement dues aux adaptations futures I2/I8
restent celles du §11 ; D4 ne les supprime ni ne les déclenche maintenant.

### Responsabilités et modules

Conserver les modules existants :

| Module | Responsabilités proposées |
|---|---|
| domain-projection | Valeurs READ pures : composition, références, sous-versions, balances matérialisées |
| port-projection | Ports typés de sources et stores Pot READ, sans infrastructure |
| engine-consume-projection-task | Les trois participants Pot V3, dans un package distinct du chemin Task historique |
| engine-materialize-current-binding | Participant CurrentBinding V3 |
| engine-materialize-command-result | Participant résultat Command V3 |
| engine-materialize-registration-result | Participant IdentityRequestResult couvrant les trois opérations |
| engine-read-pot | Lecture interne courante/historique et détails groupés |
| engine-read-current-binding | Lecture interne de son Binding |
| engine-read-command-result, engine-read-registration-result | Lectures internes des résultats V3 |
| infra-persistence-primary-jpa | Sources SQL WRITE historiques et complétions |
| infra-persistence-read-jdbc | Stores et lectures SQL READ V3 |
| composition-runtime-spring | Assemblage explicite, propriétaires transactionnels, préflights |
| Runtimes existants | Hébergement des Workers selon leurs frontières d’exécution ; activation ciblée, sans nouveau runtime dédié si un hôte existant convient |

Le nom historique d’un module ne réintroduit pas son ancien protocole. Ce tableau
affecte des responsabilités aux modules existants ; il ne préjuge d’aucune fusion
post-L6. Les déplacements, fusions et renommages structurels relèvent de D3.

### Classes proposées

Six classes participantes suffisent :

- MaterializePotVersion
- MaterializePotBalance
- MaterializePotVisibility
- MaterializeCurrentBinding
- MaterializeCommandResult
- MaterializeIdentityRequestResult

Chaque classe possède un Prepared privé ou imbriqué. Aucun moteur générique de projection, graphe ou registre de transformations n’est proposé.

Ports Pot :

- PotVersionSource : composition complète à V et contenus groupés sélectionnés ;
- SubobjectVersionStore : présence groupée, lecture exacte, insertion identique ou conflit ;
- PotVersionStore : composition immuable, contrôle de complétude et avance CurrentPot ;
- PotBalanceSource et PotBalanceStore ;
- PotVisibilitySource et PotVisibilityStore.

Ports Identity/résultats :

- source historique Binding et store CurrentBinding ;
- source de complétion Command et store CommandResult ;
- source de complétion Identity et store IdentityRequestResult.

Les stores gardent des contrats distincts : une insertion immuable, un pointeur monotone et un différentiel collectif n’ont pas les mêmes invariants.

### Lectures internes L4

L4 doit livrer des services internes capables de :

1. figer une version courante W ou sélectionner une version historique V ;
2. vérifier les faits READ nécessaires à l’autorisation ;
3. assembler uniquement les composants de cette version ;
4. retourner un résultat typé : disponible, absent/non autorisé, dépendances manquantes ou indisponibilité technique ;
5. charger un lot ordonné de détails d’Expenses appartenant exactement au manifeste demandé ;
6. parcourir la navigation par keyset ;
7. lire son Binding et ses résultats avec les règles de propriété correspondantes.

Leur entrée d’identité est un **contexte interne déjà authentifié par le futur adaptateur**, sans mécanisme d’authentification fictif.

L5 raccordera AuthN, contrôleurs, enveloppes et statuts HTTP, consentement et parcours externes. Les contrats internes doivent déjà permettre l’ordre anti-oracle, sans revendiquer une qualification HTTP.

## 6. Participants, transactions et recovery

### 6.1 Contraintes réelles de L2

**VERIFIED :**

- prepare(UUID) retourne Optional<Prepared> ;
- Optional.empty() déclenche une observation, pas une complétion ;
- Prepared.finalizeIn() reçoit une capacité SlotCompletion ;
- complete() appelle d’abord requireCompletionReady(), puis supprime le slot ;
- l’appel est unique et lié au thread/callback ;
- l’Engine exige ensuite la fermeture de publication ;
- l’observation combine preuve participante et présence du slot.

Voir [ConsumptionParticipant, lignes 7–18](../../app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/consumption/v3/ConsumptionParticipant.java#L7), [finalisation et observation Engine, lignes 549–658](../../app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/consumption/v3/ConsumptionEngine.java#L549) et [complétion à usage unique, lignes 661–695](../../app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/consumption/v3/ConsumptionEngine.java#L661).

Pour READ, « fermer la publication » signifie fermer l’unité aux écritures après la complétion. **Aucun événement READ ni suffixe dense supplémentaire n’est créé.**

### 6.2 Finding transactionnel à résoudre en I2

Aujourd’hui, l’Engine appelle :

    effectsTransactions.read(context -> participant.prepare(id))

SpringAttemptTransactions refuse une transaction ambiante et lie son contexte à une datasource physique précise. Les lecteurs historiques L1 exigent, eux, une transaction sur WRITE.

Références : [Engine, lignes 554–561](../../app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/consumption/v3/ConsumptionEngine.java#L554), [refus d’owner ambiant, lignes 170–177](../../app/infra-tx-spring/src/main/java/com/kartaguez/pocoma/infra/tx/spring/v3/SpringAttemptTransactions.java#L170), [contrat historique L1-B, lignes 17–23](L1_B_Persistence_Contract.md#read-boundary).

**Il serait incorrect de simplement injecter un owner WRITE et de l’appeler depuis la préparation READ actuelle.**

#### A01/A03 — Proposition bornée soumise à arbitrage I2

Faire évoluer **le contrat L2 existant**, sans second protocole :

- appeler la préparation participante hors transaction ambiante ;
- lui fournir une capacité explicite de lectures préparatoires possédée par L2 ;
- cette capacité expose deux scopes nommés : source et effets ;
- chaque scope ouvre et résout un snapshot local read-only REPEATABLE_READ ;
- deux scopes ne peuvent jamais être imbriqués ;
- source = WRITE pour une matérialisation READ ; effets = READ ;
- pour les participants WRITE existants, source et effets désignent leur autorité WRITE.

Forme proposée, à figer à l’audit d’I2 :

    interface PreparationReads {
        <T> T source(Function<RecoveryReadContext, T> read);
        <T> T effects(Function<RecoveryReadContext, T> read);
    }

prepare(id, reads) peut alors orchestrer les lectures séquentielles nécessaires. L2 conserve les owners physiques, la résolution et la classification des issues.

Contrat obligatoire à figer avant code I2 : capacité créée et contrôlée **exclusivement par L2**, valable pour **une seule préparation**, son thread et son callback propriétaires. Aucun participant ne construit ni prolonge cette capacité. Scopes source/effets strictement séquentiels, jamais imbriqués/concurrents ; fermeture et **résolution physique qualifiée** de chaque scope avant le suivant. Les contextes étrangers/expirés sont refusés ; les résultats retournés sont complètement détachés (ni connexion, curseur, stream JDBC, proxy JPA paresseux ou entité attachée).

L2 détient un état d’invalidation ou d’incertitude **irréversible** pour la préparation : scope mal résolu, erreur invalidante ou UNKNOWN interdit tout scope suivant et toute finalisation, même si le participant intercepte l’exception. Un simple retour Java ou timeout ne prouve pas la fin physique. Interruption, annulation, capacité réservée, renouvellements et drainage conservent leurs garanties L2 ; aucun ticket libéré sur une issue non résolue. Ce mécanisme n’est pas implémenté en I1 ni par le présent amendement.

**Préservation WRITE (A03)** : la préparation Command reste un **snapshot unique WRITE** pour la demande, l’autorité Identity et les faits Pot ; elle ne doit pas être éclatée entre appels source/effets susceptibles d’observer des instants différents. IdentityRequest continue de décider la transition **sous les verrous de finalisation EUI en ordre canonique**, comme `ExecuteIdentityRequest`, et non dans un snapshot préparatoire substitué aux verrous. Les transactions finales existantes restent **READ_COMMITTED**, afin de voir l’autorité committée après attente. Aucun changement du protocole métier WRITE (intention, CAS, résultat, publication, fencing, budget). I2 doit lister preuves invalidées/conservées/à produire : préparation, scopes/owners et recovery affectés à requalifier ; transitions et routage inchangés réutilisables seulement après comparaison ; non-régression Command/Identity réelle obligatoire. Sources : [contrat L1-C](L1_C_Transaction_Contract.md), [ExecuteIdentityRequest](../../app/engine-consume-registration/src/main/java/com/kartaguez/pocoma/engine/consume/registration/v3/ExecuteIdentityRequest.java), [SpringAttemptTransactions](../../app/infra-tx-spring/src/main/java/com/kartaguez/pocoma/infra/tx/spring/v3/SpringAttemptTransactions.java).

Pour recovery, préparer hors transaction READ les **références source immuables** nécessaires, puis effectuer l’observation effective dans le snapshot READ L2. Une observation préparée, non persistée, peut capturer ces références ; elle ne capture aucun verdict de commit.

Cette extension doit :

- préserver les quatre RecoveryObservation ;
- transmettre à l’observation la présence du slot déjà lue par l’Engine, ou fournir un accès technique explicite équivalent ;
- conserver l’observation des effets et du checkpoint dans le même snapshot READ ;
- n’ajouter ni reçu SQL, ni cache d’événements faisant autorité, ni état UNKNOWN durable.

**FOUNDATION REQUALIFICATION : OUI pour I2.** Les preuves de préparation/recovery affectées doivent être rejouées. Cet ajustement ne peut pas être présenté comme un simple branchement métier.

### 6.3 Démonstration de la frontière physique cible

| Phase | Autorité | Contenu | Résolution avant phase suivante |
|---|---|---|---|
| Source initiale | WRITE, snapshot read-only | Événement exact, composition ou résultat durable | Oui |
| Présence des sous-versions, si nécessaire | READ, snapshot read-only | Recherche groupée des clés présentes | Oui |
| Contenus manquants | WRITE, snapshot read-only | Sous-versions exactes uniquement | Oui |
| Calcul | Aucune transaction | Composition détachée, fractions, ensembles | Sans effet durable |
| Finalisation | READ, transaction writable L2 | Slot, validations, effets, complétion, fermeture | Commit/rollback/UNKNOWN L2 |
| Recovery | Références source préparées puis snapshot READ | Checkpoint, slot et preuves métier | Observation seule |

Les données historiques déjà committées à V ne changent pas entre les snapshots WRITE. Les bornes activeTo, susceptibles de se fermer ultérieurement, servent à sélectionner les révisions ; elles ne doivent pas entrer dans le contenu immuable projeté.

Aucune transaction ne contient simultanément des effets WRITE et READ. Aucune connexion WRITE n’est conservée pendant la finalisation READ.

### 6.4 Protocole commun des participants

Pour les six participants :

1. consumableId = **eventId natif**, jamais PotId, version ou requestId substitué ;
2. valider famille, type de sujet, identité, position publiée et liens source ;
3. préparer les faits exacts, sans effet ;
4. L2 protège le slot et valide token/échéance ;
5. écrire ou constater les effets conformes ;
6. requireCompletionReady() vérifie le préfixe durable réel ;
7. appeler complete() une fois ;
8. fermer l’unité READ aux writers ;
9. laisser L2 résoudre physiquement ;
10. après UNKNOWN, observer sans rejouer.

Une projection déjà présente ne justifie pas Optional.empty() : le slot courant peut encore devoir être finalisé. Un événement obsolète pour une projection monotone produit normalement un Prepared sans modification métier, mais avec complétion du slot.

### 6.5 Détail par participant

| Participant | Préparation et validations | Effets READ et readiness | Recovery / conflits |
|---|---|---|---|
| PotVersion | POT_EVENT(P,V), composition complète ; présence groupée des sous-versions ; chargement exact des absentes ; validation identités, types, versions, droits, ordre et suppressions | Insérer/comparer sous-versions ; insérer/comparer PotVersion ; avancer CurrentPot si V supérieure ; vérifier toutes les références avant complétion | Version exacte et contenus disponibles ; CurrentPot≥V ne suffit pas à prouver PotVersion(V) ; divergence technique |
| PotBalance | POT_EVENT ; Header exact ; si actif, source financière complète et calcul pur ; vérifier ensemble complet des Shareholders et fractions | Insérer/comparer balance complète ; à D, vérifier absence de Balance(D), puis compléter normalement | À V active, contenu exact requis ; à D, preuve dérivée checkpoint+absence du slot, avec état terminal source et absence de balance |
| PotVisibility | Ensemble complet visible à V, sans Expenses | Initialiser/verrouiller garde ; si V supérieure, comparer visibles courantes, appliquer créations/réactivations/tombstones, avancer garde ; compléter | Une garde supérieure ne prouve pas l’exécution du slot ancien ; preuve technique dérivée obligatoire |
| CurrentBinding | IDENTITY_EVENT EXTERNAL_USER_IDENTITY ; lire EUI exacte à sa version ; vérifier EUI interne et couple externe | Avance conditionnelle ; même version identique = idempotent ; même version divergente = anomalie ; inférieur = sans recul | État courant supérieur admissible, mais complétion de l’événement établie par checkpoint+slot ; jamais par version seule |
| CommandResult | Complétion → requestId ; lire demande ET terminal durable ; vérifier provenance et correspondance | Insérer/comparer résultat terminal avec requesterEui ; aucune attente des projections Pot | Comparer tous les champs contractuels ; PENDING ou résultat manquant derrière une complétion publiée = incohérence source |
| IdentityRequestResult | Même protocole ; vérifier opération et cible ; Registration/Link/Unlink | Insérer/comparer résultat ; EUI cible/version distinctes de requesterEui ; aucun token | Même propriété EUI initiatrice après Link/Unlink ; divergence de propriétaire ou résultat = anomalie |

#### Preuve de complétion non-burnable

Pour un événement de position s, partition p, Consumption C :

- checkpoint (C,p) ≥ s ;
- événement réellement éligible à C ;
- slot (C,eventId) absent ;
- invariants métier requis cohérents.

Ces conditions doivent être examinées ensemble. **Un pointeur monotone plus récent n’atteste pas que tous les événements antérieurs ont été exécutés.**

Un slot encore présent peut coexister légitimement avec un contenu immuable déjà identique ou une projection mutable plus récente. L’observation doit alors rester NOT_COMPLETED, sans transformer cette coexistence en fausse corruption.

#### A02 — Table de décision recovery à réaliser en I2

L’observation technique combine **checkpoint de la Consumption C concernée**, partition calculée/famille/position source éligible, slot natif `(C,eventId)` et **preuves métier de publication**. Checkpoint, slot et effets sont lus dans **un même snapshot READ cohérent** L2 ; les références historiques immuables WRITE sont préparées séparément avant ce snapshot, jamais lues via une connexion WRITE cachée dans la transaction READ. Aucun reçu durable supplémentaire ni état UNKNOWN SQL. Les quatre résultats L2 restent `COMPLETED`, `NOT_COMPLETED`, `INCONSISTENT`, `UNAVAILABLE`. Les lignes nominales ci-dessous supposent une source valide et un enrôlement cohérent ; incohérence démontrée de routage/checkpoint/source → INCONSISTENT, impossibilité de les observer → UNAVAILABLE avant tout verdict de complétion.

| Cas dans le snapshot READ | Résultat / condition |
|---|---|
| Slot présent, effets requis absents | NOT_COMPLETED, même si checkpoint couvre ; la disparition du slot n’est pas présumée |
| Slot présent, effets identiques préexistants | NOT_COMPLETED : déduplication légitime ; ni fausse complétion ni corruption par simple présence de l’effet |
| Slot absent, checkpoint C/p couvrant la position éligible et preuves métier conformes | COMPLETED ; l’effet seul ou le checkpoint seul ne suffit pas |
| Slot absent, sans couverture C/p établie | INCONSISTENT pour un événement prétendu enrôlé ; absence ne prouve aucune complétion. Si la couverture ne peut être lue, UNAVAILABLE |
| Slot absent, couverture établie mais preuve métier obligatoire manquante | INCONSISTENT, aucune reconstruction/réparation pendant observation |
| Effet divergent/corrompu | INCONSISTENT, slot présent ou absent ; ne pas convertir en succès ni réécrire |
| Observation techniquement indisponible (READ ou référence source) | UNAVAILABLE, conserver l’incertitude ; aucun retry/cession spéculatif |
| Balance(D) | Source exacte prouve D, absence de Balance(D) conforme ; slot présent → NOT_COMPLETED ; absent + checkpoint couvrant → COMPLETED ; Balance(D) présente → INCONSISTENT |
| Visibility obsolète V<G | Garde/état courant cohérents ; slot présent → NOT_COMPLETED ; absent + couverture → COMPLETED sans exiger l’ensemble ancien encore courant ; garde récente seule insuffisante |
| CurrentBinding dépassé V<W | Même règle technique ; vérifier cohérence de l’état W, ne pas exiger qu’il reproduise le contenu de V, ne pas déduire l’exécution de V du seul W |

PotVersion et balances actives exigent toujours leurs preuves **exactes à V**, même si un pointeur supérieur existe. Pour Visibility/Binding à version égale, comparer le contenu complet attendu ; divergence → INCONSISTENT. La préparation des références n’emporte jamais de verdict de commit ; NOT_COMPLETED après UNKNOWN ne prouve pas un rollback. Aucune mutation, nouvelle tentative ou cession à partir de cette seule observation.

**Écart technique actuel assumé** : Engine combine aujourd’hui une observation participante et le slot (`COMPLETED`+slot présent → INCONSISTENT), sans transmettre le checkpoint/slot au participant. I2 doit rendre disponibles ces faits techniques dans son observation bornée, comme proposé §6.2. Ne pas imposer ce recovery étendu à la fixture technique I1 ni présenter le comportement cible comme déjà livré.

### 6.6 Échecs, retry et incertitude

| Situation | Traitement |
|---|---|
| Réinsertion identique | Succès idempotent dans la finalisation |
| Événement inférieur pour CurrentPot/Visibility/Binding | Aucun recul ; complétion normale |
| Insertion concurrente identique | Attendre la résolution SQL, relire et comparer |
| Contenu divergent | Exception technique explicite ; rollback intégral |
| Version historique attendue absente | Anomalie source ; aucun fallback vers le courant |
| Timeout/deadlock avec rollback confirmé | Cause technique comptable selon classifier L2 |
| Claim perdu | Aucun effet, aucune cession tardive |
| Crash ou transport d’issue inconnue | Aucun incrément supposé |
| N échecs techniques constatés | Slot QUARANTINED conservé |
| Commit inconnu | Observation seule ; NOT_COMPLETED ne prouve pas le rollback |
| Observation incohérente | INCONSISTENT, diagnostic, aucune réparation automatique |
| Observation impossible | UNAVAILABLE, conservation de l’incertitude |

Le propriétaire unique du budget demeure L2, conformément à [R5 et R9, lignes 58–114](L2_R1_R10_Normative_Consolidation.md#5-r5--classification-et-cession-unique-option-a).

## 7. Modèle SQL et migrations

Tout nom SQL ci-dessous est **proposé**, non livré.

### 7.1 Schémas actuels

**VERIFIED :**

- chaîne READ historique db/read-store/migration, V1–V14 ;
- store générique projection_root, projection_artifact, projection_failure ;
- current_external_identity_binding historique ;
- structures historiques de résultats, avec adaptateurs situés dans le module PRIMARY ;
- schéma READ technique V3 distinct, db/v3-consumption-read/migration/V1__consumption_foundation.sql.

Les anciens index Pot ne constituent pas une base V3 actuelle : la migration READ V8 supprime plusieurs structures créées auparavant.

Les quatre tables L2 sont :

- consumption_routing_contract ;
- partition_ownership ;
- discovery_checkpoint ;
- consumption_slot.

Elles restent inchangées.

### 7.2 Matrice cible

| Projection | Tables proposées | Réemploi / adaptation | Invariants SQL et applicatifs | Index et accès |
|---|---|---|---|---|
| SubobjectVersion | read_subobject_version | Nouveau store | PK (pot_id,kind,object_id,local_version) ; versions positives ; contenu immuable | PK pour exact/batch |
| PotVersion | read_pot_version | Nouveau store | PK (pot_id,pot_version) ; composition complète canonique ; droits internes ; aucune valeur détaillée dupliquée | PK historique |
| CurrentPot | read_current_pot | Nouveau | PK Pot ; FK vers PotVersion exacte ; avance uniquement supérieure | Lookup Pot |
| PotBalance | read_pot_balance | Calculateur L1 réutilisé | PK (pot_id,V) ; contenu complet immuable ; fractions exactes ; aucune ligne D | PK exact |
| Visibility | read_pot_visibility_state, read_user_pot | Nouveau | Garde unique Pot ; PK (user_id,pot_id) ; tombstones conservées ; writers sous garde | Partiel (pot_id,user_id) WHERE visible ; partiel (user_id,pot_id) WHERE visible |
| CurrentBinding | read_current_binding | Algorithme historique adapté | Clé EUI exacte ; version positive ; user/token tous deux présents ou absents ; monotonie | PK (issuer,subject) ; unicité EUI interne si stockée |
| CommandResult | read_command_result | Principes historiques adaptés | PK requestId ; requester obligatoire ; formes COMPLETED/REJECTED cohérentes ; immuable | PK requestId |
| IdentityRequestResult | read_identity_request_result | Remplacement Registration-only | PK requestId ; opération/cible/version cohérentes ; requester immuable ; aucun token | PK requestId |
| CurrentInvitation | Aucune table L4 | DEFER L5 | Contrat réservé, sans activation | À cadrer L5 |

Cette proposition aboutit à neuf tables métier pour les huit projections L4, parce que la navigation comporte une garde et des entrées. **Le nombre résulte des responsabilités, pas du nombre normatif de projections.**

#### A08 — Représentation canonique PotVersion (I3, sans livraison I1)

Contrat interne proposé : racine JSONB `schema_version=1`, colonnes `(pot_id,pot_version)` et composition **typée complète à V**. Références exactes : Pot `{headerLocalVersion,detailsLocalVersion}` avec objet=Pot ; Shareholders `{shareholderId,headerLocalVersion,detailsLocalVersion,weightLocalVersion,deleted,isFree}` ; Expenses `{expenseId,headerLocalVersion,detailsLocalVersion,financialsLocalVersion,deleted}`. Le Header local détermine l’état supprimé, sans effacer les références historiques ; les listes recensent les objets existant à V et leurs états, jamais des objets futurs. Le manifeste Expense ne se contente pas d’une version globale ou d’un seul identifiant : il contient les trois versions locales nécessaires aux détails exacts. Aucun payload financier dupliqué dans la composition.

Tableaux triés par UUID PostgreSQL : comparaison des 16 octets **non signés**, ou chaînes UUID canoniques minuscules de largeur fixe ; **pas `UUID.compareTo` Java signé**. Références uniques par identité et kind, versions positives, appartenance au Pot et correspondance des métadonnées vérifiées. Formes inconnues/corrompues et références dupliquées refusées avant conversion en map. `authorizedUserIds` = créateur ∪ utilisateurs des Shareholders actifs à V ; `attachedActiveUserIds` = utilisateurs effectivement rattachés à ces Shareholders, **sans ajout automatique du créateur**. Deux ensembles internes distincts, chacun intentionnellement dédupliqué et trié ; un utilisateur multi-Shareholders apparaît une fois, sans supprimer de référence Shareholder. `isFree` correspond à l’absence de userId dans la sous-version exacte et ne l’expose pas au client.

Égalité métier : identité/version globale, composition entière, versions locales, états, indicateurs et ensembles internes ; indépendante de l’ordre des propriétés JSONB et des métadonnées eventId/tentative/horloge. Codec strict et comparaison typée, aucune égalité textuelle ou digest seul. Un autre schema_version est refusé tant qu’un contrat d’évolution n’est pas accepté. Reconstruction directe WRITE@V, sans dépendance à PotVersion(V−1). I3 vérifiera groupé l’existence/décodage de toutes ces sous-versions avant readiness et pointeur ; I1 ne crée aucune valeur ni table de manifeste. Source : [SPEC-04 §§3/12](SPEC-04_Read_Projections.md).

#### Représentation commune

Proposition :

- compositions et collections complètes en JSONB canonique dans une ligne racine ;
- sous-versions avec identité/type/version en colonnes et contenu typé encodé ;
- balances comme collection complète de fractions dont numérateur/dénominateur sont des chaînes entières ;
- tableaux ordonnés explicitement ;
- ensembles internes triés et dédupliqués ;
- schema_version explicite, sans sérialisation de noms de classes Java.

Les contraintes SQL assurent clés, nullité, types de premier niveau et formes simples. Les codecs et services assurent les invariants profonds.

Les références JSONB vers les sous-versions ne bénéficient pas d’une FK élémentaire. Le contrat impose donc :

- contrôle groupé de toutes les références dans la transaction finale ;
- absence de DELETE/UPDATE runtime sur les sous-versions ;
- tests de corruption et de complétude.

Aucune FK de Balance vers PotVersion : elle créerait une dépendance entre participants indépendants.

### 7.3 Immutabilité — sémantique obligatoire

**A04 — Contrat I1 figé pour revue** : clé exacte `(pot_id,kind,object_id,local_version)`, huit kinds seulement (`POT_HEADER`, `POT_DETAILS`, `SHAREHOLDER_HEADER`, `SHAREHOLDER_DETAILS`, `SHAREHOLDER_WEIGHT`, `EXPENSE_HEADER`, `EXPENSE_DETAILS`, `EXPENSE_FINANCIALS`). Les deux kinds Pot imposent object_id=pot_id, comme le [DDL source](../../app/infra-persistence-primary-jpa/src/main/resources/db/v3-pot/migration/V1__pot_components.sql). Identités et versions locales ne sont ni revision_id ni version globale. Le [plan I1 §3](L4_I1_Implementation_Plan.md#3-contrats-figés-i1-a04-partie-i1-de-a3) définit tous les champs contractuels, exclusions d’égalité, codecs stricts, signatures du port, batchs et erreurs ; §4 fixe colonnes/contraintes/droits/immutabilité et migration V2 cumulative. Ce contrat remplace les simples suggestions de ce cadrage pour I1, sans modifier les SPEC.

Ordre final : slot protégé par L2, puis sous-versions triées par Pot UUID PostgreSQL/kind à rang fixe/objet UUID PostgreSQL/version locale, puis en I3 seulement PotVersion et CurrentPot. INSERT ON CONFLICT DO NOTHING, **SELECT séparé à READ_COMMITTED** après résolution du conflit, décodage et comparaison complète du contenu réellement committé. Un CTE sur le snapshot précédent ne suffit pas. Les erreurs de validation/codec/divergence/SQL restent sous garde et poisoning même interceptées. Aucune mutation d’immuable.

| Cas | Sémantique |
|---|---|
| Première insertion | Insérer le contenu validé, dans l’unité READ appelante |
| Réinsertion identique | Accepter après comparaison du contenu contractuel complet |
| Réinsertion divergente | Exception technique nommée, jamais ALREADY_EXISTS silencieux |
| Deux insertions concurrentes | Unicité PostgreSQL ; perdant relit après résolution et compare |
| Gagnant rollback | L’autre insertion peut devenir première insertion |
| Version READ absente | La matérialiser directement depuis WRITE@V |
| Version WRITE absente derrière événement durable | Signaler une incohérence ; ne pas lire le courant |
| Arrivée hors ordre | Conserver les historiques ; pointeurs/gardes ne reculent pas |
| Reprise après crash | Recharge complète ; aucune proposition ancienne réutilisée |
| Commit incertain | Observer ; ne pas relancer spéculativement |
| Corruption détectée | Anomalie explicite ; pas de normalisation réparatrice |
| Suppression terminale | Nouvelle version Header ; aucune suppression d’histoire |

Mécanisme PostgreSQL recommandé :

1. INSERT ... ON CONFLICT DO NOTHING ;
2. lire le contenu effectif ;
3. comparer sa représentation métier canonique ;
4. lever une anomalie en cas de différence.

Pas de DO UPDATE sur les valeurs immuables. Un digest peut accélérer ou diagnostiquer, **mais ne remplace pas seul l’égalité de contenu**.

La comparaison exclut les données variables de traitement : heure de projection, compteur de tentative, activeFrom/activeTo WRITE, revision_id et writer_xid source. Une sous-version réutilisée par plusieurs PotVersion ne doit pas devenir divergente à cause d’un autre eventId consommateur.

#### Limite du chargement sélectif

Une clé déjà présente n’entraîne pas une republication fictive ni le rechargement systématique de son contenu WRITE. Sa réutilisation repose sur :

- une première insertion validée ;
- un stockage effectivement immuable ;
- un décodage strict du contenu présent ;
- la comparaison complète lorsqu’un contenu candidat est réellement republié.

Cela ne constitue pas un audit universel contre une falsification administrative cohérente. La confiance envers les administrateurs PostgreSQL reste une réserve explicite ; la qualification de maintenance exhaustive relève de L6.

### 7.4 Migrations et bootstrap

Créer une **nouvelle localisation métier READ V3**, sans réécrire les migrations historiques ni la V1 L2.

Ordre proposé :

1. validation administrative de la cible ;
2. quatre tables L2 via leur bootstrap existant, uniquement sur cible neuve ;
3. sous-versions ;
4. PotVersion puis CurrentPot et sa FK ;
5. Balance ;
6. garde et entrées Visibility ;
7. CurrentBinding ;
8. résultats ;
9. contraintes, protections d’immutabilité, index ;
10. grants par rôle ;
11. installation/vérification administrative des contrats de routage.

Les objets 4–8 arrivent dans leurs incréments, pas tous en I1.

Pour I1 : localisation métier `db/v3-read-business/migration`, **V2__subobject_version.sql** proposée (V2 libre dans la chaîne V3 READ à la référence, à recontrôler avant code), même `flyway_schema_history` et schéma non-public que V1 L2, localisations cumulatives. Sur extension peuplée avec historique V1 valide, Flyway n’exécute que V2 ; pas de baselineOnMigrate, second historique ni reset. La chaîne historique READ V1–V14 n’est pas chargée dans cette chaîne V3. [Parcours détaillés I1 §4](L4_I1_Implementation_Plan.md#4-migration-postgresql-current-v3).

**Attention VERIFIED :** la migration L2 V1 refuse une cible non vide. Elle ne doit pas être rejouée sur une autorité READ déjà peuplée lors de l’ajout des tables métier.

Deux parcours futurs doivent être testés séparément :

- installation V3 neuve ;
- ajout des migrations métier sur une autorité L2 valide déjà initialisée.

Aucune migration de données V2 n’est requise par défaut. Aucune baseline historique TRUSTED n’est identifiée.

### 7.5 Rôles

| Rôle | Droits |
|---|---|
| Administrateur migration | DDL et bootstrap explicites |
| Contrôle L2 | Droits techniques exacts déjà prévus sur ownership/checkpoints/slots |
| Writer métier READ | SELECT/INSERT immuables ; UPDATE limité aux projections mutables ; pas de DDL/TRUNCATE |
| Lecteur de consultation READ | SELECT nécessaire ; aucun accès WRITE |
| Accès source du Worker | Lecture historique WRITE via pool/owner explicite ; jamais exposé aux endpoints |
| Administration quarantaine | Action ciblée existante ; aucune purge |

Les préflights vérifient les droits effectifs : héritage, PUBLIC, appartenance au rôle propriétaire, droits de colonnes et exposition auxiliaire des secrets.

**Aucune modification physique du protocole L2 n’est proposée.** Si un changement des quatre tables, du routage ou des droits techniques devient nécessaire, arrêt de l’incrément et redéclaration de la requalification.

## 8. Intégration au moteur L2 et aux Workers

### Catalogue

Les neuf définitions existent déjà dans [V3ConsumptionCatalogConfiguration, lignes 18–55](../../app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/v3/V3ConsumptionCatalogConfiguration.java#L18).

| Participant | UUID existant à conserver | Famille source canonique |
|---|---|---|
| PotVersion | 3dec1c8b-6e3e-57e1-82aa-aa0b5f297a4a | POT_EVENT |
| PotBalance | 190a93c6-b8af-5ac7-8001-72e2ec53c2df | POT_EVENT |
| PotVisibility | b4d04cce-b5da-5d1b-9698-5ff9af9a46fa | POT_EVENT |
| CurrentBinding | 7b8d2838-bf7a-5117-868a-e5c14b4c0221 | IDENTITY_EVENT |
| CommandResult | 63664303-d33e-5d42-8cce-c4dae8023311 | COMMAND_COMPLETION_EVENT |
| IdentityRequestResult | 1d8ff246-358f-5ddd-a7f5-416efcfd7445 | IDENTITY_COMPLETION_EVENT |
| Invitation, différée | 924cd9e3-435d-5804-9d1e-c6aecadb9ab4 | INVITATION_EVENT |

Les correspondances nominatives doivent être figées et testées ; éviter de les maintenir seulement par position dans une liste.

Routage : famille canonique + eventId natif, convention sha256-v1, quatre partitions du pilote. Trois consommateurs POT_EVENT partagent la convention, mais possèdent checkpoints, slots et capacité propres.

### Discovery

Réutiliser V3ConsumptionDiscoveryComposition.events :

- lecture source WRITE bornée ;
- enrôlement/checkpoint atomiques READ ;
- filtre EXTERNAL_USER_IDENTITY existant ;
- aucune source INVITATION activée en L4 ;
- aucune Discovery commune aux trois participants Pot.

Voir [factory événementielle, lignes 57–86](../../app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/v3/V3ConsumptionDiscoveryComposition.java#L57).

### Limites du pilote actif

**VERIFIED :**

- boucle d’assemblage limitée à IdentityRequest, Command et deux probes ;
- settings refusant les autres noms ;
- garde de types et de singletons ;
- quatre pools et owners identifiés ;
- deux racines actuellement autorisées : Worker Command et monolithe.

Voir [assemblage actif, lignes 189–268](../../app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/v3/V3ConsumptionWorkerConfiguration.java#L189) et [mapping fermé des settings, lignes 364–375](../../app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/v3/V3ConsumptionWorkerSettings.java#L364).

### Hébergement proposé

Cet hébergement concerne le branchement fonctionnel L4 dans les structures
existantes, sous D1. Il ne réalise pas la convergence définitive des trois
familles D2 et ne modifie pas les frontières d’exécution pour les aligner sur
ces familles.

- conserver le Worker L2 et ses deux boucles ;
- utiliser le runtime PROJECTION existant comme racine READ V3 ;
- partager la factory explicite avec le monolithe ;
- conserver le runtime WRITE existant ;
- conserver les autres runtimes existants et leurs frontières d’exécution, sans fusion, renommage ni retrait physique anticipé ;
- aucun nouveau module, runtime, processus métier ou conteneur dédié requis par participant si un hébergement existant convient ;
- rendre les anciens composants Task/Binding/Result inactifs dans le profil V3 concerné, sans imposer leur suppression structurelle avant la qualification de L6.

Les participants sont d’abord prouvés par factories inactives. **L’activation
fonctionnelle des six participants est maintenue en I8**, avec les adaptations
indispensables des settings, factories, permissions, préflights et allowlists
dans les compositions existantes. Les gardes d’assemblage doivent accueillir ces
participants sans affaiblir les invariants d’isolation L2 ni déplacer les frontières
transactionnelles. Une exception structurelle indispensable relève du traitement
explicite D1, pas d’une rationalisation implicite.

I8 doit livrer un fonctionnement READ réel et qualifié ; il ne peut se limiter à
des participants inactifs en attendant L7. L’inventaire, les regroupements et la
topologie définitive des trois familles restent au chantier D3 après L6.

**Architecture REQUIRED en I8**, car les racines, allowlists et règles d’activation évoluent.

### Pools et ressources

Conserver comme hypothèse initiale les quatre pools :

- write-business ;
- write-control ;
- read-business ;
- read-control.

Les lectures source utilisent explicitement l’owner WRITE ; les effets utilisent l’owner READ. Les opérations sont successives.

La composition actuelle impose notamment des réserves M_W+2 business et M_W+6 control ; elles ne deviennent pas automatiquement une preuve de capacité pour six nouveaux participants. Voir [composition lifecycle, lignes 83–117](../../app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/v3/V3ConsumptionLifecycleComposition.java#L83).

**A07 — Dossier d’activation I8 obligatoire** : pour chacun des six participants du tableau catalogue, figer nom↔UUID↔famille, filtre de sujet, autorité READ, settings/allowlists, candidatures de partitions et capacités locales/globales (`M_C`, `M_W`, batch, softCap), intervalles et budgets complets de leases/claims/renewals, timeouts et hypothèses de pauses/latence. Les quatre partitions du contrat durable restent la référence, pas un paramètre modifiable implicitement. Comptabiliser tous les participants WRITE et READ coexistants et toutes les instances dans la capacité PostgreSQL globale, connexions admin/observation et réserves serveur incluses ; distinguer maximum configuré, connexions physiques mesurées et disponibilité sous saturation.

Les réservations business/control doivent laisser progresser Discovery/renewals pendant les préparations et finalisations longues, et rester protégées en UNKNOWN/DRAINING. Les quatre pools sont une **hypothèse initiale à qualifier**, pas une topologie déjà démontrée pour six participants. Les budgets `M_W+2`/`M_W+6`, paramètres et leases du pilote ne se transfèrent pas automatiquement. Préflights de cibles, routage, droits effectifs, identités datasource/owner et exposition auxiliaire ; exclusion vérifiée des chemins Task/Binding/Result legacy et des probes dans les compositions métier V3. Tests de démarrage/refus, régime chargé, arrêt d’admissions, drainage, arrêt qualifié ou CRASH explicite, puis reprise sans reset/fencing, dans **le runtime PROJECTION existant et le monolithe**. Aucun nouveau processus imposé par participant.

I8 devra prouver :

- renouvellements malgré préparations longues ;
- capacité conservée lors des issues inconnues ;
- absence de réservation libérée prématurément ;
- drainage et restart ;
- absence de famine sous les hypothèses admises.

Une éventuelle séparation supplémentaire du rôle source dans un pool dédié exige une justification de permissions ou de contention ; elle n’est pas imposée préventivement.

Les observations reprennent les phases L2. Diagnostics sans token ni payload sensible ; instrumentation transversale et SLO complets restent L6.

## 9. Séquencement détaillé I1… I8

Tous les livrables suivants sont futurs, **UNVERIFIED**.

**Amendement de séquencement : aucun changement fonctionnel I1–I8.** Les mentions
« créer » visent les classes, ports, stores, migrations et services nécessaires,
dans les modules existants ; elles ne demandent pas la création d’un module ou
d’un runtime. I1 reste borné au stockage/atomicité ; I2 conserve l’évolution L2
contrôlée ; I3–I7 livrent les matérialisations prévues ; I8 les active et qualifie
les lectures internes. Les préconditions, tests et gates futurs restent dus.
Seule la portée structurelle est précisée par D1–D4 : aucune rationalisation
Maven/runtimes n’est un préalable ou un critère de sortie I1–I8.

### I1 — Stockage immuable READ minimal

**Objectif :** établir le contrat physique de SubobjectVersion et son atomicité avec un slot L2, sans source métier WRITE.

- **Préconditions :** plan audité, schéma des neuf projections validé sur le papier, GO I1 distinct.
- **Créer :** valeur pure de clé/contenu, SubobjectVersionStore, adaptateur JDBC, migration de cette seule table, fixture participante technique.
- **Modules :** domain-projection, port-projection, infra-persistence-read-jdbc ; tests d’assemblage bornés dans la fermeture PROJECTION.
- **Contrat :** première insertion, duplication identique, divergence, batch présence/lecture, transaction appelante obligatoire.
- **Migrations :** table, PK, checks, droits, protection d’immutabilité.
- **Tests (A05) :** insertion identique/divergente, courses identiques/divergentes, conflit SQL suivi de lecture du contenu réellement committé ; erreur interceptée/poisoning, mauvais owner physique, contexte étranger/expiré ; rollback après tentative DELETE du slot, contrainte différée rejetée au vrai commit, ACK de commit perdu/UNKNOWN, fermeture incorrecte et tentative de finalisation sans readiness effective. [Protocole et matrice I1 §§5–6](L4_I1_Implementation_Plan.md#5-preuve-transactionnelle-avec-l2-réel-a05) : vraie transaction READ possédée par L2, slot acquis/protégé/supprimé par ses stores réels, témoins PG PID/XID des effets et DELETE dans la même unité, contre-oracle deux connexions. Une transaction simulée ou créée par le store ne constitue pas cette preuve.
- **Gates :** G-I1 du §11.
- **Preuves réutilisables :** fondation physique I1/I2 L2 sous hypothèses inchangées ; aucune preuve métier.
- **Acceptation :** zéro divergence silencieuse ; zéro effet orphelin ; quatre tables L2 inchangées.
- **Risques :** F02, comparaison non canonique, transaction créée dans le store.
- **Livrables :** contrat, migration minimale, store, preuves et rapport bornés.
- **Passage :** audit I1 et décision humaine ; pas d’activation de participant réel.

### I2 — Préparation et recovery interautorités dans L2

**Objectif :** résoudre le finding du §6.2 dans l’unique Engine.

Cette adaptation indispensable reste dans L4 ; D1 n’interdit pas l’évolution
contrôlée des contrats L2 nécessaire aux transactions READ. Elle ne doit servir
ni à déplacer les responsabilités L2 ni à simplifier l’organisation des modules.

- **Préconditions :** arbitrage explicite de la frontière ; I1 accepté.
- **Adapter :** contrat de préparation, orchestration Engine, préparation des observations, compositions et participants WRITE existants.
- **Créer seulement si retenu :** capacité PreparationReads et observation préparée non persistée.
- **Modules :** engine-consumption, port-transaction si nécessaire, infra-tx-spring, composition, participants Command/Identity.
- **Migrations :** aucune modification des quatre tables techniques.
- **Tests :** scopes séquentiels WRITE/READ, refus d’imbrication, contexte étranger/expiré, erreurs préparatoires, UNKNOWN, conservation de capacité, observation sans mutation, non-régression Command/Identity.
- **Gates :** G-I2 ; FOUNDATION REQUALIFICATION oui.
- **Capitalisation :** routage/Discovery inchangés ; preuves de préparation/recovery affectées non substituables.
- **Acceptation :** aucune lecture WRITE pendant la transaction finale READ ; owner L2 unique ; mêmes règles de cession et d’incertitude.
- **Risques :** propagation Spring, erreur de classification, relâchement de capacité.
- **Livrables :** API exacte, flux physiques, tests défavorables, matrice de requalification.
- **Passage :** audit spécifique de cette évolution L2 avant I3.

### I3 — PotVersion, SubobjectVersion et CurrentPot

**Objectif :** matérialiser une composition autonome de toute V, y compris D.

- **Préconditions :** I1/I2 acceptés ; source historique exacte disponible.
- **Créer :** MaterializePotVersion, PotVersionSource, PotVersionStore, adaptateurs source et READ.
- **Adapter :** accès SQL groupé L1 pour produire références et droits sans charger systématiquement les Financials.
- **Modules :** engine-consume-projection-task, port-projection, domain-projection, deux infrastructures.
- **Migrations :** PotVersion, CurrentPot, FK du pointeur.
- **Tests :** P01-A…K, contribution P01-L, sous-versions présentes/absentes, concurrence de publication divergente, V3 avant V1, mutation multi-cibles, D avant anciennes versions.
- **Gates :** G-I3.
- **Capitalisation :** lecteurs exacts L1 et techniques L2 inchangées, après comparaison des hypothèses.
- **Acceptation :** composition complète ; aucun V−1 ; CurrentPot=max ; toutes les sous-versions requises atomiques.
- **Risques :** métadonnées locales incomplètes, scan de payloads inutiles, contenu incluant activeTo.
- **Livrables :** participant inactif, migrations, preuves SQL/concurrence.
- **Passage :** comparaison indépendante des références et droits historiques.

PotVersion doit déjà conserver l’ensemble interne des utilisateurs **effectivement rattachés à des Shareholders actifs**, distinct de authorizedUserIds, conformément à SPEC-04 §12. Cela prépare le contrat L5 sans réaliser CurrentInvitation.

### I4 — PotBalance

**Objectif :** stocker une balance exacte complète pour chaque V active.

- **Préconditions :** I2 accepté ; source financière L1 validée.
- **Créer :** participant, source étroite et store Balance.
- **Réutiliser :** PotBalanceCalculator pur ; ne pas appeler ExecutePotCommand.
- **Modules :** modules Pot READ et infrastructures ; domaine Pot inchangé sauf défaut démontré.
- **Migration :** table Balance sans FK de dépendance vers PotVersion.
- **Tests :** P02-A…L ; DOM-03 discriminants ; grands entiers ; mutation non financière ; balance V avant V−1 ; D sans ligne ; V<D calculée après D.
- **Gates :** G-I4.
- **Capitalisation :** calcul pur et source financière L1 ; pas de transfert automatique de performance.
- **Acceptation :** toutes les clés Shareholder attendues, y compris zéros ; fractions normalisées ; anciennes balances inchangées.
- **Risques :** calcul partiel, poids courants, omission de zéros, confusion absence D/retard.
- **Livrables :** participant inactif, store, oracle rationnel indépendant.
- **Passage :** conservation exacte et invariants terminaux démontrés.

I4 peut être vérifié sans attendre que MaterializePotVersion ait traité le même événement.

### I5 — Visibility et navigation interne

**Objectif :** projection monotone par Pot et parcours keyset non autorisant.

- **Préconditions :** I2 ; source complète créateur/rattachements à V.
- **Créer :** participant, garde/store collectif et port de navigation.
- **Modules :** modules Pot READ et infrastructures.
- **Migrations :** garde, entrées et deux index partiels.
- **Tests :** P03-A…O ; première initialisation concurrente ; utilisateur multi-Shareholders ; tombstones ; D ; V ancienne après D ; pages stables et mutations entre pages.
- **Gates :** G-I5.
- **Capitalisation :** claims/transactions inchangés ; aucune preuve métier héritée.
- **Acceptation :** visible exact à la version maximale ; aucun scan des tombstones pour le différentiel ; absence de droits dérivés de la navigation.
- **Risques :** fantômes d’initialisation, désactivation erronée d’un utilisateur encore lié.
- **Livrables :** participant inactif, requêtes keyset et plans SQL.
- **Passage :** courses et absence de fuite interutilisateurs démontrées en interne.

**A06 — Contrat I5** : sous la garde collective **par Pot**, V=G exige la comparaison de **l’ensemble visible complet** avec l’ensemble préparé à V ; égalité = idempotence, divergence = anomalie technique et rollback, jamais un no-op silencieux. Pour V>G, readiness relit le résultat entier du différentiel (visibles attendus, désactivations/tombstones et garde G=V), avant complete. Les tombstones restent conservées et les entrées invisibles ne participent pas au scan différentiel ; V<G ne recule ni garde ni ligne et complète normalement le slot sous les conditions techniques. L’état récent ne prouve pas l’exécution de cet ancien slot (table §6.5).

Une ligne inchangée peut conserver un potVersion inférieur à celui de la garde. Elle ne doit pas servir d’indicateur global de fraîcheur.

### I6 — CurrentBinding

**Objectif :** projection EUI monotone à version exacte.

- **Préconditions :** L3 fermé avec ses réserves ; I2 accepté.
- **Créer/adapter :** modèle CurrentBinding V3, participant, source historique étroite, store et service interne de lecture propre.
- **Modules :** domain-user-identity, engine-materialize-current-binding, engine-read-current-binding, infrastructures.
- **Migration :** nouvelle table V3 ; aucune conversion BindingId→bindingToken.
- **Tests :** EUI V3 avant V1/V2 ; Link/Unlink/re-Link ; même version divergente ; POCOMA_USER ignoré ; cible E2 différente d’E1 ; minVersion de la bonne EUI ; absence de token dans diagnostics.
- **Gates :** G-I6.
- **Capitalisation :** histoire et publication L3 ; aucune qualification de session externe.
- **Acceptation :** couple (EUI,euiVersion) exact ; projection courante sans recul ; pas d’index User→EUI.
- **Risques :** mauvaise cible/version, accès au token tiers, source current() utilisée par erreur.
- **Livrables :** participant et lecture interne ; contribution I01-Q.
- **Passage :** comparaison de toute l’histoire EUI d’une fixture réelle et tests de propagation contrôlée.

CurrentBinding@V signifie ici « état projeté de cette EUI à sa version locale V », puis conservé tant qu’aucune version supérieure n’est matérialisée. **Il n’existe aucune version globale Identity ni relation avec la version Pot V.**

### I7 — CommandResult et IdentityRequestResult

**Objectif :** matérialiser les résultats terminaux indépendamment du métier.

- **Préconditions :** I2 ; demandes et terminaux WRITE réels.
- **Créer/adapter :** deux participants, deux sources de complétion, deux stores et lectures internes V3.
- **Modules :** matérialisation/lecture des résultats existants, infrastructures, contrats purs nécessaires.
- **Migrations :** deux tables terminales, contraintes de formes et immutabilité.
- **Tests :** succès, no-op, rejet, token invalide, Registration sans Binding initial, EUI commune à plusieurs sessions, EUI distinctes liées au même User, Link/Unlink/re-Link, courses et commits inconnus.
- **Gates :** G-I7.
- **Capitalisation :** provenance et terminalisation WRITE L1/L3 ; les anciens tests Result ne prouvent pas la source V3.
- **Acceptation :** requesterEui strictement recopiée depuis la demande durable ; mappings distincts par modèle décrits ci-dessous ; aucun FAILED technique/PENDING/EXPIRED inventé.
- **Risques :** lecture du seul événement, attribution au User courant, confusion cible/initiateur.
- **Livrables :** deux participants inactifs et preuves A10 internes.
- **Passage :** résultats accessibles en interne avant toutes les projections métier ; ownership inchangé après mutations Identity.

**A10 — Issues terminales exactes I7** : Command WRITE `CHANGED` ou `UNCHANGED` → READ `COMPLETED` avec resultingPotVersion exacte (nouvelle V ou V courante inchangée) ; `REJECTED` → READ `REJECTED` avec motif, sans resultingPotVersion. IdentityRequest WRITE, suivant [IdentityRequestStore.Result](../../app/engine-consume-registration/src/main/java/com/kartaguez/pocoma/engine/consume/registration/v3/IdentityRequestStore.java), n’a que **CHANGED et REJECTED** : CHANGED → READ COMPLETED avec EUI cible/targetVersion ; REJECTED → READ REJECTED avec motif, sans cible/version de succès. **Aucun UNCHANGED Identity artificiel.** La liste de tests « no-op » ci-dessus concerne Command ; Registration/Link/Unlink conservent leurs transitions réelles. Aucun FAILED technique parmi les résultats métier V3.

Lire demande ET terminal via requestId de complétion ; copier requesterEui `(issuer,subject)` de l’admission durable, jamais du User ou Binding courant. Comparer immuablement **tous les champs contractuels de chaque ligne** : requestId, opération, requesterEui, issue READ et issue WRITE conservée si le modèle la porte, resultingPotVersion pour Command, EUI cible/targetVersion pour Identity, motif de rejet, et toute provenance contractuelle effectivement stockée. Tokens, état d’autorité copié et métadonnées variables de tentative ne font pas partie du résultat. La forme et nullité de chaque variante sont contrôlées ; aucune réparation d’un résultat divergent. Sources : [SPEC-04 §7](SPEC-04_Read_Projections.md#7-résultats-terminaux), [PotCommandStore](../../app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/port/execution/v3/PotCommandStore.java).

### I8 — Assemblage actif et qualification L4

**Objectif :** faire fonctionner les six participants dans les vrais Workers des runtimes existants et livrer les lectures internes cohérentes, sans convergence structurelle anticipée.

- **Préconditions :** I1… I7 acceptés ; arbitrages d’assemblage et permissions arrêtés.
- **Adapter :** settings, noms de définitions, branchement dans la racine PROJECTION et le monolithe existants, préflights, allowlists, assemblage constructor ; sans renommage de module/runtime ni refonte de leurs frontières.
- **Créer :** factory READ explicite, services internes courants/historiques et lots d’Expenses.
- **Migrations :** grants finaux ; aucune modification implicite du routage.
- **Tests :** trois checkpoints POT indépendants ; arrêt d’un participant sans bloquer les autres ; mélanges d’ordres ; D ; résultats avant métier ; lecteur pendant avance CurrentPot ; saturation, renewal, drainage et restart.
- **Gates :** G-I8 ; architecture et full requis pour ce jalon majeur.
- **Capitalisation :** preuves techniques inchangées avec attribution ; aucune substitution aux gates fraîches.
- **Acceptation :** toutes les obligations internes L4 allouées exécutées ; aucun chemin V2 actif dans la composition V3 testée ; réserves et reports explicités.
- **Risques :** allowlist trop large, composant legacy indirect, capacité insuffisante.
- **Livrables :** six participants READ actifs dans les runtimes existants, lectures internes, dossier de qualification, matrice SPEC-10, rapport et manifeste futurs ; aucun nouveau runtime dédié requis.
- **Passage :** audit indépendant du code et des preuves, puis décision humaine de clôture L4.

Aucune reconstruction administrative complète, certification HTTP, performance globale ou distribution n’est incluse dans cette acceptation interne.
La convergence fonctionnelle READ est requise à I8 ; la convergence définitive
des familles, les fusions Maven et le retrait physique des runtimes historiques
ne conditionnent pas cette acceptation et relèvent de D3 après qualification de L6.

## 10. Matrice de tests et allocation SPEC-10

### Tests existants et manquants par projection

| Projection | Contributions existantes, sans PASS au HEAD inféré | Preuves nouvelles indispensables |
|---|---|---|
| PotVersion / CurrentPot | PotHistoryPostgresTest, PotHistoryCardinalityPostgresTest, tests historiques Projection | P01, composition légère exacte, contenus divergents, atomicité du pointeur |
| SubobjectVersion | Tests historiques de store JSON | Immutabilité V3 complète, batch, courses et refus transactionnels |
| Balance | PotBalanceCalculatorTest, PotFinancePostgresTest | Participant/store, D, hors ordre, rollback et ACK perdu |
| Visibility | Aucune preuve métier V3 complète identifiée | P03 et navigation keyset |
| Binding | CurrentBindingPersistencePostgresTest, tests Identity L3 | EUI@V réel, filtre User, token et cible/minVersion |
| CommandResult | CommandResultTest, JdbcCommandResultStorePostgresTest | Complétion V3 minimale, demande+terminal, mapping exact |
| IdentityRequestResult | RegistrationResultServiceTest et runtime historique | Trois opérations, requesterEui, cible et version |
| Invitation | Contrats et primitives L1 | Parcours complet L5 |

### Allocation normative

| Famille / scénarios | L4 | Report et limite |
|---|---|---|
| PRJ-01 P01-A…K | I3 et I8 | Contribution interne réelle |
| P01-L et mesures complémentaires | Compteurs SQL/volumes et caractérisation locale | Qualification PERF complète L6 |
| PRJ-02 P02-A…L | I4 et I8 | Oracle rationnel indépendant |
| P02-M | Mesures locales des profils | SLO et campagnes de charge L6 |
| PRJ-03 P03-A…N | I5/I8 | Courses PostgreSQL et lectures concurrentes |
| P03-O | Plans et volumes du différentiel en L4 | Performance transversale L6 |
| READ-01 R01-A…M | Contrats et comportements internes I8 | HTTP/Bruno et AuthN réels L5 |
| R01-N | Non certifié en L4 | Instrumentation de polling et mesures L5/L6 |
| EVT-03 E03-A…G/J | Contributions avec vrais participants L4 | Catalogue complet incluant Invitation reste L5 |
| E03-H/I | Pas de nouvelle fonction de replay/reset en L4 | REC-01/C-02 à L6 |
| CON-01/02 | Branchement aux sources et autorités READ réelles | Mécanismes inchangés capitalisables sous conditions |
| CON-03…06 | Variantes affectées par I2 et chaque transaction participante | Aucune dispense de PostgreSQL |
| DOM-03 | Réutilisation calcul et source + round-trip READ I4 | Pas de certification PERF |
| I01-Q | Retard/rattrapage et isolation interne du token I6/I8 | Sessions et accès HTTP réels L5 |
| A10-A…K | Provenance, immutabilité, indépendance et décisions internes I7/I8 | Codes/enveloppes/polling HTTP L5 |
| A11-B…E | Matérialisations et suppression I3… I5/I8 | Exécution WRITE A11-A reste attribuée à L1 |
| A11-F…K/N | Lecture/autorisation interne et références exactes | Qualification HTTP L5 |
| A11-L/M | Oracle interne disponible | Budgets de latence L6 |
| S-01/S-02 | Ports keyset, manifeste complet et lots cohérents | Curseur public, plafonds et HTTP L5 |
| SEC-01/02 | Invariants internes et fenêtre de propagation contrôlée | Qualification externe L5 |
| ID-02/C-01, INV-01 | Hors L4 | L5 |
| REC-01/C-02, OPS/PERF complets | Hors qualification L4 | L6, puis confirmation L7/L8 |

Références : [PRJ-01…03, lignes 689–826](SPEC-10_Test_Certification.md#7-projections-read), [READ-01, lignes 830–871](SPEC-10_Test_Certification.md#read-01--protocole-et-oracles), [A10/A11, lignes 971–1012](SPEC-10_Test_Certification.md#aud-v3-010--résultats-et-polling-a10-a-à-a10-k).

**Aucun PASS global PRJ/READ/SEC n’est préannoncé.** Les variantes comprenant HTTP ou performance restent explicitement non prouvées à la clôture interne L4.

Chaque future preuve doit relier : scénario, méthode, variantes, oracle indépendant, commande, SHA, configuration, autorité réelle/fixture, artefacts et verdict.

## 11. Gates et réutilisation des preuves L2

Les gates ci-dessous restent prospectives et inchangées par l’amendement D1–D4.
Elles qualifient les adaptations réellement nécessaires à L4, y compris I2 et
l’activation I8 ; elles n’imposent aucune rationalisation Maven/runtimes préalable.
Le seul amendement documentaire ne déclenche aucune campagne ni requalification
L2/L3. Le chantier D3 devra redéclarer ses propres frontières et gates sur la base
de son inventaire, sans hériter automatiquement de la validité des preuves L4–L6.

### 11.1 Matrice prospective par incrément

FOUNDATION REQUALIFICATION désigne ici une catégorie conceptuelle de requalification L2. **La slice FOUNDATION reste exclusivement L0.**

| Gate | Primary slice | Secondaires / ancres complémentaires | FAST | CHANGE | INTEGRATION | Requalification L2 | Architecture | Full |
|---|---|---|---|---|---|---|---|---|
| G-I1 | PROJECTION | Aucune canonique | Valeurs/égalité/codecs | Store SQL et conflits | Atomicité store+slot, rollback/ACK | Non, sauf défaut ou modification technique | REQUIRED : dépendances compile nécessaires au store/ports/guards, §17 | NOT REQUIRED |
| G-I2 | PROJECTION | COMMAND ; monolithe en complément | Contrats Engine et participants | Préparation, observations et transactions | UNKNOWN, capacity, Command/Identity réels | Oui, préparation/recovery/owners | REQUIRED, frontière transactionnelle §17 | NOT REQUIRED si impact borné démontré |
| G-I3 | PROJECTION | Aucune par défaut | Composition/références | P01 et sources SQL | Courses/hors ordre/ACK | Non si L2 inchangé | NOT REQUIRED sans POM/règle modifiée | NOT REQUIRED |
| G-I4 | PROJECTION | Aucune par défaut | Calcul/mapping exact | P02 et store | D, courses, rollback/ACK | Non | NOT REQUIRED sous même condition | NOT REQUIRED |
| G-I5 | PROJECTION | Aucune par défaut | Ensembles/keyset | P03 et SQL | Garde collective, init et lecteurs | Non | NOT REQUIRED sous même condition | NOT REQUIRED |
| G-I6 | BINDING | PROJECTION pour hébergement/contrats READ | État EUI/monotonie | Source et store Binding | Courses, retard/rattrapage/ACK | Non | NOT REQUIRED sans nouvelle dépendance/règle | NOT REQUIRED |
| G-I7 | PROJECTION | Ancres complémentaires Results concernées | Mapping/provenance | Sources/stores terminaux | Courses et indépendance | Non | REQUIRED pour les dépendances L2/READ nouvelles, §17 | NOT REQUIRED |
| G-I8 | PROJECTION | BINDING, COMMAND pour assemblage commun ; monolithe et Results | Régression locale pertinente | Slices canoniques composées | Workers réels, convergence, ressources | Oui sur assemblage/capacité affectés ; pas de réouverture universelle L2 | REQUIRED, isolation/activation §17 | REQUIRED, jalon majeur §§19/21 |

Les conditions « sans POM/règle modifiée » sont vérifiables : toute nouvelle dépendance réellement nécessaire rend Architecture REQUIRED avant poursuite. Elles ne permettent pas de déplacer discrètement les changements d’un incrément.

**EVENT n’est pas ajouté pour le seul fait de lire un événement.** Il devient secondaire si la publication, la fenêtre source, le filtre d’éligibilité ou un contrat Discovery partagé est modifié.

**WEB n’est pas ajouté automatiquement en L4.** Il devient nécessaire si un contrat existant ou une composition HTTP est effectivement modifié. Les nouveaux parcours HTTP restent L5.

### 11.2 PostgreSQL

Pour chaque incrément SQL/transactionnel : **CURRENT V3 réel**.

- historique V2 séparé : non demandé par défaut ;
- baseline historique : aucune TRUSTED identifiée ;
- migrations historiques exécutées transitivement par une gate canonique : conservées et déclarées ;
- changements d’infrastructure de migration ou de sémantique d’upgrade : scope HISTORICAL à redéclarer selon §27.

La distinction est imposée par [politique §§22–27, lignes 728–872](../testing/Reactor_Verification_Policy.md).

### 11.3 Commandes canoniques futures

**À exécuter seulement dans les futurs mandats autorisés, depuis app/. Aucune ne l’a été ici.**

    ./mvnw -pl runtime-task-consumption-worker -am test

Pour I2 :

    ./mvnw -pl runtime-task-consumption-worker,runtime-command-consumption-worker -am test

Pour I6 :

    ./mvnw -pl runtime-binding-consumption-worker,runtime-task-consumption-worker -am test

Pour I7, avec les propriétaires actuels des résultats :

    ./mvnw -pl runtime-task-consumption-worker,runtime-command-result-consumption-worker,runtime-registration-result-consumption-worker -am test

Aux incréments où §17 l’exige :

    ./mvnw -pl architecture-tests -am test

À la qualification majeure I8 :

    ./mvnw test

L’ancre runtime-monolith s’ajoute aux preuves de composition qui la concernent ; ce n’est pas une nouvelle slice canonique.

### 11.4 Recettes FAST / CHANGE / INTEGRATION

**VERIFIED :** le registre actuel contient identity et foundation, pas read. verify.py refuse un scope inconnu et exige une recette explicite pour FAST. Voir [sélection, lignes 407–458](../../scripts/testing/verify.py#L407).

Il ne faut donc pas annoncer « verify.py FAST read » comme une commande disponible.

Propositions de sélections futures :

| Niveau | Sélection READ proposée |
|---|---|
| FAST | Valeurs READ, canonicalisation, mappings terminaux, logique des six participants sans infrastructure ; calculateur L1 pertinent |
| CHANGE | FAST + stores SQL, sources historiques ciblées, privilèges et contrats participants de l’incrément |
| INTEGRATION | CHANGE + vraies transactions, courses, fautes réseau/commit, Discovery branchée et compositions affectées |

Sans modification de l’outillage, les futures sélections filtrées peuvent employer Maven avec une liste positive revue. Elles restent du feedback. Une recette READ enregistrée nécessiterait un mandat d’outillage distinct.

Les commandes existantes suivantes peuvent porter les gates non filtrées :

    python3 -B scripts/testing/verify.py CHANGE PROJECTION
    python3 -B scripts/testing/verify.py INTEGRATION PROJECTION,BINDING

Elles ne garantissent un PASS que si le collecteur sait inventorier les formes JUnit effectives. Un refus ou INCONCLUSIVE doit rester visible.

### 11.5 Capitalisation Fondation/Branchement

| Ensemble | Classement logique | Conditions de capitalisation |
|---|---|---|
| Routage, slots, ownership, cession, capacité, owners | Fondation L2 | Classes, SQL, configuration, oracles et environnement pertinents inchangés |
| Discovery | Fondation pour l’algorithme ; branchement pour les sources/filters/catalogue | Preuve bornée au catalogue Pocoma, pas à une source arbitraire |
| Sources Pot/Identity/complétions | Branchement | Nouveaux contrats réels à prouver |
| Participants et stores métier READ | Branchement | Aucune certification héritée des probes |
| Spring, pools, permissions, activation | Branchement avec hypothèses Fondation | Requalification des propriétés de ressources/isolation affectées |

Sources historiques capitalisables, **DOCUMENTED_ONLY ici** :

| Preuve | SHA testé | Limites |
|---|---|---|
| L2 I1 fondation | 12eaa5ae983c23a4b07c0299ce59c4d7933bfb5d ; architecture séparée 6835b5c15cc40d09c22e0b0a4f3b6538ac49992d | Schémas/routage/permissions du périmètre historique |
| L2 I2 owner/Engine | 4f2264ae6e11e7e6e1511577064bca5e31ad1336 | R1/R9 ; préparation affectée par I2 L4 à rejouer |
| L2 I3 corrections | 3ee575940669feb56b73b10122b5a3406bce6a08 | Réserve R-AUD-I3-03 conservée |
| L2 I4 Discovery | 0b08d3bf0bfce1334260cfd5249b13c3040a67bb | Sources réelles et fixtures distinguées |
| L2 I5 | SHAs distincts du dossier de clôture | Anciennes observations temporelles non converties en PASS |
| L3 O20/O21 | cfd3f6458f7de61945bd3f73cb71c794dddf9e57 | Verdicts stricts INCONCLUSIVE, pas de preuve READ |
| Requalification nominative r05/r12 | HEAD des reçus `0092b4d1bafe644017717865a620f0a35b68fbb5`, **dirty** ; publication du contre-audit `32504fca0f8d5ba6f236cf3111145d346ddc7930` | Attribution exacte et limites détaillées A09 ci-dessous ; aucun PASS d’un checkout propre 32504fca déduit |

Références : [attribution L2, lignes 121–169](L2_Documentary_Closure.md#5-preuves-existantes-et-shas-réellement-testés), [consolidation et limites, lignes 147–170](L2_I6_I7_Consolidation.md#6-provenance-réutilisation-et-récupération), [O20/O21, lignes 48–58](L3_Identity_Closure.md#ratification-nominative-du-skip).

#### A09 — Attribution nominative et limites vérifiées sur pièces

| Preuve accessible dans le dépôt | Attribution réelle | Verdict et portée |
|---|---|---|
| [r05 résultat](../testing/evidence/L3-I5/reused/gemini-r05/result.json.gz), [provenance](../testing/evidence/L3-I5/reused/gemini-r05/provenance.json.gz), [brut](../testing/evidence/L3-I5/reused/gemini-r05/raw.tar.gz) | Invocation `cd87a634f2804df3bd91d312e74007fb`, HEAD `0092b4d1bafe644017717865a620f0a35b68fbb5`, arbre **dirty** | 1/0/0/0, PASS ciblé ; 43,369 s méthode / 47,70041 s processus Maven |
| [r12 résultat](../testing/evidence/L3-I5/reused/gemini-r12/result.json.gz), [provenance](../testing/evidence/L3-I5/reused/gemini-r12/provenance.json.gz), [brut](../testing/evidence/L3-I5/reused/gemini-r12/raw.tar.gz) | Invocation `94527adaae634c3a815004d52cf9968d`, même HEAD, arbre **dirty** | 1/0/0/0, PASS ciblé ; 51,819 s méthode / 56,06311 s processus Maven |
| [O20/O21 index des campagnes](../testing/evidence/L3-I5/canonical/Campaigns.json), [index d’archives et empreintes](../testing/evidence/L3-I5/Archive_Index.json), [archives et limites](../testing/evidence/L3-I5/README.md) | SHA propre exécuté `cfd3f6458f7de61945bd3f73cb71c794dddf9e57`, invocations O20 `cf3c434170594c8597027aca4cdabe2e`, O21 `2dd51d57245e44bfb794dd0bb74e387e` | O20 2057/0/0/1 (Architecture 163 PASS) ; O21 1791/0/0/1 ; verdicts stricts **INCONCLUSIVE**, r05/r12 nominatifs PASS dans les deux |

Dirty des deux campagnes ciblées : trois sources de tests (`I5TemporalV2Postgres`, `I5TemporalV2Test`, `V3WorkerResourcesPostgresTest`), `scripts/testing/test_verification.py`, `verification-campaigns.json`, `verify.py`, et `__pycache__` non suivi. Leur code testé ne peut donc être identifié par le seul SHA du HEAD. Le rapport de contre-audit est publié à `32504fca…` ; son SHA initial long `0092b4d188aa1d575c3f3fa599bbdf38865d1309` **ne correspond pas au HEAD des reçus**, dont la valeur exacte est celle du tableau. Ne pas attribuer automatiquement ces exécutions à l’arbre propre 32504fca : une équivalence exige la comparaison des empreintes d’entrées, tests/outillage compris. Ce constat ne réécrit aucun rapport, registre ou archive.

**Limites de l’oracle r05** : le code présent [I5TemporalV2Postgres](../../app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/v3/I5TemporalV2Postgres.java) distingue l’omission sous Discovery concurrente et le CAS retourné (`returned`). La reproduction PG `i5_r05_discovery_skip_locked_interleaving` et les contre-exemples établissent un entrelacement précis et le refus d’une omission sans contention ; le journal emploie aussi des enveloppes temporelles/clé, pas une reconstruction universelle de chaque durée de verrou PostgreSQL. Un PASS ne certifie pas toutes les distributions de contention, pauses JVM ou fenêtres physiques. Le code à la référence appelle pour `i5_r05` **W1/B1/MW4** ; les autres anciens profils ne sont pas réattribués à cette méthode actuelle.

**Limites du scénario r12** : le code appelle `campaign(PG,2,32,true)`, donc **W2/B32/MW4**, lease 90 s ; pas les quatre combinaisons B1/B32 × MW1/MW4 affirmées dans certains résumés historiques. Deux Workers, quatre pools chacun, huit préparations retenues et quatre contentions excessives constituent le scénario contrôlé. Les candidatures **0|1 et 2|3 sont disjointes** ; la compétition sur candidatures chevauchantes relève de `w02`, pas de r12 seul. Les douze couples couverts sont **trois Consumptions (Command et deux probes READ) × quatre partitions**, pas six participants READ métier ni « quatre autorités × trois partitions ». Les sources de méthodes/oracle n’ont pas changé entre `cfd3f645…` et la référence de consolidation ; ce constat statique ne vaut pas rejeu. Les assertions d’allocation physique sont bornées à ce scénario, pas une preuve générale de disponibilité.

**Réserves maintenues** : paramètres de production à 30 s non certifiés, enveloppe expérimentale 90 s, UNKNOWN → drainage conservateur, réserves L2/L3 et confiance admin PG. Le registre qualifie nominativement r05/r12 (`active:false`) ; la formulation historique de politique/rapports ne dispense d’aucun gate frais. O20/O21 ont un SKIP nominatif `TerminalSequencingPostgresTest#zExploratoryContentionSeparateFromCorrectness`, accepté humainement **pour L3/I5 seulement** ; ne pas transposer cette exception à I1. Le résumé ancien « 319 tests COMMAND » est un compte de module historique, pas du réacteur entier ; il n’établit pas un PASS strict avec skip. **Aucune certification générale L2 ni levée globale de réserve** n’est déduite.

Avant réutilisation : artefacts accessibles et empreintes vérifiées via l’index, attribution SHA + dirty + inputs, oracle et limites conservés, comparaison explicite ancien→nouveau **SQL, sources, tests, configuration, dépendances, toolchain/PG, charge et hypothèses temporelles**. Toute hypothèse modifiée/non vérifiée interdit la réutilisation pour cette propriété. Les changements I2/I8 imposent leurs preuves affectées fraîches ; le stockage I1 exige ses propres preuves physiques. Les contrôles de cette consolidation sont des lectures d’archives et attributions, **pas une nouvelle exécution ni une vérification exhaustive de tout le dossier historique**.

La [politique §34, lignes 1245–1250](../testing/Reactor_Verification_Policy.md#34-proportionate-certification-and-evidence-reuse) interdit de remplacer une gate fraîche prescrite par ces observations historiques.

## 12. Coûts connus et limites des estimations

Les valeurs suivantes sont **DOCUMENTED_ONLY comme mesures d’exécution** ; leur présence dans l’inventaire JSON a été vérifiée. Elles proviennent du SHA historique 49ae298d879a41b29d1caf14e7597dffb947713a.

| Suite | Durée historique |
|---|---:|
| V3WorkerPostgresTest | 1 177,621 s |
| DiscoveryPostgresTest | 505,648 s |
| V3WorkerResourcesPostgresTest | 381,585 s |
| L2ManifestProof | 111,615 s |
| SlotNavigationPostgresTest | 57,300 s |
| ConsumptionLifecyclePostgresTest | 56,295 s |
| EventSequencingAdministrationPostgresTest | 47,973 s |
| V3WorkerMetricsTest | 36,922 s |

Source : [rapport d’optimisation, lignes 31–88](../testing/Verification_Optimization_Report.md#inventaire-provenance-et-coûts).

L’archive correspond à :

- 286 suites et 1 822 tests ;
- somme des durées de suites : **2 676,157 s** ;
- durée murale documentée de gate : **45 min 27 s** ;
- un failure et un skip historiques.

Les trois premières suites concentrent 77,16 % de cette somme. Ce n’est pas une estimation du futur temps mural L4.

### Campagnes évitables dans le feedback local inchangé

- fenêtres idle de i5_w16, environ dix minutes par construction ;
- campagne volumétrique i4_p01 ;
- campagnes complètes Worker/ressources ;
- ensemble exhaustif Discovery quand seul un codec ou un store métier change.

### Campagnes restant dues

- celles dont l’invariant est affecté par I2 ou I8 ;
- les variantes PostgreSQL des nouveaux stores/participants ;
- toutes celles tirées transitivement par les gates canoniques non filtrées ;
- les campagnes prescrites au jalon majeur.

La gate Architecture ayant une fermeture très large, son coût ne peut pas être assimilé à celui des seules assertions ArchUnit.

Les mesures Identity FAST/CHANGE/INTEGRATION — environ 6,686 s, 144,100 s et 216,263 s — sont des références d’outillage d’un autre périmètre, **pas des prévisions READ**.

**Durée des nouveaux tests READ : UNVERIFIED.** Aucun total L4, gain chiffré ou délai de certification n’est inventé.

## 13. Registre des risques et findings

| ID / sévérité | Risque ou finding | Prévention | Test attendu / qualification |
|---|---|---|---|
| R01 — Critique | Mélange de versions READ | Version figée ; résolution exacte des références et Balance | R01-D…G/M, I8 |
| R02 — Critique | CurrentPot pointe une composition incomplète | Sous-versions + PotVersion + pointeur dans une unité | Coupures P01-G, I3 |
| R03 — Critique | F02, divergence silencieuse | Comparaison complète après conflit | Identique/divergent/concurrent, I1/I3/I4/I7 |
| R04 — Critique | Lecture WRITE courante pour événement ancien | Ports exigeant V ; aucun fallback | WRITE avance pendant préparation, I3/I4/I6 |
| R05 — Critique | Transactions imbriquées ou mauvais owner | Scopes séquentiels possédés par L2 | Contextes étrangers/ambiants, I2 |
| R06 — Critique | Commit UNKNOWN rejoué | Observation seule ; aucune cession supposée | ACK perdu avant/après commit, I2 et participants |
| R07 — Critique | Mauvaise propriété des résultats | requesterEui durable, distincte du User et de la cible | A10 internes, I7 |
| R08 — Critique | Token d’une autre EUI exposé | Cible exclusivement authentifiée ; réponse dédiée | E1/E2 distinctes, I6/L5 |
| R09 — Haute | Balance partielle | Source financière groupée complète ; comparaison des clés et valeurs | DOM-03/P02, I4 |
| R10 — Haute | Balance(D) attendue ou historiques ignorés après D | Décision depuis Header@V | D avant V<D, I4 |
| R11 — Haute | Hors ordre faisant reculer état | Upsert conditionnel/garde collective | P01/P03/Binding, I3/I5/I6 |
| R12 — Haute | Navigation confondue avec autorisation | Droits PotVersion à V | Entrée visible falsifiée, I5/I8 |
| R13 — Haute | Multi-Shareholders mal dédupliqués | Ensemble des utilisateurs encore liés | P03-D, I5 |
| R14 — Haute | Révocation Identity retardée mal présentée | Fenêtre asynchrone explicite ; aucune promesse instantanée | I01-Q/SEC-02, I6 puis L5 |
| R15 — Haute | Faux recovery d’un slot ancien via pointeur récent | Checkpoint + slot + preuve métier | État récent mais ancien slot présent, I2/I5/I6 |
| R16 — Haute | Deadlocks sous versions concurrentes | Slot d’abord ; sous-versions en ordre total ; pointeur ensuite ; garde Visibility unique | Barrières et deadlocks, I3/I5 |
| R17 — Haute | Ressources Worker insuffisantes | Réserves control conservées ; scopes source courts | Saturation/renewal/drainage, I8 |
| R18 — Haute | Composants V2 réactivés indirectement | Imports explicites, allowlists et profils exclusifs | Beans/classpath/racines, I8 |
| R19 — Haute | Requalification L2 involontaire | Déclaration des classes/SQL/hypothèses avant changement | Audit de diff et gates I2/I8 |
| R20 — Haute | Scénario SPEC-10 oublié | Matrice variantes→méthodes→lots avant tests | Contrôle de couverture I8 |
| R21 — Moyenne | Coût N+1 et payloads inutiles | Sources spécialisées et chargements groupés | Compteurs SQL/plans I3… I6 ; PERF L6 |
| R22 — Moyenne | activeTo/projectedAt rend les doublons divergents | Égalité limitée au contenu immuable contractuel | Même sous-version à plusieurs V, I1/I3 |
| R23 — Moyenne | Scans de tombstones | Index partiel et diff des visibles seulement | P03-I/O, I5 |
| R24 — Moyenne | Tests lourds payés à chaque édition | Sélections positives locales ; gates aux frontières déclarées | Recettes revues, chaque incrément |
| R25 — Moyenne | Baseline supposée fiable | CURRENT V3 explicite ; aucune baseline TRUSTED inventée | Bootstrap neuf/extension I1… I8 |
| R26 — Moyenne | Statuts r05/r12 mal interprétés | Attribution des campagnes et réserves séparées | Audit probatoire avant capitalisation |
| R27 — Moyenne | Références de manifeste sans contrôle d’appartenance | Lot résolu dans PotVersion exacte | Références étrangères/substituées, I8/L5 |
| R28 — Haute | Rationalisation anticipée ou activation I8 reportée à L7 par confusion des niveaux | D1–D4 ; distinction participants/modules/compositions/runtimes/instances ; activation maintenue | Audit du plan amendé puis diffs et gates des futurs incréments |

L’ordre des verrous proposé évite notamment de verrouiller CurrentPot avant des insertions immuables susceptibles d’attendre une autre transaction. Les lots multiclés utilisent le même ordre total dans tous les writers.

## 14. Arbitrages humains nécessaires

### Décisions normatives déjà prises — à ne pas rouvrir

- composition complète autonome à chaque V ;
- trois consommations POT_EVENT indépendantes ;
- calcul intégral des balances ;
- D sans Balance(D) ;
- navigation non autorisante ;
- EUI locale versionnée, sans version globale Identity ;
- requesterEui propriétaire immuable ;
- aucune expiration fonctionnelle des résultats ;
- moteur L2 unique, pas de transaction distribuée ;
- scopes L5/L6/L7/L8 ;
- D1–D4 : stabilité structurelle L4–L6, trois familles fonctionnelles cibles, rationalisation après clôture fonctionnelle et qualification de L6, préservation des preuves.

### Choix techniques proposés

- stores typés et schéma du §7 ;
- JSONB canonique pour les collections complètes ;
- comparaison de contenu après conflit, sans UPDATE immuable ;
- classes participantes avec Prepared privé ;
- factories inactives jusqu’à I8 ;
- runtime PROJECTION existant comme hôte READ ;
- absence de nouvelle table de reçu ou de complétion.

Ces choix sont à auditer avec le plan ; ils ne nécessitent pas chacun un arbitrage métier séparé.

### Arbitrages réellement ouverts

**A1 — Évolution bornée de la frontière L2.**

Accepter ou amender la proposition du §6.2 : préparation hors transaction ambiante, scopes source/effets explicites et préparation des références de recovery avant snapshot READ. C’est l’arbitrage principal avant I2. Il doit inclure son périmètre de requalification ; aucune solution cachée par datasource ou thread n’est acceptable.

**A2 — Assemblage runtime.**

Ratifier le branchement READ dans la racine PROJECTION existante et son partage
avec le monolithe, ainsi que les gardes d’activation adaptées en I8, dans le respect
des frontières d’exécution et des invariants d’isolation L2. L’audit devra vérifier
la compatibilité de cet hébergement avec les ressources et permissions nécessaires.
Toute exception structurelle indispensable doit suivre D1. Aucun processus
supplémentaire ni convergence définitive vers trois familles n’est requis pour L4.
Cet arbitrage porte sur l’activation fonctionnelle, pas sur une fusion de runtimes.

**A3 — Ordre et représentation interne du manifeste.**

**Partie I1 figée** : clé, huit contenus, égalité/codec/SQL et ordre UUID PostgreSQL définis au §7.3 et au plan autonome I1 §3. La composition canonique anticipée pour I3 est précisée par A08 au §7.2 ; aucune valeur PotVersion n’est implémentée en I1. Revue de réalisation du manifeste avant I3 ; représentation HTTP, compression et plafonds restent L5. Aucun point ouvert de représentation ne force I2 avant I1.

**A4 — Attribution des qualifications nominatives récentes.**

Attribution et limites fixées au §11.5/A09 ; comparaison ancien→nouveau et vérification d’artefacts restent dues **avant chaque réutilisation**, notamment I2/I8. Les divergences des résumés historiques sont signalées sans réécriture. Aucun arbitrage de levée générale de réserve n’est demandé ni présumé ; A4 ne bloque pas la conception du stockage I1.

### Obligations différées

- L5 : CurrentInvitation, consentement réel, AuthN, HTTP/Bruno, limites publiques ;
- L6 : reconstruction exclusive, maintenance, O-01, capacité et SLO complets ;
- après clôture fonctionnelle et qualification de L6, idéalement première étape de L7 : chantier D3 de rationalisation Maven/compositions/runtimes et convergence vers les trois familles D2, avec inventaire, mesures, analyse des preuves et gates selon D4 ;
- L7 : convergence finale et retrait exhaustif V2, rationalisation des noms et certification du code unifié selon le plan directeur ;
- L8 : distribution, installation vierge et exploitation finale.

L’affectation définitive des participants aux modules, compositions et processus,
les fusions concrètes et le nombre final d’instances restent à déterminer dans D3 à
partir des besoins d’isolation, capacité, exploitation et performance. Ces choix
futurs ne sont pas des arbitrages bloquant L4. Aucun mécanisme nécessaire au
fonctionnement réel de L4–L6 n’est transféré à ce chantier ; leurs adaptations
indispensables et leurs qualifications restent dans les lots concernés.

## 15. Proposition de périmètre précis de L4/I1

**I1 doit rester un contrat technique de stockage et d’atomicité, sans matérialisation métier.** Le [plan autonome I1](L4_I1_Implementation_Plan.md) détaille fichiers/signatures, huit valeurs, migration V2 neuve/extension, oracles PG et gates ; il fait partie du contrat de revue de cet incrément.

### Inclus

- clé typée de SubobjectVersion ;
- représentation canonique et égalité de contenu ;
- port de présence/lecture groupée et insertion immuable ;
- une table métier READ ;
- droits minimaux et contrôles d’immutabilité ;
- participation stricte à l’owner transactionnel existant ;
- fixture technique utilisant Prepared, requireCompletionReady, complete() et fermeture ;
- PostgreSQL réel pour insertions concurrentes, rollback, poisoning et issue inconnue ;
- déclaration PROJECTION et gate Architecture justifiée par les dépendances nécessaires ;
- rapport et preuves explicitement bornés.

### Exclus

- source POT_EVENT métier ;
- lecture WRITE historique ;
- évolution préparation/recovery L2 ;
- PotVersion et CurrentPot ;
- calcul ou stockage Balance ;
- Visibility, Binding et résultats ;
- activation Worker métier ;
- endpoint HTTP ;
- reset/reconstruction ;
- déplacement, fusion, renommage ou création de module/runtime au titre d’une rationalisation anticipée ;
- modification des scripts de vérification.

### Critère de sortie

I1 démontre qu’un contenu immuable peut être écrit et vérifié dans **la même transaction physique READ que la suppression de son slot**, et qu’une divergence entraîne un rollback observable.

Il ne démontre ni PRJ-01 complet, ni source WRITE@V, ni disponibilité métier READ.

## 16. Conditions avant GO et verdict

Avant tout GO d’implémentation :

1. audit contradictoire indépendant du présent plan ;
2. confirmation des neuf contrats logiques et du report CurrentInvitation à L5 ;
3. validation du périmètre I1 borné ;
4. déclaration des dépendances réellement nécessaires et des gates correspondantes ;
5. matrice prévisionnelle des tests I1 avec oracles positifs et défavorables ;
6. validation des cibles PostgreSQL futures, rôles et bootstrap sans toucher aux environnements existants ;
7. maintien explicite des réserves L2/L3 et des verdicts historiques ;
8. GO humain distinct pour **I1 seulement**.

Avant I2, A1 doit en outre être tranché et l’adaptation exacte des contrats L2 auditée. Avant I8, l’assemblage et ses règles d’isolation doivent recevoir leur propre revue.

**Bilan de la mission initiale de cadrage, conservé comme historique :**

| Champ | État |
|---|---|
| Slices de vérification exécutées | Aucune — analyse READ-ONLY |
| Commandes applicatives exécutées | Aucune |
| Franchissement inattendu exécuté | Aucun |
| Frontière future découverte | Préparation/recovery L2, explicitement allouée à I2 |
| Gate Architecture de cette mission | NOT REQUIRED / NOT RUN |
| Full reactor | NOT RUN, interdit par le mandat |
| PostgreSQL / migrations | NOT RUN |
| Fichiers modifiés | Aucun |
| Nouvelle certification | Aucune |

**Verdict consolidé : READY FOR I1 IMPLEMENTATION REVIEW.**

Les dix amendements A01–A10 sont intégrés ; le contrat I1 est figé dans son plan autonome. A1 reste à décider avant **I2**, A2 avant **I8**, A4 avant chaque réutilisation ; la partie I1 d’A3 est définie. Les huit incréments et leurs livrables restent inchangés, sans dépendance circulaire I1/I2. D1–D4 et la stratégie de gates proportionnées sont conservés. Aucun arbitrage normatif bloquant I1 identifié ; les limites probatoires et désaccords techniques A09 restent explicites.

Cette consolidation ne constitue ni un audit indépendant du code, ni une preuve d’implémentation, ni un GO. La revue du plan I1 puis la **décision humaine séparée d’implémentation I1** restent requises. Les conditions historiques ci-dessus désignent les obligations préalables, pas des tâches déjà satisfaites par du code.

**Vérification de cette mission documentaire (2 novembre 2026)** : slices **NONE** ; lectures statiques sources/SPEC/politique et archives nominatives, contrôle des liens/ancres/chemins et du séquencement/cohérence des deux plans, empreintes SHA-256 et accès des quatre archives r05/r12/O20/O21 vérifiés contre leur index (sans vérification exhaustive des membres imbriqués), revue complète du diff et `git diff --check`. Aucun fichier applicatif, test, migration, script, POM, preuve/archive/registre ou autorité normative supérieure modifié ; seuls les deux plans documentaires sont écrits. Aucune campagne Maven/PostgreSQL ni collecteur exécuté, aucune requalification L2/L3 déclenchée. Gate globale **NOT REQUIRED / NOT RUN**, full reactor **NOT RUN**, car mission documentaire ; aucun franchissement inattendu. Le SHA final et la synchronisation de publication sont rapportés après commit/push, sans autoréférence du présent document.
