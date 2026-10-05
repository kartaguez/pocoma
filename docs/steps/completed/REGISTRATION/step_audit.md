> **Statut documentaire : SUPERSEDED — snapshot antérieur à la chaîne Registration livrée. Les hypothèses CURRENT_BINDING/Event→Task et les anciens chemins absolus de machine restent comme preuve historique ; voir [Architecture](../../../architecture/Architecture.md).**

# Audit de cadrage — `RegistrationRequest` et `docs/steps`

## A. Baseline repository

- Branche : `v2-make-it-pull`
- HEAD local : `7e9ba329dad8b5071a4518b89c1b623220c1372d`
- HEAD distant vérifié : identique
- Divergence : `0` derrière, `0` devant
- Working tree : aucun fichier suivi modifié ou indexé
- Fichier non suivi préexistant : `docs/operations/cmd-start-runtimes.md`

Aucun fichier, commit ou push n’a été produit pendant cet audit.

## B. Chaîne d’identité actuelle

```text
Authorization: Bearer JWT
  ↓
Spring Security OAuth2 Resource Server
  ↓ validation signature / issuer / audience / dates
JwtAuthenticationToken
  ↓
SpringSecurityExternalPrincipalAdapter
  ↓
AuthenticatedExternalPrincipal
  { issuer, subject, auth_time, iat, exp, scopes }
  ↓
ExternalIdentity(issuer, subject)
  ↓
external_identities
  ↓
PocomaUserId
```

Constats :

- La validation JWT est installée dans `WebApiSecurityConfiguration` (`/Users/julien.guezennec/Dev/projects/pocoma/app/supra-authentication-spring-security/src/main/java/com/kartaguez/pocoma/supra/authentication/springsecurity/WebApiSecurityConfiguration.java:22` ; chemin absolu de la baseline auditée).
- `AuthenticatedExternalPrincipal` (`/Users/julien.guezennec/Dev/projects/pocoma/app/orchestrator-command-admission/src/main/java/com/kartaguez/pocoma/orchestrator/command/admission/model/AuthenticatedExternalPrincipal.java:8` ; chemin absolu de la baseline auditée) fournit déjà tout ce qui est nécessaire pour capturer l’identité externe sans résoudre un User : `issuer`, `subject`, dates d’authentification et autorités.
- Sa méthode `identity()` produit exactement `ExternalIdentity(issuer, subject)`.
- La résolution est effectuée par `ExternalIdentityResolverPort` (`/Users/julien.guezennec/Dev/projects/pocoma/app/orchestrator-command-admission/src/main/java/com/kartaguez/pocoma/orchestrator/command/admission/port/out/ExternalIdentityResolverPort.java:8` ; chemin absolu de la baseline auditée).
- La table `external_identities` (`/Users/julien.guezennec/Dev/projects/pocoma/app/infra-persistence-jpa/src/main/resources/db/migration/V9__external_identities.sql:1` ; chemin absolu de la baseline auditée) impose l’unicité de `(issuer, subject)`.

Contrainte de propriété actuelle : `AuthenticatedExternalPrincipal` et `ExternalIdentity` vivent dans `orchestrator-command-admission`, alors qu’ils sont déjà utilisés par les controllers READ. Leur contenu est réutilisable, mais leur emplacement crée un couplage nominal à Command. Un cadrage Registration devrait leur donner une propriété neutre, sans inventer un second modèle d’identité équivalent.

## C. Faisabilité de `RegistrationRequest`

Verdict : **faisable comme nouveau type de consommable, sans être une Command**.

La séparation peut être matérialisée ainsi :

```text
Command
  → AuthenticatedExternalPrincipal
  → résolution PocomaUserId obligatoire
  → AuthorizationSnapshot
  → RecordedCommand

RegistrationRequest
  → AuthenticatedExternalPrincipal
  → capture directe de ExternalIdentity
  → aucune résolution PocomaUserId à l’admission
  → demande durable spécifique
```

La frontière Command reste intacte : `SubmitRecordedCommandService` (`/Users/julien.guezennec/Dev/projects/pocoma/app/orchestrator-command-admission/src/main/java/com/kartaguez/pocoma/orchestrator/command/admission/SubmitRecordedCommandService.java:39` ; chemin absolu de la baseline auditée) continue d’exiger un `PocomaUserId` et de rejeter une identité inconnue.

Proposition de cadrage :

- endpoint et use case d’admission distincts ;
- store durable `RegistrationRequest` distinct de `recorded_commands` ;
- identifiant `RegistrationRequestId`, probablement UUID comme `CommandId` ;
- nouvelle convention de clé Consumption, par exemple :
  - consumable : `REGISTRATION_REQUEST / [registrationRequestId]`
  - consumer : `REGISTRATION_PROCESSOR / []`
- locator, exécution et résultat propres à Registration ;
- réutilisation du moteur Consumption et de son worker, pas du moteur Command.

Aucune généralisation supplémentaire du moteur Consumption n’est requise par le code actuel.

## D. Réutilisation de Consumption

| Mécanisme | Existant | Réutilisable tel quel | Adaptation nécessaire | Preuve code |
|---|---|---:|---|---|
| Identité structurelle | `ConsumableIdentity`, `ConsumerIdentity`, `ConsumptionKey` | Oui | Nouvelle convention Registration | `ConsumptionKey` (`/Users/julien.guezennec/Dev/projects/pocoma/app/domain-consumption/src/main/java/com/kartaguez/pocoma/domain/consumption/key/ConsumptionKey.java:5` ; chemin absolu de la baseline auditée) |
| Création du slot | Création paresseuse à l’acquisition | Oui | Aucune colonne d’état dans RegistrationRequest | `JpaConsumptionLifecycleAdapter.acquire` (`/Users/julien.guezennec/Dev/projects/pocoma/app/infra-persistence-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/jpa/adapter/consumption/JpaConsumptionLifecycleAdapter.java:62` ; chemin absolu de la baseline auditée) |
| Unicité d’une consommation | Clé structurelle unique | Oui | Types/components Registration | `V4__consumption_engine.sql` (`/Users/julien.guezennec/Dev/projects/pocoma/app/infra-persistence-jpa/src/main/resources/db/migration/V4__consumption_engine.sql:15` ; chemin absolu de la baseline auditée) |
| Eligibility | Discovery propre à chaque famille | Partiellement | Requête de discovery Registration | `JpaCommandConsumptionDiscoveryRepository` (`/Users/julien.guezennec/Dev/projects/pocoma/app/infra-persistence-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/jpa/repository/command/JpaCommandConsumptionDiscoveryRepository.java:23` ; chemin absolu de la baseline auditée) |
| Claim | Claim atomique avec tentative et worker | Oui | Aucune | `ConsumptionLifecyclePersistencePort` (`/Users/julien.guezennec/Dev/projects/pocoma/app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/port/out/consumption/ConsumptionLifecyclePersistencePort.java:18` ; chemin absolu de la baseline auditée) |
| Lease/takeover | Lease fixe, reprise après expiration | Oui | Dimensionner le lease ; aucun heartbeat existant | `JpaConsumptionLifecycleAdapter` (`/Users/julien.guezennec/Dev/projects/pocoma/app/infra-persistence-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/jpa/adapter/consumption/JpaConsumptionLifecycleAdapter.java:99` ; chemin absolu de la baseline auditée) |
| Retry | `RetryAfter` puis nouvelle tentative | Oui | Classifier/policy Registration | `DefaultConsumptionFailurePolicy` (`/Users/julien.guezennec/Dev/projects/pocoma/app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/service/consumption/DefaultConsumptionFailurePolicy.java:13` ; chemin absolu de la baseline auditée) |
| Échec terminal | `DONE/FAILED`, raison, failure du claim | Oui | Définir les codes stables et l’éventuel résultat public | `HandleConsumptionFailureService` (`/Users/julien.guezennec/Dev/projects/pocoma/app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/service/consumption/HandleConsumptionFailureService.java:38` ; chemin absolu de la baseline auditée) |
| Fencing | CAS sur `current_claim_id` | Oui | L’effet get-or-create doit être dans la transaction fenced | `ExecuteConsumptionService` (`/Users/julien.guezennec/Dev/projects/pocoma/app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/service/consumption/ExecuteConsumptionService.java:39` ; chemin absolu de la baseline auditée) |
| Transaction métier | Travail + provenance + terminalisation | Oui | Nouvel adaptateur d’exécution Registration | `TransactionalExecuteConsumptionUseCase` (`/Users/julien.guezennec/Dev/projects/pocoma/app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/service/transaction/consumption/TransactionalExecuteConsumptionUseCase.java:10` ; chemin absolu de la baseline auditée) |
| Crash avant commit | Rollback puis takeover après expiration | Oui | Aucune si l’effet reste PostgreSQL transactionnel | Même chaîne |
| Polling | `ConsumptionPollingWorker` permanent | Oui | Propriétés et lifecycle Registration | `ConsumptionPollingWorker` (`/Users/julien.guezennec/Dev/projects/pocoma/app/supra-consumption-worker/src/main/java/com/kartaguez/pocoma/supra/consumption/ConsumptionPollingWorker.java:16` ; chemin absolu de la baseline auditée) |
| Budget/fairness | Limites candidats/exécutions | Oui, dans une famille | Ordering/fairness de discovery à définir | `SequentialConsumptionOrchestrator` (`/Users/julien.guezennec/Dev/projects/pocoma/app/orchestrator-consumption/src/main/java/com/kartaguez/pocoma/orchestrator/consumption/SequentialConsumptionOrchestrator.java:24` ; chemin absolu de la baseline auditée) |
| Ordering | Défini par chaque discovery | Non générique | `capturedAt, requestId` est cohérent avec Command | Repository Command ci-dessus |
| Segmentation | Présente pour Event/ProjectionTask, absente pour Command | Optionnelle | Pas nécessaire en V1 sauf besoin de débit | `ProjectionTaskConsumptionOrchestrator` (`/Users/julien.guezennec/Dev/projects/pocoma/app/orchestrator-consumption/src/main/java/com/kartaguez/pocoma/orchestrator/consumption/ProjectionTaskConsumptionOrchestrator.java:22` ; chemin absolu de la baseline auditée) |

Limite canonique importante : Consumption ne renouvelle pas les leases. Un traitement dépassant le lease peut être repris par un autre worker ; le fencing protège alors le commit du gagnant.

## E. Modèle durable minimal

### `RegistrationRequest`

Données nécessaires :

| Donnée | Nécessité | Motif |
|---|---:|---|
| `registrationRequestId` | Oui | Identité durable et clé de polling |
| `issuer` | Oui | Première moitié de l’identité externe et ownership |
| `subject` | Oui | Seconde moitié de l’identité externe et ownership |
| `capturedAt` | Oui | Ordering et instant d’acceptation |
| JWT brut | Non | Secret inutile et périssable |
| scopes complets | Non par défaut | Le traitement ne doit permettre que l’auto-provisionnement |
| `PocomaUserId` | Non | Inconnu à l’admission ; appartient au résultat |
| statut pending/processing/done | Non | Déjà détenu par Consumption |
| claim/lease/retry count | Non | Déjà détenus par Consumption |

L’identité capturée doit être immutable. L’endpoint doit toujours la dériver du principal validé, jamais du body HTTP.

Les timestamps `auth_time`, `iat`, `exp` ne sont nécessaires que si une future règle exige une preuve d’authentification plus forte que « JWT valide à l’admission ». Le code ne permet pas de conclure que cette conservation est requise.

### État technique

Il appartient entièrement à :

- `consumption_slots` ;
- `consumption_claims` ;
- éventuellement `consumption_inputs`/`consumption_results` pour la provenance.

Il ne doit pas être recopié dans `RegistrationRequest`.

### Résultat fonctionnel

Proposition minimale :

- `registrationRequestId`
- issue terminale
- `PocomaUserId` en succès
- `resolvedAt`
- éventuel code public en échec

`consumption_results` ne doit pas être détourné comme résultat métier public : cette table décrit la provenance technique d’une Consumption.

### Résultat primaire ou projection

Constat :

- `COMMAND_RESULT` possède un outcome primaire, un terminal Event, puis une projection READ.
- Le pipeline Event actuel ne découvre que `business_event_outbox` et `command_terminal_events`, explicitement dans `JdbcProjectionMaterializationDiscoveryAdapter` (`/Users/julien.guezennec/Dev/projects/pocoma/app/infra-persistence-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/jpa/adapter/processing/event/JdbcProjectionMaterializationDiscoveryAdapter.java:57` ; chemin absolu de la baseline auditée).
- Ajouter des Registration terminal Events est possible, mais demande d’étendre cette source et la policy de routes ; ce n’est pas automatique.

Proposition de cadrage :

1. produire directement un outcome Registration autoritatif dans la transaction fenced qui réalise le get-or-create ;
2. ne produire un Event et une projection `REGISTRATION_RESULT` que si la séparation WRITE/READ actuelle doit aussi s’appliquer à ce polling ;
3. si cette séparation est maintenue, l’Event ne porte que l’identité du résultat disponible ; le `PocomaUserId` reste dans l’outcome primaire et est chargé par le producer de projection.

Ainsi `NOT_READY` signifie absence d’outcome/projection terminale ; `FAILED` est une issue terminale durable, pas une simple absence.

## F. Chaîne runtime candidate

```text
JWT valide
  ↓
AuthenticatedExternalPrincipal
  ↓ sans ExternalIdentityResolverPort
POST /api/v1/registrations
  ↓
persist RegistrationRequest {
  requestId,
  issuer,
  subject,
  capturedAt
}
  ↓
202 + registrationRequestId
  ↓
Registration locator
  ↓
Consumption(
  REGISTRATION_REQUEST/requestId,
  REGISTRATION_PROCESSOR
)
  ↓ claim / lease / retry / fencing
Registration worker
  ↓ transaction fenced
INSERT association avec résolution du conflit
  ↓
SELECT association autoritative
  ↓
PocomaUserId
  ↓
RegistrationOutcome durable
  ↓ éventuellement terminal Event
  ↓ éventuellement REGISTRATION_RESULT
  ↓
GET /api/v1/registrations/{requestId}
```

Le worker ne doit pas faire un naïf « SELECT absent → INSERT » : deux workers peuvent observer l’absence simultanément.

La primitive attendue est conceptuellement :

```text
candidateUserId = UUID
INSERT (issuer, subject, candidateUserId)
ON CONFLICT (issuer, subject) DO NOTHING
SELECT pocoma_user_id WHERE issuer=? AND subject=?
```

Dans une transaction PostgreSQL unique, la PK `(issuer, subject)` sérialise le conflit. Le gagnant persiste son UUID ; les concurrents relisent le même UUID autoritatif. Cela est compatible avec l’idempotence envisagée.

Si un futur agrégat/table `User` est ajouté, il faudra éviter de créer un User orphelin pour le candidat perdant. Le repository actuel ne permet pas de trancher si une ligne `external_identities` suffit à constituer un User.

## G. Sécurité et invariants

Le cadrage devrait figer les invariants suivants :

1. `/api/v1/registrations/**` est explicitement `authenticated()` ; aujourd’hui un nouveau chemin tomberait sous `anyRequest().permitAll()`.
2. Seul un `JwtAuthenticationToken` déjà validé peut devenir `AuthenticatedExternalPrincipal`.
3. L’identité enregistrée vient exclusivement du JWT ; aucun `issuer`, `subject` ou `userId` n’est accepté du client.
4. L’admission Registration n’appelle pas la résolution Pocoma User.
5. L’admission Command continue d’exiger cette résolution.
6. Aucun controller HTTP ne crée ni ne rattache directement un `PocomaUserId`.
7. Seul le traitement asynchrone fenced réalise le get-or-create.
8. `(issuer, subject)` désigne au plus un `PocomaUserId`.
9. Une répétition concurrente converge vers le même `PocomaUserId`.
10. `RegistrationRequest` est immutable et ne duplique aucun état Consumption.
11. Le résultat n’utilise ni `CommandOutcome`, ni `COMMAND_RESULT`.
12. Le polling compare l’identité externe exacte du caller avec celle capturée.
13. Une demande absente, non prête ou appartenant à une autre identité ne révèle pas son existence.
14. Le token brut n’est jamais persisté.

Ownership candidat :

```text
principal.identity() == request/result.ownerIdentity
    → résultat visible
sinon
    → 404
```

Une projection terminale peut embarquer `issuer + subject` pour ce contrôle, ou une clé d’ownership dérivée dont la normalisation et la résistance cryptographique seraient explicitement définies. Le code actuel utilise l’égalité textuelle exacte ; aucun mécanisme canonique de hashing d’identité n’existe.

## H. Questions encore ouvertes

### Décisions indispensables avant un plan d’implémentation

1. Une ligne `external_identities` constitue-t-elle à elle seule un User, ou faut-il un modèle/table User canonique ?
2. Le polling Registration doit-il respecter la frontière READ par projection, ou peut-il lire l’outcome primaire ?
3. Si une projection est retenue, confirme-t-on le pattern outcome primaire → terminal Event → `REGISTRATION_RESULT` ?
4. Quelle sémantique HTTP est attendue avant terminaison :
   - `404` comme `COMMAND_RESULT`, masquant absent/non prêt/non propriétaire ;
   - ou un statut explicite `202/PENDING`, qui nécessite un moyen READ d’établir l’ownership avant le résultat terminal ?
5. Une répétition doit-elle produire un nouveau `registrationRequestId` convergeant vers le même User, ou réutiliser une demande antérieure ?
6. Une identité déjà provisionnée doit-elle toujours passer par `202` et le worker, ou une autre sémantique est-elle voulue ?
7. Quels échecs doivent devenir publics et lesquels doivent rester purement techniques ?
8. Une authentification valide suffit-elle, ou une capability/scope Registration spécifique est-elle requise ?

### Décisions pouvant rester à l’implémentation

- noms exacts des tables et DTO ;
- UUID aléatoire ou autre générateur compatible avec les conventions ;
- valeurs initiales de lease, polling et retry ;
- pagination et taille des pages de discovery ;
- segmentation initiale du worker ;
- noms exacts des codes métriques ;
- organisation physique précise des modules après clarification de la propriété des types d’identité.

## I. Inventaire `docs/steps`

| Sujet | Objectif | État proposé | Destination proposée | Justification |
|---|---|---|---|---|
| EPT | Event → ProjectionTask | COMPLETED | `completed/EPT` | `Step_Plan` (`/Users/julien.guezennec/Dev/projects/pocoma/docs/steps/EPT/Step_Plan.md:1` ; chemin absolu de la baseline auditée) déclare globalement `DONE`; clôture le 2026-09-27 |
| PCL | Suppression du legacy projection/query | COMPLETED | `completed/PCL` | Les huit lots sont `DONE`; clôture PCL.8 le 2026-09-29 |
| CCR | Résultat terminal des Commands | COMPLETED | `completed/CCR` | `Step_Plan` (`/Users/julien.guezennec/Dev/projects/pocoma/docs/steps/CCR/Step_Plan.md:5` ; chemin absolu de la baseline auditée) déclare `DONE` et « formellement clôturé » |
| POT_E2E | Flux Command → résultat → READ_POT autorisé | AMBIGUOUS | provisoirement `current/POT_E2E` | Le document parle de « Delivered slice » et les commits indiquent une livraison, mais aucun statut/avis de clôture formel n’existe |
| AUTH | Aucun répertoire | — | Aucun déplacement | AUTH existe comme projection et sujet d’architecture, pas comme step autonome dans `docs/steps` |
| REGISTRATION | Nouveau bootstrap User | CURRENT | `current/REGISTRATION` | Cadrage actif distinct de CCR |

Le récent `RegisterUser_Identity_Audit.md` ne réactive pas CCR. Son sujet est Registration ; il devrait rejoindre `current/REGISTRATION`.

Point documentaire important : EPT est clôturé comme step, mais son `Step_Canon` est encore présenté comme autorité active par `docs/README.md` et `docs/architecture/consumption-event-pull-runtime.md`. Avant archivage, il faut décider si :

- l’autorité durable reste un canon sous `completed/EPT`, avec des liens explicites ;
- ou les invariants durables sont consolidés dans `docs/architecture`, le step devenant purement historique.

## J. Plan conceptuel de réorganisation

Arborescence proposée :

```text
docs/steps/
├── current/
│   ├── REGISTRATION/
│   │   └── RegisterUser_Identity_Audit.md
│   └── POT_E2E/                 # provisoire jusqu’à décision de clôture
│       ├── Step_Plan.md
│       └── E2E_Latency_Observability_Audit.md
└── completed/
    ├── EPT/
    │   ├── Step_Canon.md
    │   ├── Step_Plan.md
    │   └── Legacy_Cleanup_Inventory.md
    ├── PCL/
    │   ├── Step_Canon.md
    │   └── Step_Plan.md
    └── CCR/
        ├── Step_Canon.md
        └── Step_Plan.md
```

Mouvements conceptuels :

```text
docs/steps/EPT
  → docs/steps/completed/EPT

docs/steps/PCL
  → docs/steps/completed/PCL

docs/steps/CCR/Step_Canon.md
  → docs/steps/completed/CCR/Step_Canon.md

docs/steps/CCR/Step_Plan.md
  → docs/steps/completed/CCR/Step_Plan.md

docs/steps/CCR/RegisterUser_Identity_Audit.md
  → docs/steps/current/REGISTRATION/RegisterUser_Identity_Audit.md

docs/steps/POT_E2E
  → docs/steps/current/POT_E2E
```

Références à mettre à jour :

- `docs/README.md` (`/Users/julien.guezennec/Dev/projects/pocoma/docs/README.md:9` ; chemin absolu de la baseline auditée) :
  - liens EPT ;
  - section qui présente encore EPT comme « Active implementation step » ;
  - ajout des index `current` et `completed`.
- `docs/pipeline-event-materialization-plan.md` (`/Users/julien.guezennec/Dev/projects/pocoma/docs/pipeline-event-materialization-plan.md:3` ; chemin absolu de la baseline auditée) : deux liens EPT.
- `docs/architecture/consumption-event-pull-runtime.md` (`/Users/julien.guezennec/Dev/projects/pocoma/docs/architecture/consumption-event-pull-runtime.md:3` ; chemin absolu de la baseline auditée) : lien vers le canon EPT.
- `docs/steps/EPT/Step_Plan.md`, lignes 553-554 : chemins littéraux vers lui-même.
- `docs/steps/PCL/Step_Plan.md`, lignes 1129-1130 : chemins littéraux vers lui-même.

Les liens relatifs entre EPT, PCL et CCR resteraient valides si les trois dossiers sont déplacés ensemble sous `completed`, car leur position relative ne change pas.

Aucune référence directe à `docs/steps/...` n’a été trouvée dans :

- le code Java de production ;
- les tests ;
- `.github` ;
- les scripts Bruno/K6 ;
- les POM Maven.

Risque principal : déplacer les fichiers sans corriger leur statut sémantique. Un `Step_Canon` sous `completed` peut encore porter des invariants actifs ; l’index doit indiquer clairement quelle documentation est normative aujourd’hui.

## K. Avis de clôturabilité

**Cadrage non clôturable à ce stade.**

La faisabilité technique est établie : `RegistrationRequest` peut être un consommable distinct reposant sur l’infrastructure Consumption existante, sans affaiblir ni coupler le pipeline Command.

Les décisions minimales encore nécessaires sont :

1. définir si le User canonique est une entité persistée ou uniquement l’UUID autoritaire de `external_identities` ;
2. choisir la frontière de lecture du résultat : outcome primaire direct ou projection `REGISTRATION_RESULT` ;
3. décider la sémantique de polling avant terminaison (`404` opaque ou statut explicite) ;
4. fixer l’idempotence au niveau de la demande : même User seulement ou même `registrationRequestId` ;
5. définir le profil d’autorisation du POST Registration ;
6. statuer formellement sur la clôture de `POT_E2E` ;
7. décider où réside l’autorité documentaire durable d’EPT/CCR après déplacement dans `completed`.

Ces décisions suffisent ensuite à préparer le canon Registration et son step plan, sans avoir besoin de redessiner le moteur Consumption.