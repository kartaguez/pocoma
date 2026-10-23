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
