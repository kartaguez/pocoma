# REGISTRATION — Plan d’implémentation

```text
Step: REGISTRATION
Phase: IMPLEMENTATION PLANNED
Authorities:
  - Step_Canon.md — D1–D33
  - ../WRITE_ADMISSION/Step_Canon.md — WA1–WA11
Sequencing: REG.1 → REG.2 → REG.3 → REG.4 → REG.5
Prerequisite: WRITE_ADMISSION WA.1–WA.8 complete
```

## 1. Baseline et portée

- branche : `v2-make-it-pull` ;
- HEAD local et distant : `00de08c3fbe3aef9290b7183d9b85331bd6b7582` ;
- divergence : `0/0` ;
- working tree initial : propre ;
- autorité REGISTRATION : [`Step_Canon.md`](Step_Canon.md), D1–D33 ;
- autorité transverse : [`../WRITE_ADMISSION/Step_Canon.md`](../WRITE_ADMISSION/Step_Canon.md),
  WA1–WA11.

Ce plan remplace intégralement le plan superseded en sept lots. Il ne modifie aucun canon. Les
fondations User/Identity, `BindingId`, admission WRITE et READ self-service sont livrées par
WRITE_ADMISSION ; REGISTRATION les consomme sans créer de second type, port ou store concurrent.

```text
WA.1–WA.8
  → types et autorité Binding(E,U,B)
  → faits User/Identity occurrence-aware
  → admission HTTP WRITE sans lecture primaire
  → projections READ et conventions de Consumption éprouvées
  → REG.1–REG.5
```

Chaque lot est committable et conserve le reactor vert. RegistrationRequest reste distincte d’une
Command et Consumption reste distinct du résultat fonctionnel public.

## 2. Architecture cible

```text
POST /api/v1/registrations
  AuthN → E → validation structurelle → RegistrationRequest R(E) durable → 202
                                  aucune lecture primaire, aucune mutation User

Registration worker fenced
  R(E) → transition atomique
       → Registered(U,B)
          + User(U)
          + Binding(E,U,B)
          + UserCreated(U)
          + ExternalIdentityAttached(E,U,B)
       ou Rejected(EXTERNAL_IDENTITY_ALREADY_USED)
          + aucun effet User/Identity

outcome/faits → projection workers → READ
                                     ↓
GET /api/v1/registrations/{requestId}
  AuthN E → READ uniquement → résultat visible ou 404 opaque
```

Le succès possède cinq effets métier durables dans une frontière atomique. Le rejet fonctionnel
possède uniquement son résultat terminal. Une Registration distincte perdante ne récupère jamais
l’owner existant et ne laisse jamais de User orphelin.

## 3. Lots d’implémentation

### REG.1 — Modèle Registration, intention durable et admission

**Prérequis.** WA.1–WA.8 complets ; types User/Identity canoniques et conventions WRITE disponibles.

**Objectif.** Introduire la request immutable, sa persistence, sa discovery Consumption et son
admission HTTP, sans exécuter encore la transition métier.

**Changements.** Créer `engine-registration`, l’orchestrateur et le supra d’admission, puis la
spécialisation Consumption Registration. Introduire :

```text
RegistrationRequestId(UUID)
RegistrationRequest(requestId, creatorExternalIdentity, capturedAt)
ConsumableIdentity = REGISTRATION_REQUEST / requestId
ConsumerIdentity   = REGISTRATION_PROCESSOR
```

La table request ne porte ni JWT, ni scope/capability, ni UserId, ni BindingId, ni statut, claim,
lease ou retry. `requestId` et `capturedAt` viennent du serveur. Le POST dérive E exclusivement du
principal authentifié, persiste la request et retourne `202` ; il n’injecte aucun port de lecture
User/Identity et ne vérifie jamais si E est disponible.

**Preuves.** JWT valide connu ou inconnu → `202` et request seule ; JWT absent/invalide → `401` et
aucune request ; round-trip immutable ; ordering `(capturedAt, requestId)` stable ; discovery exclut
DONE, occupé et non éligible ; aucun slot avant acquire ; aucun User, binding, outcome ou fait créé
au POST ; guard zéro lecture primaire.

**DONE.** L’intention est durable et découvrable, le POST est ouvert et asynchrone, aucun worker
Registration actif ne peut encore produire d’effet métier.

### REG.2 — Conflit atomique User/binding, outcome et faits

**Prérequis.** REG.1 et primitive User/Identity de WA.

**Objectif.** Implémenter la transition métier et son arbitrage concurrent sans compensation par
suppression d’un User durable.

**Frontière obligatoire.** Une tentative de succès rend atomiquement durables :

```text
User(U)
Binding(E,U,B)
Registered(R,U,B)
UserCreated(U)
ExternalIdentityAttached(E,U,B)
```

ou ne laisse **aucun** de ces effets. La RegistrationRequest préalablement admise reste durable.
Un rejet écrit seulement `Rejected(R, EXTERNAL_IDENTITY_ALREADY_USED)` et ne produit ni User, ni
binding, ni fait. Il est explicitement interdit de créer durablement un User candidat puis de le
supprimer comme compensation métier, dans la transaction ou après elle. Aucun code de rejet ne doit
appeler un delete User.

**Conflit PostgreSQL/Spring.** Une violation SQL peut marquer la transaction Spring
`rollback-only` même si l’exception est interceptée. L’implémentation ne doit jamais poursuivre dans
une transaction ainsi abortée pour écrire le rejet. Elle choisit et prouve l’une des mécaniques
valides suivantes :

- acquisition conditionnelle qui arbitre le binding sans transaction abort, par exemple
  `INSERT ... ON CONFLICT DO NOTHING` ou une écriture conditionnelle équivalente, en n’insérant U
  qu’une fois l’acquisition gagnée ou dans un statement atomique sans état intermédiaire durable ;
- savepoint explicitement maîtrisé dont le rollback restaure une transaction valide avant toute
  finalisation ;
- rollback complet de la tentative perdante, suivi de la finalisation fenced du rejet dans une
  nouvelle transaction valide qui revérifie l’autorité nécessaire et le claim courant.

Le choix final est pris au début du lot avec un test PostgreSQL minimal qui démontre l’état de la
transaction après conflit. Il doit rester compatible avec le CAS final de Consumption : une perte
de claim rollbacke tous les effets métier du succès ou la finalisation du rejet concernée.

**Idempotence.** Une même request retrouve d’abord son outcome propre et ne réapplique jamais la
mutation. Deux requests distinctes pour E ne sont pas idempotentes entre elles et ne font jamais de
get-or-create.

**Preuves.** Injection d’échec après chacun des cinq effets ; retry avant/après réponse de commit
perdue ; claim perdu ; takeover ; exception SQL laissant rollback-only ; aucun `REQUIRES_NEW`
accidentel dans le succès ; aucune compensation par delete User.

La preuve concurrente de référence utilise deux transactions/connexions réellement parallèles et
une barrière d’arbitrage :

```text
R1(E) || R2(E)
  → exactement un Registered(U,B)
  → exactement un Rejected(EXTERNAL_IDENTITY_ALREADY_USED)
  → exactement un User
  → exactement un binding courant Binding(E,U,B)
  → exactement un UserCreated(U)
  → exactement un ExternalIdentityAttached(E,U,B)
  → aucun User orphelin
```

**DONE.** Le cœur métier satisfait D2–D5, D9–D10, D13, D15, D17, D20, D22–D25 et D32–D33, sans
suppression compensatoire d’un User durable.

### REG.3 — Worker Registration, reprise et publication

**Prérequis.** REG.2.

**Objectif.** Composer le runtime asynchrone réel et publier les sources nécessaires au READ.

**Changements.** Assembler locator, loader, executor, classifier, policy, transaction fenced,
polling et lifecycle dans un runtime Registration dédié. Utiliser les tables génériques Consumption
pour claim, lease, retry, takeover et terminalisation. Persister l’outcome autoritatif unique et les
faits User/Identity dans les sources/outboxes prévues ; produire les entrées de projection sans
exposer d’état technique Registration.

Les facts restent exactement `UserCreated(U)` et `ExternalIdentityAttached(E,U,B)` avec causation
Registration et identifiants d’événement distincts. Aucun `UserRegistered`, outcome `FAILED` ou
réutilisation forcée de l’outbox Pot n’est introduit.

**Preuves.** Discovery ordonnée et bornée, acquire paresseux, retry de failure technique, takeover
après lease, restart runtime, plusieurs workers, unicité outcome/faits et fencing du commit. Le
rejet ne publie aucun fait User/Identity. Une failure d’infrastructure reste technique et ne devient
pas un troisième résultat public.

**DONE.** Des requests admises via REG.1 convergent vers l’un des deux résultats canoniques, avec
reprise sûre et sources de projection durables.

### REG.4 — Visibilité exclusivement READ

**Prérequis.** REG.3 et projection self-service User/Identity livrée par WA.6.

**Objectif.** Exposer le résultat terminal sans aucune dépendance WRITE du GET et préserver
l’invariant d’occurrence exacte.

**Étude obligatoire au début du lot.** Mesurer l’ordering, la livraison, le rejeu et la convergence
réels du moteur de projections, puis comparer au minimum :

1. composition de projections READ, en combinant le résultat Registration et le binding courant ;
2. projection Registration spécialisée consommant les faits Attached/Detached ;
3. autre représentation exclusivement READ satisfaisant les mêmes invariants.

Choisir la représentation la plus simple compatible avec l’ordering et la convergence réellement
prouvés. Le plan n’impose aucun couplage entre une projection `REGISTRATION_RESULT` éventuelle et la
projection self-service User/Identity. Elles peuvent avoir des sources, clés et rythmes distincts.

**Invariant de visibilité.** Un résultat `Registered(U,B)` n’est visible par E que si l’occurrence
exacte `Binding(E,U,B)` est encore courante dans READ. Égalité de U seule, identité créatrice seule
ou présence historique de B ne suffisent pas. Un rejet est visible uniquement par l’E créatrice et
n’expose jamais l’owner du binding concurrent.

**Interdictions.** Le GET et ses use cases/adapters ne lisent jamais :

- `users`, `external_identities`, `registration_requests`, `registration_outcomes` ou toute autre
  table WRITE ;
- les outcomes primaires directement ;
- `consumption_slots`, claims, results, failures ou toute autre donnée Consumption.

**Contrat public.** Résultat terminal visible → `200`. Request absente, non terminale, projection
non convergée, caller non propriétaire ou occurrence perdue → réponse opaque identique, attendue
`404`. Aucun `PENDING`, `PROCESSING`, retry ou failure de projection n’est exposé.

**Preuves.** Créateur exact lit son rejet ; autre E non. Créateur exact lit `Registered(U,B1)` tant
que E/U/B1 est courant ; autre identité du même U non. Detach B1 masque le résultat. Reattach de E
vers le même U ou un autre U avec B2 ne réactive jamais B1, y compris avec livraison hors ordre et
rejeu d’un événement stale. Guards d’architecture et instrumentation SQL prouvent l’absence totale
de lecture WRITE depuis le GET.

**DONE.** D7, D19–D21, D27 et D30 sont servis exclusivement par READ, avec convergence et
occurrence-awareness démontrées.

### REG.5 — Premier E2E complet, matrice et clôture

**Prérequis.** REG.1–REG.4 et WA.1–WA.8.

**Objectif.** Fournir la première preuve qui part d’une identité externe inconnue et atteint un Pot
lisible, puis fermer toutes les matrices d’invariants.

**E2E de référence obligatoire.** Sans insertion manuelle d’outcome, fait ou projection :

```text
Registration(E)
  → résultat Registered(U,B) visible dans READ
  → self identity READ retourne exactement U/B
  → CreatePot(E,B)
  → Command worker résout autoritativement (E,B) -> U
  → COMMAND_RESULT visible dans READ
  → Pot READ autorisé et prêt
```

Seule l’AuthN technique peut être fournie par le support de test. Les workers, transactions,
outboxes, projection tasks et stores READ réels sont traversés.

**Négatifs structurants.** La clôture conserve au minimum :

- deux Registration R1(E)/R2(E) concurrentes : un succès, un rejet et aucun User orphelin ;
- identité déjà utilisée : rejet seul, aucun owner divulgué ;
- retry de la même request : même outcome, aucun second effet ;
- claim perdu et failure injectée : rollback complet ;
- admission connue/inconnue identique et sans lecture primaire ;
- GET Registration sans dépendance WRITE ni Consumption ;
- autre E du même U masquée ;
- detach B1 masque le résultat et reattach B2 ne réactive jamais B1 ;
- Command avec B1 ancien rejetée par `CALLER_IDENTITY_NOT_CURRENT`, Command avec B2 traitée ;
- restart et concurrence multi-worker ;
- migrations depuis une base pré-WA/REG, données et UUID historiques conservés ;
- guards de modules, ownership et absence des anciens FQCN/ports/writers.

**Validation.** Reactor complet, tests PostgreSQL/Testcontainers, architecture tests, tests runtime
et HTTP, `git diff --check`, scans de dépendances et matrices WA/D complets.

**DONE.** `REGISTRATION PROOF MATRIX COMPLETE — CLOSABLE`, aucun finding ouvert et documentation
d’exploitation alignée.

## 4. Matrice de traçabilité D1–D33

| Décisions | Lots principaux |
|---|---|
| D1, D3–D4, D7–D8, D10–D11, D26–D32 | fondations WA ; REG.2 et REG.4 prouvent leur consommation |
| D2, D5, D9, D13, D15, D17, D20, D22–D25, D33 | REG.2–REG.3 |
| D6, D12, D14, D16, D18, D23, D29–D30 | REG.1 |
| D19, D21, D27, D30 | REG.4 |
| D1–D33 et non-régression WA1–WA11 | REG.5 |

La matrice de clôture détaille chaque décision individuellement ; ce regroupement ne remplace pas
la vérification une par une de D1–D33.

## 5. Invariants hors négociation

- RegistrationRequest n’est jamais une Command.
- Admission Registration ne résout jamais E vers U et ne vérifie jamais la disponibilité de E.
- User, binding, résultat et faits d’un succès sont atomiques, ou tous absents.
- Un rejet n’entraîne aucune création/suppression compensatoire de User durable.
- Une transaction rollback-only n’est jamais utilisée pour finaliser un rejet.
- Une nouvelle request pour une E utilisée rejette ; elle ne récupère jamais l’owner.
- Le retry de la même request retrouve son propre résultat.
- Consumption ne devient jamais l’état fonctionnel public.
- Il n’existe ni résultat Registration `FAILED`, ni fait `UserRegistered`.
- `Registered(U,B)` reste visible seulement sous l’occurrence exacte E/U/B dans READ.
- Tous les GET utilisent exclusivement READ/projections.

## 6. Validation documentaire

- séquencement REG.1–REG.5 inchangé ;
- dépendance explicite aux fondations WA.1–WA.8 ;
- conflit atomique REG.2 sans compensation User ;
- étude et choix READ obligatoires au début de REG.4 ;
- premier E2E complet maintenu en REG.5 ;
- couverture individuelle à vérifier pour WA1–WA11 et D1–D33 ;
- aucun canon modifié ;
- `git diff --check` propre ;
- liste de fichiers modifiés limitée à :
  - `docs/steps/current/WRITE_ADMISSION/Step_Plan.md` ;
  - `docs/steps/current/REGISTRATION/Step_Plan.md`.

Commit prévu : `docs: refine registration implementation plan`

Push prévu : `origin/v2-make-it-pull`

Blocking questions : **0**

Le commit et le push sont seulement annoncés : ils ne sont pas exécutés pendant cette session de
planification.
