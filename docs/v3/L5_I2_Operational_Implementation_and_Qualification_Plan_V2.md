# Pocoma V3 — L5/I2 — Plan opérationnel V2

Date : **4 novembre 2026**. Branche : **`feat/v3-architecture`**. Référence auditée : **`ad98d690e081bb354662c7a52c5a203778483259`**.

**READY WITH RESERVATIONS — prêt pour audit et approbation finale. IMPLEMENTATION NOT AUTHORIZED.**

Ce document publie le plan V2 présenté dans la conversation après une mission d'analyse en lecture seule. Le mandat humain suivant, « Push le plan », autorise son enregistrement, son commit et son push documentaires seulement. Il ne ratifie pas le plan, ne donne aucun GO d'implémentation et n'autorise aucune campagne applicative. Aucun autre document de programme, SPEC, politique ou preuve historique n'est actualisé par cette publication.

Décisions humaines résiduelles :

1. **D4-WRITE : autoriser la calibration ciblée en F**, puis ratifier ses seuils. Les mesures existantes ne permettent pas de fixer honnêtement les limites WRITE.
2. **Approuver l'allocation de qualification V2**, comprenant une gate Architecture obligatoire pendant I2, distincte du full natif de Q5.
3. Après résolution des conditions et audit : **donner un GO d'implémentation distinct**.

D2, D3 et D7 ne nécessitent pas de nouvel arbitrage architectural. Leur déclinaison technique proposée ci-dessous reste soumise à revue. Les noms de composants, classes et méthodes futurs sont des propositions, jamais des capacités actuellement exécutables ou qualifiées.

## A. Référentiel et état Git

| Contrôle de l'analyse | Constat |
|---|---|
| Branche | `feat/v3-architecture` |
| HEAD réel | `ad98d690e081bb354662c7a52c5a203778483259` |
| Référence de suivi | Même SHA ; divergence locale `0 / 0` |
| Tête distante | Même SHA, vérifié par `git ls-remote origin refs/heads/feat/v3-architecture` |
| Worktree | Propre au début et au dernier contrôle de l'analyse |
| Sources depuis la qualification I1 | Aucun delta sous `app/` ou `scripts/` entre `46b94ce69a4b0f42474d38ac10b26daa78bb6b23` et la référence auditée |
| Écritures de l'analyse initiale | Aucune |

Le premier contrôle distant a rencontré une erreur DNS dans la sandbox. La vérification en lecture seule avec accès réseau autorisé a ensuite confirmé le SHA distant. La publication documentaire ultérieure est distinguée en fin de document ; elle ne réattribue aucune preuve applicative à son nouveau commit.

Référentiel examiné :

- [Master Plan](V3_Implementation_Master_Plan.md).
- [Plan global L5](L5_Supra_Entry_Audit_and_Implementation_Plan.md).
- [Plan I1](L5_I1_Implementation_and_Qualification_Plan.md), [rapport I1](L5_I1_Execution_Report.md), [clôture I1](L5_I1_Acceptance_and_Closure_Decision.md).
- [Calibration D4-READ](L5_D4_Read_Calibration_Report.md), [mutualisation D6](L5_D6_Gates_Mutualization_Report.md).
- [Reactor Verification Policy](../testing/Reactor_Verification_Policy.md), notamment §§3, 14, 17, 19, 34 et 35.
- [Canon](V3_Canon_and_Roadmap.md), [SPEC-01](SPEC-01_Pot_Domain.md), [SPEC-02](SPEC-02_Identity_Domain.md), [SPEC-03](SPEC-03_Events_Requests_Consumables.md), [SPEC-04](SPEC-04_Read_Projections.md), [SPEC-05](SPEC-05_Consumption_Discovery.md), [SPEC-06](SPEC-06_Supra_Contracts.md), [SPEC-07](SPEC-07_Transverse_Requirements.md), [SPEC-08](SPEC-08_Observability.md), [SPEC-09](SPEC-09_Performance.md), [SPEC-10](SPEC-10_Test_Certification.md), dans leurs clauses applicables.
- Manifestes L1/F/F2, L2, L3, L4 et I1, archives et mesures pertinentes ; POM ; sources admission/exécution/persistance/HTTP/composition ; fixtures et scripts de qualification.

L'approbation du cadrage V1 et des orientations D2/D3/D4/D7 provient du mandat humain transmis dans la conversation. Aucun document autonome de plan L5/I2 V1 n'a été retrouvé. Les bandeaux du dépôt indiquant encore « ELIGIBLE FOR PLANNING » sont antérieurs à ce mandat. Ils ne sont pas réécrits dans cette publication.

### Disponibilité effectivement vérifiée

| Élément | Comportement livré |
|---|---|
| Admission Command existante | `SubmitRecordedCommandService` insère un `RecordedCommand` historique avec `TargetCommandEnvelope` ; ce n'est pas l'admission publique V3 |
| Admission Registration existante | `AdmitRegistrationService` crée la demande Registration historique ; pas d'association S-03 V3 |
| `PotCommand` V3 | Onze intentions disponibles ; requester et bindingToken présents ; pas de SELF durable |
| `JdbcPotCommandStore` | Chargement, exécution et publication ; pas de méthode d'admission publique |
| `JdbcIdentityRequestStore.insertPending` | Admission interne contrôlée seulement ; son `load` joint les résultats |
| `AdmissionContext` | Provenance structurée ; aucune preuve cryptographique de consentement par sa seule existence |
| HTTP I1 | READ livré ; filtre applicable à tout `/api/v3/*`, corps limité à 65 536 octets |
| Composition I1 | Refuse les beans `engine.admit.*` et les composants HTTP historiques |
| Préflight Worker | Attend exactement ses quatre pools ; un pool d'admission supplémentaire exige une allocation explicite |
| Principal I1 | Pas de client attesté exposé ; autorités externes actuellement fournies comme ensemble vide |

I2 n'est donc pas un simple ajout de deux contrôleurs. Un nom de type, une interface ou une valeur d'enum ne prouve pas l'existence d'une capacité.

## B. Périmètre exact et déclaration future de vérification

I2 livre :

- `POST /api/v3/commands`, pour les onze intentions Pot existantes.
- `POST /api/v3/identity/requests`, pour Registration et Unlink.
- Capture immuable de l'EUI authentifiée.
- Association durable S-03 et insertion atomique.
- SELF résolu exclusivement dans WRITE.
- Réemploi des résultats et consultations I1/L4.
- Bruno B2 et assemblage Spring nécessaire.

Les onze opérations sont CreatePot, UpdatePotDetails, DeletePot, AddShareholders, UpdateShareholderDetails, UpdateShareholderWeights, DeleteShareholder, CreateExpense, UpdateExpenseDetails, UpdateExpenseFinancials et DeleteExpense.

Link public, consentement réel, Invitation, nouveau participant READ, refonte Consumption, rationalisation générale des runtimes et qualification globale Q5 restent exclus.

| Champ | Déclaration proposée avant implémentation |
|---|---|
| Slice primaire | **WEB** |
| Slice secondaire | **COMMAND**, pour SELF, contrat durable et interaction Identity |
| Racine complémentaire | `runtime-monolith` |
| PROJECTION/BINDING/EVENT/LKV | Comportements hérités ; aucune modification de leurs algorithmes prévue |
| DB | CURRENT V3 WRITE et READ de qualification, rôles séparés |
| Architecture globale | **REQUIRED pendant I2**, selon l'architecture proposée |
| Full reactor natif | **NOT REQUIRED pendant I2 ; REQUIRED à Q5** |
| Impact interdit | Lookup métier à l'admission, changement des moteurs partagés, leases, routage, calculs et producteurs READ |
| Escalade | Toute modification de ces frontières impose une redéclaration avant poursuite |

Le recours à Identity dans COMMAND n'impose pas automatiquement la slice historique BINDING : l'exécution Identity V3 est portée par le participant raccordé à COMMAND.

## C. Décisions acquises et paramètres ouverts

| Décision | Acquis conservé | Déclinaison V2 |
|---|---|---|
| D2 | Portée authentifiée, canonicalisation versionnée, association unique et atomique, même intention → même requestId, différence → 409, absence de purge V3 | Table commune, empreintes protégées, concurrence PostgreSQL et compatibilité détaillées en D |
| D3 | Unicité d'octroi indépendante de la clé client ; liaison protégée aux identités et à l'intention | Deux empreintes facultatives et contrainte unique préparables ; protocole fournisseur différé |
| D4 | Calibration ciblée, séparation protections techniques/invariants métier | Aucun nombre READ repris automatiquement ; seuils WRITE à établir en F |
| D7 | AuthN/protocole/validation autonome à l'entrée ; autorité courante et métier dans WRITE | Aucun port d'autorité métier dans les services d'admission |

Deux tensions textuelles sont déjà recensées au §8.4 du plan L5 : ADM-01(D) évoque un refus d'usurpation du bindingToken à l'admission ; une phrase de SPEC-02 §5 évoque les droits externes à l'exécution.

**D7 approuvé fournit l'interprétation applicable** : falsification détectable d'identité/protocole refusée à l'entrée ; token opaque non concordant ou périmé contrôlé dans WRITE ; attestations externes vérifiées à l'admission seulement. Les clauses détaillées de SPEC-02 §§5–6 et ID-02 H/K concordent avec cette lecture.

Aucun nouveau conflit normatif bloquant n'est identifié. Le rapport d'implémentation devra conserver cette attribution, sans corriger silencieusement les SPEC.

## D. Architecture minimale

### Composants proposés

| Composant | Emplacement | Pourquoi l'existant ne suffit pas |
|---|---|---|
| `AdmitCommandV3` | `engine-admit-command` | L'admission existante utilise le contrat historique |
| `AdmitIdentityRequestV3` | `engine-admit-registration` | Registration historique ne couvre ni Unlink V3 ni S-03 |
| Contrat commun d'association, avec port étroit | `contracts-command`, sous-package V3 | Aucun contrat commun durable portée/clé/intention n'existe |
| `JdbcAdmissionStore` | `infra-persistence-primary-jpa`, V3 | Les stores d'exécution ne fournissent pas l'unité d'admission requise |
| Deux points d'entrée HTTP et transport WRITE borné | `supra-http-write`, V3 | Contrôleurs historiques ; limites I1 READ |
| Configuration HTTP admission explicite | `composition-runtime-spring` | Le préflight I1 interdit actuellement l'admission |
| Représentation des modes de rattachement | `PotCommand`, codec et exécuteur existants | `Optional<UserId>` ne représente pas SELF/KEEP |

Le contrat commun ne devient pas un service d'orchestration. Il contient les données d'association et les seules opérations de recherche/réservation. Les ports d'insertion Command et Identity peuvent être imbriqués dans leurs services. Un seul adaptateur JDBC peut implémenter ces ports et mutualiser les opérations d'association. Aucun nouveau module Maven, moteur, cache, workflow, file ou état PROCESSING.

### Séquence d'admission

1. Protection transport et authentification.
2. Parsing sûr, sans appliquer prématurément les règles courantes aux anciens retries.
3. Calcul de la portée authentifiée et recherche de l'association.
4. Association présente : comparaison selon sa version canonique et sa clé de comparaison.
5. Association absente : validation autonome courante, attestations requises, préparation de l'intention et de son empreinte hors transaction d'écriture.
6. Transaction WRITE courte : réservation de l'association puis INSERT de la demande.
7. Commit connu avant réponse `202`.
8. Discovery crée les slots ultérieurement.

La répétition ne consulte ni Pot, ni autorité Identity, ni résultat métier WRITE. Elle retourne le requestId original avec `202`, y compris après terminalisation.

### Portée et canonicalisation

Portée logique :

```text
issuer, subject, présence/valeur du client attesté, catégorie, opération, clé client
```

Au contrat I1 actuel, aucun client attesté n'est disponible. La portée sans client est donc la réalisation initiale légitime. `aud`, un en-tête libre ou un identifiant fourni dans le corps ne deviennent pas implicitement un client attesté.

L'intention canonique inclut opération, cible, observation, payload pertinent, EUI initiatrice et cible Identity éventuelle, occurrence de binding déclarée, modes SELF/KEEP/DETACH et future liaison d'octroi Link quand I3 la définira. Elle exclut bearer, traceId, requestId généré et horodatages de transport.

Règles V1 proposées :

- Objets JSON indépendants de l'ordre des propriétés.
- Rationnels exacts normalisés, sans flottants.
- Identités externes opaques préservées, sans trim, changement de casse ou normalisation Unicode inventée.
- Participants financiers triés par identifiant, conformément à leur sémantique de map.
- **Ordre des trois listes multi-cibles conservé** : le code actuel utilise un `LinkedHashMap` et parcourt les cibles ; l'ordre peut influencer le premier rejet.
- Aucun tri générique de tous les tableaux ; doublons rejetés avant construction d'une map.

La version canonique sélectionne également les règles de décodage nécessaires à la comparaison historique. Une nouvelle validation ou un nouveau défaut ne doit pas être injecté avant la recherche de l'ancienne association.

### Schéma physique proposé

Une table `v3_admission_association` :

| Donnée | Forme proposée |
|---|---|
| Identifiant | `request_id uuid primary key` |
| Recherche portée/clé | Empreinte HMAC de 32 octets, unique |
| Contrat | Catégorie, opération, version canonique |
| Comparaison | HMAC d'intention de 32 octets et identifiant de clé |
| Références | `command_id` ou `identity_request_id`, exclusifs et égaux au requestId |
| Intégrité | Deux FK réelles vers les demandes, différées pour réserver avant INSERT |
| Provenance | Date de première admission, identifiant de clé de recherche |
| Préparation I3 | Deux empreintes facultatives : espace d'octroi et identifiant d'octroi |

Contraintes : XOR des références et cohérence de catégorie ; longueurs exactes des empreintes ; une demande ne peut recevoir deux associations ; association immuable ; champs d'octroi tous deux nuls ou présents ; index unique partiel sur le couple d'octroi, indépendant de la clé d'idempotence ; champs d'octroi utilisables seulement pour Link, sans chemin public les renseignant en I2.

Les FK empêchent une association sans demande. Pour garantir aussi l'inverse sur les **nouvelles admissions publiques**, prévoir un marqueur immuable d'origine sur les demandes et une vérification différée à l'insertion exigeant leur association correspondante. Les demandes internes historiques restent valides sans association rétroactive.

Cette vérification ne doit ajouter ni verrou métier ni lecture d'association lors du suffixe de publication Worker. Son déclenchement est limité à l'insertion d'admission.

Une migration additive dans une location CURRENT dédiée, après V6 dans la composition concernée, évite de réécrire V2/V6. Son numéro exact sera réservé au GO après nouveau contrôle des locations.

### Concurrence et transactions

Réservation :

```sql
INSERT ... ON CONFLICT (scope_key_fingerprint) DO NOTHING RETURNING request_id
```

- Gagnant : insère la demande dans la même transaction.
- Perdant : n'insère aucune demande ; lit l'association gagnante dans une nouvelle instruction après résolution du conflit.
- Même intention : requestId gagnant ; intention différente : 409.
- Rollback du gagnant : la réservation concurrente peut réussir normalement.
- Attente excessive : refus technique borné ; aucune boucle de retry applicative.

Une transaction READ COMMITTED suffit pour cette unité, sous contraintes uniques et FK. Le choix final devra être vérifié contre le propriétaire transactionnel effectivement composé.

Réutiliser `SpringAttemptTransactions` avec un owner d'admission dédié. Son emploi JDBC doit être qualifié séparément : L3 R1 ne certifie pas automatiquement cette nouvelle composition.

Le rôle d'admission reçoit seulement les droits requis sur associations et colonnes des demandes. Aucun droit sur Pot, EUI, User, résultats, événements, compteurs, slots ou checkpoints. Examiner les droits effectifs indirects, fonctions et appartenances, pas seulement les GRANT directs.

### Commit incertain

Une exception de commit ne prouve pas un rollback. Réponse technique générique, conservation de la clé côté client, puis nouvelle recherche de la même association. Aucun requestId de remplacement créé pour réparer l'incertitude.

La capacité et les connexions ne sont libérées comme résolues qu'après constat de leur terminaison physique. Aucun statut métier UNKNOWN supplémentaire.

### Clés, rétention et restauration

- HMAC-SHA-256 avec séparation explicite des usages recherche/intention/octroi.
- Clé de recherche logique stable en V3 : son changement naïf permettrait une seconde association pour une ancienne clé client.
- Rotation possible de l'enveloppe de protection de cette clé ; pas de remplacement de sa valeur logique sans migration contrôlée.
- Clés d'intention versionnées : anciennes clés disponibles tant que leurs associations doivent être comparables.
- Identifiant inconnu ou clé incompatible : échec fermé, jamais comparaison approximative.
- Secrets injectés hors Git, base et rapports ; aucun bearer conservé.
- Aucun payload complet supplémentaire dans l'association.
- Aucune purge automatique ; aucune réutilisation de clé après terminalisation.

La restauration porte sur demandes, associations, effets/résultats correspondants et matériel cryptographique compatible. Restaurer seulement les demandes ou seulement les associations ne respecte pas S-03. I2 prouve les références, la reprise d'associations restaurées de fixture et la compatibilité des clés ; ce n'est pas une homologation générale de sauvegarde/restauration de production.

### SELF

Solution minimale proposée : conserver les onze intentions et les types de transition existants, et ajouter au `PotCommand` une petite description immuable des modes de rattachement par cible.

- AddShareholders : `FREE` ou `SELF`.
- UpdateShareholderDetails : `KEEP`, `SELF` ou `DETACH`.
- Anciennes intentions internes concrètes : comportement conservé par un constructeur compatible et absence de modes explicites.
- API publique : aucun UserId accepté.

Le Worker construit les intentions de transition transitoires après résolution de l'acteur WRITE : SELF vers l'acteur du témoin Identity protégé ; KEEP vers la valeur courante WRITE ; DETACH/FREE vers la valeur vide selon l'opération. Le `PotCommand` durable reste inchangé. Les transitions existantes conservent les permissions d'attachement/détachement.

Le codec conserve la lecture historique et versionne la nouvelle représentation. **Aucun ancien Worker ne doit consommer silencieusement une intention contenant ces nouveaux modes** : mise à niveau des consommateurs avant activation de l'admission.

### Identity et préparation Link

Ajouter une origine explicite d'admission HTTP authentifiée pour Registration/Unlink. Ne pas qualifier cette provenance comme `EXTERNAL_AUTHORIZATION_SERVER`.

Conserver le refus actuel du store d'admission interne ; le nouvel adaptateur d'admission utilise son INSERT dédié, sans joindre le résultat.

Pour Link, I2 peut fixer les emplacements et l'unicité physique des deux empreintes facultatives. Restent à I3 : définition de l'espace d'unicité fournisseur ; extraction de l'identifiant d'octroi ; contrat de preuve, signature/session, audience et fraîcheur ; liaison exacte aux identités et à l'intention ; qualification réelle et activation.

Aucune valeur fictive, table Consent ou validation fournisseur en I2.

### HTTP et composition

Réponses : 202 avec requestId et Location du résultat ; 409 pour conflit d'intention ; 400 pour forme invalide ; 413 pour corps excessif ; 415 pour média/encodage non supporté ; 401 pour AuthN invalide ; 503 pour surcharge ou incident technique. Résultat métier ultérieur : 200 COMPLETED/REJECTED selon les lecteurs existants.

Le transport WRITE est distinct du budget READ I1. Restreindre le filtre I1 afin qu'il n'applique pas son plafond de 64 Kio aux deux nouvelles admissions. Ne pas créer de dépendance HTTP WRITE → HTTP READ pour réutiliser ce filtre.

Profil d'admission explicite, associé à la configuration AuthN/READ V3 existante ; imports directs dans WEB et Monolithe. Le préflight conserve une liste fermée de composants et ajoute uniquement le pool d'admission déclaré.

Les classes HTTP optionnelles restent confinées aux configurations HTTP. Aucun nouveau supertype ou champ partagé ne doit forcer leur chargement sur COMMAND, défaut déjà observé en I1.

## E. Performances anticipées

| Chemin | Travail nécessaire | Risque principal | Mesure de conception |
|---|---|---|---|
| Première admission | AuthN, parsing, validation autonome, canonique/HMAC, réservation, INSERT, commit | CPU avant SQL et copies du payload | Bornes avant BigInteger/collections ; calcul hors transaction ; empreinte alimentée progressivement |
| Répétition | AuthN, recherche indexée, comparaison historique, requestId | Nouvelles règles rétroactives ; recalcul coûteux | Version conservée ; aucune reconstruction métier |
| Même clé concurrente | Attente d'unicité et résolution du commit | Transaction retenue, saturation du petit pool | Timeout de verrou ; pas de retry interne ni UPDATE du gagnant |
| Clés distinctes | Inserts indépendants | Index, WAL et connexions | Aucun compteur global d'admission ; aucune sérialisation applicative |
| Exécution | Moteurs et protections existants | SELF hors témoin protégé | Résolution transitoire en préparation WRITE ; témoin revalidé jusqu'au commit |

Parsing/HMAC dépendent du volume d'intention, avec coûts supplémentaires de normalisation des grands rationnels. Le GCD ne se réduit pas à un simple nombre d'octets : il faut le mesurer.

La croissance durable est d'une association par première admission :

```text
ΔN = premières admissions Command + premières admissions Identity
```

Aucune ligne par répétition ou refus. Les coûts physiques ligne/index/WAL ne sont pas mesurés pour ce schéma et ne peuvent être déduits des profils READ ni des charges offertes SPEC-09.

Le pool admission est distinct des pools Worker et READ. Sous l'hypothèse historique de deux racines Worker M=4, connexions réservées = `64 + A`. Avec le candidat A=2 et la réserve fixture/admin historique de 12 : `78 ≤ 97`. Calcul local de connexions seulement, ni capacité de service démontrée ni topologie I2 imposée.

## F. Points ouverts et protocole D4

### État des connaissances

| Sujet | Mesuré | Calculable | Encore inconnu |
|---|---|---|---|
| Corps READ I1 | Tailles et frontières READ | Ratios des profils READ | Taille légitime des intentions WRITE |
| Parsing/canonicalisation WRITE | Aucune mesure réutilisable identifiée | Volume des corpus futurs | CPU, allocations, amplification des rationnels |
| INSERT association/demande | Moteurs et transactions historiques qualifiés | Nombre logique d'INSERT | Coût de la nouvelle unité, index et WAL |
| Concurrence admission | Aucune capacité WRITE publique mesurée | Relation permis/pool | Temps retenu et surcharge |
| Rétention longue | Aucun coût du nouveau schéma | Croissance en nombre d'associations | Taille physique et performances à grande cardinalité |

### Candidats d'expérience uniquement — aucune valeur approuvée

| Paramètre | Points de départ à confronter au corpus |
|---|---|
| Corps Command | 256 Kio et 1 Mio |
| Corps Identity | 16 et 32 Kio |
| Clé d'idempotence | 1–128 octets ASCII, alphabet explicite sans espaces ni contrôles |
| Profondeur JSON | 8 et 16 |
| Textes | Paliers 4 Kio et 64 Kio UTF-8 |
| Entiers rationnels transportés | Paliers 1 024 et 4 096 chiffres |
| Multi-cibles | Profils 1, 100, **101**, 1 000, puis volume borné par le corps |
| Pool / en vol | A=1 puis A=2 ; permis WRITE cohérents avec A |
| Délais | Candidats distincts pour acquisition, verrou, instruction, transaction et socket |

Les plafonds de textes/chiffres seraient des protections de nouvelle admission, jamais des restrictions du domaine ni du décodage des demandes déjà admises.

**Aucun plafond indépendant de 100 cibles proposé.** Une borne de cardinalité éventuelle doit être justifiée par le coût, avec preuve d'une commande légitime au-dessus de 100. Aucune division automatique d'un lot atomique.

### Protocole minimal à autoriser séparément

1. Corpus synthétique versionné des onze intentions, Registration et Unlink.
2. Octets UTF-8 réels : UUID, textes multioctets/échappés, fractions réductibles et grands entiers.
3. JVM isolée : parsing, validation, normalisation, HMAC ; préparation puis échantillons enregistrés, sans inventer de percentile sur quelques passages.
4. PostgreSQL réel jetable : prototype du seul schéma d'admission, deux rôles de service et observateur indépendant.
5. Première admission, répétition, conflit, deux mêmes clés et clés distinctes.
6. Connexions, attentes, plans, tailles relations/index, WAL si disponible, rollback et absence d'orphelins.
7. Surcharge et terminaison physique sous les délais candidats.

Commande envisagée, **pilote temporaire à préparer et revoir, inexistant actuellement** :

```sh
python3 -B /private/tmp/pocoma-l5-i2-calibration/run_once.py \
  --repo /Users/julien.guezennec/Dev/projects/pocoma \
  --output /private/tmp/pocoma-l5-i2-calibration/evidence
```

Réservation : **15–20 minutes d'exécution**, hors préparation du pilote/corpus. La calibration D4 historique a consommé 2,152463 s ; complément à inscrire dans l'enveloppe commune D4, sans second budget automatique.

Décision : retenir le plus petit ensemble de seuils acceptant les profils légitimes, bornant les coûts observés et refusant correctement les dépassements. Profil nécessaire refusé ou ressources non maîtrisées → nouvelle proposition, aucune modification silencieuse du métier.

Cette expérience ne remplace pas la qualification I2. Elle n'est ni autorisée ni exécutée par la publication du plan.

## G. Qualification A/B/C/D et commandes

Toutes les commandes de cette section sont **futures, non exécutées**. Les recettes et classes I2 n'existent pas encore.

### Niveau A — FAST

Deux sélections : A1, **41 méthodes proposées**, sans démarrage Spring/PostgreSQL ; A2, **3 méthodes de codec**, également sans démarrage Spring/PG, mais avec compilation de la fermeture du module primaire.

Objectif A1 : quelques secondes à deux minutes, à mesurer après modification réelle. Aucun temps I2 acquis.

Commandes après ajout/revue des recettes :

```sh
python3 -B scripts/testing/verify.py FAST l5-i2-fast \
  --output /private/tmp/pocoma-l5-i2-fast-01

python3 -B scripts/testing/verify.py FAST l5-i2-codec \
  --output /private/tmp/pocoma-l5-i2-codec-01
```

Sélection Maven A1 exacte prévue, depuis `app/` :

```sh
./mvnw --batch-mode --no-transfer-progress \
  -pl engine-admit-command,engine-admit-registration,engine-write-pot,engine-consume-registration \
  -am \
  -Dtest=V3AdmissionCanonicalizationTest,V3AdmissionInputTest,V3IdentityAdmissionInputTest,PotCommandIntentTest,ExecutePotCommandTest,IdentityRequestContractTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

A2 :

```sh
./mvnw --batch-mode --no-transfer-progress \
  -pl infra-persistence-primary-jpa -am \
  -Dtest=PotCommandJsonTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

L'option Surefire permet les modules amont sans classe sélectionnée ; elle ne dispense jamais le collecteur de vérifier chaque méthode attendue.

### Niveau B — T2 et B2

Recette T2 : **84 méthodes prévues**, soit A1+A2 et 40 méthodes de qualification/interactions. Inventaire de conception, pas résultat actuel.

```sh
python3 -B scripts/testing/verify.py INTEGRATION l5-i2 \
  --output /private/tmp/pocoma-l5-i2-t2-01
```

Ancres : `runtime-web-api,runtime-command-consumption-worker,runtime-monolith`, avec `-am` et sélection positive des méthodes ci-dessous.

Objectif : **5–10 minutes pour T2+B2**, ambitieux et non mesuré ; alerte héritée à 20 minutes. Premier passage : établissement du coût réel. Éviter un A inchangé lancé juste avant T2 lorsqu'il n'apporte aucun diagnostic supplémentaire.

B2 : dossier futur `02 Admission and Commands`, **24 scénarios nominatifs proposés**, inventaire des sous-cas et nombre réel de requêtes : Registration inconnue/libre et Unlink soi/autre EUI du même User ; onze intentions Pot ; reprise même clé, 409, JSON équivalent et séparation des portées ; refus de forme/AuthN sans durable ; résultat et READ asynchrones, propriété après Unlink ; SELF, changement d'autorité et lot légitime au-dessus de 100 ; absence d'activation Link/Invitation.

Commande CLI depuis `scripts/bruno-v3/`, après préparation de fixture :

```sh
./node_modules/.bin/bru run -r "02 Admission and Commands" \
  --env Local --sandbox developer --noproxy \
  --reporter-json /private/tmp/pocoma-l5-i2-b2-01/bruno.json \
  --reporter-skip-all-headers --reporter-skip-body
```

Bruno 4.3.0 est verrouillé ; versions effectives constatées au run. Unlink d'une autre EUI du même User peut nécessiter un état initial Link **interne contrôlé**, attribué à la fixture L3 : aucun Link public ni qualification I3. Les états dont l'objet est l'admission publique sont créés par les nouvelles routes V3.

Polling fixé avant exécution : échéance totale, nombre maximal d'appels, délai par appel et backoff. Un résultat terminal ne vaut pas disponibilité immédiate de toutes les projections.

### Matrice nominative

N = nouveau, proposé ; E = existant. Oracle U : vecteurs indépendants sans Spring/PG. Oracle SQL : connexion d'observation indépendante, états avant/après et barrières réelles. Oracle HTTP : octets/statuts reçus et observations SQL/logs séparées.

Chaque ligne hérite des prérequis, commande et enveloppe de son groupe. Historique absent = **NON MESURÉ** ; les durées de J sont celles de classes historiques, pas des méthodes futures. A exige JDK/dépendances disponibles, sans Spring/PG ; B exige PostgreSQL réel épinglé, autorités et rôles séparés, CURRENT V3 et fixture AuthN locale signée. Le modèle effectif et les sources décident des comptes réels.

PASS = invariant satisfait, inventaire exact, zéro skip. FAIL = invariant violé, assertion/erreur technique ou exit non nul. SKIP = propriété non exécutée, jamais PASS. INCONCLUSIVE = prérequis, observation physique, collecte ou attribution insuffisants. Aucun temps individuel fictif n'est attribué.

| Exigence / invariant | Classe et méthodes | État, nombre | Niveau / oracle / condition de rejeu |
|---|---|---:|---|
| S-03 : équivalence, différences, cadrage non ambigu | `V3AdmissionCanonicalizationTest` : `equivalentObjectsAndExactRationals`, `orderedAndUnorderedCollections`, `canonicalFramingCannotAlias` | N, 3 | A1 / U / changement canonique |
| S-03 : portée et binding | Même classe : `scopeSeparatesIdentityOperationAndClient`, `bindingAndAttachmentAreIntent` | N, 2 | A1 / U / portée, principal ou modes |
| S-03, ADM-01(F) : comparaison historique et clés | Même classe : `oldIntentAfterRuleChange`, `currentAndHistoricalValidationStaySeparate`, `keyRotationAndMissingKeyFailClosed` | N, 3 | A1 / U / règles, versions ou clés |
| ADM-01 : mapping, validation autonome | `V3AdmissionInputTest` : `allElevenOperationsMapExactly`, `malformedTargetsRejectedBeforeStore`, `exactFractionsAndPublicIds`, `clientCannotChooseRequesterOrUser`, `validationFailureDoesNotCallStore` | N, 5 | A1 / U / mapping et contrôles |
| ID-01, frontière ID-02 : Registration/Unlink seulement | `V3IdentityAdmissionInputTest` : `registrationHasNoTokenOrClientEui`, `unlinkNeedsInitiatorTokenOnly`, `linkIsNotPublic`, `attestationsAreServerOwned` | N, 4 | A1 / U / entrée Identity |
| DOM-02, ADM-01 : listes/modes immuables | `PotCommandIntentTest` : trois méthodes existantes `additionsValidateStructureAndCopy`, `detailsValidateStructureAndCopy`, `weightsValidateStructureAndCopy` ; ajout `attachmentModesAreImmutableAndScoped`, `legacyIntentRemainsValid` | E + ajouts, 5 | A1 / U / enveloppe Pot |
| DOM-02, D7 : exécution, fencing, SELF | `ExecutePotCommandTest` : sept méthodes existantes ; ajout `selfUsesProtectedWriteActor`, `keepAndDetachUseWriteFacts` | E + ajouts, 9 | A1 / U / exécuteur ou modes |
| SPEC-02/03 : contrat Identity et provenance | `IdentityRequestContractTest`, dix méthodes actuelles intégralement | E, 10 | A1 / U / origine ou contrat Identity |
| Contrat durable : ancien/nouveau codec | `PotCommandJsonTest` : existante `allThreeTargetListsRoundTripAndDurableCorruptionIsExplicitlyTechnical` ; ajout `attachmentModesRoundTripWithoutResolvedUser`, `unknownPayloadVersionIsTechnical` | E + ajouts, 3 | A2 / U / codec |
| S-03 : première admission, répétition, conflit | `V3AdmissionPostgresTest` : `firstAndReplay`, `differentIntentConflicts`, `scopeIsolation` | N, 3 | B / SQL / association ou portée |
| S-03 : concurrence | Même classe : `concurrentSameKeySameIntent`, `concurrentSameKeyDifferentIntent`, `distinctKeysProgressIndependently` | N, 3 | B / SQL / SQL, index ou transaction |
| S-03, CON-06 : rollback/issue incertaine | Même classe : `rollbackAndOwnerBoundaries`, `lostCommitAcknowledgement`, `unknownCommitDoesNotCreateReplacement` | N, 3 | B / SQL / owner, transaction ou erreur |
| ADM-01 : refus sans durable, aucune lecture métier | Même classe : `invalidLeavesNoDurable`, `admissionNeverReadsBusinessTables` | N, 2 | B / SQL / validation ou accès |
| S-03, sécurité : contraintes, droits, restauration | Même classe : `associationForeignKeysAndImmutability`, `admissionRoleAndIndirectPrivileges`, `restoreAndOldKeyCompatibility` | N, 3 | B / SQL / DDL, ACL ou clés |
| HTTP : admissions, replay, identité capturée | `V3AdmissionHttpTest` : `commandRegistrationUnlinkAccepted`, `sameIdAnd409`, `authenticationAndCapturedRequester` | N, 3 | B / HTTP / contrôleur, AuthN ou mapping |
| ADM-01 : limites/évolution | Même classe : `bodyKeyDepthTextBoundaries`, `mediaDuplicateUnknownAndForbiddenFields`, `limitsChangedReplayOldContract` | N, 3 | B / HTTP + SQL / transport ou règles |
| Ressources/confidentialité | Même classe : `overloadAndPhysicalTimeoutRelease`, `noSecretOrWriteFallback`, `linkAndInvitationNotExposed` | N, 3 | B / HTTP + SQL / ressources, profils ou erreurs |
| DOM/ID : consommateurs réels | `V3AdmissionFlowPostgresTest` : `allElevenPotOperationsThroughWorker`, `registrationAndUnlinkThroughWorker`, `invalidBusinessIntentsAreAdmittedThenRejected` | N, 3 | B / SQL + HTTP / contrat transmis ou consommateur |
| SELF, indépendance READ | Même classe : `selfUsesWriteAndSurvivesBindingChange`, `resultsAndReadProgressIndependently` | N, 2 | B / SQL + HTTP / SELF, publication ou résultat |
| Composition WEB | `V3HttpCompositionPostgresTest` : cinq méthodes actuelles ; ajout `admissionUsesDedicatedOwnerAndRole`, `nonHttpCompositionHasNoAdmissionAllocation` | E + ajouts, 7 | B / inventaire + SQL / composition |
| Composition Monolithe | `V3MonolithHttpCompositionPostgresTest#monolithRoot` ; ajout `admissionAndWorkerUseDistinctPools` | E + ajout, 2 | B / inventaire + SQL / composition |
| Non-régression transport/résultats I1 | `V3ReadHttpContractTest#inputByteBoundaries`, `#mediaAndUnknownFields`, `#terminalResultsOwnership` | E, 3 | B / HTTP / routage filtre et exposition |
| Contrats publics B2 | Dossier Bruno futur, scénarios B201–B224 à nommer dans Coverage | N, 24 scénarios | B / HTTP ; atomicité SQL dans T2 |
| Isolation/dépendances | Inventaire complet `architecture-tests` et fermeture requise | E + adaptations | C / oracles structurels ; déclencheurs §17 |
| Certification finale | Full natif, B5, variantes et externes applicables | Futur | D / SPEC-10 |

Un test parcourant onze opérations conserve **onze reçus de sous-cas**, mais reste une méthode JUnit.

| Module Maven | Classes |
|---|---|
| `engine-admit-command` | Canonicalisation et entrée Command |
| `engine-admit-registration` | Entrée Identity |
| `engine-write-pot` | `PotCommandIntentTest`, `ExecutePotCommandTest` |
| `engine-consume-registration` | `IdentityRequestContractTest` |
| `infra-persistence-primary-jpa` | Codec, `V3AdmissionPostgresTest` |
| `runtime-web-api` | `V3AdmissionHttpTest`, I1 HTTP/composition |
| `runtime-monolith` | `V3AdmissionFlowPostgresTest`, composition Monolithe |

Les FQCN nouveaux suivront les packages existants de ces modules et seront figés dans le registre avant exécution. Les tests de flux utilisent la racine Monolithe ; **aucune dépendance Monolithe → runtime-web-api**, même en test.

### Niveau C — gates transverses

Le plan modifie au moins les dépendances nécessaires entre contrats/admissions/intentions, la sélection des admissions autorisées, l'allocation des pools/owners HTTP/Monolithe et les règles de chargement des composants optionnels. **§17 est déclenché.** Une extension de beans utilisant des contrats/ressources déjà autorisés pourrait rester locale ; ce n'est pas l'état actuel.

Commandes canoniques de référence, depuis `app/` :

```sh
./mvnw -pl runtime-web-api -am test
./mvnw -pl runtime-command-consumption-worker -am test
./mvnw -pl architecture-tests -am test
```

Fermeture mutualisée proposée, une seule invocation non filtrée :

```sh
./mvnw -pl runtime-web-api,runtime-command-consumption-worker,architecture-tests \
  -am test
```

Démontrer par le modèle effectif la présence des deux ancres, de Monolithe, de tous les tests I2 et d'Architecture intégrale. Ne pas payer auparavant les deux slices séparées pour les mêmes entrées.

Collecteur existant après ajout au registre d'un identifiant **explicite de gate** `ARCHITECTURE`, sans nouvelle slice ni déclenchement automatique :

```sh
python3 -B scripts/testing/verify.py INTEGRATION WEB,COMMAND,ARCHITECTURE \
  --output /private/tmp/pocoma-l5-i2-gate-01
```

Cette commande n'est pas disponible dans le registre actuel. Sa revue doit vérifier sélection non filtrée et provenance avant exécution.

Aucun full natif I2 : pas de restructuration large ni d'impact transversal non bornable proposé. Un tel impact découvert changerait cette conclusion.

### Niveau D — Q5 conservé

Un full natif unique :

```sh
python3 -B scripts/testing/verify.py CERTIFICATION \
  --output /private/tmp/pocoma-l5-q5-01
```

Aucune dispense de variante absente, B5, qualification AuthN/Link réelle, preuve externe ou obligation SPEC-10. Les réserves historiques restent ouvertes dans leur portée. Le PASS I2 ne devient pas un certificat global.

## H. Registre de réemploi des preuves

Deux archives I1 ont été relues et leurs SHA-256 vérifiés pendant l'analyse :

- [Reprise finale](../testing/evidence/L5-I1/archives/pocoma-l5-i1-optional-port-01.tar.gz), SHA-256 `c8d14d40920313fe7795e2770fe96ff967e3090fc7879d794775ec915c275a71`.
- [Gate native](../testing/evidence/L5-I1/archives/pocoma-l5-i1-architecture-08.tar.gz), SHA-256 `424c65eaaf1a12d8f2e8d8243f9aa8dd663b2fc3827af02ad693aa0c214f5bd5`.

[Manifeste I1](L5_I1_Proof_Manifest.json). Leurs sources applicatives sont identiques à celles de la référence auditée.

| Propriété | SHA et preuve réutilisable | Hypothèse de validité | Invalidation I2 / réponse |
|---|---|---|---|
| Pot : histoire, versions, mutations, révisions | `46b94ce6…`, archive native I1 ; `PotHistoryPostgresTest` 21, `WriteRevisionPostgresTest` 6, `CommandExecutionPostgresTest` 31 | Domaine, SQL effets, droits et owner inchangés | SELF invalide son raccord/codec ; A/T2 ciblés. Transition/SQL changé → tests Pot affectés |
| Consumption/Discovery | `46b94ce6…`, même archive ; Discovery 49 tests notamment | Algorithmes, routage, claims, leases et sources inchangés | Changement de contrat → variantes L2 pertinentes ; pas de reprise automatique |
| Identity interne | L3 : `145f42731a4e35488e9aaa56a7de3c3f453a9231`, `0ba18ec1e2fb9d9ead4e6ff84b9c839048c82dc3`, manifestes L3/I4/I5 ; contribution fraîche I1 `46b94ce6…` | Autorité, transitions, locks, publication inchangés | Origine/admission nouvelle → raccord/contrat ; autorité modifiée → tests L3 concernés |
| Transactions partagées | `46b94ce6…`, `AttemptTransactionsPostgresTest`, 13 tests | Composant inchangé | Owner admission nouveau à qualifier ; owner partagé modifié → consommateurs et FOUNDATION applicable |
| Projections/résultats L4 | Composite : productif `a3470df662f4552d05785ab3ec191951c4cc9d56`, Architecture `8a007768c23021153764473f6c6686042168df00`, manifeste I8 ; résultats également rejoués en I1 | SQL READ, sources et matérialisateurs inchangés | Forme terminale/source/SQL modifiée → tests L4 correspondants |
| HTTP/AuthN/READ I1 | `46b94ce6…`, T1 40/40 ; reprise 63/63 ; B1 32/32, 66 requêtes | Contrat JWT, DTO, lecteurs conservés | Filtre/composition invalidés localement ; T2 puis gate ; fournisseur réel toujours non qualifié |
| Architecture historique | `46b94ce6…`, 2 231 PASS dont 167 oracles du module | Attribution à cet ancien graphe | **Ne satisfait pas la nouvelle gate I2 déclenchée** |

Dans ce registre, `46b94ce6…` désigne toujours **`46b94ce69a4b0f42474d38ac10b26daa78bb6b23`**.

Archives amont : [manifeste L1](L1_Proof_Manifest.json), [F](L1_F_Proof_Manifest.json), [F2](L1_F2_Proof_Manifest.json), [L2 I2](L2_I2_Proof_Manifest.json), [L2 I4](L2_I4_Proof_Manifest.json), [L3 Identity](L3_Identity_Proof_Manifest.json), [L3 I4](L3_I4_Proof_Manifest.json), [L4 I8](L4_I8_Proof_Manifest.json). Les archives de campagnes précises restent désignées par ces manifestes et leurs index ; aucune revalidation exhaustive de toutes les archives amont n'est prétendue.

Le registre final enregistrera chemins XML précis et hypothèses comparées avant/après I2 : production, tests/oracles, SQL, dépendances, configuration sémantique, JDK et PostgreSQL. Un SHA ancien n'est pas une qualification fraîche. FAIL/SKIP/INCONCLUSIVE historiques inchangés ; qualification composite L4 jamais transformée en full natif PASS.

## I. Inventaire des modifications et ordre de réalisation

Tous les chemins nouveaux sont proposés, non créés par ce plan.

| Étape | Fichiers/composants | Checkpoint |
|---|---|---|
| 0. Figer les entrées | JSON des deux routes, champs inconnus, D4 final, configuration clés/attestations | Décisions/prérequis prêts ; aucune règle métier nouvelle |
| 1. Contrats et FAST | Contrat association dans `contracts-command` ; services V3 des deux modules d'admission ; tests A1 | Onze mappings, anciennes intentions, comparaisons historiques |
| 2. SELF durable | `PotCommand.java`, `ExecutePotCommand.java`, `PotCommandJson.java`, chargement `JdbcPotCommandStore.java` ; tests ciblés | Aucun UserId public ; résolution WRITE ; ancien codec conservé |
| 3. Persistance admission | Nouveau `JdbcAdmissionStore`, migration CURRENT additive, script administratif de privilèges dédié | FK/unicité/immutabilité ; aucun durable partiel ; zéro droit métier |
| 4. Identity | `IdentityRequest.java`, provenance, origine HTTP ; évolution SQL additive | Registration/Unlink publics ; garde interne/Link externe préservés |
| 5. HTTP | Deux contrôleurs V3, transport WRITE ; routage ciblé de `V3ReadTransport` | 202/409/refus ; plafonds WRITE indépendants READ |
| 6. Assemblage | Configuration admission ; `V3ReadHttpConfiguration`, préflight Worker ; imports WEB/Monolithe, propriétés nécessaires | Pool/owner/rôle déclarés ; aucun chargement HTTP optionnel imposé à COMMAND |
| 7. Preuves | Classes A/B, fixture Bruno Monolithe, B2, Coverage/README, recettes | Inventaire attendu révisé, préflight source exact |
| 8. Qualification | T2+B2 puis gate C mutualisée | Résultats, réserves, coûts, preuves attribués |
| 9. Revue technique | Rapport/manifeste I2 après mandat d'implémentation | Audit avant acceptation humaine |

### Localisation concrète des fichiers existants concernés

- [PotCommand](../../app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/port/execution/v3/PotCommand.java).
- [ExecutePotCommand](../../app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/v3/ExecutePotCommand.java).
- [PotCommandJson](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/PotCommandJson.java).
- [JdbcPotCommandStore](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/JdbcPotCommandStore.java).
- [IdentityRequest](../../app/contracts-registration/src/main/java/com/kartaguez/pocoma/contracts/registration/v3/IdentityRequest.java).
- [IdentityRequestJson](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/identity/IdentityRequestJson.java), adaptation seulement si requise par la nouvelle provenance ; le store d'exécution n'est pas transformé en admission publique.
- [V3ReadTransport](../../app/supra-http-read/src/main/java/com/kartaguez/pocoma/supra/http/read/v3/V3ReadTransport.java).
- [V3ReadHttpConfiguration](../../app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/v3/V3ReadHttpConfiguration.java).
- [V3ConsumptionWorkerConfiguration](../../app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/v3/V3ConsumptionWorkerConfiguration.java).
- [Racine WEB](../../app/runtime-web-api/src/main/java/com/kartaguez/pocoma/PocomaWebApiApplication.java), [racine Monolithe](../../app/runtime-monolith/src/main/java/com/kartaguez/pocoma/PocomaRuntimeMonolithApplication.java).
- [Registre de qualification](../../scripts/testing/verification-campaigns.json), [Bruno Coverage](../../scripts/bruno-v3/Coverage.md), [Bruno README](../../scripts/bruno-v3/README.md).

POM à prévoir : `engine-admit-command`, accès au contrat d'intention Pot existant ; `engine-admit-registration`, accès au contrat commun d'association ; déclarations directes optionnelles dans composition si ses références l'exigent ; dépendances de test strictement nécessaires. Chaque arête sera revue pour cycles/chargement optionnel. Aucun déplacement global de `PotCommand`, aucun nouveau module.

Le wrapper éventuel I2 reste un petit propriétaire T2+B2 utilisant le collecteur existant. Le wrapper I1, inventaire fixé à B1, ne doit pas être réinterprété comme preuve B2.

## J. Audit des coûts et budget

### Mesures historiques conservées

| Observation | Durée | Portée/limite |
|---|---:|---|
| Compilation COMMAND propre | 10,365 s | Ancienne fermeture/environnement |
| Test-compile COMMAND après nettoyage | 14,993 s | Aucun test exécuté par cette phase |
| Compilation locale Identity propre | 4,113 s | Fermeture plus petite, couverture différente |
| FAST Identity | 6,686 s / 65 tests ; autre passage 2,205 s | Historique, pas estimation acquise I2 |
| T1+B1 et corrections I1 cumulés | 1 426,037842 s | Échecs/reprises inclus ; pas passage nominal |
| Reprise finale I1 | 537,468959 s | 63 JUnit + B1 |
| Dont Maven ciblé | 522,407524 s | Six classes COMMAND : 442,154 s JUnit, dont 330,357 s pour neuf tests Identity/Pot |
| Bruno final | 2,564 s indiqué par Bruno | Fixture avant CLI 6,377840 s ; fenêtre Java CLI/validation 3,712695 s ; fenêtres imbriquées |
| Gate Architecture native finale | **5 213,848515 s civiles** | 86 min 53,849 s |
| Même phase Maven, monotone | **4 273,249066 s** | Écart inexpliqué de 940,599449 s |
| Collecteur natif, monotone | 4 281,126341 s | Autres phases incluses ; distinct de Maven civil |

Lecture des XML natifs I1, sans exécution :

| Classe | Tests | Durée de suite déclarée |
|---|---:|---:|
| `PotCommandIntentTest` | 3 | 0,004 s |
| `ExecutePotCommandTest` | 7 | 0,019 s |
| `PotCommandJsonTest` | 1 | 0,002 s |
| `IdentityPrivilegesPostgresTest` | 3 | 2,191 s |
| `IdentityRequestExecutionPostgresTest` | 13 | 12,987 s |
| `DiscoveryPostgresTest` | 49 | 503,923 s |
| `V3IdentityPotCommandPostgresTest` | 9 | 319,112 s |
| `V3ReadHttpContractTest` | 24 | 28,461 s |

Ces durées de suites ne séparent pas exhaustivement compilation, démarrage PG, migrations, Spring et corps JUnit. Sources : [temps I1](../testing/evidence/L5-I1/Observed_Timings.md), [analyse des horloges](../testing/evidence/L5-I1/Clock_Measurement_Analysis.json), [optimisation de vérification](../testing/Verification_Optimization_Report.md), archives de H.

### Causes de coût établies

- Closures statiques : 50 modules depuis WEB ou COMMAND, 53 depuis WEB+COMMAND+Monolithe, 60 depuis Architecture, parent exclu. Le modèle effectif Maven reste l'autorité.
- Certaines suites Worker imposent des fenêtres physiques de plusieurs minutes ; `i5_w16` comprend deux observations de cinq minutes.
- Fixtures Identity : schéma, rôles, migrations CURRENT recréés par méthode.
- Contrat HTTP I1 : contexte Spring démarré par méthode.
- Aucun coût exclusif de démarrage Spring/PG ou de migration I2 actuellement mesuré.

### Économies retenues

- A1 sans Spring/PG ; A2 séparé malgré sa fermeture de compilation plus large.
- T2 sélectionne les nouvelles propriétés, sans reprendre toute la classe de neuf tests Identity/Pot à plus de cinq minutes.
- Conteneur statique par classe/protocole, autorités isolées ; aucun partage caché entre fixtures indépendantes.
- B2 initialise une seule campagne cohérente, sans injecter artificiellement ses résultats.
- Pas de `clean`, `install`, packaging, `-amd` ou `-T` par défaut.
- Aucun replay historique V2 ajouté. Baseline TRUSTED : **NONE** ; bootstrap CURRENT conservé ; histoire transitive des gates déclarée.
- PostgreSQL sériel au niveau campagnes Maven ; deux admissions concurrentes d'un scénario = expérience contrôlée.
- Aucune suppression d'oracle sur la seule ressemblance de deux tests.

### Réservations V2 — non mesurées

| Poste | Prévision | Réévaluation |
|---|---:|---|
| A1 après modification | Quelques secondes à 2 min | Compilation/sélection examinées au-delà |
| A2 codec | Cible <2 min | Compilation/JUnit distingués |
| T2+B2 | **5–10 min** | Premier run discriminant ; alerte 20 min |
| Gate C obligatoire | **70–100 min indicatives** | I1 civil 86,9 min conservé ; aucune garantie |
| Calibration D4 complémentaire | 15–20 min d'exécution | Autorisation/corpus préalables |
| Diagnostic | 15 min par incident | Puis hypothèse/décision explicites |
| Réalisation + tests + dossier | **13–23 h estimées** | Hors attente humaine, incidents, qualification fournisseur |

Une fermeture I2 complète ne peut pas être promise en quelques minutes avec la politique actuelle. Réduction principale : boucles de développement et campagnes redondantes.

## K. Relances, critères d'acceptation et preuves

Avant implémentation, figer :

| Catégorie | Contenu |
|---|---|
| Obligatoires | A/B concernés, T2+B2 final, gate C déclenchée, SQL indépendant |
| Conditionnels | Consommateurs effectivement invalidés ; FOUNDATION si owner partagé modifié ; L2 si mécanismes partagés changés |
| Exclus du feedback courant | Full natif, campagne complète Identity/Pot/L2, Architecture à chaque modification, B1 complet inchangé |
| Hérités | Registre H, sous comparaison d'hypothèses |
| Diagnostic | Reproduction minimale avant correction |
| Relance | **Une par cause identifiée** ; tentative supplémentaire seulement avec nouvelle hypothèse et décision |
| Dépassement | Alerte/analyse ; campagne saine menée à terme |

Arrêt contrôlé seulement pour risque, fuite, incohérence déterminante, absence de progression démontrée ou blocage physique. Récolter Maven/forks, observer connexions/transactions, conserver les résultats partiels. Timeout de processus ≠ rollback PostgreSQL.

Après correction : propriété affectée puis consommateurs/gates réellement invalidés. Aucun retry inchangé pour obtenir un résultat favorable.

### Acceptation technique I2

- Deux routes et treize intentions exposées exactement dans leur périmètre.
- EUI immuable issue uniquement de l'authentification ; aucun lookup métier à l'admission.
- Même portée/clé/intention → même requestId, avant/après terminalisation ; différence → 409 sans seconde demande.
- Association/demande atomiques sous concurrence, rollback et réponse perdue.
- SELF résolu par l'acteur WRITE protégé ; aucun UserId public.
- Registration/Unlink réutilisent transitions/publications existantes.
- Refus préalable → zéro demande/association/slot/événement/résultat.
- Demande formée mais invalide métier → admission puis rejet terminal normal.
- Résultats READ appartenant à requesterEui, indépendants du binding courant.
- Limites WRITE ratifiées, frontières exactes vérifiées, surcharge bornée, ressources physiquement restituées.
- Inventaire exact méthodes/scénarios, zéro manque/doublon/skip, provenance et empreintes avant/après.
- Gate Architecture fraîche satisfaite ; aucune obligation masquée par le coût.
- Réserves L1–L4 et R-I1-01…04 reprises explicitement.

Dépendance physique absente → NOT RUN/INCONCLUSIVE, sans réduire l'inventaire attendu. Gate obligatoire non satisfaite → clôture technique correspondante impossible.

## L. Décisions humaines résiduelles

1. **D4 : mandat de calibration puis ratification des seuils.** Seul point nécessitant une mesure physique nouvelle avant fixation des paramètres opérationnels.
2. **Approbation du plan V2 et de son allocation de preuves**, avec Architecture obligatoire I2 et budget séparé.
3. **GO d'implémentation distinct**, après audit et résolution des conditions.

Fournisseur Link/protocole restent à I3. Rétention sans purge, frontière D7 et principe d'association commun ne sont pas rouverts. Le mandat « Push le plan » résout seulement la question de publication documentaire ; il est indépendant de ces décisions et du GO technique.

## Gains de qualification escomptés

- **Mesuré historiquement :** tests purs concernés en millisecondes JUnit ; une classe Identity/Pot à plus de cinq minutes ; Discovery à plus de huit minutes ; gate native I1 à 86,9 minutes civiles.
- **Estimé :** A1 <2 minutes, T2+B2 5–10 minutes, par sélection des nouveaux invariants et réemploi attribué des moteurs inchangés.
- **Économie structurelle proposée :** une fermeture non filtrée WEB+COMMAND+Architecture, au lieu de trois campagnes fortement recouvrantes.
- **Limite :** aucun gain I2 encore mesuré ; gate obligatoire due, full natif Q5 indépendant.

## Vérification documentaire et publication

Déclaration avant écriture : **documentation uniquement ; slices primaire/secondaires NONE ; DB NONE ; impact applicatif interdit ; franchissement inattendu NONE ; Architecture globale NOT REQUIRED / NOT RUN ; full reactor NOT REQUIRED / NOT RUN pour cette publication**.

Contrôles autorisés : Git en lecture seule, références locales et SHAs du document, inventaires statiques, revue du diff et `git diff --check`. Aucun Maven, JUnit, Bruno, PostgreSQL, Docker ou Spring ; aucune migration, source, test, POM, recette, SPEC ou politique modifié.

Un seul fichier documentaire est proposé au commit : le présent plan. Commit/push sans force sur `feat/v3-architecture` autorisés par le mandat « Push le plan » ; le SHA publié et le contrôle de tête distante sont fournis dans le reçu de livraison de la conversation.

Déclaration de l'analyse initiale : **NO IMPLEMENTATION, NO TEST EXECUTION, NO COMMIT, NO GO**. La seule exception ultérieure est le **commit et push documentaires explicitement demandés**. Pour la publication : **NO IMPLEMENTATION, NO TEST EXECUTION, NO IMPLEMENTATION GO**. Aucun verdict de clôture I2 ou L5.
