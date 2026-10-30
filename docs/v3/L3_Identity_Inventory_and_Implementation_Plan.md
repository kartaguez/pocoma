# Pocoma V3 — L3 Identity : inventaire et plan d'implémentation

**30 octobre 2026 — READY FOR INDEPENDENT AUDIT — PLAN NON EXÉCUTÉ.**

Référence examinée : `kartaguez/pocoma`, branche `feat/v3-architecture`,
`8182fef0700d78e42d7d199d61c71847cd67bd4c`. Aucun GO d'implémentation.
Le présent document propose une réalisation ; les SPEC restent normatives.
Aucun résultat prospectif ci-dessous ne vaut test écrit, exécuté ou PASS.

## 1. Mandat, état vérifié et autorités

Le checkout initial `/Users/julien.guezennec/Dev/projects/pocoma` est sur
`feat/runtime-monolith`, HEAD `90a3e9bc1a1fdd325d0c36e543824239cd85a431`,
avec modifications suivies et fichiers non suivis préexistants (application,
documentation, Docker/Bruno). Le worktree existant
`/private/tmp/pocoma-v3-postaud` est propre, sur la branche et au SHA demandés.
Il est utilisé sans checkout, reset, stash, clean, ni modification des autres
worktrees. `git ls-remote origin refs/heads/feat/v3-architecture` a confirmé
la tête distante exacte `8182fef0700d78e42d7d199d61c71847cd67bd4c` : divergence
initiale 0/0 par identité des têtes. L'accès réseau initial restreint a échoué ;
la lecture distante autorisée a ensuite réussi. Refaire ce contrôle avant push.

**L1 ACCEPTED et L2 ACCEPTED WITH EXPLICIT RESERVATIONS sont des acquis.**
Les réserves R-L2-01…05, G1/G2 INCONCLUSIVE, G3 NOT PASSED / NOT COMPLETED,
AUD-I5-01/02 et R-AUD-I3-03 restent tels que transmis dans la
[clôture L2](L2_Documentary_Closure.md). Aucun réexamen d'acceptation,
renouvellement de campagne temporelle ou certificat de production n'est entrepris.

Corpus examiné, avec priorité aux clauses amendées et aux états de clôture :

| Autorité / sources | Apport au présent plan |
|---|---|
| [Canon](V3_Canon_and_Roadmap.md), [plan L0–L8](V3_Implementation_Master_Plan.md) | Deux domaines ; réemploi simple ; progression et frontières L3/L4/L5/L7/L8 |
| [SPEC-02](SPEC-02_Identity_Domain.md) §§1–8 | User autonome, EUI stable, transitions strictes, tokens et verrous |
| [SPEC-03](SPEC-03_Events_Requests_Consumables.md) §§1–7, 9–11 | Demandes durables, sept familles, événements minimaux, cinq séquences, suffixe |
| [SPEC-04](SPEC-04_Read_Projections.md) §§6–8 | Histoire WRITE@version pour CurrentBinding ; résultats et provenance, sans token tiers |
| [SPEC-05](SPEC-05_Consumption_Discovery.md) §§1–7, 11–12 ; [R1–R10](L2_R1_R10_Normative_Consolidation.md) | Participant, owner physique, completion avant suffixe, R9, Discovery et lifecycle |
| [SPEC-06](SPEC-06_Supra_Contracts.md) §§1–3 et contrats Identity | Admission déclarative, idempotence, consentement externe, frontières de confiance |
| [SPEC-07](SPEC-07_Transverse_Requirements.md), [SPEC-10](SPEC-10_Test_Certification.md) §§4–6, 8–9 et amendements | ID-01/02, EVT/CON, sécurité, A10 ; oracles et portée des reports |
| [Politique Reactor](../testing/Reactor_Verification_Policy.md) | Slices par consommateurs réels, CURRENT DB, gates proportionnées |
| [Plan I2](L2_I2_Implementation_Plan.md), [rapport I2](L2_I2_Execution_Report.md) | Propriété Engine, participant, poisoning, R1/R9 et suffixe fermé |
| [Plan I3](L2_I3_Implementation_Plan.md), [rapport I3](L2_I3_Execution_Report.md), [corrections](L2_I3_Post_Audit_Corrections_Report.md) | Acquisition, fencing, retry/quarantaine, classement technique |
| [Plan I4](L2_I4_Implementation_Plan.md), [rapport I4](L2_I4_Execution_Report.md) | Sources actuellement réelles, verrou de source burnable et stratégie B |
| [Plan I5](L2_I5_Implementation_Plan.md), [rapport I5](L2_I5_Execution_Report.md), [runbook](L2_I5_Worker_Development_Runbook.md) | Composition active effectivement livrée, pools, préflight, outils DEV |
| [Consolidation I6/I7](L2_I6_I7_Consolidation.md), [clôture officielle](L2_Documentary_Closure.md) | Réemploi des mécanismes et limites ; les mentions historiques sont supplantées pour les statuts |

Les anciennes mentions « non implémenté » dans les SPEC ne décrivent pas l'état
courant L1/L2. Aucun ancien PASS n'est attribué à Identity réelle. Le plan
directeur reste le registre de progression ; seul ce document est ajouté.

## 2. Constats techniques vérifiés

1. `domain-user-identity` contient des valeurs pures réutilisables :
   `ExternalIdentity` conserve exactement issuer/subject, `User` et
   `PocomaUserId` sont minimaux. Il ne contient pas les transitions V3 ni
   d'identifiant EUI stable et d'histoire EUI@version V3. `BindingId`,
   `BindingRevision`, faits Attached/Detached et CurrentBinding sont V2.
   Le test V2 accepte même Attached à révision zéro : ce contrat ne doit pas
   être importé pour EUI V3, dont la première version métier est 1.
2. Registration V2 possède admission, demande, exécution, outcomes, faits User,
   résultat et plusieurs runtimes. `ExecuteRegistrationService` utilise
   `acquireWithInitializer` : il sait éviter le User perdant, mais dépend des
   objets Binding V2 et de `PublishedEvents`. Il ne constitue pas un participant
   IdentityRequest du moteur L2.
3. Le repository V2 `ExternalIdentityJdbcRepository` supprime la ligne active à
   Unlink. Streams/occurrences/facts complètent son histoire V2, mais n'offrent
   pas directement l'état exact EUI@version V3. Le simple renommage de BindingId
   en bindingToken laisserait une seconde autorité et des événements trop riches.
4. Le matérialisateur V2 CurrentBinding copie User/BindingId depuis le fait ; V3
   doit lire EUI@V car l'événement ne contient pas le token. L'upsert monotone et
   le refus d'une même version divergente sont des idées à reprendre en L4.
   `GetCurrentBindingService.getAttached` filtre les états libres : insuffisant
   pour la réponse V3 `(euiVersion,active,token?)`.
5. V3 a les sept valeurs de `ConsumableFamily`, `RoutingV1`, neuf définitions
   stables et leurs contrats SQL. `IdentityRequest` est déclarée avec UUID
   `2999e75c-272d-5afe-a966-c9364ef021fe` / WRITE. CurrentBinding et IdentityResult
   ont respectivement `7b8d2838-bf7a-5117-868a-e5c14b4c0221` et
   `1d8ff246-358f-5ddd-a7f5-416efcfd7445` / READ. Aucun de ces IDs n'est à recréer.
6. Aucun modèle, store, participant ni producteur événementiel Identity V3 réel
   n'existe au SHA examiné. Les deux sources événementielles productives sont
   `v3_pot_event` et `v3_command_completion_event`. Le CHECK de
   `v3_event_counter` ne permet que POT et COMMAND_COMPLETION.
   L'enum historique `port.transaction.EventFamily` a quatre valeurs V2 ;
   il ne faut pas l'étendre pour brancher V3 sur l'ancien séquenceur.
7. `ConsumptionEngine` protège le slot, puis appelle `Prepared.finalizeIn` dans
   son unique unité physique finale. `complete()` vérifie
   `requireCompletionReady()`, supprime le slot exact, est mono-usage, lié au
   thread/callback et empoisonne l'unité en cas de faute. L'Engine exige ensuite
   une publication fermée. Ces mécanismes existent, sans dépendance Pot/Identity.
8. `V3ConsumptionDiscoveryComposition.requests` refuse toute famille sauf
   COMMAND ; `events` et `JdbcEventDiscoveryReferences` refusent les familles
   autres que POT_EVENT et COMMAND_COMPLETION_EVENT. `DiscoveryEngine` et les
   stores techniques sont déjà génériques : adapter les raccords/sources, pas
   réécrire les algorithmes de Discovery ou d'acquisition.
9. `ExecutePotCommand` consomme `PotActorAuthority.Witness(revision,actor?)`.
   Observation préparatoire, protection finale du témoin positif **ou négatif**,
   puis Pot seulement si actor présent. Un témoin changé déclenche Collision et
   une nouvelle tentative complète après nouvelle acquisition. L'autorité active
   est `V3FixturePotActorAuthority`, limitée à `i5-dev/actor` et
   `i5_fixture_identity`. Les fixtures L1 de tests ne sont pas des fournisseurs
   de production ; elles restent utiles pour isoler leurs oracles.
10. Le profil actif `v3-consumption-worker-dev` assemble uniquement Command et
    deux probes READ techniques. `V3ConsumptionWorkerSettings` lie encore
    l'activation de Command au flag fixtureIdentity. Les allowlists de beans,
    tables, privilèges, noms de couples et les outils doivent évoluer ensemble.
    Le monolithe et le worker Command importent la même composition ; Legacy
    est exclu sous les profils V3. La présence du catalogue n'active aucun moteur.
11. Migrations PRIMARY historiques V1–V31 ; READ V1–V14. CURRENT V3 WRITE :
    `db/v3-pot/migration` V1/V3/V4, `db/v3-pot-execution/migration` V2,
    `db/v3-consumption-write/migration` V5. READ technique :
    `db/v3-consumption-read/migration` V1. V5 supprime le slot Command dédié,
    adapte la contrainte terminale à la clé canonique et fournit SHA-256 SQL.
    Aucune baseline V2 TRUSTED n'est déclarée. Bootstrap/grants sont administratifs,
    Flyway est désactivé dans le runtime DEV et au redémarrage.

L'annexe A donne les chemins exacts et destinations de l'inventaire, y compris
les tests et configurations historiques. Une présence dans le graphe Maven ne
signifie pas activation d'un runtime.

## 3. Architecture proposée et réemploi

Conserver les cinq étapes proposées ; elles correspondent aux risques observés.
Utiliser les modules existants `domain-user-identity`, `contracts-registration`
et `engine-consume-registration`, avec packages `v3` explicites pendant la
transition. Le nom historique du module Registration n'impose pas trois moteurs
ou trois workers ; sa rationalisation globale appartient à L7. Pas de nouveau
module Identity, port-binding bis, Worker générique, Entitlements, Consent
métier autonome ni Binding autonome.

| Ensemble | Décision | Justification / moment |
|---|---|---|
| ExternalIdentity, User, PocomaUserId et tests d'égalité exacts | REUSE | Valeurs déjà pures ; éviter une seconde Eui métier. Adapter uniquement les frontières Pot.Eui existantes |
| Modèle Binding V2 et ports d'acquisition/détachement | REPLACE pour V3 | EUI stable et versions d'états, pas BindingId/occurrences. RETIRE des compositions V3 ; suppression physique DEFER L7 |
| RegistrationRequest V2, ExecuteRegistrationService, stores/outcomes V2 | REPLACE pour V3 | Une IdentityRequest durable et un participant pour trois opérations. Garder l'ancien parcours isolé jusqu'à convergence |
| Principe initializer V2, réservations uniques, tests de courses | ADAPT | Précondition sous verrou avant User ; unicité historique du token, nouvelles preuves avec vrais producteurs |
| CurrentBinding V2, matérialisation et lecture | DEFER L4 | Reprendre monotonie et détection de divergence ; remplacer payload fact→projection par lecture exacte WRITE@V |
| Admission et HTTP V2, idempotence externe, permissions d'entitlements | DEFER L5 | Aucun raccord vers V3 par contournement de consentement ou lecture WRITE à l'admission |
| ConsumptionParticipant, Engine, SlotCompletion, AttemptTransactions/Spring | REUSE | Un owner final et protocole R1/R9 existants ; aucune transaction cachée dans Identity |
| Ownership/slots/capacité/retry/renouvellement et boucles | REUSE | Pas de modification des réserves temporelles ni de scheduler supplémentaire |
| Catalogues et RoutingV1/contrats P=4 | REUSE IDs/hash ; ADAPT capacités livrées | Distinguer déclaré/exécutable ; ne pas changer le hash en fonction d'EUI |
| Discovery sources/closed mappings | ADAPT | Nouvelle source burnable et deux familles réelles ; filtre EXTERNAL_USER_IDENTITY pour CurrentBinding |
| ExecutePotCommand et PotActorAuthority | REUSE contrats et logique | Fournir un adaptateur PostgreSQL réel compatible ; pas de lecture READ |
| V3FixturePotActorAuthority et fixture I5 | RETIRE du parcours L3 | Garder banc DEV historique explicitement isolé ; jamais fallback si autorité réelle indisponible |
| V2 séquenceur/owners et EventFamily | DEFER / hors graphe V3 | Réemployer les garanties, jamais superposer les owners ni convertir les familles |
| DDL V3 existants et scripts administratifs | REUSE migrations publiées ; ADAPT par V6 additive | Ne pas modifier les checksums V1–V5 ni migrer des données V2 sans besoin démontré |
| Docker, Bruno, Keycloak, distribution finale | DEFER L5/L8 | Runtime interne testable ne signifie pas produit externe déployable |

### 3.1 Contrats purs et ports cohésifs

Contrats proposés, noms et signatures à implémenter seulement après GO :

- `ExternalUserIdentityId(UUID)`, `BindingToken` opaque (représentation masquée
  dans `toString`), `ExternalUserIdentity(id,identity,version,userId?,token?)`.
  Version strictement positive, paire User/token indissociable ; User existant
  mais aucune liste de bindings portée par lui. Pas de version globale Identity.
- `IdentityTransitions` : fonctions Registration/Link/Unlink recevant états
  connus/absents, intention et candidats UserId/EuiId/token. Elles retournent
  soit un rejet typé, soit les changements typés non vides. Pas de SQL, horloge,
  UUID/random générés au domaine, JPA/Spring ou callback transactionnel.
  Aucun résultat Unchanged nécessaire pour ces transitions strictes.
- `IdentityRequest` dans les contrats : requestId, requesterEui authentifiée,
  opération scellée Registration/Link/Unlink, token initiateur hors Registration,
  cible exacte pour Link/Unlink, createdAt, contexte d'admission immuable.
  Registration cible nécessairement requesterEui. Aucun UserId destinataire
  fourni par le client, aucune sélection de User à partir du token seul.
- Contexte d'admission capturé dans la demande, sans objet métier Consent :
  provenance du validateur et version de contrat, identité E1/E2, empreinte
  d'intention canonique, référence d'octroi/corrélation, instant de validation,
  attestations externes pertinentes et origine contrôlée de l'admission.
  La forme d'une preuve fournisseur réelle et sa rétention sont L5. Pas de
  nouveau contrôle fournisseur, d'expiration rétroactive ou d'entitlement au retry.
- `IdentityAuthority` : `lockInCanonicalOrder(identities)` (création des seules
  ancres techniques manquantes, verrous retenus jusqu'à résolution), lecture
  courante sous ces verrous, `apply(changes,requestId)`, `at(euiId,version)` et
  lecture de User. Un port cohésif d'autorité/histoire suffit ; ne pas créer
  LockService, UnitOfWork ou port CRUD par table. L'accès historique exact prépare L4.
- `IdentityRequestStore` : lecture d'intention/terminal, insertion PENDING interne
  idempotente, `finishTerminal`, `requireCompletionReady`, `publish`.
  Résultat WRITE CHANGED ou REJECTED, cible/version sur succès, motif fermé sur
  rejet. Pas de bindingToken ni UserId dans le résultat public à préparer pour L4.
- `IdentityRequestObservation` : photographie R9 de demande/résultat/versions/
  événements/positions ; `RecoveryReadContext` obligatoire. L'Engine combine avec
  son observation du slot ; aucun store ne répare une incohérence pendant recovery.
- `ExecuteIdentityRequest implements ConsumptionParticipant` reçoit ces trois
  ports, le générateur `Supplier<BindingToken>` et les fournisseurs de candidats
  UUID nécessaires, par constructeur. Pas de runner transactionnel injecté au
  participant. Une classe pour trois intentions, pas un service par opération.

La préparation RO charge uniquement l'intention immuable et détecte un terminal.
Pour Identity, les décisions sont courtes : les prendre **dans l'unité finale**
sous verrous EUI, sans proposition spéculative coûteuse ni CAS métier parallèle.
`Prepared` garde le résultat effectif pour `requireCompletionReady` et publie ce
même résultat. Ne pas rendre mutable un résultat partagé entre tentatives.

### 3.2 Verrous, EUI inconnues et autorité Pot

Proposition physique : une ancre technique durable `v3_eui_authority` par couple
opaque, avec ID stable et `current_version=0` tant qu'aucune EUI métier n'existe.
**Zéro est un témoin technique d'absence, jamais EUI@0.** La première histoire
métier est directement liée v1 ; pas de faux état libre v1, d'événement de
création intermédiaire ou d'User précréé. Une ancre seule peut subsister après
un rejet négatif ; elle ne constitue ni mutation métier ni EUI publiable.
Cette adaptation interne borne le verrou d'absence exigé par SPEC-05 et le témoin
négatif de Pot. Elle doit être vérifiée explicitement par l'audit.

Pour les clés E1/E2 dédoublonnées et triées lexicographiquement `(issuer,subject)` :

1. Après protection finale du slot, pour **chaque clé successivement dans cet
   ordre**, `INSERT ... ON CONFLICT (issuer,subject) DO NOTHING` de l'ancre,
   puis `SELECT ... FOR UPDATE` de cette même ancre. Ne pas insérer toutes les
   cibles dans un ordre arbitraire avant le tri ; l'INSERT conflictuel attend aussi.
2. Comparaison opaque déterministe commune Java/SQL, sans lowercase, trim ni
   normalisation URL. Définir l'ordre comme ordre des octets UTF-8 non signés,
   issuer puis subject ; colonnes PostgreSQL `COLLATE "C"`. Tester accents,
   caractères hors BMP et préfixes. Un comparateur Java UTF-16 par défaut ne
   suffit pas à garantir cet ordre. PostgreSQL text exclut U+0000 ; son refus
   intrinsèque à la frontière de stockage ne doit pas transformer la clé.
3. Lire les états actuels **après** ces verrous et vérifier E1/token puis E2.
   E1==E2 prend un seul verrou ; Link se rejette car cible déjà liée, self-Unlink
   est valide. Le User destinataire est résolu d'E1 sous verrou. Aucun verrou de
   partition dans la finalisation ; le claim individuel est l'autorité Engine.
4. Registration vérifie l'absence/liberté puis crée son User dans cette unité.
   La tentative concurrente attend puis voit l'état lié et se rejette sans User.
   Registration/Link et deux Links n'ont aucune priorité ; la première transition
   validée sous les verrous gagne. Unlink croisé ne peut conserver deux initiateurs
   valides ; l'Unlink concurrent d'E1 ne peut se glisser entre contrôle et mutation.
5. Retenir tous les verrous jusqu'au commit/rollback. User ne se supprime jamais ;
   ses FK ne créent pas un second protocole de verrouillage User. Aucun appel
   ultérieur ne reprend une clé EUI inférieure à une clé déjà détenue.

Pas d'advisory lock hashé pour remplacer les verrous EUI normatifs ; pas de
verrou global Identity. Le tri évite les cycles EUI, sans promettre l'absence
universelle de deadlock de toute l'application : tester aussi FK, source,
Discovery et suffixe ; un deadlock SQL technique reste rollback/reprise L2.

`JdbcIdentityPotActorAuthority` implémente le port Pot inchangé : observation RO
par couple exact, revision=version métier ou zéro si absence ; actor présent
uniquement si token présenté correspond à l'état lié. Protection finale par la
**même ancre** que Identity, puis comparaison complète du Witness. Changement
entre observation et protection ⇒ Collision, jamais faux INVALID_BINDING figé.
Inconnu stable ⇒ rejet INVALID_BINDING protégé sans lecture/verrou Pot. Une
ancre à zéro ne change pas le Witness ; EUI réellement créée ⇒ version positive.
Le mapping PocomaUserId→UserId est explicite à l'adaptateur, sans dépendance du
domaine Identity au domaine Pot. Aucun changement à `ExecutePotCommand` requis.

### 3.3 Une transaction physique, R1 et suffixe

```text
Engine : préparation RR RO, fermée
Engine : unité finale RC RW, slot FOR UPDATE, horloge PostgreSQL fraîche/fencing
Participant : relire demande immuable → ancres EUI triées → préconditions
             → User éventuel + EUI@version + curseur → résultat + TERMINAL
Participant : completion.complete()
Engine : requireCompletionReady effectif → DELETE exact du seul slot
Participant : enveloppes mutation(s) + complétion → flush/contraintes métier
             → closePublication → positions IDENTITY puis IDENTITY_COMPLETION
Engine : vérifier completion et fermeture → Spring commit / rollback / R9
```

Avant la completion, vérifier état durable exact, ownership `writer_xid`, nombre
et identité des changements de cette demande. Un rejet n'a aucun User ni état
EUI nouveau. Les enveloppes et la complétion sont créées avant fermeture ; la
complétion a `subjectId=requestId`, `subjectType=REGISTRATION|LINK|UNLINK`, sans
requestId dupliqué, version artificielle, résultat ni token. Mutation :
`eventId,subjectType,subjectId,subjectVersion,requestId,recordedAt`, aucun eventType,
token, User recopié ni état complet. Registration publie User v1 puis EUI@V dans
le même lot IDENTITY ; Link/Unlink publient un événement EUI.

Les FK/contraintes métier différées pouvant acquérir des verrous sont forcées
avant fermeture. Les contraintes terminales/publication, **lectures seulement**,
restent différées jusqu'au commit ; lister leurs noms exacts lors de la migration.
Après fermeture : seuls UPDATE des compteurs préexistants et INSERT des positions.
Pour Registration, une allocation transactionnelle de deux positions consécutives
préserve l'ordre User puis EUI ; pas d'intercalation, pas de nextval. Aucun ordre
global POT/IDENTITY n'est instauré. `SpringAttemptTransactions.guarded` condamne
l'unité même sur faute Java/SQL interceptée ; aucun `REQUIRES_NEW`, manager privé,
connexion indépendante ou owner V2 dans un writer.

R9 compare en une photographie RO demande, résultat, effets exacts par requestId,
événements et positions. TERMINAL cohérent + slot absent = COMPLETED ; PENDING
sans effets + slot présent = NOT_COMPLETED ; incohérences = INCONSISTENT ; lecture
impossible = UNAVAILABLE. Aucune correction, republication ou déduction rollback
par délai/absence de réponse. Une acquisition donne une tentative complète ; les
reprises, budgets et quarantaines restent L2, sans nouvelle boucle dans Identity.

## 4. DDL et migrations proposés (non exécutés)

Créer seulement après GO :
`app/infra-persistence-primary-jpa/src/main/resources/db/v3-identity/migration/V6__identity_authority_and_requests.sql`.
V6 est le prochain numéro du **jeu de locations WRITE V3 partagé** au SHA de
référence ; recontrôler l'absence de collision avant code. Pas de V32 historique,
ni modification de V1–V5. Les bases de tests actuelles doivent conserver leur
bootstrap V1…V5 isolé lorsqu'elles prouvent L1/L2 ; les nouveaux scénarios L3
chargent également V6. Une nouvelle cible L3 charge V1…V6 dans la même history
Flyway WRITE. Aucune migration des tables V2, ancres I5 ou données utilisateurs
n'est présumée nécessaire.

### 4.1 Schéma de référence

DDL de conception, complété impérativement par les contraintes §4.2 dans V6 ;
il n'est pas un script prêt à exécuter isolément. Tous les objets sont dans le
schéma WRITE explicite. Noms de contraintes stables à donner dans la réalisation.

```sql
create table v3_identity_request (
  request_id uuid primary key,
  requester_issuer text collate "C" not null,
  requester_subject text collate "C" not null,
  operation text not null check(operation in ('REGISTRATION','LINK','UNLINK')),
  binding_token text,
  target_issuer text collate "C" not null,
  target_subject text collate "C" not null,
  admission_context jsonb not null,
  created_at timestamptz not null,
  status text not null default 'PENDING' check(status in ('PENDING','TERMINAL')),
  check ((operation='REGISTRATION') = (binding_token is null)),
  check (operation<>'REGISTRATION' or
    (requester_issuer=target_issuer and requester_subject=target_subject))
);
create index v3_identity_pending_idx on v3_identity_request(request_id)
  where status='PENDING';

create table v3_eui_authority (
  eui_id uuid primary key,
  issuer text collate "C" not null,
  subject text collate "C" not null,
  current_version bigint not null default 0 check(current_version>=0),
  unique(issuer,subject)
);
create table v3_pocoma_user (
  user_id uuid primary key,
  created_request_id uuid not null unique references v3_identity_request(request_id),
  writer_xid xid8 not null default pg_current_xact_id()
);
create table v3_eui_version (
  eui_id uuid not null references v3_eui_authority(eui_id),
  version bigint not null check(version>=1),
  user_id uuid references v3_pocoma_user(user_id),
  binding_token text unique,
  request_id uuid not null unique references v3_identity_request(request_id),
  writer_xid xid8 not null default pg_current_xact_id(),
  primary key(eui_id,version),
  check((user_id is null)=(binding_token is null))
);
-- UNIQUE(binding_token) conserve toutes les anciennes occurrences non nulles.
-- Pas d'index inverse par User requis ; l'état courant se joint au curseur.

create table v3_identity_request_result (
  request_id uuid primary key references v3_identity_request(request_id),
  outcome text not null check(outcome in ('CHANGED','REJECTED')),
  target_eui_id uuid,
  target_version bigint,
  rejection text,
  foreign key(target_eui_id,target_version) references v3_eui_version(eui_id,version),
  check ((outcome='CHANGED' and target_eui_id is not null and target_version is not null and target_version>=1
                            and rejection is null)
      or (outcome='REJECTED' and target_eui_id is null and target_version is null
                             and rejection is not null))
);
create table v3_identity_event (
  event_id uuid primary key,
  subject_type text not null check(subject_type in ('POCOMA_USER','EXTERNAL_USER_IDENTITY')),
  subject_id uuid not null,
  subject_version bigint not null check(subject_version>=1),
  request_id uuid not null references v3_identity_request(request_id),
  recorded_at timestamptz not null,
  unique(subject_type,subject_id,subject_version),
  unique(request_id,subject_type)
);
create table v3_identity_completion_event (
  event_id uuid primary key,
  subject_type text not null check(subject_type in ('REGISTRATION','LINK','UNLINK')),
  subject_id uuid not null unique references v3_identity_request_result(request_id),
  recorded_at timestamptz not null
);
```

L'unicité `v3_eui_version.request_id` convient : les trois opérations changent
au plus **une** EUI cible. Registration crée également un User, dans l'autre
table ; elle n'altère pas une seconde EUI. Un rejet ne réserve pas de version.
Le User détaché de sa dernière EUI reste valide : « aucun User orphelin » vise
la tentative Registration perdante/partielle, pas l'interdiction d'un User sans
EUI après un Unlink légitime.

### 4.2 Contraintes, publication et qualité à ajouter au même DDL

| Objet / contrôle | Garantie obligatoire |
|---|---|
| Demande et contexte | Champs EUI non vides, formes intrinsèques token/context ; intention immuable, DELETE interdit, seule transition PENDING→TERMINAL, pas d'insertion terminale admise |
| Ancre | issuer/subject/eui_id immuables, pas de suppression ; curseur n'avance que 0→1 ou N→N+1 avec histoire exacte. Zéro sans histoire, positif désigne l'unique état courant ; trigger différé local, pas audit de toutes les EUI |
| Histoire/User | INSERT seulement, writer_xid réel non falsifiable, suite N+1 sous verrou, FK exactes ; User créé par Registration réussie uniquement. Toute création métier doit avoir demande terminale/effet/événement correspondant au commit |
| États EUI | Contrôle positif de version, paire null cohérente, unicité historique du token ; aucune réécriture d'ancienne version, même après re-Link au même User |
| Résultat | Immuable ; cible égale à la cible exacte de la demande ; motif de rejet fermé ; succès uniquement avec l'état créé par cette demande et ce writer_xid |
| Événement mutation | Subject typé existant à version exacte ; User version=1 et requestId créateur ; EUI version/requestId exacts ; interdiction d'événement sans état et d'état sans événement |
| Complétion | Une par résultat, type correspondant à l'opération ; aucune duplication d'état, de version ou de provenance |
| Unité terminale | TERMINAL⇔résultat+complétion positionnée+absence du slot Identity exact. Registration succès = 1 User + 1 EUI version + 2 mutations ; Link/Unlink succès = 0 User + 1 version + 1 mutation ; rejet = 0 User/version/mutation, complétion seule |
| Positions | Étendre le CHECK existant de `v3_event_counter` à POT, COMMAND_COMPLETION, IDENTITY, IDENTITY_COMPLETION et insérer les deux nouvelles lignes à zéro. INVITATION reste L5 ; aucun producteur fictif. Relier chaque nouvel événement à exactement une position de sa famille ; interdire position orpheline/cross-family et enveloppe non positionnée au commit |
| Immuabilité | Interdire UPDATE/DELETE résultat, histoire, User, événements et positions ; ne pas réécrire le trigger Command ni modifier ses 22 contraintes métier |
| Portée des contrôles | Checks/FK/trigger locaux par demande, EUI/version et PK, pas reconstruction de chaîne ni scan global dans le chemin critique ; vérificateur exhaustif réservé à maintenance explicite |

L'extension de CHECK doit utiliser le nom réellement trouvé dans `pg_constraint`
(au bootstrap V2 V3 : `v3_event_counter_family_check`), sans drop/recréation des
compteurs ou positions. Décrire les nouveaux triggers différés exacts et les
contraintes forcées avant suffixe dans le contrat physique I2. Tester de manière
adversariale les écritures directes sous rôle runtime, pas seulement le chemin
Java heureux. Les ACL ne peuvent distinguer à elles seules un appel R1 d'un SQL
arbitraire disposant du même DELETE : garder le périmètre de writers de confiance
et ses contrôles d'architecture explicites, comme L2.

Tokens : générateur concret `SecureBindingTokenGenerator` utilisant
`SecureRandom`, 32 octets indépendants (256 bits), encodage Base64 URL sans
padding ; aucune dérivation d'EUI, requestId, timestamp, compteur ou UUID choisi
par l'appelant. Inspection du générateur + tests de contrat + unicité SQL
historique ; une poignée de tokens différents ne prouve pas l'entropie. Collision
forcée du token ⇒ rollback intégral technique, nouvelle acquisition/nouvelle
tentative ; jamais succès réutilisant le token, rejet métier simulé ou retry
interne sous le claim. Pas de valeur de token dans assertions exportées,
exceptions SQL publiques, toString des demandes, logs ou labels métriques.

## 5. Catalogues, Discovery, permissions et compositions

### 5.1 Sources réelles et contrat de routage

- Garder neuf IDs canoniques, famille/autorité/P=4, `sha256-v1` et les vecteurs.
  Requests routées par requestId ; événements par eventId, jamais par EUI ni User.
  Les locks métier restent indispensables entre partitions.
- Ajouter `JdbcIdentityRequestDiscoveryReferences implements BurnableReferences`
  en reprenant le petit SQL borné Command sur la nouvelle table : borne U,
  pages UUID PostgreSQL, PENDING et `FOR UPDATE SKIP LOCKED` lors de revalidation.
  Aucun slot à l'insertion interne. Ne pas créer un registre paramétrable de SQL.
- `JdbcEventDiscoveryReferences` reçoit deux branches fermées nouvelles.
  IDENTITY lit les **deux** types ; IDENTITY_COMPLETION valide les trois types
  d'opérations. Ne pas garder la validation « un subjectType unique » actuelle.
  Pour CurrentBinding, avancer le checkpoint sur toutes les positions examinées,
  enrôler seulement EXTERNAL_USER_IDENTITY. Pour IdentityResult, enrôler toutes
  les complétions. Aucun filtre ne cache un trou/enveloppe manquante du préfixe.
- Étendre les factories de `V3ConsumptionDiscoveryComposition`, sans activer en
  L3 les matérialisateurs READ L4. Tester ces mappings/filtres avec un banc READ
  identifié comme technique ; ne pas exposer CurrentBinding/IdentityResult réels.
- `JdbcDiscoveryAdmissionStore`, `JdbcConsumptionSlotStore`, ownership, navigation
  et loops restent inchangés. Examiner le cycle source/slot lors du raccord :
  Discovery B verrouille source puis relit sans verrou le slot ; finaliseur a
  slot puis source. Conserver cette relecture sans `FOR UPDATE` ni FK slot→source.

### 5.2 Droits minimaux et bootstrap

Étendre explicitement les allowlists Java et scripts L2, sans `GRANT ALL` ni
`SECURITY DEFINER`. Bootstrap/migration par admin sur cible neuve autorisée,
jamais au démarrage du Worker. Les scripts historiques restent disponibles pour
les cibles I5 V1…V5 ; le chemin L3 sélectionne le jeu de droits supplémentaire
par présence contrôlée de V6 et mode réel explicite, pas par simple fallback.

| Principal | Droits L3 nécessaires / interdits |
|---|---|
| Runtime WRITE, pools Business/Control même rôle existant | SELECT Identity et publications ; INSERT ancres/User/histoire/résultat/événements/positions ; UPDATE ancre(current_version), demande(status), compteur(position). Pas INSERT demande, UPDATE histoire/intention, DELETE métier, DDL/TRUNCATE, seed routage ni droits admin |
| Injecteur interne DEV distinct | INSERT/SELECT demande par port interne ; accès aux éléments de démonstration strictement nécessaire, sur cible DEV seulement. Aucun droit de mutation EUI/User/résultat/slot ; relecture token pour démonstration uniquement par chemin confidentiel dédié |
| Lecteur WRITE pour matérialisation L4 | SELECT historique exact et demande/résultat ; aucun droit métier WRITE. En L3, banc technique sous rôle de test explicite |
| Runtime READ | Droits techniques L2 existants uniquement, aucune mutation Identity WRITE ; pas de nouvelles tables READ métier en L3 |
| Réactivation | Droits existants sur même slot QUARANTINED, pas admission ni mutation Identity |

Les FK/triggers en mode invoker exigent aussi leurs SELECT ; les inventorier
réellement et exercer chaque rôle non propriétaire. Valider privilèges effectifs,
héritage, PUBLIC, CREATE/TEMP et identité DB/schéma/rôle/adresse avant toute boucle.
Aucune datasource de repli ou compte admin injecté au Worker.

### 5.3 Raccord actif minimal

Adapter le profil DEV existant, les deux racines et leurs gardes, sans nouveau
Worker ni nouvelle boucle. Ajouter une sélection **explicite et exclusive**
`identity-authority=fixture|real` et activation nommée `IdentityRequest`.
Le mode real exige V6 et refuse l'autorité fixture ; il permet Command avec
fixtureIdentity=false. Ne pas déduire Command du flag fixture. À I3, le mode réel peut activer IdentityRequest seul ; Command réel n'est
activable qu'après l'adaptateur I4. Les couples
IdentityRequest et Command partagent la WorkerCapacity et les ressources WRITE
existantes ; la préparation/autorité et la finalisation utilisent la datasource
Business, le lifecycle/Discovery la Control selon le raccord L2. Ne pas ajouter
une datasource Identity ou un transaction manager propriétaire.

Conserver le profil inerte `v3-consumption-pilot`. Les profils DEV historiques
avec fixture restent des bancs isolés, pas des chemins de repli. Legacy,
controllers métier, workers Registration/Binding V2, migrations automatiques et
beans de type indéterminé restent exclus avant création des ressources. Pas de
nouvel endpoint HTTP d'injection ou de lecture WRITE pour récupérer un token.

## 6. Développement ordonné et fichiers exacts

Chaque étape exige son GO et sa déclaration de scope avant code. Les chemins
sont relatifs à la racine ; l'annexe A développe les fichiers existants. Les
préfixes ci-dessous sont des **règles d'expansion exactes**, pas des packages
hypothétiques à rechercher :

| Code | Préfixe exact |
|---|---|
| D | `app/domain-user-identity/src/main/java/com/kartaguez/pocoma/domain/useridentity/v3/` |
| C | `app/contracts-registration/src/main/java/com/kartaguez/pocoma/contracts/registration/v3/` |
| E | `app/engine-consume-registration/src/main/java/com/kartaguez/pocoma/engine/consume/registration/v3/` |
| P | `app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/identity/` |
| PD | `app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/consumption/` |
| S | `app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/v3/` |
| DT | `app/domain-user-identity/src/test/java/com/kartaguez/pocoma/domain/useridentity/v3/` |
| ET | `app/engine-consume-registration/src/test/java/com/kartaguez/pocoma/engine/consume/registration/v3/` |
| PT | `app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/identity/` |
| RT | `app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/v3/` |

### I1 — Domaine pur et contrats

Créer D:`ExternalUserIdentityId.java`, `BindingToken.java`,
`ExternalUserIdentity.java`, `IdentityTransitions.java`,
`IdentityTransitionResult.java` ; C:`IdentityRequest.java` ;
E:`IdentityAuthority.java`, `IdentityRequestStore.java`,
`IdentityRequestObservation.java`. Les opérations/résultats/faits de taille
réduite sont records/enums scellés imbriqués dans ces fichiers, sans multiplication
de wrappers ni nouveau catalogue de faits universel.

Modifier dès I1 `app/engine-consume-registration/pom.xml` pour la dépendance
`engine-consumption` requise par le contrat d'observation et `RecoveryObservation` ;
déclarer directement `port-transaction` si le contrat référence RecoveryReadContext,
au lieu de dépendre implicitement du port Binding V2. Cette arête est volontaire
et impose le gate architecture annoncé. Ni domaine ni contrats ne dépendent de
l'Engine.

Créer DT:`IdentityTransitionsTest.java`, `IdentityValueContractsTest.java` ;
ET:`IdentityRequestContractTest.java`. Conserver tests V2 et valeurs réutilisées,
aucun changement de leur sémantique. Vérifier les trois opérations, versions,
nulls et égalité opaque ; pas de logique Spring ou de persistance.

Sortie : contrat cohérent avec SPEC-02/03 et PotActorAuthority ; transitions
strictes et aucune nouvelle dépendance domaine→infrastructure. Runtime : **NON**.

### I2 — Autorité PostgreSQL, histoire et verrouillage

Dépend I1. Créer P:`JdbcIdentityAuthority.java`,
`SecureBindingTokenGenerator.java` ; migration V6 §4 (incluant dès cette étape
les tables demande/publication nécessaires aux FK, sans participant actif).
Créer PT:`IdentityAuthorityPostgresTest.java`,
`IdentityConcurrencyPostgresTest.java`, `IdentitySchemaPostgresTest.java`,
`BindingTokenGeneratorTest.java`. Les insertions de demandes des tests sont des
fixtures contrôlées ; elles ne deviennent pas une admission externe.

POM `app/infra-persistence-primary-jpa/pom.xml` : réutiliser ses dépendances déjà
présentes aux contrats/engine/domain Identity ; ne pas en ajouter par réflexe.
En cas de tests de primitives avant I3, leurs unités doivent déjà satisfaire le
DDL terminal complet, par harness de test explicite et non par désactivation des
contraintes. Aucun writer autonome de production.

Sortie : invariants DB locaux, histoire directe exacte, qualité des tokens,
verrous canonique connu/inconnu, courses et rollback démontrés. Runtime : **NON**,
une primitive appelée par un test ne livre pas le moteur.

### I3 — IdentityRequest et participant réel L2

Dépend I2. Créer E:`ExecuteIdentityRequest.java` ; P:
`IdentityRequestJson.java`, `JdbcIdentityRequestStore.java`,
`JdbcIdentityRequestObservation.java` ; PD:
`JdbcIdentityRequestDiscoveryReferences.java` ; S:
`V3IdentityRequestComposition.java` (factory explicite, pas owner).

Modifier PD:`JdbcEventDiscoveryReferences.java` ; S:
`V3ConsumptionDiscoveryComposition.java`, `V3BootstrapGuard.java`,
`V3ConsumptionWorkerSettings.java`, `V3ConsumptionWorkerConfiguration.java`.
Garder `V3ConsumptionCatalogConfiguration.java` et ses UUID ; modifier sa
présentation/test seulement si nécessaire, pas son identité.

Conserver les arêtes Engine/port transaction ajoutées dès I1. Modifier les POM
exacts `app/composition-runtime-spring/pom.xml` (dépendance directe optionnelle
engine-consume-registration, au lieu de compter sur supra),
`app/runtime-command-consumption-worker/pom.xml` (engine-consume-registration et
contracts-registration si utilisés par ses outils). Les arêtes restent orientées
composition→engine→ports/domaine. Le runtime monolithe possède déjà le graphe
Registration ; vérifier le besoin réel avant tout changement de son POM.

Modifier `scripts/v3/grant-l2-lifecycle.sql`, `grant-l2-discovery.sql`,
`bootstrap-l2-worker-dev.sql`, `grant-l2-worker-dev.sql`, `worker-dev.py` ;
`bootstrap-l2-routing.sql`, `reactivate-l2-slot.sql` et
`check-routing-vectors.py` restent inchangés (IDs, routage et lifecycle conservés).
Modifier l'outil exact
`app/runtime-command-consumption-worker/src/main/java/com/kartaguez/pocoma/runtime/command/v3/dev/V3WorkerDevelopmentTools.java`
pour préparation administrative V6 explicite, insertion interne PENDING,
inspection terminale bornée et non-divulgation. Pas de remise à zéro au restart.

Modifier les configurations exactes
`app/runtime-command-consumption-worker/src/main/resources/application-v3-consumption-worker-dev.properties`
et `app/runtime-monolith/src/main/resources/application-v3-consumption-worker-dev.properties`
si leurs paramètres de mode l'exigent ; elles gardent Flyway/OSIV/DB health et
legacy désactivés. Aucun changement des profils HTTP historiques.

Créer ET:`ExecuteIdentityRequestTest.java` ; PT:
`IdentityRequestExecutionPostgresTest.java`, `IdentityPublicationPostgresTest.java`,
`IdentityDiscoveryPostgresTest.java`, `IdentityPrivilegesPostgresTest.java` ;
RT:`V3IdentityRequestCompositionPostgresTest.java`.

**Milestone runtime interne R-ID : fin I3**, une fois factory, activation,
scripts, V6, Discovery et outils livrés ensemble et tests réussis. Le jar du
worker existant peut démarrer et traiter Registration/Link/Unlink sous admissions
internes contrôlées sur PostgreSQL réel. Si seule la factory est livrée, R-ID
reste NON atteint ; ne pas reporter silencieusement l'activation à I5 puis
annoncer un runtime dès I3.

### I4 — Autorité Identity réelle consommée par Pot

Dépend I3. Créer P:`JdbcIdentityPotActorAuthority.java`.
Modifier `app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/command/v3/V3PotCommandComposition.java`
si nécessaire à la factory réelle (conserver l'injection du port), et
S:`V3ConsumptionWorkerConfiguration.java`, `V3ConsumptionWorkerSettings.java`,
`V3BootstrapGuard.java` pour sélectionner exclusivement l'autorité réelle.
Ne pas modifier `ExecutePotCommand`, `PotCommand`, `PotActorAuthority` ni les
révisions Pot pour convenance de l'adaptateur.

Créer PT:`IdentityPotAuthorityPostgresTest.java` ;
RT:`V3IdentityPotCommandPostgresTest.java`.
Compléter RT:`V3PotCommandCompositionPostgresTest.java` avec un parcours réel
séparé du banc fixture ; conserver ses oracles L1/L2.
La classe `V3FixturePotActorAuthority.java` reste fixture isolée ; aucune branche
real ne la référence. Ne supprimer ni réécrire les tests historiques pour faire
passer le raccord.

Sortie R-ID-POT : Registration réelle → Command réelle ; Link/Unlink et
Command concurrents, ancien token refusé sous WRITE malgré READ périmé,
Collision en cas de changement après préparation, INVALID_BINDING négatif sans
Pot. Un User sans EUI après dernier Unlink persiste et ne peut être récupéré par
simple UserId ; nouvelle Registration crée un autre User.

### I5 — Qualification bornée, compositions et clôture

Dépend I4. Compléter les tests L3 des étapes précédentes ; créer
`app/runtime-monolith/src/test/java/com/kartaguez/pocoma/V3IdentityMonolithPostgresTest.java`
et
`app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/v3/V3IdentityArchitectureTest.java`.
Adapter seulement si leurs listes exactes l'imposent :
`app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/RegistrationModuleBoundaryTest.java`,
`app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/v3/V3ConsumptionEngineArchitectureTest.java`
et `V3ConsumptionFoundationArchitectureTest.java` dans ce même répertoire.
Conserver les règles V2 applicables dans leurs packages, sans désactiver une règle
pour masquer une dépendance interdite.

Livrer, dans cette future étape et après GO seulement,
`docs/v3/L3_Identity_Execution_Report.md`,
`docs/v3/L3_Identity_Runtime_Runbook.md`,
`docs/v3/L3_Identity_Proof_Manifest.json` et
`docs/v3/L3_Identity_Closure.md` ; mettre à jour le plan directeur lors du statut
réel. N'ajouter aucun de ces rapports vides pendant le présent inventaire.
Conserver reçus compacts, SHA testé propre, XML frais, oracles nominatifs et
empreintes ; archives volumineuses selon la politique existante.

Sortie : R-ID-POT démontré dans les deux racines, audit indépendant et décision
humaine L3 dans le périmètre réel. Aucune clôture automatique ID-02/SEC/READ ou
certification globale V3. Ni chantier L4 ni HTTP/Bruno L5 commencé.

## 7. Matrice de tests et oracles (tous prospectifs / NOT RUN)

Un oracle de succès compare depuis une connexion indépendante un vecteur avant/
après : Users, ancres, états EUI exacts, intentions, résultats, événements,
positions/compteurs et slot. Ne pas calculer l'attendu en appelant les fonctions
productives de transition/reconstruction. Barrières, PID/XID et observations
PostgreSQL prouvent les entrelacements ; aucun sleep ne constitue une preuve.
Les noms `l3_*` ci-dessous sont des méthodes proposées dans les classes exactes
§6 ; chaque variante est une allocation distincte dans le futur manifeste.

| Étape / classe | Méthodes / stimuli | Oracle SPEC-10 / preuve attendue |
|---|---|---|
| I1 IdentityTransitionsTest | `l3_domain_registration`, `l3_domain_link`, `l3_domain_unlink`, `l3_domain_rejections` | I01-A/B/C/E/G/H/I/L/M/P : v1 ou N+1 exact, strict, aucun transfert direct, nulls cohérents, même User déduit d'E1 |
| I1 IdentityValueContractsTest / IdentityRequestContractTest | `l3_values`, `l3_request_shape` | I01-M, I02-M/Q interne : égalité opaque, Unicode, E1==E2, token obligatoire hors Registration, UserId arbitraire impossible, pas de fuite toString |
| I2 IdentityAuthorityPostgresTest | `l3_history_exact`, `l3_last_unlink` | I01-A/B/H/L : histoire U1/B→libre→U2/C→libre→U1/D exacte par PK ; anciennes valeurs immuables ; User demeure |
| I2 IdentitySchemaPostgresTest | `l3_sql_negative_shapes`, `l3_sql_publication_closure` | I01-M/N ; E02-I et contraintes de C06-B : doublon couple, état hybride, trou de version, faux xid, version réécrite, mutation sans bonne demande/événement, publication sans état refusés |
| I2 BindingTokenGeneratorTest | `l3_generator_contract`, `l3_forced_token_collision` (SQL dans AuthorityPostgresTest) | I01-L/N et preuve qualité du générateur : SecureRandom/256 bits, encodage, masquage ; ancien token forcé provoque rollback technique sans User/état partiel |
| I2 IdentityConcurrencyPostgresTest | `l3_registration_race` connu libre + inconnu ; `l3_link_races` deux Links + Registration/Link ; `l3_cross_unlinks` ; `l3_link_vs_initiator_unlink` | I01-D/F/J/K, I02-I/J/N interne : un seul gagnant, perdant rejet terminal après nouvelle lecture sous verrou, aucun User perdant, ordre EUI identique entre partitions |
| I2 même classe | `l3_canonical_unicode`, `l3_unknown_key_race`, `l3_disjoint_progress` | SPEC-02 §7 / C06-B : INSERT inconnus en ordre, E1/E2 inversées, même sujet/deux issuers, collisions de clés ; EUI indépendantes sans verrou global métier |
| I3 ExecuteIdentityRequestTest | `l3_participant_order`, `l3_rejection_terminal`, `l3_technical_not_rejected` | R1, E02-D/E/F : résultat avant complete avant suffixe ; actual result dans requireCompletionReady ; erreur technique jamais convertie en rejet |
| I3 IdentityRequestExecutionPostgresTest | `l3_atomic_registration`, `l3_atomic_link_unlink`, `l3_rollback_each_boundary` | I01-N, E02-D/E/F, C06-B/E/F/G : coupure avant métier, après User, état, résultat, TERMINAL, DELETE, premier événement, complétion, fermeture et positions ; vecteur intégralement annulé |
| I3 même classe | `l3_fencing`, `l3_completion_misuse` | C03-A…K applicables, C04-A/B/C/G/H/I, R1 : horloge après attente slot, token remplacé/A→B→A, claim expiré avant verrou, expiration après verrou valide, perte partition claim encore valide ; complete absent/double/étranger/tardif/ mauvais thread échoue et poison |
| I3 même classe | `l3_unknown_commit`, `l3_terminal_retry`, `l3_observation_corruption` | I01-O/P, E02-G/H/J, R9 : vrai ACK perdu après commit vs rollback, même requestId résultat identique sans token/User nouveau ; nouveau requestId rejoue les préconditions ; quatre observations et zéro réparation |
| I3 même classe | `l3_retry_quarantine`, `l3_slot_delete_paths` | C05-A/B/D…M, C04-L et extension QUARANTINED de CON-05 : une tentative/claim, collision classée, échec technique compté une fois après rollback certain, N configurable, Q persistante et non réenrôlée, admin réactive le même slot, aucune autre voie DELETE ; UNKNOWN/crash/expiration ne consomment pas le budget supposé |
| I3 IdentityPublicationPostgresTest | `l3_identity_batch`, `l3_dense_rollback`, `l3_rejection_completion_only`, `l3_identity_pair_contention` | E01-A…F, variante G ID/IDCOMP, E02-D/E ; lot User puis EUI consécutif, aucun nextval, IDENTITY→IDENTITY_COMPLETION ; rollback ne consomme aucune position |
| I3 même classe | `l3_suffix_closed`, `l3_physical_unit` | C06-B/E/F/G/H/I/L/M, R1 : même connexion/PID/XID effets/slot/publications, pas nouveau verrou métier après fermeture, faute writer interceptée/manager faux/ambiante/REQUIRES_NEW refusés. Ne pas modifier l'owner pour simplifier le test |
| I3 IdentityDiscoveryPostgresTest | `l3_burnable_discovery`, `l3_discovery_source_slot_race`, `l3_identity_windows` | CON-01/02, EVT-03 : PENDING sans slot puis enrôlement asynchrone unique, TERMINAL jamais réenrôlé, Q conservée ; concurrence Discovery B/source/slot sans cycle ; fenêtre cohérente et K examinant aussi User ignoré |
| I3 IdentityPrivilegesPostgresTest | `l3_effective_privileges`, `l3_wrong_authority`, `l3_internal_admission_idempotence` | C06-H/I/M et S-03 contribution interne : moindre privilège, roles hérités excessifs rejetés, pas accès READ→WRITE, insertion même requestId/intention retourne même source ; autre intention sous cet ID refusée, rollback/réponse perdue n'invente pas une seconde demande |
| I3 V3IdentityRequestCompositionPostgresTest | `l3_runtime_identity`, `l3_preflight_negative` | E02-J, C06-M et adaptation Identity de K : jar/contexte et Engine réels, 3 opérations, opt-in absent, mauvais catalogue/rôle/migration, fixture en real, bean Legacy/factory inconnu refusés avant boucle/effet |
| I4 IdentityPotAuthorityPostgresTest | `l3_positive_negative_witness`, `l3_observation_change`, `l3_foreign_token` | Contrat L1-C/F/F2, S02-C, I01-K/M : witness négatif stable, inconnu créé, version/token changés, token tiers même User refusé ; zéro accès Pot si INVALID_BINDING |
| I4 V3IdentityPotCommandPostgresTest | `l3_registration_command`, `l3_command_vs_unlink`, `l3_relink_old_token` | C06-A/B/L, E02-A/C/H, I02-H/I/K internes : vraie Registration donne autorité Command ; EUI verrouillée jusqu'au commit ; re-Link au même User change token, READ retardé sans effet sur WRITE ; intention/observedVersion Pot intactes |
| I5 V3IdentityMonolithPostgresTest / V3IdentityArchitectureTest | `l3_monolith_real`, `l3_no_legacy_or_second_owner` | C06-M, A-01/I-01 : deux racines mêmes constructs, pas de binding V2 actif, pas worker en plus, pas dépendance Spring au domaine ou dépendance Identity→Pot |

Les variantes C06-F/G de poisoning JPA/JDBC utilisent les sentinelles/harness
I2 existants sous le même owner ; elles n'imposent pas un writer métier Identity
JPA supplémentaire. C06-D, J, N/O/P et C04-J qui concernent des effets READ ne
sont pas certifiés par une transaction Identity WRITE ; seules des contributions
techniques explicitement nommées peuvent être exercées avec des probes.

Les classes de tests L1/L2 inventoriées restent leurs preuves de mécanismes ;
les compléter sur l'autorité réelle plutôt que recopier tout le moteur. Pour
les E01-D/E et paire ID/IDCOMP historiquement NOT RUN, produire des contributions
L3 nommées avec vrais producteurs ; ne pas réécrire le manifeste L2 ni proclamer
EVT-01 global PASS. E01-H complet et les cinq familles demandent aussi les
producteurs Invitation et les campagnes ultérieures appropriées.

### 7.1 Link contrôlé en L3, consentement externe en L5

Les fixtures d'admission internes sont créées par le rôle de test/injecteur DEV
explicitement autorisé, avec contexte immuable marqué `CONTROLLED_INTERNAL`.
Elles prouvent l'exécution **après admission supposée valide**, pas une preuve
cryptographique externe. Le Worker vérifie la cohérence de ce contexte avec E1,
E2 et l'intention enregistrées ; absence ou corruption de ce prérequis est une
anomalie technique de source (pas un Link exécuté ni une fausse certification de
consentement). Le client HTTP ne peut jamais fabriquer cette provenance.

Couvrir les attaques internes : cible substituée, contexte/requestId discordants,
absence de données d'admission Link, token E2 à la place d'E1, UserId arbitraire,
modification après persistance, token dans événement/log/résultat, rôle injecteur
ayant par erreur des droits de mutation. Zéro fallback « consent=true » ouvert
au public. Une origine contrôlée n'est pas une validation fournisseur qualifiée.

| Allocation | L3 / limite | Lot de certification restant |
|---|---|---|
| I01-A…P | Domaine/WRITE réels ; Bruno/API absent | I01-Q accès token READ L4/L5 ; ID-01 global reste incomplet |
| I02-H/I/J/K/M/N/P/Q | Contribution interne : pas de relecture fournisseur au Worker, revalidation WRITE, persistance non substituable, absence de fuite | L5 complète les variantes admission réelle ; pas de PASS ID-02 |
| I02-A…G/L/O, C-01 | **DEFER** authentifications réelles, accord explicite, signature/audience/expiration/rejeu atomique, deux sessions et résumé Pots READ | L5, fournisseur qualifié Keycloak/RAR ou alternative, sans abaissement du contrat |
| S-03 | Seul retry durable même requestId et insertion interne unique en L3 | L5 portée EUI/opération/client, clé client/intention canonique, 409, rétention et anti-rejeu associés atomiquement |
| A10-C/D/E/F/J/K | Provenance WRITE/resultat/complétion préparée en L3 ; zéro token E2 dans résultat E1 | L4 matérialisation + L5 404/200/401/503, propriété par EUI, indépendance de Binding courant |
| I01-Q, SEC-01/02, C06-O | Ports historiques exacts livrés ; aucune projection ou HTTP certifié | L4 CurrentBinding/IdentityResult, L5 contrôle d'accès ; aucune lecture WRITE synchrone par Supra |
| CON/EVT globaux, REC/OPS/PERF | Variantes Identity nommées seulement | L6–L8 selon plan directeur ; réserves L2 inchangées |

## 8. Slices, commandes et limites de vérification

### 8.1 Déclaration du présent mandat

Impact production **NONE**, slice primaire **NONE**, secondaires **NONE**.
Architecture globale **NOT REQUIRED / NOT RUN**, full reactor **NOT REQUIRED /
NOT RUN**, DB/migrations/packaging/runtime **NOT RUN**. Aucun test Maven, Python,
PostgreSQL, Docker, Bruno/k6 ou campagne temporelle exécuté. Lectures statiques,
état Git/distant, résolution de chemins et `git diff --check` seulement.
Aucun franchissement inattendu de slice. Un seul document ajouté dans `docs/v3`.

### 8.2 Déclarations prospectives avant implémentation

Les noms de slices canoniques désignent une capacité avec son **ancre réelle**.
BINDING historique matérialise des faits V2 vers READ : le mot Identity dans ce
plan ne suffit pas à exiger sa slice. La nouvelle exécution sera raccordée au
worker Command existant ; son ancre est donc le consommateur réel principal.
Registration est une preuve complémentaire de l'isolement historique, pas une
nouvelle slice canonique ni une raison de lancer tous les workers Identity V2.

| Étape | Impact attendu / primaire et secondaires | Global / full / DB | Impact interdit et escalade |
|---|---|---|---|
| I1 | Domaine/contrats ; feedback direct domain-user-identity, contracts-registration, engine-consume-registration. Clôture COMMAND avec nouveau graphe pertinent | Architecture REQUIRED au premier changement d'arête ; full NOT REQUIRED ; DB aucune pour tests purs, transitifs tracés | Pas Spring au domaine ni changement Binding V2. Ajouter BINDING/Registration consommateurs si leurs contrats existants changent réellement |
| I2 | Autorité/DDL PRIMARY ; COMMAND primaire via future dépendance déjà présente PRIMARY→engine Registration | Architecture REQUIRED pour nouvelles règles de writers ; full NOT REQUIRED ; CURRENT V3 V1…V6 | Pas migrations V2/READ/owner commun. Toute modification physique owner/routage impose redéclaration et consommateurs supplémentaires |
| I3 | Participant/source/discovery/grants/composition ; COMMAND primaire ; preuve directe monolithe | Architecture REQUIRED (POM/activation/frontières) ; full NOT REQUIRED pour ce raccord borné ; CURRENT WRITE V1…V6, READ technique V1 si probe | Pas projections L4, endpoint L5, nouveau Worker, renewal redesign. EVENT/PROJECTION seulement si leurs comportements productifs deviennent réellement modifiés |
| I4 | Adaptateur autorité Pot et sa composition ; COMMAND primaire ; monolithe direct | Architecture REQUIRED si allowlists/frontières changent ; full NOT REQUIRED ; CURRENT V3 | Pot transitions/révisions inchangées. Changement d'un port Binding V2 ⇒ BINDING et Registration réellement affectés avant poursuite |
| I5 | Clôture interne L3 ; COMMAND + monolithe, architecture ; Registration historique en complément si son raccord/arêtes est affecté | Architecture REQUIRED ; full conditionnel ci-dessous ; CURRENT V3 réel | Pas audit renouvelé L2, aucun PASS global externes/READ. Escalade seulement pour frontière effectivement franchie |

Commandes futures, **depuis `app/`**, jamais exécutées pour ce plan :

```sh
# Feedback pur I1, contribution locale (pas une nouvelle slice canonique)
./mvnw -pl domain-user-identity,contracts-registration,engine-consume-registration -am test

# Capacité réellement raccordée et racine complémentaire : clôture bornée L3
./mvnw -pl runtime-command-consumption-worker,runtime-monolith -am test

# Gate structurelle justifiée par les arêtes/allowlists/writers
./mvnw -pl architecture-tests -am test

# Seulement si le raccord historique Registration est matériellement affecté
./mvnw -pl runtime-registration-consumption-worker -am test

# Seulement si comportement BINDING / WEB / EVENT / PROJECTION changé :
./mvnw -pl runtime-binding-consumption-worker -am test
./mvnw -pl runtime-web-api -am test
./mvnw -pl runtime-event-consumption-worker,runtime-task-consumption-worker -am test

# Intégration globale/major Wave ou release : gate distinct, pas feedback local
./mvnw test
```

Les commandes canoniques non filtrées peuvent exécuter transitivement tests
historiques DB et tests temporels L2 existants : **ce plan ne les lance pas et
n'autorise pas une nouvelle certification I5**. À la reprise d'implémentation,
inventorier ces exécutions avant la campagne ; si le mandat interdit encore leur
exécution transitive, résoudre explicitement ce conflit de gate avec le responsable
avant lancement. Ni filtrage/skip dissimulé, ni PASS historique transféré en gate
fraîche. Ce point d'exécution ne bloque pas l'audit documentaire du plan.

I5 est proposé comme clôture d'un **raccord métier borné** aux mécanismes déjà
acceptés, pas comme requalification globale L1/L2 ni restructuration large.
Un full reactor n'est donc pas le feedback ou la clôture automatique de chaque
étape. S'il est qualifié de clôture de Wave majeure dans le GO, ou si le graphe
réel se restructure largement, le full devient REQUIRED (§§19/21 politique),
avec décision explicite sur les tests transitifs ci-dessus. L'intégration main et
les gates majeurs L7/release le requièrent dans tous les cas. Aucun gate obligatoire
n'est dispensé par ce document.

DB : nouvelles preuves CURRENT V3 isolées. Ne pas prétendre une baseline TRUSTED
V2 ; ne pas lancer une campagne séparée V1–V31 par défaut. Noter les migrations
historiques réellement lancées transitivement dans chaque reçu. Pas `-amd`,
`-DskipTests`, modification d'oracle ou silence sur FAIL/SKIP/INCONCLUSIVE.

## 9. Runtime testable, risques et critères de sortie

### 9.1 Jalon précis et limites

| État | Ce qui devient exécutable | Ce qui manque |
|---|---|---|
| SHA de référence / présent plan | Pilote Command + fixture et probes L2 existants seulement | Aucun cycle Identity V3 réel |
| I1 / I2 | Contrats puis primitives PostgreSQL testées après GO | Pas de service durable exécutable |
| **Fin I3, R-ID** | Jar existant, PostgreSQL réel, bootstrap V6/grants, IdentityRequest PENDING → Discovery → Engine → Registration/Link/Unlink → terminal/events ; admission **interne contrôlée** | Pas HTTP, READ métier, consentement externe certifié ou autorité Pot remplacée |
| **Fin I4, R-ID-POT** | Même runtime avec Command utilisant la vraie autorité Identity | Parcours complet utilisateur encore absent |
| Fin I5 | Démonstration reproductible bornée dans worker et monolithe, dossier auditable | L4/L5 et réserves L2 demeurent, aucun GO production |
| L4 + L5 puis L8 | CurrentBinding/token propre, résultats READ, HTTP/consentement qualifié puis distribution de référence | Qualification globale selon les lots, jamais déduite du seul démarrage |

Après les vérifications requises et sous GO d'implémentation, le packaging
reste celui du runtime existant, depuis `app/` (commande prospective) :

```sh
./mvnw -pl runtime-command-consumption-worker -am package
java -jar runtime-command-consumption-worker/target/pocoma-runtime-command-consumption-worker-0.0.1-SNAPSHOT-exec.jar --spring.profiles.active=v3-consumption-worker-dev --spring.config.additional-location=file:/private/tmp/l3-dev.properties
```

Le fichier externe est à préparer dans I3 avec cibles et rôles exacts autorisés,
mode real, IdentityRequest activé et, avant I4, Command désactivé. Aucun credential
n'est versionné ou passé en argument. La préparation utilise la CLI administrative
existante `python3 -B scripts/v3/worker-dev.py prepare --config /private/tmp/l3-dev.properties`
depuis la racine, après extension V6 ; un restart n'appelle pas `prepare`.
Les sous-commandes d'injection/vérification Identity sont à ajouter dans I3,
avec un fichier d'intention protégé plutôt que des tokens en argv. Ces commandes
ne sont **pas opérationnelles pour Identity au SHA de référence** et ne sont pas
exécutées dans ce mandat. Les deux racines doivent accepter la même configuration
utile et refuser les profils Legacy.

La démonstration R-ID doit créer une cible neuve par admin, démarrer sans DDL
runtime, soumettre deux Registrations et un Link contrôlé, Unlink autre/self/
dernière EUI, vérifier les états exacts et terminalisations indépendamment du
processus, arrêter/redémarrer sans reset et retrouver les mêmes effets. Le token
nécessaire au prochain appel interne est obtenu par un helper **de test/DEV
confidentiel**, pas via une API publique contournant L4. Aucun token E2 n'est
retourné au résultat de Link consulté par E1. L'opérateur de banc n'est pas un
utilisateur HTTP et ses privilèges ne doivent pas être exposés.

La disponibilité/drain peut rester conservatrice selon R-L2-01/02 ; pas de
promesse temporelle nouvelle, modification des tailles de pools ou des échéances
pour faire passer L3. Ajouter un couple modifie la charge et les occasions de
polling : vérifier la composition et la sûreté, sans affirmer les budgets de
renewal/production non certifiés. Si cette charge rend une configuration DEV
refusée par les contrôles existants, documenter le refus ; toute qualification
temporelle distincte reste hors présent mandat.

### 9.2 Risques et preuve de sortie

| Risque concret | Preuve / critère de sortie |
|---|---|
| Faux EUI libre v1 introduit pour verrouiller un inconnu | Ancre zéro purement technique ; première version métier liée=1 ; rejet n'a aucun événement/état métier |
| Deadlock lors des INSERT EUI inconnues ou ordre Unicode incohérent | Tri/dédoublonnage avant chaque INSERT+FOR UPDATE, couples inversés et non-ASCII ; observation PostgreSQL des attentes, pas sleep |
| Ancien token ou fait négatif Pot figé après changement | Même ancre partagée, Witness comparé après lock, collision complète ; tests négatifs sans accès Pot |
| User créé avant le contrôle ou conservé après rollback | Cardinalité exacte par requestId et coupures après création ; User volontairement sans EUI après Unlink conservé |
| Histoire reconstruite depuis courant ou version globale inventée | PK EUI/version directe, snapshots distincts et histoire B/C/D exacte ; CurrentBinding V2 jamais utilisé comme autorité WRITE |
| Token réutilisé/divulgué | Générateur inspecté, UNIQUE sur tout l'historique, collision forcée, inspection events/results/logs/metrics, représentation masquée |
| Terminal ou événement partiel / suffixe réouvrable | R1 exact, writer_xid, poisoning, contraintes différées read-only, crashs à chaque frontière et lecture indépendante |
| Source/discovery bouclant sur Q ou recréant un terminal | Burnable sans checkpoint, protections L2/stratégie B réutilisées, même slot Q jusqu'à admin, faux PENDING détecté |
| Composition déclare Identity mais continue fixture | Tests de graphes/beans/permissions et jar réel, real exige V6 et interdit fixture, deux racines prouvées |
| Consentement de fixture pris pour qualification | Contexte CONTROLLED_INTERNAL, pas endpoint ni validateur permissif, NOT RUN ID-02/C-01 et reports nominaux |
| Nettoyage anticipé perdant une voie historique utile | REPLACE au parcours V3, fichiers historiques gardés isolés, carte RETIRE physique L7 |
| Dérive de scope sous prétexte de gate globale | Déclaration préalable, arêtes POM exactes, commandes reçues, aucune nouvelle campagne I5 automatique |

Critères de sortie techniques L3 : tous scénarios internes obligatoires planifiés
attribués à méthodes/variantes, aucune régression d'invariants L1/L2, zéro double
effet/User perdant/trou de publication, verrous et owner physiques démontrés,
R-ID-POT reproductible, sécurité des tokens et confiance d'admission documentées,
provenance code testé→publié vérifiée. ID-01 global reste incomplet tant que Q et
les parcours externes manquent ; ID-02/C-01, READ et SLO restent aux lots compétents.
L'auteur ne prononce pas sa propre acceptation indépendante.

## 10. Arbitrages et verdict de préparation

Aucun blocage conceptuel démontré pour l'autorité WRITE et son intégration L2.
Les décisions fonctionnelles requises sont déjà dans les SPEC ; ne pas rouvrir
l'acceptation L1/L2 ou inventer un transfert d'EUI, une récupération de User
abandonné, un Binding autonome ou un consentement implicite.

Choix d'implémentation proposés à contrôler pendant l'audit : ancre d'absence
technique zéro distincte d'EUI@1 ; ordre UTF-8/C ; tokens aléatoires 256 bits et
unicité historique ; packages V3 dans modules existants ; contrats de completion
et contraintes locales exactes ; raccord actif dès fin I3. Ils ne prétendent pas
être des amendements normatifs. Si l'audit démontre une contradiction normative,
la traiter avant code, sans extension silencieuse de L3.

Restent des décisions de lots ultérieurs, **non bloquantes pour ce plan** :
format/produit et qualification du consentement externe, canonicalisation/clé
client/rétention S-03, protection et restitution HTTP du token (L5), schéma READ
(L4), qualification temporelle/charge et release (jalons compétents). La sélection
des gates futures et le traitement de leurs tests temporels transitifs doivent
être résolus avant leur exécution, sans requalification implicite de L2.

**Verdict : READY FOR INDEPENDENT AUDIT.** Ce verdict porte sur la préparation du
plan, pas sur son acceptation ni sur la disponibilité d'Identity. Arrêt après
publication documentaire ; implémentation exclusivement après GO humain distinct.

## Annexe A — Inventaire nominatif et destination

Les chemins suivants sont ceux du SHA de référence. Leur décision concerne le
parcours L3 ; REPLACE/RETIRE n'ordonne aucune suppression physique immédiate.
Les fichiers DEFER restent historiques ou appartiennent aux lots L4/L5/L7.
Les tests marqués ADAPT transmettent un oracle applicable, sans transférer un PASS.

Périmètre fermé : tous les fichiers suivis des modules Identity/Registration/
CurrentBinding et ports d'autorité historiques ; familles/authentification ;
classes V3 domaine/engine/transactions/persistance/composition et tests associés ;
racines Command/monolithe, migrations PRIMARY/READ et scripts V3. Les autres
classes Pot/HTTP sans interaction Identity ne demandent aucun changement L3.
Les ressources Docker/Bruno restent recensées séparément à la fin.

| Motif | Justification commune des lignes nominatives |
|---|---|
| R1 | Valeur pure réutilisée directement, sans changer l'égalité opaque |
| R2 | Contrat/mécanisme ou banc V3 conservé ; compléter les preuves réelles séparément, aucun PASS transféré |
| R3 | Migration CURRENT publiée inchangée ; V6 additive seulement |
| R4 | Module/graphe existant réutilisé ; pas de dépendance ajoutée sans besoin de compilation démontré |
| A1 | Raccord explicite d'Identity réelle : sources/types/activation/allowlists ; protocoles L2 conservés |
| A2 | Arête Maven directe indiquée au §6, pas nouveau module |
| A3 | Extension administrative bornée aux objets V6/mode réel, préservation du banc I5 |
| A4 | Réemploi d'un oracle V2 applicable dans un test V3 distinct ; fichier historique préservé sauf nécessité démontrée |
| A5 | Étendre la preuve structurelle à Identity réelle en conservant l'interdiction historique applicable |
| X1 | Contrat Binding V2 remplacé au parcours V3 par EUI stable@version ; retrait physique reporté L7 |
| X2 | Pipeline Registration V2 remplacé au parcours V3 par IdentityRequest/participant L2 ; isolation Legacy maintenue |
| T1 | Retrait de la composition L3 réelle ; conservation éventuelle dans le seul banc fixture historique |
| D1 | READ/matérialisation/resultat historiques : adaptation métier L4, accès externe L5, retrait L7 |
| D2 | Authentification, admission, permissions externes et HTTP : L5 ; valeurs pertinentes conservées |
| D3 | Campagne/qualification temporelle historique hors mandat, aucune réouverture |
| D4 | Chaîne historique immuable, pas migration de données ni nouvelle campagne historique par défaut |
| D5 | Contrat/composition/protocole historique hors activation L3 ; retrait global L7 ou lot compétent |

### A — architecture-tests

| Chemin exact | Décision | Motif |
|---|---|---|
| `app/architecture-tests/pom.xml` | REUSE | R4 |
| `app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/BindingLockOwnershipTest.java` | ADAPT | A5 |
| `app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/RegistrationModuleBoundaryTest.java` | ADAPT | A4 |
| `app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/Wa67BindingArchitectureTest.java` | ADAPT | A5 |
| `app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/v3/V3ConsumptionEngineArchitectureTest.java` | REUSE | R2 |
| `app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/v3/V3ConsumptionFoundationArchitectureTest.java` | REUSE | R2 |
| `app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/v3/V3FoundationArchitectureTest.java` | REUSE | R2 |
| `app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/v3/V3PotExecutionArchitectureTest.java` | REUSE | R2 |
| `app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/v3/fixture/domain/ForbiddenDomain.java` | REUSE | R2 |
| `app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/v3/fixture/domain/ForbiddenSqlDomain.java` | REUSE | R2 |
| `app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/v3/fixture/domain/PureDomain.java` | REUSE | R2 |
| `app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/v3/fixture/engine/ForbiddenApplication.java` | REUSE | R2 |
| `app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/v3/fixture/engine/PureApplication.java` | REUSE | R2 |
| `app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/v3/fixture/engine/sample/port/persistence/BadAnnotationPort.java` | REUSE | R2 |
| `app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/v3/fixture/engine/sample/port/persistence/BadApplicationPort.java` | REUSE | R2 |
| `app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/v3/fixture/engine/sample/port/persistence/BadInfrastructurePort.java` | REUSE | R2 |
| `app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/v3/fixture/engine/sample/port/persistence/BadInheritancePort.java` | REUSE | R2 |
| `app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/v3/fixture/engine/sample/port/persistence/BadJdbcPort.java` | REUSE | R2 |
| `app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/v3/fixture/engine/sample/port/persistence/BadJpaPort.java` | REUSE | R2 |
| `app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/v3/fixture/engine/sample/port/persistence/BadSpringPort.java` | REUSE | R2 |
| `app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/v3/fixture/engine/sample/port/persistence/BadSqlPort.java` | REUSE | R2 |
| `app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/v3/fixture/engine/sample/port/persistence/GoodNestedPort.java` | REUSE | R2 |
| `app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/v3/fixture/port/ForbiddenPort.java` | REUSE | R2 |
| `app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/v3/fixture/port/PurePort.java` | REUSE | R2 |

### A — composition-runtime-spring

| Chemin exact | Décision | Motif |
|---|---|---|
| `app/composition-runtime-spring/pom.xml` | ADAPT | A2 |
| `app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/RegistrationAdmissionConfiguration.java` | REPLACE | X2 |
| `app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/RegistrationResultReadConfiguration.java` | DEFER | D1 |
| `app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/binding/BindingConsumptionProperties.java` | DEFER | D5 |
| `app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/binding/BindingRuntimeConfiguration.java` | DEFER | D5 |
| `app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/binding/BindingWorkerLifecycle.java` | DEFER | D5 |
| `app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/command/consumption/PotCommandBindingConfiguration.java` | DEFER | D5 |
| `app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/command/v3/V3PotCommandComposition.java` | REUSE | R2 |
| `app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/v3/MicrometerV3ConsumptionObservation.java` | REUSE | R2 |
| `app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/v3/V3BootstrapGuard.java` | ADAPT | A1 |
| `app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/v3/V3ConsumptionCatalogConfiguration.java` | REUSE | R2 |
| `app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/v3/V3ConsumptionDiscoveryComposition.java` | ADAPT | A1 |
| `app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/v3/V3ConsumptionLifecycleComposition.java` | REUSE | R2 |
| `app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/v3/V3ConsumptionWorkerConfiguration.java` | ADAPT | A1 |
| `app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/v3/V3ConsumptionWorkerSettings.java` | ADAPT | A1 |
| `app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/v3/dev/V3FixturePotActorAuthority.java` | RETIRE | T1 |
| `app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/v3/dev/V3TechnicalReadParticipant.java` | REUSE | R2 |
| `app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/registration/RegistrationConsumptionProperties.java` | REPLACE | X2 |
| `app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/registration/RegistrationRuntimeConfiguration.java` | REPLACE | X2 |
| `app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/registration/RegistrationWorkerLifecycle.java` | REPLACE | X2 |
| `app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/registrationresult/RegistrationResultConsumptionProperties.java` | DEFER | D1 |
| `app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/registrationresult/RegistrationResultRuntimeConfiguration.java` | DEFER | D1 |
| `app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/registrationresult/RegistrationResultWorkerLifecycle.java` | DEFER | D1 |
| `app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/web/authentication/SpringSecurityExternalPrincipalAdapter.java` | DEFER | D2 |
| `app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/web/authentication/WebApiSecurityConfiguration.java` | DEFER | D2 |

### A — contracts-authentication

| Chemin exact | Décision | Motif |
|---|---|---|
| `app/contracts-authentication/pom.xml` | REUSE | R4 |
| `app/contracts-authentication/src/main/java/com/kartaguez/pocoma/contracts/authentication/AuthenticatedExternalPrincipal.java` | DEFER | D2 |

### A — contracts-command

| Chemin exact | Décision | Motif |
|---|---|---|
| `app/contracts-command/src/main/java/com/kartaguez/pocoma/contracts/command/CommandAuthenticationEvidence.java` | DEFER | D5 |

### A — contracts-registration

| Chemin exact | Décision | Motif |
|---|---|---|
| `app/contracts-registration/pom.xml` | REUSE | R4 |
| `app/contracts-registration/src/main/java/com/kartaguez/pocoma/contracts/registration/RegistrationRequest.java` | REPLACE | X2 |

### A — domain-authorization

| Chemin exact | Décision | Motif |
|---|---|---|
| `app/domain-authorization/pom.xml` | REUSE | R4 |
| `app/domain-authorization/src/main/java/com/kartaguez/pocoma/domain/authorization/ExternalAuthorityPermissionTranslator.java` | DEFER | D2 |
| `app/domain-authorization/src/main/java/com/kartaguez/pocoma/domain/authorization/Permission.java` | DEFER | D2 |
| `app/domain-authorization/src/main/java/com/kartaguez/pocoma/domain/authorization/PocomaPermissions.java` | DEFER | D2 |
| `app/domain-authorization/src/main/java/com/kartaguez/pocoma/domain/authorization/RequiredCurrentCapabilities.java` | DEFER | D2 |
| `app/domain-authorization/src/main/java/com/kartaguez/pocoma/domain/authorization/TokenCapabilities.java` | DEFER | D2 |
| `app/domain-authorization/src/test/java/com/kartaguez/pocoma/domain/authorization/CapabilitySetsTest.java` | DEFER | D2 |
| `app/domain-authorization/src/test/java/com/kartaguez/pocoma/domain/authorization/ExternalAuthorityPermissionTranslatorTest.java` | DEFER | D2 |
| `app/domain-authorization/src/test/java/com/kartaguez/pocoma/domain/authorization/PermissionTest.java` | DEFER | D2 |

### A — domain-consumption

| Chemin exact | Décision | Motif |
|---|---|---|
| `app/domain-consumption/pom.xml` | REUSE | R4 |
| `app/domain-consumption/src/main/java/com/kartaguez/pocoma/domain/consumption/key/ConsumableIdentity.java` | DEFER | D5 |
| `app/domain-consumption/src/main/java/com/kartaguez/pocoma/domain/consumption/key/ConsumerIdentity.java` | DEFER | D5 |
| `app/domain-consumption/src/main/java/com/kartaguez/pocoma/domain/consumption/v3/ConsumableFamily.java` | REUSE | R2 |
| `app/domain-consumption/src/main/java/com/kartaguez/pocoma/domain/consumption/v3/ConsumptionDefinition.java` | REUSE | R2 |
| `app/domain-consumption/src/main/java/com/kartaguez/pocoma/domain/consumption/v3/ConsumptionId.java` | REUSE | R2 |
| `app/domain-consumption/src/main/java/com/kartaguez/pocoma/domain/consumption/v3/RoutingV1.java` | REUSE | R2 |
| `app/domain-consumption/src/test/java/com/kartaguez/pocoma/domain/consumption/v3/RoutingV1Test.java` | REUSE | R2 |

### A — domain-event

| Chemin exact | Décision | Motif |
|---|---|---|
| `app/domain-event/pom.xml` | REUSE | R4 |

### A — domain-pot

| Chemin exact | Décision | Motif |
|---|---|---|
| `app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/v3/ActiveShareholderUsage.java` | REUSE | R2 |
| `app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/v3/Amount.java` | REUSE | R2 |
| `app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/v3/AttachShareholderViaInvitation.java` | REUSE | R2 |
| `app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/v3/AuthorizationFacts.java` | REUSE | R2 |
| `app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/v3/ExpenseDetails.java` | REUSE | R2 |
| `app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/v3/ExpenseFinancials.java` | REUSE | R2 |
| `app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/v3/ExpenseHeader.java` | REUSE | R2 |
| `app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/v3/ExpenseState.java` | REUSE | R2 |
| `app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/v3/ExpenseTransitions.java` | REUSE | R2 |
| `app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/v3/Fraction.java` | REUSE | R2 |
| `app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/v3/InconsistentPotFactsException.java` | REUSE | R2 |
| `app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/v3/PotBalanceCalculator.java` | REUSE | R2 |
| `app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/v3/PotDetails.java` | REUSE | R2 |
| `app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/v3/PotFacts.java` | REUSE | R2 |
| `app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/v3/PotFinancialState.java` | REUSE | R2 |
| `app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/v3/PotHeader.java` | REUSE | R2 |
| `app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/v3/PotState.java` | REUSE | R2 |
| `app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/v3/PotTransitions.java` | REUSE | R2 |
| `app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/v3/ShareholderDetails.java` | REUSE | R2 |
| `app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/v3/ShareholderHeader.java` | REUSE | R2 |
| `app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/v3/ShareholderReference.java` | REUSE | R2 |
| `app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/v3/ShareholderState.java` | REUSE | R2 |
| `app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/v3/ShareholderTransitions.java` | REUSE | R2 |
| `app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/v3/ShareholderWeight.java` | REUSE | R2 |
| `app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/v3/TransitionChecks.java` | REUSE | R2 |
| `app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/v3/TransitionResult.java` | REUSE | R2 |
| `app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/v3/Versioned.java` | REUSE | R2 |
| `app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/v3/Weight.java` | REUSE | R2 |
| `app/domain-pot/src/test/java/com/kartaguez/pocoma/domain/pot/aggregate/ShareholderIdentityCollisionTest.java` | DEFER | D5 |
| `app/domain-pot/src/test/java/com/kartaguez/pocoma/domain/pot/v3/InvitationAttachmentTest.java` | REUSE | R2 |
| `app/domain-pot/src/test/java/com/kartaguez/pocoma/domain/pot/v3/PotAuthorizationTest.java` | REUSE | R2 |
| `app/domain-pot/src/test/java/com/kartaguez/pocoma/domain/pot/v3/PotBalanceCalculatorTest.java` | REUSE | R2 |
| `app/domain-pot/src/test/java/com/kartaguez/pocoma/domain/pot/v3/PotBalanceProfile.java` | REUSE | R2 |
| `app/domain-pot/src/test/java/com/kartaguez/pocoma/domain/pot/v3/PotTransitionsTest.java` | REUSE | R2 |

### A — domain-user-identity

| Chemin exact | Décision | Motif |
|---|---|---|
| `app/domain-user-identity/pom.xml` | REUSE | R4 |
| `app/domain-user-identity/src/main/java/com/kartaguez/pocoma/domain/useridentity/BindingAcquireResult.java` | REPLACE | X1 |
| `app/domain-user-identity/src/main/java/com/kartaguez/pocoma/domain/useridentity/BindingDetachResult.java` | REPLACE | X1 |
| `app/domain-user-identity/src/main/java/com/kartaguez/pocoma/domain/useridentity/BindingId.java` | REPLACE | X1 |
| `app/domain-user-identity/src/main/java/com/kartaguez/pocoma/domain/useridentity/BindingRevision.java` | REPLACE | X1 |
| `app/domain-user-identity/src/main/java/com/kartaguez/pocoma/domain/useridentity/ExternalIdentity.java` | REUSE | R1 |
| `app/domain-user-identity/src/main/java/com/kartaguez/pocoma/domain/useridentity/ExternalIdentityAttached.java` | REPLACE | X1 |
| `app/domain-user-identity/src/main/java/com/kartaguez/pocoma/domain/useridentity/ExternalIdentityBindingFact.java` | REPLACE | X1 |
| `app/domain-user-identity/src/main/java/com/kartaguez/pocoma/domain/useridentity/ExternalIdentityDetached.java` | REPLACE | X1 |
| `app/domain-user-identity/src/main/java/com/kartaguez/pocoma/domain/useridentity/ObservedBinding.java` | REPLACE | X1 |
| `app/domain-user-identity/src/main/java/com/kartaguez/pocoma/domain/useridentity/PocomaUserId.java` | REUSE | R1 |
| `app/domain-user-identity/src/main/java/com/kartaguez/pocoma/domain/useridentity/User.java` | REUSE | R1 |
| `app/domain-user-identity/src/main/java/com/kartaguez/pocoma/domain/useridentity/currentbinding/CurrentBinding.java` | DEFER | D1 |
| `app/domain-user-identity/src/main/java/com/kartaguez/pocoma/domain/useridentity/currentbinding/CurrentBindingApplyResult.java` | DEFER | D1 |
| `app/domain-user-identity/src/main/java/com/kartaguez/pocoma/domain/useridentity/currentbinding/CurrentBindingInvariantException.java` | DEFER | D1 |
| `app/domain-user-identity/src/main/java/com/kartaguez/pocoma/domain/useridentity/currentbinding/CurrentBindingStatus.java` | DEFER | D1 |
| `app/domain-user-identity/src/test/java/com/kartaguez/pocoma/domain/useridentity/UserIdentityModelTest.java` | ADAPT | A4 |

### A — engine-admit-command

| Chemin exact | Décision | Motif |
|---|---|---|
| `app/engine-admit-command/src/main/java/com/kartaguez/pocoma/engine/admit/command/CommandAuthenticationEvidenceFactory.java` | DEFER | D5 |
| `app/engine-admit-command/src/test/java/com/kartaguez/pocoma/engine/admit/command/CommandAuthenticationEvidenceFactoryTest.java` | DEFER | D5 |

### A — engine-admit-registration

| Chemin exact | Décision | Motif |
|---|---|---|
| `app/engine-admit-registration/pom.xml` | REUSE | R4 |
| `app/engine-admit-registration/src/main/java/com/kartaguez/pocoma/engine/admit/registration/AdmitRegistrationService.java` | DEFER | D2 |
| `app/engine-admit-registration/src/main/java/com/kartaguez/pocoma/engine/admit/registration/RegistrationRequestRecorder.java` | DEFER | D2 |

### A — engine-consume-command

| Chemin exact | Décision | Motif |
|---|---|---|
| `app/engine-consume-command/src/main/java/com/kartaguez/pocoma/engine/consume/command/consumption/BindingFenceRecoveryExecuteUseCase.java` | DEFER | D5 |
| `app/engine-consume-command/src/main/java/com/kartaguez/pocoma/engine/consume/command/execution/BindingFenceConflictException.java` | DEFER | D5 |
| `app/engine-consume-command/src/main/java/com/kartaguez/pocoma/engine/consume/command/execution/BindingFenceLostException.java` | DEFER | D5 |

### A — engine-consume-registration

| Chemin exact | Décision | Motif |
|---|---|---|
| `app/engine-consume-registration/pom.xml` | ADAPT | A2 |
| `app/engine-consume-registration/src/main/java/com/kartaguez/pocoma/engine/consume/registration/ExecuteRegistrationService.java` | REPLACE | X2 |
| `app/engine-consume-registration/src/main/java/com/kartaguez/pocoma/engine/consume/registration/RegistrationDiscoveryPort.java` | REPLACE | X2 |
| `app/engine-consume-registration/src/main/java/com/kartaguez/pocoma/engine/consume/registration/RegistrationOutcome.java` | REPLACE | X2 |
| `app/engine-consume-registration/src/main/java/com/kartaguez/pocoma/engine/consume/registration/RegistrationOutcomeRepository.java` | REPLACE | X2 |
| `app/engine-consume-registration/src/main/java/com/kartaguez/pocoma/engine/consume/registration/RegistrationRequestReader.java` | REPLACE | X2 |
| `app/engine-consume-registration/src/main/java/com/kartaguez/pocoma/engine/consume/registration/UserCreatedFactPort.java` | REPLACE | X2 |
| `app/engine-consume-registration/src/test/java/com/kartaguez/pocoma/engine/consume/registration/RegistrationPublicationContractTest.java` | ADAPT | A4 |

### A — engine-consumption

| Chemin exact | Décision | Motif |
|---|---|---|
| `app/engine-consumption/pom.xml` | REUSE | R4 |
| `app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/consumption/v3/ClaimedSlot.java` | REUSE | R2 |
| `app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/consumption/v3/ConsumptionEngine.java` | REUSE | R2 |
| `app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/consumption/v3/ConsumptionParticipant.java` | REUSE | R2 |
| `app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/consumption/v3/DiscoveryEngine.java` | REUSE | R2 |
| `app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/consumption/v3/LifecycleSettings.java` | REUSE | R2 |
| `app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/consumption/v3/PartitionLease.java` | REUSE | R2 |
| `app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/consumption/v3/RecoveryObservation.java` | REUSE | R2 |
| `app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/consumption/v3/SlotCompletion.java` | REUSE | R2 |
| `app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/consumption/v3/WorkerCapacity.java` | REUSE | R2 |
| `app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/consumption/v3/port/AttemptFailureClassifier.java` | REUSE | R2 |
| `app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/consumption/v3/port/BurnableReferences.java` | REUSE | R2 |
| `app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/consumption/v3/port/ConsumptionObservation.java` | REUSE | R2 |
| `app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/consumption/v3/port/ConsumptionSlotStore.java` | REUSE | R2 |
| `app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/consumption/v3/port/DiscoveryAdmissionStore.java` | REUSE | R2 |
| `app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/consumption/v3/port/NonBurnableReferences.java` | REUSE | R2 |
| `app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/consumption/v3/port/PartitionOwnershipStore.java` | REUSE | R2 |
| `app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/consumption/v3/port/SlotKeyNavigation.java` | REUSE | R2 |
| `app/engine-consumption/src/test/java/com/kartaguez/pocoma/engine/consumption/v3/ConsumptionFailureClassificationTest.java` | REUSE | R2 |
| `app/engine-consumption/src/test/java/com/kartaguez/pocoma/engine/consumption/v3/DiscoveryEngineTest.java` | REUSE | R2 |
| `app/engine-consumption/src/test/java/com/kartaguez/pocoma/engine/consumption/v3/WorkerCapacityTest.java` | REUSE | R2 |

### A — engine-materialize-current-binding

| Chemin exact | Décision | Motif |
|---|---|---|
| `app/engine-materialize-current-binding/pom.xml` | REUSE | R4 |
| `app/engine-materialize-current-binding/src/main/java/com/kartaguez/pocoma/engine/materialize/currentbinding/BindingFactCandidate.java` | DEFER | D1 |
| `app/engine-materialize-current-binding/src/main/java/com/kartaguez/pocoma/engine/materialize/currentbinding/BindingFactCursor.java` | DEFER | D1 |
| `app/engine-materialize-current-binding/src/main/java/com/kartaguez/pocoma/engine/materialize/currentbinding/BindingFactDiscoveryPort.java` | DEFER | D1 |
| `app/engine-materialize-current-binding/src/main/java/com/kartaguez/pocoma/engine/materialize/currentbinding/BindingFactReadPort.java` | DEFER | D1 |
| `app/engine-materialize-current-binding/src/main/java/com/kartaguez/pocoma/engine/materialize/currentbinding/MaterializeCurrentBindingService.java` | DEFER | D1 |
| `app/engine-materialize-current-binding/src/main/java/com/kartaguez/pocoma/engine/materialize/currentbinding/port/CurrentBindingWritePort.java` | DEFER | D1 |

### A — engine-materialize-registration-result

| Chemin exact | Décision | Motif |
|---|---|---|
| `app/engine-materialize-registration-result/pom.xml` | REUSE | R4 |
| `app/engine-materialize-registration-result/src/main/java/com/kartaguez/pocoma/engine/materialize/registrationresult/MaterializeRegistrationResultService.java` | DEFER | D1 |
| `app/engine-materialize-registration-result/src/main/java/com/kartaguez/pocoma/engine/materialize/registrationresult/RegistrationResultDiscovery.java` | DEFER | D1 |
| `app/engine-materialize-registration-result/src/main/java/com/kartaguez/pocoma/engine/materialize/registrationresult/RegistrationResultSource.java` | DEFER | D1 |
| `app/engine-materialize-registration-result/src/main/java/com/kartaguez/pocoma/engine/materialize/registrationresult/RegistrationResultSourcePort.java` | DEFER | D1 |
| `app/engine-materialize-registration-result/src/test/java/com/kartaguez/pocoma/engine/materialize/registrationresult/RegistrationResultServiceTest.java` | DEFER | D1 |

### A — engine-read-current-binding

| Chemin exact | Décision | Motif |
|---|---|---|
| `app/engine-read-current-binding/pom.xml` | REUSE | R4 |
| `app/engine-read-current-binding/src/main/java/com/kartaguez/pocoma/engine/read/currentbinding/GetCurrentBindingService.java` | DEFER | D1 |
| `app/engine-read-current-binding/src/main/java/com/kartaguez/pocoma/engine/read/currentbinding/GetCurrentBindingUseCase.java` | DEFER | D1 |
| `app/engine-read-current-binding/src/main/java/com/kartaguez/pocoma/engine/read/currentbinding/port/CurrentBindingReadPort.java` | DEFER | D1 |
| `app/engine-read-current-binding/src/test/java/com/kartaguez/pocoma/engine/read/currentbinding/GetCurrentBindingServiceTest.java` | DEFER | D1 |

### A — engine-read-pot

| Chemin exact | Décision | Motif |
|---|---|---|
| `app/engine-read-pot/src/main/java/com/kartaguez/pocoma/engine/read/pot/ReadPotForExternalIdentityService.java` | DEFER | D5 |
| `app/engine-read-pot/src/main/java/com/kartaguez/pocoma/engine/read/pot/ReadPotForExternalIdentityUseCase.java` | DEFER | D5 |

### A — engine-read-registration-result

| Chemin exact | Décision | Motif |
|---|---|---|
| `app/engine-read-registration-result/pom.xml` | REUSE | R4 |
| `app/engine-read-registration-result/src/main/java/com/kartaguez/pocoma/engine/read/registrationresult/GetRegistrationResultService.java` | DEFER | D1 |
| `app/engine-read-registration-result/src/main/java/com/kartaguez/pocoma/engine/read/registrationresult/ImmutableRegistrationResult.java` | DEFER | D1 |
| `app/engine-read-registration-result/src/main/java/com/kartaguez/pocoma/engine/read/registrationresult/PublishedRegistrationResult.java` | DEFER | D1 |
| `app/engine-read-registration-result/src/main/java/com/kartaguez/pocoma/engine/read/registrationresult/RegistrationResultStore.java` | DEFER | D1 |

### A — engine-write-pot

| Chemin exact | Décision | Motif |
|---|---|---|
| `app/engine-write-pot/pom.xml` | REUSE | R4 |
| `app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/port/execution/v3/PotActorAuthority.java` | REUSE | R2 |
| `app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/port/execution/v3/PotCommand.java` | REUSE | R2 |
| `app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/port/execution/v3/PotCommandObservation.java` | REUSE | R2 |
| `app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/port/execution/v3/PotCommandStore.java` | REUSE | R2 |
| `app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/port/persistence/v3/ComponentRead.java` | REUSE | R2 |
| `app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/port/persistence/v3/PotHistoryPort.java` | REUSE | R2 |
| `app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/port/persistence/v3/PotRevisionPort.java` | REUSE | R2 |
| `app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/v3/CalculatePotBalances.java` | REUSE | R2 |
| `app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/v3/ExecutePotCommand.java` | REUSE | R2 |
| `app/engine-write-pot/src/test/java/com/kartaguez/pocoma/engine/write/pot/v3/ExecutePotCommandTest.java` | REUSE | R2 |
| `app/engine-write-pot/src/test/java/com/kartaguez/pocoma/engine/write/pot/v3/PotCommandIntentTest.java` | REUSE | R2 |

### A — infra-persistence-primary-jpa

| Chemin exact | Décision | Motif |
|---|---|---|
| `app/infra-persistence-primary-jpa/pom.xml` | REUSE | R4 |
| `app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/identity/ExternalIdentityBindingFactRecordMapper.java` | REPLACE | X1 |
| `app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/identity/JdbcBindingFactDiscoveryAdapter.java` | REPLACE | X1 |
| `app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/identity/JpaExternalIdentityBindingAdapter.java` | REPLACE | X1 |
| `app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/identity/JpaExternalIdentityBindingFactAdapter.java` | REPLACE | X1 |
| `app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/identity/JpaExternalIdentityBindingStreamAdapter.java` | REPLACE | X1 |
| `app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/identity/JpaUserAuthorityAdapter.java` | REPLACE | X1 |
| `app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/registration/JdbcRegistrationDiscovery.java` | REPLACE | X2 |
| `app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/registration/JdbcRegistrationOutcomeStore.java` | REPLACE | X2 |
| `app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/registration/JdbcRegistrationRequestStore.java` | REPLACE | X2 |
| `app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/registration/JdbcRegistrationResultDiscovery.java` | DEFER | D1 |
| `app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/registration/JdbcRegistrationResultSource.java` | DEFER | D1 |
| `app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/registration/JdbcRegistrationResultStore.java` | DEFER | D1 |
| `app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/registration/JdbcUserCreatedFactAdapter.java` | REPLACE | X2 |
| `app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/repository/identity/ExternalIdentityBindingFactJdbcRepository.java` | REPLACE | X1 |
| `app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/repository/identity/ExternalIdentityBindingFactRow.java` | REPLACE | X1 |
| `app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/repository/identity/ExternalIdentityBindingOccurrenceJdbcRepository.java` | REPLACE | X1 |
| `app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/repository/identity/ExternalIdentityBindingStreamJdbcRepository.java` | REPLACE | X1 |
| `app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/repository/identity/ExternalIdentityBindingStreamRow.java` | REPLACE | X1 |
| `app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/repository/identity/ExternalIdentityJdbcRepository.java` | REPLACE | X1 |
| `app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/repository/identity/UserJdbcRepository.java` | REPLACE | X1 |
| `app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/transaction/event/ClosedPublishedEvents.java` | DEFER | D5 |
| `app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/transaction/event/JdbcEventTransactionSequencer.java` | DEFER | D5 |
| `app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/transaction/event/PrimaryEventInsert.java` | DEFER | D5 |
| `app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/transaction/event/PrimaryEventTransactionRunner.java` | DEFER | D5 |
| `app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/transaction/event/PrimaryEventTransactionSuffix.java` | DEFER | D5 |
| `app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/JdbcPotCommandObservation.java` | REUSE | R2 |
| `app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/JdbcPotCommandStore.java` | REUSE | R2 |
| `app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/JdbcPotHistoryAdapter.java` | REUSE | R2 |
| `app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/JdbcPotRevisionAdapter.java` | REUSE | R2 |
| `app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/PotCommandJson.java` | REUSE | R2 |
| `app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/PotHistoryRows.java` | REUSE | R2 |
| `app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/consumption/JdbcCommandDiscoveryReferences.java` | REUSE | R2 |
| `app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/consumption/JdbcConsumptionSlotStore.java` | REUSE | R2 |
| `app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/consumption/JdbcDiscoveryAdmissionStore.java` | REUSE | R2 |
| `app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/consumption/JdbcEventDiscoveryReferences.java` | ADAPT | A1 |
| `app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/consumption/JdbcPartitionOwnershipStore.java` | REUSE | R2 |
| `app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/consumption/JdbcSlotKeyNavigation.java` | REUSE | R2 |
| `app/infra-persistence-primary-jpa/src/main/resources/db/migration/V10__align_projection_task_scheduling.sql` | DEFER | D4 |
| `app/infra-persistence-primary-jpa/src/main/resources/db/migration/V11__pot_version_metadata.sql` | DEFER | D4 |
| `app/infra-persistence-primary-jpa/src/main/resources/db/migration/V12__pipeline_version_lifecycle.sql` | DEFER | D4 |
| `app/infra-persistence-primary-jpa/src/main/resources/db/migration/V13__canonical_projection_tasks.sql` | DEFER | D4 |
| `app/infra-persistence-primary-jpa/src/main/resources/db/migration/V14__expense_business_date.sql` | DEFER | D4 |
| `app/infra-persistence-primary-jpa/src/main/resources/db/migration/V15__canonical_business_event_types.sql` | DEFER | D4 |
| `app/infra-persistence-primary-jpa/src/main/resources/db/migration/V16__drop_legacy_projection_runtime_structures.sql` | DEFER | D4 |
| `app/infra-persistence-primary-jpa/src/main/resources/db/migration/V17__command_outcomes_and_terminal_events.sql` | DEFER | D4 |
| `app/infra-persistence-primary-jpa/src/main/resources/db/migration/V18__user_identity_binding_authority.sql` | DEFER | D4 |
| `app/infra-persistence-primary-jpa/src/main/resources/db/migration/V19__recorded_command_envelope_expand.sql` | DEFER | D4 |
| `app/infra-persistence-primary-jpa/src/main/resources/db/migration/V1__init_schema.sql` | DEFER | D4 |
| `app/infra-persistence-primary-jpa/src/main/resources/db/migration/V20__external_identity_binding_lifecycle.sql` | DEFER | D4 |
| `app/infra-persistence-primary-jpa/src/main/resources/db/migration/V21__binding_occurrences_and_fact_expand.sql` | DEFER | D4 |
| `app/infra-persistence-primary-jpa/src/main/resources/db/migration/V22__repair_binding_history_and_contract.sql` | DEFER | D4 |
| `app/infra-persistence-primary-jpa/src/main/resources/db/migration/V23__close_binding_fact_type_contract.sql` | DEFER | D4 |
| `app/infra-persistence-primary-jpa/src/main/resources/db/migration/V24__contract_recorded_commands.sql` | DEFER | D4 |
| `app/infra-persistence-primary-jpa/src/main/resources/db/migration/V25__immutable_command_result.sql` | DEFER | D4 |
| `app/infra-persistence-primary-jpa/src/main/resources/db/migration/V26__retire_command_result_projection_data.sql` | DEFER | D4 |
| `app/infra-persistence-primary-jpa/src/main/resources/db/migration/V27__registration_requests.sql` | DEFER | D4 |
| `app/infra-persistence-primary-jpa/src/main/resources/db/migration/V28__registration_outcomes.sql` | DEFER | D4 |
| `app/infra-persistence-primary-jpa/src/main/resources/db/migration/V29__registration_results.sql` | DEFER | D4 |
| `app/infra-persistence-primary-jpa/src/main/resources/db/migration/V2__projection_outbox_tasks.sql` | DEFER | D4 |
| `app/infra-persistence-primary-jpa/src/main/resources/db/migration/V30__prepared_event_transactional_sequencing.sql` | DEFER | D4 |
| `app/infra-persistence-primary-jpa/src/main/resources/db/migration/V31__event_sequencing_administration.sql` | DEFER | D4 |
| `app/infra-persistence-primary-jpa/src/main/resources/db/migration/V3__pipeline_materialization_tasks.sql` | DEFER | D4 |
| `app/infra-persistence-primary-jpa/src/main/resources/db/migration/V4__consumption_engine.sql` | DEFER | D4 |
| `app/infra-persistence-primary-jpa/src/main/resources/db/migration/V5__transactional_task_consumption.sql` | DEFER | D4 |
| `app/infra-persistence-primary-jpa/src/main/resources/db/migration/V6__consumption_terminal_reason.sql` | DEFER | D4 |
| `app/infra-persistence-primary-jpa/src/main/resources/db/migration/V7__consumption_processing_failure_code.sql` | DEFER | D4 |
| `app/infra-persistence-primary-jpa/src/main/resources/db/migration/V8__recorded_commands.sql` | DEFER | D4 |
| `app/infra-persistence-primary-jpa/src/main/resources/db/migration/V9__external_identities.sql` | DEFER | D4 |
| `app/infra-persistence-primary-jpa/src/main/resources/db/v3-consumption-write/migration/V5__consumption_foundation.sql` | REUSE | R3 |
| `app/infra-persistence-primary-jpa/src/main/resources/db/v3-pot-execution/migration/V2__command_execution.sql` | REUSE | R3 |
| `app/infra-persistence-primary-jpa/src/main/resources/db/v3-pot/migration/V1__pot_components.sql` | REUSE | R3 |
| `app/infra-persistence-primary-jpa/src/main/resources/db/v3-pot/migration/V3__point_history_indexes.sql` | REUSE | R3 |
| `app/infra-persistence-primary-jpa/src/main/resources/db/v3-pot/migration/V4__write_effect_indexes.sql` | REUSE | R3 |
| `app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/engine/consumption/v3/I2ReservedClaimFixture.java` | REUSE | R2 |
| `app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/identity/BindingOptimisticCasSemanticsPostgresTest.java` | ADAPT | A4 |
| `app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/identity/ExternalIdentityBindingFactRecordMapperTest.java` | ADAPT | A4 |
| `app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/identity/JpaExternalIdentityBindingLifecycleAdapterPostgresTest.java` | ADAPT | A4 |
| `app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/identity/JpaUserIdentityAuthorityAdapterPostgresTest.java` | ADAPT | A4 |
| `app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/registration/RegistrationAuthorityPostgresTest.java` | ADAPT | A4 |
| `app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/migration/ExternalIdentityBindingCanonicalMigrationPostgresTest.java` | DEFER | D5 |
| `app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/migration/ExternalIdentityBindingLifecycleMigrationPostgresTest.java` | DEFER | D5 |
| `app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/CommandExecutionPostgresTest.java` | REUSE | R2 |
| `app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/CommitAcknowledgementProxy.java` | REUSE | R2 |
| `app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/ConsumptionLifecyclePostgresFixture.java` | REUSE | R2 |
| `app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/ConsumptionLifecyclePostgresTest.java` | REUSE | R2 |
| `app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/DiscoveryPostgresFixture.java` | REUSE | R2 |
| `app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/DiscoveryPostgresTest.java` | REUSE | R2 |
| `app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/ForwardingTransactions.java` | REUSE | R2 |
| `app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/I2Schema.java` | REUSE | R2 |
| `app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/L1CommandCompatibilityHarness.java` | REUSE | R2 |
| `app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/L1CommandStore.java` | REUSE | R2 |
| `app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/L1IntegrationPostgresTest.java` | REUSE | R2 |
| `app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/L2ManifestProof.java` | REUSE | R2 |
| `app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/PotCommandJsonTest.java` | REUSE | R2 |
| `app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/PotFinancePostgresTest.java` | REUSE | R2 |
| `app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/PotHistoryCardinalityPostgresTest.java` | REUSE | R2 |
| `app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/PotHistoryPostgresTest.java` | REUSE | R2 |
| `app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/SlotNavigationPostgresTest.java` | REUSE | R2 |
| `app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/WriteRevisionPerformancePostgresTest.java` | REUSE | R2 |
| `app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/WriteRevisionPostgresTest.java` | REUSE | R2 |
| `app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/consumption/ConsumptionFoundationPostgresTest.java` | REUSE | R2 |

### A — infra-persistence-read-jdbc

| Chemin exact | Décision | Motif |
|---|---|---|
| `app/infra-persistence-read-jdbc/pom.xml` | REUSE | R4 |
| `app/infra-persistence-read-jdbc/src/main/java/com/kartaguez/pocoma/infra/persistence/read/jdbc/JdbcCurrentBindingAdapter.java` | DEFER | D1 |
| `app/infra-persistence-read-jdbc/src/main/resources/db/read-store/migration/V10__current_binding_expand.sql` | DEFER | D4 |
| `app/infra-persistence-read-jdbc/src/main/resources/db/read-store/migration/V11__current_binding_canonical_detached.sql` | DEFER | D4 |
| `app/infra-persistence-read-jdbc/src/main/resources/db/read-store/migration/V12__current_binding_fact_provenance.sql` | DEFER | D4 |
| `app/infra-persistence-read-jdbc/src/main/resources/db/read-store/migration/V13__verify_terminal_binding_facts.sql` | DEFER | D4 |
| `app/infra-persistence-read-jdbc/src/main/resources/db/read-store/migration/V14__gate_command_result_exact_identity.sql` | DEFER | D4 |
| `app/infra-persistence-read-jdbc/src/main/resources/db/read-store/migration/V1__initialize_read_store.sql` | DEFER | D4 |
| `app/infra-persistence-read-jdbc/src/main/resources/db/read-store/migration/V2__generic_projection_foundation.sql` | DEFER | D4 |
| `app/infra-persistence-read-jdbc/src/main/resources/db/read-store/migration/V3__remove_projection_coverage.sql` | DEFER | D4 |
| `app/infra-persistence-read-jdbc/src/main/resources/db/read-store/migration/V4__add_source_version_watermarks.sql` | DEFER | D4 |
| `app/infra-persistence-read-jdbc/src/main/resources/db/read-store/migration/V5__canonical_pot_projection.sql` | DEFER | D4 |
| `app/infra-persistence-read-jdbc/src/main/resources/db/read-store/migration/V6__pot_version_metadata_and_user_index.sql` | DEFER | D4 |
| `app/infra-persistence-read-jdbc/src/main/resources/db/read-store/migration/V7__canonical_projection_store.sql` | DEFER | D4 |
| `app/infra-persistence-read-jdbc/src/main/resources/db/read-store/migration/V8__drop_legacy_read_projection_structures.sql` | DEFER | D4 |
| `app/infra-persistence-read-jdbc/src/main/resources/db/read-store/migration/V9__current_external_identity_binding.sql` | DEFER | D4 |
| `app/infra-persistence-read-jdbc/src/main/resources/db/v3-consumption-read/migration/V1__consumption_foundation.sql` | REUSE | R3 |
| `app/infra-persistence-read-jdbc/src/test/java/com/kartaguez/pocoma/infra/persistence/read/jdbc/CommandResultExactIdentityCutoverMigrationPostgresTest.java` | DEFER | D5 |
| `app/infra-persistence-read-jdbc/src/test/java/com/kartaguez/pocoma/infra/persistence/read/jdbc/CurrentBindingPersistencePostgresTest.java` | DEFER | D1 |
| `app/infra-persistence-read-jdbc/src/test/java/com/kartaguez/pocoma/infra/persistence/read/jdbc/CurrentBindingTerminalCutoverMigrationPostgresTest.java` | DEFER | D1 |
| `app/infra-persistence-read-jdbc/src/test/java/com/kartaguez/pocoma/infra/persistence/read/jdbc/v3/consumption/ReadConsumptionFoundationPostgresTest.java` | REUSE | R2 |
| `app/infra-persistence-read-jdbc/src/test/java/com/kartaguez/pocoma/infra/persistence/read/jdbc/v3/consumption/ReadConsumptionLifecyclePrivilegesPostgresTest.java` | REUSE | R2 |
| `app/infra-persistence-read-jdbc/src/test/java/com/kartaguez/pocoma/infra/persistence/read/jdbc/v3/consumption/ReadDiscoveryPrivilegesPostgresTest.java` | REUSE | R2 |

### A — infra-tx-spring

| Chemin exact | Décision | Motif |
|---|---|---|
| `app/infra-tx-spring/pom.xml` | REUSE | R4 |
| `app/infra-tx-spring/src/main/java/com/kartaguez/pocoma/infra/tx/spring/v3/SpringAttemptTransactions.java` | REUSE | R2 |
| `app/infra-tx-spring/src/test/java/com/kartaguez/pocoma/infra/tx/spring/v3/AttemptTransactionsPostgresTest.java` | REUSE | R2 |
| `app/infra-tx-spring/src/test/java/com/kartaguez/pocoma/infra/tx/spring/v3/PostgresFoundationTestSupport.java` | REUSE | R2 |
| `app/infra-tx-spring/src/test/java/com/kartaguez/pocoma/infra/tx/spring/v3/V3FoundationCompositionTest.java` | REUSE | R2 |
| `app/infra-tx-spring/src/test/java/com/kartaguez/pocoma/infra/tx/spring/v3/V3HistoricalIntervalPostgresTest.java` | REUSE | R2 |
| `app/infra-tx-spring/src/test/java/com/kartaguez/pocoma/infra/tx/spring/v3/V3SchemaIsolationPostgresTest.java` | REUSE | R2 |
| `app/infra-tx-spring/src/test/java/com/kartaguez/pocoma/infra/tx/spring/v3/V3TransactionFoundationPostgresTest.java` | REUSE | R2 |
| `app/infra-tx-spring/src/test/java/com/kartaguez/pocoma/infra/tx/spring/v3/fixture/FoundationEffect.java` | REUSE | R2 |
| `app/infra-tx-spring/src/test/resources/db/v3-foundation/migration/V1__foundation_fixture.sql` | REUSE | R3 |

### A — orchestrator-poll-consumption

| Chemin exact | Décision | Motif |
|---|---|---|
| `app/orchestrator-poll-consumption/pom.xml` | REUSE | R4 |
| `app/orchestrator-poll-consumption/src/main/java/com/kartaguez/pocoma/orchestrator/poll/consumption/v3/V3ConsumptionLoop.java` | REUSE | R2 |
| `app/orchestrator-poll-consumption/src/main/java/com/kartaguez/pocoma/orchestrator/poll/consumption/v3/V3ConsumptionWorker.java` | REUSE | R2 |
| `app/orchestrator-poll-consumption/src/main/java/com/kartaguez/pocoma/orchestrator/poll/consumption/v3/V3DiscoveryLoop.java` | REUSE | R2 |
| `app/orchestrator-poll-consumption/src/main/java/com/kartaguez/pocoma/orchestrator/poll/consumption/v3/V3RenewalService.java` | REUSE | R2 |
| `app/orchestrator-poll-consumption/src/test/java/com/kartaguez/pocoma/orchestrator/poll/consumption/v3/V3ConsumptionWorkerTest.java` | REUSE | R2 |

### A — port-binding-authority

| Chemin exact | Décision | Motif |
|---|---|---|
| `app/port-binding-authority/pom.xml` | REUSE | R4 |
| `app/port-binding-authority/src/main/java/com/kartaguez/pocoma/port/binding/authority/BindingAcquisitionAccepted.java` | REPLACE | X1 |
| `app/port-binding-authority/src/main/java/com/kartaguez/pocoma/port/binding/authority/BindingDetachmentAccepted.java` | REPLACE | X1 |
| `app/port-binding-authority/src/main/java/com/kartaguez/pocoma/port/binding/authority/ExternalIdentityBindingFactPort.java` | REPLACE | X1 |
| `app/port-binding-authority/src/main/java/com/kartaguez/pocoma/port/binding/authority/ExternalIdentityBindingPort.java` | REPLACE | X1 |
| `app/port-binding-authority/src/main/java/com/kartaguez/pocoma/port/binding/authority/ExternalIdentityBindingStreamPort.java` | REPLACE | X1 |
| `app/port-binding-authority/src/main/java/com/kartaguez/pocoma/port/binding/authority/UserAuthorityPort.java` | REPLACE | X1 |

### A — port-transaction

| Chemin exact | Décision | Motif |
|---|---|---|
| `app/port-transaction/pom.xml` | REUSE | R4 |
| `app/port-transaction/src/main/java/com/kartaguez/pocoma/port/transaction/EventFamily.java` | DEFER | D5 |
| `app/port-transaction/src/main/java/com/kartaguez/pocoma/port/transaction/EventRef.java` | DEFER | D5 |
| `app/port-transaction/src/main/java/com/kartaguez/pocoma/port/transaction/PublishedEvents.java` | DEFER | D5 |
| `app/port-transaction/src/main/java/com/kartaguez/pocoma/port/transaction/TransactionCommitUnknownException.java` | DEFER | D5 |
| `app/port-transaction/src/main/java/com/kartaguez/pocoma/port/transaction/TransactionRunner.java` | DEFER | D5 |
| `app/port-transaction/src/main/java/com/kartaguez/pocoma/port/transaction/v3/AttemptTransactions.java` | REUSE | R2 |
| `app/port-transaction/src/main/java/com/kartaguez/pocoma/port/transaction/v3/RecoveryReadContext.java` | REUSE | R2 |

### A — runtime-binding-consumption-worker

| Chemin exact | Décision | Motif |
|---|---|---|
| `app/runtime-binding-consumption-worker/pom.xml` | REUSE | R4 |
| `app/runtime-binding-consumption-worker/src/main/java/com/kartaguez/pocoma/PocomaBindingConsumptionWorkerApplication.java` | DEFER | D1 |
| `app/runtime-binding-consumption-worker/src/main/resources/application.properties` | DEFER | D1 |
| `app/runtime-binding-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/binding/BindingRuntimePostgresTest.java` | DEFER | D1 |

### A — runtime-command-consumption-worker

| Chemin exact | Décision | Motif |
|---|---|---|
| `app/runtime-command-consumption-worker/pom.xml` | ADAPT | A2 |
| `app/runtime-command-consumption-worker/src/main/java/com/kartaguez/pocoma/PocomaCommandConsumptionWorkerApplication.java` | REUSE | R2 |
| `app/runtime-command-consumption-worker/src/main/java/com/kartaguez/pocoma/runtime/command/v3/dev/V3WorkerDevelopmentTools.java` | ADAPT | A1 |
| `app/runtime-command-consumption-worker/src/main/resources/application-postgres.properties` | DEFER | D5 |
| `app/runtime-command-consumption-worker/src/main/resources/application-v3-consumption-pilot.properties` | DEFER | D5 |
| `app/runtime-command-consumption-worker/src/main/resources/application-v3-consumption-worker-dev.properties` | ADAPT | A1 |
| `app/runtime-command-consumption-worker/src/main/resources/application.properties` | DEFER | D5 |
| `app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/engine/consumption/v3/CommandReservedClaimFixture.java` | REUSE | R2 |
| `app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/consumption/BindingSharedFencePostgresTest.java` | DEFER | D5 |
| `app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/v3/ConsumptionBootstrapPostgresTest.java` | REUSE | R2 |
| `app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/v3/ConsumptionCompositeClassificationTest.java` | REUSE | R2 |
| `app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/v3/ConsumptionLifecycleCompositionPostgresTest.java` | REUSE | R2 |
| `app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/v3/DiscoveryCompositionPostgresTest.java` | REUSE | R2 |
| `app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/v3/I5PhysicalWindow.java` | DEFER | D3 |
| `app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/v3/I5PhysicalWindowTest.java` | DEFER | D3 |
| `app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/v3/I5TemporalBudgetTest.java` | DEFER | D3 |
| `app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/v3/I5TemporalV2Postgres.java` | DEFER | D3 |
| `app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/v3/I5TemporalV2Test.java` | DEFER | D3 |
| `app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/v3/V3PotCommandCompositionPostgresTest.java` | ADAPT | A1 |
| `app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/v3/V3WorkerCompositionPostgresTest.java` | REUSE | R2 |
| `app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/v3/V3WorkerMetricsTest.java` | REUSE | R2 |
| `app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/v3/V3WorkerPostgresTest.java` | REUSE | R2 |
| `app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/v3/V3WorkerResourcesPostgresTest.java` | DEFER | D3 |

### A — runtime-event-consumption-worker

| Chemin exact | Décision | Motif |
|---|---|---|
| `app/runtime-event-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/event/consumption/EventConsumptionRuntimeSpringBindingTest.java` | DEFER | D5 |

### A — runtime-monolith

| Chemin exact | Décision | Motif |
|---|---|---|
| `app/runtime-monolith/pom.xml` | REUSE | R4 |
| `app/runtime-monolith/src/main/java/com/kartaguez/pocoma/PocomaRuntimeMonolithApplication.java` | REUSE | R2 |
| `app/runtime-monolith/src/main/resources/application-v3-consumption-pilot.properties` | DEFER | D5 |
| `app/runtime-monolith/src/main/resources/application-v3-consumption-worker-dev.properties` | ADAPT | A1 |
| `app/runtime-monolith/src/main/resources/application.properties` | DEFER | D5 |
| `app/runtime-monolith/src/test/java/com/kartaguez/pocoma/V3WorkerMonolithPostgresTest.java` | REUSE | R2 |

### A — runtime-registration-consumption-worker

| Chemin exact | Décision | Motif |
|---|---|---|
| `app/runtime-registration-consumption-worker/pom.xml` | REUSE | R4 |
| `app/runtime-registration-consumption-worker/src/main/java/com/kartaguez/pocoma/PocomaRegistrationConsumptionWorkerApplication.java` | REPLACE | X2 |
| `app/runtime-registration-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/registration/RegistrationRuntimePostgresTest.java` | ADAPT | A4 |

### A — runtime-registration-result-consumption-worker

| Chemin exact | Décision | Motif |
|---|---|---|
| `app/runtime-registration-result-consumption-worker/pom.xml` | REUSE | R4 |
| `app/runtime-registration-result-consumption-worker/src/main/java/com/kartaguez/pocoma/PocomaRegistrationResultConsumptionWorkerApplication.java` | DEFER | D1 |
| `app/runtime-registration-result-consumption-worker/src/main/resources/application-postgres.properties` | DEFER | D1 |
| `app/runtime-registration-result-consumption-worker/src/main/resources/application.properties` | DEFER | D1 |
| `app/runtime-registration-result-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/registrationresult/RegistrationResultRuntimePostgresTest.java` | DEFER | D1 |

### A — runtime-web-api

| Chemin exact | Décision | Motif |
|---|---|---|
| `app/runtime-web-api/src/test/java/com/kartaguez/pocoma/PotBindingConvergencePostgresTest.java` | DEFER | D2 |
| `app/runtime-web-api/src/test/java/com/kartaguez/pocoma/RegistrationAdmissionPostgresTest.java` | DEFER | D2 |
| `app/runtime-web-api/src/test/java/com/kartaguez/pocoma/WriteSidePotCommandBindingConfiguration.java` | DEFER | D2 |
| `app/runtime-web-api/src/test/java/com/kartaguez/pocoma/runtime/web/authentication/SpringSecurityExternalPrincipalAdapterTest.java` | DEFER | D2 |

### A — scripts/v3

| Chemin exact | Décision | Motif |
|---|---|---|
| `scripts/v3/bootstrap-l2-routing.sql` | REUSE | R2 |
| `scripts/v3/bootstrap-l2-worker-dev.sql` | ADAPT | A3 |
| `scripts/v3/check-routing-vectors.py` | REUSE | R2 |
| `scripts/v3/grant-l2-discovery.sql` | ADAPT | A3 |
| `scripts/v3/grant-l2-lifecycle.sql` | ADAPT | A3 |
| `scripts/v3/grant-l2-worker-dev.sql` | ADAPT | A3 |
| `scripts/v3/reactivate-l2-slot.sql` | REUSE | R2 |
| `scripts/v3/worker-dev.py` | ADAPT | A3 |

### A — supra-consume-binding

| Chemin exact | Décision | Motif |
|---|---|---|
| `app/supra-consume-binding/pom.xml` | REUSE | R4 |
| `app/supra-consume-binding/src/main/java/com/kartaguez/pocoma/supra/consume/binding/BindingFactConsumptionLocator.java` | DEFER | D1 |
| `app/supra-consume-binding/src/main/java/com/kartaguez/pocoma/supra/consume/binding/BindingFactFailureClassifier.java` | DEFER | D1 |
| `app/supra-consume-binding/src/main/java/com/kartaguez/pocoma/supra/consume/binding/BindingFactFailurePolicy.java` | DEFER | D1 |
| `app/supra-consume-binding/src/main/java/com/kartaguez/pocoma/supra/consume/binding/BindingFactNotFoundException.java` | DEFER | D1 |

### A — supra-consume-registration

| Chemin exact | Décision | Motif |
|---|---|---|
| `app/supra-consume-registration/pom.xml` | REUSE | R4 |
| `app/supra-consume-registration/src/main/java/com/kartaguez/pocoma/supra/consume/registration/RegistrationConsumptionLocator.java` | REPLACE | X2 |

### A — supra-consume-registration-result

| Chemin exact | Décision | Motif |
|---|---|---|
| `app/supra-consume-registration-result/pom.xml` | REUSE | R4 |
| `app/supra-consume-registration-result/src/main/java/com/kartaguez/pocoma/supra/consume/registrationresult/RegistrationResultConsumptionLocator.java` | DEFER | D1 |

### A — supra-http-read

| Chemin exact | Décision | Motif |
|---|---|---|
| `app/supra-http-read/src/main/java/com/kartaguez/pocoma/supra/http/read/CurrentBindingController.java` | DEFER | D1 |
| `app/supra-http-read/src/main/java/com/kartaguez/pocoma/supra/http/read/CurrentBindingResponse.java` | DEFER | D1 |
| `app/supra-http-read/src/main/java/com/kartaguez/pocoma/supra/http/read/RegistrationResultController.java` | DEFER | D1 |
| `app/supra-http-read/src/test/java/com/kartaguez/pocoma/supra/http/read/CurrentBindingControllerTest.java` | DEFER | D1 |
| `app/supra-http-read/src/test/java/com/kartaguez/pocoma/supra/http/read/RegistrationResultControllerTest.java` | DEFER | D1 |

### A — supra-http-write

| Chemin exact | Décision | Motif |
|---|---|---|
| `app/supra-http-write/src/main/java/com/kartaguez/pocoma/supra/http/write/RegistrationController.java` | DEFER | D2 |

Inventaire nominatif : **422 fichiers suivis**. Les chemins de création
proposés sont ceux du §6 et ne sont pas comptés comme fichiers existants.

### A — Frontières transversales et distribution examinées

Ces fichiers complètent les classes et migrations nominatives précédentes.
Ils ne sont pas modifiés par le mandat documentaire.

| Chemin exact | Décision | Justification |
|---|---|---|
| `app/pom.xml` | REUSE | Aucun nouveau module/agrégateur ; les dépendances locales prévues sont listées au §6 |
| `app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/transaction/PrimaryWriteParticipation.java` | DEFER | Guard historique ; ne pas superposer à l'owner V3 |
| `app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/ConsumptionOwnerOrchestrator.java` | RETIRE du parcours V3 | Ancien owner, aucune imbrication dans Identity |
| `app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/ConsumptionOwnerTransactionRunner.java` | RETIRE du parcours V3 | Un seul propriétaire physique Engine/SpringAttemptTransactions |
| `app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/ConsumptionTerminalParticipant.java` | RETIRE du parcours V3 | Remplacé par ConsumptionParticipant/SlotCompletion existants |
| `app/infra-tx-spring/src/main/java/com/kartaguez/pocoma/infra/tx/spring/SpringTransactionRunner.java` | DEFER | Contrat V2, pas runner caché d'Identity |
| `app/infra-tx-spring/src/main/java/com/kartaguez/pocoma/infra/tx/spring/SpringTransactionRunnerConfiguration.java` | DEFER | Isolation des imports historiques conservée |
| `app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/PocomaWebRuntimeConfiguration.java` | DEFER L5 | Pas d'API métier L3 |
| `app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/WebAuthorizationConfiguration.java` | DEFER L5 | Entitlements externes à l'admission, aucune autorité métier autonome |
| `app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/PrimaryEventPublicationConfiguration.java` | DEFER | Publications V2, pas de traduction des familles V3 |
| `app/infra-persistence-read-jdbc/src/main/java/com/kartaguez/pocoma/infra/persistence/read/jdbc/ReadStore.java` | DEFER L4 | Aucune nouvelle projection dans ce mandat |
| `app/infra-persistence-read-jdbc/src/main/java/com/kartaguez/pocoma/infra/persistence/read/jdbc/ReadStoreProperties.java` | DEFER L4 | READ physique V3 à cadrer par L4 |
| `app/infra-persistence-read-jdbc/src/main/java/com/kartaguez/pocoma/infra/persistence/read/jdbc/ReadStoreMigrator.java` | DEFER L4 | Pas migration READ automatique en runtime DEV |
| `app/infra-persistence-read-jdbc/src/main/java/com/kartaguez/pocoma/infra/persistence/read/jdbc/ReadStoreAccessAutoConfiguration.java` | DEFER L4 | Auto-configuration exclue dans le profil DEV |
| `app/infra-persistence-read-jdbc/src/main/java/com/kartaguez/pocoma/infra/persistence/read/jdbc/ReadStoreMigrationAutoConfiguration.java` | DEFER L4 | Auto-configuration exclue, bootstrap administratif distinct |
| `docker-compose.functional.yml` | DEFER L8 | Monolithe historique, PostgreSQL/Keycloak, pas distribution V3 qualifiée |
| `docker-compose.distributed.yml` | DEFER L8 | Workers historiques Command/Event/Task/Result/LKV ; ne constitue pas le cycle Identity réel |
| `app/docker-compose.postgres.yml` | DEFER | Support PG local, pas preuve du schéma ni de parité avec PG de certification |
| `app/Dockerfile` | DEFER L8 | Packaging réutilisable après qualification, aucun build ici |
| `docker/postgres/functional-init.sql` | DEFER L8 | Init historique ; migrations V6 et grants DEV séparés |
| `docker/keycloak/pocoma-realm.json` | DEFER L5/L8 | Fournisseur candidat ; présence du realm ne qualifie pas le consentement Link |
| `scripts/bruno/README.md` | DEFER L5 | Scénarios externes à actualiser après READ/admission réels |
| `scripts/bruno/environments/Local.bru` | DEFER L5 | Sessions EUI distinctes et tokens protégés à qualifier |
| `scripts/bruno/06 Registration to Command/01 Register.bru` | DEFER L5 | Admission historique, pas scénario IdentityRequest V3 réel |
| `scripts/bruno/06 Registration to Command/02 Registration Result.bru` | DEFER L4/L5 | Résultat historique ; propriété requesterEui à prouver |
| `scripts/bruno/06 Registration to Command/03 Current Binding.bru` | DEFER L4/L5 | Token propre sans token entrant, aucune lecture WRITE publique |
| `scripts/bruno/06 Registration to Command/04 Create Pot with Current Binding.bru` | DEFER L5 | R-ID-POT interne précède ce parcours externe |
| `scripts/bruno/06 Registration to Command/05 Command Result.bru` | DEFER L4/L5 | Polling de résultat READ indépendant du Binding actuel |
| `scripts/k6/README.md` | DEFER L6 | Charges et budgets intégrés après composants réels, aucune campagne ici |

### A — Tests existants à conserver, étendre ou ne pas relancer

Le classement REUSE des tests V3 signifie conserver leurs contrats et harness ;
les fixtures SQL nommées Identity restent des fixtures dans les tests L1/L2.
L3 ajoute les scénarios réels du §7, sans modifier leurs anciennes archives.
`I2Schema`, `L1CommandCompatibilityHarness`, `CommitAcknowledgementProxy`,
`ForwardingTransactions` et fixtures Discovery/Lifecycle sont réemployables pour
les coupures/observations mais doivent permettre explicitement le bootstrap V6
**des nouveaux tests** ; ne pas modifier silencieusement la baseline des tests
historiques. Les READ probes demeurent techniques. Les tests
`V3WorkerResourcesPostgresTest` et `I5Temporal*` sont DEFER pour leur campagne
historique ; la difficulté d'une gate transitive est traitée au §8, sans modifier
leurs seuils, noms ni statuts.

L’annexe A recense au total **451 chemins existants distincts** (422 dans les
tables par module, 29 compléments transversaux/distribution).

## Annexe B — Réponses aux dix questions du mandat

| Question | Réponse proposée et preuve à produire |
|---|---|
| 1. Réemploi V2 | Valeurs User/PocomaUserId/ExternalIdentity directes ; oracles concurrence et upsert monotone adaptables ; Binding/outcomes/pipelines V2 remplacés dans le parcours V3 ; READ/HTTP et retrait physique différés. Annexe A et §3 |
| 2. Existant V3 | Familles/IDs/routage/fondations et Engine présents ; modèle/participant/stores Identity et deux producteurs absents ; enum EventFamily V2 hors raccord. §2 |
| 3. Intégration physique I2 | ConsumptionParticipant réel, même owner/datasource, completion exacte avant suffixe, R9 lecture seule ; aucun Engine reconstruit. §3.3 et I3 |
| 4. Verrous connus/inconnus | Ancre technique unique, INSERT+FOR UPDATE clé par clé en ordre UTF-8/C, partage avec autorité Pot, E1==E2 dédoublonné. §3.2 et courses §7 |
| 5. Histoire/unicité/tokens/User | PK EUI/version directe, curseur local, unicité couple et token historique, SecureRandom, User seulement après précondition protégée ; dernier Unlink conserve User. §4 |
| 6. Demande et atomicité | PENDING immuable sans slot, Discovery asynchrone, résultat/TERMINAL/effets/enveloppes/positions/DELETE dans une transaction ; retry durable ne répète pas la mutation. §3.3/I3 |
| 7. Adaptations minimales | V6 additive, deux compteurs/tables de publication, source burnable, mappings fermés, droits/guards et mode réel explicite ; aucun ID/hash nouveau, pas matérialisation L4. §4–6 |
| 8. Pot sans régression | Implémentation du port PotActorAuthority existant, même ancre/witness, collision si modification, témoin négatif protégé sans Pot ; nouvelles preuves réelles en plus des fixtures L1/L2. I4 |
| 9. Link L3 | Admission contrôlée de banc avec provenance immuable et périmètre DEV ; exécution et négatifs internes prouvables ; ID-02/C-01 externe reste NOT RUN L5. §7.1 |
| 10. Runtime | Fin I3 seulement si raccord actif/outils/grants livrés et démontrés ensemble : R-ID interne ; fin I4 R-ID-POT ; HTTP/READ/consentement et distribution finale ultérieurs. §9.1 |

## Annexe C — Contrôles documentaires et publication

Commandes réellement exécutées : `pwd`, `git status --short`,
`git branch --show-current`, `git branch -avv`, `git rev-parse HEAD`,
`git remote -v`, `git worktree list`,
`git ls-remote origin refs/heads/feat/v3-architecture` ; lectures `cat`, `sed`,
`rg`/`rg --files`, `git ls-files` et scripts Python ad hoc en lecture de chemins
pour construire/vérifier l'inventaire documentaire ; `git diff --check` et
contrôle des liens/chemins du document. Ce sont des contrôles statiques, pas des
tests applicatifs ou de certification. Les commandes de publication autorisées
sont `git add` de ce seul document, `git diff --cached --check`,
`git diff --cached --name-status`, commit documentaire puis push normal après
recontrôle distant ; leur succès et le SHA exact sont communiqués séparément
après réalisation, sans auto-référence circulaire dans le commit.

Slice primaire/secondaires : NONE ; franchissement inattendu : NONE ; gate
Architecture/full reactor : NOT REQUIRED et NOT RUN ; PostgreSQL/migrations :
NOT RUN. Aucune preuve historique modifiée ou campagne temporelle rouverte.
