# Pocoma — Lot 7.1 — Consolidation documentaire et prérequis

## 1. Objectif du Lot 7.1

Le Lot 7.1 aligne la cible normative, l'état courant et le plan directeur avant toute modification du
read side. Il est exclusivement documentaire.

Il doit :

- consacrer dans `read-side-target.md` les invariants déjà arbitrés dans le plan directeur ;
- retirer les formulations laissant croire que ces arbitrages restent bloquants ;
- préserver comme décisions ultérieures les choix d'implémentation non canoniques ;
- maintenir `read-side-current-state.md` comme description factuelle de l'existant ;
- rendre le périmètre documentaire du Lot 7 accessible depuis `docs/README.md`.

Aucun code, test de production, module, migration ou lot 7.2+ n'est modifié ou planifié.

## 2. Sources canoniques utilisées

1. `docs/architecture/read-side-target.md` — cible normative.
2. `docs/architecture/read-side-current-state.md` — état factuel audité.
3. `docs/plans/lot-7-read-side-implementation-plan.md` — séquencement directeur et arbitrages
   consolidés.
4. `docs/README.md` — index documentaire.

`write-side-closure.md` n'est nécessaire que comme référence du write side clos et du prérequis
externe concernant le delete terminal. Aucun audit du code n'est requis.

## 3. Écarts identifiés

| Document | Écart à corriger |
|---|---|
| Target, statut | Le texte parlait encore d'arbitrages préalables à un futur plan alors que le plan directeur existe. |
| Target, temporalité | L'indépendance des projectors et la non-découverte d'une version depuis un artifact devaient être explicitées. |
| Target, ProjectionState | L'ownership durable de `NOT_READY` n'était pas fixé. |
| Target, immutabilité | Le maintien de l'artifact et du state `READY` lors d'un duplicate divergent n'était pas explicite. |
| Target, atomicité | La coordination locale avec le lifecycle Task risquait d'être confondue avec l'invariant read-store. |
| Target, autorisation | Le cas Balance prête sans PotProjection était encore présenté comme non arbitré. |
| Target, workers | Producer logique unique et instances multiples de workers n'étaient pas distingués. |
| Target, identité GET | OAuth2 était un écart documenté mais pas encore consacré comme frontière cible. |
| Target, delete | Le défaut actuel devait être qualifié de prérequis write-side externe non compensable. |
| Target, choix ouverts | Les arbitrages clos et les décisions d'implémentation différées étaient mélangés. |
| Current state | Le commit audité et la baseline documentaire n'étaient pas distingués ; le défaut post-delete n'était pas rappelé. |
| Plan directeur, Lot 7.1 | Le lot prévoyait encore une cartographie ciblée du code, hors du périmètre documentaire retenu. |
| Index documentaire | Les rôles current/target pouvaient être plus explicites et le plan directeur actif n'était pas référencé. |

## 4. Modifications exactes par fichier

### Step 1 — Consacrer l'indépendance temporelle

**File**

`docs/architecture/read-side-target.md`

**Sections**

§1 « Statut et périmètre », §4 « Modèle temporel », §8 « Résolution des lectures unitaires »,
§12 « Observabilité ».

**Changes**

- Identifier le document comme cible normative et référencer le plan directeur existant.
- Énoncer que consumer express et pipeline de projection sont deux chemins indépendants.
- Interdire tout gate d'acquire, claim ou projector sur `latestVersionSeen`.
- Autoriser explicitement `latestProjectedVersion > latestVersionSeen` de manière transitoire.
- Préciser qu'un artifact produit en avance ne fait pas progresser le watermark et ne fait pas
  découvrir une version au reader.
- Conserver `V > latestVersionSeen -> NOT_FOUND`, même si l'artifact V existe.
- Maintenir le lag négatif transitoire comme état observable valide.

**Verification**

- Aucune phrase ne conditionne une projection à l'avancement du watermark.
- Artifact, head ou `MAX(version)` ne servent jamais à établir l'existence source.
- Current cible toujours exactement `latestVersionSeen`.

### Step 2 — Fermer le lifecycle fonctionnel de ProjectionState

**File**

`docs/architecture/read-side-target.md`

**Sections**

§5 « Artifact immuable », §5 « État fonctionnel et head », §6 « Production et reconstruction ».

**Changes**

- Définir `NOT_READY` comme une intention durable de projection.
- Attribuer sa création/adoption au chemin durable de création/adoption de la Task, y compris pour
  backfill et réparation.
- Exiger une adoption/no-op idempotent si le state existe.
- Interdire toute création ou mutation de state par un GET/reader.
- Préciser qu'un projector exécute une intention existante et n'invente pas un `NOT_READY`.
- Pour un duplicate divergent sur une identité `READY`, maintenir artifact et state, refuser le
  nouveau contenu et remonter une violation séparée.
- Réserver `FAILED` à l'échec durable d'une projection qui n'a pas déjà atteint `READY`.

**Verification**

- Le reader est strictement sans effet de bord.
- Le projector ne matérialise pas opportunistiquement une intention absente.
- Il n'existe aucune transition automatique `READY -> FAILED` pour une divergence duplicate.

### Step 3 — Fermer autorisation et atomicité

**File**

`docs/architecture/read-side-target.md`

**Sections**

§3 « Read store », §6 « Production », §8 « Contrat HTTP », §9 « Autorisation temporelle », §15.

**Changes**

- Consacrer toute PotProjection de contexte absente, `NOT_READY` ou `FAILED` comme
  `NOT_READY`/409 fonctionnel, y compris lorsqu'une Balance est déjà `READY`.
- Réserver l'exposition de `FAILED`/503 à une projection métier interprétée après disponibilité du
  contexte d'autorisation et accès accordé.
- Réserver le 404 à une inexistence établie ou à un refus évalué avec un contexte Pot disponible.
- Consacrer le principal OAuth2 Resource Server comme identité cible des GET.
- Limiter l'atomicité canonique à artifact/fragments, `READY`, head et indexes indispensables dans le
  read store.
- Présenter l'inclusion éventuelle du lifecycle Task dans la transaction locale comme un choix du
  Lot 7 lié à la colocalisation PostgreSQL.
- Imposer à une séparation physique future un protocole idempotent/fencé, sans transaction distribuée
  implicite, sans concevoir ce protocole ici.

**Verification**

- Aucun passage ne donne 404 uniquement parce que le contexte d'autorisation manque.
- Aucun invariant n'exige une transaction permanente englobant Task et read store.
- Les GET ne lisent pas le primaire pour décider des droits.

### Step 4 — Clarifier workers, delete et décisions différées

**File**

`docs/architecture/read-side-target.md`

**Sections**

§7 « Workers, backfill et réparation », §11, §14, §15, §16, §17.

**Changes**

- Autoriser plusieurs workers d'un même pipeline, concurrents via claims/fencing.
- Exiger un seul producer logique d'intentions par identité/génération.
- Interdire legacy + nouveau producer ou deux stratégies Event vers Task incompatibles pour la même
  génération.
- Qualifier le delete terminal de prérequis write-side externe au Lot 7.
- Interdire au read side de filtrer, réinterpréter ou compenser des versions post-delete.
- Reclasser §15 en décisions d'implémentation différées.
- Y conserver le routage Expense, la source de `updatedAt` et le protocole concret d'une séparation
  physique, avec leur lot de résolution.
- Retirer de cette section les arbitrages désormais clos sur autorisation, watermark et atomicité.
- Déclarer explicitement non canoniques : source exacte de `updatedAt`, curseur sérialisé, limites,
  envelope HTTP, outil de backfill, identités/versions initiales de pipeline, temporalité de
  `balances/me`, réparation des `FAILED`, durée du legacy et GC.

**Verification**

- Plusieurs workers restent permis mais deux producers logiques concurrents ne le sont pas.
- Le delete terminal est visible comme dépendance externe.
- Aucun choix différé n'est résolu arbitrairement.

### Step 5 — Consolider la liste des invariants

**File**

`docs/architecture/read-side-target.md`

**Section**

§16 « Invariants canoniques consolidés ».

**Changes**

Faire apparaître explicitement dans la liste :

1. indépendance watermark/projectors ;
2. possibilité temporaire `latestProjectedVersion > latestVersionSeen` ;
3. artifact en avance sans effet sur la connaissance source ;
4. `V > latestVersionSeen -> NOT_FOUND`, artifact éventuel ignoré ;
5. ownership durable de `NOT_READY` par la création/adoption de Task ;
6. reader sans création de state ;
7. projector sans invention d'intention ;
8. duplicate divergent laissant artifact et state `READY` inchangés ;
9. Balance sans contexte Pot prêt donnant 409 ;
10. atomicité limitée au read store ;
11. producer logique unique et workers multiples ;
12. delete terminal comme prérequis write-side externe.

Éviter de dupliquer les détails opérationnels des sections spécialisées.

### Step 6 — Maintenir un current state strictement factuel

**File**

`docs/architecture/read-side-current-state.md`

**Sections**

Introduction, §6 « Modèle temporel », §9 « Frontière avec le Lot 6 ».

**Changes**

- Distinguer l'état audité au commit `e842f201…` de son intégration à la baseline `24ef19f…`.
- Ne pas remplacer le SHA audité sans nouvel audit.
- Ajouter le constat minimal que certaines mutations Expense postérieures au delete Pot restent
  possibles dans l'état audité.
- Pointer vers §14 de la cible pour l'analyse et le prérequis.
- Ne recopier aucun modèle ou contrat cible.

**Verification**

- Le document reste descriptif.
- Les candidats du §8 ne deviennent pas des décisions.
- Commit audité et baseline documentaire ne sont plus ambigus.

### Step 7 — Réduire le Lot 7.1 du plan directeur

**File**

`docs/plans/lot-7-read-side-implementation-plan.md`

**Sections**

§3.4 et « Lot 7.1 ».

**Changes**

- Renommer le lot « Consolidation documentaire et prérequis ».
- Remplacer la cartographie de code/modules par la comparaison target/current/plan, la remontée des
  invariants et la classification des choix différés.
- Retirer inspection des controllers, ports, adapters et builds, matrice query/code, inventaire code
  des identités Balance et tests d'architecture.
- Conserver ces travaux dans les lots ultérieurs concernés.
- Compléter les choix non canoniques avec réparation `FAILED`, durée de coexistence legacy et GC.
- Ne modifier aucun autre lot sauf renvoi devenu incorrect vers l'ancien périmètre 7.1.

**Verification**

- Le Lot 7.1 s'exécute sans audit du repository.
- Target et plan directeur portent les mêmes invariants.
- L'ordre et le contenu des Lots 7.2+ sont préservés.

### Step 8 — Ajuster l'index documentaire

**File**

`docs/README.md`

**Section**

« Read side » et « Historical material ».

**Changes**

- Décrire le current state comme description factuelle de l'existant.
- Décrire la target comme cible normative du Lot 7.
- Ajouter une seule entrée vers le plan directeur en précisant sa subordination à la cible.
- Ne pas ajouter chaque sous-plan 7.x à « Start here ».
- Ne pas classer le plan directeur actif parmi les plans purement historiques.

**Verification**

- L'index reste compact.
- Current, target et plan directeur ont des rôles non ambigus.
- Aucun plan ne remplace la documentation architecturale canonique.

## 5. Ordre des modifications

1. Modifier `read-side-target.md`, source normative des autres alignements.
2. Consolider ses invariants et reclasser les décisions différées.
3. Réduire le Lot 7.1 dans le plan directeur.
4. Corriger minimalement le current state sans le transformer en cible.
5. Ajuster `docs/README.md`.
6. Exécuter la passe de cohérence sémantique et les recherches négatives.
7. Relire uniquement les diffs documentaires et exécuter `git diff --check`.

## 6. Vérifications de cohérence

La matrice finale attendue est :

| Situation | Résultat canonique |
|---|---|
| Projector reçoit une Task N avec watermark N-1 | Projection autorisée |
| Artifact N existe, watermark N-1, query explicite N | `NOT_FOUND`/404 |
| Artifact N existe en avance | Aucun changement de `latestVersionSeen` |
| Duplicate divergent sur projection `READY` | Artifact inchangé, state `READY`, violation séparée |
| Reader constate une projection absente | Aucun state créé |
| Projector constate une intention absente | Erreur de protocole, aucun `NOT_READY` inventé |
| PotProjection absente, `NOT_READY` ou `FAILED` comme contexte | `NOT_READY`/409 ; éventuel `FAILED` observable en interne |
| PotProjection `READY`, utilisateur refusé | `NOT_FOUND` masqué/404 |
| PotProjection `READY`, utilisateur autorisé, Balance `FAILED` | `FAILED`/503 |
| PotProjection `READY`, utilisateur autorisé, Balance `NOT_READY` | `NOT_READY`/409 |
| PotProjection `READY`, utilisateur autorisé, Balance `READY` | succès/200 |
| Matérialisation réussie | Artifact + `READY` + head + indexes atomiques dans le read store |
| Plusieurs workers d'un pipeline | Autorisés et fencés |
| Deux producers logiques d'une génération | Interdits |
| Version postérieure au delete Pot | Défaut write-side externe, jamais compensé côté read |

Recherches obligatoires sur les documents modifiés :

- aucune règle d'éligibilité `latestVersionSeen >= targetVersion` ;
- aucune attente du watermark avant acquire/exécution ;
- aucune transition positive `READY -> FAILED` pour duplicate divergent ;
- aucun 404 causé uniquement par une PotProjection d'autorisation indisponible ;
- aucun `FAILED`/503 exposé avant que le contexte d'autorisation soit `READY` et l'accès accordé ;
- aucune transaction distribuée supposée ;
- aucune création de `ProjectionState` par un GET/reader ;
- aucune création opportuniste de `NOT_READY` par un projector ;
- présence de `latestProjectedVersion > latestVersionSeen` ;
- présence du prérequis delete terminal ;
- présence de la liste complète des choix non canoniques.

Contrôles finaux :

- `git diff --check` ;
- diff limité aux quatre documents prévus et au nouveau plan 7.1 ;
- aucune modification de code, build, migration ou configuration ;
- aucun crawl du repository ajouté au plan.

## 7. Critères de sortie

Le Lot 7.1 est terminé lorsque :

- `read-side-target.md` et le plan directeur expriment les mêmes invariants ;
- les arbitrages obsolètes ont disparu de la liste des points bloquants ;
- les responsabilités de watermark, Task, state, projector et reader sont sans ambiguïté ;
- 404, 409 et 503 sont distingués sans contradiction ;
- l'atomicité read-store est séparée de la coordination du lifecycle Task ;
- `read-side-current-state.md` reste une description fiable de l'existant audité ;
- le prérequis write-side du delete terminal est explicitement visible ;
- tous les choix provisoires demandés restent non canoniques ;
- `docs/README.md` distingue clairement current, target et plan directeur ;
- aucun point conceptuel ne bloque la conception du Lot 7.2.

## 8. Risques

- Transformer une décision différée en invariant lors de la réécriture de la cible.
- Copier des éléments cibles dans le current state et brouiller son statut factuel.
- Supprimer une ambiguïté en inventant le protocole concret de séparation physique.
- Confondre producer logique unique et worker unique.
- Présenter le défaut de terminalité du delete comme compensable côté read.
- Modifier de larges portions du plan directeur alors qu'un remplacement ciblé du Lot 7.1 suffit.

## 9. Hors scope

- Toute modification de code ou de test de production.
- Création de modules, classes, migrations, tables ou configurations.
- Audit des controllers, adapters, runtimes ou dépendances.
- Choix définitif de `updatedAt`, du routage Expense ou du contrat `balances/me`.
- Choix d'un outil de backfill, d'un format de curseur ou de limites HTTP.
- Correction write-side du delete terminal.
- Conception ou implémentation des Lots 7.2 et suivants.
- Retrait du legacy, cutover, backfill réel ou garbage collection.
