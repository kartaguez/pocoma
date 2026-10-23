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
