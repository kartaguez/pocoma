# TBD-LKV — audit de la matérialisation convergente

**Baseline auditée :** `504ce3bf1c8e60f8c2e1ec248556102da0192431` (`v2-make-it-pull`).

**Décision recommandée :** `TBD-LKV: RESOLVED AS A SPECIALIZED C2 CONVERGENT CURRENT-STATE INDEX`.

**Nature du passage :** décision documentaire avant WP6 ; aucun code de production, POM, SQL ou migration n'est modifié.

## 1. Conclusion

L'hypothèse principale est confirmée au niveau architectural : `CURRENT_BINDING` et LKV appartiennent à la même famille de **matérialisations d'index d'état courant convergentes**. Elles consomment des entrées versionnées indépendamment, gardent une seule valeur mutable par clé, acceptent un saut de version et convergent par maximum sans exiger l'ordre d'arrivée.

La classification retenue est **H2 — même pattern, implémentations spécialisées**. Pocoma possède déjà l'orchestration générique utile dans `engine-consumption`, `orchestrator-consumption` et `orchestrator-poll-consumption`. Un nouveau moteur générique `apply-if-newer` ne protégerait pas l'invariant décisif : le compare-and-set atomique doit rester dans chaque write port et son store. En outre, `CURRENT_BINDING` compare un payload complet à révision égale alors que LKV ne stocke que `(PotId, PotVersion)`. H3/H4 ajouteraient une abstraction sans invariant supplémentaire à protéger.

La topologie minimale est donc trois POM LKV spécialisés :

```text
runtime-latest-known-version-consumption-worker
  -> supra-consume-lkv
       -> engine-materialize-latest-known-version
  -> orchestrator-poll-consumption / orchestrator-consumption / engine-consumption
  -> infra-persistence-primary-jpa     (Event discovery + reload + Consumption)
  -> infra-persistence-read-jdbc       (LKV max-upsert)
```

Le runtime reste indépendant du runtime Event→ProjectionTask. Il observe la même source Event sous une autre identité de consumer et possède ses claims, retries, positions éphémères et vitesse de convergence.

## 2. Définition exacte de LKV

Pour un Pot `P`, l'état matérialisé est :

```text
LKV[P] = max(version(e))
         pour les Events e de P dont l'effet LKV a été validé avec succès
         par le pipeline LKV
```

La formulation opérationnelle finale est :

> For each Pot P, LKV[P] eventually converges to the maximum Pot source version durably available to, rediscovered by, and successfully consumed by the LKV pipeline, provided the worker continues and no terminal source defect remains unrepaired.

« Observed » signifie ici **rechargé depuis l'Event durable puis validé dans la transaction fenced du consumer LKV**. La simple découverte d'un candidat ne suffit pas. Un effet rollbacké après perte de claim n'entre pas dans le maximum réussi.

LKV n'est pas :

- la dernière projection matérialisée ;
- la dernière `ProjectionTask` terminée ;
- la dernière version lisible par un use case HTTP ;
- la version courante reconstruite depuis PRIMARY ;
- la dernière version traitée par un autre consumer Event.

Le code de production actuel ne contient aucun lecteur métier ou HTTP de LKV. Le seul contrat aval actuel est le watermark durable lui-même, ses métriques, ses tests et son exploitation. Cela n'altère pas sa sémantique ; cela signifie que le TARGET doit créer le write side spécialisé et ne doit pas inventer un read engine sans consommateur.

## 3. Source suffisante

`Event(PotId, PotVersion)` est l'information suffisante. `LatestKnownVersionConsumptionLocator` recharge l'Event durable par `eventId`, puis transmet exactement `event.potId()` et `event.version()` à `advanceToAtLeast`. Le type et le payload métier de l'Event ne participent pas à l'état LKV.

Lire PRIMARY pour recalculer la tête Pot serait redondant et changerait la définition : on obtiendrait une tête PRIMARY au moment de la lecture, au lieu du maximum effectivement observé par ce pipeline. La source de vérité de l'entrée est l'Event durable ; la source de vérité de l'état courant LKV est `source_version_watermarks`.

## 4. Pipeline CURRENT_BINDING de bout en bout

### Authority et production du fact

`JpaExternalIdentityBindingAdapter` crée le stream si nécessaire, verrouille `(issuer, subject)`, lit sa révision, alloue `R+1`, modifie l'autorité Binding, avance le stream et append un fact immutable dans la même transaction. Le verrou par identité produit des révisions contiguës. Le fact contient :

- clé : `ExternalIdentity(issuer, subject)` ;
- révision : `BindingRevision` ;
- payload complet : `ATTACHED(userId, bindingId)` ou `DETACHED` ;
- identité de source : `eventId` ;
- date autoritative du fact.

### Discovery, claim et exécution

`JdbcBindingFactDiscoveryAdapter` cherche les facts dont le slot `CURRENT_BINDING_PROJECTOR` est absent ou redevient éligible. Le scan est segmenté par identité et ordonné par `(issuer, subject, revision)`. Son curseur est local à l'invocation. `BindingFactConsumptionLocator` produit une `ConsumptionKey` par `eventId`; l'acquisition générique crée un claim loué et fenced, puis l'exécution recharge le fact immutable par `eventId`.

`MaterializeCurrentBindingService` transforme le fact en snapshot complet `CurrentBinding`. `JdbcCurrentBindingAdapter` effectue un upsert atomique dans `current_external_identity_binding` :

- `incoming R > stored R` → `APPLIED` ;
- `incoming R < stored R` → `STALE` ;
- même R et même statut/user/binding/source event → `DUPLICATE` ;
- même R avec payload ou source différents → invariant terminal.

`projectedAt` n'appartient pas à l'identité du payload.

### Transaction, retry, restart et workers

L'apply READ, la provenance Consumption et le CAS terminal du claim sont dans la transaction de `TransactionalExecuteConsumptionUseCase`. Une perte de claim fait rollbacker l'effet. Une panne technique laisse le slot retryable ; un invariant ou un fact absent devient terminal selon la policy Binding. Après restart, un nouveau scan repart sans curseur durable et retrouve les slots absents/pending. Plusieurs workers peuvent exécuter des révisions différentes ; le max-upsert interdit toute régression. L'audit C2 antérieur reste la référence détaillée : [Modularity_Post_WP4_Current_Binding_Revision_Audit.md](Modularity_Post_WP4_Current_Binding_Revision_Audit.md).

## 5. Pipeline LKV actuel de bout en bout

### Source et discovery

La source est `business_event_outbox`. `JpaLatestKnownVersionEventDiscoveryRepository` joint les slots avec l'identité de consumer `SOURCE_VERSION_WATERMARK`, retient les slots absents ou pending/éligibles, puis ordonne par `(event.version, created_at, event.id)`. `JpaLatestKnownVersionEventDiscoveryAdapter` filtre les pages par segment de `PotId`. Le curseur `EventOrderingKey` est local au scan.

Cette discovery est indépendante de l'ancien statut global de traitement Event. `JpaEventPort` le dit explicitement et recharge l'Event par son ID. Chaque Event possède une `ConsumptionKey(EVENT, eventId; SOURCE_VERSION_WATERMARK)`, distincte de toute clé du pipeline Event→ProjectionTask.

### Claim, exécution et matérialisation

Le claim, la lease, le fencing, la provenance, le CAS terminal et le retry sont ceux de `engine-consumption`. Après acquisition, le locator recharge l'Event durable et appelle :

```text
advanceToAtLeast(potId, event.version, observedAt)
```

`JdbcLatestKnownVersionAdapter` fait un upsert atomique dans `pocoma_read.source_version_watermarks` : l'insert crée la ligne ; l'update n'est exécuté que si `incoming > stored`. Une version égale ou inférieure retourne `Unchanged`. `advanced_at` ne change que lors d'une avance réelle.

L'effet LKV, la provenance et le CAS terminal sont exécutés dans la transaction générique. Une perte de claim rollbacke les trois. Les pannes techniques sont retryées ; un Event durable absent au reload est terminal. Après restart, aucun curseur persistant ne masque un slot non terminé. Plusieurs workers peuvent traiter des Events du même Pot ; PostgreSQL sérialise le conflit de ligne et la clause `where excluded.latest_version_seen > current_watermark.latest_version_seen` conserve le maximum.

### Modules CURRENT impliqués

| Responsabilité | Hébergement CURRENT |
| --- | --- |
| types, use case et write port LKV | `engine-processing-event` |
| discovery Event LKV | `infra-persistence-primary-jpa` |
| candidate/reload/issue/failure LKV | `locator-consumption-latest-known-version` |
| max-upsert et migration READ | `infra-read-persistence` |
| claim/fencing/provenance/retry | `engine-consumption` |
| boucle candidate→claim→execute | `orchestrator-consumption` |
| polling | `orchestrator-poll-consumption` |
| composition et métriques | `runtime-latest-known-version-consumption-worker` |

Les noms CURRENT mélangent encore LKV avec Event processing et le vieux store READ. Ils ne décrivent pas l'ownership TARGET.

## 6. Matrice des trois familles READ

| Propriété | Exact Projection (`AUTH@V`, `READ_POT@V`, `POT_BALANCES@V`) | `CURRENT_BINDING` | LKV |
| --- | --- | --- | --- |
| clé | `ProjectionKey(type, pipeline, object, V)` | `ExternalIdentity` | `PotId` |
| source | inputs autoritaires chargés pour la clé exacte | immutable Binding fact | durable Pot Event |
| version/révision | fait partie de l'identité historique | `BindingRevision` comparée | `PotVersion` comparée |
| mutable | non pour une clé exacte publiée | oui | oui |
| historique conservé ici | oui, artefact/failure par clé `@V` | non, une ligne courante ; facts ailleurs | non, une ligne maximum ; Events ailleurs |
| convergence | complétude par clés exactes | maximum R | maximum V |
| stale | autre clé historique, pas un overwrite stale | R inférieur ignoré | V inférieur inchangé |
| duplicate | publication de la même clé exacte idempotente selon le store | même R + même payload/source | même V → `Unchanged` |
| divergence same version | incohérence d'une même projection exacte | interdite et détectée | sans objet pour l'état scalaire `(P,V)` |
| ordre strict requis | calcul demandé pour V ; pas une règle de max | non | non |
| saut de version permis | chaque clé demandée reste distincte | oui | oui |
| `ProjectionTask` requis | oui | non | non |
| retry indépendant | par Task | par fact/consumer Binding | par Event/consumer LKV |
| worker indépendant pertinent | oui | oui | oui |
| valeur finale recherchée | artefact exact de chaque clé demandée | snapshot du fact de révision maximale | version source maximale observée avec succès |

Règle architecturale :

```text
exact historical artifact @V
  -> ProjectionTask pipeline

mutable keyed current/latest state, replacement by monotonic maximum
  -> convergent current-state index pipeline
  -> no ProjectionTask
```

Cette règle classe correctement `AUTH@V`/`READ_POT@V`/`POT_BALANCES@V` dans la première famille, et `CURRENT_BINDING`/LKV dans la seconde.

## 7. Invariants communs et différences

### Invariant commun C2

Les deux matérialisations sont **C2 — convergence vers le maximum** : elles acceptent directement `R/V → R/V+n`. Observer l'intermédiaire n'est pas requis. Un input plus ancien traité ensuite est stale/unchanged et ne rétablit jamais un état inférieur. Un saut peut omettre des états temporairement visibles ; il ne peut pas rendre faux l'état final si l'entrée maximale autoritative est finalement traitée avec succès.

Ce n'est pas C1, qui imposerait une continuité `n → n+1` à la matérialisation. Ce n'est pas C3, qui autoriserait une résolution non déterministe ou métier de versions concurrentes. L'ordre total numérique et l'opérateur `max` suffisent.

### Payload et même version

`CURRENT_BINDING` transporte un état complet et sa provenance. Deux facts à même clé/révision peuvent donc diverger ; l'adapter doit les comparer et échouer si nécessaire.

LKV transporte uniquement `PotId → PotVersion`. Deux Events ayant le même Pot et la même version induisent exactement la même valeur LKV, même si leurs IDs, types ou payloads diffèrent. Le test runtime crée d'ailleurs deux Events `V3` et les consomme séparément. Pour LKV, la seconde observation est `Unchanged`, sémantiquement un duplicate de valeur. Stocker artificiellement `eventId` ou comparer le payload introduirait un invariant étranger au besoin.

Cette différence confirme H2 : la policy de même version doit rester spécialisée. Elle ne justifie ni dupliquer le lifecycle Consumption, déjà générique, ni créer une fausse divergence LKV.

## 8. Scénarios obligatoires

| Scénario | État final LKV | Garantie | Différence avec `CURRENT_BINDING` |
| --- | ---: | --- | --- |
| ordered `V10,V11,V12` | 12 | chaque hausse passe le max-upsert | mêmes avances successives par révision |
| out of order `V10,V12,V11` | 12 | V12 avance ; V11 ne satisfait pas `incoming > stored` | Binding retourne explicitement `STALE` |
| duplicate `V12,V12` | 12 | second upsert `Unchanged`; slots distincts par Event | Binding exige même payload et même source event pour `DUPLICATE` |
| restart après V12 | 12 | slots DONE exclus ; vieux pending redécouverts puis inchangés | mécanisme identique, curseur éphémère |
| deux workers concurrents | max des versions commises avec succès | claim par Event + conflit SQL atomique par Pot | même max, payload Binding plus riche |
| late commit ancien après récent | version récente | condition du max-upsert évaluée au commit/lock de ligne | même protection par révision |
| gap `V10→V15` | 15 | aucune précondition V11–V14 | identique si le fact R15 est un snapshot complet |

Le gap LKV ne perd aucune donnée nécessaire à LKV : sa valeur ne contient que le maximum. Il ne dit rien sur l'existence, la réussite ou la lisibilité des projections V11–V14.

## 9. Indépendance des pipelines

Les états intermédiaires suivants sont légitimes :

- Event V10 visible, LKV=10, `ProjectionTask` retardée ;
- projection V10 produite, LKV encore à 9 ;
- LKV voit V12 avant V11 et passe directement à 12 ;
- le pipeline de projection a traité V12 alors que LKV n'a traité que V10.

Aucun invariant métier courant ne demande une synchronisation. LKV ne gate pas le producteur de Task, l'exécution de Task ou la lecture exacte. Les pipelines peuvent avoir leurs propres claims, retries et positions. Le fait qu'ils lisent le même Event n'est pas un motif de fusion.

Le worker LKV indépendant est donc pertinent. Il donne à cette matérialisation son rythme, son backoff, ses métriques, son identité de consumer et son arrêt/redémarrage indépendants. C'est la même justification que pour le worker Binding, pas une conséquence de la topologie Event existante.

## 10. Persistance et lecture

Les deux index sont aujourd'hui des tables mutables du schéma READ et utilisent Spring JDBC via le même `DataSource`/transaction manager de runtime. Les opérations sont des upserts conditionnels atomiques. Les sources et le lifecycle Consumption restent en PRIMARY. La transaction d'exécution englobe actuellement l'effet READ, la provenance et le terminal CAS parce que les schémas sont accessibles par cette même transaction.

`infra-persistence-read-jdbc` est donc le provider naturel des deux stores, sans en déduire un repository SQL générique :

- tables et clés différentes ;
- colonnes et payloads différents ;
- résultat `APPLIED/STALE/DUPLICATE/divergent` pour Binding contre `Advanced/Unchanged` pour LKV ;
- read port métier existant pour Binding, aucun read consumer courant pour LKV.

`engine-read-current-binding` est justifié par GET Pot et expose le snapshot courant. LKV n'a pas de symétrie de lecture actuellement. WP6 ne doit pas créer `engine-read-latest-known-version` tant qu'un use case aval réel n'existe pas. Le write port appartient au materializer LKV ; un futur read port appartiendrait au futur use case lecteur.

## 11. Generic engine, orchestration et storage

Déjà partagé :

- acquisition, claim, lease et fencing ;
- exécution transactionnelle ;
- provenance ;
- CAS terminal ;
- classification/policy de retry comme contrats ;
- boucle de scan et polling.

Spécialisé :

- source, candidate et reload ;
- clé/version/payload métier ;
- opération atomique du write port ;
- règle de même version ;
- failure policy propre ;
- store et read API.

La bonne factorisation est donc l'orchestration Consumption existante plus deux spécialisations de la famille. Aucun « Universal Processor » n'est proposé. Command, Registration, Results et ProjectionTask ne satisfont pas nécessairement l'invariant `versioned input → mutable keyed state → monotonic maximum replacement` et restent hors de cette famille.

## 12. Choix H1/H2/H3/H4

| Hypothèse | Décision | Motif |
| --- | --- | --- |
| H1 — ressemblance seulement | rejetée | C2, max atomique, stale, gaps, retry/restart et multi-worker sont réellement communs |
| H2 — même pattern, spécialisations | **retenue** | protège le vocabulaire et la règle de frontière tout en gardant les policies réelles |
| H3 — moteur générique commun | rejetée pour WP6 | déplacer la comparaison hors du store casserait l'atomicité ; la policy same-version diverge |
| H4 — moteur + orchestration communs | rejetée comme nouvelle abstraction | l'orchestration significative est déjà générique dans Consumption |

Le vocabulaire recommandé est **convergent current-state index** / **index convergent d'état courant**. « Current-state » couvre un snapshot Binding et un watermark scalaire ; « convergent » expose la propriété C2 ; « index » évite la confusion avec les artefacts historiques exacts. Dans les noms de modules, garder le nom métier explicite : `engine-materialize-current-binding` et `engine-materialize-latest-known-version`.

## 13. Décision sur les responsabilités TARGET

| Responsabilité provisoire | Décision | Cible |
| --- | --- | --- |
| `engine-advance-pot-watermark` | **RENAME** | `engine-materialize-latest-known-version`; engine spécialisé write-side LKV |
| `supra-consume-lkv` | **KEEP** | POM spécialisé candidate/reload/issue/failure, dépendant de l'orchestration générique et du materializer LKV |
| `runtime-latest-known-version-consumption-worker` | **KEEP** | racine indépendante de déploiement/composition/polling/métriques |

Pour CURRENT_BINDING :

| Responsabilité | Décision |
| --- | --- |
| `engine-materialize-current-binding` | KEEP ; spécialisation Binding de la famille conceptuelle |
| `engine-read-current-binding` | KEEP ; lecteur métier réellement consommé |
| `supra-consume-binding` | KEEP ; orchestration spécialisée des facts |
| `runtime-binding-consumption-worker` | KEEP ; worker indépendant |

La famille commune est conceptuelle. Elle n'impose ni fusion Maven des engines/supras/runtimes, ni table/repository générique.

## 14. Topologie cible minimale et relation ProjectionTask

```text
Generic family:
  convergent current-state index (C2 maximum convergence)

Binding specialization:
  BindingFact -> supra-consume-binding
              -> engine-materialize-current-binding
              -> CURRENT_BINDING
  read: engine-read-current-binding

LKV specialization:
  Event(PotId,V) -> supra-consume-lkv
                 -> engine-materialize-latest-known-version
                 -> LATEST_KNOWN_VERSION[PotId]
  read: none until an actual consumer exists

Shared:
  engine-consumption
  orchestrator-consumption
  orchestrator-poll-consumption
  infra-persistence-primary-jpa primitives
  infra-persistence-read-jdbc as technical provider

Not shared:
  source/reload contracts
  key/version/payload types
  same-version policy
  SQL repositories/tables
  failure classification details
  runtime identity/configuration/metrics

ProjectionTask relation:
  NONE
```

## 15. Impact prescrit pour WP6

WP6 devra, dans un lot LKV déclaré et vérifié :

- **CREATE** les POM `engine-materialize-latest-known-version` et `supra-consume-lkv` ;
- **RENAME/KEEP** l'identité physique du runtime LKV actuel comme `runtime-latest-known-version-consumption-worker` ;
- **REHOME** les types/use case/write port LKV depuis `engine-processing-event` vers le materializer ;
- **REHOME** le locator, classifier et policy depuis `locator-consumption-latest-known-version` vers le supra ;
- **REHOME** `JdbcLatestKnownVersionAdapter`, sa configuration et l'ownership de sa migration READ vers `infra-persistence-read-jdbc`, avec une preuve explicite de chemin Flyway avant tout mouvement de ressource ;
- **KEEP** discovery/reload Event et Consumption SQL dans `infra-persistence-primary-jpa` ;
- **DELETE** les POM legacy LKV devenus vides et retirer la partie LKV de `engine-processing-event`/`infra-read-persistence` après preuve ;
- **GUARD** l'absence de `ProjectionTask`, l'indépendance des consumer keys, le max atomique, le rollback sur claim perdu et l'absence de dépendance LKV vers un reader PRIMARY ;
- **DOCUMENT** la famille conceptuelle commune et l'absence de read engine LKV actuel.

WP6 ne doit pas créer de moteur générique de convergence, de repository générique, de lecteur LKV sans consommateur, ni fusionner les workers Binding/LKV/Event.

## 16. Vérification de cet audit

Périmètre déclaré : inspection documentaire et statique des slices BINDING, LKV, EVENT et PROJECTION. Impact production autorisé : aucun. Impact POM/SQL/migration autorisé : aucun. Gate global : non requis. Full reactor : non requis. Vérification DB : aucune.

Preuves lues : production authority/discovery/locator/materializer/store/runtime, orchestration Consumption, tests Postgres LKV et Binding existants, POM et documents CURRENT/TARGET. Les tests existants couvrent notamment LKV désordonné/duplicate/stale, max concurrent, retry, rollback et claim perdu ; Binding couvre C2, divergence, restart, late commit et multi-worker.

Commandes de vérification à la clôture : `git diff --check`, inventaire du diff et contrôle du working tree. Aucun Maven n'est nécessaire car ce passage reste strictement documentaire.

## 17. Statut

```text
TBD-LKV: RESOLVE AS SPECIALIZED C2 CONVERGENT CURRENT-STATE INDEX

H2 retained.
Independent Event consumer and worker retained.
No ProjectionTask.
No new generic engine or generic storage.
CURRENT_BINDING remains a separate physical specialization of the same family.
WP6 remains NOT STARTED.
```
