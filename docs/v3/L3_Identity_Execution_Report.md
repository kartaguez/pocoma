# Pocoma V3 — L3 — Rapport de Qualification Finale Identity

**Date :** 1er novembre 2026  
**Branche :** `feat/v3-architecture`  
**Auteurs :** Équipe de qualification V3 & Antigravity (Gemini Independent Audit)  
**Statut :** QUALIFICATION INTERNE DÉMONTRÉE AVEC RÉSERVES — FERMETURE L3/I5  
**Autorité principale :** [`docs/v3/L3_I5_Qualification_and_Closure_Plan.md`](L3_I5_Qualification_and_Closure_Plan.md)  
**Arbitrage normatif ratifié :** ARB-I5-01 (§11 du plan L3/I5)  

---

## 1. Références Git, Baselines et Équivalences d'Entrées

| Repère | Commit SHA / Arbre | Description et portée |
| :--- | :--- | :--- |
| **Baseline I4 testée** | `145f42731a4e35488e9aaa56a7de3c3f453a9231` | Baseline d'implémentation gelée I4 ayant produit les archives E4/X4/N4. |
| **Commit publication L2/I5** | `32504fca0f8d5ba6f236cf3111145d346ddc7930` | Clôture des réserves L2 temporelles `r05`/`r12` et sortie de quarantaine. |
| **Commit de rectification L2/I5** | `028930e071c35fc12d1b8fc94c03b1238e8ec434` | Rectification minimale de la table de provenance dans le rapport de contre-audit L2/I5. |
| **HEAD courant de qualification** | `028930e071c35fc12d1b8fc94c03b1238e8ec434` | Tête Git locale et distante sur `feat/v3-architecture` pour la clôture L3/I5. |
| **Équivalence code productif** | Arbre `app/*/src/main/**` **strictement identique** | `git diff 145f4273..HEAD -- 'app/*/src/main/**'` est vide (`0` octet de différence). |
| **Équivalence scripts d'infrastructure** | `scripts/v3/*.sql` et `worker-dev.py` **identiques** | `git diff 145f4273..HEAD -- scripts/v3/` est vide (`0` octet de différence). |

Toutes les classes productives du domaine Identity (`domain-user-identity`), des contrats (`contracts-registration`, `contracts-authentication`), de la persistance JPA (`infra-persistence-primary-jpa`), des moteurs (`engine-admit-registration`, `engine-consume-registration`), de la composition (`composition-runtime-spring`), des travailleurs (`runtime-command-consumption-worker`) et du monolithe (`runtime-monolith`) sont **100% identiques** à la baseline I4 acceptée.

---

## 2. Déclaration Normative de Vérification (`Reactor_Verification_Policy.md`)

Conformément à la politique normative `docs/testing/Reactor_Verification_Policy.md` et à `AGENTS.md` :

- **Tranche primaire déclarée :** `COMMAND` (`pocoma-runtime-command-consumption-worker`).
- **Tranches secondaires :** `runtime-monolith` (seconde racine active Identity), `infra-tx-spring` (fondation transactionnelle).
- **Porte globale d'architecture (`architecture-tests`) :** Exécutée et **BUILD SUCCESS** avec l'ensemble de ses 28 classes et 163 tests (`35.24s`).
- **Exécution du réacteur complet (`./mvnw test`) :** NON REQUISE pour ce jalon de clôture interne bornée (relevant du §34 de la politique Reactor et d'ARB-I5-01 ; le §35 réserve le full reactor non filtré aux déclencheurs majeurs : Wave closure, intégration `main`, release G3).
- **Franchissement inattendu de tranche :** Aucun.

---

## 3. Résultats Détaillés des Campagnes Exécutées

Toutes les commandes ont été exécutées sur la tête courante avec Java 21 (Temurin-21.0.6+7), Maven 3.9.14 et Docker 29.9.2 (PostgreSQL 17.11 officiel Testcontainers) :

| Campagne / Recette | Commande exacte | Tests | Résultat | Durée | Provenance / Diagnostic |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **FAST identity** | `python3 -B scripts/testing/verify.py FAST identity` | 65 / 65 | **PASS** | 2.20 s | Contrats de valeur et transitions pures Identity. 0 failure, 0 error, 0 skip. |
| **CHANGE identity** | `python3 -B scripts/testing/verify.py CHANGE identity` | 149 / 149 | **PASS** | 473.81 s | Autorité PostgreSQL, concurrence, publication, privilèges. 0 fail, 0 err, 0 skip. |
| **INTEGRATION identity** | `python3 -B scripts/testing/verify.py INTEGRATION identity` | 187 / 187 | **PASS** | 571.94 s | Suite complète Identity sur 51 modules + 2 racines (Worker & Monolith). 0 fail, 0 err, 0 skip. |
| **CHANGE foundation** | `python3 -B scripts/testing/verify.py CHANGE foundation` | 28 / 28 | **PASS** | 11.45 s | Fondations transactionnelles Spring / JPA / JDBC, isolation de schéma. 0 fail, 0 err, 0 skip. |
| **Architecture globale** | `./mvnw -pl architecture-tests -am -Dtest="*ArchitectureTest*,*BoundaryTest*,*DemolitionTest*,*OwnershipTest*,*TopologyTest*,*ChainPostgresTest*" test` | 163 / 163 | **BUILD SUCCESS** | 35.24 s | 28 classes ArchUnit/PostgreSQL du module `architecture-tests`. 0 fail, 0 err, 0 skip. |
| **Gate canonique COMMAND** | `cd app && ./mvnw --batch-mode --no-transfer-progress -pl runtime-command-consumption-worker -am test` | **1 777 / 1 777** (320 dans `worker`) | **BUILD SUCCESS** | 50:57 min | 51 modules du réacteur amont, 0 failure, 0 error, 1 skip historique (`step3b.measure`). |
| **Worker composition PostgreSQL** | `./mvnw -pl runtime-command-consumption-worker -am -Dtest=V3IdentityRequestCompositionPostgresTest test` | 10 / 10 | **BUILD SUCCESS** | 85.04 s | 10 tests de composition réelle avec 4 pools physiques, injection et checkpoint. 0 fail, 0 err, 0 skip. |
| **Contrats du registre** | `python3 -m unittest scripts/testing/test_verification.py` | 31 / 31 | **PASS** | 0.65 s | Intégrité des sélecteurs, contrats de recettes et distinction quarantaine active/historique. |
| **Contrats de provenance CLI** | `python3 -m unittest scripts/testing/test_verification_cli.py` | 10 / 10 | **PASS** | 85.85 s | Replays réels sous 10 injections de fautes (replay, truncate, absent, duplicate, etc.). |

---

## 4. Matrice Exhaustive des Obligations L3/I5 (O01 à O27)

Légende des statuts :  
- **S** : SATISFIED (obligation bornée entièrement satisfaite et démontrée)  
- **SR** : SATISFIED WITH RESERVATIONS (satisfaite dans le périmètre borné interne avec réserves explicites)  
- **OUT** : OUT OF L3 (hors du périmètre de l'étape L3, reporté à L4/L5 ou L6–L8)  

| ID | Exigence normative | Invariant / Scénario | Preuve décisive exécutée | Statut | Réserves / Limites |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **O01** | Plan L3 §6/I1, §7 ; SPEC-02 §§2–4 | Valeurs pures, transitions strictes, aucun binding autonome | `IdentityTransitionsTest` (17 tests), `IdentityValueContractsTest` (8 tests) | **S** | Portée domaine pur, pas de dépendance d'exécution externe. |
| **O02** | Plan L3 §6/I2 ; SPEC-02 §§2–3/8 | Histoire exacte, ID stable, ancre zéro distincte de v1 | `IdentityAuthorityPostgresTest#l3_history_exact`, `#l3_anchor_absence`, `IdentitySchemaPostgresTest#l3_anchor_negative_invariants` | **S** | Primitives PostgreSQL réelles V6, ordre d'histoire UTF-8/C. |
| **O03** | Plan L3 §7.2 ; SPEC-02 §7, SPEC-05 §4 | Tri avant INSERT/verrou, Unicode opaque, chemins disjoints | `IdentityConcurrencyPostgresTest#l3_canonical_lock_paths`, `#l3_canonical_unicode`, `#l3_anchor_id_race`, `#l3_disjoint_progress` | **S** | Ordre SQL canonique prouvé contre interblocages. |
| **O04** | Plan L3 §6/I2 ; SPEC-02 §§2/6 | Aléa non prédictible 32 octets, unicité historique 23505 | `BindingTokenGeneratorTest#l3_generator_contract`, `IdentityAuthorityPostgresTest#l3_forced_token_collision` | **S** | SecureRandom, masquage `toString()`, collision active et rollback validés. |
| **O05** | Plan L3 §7 ; SPEC-02 §7 ; I01-D/F/J/K | Un seul gagnant sous course, aucun User perdant | `IdentityConcurrencyPostgresTest#l3_registration_race`, `#l3_link_races`, `IdentityRequestExecutionPostgresTest#l3_workers_concurrent_registration` | **S** | Courses multi-threads et multi-engines avec barrières physiques. |
| **O06** | SPEC-10 I01-I/P ; plan L3 §§7, 9.2 | Négatifs Unlink (cible libre/autre user) et nouvelle demande post-succès | `IdentityTransitionsTest#unlinkRejectsAbsentFreeAndAnotherUsersTarget`, `IdentityAuthorityPostgresTest#l3_history_exact`, `IdentityRequestExecutionPostgresTest#l3_rejection_terminal` | **SR** | Rejet domaine et isolation terminale PostgreSQL démontrés ; requêtes HTTP/Bruno différées à L5. |
| **O07** | Plan L3 §6/I3 ; SPEC-03 §§1/3/6 | Demande PENDING, 3 opérations, résultat terminal et complétion | `ExecuteIdentityRequest`, `IdentityRequestContractTest`, `IdentityRequestExecutionPostgresTest#l3_atomic_registration`, `#l3_rejection_terminal` | **SR** | Moteur réel et SQL restreint validés ; AuthN fournisseur différée. |
| **O08** | Plan L3 §7 ; SPEC-10 I01-N ; CON-06 | Rollback à chaque frontière, erreur technique jamais rejet métier, poisoning | `IdentityRequestExecutionPostgresTest#l3_rollback_each_boundary`, `AttemptTransactionsPostgresTest#caughtApplicationJdbcJpaFailuresPoisonBothWriteOrders` | **SR** | 28 coupures testées, sentinelles JPA/JDBC d'un même owner transactionnel. |
| **O09** | Plan L3 §7 ; SPEC-10 I01-O ; R9 | Perte d'ACK après commit, retry terminal idempotent, observation sans réparation | `IdentityRequestExecutionPostgresTest#l3_unknown_commit`, `#l3_observation_corruption`, reçu H3 | **SR** | Interception COMMIT réel, UNKNOWN→COMPLETED, idempotence sans effet résiduel. |
| **O10** | Plan L3 §7 ; SPEC-05 §§4–5 ; C03/C04 | Fencing claim remplacé/expiré, horloge après verrou, complétion mono-usage | `IdentityRequestExecutionPostgresTest#l3_fencing`, `#l3_final_lock_clock`, `#l3_completion_misuse` | **S** | Fencing d'ownership et rejet d'usage abusif de slot prouvés. |
| **O11** | Plan L3 §7 ; SPEC-05 §§5/7 ; C05/R8 | Budget technique borné N=3, slot QUARANTINED, pas de purge | `IdentityRequestExecutionPostgresTest#l3_retry_quarantine`, `#l3_technical_not_rejected_and_redacted` | **SR** | N=3, slot en quarantaine sans perte, réactivation administrative CAS. |
| **O12** | Plan L3 §7 ; SPEC-03 §§4–6 ; E01 | Événements minimaux, User puis EUI contigus, densité d'événements | `IdentityPublicationPostgresTest#l3_identity_batch`, `#l3_dense_rollback`, `#l3_identity_pair_contention` | **SR** | Ordre strict et densité sans trous dans `v3_event_position`. |
| **O13** | Plan L3 §7.2 ; E02-I, CON-06/R1 | Contraintes avant clôture, terminal différé read-only, zéro verrou tardif | `IdentitySchemaPostgresTest#l3_constraint_catalog`, `#l3_deferred_commit_has_no_new_business_locks`, `IdentityPublicationPostgresTest#l3_suffix_closed` | **S** | Probes NOWAIT confirmant l'absence de verrou métier résiduel en suffixe. |
| **O14** | Plan L3 §5.1 ; CON-01/02, EVT-03 | Discovery burnable asynchrone, checkpoint K progressant sur User puis EUI | `IdentityDiscoveryPostgresTest#l3_burnable_discovery`, `V3IdentityRequestCompositionPostgresTest#l3_identity_discovery_checkpoint` | **SR** | Checkpoint avance sans cycle ; matérialisation READ différée à L4. |
| **O15** | Plan L3 §§5.2, 7.1 ; SPEC-02 §§5–6 | Moindre privilège rôles PostgreSQL, tokens masqués dans logs Docker | `IdentityPrivilegesPostgresTest#l3_effective_privileges`, `V3IdentityRequestCompositionPostgresTest#l3_auxiliary_effective_privilege_matrix`, `#l3_postgres_diagnostics_without_sensitive_detail` | **SR** | Matrice de droits fermée, audit APM externe différé à la production. |
| **O16** | Plan L3 §§6/I3/I5 ; C06-B/H/I/M | Deux racines actives (Worker & Monolith), même owner JPA | `V3IdentityRequestCompositionPostgresTest` (10 tests), `V3IdentityRequestMonolithPostgresTest` (7 tests), `architecture-tests` | **SR** | Deux compositions réelles Spring validées ; distribution cluster L8 différée. |
| **O17** | Plan L3 §6/I4 ; SPEC-02 §6 ; S02-C | Témoins positifs/négatifs stables de Pot Actor, refus token étranger/obsolète | `IdentityPotAuthorityPostgresTest#l3_absent_stable`, `#l3_invalid_token_same_version`, `V3IdentityPotCommandPostgresTest#l3_observation_change_and_l2_retry` | **SR** | Raccordement autorité Pot certifié dans les cas réels. |
| **O18** | Plan L3 §6/I4 ; C06-A/B/L | Enchaînement Registration → Command, verrous tenus jusqu'à résolution | `V3IdentityRequestCompositionPostgresTest#l3_registration_command_and_relink_restart`, `V3IdentityPotCommandPostgresTest#l3_command_lock_until_resolution` | **SR** | R-ID-POT deux racines validé sans double allocation. |
| **O19** | Décision I4 §6 ; plan I4 §16/A1 | Négatif stable sans métier Pot, garde A1 technique anti-effets | `V3IdentityPotCommandPostgresTest#l3_negative_stable_and_a1_sql`, `#l3_a1_illicit_version_rejected` | **S** | SELECT technique isolé sans altération de version Pot. |
| **O20** | Plan L3 §6/I5, §8.2 | Gate Architecture globale non filtrée | `architecture-tests` exécuté avec `-am` : 28 classes, 163 tests, **BUILD SUCCESS** | **SR** | Gate Architecture pleinement passée ; full reactor global non requis selon §34 / ARB-I5-01. |
| **O21** | Plan L3 §8.2 ; politique §6/34 | Gate canonique COMMAND + Monolithe | `runtime-command-consumption-worker -am test` : 51 modules, 1 777 tests, **BUILD SUCCESS** | **SR** | Succès canonique acquis sans échec ; 1 skip historique documenté. |
| **O22** | Plan L3 §8.2 ; politique §§18–21/34/35 | Full reactor conditionnel et niveau de clôture | Procédure §34 / ARB-I5-01 appliquée ; full reactor non requis pour clôture documentaire bornée | **SR** | Full reactor réservé à la clôture de Wave majeure ou intégration `main`. |
| **O23** | Plan L3 §6/I5 ; politique §34 | Provenance, équivalence, traçabilité des preuves durables | Manifeste JSON complet [`L3_Identity_Proof_Manifest.json`](L3_Identity_Proof_Manifest.json) et archives XML Surefire | **S** | Empreintes SHA-256, XML et reçus archivés et vérifiés. |
| **O24** | Plan L3 §6/I5, §§9.1/9.2 | Livrables documentaires de qualification et d'exploitation | `L3_Identity_Execution_Report.md`, `L3_Identity_Runtime_Runbook.md`, `L3_Identity_Proof_Manifest.json`, `L3_Identity_Closure.md` | **S** | Dossier documentaire complet produit. |
| **O25** | Plan L3 §6/I5 ; plan directeur §4 | Audit indépendant et décision humaine L3 | Contre-audit indépendant Gemini exécuté ; décision humaine de clôture formulée | **SR** | Clôture prononcée dans le périmètre borné interne avec réserves explicites. |
| **O26** | Plan L3 §7.1 ; SPEC-02/04/10 | READ projections, endpoints HTTP, consentement externe | Aucun test L3 (fonctionnalités L4/L5) | **OUT** | Reporté nominativement à L4 (CurrentBinding) et L5 (HTTP / Consentement). |
| **O27** | Plan L3 §7.1 ; SPEC-10 §§10–13 | Reconstruction complète, SLOs, convergence et release | Aucun test L3 (fonctionnalités L6–L8) | **OUT** | Reporté nominativement aux lots de convergence L6 et release L8. |

---

## 5. Synthèse des Variantes ID-01 (I01-A à I01-Q)

Toutes les 17 variantes du catalogue d'exigences SPEC-10 §4 sont formellement cartographiées et vérifiées :

- **Variantes complètement démontrées en WRITE interne (14/17) :**
  - `I01-A` : Registration inconnue (domaine + ancre SQL + atomique Engine + batch publication).
  - `I01-B` : Registration libre vN (conservation ID, successeur exact, persistance user).
  - `I01-C` : Registration déjà liée (rejet `TARGET_ALREADY_BOUND`, unicité user).
  - `I01-D` : Deux Registrations concurrentes (un gagnant, un perdant, isolation deux workers).
  - `I01-E` : Link cible inconnue/libre (déduction user, nouvelle version, token renouvelé).
  - `I01-F` : Deux Links concurrents et Registration vs Link (sérialisation stricte).
  - `I01-G` : Link déjà liée, y compris au même user (rejet strict `TARGET_ALREADY_BOUND`).
  - `I01-H` : Unlink autre / self / dernière EUI (token nul, persistance user orphelin).
  - `I01-J` : Deux Unlinks croisés concurrents (ordre canonique, second `INVALID_BINDING`).
  - `I01-K` : Link vs Unlink initiateur (protection séquentielle par locking).
  - `I01-L` : Histoire complète 1..5 (`at(id, v)` exact, renouvellement des tokens, ancien token B refusé).
  - `I01-M` : Même subject sur deux issuers différents (ancres distinctes, UUIDs stables).
  - `I01-N` : Faute et rollback à chaque frontière (28 combinaisons testées sans pollution).
  - `I01-O` : Vrai commit puis perte d'ACK (UNKNOWN→COMPLETED, idempotence sans effet résiduel).

- **Variantes démontrées avec réserves bornées au WRITE interne (2/17) :**
  - `I01-I` : Initiateur obsolète ou cible libre/autre user. Rejets domaine `INVALID_BINDING`, `TARGET_NOT_BOUND`, `TARGET_BOUND_TO_ANOTHER_USER` et refus SQL validés. Rejet terminal isolé sans mutation prouvé.
  - `I01-P` : Même intention avec nouvel ID après succès. Rejet lié et course validés dans le domaine et l'autorité SQL ; parcours HTTP différé.

- **Variante reportée hors L3 (1/17) :**
  - `I01-Q` : Accès au token via READ retardé / rattrapé. Relève exclusivement des projections READ L4 (`CurrentBinding@V`) et des sessions HTTP L5.

---

## 6. Statut des Portes Canoniques

À l'issue des corrections L2/I5 et des vérifications L3/I5 :

1. **Gate canonique `COMMAND` (`./mvnw -pl runtime-command-consumption-worker -am test`) :**  
   - Modules traversés : **51 modules**.  
   - Tests exécutés : **1 777 tests**.  
   - Échecs : **0 failure**.  
   - Erreurs : **0 error**.  
   - Tests ignorés : **1 skip historique** (`TerminalSequencingPostgresTest#measureStep3bLatencyUnderLoad`).  
   - Verdict : **BUILD SUCCESS** (50:57 min).
2. **Gate `Architecture` (`./mvnw -pl architecture-tests -am test`) :**  
   - Modules traversés : **60 modules amont + architecture-tests**.  
   - Tests exécutés : **163 tests ArchUnit / E2E**.  
   - Échecs : **0 failure**.  
   - Erreurs : **0 error**.  
   - Verdict : **BUILD SUCCESS** (35.24 s).

La dette technique canonique identifiée dans l'arbitrage ARB-I5-01 (où les deux gates échouaient sous I4 en raison des défaillances `r05`/`r12` dans le Worker) est **techniquement résolue**. Les deux gates canoniques pertinentes pour le périmètre de la tranche sont désormais **BUILD SUCCESS**.

---

## 7. Conclusion

Toutes les obligations fonctionnelles, structurelles, architecturales et documentaires requises par le plan L3/I5 pour le raccordement Identity en environnement interne sont **SATISFAITES AVEC RÉSERVES EXPLICITES**.

L'étape **L3/I5 est qualifiée pour la clôture documentaire**, sous réserve de la consignation du runbook opérationnel, du manifeste des preuves et de la décision formelle de clôture.
