# Pocoma V3 — L3/I5 — Qualification finale et clôture Identity

**État courant — ratification du 1er novembre 2026 : L3 CLOSED WITH EXPLICIT
RESERVATIONS ; O20/O25 SATISFIED WITH EXPLICIT RESERVATIONS ; O21 SATISFIED WITH
RESERVATIONS.** Voir §19 et l’[acte unique](L3_Identity_Closure.md).
Les §§1–18 sont conservés comme historique de préparation et d’instruction,
y compris les statuts ND/PENDING antérieurs ; aucune prescription de preuve
future ni aucun résultat d’exécution n’est rétroactivement modifié.

**31 octobre 2026 — PLAN FINALISÉ, ARBITRAGE ARB-I5-01 RATIFIÉ, NON EXÉCUTÉ (état historique).**

**Conclusion contradictoire : aucun développement productif indispensable identifié.**
Les créations de tests prévues historiquement pour I5 ont déjà leurs équivalents
livrés. Il reste **trois observations WRITE directes à obtenir sur deux variantes
ID-01 partiellement démontrées** (I01-I et I01-P), le dossier final et l'audit indépendant.
Une seule campagne interne Q-ID, utilisant les outils DEV existants, suffit pour ces
observations ; aucun ajout de test Java n'est proposé. L'audit contradictoire externe
ChatGPT a conclu « APPROVABLE WITH RESERVATIONS — aucune refonte nécessaire ».

**Arbitrage ARB-I5-01 ratifié (§11) :** Le responsable du programme autorise nominativement
la préparation d'une acceptation humaine interne bornée du raccord Identity L3 avec
les gates canoniques I4 (COMMAND + Monolithe et Architecture) demeurant FAIL, sans
substitution par Architecture bornée, sans modification de la politique Reactor ni PASS
canonique. Le jalon est formellement qualifié de clôture humaine documentaire bornée
(relevant du §34 de la politique Reactor et du plan L3 §8.2), et non de niveau de
certification globale V3 (§35). Les deux ajustements R-I5-01 (clarification normative des
gates et consignation d'ARB-I5-01) et R-I5-02 (préflight statique complet outillage et SQL)
sont intégrés. **L3 reste OPEN. I5 est NON EXÉCUTÉ. Q-ID est NOT RUN. Aucun GO d'exécution,
de développement productif ou de mise en production n'est prononcé par ce mandat.**

## 1. Baseline Git vérifiée

Lectures effectuées dans `/Users/julien.guezennec/Dev/projects/pocoma`, dépôt
`kartaguez/pocoma`, branche `feat/v3-architecture` :

| Référence | Constat du présent examen |
|---|---|
| HEAD distant réel, obtenu par `git ls-remote origin refs/heads/feat/v3-architecture` | `55d17c7db18dcc8fa3be077e10f7bdcc23476974` |
| HEAD local et remote-tracking local | Même SHA (`55d17c7d`) ; divergence locale `0/0` |
| Code testé I4 | `145f42731a4e35488e9aaa56a7de3c3f453a9231` |
| Livraison I4 | `126dc10ef6ac3fc304ad098b5d9dc85a24f40f17` |
| Acceptation documentaire I4 | `af5416706c27be52722b480d2f27cfb50570a253` |
| Publication initiale du plan I5 | `55d17c7db18dcc8fa3be077e10f7bdcc23476974` |
| Commits depuis le code testé | Exclusivement documentaires/preuves ; aucun commit applicatif |
| Arbre Git `app/`, testé et courant | `0b6fb8865600e853b859136187af7afb0de9700a` dans tous les cas |
| Arbre Git `scripts/`, testé et courant | `2e1644e0f5d8c5a0a844d2a306719f857ba6c333` dans tous les cas |
| Checkout/index à l'ouverture | Propres, aucun travail préexistant à déplacer |

Le premier accès distant a échoué sur la résolution DNS dans le sandbox ; la
lecture réseau autorisée a ensuite confirmé le SHA ci-dessus. Aucun fetch,
checkout, reset, clean Git, stash, commit ou push n'a été nécessaire.

Les cinq worktrees existants ont été inspectés en lecture seule et étaient
propres ; ils restent en place :

| Worktree | HEAD / état de branche |
|---|---|
| Checkout principal ci-dessus | `af541670…`, `feat/v3-architecture` |
| `/private/tmp/pocoma-verification-audit-d0927d94` | `d0927d9410ebcd65c221e73c5ecd04e5242fe4e9`, détaché |
| `/Users/julien.guezennec/.codex/worktrees/debt-wa6-integration/pocoma` | `2eef3ab9785c524c2fab86f586fc12580c8b1c2e`, détaché |
| `/Users/julien.guezennec/.codex/worktrees/v3-l2-documentation/pocoma` | `f81cbd72590a5cb6bdaa083641f8b8ab59371a55`, détaché |
| `/Users/julien.guezennec/.codex/worktrees/v3-l2-i1-close-i2-plan/pocoma` | `1fd9ce7529e5da439f32f889f92cec39de2c79fa`, détaché |

Recontrôler la tête distante et le diff au démarrage de la future campagne puis
avant toute publication. Un nouveau delta documentaire se compare explicitement ;
un delta exécutable invalide les seules équivalences concernées et impose une
nouvelle déclaration de portée avant poursuite. Ne jamais forcer une tête distante.

## 2. Autorités et règles de lecture

Sources lues directement dans le dépôt :

| Autorité | Clauses utilisées |
|---|---|
| [Canon](V3_Canon_and_Roadmap.md) | §§1–3 : simplicité, invariants, hiérarchie ; §§4–7 : progression et limites ; état courant I4/I5 |
| [Plan directeur](V3_Implementation_Master_Plan.md) | §§3–5 : L3 interne, L4 READ, L5 admission/consentement, L6–L8 qualification/convergence/distribution ; §9 : reprise |
| [Plan L3](L3_Identity_Inventory_and_Implementation_Plan.md) | §§6/I1–I5, 7/7.1/7.2, 8.2, 9.1/9.2 : obligations de sortie, scénarios, gates, frontières |
| [SPEC-02](SPEC-02_Identity_Domain.md) | §§2–8 : états, transitions, histoire, tokens, autorité et concurrence |
| [SPEC-03](SPEC-03_Events_Requests_Consumables.md) | §§1–7, 10 : demandes durables, résultat, provenance, événements minimaux, deux séquences Identity et atomicité |
| [SPEC-05](SPEC-05_Consumption_Discovery.md) | §§1–7, 11–12 et [R1–R10](L2_R1_R10_Normative_Consolidation.md) : réemploi du moteur, unité physique, fencing, suffixe, R9, absence de purge |
| [SPEC-07](SPEC-07_Transverse_Requirements.md) | §§2–5 : catalogue et séparation des exigences, sans seconde matrice de certification |
| [SPEC-10](SPEC-10_Test_Certification.md) | §1 et clarification du 29-10 : réemploi sans dispense implicite ; §4 I01-A…Q/I02 ; §§5–6 EVT/CON ; §§8–9 sécurité/résultats ; §13 certification globale distincte |
| [SPEC-01](SPEC-01_Pot_Domain.md), [SPEC-04](SPEC-04_Read_Projections.md), [SPEC-06](SPEC-06_Supra_Contracts.md) | Droits Pot §§5–6 ; READ §§6–8 ; admission §§1–3 et CurrentBinding/résultats §§7–9 : aucune assimilation WRITE/READ/HTTP |
| [Politique Reactor](../testing/Reactor_Verification_Policy.md) | §§3, 6, 14–22, 24–27, 34–35 : slices, gates, DB, réemploi, quarantaine et attribution |
| Décisions [I1](L3_I1_Arbitration_and_I2_GO.md), [I2](L3_I2_Acceptance_Decision_2026-10-30.md), [I3](L3_I3_Acceptance_Decision.md), [I4](L3_I4_Acceptance_Decision.md) | Acceptations humaines et limites exactes, sans transfert de PASS canonique |
| [Plan I4](L3_I4_Implementation_Plan.md), [rapport I4](L3_I4_Execution_Report.md), [manifeste I4](L3_I4_Proof_Manifest.json), [preuves I4](../testing/evidence/L3-I4/README.md) | Implémentation réelle, A1, méthodes, résultats, SHA et artefacts |
| [Clôture L2](L2_Documentary_Closure.md), [plan L2/I5](L2_I5_Implementation_Plan.md), [rapport L2/I5](L2_I5_Execution_Report.md) | Réserves R-L2-01…05 ; contrat ressources/renewals/arrêt ; distinction sûreté/disponibilité |
| [Contrat v2](L2_I5_Temporal_V2_Addendum.md), [rapport v2](L2_I5_Temporal_V2_Report.md), [contrat final](L2_I5_Temporal_Final_Addendum.md), [rapport final](L2_I5_Temporal_Final_Report.md), [résolution I1](L3_I1_Verification_Resolution.md) | Diagnostics historiques r05/r12 et limites causales, sans nouvelle reproduction |

Les décisions de programme actualisent les statuts historiques « non implémenté »
des SPEC ; elles ne remplacent pas leurs oracles. La présente reconstruction
s'appuie sur les sources, assertions et archives, pas sur des conversations.
Le rapport original d'audit I4 n'est pas dans le dépôt : son avis est uniquement
rapporté par la décision I4 §4. Le ré-audit I3 est lui-même borné ; ses bruts
indépendants non fournis ne sont pas inventés.

## 3. Acquis I1–I4 et noms effectivement livrés

| Étape | Acquis accepté / provenance historique | Limite maintenue |
|---|---|---|
| I1 | Valeurs/contrats/transitions purs ; 35 tests nouveaux PASS à `ddffcb44c92d02d335f41b55122aa6c7d885d0e2` ; [rapport](L3_I1_Execution_Report.md) | Gate composée FAIL ; aucun I1 LOCAL BOUNDED PASS |
| I2 | Autorité PostgreSQL, histoire exacte, ancres zéro, ordre UTF-8/C, contraintes V6, SecureRandom et collision historique ; 27 tests PG +21 structurels à `49ae298d879a41b29d1caf14e7597dffb947713a` ; [rapport](L3_I2_Execution_Report.md) | Harness de primitives, pas encore participant/runtime à ce SHA ; gate FAIL |
| I3 | Participant réel, Registration/Link/Unlink, Discovery, R1/R9, publication, ACL/bootstrap, jar/restart ; code initial `3ae0966baacc81ffc23aac9c1d9c2f29af578fcf`, corrections `c1172bf4a758a9077415d827ca88b359b6360ebb` ; [rapport](L3_I3_Execution_Report.md), [résolution](L3_I3_Findings_Resolution_Report.md) | 37 cas initiaux et 232 ciblés frais de résolution distincts de gates FAIL ; R1–R4 |
| I4 | R-ID-POT réel dans Worker/Monolithe, autorité unique, témoins négatifs/collisions/verrous, A1 ; code `145f42731a4e35488e9aaa56a7de3c3f453a9231` | Configurations internes testées seulement ; gates FAIL ; acceptation humaine avec réserves |

Les preuves finales I4 réexécutent les tests Identity I1–I3 dans le code gelé
I4 ; leur réemploi est préférable à la reconstitution d'une campagne par étape.
Les résultats antérieurs conservent leur attribution propre.

Le §6/I5 du plan L3 proposait `V3IdentityMonolithPostgresTest` et
`V3IdentityArchitectureTest`. Les équivalents existent :

- `V3IdentityRequestMonolithPostgresTest` : `l3_runtime_identity`,
  `l3_registration_command_and_relink_restart`, `l3_command_only_preflight`,
  préflight/confidentialité et refus du holder prélié sous JPA ; **7 PASS I4**.
- `V3IdentityAuthorityArchitectureTest` :
  `l3_identity_authority_has_no_owner_or_runtime`,
  `l3_real_authority_delegates_without_a_second_lock_protocol`,
  `l3_identity_participant_and_factory_keep_l2_ownership`,
  `l3_canonical_acquisition_is_the_only_eui_writer_path` ; **4 PASS** dans les
  **163 PASS du module Architecture borné**.
- Les règles transversales `RegistrationModuleBoundaryTest`,
  `V3ConsumptionEngineArchitectureTest` et
  `V3ConsumptionFoundationArchitectureTest` sont déjà dans ce module exécuté.

Le contenu, les appels/assertions et les XML ont été examinés. Le seul nom
prospectif différent n'impose ni nouvelle classe ni adaptation d'allowlist.
L'exception Pot de la classe technique unique et A1 restent ceux acceptés en I4.

## 4. Matrice exhaustive des obligations de sortie internes

Lecture des tables : **S** = SATISFIED ; **SR** = SATISFIED WITH RESERVATIONS ;
**ND** = NOT DEMONSTRATED ; **DC** = DEFECT CONFIRMED ; **OUT** = OUT OF L3.
Un statut S concerne uniquement l'obligation bornée décrite, jamais l'exigence
SPEC-10 globale. Aucun DC applicatif Identity n'est constaté.

Notation de provenance, réutilisée pour éviter de répéter les chemins :

- **E4** : `docs/testing/evidence/L3-I4/campaigns/integration-final/`,
  `plan.json.gz`, `result.json.gz`, `provenance.json.gz`, `raw.tar.gz` ;
  SHA **145f42731a4e35488e9aaa56a7de3c3f453a9231**, 187/0/0/0 PASS borné.
- **X4** : même arborescence, `architecture-bounded`, même SHA, 163/0/0/0 PASS
  borné ; **N4-C/N4-A** : `native-command`/`native-architecture`, même SHA,
  reçus `qualified-result.json.gz`, tous deux FAIL.
- **H3** : reçu jar `docs/testing/evidence/L3-I3/smoke-pocoma-l3-i3-jar-86_rn2_h.json`,
  code initial I3 ci-dessus ; réception bornée du retry même ID et du restart.

Pour E4, le XML se retrouve exactement sous
`reports/pocoma-<module>/surefire-reports/TEST-<nom qualifié de classe>.xml` dans
l'archive. Le package des classes PRIMARY abrégées ci-dessous est
`com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity` ; leurs traces
réelles Engine I3 sont `app/infra-persistence-primary-jpa/target/l3-i3-evidence/<Classe>-<méthode>.txt`,
les primitives I2 sont sous `l3-i2-evidence/`. Les deux types de traces ne sont
pas interchangeables : les chemins `l3-i2` hérités par des fixtures I3 ne
transforment pas l'exécution en preuve de harness I2 ; lire l'appelant réel.

| ID | Source exacte | Scénario / invariant | Implémentation existante | Méthodes décisives | Résultat / preuve | SHA / provenance | Limites / réserves | Statut | Action minimale |
|---|---|---|---|---|---|---|---|---|---|
| O01 | Plan L3 §6/I1, §7 ; SPEC-02 §§2–4 | Valeurs pures, transitions strictes, absence de Binding autonome/version globale | `ExternalUserIdentity`, `IdentityTransitions`, contrats Registration | `IdentityTransitionsTest` et `IdentityValueContractsTest`, détail §5 | 17+8 invocations PASS, XML | E4 | Exécution externe non certifiée | S | Indexer, sans rejeu |
| O02 | Plan L3 §6/I2, §7.2 AUD-L3-002 ; SPEC-02 §§2–3/8 | Histoire exacte, ID stable, ancre zéro distincte de v1 | `JdbcIdentityAuthority`, V6 | `IdentityAuthorityPostgresTest#l3_history_exact`, `#l3_anchor_absence`, `IdentitySchemaPostgresTest#l3_anchor_negative_invariants` | États 1…5, PK exacte, négatifs SQL PASS | E4 | Harness primitives pour histoire ; Engine réel O07 | S | Réemployer |
| O03 | Plan L3 §7.2 AUD-L3-003 ; SPEC-02 §7, SPEC-05 §4 | Tri avant INSERT/verrou, Unicode opaque, connu/inconnu/mixte/self | Comparateur unique, `lockInCanonicalOrder` | `IdentityConcurrencyPostgresTest#l3_canonical_lock_paths`, `#l3_canonical_unicode`, `#l3_anchor_id_race`, `#l3_disjoint_progress` ; `IdentitySchemaPostgresTest#l3_unstorable_keys`, `#l3_bad_server_encoding_preflight` | Ordre SQL, blocage serveur, vecteurs indépendants PASS | E4 | Pas garantie temporelle arbitraire | S | Réemployer |
| O04 | Plan L3 §§6/I2, 7 ; SPEC-02 §§2/6 ; SPEC-10 ID-01 preuves | Aléa non prédictible, unicité historique, aucune fuite par représentation | `SecureBindingTokenGenerator`, UNIQUE V6, `BindingToken` masqué | `BindingTokenGeneratorTest#l3_generator_contract`, `#l3_real_secure_random_encoding`, `IdentityAuthorityPostgresTest#l3_forced_token_collision` | 32 octets SecureRandom/encodage ; collision active/historique 23505 et rollback PASS | E4 | Quelques valeurs distinctes ne prouvent pas seules l'aléa ; inspection du générateur également faite | S | Conserver contrat + preuves |
| O05 | Plan L3 §7 ; SPEC-02 §7 ; I01-D/F/J/K | Un seul gagnant, aucun User perdant, deux EUI sérialisées | Autorité + transitions + Engine L2 | `IdentityConcurrencyPostgresTest#l3_registration_race`, `#l3_link_races`, `#l3_cross_unlinks`, `#l3_link_vs_initiator_unlink` ; `IdentityRequestExecutionPostgresTest#l3_workers_concurrent_registration` | Barrières/PID/bloqueurs, compteurs durables PASS | E4 | Courses primitives et preuve deux Engines distinguées | S | Aucune requalification générale L2 |
| O06 | SPEC-10 I01-I/P ; plan L3 §§7, 9.2 | Négatifs Unlink et nouvelle demande après succès, avec vecteur durable exact | Code de rejet livré, aucun comportement manquant identifié | Méthodes et limites §5 | Domaine PASS ; certaines preuves SQL voisines PASS, pas les trois observations directes §8 | E4 | Pas absence de code ; couverture partielle du contrat nommé | ND | Trois probes §10, aucun nouveau test |
| O07 | Plan L3 §6/I3, §7 ; SPEC-03 §§1/3/6 ; E02-D/E | Demande immuable PENDING sans slot, trois opérations, terminal/résultat/complétion | `ExecuteIdentityRequest`, `JdbcIdentityRequestStore`, codec | `IdentityRequestContractTest` ; `IdentityRequestExecutionPostgresTest#l3_atomic_registration`, `#l3_atomic_link_unlink`, `#l3_rejection_terminal` ; `IdentityPrivilegesPostgresTest#l3_internal_admission_idempotence` | Engine réel et SQL restreint PASS | E4 | Admission contrôlée, pas AuthN fournisseur | SR | Documenter frontière |
| O08 | Plan L3 §7 ; SPEC-10 I01-N, E02-F ; CON-06 E/F/G | Rollback à chaque frontière, erreur technique jamais rejet métier, poisoning JDBC/JPA | Owner L2 + guards/writers existants | `IdentityRequestExecutionPostgresTest#l3_rollback_each_boundary`, `#l3_technical_not_rejected_and_redacted` ; `IdentityAuthorityPostgresTest#l3_guards_and_caught_failure_poison` ; `AttemptTransactionsPostgresTest#caughtApplicationJdbcJpaFailuresPoisonBothWriteOrders`, `#jpaJdbcSamePidXidAndClosedContextCannotDirtyWriteAtCommit` ; `V3TransactionFoundationPostgresTest#rollbackInBothWriteOrdersLeavesNoJpaOrJdbcEffect` | 28 coupures Identity, sentinelles JPA/JDBC du même owner, vecteurs annulés PASS | E4 | Pas nouveau writer métier Identity JPA exigé ; R1 hors composition manuelle | SR | Réemployer avec R1 |
| O09 | Plan L3 §7 ; SPEC-10 I01-O, E02-G/H/J ; R9 | ACK perdu après vrai commit / avant commit, terminal retry sans nouvel effet, observation sans réparation | Participant/observation + proxy COMMIT L1/L2 | `IdentityRequestExecutionPostgresTest#l3_unknown_commit`, `#l3_observation_corruption`, `#l3_unavailable_observation`, `#l3_atomic_registration` | Issues physiques, vecteur inchangé et prepare vide PASS ; retry opérateur H3 | E4 + H3, SHAs propres | H3 n'est pas nouveau jar I4 ; pas réparation spéculative | SR | Attribuer séparément les deux observations |
| O10 | Plan L3 §7 ; SPEC-05 §§4–5 ; C03/C04 applicables | Claim remplacé/expiré, horloge après verrou, transfert de partition, completion mono-usage | Engine/SlotCompletion L2 réemployés | `IdentityRequestExecutionPostgresTest#l3_fencing`, `#l3_final_lock_clock`, `#l3_completion_misuse` | Fencing et rollback PASS | E4 | Ne prouve pas disponibilité générale | S | Réemployer |
| O11 | Plan L3 §7 ; SPEC-05 §§5/7 ; C05 et R8 | Une acquisition/tentative, budget technique unique, N/Q/admin, aucune purge | Lifecycle/Engine inchangés | `IdentityRequestExecutionPostgresTest#l3_retry_quarantine`, `#l3_technical_not_rejected_and_redacted` ; `IdentityDiscoveryPostgresTest#l3_burnable_discovery` | N=3, Q durable, même slot réactivé, collision expurgée PASS | E4 | Capacité/équité/SLO globaux L2/L6 non certifiés | SR | Conserver limites |
| O12 | Plan L3 §7 ; SPEC-03 §§4–6 ; E01-D/E/F, G ID/IDCOMP | Événements minimaux, User puis EUI contigus ; rejet complétion seule ; densité/rollback | Store, V6, compteurs transactionnels | `IdentityPublicationPostgresTest#l3_identity_batch`, `#l3_dense_rollback`, `#l3_identity_pair_contention` ; exécution rejet O07 | SQL familles/positions et contention PASS | E4 | E01-G sur cinq familles et H sous charge restent hors certificat L3 | SR | Recevoir nominativement les reports Identity L2, sans réécrire L2 |
| O13 | Plan L3 §7.2 AUD-L3-001 ; E02-I, CON-06/R1 | Contraintes avant fermeture, terminal différé read-only, zéro DML/verrou métier tardif | V6 et publication fermée | `IdentitySchemaPostgresTest#l3_constraint_catalog`, `#l3_fk_wait_is_in_prefix`, `#l3_deferred_commit_has_no_new_business_locks`, `#l3_other_family_suffix_is_untouched` ; `IdentityPublicationPostgresTest#l3_constraints_before_close`, `#l3_deferred_commit_checks`, `#l3_structural_poison_before_counter`, `#l3_suffix_closed`, `#l3_physical_unit` | Catalogue/négatifs/probes NOWAIT/PID-XID et rollback PASS | E4 | Autres familles testées comme non-régression, pas nouveaux producteurs | S | Réemployer, garder analyse de suffixe |
| O14 | Plan L3 §§5.1, 7 ; CON-01/02, EVT-03 | Burnable asynchrone ; terminal/Q non réenrôlés ; deux sources Identity, User examiné/ignoré | Références/mappings Discovery existants | `IdentityDiscoveryPostgresTest#l3_burnable_discovery`, `#l3_discovery_source_slot_race`, `#l3_identity_windows` ; Worker `#l3_identity_discovery_checkpoint` | Source/slot sans cycle ; K avance sur User puis EUI, PASS | E4 | Matérialisation READ métier non livrée | SR | Réemployer et reporter L4 |
| O15 | Plan L3 §§5.2, 7.1, 9.2 ; SPEC-02 §§5–6 ; I02 interne, A10 WRITE | Moindre privilège, provenance non substituable, pas de token E2 dans résultat/event, bootstrap séparé | Guard + grants/logging I3 corrigés, contrat résultat fermé | `IdentityPrivilegesPostgresTest#l3_effective_privileges`, `#l3_wrong_authority` ; Worker `#l3_indirect_exposure_refused`, `#l3_auxiliary_effective_privilege_matrix`, `#l3_postgres_diagnostics_without_sensitive_detail` ; deux racines `#l3_logging_override_refused` | Profil fermé/objets indirects/TRUNCATE/logs serveur-Docker PASS | E4 ; 30 entrées manifeste résolution I3 revérifiées | R2/R3 ; admin et injecteur de confiance ; ID-02 externe non prouvé | SR | Runbook exact, réserves explicites |
| O16 | Plan L3 §§6/I3/I5, 9.1 ; C06-B/H/I/M | Deux racines actives, même owner JPA, pas Legacy/fixture ni worker supplémentaire | Composition partagée, imports/profils existants | Deux racines `#l3_runtime_identity`, `#l3_preflight_negative`, `#l3_delivered_jpa_rejects_prebound_holder` ; X4 règles Identity et Engine | Worker 10 / Monolithe 7 cas Identity PASS, architecture bornée PASS | E4 + X4 | R1 composition manuelle JDBC hors périmètre ; distribution L8 non certifiée | SR | Pas de création de classe I5 redondante |
| O17 | Plan L3 §6/I4, §7.2 AUD-L3-004 ; SPEC-02 §6 ; S02-C WRITE | Autorité réelle et témoins positifs/négatifs stables, token tiers, collision après vraie transition | `JdbcIdentityPotActorAuthority`, composition real | `IdentityPotAuthorityPostgresTest#l3_absent_stable`, `#l3_free_stable`, `#l3_invalid_token_same_version`, `#l3_valid_binding_and_foreign_token`, `#l3_observation_change` ; `V3IdentityPotCommandPostgresTest#l3_observation_change_and_l2_retry` | Witness exact, Collision puis acquisition normale, intention conservée PASS | E4, reçus I4 | Retard READ simulé par token ancien, pas I01-Q | SR | Réemployer |
| O18 | Plan L3 §6/I4, §9.1 ; C06-A/B/L | Registration → vraie Command, locks jusqu'à résolution, rollback/UNKNOWN ; User abandonné reste sans droits récupérables | Autorité Identity + moteur Pot inchangé | Deux racines `#l3_registration_command_and_relink_restart` ; `V3IdentityPotCommandPostgresTest#l3_command_lock_until_resolution`, `#l3_atomic_command_physical_unit_and_closed_suffix`, `#l3_rollback_boundaries`, `#l3_unknown_commit`, `#l3_real_authority_preserves_claim_fencing` | R-ID-POT deux racines, restart sans doublon, 7 coupures Command et 2 issues COMMIT PASS | E4 et 64 reçus nominatifs | Configuration interne ; garde A1 séparée | SR | Réemployer, pas nouvelle démo équivalente |
| O19 | Décision I4 §6 ; plan I4 §16/A1 | Négatif stable sans métier Pot, SELECT technique anti-effets conservé | `JdbcPotCommandStore.requireCompletionReady` inchangé | `V3IdentityPotCommandPostgresTest#l3_negative_stable_and_a1_sql`, `#l3_a1_illicit_version_rejected` | SQL technique isolé, probes sans verrou métier, injection refusée/rollback PASS | E4 | Ne pas écrire « zéro SELECT Pot » | S | Reproduire exactement A1 dans le dossier |
| O20 | Plan L3 §6/I5, §8.2 ; politique §§17/34/35 | Architecture globale REQUIRED | Règles existantes suffisantes ; aucune règle à modifier | 28 classes /163 tests X4 ; gate N4-A | X4 PASS borné ; N4-A FAIL, module SKIPPED | SHA I4, archives distinctes | Gate canonique non acquise ; 67 limites de découverte | ND | ARB-I5-01 ratifié ; dette canonique ouverte, aucune substitution par X4 |
| O21 | Plan L3 §8.2 ; politique §§6/34 | Gate COMMAND + Monolithe | Deux ancres existantes | N4-C, non filtrée | 1789 tests /1 failure/1 skip, FAIL | SHA I4 | r12 ; 46 limites de découverte | ND | ARB-I5-01 ratifié ; dette canonique ouverte, pas de relance inchangée |
| O22 | Plan L3 §8.2 ; politique §§18–21/34/35 | Full conditionnel et niveau de clôture | Aucun nouveau code/graphe | Aucune exécution full I5 | NOT RUN | Aucun SHA frais prétendu | Voie interne bornée §34 retenue par ARB-I5-01 ; pas de Wave majeure/main/release dans mandat | SR | ARB-I5-01 ratifié ; FULL réservé aux déclencheurs majeurs ultérieurs |
| O23 | Plan L3 §6/I5, §9.2 ; politique §34 | Provenance/équivalence, preuves durables, attribution méthodes/oracles | Archives/manifestes et vérificateur I4 présents | Contrôles statiques §7 | 686 XML, 68 artefacts de preuve, 64 reçus contrôlés ; entrées app/SQL égales | Code I4 → HEAD actuel, pas rejeu | Hash ≠ authenticité ; limites JUnit restent ouvertes | SR | Manifeste final avec réemploi explicite et probes fraîches |
| O24 | Plan L3 §6/I5, §9.1/9.2 | Rapport, runbook, manifeste et clôture autoporteurs | Dossiers I1–I4 et runbook I3 réutilisables | Pas une méthode de test | Quatre livrables finaux non produits par ce mandat | Future campagne seulement | Pas de placeholders ni décision anticipée | ND | Contenus/recevabilité §15 |
| O25 | Plan L3 §6/I5 et §9.2 ; plan directeur §4 | Audit indépendant et décision humaine L3 | Acceptations I1–I4 acquises, pas audit I5 | Audit des sources/reçus/diff puis décision nominative | NOT RUN / PENDING I5 | Dossier gelé futur | Auteur du plan ≠ auditeur indépendant ; avis I4 rapporté ne vaut pas audit I5 | ND | Phase 4, aucun ACCEPTED automatique |
| O26 | Plan L3 §7.1, §9.1 ; SPEC-02 §§5–6 ; SPEC-04 §§6–8 ; SPEC-10 I01-Q/ID-02/SEC | READ, HTTP, consentement externe, qualification serveur | Ports/histoire WRITE livrés, consommateurs réels futurs | Aucun test L3 ne certifie ces parcours | NOT RUN dans leurs lots | Reports L4/L5 nominatifs | Ne pas certifier ID-01/ID-02 globaux | OUT | Transfert L4/L5, aucune implémentation I5 |
| O27 | Plan L3 §7.1 ; SPEC-10 §§10–13 ; plan directeur L6–L8 | Reconstruction métier, capacité/SLO, convergence/release/production | Mécanismes acquis avec réserves | Campagnes des lots compétents | G1/G2 INCONCLUSIVE, G3 non franchi, certification globale absente | Références L2 conservées | Hors clôture interne conditionnelle | OUT | Reports L6–L8, aucun GO production |

Les obligations de comportement O01–O19 ne sont pas des créations I5. O06 est
la seule lacune expérimentale interne retenue ; O20/O21 sont des gates non
satisfaites, non des preuves manquantes de développement Identity ; O24/O25
restent du travail documentaire et de décision. O22 requiert l'arbitrage de portée.

## 5. ID-01 — mapping exhaustif I01-A…Q

Toutes les méthodes ci-dessous sont **réellement présentes et PASS dans les XML
E4**, sauf mention explicite. `D` = `IdentityTransitionsTest`, `A` =
`IdentityAuthorityPostgresTest`, `C` = `IdentityConcurrencyPostgresTest`, `S` =
`IdentitySchemaPostgresTest`, `R` = `IdentityRequestExecutionPostgresTest`,
`P` = `IdentityPublicationPostgresTest`, `T` = `IdentityPotAuthorityPostgresTest`.
Les noms de méthodes sont exacts ; voir §4 pour module/package/membre d'archive.
Une preuve composée domaine + SQL est déclarée comme telle, sans prétendre que
chaque assertion s'exécute dans la même méthode Engine.

| Variante / contrat SPEC-10 §4 | Méthodes exécutées | Oracle effectivement vérifié | Résultat, provenance et couverture interne | Dépendance externe / clôture L3 |
|---|---|---|---|---|
| I01-A — Registration inconnue | D#registrationOfAbsentIdentityCreatesUserAndDirectlyBoundVersionOne ; A#l3_anchor_absence ; R#l3_atomic_registration ; P#l3_identity_batch | État exact v1 lié et nouveau User ; ancre réservée ID réutilisé ; 1 User/1 état/1 résultat/2 mutations/1 complétion ; ordre User/EUI et densité | E4 PASS, **complète bornée**, assertions domaine + SQL + Engine/publication | AuthN d'admission supposée valide ; aucune obligation READ ajoutée |
| I01-B — Registration libre vN | D#registrationOfFreeIdentityKeepsIdAndCreatesExactSuccessorAndNewUser ; A#l3_last_unlink_and_registration_free | ID conservé, v1→libre v2→nouveau User v3 ; User initial persiste ; tokens différents | E4 PASS, **complète bornée**, primitive durable réelle | Pas de dépendance READ/HTTP pour WRITE |
| I01-C — Registration déjà liée | D#registrationRejectsBoundIdentityWithoutAUserOrStateChange ; C#l3_registration_race | Rejet TARGET_ALREADY_BOUND après victoire ; un seul User supplémentaire et version exacte, domaine inchangé | E4 PASS, **complète bornée** par domaine + course SQL ; pas de test séquentiel Engine dédié | Compléter I01-P séparément, ne pas assimiler concurrence et rejeu post-succès |
| I01-D — deux Registrations inconnue/libre | C#l3_registration_race ; R#l3_workers_concurrent_registration | Un gagnant, perdant rejeté ; versions 1/3 ; un seul User ; deux Engines et blocage physique | E4 PASS, **complète bornée** ; variantes inconnue/libre dans la boucle du premier test | Réemploi B ; aucune réexécution |
| I01-E — Link cible inconnue/libre | D#linkAbsentTargetDeducesUserFromInitiatorWithoutCreatingUser ; D#linkFreeTargetRetainsIdAndExactSuccessor ; A#l3_history_exact ; R#l3_atomic_link_unlink | U déduit d'E1 ; aucun User créé ; E2 v1 puis v3/v5 ; ID stable, nouveau token, états exacts | E4 PASS, **complète WRITE** | Consentement E2 externe non prouvé : contribution ID-01 interne seulement, ID-02 L5 |
| I01-F — deux Links et Registration/Link | C#l3_link_races | Deux Links : seul owner gagnant ; Registration/Link sur inconnue/libre, les deux ordres gagnants, aucun User perdant | E4 PASS, **complète bornée**, barrières/bloqueurs et SQL | Admission Link contrôlée ; qualification fournisseur L5 |
| I01-G — Link déjà liée, même User inclus | D#linkRejectsAlreadyBoundTargetEvenToSameUser(boolean)[1]/[2] ; D#selfLinkIsStrictlyRejected ; A#l3_history_exact ; C#l3_link_races | Deux paramètres domaine réellement découverts ; re-Link strict rejeté au même User dans histoire v5 ; état/version/compteurs inchangés, complétion seule ; autre User sous course | E4 PASS, **complète bornée** par preuves complémentaires ; token non renouvelé découle aussi de l'histoire immuable sans nouvelle version | Pas de besoin de nouveau test portant le nom I01-G |
| I01-H — Unlink autre/self/dernière | D#unlinkOtherIdentityOfSameUserDoesNotRequireItsToken ; D#selfAndLastUnlinkOnlyChangeEuiAndNeverDeleteUser ; A#l3_last_unlink_and_registration_free ; R#l3_atomic_link_unlink | Seul token E1 ; E2 libre N+1 et paire User/token nulle ; User persiste après dernière EUI ; nouvelle Registration autre User | E4 PASS, **complète bornée**, deux racines corroborent | Aucun mécanisme de récupération du User abandonné |
| I01-I — initiateur obsolète / cible libre / autre User | D#absentFreeOrStaleInitiatorCannotLinkOrUnlink ; D#unlinkRejectsAbsentFreeAndAnotherUsersTarget ; A#l3_history_exact | Domaine : motifs INVALID_BINDING/TARGET_NOT_BOUND/TARGET_BOUND_TO_ANOTHER_USER ; SQL : ancien token B d'E2 refusé après v5, compteurs d'états/mutations inchangés | E4 PASS pour ces assertions ; **partielle** : pas de vecteur durable directement attribué au Unlink d'une cible déjà libre ou liée à un autre User dans le vrai Engine | **C interne** : Q-UFREE et Q-UOTHER §10 ; aucun défaut de code déduit |
| I01-J — deux Unlinks croisés | C#l3_cross_unlinks ; D#crossedUnlinksLoseInitiatorAuthorityAfterFirstSuccess | Première transition gagne, deuxième INVALID_BINDING ; E1 liée/E2 libre, ordre canonique, attente serveur puis terminaison | E4 PASS, **complète bornée**, pas de simple simulation séquentielle utilisée comme seule preuve | Réemploi B |
| I01-K — Link vs Unlink initiateur | C#l3_link_vs_initiator_unlink ; T#l3_command_vs_unlink_and_link en corroboration du protocole partagé | Deux ordres de victoire ; si Unlink gagne, E2 sans version et ancre zéro ; sinon Link finit puis E1 est détachée ; protections réelles | E4 PASS, **complète bornée** | Pas certification de disponibilité temporelle L2 |
| I01-L — histoire U1/B→libre→U2/C→libre→U1/D | D#localHistoryOneThroughFivePreservesOldStatesAndRenewsTokensEvenForSameUser ; A#l3_history_exact ; T#l3_valid_binding_and_foreign_token, #l3_observation_change ; deux racines #l3_registration_command_and_relink_restart | `at(id,v)` égale chaque état exact 1…5, U et nulls ; B/C/D distincts ; B obsolète refusé ; raccord Pot refuse ancien/tiers, égalité du token courant par clé exacte | E4 PASS, **complète bornée**, assertions complémentaires ; pas d'essai individuel de chaque token historique sur chaque opération | Pas accès public aux tokens historiques ; I01-Q distinct |
| I01-M — même subject/deux issuers, doublon exact, ID stable | `IdentityValueContractsTest#externalKeysPreserveOpaqueTextWithoutCaseUrlOrUnicodeNormalization` ; C#l3_canonical_unicode, #l3_anchor_id_race ; S#l3_sql_negative_shapes ; T#l3_valid_binding_and_foreign_token | Ancres `(a,x)` et `(b,x)` distinctes en SQL ; UNIQUE couple exact ; UUID gagnant stable ; mauvais issuer ne résout aucun acteur | E4 PASS, **complète bornée** ; ne pas prétendre deux Registrations multi-issuer dans le même scénario | Aucune normalisation autorisée |
| I01-N — faute après User/EUI/premier événement | R#l3_rollback_each_boundary ; A#l3_rollback_boundaries ; P#l3_dense_rollback, #l3_deferred_commit_checks | 28 combinaisons Engine, faute SQL après User, vecteur avant/après identique, demande PENDING/slot conservé, aucun compteur consommé | E4 PASS, **complète bornée**, exceptions techniques non rejetées | Réemploi B ; aucune nouvelle coupure préventive |
| I01-O — vrai commit, réponse perdue, même ID | R#l3_unknown_commit ; R#l3_atomic_registration ; H3 retry opérateur | Proxy intercepte COMMIT réel, UNKNOWN→COMPLETED ; seconde préparation vide et vecteur durable identique ; H3 retrouve même source/résultat avec fichier reformaté | E4 PASS + H3 historique, **complète bornée par composition** ; pas nouveau replay de jar I4 | H3 a seulement un reçu public expurgé, pas tous logs opérateur ; réserve explicite, pas défaut démontré |
| I01-P — même intention, nouvel ID après succès | A#l3_history_exact ; D#registrationRejectsBoundIdentityWithoutAUserOrStateChange ; C#l3_registration_race | Link séquentiel nouvel UUID après v5 rejeté ; Registration : domaine lié rejeté et course deux IDs prouvés, mais admission du second ID dans la course précède le succès | E4 PASS pour ces assertions ; **partielle** : rejeu Registration séquentiel post-succès dans Engine non directement observé | **C interne** : Q-REGNEW §10 ; pas nouveau mécanisme d'idempotence |
| I01-Q — accès au token via READ retardé/rattrapé | **Aucune méthode L3 ne démontre cet oracle complet** | I4 teste token ancien côté Command et absence de recours READ ; ni matérialisation CurrentBinding@V ni AuthN/contrôle HTTP du token | **Absente pour cet oracle**, NOT RUN, **OUT OF L3** ; pas FAIL applicatif L3 | **D externe** : L4 CurrentBinding monotone@version + L5 sessions E1/E2, accès propre, SEC-01/02, minVersion/cache/logs ; report obligatoire |

**Bilan : 17/17 variantes inventoriées ; 14 contributions internes complètes
bornées, 2 partielles (I et P), 1 hors L3 (Q).** Aucune variante oubliée, mais
**ID-01 global n'est pas PASS**. Après les trois probes réussies, A…P pourront
être déclarées complètes **dans le périmètre WRITE interne**, avec les limites
de composition/provenance ci-dessus. HTTP/Bruno et I01-Q resteront non certifiés.
Les obligations WRITE de la catégorie A sont A…P ; leur partie déjà démontrée
est B (réemploi) ; les trois sous-cas de I/P sont C ; Q et les volets externes
ID-02 sont D. Ces catégories ne transfèrent aucun verdict aux lots futurs.

## 6. Analyse contradictoire de i5_r05 et i5_r12

### 6.1 Contrat protégé et observations exactes

Sources déterminantes : plan L2/I5 §§14–16, amendements F01–F05/T04, contrats
temporels v2 et final, clôture L2 §§4–6 ; code actuel
`V3WorkerResourcesPostgresTest`, `I5TemporalV2Postgres`, `I5PhysicalWindow`,
`V3WorkerPostgresTest.Runtime.close`. Les XML propres des deux gates I4 ont
été lus directement dans leurs archives.

| Scénario | Invariant et conditions connues | Observation I4 et portée |
|---|---|---|
| r05 | `campaign(PG,1,1)` → controlled=false, W1/B1/MW4 ; vrai Worker, 12 leases, claims Business retenus, WRITE/READ, quatre pools ; contrat v2 B : extension de la même incarnation avant ancienne échéance avec marge, sous fenêtre disponible **indépendante** du résultat ; C : suspension/revalidation/retrait sans autorité inventée | N4-C : PASS 53,600 s ; N4-A : **FAIL** 17,069 s, `B missing independent available window a5000000-0000-4000-8000-000000000002/0`, `Journal.verify:520` via campagne nominale. Le premier PASS ne lève pas le second FAIL ni la réserve |
| r12 | `campaign(PG,2,32,true)`, W2/B32/MW4 ; partitions disjointes, 8 préparations Business retenues, 64 sessions de rôles, serveur100/réserve16 ; fenêtre v3 contrôlée par barrière/probe physique ; mêmes oracles B/C plus **arrêt qualifié** | N4-C : **FAIL** 95,051 s ; N4-A : **FAIL** 95,137 s ; `runtime condition deadline`, `Runtime.close:555` → `campaign:947` → r12:896. L'attente vise STOPPED après drain, pas un timeout Maven |

Les deux XML r12 I4 contiennent aussi `FATAL: terminating connection due to
transaction timeout`. Les marqueurs internes de qualification, lorsqu'ils
précèdent `finally/close`, ne sont pas le résultat de la méthode JUnit.

### 6.2 Causes établies et hypothèses non établies

**r05 :** le checker exige, pour chaque candidat, une tentative couverte par
une fenêtre indépendante. En mode non contrôlé, un recouvrement avec l'enveloppe
entière d'une transaction Control potentiellement conflictuelle suffit à refuser
ce témoin. Le [diagnostic I1](L3_I1_Verification_Resolution.md) prouve sur son
propre journal le chevauchement Discovery/renewal malgré une extension SQL avec
le même token. Le contre-exemple L2/v2 documente le même type de défaut de
couverture sur W2/READ, pas la même exécution W1 I4. Pour I4, l'assertion et la
clé ci-dessus sont établies ; aucune reconstitution indépendante de l'ensemble
des intervalles n'est revendiquée par ce plan. Ni faux positif, ni expiration,
ni faute de CAS/fencing, ni défaut de sûreté Identity ne sont démontrés par ce
seul échec. Une disponibilité favorable intermittente ne suffit pas au contrat.

**r12 :** le rapport final L2 établit une rétention >71,297318 s pour une durée
transactionnelle Business de 65 s (socket70s), des erreurs serveur de timeout,
puis l'échec du drain/STOPPED ; le diagnostic I1 établit >71,377141 s et les
mêmes symptômes. Les XML I4 corroborent timeout serveur et échec de fermeture.
La durée de vie physique supposée de la fixture n'est donc pas une preuve
acquise pour toute la séquence. Cela n'établit pas que tout le problème est
« uniquement un test » : sans snapshot exhaustif des responsabilités après
libération, l'état local exact empêchant STOPPED et l'exclusivité de la cause
restent non démontrés. Le maintien conservateur d'une responsabilité UNKNOWN
est compatible avec les garanties F01/F03/F04 ; un retour local/une échéance
ne permettent pas de restituer prématurément une capacité ni de fermer les pools.

Les contrats expérimentaux maintiennent TTL90s, cadence10s, T250ms,
P1s/q10ms/J1s, delta5s, marge2s, revalidation5s, D(N)=N×(T+q)+P+J.
**T est une hypothèse conditionnelle, pas une borne garantie du runtime.**
30s demeure non certifié ; aucun certificat général 90s n'est acquis.
Le mode contrôlé réduit explicitement la concurrence Control : même ses phases
positives ne certifient pas le chevauchement arbitraire de toutes les voies.

### 6.3 Rapport avec Identity I1–I4

Comparaison statique au gel final L2 `a2133b6b…` :

- `V3WorkerResourcesPostgresTest`, `I5TemporalV2Postgres` et
  `I5PhysicalWindow` sont identiques aux octets courants ; r05/r12, leurs
  assertions et paramètres ne sont pas modifiés par Identity.
- **`V3WorkerPostgresTest` n'est pas identique au gel L2** : I3 a ajouté
  `identity-authority=fixture` et `command-enabled=true` dans la fixture,
  plus une différence de formatage. Ne pas écrire « tout le harness est
  inchangé depuis L2 ». Ces flags rendent son ancien choix explicite.
- La fixture temporelle continue à utiliser l'autorité fixture, pas le nouvel
  adaptateur `JdbcIdentityPotActorAuthority`. La production partagée de
  composition a évolué en I3/I4 ; l'absence d'usage du nouvel adaptateur et les
  PASS des vrais parcours I4 isolent fonctionnellement le raccord, sans prouver
  une équivalence temporelle de toute la JVM/charge/JIT/Docker.
- Aucun replay comparatif de baseline L2 n'a été exécuté ici ; aucune causalité
  productive Identity ni absence universelle de régression temporelle n'en est
  déduite. Les défaillances sont cohérentes avec les réserves L2 acceptées.

**Conséquence :** aucune correction productive Identity n'est justifiée par
ces FAIL. Un redesign de renewal/drain, un timeout augmenté ou une assertion
supprimée seraient un chantier L2 distinct sans preuve nouvelle d'invariant
Identity violé. Les FAIL restent des FAIL de leurs gates.

### 6.4 Recevabilité d'une clôture avec réserves

**Pas de clôture technique conforme déductible des seules décisions I1–I4.**
Le plan L3 §8.2 exige Architecture ; la politique §§34–35 interdit de
substituer un filtrage réussi à la gate explicite. Les réserves L2 ne dispensent
pas automatiquement de cette obligation L3.

**L'arbitrage humain ARB-I5-01 a été ratifié le 31 octobre 2026 (§11) :**
conformément à la clarification SPEC-10 §1, le responsable du programme choisit
la voie de clôture interne bornée, maintient les gates REQUIRED/RUN/FAIL et leur dette
technique ouverte, et accorde l'exception explicite de recevabilité de la clôture
documentaire. Ce n'est ni un PASS technique, ni une gate satisfaite, ni une
dispense générale, ni une certification globale. Cette décision humaine accepte
que la qualification interne bornée soit instruite malgré cette dette technique, sans
prétendre la résoudre. La clôture finale L3 reste conditionnée aux preuves
complémentaires, à l'audit indépendant et à une nouvelle décision humaine explicite.
Détail exhaustif de l'arbitrage consigné au §11.

## 7. Preuves réutilisables et contrôle de provenance

### 7.1 Contrôles statiques réalisés

Après lecture de son source, exécution **du seul vérificateur d'archives en
lecture seule** `python3 -B docs/testing/evidence/L3-I4/verify_evidence.py` :
empreintes et tailles des **68 artefacts** du manifeste, membres exacts des
archives, ID/module/producteur des **686 XML**, compteurs, sources Git et
**64 reçus**/lignes vérifiés. Ce résultat est un contrôle documentaire, **aucune
méthode applicative n'a été exécutée**.

Comparaisons supplémentaires par lectures Git/Python :

- les **1 265** entrées `app_inputs` de l'INTEGRATION finale correspondent aux
  fichiers courants ; les **7** SQL externes aussi ; arbres Git app/scripts
  identiques entre code testé et HEAD documentaire (§1) ;
- sélecteur `verify.py` SHA-256
  `b32ad535f68cbc4846591451379e4c2dfb78fc9164691ae821acc3371ca2eed9`, registre
  `d4b9cc440ee34b21c16f666c80757456297d71ff79850d343914c014d2ee2277`, égaux
  aux entrées d'exécution ;
- les **30 entrées** du [manifeste de résolution I3](../testing/evidence/L3-I3-resolution/manifest.json)
  correspondent en taille/SHA-256 ; les anciens constats source/bytecode restent
  visibles ;
- participant, store, observation et outil Java DEV sont identiques entre le
  code final initial I3 et HEAD ; le wrapper Python a évolué et ne fait pas
  l'objet d'une revendication globale d'équivalence de jar ;
- méthodes/assertions des tables §§4–5 confrontées aux XML E4, et échecs r05/r12
  confrontés aux XML des deux N4. Aucun ancien `target/*-reports` utilisé.

L'I4 avait recompilé proprement sa fermeture avant preuves et comparé **1 898
classes**, sans différence avant/après. Ce constat est historique : les sorties
`target/` présentes aujourd'hui ne sont pas automatiquement réputées certifiées.

### 7.2 Inventaire opératoire

| Dossier / observation | Résultat à réutiliser | Usage I5 / limite |
|---|---|---|
| `campaigns/fast-final` | 65 PASS, 10 modules, 2,205 s processus Maven | Contrats purs ; pas preuve PG ni gate |
| `campaigns/change-final` | 149 PASS, 50 modules, 475,720 s | Autorité, concurrence, sécurité et raccord Worker ; filtré |
| `campaigns/integration-final` | 187 PASS, 51 modules, 574,830 s | Principale base interne L3, deux racines/owner/voisins ; filtré |
| `campaigns/architecture-bounded` | 163 PASS, 28 classes, graphe 60 modules, 37,214 s | Module entier sous sélection explicite ; pas sa gate non filtrée |
| `selector-cli-final.tar.gz` + diagnostic final | 40 contrôles PASS, vrais replays CLI isolés | Provenance/sélection ; pas certificat générique de toute découverte JUnit |
| `campaigns/native-command` | 1789/1/0/1, FAIL ; 3211,070 s | Observation canonique non filtrée, 51 modules prévus ; reprise nominative de classe secondaire dans reçu qualifié |
| `campaigns/native-architecture` | 1892/2/0/1, FAIL ; 3242,034 s | Observation canonique indépendante, graphe 60 modules ; Architecture SKIPPED après échec Worker |
| `Variant_Receipts.json`, `physical_traces` du manifeste I4 | 64 reçus ; 35 traces I4, membres/empreintes/lignes | Sous-variantes dans les boucles, dont marqueurs opaques dérivés des appels fixes du source ; pas nouveaux marqueurs runtime inventés |
| [I1 Verification](../testing/evidence/L3-I1/Verification.json), [I2 Coverage](../testing/evidence/L3-I2/Coverage.json), [I3 manifeste](../testing/evidence/L3-I3/manifest.json), résolution I3 | Historique des acceptations et preuves ; tests pertinents réexécutés par E4 | Ne pas additionner les mêmes invocations entre campagnes ; H3 reste un reçu historique expurgé |
| `Migration_Observations.json` I4 | PostgreSQL réel 17.11 ; INTEGRATION CURRENT WRITE V1–V6 / READ technique V1 | Architecture/native exécutent aussi l'histoire transitive jusqu'à V31 ; pas baseline TRUSTED ni campagne historique séparée |

Environnement I4 : Temurin 21+35, Maven3.9.14, macOS27.0/aarch64, Docker29.9.2,
locale fr_FR/UTF-8 ; image Identity
`postgres@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73`.
Les provenances signalent des ajouts documentaires pendant les preuves finales,
sans changement exécutable ; ne pas déclarer le worktree entier propre alors.
Settings Maven historiques absents (`null`), pas hypothèse de settings secrets
répliqués. Les dépendances/configurations effectives archivées restent celles
de ces exécutions, pas une nouvelle résolution Maven faite ici.

### 7.3 Limites de découverte et conditions de réemploi

Les **46** limites de découverte N4-C et **67** N4-A restent dans les plans et
reçus. La classe top-level non publique `CurrentTransactionManagerPostgresTest`
dans `JpaTerminalSequencingPostgresTest.java` a une attribution nominative revue
à son module : le total initial 1788/1891 devient **1789/1892**, sans changer
les XML, failures ou skip. Cela n'étend pas la qualification à tous les providers,
tests composés/hérités/dynamiques ou générés.

Le skip conservé est
`TerminalSequencingPostgresTest.zExploratoryContentionSeparateFromCorrectness`,
propriété `step3b.measure` absente. Même un succès Maven avec ce skip et une
découverte incomplète n'aurait pas suffi au PASS strict de la politique §35.
Le diagnostic Surefire `System.exit(0)` PRIMARY reste dans les bruts ; il ne
transforme ni le résultat du module ni la gate en certificat global.

Réemploi recevable : artefacts accessibles et empreintes vérifiées, SHA testé,
oracle exact, comparaison ancien→nouveau app/POM/SQL/recettes/paramètres et
hypothèses du profil, limites et résultat original conservés. **Une équivalence
de sources permet l'attribution historique, pas une exécution fraîche.** Une
nouvelle configuration de rôles/manager/logging/PG ou un changement d'hypothèse
exige la requalification de la propriété affectée. Les hashes prouvent
l'intégrité, pas l'authenticité face à un acteur capable de forger les producteurs.

## 8. Lacunes réellement restantes

| Lacune | Nature exacte | Complément minimal |
|---|---|---|
| Q-UFREE, I01-I | Rejet domaine prouvé ; pas observation directe du vecteur durable sur Unlink vers EUI déjà libre dans Engine | Une demande contrôlée, initiateur valide, cible existante libre ; rejet exact et vecteur §10 |
| Q-UOTHER, I01-I | Rejet domaine prouvé ; pas observation directe du vecteur durable sur Unlink vers EUI liée à un autre User | Une demande contrôlée, initiateur valide, cible autre User ; même oracle |
| Q-REGNEW, I01-P | Registration liée et concurrence prouvées ; pas admission séquentielle d'une nouvelle demande après succès avec cet oracle durable | Une nouvelle Registration de la même EUI liée, nouvel UUID après résultat initial ; rejet strict, aucun User/token/version nouveau |
| O20/O21 | Échecs canoniques présents, pas absence de logs ou de code | Arbitrage préalable ; aucune nouvelle exécution inchangée n'améliore à elle seule leur valeur |
| O24/O25 | Dossier de lot, audit et décision manquants à ce stade | Rédiger les quatre livrables complets pendant la future campagne, puis audit/décision |

Ces trois probes ne constituent ni la réouverture d'I1–I4 ni une nouvelle règle
fonctionnelle. Elles complètent une traçabilité expérimentale spécifique ; les
méthodes existantes ne deviennent pas FAIL. La simple relance de ces méthodes
**ne créerait pas les stimuli absents**. Aucun test nouveau n'est nécessaire
si l'audit accepte le protocole opérateur contrôlé suivant comme preuve directe.
Si un test automatisé versionné est imposé, arrêter cette voie et soumettre un
plan probatoire borné ; ne pas glisser un nouveau test dans une campagne approuvée.

## 9. Corrections indispensables

**Aucune correction productive confirmée. Aucun fichier applicatif, test,
migration, POM ou outil de collecte à modifier proposé.**

Les trois probes passent par `worker-dev.py inject-identity`, `identity-token`
et `verify-identity`, puis des lectures SQL indépendantes. Le défaut documentaire
ne devient pas une obligation de modifier le domaine, d'ajouter une abstraction
ou de fiabiliser globalement le Worker.

Si une probe établit une mutation sur rejet, une fuite de token dans le canal
qualifié, un second User, une demi-terminalisation ou une violation d'owner/fencing,
arrêter la qualification. Présenter invariant, reçu/snapshot, cause établie ou
encore inconnue, fichiers exacts, changement minimal et slices/gates affectées.
Un changement de comportement et un éventuel ajout probatoire sont deux objets
distincts ; **aucune correction n'est autorisée par le GO de ce plan**. Nouveau
GO correctif requis, avec conservation des preuves FAIL. Pas de correction
préventive ni extension L4–L8.

## 10. Stratégie FAST / CHANGE / INTEGRATION / CERTIFICATION

### 10.1 Déclaration de portée de la future campagne bornée

Impact productif attendu **NONE** ; risques examinés : Identity, autorisation,
atomicité, publication, runtime et provenance. Primaire **COMMAND** ; secondaires
canoniques **NONE** ; Monolithe = racine complémentaire **déjà prouvée/réemployée**.
BINDING V2 n'est pas requis par le mot Identity ; WEB/EVENT/PROJECTION/LKV n'ont
aucun comportement productif modifié. Architecture **REQUIRED, résultat I4 FAIL** ;
couvert par l'arbitrage nominatif ARB-I5-01 (§11). FULL **non planifié dans la voie
interne bornée**, conformément à ARB-I5-01. DB **CURRENT V3 neuve**, WRITE V1–V6,
READ technique V1 ; baseline TRUSTED **NONE** ; aucun historique autonome.

Niveaux : FAST/CHANGE/INTEGRATION I4 **réemployés**, pas de rejeu automatique de
65/149/187 tests ; complément fonctionnel de niveau INTEGRATION interne à petite
échelle (campagne Q-ID). **CERTIFICATION globale NOT RUN et NOT CLAIMED**, ce jalon
relevant de la clôture humaine documentaire bornée du raccord Identity L3 (§34 de
la politique Reactor et plan L3 §8.2), sans prétendre à un certificat V3 global (§35).

### 10.2 Exécution Q-ID et préflight statique approfondi (Résolution R-I5-02)

Avant d'autoriser l'exécution de la campagne Q-ID, l'exécutabilité du protocole a
fait l'objet d'un contrôle statique approfondi contre l'outillage et le DDL existants.
Après audit indépendant et GO formel (intégrant ARB-I5-01 consigné au §11), une seule
préparation, un seul Worker, **4 demandes de mise en état (R1–R4) + 3 probes (R5–R7)**,
en série. Les deux racines partagent le même participant qualifié par E4 : réexécuter
ces négatifs dans le Monolithe n'introduirait aucune frontière nouvelle.

#### A. Contrôle statique de l'outillage et de l'environnement DEV

Les interfaces réelles de `scripts/v3/worker-dev.py` et de la classe Java sous-jacente
`com.kartaguez.pocoma.runtime.command.v3.dev.V3WorkerDevelopmentTools` ont été
confrontées au protocole :

1. **Phase `prepare` :**
   - Commande : `python3 -B scripts/v3/worker-dev.py prepare --config <config_privée>`
   - Options : `--config` (Path, fichier de propriétés 0600), optionnel `--jar` (par défaut le jar exécutable du Worker).
   - Préconditions et environnement : variables d'environnement obligatoires `POCOMA_I5_ADMIN_URL`,
     `POCOMA_I5_ADMIN_USER`, `POCOMA_I5_ADMIN_PASSWORD`, `POCOMA_I5_REACTIVATION_ROLE`,
     `POCOMA_I5_REACTIVATION_PASSWORD`, `POCOMA_I5_INJECTOR_ROLE`, `POCOMA_I5_INJECTOR_PASSWORD`.
     Optionnel : `POCOMA_I5_PSQL_CONTAINER` si Docker est utilisé pour psql.
   - Séquence exécutée : `java_operation("prepare")` applique Flyway (WRITE V1–V6, READ V1) ;
     exécution administrative de `configure-l3-identity-logging.sql`, `bootstrap-l2-routing.sql`,
     `grant-l2-lifecycle.sql`, `grant-l2-discovery.sql`, `bootstrap-l2-worker-dev.sql`, `grant-l2-worker-dev.sql` ;
     `java_operation("validate")` contrôle les privilèges et la source Identity.
   - Restitution : affiche `I5_DEV_STAGE ...`, `I5_DEV_VALIDATED ...`, écrit le reçu
     `<config>.prepared.json` (mode 0600) et termine avec le message
     `I5_DEV_PREPARED; start/restart never calls prepare`. Code de sortie : 0 (succès), 2 (refus).

2. **Build du jar exécutable :**
   - Commande depuis `app/` :
     ```sh
     ./mvnw -pl runtime-command-consumption-worker -am clean package -DskipTests
     ```
   - Paramètres : compilation sans tests exécutés (`-DskipTests`), écartant tout bytecode ancien.
     Produit le jar exécutable `app/runtime-command-consumption-worker/target/pocoma-runtime-command-consumption-worker-0.0.1-SNAPSHOT-exec.jar`.
   - Fermeture : 50 modules Maven (domaine, contrats, persistence JPA, transaction, supras, composition, worker).

3. **Lancement du Worker DEV :**
   - Profil actif : `--spring.profiles.active=v3-consumption-worker-dev`.
   - Configuration dans `<config_privée>` :
     `pocoma.v3.worker.enabled=true`, `pocoma.v3.worker.environment=DEV`,
     `pocoma.v3.worker.identity-authority=real`, `pocoma.v3.worker.identity-request=true`,
     `pocoma.v3.worker.fixture-identity=false`, `pocoma.v3.worker.command-enabled=false`,
     `pocoma.v3.worker.technical-probes=false`, `pocoma.v3.worker.worker-name=l3-identity`,
     `pocoma.v3.worker.worker-limit=4`, `pocoma.v3.worker.consumption-limits=IdentityRequest=4`,
     `pocoma.v3.worker.partitions=IdentityRequest=0|1|2|3` (4 partitions disjointes 0, 1, 2, 3).
   - Propriétaire transactionnel : JPA (`JpaTransactionManager` / `SpringAttemptTransactions`).
   - Lancement en arrière-plan avec redirection des logs :
     ```sh
     java -jar "$PWD/app/runtime-command-consumption-worker/target/pocoma-runtime-command-consumption-worker-0.0.1-SNAPSHOT-exec.jar" \
       --spring.profiles.active=v3-consumption-worker-dev \
       --spring.config.additional-location=file:/private/tmp/l3-i5-private/runtime.properties \
       > /private/tmp/l3-i5-private/worker.log 2>&1 &
     ```
   - **Point d'attention statique vérifié :** `worker-dev.py stop` contrôle l'identité du processus
     via `ps -ww -p <PID>` en vérifiant la présence exacte de `str(args.jar.resolve())`
     (chemin absolu). Le lancement avec le chemin absolu (`$PWD/...`) garantit la reconnaissance
     du processus par la commande d'arrêt sans divergence de chemin relatif.

4. **Phase `inject-identity` :**
   - Commande : `python3 -B scripts/v3/worker-dev.py inject-identity --config <config> --intention-file <fichier_intention>`
   - Options : `--config` (Path), `--intention-file` (Path).
   - Format de l'intention (fichier régulier, permissions POSIX strictes 0600) :
     ```properties
     request-id=<UUID>
     created-at=<ISO-8601 UTC instant, e.g. 2026-10-31T20:00:00Z>
     operation=REGISTRATION|LINK|UNLINK
     requester.issuer=<issuer>
     requester.subject=<subject>
     target.issuer=<issuer>
     target.subject=<subject>
     binding-token=<token>
     ```
     (`binding-token` obligatoire pour LINK et UNLINK ; absent ou omis pour REGISTRATION. Pour REGISTRATION,
     `requester` et `target` doivent être strictement identiques).
   - Préconditions : variables `POCOMA_I5_INJECTOR_ROLE` et `POCOMA_I5_INJECTOR_PASSWORD`.
   - Restitution : stdout `L3_DEV_IDENTITY_ADMITTED request=<id> status=PENDING origin=CONTROLLED_INTERNAL`. Code 0 (succès), 2 (refus).

5. **Phase `verify-identity` :**
   - Commande : `python3 -B scripts/v3/worker-dev.py verify-identity --config <config> --request-id <UUID> --await-seconds 90`
   - Options : `--config`, `--request-id`, `--await-seconds` (défaut 90).
   - **Constat statique du CLI public :** le CLI python n'expose pas d'option `--expected` (paramètre
     interne Java). `verify-identity` attend l'apparition d'un résultat terminal dans
     `v3_identity_request_result`, vérifie que `observation.observe(id)` renvoie `COMPLETED`
     et que le slot dans `consumption_slot` (contrat `'2999e75c-272d-5afe-a966-c9364ef021fe'`)
     est à 0.
   - Restitution : stdout `L3_DEV_IDENTITY_VERIFIED request=<id> result={outcome=..., target_eui_id=..., target_version=..., rejection=...}`. Code 0 (succès), 2 (délai dépassé ou incohérence).
   - **Conséquence :** le code de rejet exact (`rejection`) et l'absence totale de mutation métier
     sont formellement validés par les assertions SQL indépendantes décrites ci-après.

6. **Phase `identity-token` :**
   - Commande : `python3 -B scripts/v3/worker-dev.py identity-token --config <config> --own-key-file <key_file> --token-output <token_file>`
   - Format de `own-key-file` (0600) : `issuer=<issuer>\nsubject=<subject>\n`.
   - Restitution : fichier `token-output` créé en permissions 0600 contenant le token courant (issu de `v3_eui_authority` join `v3_eui_version`) ; stdout `L3_DEV_CONFIDENTIAL_OWN_TOKEN_WRITTEN`. Code 0 (succès), 2 (refus).

7. **Phase `stop` et drain :**
   - Commande : `python3 -B scripts/v3/worker-dev.py stop --config <config> --pid <PID> --log-file <log_worker>`
   - Options : `--config`, `--pid` (obligatoire), `--log-file` (obligatoire pour la qualification du drain).
   - Comportement : envoi de SIGTERM, attente jusqu'à 105s, recherche du marqueur physique
     `I5_WORKER_STOPPED pid=<PID>` dans `log-file` après l'envoi du signal.
   - Codes de sortie :
     * 0 (`I5_DEV_STOPPED`) : callback clean observé après drain physique complet.
     * 3 (`I5_DEV_STOP_INCONCLUSIVE`) : processus terminé mais callback non observé dans le journal.
     * 2 (`I5_DEV_CRASH`) : délai dépassé sans arrêt, envoi forcé de SIGKILL.

#### B. Contrôle statique du schéma et du DDL PostgreSQL

Toutes les relations, colonnes, contraintes et identifiants utilisés ont été vérifiés
contre les migrations `V5__consumption_foundation.sql` et `V6__identity_authority_and_requests.sql` :

- **Tables et colonnes vérifiées :**
  * `v3_pocoma_user` (`user_id`, `created_request_id`, `writer_xid`) ;
  * `v3_eui_authority` (`eui_id`, `issuer`, `subject`, `current_version`) ;
  * `v3_eui_version` (`eui_id`, `version`, `user_id`, `binding_token`, `request_id`, `writer_xid`) ;
  * `v3_identity_request` (`request_id`, `requester_issuer`, `requester_subject`, `operation`, `binding_token`, `target_issuer`, `target_subject`, `admission_context`, `created_at`, `status`) ;
  * `v3_identity_request_result` (`request_id`, `outcome`, `target_eui_id`, `target_version`, `rejection`) ;
  * `v3_identity_event` (`event_id`, `subject_type`, `subject_id`, `subject_version`, `request_id`, `recorded_at`) ;
  * `v3_identity_completion_event` (`event_id`, `subject_type`, `subject_id`, `recorded_at`) ;
  * `consumption_routing_contract` et `consumption_slot` (`consumption_id`, `consumable_id`, `partition_id`, `state`) ;
  * `v3_event_counter` (`family`, `position`) et `v3_event_position` (`family`, `position`, `event_id`).
- **UUID de contrat de routage :** `'2999e75c-272d-5afe-a966-c9364ef021fe'` (`IDENTITY_REQUEST` dans `WRITE`).
- **Familles d'événements :** `IDENTITY` pour les mutations métier, `IDENTITY_COMPLETION` pour les complétions.
- **États et issues normatifs :**
  * Status : `PENDING`, `TERMINAL`.
  * Outcome : `CHANGED`, `REJECTED`.
  * Motifs de rejet : `'INVALID_BINDING'`, `'TARGET_ALREADY_BOUND'`, `'TARGET_NOT_BOUND'`, `'TARGET_BOUND_TO_ANOTHER_USER'`.

#### C. Plan d'exécution des 7 demandes et oracles SQL exploitables

| Ordre / ID de reçu | Intention contrôlée | Préconditions à constater | Oracle attendu |
|---|---|---|---|
| Setup R1 | Registration A, inconnue | A absente d'autorité | CHANGED, User UA, A liée v1 ; extraire token A via helper DEV |
| Setup R2 | Link A→B, inconnue, token A | A liée à UA | CHANGED, B liée v1 à UA, sans nouveau User |
| Setup R3 | Unlink A→B, token A | A/B même UA | CHANGED, B libre v2, token nul ; UA inchangé |
| Setup R4 | Registration C, inconnue | C absente d'autorité | CHANGED, nouveau UC distinct de UA, C liée v1 |
| Q-UFREE / R5 | Unlink A→B, token A courant | B libre v2 ; A encore liée UA | REJECTED / `TARGET_NOT_BOUND` ; zéro mutation métier |
| Q-UOTHER / R6 | Unlink A→C, token A courant | C liée UC ; UC≠UA | REJECTED / `TARGET_BOUND_TO_ANOTHER_USER` ; zéro mutation métier |
| Q-REGNEW / R7 | Registration A, nouvel UUID post-succès R1 | A liée v1 ; R1 déjà TERMINAL/CHANGED | REJECTED / `TARGET_ALREADY_BOUND` ; aucun nouveau User, token ou version |

**Connexion d'observation SQL indépendante obligatoire :** session `psql -X -v ON_ERROR_STOP=1`
distincte de la JVM, avec rôle d'observation autorisé et search_path explicite.

##### 1. Oracle d'immutabilité absolue du domaine métier (avant et après chaque probe)

Avant l'injection de chaque probe (R5, R6, R7) et après retour de `verify-identity`,
exécuter la requête suivante sous transaction REPEATABLE READ READ ONLY pour capturer
l'état et l'empreinte compacte du domaine :

```sql
begin isolation level repeatable read read only;
select 'users' as relation, count(*) as n,
       md5(coalesce(string_agg(row_to_json(t)::text,E'\n' order by user_id),'')) as fingerprint
from v3_pocoma_user t
union all
select 'anchors',count(*),
       md5(coalesce(string_agg(row_to_json(t)::text,E'\n' order by eui_id),''))
from v3_eui_authority t
union all
select 'history',count(*),
       md5(coalesce(string_agg(row_to_json(t)::text,E'\n' order by eui_id,version),''))
from v3_eui_version t
union all
select 'mutations',count(*),
       md5(coalesce(string_agg(row_to_json(t)::text,E'\n' order by event_id),''))
from v3_identity_event t;
select family, position from v3_event_counter order by family;
commit;
```

**Assertion d'immutabilité :**
- Les 4 paires `(n, fingerprint)` de `users`, `anchors`, `history`, `mutations` doivent être
  **strictement identiques** avant et après la probe (démontrant l'absence de nouveau User,
  l'absence de nouvelle version EUI, l'absence de mutation d'ancre, et l'absence de nouvel événement de mutation).
- Le compteur `IDENTITY` doit rester **strictement inchangé**.
- Le compteur `IDENTITY_COMPLETION` doit progresser de **exactement +1**.

##### 2. Oracle d'assertion booléenne pour chaque probe rejetée

Pour chaque probe (`rid` = UUID de la demande, `expected_rejection` = motif attendu),
exécuter la requête d'assertion unifiée :

```sql
begin isolation level repeatable read read only;
select
    count(*) = 1 as exact_rejection_record,
    bool_and(
        q.status = 'TERMINAL'
        and r.outcome = 'REJECTED'
        and r.rejection = :'expected_rejection'
        and r.target_eui_id is null
        and r.target_version is null
        and (select count(*) from v3_identity_event e where e.request_id = q.request_id) = 0
        and (select count(*) from v3_identity_completion_event c where c.subject_id = q.request_id) = 1
        and (select count(*) from consumption_slot s
             where s.consumption_id = '2999e75c-272d-5afe-a966-c9364ef021fe'
               and s.consumable_id = q.request_id) = 0
        and (select count(*) from v3_event_position p
             join v3_identity_completion_event c using(event_id)
             where c.subject_id = q.request_id and p.family = 'IDENTITY_COMPLETION') = 1
    ) as probe_isolation_and_completion_pass
from v3_identity_request q
join v3_identity_request_result r using(request_id)
where q.request_id = :'rid'::uuid;

select
    c.family,
    c.position = count(p.event_id)
      and (c.position = 0 or (min(p.position) = 1 and max(p.position) = c.position)) as is_dense
from v3_event_counter c
left join v3_event_position p on p.family = c.family
where c.family in ('IDENTITY', 'IDENTITY_COMPLETION')
group by c.family, c.position
order by c.family;
commit;
```

**Critères décisifs de succès par probe :**
- `exact_rejection_record` = `true` ;
- `probe_isolation_and_completion_pass` = `true` ;
- `is_dense` = `true` pour les deux familles `IDENTITY` et `IDENTITY_COMPLETION`.

##### 3. Oracle d'assertion cumulatif final après l'ensemble des 7 demandes

Après exécution des demandes R1 à R7, exécuter l'assertion globale sur l'état complet du banc :

```sql
begin isolation level repeatable read read only;
select
    (select count(*) from v3_pocoma_user) = 2 as users_count_ok,
    (select count(*) from v3_eui_authority) = 3 as anchors_count_ok,
    (select count(*) from v3_eui_version) = 4 as versions_count_ok,
    (select count(*) from v3_identity_request where status = 'TERMINAL') = 7 as terminal_requests_ok,
    (select count(*) from v3_identity_request_result where outcome = 'CHANGED') = 4 as changed_results_ok,
    (select count(*) from v3_identity_request_result where outcome = 'REJECTED') = 3 as rejected_results_ok,
    (select count(*) from v3_identity_event) = 6 as identity_events_ok,
    (select count(*) from v3_identity_completion_event) = 7 as completion_events_ok,
    (select count(*) from consumption_slot where consumption_id = '2999e75c-272d-5afe-a966-c9364ef021fe') = 0 as zero_slots_ok,
    (select position from v3_event_counter where family = 'IDENTITY') = 6 as counter_identity_ok,
    (select position from v3_event_counter where family = 'IDENTITY_COMPLETION') = 7 as counter_completion_ok;
commit;
```

Toutes les colonnes booléennes doivent évaluer à `true`.

#### D. Qualification des arrêts et verdicts de la campagne Q-ID

- **PASS Q-ID :**
  1. Les 4 demandes de setup (R1–R4) et les 3 probes (R5–R7) atteignent l'état TERMINAL sous 90s ;
  2. Toutes les assertions booléennes des requêtes oracles ci-dessus évaluent à `true` ;
  3. Les empreintes du domaine métier avant/après chaque probe sont rigoureusement identiques ;
  4. L'arrêt final du Worker via `worker-dev.py stop` avec `--log-file` retourne le code 0 avec le callback `I5_WORKER_STOPPED` confirmé.
- **FAIL :**
  Toute violation d'oracle métier :
  * Mutation constatée sur une probe de rejet (nouvel utilisateur, nouvelle version EUI, nouvel événement de mutation, altération d'empreinte) ;
  * Motif de rejet différent du motif attendu ou issue non REJECTED ;
  * Présence de cibles non nulles sur un résultat rejeté ;
  * Absence ou duplication d'événement de complétion ;
  * Présence d'un slot résiduel non consommé ;
  * Rupture de densité dans les positions d'événements.
  Un verdict FAIL interdit toute clôture et impose un plan correctif (§9).
- **INCONCLUSIVE :**
  Tout échec d'infrastructure ou d'observation ne constituant pas une violation d'oracle métier :
  * Refus de connexion, droits insuffisants ou configuration divergente de PostgreSQL ;
  * Jar ou source non attribuable ;
  * Dépassement du watchdog de 90s sans état terminal ;
  * Arrêt incomplet du Worker (code 3 `I5_DEV_STOP_INCONCLUSIVE`).
  Un verdict INCONCLUSIVE ne permet ni de valider la qualification ni de réitérer sans diagnostic formel.

### 10.3 Exécutions non proposées par défaut

Pas de nouvelle FAST, CHANGE, INTEGRATION Identity, Architecture bornée,
contrôles Python de sélection, démo R-ID-POT déjà identique, Bruno/k6 ni
QUARANTINE r05/r12. Outillage/recettes inchangés ; anciennes méthodes pertinentes
déjà attribuées à E4 ; mêmes r05/r12 ne deviendraient pas résolus par une relance.
Une découverte JUnit non qualifiée n'impose pas la réécriture du collecteur I5.

Si le GO exige une **nouvelle gate normative fraîche**, la décision doit en
expliquer la raison indépendante (nouvelle hypothèse, code/configuration changé,
ou exigence de fraîcheur retenue), et accepter les coûts/échecs transitifs connus.
Commandes canoniques, depuis `app/`, sans sélection de méthode :

```sh
./mvnw -pl runtime-command-consumption-worker,runtime-monolith -am test
./mvnw -pl architecture-tests -am test
# Uniquement si le déclencheur FULL du §11 est retenu :
./mvnw test
```

Ajouter seulement les options de provenance validées `-Pverification-provenance`,
`-Dpocoma.verification.id=<UUID_neuf>`,
`-Dpocoma.verification.reports=<dossier_absolu_neuf>/reports`, avec effective POM,
inventaire attendu et producteur isolé. Les collecteurs I4 conservés montrent la
procédure de gate native et ses limites ; les réutiliser aux octets inchangés
ne supprime aucune limite et leurs libellés historiques I4 restent identifiés.
Les méthodes sélectionnées sont **toutes celles découvertes par Maven**, dont
r05/r12 ; 51 modules COMMAND+Monolithe /60 Architecture observés I4, liste exacte
dans les deux `plan.json.gz:reactor_modules`, à confronter au modèle de l'exécution.
La fermeture FULL, si requise, doit provenir du modèle non filtré, sans inventaire
partiel présenté comme complet. PG historique transitive jusqu'à V31 peut être
exécutée ; ne pas annoncer CURRENT-only pour ces gates. Pas de migrations
historiques séparées par défaut, ni baseline TRUSTED présumée.

Coût observé I4 : 53,52min et54,03min, hors préparation/archivage ; coût FULL non
budgété comme substitut. PASS canonique uniquement si code sortie0, inventaire
attendu non vide entièrement découvert/attribué, chaque invocation fraîche
exactement une fois, zéro failure/error/skip et variantes requises réellement
prouvées. Failure/error/code non nul = **FAIL** ; découverte/attribution manquante,
skip ou oracle incomplet sans failure = **INCONCLUSIVE**, pas PASS. Le collecteur
actuel laisse déjà des limites : aucun succès global promis. Une récidive
historique reste FAIL ; ne pas lancer une deuxième tentative inchangée. Une
gate obligatoire non satisfaite renvoie à la décision de recevabilité §11,
jamais à une exclusion silencieuse.

## 11. Gates, frontières, full reactor et ARB-I5-01

### 11.1 Cadrage normatif et articulation des exigences (Résolution R-I5-01)

L'instruction de la qualification et de la clôture de l'étape L3/I5 exige une
distinction rigoureuse et permanente entre quatre plans conceptuels :

1. **L'obligation normative d'exécuter et de satisfaire les gates :**
   Le plan L3 §8.2 maintient les gates canoniques `COMMAND + Monolithe` et
   `Architecture` au statut `REQUIRED`. La politique Reactor (§§17, 34–35)
   définit leurs commandes canoniques non filtrées et interdit toute substitution
   d'une gate canonique par une sélection filtrée de tests.
2. **Les résultats techniques effectivement obtenus :**
   Les gates canoniques I4 réemployées demeurent en échec technique avéré :
   `COMMAND + Monolithe` est **FAIL** (1 789 tests, 1 failure sur `i5_r12`, 1 skip) ;
   `Architecture` est **FAIL** (1 892 tests, 2 failures sur `i5_r05` et `i5_r12`, 1 skip ;
   le module `architecture-tests` n'a pas été exécuté suite à l'échec amont dans le Worker).
   La campagne Architecture bornée (163 tests PASS) est une contribution locale utile,
   mais ne satisfait ni ne remplace la gate canonique globale.
3. **La possibilité d'une acceptation humaine documentaire exceptionnelle avec réserves :**
   Conformément à SPEC-10 §1 et à la politique Reactor §34 (« no automatic FULL for
   every small lot or documentary human acceptance »), l'autorité de programme
   peut décider nominativement d'instruire une clôture documentaire bornée malgré
   des gates canoniques non acquises, en consignant explicitement cette dette technique.
4. **La certification technique globale ultérieure :**
   La certification V3 globale (milestones G1/G2/G3, SPEC-10 intégrale, niveau
   CERTIFICATION du §35 avec full reactor non filtré `./mvnw test`) relève des étapes
   d'intégration majeure, de convergence (L6) ou de release (L8). Elle ne peut en
   aucun cas être revendiquée ou préemptée par une clôture interne bornée d'un sous-lot.

**Articulation normative des §§34 et 35 de la politique Reactor :**
Le §34 n'annule ni ne supplante le §35. Le §34 encadre la proportionnalité de la
vérification et autorise le réemploi traçable d'observations pour des acceptations
documentaires ou des clôtures de lots internes bornés, sans imposer systématiquement
un full reactor. Le §35 régit les niveaux opérationnels (FAST, CHANGE, INTEGRATION,
CERTIFICATION). Le niveau CERTIFICATION prescrit le full reactor pour les jalons
d'intégration majeure ou de release. L'arbitrage de programme retient ici la voie
d'une clôture humaine interne documentaire bornée (§34 et plan L3 §8.2), sans
confondre ce choix avec un verdict CERTIFICATION (§35). Les gates FAIL ne sont pas
déclarées satisfaites, et le full reactor n'est pas « globalement dispensé » : il
est simplement non requis par le mandat documentaire borné actuel.

### 11.2 Décision humaine ARB-I5-01 ratifiée (31 octobre 2026)

Le responsable du programme Pocoma V3 a rendu et ratifié la décision suivante :

#### Clause A — Nature du jalon

L3/I5 est formellement qualifié comme une **clôture humaine interne bornée du raccord
métier Identity**, et **non comme une certification technique globale V3**.

Le périmètre considéré est strictement celui des capacités WRITE Identity
(Registration, Link, Unlink, autorité EUI, tokens, atomicité, publication) et de leur
intégration effective dans les deux racines Worker et Monolithe.

Il n'inclut pas :
- L'intégration dans la branche `main` ;
- Une nouvelle baseline globale certifiée ;
- Une restructuration majeure des modules Maven ;
- La distribution ou l'activation de production ;
- Les fonctionnalités READ et HTTP futures (différées à L4/L5) ;
- La qualification du consentement externe (différée à L5) ;
- La certification complète SPEC-10.

Les critères de certification des jalons ultérieurs demeurent pleinement applicables.

#### Clause B — Recevabilité exceptionnelle avec gates non acquises

Le responsable du programme autorise nominativement la préparation d'une acceptation
humaine interne de clôture L3 avec les gates canoniques `COMMAND + Monolithe` et
`Architecture` demeurant **FAIL**.

Les résultats historiques observés au code gelé restent strictement inchangés :
- `COMMAND + Monolithe` : **FAIL**, 1 789 tests, 1 failure sur `i5_r12`, 1 skip.
- `Architecture` : **FAIL**, 1 892 tests, 2 failures sur `i5_r05` et `i5_r12`, 1 skip ;
  module Architecture non exécuté dans cette gate suite à l'échec amont.
- `Architecture bornée` : **163 tests PASS**, sans substitution à la gate canonique.

L'exigence Architecture demeure **REQUIRED** dans le plan L3.

Cette décision constitue une **exception humaine explicite de recevabilité de la
clôture documentaire**, et non :
- Un PASS des gates ;
- Une dispense générale ou permanente ;
- Une modification rétroactive de la politique Reactor ;
- Une suppression de tests obligatoires ;
- Une certification de non-régression globale ;
- Une preuve de disponibilité générale du Worker ;
- Un précédent automatiquement applicable aux lots suivants.

Une gate obligatoire non satisfaite demeure identifiée comme telle. La décision
humaine accepte que la qualification interne bornée soit instruite malgré cette
dette technique, sans prétendre la résoudre à ce stade. La clôture finale L3 reste
conditionnée aux preuves complémentaires de la campagne Q-ID, à l'audit contradictoire
indépendant et à une nouvelle décision humaine explicite.

#### Clause C — Réserves et dette technique

Les réserves suivantes restent formellement ouvertes :
- Échecs temporels `i5_r05` et `i5_r12` ;
- Réserves historiques L2 : R-L2-01 à R-L2-05 ;
- Réserves historiques I3 : R1 à R4 (composition JPA seule, confiance admin/PostgreSQL, logs qualifiés) ;
- Absence de PASS canonique sur les gates COMMAND et Architecture ;
- Absence de full reactor final qualifiant L3 ;
- Limites de découverte (46 et 67) et d'attribution nominative des tests ;
- Milestones globaux G1/G2 INCONCLUSIVE et G3 non franchi ;
- Certification globale SPEC-10 non acquise ;
- Parcours externes, READ (L4), HTTP (L5), consentement (L5), performances et production non certifiés.

Ces garanties temporelles et gates globales devront être réexaminées et satisfaites
lorsqu'un jalon ultérieur les exigera expressément, notamment les étapes de
convergence (L6), d'intégration majeure ou de release (L8). Aucune exemption
permanente n'est accordée. Si un défaut nouveau de sûreté Identity est démontré,
cet arbitrage interdit formellement de l'accepter silencieusement.

#### Clause D — Portée de l'autorisation

ARB-I5-01 est ratifié pour le cadrage et la finalisation documentaire du plan I5.

Il autorise la finalisation documentaire du présent document.
Il ne constitue pas encore :
- Un GO d'exécution de la campagne Q-ID ;
- Un GO de développement correctif ;
- Un GO de certification globale ;
- Une acceptation finale L3 ;
- Un GO de mise en production.

Si cette décision venait à entrer en contradiction irréductible avec une exigence
normative applicable, la contradiction doit être identifiée précisément avant toute
modification, sans réécriture silencieuse de la norme.

### 11.3 Déclencheurs d'escalade et niveaux de vérification

Le full reactor (`./mvnw test`) demeure exigé lors des jalons majeurs :
clôture de Wave majeure, intégration dans `main`, restructuration large de
dépendances Maven, refonte d'infrastructure transverse non bornable ou décision
explicite d'établissement d'une baseline globale (§§19, 21, 35).

Aucun de ces déclencheurs n'est présent dans le périmètre du présent jalon borné.
L'échec de disponibilité L2 ne constitue pas un déclencheur automatique de FULL (§20).
L'exigence Architecture globale reste REQUIRED au plan L3 et figure au registre
de dette technique ouverte consigné par ARB-I5-01.

## 12. Une campagne, quatre phases après audit et GO

### Phase 1 — Consolidation sans exécution applicative

Recontrôler Git/distant/worktrees, autorités et égalité des entrées. Refaire les
contrôles d'archives de §7 en lecture seule ; conserver leurs reçus dans le futur
dossier I5 sans réécrire les dossiers amont. Verser la matrice O01–O27 et I01-A…Q
dans le manifeste final avec XML/méthodes/membres/lignes et résultats originaux.
Séparer les empreintes vérifiées, l'analyse des assertions et les résultats
expérimentaux ; chacun a sa provenance. Confirmer les seules trois probes et
la décision ARB-I5-01 déjà consignée. **Sortie : aucun delta exécutable ni lacune
nouvelle non allouée ; sinon arrêter le scope dépendant.**

### Phase 2 — Qualification complémentaire ciblée

Exécuter une fois Q-ID §10.2 sur cible neuve, avec le build seulement nécessaire,
environnement/empreintes et oracles fixés avant stimuli. Capturer les sept
demandes, trois reçus probatoires, snapshots privés et export public expurgé.
Ne pas relancer E4, X4 ou les campagnes temporelles déjà inchangées. Si toutes
les probes sont recevables, fermer uniquement les lacunes O06/I/P dans le dossier
I5 ; ID-01 global reste incomplet. **Tout défaut productif confirmé ou besoin de
test/code arrête avant correction, selon §9.**

### Phase 3 — Évaluation des gates et réserves

Appliquer la voie choisie par ARB-I5-01 : dans la recommandation bornée, conserver
N4-C/N4-A FAIL, X4 PASS borné, FULL NOT RUN, CERTIFICATION globale NOT RUN et les
réserves §14. Aucune réévaluation de verdict historique. Si une gate fraîche a
été expressément imposée pour une raison §10.3, l'exécuter une fois avec ses
propres reçus, sans transfert de compteurs/PASS, ni nouvelle tentative inchangée.
La seule réussite des probes ne résout pas un rejet de recevabilité canonique.
**Sortie : verdicts bornés/canoniques distincts et décision proposée cohérente
avec l'arbitrage, ou dossier INCONCLUSIVE/FAIL sans clôture.**

### Phase 4 — Audit indépendant et clôture documentaire

Compléter ensemble les quatre livrables §15 et les mises à jour factuelles
strictement nécessaires. Fournir un dossier gelé autoporteur à un auditeur
indépendant : sources/méthodes, oracles, preuves, réserves, ARB-I5-01 et diff.
Traiter ses observations documentaires dans le même dossier ; un défaut bloquant
de comportement renvoie au §9. Une décision humaine L3 distincte peut ensuite
accepter, refuser ou ajourner la clôture dans le périmètre ratifié. Publier
**uniquement après autorisation explicite**, contrôle du diff/index/distant et
de l'équivalence testé→publié. Les quatre phases sont l'organisation d'une seule
campagne, pas quatre sous-livraisons ni quatre nouveaux GO techniques.

Le GO de campagne peut autoriser la préparation locale complète et l'audit sans
autoriser commit/push ; la publication requiert alors son GO final. Aucun livrable
`Closure` ne porte une acceptation humaine tant qu'elle n'a pas été reçue et
consignée. Aucun GO L4 n'est déduit de la seule réalisation technique d'I5.

## 13. Arrêts et verdicts possibles de la future campagne

| Situation | Décision opératoire / verdict |
|---|---|
| Même baseline/hypothèses ; trois probes PASS, dossier complet, ARB ratifié | **QUALIFICATION INTERNE DÉMONTRÉE AVEC RÉSERVES — AUDIT PENDING** ; pas PASS canonique/global, décision humaine encore due |
| Probes métier violées | **FAIL — CORRECTIVE PLAN REQUIRED**, invariant/preuve/cause/fichiers/slices ; aucun correctif autonome |
| Preuve inaccessible, ambiguë, environnement divergent, jar/source non attribuable, observation/drain non qualifié | **INCONCLUSIVE**, périmètre précis ; ne pas convertir en succès ni réessayer aveuglément |
| Arbitrage révoqué/contesté, gate obligatoire non satisfaite sans voie de recevabilité ratifiée | **NORMATIVE ARBITRATION REQUIRED / NO CLOSURE** |
| Échec canonique historique réobservé lors d'une gate fraîche requise | **FAIL de cette gate**, diagnostic conservé ; pas arrêt transformé en PASS borné ; appliquer la décision écrite ou retourner au responsable |
| Découverte de frontière imprévue, changement SQL/POM/manager/logging nécessaire | Arrêt dépendant, plan compact de redéclaration et GO correctif ; pas de développement L4–L8 |

La qualification bornée n'autorise jamais une affirmation de non-régression
globale. L'absence de nouveau défaut établi dans les preuves examinées n'est
pas une démonstration universelle d'absence de défaut.

## 14. Réserves à conserver et caractère bloquant

| Réserve | Traitement pour une acceptation interne proposée | Ce qui bloque |
|---|---|---|
| R-L2-01 temporel : AUD-I5-01, R-AUD-I3-03, FAIL v1/v2/final, 30s non certifié, pas PASS90s global | Conservable sous ARB, aucune promesse temporelle nouvelle | Exigence d'un certificat temporel/global, ou défaut Identity nouveau dépendant de cette garantie |
| R-L2-02 disponibilité/reprise | Suspension/retrait et drain conservateur acceptés historiquement ; pas délai universel de reprise/réacquisition | Mutation sous token invalide, capacité restituée avant résolution, arrêt faussement qualifié ; un drain Q-ID non qualifié interdit PASS de campagne |
| R-L2-03 global : G1/G2 INCONCLUSIVE, G3 NOT PASSED/NOT COMPLETED, cinq NOT RUN/allocations incomplètes, AUD-I5-02 local m03 sans gate corrigée finale | Inchangée ; contributions Identity reçues nominativement, sans réécrire les allocations L2 | Revendiquer les gates globales ou G3 acquis |
| R-L2-04 fonctionnel | Conserver la portée historique : L2 n'avait pas Identity réelle ; I3/I4 la prouvent dans leurs dossiers, READ/HTTP/Invitation/SLO restent différés | Assimiler fixtures L2 ou probes READ à ces fonctionnalités livrées |
| R-L2-05 déploiement | Aucun GO production/certificat release ; DEV seulement | Demande d'activation/release sans qualification distincte |
| I3 R1 composition | JPA livré dans les deux racines seulement ; JDBC manuel avec holder prélié hors certification | Substitution du manager ou perte du refus avant callback sans requalification |
| I3 R2 confiance PostgreSQL | Profil DEV fermé, admin/propriétaires/provisionneurs de confiance, rôles auxiliaires certifiés administrativement ; pas surveillance continue ACL | Hypothèse changée, grant/objet indirect excessif ou mutation destructive non protégée sur canal qualifié |
| I3 R3 logs | Serveur PG/journaux Docker qualifiés ; pas APM/audit/collecteurs externes/production | Fuite nouvelle sur canal qualifié ou nouveau canal présenté comme certifié |
| I3 R4, r05/r12 | FAIL/réserves explicitement conservés ; pas faux positif ni test-only affirmé | Gate obligatoire exigée comme PASS sans résolution ou arbitrage de recevabilité |
| Architecture canonique non acquise, COMMAND+Monolithe FAIL | Dette canonique ouverte sous ARB-I5-01 ratifié ; jamais S, bloque toute prétention de PASS canonique ou certification globale | Rejet de l'exception ou exigence fraîche non satisfaite |
| Full reactor final absent | Conservable seulement si jalon interne confirmé §11 ; NOT RUN explicite | Déclencheur majeur/main/CERTIFICATION/global retenu |
| Découverte/attribution 46/67, skip exploratoire, revue classe secondaire | Conservables pour les contributions nominatives prouvées ; pas complétude canonique | Méthode nécessaire non attribuée ou obligation interne encore ND |
| H3 reçu public expurgé, audit I3 bruts indépendants non fournis, avis I4 rapporté | Limites de provenance conservées ; E4 apporte sa preuve propre du cœur exécuté | Nouvelle affirmation dépendant uniquement de bruts indisponibles |
| READ/HTTP, I01-Q, ID-02/C-01, serveur d'autorisation externe, SEC-01/02 globaux | OUT L3, reports L4/L5 explicites avec méthodes/oracles futurs selon SPEC | Revendication ID-01/ID-02 global PASS ou consentement externe validé par fixture |
| SPEC-10 globale, REC/OPS/PERF/convergence/distribution L6–L8 | Aucun certificat, aucun GO production | Clôture globale V3 prétendue sans protocoles correspondants |
| Trois sous-cas internes O06 et dossier/audit/décision O24/O25 | Non conservables comme s'ils étaient prouvés ; les produire/obtenir dans la campagne | Preuve incomplète, FAIL non résolu, audit/décision absents |

Aucune décision L2 ou I1–I4 n'est modifiée rétrospectivement. La possibilité
humaine d'accepter un risque de disponibilité ne permet pas d'accepter une
violation non résolue d'atomicité, fencing, unicité ou confidentialité dans le
périmètre que l'on déclare démontré.

## 15. Dossier documentaire final attendu

À produire **complet dans la future campagne**, jamais comme fichiers vides
pendant ce cadrage :

| Livrable | Contenu et critère de recevabilité |
|---|---|
| `docs/v3/L3_Identity_Execution_Report.md` | Baselines initiale/testée/publiée et équivalences ; O01–O27/I01-A…Q avec issue et preuve ; SHA E4/H3 vs nouvelles observations ; scénarios/commandes réellement exécutés, refus/FAIL/INCONCLUSIVE inclus ; slices/secondaires/franchissements ; PG/migrations/versions/configuration non secrète ; gates REQUIRED/RUN/FAIL ou NOT RUN motivé ; full/CERTIFICATION ; ARB-I5-01 ; pas de décision d'audit autodéclarée |
| `docs/v3/L3_Identity_Runtime_Runbook.md` | Reprise autonome depuis environnement neuf ; configuration real Identity seule et Identity+Command I4 (limites/partitions exactes), deux racines/owner JPA ; préparation admin V1–V6/READ technique V1, profils/grants/logging/préflight ; injection privée, token propre DEV confidentiel, résultats, arrêt/restart sans prepare ; diagnostic Q/UNKNOWN/drain conservateur ; A1 et R1–R3 ; aucune API WRITE publique de substitution ; pointeurs E4 pour R-ID-POT, Q-ID pour compléments ; aucun démarrage déclaré rejoué sans reçu |
| `docs/v3/L3_Identity_Proof_Manifest.json` | JSON valide ; IDs Oxx/I01-X/sous-cas ; source normative ; méthode complète ou commande opérateur ; oracle/assertion/source ; module/producteur/invocation ; SHA testé, réemploi ou frais ; URI locale ou stockage Git récupérable, archive/membre/ligne, bytes/SHA-256 ; ancien→nouveau hypothèses/app/POM/SQL/config/outil/PG ; compteurs, statut et limites ; 64 reçus I4 référencés sans duplication ; nouvelles probes ; gates/ARB et reports nominatifs L4–L8 ; aucune somme trompeuse de campagnes cumulatives |
| `docs/v3/L3_Identity_Closure.md` | Dossier examiné, avis d'audit indépendant original ou provenance/limites si transmis, réponses aux findings ; décision humaine explicite datée et périmètre ; gates canoniques exactes ; ARB-I5-01 et réserves inchangées ; transferts L4/L5/L6–L8 ; absence ID-01/ID-02/SPEC-10 globale et GO production ; refus/ajournement si nécessaire. Avant décision : statut PENDING, jamais ACCEPTED anticipé |

Preuves nouvelles sous un dossier I5 clairement attribué, reçus compacts et
bruts nécessaires compressés sans perte suivant la
[politique de conservation](../testing/Certification_Evidence_Rationalization.md).
Aucun secret ni fichier privé d'intention/configuration versionné. Stockage
externe non supposé ; si une archive est conservée via Git, fournir stockage
et SHA testé distincts, commande de résolution/restauration et empreinte.
Un auditeur doit pouvoir retrouver les preuves sans conversation ni `/tmp`
indispensable. Les liens vers des livrables à créer ci-dessus sont volontairement
des chemins textuels prospectifs, pas des références prétendument présentes.

Mises à jour futures strictement nécessaires : plan directeur (état réel I5/L3,
suite, réserves et liens), index `docs/README.md`, encadrés de progression du canon
et du plan L3 uniquement pour pointer la décision réelle. Pas de réécriture des
clauses normatives ni des anciennes décisions/manifestes. Si ARB impose une
modification normative, elle exige son mandat séparé avant la campagne ; ne pas
l'incorporer sous couvert d'actualisation factuelle.

Avant publication : liste exacte des fichiers, diff/index, liens/ancres et chemins
de manifestes résolus, SHA-256 et restauration sans perte, absence d'exécutable
modifié, HEAD distant recontrôlé, travaux étrangers intacts ; puis commit/push
normaux **seulement selon GO explicite**. Le SHA documentaire publié n'est pas
inscrit de façon circulaire dans son propre manifeste : le reçu de livraison
fournit ce SHA après publication et le rapproche du SHA réellement testé.

## 16. Risques résiduels

Le risque principal de clôture est sémantique : appeler « qualification finale »
une certification globale que ni ID-01 ni les gates ne permettent. ARB-I5-01 et
les statuts distincts sont indispensables. Le deuxième risque est probatoire :
confondre oracle de domaine, primitives SQL, vrai Engine, contexte Spring et jar
opérateur. Les tables maintiennent ces niveaux, et les trois probes comblent
seulement leurs sous-cas propres.

Les preuves sont un état borné : mêmes sources et profil n'assurent pas toutes
charges/machines/ACL futures ; les réserves temporelles et la confiance admin
demeurent. Le dossier I4 est vérifiable mais ses identifiants de provenance ne
protègent pas contre un producteur malveillant disposant des mêmes droits.
Le reçu H3 ne remplace pas un jar R-ID-POT fraîchement exécuté ; ce jalon I4
repose ici sur ses tests de compositions réelles et leurs traces acceptées.
Le runbook final doit exprimer cette différence sans promettre une distribution L8.

Si l'audit refuse la suffisance des preuves composées d'une ligne §5, demander
l'assertion normative manquante et son effet sur le périmètre ; ajouter seulement
le contrôle minimal après décision. Ni nouvelle campagne exhaustive I1–I4 ni
création automatique de test au seul motif d'une préférence de nommage.

## 17. Conditions précises de GO et contrôle de ce plan

Avant le GO d'exécution de la campagne Q-ID :

1. Audit contradictoire indépendant du présent plan finalisé, notamment trois probes,
   suffisance composée des 14 autres variantes internes et attribution E4/H3.
2. **Arbitrage ARB-I5-01 écrit et ratifié le 31 octobre 2026 (§11) :**
   La décision humaine formelle est actée et consignée dans ce plan. Elle qualifie
   le jalon de clôture humaine documentaire bornée (§34) et autorise l'instruction
   de la clôture avec les gates canoniques demeurant FAIL et réserves ouvertes.
   Cette condition préalable est **SATISFAITE pour le cadrage et la recevabilité du plan**.
3. Autorisation formelle (GO) de la campagne Q-ID et de son build sans tests, cibles
   PostgreSQL neuves exactes et profil DEV existant ; aucun code/test/migration/POM/outillage
   modifiable. Les commandes §10 sont alors autorisées à l'exécution ; elles n'ont
   pas été exécutées pour ce mandat d'amendement documentaire.
4. Autorisation de préparer localement les quatre livrables complets (§15) et les
   mises à jour factuelles ; périmètre de publication explicite séparément.
5. Revalidation de baseline/équivalence au démarrage ; aucun défaut bloquant
   nouveau ni hypothèse de manager/privilèges/logging/PG non qualifiée.

La campagne s'exécutera alors en une passe selon §12, avec arrêt exceptionnel
uniquement sur défaut bloquant réel, preuve impossible ou nouvelle contradiction.
L'acceptation finale de L3 appartiendra à une décision humaine distincte après audit du dossier.

**Mandat d'amendement et finalisation :** seul
`docs/v3/L3_I5_Qualification_and_Closure_Plan.md` est modifié. Slices primaire et
secondaires **NONE**, impact productif **NONE**, franchissement inattendu **NONE**.
Gate globale/full **NOT REQUIRED / NOT RUN pour cet amendement documentaire** ; cette
déclaration ne dispense pas I5 de ses obligations O20–O22. DB/migrations,
Maven/Java/Docker/Bruno/k6 et tests applicatifs **NOT RUN**. Campagne Q-ID **NOT RUN**.

Commandes réellement utilisées : `pwd`, `git status`, `git log`, `git worktree list`,
`git remote -v`, `git ls-remote`, `git diff`, `git diff --check`, lectures de code et
de DDL `find`, `git grep`, inspection de scripts et de fichiers sources.
Ces contrôles statiques ne constituent aucune exécution de tests. Aucun autre
fichier du dépôt modifié.

**Recommandation :** L'arbitrage ARB-I5-01 étant consigné et ratifié, les ajustements
R-I5-01 et R-I5-02 étant intégrés avec succès, et le préflight statique confirmant
l'exécutabilité du protocole sans modification de code ou d'outillage, le plan de
qualification est finalisé, cohérent et recevable. Il est prêt pour le GO d'exécution
de la campagne Q-ID.

**I5 — QUALIFICATION PLAN APPROVED / READY FOR EXECUTION GO**

## 18. Actualisation documentaire historique du 1er novembre 2026 — avant ratification finale

Le [rapport courant](L3_Identity_Execution_Report.md), le
[manifeste](L3_Identity_Proof_Manifest.json) et le
[projet d'acte](L3_Identity_Closure.md) portent l'exécution effective Q-ID et les
campagnes canoniques postérieures au plan. Leurs résultats n'actualisent pas
rétrospectivement les observations I4 ni les clauses ratifiées d'ARB-I5-01.
Q-ID : trois probes PASS, sept demandes ; O20/O21 à `cfd3f645` : respectivement
2057/0/0/1 et 1791/0/0/1, BUILD SUCCESS, strict INCONCLUSIVE pour les deux.
Les limitations 46/67 restent historiques ; aucune résiduelle n'est rapportée
pour ces campagnes. O20 Architecture : 163 PASS ; O21 : deux ancres atteintes.

L'[analyse nominative du SKIP](L3_Identity_Execution_Report.md#qualification-nominative-du-skip--proposition-du-1er-novembre-2026)
propose OPTIONAL_EXPLORATORY_PERFORMANCE pour la seule méthode
`TerminalSequencingPostgresTest#zExploratoryContentionSeparateFromCorrectness`.
Les assertions de validité de ses mesures et les treize scénarios de correction
indépendants sont examinés, pas supprimés. Les obligations SPEC-09/PERF-01 restent
entières ; cette mesure de fixture n'en est pas un substitut.

Les §§10.3/11 et la politique §§34–35 restent applicables : aucun PASS canonique
avec SKIP. Le §34 permet l'instruction de la recevabilité interne avec réserves,
mais ne permet pas expressément de déclarer O20 satisfaite. **O20 reste NOT
DEMONSTRATED — NORMATIVE ARBITRATION REQUIRED**. O21 conserve **SATISFIED WITH
RESERVATIONS**, recevabilité interne du mandat précédent, strict INCONCLUSIVE.
La proposition humaine est limitée au SKIP nommé ; elle ne modifie pas la politique
et n'est pas ratifiée par ce mandat. Une éventuelle satisfaction O20 avec réserves
requiert une décision supplémentaire explicite, sans certification globale.

Le présent GO autorise seulement analyse, corrections documentaires, contrôles
statiques et publication sans force. Slices primaire/secondaires NONE ; impact
productif et franchissement inattendu NONE. Gate globale et full reactor NOT
REQUIRED / NOT RUN ici ; DB/migrations, Maven, tests temporels et Q-ID NOT RUN
pour cette passe. Les gates déjà archivées demeurent REQUIRED / RUN historiquement.
L3 OPEN ; O25 PENDING jusqu'à audit indépendant et décision humaine distincte ;
aucun GO L4, SPEC-10 global ou production. Les clauses de décision proposées
figurent dans le projet d'acte, sans acceptation préremplie.

## 19. Ratification finale et publication documentaire — 1er novembre 2026

Le responsable du programme a reçu l’avis favorable O25 et donné GO explicite
pour publier la clôture. L’[acte de clôture unique](L3_Identity_Closure.md) consigne
la classification nominative OPTIONAL_EXPLORATORY_PERFORMANCE, la recevabilité
interne L3/I5 du seul SKIP conditionnel de
`TerminalSequencingPostgresTest#zExploratoryContentionSeparateFromCorrectness`,
les statuts O20/O25 **SATISFIED WITH EXPLICIT RESERVATIONS**, O21 **SATISFIED WITH
RESERVATIONS** et **L3 CLOSED WITH EXPLICIT RESERVATIONS**.
Cette décision humaine distincte satisfait l’arbitrage encore demandé au §18 ;
ARB-I5-01 reste un acte de préparation historique.

Les comptes O20 2057/0/0/1 et O21 1791/0/0/1 et leurs verdicts stricts
**INCONCLUSIVE** restent inchangés. La règle zéro-SKIP, la politique Reactor,
les archives et les obligations futures restent intégrales. L’avis O25 est
transmis par le mandat : rapport original/détail des contrôles non fournis,
aucun nouveau Maven/PostgreSQL ni recalcul indépendant exhaustif des archives.
La décision accepte explicitement ces réserves de vérification indépendante.

L4 peut s’ouvrir au cadrage après publication et vérification, sans implémentation.
L’orientation « Qualification réutilisable de L2 Fondation » est à instruire dans
le [plan directeur](V3_Implementation_Master_Plan.md#orientation-à-instruire-au-cadrage-l4--qualification-réutilisable-de-l2-fondation).
Elle ne constitue aucune certification obtenue ni réduction des gates.

Portée de cette publication : slices **NONE**, impact productif/franchissement
inattendu **NONE**, Architecture globale **NOT REQUIRED**, full reactor
**NOT REQUIRED / NOT RUN**, DB/Maven/benchmark/tests temporels **NOT RUN**.
Contrôles documentaires et protocole d’empreintes :
[rapport final](L3_Identity_Execution_Report.md#publication-de-la-clôture-ratifiée--1er-novembre-2026).
