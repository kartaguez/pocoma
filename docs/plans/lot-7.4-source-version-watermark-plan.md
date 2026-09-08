# Pocoma — Lot 7.4 — SourceVersionWatermark express

## 1. Objectif et définition du done

Le Lot 7.4 introduit un consumer Event express et indépendant qui alimente la connaissance read-side
de la plus haute version source observée pour chaque Pot :

```text
BusinessEvent
  -> potId + potVersion
  -> latestVersionSeen = max(stored, observed)
```

Le lot est terminé lorsque ce consumer peut être déployé séparément, consommer les Events avec le
lifecycle générique, mettre à jour le watermark de manière atomique et monotone dans le read store,
et reprendre sans régression après duplication, désordre, concurrence ou crash.

Ce consumer ne crée aucune Task, ne reconstruit jamais le Pot, ne calcule aucun snapshot, ne produit
aucun artifact et ne modifie aucun `ProjectionHead`. Il n'implémente ni Query Kernel ni GET.

## 2. Sources canoniques consultées

Le plan est subordonné aux documents suivants :

- [`read-side-target.md`](../architecture/read-side-target.md) ;
- [`read-side-current-state.md`](../architecture/read-side-current-state.md) ;
- [`write-side-closure.md`](../architecture/write-side-closure.md) ;
- [`lot-7-read-side-implementation-plan.md`](lot-7-read-side-implementation-plan.md) ;
- [`docs/README.md`](../README.md) ;
- [`consumption-event-pull-runtime.md`](../architecture/consumption-event-pull-runtime.md) ;
- [`module-dependency-matrix.md`](../architecture/module-dependency-matrix.md).

Aucune contradiction canonique n'a été identifiée. La colocalisation transactionnelle observée est
une propriété de l'implémentation actuelle, pas un invariant abstrait de la cible.

## 3. État actuel vérifié dans le code

### Données Event disponibles

`domain-pot/.../event/BusinessEvent.java` expose directement :

```java
PotId potId();
long version();
```

Tous les Events Pot actuellement supportés implémentent ce contrat et valident une version positive.
`RecordedEvent` ajoute l'identité durable `eventId`, `recordedAt` et la trace sans masquer l'Event
typé. `JpaEventPort` recharge le `RecordedEvent` après acquisition depuis
`business_event_outbox`. `BusinessEventRecordMapper` vérifie la cohérence entre l'envelope durable et
le payload avant de reconstruire l'Event typé.

Il n'est donc nécessaire ni de reconstruire le Pot, ni de créer un extracteur par type d'Event. Un
Event absent, inconnu ou incohérent doit suivre le traitement d'erreur technique du consumer et ne
doit produire aucun watermark.

### Runtime de consommation réutilisable

Le chemin Event existant réutilisable est :

```text
discovery best effort
  -> ConsumptionKey
  -> AcquireConsumption
  -> callback sous TransactionalExecuteConsumptionUseCase
  -> provenance
  -> CAS terminal protégé par currentClaimId
```

`SequentialConsumptionOrchestrator`, `ConsumptionPollingWorker`, les slots, claims, leases, retries et
le fencing sont génériques. En revanche, `EventConsumptionLocator` et
`EventConsumptionDiscoveryPort` sont actuellement orientés Event→Task et `PipelineDefinition`. Le
consumer watermark ne doit ni détourner ce chemin, ni inventer un faux pipeline.

`ConsumptionExecutionResult` impose un résultat d'exécution non nul mais autorise des listes de
`ConsumptionInput` et `ConsumptionResult` vides. Le test existant d'une transformation Event créant
zéro Task confirme qu'aucun `ConsumptionResult` artificiel n'est requis par le moteur actuel.

### Ressources transactionnelles observées

`TransactionalExecuteConsumptionUseCase` ouvre la transaction autour du callback métier, de la
provenance et du CAS terminal. Les adapters inspectés reposent actuellement sur les mêmes ressources
transactionnelles Spring, sous réserve de confirmer l'enlistment du chemin effectif du nouveau
runtime watermark. `infra-read-persistence` utilise aujourd'hui le `DataSource` et le
`PlatformTransactionManager` fournis par l'application, tout en qualifiant ses accès et son schéma.

Cette observation ne prouve pas à elle seule l'atomicité du futur wiring. Celle-ci devra être vérifiée
dans le contexte Spring réel du nouveau runtime et par un test de rollback commun.

## 4. Invariants verrouillés

- L'identité fonctionnelle du watermark est `potId`.
- Son état fonctionnel est uniquement `potId + latestVersionSeen`.
- La première observation crée le watermark même si sa version est supérieure à 1.
- Une observation supérieure avance le watermark ; une observation égale ou inférieure ne le fait pas.
- Les duplications, le désordre et la concurrence ne peuvent faire régresser la valeur.
- Le producteur runtime normal est exclusivement le consumer express Event.
- GET, Tasks, projectors, artifacts, failures et heads ne mettent jamais à jour le watermark.
- Le consumer ne consulte ni `PipelineVersionDefinition`, ni `PipelineSelectionStrategy`, ni le read
  model de projection, ni le lifecycle des Tasks.
- `latestVersionSeen` ne conditionne jamais création, acquisition ou exécution d'une Task, ni la
  matérialisation d'un artifact.
- Un artifact N et un head au moins égal à N peuvent exister avec `latestVersionSeen=N-1` ; N reste
  alors inconnue du futur reader.

## 5. Architecture cible du consumer express

La séparation attendue est :

```text
domain/read-side
  -> SourceVersionWatermark(potId, latestVersionSeen)

engine/use case
  -> observation intentionnelle d'une version source

infra-read-persistence
  -> max-upsert PostgreSQL

locator Event spécialisé
  -> relecture autoritative + extraction potId/version + invocation du use case

runtime/composition root indépendant
  -> discovery + claim + transaction + polling + configuration
```

Le modèle et le service restent framework-free. Le locator ne contient ni SQL ni logique de
projection. L'adapter JDBC ne connaît ni Event, ni claim, ni pipeline.

## 6. Décisions concrètes par module et couche

### Domaine et engine read-side

Ajouter le contrat fonctionnel minimal `SourceVersionWatermark` dans la frontière read-side déjà
portée par `domain-projection`. Il contient seulement `PotId potId` et `long latestVersionSeen`, avec
validation de nullité et de positivité.

Ajouter dans `engine-read-projection` :

- un use case intentionnel d'observation, nommé selon les conventions retenues lors de l'exécution,
  par exemple `observeVersion`, `advanceToAtLeast` ou `recordSeenVersion` ;
- un port sortant exprimant cette même intention, et non un CRUD générique `save` ;
- un résultat distinguant au minimum `Advanced` et `Unchanged`, afin d'alimenter l'observabilité sans
  read-then-write applicatif.

Le service reçoit `potId`, `potVersion` et, si la métadonnée technique est retenue, l'instant fourni
par un `Clock`. Il ne reçoit ni Event complet, ni pipeline, ni Task.

### Discovery et locator

Le choix de forme du discovery sera effectué pendant l'implémentation après comparaison ciblée des
contrats existants. Les options admises sont un port spécialisé minimal, une petite généralisation du
discovery actuel ou la réutilisation d'une abstraction générique existante si elle respecte réellement
le besoin.

Le choix doit satisfaire simultanément :

- aucune dépendance à `PipelineDefinition` ou à la stratégie Event→Task ;
- aucune fausse identité pipeline ;
- séparation explicite des consumer identities ;
- pas de duplication inutile de la pagination, de l'ordre ou de l'éligibilité SQL ;
- pas de généralisation dépassant les deux consumers réellement connus.

`engine-processing-event` est à inspecter et ne sera modifié que si les contrats retenus l'exigent.
Le Lot 7.4 ne doit pas modifier le moteur métier Event→Task pour y intégrer le watermark.

Le locator watermark doit recharger l'Event autoritatif via `EventPort`, utiliser
`event().potId()` et `event().version()`, invoquer le use case puis retourner un succès technique.
L'Event lu doit être déclaré comme `ConsumptionInput`. Il n'existe aucun résultat métier fonctionnel.
Avec le contrat actuel, la liste de `ConsumptionResult` peut rester vide. Si le wiring réellement
retenu impose un résultat technique, celui-ci devra décrire honnêtement l'observation effectuée sans
inventer un artifact ou un état métier supplémentaire.

### Runtime

Créer un composition root déployable indépendamment du runtime Event→Task, avec une configuration
propre, désactivée par défaut. Il réutilise l'orchestrateur, le polling, le lifecycle et les policies
techniques existants sans embarquer de stratégie de création de Tasks.

## 7. Modèle et persistance du watermark

La structure SQL cible est :

```text
pocoma_read.source_version_watermarks
  pot_id                 uuid primary key
  latest_version_seen    bigint not null check (latest_version_seen >= 1)
  advanced_at            timestamptz not null   -- métadonnée technique
```

`advanced_at` appartient uniquement à la persistence et au diagnostic. Il n'est pas exposé par
`SourceVersionWatermark` et ne participe à aucune décision applicative.

Sa sémantique est stricte :

```text
observedVersion > current
  -> latestVersionSeen = observedVersion
  -> advanced_at = observation time

observedVersion == current ou observedVersion < current
  -> aucune mutation fonctionnelle
  -> advanced_at inchangé
```

La première observation initialise les deux colonnes, sans exiger `potVersion=1`. Aucun `eventId`
n'est stocké et aucune FK ne relie le read store au primaire.

L'adapter doit effectuer une mutation atomique équivalente à :

```sql
insert into source_version_watermarks (...)
values (...)
on conflict (pot_id) do update
set latest_version_seen = excluded.latest_version_seen,
    advanced_at = excluded.advanced_at
where excluded.latest_version_seen > source_version_watermarks.latest_version_seen;
```

La correctness ne doit reposer sur aucun read-then-write. Un `RETURNING` ou une lecture technique
postérieure peut servir à qualifier `Advanced`/`Unchanged`, mais ne doit pas décider de la mutation.

## 8. Intégration au runtime Event

L'identité logique de consommation est :

```text
consumable = EVENT[eventId]
consumer   = SOURCE_VERSION_WATERMARK[]
```

Une variante strictement équivalente conforme aux conventions de `ConsumerIdentity` est acceptable,
mais elle ne doit contenir ni `pipelineId` ni `pipelineVersion`.

Le consumer possède son propre slot pour chaque Event, indépendant de tout slot Event→Task. Plusieurs
instances peuvent partager cette identité et utiliser les claims/leases/fencing existants. La
segmentation doit rester déterministe par Pot, par exemple via `PartitionHash.forPot(potId)`, sans
supposer que les Events arrivent dans l'ordre.

La discovery reste best effort. Après acquisition, seul le rechargement par `eventId` fait autorité.
Une disparition ou une incohérence de l'Event suit la classification technique existante ou son
adaptation minimale ; elle ne doit jamais être convertie en observation partielle.

## 9. Transaction, crash et concurrence

### Gate transactionnel

L'inspection d'implémentation doit confirmer précisément :

- le `PlatformTransactionManager` utilisé par `TransactionalExecuteConsumptionUseCase` ;
- le `DataSource` de l'Event store, du lifecycle et de la provenance ;
- le `DataSource` réellement utilisé par l'adapter watermark qualifié read-store ;
- la participation effective des quatre écritures/lectures au même contexte transactionnel Spring et
  à la même transaction PostgreSQL.

Si cette propriété est confirmée, l'unité atomique est :

```text
relecture Event
  -> max-upsert watermark
  -> provenance
  -> CAS terminal du slot
  -> commit unique
```

Un test d'intégration doit écrire le watermark puis provoquer une erreur avant la terminalisation et
prouver que watermark, provenance et terminalisation sont tous rollbackés.

Si l'enlistment transactionnel attendu n'est pas obtenu avec le wiring réel, l'implémentation doit
s'arrêter avant validation du lot et le plan doit être amendé pour définir explicitement le protocole
idempotent nécessaire. Ce cas est un gate de conception et de validation, pas la preuve que le Lot 7.4
est impossible. Le protocole alternatif n'est pas conçu dans le présent plan.

### Crash et retry si la transaction commune est prouvée

- Crash avant l'upsert : aucune mutation, reprise par claim/retry.
- Erreur après l'upsert mais avant le CAS : rollback commun, puis reprise.
- Crash après commit : watermark, provenance et slot terminal sont déjà durables ; le slot empêche une
  nouvelle exécution logique.
- Perte de claim : le CAS échoue et la transaction incluant l'upsert doit rollbacker.

### Concurrence

Le slot/fencing arbitre deux workers sur le même Event. Pour deux Events distincts du même Pot, la
correctness vient du max-upsert PostgreSQL : que 42 ou 45 committe en premier, le résultat final vaut
45. Des duplications simultanées de 45 restent idempotentes et ne modifient pas `advanced_at` après la
première avance effectivement committée.

## 10. Migration et compatibilité

Ajouter pendant l'implémentation une migration forward-only au cycle Flyway du read store. Au vu des
migrations existantes V1 à V3, le nom attendu est
`V4__add_source_version_watermarks.sql`, sous réserve de vérifier une dernière fois la séquence au
moment de l'exécution.

La migration crée uniquement la table et ses contraintes dans `pocoma_read`. Elle ne crée aucune FK,
vue ou dépendance vers le primaire.

Le déploiement recommandé est :

1. déployer/exécuter la migration avec le consumer désactivé ;
2. vérifier la table et les permissions ;
3. activer le runtime watermark ;
4. observer progression et erreurs.

Si Flyway est désactivé ou la migration absente, le consumer ne doit pas être activé. Le rollback
applicatif consiste à arrêter ou revenir au runtime précédent en conservant la table et ses données ;
aucune down migration destructive n'est prévue.

## 11. Observabilité

Prévoir des compteurs bornés, sans tag `potId` ni `eventId` :

- observations `advanced` ;
- observations `unchanged` ;
- erreurs d'observation/persistence ;
- cycles, claims et retries via l'observabilité générique du worker.

`advanced` couvre création initiale et progression effective. La distinction création/progression
n'est pas nécessaire au contrat fonctionnel. Les logs peuvent inclure les identifiants nécessaires au
diagnostic, sans les transformer en dimensions métriques.

`advanced_at` permet un diagnostic SQL ponctuel. Il ne devient ni une métrique fonctionnelle, ni une
preuve d'existence source indépendante de `latestVersionSeen`.

## 12. Tests attendus

### Unitaires

- Construction du watermark avec version positive ; rejet d'une version non positive.
- Première observation à V=42.
- Observation supérieure, égale puis inférieure.
- Résultat `Advanced` ou `Unchanged` cohérent.
- Aucun champ ou accès `advancedAt` dans le modèle fonctionnel.
- Locator : extraction depuis le `BusinessEvent`, key dédiée et invocation exacte du use case.
- Event absent/invalide : aucune observation et classification technique attendue.
- Forme retenue de `ConsumptionExecutionResult`, avec liste technique vide si le contrat reste celui
  inspecté, sans sémantique métier artificielle.

### Persistence PostgreSQL

- Insert initial à une version supérieure à 1.
- Upsert avec version supérieure : version et `advanced_at` avancent.
- Upsert égal ou inférieur : version et `advanced_at` restent inchangés.
- Contrainte `latest_version_seen >= 1`.
- Deux transactions concurrentes 42/45 dans les deux ordres de commit : résultat 45.
- Duplications concurrentes de 45 : résultat 45 et aucune régression.
- Installation V1→V2→V3→V4, installation fraîche et redémarrage idempotent.

### Runtime et transaction

- Event consommé : watermark créé/avancé et slot `DONE/SUCCESS`.
- Event dupliqué et Events hors ordre.
- Plusieurs workers avec claim/fencing sur le même Event.
- Plusieurs Events du même Pot traités concurremment.
- Erreur après upsert avant CAS : watermark, provenance et terminalisation tous absents après rollback.
- Perte de claim avant CAS : aucune mutation watermark committée.
- Aucune ligne créée dans les tables Task.
- Aucun artifact, failure ou `ProjectionHead` créé ou modifié.
- Le runtime watermark n'importe ni stratégie de Task creation ni logique métier Event→Task.

### Invariant d'indépendance

Ajouter un test d'architecture ou un test ciblé prouvant qu'une Task/un projector pour N peut être
exécuté lorsque `latestVersionSeen=N-1`. Le chemin Task/projector ne doit posséder aucune dépendance
vers le port ou l'adapter watermark.

## 13. Ordre précis d'implémentation

1. Ajouter le modèle fonctionnel minimal et ses tests.
2. Ajouter le port intentionnel, le service d'observation et leurs résultats/tests.
3. Ajouter la migration read-store et l'adapter max-upsert avec tests PostgreSQL.
4. Comparer les options de discovery et retenir la forme minimale satisfaisant les garde-fous de la
   section 6.
5. Ajouter le locator express avec son identité et ses tests, sans toucher à la création de Tasks.
6. Créer le runtime indépendant, sa configuration désactivée par défaut et son wiring.
7. Prouver l'enlistment transactionnel et le rollback commun avant de poursuivre la validation.
8. Ajouter concurrence, retry, takeover et tests négatifs Task/projection.
9. Ajouter l'observabilité bornée.
10. Exécuter les tests ciblés, d'architecture puis la suite complète.
11. Mettre à jour seulement après implémentation la documentation factuelle concernée.

## 14. Fichiers et modules probablement touchés

### À ajouter ou modifier

- `domain-projection` : contrat fonctionnel minimal.
- `engine-read-projection` : use case, port et résultat d'observation.
- `infra-read-persistence` : migration, adapter et auto-configuration.
- `locator-consumption-event` ou un locator minimal adjacent : callback de consommation express.
- un nouveau runtime Spring déployable indépendamment et son entrée dans le reactor Maven.
- tests d'architecture pour les nouvelles frontières.

### À inspecter, modification conditionnelle

- `engine-processing-event` : uniquement si le choix de discovery exige une évolution de contrat.
- `infra-persistence-jpa` : uniquement pour l'adapter de discovery/relecture Event nécessaire.
- observabilité générique ou runtime : selon le point d'intégration minimal des compteurs.

### À ne pas modifier fonctionnellement

- Task creation et exécution de projection ;
- pipelines Balance et READ_POT ;
- Query Kernel et GET ;
- write side et modèle primaire.

## 15. Replay, disaster rebuild, risques et points ouverts

Le runtime normal repose sur les Events. Un replay lorsque les Events sont disponibles réutilise le
même consumer et la même opération monotone.

Si les Events ne sont plus disponibles, une procédure administrative du Lot 7.8 pourra lire le
primaire autoritatif et invoquer le même boundary d'observation. Cette lecture ne sera jamais exécutée
par un GET et n'appartient pas au Lot 7.4.

Risques principaux :

- supposer l'enlistment transactionnel sans le prouver dans le nouveau runtime ;
- lier la discovery au faux concept de pipeline watermark ;
- faire remonter `advanced_at` dans la sémantique fonctionnelle ;
- créer une métrique à forte cardinalité ;
- introduire accidentellement une dépendance Task/projector→watermark ;
- confondre artifact en avance et existence source connue.

Les noms exacts des interfaces, la représentation JDBC interne de `advanced_at` et le choix entre port
spécialisé ou généralisation minimale du discovery restent des choix de forme à trancher à partir du
code lors de l'exécution. Ils ne modifient aucun invariant fonctionnel.

## 16. Critères de sortie

- `latestVersionSeen` est monotone, idempotent et concurrent-safe.
- La première observation peut être V=42 ou toute autre version positive.
- Le consumer possède une identité et des slots indépendants d'Event→Task.
- Il est déployable et observable séparément.
- Il ne crée ni Task, ni artifact, ni failure, ni head.
- Il ne consulte ni applicabilité, ni stratégie reader, ni état de projection/Task.
- Le modèle domaine ne contient pas `advancedAt`.
- `advanced_at` ne change que lors d'une avance réelle.
- L'atomicité locale est prouvée par le wiring et le test de rollback commun.
- Si cette preuve échoue, le lot n'est pas validé avant amendement explicite du plan avec un protocole
  idempotent adapté.
- Un projector N reste exécutable avec `latestVersionSeen=N-1`.
- Les migrations fonctionnent en upgrade, installation fraîche et redémarrage.
- Aucun GET actif n'est modifié.

## 17. Commandes de validation

Depuis `app`, exécuter au minimum :

```bash
./mvnw -pl domain-projection,engine-read-projection,infra-read-persistence -am test
./mvnw -pl <locator-retenu>,<runtime-watermark-retenu> -am test
./mvnw -pl architecture-tests -am test
./mvnw test
git diff --check
```

Les placeholders ne seront remplacés qu'après choix des noms de modules pendant l'implémentation. La
validation SQL doit également confirmer l'absence de FK du schéma `pocoma_read` vers le primaire et
l'absence de toute nouvelle table fonctionnelle autre que le watermark.

## 18. Classification finale des décisions

### Invariants verrouillés

- watermark monotone ;
- consumer identity indépendante ;
- aucune Task, projection ou head ;
- aucune barrière watermark pour les projectors ;
- premier Event pas nécessairement V1.

### Constats confirmés par inspection

- `BusinessEvent` expose `potId()` et `version()` ;
- les résultats techniques de consommation peuvent être vides ;
- les adapters inspectés utilisent actuellement les mêmes ressources Spring transactionnelles.

### Propriété encore à prouver pendant l'implémentation

L'Event store, l'upsert watermark, la provenance et le lifecycle/CAS terminal doivent être réellement
enlistés dans une même transaction du nouveau runtime. La validation repose sur l'inspection du wiring
effectif et sur le test de rollback commun, pas sur la seule colocalisation actuelle.

### Formes laissées ouvertes

- noms exacts des interfaces et records techniques ;
- représentation technique de `advanced_at` ;
- port spécialisé ou généralisation minimale du discovery.

Il ne subsiste aucun bloqueur documentaire avant implémentation. Un défaut d'enlistment constaté
pendant celle-ci deviendrait un gate de validation imposant l'amendement décrit en section 9, et non
une impossibilité intrinsèque du Lot 7.4.
