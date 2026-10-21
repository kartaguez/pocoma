# DISCOVERY — Step Plan

Date : 2026-10-20. **current_plan**. Branche `feat/consumption-transaction-protocol` ; référence
`8e09b433a949d8ecb06f048bdc0711edd1885e0d` (candidat 4E intégré à l’identique). **4A CLOSED ; 4B/4C CLOSED / PASS ; 4D CLOSED / PASS PREPARED ; 4E CLOSED / PASS fonctionnel hors production.**
Ce document suit la finalisation du séquencement et prépare son exploitation par la Discovery.
4B a livré un mécanisme dormant, raccordé depuis 4D ; aucun séquencement E n’est actif en production. [Canon et limites](Step_Canon.md) ; [trajectoire suivante](Next_Plan.md).

## Autorité, critères et état 4A

La [spécification détaillée Étape 4](../../../architecture/Event_Transactional_Sequencing_Step_4_Implementation_Plan_2026-10-19.md)
reste l'unique définition détaillée des lots : §6 pour les critères exacts, §7 pour le cutover,
§8 pour V-A…V-E, §9 pour les arrêts. Ce point d'entrée porte leur état courant et les dépendances.
Numérotation et sens de 4B–4E conservés. Aucun critère n'est affaibli par la synthèse suivante.

**4A — CLOSED**, décision de consolidation demandée pour la référence Git ci-dessus, sur preuves versionnées :

| Livré / preuve | Résultat et portée |
|---|---|
| [Contrats 4A](../../../architecture/Event_Publication_Contracts_Step_4A_2026-10-19.md) | EventRef, EventFamily, PublishedEvents et enveloppes d'acceptation ; gardes locales et règles structurelles ; deux compositions de test (non persistante / durable ordonnée). Aucun collecteur/owner E de production |
| [Fixtures Consumption corrigées](../../../architecture/Consumption_Ambient_Transaction_Five_Tests_Fix_2026-10-20.md) et [JSON](../../../testing/evidence/Consumption_Ambient_Transaction_Five_Tests_Fix_2026-10-20.json) | Cinq courses exercées dans leurs owners réels, garde d'ambient conservée ; livraison `74eda7030769d6a5acb270497eda2f055c178160` |
| [Bootstrap PostgreSQL/Testcontainers corrigé](../../../architecture/Architecture_Postgres_Bootstrap_Six_Errors_Fix_2026-10-20.md) et [JSON](../../../testing/evidence/Architecture_Postgres_Bootstrap_Six_Errors_Fix_2026-10-20.json) | Isolation ConfigData, mapper partagé, READ initialisé indépendamment de l'ordre des tests ; livraison `b7aacef54457583eccd405ef03acc21e05bb156c` |
| Gate architecture global historique `./mvnw -pl architecture-tests -am test` | **PASS : 1 257 tests déclarés, 0 failure, 0 error, 1 skip intentionnel**, sortie 0 ; module architecture : 138 tests sans skip. Ce n'est pas un full reactor |
| [Campagne exploratoire 3B, §§7/9](../../../architecture/Event_Terminal_Sequencing_Step_3B_PostgreSQL_Proof_2026-10-19.md) | `TerminalSequencingPostgresTest.zExploratoryContentionSeparateFromCorrectness`, condition `step3b.measure=true` ; activable avec `-Dstep3b.measure=true`. Pas de seuil de performance ni d'intégration runtime prouvés |

Les rapports initiaux de livraison/diagnostic avaient légitimement un gate rouge et ne prononçaient
pas la clôture. Leurs résultats restent historiques ; la présente consolidation ferme 4A après les
corrections. Le rapport non suivi d'audit du skip est préservé hors livraison et n'est pas une source
indispensable : la condition JUnit et les preuves 3B/du bootstrap sont déjà versionnées.
Aucun test n'est relancé pour cette décision documentaire. Aucun séquenceur PostgreSQL actif.

## 4B — CLOSED : owner PRIMARY et collecteur, hors production

Construire le runner propriétaire/participant PRIMARY et la réalisation de `PublishedEvents` pour
le graphe durable couvert. Configuration importée uniquement par tests, sans scan/autoconfiguration
productif ; pas de migration des producteurs réels. Cycle OPEN/CLOSED/POISONED/CLEANED lié à la
transaction physique, façade de déclaration injectée, snapshot ordonné immuable et cleanup.

Le propriétaire seul, dans le callback du `TransactionTemplate` standard, après retour de l'action
Consumption (finalisation S/Claim incluse), flush/confirme/ferme puis appelle le suffixe expérimental.
Le participant REQUIRED/MANDATORY partage le lot sans le fermer. Pas de `doCommit` personnalisé,
listener Hibernate ou proxy de connexion livré. Primitive JPA insert-only et writer synthétique
éprouvés localement ; **aucun séquenceur PostgreSQL actif ni owner réel recâblé**.

Preuves obligatoires de §6/4B : même PID/xid JPA/JDBC ; ambient inconnu refusé ; suffixe unique malgré
plusieurs participants ; lot vide sans accès E, même E verrouillées ailleurs ; ordre acceptation →
déclaration → S/Claim → flush → fermeture → suffixe ; doublons, publication après fermeture et
contamination entre transactions refusés. Erreur de garde interceptée reste rollback-only/poisoned.
Tester action/flush/suffixe en échec, rollback-only, begin échoué, cleanup après toutes résolutions
et transactions successives sur même thread. Préserver COMMITTED/ROLLED_BACK/UNKNOWN dans la
composition exacte du §4.4 : un échec de suffixe hors action peut être classé phase COMMIT avec
rollback confirmé. Rejet structurel des callbacks écrivains, sans promettre une police SQL universelle.

**Clôture 4B :** V-B vert, rollback physique observé, aucune fuite, suffixe avant commit/après S,
inventaire complet des owners autonomes à migrer ; anomalie de composition réelle bloque 4C/4D.
Code dormant retirable sans opération DB. Les deux compositions de publication restent distinctes.

**Livré et prouvé le 2026-10-20 — PASS.** [Rapport 4B](../../../architecture/Event_Transaction_Protocol_Step_4B_2026-10-20.md)
et [preuves JSON](../../../testing/evidence/Event_Transaction_Protocol_Step_4B_2026-10-20.json) :
29 nouveaux tests 4B verts ; ciblés 37/0/0/0, puis COMMAND canonique 1 047 déclarés,
0 failure/error, 1 skip exploratoire 3B préexistant. Transaction physique JPA/JDBC, vrai finalizer
S/Claim, rollback, ordre/fermeture, poisoning, trois résolutions et cleanup prouvés. Inventaire des
owners autonomes achevé. Configuration et writer synthétiques dans les tests uniquement ; aucun
producteur réel migré. Global architecture/full non requis, non exécutés ; crossing NONE.

**Audit indépendant 4B — PASS confirmé sur `5640220b94f6208aa4c156e248d95e2eced1522f`.** Revue statique favorable,
sans réexécution Maven ou PostgreSQL par l'auditeur ; les résultats ci-dessus restent ceux de la
campagne de livraison 4B. À cette clôture historique, runner et collecteur étaient dormants, non raccordés en production ; leur raccordement ultérieur est certifié en 4D ci-dessous.
Deux réserves non bloquantes pour cette clôture sont rattachées aux critères existants de **4D/V-D**
(conditions A et B du §4D ci-dessous), sans dette technique 4B. Les limites des preuves et le
[compte rendu de clôture documentaire](../../../architecture/Event_Transaction_Protocol_Step_4B_2026-10-20.md#clôture-documentaire-après-audit-indépendant)
ne valent pas certification des writers/owners réels. **4C CLOSED / PASS dormant ; raccordement 4D certifié ci-dessous ; 4E CLOSED / PASS fonctionnel hors production.**

## 4C — CLOSED / PASS : schéma additif et séquenceur transactionnel inactifs

Quatre autorités E, quatre associations typées et vue ; migration additive après version courante
(V29 à la référence), mode durable unique PREPARED, droits et contraintes de complétude ACTIVE.
Séquenceur JDBC sur la connexion JPA courante, blocs par famille dans l'ordre déclaré ; aucun
producteur/owner réel raccordé, compteurs à zéro et associations vides en production PREPARED.

**Preuves/clôture :** V-C et vrai DDL PostgreSQL ; continuité, UUID opposés à l'ordre déclaré,
concurrence et attentes serveur, ordre fixe des E, rollback après chaque réservation/INSERT,
collisions/FK/overflow, droits UPDATE/DELETE, lot vide sans E. PREPARED accepte les Events sans
position ; ACTIVE isolé refuse une ligne couverte sans position. Coût indexé des contraintes et
absence d'arête P/B/S après E prouvés ; sinon STOP. Upgrade courant et retour au binaire précédent
avec schéma PREPARED répétés ; aucune infrastructure E activée en production.

**Livré et prouvé le 2026-10-20 — PASS.** Départ 4C :
`1771514a51c74452e019a2a259b40d0303cf2cde`. [Rapport/matrice V-C](../../../architecture/Event_Transactional_Sequencer_Step_4C_2026-10-20.md)
et [preuves JSON](../../../testing/evidence/Event_Transactional_Sequencer_Step_4C_2026-10-20.json).
V30 installe PREPARED, quatre E à zéro, associations/FK/vue, complétude différée ACTIVE et droits ;
suffixe JDBC dormant, aucun vrai writer/owner raccordé. 36 nouveaux cas PostgreSQL V-C verts ;
47 tests ciblés finaux sans failure/error/skip ; ancien runtime compilé au SHA de départ démarré
et exercé deux fois sur V30 PREPARED. COMMAND + BINDING canonique : **1 091 tests déclarés,
0 failure/error, 1 skip 3B exploratoire préexistant**. Aucun franchissement inattendu ; global/full
non requis, non exécutés. DDL et fonctions éprouvés sur vraies sources ; ACTIVE uniquement en bases
isolées. Comptes de l'environnement déployé, vrais producteurs et compositions restent 4D ;
activation/cutover/backfill restent 4E. **4C CLOSED / PASS ; raccordement ultérieur 4D décrit ci-dessous.**

**Audit indépendant 4C — PASS statique, sans défaut bloquant démontré dans le périmètre 4C.**
Le [verdict et R1/R2/R3](../../../architecture/Event_Transactional_Sequencer_Step_4C_2026-10-20.md#clôture-documentaire-après-audit-indépendant)
sont enregistrés sans réexécution Maven/PostgreSQL, réouverture, extension de certification ni
dette nouvelle propre à 4C. Les observations sont suivies en 4D/V-D ci-dessous ; **4E CLOSED / PASS fonctionnel hors production**.

## 4D — Producteurs et orchestrations réels, PREPARED complet

**Préparation au HEAD `4defd5a6`, 2026-10-20 :** [audit de raccordement](../../../architecture/Event_Producer_Wiring_Step_4D_Readiness_2026-10-20.md)
réalisé, inventaire et scope V-D déclarés avant modification productive. L'arbitrage du 2026-10-20
approuve UUID sur le port auxiliaire puis EventRef déclaré par la façade applicative sous le même
owner PRIMARY. Amendement 4A daté ; aucune dépendance Maven nouvelle.

**Livraison — 4D CLOSED / PASS, PREPARED ; 4E CLOSED / PASS fonctionnel hors production.** Le [rapport 4D](../../../architecture/Event_Producer_Wiring_Step_4D_2026-10-20.md)
porte les intégrations, matrices A/B/C, commandes réellement exécutées et preuves PostgreSQL.

| Gate V-D réellement exécuté | Résultat |
|---|---|
| COMMAND + BINDING + WEB canonique | 1 136 tests déclarés, 0 failure/error, 1 skip historique 3B ; sortie 0 |
| Complément Registration + monolithe canonique | 930 tests déclarés, 0 failure/error/skip ; sortie 0 |
| Architecture global canonique | 1 353 tests déclarés, 0 failure/error, 1 skip historique 3B ; module architecture : 139 sans skip ; sortie 0 |
| Assertions physiques renforcées + fixture Registration Result | 38 tests, 0 failure/error/skip ; sortie 0 |
| Nouveau → ancien répété deux fois avec drainage | 4 démarrages/publications, 4 PASS ; PREPARED, E=0, positions=0 |

Ces totaux de gates se recouvrent ; ils ne désignent pas autant de tests uniques. Full reactor
non requis/non lancé. Aucun crossing productif inattendu, aucune migration ni POM modifié.

Raccorder ensemble BusinessEvents, CommandTerminalEvents, BindingFacts et UserCreatedFacts,
auxiliaires et recoveries compris ; interfaces d'acceptation puis déclarations applicatives,
sans registre dans le métier ni capture technique cachée. Couvrir beans Spring **et constructeurs
`new`**, initializer Registration, TE/TR/TH, et façade propriétaire de l'append Business auxiliaire.
Confirmer manager/EMF/datasource communs en runtimes distribués et monolithe. Conserver P → B → S.

**Preuves/clôture :** V-D, chemins réels gagnants/rejetés/existing, retries vides, claim/CAS/fence
perdus, rollback initializer/finalisation ; références acceptées = déclarées = Events persistés =
positions sur ACTIVE **isolé**, avec ordre de famille exact. Omissions/fictives/doublons échouent
atomiquement en ACTIVE ; PREPARED les détecte par tests sans revendiquer sa contrainte inactive.
Même sémantique exceptions/versions/fences/retries ; tests ports hors owner/fermés ; aucun DML métier
après E sur compositions certifiées. Publication indépendante sans E toujours démontrée.

**Conditions de certification 4D issues des audits 4B/4C — prouvées en V-D dans le rapport :**

- **A — Poisoning des writers réels** : chaque writer durable couvert, chemins JDBC compris ;
  erreur interceptée et impossibilité de commit partiel.
- **B — Identité de transaction physique** : owners Command, Registration et auxiliaires ;
  même manager PRIMARY et même transaction physique JPA/JDBC dans toutes les compositions réelles.

Définition normative unique et règles de refus : [§6/4D, conditions A et B](../../../architecture/Event_Transactional_Sequencing_Step_4_Implementation_Plan_2026-10-19.md#4d--raccordement-complet-production-prepared).
Traçabilité 4C : **R2 → B ; R3 → A**. **R1** précise dans ce même §6/4D la vérification de densité,
de l'absence de contournement, des droits effectifs et de l'égalité exacte associations/publications acceptées.
Ces conditions précisent la validation existante de 4D ; elles ne rouvrent pas 4B, n'ouvrent aucune
dette technique 4B et ne changent ni le périmètre, ni les invariants, ni les critères de clôture 4C.

Tous les owners/writers certifiés ensemble ; pas de canary par famille. En production PREPARED,
owner/collecte/flush/fermeture/gardes actifs, E/positions encore inactifs. Le retour au runtime précédent
est répété sous drainage avant activation ; échec bloque le déploiement. Contrat exact : §6.1 du plan détaillé.

## 4E — Clôture de l'implémentation fonctionnelle

**4E FUNCTIONAL IMPLEMENTATION : CLOSED / PASS.**
**PRODUCTION ACTIVATION READINESS : NOT CERTIFIED. Activation production : NON AUTORISÉE.**
**Discovery efficiente : NEXT.**

Arbitrage approuvé le 2026-10-20 : « 4A–4E : implémentation fonctionnelle terminée,
sous réserve de validation finale des gates de clôture. » Cette réserve est levée par
les nouveaux gates PASS ci-dessous. Les objectifs de performance ne sont pas inventés ;
leur qualification est reportée après raccordement E2E et reste un gate de production.

Références : documentation initiale `864fdec8`, candidat publié `8e09b433` sur la branche
d'audit et intégré par avance rapide à la branche source ; 23 fichiers identiques, aucune
modification fonctionnelle ou de tests supplémentaire. [Rapport](../../../architecture/Event_Sequencing_Step_4E_2026-10-20.md),
[preuve fraîche](../../../testing/evidence/Event_Sequencing_Step_4E_Functional_Closure_2026-10-20.json),
[preuves initiales historiques](../../../testing/evidence/Event_Sequencing_Step_4E_2026-10-20.json),
[runbook](../../../operations/event-sequencing-runbook.md).

| Gate de clôture relancé | Tests | Échecs / erreurs | Skips |
|---|---:|---:|---:|
| V-E — COMMAND, WEB, BINDING, EVENT, PROJECTION, LKV | 1230 | 0 / 0 | 1 historique 3B |
| Architecture globale | 1388 | 0 / 0 | 1 historique 3B |
| Full reactor | 1388 | 0 / 0 | 1 historique 3B |

35 cas nouveaux 4E passent sans skip à chaque gate. Gate global et full requis pour cette
clôture majeure ; aucun nouveau franchissement de slice. DB CURRENT V31 et upgrade via les
tests existants sur PostgreSQL jetable, baseline trusted NONE ; aucune campagne SQL déployée.
Les deux retours PREPARED vers 4D et quatre démarrages/publications sont des preuves antérieures,
non réexécutées pendant cette clôture. Aucun test non exécuté n'est renommé PASS.

La clôture fonctionnelle conserve les contrôles de complétude quatre familles, associations
exactes/denses, ordre, frontières P/B/S/E, owner physique commun, rollback et UNKNOWN.
Elle ne certifie ni la capacité, ni un schéma non-public, ni les rôles ou données déployés.

## Réserves de production R1–R6

Ces réserves ne bloquent pas le développement de la Discovery efficiente. Elles bloquent,
selon leur applicabilité, la certification de l'activation en production. Elles restent
ouvertes jusqu'à leurs preuves propres ; R6 est actualisée ici et maintenue pour la suite.

| Réserve | Preuve ou action restant à fournir |
|---|---|
| **R1 — COMMIT UNKNOWN** | Compléter la vérification de l'idempotence métier après perte d'accusé COMMIT, avec corrélation stable et réconciliation durable. Conserver UNKNOWN sans replay aveugle ; les tests de transport existants ne prouvent pas tous les appels métier autonomes. |
| **R2 — PORTABILITÉ V31** | Vérifier les fonctions administratives dans un schéma PostgreSQL non-public si cette configuration est retenue ; aucune certification multi-schémas actuelle. |
| **R3 — PERFORMANCE** | Après raccordement E2E, qualifier la contention E et le backlog sur charges représentatives, avec échantillons suffisants et seuils fixés avant mesure. Sans données de production, utiliser des jeux synthétiques reproductibles à volumes/concurrences définis ; aucune capacité de production déduite. |
| **R4 — PRIVILÈGES** | Auditer les rôles réellement déployés, appartenances transitives, privilèges effectifs, propriétaires, paramètres et voies SQL. Conserver la frontière de confiance SQL appelée R1 dans l'audit 4A–4D ; cette ancienne R1 correspond ici à R4, pas à R1 COMMIT UNKNOWN. |
| **R5 — CUTOVER** | Répéter activation, backfill, sauvegarde/restauration et reprise sur une copie représentative des données de production avant activation, selon applicabilité. Aucune donnée de production disponible actuellement ; les bases synthétiques locales ne constituent pas cette preuve. |
| **R6 — DOCUMENTATION** | Runbook, rapport, canon, plans et références Git actualisés par cette clôture. La maintenance documentaire reste ouverte : joindre les futures preuves R1–R5 et mettre à jour les versions, commandes et conditions avant certification de production. |

L'activation reste administrative, explicite et propre à un environnement autorisé.
Avant tout flip : drainage/gel, inventaire/version et rôles, sauvegarde restaurable,
historique complet des quatre sources, frontières N consignées et contrôles exhaustifs.
UNKNOWN non réconcilié, écart de densité ou gel insuffisant ⇒ STOP. Après premier commit
ACTIVE : positions immuables et correction en avant, sans downgrade ni réparation automatique.

**4E clôt l'implémentation du séquencement ; la Discovery efficiente reste NEXT.**

## Vérification des futurs lots

Depuis `app/`, après déclaration du lot et lecture de la [politique normative](../../../testing/Reactor_Verification_Policy.md).
4B, 4C et 4D ont exécuté leurs commandes canoniques ; les commandes 4E sont suivies dans le rapport courant. Le détail §8 du plan fait autorité.

| Lot | Slices / commande de clôture | Gates supplémentaires |
|---|---|---|
| 4B | COMMAND : `./mvnw -pl runtime-command-consumption-worker -am test` | Global architecture non requis par défaut ; full non requis |
| 4C | COMMAND + BINDING : `./mvnw -pl runtime-command-consumption-worker,runtime-binding-consumption-worker -am test` | Global non requis par défaut ; full non requis |
| 4D | COMMAND + BINDING + WEB : `./mvnw -pl runtime-command-consumption-worker,runtime-binding-consumption-worker,runtime-web-api -am test` | Complément réel `./mvnw -pl runtime-registration-consumption-worker,runtime-monolith -am test` ; global architecture requis ; full non requis |
| 4E | COMMAND + WEB + BINDING + EVENT + PROJECTION + LKV : `./mvnw -pl runtime-command-consumption-worker,runtime-web-api,runtime-binding-consumption-worker,runtime-event-consumption-worker,runtime-task-consumption-worker,runtime-latest-known-version-consumption-worker -am test` | Registration/Results/monolithe au gate d'intégration ; global architecture et full requis pour clôture majeure |

Global architecture : `./mvnw -pl architecture-tests -am test` ; full : `./mvnw test`.
En 4B/4C, nouvelle dépendance/POM/frontière ⇒ réviser d'abord le gate selon la politique.
DB : 4B CURRENT/prototypes isolés ; 4C CURRENT + nouveau DDL ; 4D CURRENT/ACTIVE isolé ;
4E CURRENT + historique/cutover représentatif. `Trusted baseline: NONE` au HEAD : ne pas inventer
une baseline V29 certifiée ; utiliser une baseline trusted si certifiée ultérieurement.

## A1 — Cadrage de la Discovery efficiente

**CONCEPTION RETENUE le 2026-10-21 ; implémentation et certification non commencées.**
[Contrat A1](../../../architecture/Discovery_A1_Contract_2026-10-21.md) : Discovery et Consumption
mono-famille sur `(eventFamily, consumptionId, partitionId)`, partition par hash métier écrit
avec l'Event, scan de positions E contiguës, enrôlement de tous les Events locaux, checkpoint
et slots atomiques par **CAS sans verrou anticipé**. Flux et générations indépendants.

**A2.0 — Audit de simplification engagé (2026-10-21), documentaire seulement.** [Diagnostic](../../../architecture/Discovery_A2_0_Consumption_Model_Simplification_2026-10-21.md) : cible d'identité `consumptionId` pour les flux E ; examen de la suppression des identités polymorphes et de la provenance systématique ; outcomes terminaux et protections claim/CAS/fencing maintenus. Audit exhaustif des lecteurs et migrations multi-sources **encore requis** avant toute suppression DDL. Aucune migration ni test exécuté.

**A2 NEXT :** auditer la stabilité des quatre hash, ajouter/faire migrer le hash de
`USER_CREATED_FACT`, fixer l'encodage canonique `consumptionId` dans l'unicité actuelle des slots,
leur sélection par partition et le schéma/configuration active persistée ; puis implémenter et
prouver le parcours JDBC sur PostgreSQL. A3 reprend les slots, A4 orchestre, A5 qualifie les coûts.
Aucun script SQL, migration, runtime, gate ou activation n'a été livré par cette décision documentaire.

## Transition 4E → Discovery

4E rend disponible un parcours dense `position > c ORDER BY position LIMIT k`, complet après
frontière historique et atomique avec les effets. Restent à concevoir/implémenter checkpoint durable,
enrôlement, reprise des slots y compris acquis, partitionnement, requêtes et workers :
[chantier A du Next Plan](Next_Plan.md#chantier-a--discovery-efficiente).

Prérequis : index position/famille et UUID source issus de 4C/4E ; schéma/version de checkpoint,
identité consumer/route/partition, unicité des slots, accès indexé aux reprises par état/échéance/lease.
Choisir sur preuve les index supplémentaires ou métadonnées relationnelles minimales ; ne pas
matérialiser tous les payloads pour remplacer une anti-jointure.

Attendus : crash avant/après enrôlement, commit tardif et rollback producteur, UNKNOWN, Busy/lease,
plusieurs routes, nouveaux consumers et repartitionnement ; comparaison PostgreSQL reproductible
`EXPLAIN (ANALYZE, BUFFERS)`, lignes réellement lues, pages, WAL, backlog et latences.
Questions ouvertes : checkpoint/segmentation, équité reprise/nouveaux travaux, taille des lots,
parallélisme intra-worker, seuils de gain et coût des index. Aucune promesse chiffrée actuelle.

## Maintenance et contrôle

Ce plan suit l'avancement ; le canon suit les décisions ; Next Plan suit les dépendances futures.
Promouvoir le prochain chantier après consolidation de l'acquis et archivage traçable du précédent.
La [revue documentaire](Consolidation_Report_2026-10-20.md) conserve inventaire, résolutions et contrôle.
La consolidation initiale était documentaire. La livraison 4B est bornée à COMMAND/NONE, DB CURRENT
et prototypes isolés ; son [rapport](../../../architecture/Event_Transaction_Protocol_Step_4B_2026-10-20.md)
porte commandes, résultats et limites. Le [rapport 4C](../../../architecture/Event_Transactional_Sequencer_Step_4C_2026-10-20.md)
clôt V-C COMMAND/BINDING et l’upgrade CURRENT V30. Aucun gate global/full requis ; crossing NONE.
Le rapport 4D clôt désormais V-D, avec architecture globale requise exécutée et full non requis.
Action suivante : **Discovery efficiente**, premier lot à cadrer selon le Next Plan ; activation production NON AUTORISÉE. La campagne de performance représentative interviendra après raccordement E2E, avant toute certification de production.

### Décision A2.0 — retirer la provenance générique

**Approuvée le 2026-10-21, cible uniquement.** Suppression de l'obligation générique `ConsumptionInput`/`ConsumptionResult` et de leur écriture/lecture dans le suffixe commun ; les références causales utiles appartiendront à chaque Consumer. Conserver effet + CAS terminal atomiques, claims/leases/retry et outcomes techniques. **NEXT A2 :** recenser tous producteurs/lecteurs, préparer migration et suppression contrôlée des tables/ports/adapters, y compris compatibilité des consommateurs non-E et archives ; aucun effacement historique ou changement runtime avant preuve.
