# Matrice des dépendances des modules

## Direction générale

```text
domain
   ↑
engine fonctionnel
   ↑
engine processing
   ↑
worker / supra
   ↑
runtime
```

Les flèches représentent la direction dans laquelle une couche extérieure peut utiliser une
couche intérieure. L'infrastructure implémente les ports sortants définis par les engines. Aucun
domaine ou engine ne dépend d'un runtime, d'un supra ou d'un adapter d'infrastructure.

## Modules de domaine

| Module | Responsabilité et principaux types | Dépendances autorisées | Interdites | État |
|---|---|---|---|---|
| `domain-authorization` | Capacité provider-neutral `Permission(objectType, action)` | JDK | Pot, engines, frameworks | target |
| `domain-event` | Marqueur générique `BusinessEvent` | JDK | domaines fonctionnels, engines, frameworks | target |
| `domain-pot` | Modèle Pot, valeurs, agrégats, `BusinessEvent` typés et vérité temporelle canonique `PotVersionMetadata` | JDK | autres domaines, engines, frameworks | target Lots 6/7.7 |
| `domain-pot-policy` | Policies Pot utilisant directement `Permission` | autorisation, Pot, JDK | engines, infra, runtime | target |
| `domain-projection-balance` | Valeurs `PotBalances` et `Balance` pour le calcul exact canonique | `domain-pot`, JDK | engines, persistence, workers | target |
| `domain-projection` | identité générique, statut dérivé, artifact/failure/head et modèle canonique `PotProjection` | Pot, JDK | engines, persistence, frameworks | target Lots 7.4/7.6/7.7 |
| `domain-consumption` | `ConsumptionKey`, `ConsumptionSlot`, `ClaimId`, lease, failure | JDK | objets consommés, engines, workers, persistence | target |

## Engines

| Module | Responsabilité et principaux types | Dépendances autorisées | Interdites | État |
|---|---|---|---|---|
| `engine-core` | contrats partagés : snapshots, `RecordedEvent`, trace, transaction et segmentation | domaines nécessaires | infra, supra, runtime | shared |
| `engine-pot-command` | Commands métier typées, inbound ports d'écriture Pot, services et adapters du moteur Command | Pot, policies, core, engine-command | consumption, processing, tasks, workers | target |
| `engine-read-projection` | primitive et avance monotone de `LatestKnownVersion`, port de persistance et source de reconstruction historique Pot | Pot | pipeline/lifecycle/serving, consumers LKV, producteurs de projections | conservé ; frontière neutre LKV et source canonical READ_POT |
| `engine-consumption` | slots/claims, acquisition/failure et exécution générique atomique protégée par `currentClaimId` | consumption, transaction core | Command, Event, Task, Pot, Pipeline, execution guard | target |
| `engine-command` | envelope durable générique, décodage, dispatch, exécution et ports de persistence/discovery | authorization, event, consumption terminal, JDK | Pot, processing, infra, frameworks | target |
| `engine-processing-event` | contrats metadata-only de discovery EPT et LKV, ordre Event et policy exhaustive EventType→ProjectionType | consumption, Pot event, core | Command/Task processing, pipeline generation, read store | target EPT/LKV |

## Adaptateurs, orchestration et composition

| Module(s) | Responsabilité | Peut dépendre de | État / retrait |
|---|---|---|---|
| `orchestrator-command-admission` | principal authentifié provider-neutral, traduction des autorités, résolution d'identité, snapshot et insert transactionnel | engine-command, transaction core | target, sans Spring/JWT/Keycloak |
| `supra-worker-event` | ancien nom documentaire retiré ; le polling Event canonique est porté par `supra-consumption-worker` et `locator-consumption-event` | — | absent du reactor |
| `supra-consumption-worker` | boucle de polling générique, budgets, cadence, arrêt coopératif et observation runtime minimale | orchestrateur consumption | target, ignorant des familles métier |
| `locator-consumption-command` | convention `ConsumptionKey` Command, discovery, relecture/exécution autoritative, adaptation de provenance et classification technique conservative | engine-command, domain/engine consumption, orchestrator-consumption | target, sans runtime |
| `locator-consumption-latest-known-version` | localisation Event dédiée, max-upsert monotone, provenance d'entrée et classification technique | processing Event, read projection, consumption générique | target Lot 7.4, sans Task ni projection métier |
| `binding-pot-command-spring` | assemblage des decoders et adapters Pot derrière les contrats génériques Command | engine-command, engine-pot-command, Spring composition | target, sans polling ni transaction locale |
| `supra-authentication-spring-security` | Resource Server OAuth2 standard et adaptation du principal Spring vers `AuthenticatedExternalPrincipal` | Spring Security, orchestrator-command-admission | target, implémentation de frontière remplaçable |
| `supra-http-rest-spring` | admission Command asynchrone ; aucun endpoint Pot/Expense/Balance legacy | command admission | target |
| `infra-tx-spring` | implémentation Spring de `TransactionRunner` | engine-core | target |
| `infra-persistence-jpa` | implémentations JPA/JDBC des ports, migrations primaires V1–V16, Recorded Commands, discovery Event/Task et sources historiques des producers canoniques | engines propriétaires, domaines | target ; aucun runtime mutable Balance ou `projection_tasks_legacy` |
| `infra-read-persistence` | store canonique exact, latest-known et migrations historiques append-only | `engine-projection-read`, `engine-read-projection`, Spring JDBC et Flyway | conservé ; aucun reader metadata/index legacy |
| `observability` | contexte de corrélation de trace partagé par HTTP et append Event | JDK | infrastructure transversale minimale |
| `runtime-web-api` | composition de l'admission Command HTTP | supra HTTP, persistence et sécurité | composition |
| `runtime-event-consumption-worker` | composition EPT metadata-only Event→ProjectionTask par acquire/finalize fenced et polling générique | policy/locator EPT/orchestrateur/supra/infra | composition target |
| `runtime-latest-known-version-consumption-worker` | consumer Event direct transactionnel indépendant : reload autoritatif, max-upsert latest-known, lifecycle générique | locator latest-known/orchestrateur/supra/infra primaire et read store | composition target Lot 7.4, sans Task ni projector |
| `runtime-task-consumption-worker` | composition canonique `projection_tasks` → `ProjectionEngineService` pour READ_POT et POT_BALANCES | orchestrateur/supra/infra/engines de projection canoniques | composition target EPT |
| `runtime-command-consumption-worker` | composition du locator Command, moteur transactionnel, polling générique et binding Pot | locator/orchestrateur/supra/infra/binding Pot Command | composition target, processus distinct de l'API HTTP |
| `architecture-tests` | vérification des frontières de packages | tous les modules inspectés | validation |

## Exceptions transitoires contrôlées

- Les processing engines composent les use cases génériques d'`engine-consumption`.
- Le chemin Command protège le commit avec une transaction métier unique terminée par le CAS
  `status=PENDING AND current_claim_id=:claimId`. L'ancien execution guard et l'ancien processing
  Command ont été supprimés.
- La persistence Command cible implémente uniquement les ports d'`engine-command` et ne crée aucun slot.
- `engine-command` ne connaît pas `ConsumptionKey`; la convention `COMMAND / COMMAND_PROCESSOR`
  appartient exclusivement à `locator-consumption-command`.
- Seules les erreurs Command explicitement reconnues comme transitoires sont retentées ; une
  exception runtime inconnue est terminale.
- L'observation du polling reste limitée aux cycles, budgets, délais et compteurs déjà exposés par
  l'orchestrateur. Elle n'ajoute aucun callback métier à `engine-consumption` ou à
  `orchestrator-consumption`.
- L'Event processing utilise `RecordedEvent` et la policy canonique EventType→ProjectionType,
  sans modèle pipeline/generation/lifecycle.
- Task processing connaît uniquement les données structurelles de la Task et ne consulte ni slot,
  ni claim, ni statut ou lease legacy.
- L'infrastructure dépend des ports sortants qu'elle implémente.
- Les interfaces spécialisées `*UseCase` de `engine-pot-command` restent les inbound ports métier ;
  les adapters Command durables ne dépendent pas des services concrets.
- Le supra HTTP ne dépend ni des ports ni des services de mutation Pot. Les verbes HTTP ne sont pas
  interdits globalement : seule la mutation directe du write model primaire l'est.
- La persistence Task n'a aucune dépendance vers un supra ni vers le lifecycle Consumption pour
  sélectionner ses candidats.
