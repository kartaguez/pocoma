# Pocoma V3 — L5/Q5-B — Rapport de qualification finale et consolidation des preuves

Date : **5 novembre 2026**
Dépôt : **`kartaguez/pocoma`**
Branche : **`feat/v3-architecture`**
SHA initial de référence : **`b7bc2bdbcb39d4c3f9cde67718b73351e2e34913`** (`b7bc2bdb`)
SHA Phase 0 (préparatoire) & Tentative 1 : **`ac32a7db58545bf147b244a2fe528636b4bb464f`** (`ac32a7db`)
SHA Phase 3 (correctif ciblé) & Tentative 2 : **`0e5583478fa5a2b5da3028671e4a2ad7ee1412de`** (`0e558347`)
Statut officiel terminal : **Q5-B — QUALIFICATION FAILED — REPETITION LIMIT REACHED — DIAGNOSIS RECORDED**
Statut du lot L5 : **L5 — OPEN — NO CLOSURE BEFORE Q5-C AND HUMAN DECISION Q5-D**

---

## 1. Déclarations normatives préalables (Reactor Verification Policy)

Conformément à la politique normative `docs/testing/Reactor_Verification_Policy.md` et aux directives de gouvernance de Pocoma :

- **Tranche(s) de vérification déclarée(s) :**
  - Tranche principale : **CERTIFICATION** (Full Reactor natif non filtré, V3 G5 Milestone / SPEC-10).
  - Tranches secondaires : **ARCH** (`architecture-tests`), **BRUNO** (B2, B3, B4 intégrés dans `runtime-monolith`, B1 autonome dans `runtime-web-api`, B5 consolidé).
  - Tranche locale ciblée (Phase 2) : Slice `infra-persistence-primary-jpa` pour validation du test d'identité.
- **Commandes réellement exécutées :**
  1. *Préflight 1 :* `docker compose -p pocoma-functional -f docker-compose.functional.yml up -d postgres keycloak`
  2. *Tentative 1 (Full Maven CERTIFICATION) :*
     `python3 -B scripts/testing/verify.py CERTIFICATION --output "/tmp/pocoma-q5b-evidence-20261105/maven-full"`
  3. *Validation locale ciblée (Slice test après fix) :*
     `./mvnw -pl infra-persistence-primary-jpa -am -Dtest=IdentityDiscoveryPostgresTest -Dsurefire.failIfNoSpecifiedTests=false test`
  4. *Préflight 2 :* `scripts/testing/l5-i3-auth.py`
  5. *Tentative 2 unique autorisée (Full Maven CERTIFICATION de reprise) :*
     `python3 -B scripts/testing/verify.py CERTIFICATION --output "/tmp/pocoma-q5b-evidence-20261105-retry/maven-full"`
- **Franchissement inattendu de frontière de tranche :** Aucun franchissement imprévu d'architecture ; la sélection CERTIFICATION couvrait l'ensemble des modules par mandat de clôture de lot (§35).
- **Gate globale requise :** **OUI** (Gate Architecture et Gate de Certification globale requises par le mandat Q5-B).
- **Exécution du full reactor :** **OUI**, initié de façon canonique et native pour satisfaire les obligations de certification de fin de lot L5 (deux exécutions maximales autorisées par le GO humain, 0 exécution supplémentaire).

---

## 2. Contexte, décisions humaines et mandats d'exécution

Les quatre incréments de L5 (L5/I1, L5/I2, L5/I3 et L5/I4) ont été clos avec des réserves explicites. La mission Q5-A a défini une stratégie de qualification globale mutualisée :
1. Une unique campagne Maven globale CERTIFICATION couvrant Surefire, Architecture et les suites Bruno B2, B3 et B4 hébergées dans `runtime-monolith`.
2. L'exécution d'un complément autonome B1 (`V3ReadHttpFixture.main`) via le classpath Surefire de `runtime-web-api`.
3. La consolidation des 70 scénarios B5 via `scripts/testing/consolidate_bruno_b5.py`.

### 2.1 Décision GO Q5-B initiale
L'arbitrage humain a accordé le GO sous réserve de deux corrections préparatoires (AUD-Q5A-05 et AUD-Q5A-06), avec un mandat strict interdisant tout développement fonctionnel, refactorisation ou boucle de reprise non bornée.

### 2.2 Décision GO Reprise bornée suite à l'incident AUD-Q5B-01
Suite à l'interruption de la première tentative au module 24 sur un oracle obsolète d'`IdentityDiscoveryPostgresTest`, l'arbitrage humain a accordé un mandat spécifique :
- **Mode :** `TARGETED TEST CORRECTION AUTHORIZED — ONE FULL CERTIFICATION RETRY — NO FUNCTIONAL DEVELOPMENT — NO AUTOMATIC RETRY LOOP`.
- **Autorisation :** Correction ciblée de la seule assertion de test, preuve locale, commit/push, et une seule nouvelle exécution globale CERTIFICATION.
- **Interdiction formelle :** Aucune troisième tentative, aucun développement fonctionnel, conservation intégrale des preuves des deux tentatives, lot L5 maintenu ouvert en cas de nouvel échec.

---

## 3. Phase 0 — Corrections opérationnelles préalables (AUD-Q5A-05 & AUD-Q5A-06)

Avant la première campagne, deux points bloquants d'outillage ont été résolus et publiés sur `feat/v3-architecture` au commit [`ac32a7db`](https://github.com/kartaguez/pocoma/commit/ac32a7db58545bf147b244a2fe528636b4bb464f) :

1. **AUD-Q5A-05 — Consolidateur B5 :**
   Création de [`scripts/testing/consolidate_bruno_b5.py`](file:///Users/julien.guezennec/Dev/projects/pocoma/scripts/testing/consolidate_bruno_b5.py) en bibliothèque standard Python pure (0 dépendance externe, 0 conteneur, 0 appel réseau). Contrôle strict des 70 scénarios (B1: 32, B2: 24, B3: 10, B4: 4), 0 failure, 0 error, 0 skip, hachage SHA-256 de chaque preuve et contrôle d'absence de fuite de tokens JWT dans le manifeste. Validé sur données synthétiques et archives réelles I1/I4.
2. **AUD-Q5A-06 — Procédure B1 et racine d'évidence :**
   Sécurisation du passage du classpath Surefire dans [`docs/v3/L5_Q5_Final_Qualification_Plan.md`](file:///Users/julien.guezennec/Dev/projects/pocoma/docs/v3/L5_Q5_Final_Qualification_Plan.md), suppression des fallbacks non équivalents (`dependency:build-classpath`), fixation d'une racine d'évidence unique `<EVIDENCE_ROOT>`.

---

## 4. Première campagne de qualification globale (`ac32a7db`)

### 4.1 Exécution
- **Date & Heure :** 5 novembre 2026, 16:23:57 – 16:41:28 CET (durée : 1 051,58 s).
- **Commande :** `python3 -B scripts/testing/verify.py CERTIFICATION --output /tmp/pocoma-q5b-evidence-20261105/maven-full`
- **Invocation ID :** `61160296705d42239d26995b8fb5c0b6`
- **Résultat :** `FAIL` (1 532 tests exécutés, 1 531 PASS, 1 FAIL, 0 ERROR, 0 SKIP).

### 4.2 Module d'arrêt et incident AUD-Q5B-01
- **Module :** `pocoma-infra-persistence-primary-jpa` (module 24/61).
- **Test en échec :** `IdentityDiscoveryPostgresTest#l3_identity_windows` (ligne 98).
- **Diagnostic :** L'oracle de test vérifiait `assertThrows(IllegalArgumentException.class, () -> new JdbcEventDiscoveryReferences(source, ConsumableFamily.INVITATION_EVENT, discoverySettings))`.
  Or, depuis l'incrément L5/I4, la famille `INVITATION_EVENT` est légitimement gérée par `JdbcEventDiscoveryReferences`. L'assertion historique issue de L3 était donc devenue fausse et obsolète.
- **Impact :** Interruption de Maven au module 24 ; 37 modules avals non exécutés (dont le monolith portant Bruno B2/B3/B4, `runtime-web-api` générant le classpath B1, et `architecture-tests`).

---

## 5. Phase 1 à 3 — Traitement d'AUD-Q5B-01 et préparation de la reprise

### 5.1 Correction ciblée de l'oracle de test
- **Fichier modifié :**
  [`app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/identity/IdentityDiscoveryPostgresTest.java`](file:///Users/julien.guezennec/Dev/projects/pocoma/app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/identity/IdentityDiscoveryPostgresTest.java)
- **Nature de la modification :**
  - Ajout d'un oracle positif démontrant que `INVITATION_EVENT` est désormais instanciable et expose sa famille nominalement :
    ```java
    var invitations =
            new JdbcEventDiscoveryReferences(
                    source, ConsumableFamily.INVITATION_EVENT, discoverySettings);
    assertEquals(ConsumableFamily.INVITATION_EVENT, invitations.family());
    ```
  - Repositionnement de l'oracle négatif sur une famille réellement non gérée par les événements (`ConsumableFamily.COMMAND`) afin de préserver la valeur discriminante de la garde :
    ```java
    assertThrows(
            IllegalArgumentException.class,
            () ->
                    new JdbcEventDiscoveryReferences(
                            source, ConsumableFamily.COMMAND, discoverySettings));
    ```
- **Code productif :** Aucun changement.
- **POM / configurations :** Aucun changement.

### 5.2 Preuve locale de la correction
- **Commande exécutée :**
  ```sh
  ./mvnw -pl infra-persistence-primary-jpa -am -Dtest=IdentityDiscoveryPostgresTest -Dsurefire.failIfNoSpecifiedTests=false test
  ```
- **Résultat :** **BUILD SUCCESS** (5,43 s). 3 tests exécutés, **3 PASS**, 0 FAIL, 0 ERROR, 0 SKIP.

### 5.3 Publication et gel du SHA de reprise
- Vérification : `git diff --check` sans anomalie.
- Commit : [`0e558347`](https://github.com/kartaguez/pocoma/commit/0e5583478fa5a2b5da3028671e4a2ad7ee1412de) (`test(identity): align discovery event assertions with L5/I4 invitation support (AUD-Q5B-01)`).
- Poussé sur `origin/feat/v3-architecture`.
- **Nouveau SHA de qualification :** `0e5583478fa5a2b5da3028671e4a2ad7ee1412de`.

---

## 6. Phase 4 — Seconde campagne de qualification globale unique de reprise

### 6.1 Préflight
- Base de données PostgreSQL fonctionnelle et conteneur Keycloak opérationnel.
- Acquisition préflight des 3 tokens OIDC réels validée (`scripts/testing/l5-i3-auth.py` OK).
- Répertoire d'évidence dédié créé vierge : `/tmp/pocoma-q5b-evidence-20261105-retry/maven-full`.
- Arbre Git propre sur le commit `0e558347`.

### 6.2 Commande exécutée
```sh
export JAVA_HOME="/Users/julien.guezennec/.sdkman/candidates/java/current"
export PATH="$JAVA_HOME/bin:$PATH"
python3 -B scripts/testing/verify.py CERTIFICATION \
  --output "/tmp/pocoma-q5b-evidence-20261105-retry/maven-full"
```

- **Invocation ID :** `5ce7d6f15ac943acae5197ecd80b9534`
- **Début d'exécution :** 2026-11-05 16:59:05 CET
- **Fin d'exécution :** 2026-11-05 17:20:38 CET
- **Durée totale observée :** 1 276,03 s (~21 min 16 s ; campagne totale : 1 283,99 s).
- **Verdict :** **`FAIL`** (exit code 1).

### 6.3 Bilan quantitatif de la seconde campagne
- **Modules du réacteur :** 61 déclarés.
- **Modules exécutés avec succès :** **50 modules**.
- **Validation définitive du correctif AUD-Q5B-01 :**
  Le module `pocoma-infra-persistence-primary-jpa` (module 43 dans l'ordre réacteur effectif de cette campagne) a été **entièrement exécuté avec succès en 16 min 38 s** sans aucune failure ni erreur.
- **Progression par rapport à la tentative 1 :**
  +27 modules exécutés avec succès par rapport à la première tentative (notamment `pocoma-infra-persistence-read-jdbc`, `pocoma-infra-persistence-primary-jpa`, `pocoma-supra-http-write`, `pocoma-supra-http-read`, `pocoma-composition-runtime-spring`).
- **Tests exécutés :** **1 682 tests** (+150 tests exécutés par rapport à la première tentative).
- **Résultats détaillés des tests :**
  - Tests réussis : **1 678**
  - Failures : **0**
  - Errors : **4**
  - Skipped : **0**
- **Module en échec :** Module 51 (`pocoma-runtime-task-consumption-worker`).
- **Modules avals skippés :** 10 modules :
  - `pocoma-runtime-monolith`
  - `pocoma-runtime-web-api`
  - `pocoma-runtime-event-consumption-worker`
  - `pocoma-runtime-command-result-consumption-worker`
  - `pocoma-runtime-registration-result-consumption-worker`
  - `pocoma-runtime-registration-consumption-worker`
  - `pocoma-runtime-latest-known-version-consumption-worker`
  - `pocoma-runtime-binding-consumption-worker`
  - `pocoma-runtime-command-consumption-worker`
  - `pocoma-architecture-tests`

---

## 7. Analyse approfondie de la nouvelle anomalie (AUD-Q5B-02)

L'échec de la seconde tentative s'est produit dans le module `pocoma-runtime-task-consumption-worker` lors de l'exécution des tests de runtime de consommation. Quatre erreurs ont été constatées dans trois classes distinctes :

### 7.1 Erreur 1 : Débordement d'index dans `ReadActivationSettingsTest`
- **Classe & Méthode :** `ReadActivationSettingsTest#independentSelectionAndTemporalPreflight` (ligne 85)
- **Erreur :** `java.lang.ArrayIndexOutOfBoundsException: Index 6 out of bounds for length 6`
- **Mécanisme :**
  La liste `V3ConsumptionWorkerSettings.READ_NAMES` contient désormais 7 participants de lecture (incluant les extensions L5). Dans le test unitaire, la boucle itère sur `names.size()` (7), mais accède à une liste fixe `ids` ne contenant que 6 UUIDs pré-renseignés :
  ```java
  var names = V3ConsumptionWorkerSettings.READ_NAMES; // taille 7
  var ids = List.of( ... ); // 6 éléments
  for (int i = 0; i < names.size(); i++)
      assertEquals(ids.get(i), ...); // Déborde à i=6
  ```
- **Qualification :** Défaut d'oracle de test unitaire non aligné avec l'extension de la liste des participants.

### 7.2 Erreurs 2, 3 et 4 : Dépassement des marges temporelles du pilote (`IllegalArgumentException`)
- **Classes & Méthodes :**
  1. `ReadFacadesPostgresTest#bindingAndResultsKeepTheirOwnAuthorities` (via `realUnlinkPropagationWindow`, ligne 841)
  2. `ReadRuntimeAssemblyPostgresTest#rootsAndPrivilegesAreClosed` (ligne 101)
  3. `ReadRuntimeAssemblyPostgresTest#sixParticipantsProgressThroughWorkers` (ligne 284)
- **Erreur :** `java.lang.IllegalArgumentException: renewal budget exceeds pilot margins; explicit requalification required`
- **Origine :** Levée par la garde temporelle stricte dans `app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/v3/V3ConsumptionWorkerSettings.java` (lignes 311–317) :
  ```java
  if (leaseBound.compareTo(life.leaseDuration()) >= 0
          || settings.renewalInterval()
                          .plusSeconds(5L * mw + 3)
                          .compareTo(life.claimDuration())
                  >= 0)
      throw new IllegalArgumentException(
              "renewal budget exceeds pilot margins; explicit requalification required");
  ```
- **Mécanisme :**
  Dans ces tests d'intégration, la fixture `ReadRuntimeI8Fixture` initialise un contexte Spring multi-participants. Le calcul de la borne temporelle du bail (`leaseBound`) ou du délai de renouvellement (`renewalInterval().plusSeconds(...)`) excède la marge du pilote configurée (`leaseDuration` ou `claimDuration`) lorsque plusieurs participants concurrents sont activés.
- **Qualification :** Condition de garde de configuration temporelle stricte violée lors de l'assemblage multi-participants de la fixture de test.

---

## 8. Synthèse comparée des campagnes et traçabilité des anomalies

| Métrique / Élément | Tentative 1 (`ac32a7db`) | Tentative 2 (`0e558347`) | Évolution / Statut |
|---|---|---|---|
| **Date & Heure** | 05/11/2026 16:29 CET | 05/11/2026 16:59 CET | Reprise unique réalisée |
| **Durée** | 1 051,58 s (~17 min) | 1 276,03 s (~21 min) | +225 s d'exécution supplémentaire |
| **Modules réacteur déclarés** | 61 | 61 | Identique |
| **Modules réussis** | 23 | **50** | **+27 modules franchis** |
| **Module d'arrêt** | `infra-persistence-primary-jpa` (24) | `runtime-task-consumption-worker` (51) | Repoussé de 27 modules |
| **Tests exécutés** | 1 532 | **1 682** | **+150 tests exécutés** |
| **Tests PASS** | 1 531 | **1 678** | **+147 tests PASS** |
| **Failures** | 1 | **0** | Aucune assertion échouée |
| **Errors** | 0 | **4** | 4 exceptions au module 51 |
| **Statut AUD-Q5B-01** | Échec (assertion obsolète) | **RÉSOLU & VALIDÉ** | Module 43 entièrement PASS |
| **Statut AUD-Q5B-02** | Non atteint | **DÉCOUVERT (FAIL)** | 4 erreurs module 51 documentées |
| **Gate Architecture (178 oracles)** | Non atteinte | Non atteinte | Module 61 skippé |
| **Bruno B2, B3, B4** | Non atteints | Non atteints | `runtime-monolith` skippé |
| **Bruno B1** | NOT READY | NOT READY | `runtime-web-api` skippé |
| **Consolidation B5** | INCOMPLETE | INCOMPLETE | Non lancée |

---

## 9. Matrice de couverture des obligations L5

| Exigence / Propriété | Obligation normative | Statut visé Q5-B | Statut observé | Motif / Diagnostic |
|---|---|---|:---:|---|
| **REQ-READ-01 à 07** | Consultation, soldes, masquage, quota $Q=2$ | PASS CERTIFICATION | **NOT REACHED** | `runtime-web-api` non atteint |
| **REQ-CMD-01 à 05** | 11 commandes Pot, Registration/Unlink, S-03 | PASS CERTIFICATION | **NOT REACHED** | `runtime-monolith` non atteint |
| **REQ-CMD-06** | Privilèges SQL V8 resserrés | PASS CERTIFICATION | **PASS VERIFIED** | Validé dans `infra-persistence-primary-jpa` |
| **REQ-LINK-01 à 04** | Consentement E2, binding, anti-rejeu | PASS CERTIFICATION | **NOT REACHED** | `runtime-monolith` non atteint |
| **REQ-INV-01 à 09** | Protocole d'invitation, AES-GCM, expiration | PASS CERTIFICATION | **NOT REACHED** | `runtime-monolith` non atteint |
| **REQ-DEP-01 à 03** | Désaffiliation volontaire sans perte d'historique | PASS CERTIFICATION | **NOT REACHED** | `runtime-monolith` non atteint |
| **REQ-ARCH-01** | Architecture globale (178 oracles) | PASS CERTIFICATION | **NOT RUN** | Module `architecture-tests` skippé |
| **REQ-EXT-01** | Keycloak OIDC réel port 8081 | PASS CERTIFICATION | **NOT RUN** | Préflight validé, monolith non atteint |
| **REQ-EXT-02** | 70 scénarios Bruno B5 consolidés | B5 COMPOSITE PASS | **INCOMPLETE** | Artefacts B1, B2, B3, B4 non produits |
| **AUD-Q5B-01** | Non-régression découverte Identity | PASS CERTIFICATION | **RESOLVED / PASS** | Validé unitairement et dans le full |
| **AUD-Q5B-02** | Isolation & marges runtime de consommation | PASS CERTIFICATION | **FAIL (4 ERRORS)** | Erreurs dans `runtime-task-consumption-worker` |

---

## 10. Préservation intégrale et archivage des preuves

Les preuves des deux tentatives sont rigoureusement isolées et archivées dans le dépôt :

- Dossier racine d'évidence : [`docs/testing/evidence/L5-Q5B/`](file:///Users/julien.guezennec/Dev/projects/pocoma/docs/testing/evidence/L5-Q5B/)
- **Sous-dossier Tentative 1 :** [`docs/testing/evidence/L5-Q5B/attempt-1-ac32a7db/`](file:///Users/julien.guezennec/Dev/projects/pocoma/docs/testing/evidence/L5-Q5B/attempt-1-ac32a7db/)
  - `plan.json` (SHA-256 : `8ee75b8ba1704e6c99c9da6060c5a3597d83dd27ce5b7d60f9ef465c4ecab9f9`)
  - `provenance.json` (SHA-256 : `e358bfa79fe5fc5b3511eb9bc76317bc2d44fc3777d5ea8c919d7d242ef99827`)
  - `result.json` (SHA-256 : `9eefee0396fd0a4f5cb13ef419cb7d4eb832962eb7dd826f04646700df13d508`)
  - `timings.json` (SHA-256 : `b99d6350f555c82ff2f61a1532f7823f668270529d892e850b5514f04c6d2fe2`)
  - Rapports Surefire XML et TXT d'`IdentityDiscoveryPostgresTest`.
- **Sous-dossier Tentative 2 :** [`docs/testing/evidence/L5-Q5B/attempt-2-0e558347/`](file:///Users/julien.guezennec/Dev/projects/pocoma/docs/testing/evidence/L5-Q5B/attempt-2-0e558347/)
  - `plan.json` (SHA-256 : `c52119eb1899147fc046f8eeec952402ffcf5ebc402d066b5b5b4819d45ef3bb`)
  - `provenance.json` (SHA-256 : `1ae0a094a9d70104fc7ba93780a47d4e5eead907727189196b01b6357064971c`)
  - `result.json` (SHA-256 : `4c1b9795fd8fe08285519ec355152a5509749eb403ce8faee84fa89dc5f2e600`)
  - `timings.json` (SHA-256 : `ef6ec33c3a113a3dd4cbef2ba0a5bb5c81dff90e54b6d3968ae2f58e13d9ca37`)
  - `maven-failure.txt` (extrait des 350 dernières lignes de `maven.log`)
  - Rapports Surefire XML et TXT de `ReadActivationSettingsTest`, `ReadFacadesPostgresTest` et `ReadRuntimeAssemblyPostgresTest`.
- **Manifeste global d'intégrité :** [`docs/testing/evidence/L5-Q5B/manifest.json`](file:///Users/julien.guezennec/Dev/projects/pocoma/docs/testing/evidence/L5-Q5B/manifest.json)
- **Descriptif d'archivage :** [`docs/testing/evidence/L5-Q5B/README.md`](file:///Users/julien.guezennec/Dev/projects/pocoma/docs/testing/evidence/L5-Q5B/README.md)

---

## 11. Clôture de l'intervention et statut officiel terminal

En conformité stricte avec le mandat :
1. La limite d'une reprise globale unique a été strictement respectée.
2. Aucun troisième run n'a été déclenché.
3. Aucune modification de code opportuniste n'a été engagée dans ce cycle.
4. Les services Docker ont été stoppés.
5. La branche `feat/v3-architecture` n'a pas été fusionnée.
6. Le lot L5 n'a pas été tagué ni clos.
7. L'audit Q5-C n'a pas été déclenché.

```
================================================================================
Q5-B — QUALIFICATION FAILED — REPETITION LIMIT REACHED — LOT L5 OPEN
================================================================================
1. Tentative 1 (ac32a7db) : FAIL à infra-persistence-primary-jpa (AUD-Q5B-01)
2. Correctif AUD-Q5B-01 (0e558347) : VALIDÉ (infra-persistence-primary-jpa PASS)
3. Tentative 2 (0e558347) : FAIL au module 51 pocoma-runtime-task-consumption-worker
   - 1 682 tests exécutés, 1 678 PASS, 0 FAIL, 4 ERRORS (AUD-Q5B-02)
   - 50 modules réussis sur 61
   - 10 modules avals non exécutés (monolith, web-api, architecture-tests)
4. Bruno B1 à B5 : Non exécutés / Incomplets
5. Preuves complètes : Archivées sous docs/testing/evidence/L5-Q5B/ (tentatives 1 et 2)
================================================================================
STATUT LOT L5 : OUVERT — NÉCESSITE ARBITRAGE HUMAIN SUR AUD-Q5B-02
================================================================================
```
