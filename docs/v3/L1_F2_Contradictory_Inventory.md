# L1-F2 — Inventaire contradictoire et modèle WRITE (F2-0 / F2-1)

2026-10-25. Inventaire avant modification de production. Baseline et HEAD distant vérifié : `b3df3f3e619eeed6fc551177c778b412daa4fa4b`. L1-F reste historiquement CLOSED/PASS, avec writers exhaustifs explicitement conservés dans son rapport. Ce lot corrige cette limite ; L2 n'est pas commencé.

## Préservation et sources

Checkout utilisateur `feat/runtime-monolith` à `90a3e9bc` sale ; branche locale V3 à `ddca2827` occupée par `/private/tmp/pocoma-v3-cleanup` avec dix documents indexés. Travail isolé `/private/tmp/pocoma-v3-l1-f2`, HEAD détaché de la baseline pour ne déplacer ni cette branche ni son index. Publication prévue `HEAD:refs/heads/feat/v3-architecture`, sans force, après nouveau contrôle distant. Aucun reset, stash ou nettoyage des autres travaux.

Autorités consultées : politique Reactor, canon, plan directeur, SPEC-01/03/05/09/10, contrats L1-B/C, inventaire/rapport/code/tests L1-F, migrations V1 Pot, V2 exécution et V3 index. Les résultats historiques ne sont pas des exécutions F2.

## Verification scope — déclaration avant production

- Impact attendu : `infra-persistence-primary-jpa`, mécanismes JDBC WRITE V3 et index CURRENT ; tests et preuves. Domaine et engine inchangés.
- Slice primaire : **COMMAND** ; secondaires : **NONE**. Gate canonique depuis `app/` : `./mvnw -pl runtime-command-consumption-worker -am test`.
- Diagnostics : domain-pot, engine-write-pot, infra-persistence-primary-jpa avec `-am test` ; filtres explicitement tracés.
- Impact interdit : L2, Identity réelle, HTTP/READ, autres runtimes, ports/domaines/dépendances/modules.
- Global architecture gate : **NOT REQUIRED**, aucune frontière/port/dépendance affectée. Full reactor : **NOT REQUIRED**, complément correctif borné, pas clôture majeure ni intégration main. Prochain global : gate L2 selon son plan, puis intégration.
- DB : **CURRENT**, schémas neufs, lieux Flyway `db/v3-pot/migration` et `db/v3-pot-execution/migration`. Pas de baseline V2 TRUSTED présumée. Migrations historiques éventuellement transitives du gate à signaler, pas campagne historique séparée.
- Escalade avant poursuite si port, protocole transactionnel, autre capacité ou dépendance doit évoluer. Arrêt si contradiction métier, risque transactionnel non résolu ou périmètre significativement élargi.

## Inventaire des chemins réels

`ExecutePotCommand` prépare dans un snapshot REPEATABLE_READ puis finalise READ_COMMITTED, claim → intention → Identity → Pot → révisions → résultat/événements → suffixe → commit. Il ne construit aucune chaîne. `JdbcPotHistoryAdapter.current/activeUsage` emploient déjà des sélecteurs ponctuels/groupés ; seuls `verifyHistory → versions/checkedComponent` doivent rester exhaustifs. Recherche des usages de ces helpers sur tout le code V3 : exclusivement cet audit et les quatre sites writer ci-dessous. Les writers V2 ne participent pas au handoff V3 et ne sont pas convertis.

| Chemin / mutation | Nécessaire | Historique ramené avant | Invariants / SQL existants | Concurrence | Substitution / tests |
|---|---|---|---|---|---|
| createPot : identité, V1, Header+Details | Absence, candidate1, deux locales1, même Pot, créateur | Aucun | PK identité/version, CHECK, FK, triggers complétude/effet ; initial() | Advisory absence en C ; PK sinon ; unité physique | Conserver ; création, identité incompatible, rollback composante manquante |
| recordVersion : V→V+1 | Dernière V, Header actif et valide | `versions` 1..V + chaîne Header entière | Continuité globale/locale, naissance1, bornes, ouverte ; PK/FK, CAS curseur V2 | Pot verrouillé en C, trigger CAS, PK candidat ; rollback | Dernière métadonnée indexée, contrôle local Header ; c02/c03/c04/c19 et fermeture concurrente |
| proposal : chaque mutation après metadata | Dernière candidate et propriété xid8 physique | `versions` 1..candidate à chaque méthode | Candidate courante, ownership complet ; PK + writer_xid | Candidate d'un autre commit interdite, owner poisoning | Une requête dernière version + writer_xid ; b18/b19, c17/c18/c20 |
| revisePotDetails / Headers Pot, Shareholder, Expense | Identité, ouverte exacte, locale suivante, creator immuable / deletion effective | `check → checkedComponent` : chaîne entière de la cible | Naissance, locales1..N, continuité, bornes ; uniques locales/ouvertes, CHECK/FK | close compare locale et ouverte ; une fermeture exactement, transaction caller | Préconditions locales groupées ; première, dernière, prédécesseur, ouverte et chevauchement avec celle-ci ; corruptions ciblées |
| reviseShareholderDetails/Weights multi-cibles | Identités ciblées, ouvertes, locales suivantes ; clés/valeurs cohérentes | Une chaîne par cible, requêtes répétées N | Idem ; validation de toutes avant close/insert, rollback total | Même Pot/candidate ; aucune cible partielle | Une lecture groupée par type pour toutes les cibles ; créations multiples et échec dernière cible |
| reviseExpenseDetails/Financials | Identité, ouverte de la cible et locale suivante | Chaîne cible entière | Idem ; finances rationnelles et participants typés | Même protection Pot ; ancienne fermeture atomique avec nouvelle/participants | Même contrôle local ; tests finance/suppression/historique après mutations |
| createShareholders / createExpense | Absence identité incompatible, locales1 à candidate, références | Shareholders aucun ; Expense via financialReferences chaîne Header de chaque référence | PK identité globale, FK même Pot, naissance et triggers complétude ; initial() | Unité physique ; collision PK ; pas de réparation | Créations conservées, références groupées directes et vérifiées ; b16/b17/c01/c04 |
| financialReferences (create/revise) | Identités et Headers ouverts actifs à expected V | `identities` ciblées puis `checkedComponent` tous les Headers historiques référencés | Même Pot, naissance avant mutation, Header non supprimé ; FK identités/payer/participants | Garde Pot couvre suppressions et phantoms ; Identity indépendante | Même accès local groupé par références ; invalides/manquantes/supprimées/concurrence |
| publish.changedComponents / trigger version_has_effect | Révisions nées à candidate, huit types | Ne transfère que les lignes à candidate, mais filtre `(pot_id,active_from)` sans index dédié | Publication exacte = proposal, xid8, effet non vide ; triggers différés | Validation avant suffixe, compteurs denses après tous writers | Index B-tree `(pot_id,active_from)` sur huit tables, performance uniquement ; plans/buffers/index catalogue |
| current / activeUsage / autorisation / suppression | Composantes nécessaires et références actives exhaustives | Déjà ponctuel ; toutes identités/Expenses courantes si nécessité métier, jamais versions passées | Pas de faux négatif NotLoaded ; bornes/références à V ; décisions pures | Witness Identity + Pot protège la décision jusqu'au commit | Conserver ; b06/b07/b12/b15 et c04/c24 ; coût selon cardinalité métier explicitement admis |
| verifyHistory, maintenance | Toutes versions et chaînes | Exhaustif intentionnel | Continuité globale et chaque locale, naissance, bornes et ouverte finale | Snapshot cohérent caller ; aucune mutation/réparation | Conserver explicitement ; anomalies anciennes étrangères autorisées en WRITE, audit les refuse |

## Modèle local proposé

Aucune nouvelle abstraction publique ni moteur. Réutiliser `PotHistoryRows` : métadonnée dernière par PK inverse ; helper privé au package de lecture WRITE ouverte, distinct de `component` temporel et `checkedComponent` exhaustif. Pour un lot ciblé : lire les identités par clé, puis les ouvertes en une requête, joindre première locale1, prédécesseur exact N−1 et dernière locale via index unique. Vérifier naissance de la première, validité des bornes, dernière=ouverte, jonction prédécesseur→ouverte ; rechercher un autre intervalle chevauchant l'ouverte via GiST, sans transférer de chaîne. Absence/duplication/ouverte incohérente : anomalie technique. Les références financières emploient le même contrôle sur leurs Headers, groupés.

Une révision modifiée doit être ouverte à expected, non future, de locale N, née après identité, dernière locale, sans chevauchement ; next=(N+1,candidate). Fermer par CAS **revision_id + bornes + locale** exactement cette ligne puis insérer. Tous les contrôles avant mutation pour un lot ; erreurs empoisonnent l'unité existante. Première et predecessor ne certifient pas la chaîne entière : défaut ancien hors jonctions/intervalle modifié relève de l'auditeur indépendant.

Les créations utilisent PK/FK/complétude existantes et locales1 ; suppression conserve les composantes non-Header ouvertes. NotLoaded n'est jamais traité comme absent. Domaine conserve droits et observedVersion ; protocole conserve claim/Identity/Pot/ownership/publication. Aucun cache, verrou supplémentaire d'autorité ou réparation.

## Preuves à produire avant clôture

Matrice séparée mécanisme/invariant ; suites B/C/D/E et unités domaine/engine dans COMMAND ; tests F2 de corruptions locales vs étrangères, exact close concurrent, sélection groupée et absence de chaîne. Benchmark identique avant/après sur PostgreSQL réel, 100/1000/10000 versions : préparation/finalisation/total (commit compris), SQL et lignes réellement lues par ResultSet, plans ANALYZE BUFFERS, index, fixtures séparées, warmup/répétitions. Baseline source Git + même harnais, pas de simulation de la croissance. Pas de promesse de temps constant ni certification V3 globale.
