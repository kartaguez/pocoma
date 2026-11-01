# L3 — Clôture Documentaire Officielle — Identity WRITE Engine

**1er novembre 2026 — L3 : ACCEPTED WITH EXPLICIT RESERVATIONS.**  
Dépôt `kartaguez/pocoma`, branche `feat/v3-architecture`.  
SHA de référence I4 : `145f42731a4e35488e9aaa56a7de3c3f453a9231`.  
SHA de contre-audit L2/I5 : `32504fca0f8d5ba6f236cf3111145d346ddc7930`.  
SHA de rectification documentaire L2/I5 : `028930e071c35fc12d1b8fc94c03b1238e8ec434`.  
SHA de code testé : `028930e071c35fc12d1b8fc94c03b1238e8ec434`.

---

## 1. Contexte, Mandat et Dossier Examiné

Le présent document constitue l'enregistrement unique de clôture du **Lot L3 (Identity WRITE Engine)** du programme Pocoma V3, conformément au plan de qualification [L3_I5_Qualification_and_Closure_Plan.md](L3_I5_Qualification_and_Closure_Plan.md), aux décisions d'acceptation d'étape L3/I1 à I4, et aux directives normatives de [AGENTS.md](../../AGENTS.md) et [Reactor_Verification_Policy.md](../testing/Reactor_Verification_Policy.md).

### Dossier documentaire et technique examiné

1. **Rapports d'étapes Identity antérieures :**
   - [L3_Identity_Inventory_and_Implementation_Plan.md](L3_Identity_Inventory_and_Implementation_Plan.md) (I1, modèle de domaine pur) ;
   - [L3_I2_Execution_Report.md](L3_I2_Execution_Report.md) (I2, autorité PostgreSQL et ancre v0) ;
   - [L3_I3_Execution_Report.md](L3_I3_Execution_Report.md) (I3, moteur d'exécution et participant Request) ;
   - [L3_I4_Execution_Report.md](L3_I4_Execution_Report.md) (I4, intégration Worker, Monolithe et injection A1) ;
   - [L3_I4_Proof_Manifest.json](L3_I4_Proof_Manifest.json) et l'archive de 64 reçus physiques [Variant_Receipts.json](../testing/evidence/L3-I4/Variant_Receipts.json).
2. **Rapports de remédiation et de contre-audit L2/I5 :**
   - [L2_I5_Counter_Audit_and_Definitive_Closure_Report.md](L2_I5_Counter_Audit_and_Definitive_Closure_Report.md) (rectifié au commit `028930e0`) ;
   - Archives de traces physiques PostgreSQL sous contention (`isConflict` sous `SKIP LOCKED`).
3. **Livrables d'exploitation et de preuve L3/I5 :**
   - [L3_Identity_Execution_Report.md](L3_Identity_Execution_Report.md) (rapport d'exécution complet) ;
   - [L3_Identity_Runtime_Runbook.md](L3_Identity_Runtime_Runbook.md) (runbook opérationnel DEV / test) ;
   - [L3_Identity_Proof_Manifest.json](L3_Identity_Proof_Manifest.json) (registre machine-readable exhaustif).

### Démonstration d'équivalence byte-for-byte

Le code de production sous `app/*/src/main/**`, les scripts de migration `scripts/v3/*.sql` et les utilitaires d'orchestration `worker-dev.py` sont strictement identiques entre le jalon I4 (`145f4273`), le contre-audit L2/I5 (`32504fca`) et la tête testée (`028930e0`) :
```bash
git diff 145f42731a4e35488e9aaa56a7de3c3f453a9231..028930e071c35fc12d1b8fc94c03b1238e8ec434 -- app/*/src/main scripts/v3
# Sortie : 0 files changed, 0 insertions, 0 deletions
```
Aucun code de production n'a été altéré. L'ensemble des 64 reçus de variantes I4 et des traces physiques antérieures conservent leur validité intégrale sur le commit testé.

---

## 2. Constats d'Audit et Réponses aux Findings

| Finding / Point d'Audit | Constat Établi | Réponse et Traitement Appliqué | Statut |
| :--- | :--- | :--- | :--- |
| **F-01 : Faux FAIL potentiel r05 sous SKIP LOCKED** | L'oracle historique excluait les transactions Discovery terminées, pouvant déclarer à tort un FAIL si Discovery détenait le verrou physique de ligne. | Prédicat d'oracle corrigé dans `V3WorkerResourcesPostgresTest` pour identifier la contention réelle sous `SKIP LOCKED` sans relâcher la détection des vraies famines. Deux contre-exemples physiques ajoutés. | **RÉSOLU & CLOS** (Commit `32504fca`) |
| **F-02 : Incohérence registre quarantaine r05/r12** | Le registre `scripts/testing/verification-campaigns.json` conservait les méthodes en quarantaine alors qu'elles étaient annoncées qualifiées. | Sortie de quarantaine formalisée dans le registre ; tests r05 et r12 intégrés dans la campagne active et validés PASS. | **RÉSOLU & CLOS** (Commit `32504fca`) |
| **F-03 : Provenance de la gate COMMAND (1 777 tests)** | Le chiffre de 319 tests annoncé précédemment provenait d'une invocation sans `-am` limitée au seul module worker. | Exécution canonique avec `-am` sur le graphe complet : 51 modules, 1 777 tests, 0 échec, 0 erreur, 1 skip historique (`step3b.measure`). Présence nominative de r05 et r12 vérifiée dans le rapport Surefire. | **CONFIRMÉ & CONFORME** |
| **F-04 : Provenance SHA dans le rapport de contre-audit** | Le rapport `L2_I5_Counter_Audit_and_Definitive_Closure_Report.md` citait un SHA intermédiaire `7e01f82b` pré-amend. | Rectification documentaire effectuée au commit `028930e0` distinguant explicitement l'arbre testé, le SHA intermédiaire et le SHA de publication `32504fca`. | **RECTIFIÉ & CLOS** (Commit `028930e0`) |
| **F-05 : Injection A1 et isolation ArchUnit** | L'isolation de `JdbcIdentityPotActorAuthority` doit être garantie sans fuite de verrou vers le domaine Command. | Vérifié par `V3IdentityAuthorityArchitectureTest` (imports restreints stricts) et `IdentityPotAuthorityPostgresTest` (16 tests, probes NOWAIT démontrant un SELECT pur sans lock nouveau). | **CONFORME** |
| **F-06 : Composition autonome Worker et Monolithe** | Le runtime doit fonctionner de manière identique en mode Worker autonome et profil Monolithe unifié. | Vérifié par `V3IdentityRequestCompositionPostgresTest` (10 tests réels exécutés contre PostgreSQL V1-V6 avec profils Spring distincts). | **CONFORME** |

---

## 3. Décision Humaine Officielle d'Acceptation

Conformément à la directive humaine formulée le 1er novembre 2026 et aux clauses d'arbitrage de **ARB-I5-01** :

> **Le Lot L3 — Identity WRITE Engine est accepté et clos avec réserves explicites.**  
>  
> **Statut officiel de programme : ACCEPTED WITH EXPLICIT RESERVATIONS.**  
>  
> **Périmètre accepté :** Les capacités WRITE transactionnelles internes de l'Identity Engine, incluant les transitions pures de domaine, les invariants d'ancre SQL V6, le moteur d'exécution sérialisé sous verrous canoniques, l'isolation des pannes à chaque frontière, la gestion des slots PENDING/REJECTED/QUARANTINED, et l'injection modulaire A1 sur Worker de consommation et Monolithe unifié.  
>  
> **Restrictions formelles :**  
> 1. Cette acceptation **ne vaut ni certification globale SPEC-10**, ni validation générale de bout en bout des parcours utilisateurs ID-01 / ID-02.  
> 2. Cette acceptation **ne certifie pas la configuration cible de bail à 30 secondes** sous forte dérive d'horloge ou saturation système.  
> 3. Cette acceptation **n'autorise aucun déploiement en production**.  
> 4. Elle autorise formellement l'ouverture des travaux du lot suivant : **Lot L4 — READ Projections (`CurrentBinding@V`)**.

---

## 4. Résultats des Campagnes et Gates Canoniques

Toutes les vérifications requises par la politique [Reactor_Verification_Policy.md](../testing/Reactor_Verification_Policy.md) et le plan I5 ont été exécutées avec succès sur l'environnement de référence :

```
Environnement : Mac OS X / Apple Silicon, Java 21 Temurin (21.0.8), Maven 3.9.x, Docker Desktop / PostgreSQL 16
Arbre testé   : 028930e071c35fc12d1b8fc94c03b1238e8ec434
```

| Campagne / Gate | Commande Exécutée | Périmètre | Résultat | Temps |
| :--- | :--- | :--- | :--- | :--- |
| **FAST identity** | `python3 -B scripts/testing/verify.py FAST identity` | 2 modules | **65 tests PASS**, 0 failure, 0 error | 2.20s |
| **CHANGE identity** | `python3 -B scripts/testing/verify.py CHANGE identity` | 5 modules | **149 tests PASS**, 0 failure, 0 error | 473.81s |
| **INTEGRATION identity** | `python3 -B scripts/testing/verify.py INTEGRATION identity` | 5 modules | **187 tests PASS**, 0 failure, 0 error | 571.94s |
| **CHANGE foundation** | `python3 -B scripts/testing/verify.py CHANGE foundation` | 2 modules | **28 tests PASS**, 0 failure, 0 error | 11.45s |
| **Architecture globale** | `./mvnw -pl architecture-tests -am test` | 61 modules | **163 tests PASS**, 0 failure, 0 error (**BUILD SUCCESS**) | 35.24s |
| **Composition PostgreSQL** | `./mvnw -pl runtime-command-consumption-worker -am test -Dtest=V3IdentityRequestCompositionPostgresTest` | 51 modules | **10 tests PASS**, 0 failure, 0 error (**BUILD SUCCESS**) | 85.04s |
| **Gate canonique COMMAND** | `./mvnw --batch-mode --no-transfer-progress -pl runtime-command-consumption-worker -am test` | 51 modules | **1 777 tests PASS**, 0 failure, 0 error, 1 skip (**BUILD SUCCESS**) | 50:57 min |
| **Cadre de vérification** | `python3 -m unittest scripts/testing/test_verification.py scripts/testing/test_verification_cli.py` | CLI & suite | **41 tests PASS**, 0 failure, 0 error | 86.50s |

Le test ignoré dans la gate COMMAND est le test historique `TerminalSequencingPostgresTest#measureStep3bLatencyUnderLoad` (annoté `@Disabled` pour exclusion du profil de build nominal). Aucun échec, timeout ou corruption n'a été relevé.

---

## 5. Statut Exhaustif des Réserves L2 et L3

Le tableau ci-dessous établit la cartographie normative complète des réserves à la clôture de L3/I5 :

| Identifiant | Domaine | Intitulé de la Réserve | Statut à Clôture L3 | Justification et Portée |
| :--- | :--- | :--- | :--- | :--- |
| **R-L2-01** | L2 / Concurrence | Comportement temporel sous charge et dérive | **CLOSED WITH RESERVATIONS** | Le contre-audit Gemini (`32504fca`) a clos les faux FAIL de r05 et r12. La qualification est acquise pour le bail expérimental à 90s (marge 30s). La réduction au bail nominal de 30s reste une réserve ouverte jusqu'aux bancs d'échelle L6. |
| **R-L2-02** | L2 / Résilience | Disponibilité et drainage | **CLOSED WITH RESERVATIONS** | Le drainage gracieux et la protection UNKNOWN → DRAINING sont démontrés. En cas de dérive d'horloge non maîtrisée en environnement instable, la politique conservatrice UNKNOWN → DRAINING demeure obligatoire. |
| **R-L2-03** | L2 / Architecture | Intégration globale G1/G2/G3 | **OPEN** | Les gates G1, G2 et G3 relèvent de la convergence globale de l'architecture Pocoma V3 et sont planifiées pour Wave 3 / Lot L6. |
| **R-L2-04** | L2 / Fonctionnel | Périmètre fonctionnel des workers | **CLOSED WITH RESERVATIONS** | Le fonctionnement est qualifié sur le moteur `worker-consumption-primary-postgres`. Les extensions multi-régions ou moteurs non-PostgreSQL sont hors périmètre. |
| **R-L2-05** | L2 / Déploiement | Déploiement en production | **OPEN** | L'autorisation de déploiement en production est formellement suspendue jusqu'à l'achèvement de L8. |
| **L3-I3-R1** | L3 / Persistance | Composition JPA sur Worker et Monolithe | **CLOSED WITH RESERVATIONS** | L'intégration unifiée du schéma PostgreSQL V1-V6 avec le participant JPA Command Pot est validée sur Worker et Monolithe. La séparation physique des bases de données de commandes et d'identités est différée à une vague ultérieure. |
| **L3-I3-R2** | L3 / Base de données | Confiance PostgreSQL | **CLOSED WITH RESERVATIONS** | La robustesse des verrous de ligne, contraintes d'exclusion et clés composites `(issuer, subject)` est prouvée sous PostgreSQL 16 standard. Non qualifié sur bases managées avec réplication asynchrone non synchrone. |
| **L3-I3-R3** | L3 / Sécurité | Confidentialité des logs | **CLOSED WITH RESERVATIONS** | Le masquage des BindingToken par `toString()` et dans les exceptions de rejet technique est vérifié en tests d'intégration. L'audit de surface externe (en-têtes HTTP, traces d'audit de sécurité) sera finalisé lors du lot L5. |
| **L3-I3-R4** | L3 / Tests L2 | Faux échecs résiduels r05/r12 | **CLOSED** | Définitivement levée par le contre-audit indépendant Gemini (`32504fca`) ; les deux tests sont qualifiés PASS et réintégrés hors quarantaine active. |

---

## 6. Engagements et Dépendances pour le Lot L4 (READ / CurrentBinding)

Le lot L4 prendra en charge la projection de lecture asynchrone de l'Identity Engine. Les engagements stricts définis par L3 sont :

1. **Source de Vérité Immutable :**
   - L4 ne doit jamais écrire dans les tables d'autorité d'identité (`v3_identity_anchor`, `v3_identity_binding`, `v3_identity_eui_history`, `v3_identity_user`).
   - L4 doit consommer exclusivement le flux d'événements produit par L3 dans `v3_outbox` / `v3_event_position`.
2. **Contrat `CurrentBinding@V` :**
   - La projection doit exposer l'état courant de l'EUI avec son numéro de version exact $v$.
   - Toute détection d'incohérence entre la version projetée et l'ancre d'autorité doit être signalée sous forme de lag de projection sans altérer l'état amont.
3. **Isolation et Découplage de Verrouillage :**
   - Aucune requête READ de L4 ne doit poser de verrou de ligne (`FOR UPDATE` ou `FOR SHARE`) sur les tables d'écriture de L3.
   - Les lectures doivent opérer sous niveau d'isolation `READ COMMITTED` standard ou sur réplicat en lecture seule.
4. **Gestion du Token de Binding :**
   - Le token secret de binding ne doit être exposé en lecture que si le contrat de projection l'exige explicitement pour l'acteur autorisé, sous respect strict des règles de non-divulgation.

---

## 7. Conditions de Transfert vers le Lot L5 (HTTP / Bruno / Consentement Externe)

Les fonctionnalités suivantes sont explicitement déclarées hors du lot L3 et transférées sous la responsabilité du Lot L5 :

1. **Contrôleurs et Endpoints HTTP :**
   - Exposition REST / JSON des endpoints de demande (`/api/v3/identity/requests`), de liaison et de consultation.
   - Traduction des codes de résultat de terminalisation (`REJECTED_ALREADY_BOUND`, `INVALID_BINDING`, etc.) en statuts HTTP appropriés (409 Conflict, 400 Bad Request, etc.).
2. **Suites de Tests E2E Bruno :**
   - Création et exécution des collections Bruno automatisées validant les parcours de requête complets en situation réelle.
3. **Fournisseurs d'Identité Externes (OAuth2 / OIDC) :**
   - Intégration des flux de consentement tiers et échange de jetons d'authentification externes.
   - L3 a validé la déduction de subject/issuer sous format opaque UTF-8 ; L5 assurera la validation cryptographique des signatures JWT des fournisseurs.
4. **Politiques de Sécurité et Masquage :**
   - Assurer que les réponses d'erreur HTTP ne divulguent aucun fragment de jeton interne ni trace de pile PostgreSQL.

---

## 8. Déclarations Normatives d'Absence

Pour prévenir toute interprétation abusive des résultats de qualification de L3, les déclarations formelles suivantes sont réitérées :

1. **Absence de Certification Globale SPEC-10 :**  
   Les campagnes exécutées dans le cadre de L3 qualifient rigoureusement les composants unitaires et d'intégration du WRITE Engine d'Identité. Elles ne constituent en aucun cas la certification globale du catalogue de scénarios de bout en bout de SPEC-10 (scénarios E01 à E03 complets, ID-01 sous charge distribuée, ID-02).
2. **Absence de Qualification de la Cible 30 Secondes sous Charge :**  
   Les tests de baux temporels ont été validés avec la marge expérimentale conservatrice de 90 secondes (marge 30s). Aucune garantie technique n'est donnée quant au comportement du système avec un bail contractuel de 30 secondes en présence de contention sévère ou de latences disques excessives.
3. **Absence d'Autorisation de Déploiement en Production (NO PRODUCTION GO) :**  
   Le système Pocoma V3 en l'état de la branche `feat/v3-architecture` demeure en phase de développement actif. Aucun composant ne doit être mis en service en environnement de production réel sans la qualification complète des lots L4 à L8 et la tenue de la gate finale de release.
4. **Obligation de la Politique Conservatrice UNKNOWN → DRAINING :**  
   En exploitation, tout worker constatant un statut de bail ou d'ACK indéterminé (`UNKNOWN`) doit basculer sans délai en mode de drainage (`DRAINING`) conformément aux règles établies par L2/I5.

---

## 9. Synthèse des Livrables et Traçabilité Git

L'ensemble des pièces constitutives du dossier de qualification et de clôture L3/I5 est consigné sur la branche `feat/v3-architecture` :

- [L3_Identity_Execution_Report.md](L3_Identity_Execution_Report.md) : Rapport d'exécution détaillé de la campagne de qualification I5.
- [L3_Identity_Runtime_Runbook.md](L3_Identity_Runtime_Runbook.md) : Runbook opérationnel pour le démarrage, l'injection et le drainage du runtime Identity.
- [L3_Identity_Proof_Manifest.json](L3_Identity_Proof_Manifest.json) : Registre machine-readable exhaustif des 27 obligations, 17 variantes, campagnes et reçus de preuves.
- [L3_Identity_Closure.md](L3_Identity_Closure.md) : Le présent acte officiel de clôture documentaire et d'acceptation avec réserves.
- [L2_I5_Counter_Audit_and_Definitive_Closure_Report.md](L2_I5_Counter_Audit_and_Definitive_Closure_Report.md) : Rapport rectifié de contre-audit et de clôture définitive des tests temporels L2.
- [V3_Implementation_Master_Plan.md](V3_Implementation_Master_Plan.md) : Plan directeur actualisé actant la clôture de L3 et la transition vers L4.
- [V3_Canon_and_Roadmap.md](V3_Canon_and_Roadmap.md) : Canon et feuille de route actualisés.

Le lot L3 est déclaré **DOCUMENTARY CLOSED / ACCEPTED WITH EXPLICIT RESERVATIONS**.  
Le chantier suivant autorisé est le **Lot L4 — READ Projections (`CurrentBinding@V`)**.
