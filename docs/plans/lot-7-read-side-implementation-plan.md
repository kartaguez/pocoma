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
- `PipelineVersionDefinition.appliesTo(potVersion)` est l'unique règle d'applicabilité et toute génération applicable est éligible à la production.
- `PipelineSelectionStrategy` est reader-only et choisit, pour un `pipelineId` et une `potVersion`, l'unique `pipelineVersion` à exposer.
- Applicabilité, sélection reader, scheduling des Tasks et état fonctionnel sont quatre responsabilités distinctes.
- Les pools de workers sont séparés par pipeline et réutilisent le même moteur générique.
- Les projections peuvent terminer hors ordre ; les heads avancent par `max` et ne régressent jamais.
- Aucun reader ne fait de fallback silencieux vers une ancienne `potVersion`, une ancienne `pipelineVersion` ou le primaire.
- Les autorisations historiques sont évaluées avec le contexte de la version consultée ; un refus réel est masqué en `404`.
- Les backfills utilisent les mêmes Tasks, workers et garanties que le flux normal.
- Un Event reste une unité indépendante : la Task Event→Task est unique par `(eventId, pipelineId, pipelineVersion)`, jamais par `ProjectionIdentity`.
- Aucun état `projection_expectations`, `projection_states` ou équivalent n'est introduit.

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
- les détails physiques de `PotVersionMetadata.createdAt`, dont la source primaire durable est
  verrouillée et constitue le gate d'entrée du Lot 7.7 pour l'ordre et la pagination ;
- la politique de réparation d'une projection `FAILED` ;
- le contenu exact des stratégies de sélection READ_POT et Balance à chaque release ; leur modèle et leur sémantique sont en revanche verrouillés ;
- la durée de coexistence du legacy et la politique de garbage collection des anciennes générations.

La rétention des BusinessEvents peut aider la provenance, le replay du consommateur express et le diagnostic. Elle ne constitue pas une obligation de rétention éternelle pour reconstruire le read store : les projections se reconstruisent depuis le primaire historisé et un disaster rebuild du watermark peut utiliser une lecture administrative explicite du primaire autoritatif, hors chemin des GET.

## 4. Écarts actuels vers la cible

Les écarts documentés à résorber structurent l'ordre des lots :

| Domaine | État à migrer | Cible |
|---|---|---|
| Lectures | Certaines queries lisent encore le primaire historisé ou des adapters orientés write | Toutes les queries en production lisent uniquement le read store |
| Projection Pot | Absence de snapshot read canonique complet | `PotProjection` immuable par version, physiquement fragmentable |
| Balance | Projection et runtime existants avec concepts spécifiques/legacy | Même identité, applicabilité, statut dérivé, head et règles de matérialisation que Pot ; stratégie reader propre |
| Version source | Pas de watermark read-side spécialisé | `SourceVersionWatermark.latestVersionSeen`, alimenté par un consommateur Event express |
| État fonctionnel | État parfois déduit de la mécanique Task | Pour une version applicable : artifact/failure/absence donnent `READY`/`FAILED`/`NOT_READY`, sans lecture des Tasks |
| Head | Résolution potentielle par recherche dans les artifacts | `ProjectionHead.latestProjectedVersion`, monotone et canonique |
| Queries transverses | Risque de scans ou N+1 | Indexes dérivés `user -> Pot` et `user -> Balances` ; sous-ressources adressées sous leur Pot parent |
| Autorisation | Contexte potentiellement obtenu du primaire ou incomplet | Contexte versionné embarqué dans `PotProjection` |
| Pipeline generations | Activation implicite ou legacy | Définitions applicables produites indépendamment ; exposition décidée par une stratégie statique par `pipelineId` |
| Read store | Colocalisation et responsabilités à clarifier | Schéma, transactions, migrations et ownership logiquement séparés |

Le caractère terminal du delete et la contiguïté globale des versions sont des préconditions write-side.
Pour le delete Pot, le Lot 7.6 suit explicitement : projector shadow, preuve de reconstruction de la
version `DELETED`, puis micro-correctif write-side ciblé et tests avant clôture. Toute autre divergence
concrète constatée doit rester un bloqueur explicite, sans rouvrir silencieusement le Lot 6.

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

Les dépendances inter-projection, lorsqu'elles sont explicitement décidées, référencent cette identité complète. Il n'existe ni pipelineVersion active globale, ni sélection par `MAX`, ni fallback de génération.

### 5.2 Structures fonctionnelles minimales

- `SourceVersionWatermark` : `potId`, `latestVersionSeen` et métadonnées techniques minimales d'observation.
- `PipelineVersionDefinition` : identité de génération et `VersionApplicability` immuable ; `appliesTo` décide si cette génération peut produire une version Pot.
- `PipelineSelectionStrategy` : stratégie statique reader-only, propre à un `pipelineId`, qui choisit la génération à servir pour une version Pot.
- `ProjectionArtifact` et `ProjectionFailure` : issues terminales mutuellement exclusives par identité complète ; leur absence pour une version applicable donne `NOT_READY`.
- `ProjectionHead` : identité de génération de pipeline et `potId`, avec `latestProjectedVersion`.
- `PotProjection` : header du snapshot et fragments versionnés nécessaires aux ressources filles, à la pagination et à l'autorisation.
- `BalanceProjection` : artifact aligné sur la même identité et le même lifecycle générique.
- Indexes dérivés versionnés : `user -> Pot` et `user -> Balances`. Les sous-ressources se résolvent
  dans la projection exacte de leur Pot parent, sans routage transverse.
- Catalogue canonique des définitions et stratégies de sélection statiques.
- Trace séparée des violations d'invariant de matérialisation.

### 5.3 Applicabilité, sélection et statut dérivé

`PipelineVersionDefinition(identity, applicability)` est immuable et conservée tant que sa génération
peut être référencée. `appliesTo(V)` est l'unique règle de production : pour un Event V, le producer
parcourt toutes les définitions du `pipelineId` et crée/adopte une Task pour chaque définition
applicable. Il n'existe aucune policy `activeForProduction`, `enabledGeneration` ou équivalent.

`PipelineSelectionStrategy` répond à une autre question : quelle génération un reader sert-il pour V ?
Pour un `pipelineId` déjà connu par la stratégie, sa configuration est une liste ordonnée de couples
`(fromPotVersion, pipelineVersion)` ; le pipelineId n'est pas répété dans les entrées. Le contrat reste
conceptuellement `PipelineSelectionStrategy.resolve(potVersion) -> Optional<pipelineVersion>` et
retourne la version du couple ayant le plus grand `fromPotVersion <= V`, ou `empty`.

```text
READ_POT: (1,v1), (50,v2), (120,v3)
1..49 -> v1 ; 50..119 -> v2 ; 120..∞ -> v3
```

Les seuils sont positifs et strictement croissants. Les `pipelineVersion` ne sont pas monotones et
peuvent être répétées : `(1,v1), (50,v2), (100,v1)` est valide. Le premier seuil peut être supérieur à
1 et la stratégie peut être vide. Une stratégie vide masque volontairement le pipeline ; comme V avant
le premier seuil, elle donne `empty`, donc `NOT_FOUND`. L'absence de stratégie pour un pipeline demandé
est une erreur de configuration. Tous les pipelines connus ne sont pas obligés d'avoir une stratégie :
elle devient nécessaire seulement lorsqu'un reader demande leur résolution. L'observabilité peut
distinguer stratégie vide et V avant seuil sans modifier le contrat fonctionnel.

Chaque version référencée par une stratégie doit exister dans le catalogue pour le même `pipelineId` ;
l'assemblage doit idéalement rejeter une référence inconnue avant la première query. Il n'existe aucune
validation globale des trous ou chevauchements d'applicabilité. La stratégie est statique en code pour
le Lot 7, unique par `pipelineId` dans un build, sans SQL, configuration externe mutable ou refresh.
Elle peut changer entre releases sans nouvelle identité métier.

La sélection est reader-only. Elle ne participe jamais aux Tasks, backfills, repairs, rebuilds ou
projectors. Une génération applicable, produite et `READY` mais jamais sélectionnée est normale en
shadow mode, comparaison, préparation de cutover ou rollback.

Pour une définition applicable : artifact présent donne `READY`, failure terminale donne `FAILED`,
absence des deux donne `NOT_READY`. Une version non applicable n'est jamais `NOT_READY`. Aucun de ces
résultats ne lit le lifecycle Task et aucune expectation ou state par version n'est persistée.

Si une identité déjà `READY` est recalculée :

- contenu identique : succès idempotent/no-op ;
- contenu différent : artifact existant inchangé, nouveau contenu non écrit, statut maintenu à `READY`, violation d'invariant enregistrée et alertée.

Une divergence duplicate ne provoque donc jamais `READY -> FAILED`. Une éventuelle quarantaine administrative serait un mécanisme distinct à concevoir ultérieurement.

### 5.4 Query Kernel commun

Current et historique utilisent exactement la même stratégie. Current fixe d'abord
`targetPotVersion = latestVersionSeen`; une query explicite utilise la version demandée. Le kernel suit
ensuite cet ordre canonique :

1. déterminer `targetPotVersion` ; sans watermark pour current, retourner `NOT_FOUND` ;
2. vérifier via `SourceVersionWatermark` que la source est connue ; `V > latestVersionSeen` donne `NOT_FOUND` ;
3. récupérer la `PipelineSelectionStrategy` du `pipelineId` ; son absence est une erreur de configuration ;
4. appeler `resolve(V)` ; `empty` donne `NOT_FOUND` ;
5. reconstruire `PipelineDefinition(pipelineId, selectedPipelineVersion)` ;
6. charger la `PipelineVersionDefinition` exacte ; son absence est une erreur de configuration ;
7. vérifier `appliesTo(V)` ; `false` donne `NOT_FOUND` et une observabilité interne dédiée, sans nouvel état fonctionnel ;
8. résoudre artifact/failure/absence en `READY`/`FAILED`/`NOT_READY` ;
9. charger la `PotProjection` exacte requise pour l'autorisation. Si la ressource demandée relève de
   READ_POT, elle porte l'identité sélectionnée aux étapes précédentes ; sinon le kernel applique la
   stratégie READ_POT au même V et utilise exactement cette génération de contexte. Chaque projection
   requise est ainsi autorisée et servie avec sa propre stratégie, sans mélange de générations ;
10. appliquer les règles d'autorisation canoniques.

La sélection précède donc toujours l'autorisation. Une policy ne peut jamais utiliser une génération
différente de celle servie. Avec `latestVersionSeen=135` et `(1,v1),(50,v2),(120,v3)`, current sert v3
à V=135 et une query explicite V=73 sert v2.

Après contexte d'autorisation `READY`, un refus est masqué en 404 ; pour une projection métier
autorisée, `NOT_READY` donne 409, `FAILED` donne 503 et `READY` exige l'artifact exact. Si la
`PotProjection` de contexte est absente, non prête ou failed, le client reçoit 409 et le détail interne
reste observable.

Le kernel ne déduit pas l'existence source d'un artifact, ne recalcule pas un head avec `MAX`, ne crée aucune donnée fonctionnelle et ne consulte jamais le primaire.

## 6. Stratégie globale de migration

La migration suit une stratégie expand-and-contract :

1. Introduire les fondations read-side sans modifier les readers actifs.
2. Alimenter le watermark et les nouvelles projections en shadow mode.
3. Backfiller et comparer les résultats aux comportements existants hors chemin client.
4. Activer les nouveaux readers query par query, avec configuration explicite et rollback rapide.
5. Aligner Balance sans étendre sa refonte au-delà du modèle générique et des autorisations nécessaires.
6. Étendre explicitement une stratégie de sélection après preuve opérationnelle que la génération est suffisamment projetée sur la plage servie.
7. Retirer les readers et producers legacy seulement après une période d'observation et des critères de sortie vérifiés.

Chaque cutover modifie explicitement la stratégie reader et doit posséder un rollback explicite. Une
stratégie modifiée agit immédiatement, sans migration d'artifact : si la génération nouvellement
sélectionnée n'est pas prête, le résultat est `NOT_READY`, sans fallback.

## 7. Lots de réalisation détaillés

### Lot 7.1 — Consolidation documentaire et prérequis

**Objectif**

Faire porter explicitement par la documentation canonique tous les invariants nécessaires avant toute modification du read side.

**Responsabilités couvertes**

- cohérence entre cible, état actuel et plan directeur ;
- vocabulaire et ownership de l'identité, du statut dérivé, du head et du watermark ;
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
- applicabilité canonique, catalogue et registry exact ;
- `ProjectionHead` ;
- immutabilité, déterminisme, idempotence et hors-ordre ;
- violations d'invariant distinctes de l'état fonctionnel.

**Fichiers/modules probablement concernés**

- module de domaine/application de projection proposé ;
- adapters read persistence du Lot 7.2 ;
- composants Balance réutilisables identifiés au Lot 7.1.

**Modifications principales**

- Introduire les value objects et ports génériques.
- Définir l'upsert monotone du head avec `max`.
- Définir l'insertion immuable et la comparaison déterministe de contenu.
- Définir `PipelineVersionDefinition.appliesTo` et la résolution `READY`/`FAILED`/`NOT_READY` depuis artifact/failure/absence.
- Retirer définitivement l'ancien modèle de coverage et sa persistence sans créer de table de remplacement.
- Enregistrer les divergences duplicate dans un canal d'invariant séparé.

**Dépendances**

Lot 7.2.

**Tests**

- Fins de projection `44 -> 46 -> 45` : head final à 46 et artifacts tous adressables.
- Tentative de régression du head sans effet.
- Duplicate identique idempotent.
- Même identité et contenu différent : artifact inchangé, statut `READY`, nouveau contenu rejeté, violation enregistrée.
- Deux identités de pipeline versions différentes coexistent.
- Registry exact sans `MAX` ni fallback ; doublons d'identité refusés ; définitions historiques conservées.

**Critères de sortie**

- Pot et Balance peuvent dépendre du même contrat générique.
- L'état fonctionnel ne dépend ni des claims ni des retries.
- L'immuabilité et les violations divergentes sont testées.

**Risques/points à vérifier**

- Comparaison de contenu sensible à une sérialisation non canonique.
- Confusion entre échec terminal d'une projection non prête et divergence d'un artifact déjà prêt.
- Confusion entre applicabilité, sélection reader et scheduling.

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
- Ne jamais faire participer `latestVersionSeen` à `appliesTo` ni à la sélection des Tasks à produire.
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

### Lot 7.5 — Scheduling durable des projections applicables

**Objectif**

Créer/adopter les Tasks de chaque génération applicable à partir des Events candidats et du catalogue courant.

**Responsabilités couvertes**

- chemin Event -> Task ;
- évaluation de toutes les `PipelineVersionDefinition` du `pipelineId` ;
- payload d'exécution commun et provenances distinctes des Tasks ;
- séparation reader/projector/intention.

**Fichiers/modules probablement concernés**

- Event worker et builder/adopter de Tasks existants ;
- catalogue canonique de `domain-pipeline` ;
- runtime Task générique ;
- tests d'intégration Event/Task.

**Modifications principales**

- Pour chaque `BusinessEvent(V)`, parcourir toutes les définitions du pipeline concerné et créer/adopter
  une Task logique pour chacune telle que `appliesTo(V)` est vraie.
- Garantir durablement l'unicité `(eventId, pipelineId, pipelineVersion)`. Deux Events partageant
  `potId + potVersion` restent indépendants et peuvent produire deux Tasks.
- Définir conceptuellement le payload commun
  `ProjectionExecutionPayload(pipelineId, pipelineVersion, potId, potVersion)`. Une Task Event et une
  Task administrative partagent ce payload d'exécution, mais pas leur identité/provenance. Pour une
  Task Event, celle-ci est `(eventId, pipelineId, pipelineVersion)` ; `eventId` n'est donc pas une
  composante obligatoire du payload d'exécution commun. La représentation Java exacte reste ouverte.
- Une Task Event existante sous la même identité avec un `potId` ou `potVersion` différent dans son
  payload d'exécution est une violation technique, jamais un overwrite.
- Ne consulter ni artifact, failure, head ni statut : une Task manquante est créée même si la
  `ProjectionIdentity` est déjà `READY`; l'executor pourra retourner `AlreadySatisfied`.
- L'inspection ciblée du futur plan détaillé 7.5 doit démontrer le mécanisme réel de sélection des
  Events candidats. Conceptuellement, un Event est candidat s'il existe au moins une
  `PipelineVersionDefinition` applicable pour laquelle aucune Task
  `(eventId, pipelineId, pipelineVersion)` n'existe encore. La logique ne doit jamais considérer qu'un
  Event consommé une première fois est définitivement exclu.
- Évaluer ainsi les anciens Events avec le catalogue courant. Ajouter v3 applicable `[50..∞]` autorise
  naturellement un ancien Event V=73 à produire sa Task v3, sans mécanisme spécial de replay.
- Ne consulter `latestVersionSeen` ni `PipelineSelectionStrategy` pour créer, acquérir ou exécuter une Task.
- Ne créer aucune policy de production supplémentaire : applicabilité implique production.

**Dépendances**

Lot 7.3 ; connaissance ciblée des runtimes Event/Task.

**Tests**

- Même Event et même génération : une Task ; même Event et nouvelle génération : nouvelle Task légitime.
- Deux Events distincts à même `potVersion` peuvent créer deux Tasks.
- Payload d'exécution commun sans `eventId` obligatoire, provenance Event distincte et conflit de
  payload détecté sans overwrite.
- Artifact déjà `READY` n'empêche pas la création de la Task manquante.
- Scénario obligatoire : E existe à V=73 ; sa Task READ_POT/v1 existe ; READ_POT/v2 est ensuite ajoutée
  et `appliesTo(73)` est vraie ; aucune Task `(E, READ_POT, v2)` n'existe. Le producer redécouvre E,
  crée ou adopte exactement une Task v2, et ne duplique ni ne modifie la Task v1.
- Un reader constatant une absence ne crée aucune ligne.
- Une version non applicable ne produit aucune Task ; définition absente ou incohérente donne une erreur de configuration.
- Task N créée/acquise/exécutée avec `latestVersionSeen=N-1`.

**Critères de sortie**

- `NOT_READY` reste dérivé et indépendant du lifecycle Task.
- Le projector exécute une intention, il ne la crée pas.
- Le Query Kernel reste strictement read-only.
- Aucun gate watermark n'existe dans le runtime Task.
- L'ajout d'une nouvelle `pipelineVersion` applicable rend sélectionnables les anciens Events pour
  lesquels le triplet `(eventId, pipelineId, pipelineVersion)` manque encore.

**Risques/points à vérifier**

- Déduplication accidentelle par `potId + potVersion + pipelineVersion`, qui fusionnerait des Events indépendants.
- Consultation du read store par le producer et omission indue d'une Task déjà matérialisée.
- Introduction implicite d'une notion de génération active pour la production.

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
- Conserver les sous-objets historiquement applicables, y compris supprimés : leur présence dans le
  snapshot canonique ne signifie pas leur exposition par défaut par un futur GET.
- Calculer depuis le primaire historisé à `potVersion` exacte, sans dépendre d'une projection précédente.
- L'executor recharge la définition exacte et revérifie `appliesTo(potVersion)` avant matérialisation ;
  une Task non applicable est une erreur interne de protocole et ne produit aucun artifact.
- Une Task Event ou administrative produit la même `ProjectionIdentity`; la sélection reader n'intervient jamais.
- Mettre artifact, descriptor éventuel, head et indexes indispensables déjà introduits dans la transaction read-store.
- Garder `updatedAt` hors du snapshot tant qu'aucun mapping fonctionnel univoque par version n'est
  durablement garanti ; ne choisir ni min/max `recordedAt`, ni premier, dernier ou temps de projection.
- Après validation shadow de la version `DELETED`, livrer dans ce lot le micro-correctif write-side
  ciblé qui rejette toute mutation métier post-delete sans nouvelle version ni BusinessEvent.

**Dépendances**

Lots 7.2, 7.3 et 7.5.

**Tests**

- Reconstruction exacte de plusieurs versions, y compris ajout/retrait d'un membre et mutations d'Expense.
- Snapshot autonome sans reconstruction au GET.
- Version de delete projetée avec statut terminal, puis tests write-side prouvant l'absence de version
  et de BusinessEvent de mutation futurs après le micro-correctif.
- Données d'autorisation cohérentes avec chaque version historique.
- Atomicité des fragments et du header.
- Déterminisme du timestamp candidat entre deux reconstructions, si la source est déjà validée.

**Critères de sortie**

- La projection shadow est comparable aux lectures primaires existantes.
- Les fragments couvrent les queries Pot, Shareholder et Expense connues.
- `updatedAt` reste explicitement ouvert sans empêcher la clôture shadow 7.6, et constitue le gate
  d'entrée du Lot 7.7 pour ordering, indexes et pagination current.
- Delete Pot est réellement terminal côté write-side après le micro-correctif ciblé.

**Risques/points à vérifier**

- Reconstruction historique incomplète pour le delete terminal.
- Timestamp write/Event non univoque par version.
- Snapshot trop couplé aux DTO HTTP.

### Lot 7.7 — Métadonnées de version Pot, index utilisateur et pagination keyset

**Objectif**

Matérialiser le timestamp fonctionnel de chaque Pot version et les structures historisées nécessaires
aux listes multi-Pots déterministes.

**Responsabilités couvertes**

- `PotVersionMetadata.createdAt` primaire et sa copie read-side exacte ;
- index versionné `userId -> PotProjection@V` ;
- résolution de la version cible par watermark individuel ;
- pagination keyset ;
- cohérence atomique avec `PotProjection`.

**Fichiers/modules probablement concernés**

- transaction de matérialisation Pot ;
- repositories d'indexes read ;
- query adapters shadow ;
- allocation primaire des Pot versions et reconstruction historique.

**Modifications principales**

- Créer exactement un `createdAt` durable dans la transaction primaire qui crée chaque Pot version.
- Refuser tout backfill approximatif des versions legacy.
- Propager cette metadata dans la transaction de matérialisation de PotProjection.
- Maintenir l'index utilisateur/Pot versionné et scopé par génération, sans ligne current.
- Résoudre chaque candidat par `latestVersionSeen(potId)` puis génération sélectionnée à la lecture.
- Définir l'ordre strict `updatedAt DESC, potId ASC` et un curseur opaque keyset.
- Conserver le consumer SourceVersionWatermark pur : Event vers max version, sans route ni index.
- Documenter le cas d'une appartenance nouvelle portée par une PotProjection NOT_READY ; ne pas créer
  implicitement une structure source-membership supplémentaire.
- Préparer les contrats des sous-ressources imbriquées : projection exacte absente = `NOT_READY`,
  enfant absent dans une projection READY = `NOT_FOUND`.

**Dépendances**

Lot 7.6. La matérialisation primaire de `PotVersionMetadata.createdAt` est la première étape du lot.

**Tests**

- Indexes et projection canonique committés ou rollbackés ensemble.
- Pagination stable avec timestamps égaux grâce au tie-breaker `potId`.
- Pas de doublon ni omission entre pages lors d'un parcours sur un snapshot de données stable.
- Rebuild des indexes à partir des sources canoniques.
- Projections hors ordre et générations isolées.
- Version source connue + projection requise absente = `NOT_READY`, sans fallback.
- PotProjection READY + Expense/Shareholder absent = `NOT_FOUND`.
- Appartenance user nouvelle à une version NOT_READY explicitement couverte.

**Critères de sortie**

- `updatedAt` est durable, exact et stable entre rebuilds.
- L'ordre canonique `updatedAt DESC, potId ASC` est déterministe.
- Les indexes restent versionnés, génération-scopés, dérivés, atomiques et reconstructibles.
- Aucun routage global Expense/Shareholder et aucun current fonctionnel ne sont introduits.
- Le SourceVersionWatermark conserve sa responsabilité unique.

**Risques/points à vérifier**

- Coût du join entre candidats versionnés, watermarks individuels et plages de générations.
- Une appartenance apparaissant dans une projection NOT_READY n'est pas découvrable par l'index
  dérivé ; la sémantique produit de la liste doit être fermée au Lot 7.9 avant cutover.

### Lot 7.8 — Tasks administratives, rebuild et validation de reconstructibilité

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

- Générer des Tasks administratives identifiées par
  `(campaignId, potId, potVersion, pipelineId, pipelineVersion)` sans moteur de calcul parallèle.
- Autoriser deux campagnes à créer deux Tasks pour la même `ProjectionIdentity`; l'idempotence reste
  portée par la matérialisation finale.
- Rendre le déclenchement durable, reprenable et idempotent, quelle que soit l'interface retenue.
- Distinguer campagne volontaire, rebuild complet et réparation automatique ciblée.
- Utiliser le même `ProjectionExecutionPayload(pipelineId, pipelineVersion, potId, potVersion)` et le
  même executor que les Tasks Event→Task, tout en conservant la provenance administrative
  `(campaignId, potId, potVersion, pipelineId, pipelineVersion)`. L'executor ignore la provenance et
  revérifie l'applicabilité. La forme Java de cette séparation n'est pas figée dans ce lot.
- Pour un disaster rebuild, reconstruire le watermark par replay Event si disponible ou par lecture administrative autoritative ; ne jamais exposer ce chemin aux GET.
- Réutiliser la source de `updatedAt` qui doit déjà avoir été fermée au gate d'entrée du Lot 7.7 : elle
  doit être déterministe, stable entre rebuilds, durable et indépendante d'un état volatile du read store.
- Ne pas rendre le backfill dépendant de la rétention des Events.
- Prouver que artifacts, heads, indexes et métadonnées de tri sont reproductibles.

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
- La source de `updatedAt`, prérequis hérité du Lot 7.7, reste décidée, documentée et testée pendant le rebuild.
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
- `PipelineSelectionStrategy` statique par `pipelineId` ;
- `NOT_FOUND`, `NOT_READY`, `FAILED` ;
- `404`, `409`, `503` ;
- absence de fallback ;
- `potVersion` dans les succès.

**Fichiers/modules probablement concernés**

- module application/query read ;
- ports watermark, stratégie, catalogue, head et artifact/failure ;
- mapping d'erreurs HTTP commun ;
- tests contractuels.

**Modifications principales**

- Implémenter l'ordre décrit en section 5.4.
- Déterminer d'abord V : `latestVersionSeen` pour current, version fournie pour explicite ; appliquer
  ensuite exactement la même stratégie de sélection.
- Distinguer stratégie absente (erreur de configuration) et stratégie présente retournant `empty`
  (`NOT_FOUND`), que celle-ci soit vide ou que V précède son premier seuil.
- Valider à l'assemblage chaque référence de stratégie contre le catalogue du même `pipelineId`.
- Si la définition sélectionnée n'est pas applicable à V, retourner `NOT_FOUND` avec observabilité
  interne ; ne créer aucun nouvel état fonctionnel.
- Ne jamais choisir une version artifact plus ancienne, sonder une génération précédente ou appliquer un fallback.
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
- Génération sélectionnée non prête avec une autre génération prête : aucun fallback.
- Stratégie vide, V avant premier seuil et stratégie absente suivent leurs contrats distincts.
- Stratégie `(1,v1),(50,v2),(100,v1)` et pipelineVersion répétée acceptées.
- Reader n'insère ni ne modifie artifact, failure ou head.

**Critères de sortie**

- Tous les états ont un contrat stable.
- L'existence source connue est exclusivement résolue via le watermark.
- Les projectors restent indépendants de cette règle de query.

**Risques/points à vérifier**

- Confusion entre génération sélectionnée non applicable (`NOT_FOUND`) et génération applicable sans résultat (`NOT_READY`).

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
- Sélectionner la génération avant l'autorisation et charger le `PotProjection` exact correspondant
  au `pipelineId`, à la `pipelineVersion`, au `potId` et à la `potVersion` effectivement servis.
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
- `GET /pots/{potId}/expenses/{expenseId}` ;
- `GET /pots/{potId}/shareholders/{shareholderId}` et autres queries Shareholder imbriquées concernées.

**Fichiers/modules probablement concernés**

- controllers et query handlers identifiés au Lot 7.1 ;
- adapters de `PotProjection` et indexes ;
- feature/configuration de cutover ;
- tests API et sécurité.

**Modifications principales**

- Basculer d'abord les queries directement résolubles dans un Pot connu.
- Basculer `GET /pots` avec actifs par défaut, archives seulement sur demande explicite et pagination keyset.
- Ne pas migrer le endpoint global legacy Expense vers la cible : les sous-ressources sont adressées
  sous leur Pot parent.
- Retourner `potVersion` dans chaque succès versionné.
- Utiliser la même sélection de génération pour current et historique.
- Conserver un rollback explicite vers le reader legacy pendant la période d'observation, sans fallback par requête.

**Dépendances**

Lots 7.7, 7.9 et 7.10. La sémantique de liste face à une appartenance nouvelle NOT_READY doit être
fermée avant le cutover de `GET /pots`.

**Tests**

- Contrats 404/409/503 pour current et explicite.
- Liste active par défaut et inclusion archive explicite.
- Pagination keyset déterministe.
- Sous-ressource imbriquée : projection Pot absente pour une version connue = `NOT_READY`, enfant
  absent dans une projection READY = `NOT_FOUND`.
- Test d'intégration exécutant les GET avec un compte SQL sans droit `SELECT` sur le primaire.
- Comparaison shadow et tests de rollback de configuration.

**Critères de sortie**

- Chaque GET basculé n'utilise que le read store.
- Aucun GET global Expense/Shareholder n'est introduit dans la cible.
- Le legacy reste isolé et désactivable, pas consulté en fallback.

**Risques/points à vérifier**

- Différences de forme ou de pagination constituant une rupture API.
- Sémantique produit des listes archives.

### Lot 7.12 — Alignement minimal de Balance sur le modèle générique

**Objectif**

Faire adopter à Balance l'identité, l'applicabilité, le statut dérivé, le head et la transaction read-store génériques sans refonte fonctionnelle élargie.

**Responsabilités couvertes**

- coexistence legacy/nouveau pipeline ;
- artifact Balance immuable ;
- définitions applicables et stratégie reader Balance indépendante ;
- index `user -> Balances` atomique ;
- pools séparés.

**Fichiers/modules probablement concernés**

- pipeline/runtime Balance existant ;
- modèle générique du Lot 7.3 ;
- adapter Balance et index transverse ;
- configuration de workers.

**Modifications principales**

- Adapter l'identité Balance existante sans inventer une version exacte avant inventaire.
- Matérialiser artifact/failure/head/index selon les mêmes règles que Pot.
- Conserver temporairement l'artifact legacy pour comparaison et rollback.
- Configurer un pool `BALANCE` séparé du pool Pot, sur le même moteur générique.
- Éviter toute dépendance structurelle d'ordre entre projectors Balance et Pot.

**Dépendances**

Lots 7.3, 7.5, 7.8 et 7.9.

**Tests**

- Tests génériques idempotence/hors-ordre appliqués à Balance.
- Index utilisateur/Balance atomique avec la projection.
- Pools distincts sans famine inter-pipeline.
- Coexistence de deux générations, production de toutes les applicables et sélection reader explicite.
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

- Résoudre Balance avec sa propre `PipelineSelectionStrategy`, indépendante de READ_POT. Par exemple,
  READ_POT peut basculer à v2 dès V=50 et Balance seulement à V=120.
- Charger pour l'autorisation la `PotProjection` exacte de la génération sélectionnée par la stratégie
  du pipeline requis ; ne jamais autoriser sur une autre génération que celle servie.
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

### Lot 7.14 — Stratégies reader, shadow, cutover et rollback

**Objectif**

Rendre les changements de pipelineVersion explicites, mesurables et réversibles.

**Responsabilités couvertes**

- coexistence des générations ;
- stratégie unique et explicite par `pipelineId` ;
- readiness opérationnelle avant extension d'une plage servie ;
- absence de fallback ;
- producer logique unique et workers multiples.

**Fichiers/modules probablement concernés**

- catalogue et stratégies reader statiques ;
- mécanisme de backfill ;
- déploiement/configuration Event->Task et pools de workers ;
- runbook de cutover/rollback.

**Modifications principales**

- Déployer une nouvelle définition applicable : le producer crée ses Tasks sans attendre sa sélection reader.
- Vérifier heads, artifacts, failures, indexes et divergences avant d'étendre la stratégie sur une plage.
- Modifier explicitement la stratégie statique. Le changement agit immédiatement sur current et historique,
  sans migration d'artifact ni nouvelle identité métier.
- Définir le rollback comme une nouvelle modification explicite de la stratégie ; une pipelineVersion
  peut donc réapparaître dans une entrée ultérieure.
- Conserver les anciennes générations selon une politique de rétention/GC ultérieure.
- Garantir un seul **producer logique** Event→Task coordonné, qui évalue toutes les définitions applicables.
- Autoriser plusieurs instances de workers derrière ce producer logique. Elles peuvent scanner, claim et exécuter en concurrence via leases/claims/fencing ; c'est le fonctionnement nominal d'un pool.
- Interdire toute stratégie différente selon endpoint, utilisateur, scope, query ou environnement runtime
  dans un même build : `pipelineId + potVersion` sélectionne une seule pipelineVersion.

**Dépendances**

Lots 7.8, 7.11 et 7.13 selon le pipeline.

**Tests**

- Nouvelle génération sélectionnée non prête avec ancienne prête : 409, pas de fallback.
- Changement `(1,v1),(50,v2)` vers `(1,v1),(80,v2)` ressert V=60 depuis v1 sans migration.
- Gate de cutover opérationnel : génération suffisamment projetée sur toute la plage qui sera servie.
- Plusieurs workers concurrents d'un même pipeline produisent un seul commit grâce au fencing.
- Configuration/déploiement empêche deux producers logiques pour une même génération.

**Critères de sortie**

- Cutover et rollback sont documentés et testés.
- Une seule source logique d'intentions évalue le catalogue ; la sélection reader reste indépendante.
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
- Exposer nombres `NOT_READY` et `FAILED` sur les définitions applicables, trous visibles et progression de backfill.
- Distinguer lag du consumer express, lag de projection et artifact projeté en avance.
- Alerter séparément les divergences de contenu d'une identité `READY` sans changer son statut dérivé.
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
- Désactiver d'abord le producer logique legacy, vérifier qu'un seul producer coordonné évalue le catalogue, puis supprimer son wiring.
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
- Un seul producer logique coordonné évalue toutes les générations applicables ; plusieurs workers peuvent les servir.
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
12. PipelineVersion sélectionnée sans fallback vers une autre génération prête.
13. Reader strictement read-only : aucune donnée de projection créée par un GET.
14. Même Event et même génération : une seule Task durable ; deux Events de même version restent indépendants.
15. Une nouvelle définition applicable permet à un Event ancien encore candidat de produire une nouvelle Task.
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
26. Sous-ressource imbriquée : PotProjection exacte absente pour une version connue, `NOT_READY`.
27. Sous-ressource imbriquée : PotProjection READY mais Expense/Shareholder absent, `NOT_FOUND`.
28. Pagination keyset stable selon `updatedAt DESC, potId`.
29. Plusieurs workers d'un pipeline concurrents, fencés et idempotents.
30. Un seul producer logique actif pour une identité/génération donnée.
31. Tous les GET réussissent avec un compte SQL privé de `SELECT` sur le primaire.
32. Stratégie absente : erreur de configuration ; stratégie vide ou V avant premier seuil : `NOT_FOUND`.
33. Current et historique passent par la même stratégie après détermination de V.
34. Une stratégie peut revenir de v2 à v1 et répéter une pipelineVersion ; aucun `MAX` n'est utilisé.
35. Toute référence de stratégie inconnue du catalogue et du même `pipelineId` est rejetée à l'assemblage.
36. Une définition sélectionnée mais non applicable donne `NOT_FOUND` avec signal interne, jamais `NOT_READY`.
37. L'autorisation utilise exactement la génération sélectionnée pour l'artifact de contexte servi.
38. Le producer ne consulte aucun artifact, failure, head ou statut et crée la Task manquante même si l'artifact est déjà prêt.
39. Une Task Event embarque l'identité complète ; un conflit de `potId` ou `potVersion` sous la même identité est rejeté.
40. Deux campagnes administratives peuvent viser la même `ProjectionIdentity` via deux Tasks distinctes et le même executor.

## 9. Risques et décisions humaines nécessaires

### Décisions à fermer avant les lots concernés

- **Source de `updatedAt`** : valider une métadonnée durable par version. `RecordedEvent.recordedAt` est
  un candidat, sans règle arbitraire si plusieurs Events partagent une version. Décision requise avant
  le démarrage des parties ordering/indexes/pagination current du Lot 7.7.
- **Routage Expense** : choisir A ou B après vérification ciblée des invariants d'identité/création/suppression. Décision bloquante au début du Lot 7.7 pour le cutover du GET direct.
- **Contrat `balances/me`** : confirmer s'il reste versionnable ou devient current-only avant toute rupture API.
- **Interface de backfill** : retenir commande, endpoint administratif ou autre orchestration durable selon les conventions d'exploitation.
- **Identités et stratégies initiales** : confirmer l'identité READ_POT et les seuils initiaux READ_POT/Balance à partir des générations réellement disponibles avant leur lot de reader ; cela ne bloque pas 7.4.
- **Coordination Task/read store** : décider si le Lot 7 exploite la transaction PostgreSQL colocalisée, tout en conservant une frontière permettant un protocole fencé futur.
- **Politique de rétention/GC** : différée mais nécessaire avant suppression physique d'anciennes générations.

### Risques structurants

- Le delete terminal ou la contiguïté des versions ne seraient pas garantis par le write side réel.
- Une dépendance primaire subsisterait derrière un adapter de lecture ou d'autorisation.
- Le shadow mode créerait deux producers logiques pour une même génération au lieu de générations distinctes.
- Le read store séparé logiquement resterait couplé aux transactions ou entités du primaire.
- Un index transverse incomplet serait interprété à tort comme preuve d'inexistence.
- La matérialisation d'une version en avance serait confondue avec sa connaissance source par le Query Kernel.
- Une stratégie reader serait réutilisée par le producer, empêchant le shadow mode ou la production d'une génération applicable.
- Une Task serait dédupliquée par `ProjectionIdentity`, fusionnant deux Events ou campagnes indépendants.
- Une définition historique encore référencée serait supprimée avant une politique explicite de GC.
- Une stratégie sélectionnerait une définition absente ou non applicable sans erreur/signal opérationnel.
- La cardinalité de l'observabilité masquerait les signaux importants ou deviendrait coûteuse.

## 10. Critères de fin du Lot 7

Le Lot 7 est terminé lorsque toutes les conditions suivantes sont satisfaites :

- Les GET Pot, Shareholder, Expense et Balance du périmètre lisent exclusivement le read store.
- Un test d'intégration SQL prouve qu'ils fonctionnent sans droit de lecture sur le primaire.
- `PotProjection` et `BalanceProjection` utilisent l'identité, l'applicabilité, le statut dérivé et le head génériques.
- Les artifacts sont immuables, les heads monotones et les materialisations read-side atomiques.
- `latestVersionSeen` est alimenté indépendamment et n'est jamais un gate des projectors.
- Le Query Kernel ne découvre pas une version depuis les artifacts et n'effectue aucun fallback.
- Toutes les générations applicables sont produites ; la stratégie reader ne participe jamais au scheduling.
- `NOT_READY` est dérivé uniquement de l'absence d'artifact et de failure pour une définition applicable.
- Aucune coverage, expectation ou state de projection n'existe.
- Les autorisations historiques sont évaluées depuis `PotProjection` à la version exacte.
- Balance prête sans contexte Pot prêt retourne 409 ; un vrai refus avec contexte disponible retourne 404.
- Les sous-ressources sont adressées sous leur Pot parent ; projection exacte absente et enfant absent
  ont des sémantiques `NOT_READY`/`NOT_FOUND` distinctes et testées.
- Les indexes transverses sont atomiques, dérivés et reconstructibles.
- `GET /pots` utilise une pagination keyset déterministe et un `updatedAt` durable, stable et reconstructible.
- Un read store vide peut être reconstruit, y compris watermarks, artifacts, failures, heads, indexes et métadonnées de query.
- Les stratégies de sélection, cutovers et rollbacks sont explicites, statiques et sans fallback automatique.
- Current et historique utilisent une stratégie unique par `pipelineId`, validée contre le catalogue canonique.
- Les définitions historiques restent présentes tant qu'une Task, un artifact, l'historique, un rollback,
  une comparaison, un backfill, repair ou rebuild peut les référencer.
- Un seul producer logique est actif par identité/génération, tandis que plusieurs worker instances peuvent partager normalement le pool correspondant.
- Lag, `NOT_READY`, `FAILED`, trous, backfills et violations divergentes sont observables par pipeline.
- Les chemins legacy restants sont retirés ou explicitement conservés sous une échéance et une responsabilité documentées.

## 11. Dépendances structurantes entre lots

- 7.4 dépend seulement des fondations 7.2/7.3.1 : watermark express, sans Task, stratégie reader ni Lot 7.5.
- 7.5 dépend du catalogue 7.3.1 et établit le contrat durable Event→Task avant tout projector métier.
- 7.6 et 7.12 partagent le même executor défensif et ne connaissent aucune sélection reader.
- 7.8 réutilise ce contrat via une identité administrative distincte et ne dépend pas des Events retenus.
- 7.9 introduit la stratégie reader et l'ordre canonique du Query Kernel avant 7.10/7.11/7.13.
- 7.10 impose la sélection avant l'autorisation ; 7.11 et 7.13 réutilisent ensuite le même chemin current/historique.
- 7.14 formalise les changements de stratégie seulement après production shadow et vérification opérationnelle.

Aucun point architectural n'est bloquant avant le plan détaillé de 7.4. La forme concrète du consumer
express et la procédure administrative de reconstruction du watermark sont des décisions internes à
ce lot. Les seuils des stratégies reader, l'identité exacte READ_POT, `updatedAt`, Balance et le
contrat des Tasks administratives ne bloquent pas 7.4 et restent attachés à leurs lots.
