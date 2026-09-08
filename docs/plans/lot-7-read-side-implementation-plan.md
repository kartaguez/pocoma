# Pocoma — Lot 7 — Plan de réalisation du read side

## 1. Statut et objectif

Ce document est le plan directeur d'implémentation du Lot 7. Il transforme la cible conceptuelle du read side en une suite de lots incrémentaux, testables, commitables et réversibles.

Il ne constitue pas une modification de la cible architecturale et n'autorise pas le démarrage implicite de l'implémentation. Le write side du Lot 6 reste clos.

Le résultat attendu du Lot 7 est un read side qui sert les GET sans lecture du primaire, à partir de projections immuables et versionnées, avec une connaissance asynchrone explicite de la version source, des indexes dérivés adaptés aux queries et un mécanisme contrôlé de migration depuis les lectures legacy.

## 2. Sources canoniques utilisées

Les documents suivants font autorité, dans cet ordre pour leur périmètre :

- `docs/architecture/read-side-target.md` : cible normative du read side ;
- `docs/architecture/read-side-current-state.md` : état actuel et écarts connus ;
- `docs/architecture/write-side-closure.md` : invariants du write side clos ;
- `docs/README.md` : index et règles de lecture de la documentation.

Les documents de runtime Event, Task Balance et la matrice de dépendances ne doivent être consultés qu'au moment des lots qui touchent directement ces mécanismes. Le code doit rester une source de vérification ponctuelle : localisation d'un adapter, confirmation d'une abstraction réutilisable ou validation d'un invariant absent de la documentation.

En cas de contradiction, `read-side-target.md` demeure la cible. La contradiction doit être consignée dans le lot concerné avant toute modification.

## 3. Hypothèses et décisions de cadrage

### 3.1 Invariants non négociables

- Le write side n'écrit jamais directement dans le read store.
- Le chemin normal d'un GET ne lit jamais le primaire et ne joint jamais le read store au primaire.
- Une projection canonique est un snapshot logique complet, autonome, versionné et immuable ; son stockage physique peut être éclaté.
- Pot et Balance partagent le même modèle générique d'identité, d'état et de head de projection.
- `latestVersionSeen` et `latestProjectedVersion` ont des sémantiques, des stockages logiques et des producteurs distincts.
- Le consommateur express de `latestVersionSeen` ne crée aucune Task.
- Les projections métier suivent `BusinessEvent -> Task -> Task worker`.
- Les pools de workers sont séparés par pipeline et réutilisent le même moteur générique.
- Les projections peuvent terminer hors ordre ; les heads avancent par `max` et ne régressent jamais.
- Aucun reader ne fait de fallback silencieux vers une ancienne `potVersion`, une ancienne `pipelineVersion` ou le primaire.
- Les autorisations historiques sont évaluées avec le contexte de la version consultée ; un refus réel est masqué en `404`.
- Les backfills utilisent les mêmes Tasks, workers et garanties que le flux normal.

### 3.2 Existence connue et projectors indépendants

`latestVersionSeen` est la référence du Query Kernel pour l'existence source connue côté read. Les versions d'un Pot étant contiguës, une requête explicite pour `V > latestVersionSeen` retourne `NOT_FOUND`, soit HTTP `404`.

Cette règle de lecture ne crée aucune dépendance d'ordonnancement pour les projectors. Un BusinessEvent ou une Task légitime portant `potVersion=N` suffit à autoriser le calcul de la projection N. Il est donc valide d'observer temporairement :

```text
latestVersionSeen = N - 1
latestProjectedVersion >= N
artifact N présent
```

Dans ce cas, l'artifact N produit en avance ne met pas à jour `latestVersionSeen` et le reader ne « découvre » pas N en inspectant l'artifact ou le head. Une query explicite pour N retourne encore `NOT_FOUND` jusqu'à l'avancement du watermark. Cette asymétrie est une forme acceptée d'eventual consistency.

En conséquence :

- aucun acquire, claim ou projector n'est bloqué par `latestVersionSeen` ;
- `latestProjectedVersion` peut temporairement être supérieur à `latestVersionSeen` ;
- un lag calculé comme `latestVersionSeen - latestProjectedVersion` peut temporairement être négatif sans constituer une violation ;
- seul le consommateur express, ou une procédure administrative explicite de reconstruction, fait évoluer `latestVersionSeen`.

### 3.3 Atomicité canonique et coordination des Tasks

L'atomicité fonctionnelle obligatoire porte sur la matérialisation dans le read store :

```text
artifact immuable
+ descriptor d'artifact éventuel
+ avance éventuelle de ProjectionHead
+ indexes secondaires indispensables
```

Ces écritures doivent être visibles ou absentes ensemble dans une transaction du read store.

Tant que le runtime de Tasks et le read store sont colocalisés dans PostgreSQL, le Lot 7 peut coordonner le lifecycle de la Task dans la même transaction locale. Il s'agit d'un choix d'implémentation simplificateur, pas d'une propriété conceptuelle permanente.

Une séparation physique future devra conserver les mêmes effets fonctionnels au moyen d'un protocole idempotent et fencé, avec reprise après crash. Elle ne devra pas supposer une transaction distribuée implicite. Le déplacement physique sera donc conceptuellement neutre pour les modèles et contrats de lecture, mais nécessitera une adaptation explicite du protocole de commit du worker.

### 3.4 Choix à confirmer pendant la réalisation

Les éléments suivants ne sont pas des invariants architecturaux verrouillés :

- les noms exacts des modules, packages, pipelines et tables ;
- des identités proposées telles que `READ_POT` ou une version `v1` ; elles devront être alignées sur les conventions et identités déjà présentes ;
- l'interface d'administration du backfill : une commande one-shot durable est une proposition, pas une obligation ;
- les valeurs par défaut de pagination, par exemple `limit=50`, maximum `200`, curseur Base64URL versionné et envelope `{items,nextCursor}` ; seuls keyset, opacité du curseur et ordre déterministe sont requis ;
- le caractère current-only de `GET /pots/balances/me`, qui reste une décision produit/API à valider ;
- la source exacte de `updatedAt`, qui doit être fermée avant qu'un rebuild complet soit déclaré valide ;
- la preuve d'inexistence d'une Expense lorsque l'index `expenseId -> potId` ne contient aucune entrée, qui doit être tranchée au début du Lot 7.7 ;
- la politique de réparation d'une projection `FAILED` ;
- la durée de coexistence du legacy et la politique de garbage collection des anciennes générations.

La rétention des BusinessEvents peut aider la provenance, le replay du consommateur express et le diagnostic. Elle ne constitue pas une obligation de rétention éternelle pour reconstruire le read store : les projections se reconstruisent depuis le primaire historisé et un disaster rebuild du watermark peut utiliser une lecture administrative explicite du primaire autoritatif, hors chemin des GET.

## 4. Écarts actuels vers la cible

Les écarts documentés à résorber structurent l'ordre des lots :

| Domaine | État à migrer | Cible |
|---|---|---|
| Lectures | Certaines queries lisent encore le primaire historisé ou des adapters orientés write | Toutes les queries en production lisent uniquement le read store |
| Projection Pot | Absence de snapshot read canonique complet | `PotProjection` immuable par version, physiquement fragmentable |
| Balance | Projection et runtime existants avec concepts spécifiques/legacy | Même identité, couverture, statut dérivé, head et règles de matérialisation que Pot |
| Version source | Pas de watermark read-side spécialisé | `SourceVersionWatermark.latestVersionSeen`, alimenté par un consommateur Event express |
| État fonctionnel | État parfois déduit de la mécanique Task | `ProjectionStatus` dérivé de la couverture, des artifacts et des failures, sans lecture des Tasks |
| Head | Résolution potentielle par recherche dans les artifacts | `ProjectionHead.latestProjectedVersion`, monotone et canonique |
| Queries transverses | Risque de scans ou N+1 | Indexes dérivés `user -> Pot`, `expenseId -> potId`, `user -> Balances` |
| Autorisation | Contexte potentiellement obtenu du primaire ou incomplet | Contexte versionné embarqué dans `PotProjection` |
| Pipeline generations | Activation implicite ou legacy | Version active explicitement configurée, shadow/backfill/cutover/rollback |
| Read store | Colocalisation et responsabilités à clarifier | Schéma, transactions, migrations et ownership logiquement séparés |

Le caractère terminal du delete et la contiguïté globale des versions sont des préconditions write-side. Toute divergence concrète constatée pendant les inspections ciblées doit être traitée comme un bloqueur explicite, sans rouvrir silencieusement le Lot 6.

## 5. Modèle cible à matérialiser

### 5.1 Identité générique

Toute projection est adressée par :

```text
ProjectionIdentity(
  projectionType,
  pipelineId,
  pipelineVersion,
  potId,
  potVersion
)
```

Les dépendances inter-projection, lorsqu'elles sont explicitement décidées, référencent cette identité complète. Une pipeline active est sélectionnée par configuration et jamais par `MAX(pipelineVersion)`.

### 5.2 Structures fonctionnelles minimales

- `SourceVersionWatermark` : `potId`, `latestVersionSeen` et métadonnées techniques minimales d'observation.
- `ProjectionCoverage` : identité de génération et Pot, plage continue inclusive des versions attendues.
- `ProjectionArtifact` et `ProjectionFailure` : issues terminales mutuellement exclusives par identité complète ; leur absence pour une version couverte donne `NOT_READY`.
- `ProjectionHead` : identité de génération de pipeline et `potId`, avec `latestProjectedVersion`.
- `PotProjection` : header du snapshot et fragments versionnés nécessaires aux ressources filles, à la pagination et à l'autorisation.
- `BalanceProjection` : artifact aligné sur la même identité et le même lifecycle générique.
- Indexes courants dérivés : `user -> Pot`, routage Expense, `user -> Balances`.
- Configuration de génération active par type/pipeline.
- Trace séparée des violations d'invariant de matérialisation.

### 5.3 Couverture et statut dérivé

Une `ProjectionCoverage` finie `[fromVersion..throughVersion]` exprime l'attente durable pour une
génération et un Pot. Elle est indépendante de l'ordonnancement des Tasks, continue, sans exception
interne, et ses bornes ne peuvent être étendues que de manière monotone.

Pour une identité complète dans la couverture : artifact présent donne `READY`, failure terminale
présente donne `FAILED`, absence des deux donne `NOT_READY`. Hors couverture, le résultat interne est
`NOT_EXPECTED`. Aucun de ces résultats n'est obtenu en lisant le lifecycle Task et aucune ligne de
state ou d'attente unitaire par version n'est persistée.

Si une identité déjà `READY` est recalculée :

- contenu identique : succès idempotent/no-op ;
- contenu différent : artifact existant inchangé, nouveau contenu non écrit, statut maintenu à `READY`, violation d'invariant enregistrée et alertée.

Une divergence duplicate ne provoque donc jamais `READY -> FAILED`. Une éventuelle quarantaine administrative serait un mécanisme distinct à concevoir ultérieurement.

### 5.4 Query Kernel commun

Le Query Kernel centralise, dans un ordre stable, la résolution de version, de génération active, du contexte d'autorisation puis de l'état de la projection métier :

1. Charger la pipeline version active explicitement configurée.
2. Résoudre la version demandée : `latestVersionSeen` pour current, valeur fournie pour une query explicite.
3. Si aucun watermark n'existe, appliquer le contrat d'inexistence documenté ; si `V > latestVersionSeen`, retourner `NOT_FOUND`, même si un artifact V existe techniquement en avance.
4. Résoudre le statut dérivé et charger l'artifact de la `PotProjection` de même version requise comme contexte d'autorisation, y compris lorsque la ressource demandée est le Pot lui-même.
5. Si ce contexte est absent, `NOT_READY` ou `FAILED`, retourner fonctionnellement `NOT_READY`/`409` ; conserver un éventuel `FAILED` interne dans l'observabilité opérationnelle.
6. Si le contexte est `READY` mais que l'accès est refusé, masquer en `NOT_FOUND`/`404`.
7. Seulement après autorisation accordée, interpréter le statut dérivé de la projection métier demandée : `NOT_READY` donne `409`, `FAILED` donne `503`, `READY` exige l'artifact exact.
8. Servir uniquement l'artifact exact et exposer son `potVersion`.

Le kernel ne déduit pas l'existence source d'un artifact, ne recalcule pas un head avec `MAX`, ne crée aucune donnée fonctionnelle et ne consulte jamais le primaire.

## 6. Stratégie globale de migration

La migration suit une stratégie expand-and-contract :

1. Introduire les fondations read-side sans modifier les readers actifs.
2. Alimenter le watermark et les nouvelles projections en shadow mode.
3. Backfiller et comparer les résultats aux comportements existants hors chemin client.
4. Activer les nouveaux readers query par query, avec configuration explicite et rollback rapide.
5. Aligner Balance sans étendre sa refonte au-delà du modèle générique et des autorisations nécessaires.
6. Activer les générations de pipeline après preuve de couverture des versions courantes.
7. Retirer les readers et producers legacy seulement après une période d'observation et des critères de sortie vérifiés.

Chaque cutover doit posséder un point de rollback qui rebascule explicitement le reader ou la génération active. Aucun fallback implicite n'est introduit dans le code fonctionnel.

## 7. Lots de réalisation détaillés

### Lot 7.1 — Consolidation documentaire et prérequis

**Objectif**

Faire porter explicitement par la documentation canonique tous les invariants nécessaires avant toute modification du read side.

**Responsabilités couvertes**

- cohérence entre cible, état actuel et plan directeur ;
- vocabulaire et ownership de l'identité, du state, du head et du watermark ;
- classement des invariants et des choix d'implémentation différés ;
- prérequis write-side du delete terminal.

**Fichiers/modules probablement concernés**

- `docs/architecture/read-side-target.md` ;
- `docs/architecture/read-side-current-state.md` ;
- le présent plan directeur ;
- `docs/README.md` et le plan détaillé du Lot 7.1.

**Modifications principales**

- Remonter dans la cible les arbitrages déjà consolidés par le plan directeur.
- Consacrer l'indépendance watermark/projectors, l'ownership de `NOT_READY`, le traitement des duplicates divergents, le 409 Balance sans contexte Pot et la frontière d'atomicité read-store.
- Reclasser les choix de détail encore ouverts comme décisions d'implémentation ou produit ultérieures.
- Maintenir le current state comme constat factuel et rendre visible le prérequis externe du delete terminal.
- Aligner l'index documentaire sans audit du repository ni inspection des modules.

**Dépendances**

Aucune.

**Tests/validations**

- Revue croisée target/current state/plan directeur.
- Recherches négatives sur les anciennes contradictions et vérification des invariants requis.
- `git diff --check` sur un diff limité aux documents attendus.

**Critères de sortie**

- La cible et le plan directeur portent exactement les mêmes invariants.
- Le current state reste une description fiable de l'existant.
- Aucun choix provisoire n'est présenté comme invariant.
- Le Lot 7.2 peut être conçu sans rouvrir le cadrage conceptuel.

**Risques/points à vérifier**

- Duplication des décisions target dans le current state.
- Transformation accidentelle d'un choix différé en invariant.
- Tentation de compenser côté read l'absence de terminalité du delete côté write.

### Lot 7.2 — Read store logique et frontière transactionnelle

**Objectif**

Créer la séparation logique du read store et le cadre de persistance commun, sans basculer de GET.

**Responsabilités couvertes**

- schéma et ownership read distincts ;
- repositories/adapters read ;
- transactions de matérialisation ;
- capacité future de datasource/instance séparée.

**Fichiers/modules probablement concernés**

- nouveau module d'infrastructure read persistence ou adaptation ciblée d'un module existant ;
- configuration datasource/transactions ;
- migrations du schéma read dans une phase d'implémentation ultérieure ;
- tests d'intégration PostgreSQL.

**Modifications principales**

- Définir un schéma read et un compte/ownership logique distincts.
- Introduire des ports de read store qui ne permettent aucune lecture du primaire.
- Définir une unité de travail du read store couvrant artifact, `READY`, head et indexes nécessaires.
- Isoler la coordination optionnelle du lifecycle Task afin qu'elle ne fasse pas partie du contrat du port de matérialisation.
- Prévoir une configuration permettant une datasource read distincte sans modifier le domaine de projection.

**Dépendances**

Lot 7.1.

**Tests**

- Commit atomique des quatre catégories d'écritures read-side.
- Rollback complet sur faute injectée entre chaque écriture.
- Absence de `READY` sans artifact et de head pointant vers un artifact invisible.
- Test SQL/permissions privant les GET de `SELECT` sur le primaire.
- Test de frontière montrant que le port de matérialisation n'exige pas la transaction Task.

**Critères de sortie**

- La frontière transactionnelle canonique est explicite et testée.
- La colocalisation Task/read store est documentée comme choix local révisable.
- Aucun GET n'a encore changé de source.

**Risques/points à vérifier**

- Propagation accidentelle d'une transaction primaire.
- Couplage des repositories read aux entités JPA write.

### Lot 7.3 — Modèle générique de projection

**Objectif**

Introduire les concepts partagés par Pot et Balance avant les artifacts métier.

**Responsabilités couvertes**

- identité complète ;
- couverture continue et statut fonctionnel dérivé ;
- `ProjectionHead` ;
- immutabilité, déterminisme, idempotence et hors-ordre ;
- violations d'invariant distinctes du state fonctionnel.

**Fichiers/modules probablement concernés**

- module de domaine/application de projection proposé ;
- adapters read persistence du Lot 7.2 ;
- composants Balance réutilisables identifiés au Lot 7.1.

**Modifications principales**

- Introduire les value objects et ports génériques.
- Définir l'upsert monotone du head avec `max`.
- Définir l'insertion immuable et la comparaison déterministe de contenu.
- Définir la résolution `NOT_EXPECTED`/`NOT_READY`/`READY`/`FAILED` depuis coverage, artifact et failure.
- Enregistrer les divergences duplicate dans un canal d'invariant séparé.

**Dépendances**

Lot 7.2.

**Tests**

- Fins de projection `44 -> 46 -> 45` : head final à 46 et artifacts tous adressables.
- Tentative de régression du head sans effet.
- Duplicate identique idempotent.
- Même identité et contenu différent : artifact inchangé, statut `READY`, nouveau contenu rejeté, violation enregistrée.
- Deux identités de pipeline versions différentes coexistent.

**Critères de sortie**

- Pot et Balance peuvent dépendre du même contrat générique.
- L'état fonctionnel ne dépend ni des claims ni des retries.
- L'immuabilité et les violations divergentes sont testées.

**Risques/points à vérifier**

- Comparaison de contenu sensible à une sérialisation non canonique.
- Confusion entre échec terminal d'une projection non prête et divergence d'un artifact déjà prêt.

### Lot 7.4 — Consommateur express de latestVersionSeen

**Objectif**

Alimenter rapidement la connaissance read-side de la version source sans Task ni projection métier.

**Responsabilités couvertes**

- `SourceVersionWatermark` ;
- consommation Event minimale ;
- idempotence et hors-ordre ;
- indépendance du pipeline de projection.

**Fichiers/modules probablement concernés**

- runtime Event existant ;
- nouveau consumer/admission handler spécialisé ;
- adapter du watermark dans le read store ;
- configuration et métriques du consumer.

**Modifications principales**

- Sur chaque BusinessEvent portant une version Pot, appliquer `max(current, event.potVersion)`.
- N'effectuer aucune reconstruction, aucun calcul métier, aucun snapshot et aucune création de Task.
- Configurer une identité/slot de consommation dédiée selon le runtime Event existant.
- Prévoir replay Event si disponible et procédure administrative de reconstruction du watermark depuis le primaire autoritatif pour disaster rebuild.

**Dépendances**

Lots 7.2 et 7.3 pour la persistance et le vocabulaire.

**Tests**

- Events dupliqués et hors ordre.
- Reprise après crash autour du commit du watermark.
- Aucun enregistrement de Task créé.
- Un projector peut matérialiser N alors que le watermark vaut N-1.
- Le passage du head devant le watermark est accepté.

**Critères de sortie**

- Le watermark progresse monotoniquement.
- Le consumer express est déployable/observable séparément.
- Aucune policy d'éligibilité des Tasks ne dépend du watermark.

**Risques/points à vérifier**

- BusinessEvents ne portant pas directement la version nécessaire.
- Procédure de reconstruction administrative à sécuriser hors API GET.

### Lot 7.5 — Scheduling durable des projections couvertes

**Objectif**

Créer/adopter les Tasks comme mécanisme durable d'exécution des versions déjà déclarées attendues par la couverture.

**Responsabilités couvertes**

- chemin Event -> Task ;
- contrôle d'appartenance à la couverture ;
- backfill et réparation ;
- séparation reader/projector/intention.

**Fichiers/modules probablement concernés**

- Event worker et builder/adopter de Tasks existants ;
- ports de lecture/extension de `ProjectionCoverage` ;
- runtime Task générique ;
- tests d'intégration Event/Task.

**Modifications principales**

- À la création/adoption durable d'une Task, vérifier que l'identité appartient à la couverture de sa génération sans créer une attente unitaire.
- Définir le comportement idempotent selon l'issue dérivée déjà présente (`NOT_READY`, `READY` ou `FAILED`).
- Réutiliser exactement ce chemin pour trafic normal, backfill et réparation.
- Exiger du projector une Task et une couverture existantes ; une version hors couverture est une erreur de protocole, pas l'occasion d'étendre opportunistiquement la couverture.
- Interdire toute écriture de coverage, artifact ou failure depuis les GET et readers.
- Ne consulter `latestVersionSeen` ni pour créer la Task, ni pour l'acquérir, ni pour l'exécuter.

**Dépendances**

Lot 7.3 ; connaissance ciblée des runtimes Event/Task.

**Tests**

- BusinessEvent duplicate : une Task logique sans duplication ni ligne d'attente par version.
- Task de backfill et Task de réparation suivent la même adoption.
- Artifact/failure/absence pour une version couverte : comportement idempotent documenté.
- Un reader constatant une absence ne crée aucune ligne.
- Un projector sans Task/couverture préexistante n'invente ni intention, ni couverture, ni artifact.
- Task N créée/acquise/exécutée avec `latestVersionSeen=N-1`.

**Critères de sortie**

- `NOT_READY` reste dérivé et indépendant du lifecycle Task.
- Le projector exécute une intention, il ne la crée pas.
- Le Query Kernel reste strictement read-only.
- Aucun gate watermark n'existe dans le runtime Task.

**Risques/points à vérifier**

- Confusion possible entre couverture fonctionnelle et scheduling des Tasks.
- Ancien producer Balance créant une Task hors de la future couverture.

### Lot 7.6 — PotProjection canonique en shadow mode

**Objectif**

Construire, pour chaque version exacte, un snapshot logique complet du Pot sans modifier les GET actifs.

**Responsabilités couvertes**

- pipeline Pot dédié ;
- reconstruction primaire historisée à la version exacte ;
- artifact immuable et fragments physiques ;
- statut `ACTIVE`/`DELETED` ;
- contexte d'autorisation versionné.

**Fichiers/modules probablement concernés**

- module/pipeline Pot read proposé ;
- use case/port existant de reconstruction historique ;
- runtime Task générique ;
- tables/adapters header, shareholders, expenses et expense shares ;
- tests de projection et PostgreSQL.

**Modifications principales**

- Définir le contenu logique complet à partir des queries actuelles et des règles d'autorisation.
- Matérialiser un header et des fragments versionnés portant tous l'identité nécessaire.
- Inclure statut, membres/rôles contextuels et données minimales des ressources filles.
- Calculer depuis le primaire historisé à `potVersion` exacte, sans dépendre d'une projection précédente.
- Mettre artifact, descriptor éventuel, head et indexes indispensables déjà introduits dans la transaction read-store.
- Définir avant la fin du lot la provenance candidate du timestamp durable de tri des Pots ; `RecordedEvent.recordedAt` n'est retenu qu'après preuve d'un mapping univoque par version.
- Ne pas inventer une règle telle que `min(recordedAt)` si plusieurs Events peuvent porter une même `potVersion` ; dans ce cas, définir une autre métadonnée durable ou renforcer explicitement l'invariant source.

**Dépendances**

Lots 7.2, 7.3 et 7.5.

**Tests**

- Reconstruction exacte de plusieurs versions, y compris ajout/retrait d'un membre et mutations d'Expense.
- Snapshot autonome sans reconstruction au GET.
- Version de delete projetée avec statut terminal ; absence de version future valide.
- Données d'autorisation cohérentes avec chaque version historique.
- Atomicité des fragments et du header.
- Déterminisme du timestamp candidat entre deux reconstructions, si la source est déjà validée.

**Critères de sortie**

- La projection shadow est comparable aux lectures primaires existantes.
- Les fragments couvrent les queries Pot, Shareholder et Expense connues.
- La source de `updatedAt` est soit définitivement fixée et testable, soit porte une décision bloquante planifiée au début du Lot 7.8 ; aucun rebuild complet ne pourra être déclaré avant sa résolution.

**Risques/points à vérifier**

- Reconstruction historique incomplète pour le delete terminal.
- Timestamp write/Event non univoque par version.
- Snapshot trop couplé aux DTO HTTP.

### Lot 7.7 — Indexes Pot/Expense et décision de routage

**Objectif**

Ajouter les structures transverses nécessaires aux listes et fermer, avant tout cutover Expense, la sémantique de l'absence dans `expenseId -> potId`.

**Responsabilités couvertes**

- index `userId -> Pot` ;
- statut courant et tri ;
- routage `expenseId -> potId` ;
- pagination keyset ;
- cohérence atomique avec `PotProjection`.

**Fichiers/modules probablement concernés**

- transaction de matérialisation Pot ;
- repositories d'indexes read ;
- query adapters shadow ;
- code write/domain Expense consulté seulement pour vérifier identité, création, suppression et éventuelle réutilisation d'identifiant.

**Modifications principales**

- Maintenir l'index utilisateur/Pot courant atomiquement avec la projection canonique.
- Définir l'ordre strict `updatedAt DESC, potId` et un curseur opaque keyset.
- Maintenir le routage Expense nécessaire aux accès directs.
- Au début du lot, prendre et documenter obligatoirement l'une des décisions suivantes après vérification ciblée des invariants Expense :
  - **Option A** : l'absence du routage prouve `NOT_FOUND`, uniquement si l'index est garanti complet, atomiquement maintenu avec toutes les PotProjections concernées, et si création/suppression/réutilisation d'identité ne laisse aucun état ambigu ;
  - **Option B** : l'absence est indéterminée ; introduire une structure minimale reconstructible distinguant `Expense inexistante` de `Expense existante mais routage/projection non prête`.
- Définir les effets d'une version de suppression et la temporalité exacte du routage, sans créer de timeline Expense indépendante.
- Bloquer explicitement le cutover de `GET /expenses/{id}` tant que la décision, la preuve et les tests ne sont pas validés.

**Dépendances**

Lot 7.6 et source durable de `updatedAt` suffisamment définie pour tester l'ordre.

**Tests**

- Indexes et projection canonique committés ou rollbackés ensemble.
- Pagination stable avec timestamps égaux grâce au tie-breaker `potId`.
- Pas de doublon ni omission entre pages lors d'un parcours sur un snapshot de données stable.
- Rebuild des indexes à partir des sources canoniques.
- Scénarios création, mutation, suppression et, si possible, réutilisation d'Expense.
- Tests propres à l'Option A ou B prouvant la distinction `NOT_FOUND`/`NOT_READY`.

**Critères de sortie**

- La stratégie A ou B est documentée comme décision vérifiée, implémentée et testée.
- L'absence d'entrée de routage a une sémantique non ambiguë.
- `GET /expenses/{id}` est déclaré éligible au cutover seulement après ce critère.
- Les indexes restent dérivés, atomiques et reconstructibles.

**Risques/points à vérifier**

- Index courant incapable de répondre à une requête historique sans information de routage complémentaire.
- Réutilisation éventuelle d'un `expenseId` entre Pots.

### Lot 7.8 — Backfill, rebuild et validation de reconstructibilité

**Objectif**

Permettre le remplissage volontaire d'une génération, la reconstruction après perte du read store et une réparation distincte des trous anormaux.

**Responsabilités couvertes**

- génération durable de Tasks de backfill ;
- même moteur de projection ;
- reprise et observabilité ;
- reconstruction du watermark et des métadonnées de query.

**Fichiers/modules probablement concernés**

- mécanisme administratif proposé, éventuellement commande one-shot ;
- builder/adopter de Tasks du Lot 7.5 ;
- reconstruction historique primaire ;
- adapters de scan administratif et métriques.

**Modifications principales**

- Générer explicitement des Tasks par identité complète sans moteur de calcul parallèle.
- Rendre le déclenchement durable, reprenable et idempotent, quelle que soit l'interface retenue.
- Distinguer campagne volontaire, rebuild complet et réparation automatique ciblée.
- Pour un disaster rebuild, reconstruire le watermark par replay Event si disponible ou par lecture administrative autoritative ; ne jamais exposer ce chemin aux GET.
- Fermer définitivement avant validation du rebuild la source du `updatedAt` de `GET /pots` : elle doit être déterministe, stable entre rebuilds, durable et indépendante d'un état volatile du read store.
- Prouver que artifacts, états, heads, indexes et métadonnées de tri sont reproductibles.

**Dépendances**

Lots 7.4 à 7.7.

**Tests**

- Même campagne lancée deux fois sans artifacts divergents.
- Reprise après interruption à différentes étapes.
- Rebuild d'un read store vide depuis le primaire historisé.
- Reconstruction du watermark sans hypothèse de rétention éternelle des Events.
- Comparaison de `updatedAt`, ordre et cursors après deux rebuilds indépendants.
- Réparation d'un trou sans transformer une migration globale en réparation automatique.

**Critères de sortie**

- Un read store vide peut être reconstruit sans lire le primaire dans les GET.
- La source de `updatedAt` est définitivement décidée, documentée et testée.
- Deux rebuilds produisent les mêmes métadonnées fonctionnelles et le même ordre.
- Backfill et réparation sont déclenchables et observables séparément.

**Risques/points à vérifier**

- Volume de Tasks et pression sur les workers du trafic normal.
- Source de timestamp absente ou non déterministe.
- Scan administratif du primaire insuffisamment borné.

### Lot 7.9 — Query Kernel et contrat HTTP

**Objectif**

Implémenter une résolution commune des versions et états, encore utilisable en shadow mode avant les cutovers.

**Responsabilités couvertes**

- current et version explicite ;
- `NOT_FOUND`, `NOT_READY`, `FAILED` ;
- `404`, `409`, `503` ;
- absence de fallback ;
- `potVersion` dans les succès.

**Fichiers/modules probablement concernés**

- module application/query read ;
- ports watermark, state, head, artifact et active pipeline ;
- mapping d'erreurs HTTP commun ;
- tests contractuels.

**Modifications principales**

- Implémenter l'ordre décrit en section 5.4.
- Pour current, cibler strictement `latestVersionSeen`.
- Pour explicite, considérer `V > latestVersionSeen` comme inconnu, même si le projector a pris de l'avance.
- Ne jamais choisir une version artifact plus ancienne ni sonder une génération précédente.
- Exposer pour `NOT_READY` au minimum `requestedVersion` et `latestProjectedVersion`, sans détails techniques de pipeline.
- N'exposer `FAILED`/503 qu'après disponibilité du contexte d'autorisation et accès accordé ; une PotProjection de contexte absente, `NOT_READY` ou `FAILED` produit fonctionnellement `NOT_READY`/409.
- Maintenir l'état du kernel en lecture seule.

**Dépendances**

Lots 7.3, 7.4 et 7.6.

**Tests**

- Version connue mais projection absente : `NOT_READY`/409.
- PotProjection `NOT_READY` : 409.
- PotProjection `FAILED` et donc contexte d'autorisation indisponible : 409, avec échec interne observable.
- Contexte PotProjection `READY`, utilisateur autorisé et projection métier terminalement échouée : `FAILED`/503.
- Version supérieure au watermark : `NOT_FOUND`/404.
- Artifact N présent et head >= N, watermark N-1 : query explicite N en 404 et current résolu à N-1.
- Current N non prêt avec N-1 prêt : 409, jamais succès N-1.
- Génération active non prête avec ancienne génération prête : aucun fallback.
- Reader n'insère ni ne modifie coverage, artifact, failure ou head.

**Critères de sortie**

- Tous les états ont un contrat stable.
- L'existence source connue est exclusivement résolue via le watermark.
- Les projectors restent indépendants de cette règle de query.

**Risques/points à vérifier**

- Confusion entre version hors couverture et version couverte sans artifact ; la résolution dérivée doit conserver cette distinction.

### Lot 7.10 — Autorisation contextuelle et scopes

**Objectif**

Évaluer les droits exclusivement depuis les snapshots versionnés et appliquer les scopes OAuth2 de chaque ressource.

**Responsabilités couvertes**

- `VIEW`/`VIEW_ARCHIVE` ;
- scopes Pot, Shareholder, Expense et Balance ;
- historique cohérent ;
- masquage en 404.

**Fichiers/modules probablement concernés**

- configuration OAuth2/Security des GET ;
- policy d'autorisation read-side ;
- Query Kernel ;
- DTO/contexte de `PotProjection`.

**Modifications principales**

- Déterminer archive par rapport à `latestProjectedVersion`, pas au watermark.
- Exiger `VIEW_ARCHIVE` pour un Pot supprimé, y compris sa dernière projection disponible.
- Évaluer le contexte à la version consultée.
- Rendre les scopes des ressources autonomes : Shareholder/Expense/Balance ne requièrent pas automatiquement `POT:*`.
- Ajouter/valider `BALANCE:VIEW_ARCHIVE`.
- Distinguer contexte indisponible, que sa PotProjection soit absente, `NOT_READY` ou `FAILED` (`NOT_READY`/409), contexte disponible avec refus (`NOT_FOUND` masqué/404), et échec de la projection métier exposable après autorisation (`FAILED`/503).

**Dépendances**

Lots 7.6 et 7.9.

**Tests**

- Utilisateur ajouté en v50 non autorisé rétroactivement en v42.
- Version courante projetée : `VIEW`; version antérieure : `VIEW_ARCHIVE`.
- Dernière version deleted : `VIEW_ARCHIVE`.
- Refus réel masqué en 404.
- OAuth2 requis sur chaque GET.
- PotProjection absente ou `NOT_READY` : 409 et non 404.
- PotProjection `FAILED` : contexte indisponible, donc 409 et non 503.

**Critères de sortie**

- Aucune policy ne lit le primaire.
- Chaque ressource applique son scope autonome.
- 404 et 409 ne sont pas confondus.

**Risques/points à vérifier**

- Données d'autorisation insuffisantes dans les anciens snapshots backfillés.

### Lot 7.11 — Migration incrémentale des GET Pot, Shareholder et Expense

**Objectif**

Basculer query par query vers le read store après validation shadow, sans supprimer immédiatement le legacy.

**Responsabilités couvertes**

- `GET /pots` ;
- `GET /pots/{id}` ;
- `GET /pots/{id}/expenses` ;
- `GET /expenses/{id}` ;
- queries Shareholder existantes concernées.

**Fichiers/modules probablement concernés**

- controllers et query handlers identifiés au Lot 7.1 ;
- adapters de `PotProjection` et indexes ;
- feature/configuration de cutover ;
- tests API et sécurité.

**Modifications principales**

- Basculer d'abord les queries directement résolubles dans un Pot connu.
- Basculer `GET /pots` avec actifs par défaut, archives seulement sur demande explicite et pagination keyset.
- Basculer `GET /expenses/{id}` uniquement après satisfaction du gate de routage du Lot 7.7.
- Retourner `potVersion` dans chaque succès versionné.
- Conserver un rollback explicite vers le reader legacy pendant la période d'observation, sans fallback par requête.

**Dépendances**

Lots 7.7, 7.9 et 7.10. Le critère de routage Expense est bloquant pour le seul GET Expense direct.

**Tests**

- Contrats 404/409/503 pour current et explicite.
- Liste active par défaut et inclusion archive explicite.
- Pagination keyset déterministe.
- Expense directe : inexistante vs existante non prête selon la décision 7.7.
- Test d'intégration exécutant les GET avec un compte SQL sans droit `SELECT` sur le primaire.
- Comparaison shadow et tests de rollback de configuration.

**Critères de sortie**

- Chaque GET basculé n'utilise que le read store.
- Aucun GET Expense direct n'est activé avant preuve de routage.
- Le legacy reste isolé et désactivable, pas consulté en fallback.

**Risques/points à vérifier**

- Différences de forme ou de pagination constituant une rupture API.
- Sémantique produit des listes archives.

### Lot 7.12 — Alignement minimal de Balance sur le modèle générique

**Objectif**

Faire adopter à Balance l'identité, le state, le head, la génération active et la transaction read-store génériques sans refonte fonctionnelle élargie.

**Responsabilités couvertes**

- coexistence legacy/nouveau pipeline ;
- artifact Balance immuable ;
- pipelineVersion active ;
- index `user -> Balances` atomique ;
- pools séparés.

**Fichiers/modules probablement concernés**

- pipeline/runtime Balance existant ;
- modèle générique du Lot 7.3 ;
- adapter Balance et index transverse ;
- configuration de workers.

**Modifications principales**

- Adapter l'identité Balance existante sans inventer une version exacte avant inventaire.
- Matérialiser state/head/index selon les mêmes règles que Pot.
- Conserver temporairement l'artifact legacy pour comparaison et rollback.
- Configurer un pool `BALANCE` séparé du pool Pot, sur le même moteur générique.
- Éviter toute dépendance structurelle d'ordre entre projectors Balance et Pot.

**Dépendances**

Lots 7.3, 7.5 et 7.8.

**Tests**

- Tests génériques idempotence/hors-ordre appliqués à Balance.
- Index utilisateur/Balance atomique avec la projection.
- Pools distincts sans famine inter-pipeline.
- Coexistence de deux générations et activation explicite.
- Plusieurs workers Balance peuvent claim/exécuter avec fencing sans double commit.

**Critères de sortie**

- Balance utilise le modèle générique sans perte de comportement métier.
- L'ancien artifact reste disponible uniquement pour shadow/rollback.

**Risques/points à vérifier**

- Identité legacy incompatible avec une migration directe.
- Élargissement involontaire du calcul métier Balance.

### Lot 7.13 — Migration des GET Balance

**Objectif**

Servir les Balances et la vue transverse utilisateur depuis les projections génériques, avec le contexte Pot exact.

**Responsabilités couvertes**

- `GET /pots/{id}/balances` ;
- `GET /pots/balances/me` ;
- autorisation temporelle ;
- absence de N+1.

**Fichiers/modules probablement concernés**

- controllers/query handlers Balance ;
- adapter `BalanceProjection` ;
- index `user -> Balances` ;
- policy du Lot 7.10.

**Modifications principales**

- Résoudre Balance dans la génération active exacte.
- Charger `PotProjection` de la même `potVersion` pour l'autorisation.
- Si la PotProjection de contexte est absente, `NOT_READY` ou `FAILED`, retourner fonctionnellement `NOT_READY`/409, quel que soit l'état de Balance.
- Si le contexte Pot est disponible et l'utilisateur non autorisé, masquer en 404.
- Interpréter l'état de Balance seulement avec un contexte Pot `READY` et un utilisateur autorisé : Balance `NOT_READY` donne 409, `FAILED` donne 503 et `READY` donne 200.
- Servir `balances/me` via l'index transverse, sans enchaînement user -> Pots -> N Balances.
- Faire valider explicitement la proposition current-only de `balances/me` avant toute suppression d'un paramètre API existant.

**Dépendances**

Lots 7.10 et 7.12.

**Tests**

- Balance READY + PotProjection NOT_READY/absente : 409.
- PotProjection FAILED : contexte indisponible, donc 409 quel que soit l'état de Balance.
- Balance READY + contexte disponible + refus : 404.
- PotProjection READY + utilisateur autorisé + Balance FAILED : 503.
- PotProjection READY + utilisateur autorisé + Balance NOT_READY : 409.
- PotProjection READY + utilisateur autorisé + Balance READY : 200.
- Autorisation historique à même version.
- `balances/me` sans N+1/scans transverses.
- Contrat versionné ou current-only conforme à la décision produit.

**Critères de sortie**

- Les GET Balance ne lisent plus le primaire.
- L'indisponibilité du contexte d'autorisation n'est jamais présentée comme un refus.
- L'API `balances/me` n'est pas modifiée sans décision explicite.

**Risques/points à vérifier**

- Index transverse incomplet pendant le backfill.

### Lot 7.14 — Générations, shadow, cutover et rollback

**Objectif**

Rendre les changements de pipelineVersion explicites, mesurables et réversibles.

**Responsabilités couvertes**

- coexistence des générations ;
- activation explicite ;
- couverture minimale avant bascule ;
- absence de fallback ;
- producer logique unique et workers multiples.

**Fichiers/modules probablement concernés**

- configuration des pipelines actifs ;
- mécanisme de backfill ;
- déploiement/configuration Event->Task et pools de workers ;
- runbook de cutover/rollback.

**Modifications principales**

- Déployer une nouvelle génération en shadow et backfiller les versions courantes requises.
- Vérifier les heads, states, artifacts, indexes et divergences avant activation.
- Basculer la configuration active explicitement ; un `NOT_READY`/`FAILED` de la nouvelle génération ne retombe pas sur l'ancienne.
- Définir le rollback comme une nouvelle décision de configuration explicite.
- Conserver les anciennes générations selon une politique de rétention/GC ultérieure.
- Garantir un seul **producer logique** actif par identité/génération de pipeline : un seul mécanisme coordonné crée/adopte les intentions et Tasks de cette génération.
- Autoriser plusieurs instances de workers derrière ce producer logique. Elles peuvent scanner, claim et exécuter en concurrence via leases/claims/fencing ; c'est le fonctionnement nominal d'un pool.
- Interdire la coexistence non coordonnée d'un producer legacy et d'un nouveau producer, ou de deux stratégies Event->Task incompatibles, pour la même identité/génération.

**Dépendances**

Lots 7.8, 7.11 et 7.13 selon le pipeline.

**Tests**

- Nouvelle génération active non prête avec ancienne prête : 409, pas de fallback.
- Rollback explicite restaure l'ancienne génération.
- Gate de cutover : toutes les versions courantes nécessaires sont `READY`, historique partiel permis.
- Plusieurs workers concurrents d'un même pipeline produisent un seul commit grâce au fencing.
- Configuration/déploiement empêche deux producers logiques pour une même génération.

**Critères de sortie**

- Cutover et rollback sont documentés et testés.
- Une seule source logique d'intentions existe par génération.
- Le nombre de workers reste un paramètre opérationnel indépendant.

**Risques/points à vérifier**

- Double production de Tasks pendant une période de déploiement roulant.
- Confusion entre identité de génération et nom de pool.

### Lot 7.15 — Observabilité fonctionnelle et opérationnelle

**Objectif**

Rendre visibles lag, états, trous, backfills et violations sans exposer la mécanique runtime dans le contrat HTTP.

**Responsabilités couvertes**

- métriques par pipeline ;
- alertes ;
- diagnostic de projection ;
- divergence duplicate.

**Fichiers/modules probablement concernés**

- instrumentation des consumers/projectors ;
- repositories de statistiques read-side ;
- dashboards et alertes ;
- runbooks.

**Modifications principales**

- Exposer `latestVersionSeen - latestProjectedVersion` par Pot/pipeline ou agrégats appropriés, en acceptant temporairement une valeur négative.
- Exposer nombres `NOT_READY` et `FAILED`, âge du plus ancien attendu, trous visibles et progression de backfill.
- Distinguer lag du consumer express, lag de projection et artifact projeté en avance.
- Alerter séparément les divergences de contenu d'une identité `READY` sans muter son state.
- Garder claims, leases et retries dans les métriques techniques du runtime.

**Dépendances**

Tous les composants instrumentés, au fil de leur livraison ; finalisation après 7.14.

**Tests**

- Métriques cohérentes pour `44 READY, 45 NOT_READY, 46 READY`.
- Valeur négative du lag acceptée et identifiée comme avance temporaire du projector.
- Violation duplicate divergente visible alors que la projection reste servable.
- Métriques isolées par pipeline/génération.

**Critères de sortie**

- Les opérateurs distinguent retard watermark, retard projection, trou, failure et divergence.
- Aucun détail de worker n'envahit les réponses fonctionnelles.

**Risques/points à vérifier**

- Cardinalité excessive des métriques par Pot.
- Alertes déclenchées à tort sur un lag négatif transitoire.

### Lot 7.16 — Extinction contrôlée du legacy

**Objectif**

Supprimer les anciens chemins seulement après validation des nouvelles lectures, tout en conservant les données requises par la politique de rétention.

**Responsabilités couvertes**

- retrait des readers primaires ;
- arrêt des producers legacy ;
- nettoyage des adapters/configurations ;
- rollback sûr avant suppression irréversible.

**Fichiers/modules probablement concernés**

- anciens query adapters/controllers ;
- runtime/producer Balance legacy ;
- configuration de déploiement ;
- modules devenus sans consommateur ;
- documentation et runbooks.

**Modifications principales**

- Vérifier une période d'observation sans lecture primaire ni fallback.
- Désactiver d'abord le producer logique legacy, vérifier qu'un seul producer logique reste actif pour chaque génération, puis supprimer son wiring.
- Ne pas confondre cette unicité avec le nombre de workers : plusieurs instances du pool actif restent autorisées et souhaitables.
- Retirer les readers legacy après expiration du point de rollback convenu.
- Différer la GC des anciennes générations selon une politique séparée.
- Renforcer les règles de dépendances et permissions SQL empêchant une réintroduction du primaire dans les GET.

**Dépendances**

Lots 7.11 à 7.15 et période d'observation validée.

**Tests**

- Démarrage avec uniquement les nouveaux producers/readers.
- Plusieurs workers du pipeline actif continuent à fonctionner normalement.
- Absence de duplicate Task due à deux producers logiques.
- Suite API complète avec privilèges SQL read-only limités au read store.
- Test de configuration et procédure de rollback avant suppression finale.

**Critères de sortie**

- Aucun GET ne lit le primaire.
- Un seul producer logique actif existe par génération de pipeline ; plusieurs workers peuvent le servir.
- Aucun legacy ne produit concurremment les mêmes intentions.
- Les éléments supprimés et la récupérabilité éventuelle sont documentés.

**Risques/points à vérifier**

- Suppression trop précoce d'un chemin nécessaire au rollback.
- Job legacy oublié dans une configuration de déploiement.

## 8. Scénarios d'acceptation transverses obligatoires

Le Lot 7 ne peut être déclaré achevé sans une suite couvrant au minimum :

1. Projections terminées `44 -> 46 -> 45`, avec head final 46 et aucun artifact perdu.
2. Head monotone sans régression.
3. Exécution duplicate identique idempotente.
4. Même identité et contenu différent : artifact inchangé, statut dérivé `READY`, violation séparée ; jamais `READY -> FAILED`.
5. Artifact, `READY`, head et indexes atomiques dans le read store, indépendamment du choix local de coordination Task.
6. Version connue mais projection absente : `NOT_READY`/409.
7. PotProjection `FAILED` lorsqu'elle fournit le contexte d'autorisation : `NOT_READY`/409 côté client, `FAILED` observable en interne.
8. Version supérieure à `latestVersionSeen` : `NOT_FOUND`/404.
9. Artifact N projeté en avance avec watermark N-1 : N reste inconnu du Query Kernel ; l'artifact ne modifie pas le watermark.
10. Projector N non bloqué par un watermark N-1 et lag négatif temporaire accepté.
11. Current sans fallback vers une ancienne projection prête.
12. PipelineVersion active sans fallback vers une ancienne génération prête.
13. Reader strictement read-only : aucune donnée de projection créée par un GET.
14. Projector sans intention durable préexistante : aucune projection attendue inventée.
15. Task normale, backfill et réparation créent/adoptent `NOT_READY` par le même chemin durable.
16. Balance `READY` mais PotProjection de même version absente, `NOT_READY` ou `FAILED` : `NOT_READY`/409.
17. PotProjection `READY` et accès refusé : 404 masqué.
18. PotProjection `READY`, accès accordé et Balance `FAILED` : 503.
19. PotProjection `READY`, accès accordé et Balance `NOT_READY` : 409.
20. PotProjection `READY`, accès accordé et Balance `READY` : 200.
21. Autorisation historique évaluée à la version consultée.
22. Delete projeté comme version terminale.
23. Backfill et rebuild reproductibles depuis le primaire historisé.
24. `updatedAt` et ordre de liste identiques entre rebuilds.
25. Index transverse cohérent et atomique avec sa projection canonique.
26. Routage Expense prouvant sans ambiguïté `NOT_FOUND` ou `NOT_READY` selon la décision du Lot 7.7.
27. `GET /expenses/{id}` impossible à basculer tant que ce routage n'est pas validé.
28. Pagination keyset stable selon `updatedAt DESC, potId`.
29. Plusieurs workers d'un pipeline concurrents, fencés et idempotents.
30. Un seul producer logique actif pour une identité/génération donnée.
31. Tous les GET réussissent avec un compte SQL privé de `SELECT` sur le primaire.

## 9. Risques et décisions humaines nécessaires

### Décisions à fermer avant les lots concernés

- **Source de `updatedAt`** : valider une métadonnée durable par version. `RecordedEvent.recordedAt` est un candidat, sans règle arbitraire si plusieurs Events partagent une version. Décision requise au Lot 7.6 ou au début du 7.8, bloquante pour la validation du rebuild.
- **Routage Expense** : choisir A ou B après vérification ciblée des invariants d'identité/création/suppression. Décision bloquante au début du Lot 7.7 pour le cutover du GET direct.
- **Contrat `balances/me`** : confirmer s'il reste versionnable ou devient current-only avant toute rupture API.
- **Interface de backfill** : retenir commande, endpoint administratif ou autre orchestration durable selon les conventions d'exploitation.
- **Identités de pipelines** : confirmer `pipelineId` et première `pipelineVersion` à partir des identités Balance réellement déployées.
- **Coordination Task/read store** : décider si le Lot 7 exploite la transaction PostgreSQL colocalisée, tout en conservant une frontière permettant un protocole fencé futur.
- **Politique de rétention/GC** : différée mais nécessaire avant suppression physique d'anciennes générations.

### Risques structurants

- Le delete terminal ou la contiguïté des versions ne seraient pas garantis par le write side réel.
- Une dépendance primaire subsisterait derrière un adapter de lecture ou d'autorisation.
- Le shadow mode créerait deux producers logiques pour une même génération au lieu de générations distinctes.
- Le read store séparé logiquement resterait couplé aux transactions ou entités du primaire.
- Un index transverse incomplet serait interprété à tort comme preuve d'inexistence.
- La matérialisation d'une version en avance serait confondue avec sa connaissance source par le Query Kernel.
- La cardinalité de l'observabilité masquerait les signaux importants ou deviendrait coûteuse.

## 10. Critères de fin du Lot 7

Le Lot 7 est terminé lorsque toutes les conditions suivantes sont satisfaites :

- Les GET Pot, Shareholder, Expense et Balance du périmètre lisent exclusivement le read store.
- Un test d'intégration SQL prouve qu'ils fonctionnent sans droit de lecture sur le primaire.
- `PotProjection` et `BalanceProjection` utilisent l'identité, la couverture, le statut dérivé et le head génériques.
- Les artifacts sont immuables, les heads monotones et les materialisations read-side atomiques.
- `latestVersionSeen` est alimenté indépendamment et n'est jamais un gate des projectors.
- Le Query Kernel ne découvre pas une version depuis les artifacts et n'effectue aucun fallback.
- La couverture porte l'attente fonctionnelle indépendamment des Tasks ; `NOT_READY` est dérivé et readers/projectors n'étendent pas opportunistiquement cette couverture.
- Les autorisations historiques sont évaluées depuis `PotProjection` à la version exacte.
- Balance prête sans contexte Pot prêt retourne 409 ; un vrai refus avec contexte disponible retourne 404.
- Le routage direct d'une Expense distingue de façon prouvée inexistence et indisponibilité.
- Les indexes transverses sont atomiques, dérivés et reconstructibles.
- `GET /pots` utilise une pagination keyset déterministe et un `updatedAt` durable, stable et reconstructible.
- Un read store vide peut être reconstruit, y compris watermarks, artifacts, states, heads, indexes et métadonnées de query.
- Les générations actives, cutovers et rollbacks sont explicites et sans fallback automatique.
- Un seul producer logique est actif par identité/génération, tandis que plusieurs worker instances peuvent partager normalement le pool correspondant.
- Lag, `NOT_READY`, `FAILED`, trous, backfills et violations divergentes sont observables par pipeline.
- Les chemins legacy restants sont retirés ou explicitement conservés sous une échéance et une responsabilité documentées.

## Revision notes

- Priorité explicite de la readiness du contexte d'autorisation : une PotProjection de contexte absente, `NOT_READY` ou `FAILED` donne fonctionnellement 409 ; `FAILED`/503 n'est exposé qu'après autorisation établie.
- Clarification de `latestVersionSeen` dans le Query Kernel : un artifact projeté en avance ne rend pas la version connue et ne bloque pas les projectors.
- Séparation explicite entre couverture fonctionnelle et scheduling : readers et projectors n'étendent pas opportunistiquement la couverture, et `NOT_READY` reste dérivé.
- Décision obligatoire en Lot 7.7 sur la sémantique et la complétude du routage `expenseId -> potId` avant le cutover de `GET /expenses/{id}`.
- Validation d'un `updatedAt` durable et reconstructible rendue bloquante pour déclarer un rebuild complet.
- Distinction explicite entre un producer logique unique par génération et plusieurs instances de workers autorisées dans le pool correspondant.
