# Lot 7 — Plan directeur du read side

## 1. Autorité, baseline et statuts

Ce plan séquence l'écart entre le code observé au commit
`34df9348dd0823d4e977859048aaf8b9ef44db6c` et
[l'architecture cible](../architecture/read-side-target.md).
L'[état actuel](../architecture/read-side-current-state.md) porte l'inventaire factuel. En cas de contradiction,
la cible normative prévaut.

Statuts utilisés :

- `DONE` : critères fonctionnels du sous-lot présents et vérifiés dans le code/tests ;
- `PARTIAL` : fondation réelle présente, mais au moins un critère du sous-lot manque ;
- `NOT_STARTED` : aucun livrable propre au sous-lot n'est intégré ;
- `SUPERSEDED` : mécanisme remplacé par un autre invariant ;
- `ABSORBED` : objectif porté par un mécanisme ou un autre sous-lot, sans implémentation autonome.

La suite complète de la baseline exécute 869 tests sans échec ni erreur.

## 2. Invariants applicables à tous les lots

1. Events et Tasks peuvent être traités dans n'importe quel ordre.
2. Aucun projector N n'attend N-1 ou `latestKnownVersion >= N`.
3. Les artifacts sont immuables, exacts, idempotents et adressés par identité complète.
4. Un head est un maximum matérialisé, jamais une preuve de continuité ou de convergence.
5. Les pipelines convergent indépendamment ; leur composition intervient uniquement à la lecture.
6. `CURRENT` sert la meilleure intersection de versions `READY` des composants requis.
7. `EXACT(V)` ne sert que V, sans fallback.
8. `latestKnownVersion` est informatif pour les queries et ne choisit pas la version servie.
9. Une liste est un read model convergent ; elle ne relit ni le primaire ni latest-known pour être
   reconstruite à la volée.
10. Les scopes du token sont courants ; seuls les faits métier member/creator sont historisés.
11. AUTH est une projection indépendante de READ_POT.
12. Une reconstruction utilise une nouvelle `pipelineVersion`; elle ne reset jamais une ancienne
    Task, Slot ou Claim.
13. Le passage d'une pipelineVersion à `serving` est manuel et exige une convergence initiale complète.

Les identifiants physiques legacy `SOURCE_VERSION_WATERMARK` et
`source_version_watermarks.latest_version_seen` sont conservés tant que le code ou les données les
requièrent. Ils portent `latestKnownVersion`, pas une continuité.

## 3. État synthétique

| Lot | Statut | État réel et portée restante |
|---|---|---|
| 7.1 Documentation/prérequis | `DONE` | Documents canoniques réconciliés avec la baseline et les décisions courantes. |
| 7.2 Frontière read store | `PARTIAL` | Schéma/module/transactions dédiés ; datasource physique et permissions GET encore partagées. |
| 7.3 Projection générique | `DONE` | Identité, artifact, failure, head, violation, atomicité et statut dérivé. |
| 7.3.1 Applicabilité | `DONE` | Catalogue et `appliesTo` canoniques ; coverage persisté supprimé. |
| 7.4 LatestKnownVersion | `DONE` | Consumer direct, max-upsert, lifecycle générique, aucune Task. |
| 7.5 Scheduling applicable | `DONE` | Toutes les générations applicables, redécouverte historique native. |
| 7.6 READ_POT shadow | `DONE` | Snapshot canonique complet et delete terminal. |
| 7.7 Metadata/index/keyset | `DONE` | Metadata exacte et index atomique ; reader shadow current désormais legacy. |
| 7.8 Rebuild administratif | `ABSORBED` | Absorbé par 7.5 et 7.14 : nouvelle pipelineVersion, jamais de reset/replay spécial. |
| 7.9 Query Kernel | `NOT_STARTED` | Intentions, composants, sélection READY et enveloppe à implémenter. |
| 7.10 Authorization Kernel/AUTH | `NOT_STARTED` | TokenCapabilities, AUTH, freshness et historique à implémenter. |
| 7.11 GET Pot/Expense | `NOT_STARTED` | Lot d'intégration après 7.9/7.10. |
| 7.12 Balance générique | `PARTIAL` | Calcul exact/hors ordre livré ; persistence/statut/head génériques manquants. |
| 7.13 GET Balance | `PARTIAL` | Artifact immuable déjà lu, mais primary/auth/N+1/erreur technique restent. |
| 7.14 Lifecycle pipeline/cutover | `NOT_STARTED` | declared/active/serving et éligibilité absents. |
| 7.15 Observabilité | `PARTIAL` | Consumption/latest-known/legacy instrumentés ; signaux read-side cibles incomplets. |
| 7.16 Extinction legacy | `NOT_STARTED` | Legacy toujours actif dans GET et monolithe. |

## 4. Lots livrés ou absorbés

### 7.1 — Consolidation documentaire et prérequis

**Statut : `DONE`**

Livré :

- séparation explicite cible/état courant/plan ;
- définition canonique de latest-known, CURRENT/EXACT, composants, listes, AUTH et pipeline lifecycle ;
- correction des contradictions current, rebuild, head et scope historique ;
- alignement avec le delete Pot terminal et `PotVersionMetadata.createdAt`.

Une évolution ultérieure du code devra mettre à jour l'état courant et le statut du lot correspondant,
sans recopier toute la cible dans ce document.

### 7.2 — Read store logique et frontière transactionnelle

**Statut : `PARTIAL`**

Déjà livré :

- `infra-read-persistence` sans dépendance vers l'adapter primaire ;
- schéma/migrations Flyway `pocoma_read` autonomes ;
- accès JDBC et transaction manager nommés ;
- rollback atomique artifact/metadata/head/fragments/index ;
- tests d'installation du read store sans migrations primaires.

Restant avant cutover GET :

- compte SQL de lecture limité au read store ;
- test d'intégration prouvant qu'un GET cible fonctionne sans `SELECT` primaire ;
- déplacement de la persistence Balance hors des tables et FK primaires lors de 7.12.

La séparation physique en deux bases n'est pas une précondition du Lot 7.

### 7.3 — Modèle générique de projection

**Statut : `DONE`**

Livré et testé : identité complète, artifact/failure mutuellement exclusifs, statut dérivé,
`ProjectionHead` monotone, duplicate identique, duplicate divergent sans dégradation de READY,
verrouillage par identité et atomicité read-store.

Le mécanisme `ProjectionCoverage` persisté initialement envisagé est `SUPERSEDED`. La migration V3 le
supprime ; applicabilité, artifacts et failures suffisent au statut exact.

### 7.3.1 — Applicabilité canonique

**Statut : `DONE`**

`PipelineVersionDefinition.appliesTo(potVersion)` est l'unique règle de production. Une définition
applicable peut être produite même si une autre génération ou un artifact existe déjà. Aucune notion
de génération serving n'intervient côté producer.

### 7.4 — Consumer direct LatestKnownVersion

**Statut : `DONE`**

Livré : consumer Event spécialisé, reload autoritatif, max-upsert transactionnel, provenance,
claim/lease/fencing/retry génériques et tests hors ordre/duplicate/rollback/takeover.

Le consumer garde les identifiants legacy persistés mais n'est ni un Task producer, ni un projector,
ni un gate de query.

### 7.5 — Scheduling durable de toutes les projections applicables

**Statut : `DONE`**

Livré :

- pertinence stable par `pipelineId` ;
- production de chaque `pipelineVersion` applicable ;
- Task Event-derived unique par `(eventId,pipelineId,pipelineVersion)` ;
- redécouverte des anciens Events lorsqu'une nouvelle génération applicable apparaît ;
- aucune lecture du read store, de latest-known ou d'une stratégie serving.

Cette redécouverte est le mécanisme unique utilisé pour toute reconstruction future.

### 7.6 — PotProjection canonique en shadow

**Statut : `DONE`**

Livré : `read-pot/v1`, Task et executor dédiés, reconstruction primaire exacte, artifact logique
READ_POT complet, fragments physiques, statut ACTIVE/DELETED, descriptor/head/failure génériques et
atomicité avec la consumption Task. Le write side interdit désormais toute mutation après delete Pot.

### 7.7 — Metadata Pot, index user et pagination keyset

**Statut : `DONE`**

Livré :

- `PotVersionMetadata.createdAt` exact et immuable dans la transaction primaire ;
- copie read-side vérifiée ;
- index user→Pot versionné et scopé par génération ;
- écriture atomique avec READ_POT ;
- pagination `updatedAt DESC, potId ASC` ;
- absence de current fonctionnel persisté.

Élément explicitement legacy : `JdbcPotUserIndexReader` joint encore la ligne indexée à
`latest_version_seen`. Aucun GET actif ne l'utilise. Sa logique de sélection sera remplacée lors de
7.9/7.11 ; cela ne rouvre pas 7.7, dont les données et garanties d'index restent valides.

### 7.8 — Rebuild/replay/backfill administratif

**Statut : `ABSORBED` dans 7.5 et 7.14**

Il ne sera créé ni campagne de rebuild, ni Task administrative de replay, ni génération spéciale de
rebuild, ni procédure rouvrant les états d'une ancienne génération.

Pour toute évolution ou reconstruction :

```text
déclarer une nouvelle pipelineVersion avec ses bornes
-> l'activer
-> laisser 7.5 redécouvrir l'historique applicable
-> traiter ses propres Tasks/Slots/Claims
-> vérifier la convergence avec 7.14/7.15
-> cutover serving manuel éventuel
```

Tests déjà présents : redécouverte d'un Event ancien pour une nouvelle génération, unicité des Tasks,
indépendance des générations et exécution idempotente. Les tests complémentaires de convergence et
d'éligibilité appartiennent à 7.14/7.15.

## 5. Lots architecturaux restants

### 7.9 — Query Kernel, composants et enveloppe versionnée

**Statut : `NOT_STARTED`**

Ce lot ne migre encore aucun controller. Il fournit le moteur read-only commun.

#### 7.9.1 — Contrats de query versionnée

Définir :

- `QueryVersionIntent` avec `CURRENT` et `EXACT(V)` ;
- `VersionedQueryResponse<T>` avec `requestedVersion`, `servedVersion`, `latestKnownVersion`,
  `generatedAt`, `data`, sans champ `stale` ;
- identité d'un composant logique de vue ;
- déclaration statique des composants requis par une query ;
- contrat de lecture des versions `READY` exactes pour une version de pipeline serving fournie.

Tests : validation de V positif, fidélité de `requestedVersion`, distinction generatedAt/projection
time, un seul READ_POT malgré plusieurs tables et absence de dépendance HTTP/persistence dans les
contrats.

#### 7.9.2 — Résolution CURRENT et EXACT

Implémenter sans autorisation ni controller :

- `CURRENT = max(intersection des versions READY de tous les composants requis)` ;
- `EXACT(V) = tous les composants READY à V` ;
- résolution depuis les artifacts exacts, jamais depuis les heads ;
- latest-known lu uniquement pour l'enveloppe/information ;
- version `FAILED` plus récente n'occultant pas une version READY antérieure en CURRENT ;
- aucun fallback en EXACT ;
- composants de deux endpoints autorisés à converger vers des servedVersions différentes.

Tests obligatoires :

- latest-known 15, READ_POT READY 13 → CURRENT sert 13 ;
- READ_POT READY 15 et latest-known 14 → CURRENT sert 15 ;
- composantes READY `{12,13,15}` et `{11,13,14}` → CURRENT sert 13 ;
- head 15 avec trou à 14 ne prouve pas READY(14) ;
- EXACT(15) avec un composant absent/NOT_READY/FAILED ne sert aucune autre version.

#### 7.9.3 — États fonctionnels et mapping HTTP commun

Après l'Authorization Kernel 7.10, assembler l'ordre sans fuite : AUTH gate, sélection/existence,
readiness, lecture. Stabiliser `NOT_READY`, `PROJECTION_FAILED`, `AUTH_NOT_READY`, masquage des refus
et enveloppes de succès. Aucun détail de claim/Task/pipeline interne ne doit fuir.

Dépendances : 7.9.1 → 7.9.2 ; 7.9.3 dépend de 7.10 et du modèle serving minimal de 7.14.1.

### 7.10 — Authorization Kernel et projection AUTH

**Statut : `NOT_STARTED`**

#### 7.10.1 — Contrats d'autorisation

- séparer `TokenCapabilities` actuelles de `PotAuthorizationAtVersion` ;
- historiser seulement `isMember` et `isCreator` ;
- dériver les droits via les policies métier partagées ;
- ajouter les capacités `VIEW_ARCHIVE` nécessaires ;
- interdire le vocabulaire et le stockage de « scopes historiques ».

#### 7.10.2 — latestAuthRelevantVersion

Créer un consumer Event express indépendant, filtré sur les Events affectant member/creator :
max-upsert, hors ordre, idempotent, transactionnel, sans Task.

#### 7.10.3 — AUTH_HISTORY et AUTH_CURRENT

Créer une projection AUTH indépendante de READ_POT : historique des changements effectifs et état
current monotone. Une ancienne version traitée après une nouvelle ne doit jamais régresser current.
AUTH_HISTORY résout l'état applicable à V sans matérialiser artificiellement chaque business version.
La projection métier suit la voie Event→Task→executor générique ; le consumer express 7.10.2 ne
maintient que latestAuthRelevantVersion.

#### 7.10.4 — Freshness et décisions d'accès

- CURRENT : exiger `currentAuthVersion >= latestAuthRelevantVersion`, sinon `AUTH_NOT_READY` ;
- EXACT(V) : capacités courantes avec `VIEW_ARCHIVE` et droits métier effectifs à V ;
- masquer un refus établi sans révéler existence/readiness/failure de la projection métier.

Dépendances : contrats 7.9.1 ; moteur générique 7.3–7.5. Le raccordement final dépend de 7.9.3.

### 7.12 — Alignement complet de BALANCE sur le modèle générique

**Statut : `PARTIAL`**

Déjà livré : Event→Task, calcul complet à V, artifact immuable dédié, identité pipeline/Pot/version,
out-of-order et reader exact dans `runtime-web-api`.

Restant :

- faire de BALANCE un artifact générique du read store ;
- utiliser generic descriptor/status/failure/head/violation ;
- produire BALANCE pour toute business version applicable, même sans changement de montant ;
- conserver le calcul complet indépendant de V-1 ;
- ajouter l'index transverse requis par `balances/me` ;
- retirer la FK et la persistence Balance spécifiques du primaire après compatibilité/cutover.

Tests : 46 avant 45, duplicate identique, divergence sans overwrite, failure générique, trous permis,
version sans changement de balance néanmoins matérialisée, atomicité artifact/head/index.

Dépendances : 7.2/7.3/7.5 déjà suffisants. Ce lot peut progresser en parallèle de 7.9/7.10.

### 7.14 — Lifecycle des pipelineVersions et cutover manuel

**Statut : `NOT_STARTED`**

#### 7.14.1 — Modèle declared/active/serving

- distinguer définition déclarée, génération activée et génération serving ;
- garantir une seule version serving par famille ;
- rendre la sélection serving explicite et consommable par le Query Kernel ;
- ne modifier aucune règle producer `appliesTo`.

#### 7.14.2 — Éligibilité au serving

Calculer `eligibleForServing` et ses raisons depuis les états existants : structural readiness,
historical catchup complet, aucun trou connu, aucune failure non résolue. Ne jamais inférer cette
éligibilité d'un head seul.

#### 7.14.3 — Cutover/rollback

Le changement serving est manuel. Documenter et tester préflight, changement explicite, observation
et rollback vers une génération conservée. Aucun état `eligible` ne déclenche automatiquement le
cutover.

Dépendances : 7.5 pour la convergence ; 7.15 pour l'exposition complète des raisons. Un modèle serving
minimal 7.14.1 est requis avant l'assemblage final 7.9.3.

### 7.15 — Observabilité fonctionnelle et opérationnelle

**Statut : `PARTIAL`**

Déjà livré : métriques de consumption générique, instrumentation latest-known et métriques Balance
legacy.

Restant :

- declared/active/serving, applicabilité, head-max, backlog, holes, failures et eligibleForServing ;
- latest-known, latest-auth-relevant, current-auth et meilleure version READY ;
- latences Event→pickup, Event→Task et Task→completion ;
- requestedVersion, servedVersion, NOT_READY, PROJECTION_FAILED et AUTH_NOT_READY ;
- raisons structurées d'inéligibilité, sans cardinalité Pot incontrôlée.

Une distance latest-known/head peut être exposée comme signal signé. Elle ne doit pas être appelée
preuve de retard continu ou de readiness.

## 6. Lots d'intégration restants

### 7.11 — Migration des GET Pot, Expense et sous-objets

**Statut : `NOT_STARTED` — lot d'intégration**

Pour chaque endpoint retenu :

```text
HTTP -> Query Kernel -> Authorization Kernel
     -> CURRENT | EXACT(V) -> READ_POT reader
     -> VersionedQueryResponse
```

Expense et Shareholder sont lus dans READ_POT(servedVersion), sans projection autonome. La route
globale Expense legacy n'est pas reproduite comme capacité cible ; les sous-ressources sont adressées
sous leur Pot.

La liste `/pots` lit exclusivement l'index user→Pot convergent. Elle accepte insertions/suppressions
retardées et évolution entre pages, sans snapshot global V1 et sans join latest-known/primary.

Critères : aucun SELECT primaire, OAuth2/TokenCapabilities, enveloppe canonique, current/exact,
keyset, contrats d'erreur communs et rollback de configuration sans fallback par requête.

Dépendances : 7.9.3, 7.10.4, 7.14.1 et achèvement de la partie permissions de 7.2.

### 7.13 — Migration des GET Balance

**Statut : `PARTIAL` — lot d'intégration**

Le reader immuable existe déjà, mais reste couplé au primaire et transforme l'absence exacte en erreur
technique.

Cible :

```text
HTTP -> Query Kernel -> Authorization Kernel
     -> CURRENT | EXACT(V) -> BALANCE reader
     -> VersionedQueryResponse
```

CURRENT choisit la meilleure BALANCE READY autorisable ; EXACT(V) exige BALANCE(V). Aucun endpoint Pot
distinct n'impose la même servedVersion. `balances/me` utilise un index transverse et ne boucle plus
sur une liste primaire de Pots.

Décision API encore requise : conserver ou non le paramètre de version de `balances/me`. Aucune
rupture ne doit être introduite implicitement.

Dépendances : 7.9.3, 7.10.4, 7.12 et 7.14.1.

### 7.16 — Extinction contrôlée du legacy

**Statut : `NOT_STARTED` — lot d'intégration/exploitation final**

Préconditions : tous les readers cibles utilisent Query/Authorization Kernel, les générations cibles
sont serving, aucun endpoint ne lit le primaire, aucun worker legacy n'influence une réponse et
l'observabilité confirme l'absence de dépendance.

Séquence :

```text
legacy inactive mais présent
-> période d'observation et rollback possible
-> suppression physique explicite
```

Le nettoyage vise notamment `engine-query` legacy, les adapters primaires de GET, le calcul Balance
incrémental, `pot_balance_*`, les anciens workers/configurations et les headers GET libres.

## 7. Dépendances et ordre logique

```text
                        +--> 7.12 BALANCE générique -------------------+
                        |                                               |
7.9.1 contrats -------->+--> 7.9.2 CURRENT/EXACT ----+                  |
                        |                             |                  |
                        +--> 7.10 AUTH ---------------+--> 7.9.3 -------+
                                                      ^                 |
7.5 convergence --> 7.14.1 serving ------------------+                 |
7.14.2 éligibilité <----------------------- 7.15 signaux               |
                                                                        |
7.2 permissions + 7.9.3 + 7.10 + 7.14.1 --> 7.11 GET Pot/Expense      |
7.9.3 + 7.10 + 7.12 + 7.14.1 -----------> 7.13 GET Balance <----------+
7.11 + 7.13 + 7.14 + 7.15 + observation -> 7.16 extinction
```

- 7.9, 7.10 et 7.14 restent architecturaux.
- 7.11 et 7.13 sont des lots d'intégration.
- 7.12 est principalement une migration vers les mécanismes génériques existants.
- 7.8 ne porte plus aucun mécanisme autonome.
- 7.15 progresse avec chaque composant, puis finalise l'éligibilité de 7.14.

## 8. Scénarios d'acceptation transverses

1. Projection 46 terminée avant 45, head final 46 et artifacts 45/46 exacts.
2. Latest-known en retard ou en avance n'empêche ni production ni lecture CURRENT prête.
3. CURRENT sert la plus grande intersection READY, calculée sans head.
4. EXACT(V) ne sert aucune version différente.
5. Deux endpoints indépendants peuvent retourner deux servedVersions différentes.
6. Une réponse composée utilise une business version unique pour tous ses composants.
7. Une failure plus récente ne masque pas une version READY antérieure en CURRENT.
8. Une liste provient uniquement de son index convergent et peut évoluer entre pages.
9. `requestedVersion`, `servedVersion`, latest-known et generatedAt respectent leur sémantique.
10. Aucun champ `stale` n'est exposé.
11. Aucun scope de token n'est historisé ; member/creator le sont.
12. AUTH current insuffisamment fraîche retourne `AUTH_NOT_READY` sans fuite métier.
13. EXACT historique exige la capacité actuelle VIEW_ARCHIVE et les droits métier à V.
14. AUTH et READ_POT peuvent progresser dans n'importe quel ordre.
15. Une nouvelle pipelineVersion redécouvre son historique sans rouvrir l'ancienne génération.
16. Un head maximal avec trou/failure n'autorise pas serving.
17. `eligibleForServing=true` ne déclenche aucun cutover.
18. Les GET cibles fonctionnent sans permission SQL sur le primaire.
19. BALANCE(V) ne dépend jamais de BALANCE(V-1).
20. Après observation, le legacy peut être désactivé puis supprimé sans modifier les réponses.

## 9. Décisions encore ouvertes

Les décisions suivantes ne sont pas fermées par l'architecture présente :

- forme HTTP exacte des erreurs `NOT_READY`, `PROJECTION_FAILED` et `AUTH_NOT_READY` ;
- stockage/code/configuration exacts des états declared/active/serving ;
- liste précise des Event types auth-relevant ;
- représentation physique de AUTH_HISTORY/AUTH_CURRENT ;
- contrat versionné ou current-only de `GET /pots/balances/me` ;
- politique de rétention des anciennes générations après expiration du rollback ;
- budgets de cardinalité et seuils d'alerting de l'observabilité.

Ces choix doivent être fermés dans le sous-lot propriétaire, sans modifier les invariants des sections
2 et 8.

## 10. Critères de fin du Lot 7

Le Lot 7 est terminé lorsque :

- READ_POT, AUTH et BALANCE sont des pipelines indépendants, génériques et hors ordre ;
- CURRENT/EXACT et l'enveloppe versionnée sont utilisés par tous les GET cibles ;
- les listes proviennent uniquement d'indexes read-side convergents ;
- aucune query cible ne lit le primaire ;
- AUTH combine capacités courantes et faits métier versionnés sans fuite ;
- lifecycle pipeline, éligibilité et cutover manuel sont opérationnels ;
- toute reconstruction utilise une nouvelle pipelineVersion ;
- l'observabilité expose les états canoniques ;
- les chemins legacy sont désactivés, observés puis retirés.

## NEXT IMPLEMENTATION STEP

### Lot / sous-lot exact

**7.9.1 — Contrats de query versionnée**

### Pourquoi c'est le prochain

Le code possède déjà artifacts exacts, status dérivé, heads, READ_POT, latest-known et index shadow.
Il ne possède aucun contrat commun exprimant `CURRENT`, `EXACT(V)`, une vue composée ou l'enveloppe
versionnée. Ces contrats sont nécessaires à 7.9.2, à AUTH, aux états serving et aux deux lots
d'intégration, tout en pouvant être implémentés sans décision de persistence AUTH ou de cutover.

7.8 n'est pas le prochain lot : son objectif est déjà absorbé par la redécouverte native des nouvelles
pipelineVersions. 7.12 peut avancer en parallèle, mais ne débloque pas le chemin commun des GET.

### Pré-requis déjà satisfaits

- identité et applicabilité génériques ;
- statut exact READY/FAILED/NOT_READY ;
- artifacts immuables et head monotone ;
- READ_POT complet et index versionné ;
- latest-known indépendant ;
- architecture CURRENT/EXACT et enveloppe désormais canonique.

### Travail restant concret

- définir les types framework-free d'intention et d'enveloppe ;
- définir composant logique et déclaration statique d'une vue ;
- définir le port read-only d'énumération/résolution des identités READY exactes ;
- fixer les invariants de validation et les tests unitaires ;
- ne brancher encore ni controller, ni primary reader, ni policy AUTH.

### Docs canoniques à utiliser comme sources

- [Architecture cible du read side](../architecture/read-side-target.md), sections 5 à 10 ;
- [État actuel du read side](../architecture/read-side-current-state.md), sections 5, 6 et 10 ;
- le présent plan, sections 2 et 5.
