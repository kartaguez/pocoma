# Pocoma V3 — L3/I5 — Qualification finale et clôture Identity

**31 octobre 2026 — PLAN LOCAL, NON EXÉCUTÉ, À AUDITER.**

**Conclusion contradictoire : aucun développement productif indispensable identifié.**
Les créations de tests prévues historiquement pour I5 ont déjà leurs équivalents
livrés. Il reste **trois observations WRITE directes à obtenir sur deux variantes
ID-01 partiellement démontrées**, le dossier final et l'audit indépendant. Une
seule campagne interne, utilisant les outils DEV existants, suffit pour ces
observations ; aucun ajout de test Java n'est proposé.

**Blocage préalable : ARB-I5-01 (§11).** Les gates canoniques I4 sont FAIL ;
Architecture reste REQUIRED dans le plan L3. L'acceptation I4 n'autorise pas leur
dispense pour I5. La coexistence des clauses de clôture bornée (§34 de la politique)
et du niveau CERTIFICATION (§35) doit aussi être explicitée avant GO. Le présent
plan ne tranche ni ne modifie ces autorités. **L3 reste OPEN. Aucun GO d'exécution,
de publication ou de production n'est prononcé.**

## 1. Baseline Git vérifiée

Lectures effectuées dans `/Users/julien.guezennec/Dev/projects/pocoma`, dépôt
`kartaguez/pocoma`, branche `feat/v3-architecture` :

| Référence | Constat du présent examen |
|---|---|
| HEAD distant réel, obtenu par `git ls-remote origin refs/heads/feat/v3-architecture` | `af5416706c27be52722b480d2f27cfb50570a253` |
| HEAD local et remote-tracking local | Même SHA ; divergence locale `0/0` |
| Code testé I4 | `145f42731a4e35488e9aaa56a7de3c3f453a9231` |
| Livraison I4 | `126dc10ef6ac3fc304ad098b5d9dc85a24f40f17` |
| Acceptation documentaire I4 | `af5416706c27be52722b480d2f27cfb50570a253` |
| Commits depuis le code testé | Ces deux seuls commits, exclusivement documentaires/preuves ; aucun commit après l'acceptation I4 |
| Arbre Git `app/`, testé et courant | `0b6fb8865600e853b859136187af7afb0de9700a` dans les deux cas |
| Arbre Git `scripts/`, testé et courant | `2e1644e0f5d8c5a0a844d2a306719f857ba6c333` dans les deux cas |
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
| O20 | Plan L3 §6/I5, §8.2 ; politique §§17/34/35 | Architecture globale REQUIRED | Règles existantes suffisantes ; aucune règle à modifier | 28 classes /163 tests X4 ; gate N4-A | X4 PASS borné ; N4-A FAIL, module SKIPPED | SHA I4, archives distinctes | Gate canonique non acquise ; 67 limites de découverte | ND | ARB-I5-01, aucune substitution par X4 |
| O21 | Plan L3 §8.2 ; politique §§6/34 | Gate COMMAND + Monolithe | Deux ancres existantes | N4-C, non filtrée | 1789 tests /1 failure/1 skip, FAIL | SHA I4 | r12 ; 46 limites de découverte | ND | ARB-I5-01 ; pas de relance inchangée |
| O22 | Plan L3 §8.2 ; politique §§18–21/34/35 | Full conditionnel et niveau de clôture | Aucun nouveau code/graphe | Aucune exécution full I5 | NOT RUN | Aucun SHA frais prétendu | Ambiguïté CERTIFICATION §35 ; pas de Wave majeure/main/release dans mandat | SR | Décision explicite §11 ; FULL seulement si déclencheur ratifié |
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

**Pas de clôture technique conforme déductible aujourd'hui des seules décisions
I1–I4.** Le plan L3 §8.2 exige Architecture ; la politique §§34–35 interdit de
substituer un filtrage réussi à la gate explicite. Les réserves L2 ne dispensent
pas automatiquement de cette obligation L3.

**Une acceptation humaine interne de programme avec réserves est envisageable,
mais exige un arbitrage nominatif nouveau avant GO**, conformément à la
clarification SPEC-10 §1 : choisir la voie bornée, maintenir les gates
REQUIRED/RUN/FAIL et leur dette, expliciter l'exception de recevabilité de la
clôture documentaire et sa portée. Ce n'est ni un PASS technique, ni une gate
satisfaite, ni une certification globale. Si le responsable exige la satisfaction
technique canonique préalable, la clôture reste bloquée ; une simple formule
« accepté avec réserves » ne suffit pas. Arbitrage minimal détaillé au §11.

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

### 10.1 Déclaration de portée de la future campagne bornée proposée

Impact productif attendu **NONE** ; risques examinés : Identity, autorisation,
atomicité, publication, runtime et provenance. Primaire **COMMAND** ; secondaires
canoniques **NONE** ; Monolithe = racine complémentaire **déjà prouvée/réemployée**.
BINDING V2 n'est pas requis par le mot Identity ; WEB/EVENT/PROJECTION/LKV n'ont
aucun comportement productif modifié. Architecture **REQUIRED, résultat I4 FAIL** ;
traitement suspendu à ARB-I5-01. FULL **conditionnel, non planifié dans la voie
interne proposée**, décision §11 requise. DB **CURRENT V3 neuve**, WRITE V1–V6,
READ technique V1 ; baseline TRUSTED **NONE** ; aucun historique autonome.

Niveaux : FAST/CHANGE/INTEGRATION I4 **réemployés**, pas de rejeu automatique de
65/149/187 tests ; complément fonctionnel de niveau INTEGRATION interne à petite
échelle. **CERTIFICATION globale NOT RUN**, sans utiliser ce mot pour attribuer
un certificat au dossier interne. Ce choix reste une proposition soumise à
l'arbitrage §11, pas une dérogation implicite au §35.

### 10.2 Exécution Q-ID : trois observations dans un seul runtime

Après audit et GO incluant ARB-I5-01, une seule préparation, un seul Worker,
**4 demandes de mise en état +3 probes**, en série. Les deux racines utilisent
déjà le même participant qualifié par E4 : refaire ces trois négatifs dans le
Monolithe n'ajouterait pas de frontière nouvelle. Aucune concurrence hostile
nouvelle n'est utile pour ces trois contrats séquentiels ; les courses sont E4.

**Environnement :** nouveau serveur jetable PostgreSQL17.11 avec l'image épinglée
§7, deux bases WRITE/READ distinctes et schémas non publics, rôles DEV nommés
selon le runbook I3, quatre pools/budgets inchangés et owner Business JPA livré.
Admin/injecteur/observateur identifiés, aucune activité concurrente étrangère.
Configurer le préfixe serveur SQLSTATE/PID/XID et la politique de logs livrée ;
ne pas exposer les valeurs de tokens/configurations dans le dossier public.
Le GO valide les cibles jetables exactes avant leur préparation ; aucun reset
d'une cible existante. Un refus de préparation est INCONCLUSIVE, sans réparation
automatique des ACL ni augmentation de budget.

**Build :** si un jar actuel ne dispose pas déjà d'une provenance vérifiée au
même code, compiler/packager proprement la seule fermeture COMMAND, depuis `app/` :

```sh
./mvnw -pl runtime-command-consumption-worker -am clean package -DskipTests
```

Cette commande est **un build sans tests exécutés**, aucun PASS de gate. Le clean
Maven est borné aux sorties de cette fermeture, distinct d'un clean Git interdit ;
il écarte l'hypothèse de bytecode ancien. Méthodes sélectionnées : **NONE**.
Les 50 modules de `change-final/plan.json.gz:reactor_modules` constituent la
fermeture observée de cette ancre : domaines/contrats/ports/engines, PRIMARY,
READ/projection historiques nécessaires au graphe, transaction, orchestration,
Supras, composition et Worker. Conserver la liste réellement annoncée par ce
nouveau build ; ce n'est pas une exécution des tests de tous ces modules.
Aucun install préalable, `-amd`, full, test filtré ou packaging Monolithe requis.
Si un build propre exige du code, arrêter (§9).

Depuis la racine, reprendre la configuration **Identity seule** livrée par le
[runbook I3](L3_I3_Identity_Development_Runbook.md) : real, fixture=false,
IdentityRequest=true, Command=false, technical-probes=false, IdentityRequest=4,
partitions0|1|2|3. Le fichier privé externe contient cibles/credentials exacts ;
aucune nouvelle topologie n'est qualifiée.

```sh
python3 -B scripts/v3/worker-dev.py prepare --config /private/tmp/l3-i5-private/runtime.properties
java -jar app/runtime-command-consumption-worker/target/pocoma-runtime-command-consumption-worker-0.0.1-SNAPSHOT-exec.jar --spring.profiles.active=v3-consumption-worker-dev --spring.config.additional-location=file:/private/tmp/l3-i5-private/runtime.properties
```

Le répertoire et les fichiers privés sont nouveaux, permissions0700/0600 ;
les credentials sont fournis selon le runbook, jamais en argv. Lancer la JVM
séparément pour garder les commandes d'observation disponibles. Préparation
unique, jamais au restart. Archiver hash du jar, SHA/diff, fichiers productifs,
scripts SQL/configuration non secrète, JVM/Maven/PG observés et journaux expurgés.

Pour chaque intention préparée selon les champs exacts du runbook :

```sh
python3 -B scripts/v3/worker-dev.py inject-identity --config /private/tmp/l3-i5-private/runtime.properties --intention-file /private/tmp/l3-i5-private/request.properties
python3 -B scripts/v3/worker-dev.py verify-identity --config /private/tmp/l3-i5-private/runtime.properties --request-id <UUID> --await-seconds 90
python3 -B scripts/v3/worker-dev.py identity-token --config /private/tmp/l3-i5-private/runtime.properties --own-key-file /private/tmp/l3-i5-private/own-key.properties --token-output /private/tmp/l3-i5-private/own-token
```

UUID/intention/token-output distincts à chaque création, timestamp conservé pour
un retry du même ID ; `own-key.properties` contient `issuer`/`subject` exacts.
Lire le token confidentiellement et le copier au champ `binding-token` du fichier
0600 de l'intention Link/Unlink ; aucune impression console. Le token est une
valeur Properties à encoder sans altération ; les clés de campagne sont ASCII
pour ne pas introduire une variante d'encodage nouvelle. `verify-identity` attend
la terminalisation/R9 et l'absence du slot ; **la CLI publique n'impose pas le
motif attendu** : vérifier celui-ci par les assertions SQL suivantes, ne pas
prendre le seul exit0 pour oracle du scénario.

| Ordre / ID de reçu | Intention contrôlée | Préconditions à constater indépendamment | Oracle attendu |
|---|---|---|---|
| Setup R1 | Registration A, inconnue | A absente | CHANGED, User UA, A liée v1 ; récupérer seulement token A par helper DEV |
| Setup R2 | Link A→B, inconnue, token A | A liée à UA | CHANGED, B liée v1 à UA, sans nouveau User |
| Setup R3 | Unlink A→B, token A | A/B même UA | CHANGED, B libre v2, token nul |
| Setup R4 | Registration C, inconnue | C absente | CHANGED, nouveau UC différent de UA, C liée v1 |
| Q-UFREE / R5 | Unlink A→B, token A courant | B libre v2 ; A encore liée UA | REJECTED / TARGET_NOT_BOUND ; aucun changement du domaine |
| Q-UOTHER / R6 | Unlink A→C, token A courant | C liée UC ; UC≠UA | REJECTED / TARGET_BOUND_TO_ANOTHER_USER ; aucun changement du domaine |
| Q-REGNEW / R7 | Registration A, nouvel UUID **après** succès R1 ; même intention métier | A liée v1 ; R1 déjà TERMINAL/CHANGED | REJECTED / TARGET_ALREADY_BOUND ; aucun nouveau User, token ou version |

Méthodes Java sélectionnées : **NONE pour les probes opérateur** ; parcours réel
`V3WorkerDevelopmentTools.internalRequest/injectIdentity/verifyIdentity`,
Discovery/Engine → `ExecuteIdentityRequest.finalizeIn` → transitions/store.
Les méthodes JUnit déjà exécutées qui motivent les lacunes sont identifiées §5 ;
aucune méthode inexistante n'est annoncée comme sélectionnable.

**Oracle SQL indépendant obligatoire**, via connexion d'observation externe
à la JVM et sans appel aux transitions productives. Pour chaque probe, obtenir
une photographie REPEATABLE READ READ ONLY avant injection et une après le retour
de vérification. Cible neuve sans autre source active ; agréger en base pour ne
jamais exporter un token. Exemple de vecteur métier, exécuté via `psql -X
-v ON_ERROR_STOP=1` avec service/PGPASSFILE privé et search_path explicite :

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
select family,position from v3_event_counter order by family;
commit;
```

Comparer **identiquement** les quatre `(relation,n,fingerprint)` avant/après.
Le digest sert de témoin compact de non-changement, pas d'authentification de la
preuve ; les comptages et assertions relationnelles ci-dessous sont décisifs.
Archiver les reçus SQL et les booléens d'égalité, pas les données sensibles.
Pour chaque `rid` et `expected_rejection`, utiliser des variables psql (valeurs
non secrètes) et contrôler toutes les colonnes de cet oracle :

```sql
begin isolation level repeatable read read only;
select q.request_id, q.status, r.outcome, r.rejection,
       r.target_eui_id is null and r.target_version is null as no_changed_target,
       (select count(*) from v3_identity_event e where e.request_id=q.request_id) as mutations,
       (select count(*) from v3_identity_completion_event c where c.subject_id=q.request_id) as completions,
       (select count(*) from consumption_slot s
        where s.consumption_id='2999e75c-272d-5afe-a966-c9364ef021fe'
          and s.consumable_id=q.request_id) as slots,
       (select count(*) from v3_event_position p join v3_identity_completion_event c using(event_id)
        where c.subject_id=q.request_id and p.family='IDENTITY_COMPLETION') as completion_positions
from v3_identity_request q join v3_identity_request_result r using(request_id)
where q.request_id=:'rid'::uuid;
select count(*)=1 as exact_expected_result
from v3_identity_request q join v3_identity_request_result r using(request_id)
where q.request_id=:'rid'::uuid and q.status='TERMINAL'
  and r.outcome='REJECTED' and r.rejection=:'expected_rejection'
  and r.target_eui_id is null and r.target_version is null;
select c.family,c.position,count(p.event_id) as published,
       coalesce(min(p.position),0) as first_position,coalesce(max(p.position),0) as last_position
from v3_event_counter c left join v3_event_position p on p.family=c.family
group by c.family,c.position order by c.family;
commit;
```

Recevabilité par probe : exactement une ligne résultat conforme, `no_changed_target=true`,
mutations0, completions1, slots0, completion_positions1 ; compteur IDENTITY
inchangé, IDENTITY_COMPLETION +1 ; suite dense sans doublon (PK), autres familles
inchangées ; mêmes empreintes métier. Vérifier aussi avant/après l'égalité privée
du token A courant, B libre v2, C liée UC, UA≠UC, sans exporter les tokens.
Les instantanés des R1…R4 existants restent identiques après R5…R7, hors nouvelles
lignes de demandes/résultats/complétions. Sur cette base dédiée, bilan final :
**2 Users, 3 ancres, 4 versions EUI, 7 sources TERMINAL, 7 résultats, 6 événements
Identity, 7 complétions ; positions IDENTITY1…6 et IDENTITY_COMPLETION1…7**.
Les contrôles d'empreinte seuls ne remplacent pas ces oracles indépendants.

Terminer avec la commande `worker-dev.py stop --pid <PID> --log-file <log_privé>
--config <config_privée>` existante ; conserver sortie physique et verdict du
drain. Un arrêt incomplet reste INCONCLUSIVE pour la campagne, sans tuer le
processus puis présenter cette terminaison comme clean stop. Si une terminaison
est indispensable pour libérer le banc, la classer CRASH et garder le diagnostic.

**Coût relatif : faible à moyen**, 50 modules de build sans tests puis un
serveur/Worker et sept demandes séquentielles ; nettement inférieur aux deux
gates d'environ54min chacune. Aucune durée garantie inventée ; le watchdog
d'observation90s est celui du CLI existant, pas un budget de performance certifié.
**PASS Q-ID** = trois oracles complets + provenance + arrêt qualifié ; **FAIL** =
oracle violé/erreur réelle ; **INCONCLUSIVE** = environnement refusé, source/jar
non attribuable, observation incomplète ou drain non qualifié sans défaut établi.
Ne pas réessayer sans diagnostic et changement d'hypothèse documenté.

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

### 11.1 Contradiction de recevabilité à trancher

Le plan L3 §6/I5 prévoit une qualification interne avec audit/décision humaine,
§9.2 exclut ID-01 global tant que Q/externes manquent ; §8.2 exige néanmoins
COMMAND+Monolithe et **Architecture REQUIRED**, sans dispense. La politique §34
autorise le réemploi attribué et précise « no automatic FULL for every small lot
or documentary human acceptance », mais maintient les gates explicites. Le §35,
ligne CERTIFICATION, cite « Required lot closure » et prescrit le full non filtré.
Le niveau opérationnel ne remplace pas les gates, mais la lecture « toute clôture
I5 déclenche CERTIFICATION/FULL » contredirait la voie bornée conditionnelle
explicitement conservée par le plan L3. Aucun des textes ne permet au rédacteur
de choisir seul la lecture la plus économique ni de considérer les FAIL satisfaits.

Les gates I4 ont **été exécutées** au même code ; elles ne sont pas acquises.
Les réemployer conserve une obligation **non satisfaite**, pas une gate fraîche
I5 PASS. Relancer les mêmes commandes sans changement ni exigence de fraîcheur
arbitrée ne résout pas la contradiction et ne justifie pas une recherche de PASS.

### 11.2 Arbitrage minimal proposé, à consigner séparément avant GO

**ARB-I5-01 demande une décision explicite sur les trois clauses suivantes :**

1. **Nature du jalon :** I5 termine un raccord métier interne borné, sans changement
   productif, sans intégration main, restructuration ni nouvelle baseline globale.
   Il ne prononce pas CERTIFICATION globale. Confirmer que cette clôture humaine
   documentaire relève de la voie §34/plan L3 §8.2, ou décider qu'elle relève de
   CERTIFICATION §35. En cas de seconde lecture, FULL et protocoles applicables
   deviennent obligatoires ; le présent mandat économique n'est plus exécutable
   tel quel et doit revenir au responsable avant lancement.
2. **Recevabilité avec gates non acquises :** autoriser nominativement, si retenu,
   la préparation d'une acceptation de programme L3 avec O20/O21 **REQUIRED /
   RUN I4 / FAIL**, Architecture canonique non acquise, sans PASS canonique L3.
   Le réemploi X4/E4 ne satisfait pas ces gates. Cette décision est l'exception
   explicite de recevabilité de la clôture documentaire, permise seulement après
   arbitrage distinct au sens de SPEC-10 §1 ; ce n'est pas une dispense implicite
   tirée d'I4 ni une suppression de gate. Si l'autorité normative refuse cette
   exception ou exige un amendement préalable des plans, **STOP** : aucune
   modification normative n'est autorisée dans cette intervention.
3. **Dette et échéance :** conserver r05/r12, limites JUnit/skip et absence FULL,
   affecter leur résolution/qualification à un mandat explicite avant la première
   gate qui exige leur satisfaction technique, au plus tard convergence/main/
   release concernée. Aucune exemption permanente L6–L8, aucun GO production.
   Un lien causal nouveau avec Identity ou un invariant de sûreté violé rouvre
   seulement le périmètre prouvé nécessaire, sans autorisation de redesign global.

Ce texte est **une proposition non ratifiée**, pas une modification normative
accomplie. Un GO générique ne doit pas être interprété comme acceptation tacite
des trois clauses ; la décision écrite doit les référencer et choisir la voie.
L'audit du présent plan peut être conduit avant cet arbitrage ; **l'exécution
et la proposition finale de clôture en dépendent**.

### 11.3 Déclencheurs d'escalade

FULL requis si jalon ratifié Wave majeure/CERTIFICATION globale, intégration
main, restructuration Maven large, infrastructure transverse non bornable ou
stabilisation globale (§§19/21/35). Aucun de ces changements n'est démontré dans
le diff documentaire présent. Architecture demeure REQUIRED pour I5 par son
plan, même sans nouvelle arête. Une nouvelle arête/frontière/manager/routage
nécessaire impose arrêt et nouvelle déclaration, pas extension silencieuse.
Un FAIL de disponibilité ne déclenche pas automatiquement FULL (§20).

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
| Arbitrage absent/refusé, gate obligatoire non satisfaite sans voie de recevabilité ratifiée | **NORMATIVE ARBITRATION REQUIRED / NO CLOSURE** |
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
| Architecture canonique non acquise, COMMAND+Monolithe FAIL | **Bloquant tant que ARB-I5-01 non ratifié** ; ensuite dette canonique ouverte, jamais S | Rejet de l'exception ou exigence fraîche non satisfaite |
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

Avant le GO de campagne :

1. Audit indépendant du présent document, notamment trois probes, suffisance
   composée des 14 autres variantes internes et attribution E4/H3.
2. **ARB-I5-01 écrit et ratifié**, avec nature du jalon, traitement nominatif
   des deux gates FAIL/Architecture REQUIRED, dette et application §34/§35/FULL.
   Si la décision impose une autre voie, ne pas démarrer la campagne proposée.
3. Autorisation de la campagne Q-ID et de son éventuel build sans tests, cibles
   PostgreSQL neuves exactes et profil DEV existant ; aucun code/test/migration/
   POM/outillage modifiable. Les commandes §10 sont alors prospectivement
   autorisées ; elles ne sont pas exécutées pour préparer ce document.
4. Autorisation de préparer localement les quatre livrables complets et les
   mises à jour factuelles ; périmètre de publication explicite séparément.
5. Revalidation de baseline/équivalence au démarrage ; aucun défaut bloquant
   nouveau ni hypothèse de manager/privilèges/logging/PG non qualifiée.

La campagne s'exécute alors en une passe selon §12, avec arrêt exceptionnel
uniquement sur défaut bloquant réel, preuve impossible ou nouvelle contradiction.
L'acceptation finale de L3 appartient à la décision humaine après audit du dossier.

**Présent mandat :** seul
`docs/v3/L3_I5_Qualification_and_Closure_Plan.md` est créé. Slices primaire et
secondaires **NONE**, impact productif **NONE**, franchissement inattendu **NONE**.
Gate globale/full **NOT REQUIRED / NOT RUN pour ce plan documentaire** ; cette
déclaration ne dispense pas I5 de ses obligations O20–O22. DB/migrations,
Maven/Java/Docker/Bruno/k6 et tests applicatifs **NOT RUN**.

Commandes réellement utilisées : `pwd`, `git status --short --branch`,
`git worktree list --porcelain`, `git -C <worktree> status --porcelain=v1`,
`git remote -v`, `git ls-remote origin refs/heads/feat/v3-architecture`,
`git log`, `git diff`/`git diff --stat`, `git rev-list --left-right --count`,
`git rev-parse <ref>:<path>`, `git show`, lectures `rg`/`rg --files`/`cat`/`sed`,
scripts ponctuels `python3 -B -` de lecture Git/JSON/gzip/tar/XML/empreintes et
de références, `python3 -B docs/testing/evidence/L3-I4/verify_evidence.py`,
`git diff --check` et contrôle du nouveau Markdown avec
`git diff --no-index --check /dev/null docs/v3/L3_I5_Qualification_and_Closure_Plan.md`.
Ces contrôles statiques ne constituent aucune campagne de tests. Aucun autre
fichier du dépôt modifié ; aucune publication effectuée.

**Recommandation : conserver l'hypothèse sans développement productif et faire
arbitrer la recevabilité normative avant tout GO.** Le plan opérationnel des
compléments est prêt à l'audit ; ni exécution ni clôture L3 ne sont autorisées.

**I5 — NORMATIVE ARBITRATION REQUIRED**
