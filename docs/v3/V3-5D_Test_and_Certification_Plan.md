# Pocoma V3-5D — Plan des preuves et de qualification

**Statut : OUVERT — conception des campagnes, aucun test V3 exécuté.**
**Date :** 2026-10-23. **Références :** [Canon](V3_Canon_and_Roadmap.md), [V3-5A — exigences](V3-5A_Global_Review_and_Requirements.md), [V3-5B — observabilité](V3-5B_Observability_Contract.md), [V3-5C — performance](V3-5C_Performance_Objectives.md).

## 1. Règles

- Planifier et documenter maintenant les preuves ; **ne pas coder de runtime/DDL V3 avant V3-CADRE validé**.
- Une exigence reçoit un oracle vérifiable, un environnement, des cas positifs/négatifs et une preuve à collecter. Reprendre les identifiants V3-5A.
- Distinguer réussite fonctionnelle, correction concurrente, tenue en charge, qualité de la mesure et opérabilité.
- Les anciennes suites servent de catalogue de propriétés, **pas de preuve V3**. Test survivant : conserver ; contrat remplacé : remplacer ; propriété retirée : suppression documentée.

## 2. Répartition initiale des preuves

| Axe | Outil prévu | Propriété / oracle |
|---|---|---|
| Admission Command et Identity | Bruno + JUnit E2E | AuthN/EUI, consentement Link E2 authentifié à l'admission, attestation externe à l'admission ; décisions WRITE, résultats durables |
| Domaine versions et historiques | JUnit + PostgreSQL | Sous-objets/version globale V, observedVersion, balances rationnelles exactes pour chaque V, lectures historiques |
| Événements et Consumption | JUnit + PostgreSQL | Publication dense/atomique ; slot et checkpoint Discovery ; claim/fencing ; rollback, crash, incertitude de commit, handover, redécouverte |
| READ et sécurité | Bruno + JUnit PostgreSQL | Matérialisations indépendantes, deux tasks créées ensemble, CurrentPot, Balance, visibilité, CurrentBinding, token associé à EUI authentifiée, minVersion, reset/replay |
| Intégration Keycloak réelle (configuration B) | Bruno + k6 ciblé | OIDC, parcours authentifiés, Registration/Link/Unlink jusqu'au binding utilisable, sans confondre coût d'émission et vérification de jeton |
| Performance configuration A | k6 + observabilité + PostgreSQL | Profil Pot moyen : p95 ≤ 500 ms à 1/s, ≤ 1 s à 10/s, ≤ 3 s à 50/s ; grand Pot : p95 ≤ 2 s nominal ; Identity : p95 ≤ 500 ms |
| Rattrapage | k6 + JUnit de contrôle + mesures serveur | 10 000 commandes initiales et toutes leurs matérialisations, arrivée 1 nouvelle commande/s, rattrapage ≤ 5 min |
| Contention sur Pot unique | JUnit concurrent PostgreSQL + k6 ciblé | Aucun effet perdu/double, ni commit partiel, CAS/retry corrects, convergence et résorption ; pas de SLO chiffré |
| Validité de l'observabilité | Assertions test et mesures corrélées | Placement réel des timers, issue des tentatives, rollback/retry, latence READ visible, cardinalité maîtrisée et histogrammes exploitables |

## 3. Précisions obligatoires avant gate

1. Construire la **matrice exhaustive identifiant V3-5A → cas → oracle → environnement → type de preuve → étape d'exécution V3-6/V3-7**.
2. Fixer échantillons, durées, échauffement, distribution des opérations, paramètres de pool/partition/Workers, isolation des sondes et reproductibilité des jeux de données.
3. Définir les **bornes de mesure côté client** (premier résultat READ prouvant la disponibilité, cadence de sondage, timeout, coût des sondes, clock skew), éviter de confondre p95 observé et moment exact du commit.
4. Prouver la **version cible** des projections immuables, et la progression au moins V des projections courantes, y compris si V+1 survient avant l'observation ; ne pas traiter une version courante écrasée comme une version historique accessible.
5. Fixer les conditions de génération et vidange des 10 000 commandes et de leurs tâches dérivées, en évitant un faux succès obtenu par la seule vidange des Command slots.
6. Détailler les cas de sécurité (Identity E1/E2, révocation READ retardée acceptée, token ≠ AuthN) et les crash points transactionnels.
7. Définir un gate de fiabilité des métriques : chaque frontière instrumentée testée positivement et négativement ; pas seulement présence d'une série Prometheus.
8. Conserver le diagnostic des écarts performance, sans rendre les objectifs « réussis » en modifiant les budgets a posteriori.

## 4. Sortie de V3-5D

**Non atteinte.** Sortie requise : matrice exhaustive, protocoles reproductibles, oracles et critères de PASS/FAIL, revue croisée avec V3-1 à V3-5C ; seulement ensuite proposer le jalon global V3-CADRE. Aucun PASS V3-7 anticipé.

## 5. Matrice exhaustive initiale V3-5A → preuves V3-5D — 2026-10-23

**Couverture : 24 exigences / 24 scénarios maîtres.** Chaque ligne est une **spécification de preuve**, pas un test écrit ni un PASS. Chaque scénario maître devra être décliné en cas positifs/négatifs et crash/races quand indiqués, avec références explicites à ses futures méthodes et rapports. PG = PostgreSQL réel, pas H2.

| Exigence | Scénario | Stimulus / variantes obligatoires | Support | Oracle de réussite |
|---|---|---|---|---|
| DOM-01 | D01 | Versions V successives avec mutation d'un seul sous-objet ; reconstruire V ancienne après V+2 | JUnit/PG + Bruno | Pot@V et références/sous-versions identiques au modèle attendu ; aucun recours à V−1 ; lecture historique stable |
| DOM-02 | D02 | No-op, multi-cibles, suppression, observedVersion périmée : cas compatible et réellement obsolète | JUnit/PG + Bruno | Transitions et V attendues ; rejet métier seulement lorsque l'état observé invalide l'intention ; aucun effet partiel |
| DOM-03 | D03 | Poids par défaut, fractions, dépenses et révisions en chaîne, V ancienne et nouvelle | JUnit/PG + Bruno | Égalité exacte rationnelle des soldes, conservation des totaux, Balance complète/immuable par (P,V) |
| ID-01 | I01 | Registration doublée, Link, Unlink, EUI déjà liée, token ancien/nouveau, rollback injecté | JUnit/PG + Bruno | Unicité EUI ; propriétaire/identité cible exacts ; token renouvelé et ancien invalidé conformément à l'état ; aucune mutation partielle |
| ID-02 | I02 | Link E1/E2 authentifiées et E2 consent à demande exacte ; usurpation E2, consentement réutilisé, entitlements révoqués après admission | Bruno/Keycloak + JUnit/PG | Admission rejetée si preuve absente/inadaptée ; réessai ne redemande pas consentement ; droit externe vérifié à admission uniquement, droit Pocoma courant à exécution |
| EVT-01 | E01 | Publieurs concurrents avec commit, abort et retards, pour chacune des quatre familles événementielles | JUnit/PG concurrent | Séquences globales par famille denses sur commits, aucune lacune après rollback ni inversion d'ordre durable |
| EVT-02 | E02 | Mutation command/identity avec panne avant commit, après commit, tentative sans requestId | JUnit/PG + Bruno | Mutation, event, résultat terminal, completion indivisibles ; pas d'événement mutable orphelin |
| EVT-03 | E03 | Inspection des 8 familles, publication d'un POT_EVENT, réenrôlement/duplication de tâches | JUnit/PG | Six familles métier + deux tasks READ ; identifiant déterministe ; unicité même sous concurrence |
| CON-01 | C01 | Scan événements par partition avec séquence globale entrelacée ; burnables PENDING et terminales | JUnit/PG | Slots correspondant aux sources éligibles ; checkpoint du dernier global inspecté ; aucune omission d'une source éligible |
| CON-02 | C02 | Crash transactionnel entre insertion slots et avance checkpoint ; redémarrages et scans répétés | JUnit/PG fault injection | Rollback ensemble ou commit ensemble ; aucun work perdu ni réinscription indue après complétion |
| CON-03 | C03 | Bascule lease partition après acquisition du slot ; ancien/nouveau Worker en concurrence | JUnit/PG interleavings | Fencing de partition gouverne Discovery/acquisition ; seul claim individuel courant peut finaliser |
| CON-04 | C04 | Expiration claim, takeover et deux finalisations ; réponse commit perdue | JUnit/PG fault injection | Au plus un commit d'effet + slot supprimé ; récupération par constat durable ; pas de double effet |
| CON-05 | C05 | Exception technique après préparation, rollback, release conditionnel, retryAfter, réacquisition | JUnit/PG | Aucun retry dans même claim ; nouvelle tentative recharge état/version ; aucune partialité ni boucle chaude |
| CON-06 | C06 | Propriétaires PRIMARY, JPA/JDBC et transactions de tous les writers et compositions | ArchUnit + JUnit/PG | Un seul commit physique par effet et slot/source ; fail-fast composition non conforme ; aucun 2PC implicite |
| PRJ-01 | P01 | Matérialisation CurrentPot V+1 avant V, sous-objets révisés et historiques | JUnit/PG + Bruno | Vue courante monotone ; références résolubles ; toutes les versions immuables attendues présentes |
| PRJ-02 | P02 | Calcul Balance V+1 avant V ; réexécution de tâche après concurrence | JUnit/PG | Balance(P,V) exacte, immuable, idempotente et conservée ; pas d'écrasement hors ordre |
| PRJ-03 | P03 | Visible users A→B→A ; matérialisation V+2 avant V+1 ; première version et writers concurrents | JUnit/PG interleavings | PotVisibilityState verrouillé ; seuls true courants comparés ; tombstones corrects ; aucune régression de version |
| PRJ-04 | P04 | Crash avant/après création des deux tasks, pendant finalisation de chacune | JUnit/PG fault injection | Deux tâches ensemble ou aucune ; effet/terminalisation/suppression de son slot atomiques |
| READ-01 | R01 | minVersion non atteinte, sous-objet absent, V historique, projections indépendantes en retard | Bruno + JUnit/PG | Donnée complète et cohérente ou indisponibilité explicite, jamais assemblage hybride ; aucun snapshot inter-Pots promis |
| SEC-01 | S01 | Token correct avec EUI incorrecte, token seul, Shareholder absent à V, liste visible falsifiée | Bruno/Keycloak + JUnit | Refus sans EUI authentifiée correspondante ; autoriser seulement shareholder sur Pot@V ; liste non autoritative |
| SEC-02 | S02 | Unlink WRITE validé, CurrentBinding READ encore ancien puis rattrapé | Bruno + JUnit/PG | Retard READ observable/accepté ; effet après propagation conforme ; ne pas revendiquer révocation instantanée |
| REC-01 | R01B | READ indisponible, reset projections+slots+checkpoints+tasks ; replay mêmes IDs, puis indépendant | JUnit/PG E2E | Reconstruction intégrale avant réouverture ; mêmes résultats ; nouvelle Consumption pour replay indépendant |
| OPS-01 | O01 | File hétérogène et claims expirés ; polling à vide et sous backlog prolongé | k6 + JUnit/PG + métriques | Aucune famine durable ; retries et reprise ; CPU/SQL/wakeups mesurés, absence de surcharge catastrophique explicitée |
| PERF-01 | F01 | Campagnes A/B profils petit/moyen/grand, 1/10/50 par seconde, rattrapage 10k, métriques | k6 + Bruno + PG + Prometheus | Quantiles E2E et branches, débit admis/terminé, erreurs et backlog mesurés ; comparaison aux SLO V3-5C ; instruments validés |

### Vérifications transversales et critères de sortie

- **Couverture vérifiée :** DOM-01, DOM-02, DOM-03, ID-01, ID-02, EVT-01, EVT-02, EVT-03, CON-01, CON-02, CON-03, CON-04, CON-05, CON-06, PRJ-01, PRJ-02, PRJ-03, PRJ-04, READ-01, SEC-01, SEC-02, REC-01, OPS-01, PERF-01. Aucun identifiant V3-5A omis.
- **Preuves négatives obligatoires :** refus d'identité/autorisation, rollback/coupure, ordre inversé, duplication/retry, retard READ et version remplacée ; les scénarios maîtres ne suffisent pas sans leurs variantes.
- **Performance :** E2E Pot moyen p95 500 ms à 1/s, 1 s à 10/s, 3 s à 50/s ; grand Pot 2 s à 1/s ; binding utilisable p95 500 ms ; rattrapage 10 000 commandes et projections ≤ 5 min sous 1/s. Charge soutenue sans backlog croissant ; pointe et résorption ; séparément un même Pot concurrent, sans SLO arbitraire.
- **Environnements :** A (PG 4 vCPU/8 Go + runtimes 4 vCPU/8 Go), B (+ Keycloak réel isolé), injection/observabilité sur ressources distinctes si possible. Établir durées, tailles statistiques, choix des partitions et protocoles répétables avant certification.
- **Observabilité :** chaque histogramme/timer/counter doit faire preuve de sa vraie frontière, notamment commit incertain, échec/retry, deux tâches READ, sondage E2E, forte cardinalité et export Prometheus. La seule existence d'une métrique ne vaut pas preuve.
- **Gate V3-CADRE :** la présente matrice est un premier inventaire traçable, **pas encore le plan exécutable complet** : aucun passage avant revue des variantes, paramètres reproductibles et oracles vérifiables. V3-6 fabrique et V3-7 exécute les preuves.

## 6. Protocole détaillé EVT-01 — séquences globales denses (spécification)

**Statut : protocole proposé, non exécuté.** Quatre séquences indépendantes : `POT`, `IDENTITY`, `COMMAND_COMPLETION`, `IDENTITY_COMPLETION`. Ces positions ne sont ni des versions métier ni un ordre total entre familles.

### Préconditions et oracle

- PostgreSQL réel ; transactions indépendantes, au moins deux connexions et deux threads distincts, barrières explicites aux frontières d'attribution, de commit et de rollback ; **aucun sleep ne sert de preuve d'entrelacement**.
- Initialiser chaque famille à un préfixe durable `1…N`; capturer le journal des événements committés et l'issue physique des transactions. Ne pas tirer l'ordre des commits d'horodatages applicatifs.
- Pour chaque famille, **sur le jeu de données dédié au test**, les positions committées, ordonnées, sont exactement `1…M`, sans trou ni doublon ; chaque événement conserve son eventId, son sujet et sa position. Répéter le test sur bases préfixées permet de vérifier la continuation, pas seulement le départ à 1.
- Quand T1 a obtenu une position inférieure à celle demandée par T2 sur **la même famille** et que T1 est physiquement non résolue, T2 ne peut committer sa position supérieure avant la résolution de T1. Tester cela par barrières et observation des états transactionnels, sans confondre ordre de démarrage et ordre de commit.
- **Aucune exigence d'ordre entre familles** : les compteurs et validations sont indépendants ; une transaction peut publier dans la famille de mutation puis dans la famille de complétion, dans une seule transaction physique.

### Cas déterministes

| ID | Entrelacement contrôlé | Assertion décisive |
|---|---|---|
| E01-A | Une transaction publie un événement et commit, puis une autre publie | Positions successives, uniques, stables et retrouvables |
| E01-B | T1 obtient position `n+1` et reste ouverte ; T2 tente de publier sur la même famille | T2 n'obtient pas une position supérieure **committable** pendant que T1 est indécise ; après commit T1, T2 peut committer `n+2` |
| E01-C | Même scénario, mais T1 rollback ; T2 continue | T2 obtient `n+1`, pas `n+2` ; aucune position consommée par l'abandon |
| E01-D | T1 réserve un lot de deux événements Identity dans sa transaction ; T2 publie en concurrence | Les deux positions du lot sont consécutives et l'ordre interne déclaré est conservé ; pas d'intercalation |
| E01-E | Command sans effet/rejet et demande Identity rejetée | Seule la famille de complétion avance ; aucune position de mutation consommée |
| E01-F | Publication de mutation et complétion, puis rollback forcé avant commit | Aucun événement des deux familles durable, et aucun compteur avancé ; terminalisation/résultat absents (recoupement EVT-02) |
| E01-G | Multiples transactions mixtes commit/rollback sur les quatre familles, ordre d'ouverture intercalé | Chacune des quatre suites demeure dense indépendamment ; les résultats reflètent uniquement les commits |
| E01-H | Charge concurrente répétée, transactions lentes, aborts, redémarrage de connexions | Même invariant de densité ; aucune perte, doublon, inversion dans une même famille, ni attente bloquée inexpliquée |

### Garde-fous sur l'interprétation

- Ne jamais accepter un test qui construit les positions avec `nextval()` PostgreSQL non transactionnel : les rollbacks y laissent des trous. Un test vérifie le **comportement public/durable**, pas une implémentation de compteur prescrite prématurément.
- L'affirmation « ordre des commits » est **intra-famille** et sur les publications participant au séquencement transactionnel ; il n'existe pas d'ordre inter-familles à certifier.
- Ne pas inférer un ordre physique de commit depuis `recordedAt`, un compteur local du test, un `sleep`, ou l'instant où un thread reçoit sa réponse. Les barrières doivent rendre la dépendance visible, et les assertions SQL vérifier l'état **après** résolution.
- Cas de deadlock/protocole : établir qu'après prise des verrous de séquence le chemin n'acquiert plus de nouveaux verrous métier ; couvrir les deux paires d'ordres prévues `POT → COMMAND_COMPLETION` et `IDENTITY → IDENTITY_COMPLETION` par des scénarios de contention et analyse du graphe d'acquisition (liens avec CON-06).
- Isoler l'éventuelle attente de verrou des budgets de performance de V3-5C et la mesurer ; aucun blocage infini toléré.

### Preuves à conserver et critère PASS

Données de départ et SQL de vérification de densité par famille, journal de l'ordonnanceur de test/barrières et des issues transactionnelles, assertions du nombre et de l'identité des événements, résultats de répétitions, trace des éventuels rollbacks et blocages. **PASS EVT-01** seulement si A–H passent sur PostgreSQL réel et si la protection d'ordre de verrous est vérifiée ; aucune implémentation ou test n'est réalisé à ce stade.

## 7. Protocole détaillé EVT-02 — atomicité de la demande (spécification)

**Statut : protocole proposé, non exécuté.** Périmètre : transaction terminale d'une `COMMAND` ou `IDENTITY_REQUEST`. Une demande ne devient `TERMINAL` qu'avec exactement un résultat durable et un événement de complétion ; ses mutations et événements éventuels sont dans **la même transaction physique**. Un rejet ou succès sans effet ne publie aucun événement de mutation. Une erreur technique avant commit ne doit pas être transformée en rejet terminal.

### Oracle commun et photographie de référence

Avant chaque scénario, relever un état cohérent du domaine, la demande `PENDING`, l'absence de résultat terminal et les positions/événements des familles concernées. Après résolution de la transaction, interroger PostgreSQL depuis **une connexion indépendante** pour comparer : état métier et versions, statut de demande, résultat terminal, événement(s) de mutation, événement de complétion, positions des séquences et slot de Consumption.

- **Commit d'une mutation :** toutes les modifications métier attendues, leurs événements portant le `requestId` exact, un résultat unique, la demande `TERMINAL`, l'événement de complétion unique et la suppression du slot de traitement apparaissent ensemble ; aucun événement supplémentaire.
- **Commit sans mutation (no-op ou rejet) :** état métier et versions inchangés, aucun événement de mutation ; demande `TERMINAL`, résultat approprié et une complétion durable. Un rejet ne masque jamais un échec technique.
- **Rollback :** état métier, versions, événements, positions de séquence, résultat, statut `PENDING` et slot durable restent dans l'état antérieur à la tentative ; le claim peut ensuite être relâché/expirer conformément à CON-05, **sans** finalisation durable.
- **Commit incertain côté Worker :** décider par lecture durable de l'état, jamais par répétition aveugle. Si `TERMINAL` et slot absent : constater l'unique effet ; si `PENDING` et slot présent : reprise comme nouvelle acquisition. Une réponse perdue ne suffit pas à conclure au rollback.

### Cas déterministes

| ID | Stimulus et frontière de panne | Assertions attendues |
|---|---|---|
| E02-A | `COMMAND` créant/modifiant un Pot (y compris multi-cibles) ; commit | Une seule nouvelle version globale et un `POT_EVENT` correspondant ; résultat, `COMMAND_COMPLETION_EVENT`, terminalisation et slot cohérents dans le même commit |
| E02-B | `COMMAND` réussie sans effet | Aucune nouvelle version/événement Pot ; résultat succès et complétion uniques, atomiques |
| E02-C | `COMMAND` rejetée par obsolescence métier | Aucune mutation/événement Pot ; résultat rejet métier et complétion uniques ; tentative technique non assimilée à rejet |
| E02-D | `Registration` gagnante | Création `PocomaUser` et rattachement EUI, **deux `IDENTITY_EVENT`** (User v1, EUI à la version atteinte), résultat et `IDENTITY_COMPLETION_EVENT` tous atomiques |
| E02-E | `Link` / `Unlink` gagnants et demande Identity rejetée | Respectivement événement EUI et complétion ; ou aucune mutation/événement Identity et seule complétion ; aucune mutation partielle |
| E02-F | Injection d'échec **avant la première écriture métier**, après mutation métier, après événement(s) de mutation, après résultat/terminalisation, après publication de complétion et avant commit | Dans chaque cas, rollback total ; aucune version, événement, résultat ni position orpheline |
| E02-G | Commit réussi mais réponse Worker perdue / erreur réseau simulée après résolution physique | Une seule terminalisation et un seul effet après récupération, aucune republication ; slot absent ; comparer aux cas rollback + réponse perdue |
| E02-H | Deux Workers tentent de finaliser la même demande après expiration/reprise de claim | Une seule transaction gagne ; aucun doublon d'effet, résultat ou complétion ; perdant rollback conformément à CON-04 |
| E02-I | Tentative d'écrire un événement de mutation sans `requestId`, ou avec `requestId` incorrect/inexistant | Opération refusée sans commit partiel ; contraintes métier et de persistance effectivement démontrées |
| E02-J | Crash/redémarrage après admission `PENDING` mais avant finalisation | Le traitement est retrouvable et termine une fois ; aucune complétion fantôme ; relation demande/résultat/événements conservée |

### Variantes de transactions et couplage des tests

- Les familles de publication sont distinctes : `POT → COMMAND_COMPLETION` ou `IDENTITY → IDENTITY_COMPLETION` dans le même commit, mais **aucun ordre global entre les quatre séquences**.
- Pour `Registration`, forcer un échec après le premier événement Identity et vérifier qu'aucun des deux ne persiste. Contrôler leurs `subjectVersion` et leur `requestId` commun.
- Vérifier les contraintes à la frontière **SQL réelle**, y compris lorsqu'un writer JDBC/JPA lève une erreur rattrapée par un appelant : l'unité physique doit être condamnée et ne pas committer d'effets partiels. La certification précise du manager commun et de l'empoisonnement des writers est conjointe avec CON-06.
- Pour distinguer crash avant commit et réponse perdue après commit, orchestrer les points de coupure à l'aide de barrières/fault injection déterministes, sans en inférer le résultat uniquement de l'exception remontée au Worker.
- Ne pas supposer qu'un `CommandResult` ou `IdentityRequestResult` READ est immédiatement visible après le commit WRITE : leur matérialisation indépendante relève de PRJ/READ ; EVT-02 prouve l'atomicité **WRITE**.

### Preuves attendues et PASS

Conserver les identifiants de demandes, snapshots SQL avant/après, journaux d'entrelacement/fault injection, transactions et issues observées, comptages/positions par famille, assertions d'unicité et contraintes, ainsi que les résultats de répétitions. **PASS EVT-02** exige E02-A à E02-J sur PostgreSQL réel, un résultat terminal unique par demande durablement terminale, l'absence d'effet partiel à toutes les frontières injectées et la récupération correcte des commits incertains. Aucun test ni résultat n'est revendiqué ici.

## 8. Protocole détaillé EVT-03 — huit familles et identités déterministes des tâches READ

**Statut : protocole proposé, non exécuté.** Références V3-2, V3-3, V3-4 ; recoupement avec PRJ-04 et CON-01/02. Le catalogue compte **huit familles logiques** : deux demandes burnables métier (`COMMAND`, `IDENTITY_REQUEST`), quatre familles événementielles non-burnables (`POT_EVENT`, `IDENTITY_EVENT`, `COMMAND_COMPLETION_EVENT`, `IDENTITY_COMPLETION_EVENT`), et deux familles internes READ burnables (`POT_BALANCE_TASK`, `POT_VISIBILITY_TASK`). Les tâches READ ne créent **aucune cinquième séquence événementielle** et chacune possède sa Consumption destinataire unique.

### Préconditions et oracles

- Un `POT_EVENT` durable identifié par `eventId` ; une définition de Consumption de préparation identifiée par `preparationConsumptionId`.
- Pour chaque événement Pot et chaque préparation, exactement **deux identités de tâches** sont calculées à partir du triplet `(preparationConsumptionId, potEventId, taskType)`, une pour `BALANCE` et une pour `VISIBILITY`. Le même triplet redonne la même identité ; deux triplets distincts ne doivent pas désigner la même tâche.
- L'opération READ de préparation est **atomique** : création des deux tâches et suppression du slot de préparation dans le même commit. Un rollback ne laisse ni tâche nouvelle, ni slot supprimé.
- Une tâche est burnable `PENDING → TERMINAL` ; sa matérialisation, sa terminalisation et la suppression de **son propre slot** forment une transaction atomique, **indépendante** de la tâche sœur. La projection Balance ne conditionne pas le commit de visibilité et inversement.
- Une nouvelle Consumption de préparation (nouvel ID) lors d'un replay indépendant engendre de nouvelles identités de tâches ; reconstruire READ à blanc de façon cohérente permet de réutiliser les IDs de l'ancienne Consumption, car le reset a supprimé tâches, slots, projections et checkpoints (REC-01).

### Cas déterministes

| ID | Stimulus / situation | Preuve décisive |
|---|---|---|
| E03-A | Inventaire runtime des sources, de leur nature et des Consumptions destinataires | Exactement huit familles logiques, les quatre événements séquencés, quatre burnables sans séquence ; aucun mécanisme de séquence introduit pour les tâches |
| E03-B | Préparer un seul `POT_EVENT` | Deux tâches distinctes, de types attendus, référant exactement au même événement et à la même Consumption de préparation |
| E03-C | Réexécuter la même préparation à partir du même triplet (duplication/retry et tentative de réenrôlement) | IDs identiques et cardinalité toujours deux, aucune tâche supplémentaire, aucune création en double |
| E03-D | Deux préparations concurrentes du même événement pour la même Consumption | Un seul ensemble de deux tâches durable, un seul slot de préparation finalisé ; perdant rollback ou traitement idempotent sans effet supplémentaire |
| E03-E | Injection d'échec après création de Balance et avant Visibilité, puis après les deux et avant suppression du slot | Rollback intégral : aucune tâche durable et slot intact, puis nouvelle tentative crée exactement deux tâches |
| E03-F | Injection d'échec après suppression du slot, avant commit, puis commit réussi avec réponse perdue | Après rollback, slot et zéro tâche ; après commit réussi, slot absent et exactement deux tâches ; pas de republication aveugle |
| E03-G | Deux Workers finalisent indépendamment Balance et Visibilité, avec panne d'un seul | Succès durable de l'une possible sans l'autre ; chacune lie atomiquement projection, statut TERMINAL et suppression de son slot ; aucun effet double |
| E03-H | Replay indépendant avec un nouveau `preparationConsumptionId` | Nouveaux taskIds malgré même `potEventId` et `taskType`, sans collision ; périmètre de la nouvelle Consumption clairement distinct |
| E03-I | Reset READ cohérent et reconstruction avec les mêmes consumptionIds | Les tâches sont recréées avec leurs anciens IDs dans une base READ nettoyée ; aucun résidu de slot, checkpoint ou projection de l'ancien run |
| E03-J | Traitement hors ordre de plusieurs `POT_EVENT` d'un même Pot | Les tâches restent uniques par événement ; préparation hors ordre n'impose ni calcul de Balance incrémental ni recul de PotVisibilityState ; assertions de résultats renvoyées à PRJ-02/03 |

### Méthode et limites de preuve

Utiliser **PostgreSQL réel**, barrières déterministes pour injections de panne et concurrence, assertions SQL des transactions/contraintes d'unicité et vérifications des projections READ. Réaliser au moins une vérification depuis une connexion indépendante après chaque commit ou rollback. Inclure les chemins de redémarrage et reprise.

Ne pas transformer une Consumption logique en table obligatoire, et ne pas déduire la cardinalité des familles du nombre de tables. L'unicité doit être protégée durablement, pas seulement par un test `exists` avant insertion. Ne pas déduire le caractère burnable d'une tâche de sa disparition physique : la tâche durable reste TERMINAL selon le contrat et c'est **son slot de travail** qui disparaît.

**PASS EVT-03** : cas E03-A à E03-J satisfaits et invariants d'atomicité/identité démontrés, sans doublons, pertes ni nouvelle séquence événementielle. Aucun test n'est exécuté à cette étape du cadrage.

## 9. Protocole détaillé CON-01 — découverte exhaustive par partition

**Statut : protocole proposé, non exécuté.** Contrat V3-4 : la Discovery crée des slots durables à partir des références de sources ; l'Engine seul charge ensuite les données métier. Les quatre familles événementielles sont **non-burnables**, avec chacune sa séquence **globale** dense et un checkpoint de **positions examinées**, propre à `(consumptionId, partitionId)`. Les quatre familles burnables (`COMMAND`, `IDENTITY_REQUEST`, `POT_BALANCE_TASK`, `POT_VISIBILITY_TASK`) sont découvertes par état `PENDING` et absence de slot, **sans checkpoint**. Les partitions sont fixes, le routage par `partitionHash` stable, et une seule propriété effective autorise l'enrôlement.

### Oracles non-burnables

- Pour une Consumption C et sa partition P, le checkpoint `K(C,P)` (initialement zéro) signifie : **toutes les positions globales ≤ K ont été examinées** selon le prédicat de partition de C, et toutes celles éligibles qui devaient être enrôlées ont leur slot ou ont été correctement terminées. Il **ne** signifie pas que les événements de ces positions ont été exécutés.
- Dans une fenêtre globale bornée `(K,H]`, la Discovery filtre le routage vers P, crée les slots de **tous** les événements correspondants et avance le checkpoint à la **dernière position effectivement couverte**, y compris lorsque aucun événement de la fenêtre n'appartient à P. Une fenêtre vide n'autorise pas l'avancement au-delà d'une borne prouvée sûre.
- La borne H doit être un **préfixe durable visible sans trou de publication encore possible** ; l'existence d'une position élevée, un maximum observé ou une séquence réservée non committée ne suffit pas à justifier un saut par-dessus une position qui pourrait apparaître ensuite. La garantie de densité EVT-01 est une précondition ; les tests doivent vérifier la cohérence de la borne utilisée, sans prescrire d'index ni un algorithme SQL particulier.
- La création des slots requis et l'avance de K sont dans **une même transaction de la base des effets**, protégées par le contrôle du checkpoint et de la propriété de partition. Une lecture préparatoire extérieure à cette transaction doit être revalidée avant commit si son snapshot ou sa propriété est devenu obsolète.
- Si le plafond souple de slots présents bloque un lot, **ne pas sauter les événements éligibles non enrôlés** ; ne progresser que sur un préfixe effectivement couvert. Un événement déjà en dessous de K ne doit jamais être réenrôlé par la Discovery ordinaire.

### Oracles burnables

- Pour chaque source `PENDING` routée vers P, créer au plus un slot durable de clé `(consumptionId,consumableId)`. Une source `TERMINAL` n'est jamais nouvellement enrôlée.
- `PENDING` avec slot libre/claimé/expiré signifie **déjà enrôlé** et ne doit pas générer de doublon. `PENDING` sans slot est éligible. Le plafond soft peut retarder l'enrôlement, sans le faire perdre.
- Il n'y a **ni checkpoint burnable ni besoin de parcourir l'histoire des états TERMINAL**. Une tâche READ porte sa seule Consumption destinataire, une demande métier sa Consumption configurée.

### Cas déterministes

| ID | Stimulus / entrelacement | Preuve décisive |
|---|---|---|
| C01-A | Initialiser `K=0`, publier des événements sur plusieurs partitions entremêlées d'une même séquence | Chaque partition découvre exactement ses références éligibles ; son checkpoint peut franchir des positions d'autres partitions sans y créer de slot |
| C01-B | Fenêtre `(K,H]` ne contenant que des événements d'autres partitions | Avance légitime à H malgré zéro slot pour P ; aucun blocage par absence d'événement local |
| C01-C | Limiter la fenêtre globale à N positions ; injecter plusieurs centaines d'événements entremêlés | Progression par fenêtres bornées, aucune position éligible omise aux frontières de lots |
| C01-D | Transaction de publication T1 en suspens sur la position suivante, T2 concurrente, puis rollback ou commit T1 | Aucun checkpoint ne passe une position susceptible d'apparaître ensuite ; reprise découvre l'événement durable réel ; articulation avec EVT-01 |
| C01-E | Deux Discoveries de la même Consumption/partition lisent le même K puis tentent d'enrôler | Une seule avance cohérente, slots uniques, aucune perte ; tentative obsolète rechargée/abandonnée |
| C01-F | Propriété de partition transférée après lecture préparatoire, avant transaction d'enrôlement | Ancien propriétaire ne commite ni slots ni checkpoint ; nouveau propriétaire reprend à K durable |
| C01-G | Plafond souple atteint au milieu d'un lot comportant des événements éligibles | Pas de saut au-delà d'un événement non enrôlé ; progression reprend après libération de capacité |
| C01-H | Slot non-burnable finalisé et supprimé, puis nouveau cycle de Discovery | Aucun réenrôlement si sa position est ≤ checkpoint ; absence du slot seule ne vaut pas preuve de fin |
| C01-I | Pour chacune des quatre familles burnables : sources PENDING avec et sans slot, sources TERMINAL, lots répétés | Seules les PENDING sans slot sont enrôlées ; unicité même avec Discoveries concurrentes ; aucun checkpoint |
| C01-J | PENDING avec claim expiré ou retryAfter futur, et plafond atteint | Slot existant respecté ; aucune seconde ligne, aucun contournement de retryAfter par Discovery |
| C01-K | Nouvelle Consumption sur un historique conservé, avec checkpoints absents | Checkpoints à zéro pour cette Consumption ; scan indépendant et enrôlement selon la nouvelle identité sans perturber l'ancienne |
| C01-L | Campagne avec beaucoup d'événements hors partition, slots actifs, sélections répétées | Mesurer références lues, parcours de fenêtres, écritures, SQL, temps de cycle et mémoire ; pas de charge métier complète dans Discovery |

### Méthode, preuves et conditions PASS

JUnit avec **PostgreSQL réel**, plusieurs connexions, barrières contrôlant lecture préparatoire, acquisition de propriété, commit et rollback. Vérifier depuis une connexion indépendante `K`, la liste complète des sources éligibles, les clés de slots, leur unicité et l'absence de slots d'autres partitions. Injecter les publications et les modifications de statut aux frontières pertinentes. Les scénarios de crash et l'atomicité complète de checkpoint/slots sont approfondis dans **CON-02**.

Conserver traces des fenêtres globales examinées, séquences, partitions, positions, checkpoints avant/après, slots et issues transactionnelles, ainsi que mesures de lectures/SQL. **PASS CON-01** requiert C01-A à C01-L, exhaustivité, non-réenrôlement et distinction prouvée entre les deux types de sources. Aucun résultat d'exécution n'est revendiqué.

**Point de vigilance de conception :** le contrat ne prescrit pas encore la construction SQL de la borne sûre H ni la stratégie de réservation de capacité sous concurrence. Leur réalisabilité sans trou, sans famine et sans scans coûteux doit être justifiée dans le plan d'implémentation V3-6 et vérifiée en V3-7 ; toute incompatibilité avec le contrat doit revenir devant V3-CADRE plutôt qu'être cachée par un test permissif.

## 10. Protocole détaillé CON-02 — atomicité checkpoint / slots et preuve de complétion

**Statut : protocole proposé, non exécuté.** Le checkpoint non-burnable `(consumptionId,partitionId,position)` désigne le dernier événement **examiné**, non le dernier traité. Sa progression et l'enrôlement de **tous les slots requis dans la fenêtre** sont dans une seule transaction physique de la base des effets. L'exécution d'un slot le supprime uniquement dans la même transaction que les effets et leurs validations. Aucun checkpoint de complétion ni état DONE n'est ajouté.

### Invariants et oracle

Pour chaque événement E non-burnable de position p, destiné à Consumption C et partition P, après franchissement durable de p par `K(C,P)` :

- Tant que les effets de E ne sont pas durablement appliqués, il existe **exactement un slot** `(C,E)` (libre, réclamé, expiré ou en retry).
- Après suppression définitive de ce slot, les effets de E sont durablement appliqués et ne peuvent plus être annulés isolément.
- La proposition `K ≥ p ET slot absent ⇒ effets déjà committés` n'est correcte **que sous les hypothèses de conservation et d'absence d'autres chemins de suppression**. Avant franchissement du checkpoint, l'absence de slot ne prouve rien.
- Le scan d'une fenêtre, la protection de K/propriété de partition, l'insertion des slots nécessaires et l'avance de K se résolvent ensemble par commit ou rollback ; un lot interrompu n'est jamais à moitié découvert.
- Les redémarrages ne recréent pas des slots d'événements déjà couverts, ni ne sautent les événements jamais enrôlés.

### Cas déterministes

| ID | Injection / entrelacement | Assertions depuis une autre connexion PostgreSQL |
|---|---|---|
| C02-A | Échec avant l'insertion du premier slot d'une fenêtre | K inchangé ; aucun slot de cette fenêtre ; reprise crée tous les slots attendus |
| C02-B | Échec après insertion du premier slot mais avant les suivants | Rollback intégral, K inchangé et aucun slot partiel |
| C02-C | Échec après insertion de tous les slots mais avant l'avance de K | Aucun slot durable et K inchangé après rollback |
| C02-D | Échec après avance de K mais **avant commit** | Ni K avancé ni slots créés après rollback |
| C02-E | Commit complet de la fenêtre puis réponse de commit perdue | K avancé et ensemble exact des slots présent ; retry de Discovery ne crée ni doublon ni perte |
| C02-F | Fenêtre sans événement de cette partition, mais avec positions globales couvertes | K peut avancer atomiquement sans nouveaux slots ; les autres partitions demeurent inchangées |
| C02-G | Deux Discoveries concurrentes ont lu K initial, l'une commit, l'autre tente de finaliser un snapshot périmé | Une seule avance cohérente, l'autre recharge ou abandonne ; slots exacts, K monotone |
| C02-H | Transfert de propriété de partition entre préparation de la fenêtre et transaction finale | Ancien propriétaire ne commite ni K ni slots ; nouveau propriétaire reprend sur état durable |
| C02-I | Un slot traité avec effet durable et suppression, mais panne **avant commit** | Rollback de l'effet et de la suppression : slot reste présent |
| C02-J | Même effet/suppression avec commit réussi et accusé de réception perdu | Slot absent, effets durables, aucune seconde exécution à effet |
| C02-K | Après traitement de certains slots d'une fenêtre, redémarrer Discovery puis Engine | K reste à la position examinée ; seuls les slots inachevés restent ; aucun slot terminé ne réapparaît |
| C02-L | Simuler un reset partiel (slots seuls, checkpoints seuls, projections seules) ou un changement de Consumption avec réutilisation fautive de son ID | Opération administrative non autorisée/refusée ou détection explicite de violation ; seul le reset READ coordonné (REC-01) ou le nouvel ID de Consumption est admis |

### Protocole d'injection et de constat

- PostgreSQL réel, connexions et transactions indépendantes. Des barrières arrêtent le flux aux frontières **pré-commit** ; injecter exceptions, rollback explicite, déconnexion, interruption du Worker et perte simulée de la réponse de commit. Ne pas assimiler une déconnexion à la preuve d'un rollback : inspecter ensuite l'état durable.
- Capturer `K`, liste des sources éligibles de la fenêtre, clés `(consumptionId,consumableId)` attendues, slots effectivement persistés, effets métier/projection, statuts des tâches si pertinents, et issues transactionnelles.
- La lecture préparatoire de Discovery ne crée aucun état de complétion. Une éventuelle lecture cross-base des sources ne rend **pas** la transaction finale distribuée : checkpoint et slots doivent appartenir à l'autorité transactionnelle des effets de cette Consumption.
- Vérifier les contraintes d'unicité et les seuls chemins autorisés de suppression ; un `DELETE` administratif direct hors transaction de reset briserait l'oracle, et ne doit pas être présenté comme un comportement sûr.
- Couvrir une publication concurrente et la borne de préfixe sûr avec CON-01 ; l'ordre des verrous et la propriété de partition sont approfondis avec CON-03/06.

### Critère PASS

**PASS CON-02** requiert C02-A à C02-L, absence de demi-enrôlement et de demi-finalisation, monotonie de K, exactitude des slots et récupération sans travail perdu ni double effet. Exiger une preuve que la discipline de suppression/reset est respectée par **tous** les writers, pas seulement par le chemin nominal. Aucun test V3 n'a encore été implémenté ou exécuté.

## 11. Protocole détaillé CON-03 — propriété de partition distincte du claim de slot

**Statut : protocole proposé, non exécuté.** Le lease de partition (propriétaire, `ownerToken`, échéance) autorise uniquement **Discovery et acquisition de nouveaux slots**. Le claim de slot (`claimToken`, `claimedUntil`) autorise renouvellement et finalisation de **ce slot**, même si le Worker a entre-temps perdu la propriété de partition. Le `partitionOwnerToken` n'est pas recopié dans le slot et aucun verrou de partition n'est exigé dans la transaction de finalisation. L'expiration du claim ne permet sa reprise qu'après vérification transactionnelle sous verrou du slot.

### Oracles de correction

- À un instant donné, au plus un propriétaire de partition possède le droit effectif d'enrôler ou d'acquérir. Un ancien `ownerToken` n'autorise aucune nouvelle Discovery ni acquisition après takeover.
- La perte de partition **n'invalide pas** un claim déjà acquis : l'ancien Worker peut le renouveler et finaliser sous son `claimToken` valide. La possession actuelle de la partition n'est **pas** une précondition de finalisation.
- Un nouveau propriétaire peut acquérir les slots libres ; il ne peut prendre un claim encore valide appartenant à l'ancien Worker. Un claim expiré peut être remplacé par un nouveau token, avec condition et sérialisation SQL.
- La validité du claim est contrôlée à **l'acquisition du verrou exclusif final sur le slot**, à une date DB fraîche après toute attente de verrou. Une fois la protection obtenue et le verrou détenu jusqu'au commit/rollback physique, l'expiration murale ultérieure ne suffit pas à invalider ce commit.
- Renouvellement, takeover et finalisation sur **le même slot** sont sérialisés. Un `claimToken` remplacé ne peut ni renouveler, ni libérer, ni finaliser le nouveau claim.
- L'expiration de partition n'entraîne **aucune reprise anticipée des claims individuels**. Les bornes de temps de partition et de claim sont indépendantes.

### Cas PostgreSQL déterministes

| ID | Entrelaçement contrôlé | Oracle |
|---|---|---|
| C03-A | Worker A propriétaire acquiert slot S ; transfert de partition à B pendant que le claim de A est encore valide | B peut découvrir/acquérir d'autres slots mais **pas S** ; A peut terminer S ; aucune consultation de propriété de partition à la finalisation |
| C03-B | Après transfert, A tente une nouvelle Discovery et une nouvelle acquisition avec son ancien ownerToken | Les deux sont refusées sans création de slot ni changement de claim |
| C03-C | Après transfert, A renouvelle son claim S encore valide, alors que B renouvelle son lease de partition | Renouvellement de S possible sans droit de partition ; les deux opérations indépendantes ne se bloquent pas par un verrou de partition final |
| C03-D | Lease de partition A expiré, claim S non expiré ; B devient propriétaire | B ne reprend pas S avant expiration propre de `claimedUntil` |
| C03-E | Claim S expiré ; B tente takeover pendant que A tente le renouvellement tardif | Un seul gagnant selon verrou/conditions ; claim expiré **non ressuscité** ; ancien token incapable de finaliser |
| C03-F | A acquiert verrou final de S avec claim valide ; `claimedUntil` passe pendant le commit encore en cours ; B tente takeover | Le commit A peut réussir sous verrou ; B attend puis constate slot absent ou issue durable et ne produit pas de double effet |
| C03-G | A attend le verrou de S et son claim expire **pendant l'attente** ; B est en position de reprendre | A doit constater l'expiration à l'acquisition effective du verrou et ne peut finaliser grâce à une lecture antérieure de l'horloge DB |
| C03-H | B remplace le claim expiré de S avant que A prenne le verrou final ; A revient avec ancien token | A ne finalise ni ne libère S ; seul token B peut gouverner le traitement |
| C03-I | B perd lui-même sa partition après takeover de S mais garde un claim S valide | Même invariant récursivement : claim B demeure indépendant et peut terminer |
| C03-J | Expiration, takeover et crash/commit incertain pendant la finalisation | Observer slot et effets depuis connexion indépendante ; un seul traitement durable, aucune réouverture ou reprise anticipée |
| C03-K | Plusieurs partitions fixes et deux Workers partagent la même déclaration de Consumption ; alternance des propriétaires | Acquisitions exclusivement autorisées par propriétaire effectif de chaque partition ; claims anciens valides et routage stable |
| C03-L | Métriques et traces d'une campagne répétée avec changements de propriétaire et claims de durées différentes | Aucun double commit ; taux de takeover, attente de verrou, reprises et expirations correctement observables |

### Méthode et pièges à exclure

JUnit multi-connexions contre **PostgreSQL réel**, barrières contrôlant prise du verrou, tentative de renouvellement, takeover, commit et rollback. Utiliser une horloge DB maîtrisable / délais d'expiration explicites et relecture DB *après verrou* ; ne pas fabriquer la preuve avec des `sleep` probabilistes. Contrôler à la fois le contenu durable des slots, les tokens (dans les assertions internes uniquement), les ownerTokens et les effets finaux. Ne jamais exposer ces tokens en métriques cardinales ou logs sensibles.

Une stratégie fondée sur `now()` PostgreSQL pris au **début d'une longue transaction** ne suffit pas à C03-G ; la validation doit employer une horloge dont la fraîcheur après attente est prouvée. Le contrat ne prescrit ni DDL ni SQL exact : l'implémentation devra démontrer cette propriété.

C03-F ne permet **pas** à un claim déjà expiré avant protection finale d'être accepté : la distinction porte sur l'instant d'obtention du verrou final. Le transfert de partition ne constitue pas un motif de rollback d'une transaction déjà protégée.

**PASS CON-03** : C03-A à C03-L réussis, absence de reprise anticipée et de double effet, validation temporelle au verrou final, absence de dépendance de finalisation à l'ownerToken de partition. Recoupements obligatoires avec CON-04 (commit unique) et CON-06 (transaction physique). Aucun test V3 n'est exécuté à ce stade.

## 12. Protocole détaillé CON-04 — finalisation unique et commit incertain

**Statut : protocole proposé, non exécuté.** Un slot de clé `(consumptionId,consumableId)` ne peut finaliser qu'une fois. La transaction physique finale protège le `claimToken` encore valide au **moment d'acquisition effective du verrou final**, les invariants de domaine, les effets de la Consumption et la suppression du slot. La suppression n'est jamais une étape indépendante. Une réponse de commit perdue n'autorise pas une réexécution aveugle.

### Oracles

- **Au plus un effet durable** pour une clé de travail, même si plusieurs Workers ont préparé le même calcul ou si une tentative a reçu un résultat de commit inconnu.
- La finalisation doit constater le **claim courant** sous protection exclusive ; un ancien token, un claim expiré avant protection ou un slot absent interdit le commit des effets de ce traitement.
- La suppression du slot et les effets se produisent **ensemble** ou pas du tout ; si la source est burnable, sa transition `PENDING → TERMINAL` et son résultat éventuel partagent la transaction finale ; si elle est non-burnable, `checkpoint ≥ position` et slot absent ne deviennent preuve de complétion qu'avec les invariants CON-02.
- Une fois la protection finale acquise correctement, un takeover concurrent ne peut pas intervenir avant la résolution physique du commit/rollback. Le dépassement de `claimedUntil` **après** la prise du verrou n'annule pas rétroactivement le droit acquis.
- L'Engine ne recrée jamais de slot pour résoudre un commit inconnu. Il vérifie l'état durable : slot absent implique une finalisation constatée sous les invariants ; slot présent implique une éventuelle nouvelle acquisition complète selon son claim et `retryAfter`. Un état inconnu local n'est pas un nouveau statut durable.

### Cas PostgreSQL déterministes

| ID | Entrelacement ou faute injectée | Assertion décisive |
|---|---|---|
| C04-A | Deux Workers préparent le même slot et tentent la validation avec le même token | Une seule transaction applique l'effet ; le second ne peut pas appliquer une seconde fois après disparition du slot |
| C04-B | Claim de A expiré ; B reprend avec nouveau token avant finalisation de A | A ne peut ni supprimer le slot ni écrire ses effets ; seul B peut finaliser |
| C04-C | A verrouille le slot valide puis B tente un takeover après franchissement de l'échéance | B est sérialisé derrière A ; après commit, B voit slot absent et n'applique aucun effet |
| C04-D | A obtient le verrou mais déclenche une exception après écriture d'une partie des effets | Rollback des effets, source terminale éventuelle et suppression ; slot encore présent pour reprise |
| C04-E | A commite complètement, mais l'accusé de commit est perdu ; Worker redémarre | Lecture durable détecte slot absent et effets complets ; aucun effet ni événement supplémentaire |
| C04-F | A rollback, mais sa connexion est coupée et le résultat est inconnu côté application | Lecture durable retrouve slot présent et effets absents ; reprise conditionnelle, nouvelle acquisition et nouvelle tentative |
| C04-G | Un claim ancien tente de renouveler ou libérer un slot désormais repris par B | Aucune modification de l'état de B ; le token courant n'est pas écrasé |
| C04-H | Propriété de partition transférée alors que A détient encore un claim valide | A reste autorisé à finaliser ; la propriété courante de partition n'est pas consultée comme fence final |
| C04-I | Deux exécutions de la même demande burnable se disputent le slot avec fault injection sur `TERMINAL` et résultat | Une seule terminalisation et un seul résultat ; jamais `TERMINAL` sans résultat correspondant |
| C04-J | Deux tâches READ concurrentes visent des versions différentes ou la même version | Chaque slot se termine une fois ; idempotence des projections et ordre/version assurés par PRJ-02/03, sans imposer l'ordre des slots |
| C04-K | Concurrence répétée, takeover, rollbacks et interruptions de Workers | Nombre d'effets et publications conforme aux seules finalisations committées ; aucune duplication ni travail durablement perdu |
| C04-L | Revue des voies SQL d'écriture/suppression du slot et cas d'administration | Aucune voie ne supprime ou ne finalise hors transaction protégée ; reset coordonné READ seul cas d'effacement administratif permis (REC-01) |

### Organisation des preuves

JUnit/PostgreSQL réel avec **connexions indépendantes**, barrières de synchronisation sur acquisition du claim, tentative de verrou final, vérification temporelle post-verrou, mutation partielle et commit. Les délais d'expiration sont contrôlés ; les `sleep` ne constituent pas une preuve d'ordre. Enregistrer snapshots SQL avant/après, tokens uniquement dans les assertions privées, nombres d'effets et positions événementielles, issue réelle de commit et observations côté Worker.

Distinguer explicitement : (a) réponse d'échec avant commit, (b) rollback physiquement certain, (c) commit réussi/réponse perdue, (d) issue momentanément inconnue. **Une erreur réseau n'est pas une preuve d'échec du commit.** Si une observation immédiate ne tranche pas, la récupération attend la résolution de la transaction/expiration du claim et relit l'état durable, sans créer `UNKNOWN` persistant.

### Critère PASS

**PASS CON-04** exige C04-A à C04-L, une seule finalisation durable par slot, effets et suppression indivisibles, absence de double effet après takeover ou commit incertain, et sécurité des sources burnables/non-burnables. Preuves conjointes CON-02, CON-03, EVT-02 et CON-06 obligatoires. Aucun test V3 ni résultat de certification n'est revendiqué.

## 13. Protocole détaillé CON-05 — une acquisition, une tentative complète, puis reprise différée

**Statut : protocole proposé, non exécuté.** Référence V3-4 : chaque acquisition de slot ouvre **une seule tentative complète**, avec rechargement du consommable, des faits autoritatifs et du contexte de version. Après échec technique : rollback intégral de la transaction finale, cession **conditionnelle** du claim et fixation de `retryAfter`, puis une **nouvelle acquisition** et une nouvelle exécution. Pas de retry immédiat sous le même claim, pas d'état durable `FAILED` ou `UNKNOWN`, pas de transformation d'un problème technique en rejet métier.

### Oracles

- Toute tentative possédant un `claimToken` précis effectue au plus une exécution/finalisation de la transformation ; un échec ne relance pas l'exécution sous ce même token.
- Les effets de la tentative échouée ne sont pas durablement visibles. Le slot reste disponible pour une reprise ultérieure, sous réserve de `retryAfter` et d'un nouveau claim.
- La cession du claim ne réussit que si le slot existe encore et porte **exactement le token de la tentative**. Le retryAfter ne doit jamais être écrit sur un claim repris par un autre Worker.
- `retryAfter` interdit l'acquisition anticipée, y compris lorsqu'un claim est libre ou expiré. Le temps doit être testé avec une horloge DB adéquate, sans faire reposer la correction sur un `sleep` probabiliste.
- La nouvelle acquisition crée un nouveau `claimToken` et relit source, version effective et préconditions ; l'intention immuable et les attestations de l'admission restent inchangées. Une collision CAS technique mène à une nouvelle tentative, tandis qu'une obsolescence métier effectivement établie peut produire un rejet terminal.
- Si la cession ou le commit a une issue incertaine, ne jamais recréer de slot : constater l'état durable et laisser agir les règles normales de claim, expiration et reprise. Une erreur technique persistante ne génère pas un faux résultat terminal.

### Cas déterministes

| ID | Stimulus / faute | Assertion décisive |
|---|---|---|
| C05-A | Exception de chargement ou préparation avant transaction finale | Aucune mutation ; tentative abandonnée ; cession conditionnelle et retryAfter ; pas de seconde exécution sous le claim |
| C05-B | Exception technique pendant validation/écriture de la transaction finale | Rollback complet, y compris effets et slot ; cession du claim dans transaction séparée et courte ; retryAfter durable |
| C05-C | Conflit CAS sur la version de travail d'un Pot après préparation | Aucune terminalisation/rejet automatique ; après retryAfter et réacquisition, nouvelle version de travail lue et intention réévaluée |
| C05-D | Demande effectivement obsolète selon faits à la version rechargée | Résultat **métier** rejeté et complétion atomique ; aucun retry technique infini |
| C05-E | Source/payload/version modifiés entre deux acquisitions selon les contrats autorisés | Nouvelle tentative recharge les faits autoritatifs ; payload d'intention de la demande reste inchangé |
| C05-F | Tentative d'acquisition avant retryAfter alors que le slot est libre | Acquisition refusée ; acquisition possible à partir de l'échéance si les autres conditions sont satisfaites |
| C05-G | A échoue ; son claim expire et B le reprend avant que A tente de le libérer | La libération tardive de A n'altère ni le claim B ni son retryAfter ; ancien token non réutilisable |
| C05-H | Cession conditionnelle commit, mais réponse perdue ; puis variante rollback de la cession | Relire le slot et ses échéances ; pas de double cession, ni de nouvelle ligne, ni de reprise immédiate illicite |
| C05-I | Plusieurs échecs techniques consécutifs avant succès | Chaque tentative a un token différent et une exécution complète ; temporisation respectée ; aucun état FAILED/UNKNOWN ajouté |
| C05-J | Erreur technique persistante (base indisponible, violation structurelle, bug reproductible) | Slot reste récupérable, aucune terminalisation mensongère ; diagnostic explicite, cadence bornée par retryAfter |
| C05-K | Crash du Worker après rollback et avant cession du claim | Aucun effet partiel ; claim expirera normalement et pourra être repris, sans reconstruction de slot |
| C05-L | Worker perd la partition entre échec et cession, mais garde son claim individuel | Cession conditionnelle reste possible avec claim courant ; l'ancien Worker ne peut pas acquérir de nouveau slot après perte de propriété |
| C05-M | Vérification instrumentée sur erreurs, reprises et refus métier | Compteurs/timers distinguent tentative, retry, issue technique, rejet métier et succès ; pas de double comptage E2E |

### Protocole d'essai et preuves attendues

JUnit sur PostgreSQL réel avec connexions distinctes et barrières/fault injection aux points de chargement, préparation, CAS, écriture, rollback, cession et réacquisition. Observer depuis une autre connexion l'état durable du slot, `claimToken`, `claimedUntil`, `retryAfter`, la source PENDING/TERMINAL, les versions, résultats et événements. Les tokens sont réservés aux assertions internes, jamais aux métriques ou logs exposés.

Tester séparément les retards de disponibilité introduits par `retryAfter` et l'attente résultant d'un claim encore détenu. Un chrono applicatif n'est pas une horloge DB : toute comparaison d'échéance doit être validée avec la sémantique SQL réellement choisie. Le protocole n'impose pas un algorithme de backoff sophistiqué ni un nouveau compteur durable.

**PASS CON-05** : C05-A à C05-M satisfaits ; rollback avant toute reprise, une seule exécution par acquisition, libération conditionnelle non destructive, respect de retryAfter, rechargement complet et distinction démontrée entre conflit technique et rejet métier. Recoupements CON-03/04, DOM-02 et observabilité V3-5B. Aucun test V3 n'est encore exécuté.

## 14. Protocole détaillé CON-06 — une seule transaction physique par Consumption

**Statut : protocole proposé, non exécuté.** Toute finalisation d'une Consumption doit coordonner sa protection du claim, les validations, les effets persistants, la mise à jour éventuelle de la source burnable et la suppression du slot **dans la même transaction physique, sur une seule autorité de persistance**. Les opérations préparatoires peuvent lire une autre base, mais les effets atomiques et les métadonnées du traitement résident ensemble. Un Worker mixte héberge des Consumptions WRITE et READ sans jamais partager une transaction physique entre ces royaumes.

### Matrice des autorités à prouver

| Consumption / nature | Autorité finale attendue | Écritures indivisibles |
|---|---|---|
| Exécution `COMMAND` | WRITE | Domaine Pot, version/CAS, événements et séquences, résultat terminal, statut source, slot |
| Exécution `IDENTITY_REQUEST` | WRITE | Domaine Identity, événements et séquences, résultat terminal, statut source, slot |
| Matérialisation `POT_EVENT → CurrentPot/SubobjectVersion` | READ | Projections, validation de version, slot |
| Préparation `POT_EVENT → POT_BALANCE_TASK + POT_VISIBILITY_TASK` | READ | Deux tâches, slot de préparation |
| Matérialisation `POT_BALANCE_TASK` | READ | Balance, tâche TERMINAL, slot |
| Matérialisation `POT_VISIBILITY_TASK` | READ | PotVisibilityState, entrées visibles/tombstones, tâche TERMINAL, slot |
| Matérialisation événements de complétion et Identity vers READ | READ | CommandResult / IdentityRequestResult / CurrentBinding selon Consumption, slot |

La Discovery, son checkpoint et ses slots s'enregistrent dans l'autorité **des effets** de la Consumption ; le lease de partition correspondant appartient à cette même autorité. Cela ne signifie pas que les événements source et les projections se trouvent dans une seule base : la lecture de la source peut précéder la transaction READ finale, suivie d'une vérification de claim et de version appropriée.

### Oracles structurants

1. **Transaction unique :** les écritures JPA et JDBC affectées à la même Consumption utilisent le même transaction manager PRIMARY et la même transaction **physique** (connexion/identité transactionnelle PostgreSQL effectivement partagée), pas seulement des annotations `@Transactional` portant le même nom.
2. **Rollback empoisonné :** toute erreur durable d'un writer, y compris interceptée localement, empêche le commit partiel de l'unité d'effets. Une erreur SQL PostgreSQL qui invalide une transaction ne doit pas être masquée ; les exceptions applicatives interceptées doivent également marquer explicitement la transaction rollback-only si l'invariant le requiert.
3. **Aucun commit intermédiaire :** interdiction des effets `REQUIRES_NEW` ou autres transactions indépendantes laissant persister métier/projections, séquences, tasks, slots ou résultats séparément.
4. **WRITE et READ :** une Consumption n'écrit ses effets transactionnels que dans son autorité. Composer les deux types dans un Worker ne crée **ni XA/2PC ni commit multi-base**.
5. **Fencing et séquencement :** protection finale du claim et publication dense sont dans cette même transaction ; le verrou final est conservé jusqu'au commit/rollback physique. Les chemins JDBC et JPA ne contournent pas le fencing ni le verrouillage ordonné.
6. **Fail-fast architectural :** une composition possédant des managers différents pour writers qui prétendent partager une unité atomique est refusée à la configuration ou détectée par un test de contrat avant certification.

### Cas de preuve déterministes

| ID | Stimulus / configuration | Assertion décisive |
|---|---|---|
| C06-A | Mutation Pot, émission d'événements, terminalisation Command et suppression slot avec writers JPA et JDBC | Une seule transaction/connexion physique ; tous les effets visibles ensemble après commit |
| C06-B | Registration avec deux Identity events + complétion et slot | Même unité WRITE malgré plusieurs repositories/writers, aucune demi-registration |
| C06-C | Préparation des deux Projection Tasks et suppression du slot sous READ | Même transaction READ ; panne intermédiaire ne conserve aucune des deux tâches |
| C06-D | Matérialisation de Balance ou Visibility, terminalisation de la task et suppression du slot | Même transaction READ ; rollback ne conserve ni projection ni source terminale |
| C06-E | Writer JDBC échoue, exception interceptée par un niveau appelant, tentative de commit | Le commit est refusé/rollback intégral ; ni slot supprimé ni effet durable |
| C06-F | Writer JPA échoue après un writer JDBC réussi, puis exception interceptée | Rollback de toutes les écritures y compris JDBC ; pas de transaction partielle |
| C06-G | Writer JPA réussit, writer JDBC échoue et l'appelant poursuit | Rollback total ; protection contre toute récupération trompeuse de l'exception |
| C06-H | Composition erronée JPA manager A / JDBC manager B ou datasource secondaire indépendante pour la même Consumption | Démarrage/composition refusé, ou test d'architecture/contrat explicitement FAIL avant qualification |
| C06-I | Injection d'un `REQUIRES_NEW` dans un writer d'effets ou une suppression de slot | Violation détectée par garde/test ; aucun PASS si un effet peut survivre au rollback externe |
| C06-J | Worker mixte exécute successivement des Consumptions WRITE et READ | Deux transactions indépendantes, chacune cohérente dans son royaume ; aucune transaction distribuée |
| C06-K | Discovery READ lit `POT_EVENT` en WRITE, puis enrôle slots et checkpoint en READ | Source lue sans mutation WRITE ; seuls checkpoint et slots READ committés atomiquement ; reprise sûre après échec |
| C06-L | Commit incertain, claim expirant durant verrou final, transfert de partition | Fencing du slot durable jusqu'à commit ; récupération par lecture durable ; aucune protection de partition finale requise |
| C06-M | Vérifier tous les points d'entrée et compositions des writers, y compris tests et mono-runtime | Inventaire des transactions physiques et garde-fous ; aucun chemin de finalisation non couvert |

### Couverture fusionnée et correspondance avec l'ancienne fiche CON-06

La présente fiche est **l'unique protocole canonique CON-06**. Elle conserve les assertions de l'ancienne fiche « autorité transactionnelle physique et compositions mixtes » sans exiger deux campagnes distinctes :

| Ancien cas | Cas canonique et preuve conservée |
|---|---|
| ancien C06-A (Command WRITE) | C06-A |
| ancien C06-B (Registration WRITE) | C06-B |
| ancien C06-C (CurrentPot READ) | **C06-N** ci-dessous |
| ancien C06-D (préparation et tâches distinctes) | C06-C + C06-D |
| ancien C06-E (projections Result) | **C06-O** ci-dessous |
| ancien C06-F (JPA/JDBC dans les deux ordres) | C06-E + C06-F + C06-G |
| ancien C06-G (exception interceptée) | C06-E + C06-G |
| ancien C06-H (manager invalide / REQUIRES_NEW) | C06-H + C06-I |
| ancien C06-I (Worker mixte) | C06-J |
| ancien C06-J (commit inconnu) | C06-L et CON-04 |
| ancien C06-K (Consumers indépendants) | **C06-P** ci-dessous |
| ancien C06-L (inventaire des writers) | C06-M |

| Cas complémentaire conservé | Assertion décisive |
|---|---|
| **C06-N** | Matérialisation `POT_EVENT → CurrentPot/SubobjectVersion` : version courante, sous-objets immuables et suppression du slot forment **une transaction READ** ; la lecture préparatoire de l'événement WRITE ne produit aucune écriture WRITE. |
| **C06-O** | Projection des événements de complétion vers `CommandResult` / `IdentityRequestResult` : résultat et suppression du slot READ dans une transaction physique unique, même en cas de panne/rollback. |
| **C06-P** | Deux Consumptions indépendantes sur la même publication écrivent deux projections READ distinctes : commits autonomes, pas de barrière ni de transaction inter-Consumptions ; convergence même lorsque leurs finalisations sont décalées. |

**PASS CON-06 consolidé** : C06-A à C06-P couverts, avec preuves des frontières WRITE/READ, poisoning de chaque writer, absence de transaction distribuée, composition monolithique/distribuée et absence de voie secondaire. La fusion est éditoriale ; aucun test n'est exécuté.

### Dispositif et artefacts de preuve

Combiner tests d'architecture (composition, managers, annotations interdites, chemins d'accès), tests d'intégration Spring sur les compositions réelles et **PostgreSQL réel** avec injections de fautes. Relever l'identité effective de transaction et de connexion au moment des writers concernés, vérifier les états durables depuis une connexion extérieure après commit/rollback, et tester des erreurs **interceptées**, pas seulement propagées. Couvrir les configurations runtimes séparés **et** monolithiques.

Ne pas supposer qu'un simple partage de `DataSource` ou un nom de transaction manager garantit une transaction physique commune : ce sont des hypothèses à prouver. Les verrous, contraintes et timings exacts seront démontrés dans CON-03/04 et EVT-01. Le protocole ne prescrit pas de framework transactionnel supplémentaire ; une composition non conforme doit être corrigée ou refusée.

**PASS CON-06** : C06-A à C06-P vérifiés, zéro transaction à effets séparés, empoisonnement de tout writer après erreur, routes WRITE/READ indépendantes et absence de 2PC. Les preuves communes EVT-01/02, PRJ-04 et CON-03/04 sont requises. Aucun code V3 ni test n'a été exécuté.

## 15. Protocole détaillé PRJ-01 — CurrentPot et versions immuables de sous-objets

**Statut : protocole proposé, non exécuté.** Référence V3-3 : `MaterializeCurrentPot` consomme directement `POT_EVENT(P,V)`, reconstruit `Pot@V` depuis WRITE **sans dépendre de la projection V−1**, conserve les `SubobjectVersion` immuables et avance `CurrentPot` de manière monotone. Une transaction READ unique écrit les sous-objets nouveaux, le remplacement conditionnel du pointeur courant et la suppression du slot. Les branches Balance et Visibility sont indépendantes.

### Oracles et limites contractuelles

- Pour chaque `POT_EVENT(P,V)`, reconstruire directement les valeurs et références de `Pot@V`, avec les versions locales attendues. L'événement contient `changedComponents`, pas une copie de tout le Pot ; il ne faut donc pas supposer que son payload suffise seul.
- `SubobjectVersion(componentId,localVersion)` est immuable et idempotente : elle n'est ni modifiée, ni effacée lors d'une livraison tardive, d'une répétition ou de l'avancement du pointeur. Les versions nécessaires à l'historique restent stockées.
- `CurrentPot(P)` ne recule jamais : mise à jour uniquement si `incomingVersion > storedVersion`, sous protection transactionnelle résistant à deux writers concurrents. Les références que porte le pointeur sont toutes celles de **la même version globale V**, sans mélange de V et V+1.
- Si V+1 est matérialisée avant V, la livraison tardive de V doit **préserver ses sous-versions historiques**, même si la mise à jour CurrentPot(V) est ignorée.
- Le commit de l'écriture READ et de la suppression du slot est indivisible ; rollback et réponse de commit perdue suivent CON-04/06.
- **La disponibilité READ complète est séparée de PRJ-01** : CurrentPot peut pointer V tandis que certaines versions immuables référencées, venues d'autres événements, ne sont pas encore présentes, ou que `PotBalance(P,V)` n'est pas prête. Le lecteur ne doit pas présenter ce Pot comme complet ; oracle end-to-end de présence dans READ-01.
- La conservation des `SubobjectVersion` **ne signifie pas** qu'une API de consultation historique arbitraire `Pot@V` est incluse en V3. Tester la persistance et la cohérence des données historiques, pas inventer une API historique.

### Scénarios PostgreSQL déterministes

| ID | Stimulus / ordre | Oracle de réussite |
|---|---|---|
| P01-A | Créer Pot V1 avec sous-objets version 1 puis matérialiser | CurrentPot(V1) avec références exactes ; chaque SubobjectVersion nécessaire présente et identique à Pot@V1 |
| P01-B | Modifier un seul sous-objet : V2 globale, révision locale uniquement du composant concerné | CurrentPot(V2) lie les révisions locales correctes ; objets inchangés conservent leurs références/versions ; aucune révision locale artificielle |
| P01-C | Mutation multi-cibles puis suppression logique d'Expense et suppression Pot | Un seul pointeur global par Pot ; états/suppressions et références exacts à chaque V ; versions immuables antérieures conservées |
| P01-D | Matérialiser `POT_EVENT(V3)` avant `V1` et `V2` | CurrentPot ne descend pas sous V3 ; les arrivées tardives alimentent toutes les versions immuables attendues, sans écraser les données anciennes |
| P01-E | Deux Workers matérialisent V et V+1 concurremment avec barrières au moment du remplacement | CurrentPot finit à max(V,V+1), avec références homogènes ; les deux ensembles de versions immuables conservés |
| P01-F | Répéter une livraison déjà matérialisée ou une reprise après réponse de commit perdue | Aucun doublon ni mutation de version immuable ; slot/effets satisfaits une fois ; pointe courante inchangée ou avancée seulement |
| P01-G | Injection d'échec après insertion de sous-objets mais avant écriture CurrentPot, puis après son écriture avant commit | Rollback intégral et slot intact ; aucune projection partielle durable |
| P01-H | Injecter l'absence temporaire d'une SubobjectVersion référencée lors d'une arrivée hors ordre | CurrentPot peut avancer mais lecture complète refusée/temporairement indisponible jusqu'à présence de toutes les références nécessaires (READ-01) |
| P01-I | Faire avancer CurrentPot V→V+1 pendant une lecture qui a capturé V | Réponse cohérente à la version V capturée ou indisponibilité explicite, jamais mélange de références de versions ; pas d'obligation d'API historique |
| P01-J | Événement ancien réémis ou traitement concurrent pendant que CurrentPot pointe V+2 | Conservation immuable des anciennes versions ; aucune régression, aucune réécriture du pointeur courant |
| P01-K | Deux Pots indépendants, événements/transactions entremêlés | Monotonie et références garanties **par Pot**, sans imposer de synchronisation globale entre Pots |
| P01-L | Mesurer les reconstructions Pot@V et la résolution des composants, grand Pot et historique long | Mesures des requêtes, absence de N+1 pathologique, latence et allocations collectées ; comparaison aux budgets V3-5C, sans supposer une performance déjà atteinte |

### Méthode et preuves

JUnit avec PostgreSQL réel (transactions et connexions indépendantes, barrières de concurrence, injections de rollback). Capturer les snapshots WRITE de `Pot@V`, `changedComponents`, les références exactes de `CurrentPot`, les versions immuables persistées, le slot et l'issue des commits. Comparer à un oracle déterministe indépendant des projections READ, y compris pour les suppressions. Vérifier le contenu immuable, pas seulement le nombre de lignes.

**PASS PRJ-01** : P01-A à P01-L satisfaits, pointeur courant monotone et atomique, références homogènes, anciennes versions conservées sous arrivée hors ordre, aucun effet partiel ou historique fabriqué. Les consultations complètes restent à certifier en READ-01 et les soldes en PRJ-02. Aucun test V3 n'a encore été implémenté ou exécuté.

## 16. Protocole détaillé PRJ-02 — balances complètes, exactes et immuables par version

**Statut : protocole proposé, non exécuté.** Source V3-1/V3-3 : pour **chaque** mutation effective du Pot portant sa version globale à V, calculer intégralement les soldes rationnels de `Pot@V` (y compris pour une mutation non financière) et matérialiser `PotBalance(potId,V)` en READ, **sans dépendre de `PotBalance(V−1)`**. `PotBalance` est immuable ; il n'existe ni projection CurrentBalance, ni chaîne d'indirections entre versions. La tâche `POT_BALANCE_TASK` contient le résultat préparé pour V ; son traitement matérialise ce résultat et terminalise la tâche avec suppression de son slot dans la même transaction READ.

### Oracle métier indépendant

- Construire, pour chaque `Pot@V` de test, un **oracle de référence distinct de l'implémentation métier testée** : dépenses, contributeurs, bénéficiaires/quotes-parts, poids effectifs à V, associations et autres règles du domaine V3-1 ; utiliser des fractions exactes (entiers arbitraires pour numérateurs/dénominateurs ou une représentation équivalente prouvant l'exactitude), sans arrondi flottant.
- Vérifier **l'ensemble complet des utilisateurs/parts concernés**, les montants nets individuels et les invariants de conservation applicables ; comparer les valeurs rationnelles normalisées plutôt que les seuls formats textuels ou des égalités approchées. Les conventions de signe et les règles de répartition sont celles du contrat métier V3-1 ; ne pas en inventer d'autres dans les tests.
- Le résultat matérialisé porte **exactement (potId,V)**. Une balance V+1 ne peut remplacer ni modifier V et ne permet pas de conclure que V a été matérialisée ; une balance V existe indépendamment du pointeur mutable `CurrentPot`.
- Les calculs de V ne lisent pas `PotBalance(V−1)` comme condition nécessaire : injecter l'absence ou le retard de la version précédente et vérifier une production V correcte.
- L'identité et le contenu de la tâche proviennent de la reconstruction autoritative à **V exacte** ; pas d'échantillonnage de l'état courant WRITE au moment tardif de la matérialisation. Une mutation V+1 après préparation de V ne modifie pas les données de V.
- La validation finale de la tâche, les écritures de `PotBalance(P,V)`, la transition de tâche `PENDING → TERMINAL` et la suppression du slot sont atomiques (PRJ-04, CON-04/06). Une duplication/reprise ne peut altérer une balance déjà durable.

### Scénarios déterministes

| ID | Stimulus / entrelacement | Oracle de réussite |
|---|---|---|
| P02-A | Pot V1 simple avec montants entiers et plusieurs bénéficiaires | Calcul complet des soldes attendus, rationalité exacte et équilibre global selon les règles du domaine |
| P02-B | Dépenses fractionnaires, nombres premiers dans les poids et montants, réductions de fractions | Aucun arrondi ou dérive numérique ; égalité exacte à l'oracle indépendant |
| P02-C | Changement de poids par défaut ou de poids explicites entre V et V+1 | Balance V conforme aux poids à V et Balance V+1 conforme aux poids à V+1 ; aucun emprunt des valeurs courantes |
| P02-D | Mutation non financière (libellé, métadonnée compatible) produisant une nouvelle V globale | Nouvelle `PotBalance(P,V)` **complète** et indépendante, même si les montants sont identiques à V−1 |
| P02-E | Création, modification, suppression d'Expense et changement de rattachement affectant la répartition | Chaque version conserve le calcul attendu ; suppressions et liens historiques respectés |
| P02-F | Traiter la tâche Balance V+2 **avant** celles de V et V+1 | Les trois balances restent distinctes, correctes, immuables et accessibles en stockage ; aucune exigence de traitement FIFO |
| P02-G | Supprimer/retarder volontairement la balance V−1 puis traiter V | V est calculée et matérialisée correctement sans accès à V−1 |
| P02-H | Préparer la tâche V, faire avancer WRITE jusqu'à V+1 puis matérialiser V | Le contenu enregistré reste celui de V exactement, non celui de l'état WRITE courant |
| P02-I | Deux Workers tentent de matérialiser la même tâche / même (P,V), avec commit incertain et retry | Une seule valeur durable canonique ; identité/contenu inchangés ; slot et tâche finalisés une seule fois |
| P02-J | Erreur après insertion Balance mais avant terminalisation de tâche ou suppression du slot | Rollback total ; pas de balance durable orpheline, ni tâche terminale sans son résultat |
| P02-K | `CurrentPot` pointe V+1 tandis que seule la balance V existe, puis balance V+1 arrive | Consultation complète à V+1 indisponible jusqu'à présence de sa balance exacte ; aucune substitution de V (READ-01) |
| P02-L | Deux Pots indépendants et plusieurs versions chacun, tâches entrelacées | Calculs et clés isolés par (P,V) ; aucune contamination entre Pots |
| P02-M | Profils petit (5/20), moyen (20/200), grand (100/2 000) et historiques de volumes V3-5C | Mesurer séparément reconstruction/préparation, attente et transaction de matérialisation ; p95 E2E des scénarios nominaux évalué contre les objectifs V3-5C, sans les présumer atteints |

### Robustesse de l'oracle et méthodes

JUnit et **PostgreSQL réel**, avec oracle rationnel indépendant, jeux de mutations connus, snapshots `Pot@V` attendus, captures de tâches, barrières de concurrence et injections de panne. Les tests de valeurs métier purs peuvent être unitaires ; l'immuabilité, les races et l'atomicité exigent PostgreSQL. Vérifier les clés et les **contenus** des anciennes balances après les traitements ultérieurs, non leur seule présence. Conserver les données génératrices (seed), les références et toutes les fractions de référence.

Pour les performances, ne pas confondre le **temps de calcul pur**, la préparation en WRITE/READ, la création de tâche, son attente et la disponibilité par API. La mesure E2E doit inclure la chaîne complète et respecter l'incertitude du sondage. Le budget p95 de 50 ms sur calcul/écriture des balances demeure **candidat**, tandis que les SLO globaux (500 ms Pot moyen nominal, 2 s grand Pot nominal) sont les objectifs validés.

**PASS PRJ-02** exige P02-A à P02-M, exactitude rationnelle, balance complète par version globale, immutabilité/idempotence et absence de dépendance à V−1. Les budgets sont qualifiés séparément en PERF-01 ; un succès fonctionnel ne certifie pas une performance. Aucun test V3 exécuté.

## 17. Protocole détaillé PRJ-03 — visibilité collective, monotone et hors ordre

**Statut : protocole proposé, non exécuté.** Références V3-3/V3-4 : chaque `POT_VISIBILITY_TASK(P,V,visibleUserIds[])` fournit la **liste complète** des utilisateurs visibles à la version exacte V. L'autorité de matérialisation est `PotVisibilityState(potId,materializedVersion)` : son verrou exclusif protège **l'ensemble** des entrées `CurrentUserPotList` de ce Pot et non un simple CAS individuel. Le calcul compare uniquement les entrées **actuellement visibles** (`visible=true`) à la liste entrante ; les tombstones `visible=false` sont conservées mais ne sont pas scannées pour établir la différence.

### Oracles de référence

- Pour chaque Pot et V, calculer un oracle indépendant `Visible(P,V)` : Pot actif et utilisateur créateur **ou** utilisateur rattaché à au moins un Shareholder ; Pot supprimé = ensemble vide. Plusieurs Shareholders liés à un même utilisateur ne produisent qu'une seule visibilité ; détacher un lien ne supprime pas l'accès si un autre subsiste.
- Une tâche de version `V > materializedVersion` remplace l'ensemble visible courant par `Visible(P,V)` **atomiquement**, puis avance `PotVisibilityState.materializedVersion = V`. Les entrées nouvellement visibles sont créées/réactivées, celles devenues invisibles reçoivent une tombstone `visible=false`. Les entrées invisibles historiques déjà inchangées peuvent conserver leur `potVersion` antérieure : cette valeur est la dernière version de changement/évaluation enregistrée **pour l'entrée**, pas un indicateur global de fraîcheur.
- Une tâche `V ≤ materializedVersion` est obsolète : **aucun changement** des entrées ni recul de l'état ; elle est néanmoins terminalisée et son slot supprimé de façon atomique.
- Toute mutation de la liste de visibilité d'un Pot participe au **même verrou collectif** PotVisibilityState, y compris le cas de création initiale ; deux initialisations concurrentes ne peuvent créer deux autorités ni produire d'insertion fantôme.
- La projection sert à la **navigation** ; elle n'est jamais l'autorité d'accès à un Pot. Les tests de sécurité correspondants sont couverts par SEC-01.
- La transaction READ protège ensemble la lecture des `visible=true`, le différentiel, les écritures/tombstones, la progression de PotVisibilityState, la terminalisation de la tâche et la suppression de son slot. La liste ne doit pas exposer d'état partiellement mis à jour après commit.

### Scénarios PostgreSQL déterministes

| ID | Stimulus / entrelacement | Oracle de réussite |
|---|---|---|
| P03-A | Première tâche V1 pour un Pot sans ligne de contrôle préexistante | Initialisation sûre de PotVisibilityState ; ensemble visible exactement égal à `Visible(P,V1)`, aucune entrée double |
| P03-B | Deux Workers tentent simultanément la première initialisation du même Pot, avec listes/version différentes | Une seule autorité de verrou, évolution sérialisée ; état final à la plus haute version committée ; aucun utilisateur fantôme |
| P03-C | Un utilisateur devient visible, disparaît, puis réapparaît (V1→V2→V3) | Même entrée `(user,pot)` conservée avec `true→false→true` et versions adéquates ; pas de suppression physique à V2 |
| P03-D | Un utilisateur a deux Shareholders ; l'un est détaché, puis le dernier | Visible après premier détachement, invisible seulement après le dernier ; déduplication par user |
| P03-E | Créateur sans Shareholder lié, puis Pot supprimé | Créateur visible tant que Pot actif, puis toutes les entrées visibles deviennent `false` après suppression |
| P03-F | Matérialiser la tâche V3 avant V1 et V2 | Version du contrôle reste V3 et ensemble exactement `Visible(P,V3)` ; les tâches V1/V2 se terminent sans régression ni écriture de tombstones tardives |
| P03-G | Deux tâches de versions V et V+1 se concurrencent, avec barrières avant verrou collectif | Verrou unique sérialise lecture, diff et mise à jour ; résultat final conforme à la version maximale, aucune insertion fantôme |
| P03-H | Nouvelle liste vide après liste non vide, puis nouvelle liste non vide | Toutes les visibles passent à `false` ; seules les anciennes visibles sont relues ; réactivation ultérieure correcte |
| P03-I | Historique volumineux de tombstones, avec peu d'utilisateurs actuellement visibles | Le calcul de diff ne parcourt pas toutes les entrées `false` ; plan SQL/mesures le démontrent sans compromettre l'exactitude |
| P03-J | Panne après écriture de certaines lignes, après avancée du contrôle ou après terminalisation de tâche, avant commit | Rollback de l'ensemble : entrées, contrôle, tâche et slot restent cohérents ; aucune demi-projection |
| P03-K | Commit validé mais réponse perdue, suivi d'une réacquisition/duplication de tentative | Résultat idempotent, slot finalisé une fois ; aucune régression ni écriture supplémentaire |
| P03-L | Deux Pots distincts avec tâches parallèles | Sérialisation **par Pot**, pas de verrou global de visibilité ; ensembles isolés |
| P03-M | Entrée de liste incorrectement visible, mais Shareholder absent à Pot@V | La navigation ne confère aucun accès : READ refuse selon SEC-01 ; le test ne confond pas liste et autorisation |
| P03-N | Lectures concurrentes pendant matérialisation d'une liste changeant pour plusieurs utilisateurs | Un lecteur observe l'ancien ou le nouvel ensemble committé, jamais un mélange partiellement committé |
| P03-O | Grand Pot et historique important, forte alternance des membres | Mesurer lignes `visible=true` lues, coût du verrou, mutations, requêtes/latences ; pas de scan complet de l'historique des utilisateurs |

### Méthode et preuves attendues

JUnit avec **PostgreSQL réel** : jeux `Visible(P,V)` établis indépendamment des projections, connexions concurrentes, barrières de prise du verrou, fault injection et comparaison complète des entrées de navigation et de la version de contrôle. Examiner les requêtes/plans PostgreSQL pour confirmer que l'algorithme parcourt les seules entrées actuellement visibles lors du différentiel, sans balayage de toutes les tombstones. Vérifier les résultats depuis une connexion indépendante après chaque commit/rollback.

**Attention à la précision :** l'avancement du contrôle de V1 à V3 peut sauter V2 ; il signifie que la visibilité a été recalculée **entièrement à V3**, pas que chaque tâche intermédiaire a été exécutée. Cela reste correct parce que la liste d'utilisateurs visibles est fournie **intégralement** par chaque tâche. Le `potVersion` d'une ligne inchangée peut rester inférieur à `materializedVersion` sans incohérence.

**PASS PRJ-03** : P03-A à P03-O, résultat visible exact et monotone, protections collectives sur toutes les écritures, concurrence/rollback sûrs, performances du différentiel observées et aucune confusion avec les droits d'accès. Aucun test V3 n'a été exécuté.

## 18. Protocole détaillé PRJ-04 — préparation atomique et finalisation indépendante des deux Projection Tasks

**Statut : protocole proposé, non exécuté.** Pour chaque `POT_EVENT(P,V)`, la Consumption `PreparePotProjectionTasks` fabrique en READ une `POT_BALANCE_TASK` et une `POT_VISIBILITY_TASK`, avec identités stables `(preparationConsumptionId,potEventId,taskType)`. Les deux tâches et la suppression du slot de préparation sont committées **dans une seule transaction READ**. Ensuite chacune est acquise/finalisée **séparément** par sa Consumption ; projection correspondante, source tâche `PENDING → TERMINAL` et suppression de son propre slot sont atomiques. Les tâches n'ajoutent aucune séquence événementielle et ne requièrent pas la présence de la projection sœur.

### Oracles transactionnels

- **Préparation :** état initial = slot de préparation présent et aucune tâche créée pour l'événement. État final committé = slot absent **et les deux tâches durables** ; en cas de rollback, état initial intact. Aucun état « une seule tâche committée » n'est autorisé.
- **Enrôlement :** les deux tâches sont burnables ; leur Discovery crée des slots uniques lorsque chacune est `PENDING`, indépendamment de la Discovery sœur. Ne pas confondre création durable d'une tâche avec création de son slot.
- **Matérialisation Balance :** `PotBalance(P,V)` immuable + Balance Task `TERMINAL` + suppression du slot Balance, dans une transaction READ. Aucun effet ni terminalisation de la tâche Visibility exigés.
- **Matérialisation Visibility :** entrées `CurrentUserPotList`, `PotVisibilityState(P)`, Visibility Task `TERMINAL` + suppression du slot Visibility, dans une transaction READ ; verrou collectif et monotonie PRJ-03. Aucun effet ni terminalisation de la tâche Balance exigés.
- **Reprise :** un commit incertain se tranche par lecture durable ; ni tâche ni slot ne sont recréés arbitrairement. Les reprises utilisent les mêmes identités déterministes dans une Consumption inchangée ; un replay indépendant exige un nouvel ID de préparation.
- **Cohérence en retard :** CurrentPot, Balance et Visibility ne forment pas une transaction commune. READ refuse une lecture « Pot complet » si la version courante visée ne dispose pas encore des composants/balances nécessaires (READ-01), sans bloquer les matérialisations indépendantes.

### Cas PostgreSQL déterministes

| ID | Stimulus / panne contrôlée | Assertion décisive |
|---|---|---|
| P04-A | Préparer un `POT_EVENT` sans panne | Deux tâches de types distincts, référencées au même (P,V), et slot de préparation absent après commit |
| P04-B | Panne après insertion Balance mais avant insertion Visibility | Rollback : aucune tâche persistée, slot de préparation présent |
| P04-C | Panne après insertion des deux tâches mais avant suppression du slot de préparation | Rollback de l'ensemble ; aucune tâche persistée |
| P04-D | Panne après suppression du slot de préparation mais avant commit | Rollback : aucune tâche persistée et slot toujours présent |
| P04-E | Commit de préparation réussi mais réponse perdue | Deux tâches présentes, slot absent ; récupération par observation, aucun doublon |
| P04-F | Deux Workers préparent concurremment le même événement | Un seul couple de tâches durable, une seule finalisation de préparation ; identifiants déterministes et unicité en base |
| P04-G | Discovery Balance et Discovery Visibility progressent à des rythmes différents | Chaque tâche `PENDING` obtient son propre slot ; ni dépendance de découverte ni attente obligatoire de la sœur |
| P04-H | Finaliser Balance tandis que Visibility reste PENDING/non enrôlée | Balance exacte et immuable ; Balance Task TERMINAL, son slot absent ; Visibility inchangée |
| P04-I | Finaliser Visibility tandis que Balance reste PENDING/non enrôlée | Visibilité et état de contrôle corrects ; Visibility Task TERMINAL, son slot absent ; Balance inchangée |
| P04-J | Panne pendant écriture de Balance après insertion projection, avant terminalisation de la tâche ou suppression de slot | Aucun résultat partiel durable ; tâche PENDING et slot conservé après rollback |
| P04-K | Panne pendant écriture du différentiel Visibility après une partie des entrées puis après avancée du contrôle | Rollback de toutes les entrées, du contrôle, de la tâche et du slot |
| P04-L | Deux matérialisateurs opèrent en parallèle et l'un échoue | L'autre peut committer normalement ; le perdant se reprend indépendamment sans double effet |
| P04-M | Deux tâches de versions V+1 et V arrivent avant V, sur un même Pot | Balance reste immuable par V ; Visibility progresse selon version maximale sans régression ; toutes tâches se terminent |
| P04-N | Crash/restart après création des tâches mais avant enrôlement, puis après terminalisation d'une seule tâche | Chaque tâche PENDING est retrouvée ; seule la tâche inachevée reprend ; aucune nouvelle préparation/effet double |
| P04-O | Reset complet READ puis replay avec mêmes IDs, et replay indépendant avec nouvelle Consumption | Reset cohérent permet réutilisation des IDs ; nouveau `preparationConsumptionId` produit de nouvelles identités, sans collision |

### Méthode et preuves conservées

JUnit avec **PostgreSQL réel**, connexions distinctes, contrôles de points de panne avant commit, entrelacements par barrières, assertions sur taskIds, types, payloads (balances préparées exactes et liste visible à V), statuts `PENDING/TERMINAL`, slots et projections. Vérifier les effets depuis une connexion indépendante et comparer les snapshots avant/après. Les tests de concurrence comprennent les exceptions de contraintes d'unicité, rollback et perte simulée de réponse de commit ; ils n'utilisent pas le temps de sommeil comme oracle.

Séparer les preuves : **EVT-03** valide aussi le catalogue des familles et l'identité des tâches ; **CON-02/04/06** valident les frontières de slot/commit ; **PRJ-02/03** valident la justesse des valeurs. PRJ-04 démontre spécifiquement les **deux** atomicités locales et leur indépendance, sans créer une transaction distribuée entre WRITE et READ.

**PASS PRJ-04** exige P04-A à P04-O, absence de demi-préparation et de demi-finalisation, aucune obligation d'ordre entre les tâches, et reprises sûres. Aucun test V3 n'a été implémenté ni exécuté.

## 19. Protocole détaillé READ-01 — réponse complète et cohérente, ou indisponibilité explicite

**Statut : protocole proposé, non exécuté.** READ fournit les consultations courantes sans appel synchrone à WRITE. Les projections évoluent indépendamment ; aucun snapshot global entre Pots n'est promis. Pour une demande avec `minVersion=m`, la consultation sélectionne une fois une version courante V de `CurrentPot` avec `V ≥ m` et doit résoudre **toutes** les références de sous-objets et `PotBalance(P,V)` nécessaires au contenu demandé. Une réponse ne doit jamais mélanger les données de deux versions globales. La visibilité de liste, les résultats et les bindings ne constituent pas une barrière globale de disponibilité.

### Oracles contractuels

- Si `CurrentPot` est absent ou à `V < minVersion`, rendre une **indisponibilité temporaire explicite**, sans prétendre à une absence définitive de Pot. Les codes/réponses API exacts seront fixés par le contrat API V3-6 avant construction des tests Bruno ; ne pas inventer maintenant un code HTTP.
- Si `CurrentPot(P,V)` existe, capturer **une seule fois V et ses références**. Une consultation nécessitant la balance ne peut réussir que si `PotBalance(P,V)` existe, est complète et appartient exactement à V ; même règle pour chaque `SubobjectVersion` référencée. Si un élément nécessaire manque, rendre temporairement indisponible plutôt qu'une réponse partielle ou un substitut V−1/V+1.
- Une consultation qui ne requiert pas une projection donnée ne doit pas attendre artificiellement sa matérialisation (par exemple un résultat terminal consultable peut précéder CurrentPot ; une lecture d'en-tête ne doit pas nécessairement attendre les balances si son contrat n'en a pas besoin).
- Un déplacement concurrent de CurrentPot de V à V+1 après capture ne peut pas transformer la réponse en assemblage hybride. Les valeurs immuables de V restent lisibles pendant la requête ; assurer snapshot SQL adapté ou lecture cohérente par références figées, à démontrer.
- `minVersion` est un **plancher de fraîcheur**, pas l'exigence de servir exactement m. Si V > m et tous les composants de V sont prêts, la réponse peut servir V ; elle ne prétend pas exposer un historique arbitraire à m.
- La condition `PotVisibilityState(P).materializedVersion ≥ V` constitue un jalon de progression **quand la visibilité est explicitement requise par la consultation / le test E2E complet**, mais n'est pas une autorité de sécurité ni une condition systématique à toute lecture Pot. L'autorisation est vérifiée séparément avec binding authentifié et rattachement au Shareholder de la version servie (SEC-01).
- Pour plusieurs Pots, les versions servies peuvent différer, même dans une liste d'utilisateur ; aucune photographie globale transactionnelle n'est promise.

### Cas déterministes

| ID | Stimulus | Oracle de réussite |
|---|---|---|
| R01-A | Pot absent dans READ, puis projection initiale apparue | Indisponibilité temporaire avant matérialisation, puis succès complet si accès autorisé ; ne pas déclarer une absence définitive à tort |
| R01-B | CurrentPot à V4 et requête `minVersion=5` | Indisponibilité temporaire ; aucune réponse V4 présentée comme satisfaisant V5 |
| R01-C | CurrentPot à V6 et `minVersion=5` | V6 peut être servie si complète et autorisée ; pas d'exigence artificielle de servir V5 |
| R01-D | CurrentPot V6 référence plusieurs sous-versions dont l'une est absente | Lecture complète indisponible jusqu'à présence de toutes les références ; aucune suppression de champ silencieuse |
| R01-E | CurrentPot V6 et Balance(P,V5) présente, Balance(P,V6) absente | Consultation nécessitant Balance indisponible ; ne jamais réutiliser le solde V5 |
| R01-F | CurrentPot V6 et Balance(P,V7) présente, Balance(P,V6) absente | Consultation nécessitant Balance indisponible ; ne jamais emprunter un solde futur |
| R01-G | CurrentPot passe V6→V7 pendant une lecture ayant capturé V6 | Réponse cohérente V6 ou indisponibilité ; jamais assemblage V6/V7 |
| R01-H | CommandResult de la commande cible présent, mais CurrentPot cible pas encore à V | CommandResult consultable indépendamment ; lecture Pot avec `minVersion=V` encore indisponible |
| R01-I | CurrentPot et composants prêts, mais tâche Balance ou Visibility en retard | Seules les lectures dont les composants requis manquent sont indisponibles ; test E2E « toutes projections » attend séparément les jalons Balance et Visibility |
| R01-J | Deux Pots avec projections et versions décalées dans CurrentUserPotList | Navigation sans faux snapshot inter-Pots ; toute ouverture de Pot réévalue son autorisation et sa complétude propre |
| R01-K | Changement de rattachement Shareholder entre deux versions ; demande authentifiée | Autorisation appréciée selon le Shareholder de la **version réellement servie**, jamais sur liste de navigation ou snapshot WRITE courant |
| R01-L | Projection CurrentPot plus récente que la cible d'une commande déjà achevée | `minVersion` est satisfait par V supérieure, si consultation V supérieure complète ; ne pas prétendre que Pot courant restitue automatiquement l'ancienne V cible |
| R01-M | Concurrent readers pendant commit/rollback de matérialisation partielle de sous-objets / CurrentPot | Lecteurs ne voient que données committées et complètes, ou indisponibilité explicite ; aucun état transitoire partiellement écrit |
| R01-N | Vérifier instrumentation client de sondage READ avec retards artificiellement contrôlés | Mesure E2E fondée sur première réponse réussie prouvant les composants requis ; retard de polling quantifié ; ne pas qualifier cette borne de timestamp exact du commit |

### Méthode et PASS

JUnit sur PostgreSQL réel, incluant interventions précises sur l'ordre des matérialisations et les transactions concurrentes, complété par Bruno sur l'API authentifiée. Comparer l'état retourné à un oracle de reconstruction autoritative Pot@V et aux versions locales référencées ; vérifier chaque composant et balance, pas uniquement le `potVersion`. Vérifier explicitement les réponses d'indisponibilité (sans imposer encore leur forme HTTP), puis la convergence lorsque les éléments arrivent. Conserver les versions/IDs, états de tâches, snapshots SQL et captures des réponses API.

**PASS READ-01** : R01-A à R01-N satisfaits ; aucun Pot partiel, aucune version mélangée, interprétation correcte de `minVersion`, indépendance des projections et des Pots ; pas de lecture WRITE synchrone. L'authentification et l'autorisation sont qualifiées avec SEC-01/02. Aucun test V3 exécuté.

## 20. Protocole détaillé SEC-01 — identité authentifiée, bindingToken et autorisation Pot@V

**Statut : protocole proposé, non exécuté.** L'identité externe authentifiée `(issuer,subject)` doit être associée **à cette même EUI** dans `CurrentBinding` avec le `bindingToken` présenté, un rattachement actif et un `pocomaUserId` résolu. Le token n'est **pas une preuve d'authentification autonome**. Le droit de lire un Pot à la version effectivement servie V est vérifié par le rattachement de ce User à un Shareholder **dans Pot@V**, indépendamment de `CurrentUserPotList`. Les conséquences d'un Unlink encore non propagé à READ relèvent du contrat accepté SEC-02.

### Oracles de sécurité

- Authentifier la requête côté API ; extraire l'EUI uniquement d'une identité vérifiée. Refuser tout `bindingToken` absent, non actif ou appartenant à une EUI distincte, même si son détenteur le connaît.
- Résoudre le `pocomaUserId` à partir de `CurrentBinding` ; aucun paramètre fourni par le client ne peut choisir ou substituer librement ce User.
- Pour la version V **réellement servie**, vérifier qu'au moins un Shareholder de `Pot@V` est lié au User résolu. Un rattachement à V ne confère pas automatiquement un droit à V+1 ; une suppression à V+1 ne révoque pas rétroactivement le droit à une version historique V si elle était servable (l'API historique arbitraire reste hors périmètre).
- Une entrée visible dans `CurrentUserPotList` ne dispense jamais du contrôle métier ; une entrée absente ou en retard ne détermine pas non plus à elle seule l'autorisation.
- Une requête refusée ne doit divulguer ni contenu du Pot, ni identifiant interne sensible, ni bindingToken ; le choix entre réponses « refus » et « introuvable » reste une décision du contrat HTTP V3, à fixer sans fuite de données.
- Les tests doivent isoler **Authentification**, **résolution du binding** et **autorisation Pot** pour localiser précisément un refus ; aucune consultation synchrone de WRITE pour décider une lecture READ.

### Cas déterministes

| ID | Stimulus | Oracle de réussite |
|---|---|---|
| S01-A | EUI authentifiée E1 + bindingToken actif de E1 + Shareholder de Pot@V lié au User de E1 | Lecture autorisée, complète sous READ-01 |
| S01-B | Aucun JWT/identité authentifiée, mais bindingToken valide connu | Accès refusé : token seul insuffisant |
| S01-C | EUI E2 authentifiée présente le bindingToken de E1 | Refus, y compris si E2 connaît le Pot et son identifiant |
| S01-D | EUI E1 authentifiée présente token inventé, expiré/remplacé ou appartenant à EUI liée à un autre User | Refus ; aucune résolution approximative par userId ou token seul |
| S01-E | EUI authentifiée correcte, CurrentBinding absent, inactif ou sans pocomaUserId | Refus / absence de liaison, sans accès au Pot |
| S01-F | EUI et bindingToken corrects, mais aucun Shareholder lié au User dans Pot@V | Refus malgré Pot connu ou liste de navigation trompeuse |
| S01-G | Rattachement Shareholder présent à V puis retiré à V+1, avec lectures à versions réellement servies distinctes | Droit évalué sur la version servie ; accès admis à V si cette consultation est disponible, refus à V+1 si aucun lien restant |
| S01-H | User lié à deux Shareholders, puis détaché de l'un | Droit maintenu tant qu'au moins un Shareholder de la version servie reste lié |
| S01-I | CurrentUserPotList contient visible=true erroné ou périmé pour un User sans droit à Pot@V | Aucune autorisation par la liste ; ouverture du Pot refusée |
| S01-J | CurrentUserPotList ne contient pas le Pot alors que Shareholder lié et CurrentBinding valide | Accès direct au Pot autorisé si READ-01 satisfait ; navigation non autoritative |
| S01-K | Appel fournit un pocomaUserId arbitraire, ou tente de le modifier dans le payload/paramètre | Identité ignorée comme autorité ou entrée refusée ; User dérivé seulement du binding authentifié |
| S01-L | Command/IdentityResult en READ disponible avant matérialisation de CurrentBinding ou Pot | Pas de contournement des vérifications pour ouvrir le Pot ; latence de projection traitée indépendamment |
| S01-M | Test d'isolation entre deux tenants/émetteurs : même `subject` chez deux `issuer` | Deux EUI distinctes ; pas de confusion d'autorité malgré `subject` identique |
| S01-N | Requêtes concurrentes pendant changement de CurrentPot V→V+1 ou CurrentBinding version supérieure | Autorisation cohérente avec binding et version effectivement observés, sans mélange non maîtrisé ; jamais de droit obtenu par état partiel |
| S01-O | Parcours Keycloak réel : jeton signé et validé, EUI extraite puis bindingToken vérifié | Identité authentifiée effectivement reliée au bon binding ; refus des jetons falsifiés, mauvais issuer/audience, expirés selon politique OIDC applicable |

### Méthode et critères PASS

Combiner **Bruno** (API, erreurs, contrôle de non-divulgation), **JUnit avec PostgreSQL réel** (versions, rattachements, état CurrentBinding, races et absence de navigation autoritative) et **Keycloak réel** pour les parcours OIDC dans la configuration B. Utiliser plusieurs identités EUI et Pots aux historiques divergents. Vérifier pour chaque refus l'absence de contenu protégé dans réponse et logs d'application observables ; ne pas journaliser les tokens. Les tests qui forcent d'anciennes versions de Pot ne doivent pas prétendre qu'une API historique arbitraire existe déjà : utiliser un CurrentPot contrôlé à la version V puis le faire progresser.

**PASS SEC-01** : S01-A à S01-O, absence de confusion EUI/token, authentification indispensable, contrôle Shareholder à V, aucun privilège issu de CurrentUserPotList et aucune fuite de contenu lors des refus. SEC-02 teste séparément la fenêtre acceptée de propagation d'Unlink. Aucun test V3 n'a été implémenté ni exécuté.

## 21. Protocole détaillé SEC-02 — révocation Identity asynchrone et portée de CurrentBinding

**Statut : protocole proposé, non exécuté.** V3-3/V3-4 acceptent explicitement la fenêtre pendant laquelle une opération `Unlink` est committée dans WRITE, mais sa projection `CurrentBinding` n'est pas encore mise à jour dans READ. L'accès READ demeure gouverné par **l'EUI authentifiée et son bindingToken effectivement actif dans CurrentBinding READ**, puis les droits Shareholder dans la version Pot servie. Cette tolérance de propagation **ne s'étend pas à WRITE** : ses commandes et demandes Identity relisent les autorisations de l'état WRITE actuel à l'exécution.

### Oracles contractuels

- Un `Unlink` gagnant termine atomiquement en WRITE avec événement `IDENTITY_EVENT(EUI,targetVersion)`, résultat et complétion ; il n'impose pas un commit synchrone de `CurrentBinding` dans READ.
- Tant que READ porte encore l'ancienne version active, l'ancien couple **EUI authentifiée + bindingToken** peut rester utilisable pour une lecture qui satisfait aussi les droits Pot@V. C'est une **fenêtre acceptée**, non une garantie qu'une lecture réussisse pendant ce délai.
- Dès que `CurrentBinding(EUI).identityVersion ≥ targetVersion` matérialise l'Unlink, le token précédent ne donne plus accès à READ. Un événement Identity antérieur arrivant ensuite ne doit pas restaurer le lien ni le token.
- Une requête WRITE exécutée après l'Unlink ne doit pas s'autoriser à partir d'un `CurrentBinding` périmé ; elle contrôle son état autoritatif WRITE au moment de l'exécution. Les demandes déjà admises suivent le contrat d'attestation à admission et de validations Pocoma au moment de l'exécution (ID-02).
- `IdentityRequestResult` et `CurrentBinding` progressent indépendamment ; voir le résultat d'Unlink ne démontre pas que READ a révoqué le token.
- Une Registration ou un Link ultérieur vers la même EUI ne réactive pas implicitement l'ancien token ; l'autorisation exige le nouveau binding exact après projection. Un `minVersion` atteint peut désigner une version Identity ultérieure, sans garantir que le rattachement supprimé existe encore.
- Les scénarios documentent explicitement le **risque de propagation différée accepté** ; aucune prétention de révocation immédiate ou de durée maximale non validée n'est introduite.

### Cas déterministes

| ID | Stimulus / entrelacement | Oracle de réussite |
|---|---|---|
| S02-A | EUI liée, token actif et lecture Pot autorisée avant Unlink | Baseline : accès possible avec identité authentifiée correspondante et Shareholder à la version lue |
| S02-B | Unlink committé en WRITE ; bloquer la Consumption Identity→CurrentBinding | WRITE EUI détachée ; READ garde ancien binding ; ancienne lecture peut encore passer, sans être revendiquée comme toujours possible |
| S02-C | Après Unlink WRITE, tenter une mutation qui exige le binding encore actif avec l'ancien token | Contrôle WRITE courant rejette ou applique la décision conforme au domaine ; aucun droit indu par CurrentBinding en retard |
| S02-D | IdentityRequestResult(Unlink) visible avant la matérialisation de CurrentBinding | Le résultat indique succès/targetVersion mais ne suffit pas à prouver révocation READ ; les branches restent indépendantes |
| S02-E | Matérialiser `IDENTITY_EVENT` cible et observer CurrentBinding à targetVersion | Ancien token inopérant immédiatement après cette matérialisation pour toute nouvelle lecture READ |
| S02-F | Traiter l'événement EUI de version antérieure après celui d'Unlink | CurrentBinding reste monotone et révoqué ; pas de restauration par livraison tardive |
| S02-G | Relinker ultérieurement EUI avec nouveau token, projeter la nouvelle version | Ancien token toujours refusé ; nouveau token utilisable seulement avec EUI authentifiée correspondante et droits Pot |
| S02-H | EUI liée à plusieurs situations d'accès Pot, puis Unlink | Après propagation, toutes les lectures dépendant de ce binding refusées, indépendamment d'une ancienne entrée CurrentUserPotList |
| S02-I | Deux EUI distinctes liées à un même PocomaUser ; unlink d'une EUI seulement | Ancienne EUI révoquée en READ après propagation ; autre EUI et son token restent utilisables selon leurs droits |
| S02-J | Accès en concurrence avec transaction de matérialisation CurrentBinding, y compris rollback injecté | Aucune ligne partiellement mise à jour ; lecture évaluée sur état READ committé ; rollback ne provoque pas de fausse révocation durable |
| S02-K | Mesure bout en bout Unlink admission→WRITE terminal→IdentityResult visible→CurrentBinding révoqué→lecture refusée | Chronologie des jalons observable sans confondre résultats, commits et sondages ; ne pas ajouter de SLO de révocation inexistant |

### Méthode, précautions et PASS

JUnit avec **PostgreSQL réel** pour contrôler commits, ordres d'événements, projections et rollbacks ; **Bruno + Keycloak réel** pour l'identité authentifiée et les lectures avant/après matérialisation. Maintenir un Pot test avec Shareholder lié et projections déjà complètes, afin de ne pas confondre révocation Identity et retard Pot. Vérifier CurrentBinding, IdentityRequestResult, versions, token ancien/nouveau, résultats des lectures et refus WRITE. Les tokens ne doivent pas être inscrits dans les logs/rapports exposés : utiliser des identifiants anonymisés et des assertions privées.

**PASS SEC-02** : S02-A à S02-K satisfaits ; fenêtre de propagation acceptée explicitement, refus garanti après matérialisation effective d'Unlink, monotonie malgré événements hors ordre, indépendance WRITE/READ et absence d'autorité de la liste de navigation. Le p95 ≤ 500 ms validé en V3-5C concerne **Registration/Link jusqu'au binding utilisable**, pas Unlink jusqu'à révocation ; aucun SLO de révocation n'a été fixé. Aucun test V3 exécuté.

## 22. Protocole détaillé REC-01 — reconstruction READ totale et rejeu indépendant

**Statut : protocole proposé, non exécuté.** V3-4 retient deux opérations différentes : **reconstruction totale de READ à blanc**, avec arrêt des writers READ, reset cohérent et réutilisation possible des mêmes `consumptionId` ; **rejeu indépendant**, défini comme une nouvelle Consumption portant un nouvel ID et des effets dont la destination/coexistence est explicitement maîtrisée. Aucune remise à zéro isolée des checkpoints ou slots n'est sûre. Les événements immuables WRITE restent disponibles sans purge dans le périmètre V3.

### Invariants de reconstruction

- **Barrière opérationnelle :** avant le reset, empêcher toute nouvelle acquisition Discovery/Engine READ et attendre/neutraliser les écritures READ en cours ; une transaction READ ancienne ne peut pas committer après le reset en réinjectant des effets périmés. Pendant la reconstruction, les APIs ne doivent pas présenter READ partiellement reconstruit comme complet et prêt. La remise en service exige un critère explicite de disponibilité.
- **Ensemble cohérent :** projections READ, tâches internes (`POT_BALANCE_TASK`, `POT_VISIBILITY_TASK`), slots, claims/leases de partition READ et checkpoints sont réinitialisés de manière coordonnée. Les définitions et IDs des Consumptions réutilisées restent stables. Les événements immuables de WRITE et les demandes autoritatives ne sont pas effacés.
- **Reconstruction à mêmes IDs :** les checkpoints non-burnables repartent de zéro ; les tâches sont recréées avec leurs IDs déterministes d'origine, mais dans un espace READ entièrement réinitialisé. Les sources terminales WRITE ne sont **pas** rouvertes : READ redécouvre les événements immuables, pas les COMMAND/IDENTITY_REQUEST déjà terminales.
- **Consommations indépendantes :** leur finalisation n'exige pas d'ordre global ; un jalon de remise en service doit néanmoins attester les projections nécessaires aux parcours annoncés. Le rattrapage de CurrentPot ne suffit pas à lui seul à établir que les balances, visibilité, binding et résultats sont prêts.
- **Rejeu indépendant :** un nouveau `consumptionId` produit des slots/checkpoints propres et, pour une nouvelle Consumption de préparation, de nouveaux taskIds. Les projections existantes ne doivent pas être modifiées de façon incohérente par une deuxième génération d'écrivains ; leur destination, idempotence ou isolation doit être documentée avant la campagne.
- **Récupération d'interruption :** une panne pendant reset/rebuild doit laisser READ hors service ou en état de reprise cohérente ; aucune stratégie de restauration séparée d'un ancien checkpoint avec de nouveaux slots/effets n'est acceptable.

### Cas déterministes

| ID | Stimulus / incident | Oracle de réussite |
|---|---|---|
| RCV01-A | READ peuplé de CurrentPot, sous-versions, balances, listes, bindings, résultats, tâches et slots ; déclencher reset total | Aucune projection, tâche, slot ni checkpoint ancien ne subsiste dans le périmètre réinitialisé ; WRITE est intact |
| RCV01-B | Un Worker READ possède un claim et exécute une transaction au moment de la demande de reset | Barrière empêche un commit tardif après reset ; rollback/achèvement de l'ancien traitement avant réinitialisation, avec absence de réapparition d'effets |
| RCV01-C | Arrêt après reset mais avant relance des Consumers | READ reste explicitement indisponible ; ne sert pas un résultat vide comme une reconstruction achevée |
| RCV01-D | Reconstruire sans nouveaux consumptionIds depuis les quatre séquences événementielles conservées | Checkpoints repartent de zéro puis progressent ; mêmes IDs de Consumption ; projections attendues équivalentes aux valeurs autoritatives |
| RCV01-E | Repréparer un même `POT_EVENT` avec le même ID de Consumption de préparation après reset | Même paire de taskIds déterministes recréée, sans collision ni tâche historique résiduelle |
| RCV01-F | Reconstruction avec événements Pot et Identity entrelacés, tâches Balance/Visibility désynchronisées | Convergence indépendante vers CurrentPot, Balance, visibilité, CurrentBinding et résultats ; aucune condition d'ordre artificielle |
| RCV01-G | Panne au milieu de la reconstruction, avec quelques checkpoints et slots déjà créés | Reprise sûre à partir des données durables ou nouveau reset total coordonné ; aucune perte par avance incohérente de checkpoint |
| RCV01-H | Simuler restauration partielle : ancien checkpoint + slots effacés, ou projection effacée sans son slot | Procédure refusée/non autorisée ; ne pas déclarer READY un READ dont la preuve de complétion est cassée |
| RCV01-I | Réinitialiser alors que des tâches PENDING et TERMINAL coexistent | Toutes les tâches du cycle précédent éliminées ; aucune tâche terminale ne bloque leur recréation avec mêmes IDs après reset |
| RCV01-J | Démarrer une Consumption de replay indépendante avec nouvel ID sur événements historiques | Slots/checkpoints nouveaux, taskIds propres si préparation ; aucun réemploi d'ID de Consumption ou collision silencieuse |
| RCV01-K | Rejeu indépendant vers projection déjà alimentée par la Consumption initiale | Coexistence interdite si non démontrée sûre, ou preuve d'idempotence/isolation explicite ; pas de double écriture incohérente |
| RCV01-L | Demandes WRITE encore en arrivée pendant rebuild READ, puis arrêt et drainage mesurés | La borne de reconstruction/READY est définie (préfixes observables et projections requises), et les nouvelles publications sont ensuite consommées sans perte |
| RCV01-M | Requêtes API READ authentifiées pendant et après reset | Indisponibilité pendant reconstruction ; après READY, lectures complètes et autorisations via CurrentBinding, jamais bypass de sécurité |
| RCV01-N | Comparer état reconstruit avec oracle de l'état logique attendu, y compris versions historiques de sous-objets et PotBalance | Concordance des valeurs, versions et invariants ; aucun doublon, trou ou dérive de version |

### Méthode, preuves et décision opérationnelle restant à détailler

JUnit/ PostgreSQL réel avec Workers simultanés, points de pause déterministes, crash/restart contrôlé et vérification de chaque famille de projection. Bruno vérifie indisponibilité READ, remise en service et autorisations. Capturer états avant/après, statut de barrière, ensembles d'IDs, checkpoints, tâches, slots, contenu projeté et bornes de publication WRITE. Ne pas présumer que l'arrêt d'un processus tue instantanément ses transactions : la preuve inclut leur résolution physique.

**Point de cadrage pour V3-CADRE :** choisir et décrire la **barrière effective d'arrêt/reprise** et l'oracle de `READY` (notamment en présence de WRITE continu) ; le présent protocole définit ce qui doit être prouvé mais ne prétend pas avoir tranché ces mécanismes opérationnels. L'implémentation ne peut pas décider silencieusement d'une barrière plus faible.

**PASS REC-01** : RCV01-A à RCV01-N, reset sans survivants, impossibilité de commit ancien après reset, rejeu fidèle avec mêmes IDs, replay indépendant isolé et API protégée tant que READ n'est pas déclaré prêt. Aucun test V3 n'est encore exécuté.

## 23. Protocole détaillé OPS-01 — équité, reprise et coût du polling

**Statut : protocole proposé, non exécuté.** Référence V3-4/V3-5B/C : Discovery et Engine possèdent **deux boucles adaptatives indépendantes**, sans notification obligatoire, avec round-robin entre Consumptions, capacités configurables par Worker et Consumption, lots bornés et sélection de slots sans famine durable. La stabilité ne se déduit ni d'un débit HTTP élevé ni de la seule disparition des slots Command : elle exige progression des événements, tasks et projections READ.

### Oracles et indicateurs

- **Équité sous concurrence :** lorsqu'une Consumption dispose de travaux éligibles et d'une capacité non nulle, une autre Consumption continuellement alimentée ne doit pas la priver indéfiniment de Discovery ou d'acquisition Engine. Les slots différés par `retryAfter` ou claims actifs ne sont pas éligibles avant échéance.
- **Pas de famine interne :** dans une même Consumption, les travaux éligibles plus anciens doivent finalement être sélectionnés malgré le renouvellement permanent de nouveaux slots. Un `LIMIT` sans politique de progression n'est pas, à lui seul, une preuve.
- **Reprise :** un claim expiré peut être repris normalement lorsque la partition est effectivement possédée ; l'ancien détenteur ne peut pas finaliser après remplacement. Les erreurs persistantes restent diagnostiquables sans boucle chaude et sans faux résultat terminal.
- **Polling à vide :** mesurer appels SQL, cycles, réveils, CPU, mémoire, connexions et WAL quand il n'y a aucun travail ; le moteur doit éviter la sursollicitation PostgreSQL. Aucun nombre arbitraire de requêtes/s ou CPU % n'est fixé à ce stade : collecter une base mesurée et détecter les comportements manifestement non bornés.
- **Polling sous charge :** mesurer séparément la latence source→slot (Discovery), slot éligible→acquisition (Engine), temps de travail, taux de retries, collisions et âge du backlog par Consumption. Éviter les scans répétés proportionnels à tout l'historique ou à toutes les tombstones.
- **Capacités :** les plafonds souples de slots ralentissent l'enrôlement mais ne sautent pas de travail ; les limites Worker/Consumption doivent être respectées sans famine. L'autoadaptation reste compatible avec les SLO de V3-5C ; toute optimisation supplémentaire doit être justifiée par mesure.
- **Durabilité du rattrapage :** 10 commandes/s soutenues sur Pots moyens indépendants ne doivent pas produire de backlog à croissance continue ; en pointe 50/s, backlog temporaire accepté sous réserve de résorption ; scénario de 10 000 commandes et leurs matérialisations sous 1 nouvelle commande/s doit se terminer en ≤ 5 minutes, à démontrer en PERF-01.

### Scénarios de preuve

| ID | Mise en situation | Oracle / observation requise |
|---|---|---|
| O01-A | Plusieurs Consumptions constamment alimentées, une Consumption à faible volume | La petite Consumption progresse malgré la grosse ; compter les opportunités Discovery/Engine et latences d'attente |
| O01-B | Une même Consumption reçoit continuellement de nouveaux slots pendant qu'un lot ancien reste éligible | Les slots anciens finissent par être acquis ; aucune famine causée par la sélection |
| O01-C | Mélange de slots libres, claims valides, claims expirés et `retryAfter` futurs | Seuls les slots réellement éligibles sont acquis ; retryAfter respecté ; claims expirés repris sans duplication |
| O01-D | Une partition change de propriétaire sous charge | Le nouveau propriétaire reprend Discovery/acquisition ; claims valides des anciens restent finalisables ; pas de pause durable |
| O01-E | Plafond souple de slots atteint pour une Consumption/partition | Discovery suspend l'enrôlement sans perdre la suite ; reprend à capacité libérée et checkpoints corrects |
| O01-F | Workers saturés et capacités limitées par Consumption | Respect des limites ; les autres Consumptions avec capacité disponible progressent ; aucune saturation permanente du pool de connexions |
| O01-G | Longue période à vide, puis arrivée isolée d'une commande | Coût de polling (SQL/CPU/réveils) mesuré ; latence de réveil et chaîne E2E mesurées, sans hypothèse de notification |
| O01-H | Charge nominale, soutenue et pointe 1/10/50 commandes/s, Pots moyens indépendants | Débit offert/admis/terminal distingué ; backlog et quantiles par étape ; comparaison aux SLO validés, détaillée en PERF-01 |
| O01-I | Erreurs techniques persistantes, puis réparation de la cause | Reprises temporisées sans boucle chaude ni terminalisation fictive ; reprise de progression après réparation |
| O01-J | Nombreux événements hors partition, historique global 10k/1M/10M événements | Mesurer sélectivité, plans SQL, positions inspectées, nombre de lignes lues, progression de checkpoints ; pas de requête démesurée ignorée |
| O01-K | Volume important de slots déjà réclamés/différés et quelques slots disponibles | Les slots éligibles restent trouvables sans lecture déraisonnable des claims inéligibles ; absence de famine |
| O01-L | Déconnexion/crash d'un Worker puis redémarrage et renouvellement de claims | Observabilité des expirations, reprises, slots persistants et absence de double finalisation |
| O01-M | Pause des Workers READ et constitution d'un backlog de tasks, tandis que les commandes continuent | Les deux boucles reprennent indépendamment ; progression visible par famille, pas seulement disparition des commandes |
| O01-N | Instrumentation volontairement décalée/omise sur Discovery ou Engine dans un banc de vérification | La validation des métriques signale une frontière erronée ou absente ; simple présence d'une série ne suffit pas |

### Conditions de reproductibilité et interprétation

Combiner **JUnit/PostgreSQL** avec barrières pour les invariants de claims et de progression, **k6** pour les charges offertes, et **Prometheus/mesures PostgreSQL** pour CPU, WAL, sessions, plans, nombre d'exécutions et latences. Isoler autant que possible les injecteurs et la supervision des deux machines de référence. Fixer en V3-5D les nombres de partitions, Workers, tailles de lots, limites de capacité, populations, fenêtres de mesure et stratégie de collecte ; conserver la configuration exacte de chaque campagne.

Les propriétés « aucune famine » et « pas de surcharge catastrophique » exigent des **oracles opératoires** : fenêtre et volume d'essai, borne de progression et seuil de coût par scénario devront être pré-enregistrés après étalonnage, sans transformer une mesure défavorable en PASS rétrospectif. La stabilité exige l'observation simultanée des débits terminalisés et du backlog (nombre et âge) ; une admission à 10/s ne démontre pas que le système les traite à 10/s.

**PASS OPS-01** : O01-A à O01-N, équité et reprise démontrées, sélection utile sous backlog, coût de polling quantifié, métriques fiables et absence de saturation structurelle ; confirmation des SLO chiffrés renvoyée à PERF-01. Aucun test V3 exécuté.

## 24. Protocole détaillé PERF-01 — campagnes de performance reproductibles

**Statut : protocole de qualification proposé, non exécuté.** Les nombres de V3-5C sont des **SLO à prouver**, et non des résultats déjà observés. La campagne doit démontrer à la fois une réponse correcte/complète, une latence E2E mesurée et une capacité soutenable. Le seul débit des admissions HTTP ou la seule disparition des slots Command est insuffisant.

### 24.1 Environnements, données et calendrier des campagnes

- **Configuration A — moteur :** PostgreSQL dédié 4 vCPU/8 Go RAM ; runtimes/API/Workers sur machine dédiée 4 vCPU/8 Go RAM ; liaison locale. Générateurs k6 et supervision idéalement isolés. Authentification externe contrôlée, **contrôles d'autorisation Pocoma inchangés**. Fixer versions logicielles, PostgreSQL, mémoire, pools, schéma, nombre de partitions et Workers, quotas, paramètres Discovery/Engine, indices, WAL et journal de configuration avant toute campagne.
- **Configuration B — intégrée :** même base, Keycloak réel sur ressources isolées. Campagnes ciblées OIDC/Registration/Link/Unlink, validation JWT et binding utilisable ; séparer acquisition d'un token OIDC et appels ordinaires avec token valide. Ne pas faire de renouvellement/login à chaque commande, sauf scénario explicite distinct.
- **Pots :** petit 5 shareholders/20 expenses ; moyen 20/200 ; grand 100/2 000. **Volumes historiques distincts :** 100 Pots/10k événements ; 10k Pots/1M événements ; 100k Pots/10M événements. Les chiffres sont des profils de données, sans limite fonctionnelle implicite. Conserver jeu initial/seed, proportions d'opérations et versions. Mesurer historique long sur la base réellement peuplée, sans extrapoler à partir d'une table vide.
- **Charge offerte en boucle ouverte** (`constant-arrival-rate` k6 ou équivalent, capacité suffisante de VUs) : 1/s nominal, 10/s soutenu, 50/s pointe. Enregistrer **itérations prévues, réellement déclenchées, admissions committées, commandes métier appliquées, rejets, erreurs et projections complètes**. Les `dropped_iterations` sont un échec de validité/capacité, pas une façon de réduire artificiellement le dénominateur.
- **Profil mutations :** jeu reproductible couvrant création/modification/suppression, multi-cibles et révisions non financières ; pour chaque SLO de « nouvelle version du Pot », mesurer séparément les **commandes appliquées qui créent V**, sans les diluer avec no-op/rejets. Publier parallèlement les taux et latences de toutes les demandes. Ne pas favoriser artificiellement les commandes sans effet.

### 24.2 SLO et scénarios maîtres

| ID | Campagne | Offre / profil | Objectif contractuel |
|---|---|---|---|
| F01-A | Pot moyen nominal, indépendants | 1 commande/s, Pot 20/200, conf. A | **p95 ≤ 500 ms** admission durable → balances exactes disponibles et **p95 ≤ 500 ms** admission → Pot complet |
| F01-B | Pot moyen soutenu, indépendants | 10 commandes/s, Pot 20/200, conf. A | **p95 ≤ 1 s** admission → Pot complet, **sans croissance continue** du backlog |
| F01-C | Pointe répartie | 50 commandes/s, Pot 20/200, conf. A, fenêtre bornée puis retour nominal | **p95 ≤ 3 s** admission → Pot complet durant la pointe ; backlog temporaire accepté, résorption observée |
| F01-D | Grand Pot nominal | 1 commande/s, Pot 100/2 000, conf. A, Pots indépendants | **p95 ≤ 2 s** admission → Pot complet |
| F01-E | Identity nominale | Registration et Link, chacun avec échantillon distinct, conf. A | **p95 ≤ 500 ms** admission durable → première lecture réellement autorisée grâce au nouveau bindingToken |
| F01-F | Keycloak intégré | Parcours API/OIDC/Identity ciblés, conf. B | Vérifier les contrats sécurité, quantifier l'écart A/B et les coûts OIDC séparément ; **pas de nouveau SLO chiffré B inventé** |
| F01-G | Backlog de rattrapage | 10 000 commandes admises, travail dérivé à compléter ; redémarrer Workers à 1 nouvelle commande/s, conf. A | **≤ 5 min** jusqu'aux 10 000 commandes initiales terminales **et** toutes leurs projections requises prêtes |
| F01-H | Contention même Pot | Commandes concurrentes sur un Pot, conf. A, avec périodes de contention et retour normal | Absence de double/perte/commit partiel, convergence, retries/rejets corrects, backlog résorbé ; **pas de SLO de latence chiffré** |
| F01-I | Profils historiques et coût SQL | trois volumes historiques et plusieurs tailles de Pots | Mesurer amplification des requêtes, lignes lues, CPU/WAL/IO, latences et index/plans ; ne pas présenter de seuil implicite |
| F01-J | Polling inactif puis première commande | moteur au repos puis travail isolé | Coût SQL/CPU/réveils à vide, réactivité Discovery/Engine et E2E ; diagnostiquer les attentes, sans nouvelle mécanique imposée |

### 24.3 Durées et échantillons de qualification proposés

Ces durées sont un **protocole initial à ratifier avant exécution**, non des objectifs métier nouveaux :

| Campagne | Échauffement hors statistiques | Fenêtre évaluée | Volume offert indicatif |
|---|---|---|---|
| A nominal moyen et D grand | 3 min | **20 min chacune** | ~1 200 commandes/campagne |
| B soutenu | 5 min | **15 min** | ~9 000 commandes |
| C pointe | 5 min de base soutenue | **3 min à 50/s**, puis ≥ 10 min retour nominal | ~9 000 commandes offertes durant la pointe |
| E Identity | 3 min | **20 min Registration et 20 min Link séparément** | ~1 200 de chaque opération |
| F intégrée | 3 min | ≥ 10 min pour chaque parcours ciblé | nombre et débit documentés |
| G rattrapage | Base préchargée, Workers stoppés | temps depuis redémarrage jusqu'au dernier jalon | 10 000 commandes historiques + 1/s nouvelles |
| H contention | 3 min | ≥ 10 min de contention, puis drainage | répartition et parallélisme consignés |

Répéter les campagnes comparables **au moins trois fois** avec données et environnement remis dans un état défini, rapporter chaque exécution séparément, et ne pas cacher un FAIL en moyennant plusieurs runs. Les tailles utiles concernent les **commandes effectivement appliquées et observables** : si moins de 300 mesures valides dans une population SLO, la campagne est **inconclusive**, non PASS, même si le p95 affiché paraît favorable. Documenter p50/p95/p99, nombre d'observations, bornes/variabilité et données brutes ; les campagnes de stabilité prolongée peuvent être ajoutées si 15 min ne suffisent pas à distinguer une dérive de backlog.

### 24.4 Définition des mesures et oracle E2E

1. **Départ temporel :** réponse d'admission HTTP confirmant la persistance durable, horodatée par un chrono monotone **côté client** ; enregistrer aussi la durée HTTP séparément. Le point de départ contractuel « admission » ne doit pas être confondu avec le début de l'envoi de la requête. Une commande refusée à l'admission n'appartient pas aux commandes appliquées.
2. **Jalon Command :** retenir `requestId`, résultat terminal, `potId` et `resultingPotVersion=V`. Prouver que la commande a créé une nouvelle V, pas seulement reçu un résultat de succès sans effet.
3. **Balance :** vérifier la disponibilité **exacte** de `PotBalance(P,V)` et son contenu conforme à l'oracle ; une Balance(P,V+1) ne prouve pas que V existe. S'il n'existe pas d'API exposant exactement cette disponibilité, employer un **oracle de test indépendant sur READ** (lecture SQL/sonde de qualification), documenté et non considéré comme une nouvelle API utilisateur.
4. **Pot complet :** observer `CurrentPot(P)` ayant atteint au moins V, ses références nécessaires résolues, sa balance à la **version courante capturée** et `PotVisibilityState(P).materializedVersion ≥ V`. Si la version courante a avancé au-delà de V, vérifier distinctement la progression pour la commande V et **la complétude de la version courante observée** ; ne jamais affirmer qu'une API Pot@V historique est disponible.
5. **Identity :** après résultat cible `targetVersion`, contrôler CurrentBinding à la version cible ou supérieure, puis **réaliser effectivement une lecture autorisée** avec EUI authentifiée et nouveau token. Utiliser un Pot de référence pré-matérialisé, sans mélanger délai Identity et attente d'un nouveau Pot. Si une version supérieure a immédiatement délié/relié l'EUI, cela ne prouve pas l'utilisation du token cible : isoler les EUI durant ce scénario.
6. **Sondage :** cadence initiale proposée **25 ms** pour les sondes de qualification rapides, avec tolérance et coût mesurés. Le premier succès constitue une **borne supérieure observée**, pas la date exacte du commit. Limiter le nombre de sondes simultanées, isoler leur trafic et publier l'effet sur CPU/SQL ; si le sondage perturbe le système ou est insuffisamment précis face au SLO, campagne inconclusive et protocole à ajuster **avant** certification.
7. **Timeouts/erreurs :** une commande admise dont le jalon n'est pas observé avant la fenêtre de suivi **ne disparaît jamais de l'échantillon**. La classer censurée/timeout et la compter contre le critère de disponibilité ; indiquer clairement que les percentiles des seuls succès ne suffisent pas à certifier le SLO. Rejeter les campagnes dont les pertes de mesures, l'horloge ou les dropped_iterations biaisent le calcul.

### 24.5 Backlog, stabilité, instrumentation et capacité

- **Préparation F01-G :** couper les Workers de traitement/Discovery pertinents tout en maintenant l'admission durable WRITE, puis créer et vérifier exactement 10 000 commandes historiques `PENDING` sur Pots indépendants. Après redémarrage, chacune engendrera les événements, deux tâches de projection et effets associés ; on ne suppose **pas** que ces travaux dérivés existent déjà au moment du redémarrage. Photographier le stock avant redémarrage.
- **Fin F01-G :** les 10 000 requêtes initiales sont `TERMINAL`, chacune a son résultat terminal et ses projections attendues satisfaites, sans slots/Projection Tasks résiduels les concernant. L'arrivée de 1 nouvelle commande/s se poursuit durant la récupération et son impact est mesuré séparément. Une simple vidange des demandes ou slots ne suffit pas.
- **Stabilité B :** séries temporelles de backlog **par Consumption**, âges de la plus ancienne source/slot, débit de complétion et projection E2E ; vérifier qu'aucune pente positive durable ne se cache derrière une moyenne finale. Décrire la régression et la fenêtre choisies avant exécution.
- **Pointe C :** mesurer les latences **pendant** la fenêtre 50/s sans exclure les travaux achevés après son terme ; poursuivre les sondes jusqu'à complétion/timeout et vérifier le drainage après le retour nominal.
- **Instrumentation :** valider avec des injections déterministes que chaque timer/counter mesure la bonne frontière : source→slot, slot éligible→acquisition, préparation, verrou final, commit, événements et branches CurrentPot/Result/Tasks/Balance/Visibility/CurrentBinding, rollback, retry et expiration. Histogrammes Prometheus exploitables ; aucune étiquette à forte cardinalité ni token dans les logs. Comparer distributions côté client et agrégats internes, **sans additionner les p95 de phases parallèles**.
- **Ressources :** CPU, mémoire, pauses GC, latences SQL, nombre de lignes scannées, plans SQL, connexions/pools, attente/verrous, IO et WAL sur les deux machines ; conserver métriques et plans des phases coûteuses. Distinguer saturation PostgreSQL, moteur, injecteurs et Keycloak.
- **Critères de verdict :** **PASS** seulement si tous les invariants métier/sécurité sont conservés, volume offert réellement tenu, populations et sondes valides, et SLO contractuels satisfaits **sur chaque campagne requise**. **FAIL** si seuil dépassé ou invariant violé ; **INCONCLUSIVE** si échantillons, visibilité des jalons, trafic de sondage ou environnement empêchent d'établir le verdict. Documenter toute limitation de mesure, sans déplacer rétrospectivement les seuils.

### 24.6 Points à ratifier avant V3-CADRE et preuves à produire en V3-7

Les **durées/échantillons**, la cadence de sondage 25 ms, le nombre de répétitions et les modalités exactes de mesure de la visibilité complète sont des **propositions techniques à valider** ; le présent document ne les fait pas passer pour des arbitrages déjà acceptés par le porteur du projet. Fixer aussi la répartition des types de mutation, les paramètres de partition/Workers, les fenêtres de timeout et le contrôle d'identité des sondes.

**Livrables de preuve attendus :** scripts k6 paramétrés, fixtures et seeds SQL/domaine, inventaire de versions/configuration matérielle, export brut des observations, rapports p50/p95/p99, séries backlog et âge par Consumption, traces des commits/erreurs/retries, métrologie PostgreSQL/Prometheus, profils CPU et verdict par scénario. Les scripts historiques k6 de V2 restent une référence d'organisation, jamais une preuve de conformité V3.

**PASS PERF-01** nécessite F01-A à F01-J, tous les SLO chiffrés V3-5C correspondants, stabilité à 10/s, rattrapage en ≤ 5 minutes et qualification distincte de contention, avec métrologie fiable. Aucun benchmark V3 n'est réalisé à ce stade.

## 25. Protocole détaillé DOM-01 — reconstruction historique exacte de Pot@V

**Statut : protocole proposé, non exécuté.** V3-1 définit huit types de composants versionnés (PotHeader/Details, ShareholderHeader/Details/Weight, ExpenseHeader/Details/Financials). Chaque instance locale versionnée s'applique sur l'intervalle semi-ouvert `[activeFrom,activeTo)` exprimé en versions globales du Pot ; `activeTo=null` désigne une borne ouverte. `Pot@V` se reconstruit **directement à V** sans lire `Pot@(V−1)`. Toute relation vers un Shareholder (payer, participant, userId) doit être résolue dans **la même V**.

### Oracle de reconstruction

- Après une mutation effective, la version globale du Pot avance de **exactement un** ; une commande valide sans effet ne l'avance pas. Une mutation multi-cibles n'incrémente V qu'une fois. Les versions locales avancent uniquement pour les composants effectivement modifiés, sans copie des autres.
- Pour chaque composant d'un objet existant à V, il existe **exactement une** instance satisfaisant `activeFrom ≤ V && (activeTo=null || V < activeTo)`. Avant création, aucune instance n'est applicable ; un composant obligatoire absent après création est une anomalie technique.
- Lors d'une modification à V, fermer l'ancienne instance du composant modifié à `activeTo=V` et ouvrir la nouvelle à `activeFrom=V`, version locale précédente +1. Les composants inchangés conservent instance, version et bornes.
- La suppression d'Expense révise uniquement `ExpenseHeader.deleted`, celle du Pot uniquement `PotHeader.deleted` ; Details, Financials, Weight et autres objets survivent historiquement, éventuellement avec `activeTo=null`. Shareholder ne dispose pas de suppression métier.
- Toute référence inter-objets utilise l'identifiant métier stable (`ShareholderId`, `ExpenseId`) et est résolue au V demandé ; jamais la ligne technique d'une révision, la valeur courante WRITE ou une version locale numériquement égale à V.
- Un état historique composé d'intervalles contradictoires (chevauchement, trou après création, référence absente/mauvais Pot) est **signalé**, jamais réparé par un fallback vers le présent ou V−1.

### Cas JUnit / PostgreSQL

| ID | Stimulus | Assertion décisive |
|---|---|---|
| D01-A | Créer Pot V1 | PotHeader et PotDetails en versions locales 1, `activeFrom=1`, `activeTo=null` ; `creatorId` obligatoire |
| D01-B | Créer deux Shareholders en V2, puis une Expense en V3 | Chaque composant des enfants naît à version locale 1 et activeFrom correspondant ; objets absents aux V antérieures |
| D01-C | Changer `ExpenseDetails` en V4 puis `ExpenseFinancials` en V5 | Seul le composant modifié prend une révision ; anciennes bornes fermées exactement à V4/V5 ; autres composantes inchangées |
| D01-D | Changer `ShareholderWeight.defaultWeight` en V6 | Weight révisé seul ; Financials inchangé ; résolution des poids à V5 et V6 distincte ; balance V5 conservée (DOM-03) |
| D01-E | Modifier deux `ShareholderDetails` dans une seule commande V7 | Une seule augmentation globale V ; deux versions locales nouvelles ; aucune autre composante modifiée |
| D01-F | Supprimer Expense V8 puis Pot V9 | ExpenseHeader puis PotHeader seulement révisés en deleted ; enfants et historique encore reconstructibles |
| D01-G | Interroger chacune des versions V1…V9 dans un ordre aléatoire, sans précharger V−1 | États strictement égaux aux snapshots d'un oracle indépendant ; aucune dépendance à la reconstruction précédente |
| D01-H | Résoudre payeur, participants, poids et `ShareholderDetails.userId` après plusieurs révisions | Références et droits pris exclusivement à V demandé, sans fuite des valeurs courantes |
| D01-I | Vérifier limites d'intervalles `V=activeFrom`, `V=activeTo−1`, `V=activeTo` | Sémantique semi-ouverte exacte ; ni double version ni trou |
| D01-J | Injecter trou, chevauchement d'intervalles, composant obligatoire absent ou référence vers un autre Pot dans fixture isolée | Anomalie détectée explicitement ; reconstruction refusée plutôt que complétée artificiellement |
| D01-K | Commande valide sans effet puis mutation multi-cibles puis rollback forcé | Aucune version pour no-op ou rollback ; exactement une V globale et seules sous-versions changées pour multi-cibles |
| D01-L | Comparer reconstru­ction directe d'une V ancienne après de nombreuses mutations et suppressions | Digest canonique du Pot@V inchangé, indépendamment de l'ordre d'accès et des révisions ultérieures |

### Méthode et PASS

Construire les snapshots attendus en mémoire **indépendamment** des sélecteurs SQL/algorithmes de reconstruction testés. Exécuter JUnit contre PostgreSQL réel pour les intervalles, FK, frontières transactionnelles et relations ; compléter avec tests unitaires pour la composition pure. Capturer pour chaque V : snapshot attendu, lignes et intervalles en base, versions locales, identifiants métier et digest canonique. La consultation historique ici est un **oracle de domaine interne**, pas une exigence d'API READ historique arbitraire. Les règles de dépendance métier `observedVersion` seront éprouvées en DOM-02, les fractions et balances en DOM-03.

**PASS DOM-01** : D01-A à D01-L satisfaits, reconstruction sans chaînage à V−1, versions locales indépendantes, intervalles non ambigus, suppressions historisées et absence de repli silencieux en présence d'incohérence. Aucun code/test V3 exécuté.

## 26. Protocole détaillé DOM-02 — observedVersion, dépendances métier et collisions CAS

**Statut : protocole proposé, non exécuté.** `observedVersion` est la version globale initialement vue par l'auteur : elle reste **immuable à travers toutes les tentatives**. Chaque tentative repart de la version globale courante, recharge les données autoritatives et contrôle les sous-composants métier dont la commande dépend. Une perte du CAS global est un **conflit technique transitoire** et ne constitue jamais, à elle seule, une obsolescence métier. Une obsolescence établie sur les dépendances à la version courante donne lieu à un **rejet métier durable** sans mutation. Une commande valide sans changement réussit sans nouvelle version métier, mais avec complétion.

### Oracle des dépendances

| Commande | Dépendances dont le changement depuis observedVersion rend obsolète | Mutation attendue si effet |
|---|---|---|
| Modifier PotDetails | PotDetails | PotDetails |
| Ajouter Shareholder | Pas de sous-composante antérieure cible ; vérifier Pot actif et références utiles | Header, Details, Weight nouveaux à v1 |
| Modifier ShareholderDetails | ShareholderDetails | ShareholderDetails |
| Modifier ShareholderWeight | ShareholderWeight | ShareholderWeight |
| Créer Expense | Pas de sous-composante antérieure cible ; vérifier Pot actif et validité des Shareholders | Header, Details, Financials nouveaux à v1 |
| Modifier ExpenseDetails | ExpenseDetails | ExpenseDetails |
| Modifier ExpenseFinancials | ExpenseFinancials | ExpenseFinancials |
| Supprimer Expense | ExpenseHeader, ExpenseDetails, ExpenseFinancials | ExpenseHeader.deleted |
| Supprimer Pot | **Toute version globale intermédiaire** : `observedVersion == currentPotVersion` obligatoire | PotHeader.deleted |

Une composante existant au moment de l'observation reste compatible si son instance actuelle possède `activeFrom ≤ observedVersion` ; ce contrôle ne dispense jamais de vérifier existence, appartenance au Pot, statut actif des Headers, références et autorisations **au moment de l'exécution**. Les révisions indépendantes n'annulent pas automatiquement une commande compatible. Le moteur ne réécrit jamais `observedVersion` lors d'un retry.

### Cas déterministes

| ID | Stimulus / entrelacement | Oracle de réussite |
|---|---|---|
| D02-A | Changement PotDetails entre observation et exécution d'une modification PotDetails | Rejet métier terminal ; aucune nouvelle V ni sous-version ; complétion de rejet |
| D02-B | Changement ShareholderWeight après observation d'une commande visant ShareholderDetails (et cas inverse) | Les deux mutations peuvent aboutir avec des versions globales successives ; seule la composante cible est reversionnée |
| D02-C | Changement ExpenseDetails ou Financials avant suppression d'Expense observée antérieurement | Suppression rejetée ; aucune nouvelle mutation de Header ; un changement de Weight seul ne la rend pas obsolète |
| D02-D | Modification d'ExpenseFinancials concurrente d'un changement de ShareholderWeight | Si références et dépendances sont encore valides, les deux opérations réussissent ; balance issue des poids et Financials applicables à V |
| D02-E | Supprimer Pot à observedVersion ancienne après **n'importe quelle** mutation effective intermédiaire | Rejet ; à version globale égale et Pot actif, suppression admise ; aucune réécriture des enfants |
| D02-F | Ajout Shareholder ou Expense préparé depuis ancienne V alors que des composants sans lien ont évolué | Autoriser si Pot et références métier courantes restent valides ; aucune exigence artificielle d'égalité globale |
| D02-G | Commande multi-cibles dont une cible est devenue obsolète | Rejet global atomique, aucune autre cible modifiée, aucune nouvelle V |
| D02-H | Commande multi-cibles valide touchant plusieurs composantes | Une seule V globale supplémentaire, versions locales incrémentées uniquement sur les composantes effectivement changées |
| D02-I | Commande valide qui demande la valeur déjà présente et ne dépend d'aucune composante obsolète | Succès sans effet, aucune nouvelle V/événement de mutation ; résultat/complétion uniques |
| D02-J | Commande observée obsolète mais demandant une valeur égale à la valeur actuelle | **Rejet métier**, même si la nouvelle valeur paraît identique ; l'égalité de valeur ne supprime pas l'obsolescence |
| D02-K | Deux commandes préparent sur même version de travail, l'une gagne le CAS global et l'autre perd | Perdante rollback technique, **pas** rejet métier automatique ; claim cédé avec retryAfter selon CON-05 |
| D02-L | Après perte du CAS, la tentative suivante recharge V courante ; les dépendances initiales restent compatibles | Nouvelle tentative avec **même observedVersion**, puis succès et V suivante ; aucun double effet |
| D02-M | Après perte du CAS, la tentative suivante découvre qu'une dépendance métier a réellement changé | Rejet métier terminal décidé après rechargement ; pas d'écriture partielle ni retry indéfini |
| D02-N | Expense/Shareholder supprimé ou Pot supprimé entre observation et exécution | Vérification des Headers actifs et des références courantes ; refus conforme même si la composante directement visée n'a pas changé |
| D02-O | Fault injection entre vérification des dépendances et commit final, avec autre mutation concurrente | CAS/contrôles transactionnels empêchent toute validation sur version de travail obsolète ; retry complet ou rejet métier explicite, jamais état incohérent |
| D02-P | Deux requêtes équivalentes avec requestIds différents, puis retry technique d'un seul requestId | Chaque demande reçoit son propre résultat ; retry n'applique jamais deux fois **la même demande** ; ne pas confondre idempotence de demande et égalité des intentions |

### Preuves et critères PASS

JUnit avec **PostgreSQL réel**, barrières déterministes pour la préparation, la perte CAS, la réacquisition et la finalisation ; Bruno pour l'issue externe et `resultingPotVersion`. Capturer la version observée **avant** la première tentative, la version de travail de **chaque** tentative, les révisions locales, les événements et résultats/complétions durables, ainsi que les slots/claims de retry. Les états attendus doivent être calculés par un oracle indépendant des conditions SQL testées.

Recoupements obligatoires avec DOM-01 (intervalles), DOM-03 (soldes), EVT-02 (rejet/complétion atomique), CON-04/05 (fencing/retry) et SEC-01 (identité/autorisation). Ne pas inférer l'obsolescence d'une simple inégalité `observedVersion != currentVersion`, sauf pour la suppression totale du Pot où cette égalité est explicitement exigée.

**PASS DOM-02** : D02-A à D02-P démontrés ; validité fondée sur les dépendances métier, unchanged observedVersion, nouvelle tentative entière après perte CAS, absence de commit partiel et de faux rejet. Aucun test V3 n'a été implémenté ni exécuté.

## 27. Protocole détaillé DOM-03 — exactitude financière rationnelle et poids à la version V

**Statut : protocole proposé, non exécuté.** Règle métier V3-1 : pour toute Expense **active** à la version globale V, le payeur reçoit un crédit du montant ; chaque participant reçoit un débit proportionnel à son poids effectif à V, défini par `specificWeight` de l'Expense s'il existe, sinon `ShareholderWeight.defaultWeight` applicable à V. Le payeur n'est pas nécessairement participant. Les montants sont non négatifs ; poids spécifiques et par défaut strictement positifs ; la liste de participants est non vide. Les fractions sont représentées/calculées exactement, avec normalisation, sans flottants d'approximation. La somme des soldes est exactement nulle.

### Oracle indépendant et règles

- Pour chaque Expense active e à V, calculer indépendamment `w_i(V)`, `W(V)=Σ_i w_i(V)`, `part_i(e,V)=amount(e,V) × w_i(V)/W(V)` ; contribuer `+amount` au payeur et `−part_i` à chacun des participants. Le solde de chaque Shareholder est la somme des contributions rationnelles de toutes les Expenses actives de V. `Σ soldes = 0` **exactement**, y compris si payeur et participant sont identiques.
- Les `ShareholderId` des payeurs/participants doivent appartenir au Pot et être valides selon son état à V. Un `specificWeight` explicite ne dépend pas des révisions de `defaultWeight` ; une participation sans poids explicite suit le poids par défaut à **la version globale concernée**, sans modifier `ExpenseFinancials`.
- La révision d'un poids par défaut produit une nouvelle version globale du Pot et recalcule **toutes** les balances de cette version ; elle ne change pas les poids spécifiques, les participants, les Expenses financières ou les balances historiques précédentes.
- Une Expense supprimée contribue jusqu'à la dernière version précédant sa suppression, puis ne contribue plus ; ses composants historiques sont conservés. Une suppression de Pot conserve les balances historiques et les valeurs nécessaires à leur reconstruction, même si la consultation fonctionnelle courante du Pot supprimé est régie par son état.
- Le jeu de résultats doit inclure **chaque Shareholder attendu**, y compris ceux de solde nul conformément au contrat de balance complète ; comparer les clés et fractions normalisées, pas seulement la somme globale.

### Cas déterministes

| ID | Stimulus / fixture | Oracle de réussite |
|---|---|---|
| D03-A | Expense de 100, payeur A, participants A/B de poids 1 et 3 | Crédit A +100 ; débits A −25 et B −75 ; soldes nets A +75, B −75, total exactement zéro |
| D03-B | Payeur A non participant, bénéficiaires B/C à poids 1/2 | A reçoit tout le crédit ; B/C portent les fractions exactes du débit ; somme zéro |
| D03-C | Montants et poids conduisant à des tiers/septièmes, avec multiples Expenses | Fractions exactes réduites ; aucune dérive flottante ni arrondi cumulatif |
| D03-D | Participant à specificWeight et autre au defaultWeight, puis changer seulement le defaultWeight | Seules les parts dépendant du défaut évoluent ; ExpenseFinancials inchangé ; balances historiques intactes |
| D03-E | Tous les participants ont specificWeight ; réviser leur defaultWeight | Répartition de cette Expense inchangée, mais **nouvelle balance complète** à la nouvelle V globale |
| D03-F | Modifier amount et payerId d'une Expense, avec références valides | Solde recalculé sur les Financials exacts de la nouvelle V ; ancien résultat conservé |
| D03-G | Modifier le libellé ou la date sans modifier Financials | Même valeur numérique attendue à la nouvelle V, stockée comme balance indépendante |
| D03-H | Supprimer une Expense après plusieurs révisions, puis reconstruire ses V précédentes | Contribution supprimée uniquement à partir de la V de suppression ; histoire correcte |
| D03-I | Plusieurs Shareholders d'un Pot dont certains ne paient ni ne participent | Aucun Shareholder attendu oublié ; zéros explicitement traités conformément à la représentation canonique |
| D03-J | Montant zéro avec participants et poids valides | Tous les soldes exactement nuls, sans division illégale ni version artificielle |
| D03-K | Montant négatif, poids nul/négatif, participants vides, payeur hors Pot, références invalides | Entrée rejetée selon les invariants métier ; aucune mutation partielle ni balance incomplète |
| D03-L | Mutation multi-cibles mêlant poids et Financials | Une seule nouvelle V globale ; calcul sur l'état composé après mutation, non mélange avant/après |
| D03-M | Recalculer V ancienne après changement de poids et suppression à V+N | Résultat strictement identique à celui calculé initialement pour V |
| D03-N | Traitement Balance V+1 avant V, retry et duplication | Balances exactes, immuables et indépendantes ; aucune dépendance à une balance précédente (PRJ-02) |
| D03-O | Petits, moyens et grands Pots ; montants/poids rationnels de tailles diverses | Vérifier la conservation et l'exactitude sur cas générés reproductibles ; documenter coûts sans inférer un PASS de performance |

### Méthode et PASS

Tests unitaires du calcul pur avec **oracle indépendant** fondé sur des entiers/fractions exactes, puis JUnit/PostgreSQL réel pour les versions de poids, la reconstruction `Pot@V`, la publication et les balances immuables. Cas tabulaires déterministes complétés par cas génératifs à seed fixe ; conserver données, parts attendues et calcul des sommes. Comparer les valeurs **pour chaque Shareholder** à chaque V. Croiser DOM-01 (reconstruction), DOM-02 (mutations/versions), PRJ-02 (matérialisation immuable) et EVT-02 (rollback). Les objectifs de 500 ms/2 s restent évalués uniquement dans PERF-01.

**PASS DOM-03** exige D03-A à D03-O, conservation rationnelle exacte, poids par défaut appliqués à V, validations négatives et absence de modification des résultats historiques. Aucun test V3 n'a été implémenté ni exécuté.

## 28. Protocole détaillé ID-01 — transitions Identity, unicité EUI, tokens et atomicité

**Statut : protocole proposé, non exécuté.** Contrats V3-1 Identity et V3-5A : `PocomaUser` existe indépendamment des EUI ; une EUI unique par couple opaque `(issuer,subject)`, avec identifiant interne stable et histoire locale `version` commençant à 1, incrémentée de 1 **uniquement** lors d'une transition effective. Le rattachement `pocomaUserId?` et `bindingToken?` sont simultanément présents ou absents. `bindingToken` est neuf à chaque rattachement et nul après Unlink ; aucune entité Binding autonome n'est créée.

### Oracles métier et transactionnels

- **Registration :** demande initiée par une EUI authentifiée non liée, inconnue ou existante libre. Une réussite crée **exactement un nouveau PocomaUser**, puis crée EUI directement liée v1 ou incrémente sa version si elle existe libre. Aucune EUI liée à un User existant n'est réenregistrée.
- **Link :** E1 authentifiée et encore liée à U, E2 libre ou inconnue et consentante (les preuves d'admission de E2 sont spécifiées en ID-02). Le User cible est **déduit de E1**, jamais fourni librement ; E2 devient liée à ce User avec **un nouveau token**. Si E2 est déjà liée, même à ce User, la demande est rejetée.
- **Unlink :** E1 encore liée à U, E2 encore liée au **même U**, et le token déclaré de E2 correspond exactement au token courant. Il peut s'agir de E1 elle-même, de la dernière EUI liée à U, ou d'une autre EUI de U. Unlink rend User et token de E2 nuls, en nouvelle version ; PocomaUser persiste.
- **Unicité/historique :** le couple `(issuer,subject)` ne peut pas être dupliqué même après Unlink ; une EUI liée U1 peut passer à U2 **uniquement via Unlink puis Link** ; ses versions sont reconstructibles directement. Des `subject` égaux avec `issuer` distincts sont deux EUI distinctes.
- **Atomicité :** mutation du User/EUI, versions historiques, événements Identity correspondants, résultat, complétion et slot de la demande partagent la transaction WRITE (EVT-02/CON-06). Aucun User orphelin créé par une Registration perdante ; ni état `pocomaUserId` non nul avec token nul, ni l'inverse.
- **Concurrence :** les vérifications de l'autorité initiatrice E1 et de l'état de E2 restent valides **jusqu'au commit** ; verrouillage des EUI concernées et ordre anti-deadlock seront prouvés. Un conflit technique de concurrence se résout selon CON-05, tandis qu'un rejet métier constaté est terminal.

### Scénarios JUnit/PostgreSQL et Bruno

| ID | Stimulus / entrelacement | Oracle de réussite |
|---|---|---|
| I01-A | Registration d'une EUI inconnue | Un seul nouveau User, EUI créée directement liée en v1, token neuf, événements User v1 et EUI v1, complétion unique |
| I01-B | Registration d'une EUI existante libre à vN | Nouveau User, EUI liée à vN+1, identité interne et EUI opaque inchangées |
| I01-C | Registration d'une EUI déjà liée | Rejet sans nouvelle version, événement de mutation ni nouveau User ; complétion de rejet |
| I01-D | Deux Registrations concurrentes sur une EUI inconnue, puis sur une EUI libre | Un seul gagnant dans chaque cas ; aucun User orphelin ; unicité SQL vérifiée sous course |
| I01-E | Link E1→E2 inconnue, puis E2 libre existante | E2 liée au User déduit de E1, directement v1 ou vN+1 ; token neuf ; aucun User créé |
| I01-F | Deux Links concurrents visant la même E2, et Registration/Link concurrentes | Une transition gagnante, autres rejets sans mutation ; aucun User orphelin ni double liaison |
| I01-G | Link sur une E2 déjà liée, y compris au même User | Rejet strict ; aucune nouvelle version et aucun token renouvelé |
| I01-H | Unlink de E2 par E1 du même User, puis Unlink de E1 elle-même / dernière EUI | E2 devient libre avec token nul à version suivante ; PocomaUser persiste même sans EUI |
| I01-I | Unlink avec token E2 obsolète, E2 déjà libre, ou E2 liée à autre User | Rejet, zéro mutation EUI, aucun nouveau token |
| I01-J | Deux Unlinks croisés E1→E2 et E2→E1 avec barrières concurrentes | Au plus un succès : la seconde perd sa qualité d'initiatrice valide ; absence de deadlock persistant |
| I01-K | Link concurrent d'Unlink de E1 initiatrice | Link ne peut valider sur E1 déjà détachée ; vérification initiatrice et écriture E2 sérialisées jusqu'au commit |
| I01-L | Historique E2 : liée U1/token B → libre → liée U2/token C → libre → liée U1/token D | Versions contiguës, UserId/tokens exacts à chaque version ; B, C, D distincts et anciens tokens non acceptés |
| I01-M | Deux identités ayant même `subject` mais `issuer` distinct, et tentative de duplication du couple exact | Distinction par couple complet, unicité globale du couple et stabilité de externalUserIdentityId |
| I01-N | Exception après création du User, après modification EUI ou après un premier événement Identity, avant commit | Rollback intégral des mutations, de l'historique, des événements, du résultat et du slot ; aucun User orphelin |
| I01-O | Commit réussi mais réponse perdue, puis même requestId réexécuté | Résultat initial retrouvé, aucune seconde transition, aucun nouveau token ni User |
| I01-P | Rejeu de la même intention sous un **nouveau requestId** après succès | Application des préconditions actuelles : Link/Registration strictement refusés si EUI liée ; ne pas confondre avec retry idempotent |
| I01-Q | Contrôle d'accès au token courant après Link/Unlink via READ en retard puis rattrapé | Token exposé uniquement à l'EUI authentifiée autorisée ; propagation conforme à CurrentBinding et SEC-01/02 |

### Preuves, périmètre et PASS

JUnit sur **PostgreSQL réel** avec contraintes d'unicité, connexions et barrières transactionnelles pour les conflits, fault injection avant commit, comparaison des versions exactes et des cardinalités User/EUI/événements ; Bruno pour les issues API et témoins externes. Les tokens de test sont comparés dans des assertions privées sans être inscrits dans logs, exports de métriques ni rapports publics. Les valeurs de tokens sont imprévisibles et uniques par transition ; une simple différence sur quelques fixtures ne constitue pas, seule, une preuve de qualité de génération aléatoire : inspection du générateur et tests de son contrat sont également requis.

**Séparation des responsabilités :** ID-01 couvre les effets et préconditions d'Identity ; ID-02 démontre l'authentification et le consentement E2 à l'admission, ainsi que la portée temporelle des entitlements. EVT-02 et CON-06 couvrent le commit physique, tandis que SEC-01/02 couvrent l'accès READ et sa propagation.

**PASS ID-01** : I01-A à I01-Q satisfaits, aucune double EUI, aucun User orphelin, tokens renouvelés, transitions strictes/histoire exacte et atomicité sous concurrence. Aucun test V3 implémenté ni exécuté.

## 29. Protocole détaillé ID-02 — consentement exact de E2 et attestation externe à l'admission

**Statut : protocole proposé, non exécuté.** Arbitrages V3-5A : un Link exige que **E1 et E2 soient toutes deux authentifiées** et que **E2 consente explicitement à la demande de Link particulière**, avant son admission durable. La vérification des attestations/entitlements du fournisseur externe est effectuée **à l'admission seulement**, sans redemande à l'exécution ou au retry. L'exécution vérifie en revanche les **préconditions courantes de Pocoma WRITE** (E1 toujours liée avec son token courant à U, E2 libre ou inconnue, unicité, correspondance des EUI, autorité et invariants). Pas de mécanisme de consentement différé, d'objet Consent autonome, ni de domaine Entitlements chez Pocoma.

### Contrat probatoire et frontières de confiance

- **Admission :** établir deux identités authentifiées distinctement par `(issuer,subject)` (elles peuvent provenir du même fournisseur ou de fournisseurs différents), puis lier cryptographiquement ou via une session serveur fiable le consentement E2 à l'**intention exacte** qui deviendra la demande durable : identité E1, identité E2, nature de l'opération, cible liée/portée pertinente, identifiant ou empreinte canonique non substituable de la demande, et fraîcheur/anti-rejeu nécessaires. Les champs exacts et le mode de signature/session sont à arrêter dans le contrat d'admission V3 avant V3-CADRE : aucune simple déclaration client de `subject` ne suffit.
- **Consentement :** E2 ne choisit pas librement `pocomaUserId` ; la cible U est résolue de E1 à l'exécution. Le consentement est à l'opération précise, non une délégation ouverte « E1 peut rattacher n'importe quelle identité ». Un consentement usurpé, modifié, réutilisé pour une autre demande, expiré à l'admission ou retiré avant admission selon le protocole retenu ne doit pas créer de demande durable.
- **Admission durable :** persister l'intention immuable et les preuves/attestations nécessaires avec le `requestId` avant d'accuser réception. Une requête ne doit pas apparaître comme admise si la preuve E2 ou les entitlements externes exigibles sont absents/invalides.
- **Exécution :** ne pas réinterroger le fournisseur pour les entitlements externes ni redemander l'accord de E2 ; la preuve d'admission déjà validée reste attachée à la demande. En revanche les tokens, liens et préconditions Identity **courants en WRITE** peuvent rendre la demande obsolète/rejetée. La révocation externe post-admission n'annule pas rétrospectivement une demande admise.
- **Séparation sécurité :** authentification des EUI, preuve de consentement et `bindingToken` sont trois éléments différents. Un bindingToken connu ne prouve ni l'identité de E2 ni son consentement. Aucun `PocomaUserId` fourni arbitrairement par le client ne constitue une autorité.
- **Audit protégé :** prouver la validité/traçabilité de l'admission sans divulguer tokens, secrets de session ni attestations complètes dans logs ou métriques. Le protocole ne prescrit pas de nouveau stockage métier autonome : les données utiles restent dans la demande Identity admise.

### Scénarios de preuve

| ID | Stimulus / entrelacement | Oracle de réussite |
|---|---|---|
| I02-A | E1 et E2 authentifiées, consentement E2 valide et lié exactement à Link E1→E2 | Une seule IdentityRequest durable admise avec ses attestations ; exécution possible selon l'état WRITE |
| I02-B | E1 authentifiée, mais aucune authentification prouvée de E2 | Admission refusée, aucune IdentityRequest durable |
| I02-C | E2 authentifiée, mais consentement explicite absent ou refusé | Admission refusée ; aucun Link effectué ultérieurement |
| I02-D | Client substitue `issuer`, `subject`, E1, E2 ou la portée après consentement | Preuve invalidée ; impossible de faire porter l'accord sur une autre demande |
| I02-E | Preuve de consentement capturée pour demande A réutilisée pour demande B | Rejeu refusé ; aucun contournement par nouvel `requestId` ou payload équivalent non autorisé |
| I02-F | Consentement expiré, signature falsifiée, session détournée ou preuve émise par mauvais émetteur | Admission refusée ; aucun succès fondé sur la simple connaissance d'identifiants |
| I02-G | Entitlement externe exigé manquant, invalide ou non attesté à l'admission | Refus d'admission avant persistance, preuve d'absence de demande durable |
| I02-H | Entitlements valides à l'admission, puis révoqués côté fournisseur avant l'exécution | La demande admise conserve sa validité externe ; aucune nouvelle vérification fournisseur au Worker ; décision sur autorité Pocoma courante |
| I02-I | E1 est Unlink après admission de Link mais avant exécution | Link rejeté à l'exécution si E1 n'a plus son rattachement/token autoritatif ; aucun rattachement E2 ni effet partiel |
| I02-J | E2 est Registration/Link par une autre demande après admission, avant exécution | Première transition validée gagne ; Link tardif rejeté sans modifier E2 et sans redemander le consentement |
| I02-K | Exception technique, perte CAS/claim, expiration puis réacquisition de la même demande durable | Intention et consentement initiaux conservés ; aucune nouvelle sollicitation de E2 ni relecture des entitlements externes ; réévaluation complète des faits Pocoma |
| I02-L | Réponse d'admission perdue puis retransmission avec même clé/idempotence de demande | Au plus une demande durable correspondant à l'intention autorisée ; aucun consentement transformé en autorisation d'une nouvelle demande |
| I02-M | E1 fournit un `pocomaUserId` arbitraire ou un `bindingToken` de E2 en prétendant disposer de son accord | Admission/exécution n'utilise pas ces déclarations pour établir le contrôle E2 ; User cible déduit de E1 WRITE |
| I02-N | Deux Link concurrents avec preuves d'admission distinctes visant la même E2 | Les deux demandes peuvent être valablement admises, mais une seule mutation EUI gagne à l'exécution ; l'autre rejetée sans effet |
| I02-O | Parcours Bruno avec Keycloak réel : deux sessions/jetons E1 et E2, consentement lié à la demande exacte | Authentifications vérifiées, mauvais issuer/audience/expiration refusés ; aucune dépendance à des jetons non vérifiés |
| I02-P | Comparer erreurs pré-admission aux rejets métier terminaux post-admission | Admission refusée ⇒ aucune demande ; demande admise devenue invalide WRITE ⇒ résultat terminal de rejet et completion, sans mutation |
| I02-Q | Revue de l'intégrité des preuves persistées, contrôles d'accès et journaux | Impossible de substituer consentement/attestation au Worker ; aucun secret ou token sensible exposé en logs, traces ou rapports |

### Méthode, inconnues à trancher et PASS

Utiliser **Bruno/Keycloak** pour les authentifications indépendantes et attaques de substitution/rejeu ; **JUnit/PostgreSQL réel** pour l'admission atomique, la persistance des preuves, les conflits et la réévaluation à l'exécution ; injections déterministes pour les races et retries. Les tests doivent comprendre une implémentation de preuve négative réellement falsifiée, et pas seulement des mocks acceptant/refusant à volonté. Contrôler la base depuis une connexion indépendante pour distinguer refus pré-admission (absence d'IdentityRequest) et rejet terminal après admission.

**Décision préalable à V3-CADRE encore nécessaire :** fixer la forme du consentement E2 (deux authentifications dans un échange serveur fiable, attestation signée à l'intention exacte ou autre contrat vérifiable), ses bornes de fraîcheur, anti-rejeu et sa liaison à l'IdentityRequest sans adopter un nouveau workflow durable. Ce choix technique doit permettre les assertions ci-dessus, mais **aucune solution n'est déclarée implicitement validée**.

**PASS ID-02** : I02-A à I02-Q réussis, absence de Link sans contrôle/consentement E2 valide, vérification externe exclusivement à l'admission et autorité Pocoma recontrôlée à l'exécution, reprise sans nouveau consentement. Aucun test V3 n'a été écrit ni exécuté.

## 30. Revue transversale V3-5D — couverture, mutualisation et décisions (2026-10-23)

**Verdict documentaire : COUVERTURE INITIALE COMPLÈTE, CADRAGE ENCORE OUVERT.** Les 24 IDs V3-5A possèdent un protocole dédié dans les §§6–29 ; cela n'est ni la preuve de leurs assertions ni l'autorisation de V3-6. La revue compare les arbitrages V3-1/2/3/4, la matrice V3-5A, les jalons d'observabilité V3-5B et les SLO V3-5C. Les éléments ci-dessous sont des **constats et recommandations**, non des arbitrages présumés validés.

### 30.1 Audit de traçabilité

| Domaine | IDs de la matrice | Protocoles détaillés | État |
|---|---|---|---|
| Domaine Pot | DOM-01/02/03 | §§25–27 | Couvert, non exécuté |
| Identity | ID-01/02 | §§28–29 | Couvert, non exécuté |
| Événements | EVT-01/02/03 | §§6–8 | Couvert, non exécuté |
| Consumption | CON-01/02/03/04/05/06 | §§9–14 | Couvert, **duplication éditoriale CON-06** |
| Projections | PRJ-01/02/03/04 | §§15–18 | Couvert, non exécuté |
| Lectures | READ-01 | §19 | Couvert, non exécuté |
| Sécurité | SEC-01/02 | §§20–21 | Couvert, non exécuté |
| Reconstruction | REC-01 | §22 | Couvert, décision de reprise ouverte |
| Exploitation | OPS-01 | §23 | Couvert, fenêtres/bornes à finaliser |
| Performance | PERF-01 | §24 | Couvert, campagne proposée à ratifier |

**Anomalie documentaire A-01 :** deux blocs portent exactement le titre de niveau 2 « §14 CON-06 » ; ils décrivent tous deux l'unicité de la transaction physique et des managers. **Ne pas exécuter deux suites distinctes au seul motif de ces deux blocs.** Fusionner leurs oracles dans une unique fiche CON-06, garder la meilleure matrice de preuves, fournir une table de correspondance des IDs de cas conservés et vérifier les liens ; ne pas supprimer des assertions sans équivalent. Les autres protocoles restent uniques par ID de la matrice.

### 30.2 Réduction de redondance — un scénario physique, plusieurs preuves

Construire **une suite canonique par mécanisme**, avec des assertions complémentaires et des vues de rapport par exigence ; aucun doublon d'exécution n'est exigé quand la même transaction/fixture prouve plusieurs IDs :

| Suite canonique proposée | Exigences couvertes | Ce qui ne doit pas être perdu |
|---|---|---|
| **PotVersionHistory** | DOM-01, DOM-02, PRJ-01 | Versions locales, CAS, invariants historiques, atomicité, CurrentPot et sous-versions ; distinguer oracle WRITE historique de READ courant |
| **BalanceExact** | DOM-03, PRJ-02, READ-01 | Fractions rationnelles, chaque V, décalages de matérialisation et interdiction de mélanger les versions |
| **IdentityTransition** | ID-01, ID-02, SEC-01, SEC-02 | Deux EUI authentifiées pour Link, consentement ciblé, tokens, Unlink et retard READ |
| **WriteAtomicity** | EVT-01/02, CON-04/05/06 | Séquences denses, même transaction physique, writer poisoning, commit incertain, unicité de finalisation et retry après rollback |
| **DiscoveryTasks** | EVT-03, CON-01/02/03, PRJ-04 | Huit familles logiques, quatre séquences source, checkpoint/slot atomicité, claim vs ownership, création et finalisation des deux tasks |
| **ReadProjection** | PRJ-01/03/04, READ-01, SEC-01 | Versions indépendantes, visibilité collective, autorisation au Shareholder effectif et complétude par endpoint |
| **RecoveryOperations** | REC-01, OPS-01, CON-01/03 | Stop/reset/rebuild, reprises, absence de famine, polling SQL, stabilité du backlog |
| **PerformanceQualification** | OPS-01, PERF-01, V3-5B | Débits réellement terminalisés, SLO E2E, coût SQL et exactitude du branchement des instruments |

Les catégories ci-dessus sont une **organisation du runner et des fixtures**, non de nouveaux objets du domaine ni de nouvelles Consumptions. Préserver des tests unitaires purs quand suffisants ; réserver PostgreSQL/Keycloak/k6 aux garanties qu'ils sont seuls à pouvoir établir.

### 30.3 Décisions d'architecture et de contrat bloquant le passage V3-CADRE

| Réf. | Décision à obtenir | Pourquoi elle est structurante | Sortie minimale attendue |
|---|---|---|---|
| **C-01** | **Contrat de preuve de consentement E2 pour Link** : identité E1/E2, liaison immuable à la demande, fraîcheur et anti-rejeu | Sans preuve vérifiable de E2, ID-02 peut être « testé » sans véritable sécurité ; les règles V3-5A ne décrivent pas encore comment l'échange est garanti | Séquence d'admission courte, modèle de menace, éléments liés à requestId/intention, anti-rejeu et contrôles d'admission/exécution ; **sans workflow différé** |
| **C-02** | **Barrière de reconstruction READ et oracle READY** : empêcher commit de l'ancien monde, reconstruire coordonnées slots/checkpoints/projections et fournir une borne de progression malgré WRITE continu | Un reset seulement logique peut perdre des événements ou réintroduire les effets d'un ancien Worker ; sans READY, READ peut exposer un état incomplet | Protocole d'arrêt/fence et de reprise, condition testable de remise en service, traitement des publications après la borne, choix du mode indisponible |
| **C-03** | **Oracle d'E2E « Pot complet à V »** en cas de V courante ayant avancé au-delà de la commande | V3-5C exige un p95 par commande appliquée, alors que l'API ne sert pas un Pot historique arbitraire. Tester seulement `CurrentPot>=V` ne prouve pas que les composants et balances **de V** ont été matérialisés | Décider si le jalon se prouve par versions immuables et états de tâches/visibilité (oracle test hors API) avec corrélation de la commande V, sans ajouter d'API historique ni déclarer disponible un V non servi |

**Ces trois décisions sont préalables au verdict V3-CADRE.** Elles ne demandent pas de choisir prématurément tables SQL, classes Java, méthodes de locking exactes ou noms de endpoints. Si C-03 est insoluble sans changer un contrat, remonter la contradiction à V3-3/5C plutôt que fabriquer un succès à partir d'un simple watermark.

### 30.4 Paramétrage de tests reportable à V3-6/V3-7 sans rouvrir V3-CADRE

- **T-01 :** codes HTTP précis, schémas d'erreur « indisponible / refusé / inexistant », fixtures Bruno ; il faut maintenir dès V3-CADRE la **distinction sémantique**, mais les numéros HTTP relèvent du contrat API V3-6.
- **T-02 :** durées k6, taille des échantillons, cadence de sondage 25 ms, répétitions, seuils de contamination par la sonde, mix exact des mutations et limites des générateurs. Ce sont **des propositions §24**, à figer *avant exécution* et non nécessairement avant la validation de principe V3-CADRE, si C-03 est résolu.
- **T-03 :** nombre de Workers/partitions, tailles de lots, fenêtres d'équité, seuils et coût de polling OPS-01 ; en V3-CADRE la propriété de non-famine et la capacité de preuve doivent être acquises, les bornes calibrées et figées avant campagne.
- **T-04 :** noms des histogrammes et des métriques, seuils d'alertes, structure des dashboards, scripts de recueil ; V3-5B impose déjà les **frontières** observées et la validation du branchement.
- **T-05 :** implantation SQL/Java de la barrière et du traitement de consents, fixtures de crash, orchestration k6/Keycloak, CI et rapports de tests ; ces choix doivent respecter C-01/C-02 et les protocoles mais relèvent de V3-6.

### 30.5 Ordre de décision et plan de consolidation

1. **Trancher C-01** (séquence Link la plus simple vérifiable) puis reporter la décision aux docs V3-1 Identity, V3-5A et ID-02 si nécessaire.
2. **Trancher C-02** (rebuild à blanc, barrière et READY) puis reporter à V3-4, V3-3 et REC-01.
3. **Trancher C-03** (preuve E2E exacte d'une version V sans endpoint historique) puis aligner V3-5B/5C, PRJ-02/04, READ-01 et PERF-01.
4. **Consolider CON-06** en un bloc unique sans perte de cas, puis adopter suites et jeux de données mutualisés par mécanisme. Produire matrice exigence→scénario canonique→assertion→instrument→preuve.
5. Mettre à jour **canon et feuille de route** après décisions ; procéder à la revue finale des contradictions interdocuments, des cas tests réellement vérifiables et de la simplicité conceptuelle.
6. **Soumettre explicitement le jalon V3-CADRE** ; jusque-là aucune implémentation runtime/DDL V3 ni exécution revendiquée.

**Conclusion :** 24/24 exigences ont une spécification de preuve. Il subsiste **trois décisions de contrat/architecture** à fermer ; les modalités de tuning et d'outillage peuvent être différées sans affaiblir les invariants. **V3-CADRE demeure OUVERT.**

## 31. C-01 — proposition de contrat minimal pour l'authentification et le consentement E2 (2026-10-23)

**Décision proposée, NON ENCORE RATIFIÉE :** conserver **une unique admission Link**, deux authentifications OIDC E1 et E2, et **aucune nouvelle entité métier ni demande intermédiaire persistée**. Éviter toutefois l'affirmation fausse « deux jetons quelconques = consentement ». L'authentification E2 doit être initiée depuis **un écran de confirmation de Link explicitement présenté à E2**, et reliée à l'intention exacte par un état d'authentification vérifiable côté Pocoma.

### Parcours minimal recommandé

1. E1 est authentifiée et choisit « rattacher une seconde identité ». Le client prépare l'intention canonique `Link(E1,E2?,requestId)` avec un `requestId` unique ; le User destinataire n'est jamais un choix arbitraire de l'appelant.
2. Pocoma fournit une **preuve de contexte temporaire signée/protégée**, stateless si possible, liant `purpose=link`, E1, `requestId`, expiration courte, aléa et empreinte des champs de demande connus. Aucun slot, Consent ou IdentityRequest durable n'existe encore. Si E2 n'est pas connue avant authentification, son identité sera extraite du second jeton ; le contexte doit interdire la substitution de cette E2 après capture.
3. L'interface de confiance affiche à E2 « Autoriser le rattachement de cette identité à E1 ? » avec choix explicite. **Après confirmation**, le client réalise l'authentification OIDC de E2 avec Authorization Code + PKCE, `state` anti-CSRF et un `nonce` propre au contexte Link, dont le lien avec la preuve signée est vérifiable. Un `max_age` approprié peut imposer une preuve de fraîcheur selon le fournisseur ; `prompt=select_account` peut aider à éviter la réutilisation silencieuse du compte E1, sans constituer une preuve de consentement.
4. Le client soumet **une unique requête d'admission Link** avec l'identité vérifiée E1, la preuve d'authentification E2, le contexte signé et la confirmation liée à ce contexte. Le serveur valide les deux authentifications, l'émetteur, l'audience de **chaque type de jeton**, expiration, `nonce` du jeton d'identité E2, contexte/intention, fraîcheur, absence de substitution et anti-rejeu. **Ne jamais accepter un ID Token dont l'audience est uniquement le client comme un Access Token API générique** : l'API doit disposer d'un contrat explicite et sûr pour vérifier la preuve OIDC issue du flow qu'elle a initié.
5. **L'admission durable** associe intention, `requestId` et résumé/intégrité des preuves validées atomiquement ; le même contexte ne peut créer deux IdentityRequests distinctes. Le Worker ne redemande pas le consentement, ne recontacte pas l'IdP pour les entitlements, mais contrôle l'état Pocoma WRITE de E1/E2. Après usage, les jetons E2 temporaires sont abandonnés ; les secrets/token bruts ne sont pas journalisés.

### Limites de sécurité à ne pas dissimuler

- Le `nonce` OIDC prouve l'association **d'une réponse d'authentification avec une requête OIDC donnée** ; **il ne prouve pas que E2 a lu ni approuvé une description métier**. Ce dernier point exige une interface de confiance réellement opérée sous la responsabilité de Pocoma, ou une confirmation signée explicitement transactionnelle par un composant de confiance. Un simple booléen `consent=true` transmis par un client librement modifiable ne satisfait pas ID-02.
- Dans une SPA non fiable, un initiateur malveillant ou un navigateur compromis peut omettre l'écran de consentement ; une signature côté serveur du contexte ne corrige pas, à elle seule, ce défaut. **Le modèle de menace doit décider** si le client Pocoma contrôlé et son interaction explicite suffisent, ou si une page de confirmation sous origine serveur Pocoma/BFF est requise. En cas de séparation physique des personnes/appareils, le parcours à deux jetons sur un seul appareil n'est pas toujours acceptable ; cela demanderait un autre contrat, hors périmètre de cette simplification.
- L'authentification E2 peut réutiliser une session SSO existante ; sélectionner explicitement E2 et contrôler `auth_time` si fraîcheur exigée. `prompt=login` ou `select_account` dépendent des capacités réelles de chaque IdP ; ne pas supposer que tous les providers les garantissent à l'identique.
- Avant de considérer C-01 fermé : **valider explicitement le modèle de confiance de la confirmation E2**, la validation serveur du jeton E2 et sa portée d'audience, le format du contexte lié à `requestId`, le traitement de `nonce/state`, l'anti-rejeu en cas de compétition et la confidentialité du flux. Ne pas déplacer ces décisions vers la seule implémentation.

### Tests de discrimination à ajouter à ID-02

- Un ancien jeton E2 non lié au contexte, même valide et récent, est refusé.
- Une preuve E2 liée à `requestId=A` ne peut pas autoriser B ni un changement de E1/E2.
- Une authentification SSO silencieuse sans **confirmation de confiance** ne vaut pas consentement.
- Deux admissions concurrentes réutilisant le même contexte produisent au plus une demande durable autorisée.
- Une altération d'audience, de nonce, de contexte signé ou du jeton E2 entraîne refus pré-admission.
- Une seconde authentification réussie est compatible avec E1 toujours connecté ; elle ne remplace pas automatiquement la session principale.

**Statut de C-01 : OPTION PRIVILÉGIÉE, À RATIFIER**, et non résolue : la vérifiabilité du consentement E2 dépend du choix explicite de la frontière de confiance de l'interface. Aucun code ni test V3 exécuté.

## 32. C-01 — décision de principe : fournisseurs attestant un consentement transactionnel (2026-10-23)

**DÉCISION DE PRINCIPE VALIDÉE :** Pocoma n'exige pas la compatibilité universelle des fournisseurs OIDC. Pour **Link**, seules sont admises des autorités capables de (a) vérifier l'identité de E2, (b) lui présenter l'opération spécifique de rattachement, (c) recueillir sa **confirmation explicite**, et (d) retourner à Pocoma une preuve authentique et vérifiable liée à l'intention exacte, au compte destinataire, à E2 et à l'identifiant de demande, avec fraîcheur, anti-substitution et anti-rejeu. Une authentification OIDC ordinaire sans consentement transactionnel **ne suffit pas**. La collecte du consentement et son écran appartiennent à l'autorité externe ; aucune page Pocoma de substitution n'est exigée.

**Référence technique privilégiée :** OAuth 2.0 Rich Authorization Requests (RFC 9396, `authorization_details`) pour exprimer l'autorisation métier, éventuellement avec PAR pour protéger sa soumission ; le support du standard ne prouve **pas à lui seul** l'affichage, le consentement réel ni la preuve de l'octroi. Chaque fournisseur doit être qualifié sur la chaîne complète. Un mécanisme différent est recevable s'il satisfait les mêmes garanties vérifiables.

**Frontière temporelle :** E1 prépare une intention corrélable mais aucune `IdentityRequest` durable n'est admise avant authentification, consentement et vérification de la preuve E2. Le retour validé entraîne **une seule admission durable** puis l'exécution métier asynchrone habituelle ; les contrôles des liens/tokens courants se font dans WRITE. Aucun Consent métier, nouveau Worker ou workflow de demande en attente n'est introduit.

**Toujours à spécifier avant clôture C-01 :** format canonique des données montrées et attestées (notamment compte Pocoma désigné et E1), audience et intégrité de la preuve d'octroi, corrélation `requestId`, politique de fraîcheur, consommation unique en concurrence et méthode de qualification du fournisseur. **Ne pas assimiler un `state`/`nonce` OIDC ou un jeton d'identité classique à une attestation de consentement métier.**

**Statut :** exigence de fournisseur et modèle d'autorité **VALIDÉS** ; détails du contrat de preuve **À FERMER**, V3-CADRE toujours ouvert, sans implémentation.

## 33. C-01 — contenu approuvé du consentement E2 (2026-10-23)

**ARBITRAGE VALIDÉ — contenu de l'écran externe :** le fournisseur d'identité présente explicitement **Pocoma** (application bénéficiaire), **E1** (identité authentifiée initiatrice identifiant le compte cible), **le nombre de Pots distincts actifs auxquels ce PocomaUser participe** et **le nombre d'EUI actuellement liées à ce même PocomaUser**. Il indique sans ambiguïté que E2 autorise **le rattachement de sa propre identité à ce compte Pocoma existant**, lui permettant d'y accéder selon les droits associés. Il propose une confirmation explicite **Autoriser / Refuser**. **Aucun solde financier, aucune liste détaillée de Pots et aucun PocomaUserId technique public.**

**Définitions :**
- « Nombre de Pots » = nombre de `PotId` métier **distincts, actifs**, avec au moins un Shareholder lié au PocomaUser cible dans la version courante prise comme référence ; deux Shareholders du même User dans le même Pot comptent une fois.
- « Nombre d'identités liées » = nombre d'EUI **actuellement rattachées** à ce PocomaUser dans l'autorité Identity au moment du récapitulatif ; E2 n'est pas encore comptée si libre.
- Les deux nombres sont des **photographies informatives** datées ; ils peuvent changer entre présentation et exécution. Aucun invariant métier d'égalité des compteurs n'est ajouté au Link.

**Contrat de preuve :** l'attestation vérifiable retournée par le fournisseur porte sur le consentement explicite de **E2** à une intention Link précise, son `requestId`, l'identité E1 et un **récapitulatif canonique** contenant les deux compteurs, leur date de référence et la destination Pocoma. La preuve de consentement ne se réduit pas à une authentification ordinaire. Un changement de destinataire ou du récapitulatif **après** consentement invalide la preuve ; une évolution ultérieure naturelle du nombre de Pots/EUI ne l'invalide pas. Pas de nouvelle entité Consent ni de demande Link persistée avant validation de la preuve E2.

**Exigence de cohérence à détailler :** les deux chiffres proviennent de domaines/projections indépendants (Pot, Identity), sans snapshot global commun garanti. Il faut indiquer **leur instant ou watermark de référence et la fraîcheur connue** ; éviter de présenter une estimation READ en retard comme « état exact instantané ». Une lecture du compteur de Pots depuis une liste READ éventuellement périmée ne suffit pas à revendiquer un décompte autoritatif. Le protocole de collecte/qualification des deux compteurs et la gestion d'une valeur indisponible doivent être choisis avant le verdict définitif C-01, sans introduire de lecture WRITE synchrone non prévue à l'admission ni barrière inter-Pots.

**Tests additionnels ID-02 :** vérifier le dédoublonnage des Shareholders par Pot ; exclusion des Pots supprimés ; comptage des EUI liées ; attestation intègre du récapitulatif réellement affiché ; rejet de substitution E1/compteurs avant admission ; modification légitime des compteurs après consentement sans faux rejet ; impossibilité de présenter des compteurs retardés comme parfaitement actuels.

**Statut :** contenu métier du consentement **VALIDÉ** ; modalités techniques du fournisseur et preuve/fraîcheur des compteurs **À SPÉCIFIER** ; V3-CADRE reste ouvert.

## 34. C-01 — fraîcheur des informations de consentement (2026-10-23)

**ARBITRAGE VALIDÉ :** les deux compteurs présentés à E2 — nombre de Pots actifs distincts du PocomaUser cible et nombre d'EUI liées — doivent reposer sur des informations **vieilles de moins de cinq minutes** au moment de leur présentation. **Aucune fraîcheur à la seconde** ni synchronisation globale des deux domaines n'est exigée. Les deux compteurs sont informatifs ; le consentement porte sur l'identité cible E1 et l'opération Link, non sur une égalité des compteurs à l'exécution.

**Sens précis de la borne :** horodater la consultation ne prouve pas que les projections asynchrones sont fraîches. Chaque compteur doit disposer d'un **indice vérifiable de fraîcheur de sa source**, ou d'un contrat opérationnel permettant de garantir que les changements pertinents les plus anciens encore non appliqués datent de moins de cinq minutes. Pour les Pots, vérifier les traitements de visibilité/listes nécessaires ; pour Identity, la projection des liens EUI. Un checkpoint de Discovery seul n'est pas toujours une preuve de matérialisation terminée lorsque des slots/tasks subsistent. Les indices peuvent être indépendants pour chaque compteur : aucun snapshot atomique multi-Pots/Identity n'est créé.

**Comportement en cas de fraîcheur non démontrable :** ne pas présenter un compteur potentiellement périmé comme actuel. Différer la présentation de l'écran de consentement ou indiquer explicitement « information indisponible » si cela est admis par le contrat UX final. Aucun nombre approximatif inventé, aucun appel WRITE synchrone imposé. La mise à jour des compteurs après présentation, même avant exécution, ne rend pas invalide le consentement déjà accordé.

**Tests ID-02 / READ / OPS à décliner :** compteur alimenté avec retard connu inférieur à cinq minutes ; retard supérieur à cinq minutes ; timestamp de lecture récent mais projection ancienne ; absence d'indice de fraîcheur ; divergence légitime des dates de référence Pot et Identity ; évolution post-consentement ; frontière stricte de cinq minutes. Mesurer le délai sur des horloges maîtrisées et conserver la preuve de progression jusqu'aux projections nécessaires.

**Point technique restant :** choisir l'indicateur minimal de fraîcheur de chacun des deux compteurs et vérifier qu'il ne requiert pas d'ajouter un modèle de snapshot ou une nouvelle Consumption. Le seuil de cinq minutes est **validé**, sa preuve technique reste **à cadrer**. V3-CADRE demeure ouvert.

## 35. C-01 — preuve de fraîcheur à cinq minutes : faisabilité et limites des projections (2026-10-23)

**Revue technique, PAS une décision de nouveau mécanisme.** L'objectif « compteurs âgés de moins de cinq minutes à la présentation » ne peut **pas** être démontré par la seule date d'une requête READ, la version d'une ligne ou la position d'un checkpoint Discovery. Le checkpoint de la Consumption non-burnable prouve que la source a été **examinée/enrôlée**, pas nécessairement **traitée** ; les tâches de visibilité peuvent subsister, et `CurrentBinding` progresse indépendamment.

### Option de preuve minimale — borne temporelle sûre + drainage

Au moment où un récapitulatif est préparé, choisir une borne temporelle `T0 = maintenant − 5 minutes` avec horloges cohérentes. Pour chaque source concernée, obtenir un **préfixe sûr des publications durablement committées avant T0** (un numéro lu à un instant de référence prouvé, sans inférer la date de commit du timestamp créé *dans* une transaction non encore committée). Prouver ensuite que **tous les événements pertinents de ce préfixe** ont terminé leurs Consumptions menant au compteur. Cette borne peut être capturée/persistée par supervision légère périodique ; **ne pas ajouter de lecture métier WRITE synchrone au parcours d'admission Link**. Les partitions et retards hors ordre doivent être couverts ; il ne suffit pas d'un watermark de la dernière ligne vue.

- **Pots :** les mutations Pot incluses dans la borne sûre doivent avoir été entièrement appliquées à la visibilité via `PotVisibilityState` / tâches de visibilité, avec absence de slots non finalisés pour ce préfixe ; le compteur doit provenir d'une représentation correspondant exactement à sa définition métier.
- **Identity :** les événements EUI du préfixe sûr doivent être appliqués à `CurrentBinding`, malgré consommation indépendante, avant de compter les EUI effectivement liées. Pas de « dernière mise à jour récente » comme preuve si la file peut être bloquée.
- **Atomicité de lecture :** lire les compteurs et leurs indicateurs de fraîcheur sur un état cohérent côté READ, puis indiquer une **date de référence / fraîcheur démontrée**. Un nouveau changement après T0 n'oblige pas à rafraîchir en moins d'une seconde ; l'égalité exacte à l'instant de présentation n'est pas promise.
- **Défaut de preuve :** afficher « information indisponible » ou retarder la présentation selon le choix UX à ratifier, sans transformer un chiffre ancien en affirmation actuelle.

**Attention :** l'option ci-dessus constitue une **preuve par préfixe traité**, pas une recommandation de stocker un checkpoint de complétion supplémentaire. Sa faisabilité avec les seuls états actuels `checkpoint + slots` et `PENDING/TERMINAL` doit être démontrée ; tout stockage durable supplémentaire serait une décision V3-CADRE explicitement justifiée, pas une optimisation implicite.

### Incompatibilité sémantique identifiée pour le nombre de Pots

`CurrentUserPotList` contient les Pots visibles en tant que **créateur OU Shareholder lié** (V3-3), alors que la définition adoptée §33 compte les Pots auxquels le User **participe par Shareholder**. Les deux décomptes peuvent diverger pour un créateur non participant. En outre, la ligne `(userId,potId,visible)` n'enregistre pas la **raison** de visibilité : on ne peut donc pas en extraire le compte exact des participations sans examen supplémentaire des Pots concernés.

**Choix recommandé pour simplicité (à valider) :** afficher **« Nombre de Pots auxquels ce compte a accès »**, calculable à partir des entrées distinctes `visible=true` de la navigation, en précisant le retard borné et en conservant l'autorisation métier séparée. Ce choix **change le libellé et la définition** approuvés en §33 ; il n'est **pas** validé automatiquement. Si « participe comme Shareholder » est indispensable, définir une lecture qui recontrôle les Shareholders sur les Pots concernés, avec coût mesuré, sans nouveau champ ou projection a priori.

### Autre détail : nombre d'identités liées

`CurrentBinding` représente chaque EUI, pas une liste indexée par User. Un `COUNT` d'EUI liées à `pocomaUserId` est possible à partir de cette projection si accès/index appropriés, **mais** sa fraîcheur globale dépend de la progression de **toutes** les EUI pertinentes, y compris celles devenues détachées : la version de la seule EUI initiatrice ne prouve pas la fraîcheur du nombre.

### Tests de réfutation indispensables

1. Checkpoint Discovery avancé tandis qu'une tâche visibilité antérieure à T0 est encore PENDING : **fraîcheur refusée**.
2. Aucune ligne `CurrentBinding` modifiée depuis plus de cinq minutes, mais aucune nouvelle mutation Identity : **ne pas confondre ancienneté de valeur et retard de source** ; si le préfixe a été prouvé drainé, la valeur reste admissible.
3. Événement EUI antérieur à T0 bloqué alors que d'autres avancent : **fraîcheur refusée**, même si une autre EUI a changé récemment.
4. Créateur d'un Pot sans Shareholder lié : valeur de `CurrentUserPotList` **différente** du compte de participations strictes.
5. Source avec transaction démarrée avant T0 mais commit après la capture : **ne pas utiliser son timestamp pré-commit comme preuve d'inclusion dans le préfixe**.
6. Récapitulatif validé, puis mutation Pot/Identity postérieure : Link reste admissible sans nouvelle comparaison des compteurs.

**Statut :** seuil de cinq minutes **VALIDÉ**, preuve par borne sûre **OPTION TECHNIQUE À QUALIFIER**, et définition « participation » versus « accès » **à arbitrer**. Pas de nouvelle Consumption ou projection autorisée par cette note ; C-01 et V3-CADRE restent ouverts.

## 36. C-01 — simplification du contexte informatif, sans garantie de fraîcheur (2026-10-23)

**NOUVEL ARBITRAGE VALIDÉ, remplaçant la borne de cinq minutes du §34 et la piste de preuve par préfixe du §35 :** le consentement transactionnel E2 vise **l'identité du compte E1 et l'action de Link**, pas un état financier ou un nombre de Pots/EUI. Les compteurs affichés par le fournisseur sont **indicatifs**, issus des projections READ disponibles. Aucune garantie de fraîcheur de cinq minutes, aucun checkpoint de complétion supplémentaire et aucune lecture synchrone de WRITE pour cette présentation. Les valeurs peuvent être anciennes ou changer avant l'exécution, sans invalider l'autorisation.

**Récapitulatif proposé :** Pocoma, identité E1 affichable sans exposer PocomaUserId, **nombre de Pots accessibles** (`CurrentUserPotList` visible=true, comprenant créateur ou Shareholder) et **nombre d'EUI liées** dans `CurrentBinding`, ainsi qu'une **date/heure de consultation des projections par Pocoma**. La mention doit être « informations consultées le… », **jamais** « état certifié à cette date ». Elle n'atteste pas de la fraîcheur des données sous-jacentes. L'abandon de « participe via Shareholder » au profit de « accessibles » reste la formulation recommandée pour coller à la navigation READ ; à confirmer explicitement si besoin.

**Sécurité :** les compteurs, leur timestamp de consultation et leur exactitude à l'instant présent ne sont **pas** des conditions du Link et ne doivent jamais être utilisés pour désigner le compte cible. C'est l'identité E1 et la référence non substituable de la demande qui identifient l'opération. La preuve délivrée par l'autorité externe doit toujours attester le **consentement explicite** de E2 au rattachement demandé, et non une simple authentification OIDC.

**Cas de test :** compte sans nouvelle activité pendant plus de cinq minutes, valeurs historiques toujours présentables avec libellé honnête ; READ en retard, chiffres indicatifs seulement ; compteurs modifiés entre confirmation et exécution sans rejet supplémentaire ; substitution de E1 ou de la destination refusée ; impossibilité de transformer l'heure de consultation en garantie de matérialisation.

**Effet sur le statut :** le blocage de fraîcheur C-01 est **LEVÉ** par simplification du contrat. Reste à fermer le protocole de preuve de consentement fourni par l'IdP (identité, audience, intention, anti-rejeu), sans imposer de mécanisme de suivi de fraîcheur.

## 37. C-01 — contrat minimal d'attestation externe du consentement Link (2026-10-23)

**PROPOSITION DE CLÔTURE CONCEPTUELLE — à soumettre à validation, aucune implémentation.** Le fournisseur de E2 est une autorité de confiance pour **authentifier E2, présenter le contexte de Link et attester son accord explicite**. Pocoma accepte uniquement les fournisseurs qualifiés sur ces capacités ; **OIDC standard seul n'est pas suffisant**. OAuth RAR (RFC 9396) constitue une manière privilégiée de décrire l'autorisation, **mais n'impose pas universellement un rendu métier précis ni un format unique de preuve de consentement** : Pocoma qualifie aussi ces deux aspects.

### Contrat abstrait de bout en bout

1. **Préparation sans IdentityRequest durable** : E1 authentifiée initie le Link. Pocoma construit une intention non substituable comprenant un identifiant unique de corrélation, l'identité E1 et un identifiant opaque de destination, la nature `LINK_IDENTITY`, le destinataire Pocoma, une échéance et un aléa anti-rejeu. Les deux compteurs et l'heure de consultation READ sont seulement un **contexte informatif** ; aucune fraîcheur maximale n'est promise.
2. **Autorisation chez le fournisseur de E2** : Pocoma soumet de façon intègre le contexte d'autorisation destiné à E2 (RAR, éventuellement PAR, ou mécanisme équivalent). Le fournisseur **authentifie E2**, présente « rattacher cette identité au compte Pocoma de E1 », les compteurs indicatifs et leur heure de consultation, puis recueille un **oui explicite** ou un refus. Le fournisseur ne transforme pas une authentification ou un consentement OAuth générique au client en accord métier.
3. **Retour vérifiable** : le serveur d'autorisation retourne, directement ou via échange serveur sûr, une attestation d'**autorisation accordée** contenant ou liant cryptographiquement : émetteur autorisé, E2 `(issuer,subject)`, audience Pocoma/partie destinataire, opération `LINK_IDENTITY`, identité E1 et destination, identifiant d'intention unique, statut `approved`, instant et limite de validité, empreinte/intégrité du récapitulatif présenté. Un simple `state` renvoyé ou un ID Token authentifiant E2 **n'atteste pas** à lui seul le consentement.
4. **Admission atomique unique** : Pocoma vérifie la provenance et la portée de l'attestation, l'intégrité de l'intention, le refus explicite éventuel, l'expiration et l'anti-rejeu. Le droit à admission est consommé **une seule fois**, atomiquement avec l'enregistrement de l'unique `IdentityRequest`. Deux callbacks simultanés ou retries HTTP ne créent pas deux demandes autorisées. Aucun objet Consent autonome ; les preuves pertinentes ou leur digest sont attachées au dossier admis.
5. **Exécution** : le Worker ne recontacte ni fournisseur ni E2 et ne renouvelle pas le consentement ; il résout le User depuis E1, vérifie E1/E2 et les bindingTokens **dans WRITE au moment d'exécuter**, et applique ou rejette la transition. Si E1 a perdu son autorité ou E2 est devenue liée, rejet métier terminal sans mutation, même si le consentement était valide.

### Qualification du fournisseur

- Capacité effective d'affichage des informations métier Pocoma, du bouton de refus et de l'accord explicite **propre à cette intention**, même en présence de SSO E2 préexistant.
- Attestation indépendante du seul ID Token, ou jeton/response explicitement conçu et vérifié pour **l'octroi transactionnel** ; signature/introspection fiable, audience contrôlée, expiration et protection contre la substitution.
- Support d'un contexte d'autorisation intègre, d'une corrélation unique et d'une protection de rejeu, y compris callbacks en concurrence.
- Le refus ou l'expiration ne crée **aucune IdentityRequest durable**. Les secrets/jetons non nécessaires ne sont ni conservés ni journalisés.

### Scénarios décisifs pour ID-02

A. Fournisseur atteste `approved` sur intention L1 pour E2 : **une** admission L1 ; le Worker revalide Pocoma WRITE.
B. Simple authentification OIDC de E2 sans octroi métier : **aucune** admission Link.
C. SSO silencieux sans présentation/accord transactionnel : **aucune** admission Link.
D. Attestation L1 réutilisée pour L2, E1 différente, cible différente ou contexte altéré : **refus**.
E. Deux callbacks concurrents de L1 : **une** admission, résultat de retry/idempotence cohérent.
F. Refus, timeout, signature invalide, audience invalide ou fournisseur non qualifié : **aucune** admission.
G. Compteurs READ différents au moment de l'exécution : **aucun rejet supplémentaire** ; la preuve concerne l'identité cible et l'action Link.
H. Consentement accordé puis E1 Unlink ou E2 liée par ailleurs : **rejet métier après admission**, aucun nouvel accord E2 demandé.

**Limite explicite :** ce contrat **ne prescrit pas** aujourd'hui que Keycloak standard livre ces capacités. Une extension ou un serveur d'autorisation compatible sera nécessaire s'il ne sait pas les fournir ; sa faisabilité réelle et sa preuve de conformité devront être vérifiées avant qualification d'un fournisseur. La représentation concrète des assertions/retours reste une décision de V3-6, à condition de conserver ces vérifications.

**Statut recommandé C-01 : contrat de sécurité conceptuellement défini, à VALIDER au jalon V3-CADRE ; qualification d'un fournisseur concret et implémentation NON EFFECTUÉES.** Prochaine décision structurante : C-02, barrière de reconstruction et READY.

## 38. C-02 — proposition de reconstruction READ avec arrêt effectif et bornes WRITE (2026-10-23)

**PROPOSITION À VALIDER, non implémentée.** C-02 peut être traité comme une **procédure de maintenance exceptionnelle**, sans ajouter de fencing permanent, de nouvelle Consumption ni de checkpoint de complétion au moteur. WRITE continue à accepter des demandes ; READ ne peut servir de consultations pendant le reset/rattrapage contrôlé.

### Protocole

1. **Maintenance de READ** : fermer les accès de consultation au périmètre READ concerné ; empêcher toute nouvelle acquisition/Discovery et tout redémarrage automatique de writers READ pendant l'arrêt. Les APIs doivent retourner une indisponibilité explicite, sans transformer une projection absente en « compte/Pot inexistant ».
2. **Arrêt effectif des writers** : arrêter **tous les processus/compositions pouvant écrire en READ**, y compris les moteurs mixtes WRITE/READ selon une procédure isolant leur composante READ ; attendre que leurs transactions PostgreSQL READ aient committé ou rollbacké et que leurs connexions soient réellement closes/neutralisées. **La perte d'un lease de partition ne suffit pas**, car un ancien claim de slot peut encore finaliser dans V3-4. Ne jamais supprimer les lignes tant que de tels commits sont possibles. Une terminaison forcée des connexions applicatives READ et constat côté PostgreSQL constituent une barrière opérationnelle envisageable ; la méthode précise dépend du déploiement.
3. **Reset total cohérent** : réinitialiser ensemble projections, tâches internes, slots, claims, états de partition et checkpoints appartenant aux Consumptions READ ; conserver les identifiants stables des Consumptions, les événements immuables WRITE et toutes les demandes WRITE terminales. Réinitialiser les données sous exclusivité de maintenance ; en cas de reset partiel/panne, refaire un reset total avant remise en service plutôt que mélanger anciennes et nouvelles générations.
4. **Capture de la borne** : enregistrer, pour **chacune des quatre séquences non-burnables** de WRITE, un préfixe **sûr de publications committées** `B = (b1,b2,b3,b4)`, choisi après le reset et avant la validation READY. Les séquences denses et leur sérialisation transactionnelle permettent de définir le préfixe sans arrêt global de WRITE ; la preuve que la lecture de B exclut des commits tardifs à position inférieure ou égale doit être incluse dans EVT-01. Les nouvelles publications peuvent continuer après B.
5. **Rattrapage** : relancer les mêmes Consumptions. Vérifier par famille et partition que Discovery a couvert les préfixes jusqu'à B, et que **tous les slots non-burnables concernant ces préfixes ont été finalisés** (checkpoint seul insuffisant). Les tâches `POT_BALANCE_TASK` / `POT_VISIBILITY_TASK` créées par la préparation des événements Pot jusqu'à B doivent être `TERMINAL` et matérialisées, pas seulement enrôlées. Pour les complétions COMMAND/IDENTITY_REQUEST jusqu'à B, vérifier également les projections Result ; pour les mutations Identity, `CurrentBinding`. Aucune attente de terminalisation des demandes **WRITE non encore terminales** à la capture : leurs événements futurs seront consommés normalement.
6. **READY** : la borne B doit être intégralement couverte pour **toutes** les Consumptions et toutes leurs dépendances READ. Contrôler la complétude et l'autorisation des parcours de lecture représentatifs avant de lever le mode maintenance. Les événements publiés après B peuvent être en cours de traitement : les consultations utilisent toujours READ-01 (`minVersion`, « complet ou indisponible ») et ne promettent pas de snapshot global instantané.

### Pourquoi ce modèle est plus simple

- Aucun nouvel état persistant de « génération de reconstruction » nécessaire **si** l'exclusivité de maintenance, l'arrêt de toutes les transactions et le contrôle de redémarrage peuvent réellement être garantis par l'exploitation. C'est une **condition explicite**, pas une propriété supposée des leases.
- Aucun arrêt prolongé de WRITE ni transaction distribuée WRITE/READ. Les quatre bornes représentent des préfixes **indépendants**, pas un instant global commun.
- Les preuves de complétion déjà définies pour les non-burnables et les burnables sont réutilisées ; aucune nouvelle notion de `completedCheckpoint`.
- Les événements après B sont permis ; s'ils font avancer un CurrentPot avant que Balance/Visibility correspondants soient prêts, READ-01 continue à rendre une réponse complète ou temporairement indisponible selon les composants requis. READY signifie **reconstruction initiale terminée jusqu'à B**, non disparition de tout lag futur.

### Scénarios discriminants REC-01

- Writer READ tenant un claim valide mais ayant perdu sa partition : **aucun commit après reset** ; l'arrêt doit être prouvé sur ses connexions/transactions physiques.
- Événement placé dans un checkpoint mais dont le slot reste en attente : **READY interdit**.
- Événement Pot préparé jusqu'à B avec l'une des deux tâches encore PENDING : **READY interdit**.
- Complétion Command/Identity avant B mais projection de résultat en retard : **READY interdit**.
- WRITE continue à publier après B : **READY possible** une fois le préfixe B et ses dépendances terminés ; les nouvelles requêtes minVersion suivent READ-01.
- Panne au cours du reset ou du rattrapage : **maintenance maintenue** ; recommencer la reconstruction totale ou reprendre seulement depuis un état cohérent prouvé.
- Préfixe global lu pendant un commit concurrent : démontrer qu'aucun événement `position ≤ B` ne committera tardivement hors de la photographie de B.

### Point à trancher pour validation C-02

**Accepte-t-on une indisponibilité totale de READ durant la reconstruction**, avec arrêt effectif de tous les writers READ sous contrôle d'exploitation, plutôt que d'introduire une stratégie « reconstruction sans interruption » et ses générations/fences permanents ? Si oui, ce protocole peut fermer C-02 **au niveau conceptuel** ; les procédures de déploiement et commandes SQL restent à établir en V3-6 puis à prouver dans REC-01. V3-CADRE demeure ouvert.

## 39. C-02 — arbitrage validé : reconstruction READ en maintenance exclusive (2026-10-23)

**DÉCISION VALIDÉE :** une reconstruction complète de READ est une opération exceptionnelle de maintenance avec **indisponibilité totale temporaire des consultations READ**, acceptée explicitement. La procédure de §38 est retenue conceptuellement : arrêter effectivement tous les writers et transactions READ, réinitialiser conjointement projections, tâches, slots, claims/propriétés et checkpoints, réutiliser les mêmes `consumptionId`, puis reconstruire depuis les événements WRITE immuables.

**Condition de réouverture :** capturer quatre préfixes sûrs de publications committées `B` dans WRITE ; la disponibilité `READY` n'est déclarée qu'après progression des Consumptions READ jusqu'aux préfixes et achèvement de **tous les effets dérivés nécessaires**, notamment les deux Projection Tasks par événement Pot, les CurrentBinding et les résultats terminaux. Le checkpoint de découverte seul ne vaut pas achèvement. Les événements postérieurs à B ne bloquent pas READY ; READ reprend son contrat habituel d'éventuel retard, `minVersion` et complétude de chaque consultation.

**Garde-fous :** ne jamais confondre perte de propriété de partition avec arrêt des claims déjà acquis : les anciens writers doivent être physiquement arrêtés ou neutralisés avant le reset. Si l'exclusivité de maintenance est perdue ou si le reset est interrompu, ne pas ouvrir READ ; reprendre une reconstruction totale cohérente. Aucun fencing/generation permanent de reconstruction et aucun arrêt prolongé de WRITE ne sont requis **sous condition que cette exclusivité soit effectivement prouvée**.

**Impact REC-01 :** considérer §38 et cette décision comme le contrat de test RCV01-A à N ; les cas de writer tardif, tâche dérivée encore PENDING, publication au-delà de B et échec de reconstruction sont des tests bloquants pour la remise en service.

**Statut : C-02 RÉSOLU AU NIVEAU CONCEPTUEL, À PROUVER en V3-6/7.** V3-CADRE demeure ouvert : C-03 et la revue documentaire finale restent à traiter.

## 40. C-03 — décision validée : performance observée par lecture READ, correction historique séparée (2026-10-23)

**DÉCISION VALIDÉE.** Abandon de l'exigence de prouver, pour les SLO de réactivité utilisateur, que chaque version historique V a été **simultanément** exposable comme Pot complet. À partir d'une commande appliquée retournant `resultingPotVersion=V`, mesurer la **première réponse authentifiée, autorisée et complète de l'API Pot READ pour `minVersion=V`**, servant une version courante `W ≥ V` et tous les composants et balances requis **à W**. Le client ne réclame ni ne reçoit artificiellement `Pot@V` si CurrentPot a avancé. L'horloge E2E démarre à l'admission durable ; conserver les règles de sondage et de traitement des non-succès de PERF-01.

**Séparation des preuves :**
- **Disponibilité utilisateur (READ-01 / PERF-01) :** première lecture réelle avec W≥V, complète et autorisée ; aucun besoin de `PotVisibilityState(P).materializedVersion ≥ V` dans ce jalon **si l'endpoint Pot ne dépend pas de la visibilité**.
- **Correction et progression historique (DOM-01/03, PRJ-01/02/04, CON-02) :** chaque balance immutable `PotBalance(P,V)` doit exister et être correcte, toutes les tâches attendues doivent terminer et la visibilité progresser ; vérifier par oracles internes/états READ, sans prétendre exposer une API Pot historique.
- **SLO technique de balance nominale :** conserver séparément le p95 ≤ 500 ms admission→`PotBalance(P,V)` exacte ; contrairement au SLO utilisateur, cette mesure peut reposer sur un oracle technique READ indépendant et précis.
- **Backlog et reconstruction (REC-01 / PERF-01 F01-G) :** garder l'exigence de terminaison des travaux dérivés (deux tasks) pour chaque événement ciblé ; une lecture courante W≥V ne suffit pas à prouver que les tâches historiques sont finies.

**Amendements aux campagnes §24 :** dans F01-A, rapporter deux SLO distincts : première lecture Pot W≥V et Balance(P,V) ; dans F01-B/C/D, le délai Pot désigne uniquement la première lecture READ complète W≥V. Le §24.4 point 4, lorsqu'il suggère la complétude simultanée des projections de V ou une exigence `PotVisibilityState≥V` pour la lecture courante, est **supplanté** par le présent contrat. Aucun nouveau jalon par version historique pour les campagnes de réactivité.

**Effet sur la revue §30 :** **C-03 RÉSOLU AU NIVEAU CONCEPTUEL** par simplification du contrat de mesure. C-01 (consentement IdP) et C-02 (maintenance READ) disposent également de décisions de principe ; il reste la revue documentaire finale, la consolidation du doublon CON-06 et la soumission explicite de V3-CADRE. Aucun code/DDL ni test V3 exécuté.

## 41. Consolidation éditoriale et pré-gate V3-CADRE (2026-10-23)

**Action réalisée :** fusion des deux fiches §14 CON-06 en **un seul protocole**, conservant la matrice d'autorités et les preuves transactionnelles les plus précises, avec correspondance des douze anciens cas, et trois cas complémentaires C06-N/O/P pour éviter toute perte (CurrentPot READ, résultats READ, consommateurs indépendants). Les 24 IDs de V3-5A conservent leur section dédiée. Les références historiques du §30 mentionnant encore le doublon décrivent l'anomalie **avant correction** ; elles ne constituent pas une anomalie active.

### Lecture transversale de cohérence

- **Domain/Identity :** Pot@V historisé en WRITE, bindings et tokens EUI historisés, `observedVersion` constant ; aucune entité Binding/Consent ou droit Entitlements propre à Pocoma n'est réintroduit. Link reste une demande unique admise **après consentement attesté par un IdP qualifié** ; les compteurs présentés sont indicatifs avec une **heure de consultation READ**, sans garantie de fraîcheur.
- **Event/Consumption :** six familles événementielles métier et deux tâches burnables internes READ ; quatre séquences événementielles globales denses, pas de nouvelle séquence pour les tâches ; transactions locales à l'autorité des effets, pas de XA. Discovery checkpoint n'est pas un accusé de fin de traitement ; claim individuel peut survivre à un changement de propriétaire de partition.
- **READ / reconstruction :** vues courantes et sous-versions/balances historiques immuables ; lecture Pot complète de W≥minVersion ou indisponibilité ; authorization indépendante de `CurrentUserPotList`. Reconstruction totale de READ **en maintenance exclusive**, avec arrêt réel des writers et READY par préfixes entièrement traités.
- **Performance :** C-03 supplante les anciens jalons E2E « version historique V simultanément exposable ». Le jalon utilisateur est la première lecture complète Pot de W≥V ; la balance historique `PotBalance(P,V)` reste une preuve et un SLO technique distincts, ainsi que le drainage des deux tâches.

### Réserves non bloquantes de principe, à traiter avant V3-6/7

1. **Historique des amendements :** les anciennes sections §24/§30 et §34/35 contiennent des formulations désormais supplantées par §§36/40. Lors de la génération du plan d'exécution, n'utiliser que le **dernier amendement explicite** et harmoniser les passages antérieurs avant de coder les suites.
2. **Qualification fournisseur IdP :** le contrat de preuve d'octroi transactionnel de §37 est défini ; la disponibilité de cette fonctionnalité chez Keycloak ou un autre fournisseur n'est **pas** prouvée. Un serveur OIDC générique ne satisfait pas automatiquement C-01.
3. **Disponibilité opérationnelle de maintenance READ :** la procédure §38/39 impose l'exclusivité et le drainage physique des transactions, à démontrer dans les compositions déployées. L'arrêt d'un lease ne suffit pas.
4. **Mesures :** les durées/volumes et sondages k6 de §24 restent des propositions à figer avant exécution ; le contrat de lecture courante amendé §40 prévaut. Pas de métrique ou latence V3 effectivement certifiée.
5. **Définition du compteur informatif de Pots :** « Pots accessibles » via la liste de visibilité est la formulation techniquement alignée à V3-3, mais le consentement n'en dépend pas et sa fraîcheur n'est pas garantie ; adopter ce libellé cohérent dans les futurs contrats d'interface.

### Conditions d'une décision explicite V3-CADRE

Avant d'autoriser V3-6 : (a) confirmer que le contrat abstrait IdP de §37 et son test de qualification sont suffisants sans imposer un fournisseur déjà opérationnel ; (b) confirmer le mode maintenance exclusive READ ; (c) confirmer C-03 et les deux plans de mesure séparés ; (d) adopter la fiche CON-06 consolidée ; (e) vérifier que les amendements actifs prévalent partout dans le plan des tests.

**Verdict actuel : PRÉ-GATE DOCUMENTAIRE CONSOLIDÉ, NON CERTIFIÉ.** Aucun test/runtime/DDL V3 exécuté ou modifié ; **V3-CADRE reste OUVERT** jusqu'à sa décision explicite.

## 42. Revue formelle de recevabilité V3-CADRE — 2026-10-23

**Verdict de revue : FAVORABLE À LA VALIDATION DU CADRAGE CONCEPTUEL (GO RECOMMANDÉ), sous réserves d'implémentation et de qualification.** Cette revue **ne déclenche pas à elle seule V3-6** : le statut du gate dans le canon reste ouvert jusqu'à décision explicite de validation. La revue est documentaire ; aucun test V3, benchmark, SQL de preuve ni runtime V3 n'est exécuté.

| Critère du canon §5 | Verdict | Raisonnement / preuve |
|---|---|---|
| V3-1 domaine Pot + Identity | **CONFORME — conception** | Histoire Pot@V et EUI@version, invariants financiers, observedVersion, transitions Registration/Link/Unlink ; DOM-01..03 et ID-01/02 (§§25–29) |
| V3-2 événements et demandes | **CONFORME — conception** | Six familles métier/événementielles, deux tâches READ burnables internes, quatre séquences denses, complétion distincte des mutations ; EVT-01..03 (§§6–8) |
| V3-3 READ et projections | **CONFORME — conception** | CurrentPot monotone, sous-versions et balances immuables, visibilité collective, CurrentBinding, lecture actuelle `minVersion` ; PRJ-01..04, READ-01 (§§15–19) |
| V3-4 Consumption/Discovery | **CONFORME — conception, non éprouvée** | Frontières transactionnelles, checkpoint+slots, claim/fencing, retry différé, 8 familles, reconstruction exclusive ; CON-01..06, REC-01 (§§9–14,22,38–39) |
| V3-5 revue, exigences, observabilité, performances et preuves | **CONFORME — dossier de cadrage** | 24/24 IDs dotés d'oracles, V3-5B, V3-5C amendé C-03, protocoles de tests V3-5D ; qualification future OPS/PERF |
| Cohérence des interfaces et invariants | **FAVORABLE** | C-01 consentement fournisseur vérifiable (§37), C-02 maintenance READ (§39), C-03 lecture réelle W≥V (§40) ; une seule fiche CON-06 (§14) |
| Ruptures V2, remplacement de tests et intégration | **CONFORME EN STRATÉGIE** | V2 non déployée ; tests remplacés par propriété, pas de compatibilité fictive ; suites mutualisées (§30), scénarios détaillés |
| Revue concluant CADRAGE VALIDÉ | **À DÉCIDER EXPLICITEMENT** | Présente revue propose GO ; l'autorisation de V3-6 doit être consignée distinctement dans le canon |

### Réserves classées, sans créer de nouveaux concepts

| Ref | Classification | Condition de levée | Conséquence si non levée |
|---|---|---|---|
| R-G1 | **Réserve bloquante pour le parcours Link opérationnel, pas pour le cadrage de son contrat** | Disposer d'au moins un fournisseur qualifié attestant le consentement transactionnel à l'intention exacte (RAR ou équivalent) ; prouver le format et l'anti-rejeu | Impossible de déclarer Link V3 opérationnel/sécurisé |
| R-G2 | **Réserve bloquante pour reconstruire/réouvrir READ, pas pour le cadrage** | Prouver arrêt physique des writers et transactions, reset coordonné et READY fondé sur préfixes avec tâches terminées | Impossible de déclarer une reconstruction READ sûre |
| R-G3 | **Réserve bloquante pour certification PERF, pas pour le cadrage** | Fixer paramètres de campagne avant essai ; instrumenter première lecture complète W≥V, balance V historique séparément, charge/backlog et métriques fiables | Aucun PASS performance revendicable |
| R-G4 | **Dette documentaire avant exécution des suites** | Supprimer/harmoniser les anciennes formulations supplantées (§§24,30,34–35) et pointer vers clauses §36/40 ; éviter des scénarios contradictoires | Plan d'implémentation ambigu, tests incorrects |
| R-G5 | **Dette de réalisation et de preuve** | Faire passer les tests PostgreSQL concurrency/rollback et les parcours Bruno/Keycloak, k6 ; inventaire des tests V2 remplacés | Aucune certification V3 |

### Décision proposée

- **GO pour la suffisance du cadrage conceptuel**, car les mécanismes structurants et les critères de vérifiabilité sont identifiés ; ne pas demander des preuves d'exécution à une étape documentaire.
- **NO-GO pour toute prétention « V3 certifiée », « fournisseur Link compatible » ou « reconstruction sûre déjà testée »**.
- **Gate V3-CADRE : EN ATTENTE DE VALIDATION EXPLICITE.** Tant qu'elle n'est pas inscrite dans le canon, ne pas lancer le chantier V3-6 ni le DDL V3.
