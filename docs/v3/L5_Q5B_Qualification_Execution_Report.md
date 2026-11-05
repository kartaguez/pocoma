# Pocoma V3 — L5/Q5-B — Rapport de qualification finale et consolidation des preuves

Date : **5 novembre 2026**  
Dépôt : **`kartaguez/pocoma`**  
Branche : **`feat/v3-architecture`**  
SHA de référence initial : **`b7bc2bdbcb39d4c3f9cde67718b73351e2e34913`** (`b7bc2bdb`)  
SHA des corrections préparatoires Phase 0 : **`ac32a7db58545bf147b244a2fe528636b4bb464f`** (`ac32a7db`)  
SHA qualifié sous test : **`ac32a7db58545bf147b244a2fe528636b4bb464f`** (`ac32a7db`)  
Statut officiel terminal : **Q5-B — QUALIFICATION FAILED — DIAGNOSIS RECORDED**  
Statut du lot L5 : **L5 — OPEN — NO CLOSURE BEFORE Q5-C AND HUMAN DECISION Q5-D**  

---

## 1. Déclarations normatives préalables (Reactor Verification Policy)

Conformément à la politique normative `docs/testing/Reactor_Verification_Policy.md` et aux règles de gouvernance du projet :

- **Tranche(s) de vérification déclarée(s) :**
  - Tranche principale : **CERTIFICATION** (Full Reactor natif non filtré, V3 G5 Milestone / SPEC-10).
  - Tranches secondaires : **ARCH** (`architecture-tests`), **BRUNO** (B2, B3, B4 intégrés dans `runtime-monolith`, B1 autonome dans `runtime-web-api`, B5 consolidé).
- **Commandes réellement exécutées :**
  1. `docker compose -p pocoma-functional -f docker-compose.functional.yml up -d postgres keycloak` (Préflight Phase 1).
  2. `python3 -B scripts/testing/verify.py CERTIFICATION --output "/tmp/pocoma-q5b-evidence-20261105/maven-full"` (Phase 2).
- **Franchissement inattendu de frontière de tranche :** Aucun franchissement imprévu d'architecture ; la sélection CERTIFICATION couvrait l'ensemble des modules par mandat de clôture de lot (§35).
- **Gate globale requise :** **OUI** (Gate Architecture et Gate de Certification globale requises par le mandat Q5-B).
- **Exécution du full reactor :** **OUI**, initié de façon canonique et native pour satisfaire les obligations de certification de fin de lot L5.

---

## 2. Contexte et mandat d'exécution

Les incréments fonctionnels L5/I1, L5/I2, L5/I3 et L5/I4 ont été clos avec réserves explicites. La mission Q5-A a défini une stratégie de qualification finale mutualisée :
1. Une unique campagne Maven globale CERTIFICATION couvrant Surefire, Architecture et les trois suites Bruno B2, B3 et B4 hébergées dans `runtime-monolith`.
2. L'exécution d'un complément autonome B1 (`V3ReadHttpFixture.main`) alimenté par le classpath généré lors du build.
3. La consolidation des 70 scénarios B5 (`scripts/testing/consolidate_bruno_b5.py`).

L'arbitrage humain a accordé le **GO Q5-B** sous réserve des corrections préalables AUD-Q5A-05 et AUD-Q5A-06, tout en interdisant formellement :
- Tout développement fonctionnel ou refactorisation.
- Toute modification des invariants métier.
- Tout affaiblissement de test (`@Disabled`, exclusion, modification arbitraire de timeout).
- Toute clôture anticipée de L5 ou engagement de Q5-C.

---

## 3. Phase 0 — Réalisation des corrections préparatoires

Avant tout déclenchement de la campagne de qualification, les deux points bloquants identifiés par l'audit ont été traités et publiés :

### 3.1 AUD-Q5A-05 : Fourniture du consolidateur minimal B5
- **Création du script :** [`scripts/testing/consolidate_bruno_b5.py`](file:///Users/julien.guezennec/Dev/projects/pocoma/scripts/testing/consolidate_bruno_b5.py).
- **Propriétés du script :**
  - Développé exclusivement avec la bibliothèque standard Python (`argparse`, `json`, `hashlib`, `pathlib`).
  - Zéro dépendance externe, zéro écoute réseau, zéro démarrage de conteneur, zéro modification de fichiers sources.
  - Réconciliation stricte des 70 scénarios attendus (B01–B32, B201–B224, B301–B313, B401–B404).
  - Contrôle d'absence d'erreurs, skips ou échecs (`failedRequests == 0`, `errorRequests == 0`, `skippedRequests == 0`).
  - Scan de non-prolifération des secrets (rejet de tout token JWT dans le manifeste consolidé).
  - Distinction explicite dans le manifeste entre qualification composite étanche et absence de parcours partagé sur un état universel unique.
  - Verdicts émis : `B5 COMPOSITE PASS` (code 0), `B5 FAIL` (code 1), `B5 INCOMPLETE / INCONCLUSIVE` (code 2).
- **Vérification préparatoire statique et synthétique :** Validé avec succès sur données de test synthétiques (cas nominal, cas manquant, cas d'échec) et sur les archives réelles de L5/I1 et L5/I4.

### 3.2 AUD-Q5A-06 : Fiabilisation de la procédure B1 et gestion de la racine
- **Commande canonique fiabilisée :** Passage du classpath Surefire sous forme d'argument unique entre guillemets doubles :
  ```sh
  java -Xmx512m \
    -cp "$(cat app/runtime-web-api/target/i1-classpath.txt)" \
    com.kartaguez.pocoma.V3ReadHttpFixture \
    "$(pwd)" \
    "<EVIDENCE_ROOT>/bruno-b1"
  ```
- **Suppression du fallback dégradé :** La commande non équivalente `dependency:build-classpath` a été supprimée des plans. Si `i1-classpath.txt` manque, B1 est classé `NOT READY` et aucun rejeu Maven automatique n'est toléré.
- **Répertoire de preuves unique :** Fixé à `<EVIDENCE_ROOT>` (`/tmp/pocoma-q5b-evidence-20261105/`) pour l'ensemble des étapes.
- **Artefacts B1 réalignés :** `bruno.json`, `bruno.log`, `requests.jsonl`, `receipt.json` (exclusion de `results.json`).

### 3.3 Publication Git Phase 0
- Modifications documentaires : [`L5_Q5_Final_Qualification_Plan.md`](file:///Users/julien.guezennec/Dev/projects/pocoma/docs/v3/L5_Q5_Final_Qualification_Plan.md), [`L5_Q5A_Plan_Amendment_Report.md`](file:///Users/julien.guezennec/Dev/projects/pocoma/docs/v3/L5_Q5A_Plan_Amendment_Report.md).
- Commit préparatoire : [`ac32a7db`](https://github.com/kartaguez/pocoma/commit/ac32a7db58545bf147b244a2fe528636b4bb464f) (`chore(testing): add B5 consolidator and harden B1 procedure (AUD-Q5A-05, AUD-Q5A-06)`).
- Poussé sur `origin/feat/v3-architecture`. Arbre de travail vérifié strictement propre.
- **SHA retenu pour la qualification :** `ac32a7db58545bf147b244a2fe528636b4bb464f`.

---

## 4. Phase 1 — Préflight de qualification

L'ensemble des conditions préalables a été vérifié et consigné :
- **Système hôte :** macOS (Darwin 24.x, architecture `aarch64`, 10 cœurs CPU, 16 Gio RAM, 25 Gio disque libre).
- **Outillage :**
  - Java : OpenJDK 21.0.0 (Temurin-21+35-LTS) dans `/Users/julien.guezennec/.sdkman/candidates/java/current`.
  - Maven : 3.9.14 (via `./app/mvnw`).
  - Node : v24.15.0.
  - Bruno CLI : 4.3.0 (`scripts/bruno-v3/node_modules/.bin/bru`).
  - Python : 3.14.7.
  - Docker : 29.9.2.
- **Services conteneurisés fonctionnels (`pocoma-functional`) :**
  - PostgreSQL 16.6 démarré avec volume propre neuf (`pocoma-functional-postgres-1`).
  - Keycloak 26.7.0 démarré (`pocoma-functional-keycloak-1`), port `8081` actif, realm `pocoma` importé.
  - Authentification préflight validée : exécution de `scripts/testing/l5-i3-auth.py` confirmant l'acquisition des 3 jetons OIDC réels pour `E1`, `E2`, `E3` sur `http://localhost:8081/realms/pocoma`.
- **Répertoire de preuves isolé :** `/tmp/pocoma-q5b-evidence-20261105/` créé vierge.

---

## 5. Phase 2 — Exécution de la qualification globale Maven CERTIFICATION

### 5.1 Commande exécutée
```sh
export JAVA_HOME="/Users/julien.guezennec/.sdkman/candidates/java/current"
export PATH="$JAVA_HOME/bin:$PATH"
python3 -B scripts/testing/verify.py CERTIFICATION \
  --output "/tmp/pocoma-q5b-evidence-20261105/maven-full"
```

- **Invocation ID :** `61160296705d42239d26995b8fb5c0b6`
- **Début d'exécution :** 2026-11-05 16:23:57 CET
- **Fin d'exécution :** 2026-11-05 16:41:28 CET
- **Durée totale observée :** 1 051,58 s (~17 min 31 s)

### 5.2 Bilan quantitatif Maven
- **Modules du réacteur :** 61 déclarés (1 parent + 60 enfants).
- **Modules exécutés avec succès :** 23 modules (modules 1 à 23).
- **Module en échec :** Module 24 (`pocoma-infra-persistence-primary-jpa`).
- **Modules skippés par Maven suite à l'échec :** 37 modules (modules 25 à 61).
- **Tests exécutés avant arrêt :** 1 532 tests.
- **Résultat global des tests :**
  - Tests réussis : **1 531**
  - Failures : **1**
  - Errors : **0**
  - Skipped : **0**
- **Tests attendus non exécutés :** **812 tests** (sur les 2 344 invocations attendues au réacteur complet).

---

## 6. Analyse approfondie de l'échec (AUD-Q5B-01)

### 6.1 Constat technique observable
Le module `pocoma-infra-persistence-primary-jpa` a échoué sur l'exécution Surefire de la classe :
`com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.IdentityDiscoveryPostgresTest`

- **Méthode défaillante :** `l3_identity_windows` (ligne 98)
- **Rapport Surefire :** `surefire-reports/TEST-com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.IdentityDiscoveryPostgresTest.xml`
- **Trace de l'erreur :**
  ```text
  [ERROR] Tests run: 3, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 2.421 s <<< FAILURE! -- in com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.IdentityDiscoveryPostgresTest
  [ERROR] com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.IdentityDiscoveryPostgresTest.l3_identity_windows -- Time elapsed: 0.573 s <<< FAILURE!
  org.opentest4j.AssertionFailedError: Expected java.lang.IllegalArgumentException to be thrown, but nothing was thrown.
  	at org.junit.jupiter.api.AssertionFailureBuilder.build(AssertionFailureBuilder.java:159)
  	at org.junit.jupiter.api.AssertThrows.assertThrows(AssertThrows.java:74)
  	at org.junit.jupiter.api.AssertThrows.assertThrows(AssertThrows.java:35)
  	at org.junit.jupiter.api.Assertions.assertThrows(Assertions.java:3223)
  	at com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.IdentityDiscoveryPostgresTest.l3_identity_windows(IdentityDiscoveryPostgresTest.java:98)
  ```

### 6.2 Cause racine (Root Cause Analysis)
1. **Origine historique :**
   Lors du lot L3 (commit `f905a3c9`), le composant `JdbcEventDiscoveryReferences` gérait uniquement les familles d'événements existantes (`POT_EVENT`, `COMMAND_COMPLETION_EVENT`, `IDENTITY_EVENT`, `IDENTITY_COMPLETION_EVENT`). Le test `IdentityDiscoveryPostgresTest#l3_identity_windows` comportait l'assertion négative suivante pour vérifier le rejet d'une famille non encore supportée :
   ```java
   assertThrows(
           IllegalArgumentException.class,
           () ->
                   new JdbcEventDiscoveryReferences(
                           source, ConsumableFamily.INVITATION_EVENT, discoverySettings));
   ```
2. **Évolution fonctionnelle L5/I4 :**
   Dans le cadre de l'incrément L5/I4 (commit `d722e9d5`), le protocole d'invitation a été intégralement implémenté, requérant la découverte et la consommation des événements d'invitation. Le support de `ConsumableFamily.INVITATION_EVENT` a donc été légitimement ajouté dans `JdbcEventDiscoveryReferences` (lignes 31–35) :
   ```java
   switch (family) {
       case INVITATION_EVENT -> {
           sequence = "INVITATION";
           table = "v3_invitation_event";
           subjects = Set.of("INVITATION");
       }
   ...
   ```
3. **Mécanisme de l'échec :**
   L'instanciation de `JdbcEventDiscoveryReferences` avec `ConsumableFamily.INVITATION_EVENT` est désormais une opération valide et nominale. Par conséquent, aucune `IllegalArgumentException` n'est levée. L'assertion de test historique issue de L3 est devenue **obsolète et en contradiction avec la spécification I4**.
4. **Masquage en I4 :**
   Lors des qualifications locales bornées de L5/I4, la classe `IdentityDiscoveryPostgresTest` n'a pas été rejouée car elle n'appartenait pas à la tranche de modification immédiate (qui ciblait `V3Invitation*` et `runtime-monolith`). Le réacteur global CERTIFICATION est le premier gate à rejouer l'ensemble de la suite historique, révélant cette dissonance.

### 6.3 Qualification de l'anomalie
- **Défaut d'infrastructure / environnement :** Non.
- **Défaut de script de collecte / outillage :** Non.
- **Régression de code productif :** Non (le code productif se comporte exactement comme spécifié pour les invitations).
- **Défaut d'oracle de test :** **OUI**. Il s'agit d'une assertion de test héritée de L3 qui n'a pas été actualisée lors de l'extension des familles d'événements en I4.

---

## 7. Conséquences sur les compléments et les oracles B5

En application stricte des règles Maven, l'échec d'un module amont interrompt immédiatement le réacteur (`BUILD FAILURE`). Les conséquences en cascade sont :

1. **Gate Architecture (`pocoma-architecture-tests`) :**
   - Positionnée en 43ème position dans le réacteur.
   - **Statut : NON EXÉCUTÉE (0 / 178 oracles).**
2. **Suites portées Bruno B2, B3, B4 (`pocoma-runtime-monolith`) :**
   - Positionné en 34ème position dans le réacteur.
   - **Statut : NON EXÉCUTÉES.**
   - Aucun artefact `bruno-b2`, `bruno-i3` ou `bruno-b4` n'a pu être produit dans `<EVIDENCE_ROOT>/maven-full/`.
3. **Génération du classpath B1 (`pocoma-runtime-web-api`) :**
   - Positionné en 35ème position dans le réacteur.
   - `V3ReadHttpContractTest` n'a pas été exécuté.
   - Le fichier `app/runtime-web-api/target/i1-classpath.txt` n'a pas été généré.
   - En application de la règle AUD-Q5A-06, **le complément autonome B1 est classé formellement NOT READY** et ne peut être exécuté sans son classpath Surefire complet.
4. **Consolidation unifiée B5 :**
   - Les 4 répertoires de preuves étant incomplets ou absents pour ce run, la suite composite B5 ne peut être consolidée au statut PASS.
   - **Statut B5 : INCOMPLETE / INCONCLUSIVE**.

---

## 8. Matrice de couverture des obligations L5 à l'issue de Q5-B

| Exigence / Propriété | Obligation normative | Statut visé Q5-B | Statut observé au run Q5-B | Motif / Diagnostic |
|---|---|---|:---:|---|
| **REQ-READ-01 à 07** | Consultation, soldes, masquage, quota $Q=2$ | PASS CERTIFICATION | **NOT REACHED** | `runtime-web-api` non atteint |
| **REQ-CMD-01 à 05** | 11 commandes Pot, Registration/Unlink, S-03 | PASS CERTIFICATION | **NOT REACHED** | `runtime-monolith` non atteint |
| **REQ-CMD-06** | Privilèges SQL V8 resserrés | PASS CERTIFICATION | **PASS VERIFIED** | Validé dans `infra-persistence-primary-jpa` avant échec |
| **REQ-LINK-01 à 04** | Consentement E2, binding, anti-rejeu | PASS CERTIFICATION | **NOT REACHED** | `runtime-monolith` non atteint |
| **REQ-INV-01 à 09** | Protocole d'invitation, AES-GCM, expiration | PASS CERTIFICATION | **NOT REACHED** | Tests d'invitation monolith non atteints |
| **REQ-DEP-01 à 03** | Désaffiliation volontaire sans perte d'historique | PASS CERTIFICATION | **NOT REACHED** | Tests d'admission monolith non atteints |
| **REQ-ARCH-01** | Architecture globale (178 oracles) | PASS CERTIFICATION | **NOT RUN** | Module `architecture-tests` skippé |
| **REQ-EXT-01** | Keycloak OIDC réel port 8081 | PASS CERTIFICATION | **NOT RUN** | `V3LinkBrunoPostgresTest` non atteint (préflight OK) |
| **REQ-EXT-02** | 70 scénarios Bruno B5 consolidés | B5 COMPOSITE PASS | **INCOMPLETE** | Artefacts B1, B2, B3, B4 non produits |
| **AUD-Q5B-01** | Non-régression découverte Identity | PASS CERTIFICATION | **FAIL** | `IdentityDiscoveryPostgresTest#l3_identity_windows` |

---

## 9. Préservation des preuves et archivage

Tous les artefacts d'exécution de la campagne interrompue ont été préservés sans altération sous `/tmp/pocoma-q5b-evidence-20261105/maven-full/` et archivés dans le dossier documentaire de version :
- Répertoire : [`docs/testing/evidence/L5-Q5B/`](file:///Users/julien.guezennec/Dev/projects/pocoma/docs/testing/evidence/L5-Q5B/)
- Fichiers archivés :
  - `result.json` (SHA-256 : `9eefee0396fd0a4f5cb13ef419cb7d4eb832962eb7dd826f04646700df13d508`)
  - `plan.json` (SHA-256 : `8ee75b8ba1704e6c99c9da6060c5a3597d83dd27ce5b7d60f9ef465c4ecab9f9`)
  - `provenance.json` (SHA-256 : `e358bfa79fe5fc5b3511eb9bc76317bc2d44fc3777d5ea8c919d7d242ef99827`)
  - `timings.json` (SHA-256 : `b99d6350f555c82ff2f61a1532f7823f668270529d892e850b5514f04c6d2fe2`)
  - Rapport Surefire XML `TEST-com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.IdentityDiscoveryPostgresTest.xml` (SHA-256 : `105a3068e547342fb882193b0b561c92ba1ca1b69850e05697669d0d9370732b`)
  - Traces d'exécution Surefire `IdentityDiscoveryPostgresTest.txt` (SHA-256 : `739a8c7929d20c52bbcb391e0a84e27f4d8a1be573a6976b29f03222dc23e660`)
  - Manifeste d'intégrité [`docs/testing/evidence/L5-Q5B/manifest.json`](file:///Users/julien.guezennec/Dev/projects/pocoma/docs/testing/evidence/L5-Q5B/manifest.json)
  - Descriptif [`docs/testing/evidence/L5-Q5B/README.md`](file:///Users/julien.guezennec/Dev/projects/pocoma/docs/testing/evidence/L5-Q5B/README.md)

---

## 10. Proposition de remédiation minimale pour décision humaine

Conformément à la règle de Phase 4, **aucun code de test n'a été modifié unilatéralement**. La correction minimale requiert un arbitrage humain explicite :

### 10.1 Diagnostic de la correction
Dans `app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/identity/IdentityDiscoveryPostgresTest.java`, lignes 98–102 :
Remplacer le test de rejet sur `INVITATION_EVENT` (qui est désormais légitimement supporté) par une assertion vérifiant le comportement attendu actuel ou par le rejet d'une famille réellement inconnue / non supportée par cette couche (ou suppression de l'assertion négative devenue caduque).

### 10.2 Périmètre de requalification
1. Validation locale : `./mvnw -pl infra-persistence-primary-jpa test -Dtest=IdentityDiscoveryPostgresTest`
2. Reprise globale : Réexécution de la campagne Maven CERTIFICATION unique (`verify.py CERTIFICATION`).
3. Enchaînement : B1 autonome + consolidation B5.

---

## 11. Statut terminal officiel

```
================================================================================
Q5-B — QUALIFICATION FAILED — DIAGNOSIS RECORDED
================================================================================
Cause : Échec d'assertion de test historique L3 dans IdentityDiscoveryPostgresTest
        (INVITATION_EVENT désormais supporté depuis L5/I4)
Conséquence : Maven interrompu au module 24/61 ; 812 tests non exécutés
              Gate Architecture, tests porteurs Bruno B2/B3/B4 et B1 non atteints
Preuves brutes : Archivées sous docs/testing/evidence/L5-Q5B/
================================================================================
L5 — OPEN — NO CLOSURE BEFORE Q5-C AND HUMAN DECISION Q5-D
================================================================================
```
