# Pocoma V3 — L6/I2 — Rapport des Correctifs Finaux de Sûreté et de Requalification Ciblée

> [!NOTE] Clôture formelle
> Suite à la validation de ce rapport et à la revue humaine finale, l'incrément L6/I2 a été formellement et définitivement clos sous mandat `GO_L6_I2_FORMAL_CLOSURE`. Voir l'[Acte d'acceptation et de clôture formelle L6/I2](L6_I2_Acceptance_and_Closure_Decision.md) (**L6/I2 — CLOSED — LOCAL TARGETED PASS WITH EXPLICIT RESERVATIONS**).

**Date :** 6 novembre 2026  
**Dépôt :** `kartaguez/pocoma`  
**Branche :** `feat/v3-architecture`  
**SHA initial de référence :** `71597c14b7bf499ebf7b95a02182db1a9f1d7310`  
**Mode :** `FINAL TARGETED SAFETY CORRECTION — SINGLE PASS — STRICTLY BOUNDED VERIFICATION`  
**Autorisation humaine :** `GO_L6_I2_FINAL_SAFETY_CORRECTIONS`  
**Statut d'aboutissement :** `L6_I2_FINAL_SAFETY_CORRECTED_TARGETED_PASS — READY FOR HUMAN REVIEW`

---

## 1. Contexte, Mandat et Objectifs

À la suite de la publication du rapport post-audit `L6_I2_Post_Audit_Corrections_and_Requalification_Report.md` (commit `71597c14`), une revue ciblée a identifié des anomalies résiduelles relatives aux garanties de sûreté opérationnelle du rebuild READ :
- Risque de faux READY après reprise depuis l'état CONVERGED (AUD-L6-I2-13) ;
- Risque de concurrence entre instances de reprise `--resume` (AUD-L6-I2-14) ;
- Absence de procédure de récupération destructive explicite après crash DDL (AUD-L6-I2-15) ;
- Dérivation directe non prouvée de l'état READY par `--reset-failed` (AUD-L6-I2-16) ;
- Démonstration formelle de la cohérence de l'oracle entre Java et Python (AUD-L6-I2-17) ;
- Bornage de l'outillage d'exploitation local et report d'industrialisation à L8 (AUD-L6-I2-18) ;
- Réconciliation documentaire de la traçabilité des constats (AUD-L6-I2-19).

Le mandat `GO_L6_I2_FINAL_SAFETY_CORRECTIONS` a ordonné une correction unique, sans refactorisation globale ni modification des moteurs existants (`DiscoveryEngine`, `ConsumptionEngine`), et sans exécuter de full reactor non requis.

---

## 2. Matrice Complète de Traçabilité des Constats (AUD-13 à AUD-19)

| Référence | Sévérité | État avant correction | Décision | Correctif apporté | Preuve discriminante |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **AUD-L6-I2-13** | **BLOCKING** | La branche `--resume` pouvait restaurer READY immédiatement si `phase == 'CONVERGED'`, sans revalidation actuelle de l'oracle. | CONFIRMED & RESOLVED | Factorisation stricte dans `validate_and_restore_ready()` : réévaluation obligatoire de l'oracle $\mathcal{P}_{\text{READY}}(B)$ quel que soit le chemin de transition vers READY. | Gate C étape 7 : `--resume` depuis CONVERGED avec cible $B$ divergente rejeté avec code 1 ; HTTP reste en 503 ; passage à READY uniquement après oracle valide. |
| **AUD-L6-I2-14** | **BLOCKING** | Plusieurs processus `--resume` ou rebuild concurrents pouvaient agir simultanément sur le même `operation_id`. | CONFIRMED & RESOLVED | Mise en place de `PostgresRebuildLock` : verrou de session PostgreSQL advisory lock (`3847291054321`) maintenu pendant toute l'exécution et libéré automatiquement sur crash/fermeture. | Gate B `rcv01L` (rejet de session concurrente sur PG et libération sur crash) + Gate C étape 3b (rejet immédiat de toute commande concurrente). |
| **AUD-L6-I2-15** | **BLOCKING** | En cas de crash pendant les DDL/migrations, `--resume` refusait de poursuivre sans offrir de voie de récupération opérationnelle sûre. | CONFIRMED & RESOLVED | Séparation formelle : `--resume` strictement non destructif (refuse si tables manquantes) et `--recover-destructive --confirm-destructive=RESET_READ_DATA` pour reconstruire à partir d'un schéma corrompu/incomplet. | Gate B `rcv01N` (`tryAcquireRecovery` depuis FAILED) + Gate C étape 9a (`--resume` refusé sur schéma partiel) et 9c (`--recover-destructive` réussi). |
| **AUD-L6-I2-16** | **MAJOR (SAFETY)** | `--reset-failed` remettait l'autorité à `READY/IDLE` sans démontrer que READ était réellement reconstruite et cohérente. | CONFIRMED & RESOLVED | `--reset-failed` vérifie obligatoirement l'oracle $\mathcal{P}_{\text{READY}}(B)$ et l'intégrité relationnelle. Si le schéma est incomplet ou l'oracle KO, la transition est formellement rejetée et l'autorité reste en FAILED. | Gate C étape 4a (`--reset-failed` rejeté si préfixe B manquant ou schéma incomplet) et 4b (accepté uniquement après oracle vérifié). |
| **AUD-L6-I2-17** | **MAJOR** | Deux implémentations indépendantes de l'oracle de readiness en Java et Python. | CONFIRMED & RESOLVED | Option 2 retenue (Équivalence démontrée) : logique canonique stricte partagée (5 familles WRITE, 28 checkpoints catalogue, slots résolus groupés, 4 contrôles d'intégrité relationnelle) et contre-tests symétriques sur les cas limites. | Gate A (28 tests unitaire Java) + Gate B (`rcv01M` oracle KO sous CONVERGED) + Gate C étapes 4, 7, 8, 9, 10, 11 (Python oracle ensembliste). |
| **AUD-L6-I2-18** | **MAJOR (DOC)** | Paramètres d'exploitation locaux (conteneurs, bases, ports) non paramétrables et absence de bornage formel du périmètre. | CONFIRMED & RESOLVED | Configuration externalisée via variables d'environnement (`POSTGRES_CONTAINER`, `READ_DB`, etc.), sanitisation des logs et documentation explicite du bornage local (industrialisation reportée à L8). | Inspection du code de `v3-rebuild-read.py` et documentation du bornage opérationnel. |
| **AUD-L6-I2-19** | **MINOR** | Écarts de numérotation et classification entre audits successifs. | CONFIRMED & RESOLVED | Conservation intégrale de la numérotation originale AUD-L6-I2-01 à 12 et ajout séquentiel des identifiants AUD-L6-I2-13 à 19 sans réattribution. | Matrice de traçabilité unifiée intégrée au présent rapport. |

---

## 3. Traçabilité des Constats Historiques (AUD-01 à AUD-12)

Les constats AUD-L6-I2-01 à AUD-L6-I2-12 traités dans le commit `71597c14` restent pleinement valides et leurs garanties sont conservées :
- **AUD-01 (CAS Exclusivité) :** Invariant conservé (`WHERE id = 1 AND state = 'READY' AND phase = 'IDLE' RETURNING id`).
- **AUD-02 (Crash Recovery) :** Invariant conservé (persistance post-opération, pas de drop aveugle sur reprise).
- **AUD-03 (Oracle Conservateur) :** Invariant conservé (rejet de target prefix incomplet, 28 checkpoints catalogue, 0 résiduel).
- **AUD-04 (Isolation WRITE) :** Invariant conservé (rôle `pocoma_read` en SELECT seul, écriture rejetée avec erreur 42501).
- **AUD-05 (Terminaison Gracieuse) :** Invariant conservé (attente gracieuse jusqu'à `pg_stat_activity = 0` avant tout kill).
- **AUD-06 (Topologie Conteneur) :** Invariant conservé (documentation de la cohabitation monolithique locale vs distribuée).
- **AUD-07 (Snapshot Multi-tables WRITE) :** Invariant conservé (empreinte MD5 multi-tables sur flux d'événements).
- **AUD-08 (Tests de Fumée Applicatifs) :** Invariant conservé (Actuator 200 et rejet du code 000 et 503).
- **AUD-09 à 12 :** Invariants conservés (paramétrage SQL, métriques de durée, préconditions physiques, drapeau reset explicite).

---

## 4. Détail des Modifications Apportées

### 4.1 Script d'Orchestration Opérationnel (`scripts/operations/v3-rebuild-read.py`)
1. **Verrouillage de session PostgreSQL (`PostgresRebuildLock`) :**
   - Implémentation d'un context manager Python qui ouvre une session `psql` persistante et acquiert `pg_try_advisory_lock(3847291054321)`.
   - Si une autre session détient le verrou, l'exécution est immédiatement refusée (`RuntimeError`).
   - En cas d'interruption anormale ou de crash du script, PostgreSQL ferme automatiquement la session et libère le verrou sans laisser de verrou orphelin.
2. **Factorisation de la validation terminale READY (`validate_and_restore_ready`) :**
   - Unification du protocole final : réévaluation de l'oracle de readiness $\mathcal{P}_{\text{READY}}(B)$, vérification de la présence des 14 tables, présence du vecteur $B$ et transition conditionnelle.
   - Utilisé par : fresh rebuild, reprise après CONVERGED, reprise après REPLAYING, reconstruction destructive et `--reset-failed`.
3. **Sécurisation de `--resume` depuis CONVERGED (AUD-13) :**
   - Élimination de la transition aveugle vers READY. L'oracle doit être vérifié sur les données physiques réelles.
4. **Commande de récupération destructive `--recover-destructive` (AUD-15) :**
   - Permet de reconstruire le schéma READ à partir d'un état interrompu ou échoué (`MAINTENANCE`, `REBUILDING`, `FAILED`).
   - Exige obligatoirement `--confirm-destructive=RESET_READ_DATA`.
   - Refuse toute exécution depuis un état `READY` sain.
5. **Protection stricte de `--reset-failed` (AUD-16) :**
   - L'acquittement d'échec ne peut plus contourner l'oracle. Si l'oracle n'est pas satisfait, l'autorité reste en état `FAILED` et indique à l'opérateur d'utiliser `--recover-destructive`.
6. **Bornage local et variables d'environnement (AUD-18) :**
   - Utilisation systématique de `os.environ.get(...)` avec valeurs de repli pour la topologie locale.

### 4.2 Autorité JDBC et Barrière Java
1. **`ReadMaintenanceAuthority.java` & `JdbcReadMaintenanceAuthority.java` :**
   - Ajout de la méthode `boolean tryAcquireRecovery(String operatorId, UUID operationId)`.
   - Conditionne la reprise destructive à l'état `WHERE id = 1 AND state IN ('MAINTENANCE', 'REBUILDING', 'FAILED')`.
2. **`ReadMaintenanceBarrierTest.java` :**
   - Ajout des tests unitaires `jdbcAuthority_tryAcquireRecovery_acceptsInterruptedOrFailed` et `jdbcAuthority_tryAcquireRecovery_rejectsReadyState`.

### 4.3 Tests d'Intégration PostgreSQL (`V3ReadReconstructionPostgresTest.java`)
- `rcv01L_sessionAdvisoryLockExclusion` : exclusion stricte entre sessions concurrentes via advisory lock et libération automatique sur déconnexion/crash.
- `rcv01M_resumeFromConvergedRequiresValidOracle` : preuve que l'état CONVERGED avec checkpoints incomplets refuse l'évaluation READY.
- `rcv01N_destructiveRecoveryFromFailed` : preuve que `tryAcquireRecovery` accepte la reprise depuis `FAILED` alors que `tryAcquireMaintenance` la rejette.

### 4.4 Suite de Vérification Opérationnelle (`scripts/operations/verify-l6-i2-rebuild.sh`)
- Étape 3b : Validation de l'exclusion mutuelle par session advisory lock en temps réel.
- Étape 4a/4b : Validation du refus de `--reset-failed` sur données incomplètes et succès sur données valides.
- Étape 7 : Validation du refus de reprise depuis CONVERGED avec oracle KO (HTTP 503 maintenu).
- Étape 9a/9b/9c : Validation du refus de `--resume` non destructif sur schéma cassé, refus de `--recover-destructive` sans confirmation, et succès complet avec confirmation.

---

## 5. Déclaration Normative des Tranches de Vérification (AGENTS.md)

Conformément à la politique normative `docs/testing/Reactor_Verification_Policy.md` et à `AGENTS.md` :

* **Tranches de vérification déclarées :**
  1. `composition-runtime-spring` (unitaires barrière HTTP, autorité JDBC, oracle).
  2. `runtime-task-consumption-worker` (intégration réelle PostgreSQL multi-bases).
  3. Opérationnelle scriptée (`scripts/operations/verify-l6-i2-rebuild.sh`).
* **Commandes canoniques exécutées :**
  ```bash
  # Gate A : Tests unitaires ciblés
  cd app && JAVA_HOME=/Users/julien.guezennec/.sdkman/candidates/java/current ./mvnw -pl composition-runtime-spring test -Dtest=ReadMaintenanceBarrierTest,ReadinessOracleTest

  # Gate B : Scénarios d'intégration PostgreSQL ciblés
  cd app && JAVA_HOME=/Users/julien.guezennec/.sdkman/candidates/java/current ./mvnw test -pl runtime-task-consumption-worker -am -Dtest=V3ReadReconstructionPostgresTest -DfailIfNoSpecifiedTests=false -Dsurefire.failIfNoSpecifiedTests=false

  # Gate C : Suite d'exploitation locale
  bash scripts/operations/verify-l6-i2-rebuild.sh
  ```
* **Franchissement de tranche inattendu :** **Aucun**.
* **Global architecture gate requise :** **Non requise** (aucun franchissement de frontière d'architecture).
* **Full reactor :** **NON EXÉCUTÉ** (le full reactor est une preuve d'intégration globale, non la preuve locale d'un correctif de sûreté).

---

## 6. Résultats de la Requalification Ciblée

| Porte de qualification | Périmètre testé | Résultat | Durée constatée | Plafond imparti | Statut |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Gate A** (Unitaire) | Barrière HTTP, CAS, Recovery acquisition, Oracle conservateur | **28/28 tests PASS** (0 fail, 0 skip) | **1,90 s** | $\le 60\text{ s}$ | **CONFORME** |
| **Gate B** (Intégration PostgreSQL) | Concurrence CAS, Session Advisory Lock, Crash recovery, DDL recovery, Oracle CONVERGED, Droits WRITE | **12/12 scénarios PASS** (0 fail, 0 skip) | **27,84 s** | $\le 240\text{ s}$ | **CONFORME** |
| **Gate C** (Opérationnelle CLI) | Concurrence réelle, Exclusion advisory lock, Rejet faux READY, Sécurité reset-failed, Reprise DDL destructive, Smoke HTTP | **12/12 étapes PASS** | **63,00 s** | $\le 180\text{ s}$ | **CONFORME** |
| **Budget Global** | Cumul des trois portes normatives | **Totalité des vérifications PASS** | **92,74 s** | $\le 480\text{ s}$ (max 540s) | **CONFORME** |

---

## 7. Justification Technique de l'Équivalence de l'Oracle (Option 2 — AUD-17)

L'option 2 (Équivalence démontrée) a été retenue en priorité par rapport à l'Option 1 (Oracle unique via service) pour les raisons suivantes :
1. **Absence d'API administrative superflue :** La création d'une API REST administrative exposant l'oracle aurait violé le principe de simplicité et introduit une surface d'attaque supplémentaire sur le runtime.
2. **Latence d'orchestration nulle :** L'évaluation de l'oracle en Python interroge directement PostgreSQL en SQL groupé sans démarrer de JVM ad-hoc ni solliciter le runtime monolithique (qui peut être éteint pendant les phases de DDL).
3. **Identité stricte des invariants mathématiques :**
   - Vecteur $B$ : exigence stricte des 5 familles WRITE (`POT`, `IDENTITY`, `COMMAND_COMPLETION`, `IDENTITY_COMPLETION`, `INVITATION`).
   - Checkpoints : vérification stricte de 28 checkpoints $(consumption\_id, partition\_id)$ avec $pos \ge target\_pos$.
   - Slots : interrogation groupée `WHERE event_id IN (...)` ; tout slot non résolu en WRITE ($pos < 0$) ou $pos \le target\_pos$ ou mis en quarantaine bloque immédiatement READY.
   - Intégrité relationnelle : exécution stricte des 4 requêtes d'intégrité (dangling versions, missing balance, visibility, binding).
   - Fail-closed : tout comportement imprévu ou information manquante produit `ready = false`.
4. **Preuves symétriques :** Les cas limites (cible incomplète, position manquante, slot résiduel, slot en quarantaine, checkpoint manquant) ont été testés et validés symétriquement en Java (`ReadinessOracleTest`) et en Python (`verify-l6-i2-rebuild.sh`).

---

## 8. État des Réserves et Limites Connues

1. **Bornage local de l'outillage (AUD-L6-I2-18) :**
   - Le script `v3-rebuild-read.py` est outillé pour la topologie locale mono-noeud (Docker Compose). L'intégration avec des orchestrateurs distribués (Kubernetes Jobs, HashiCorp Vault) est formellement reportée au lot L8 (Exploitation industrielle).
2. **Conteneur monolithique de développement :**
   - En environnement local `docker-compose.functional.yml`, `runtime-monolith` combine admission WRITE et workers READ. L'arrêt temporaire du conteneur pendant les DDL READ interrompt localement l'admission HTTP, alors qu'en architecture distribuée (`docker-compose.distributed.yml`), les rôles sont découplés.

---

## 9. Verdict Final de l'Incrément

```
================================================================================
POCOMA V3 — LOT L6 / INCRÉMENT I2 : DÉCISION DE SÛRETÉ FINALE
Statut : L6/I2 — FINAL SAFETY CORRECTED TARGETED PASS — READY FOR HUMAN REVIEW
Dépôt : kartaguez/pocoma
Branche : feat/v3-architecture
Décision de mandat : GO_L6_I2_FINAL_SAFETY_CORRECTIONS

AUD-L6-I2-13 (Faux READY depuis CONVERGED)   : RESOLVED (Factorisé via validate_and_restore_ready)
AUD-L6-I2-14 (Exclusivité des reprises)      : RESOLVED (PostgreSQL Session Advisory Lock 3847291054321)
AUD-L6-I2-15 (Récupération DDL incomplète)   : RESOLVED (--recover-destructive avec token explicite)
AUD-L6-I2-16 (Sécurisation de --reset-failed): RESOLVED (Validation oracle obligatoire avant READY)
AUD-L6-I2-17 (Cohérence oracle Java/Python)  : RESOLVED (Option 2 démontrée et prouvée sur cas limites)
AUD-L6-I2-18 (Bornage outillage local)       : RESOLVED (Variables env, sanitisation, report L8)
AUD-L6-I2-19 (Traçabilité des constats)      : RESOLVED (Matrice unifiée AUD-01 à AUD-19)

Gate A (Unit)            : PASS (28/28 tests en 1,90 s <= 60 s)
Gate B (Integration PG)  : PASS (12/12 scénarios en 27,84 s <= 240 s)
Gate C (Operational CLI) : PASS (12/12 étapes en 63,00 s <= 180 s)
Budget global constaté   : 92,74 s <= 480 s plafond (max 540 s)
Full Reactor             : NON EXÉCUTÉ (Politique ciblée AGENTS.md respectée)

VERDICT : L6_I2_FINAL_SAFETY_CORRECTED_TARGETED_PASS — READY FOR HUMAN REVIEW
================================================================================
```

---

## 10. Rappels de Gouvernance

> [!NOTE] Clôture formelle prononcée
> L'arbitrage humain final a été rendu sous la décision `GO_L6_I2_FORMAL_CLOSURE`. L'incrément L6/I2 est officiellement clos avec réserves explicites dans l'[Acte d'acceptation et de clôture formelle L6/I2](L6_I2_Acceptance_and_Closure_Decision.md) (**L6/I2 — CLOSED — LOCAL TARGETED PASS WITH EXPLICIT RESERVATIONS**).

> [!WARNING]
> **Aucune certification globale SPEC-10 n'est déclarée.** Le périmètre validé est strictement borné à l'exploitation et la reconstruction du domaine READ pour l'incrément L6/I2.
