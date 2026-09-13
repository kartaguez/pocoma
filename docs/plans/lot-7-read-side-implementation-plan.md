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
6. `CURRENT` sert la meilleure businessVersion inférieure ou égale à latest-known où AUTH et tous
   les composants métier requis sont `READY`.
7. `EXACT(V)` exige latest-known présent et supérieur ou égal à V, AUTH(V) et tous les composants
   métier requis à V, sans fallback.
8. `latestKnownVersion` ne bloque jamais la production, mais borne explicitement toute exposition.
9. Une liste est un read model convergent ; elle ne relit ni le primaire ni latest-known pour être
   reconstruite à la volée.
10. Les scopes du token sont courants ; seuls les faits métier member/creator sont historisés.
11. AUTH(V) est une projection complète, exacte et indépendante à chaque businessVersion applicable.
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
| 7.10 Authorization Kernel/AUTH | `NOT_STARTED` | TokenCapabilities et artifact complet AUTH(V) à implémenter. |
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
ni un gate de production. Son état borne en revanche explicitement l'exposition des queries
versionnées.

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
- déclaration statique des composants métier requis par une query, AUTH étant toujours requis ;
- contrat de sélection injectée d'une pipelineVersion par famille ;
- contrat de lecture des businessVersions `READY` exactes dans les pipelineVersions fournies.

Tests : validation de V positif, fidélité de `requestedVersion`, distinction generatedAt/projection
time, un seul READ_POT malgré plusieurs tables et absence de dépendance HTTP/persistence dans les
contrats.

#### 7.9.2 — Résolution CURRENT et EXACT

Implémenter sans controller, avec une sélection de pipelineVersion fournie par l'appelant :

- latest-known absent → `NOT_READY` pour CURRENT comme EXACT ;
- `CURRENT = max V <= latestKnownVersion` dans l'intersection READY de AUTH et de tous les
  composants métier requis ;
- `EXACT(V)` exige V <= latest-known, AUTH(V) et tous les composants métier requis READY à V ;
- résolution depuis les artifacts exacts, jamais depuis les heads ;
- version `FAILED` plus récente n'occultant pas une version READY antérieure en CURRENT ;
- artifact interne au-delà de latest-known jamais exposé ;
- aucun fallback en EXACT ;
- aucune sélection implicite de pipelineVersion serving dans 7.9.2 ;
- deux endpoints autorisés à converger vers des servedVersions différentes.

Tests obligatoires :

- latest-known absent avec artifacts READY → `NOT_READY` ;
- latest-known 15, READ_POT READY `{13,14,15}`, AUTH READY `{13}` → CURRENT sert 13 ;
- latest-known 14, READ_POT/AUTH READY 15 → V15 non exposable ;
- composants métier READY `{12,13,15}` et AUTH READY `{11,13,14}` → CURRENT sert 13 ;
- head 15 avec trou à 14 ne prouve pas READY(14) ;
- EXACT(15) avec latest-known 14 → `NOT_READY`, même si tous les artifacts V15 existent ;
- EXACT(15) avec AUTH ou composant absent/NOT_READY/FAILED ne sert aucune autre version.

#### 7.9.3 — États fonctionnels et mapping HTTP commun

Après le pipeline et les policies AUTH du 7.10, assembler l'ordre sans fuite : TokenCapabilities,
recherche interne de la businessVersion commune, décision depuis AUTH(servedVersion), puis lecture.
Stabiliser `NOT_READY`, `PROJECTION_FAILED`, masquage des refus et enveloppes de succès. Aucun détail
de claim/Task/pipeline interne ne doit fuir.

Dépendances : 7.9.1 → 7.9.2 ; 7.9.3 dépend de 7.10 et du modèle serving minimal de 7.14.1.

### 7.10 — Authorization Kernel et projection AUTH

**Statut : `NOT_STARTED`**

#### 7.10.1 — Contrats d'autorisation

- séparer `TokenCapabilities` actuelles de `PotAuthorizationAtVersion` ;
- historiser seulement `isMember` et `isCreator` ;
- dériver les droits via les policies métier partagées ;
- ajouter les capacités `VIEW_ARCHIVE` nécessaires ;
- interdire le vocabulaire et le stockage de « scopes historiques ».

#### 7.10.2 — Pipeline AUTH applicable à toute businessVersion

- déclarer une famille AUTH et sa première pipelineVersion ;
- rendre tout BusinessEvent Pot pertinent afin de produire AUTH(V) pour chaque businessVersion
  applicable, même si member/creator ne change pas à V ;
- utiliser Event→Task→executor générique, sans consumer Event express AUTH ;
- conserver production et latest-known totalement indépendants.

#### 7.10.3 — Reconstruction et artifact AUTH(V)

- reconstruire exactement à V les faits complets `isMember` et `isCreator` ;
- calculer AUTH(V) sans lire AUTH(V-1) ;
- matérialiser un artifact générique, immuable, idempotent et adressé par identité complète ;
- accepter AUTH(15) avant AUTH(13) et faire avancer le head par maximum sans continuité ;
- ne créer aucun state current AUTH séparé.

#### 7.10.4 — Décisions d'accès à servedVersion

- CURRENT : capacités actuelles, puis droits métier depuis AUTH(servedVersion) ;
- EXACT(V) : capacité actuelle `VIEW_ARCHIVE` et droits métier depuis AUTH(V) ;
- masquer un refus établi sans révéler existence/readiness/failure de la projection métier ;
- ne jamais utiliser une version AUTH différente de la businessVersion servie.

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

- distinguer `declared` (définition connue), `active` (pipeline activé, capable de travailler et de
  converger sur sa plage d'applicabilité) et `serving` (pipelineVersion explicitement sélectionnée
  pour les queries) ;
- ne jamais interpréter `active` comme « convergence initiale terminée » ; cette preuve appartient à
  `eligibleForServing` et aux critères de cutover ;
- garantir une seule version serving par famille ;
- rendre la sélection de pipelineVersion serving explicite et injectable au Query Kernel ;
- laisser 7.9.2 résoudre uniquement la businessVersion dans les pipelineVersions fournies ;
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
- latest-known, meilleures businessVersions READY par pipeline et meilleure businessVersion commune
  servable pour une vue ;
- latences Event→pickup, Event→Task et Task→completion ;
- requestedVersion, servedVersion, NOT_READY et PROJECTION_FAILED ;
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

La liste `/pots` utilise exclusivement l'index user→Pot convergent pour découvrir des candidats. Cet
index n'est jamais une preuve d'autorisation : chaque Pot candidat est résolu en CURRENT dans sa
propre borne latest-known, puis filtré avec AUTH(servedVersion). Une entrée stale qui ne passe plus
l'autorisation n'est pas exposée.

La pagination parcourt le keyset de l'index jusqu'à obtenir autant que possible la taille demandée,
épuiser les candidats ou atteindre une limite technique bornée de type
`maxCandidatesScannedPerPage`. Le curseur pointe le dernier candidat d'index réellement examiné,
pas le dernier élément retourné. Une page partielle est valide lorsque la limite de scan est atteinte.
La liste accepte insertions/suppressions retardées et évolution entre pages, sans snapshot global ni
lecture du primaire.

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

CURRENT choisit la plus grande businessVersion V inférieure ou égale à latestKnownVersion pour
laquelle AUTH(V) et BALANCE(V) sont READY dans les pipelineVersions fournies. L'autorisation et la
balance sont lues à cette même V. EXACT(V) exige latest-known présent, V dans sa borne, AUTH(V) et
BALANCE(V) READY. Aucun endpoint Pot distinct n'impose la même servedVersion. `balances/me` utilise
un index transverse de découverte, puis applique cette résolution et ce filtrage par Pot ; il ne
boucle plus sur une liste primaire de Pots.

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
                        +--> 7.10 AUTH(V) ------------+--> 7.9.3 -------+
                                                      ^                 |
7.5 convergence --> 7.14.1 sélection pipelineVersion serving ---------+
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
2. Latest-known en retard ou en avance n'empêche aucune production de pipeline.
3. Sans latest-known, CURRENT et EXACT répondent NOT_READY.
4. CURRENT sert la plus grande V <= latestKnownVersion où AUTH(V) et tous les composants requis sont
   READY, calculée sans head.
5. EXACT(V) ne sert aucune version différente et répond NOT_READY si V > latestKnownVersion, même si
   un artifact interne à V existe déjà.
6. Deux endpoints indépendants peuvent retourner deux servedVersions différentes.
7. Une réponse composée utilise une business version unique pour AUTH et tous ses composants métier.
8. Une failure plus récente ne masque pas une version READY antérieure en CURRENT.
9. Une liste traite son index comme source de candidats, filtre chaque Pot via AUTH à sa
   servedVersion et peut évoluer entre pages.
10. Le curseur d'une liste filtrée pointe le dernier candidat examiné ; une limite de scan peut
    produire une page partielle sans rescanner les candidats filtrés.
11. `requestedVersion`, `servedVersion`, latest-known et generatedAt respectent leur sémantique ; une
    réponse réussie possède toujours latestKnownVersion.
12. Aucun champ `stale` n'est exposé.
13. Aucun scope de token n'est historisé ; member/creator le sont dans chaque AUTH(V) complet.
14. EXACT historique exige la capacité actuelle VIEW_ARCHIVE et les droits métier de AUTH(V).
15. AUTH(V), READ_POT(V) et BALANCE(V) peuvent progresser dans n'importe quel ordre et sans dépendre
    de leur version précédente.
16. Une nouvelle pipelineVersion redécouvre son historique sans rouvrir l'ancienne génération.
17. Un head maximal avec trou/failure n'autorise pas serving.
18. `active` ne prouve pas la convergence et `eligibleForServing=true` ne déclenche aucun cutover.
19. La sélection de pipelineVersion serving est fournie à 7.9.2, qui ne résout que la businessVersion.
20. Les GET cibles fonctionnent sans permission SQL sur le primaire.
21. Après observation, le legacy peut être désactivé puis supprimé sans modifier les réponses.

## 9. Décisions encore ouvertes

Les décisions suivantes ne sont pas fermées par l'architecture présente :

- forme HTTP exacte des erreurs `NOT_READY` et `PROJECTION_FAILED` ;
- stockage/code/configuration exacts des états declared/active/serving ;
- représentation physique de l'artifact complet AUTH(V) et de ses faits member/creator ;
- limite technique et signalisation exactes de `maxCandidatesScannedPerPage` ;
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
- ces indexes ne servent que la découverte et chaque élément exposé est autorisé à sa servedVersion ;
- aucune query cible ne lit le primaire ;
- AUTH combine capacités courantes et faits métier versionnés sans fuite ;
- lifecycle pipeline, éligibilité et cutover manuel sont opérationnels ;
- toute reconstruction utilise une nouvelle pipelineVersion ;
- l'observabilité expose les états canoniques ;
- les chemins legacy sont désactivés, observés puis retirés.

# NEXT IMPLEMENTATION STEP

## Lot / sous-lot exact

**7.9.1 — Contrats de query versionnée**

## Pourquoi

Le code possède déjà artifacts exacts, status dérivé, heads, READ_POT, latest-known et index shadow.
Il ne possède aucun contrat commun exprimant `CURRENT`, `EXACT(V)`, une vue composée incluant AUTH ou
l'enveloppe versionnée. Ces contrats sont nécessaires à 7.9.2, à AUTH, aux états serving et aux deux
lots d'intégration, tout en pouvant être implémentés sans décision de persistence AUTH ou de cutover.

7.8 n'est pas le prochain lot : son objectif est déjà absorbé par la redécouverte native des nouvelles
pipelineVersions. 7.12 peut avancer en parallèle, mais ne débloque pas le chemin commun des GET.

## Pré-requis déjà satisfaits

- identité et applicabilité génériques ;
- statut exact READY/FAILED/NOT_READY ;
- artifacts immuables et head monotone ;
- READ_POT complet et index versionné ;
- latest-known indépendant ;
- architecture CURRENT/EXACT et enveloppe désormais canonique.

## Décisions encore nécessaires avant implémentation

Aucune décision d'architecture supplémentaire ne bloque 7.9.1. Les noms Java exacts et la forme des
types sont des choix locaux au sous-lot, sous réserve de préserver la séparation entre
pipelineVersion fournie et businessVersion résolue. La représentation physique d'AUTH(V), la limite
de scan des listes et la forme HTTP des erreurs ne sont pas des prérequis de 7.9.1.

## Travail concret restant

- définir les types framework-free d'intention et d'enveloppe ;
- définir composant logique et déclaration statique d'une vue ;
- exprimer AUTH comme composant obligatoire de toute vue protégée, à la même businessVersion que les
  composants métier ;
- porter la sélection de pipelineVersion par famille comme donnée fournie au résolveur, sans la
  choisir dans 7.9.2 ;
- définir le port read-only d'énumération/résolution des identités READY exactes ;
- fixer les invariants de validation et les tests unitaires ;
- ne brancher encore ni controller, ni primary reader, ni policy AUTH.

## Docs canoniques à utiliser

- [Architecture cible du read side](../architecture/read-side-target.md), sections 5 à 10 ;
- [État actuel du read side](../architecture/read-side-current-state.md), sections 5, 6 et 10 ;
- le présent plan, sections 2 et 5.
