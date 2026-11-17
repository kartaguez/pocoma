# Audit Maven — ensembles de tests et fichiers examinés

Annexe du [rapport indépendant](Verification_Rationalization_Independent_Audit.md),
SHA `d0927d9410ebcd65c221e73c5ecd04e5242fe4e9`, 31 octobre 2026.
Les identifiants ci-dessous proviennent des XML archivés indépendamment reparsés.
Le niveau désigne la première inclusion ; les suivants sont cumulatifs.
FAST : 65 ; CHANGE : 65 + 62 = 127 ; INTEGRATION : 127 + 35 = 162.
Zéro identifiant F∖C ou C∖I, zéro doublon, skip ou méthode attendue absente.

## Classes et sources

| Premier niveau | Classe | Invocations | Source inspectée |
|---|---|---:|---|
| FAST | `ShareholderIdentityCollisionTest` | 1 | [app/domain-pot/src/test/java/com/kartaguez/pocoma/domain/pot/aggregate/ShareholderIdentityCollisionTest.java](../../app/domain-pot/src/test/java/com/kartaguez/pocoma/domain/pot/aggregate/ShareholderIdentityCollisionTest.java) |
| FAST | `InvitationAttachmentTest` | 3 | [app/domain-pot/src/test/java/com/kartaguez/pocoma/domain/pot/v3/InvitationAttachmentTest.java](../../app/domain-pot/src/test/java/com/kartaguez/pocoma/domain/pot/v3/InvitationAttachmentTest.java) |
| FAST | `PotAuthorizationTest` | 3 | [app/domain-pot/src/test/java/com/kartaguez/pocoma/domain/pot/v3/PotAuthorizationTest.java](../../app/domain-pot/src/test/java/com/kartaguez/pocoma/domain/pot/v3/PotAuthorizationTest.java) |
| FAST | `PotTransitionsTest` | 14 | [app/domain-pot/src/test/java/com/kartaguez/pocoma/domain/pot/v3/PotTransitionsTest.java](../../app/domain-pot/src/test/java/com/kartaguez/pocoma/domain/pot/v3/PotTransitionsTest.java) |
| FAST | `UserIdentityModelTest` | 6 | [app/domain-user-identity/src/test/java/com/kartaguez/pocoma/domain/useridentity/UserIdentityModelTest.java](../../app/domain-user-identity/src/test/java/com/kartaguez/pocoma/domain/useridentity/UserIdentityModelTest.java) |
| FAST | `IdentityTransitionsTest` | 17 | [app/domain-user-identity/src/test/java/com/kartaguez/pocoma/domain/useridentity/v3/IdentityTransitionsTest.java](../../app/domain-user-identity/src/test/java/com/kartaguez/pocoma/domain/useridentity/v3/IdentityTransitionsTest.java) |
| FAST | `IdentityValueContractsTest` | 8 | [app/domain-user-identity/src/test/java/com/kartaguez/pocoma/domain/useridentity/v3/IdentityValueContractsTest.java](../../app/domain-user-identity/src/test/java/com/kartaguez/pocoma/domain/useridentity/v3/IdentityValueContractsTest.java) |
| FAST | `ExecuteIdentityRequestTest` | 3 | [app/engine-consume-registration/src/test/java/com/kartaguez/pocoma/engine/consume/registration/v3/ExecuteIdentityRequestTest.java](../../app/engine-consume-registration/src/test/java/com/kartaguez/pocoma/engine/consume/registration/v3/ExecuteIdentityRequestTest.java) |
| FAST | `IdentityRequestContractTest` | 10 | [app/engine-consume-registration/src/test/java/com/kartaguez/pocoma/engine/consume/registration/v3/IdentityRequestContractTest.java](../../app/engine-consume-registration/src/test/java/com/kartaguez/pocoma/engine/consume/registration/v3/IdentityRequestContractTest.java) |
| CHANGE | `BindingTokenGeneratorTest` | 2 | [app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/identity/BindingTokenGeneratorTest.java](../../app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/identity/BindingTokenGeneratorTest.java) |
| CHANGE | `IdentityAuthorityPostgresTest` | 6 | [app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/identity/IdentityAuthorityPostgresTest.java](../../app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/identity/IdentityAuthorityPostgresTest.java) |
| CHANGE | `IdentityConcurrencyPostgresTest` | 8 | [app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/identity/IdentityConcurrencyPostgresTest.java](../../app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/identity/IdentityConcurrencyPostgresTest.java) |
| CHANGE | `IdentityDiscoveryPostgresTest` | 3 | [app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/identity/IdentityDiscoveryPostgresTest.java](../../app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/identity/IdentityDiscoveryPostgresTest.java) |
| CHANGE | `IdentityPrivilegesPostgresTest` | 3 | [app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/identity/IdentityPrivilegesPostgresTest.java](../../app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/identity/IdentityPrivilegesPostgresTest.java) |
| CHANGE | `IdentityPublicationPostgresTest` | 8 | [app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/identity/IdentityPublicationPostgresTest.java](../../app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/identity/IdentityPublicationPostgresTest.java) |
| CHANGE | `IdentityRequestExecutionPostgresTest` | 13 | [app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/identity/IdentityRequestExecutionPostgresTest.java](../../app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/identity/IdentityRequestExecutionPostgresTest.java) |
| CHANGE | `IdentitySchemaPostgresTest` | 11 | [app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/identity/IdentitySchemaPostgresTest.java](../../app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/identity/IdentitySchemaPostgresTest.java) |
| CHANGE | `V3IdentityRequestCompositionPostgresTest` | 8 | [app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/v3/V3IdentityRequestCompositionPostgresTest.java](../../app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/v3/V3IdentityRequestCompositionPostgresTest.java) |
| INTEGRATION | `V3IdentityRequestMonolithPostgresTest` | 5 | [app/runtime-monolith/src/test/java/com/kartaguez/pocoma/V3IdentityRequestMonolithPostgresTest.java](../../app/runtime-monolith/src/test/java/com/kartaguez/pocoma/V3IdentityRequestMonolithPostgresTest.java) |
| INTEGRATION | `AttemptTransactionsPostgresTest` | 9 | [app/infra-tx-spring/src/test/java/com/kartaguez/pocoma/infra/tx/spring/v3/AttemptTransactionsPostgresTest.java](../../app/infra-tx-spring/src/test/java/com/kartaguez/pocoma/infra/tx/spring/v3/AttemptTransactionsPostgresTest.java) |
| INTEGRATION | `V3FoundationCompositionTest` | 3 | [app/infra-tx-spring/src/test/java/com/kartaguez/pocoma/infra/tx/spring/v3/V3FoundationCompositionTest.java](../../app/infra-tx-spring/src/test/java/com/kartaguez/pocoma/infra/tx/spring/v3/V3FoundationCompositionTest.java) |
| INTEGRATION | `V3HistoricalIntervalPostgresTest` | 4 | [app/infra-tx-spring/src/test/java/com/kartaguez/pocoma/infra/tx/spring/v3/V3HistoricalIntervalPostgresTest.java](../../app/infra-tx-spring/src/test/java/com/kartaguez/pocoma/infra/tx/spring/v3/V3HistoricalIntervalPostgresTest.java) |
| INTEGRATION | `V3SchemaIsolationPostgresTest` | 2 | [app/infra-tx-spring/src/test/java/com/kartaguez/pocoma/infra/tx/spring/v3/V3SchemaIsolationPostgresTest.java](../../app/infra-tx-spring/src/test/java/com/kartaguez/pocoma/infra/tx/spring/v3/V3SchemaIsolationPostgresTest.java) |
| INTEGRATION | `V3TransactionFoundationPostgresTest` | 6 | [app/infra-tx-spring/src/test/java/com/kartaguez/pocoma/infra/tx/spring/v3/V3TransactionFoundationPostgresTest.java](../../app/infra-tx-spring/src/test/java/com/kartaguez/pocoma/infra/tx/spring/v3/V3TransactionFoundationPostgresTest.java) |
| INTEGRATION | `V3PotCommandCompositionPostgresTest` | 1 | [app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/v3/V3PotCommandCompositionPostgresTest.java](../../app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/v3/V3PotCommandCompositionPostgresTest.java) |
| INTEGRATION | `V3WorkerCompositionPostgresTest` | 5 | [app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/v3/V3WorkerCompositionPostgresTest.java](../../app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/v3/V3WorkerCompositionPostgresTest.java) |

## Identifiants exacts des 162 invocations

### FAST — ShareholderIdentityCollisionTest

Source : [app/domain-pot/src/test/java/com/kartaguez/pocoma/domain/pot/aggregate/ShareholderIdentityCollisionTest.java](../../app/domain-pot/src/test/java/com/kartaguez/pocoma/domain/pot/aggregate/ShareholderIdentityCollisionTest.java).

```text
com.kartaguez.pocoma.domain.pot.aggregate.ShareholderIdentityCollisionTest#generatedCollisionCannotReplaceExistingOrEarlierAddedShareholder
```

### FAST — InvitationAttachmentTest

Source : [app/domain-pot/src/test/java/com/kartaguez/pocoma/domain/pot/v3/InvitationAttachmentTest.java](../../app/domain-pot/src/test/java/com/kartaguez/pocoma/domain/pot/v3/InvitationAttachmentTest.java).

```text
com.kartaguez.pocoma.domain.pot.v3.InvitationAttachmentTest#mixedOrMissingDurableFactsAreTechnicalAnomalies
com.kartaguez.pocoma.domain.pot.v3.InvitationAttachmentTest#occupiedDeletedAbsentAndAlreadyAttachedRemainRejected
com.kartaguez.pocoma.domain.pot.v3.InvitationAttachmentTest#outsiderAndUnattachedCreatorAttachOnlyThemselvesWithOneDetailsRevision
```

### FAST — PotAuthorizationTest

Source : [app/domain-pot/src/test/java/com/kartaguez/pocoma/domain/pot/v3/PotAuthorizationTest.java](../../app/domain-pot/src/test/java/com/kartaguez/pocoma/domain/pot/v3/PotAuthorizationTest.java).

```text
com.kartaguez.pocoma.domain.pot.v3.PotAuthorizationTest#d07EveryMutationRoleAndPotLifecycleCell
com.kartaguez.pocoma.domain.pot.v3.PotAuthorizationTest#d08AttachmentDetachmentAndGeneralMultipleAttachment
com.kartaguez.pocoma.domain.pot.v3.PotAuthorizationTest#d09InvitationEligibilityAndDeletedTargets
```

### FAST — PotTransitionsTest

Source : [app/domain-pot/src/test/java/com/kartaguez/pocoma/domain/pot/v3/PotTransitionsTest.java](../../app/domain-pot/src/test/java/com/kartaguez/pocoma/domain/pot/v3/PotTransitionsTest.java).

```text
com.kartaguez.pocoma.domain.pot.v3.PotTransitionsTest#l1a01ImmutableEnvelopesAndCollections
com.kartaguez.pocoma.domain.pot.v3.PotTransitionsTest#l1a02CreationVersionsAndStableIdentities
com.kartaguez.pocoma.domain.pot.v3.PotTransitionsTest#l1a03ChangedContainsExactlyEffectiveRevisions
com.kartaguez.pocoma.domain.pot.v3.PotTransitionsTest#l1a04ObsolescencePrecedesEqualityIncludingReturnToOldValue
com.kartaguez.pocoma.domain.pot.v3.PotTransitionsTest#l1a05AtomicWeightBatchesAndPermutations
com.kartaguez.pocoma.domain.pot.v3.PotTransitionsTest#l1a06PotDetailsRightsLifecycleAndObservation
com.kartaguez.pocoma.domain.pot.v3.PotTransitionsTest#l1a07DetailsAndWeightsAreIndependent
com.kartaguez.pocoma.domain.pot.v3.PotTransitionsTest#l1a08FinancialsReferenceAndEqualityVariants
com.kartaguez.pocoma.domain.pot.v3.PotTransitionsTest#l1a09DeletionDependenciesAndHeaderOnlyChanges
com.kartaguez.pocoma.domain.pot.v3.PotTransitionsTest#l1a10ShareholderDeletionRequiresCreatorAndExhaustiveAbsence
com.kartaguez.pocoma.domain.pot.v3.PotTransitionsTest#l1a11ExactRationalsAndIntrinsicFinancialInvariants
com.kartaguez.pocoma.domain.pot.v3.PotTransitionsTest#l1a12MixedFactsAreTechnicalAnomalies
com.kartaguez.pocoma.domain.pot.v3.PotTransitionsTest#l1a13DeterministicReevaluationPreservesInitialObservation
com.kartaguez.pocoma.domain.pot.v3.PotTransitionsTest#l1a14HistoricalCompositionsAndOrdinaryAttachment
```

### FAST — UserIdentityModelTest

Source : [app/domain-user-identity/src/test/java/com/kartaguez/pocoma/domain/useridentity/UserIdentityModelTest.java](../../app/domain-user-identity/src/test/java/com/kartaguez/pocoma/domain/useridentity/UserIdentityModelTest.java).

```text
com.kartaguez.pocoma.domain.useridentity.UserIdentityModelTest#attachedFactRequiresACompletePositiveRevisionShape
com.kartaguez.pocoma.domain.useridentity.UserIdentityModelTest#bindingIdIsAnOpaqueUuidValueWithoutOrdering
com.kartaguez.pocoma.domain.useridentity.UserIdentityModelTest#bindingRevisionHasLocalNonNegativeValueSemantics
com.kartaguez.pocoma.domain.useridentity.UserIdentityModelTest#detachedFactRequiresACompletePositiveRevisionShapeWithUserId
com.kartaguez.pocoma.domain.useridentity.UserIdentityModelTest#externalIdentityPreservesExactIssuerAndSubjectEquality
com.kartaguez.pocoma.domain.useridentity.UserIdentityModelTest#userContainsOnlyItsCanonicalIdentity
```

### FAST — IdentityTransitionsTest

Source : [app/domain-user-identity/src/test/java/com/kartaguez/pocoma/domain/useridentity/v3/IdentityTransitionsTest.java](../../app/domain-user-identity/src/test/java/com/kartaguez/pocoma/domain/useridentity/v3/IdentityTransitionsTest.java).

```text
com.kartaguez.pocoma.domain.useridentity.v3.IdentityTransitionsTest#absentFreeOrStaleInitiatorCannotLinkOrUnlink
com.kartaguez.pocoma.domain.useridentity.v3.IdentityTransitionsTest#crossedUnlinksLoseInitiatorAuthorityAfterFirstSuccess
com.kartaguez.pocoma.domain.useridentity.v3.IdentityTransitionsTest#inconsistentSnapshotsIdsAndKnownUsedCandidateTokenAreFaults
com.kartaguez.pocoma.domain.useridentity.v3.IdentityTransitionsTest#linkAbsentTargetDeducesUserFromInitiatorWithoutCreatingUser
com.kartaguez.pocoma.domain.useridentity.v3.IdentityTransitionsTest#linkFreeTargetRetainsIdAndExactSuccessor
com.kartaguez.pocoma.domain.useridentity.v3.IdentityTransitionsTest#linkRejectsAlreadyBoundTargetEvenToSameUser(boolean)[1]
com.kartaguez.pocoma.domain.useridentity.v3.IdentityTransitionsTest#linkRejectsAlreadyBoundTargetEvenToSameUser(boolean)[2]
com.kartaguez.pocoma.domain.useridentity.v3.IdentityTransitionsTest#localHistoryOneThroughFivePreservesOldStatesAndRenewsTokensEvenForSameUser
com.kartaguez.pocoma.domain.useridentity.v3.IdentityTransitionsTest#registrationOfAbsentIdentityCreatesUserAndDirectlyBoundVersionOne
com.kartaguez.pocoma.domain.useridentity.v3.IdentityTransitionsTest#registrationOfFreeIdentityKeepsIdAndCreatesExactSuccessorAndNewUser
com.kartaguez.pocoma.domain.useridentity.v3.IdentityTransitionsTest#registrationRejectsBoundIdentityWithoutAUserOrStateChange
com.kartaguez.pocoma.domain.useridentity.v3.IdentityTransitionsTest#requiredCandidatesAndSnapshotsCannotBeNull
com.kartaguez.pocoma.domain.useridentity.v3.IdentityTransitionsTest#selfAndLastUnlinkOnlyChangeEuiAndNeverDeleteUser
com.kartaguez.pocoma.domain.useridentity.v3.IdentityTransitionsTest#selfLinkIsStrictlyRejected
com.kartaguez.pocoma.domain.useridentity.v3.IdentityTransitionsTest#unlinkOtherIdentityOfSameUserDoesNotRequireItsToken
com.kartaguez.pocoma.domain.useridentity.v3.IdentityTransitionsTest#unlinkRejectsAbsentFreeAndAnotherUsersTarget
com.kartaguez.pocoma.domain.useridentity.v3.IdentityTransitionsTest#versionOverflowIsAFaultAndNeverWrapsToZeroOrNegative
```

### FAST — IdentityValueContractsTest

Source : [app/domain-user-identity/src/test/java/com/kartaguez/pocoma/domain/useridentity/v3/IdentityValueContractsTest.java](../../app/domain-user-identity/src/test/java/com/kartaguez/pocoma/domain/useridentity/v3/IdentityValueContractsTest.java).

```text
com.kartaguez.pocoma.domain.useridentity.v3.IdentityValueContractsTest#allPermutationsOfCanonicalVectorGroupsSortAndDeduplicateExactKeys
com.kartaguez.pocoma.domain.useridentity.v3.IdentityValueContractsTest#changedIsNonemptyAndCanOnlyCreateTheUserOfItsNewBoundState
com.kartaguez.pocoma.domain.useridentity.v3.IdentityValueContractsTest#externalKeysPreserveOpaqueTextWithoutCaseUrlOrUnicodeNormalization
com.kartaguez.pocoma.domain.useridentity.v3.IdentityValueContractsTest#idsAndTokensHaveExactOpaqueValueSemanticsWithoutGenerationOrOrdering
com.kartaguez.pocoma.domain.useridentity.v3.IdentityValueContractsTest#independentUtf8VectorsOrderIssuerThenSubjectWithPrefixesAndUnsignedBytes
com.kartaguez.pocoma.domain.useridentity.v3.IdentityValueContractsTest#nulAndIsolatedSurrogatesAreRejectedInEitherFieldAndEitherOperand
com.kartaguez.pocoma.domain.useridentity.v3.IdentityValueContractsTest#statesRequirePositiveVersionAndUserTokenTogether
com.kartaguez.pocoma.domain.useridentity.v3.IdentityValueContractsTest#tokensNeverAppearInDefaultDiagnosticRepresentations
```

### FAST — ExecuteIdentityRequestTest

Source : [app/engine-consume-registration/src/test/java/com/kartaguez/pocoma/engine/consume/registration/v3/ExecuteIdentityRequestTest.java](../../app/engine-consume-registration/src/test/java/com/kartaguez/pocoma/engine/consume/registration/v3/ExecuteIdentityRequestTest.java).

```text
com.kartaguez.pocoma.engine.consume.registration.v3.ExecuteIdentityRequestTest#l3_participant_order
com.kartaguez.pocoma.engine.consume.registration.v3.ExecuteIdentityRequestTest#l3_rejection_terminal
com.kartaguez.pocoma.engine.consume.registration.v3.ExecuteIdentityRequestTest#l3_technical_not_rejected
```

### FAST — IdentityRequestContractTest

Source : [app/engine-consume-registration/src/test/java/com/kartaguez/pocoma/engine/consume/registration/v3/IdentityRequestContractTest.java](../../app/engine-consume-registration/src/test/java/com/kartaguez/pocoma/engine/consume/registration/v3/IdentityRequestContractTest.java).

```text
com.kartaguez.pocoma.engine.consume.registration.v3.IdentityRequestContractTest#clientIntentionCannotChooseUserAndResultCannotDiscloseTokenOrUser
com.kartaguez.pocoma.engine.consume.registration.v3.IdentityRequestContractTest#exactIntentionEqualityIncludesOperationTokenTargetAndAdmission
com.kartaguez.pocoma.engine.consume.registration.v3.IdentityRequestContractTest#externalUnicodeRemainsExactAndUnstorableKeysFailBeforeAnyAdapter
com.kartaguez.pocoma.engine.consume.registration.v3.IdentityRequestContractTest#immutableAdmissionCapturesProvenanceAndMustMatchBothIdentities
com.kartaguez.pocoma.engine.consume.registration.v3.IdentityRequestContractTest#linkAndUnlinkRequireInitiatorTokenAndAnExactTargetIncludingSelf
com.kartaguez.pocoma.engine.consume.registration.v3.IdentityRequestContractTest#missingIntentionAndProvenanceAreInvalid
com.kartaguez.pocoma.engine.consume.registration.v3.IdentityRequestContractTest#observationRequiresEngineReadSnapshotAndReturnsExistingRecoveryVocabulary
com.kartaguez.pocoma.engine.consume.registration.v3.IdentityRequestContractTest#publicResultExposesOnlyChangedTargetAndVersionOrClosedRejection
com.kartaguez.pocoma.engine.consume.registration.v3.IdentityRequestContractTest#registrationTargetsOnlyItsAuthenticatedRequesterAndOmitsToken
com.kartaguez.pocoma.engine.consume.registration.v3.IdentityRequestContractTest#storedRequestDistinguishesPendingAndTerminalAndRequiresExactResultPresence
```

### CHANGE — BindingTokenGeneratorTest

Source : [app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/identity/BindingTokenGeneratorTest.java](../../app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/identity/BindingTokenGeneratorTest.java).

```text
com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.BindingTokenGeneratorTest#l3_generator_contract
com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.BindingTokenGeneratorTest#l3_real_secure_random_encoding
```

### CHANGE — IdentityAuthorityPostgresTest

Source : [app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/identity/IdentityAuthorityPostgresTest.java](../../app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/identity/IdentityAuthorityPostgresTest.java).

```text
com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.IdentityAuthorityPostgresTest#l3_anchor_absence
com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.IdentityAuthorityPostgresTest#l3_forced_token_collision
com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.IdentityAuthorityPostgresTest#l3_guards_and_caught_failure_poison
com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.IdentityAuthorityPostgresTest#l3_history_exact
com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.IdentityAuthorityPostgresTest#l3_last_unlink_and_registration_free
com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.IdentityAuthorityPostgresTest#l3_rollback_boundaries
```

### CHANGE — IdentityConcurrencyPostgresTest

Source : [app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/identity/IdentityConcurrencyPostgresTest.java](../../app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/identity/IdentityConcurrencyPostgresTest.java).

```text
com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.IdentityConcurrencyPostgresTest#l3_anchor_id_race
com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.IdentityConcurrencyPostgresTest#l3_canonical_lock_paths
com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.IdentityConcurrencyPostgresTest#l3_canonical_unicode
com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.IdentityConcurrencyPostgresTest#l3_cross_unlinks
com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.IdentityConcurrencyPostgresTest#l3_disjoint_progress
com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.IdentityConcurrencyPostgresTest#l3_link_races
com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.IdentityConcurrencyPostgresTest#l3_link_vs_initiator_unlink
com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.IdentityConcurrencyPostgresTest#l3_registration_race
```

### CHANGE — IdentityDiscoveryPostgresTest

Source : [app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/identity/IdentityDiscoveryPostgresTest.java](../../app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/identity/IdentityDiscoveryPostgresTest.java).

```text
com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.IdentityDiscoveryPostgresTest#l3_burnable_discovery
com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.IdentityDiscoveryPostgresTest#l3_discovery_source_slot_race
com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.IdentityDiscoveryPostgresTest#l3_identity_windows
```

### CHANGE — IdentityPrivilegesPostgresTest

Source : [app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/identity/IdentityPrivilegesPostgresTest.java](../../app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/identity/IdentityPrivilegesPostgresTest.java).

```text
com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.IdentityPrivilegesPostgresTest#l3_effective_privileges
com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.IdentityPrivilegesPostgresTest#l3_internal_admission_idempotence
com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.IdentityPrivilegesPostgresTest#l3_wrong_authority
```

### CHANGE — IdentityPublicationPostgresTest

Source : [app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/identity/IdentityPublicationPostgresTest.java](../../app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/identity/IdentityPublicationPostgresTest.java).

```text
com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.IdentityPublicationPostgresTest#l3_constraints_before_close
com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.IdentityPublicationPostgresTest#l3_deferred_commit_checks
com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.IdentityPublicationPostgresTest#l3_dense_rollback
com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.IdentityPublicationPostgresTest#l3_identity_batch
com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.IdentityPublicationPostgresTest#l3_identity_pair_contention
com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.IdentityPublicationPostgresTest#l3_physical_unit
com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.IdentityPublicationPostgresTest#l3_structural_poison_before_counter
com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.IdentityPublicationPostgresTest#l3_suffix_closed
```

### CHANGE — IdentityRequestExecutionPostgresTest

Source : [app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/identity/IdentityRequestExecutionPostgresTest.java](../../app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/identity/IdentityRequestExecutionPostgresTest.java).

```text
com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.IdentityRequestExecutionPostgresTest#l3_atomic_link_unlink
com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.IdentityRequestExecutionPostgresTest#l3_atomic_registration
com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.IdentityRequestExecutionPostgresTest#l3_completion_misuse
com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.IdentityRequestExecutionPostgresTest#l3_fencing
com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.IdentityRequestExecutionPostgresTest#l3_final_lock_clock
com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.IdentityRequestExecutionPostgresTest#l3_observation_corruption
com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.IdentityRequestExecutionPostgresTest#l3_rejection_terminal
com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.IdentityRequestExecutionPostgresTest#l3_retry_quarantine
com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.IdentityRequestExecutionPostgresTest#l3_rollback_each_boundary
com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.IdentityRequestExecutionPostgresTest#l3_technical_not_rejected_and_redacted
com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.IdentityRequestExecutionPostgresTest#l3_unavailable_observation
com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.IdentityRequestExecutionPostgresTest#l3_unknown_commit
com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.IdentityRequestExecutionPostgresTest#l3_workers_concurrent_registration
```

### CHANGE — IdentitySchemaPostgresTest

Source : [app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/identity/IdentitySchemaPostgresTest.java](../../app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/identity/IdentitySchemaPostgresTest.java).

```text
com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.IdentitySchemaPostgresTest#l3_anchor_negative_invariants
com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.IdentitySchemaPostgresTest#l3_bad_server_encoding_preflight
com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.IdentitySchemaPostgresTest#l3_constraint_catalog
com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.IdentitySchemaPostgresTest#l3_deferred_commit_has_no_new_business_locks
com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.IdentitySchemaPostgresTest#l3_fk_wait_is_in_prefix
com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.IdentitySchemaPostgresTest#l3_history_rewrite_forbidden
com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.IdentitySchemaPostgresTest#l3_other_family_suffix_is_untouched
com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.IdentitySchemaPostgresTest#l3_position_constraints
com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.IdentitySchemaPostgresTest#l3_sql_negative_shapes
com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.IdentitySchemaPostgresTest#l3_sql_publication_closure
com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.IdentitySchemaPostgresTest#l3_unstorable_keys
```

### CHANGE — V3IdentityRequestCompositionPostgresTest

Source : [app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/v3/V3IdentityRequestCompositionPostgresTest.java](../../app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/v3/V3IdentityRequestCompositionPostgresTest.java).

```text
com.kartaguez.pocoma.runtime.command.v3.V3IdentityRequestCompositionPostgresTest#l3_auxiliary_effective_privilege_matrix
com.kartaguez.pocoma.runtime.command.v3.V3IdentityRequestCompositionPostgresTest#l3_delivered_jpa_rejects_prebound_holder
com.kartaguez.pocoma.runtime.command.v3.V3IdentityRequestCompositionPostgresTest#l3_identity_discovery_checkpoint
com.kartaguez.pocoma.runtime.command.v3.V3IdentityRequestCompositionPostgresTest#l3_indirect_exposure_refused
com.kartaguez.pocoma.runtime.command.v3.V3IdentityRequestCompositionPostgresTest#l3_logging_override_refused
com.kartaguez.pocoma.runtime.command.v3.V3IdentityRequestCompositionPostgresTest#l3_postgres_diagnostics_without_sensitive_detail
com.kartaguez.pocoma.runtime.command.v3.V3IdentityRequestCompositionPostgresTest#l3_preflight_negative
com.kartaguez.pocoma.runtime.command.v3.V3IdentityRequestCompositionPostgresTest#l3_runtime_identity
```

### INTEGRATION — V3IdentityRequestMonolithPostgresTest

Source : [app/runtime-monolith/src/test/java/com/kartaguez/pocoma/V3IdentityRequestMonolithPostgresTest.java](../../app/runtime-monolith/src/test/java/com/kartaguez/pocoma/V3IdentityRequestMonolithPostgresTest.java).

```text
com.kartaguez.pocoma.V3IdentityRequestMonolithPostgresTest#l3_delivered_jpa_rejects_prebound_holder
com.kartaguez.pocoma.V3IdentityRequestMonolithPostgresTest#l3_indirect_exposure_refused
com.kartaguez.pocoma.V3IdentityRequestMonolithPostgresTest#l3_logging_override_refused
com.kartaguez.pocoma.V3IdentityRequestMonolithPostgresTest#l3_preflight_negative
com.kartaguez.pocoma.V3IdentityRequestMonolithPostgresTest#l3_runtime_identity
```

### INTEGRATION — AttemptTransactionsPostgresTest

Source : [app/infra-tx-spring/src/test/java/com/kartaguez/pocoma/infra/tx/spring/v3/AttemptTransactionsPostgresTest.java](../../app/infra-tx-spring/src/test/java/com/kartaguez/pocoma/infra/tx/spring/v3/AttemptTransactionsPostgresTest.java).

```text
com.kartaguez.pocoma.infra.tx.spring.v3.AttemptTransactionsPostgresTest#autonomousRevisionCaughtFailurePoisonsJpaAndJdbcCallers
com.kartaguez.pocoma.infra.tx.spring.v3.AttemptTransactionsPostgresTest#caughtApplicationJdbcJpaFailuresPoisonBothWriteOrders
com.kartaguez.pocoma.infra.tx.spring.v3.AttemptTransactionsPostgresTest#commitServerRejectionRollbackAndMaskedCommitRemainDistinct
com.kartaguez.pocoma.infra.tx.spring.v3.AttemptTransactionsPostgresTest#guardedLateCallbackRefusedBeforeSqlAndRollback
com.kartaguez.pocoma.infra.tx.spring.v3.AttemptTransactionsPostgresTest#jpaJdbcSamePidXidAndClosedContextCannotDirtyWriteAtCommit
com.kartaguez.pocoma.infra.tx.spring.v3.AttemptTransactionsPostgresTest#nestedAmbientWrongSourceAndWrongEmfRefused
com.kartaguez.pocoma.infra.tx.spring.v3.AttemptTransactionsPostgresTest#originalCallbackCauseSurvivesRollbackFailureAndConfirmedCommitWinsCleanup
com.kartaguez.pocoma.infra.tx.spring.v3.AttemptTransactionsPostgresTest#requiresNewJpaAndJdbcCannotCommitEffectOrDelete
com.kartaguez.pocoma.infra.tx.spring.v3.AttemptTransactionsPostgresTest#sameOwnerReadAuthorityAtomicAndContextCannotCrossAuthorityOrLifetime
```

### INTEGRATION — V3FoundationCompositionTest

Source : [app/infra-tx-spring/src/test/java/com/kartaguez/pocoma/infra/tx/spring/v3/V3FoundationCompositionTest.java](../../app/infra-tx-spring/src/test/java/com/kartaguez/pocoma/infra/tx/spring/v3/V3FoundationCompositionTest.java).

```text
com.kartaguez.pocoma.infra.tx.spring.v3.V3FoundationCompositionTest#ambiguousManagersAreRefusedInsteadOfSilentlyChoosingAnAuthority
com.kartaguez.pocoma.infra.tx.spring.v3.V3FoundationCompositionTest#existingConfigurationInjectsTheActualManagerWithoutApplicationBootstrap
com.kartaguez.pocoma.infra.tx.spring.v3.V3FoundationCompositionTest#explicitRealmCompositionUsesIndependentPhysicalTransactions
```

### INTEGRATION — V3HistoricalIntervalPostgresTest

Source : [app/infra-tx-spring/src/test/java/com/kartaguez/pocoma/infra/tx/spring/v3/V3HistoricalIntervalPostgresTest.java](../../app/infra-tx-spring/src/test/java/com/kartaguez/pocoma/infra/tx/spring/v3/V3HistoricalIntervalPostgresTest.java).

```text
com.kartaguez.pocoma.infra.tx.spring.v3.V3HistoricalIntervalPostgresTest#closesAndOpensAtomicallyAndRollbackPreservesHistory
com.kartaguez.pocoma.infra.tx.spring.v3.V3HistoricalIntervalPostgresTest#rejectsInvalidIntervalsAndDuplicateOpenVersions
com.kartaguez.pocoma.infra.tx.spring.v3.V3HistoricalIntervalPostgresTest#reportsGapsAndOverlapsInsteadOfFallingBackToCurrent
com.kartaguez.pocoma.infra.tx.spring.v3.V3HistoricalIntervalPostgresTest#resolvesSemiOpenBoundariesAndOpenEndInAnyOrder
```

### INTEGRATION — V3SchemaIsolationPostgresTest

Source : [app/infra-tx-spring/src/test/java/com/kartaguez/pocoma/infra/tx/spring/v3/V3SchemaIsolationPostgresTest.java](../../app/infra-tx-spring/src/test/java/com/kartaguez/pocoma/infra/tx/spring/v3/V3SchemaIsolationPostgresTest.java).

```text
com.kartaguez.pocoma.infra.tx.spring.v3.V3SchemaIsolationPostgresTest#installsOnlyTheExplicitFoundationFixtureOnRealPostgres
com.kartaguez.pocoma.infra.tx.spring.v3.V3SchemaIsolationPostgresTest#restartValidatesWithoutReexecutingAndKeepsIndependentHistories
```

### INTEGRATION — V3TransactionFoundationPostgresTest

Source : [app/infra-tx-spring/src/test/java/com/kartaguez/pocoma/infra/tx/spring/v3/V3TransactionFoundationPostgresTest.java](../../app/infra-tx-spring/src/test/java/com/kartaguez/pocoma/infra/tx/spring/v3/V3TransactionFoundationPostgresTest.java).

```text
com.kartaguez.pocoma.infra.tx.spring.v3.V3TransactionFoundationPostgresTest#caughtRequiredParticipantFailureStillRollsBackTheOwner
com.kartaguez.pocoma.infra.tx.spring.v3.V3TransactionFoundationPostgresTest#contendingConnectionsWaitForPhysicalRollbackBeforeCommitting
com.kartaguez.pocoma.infra.tx.spring.v3.V3TransactionFoundationPostgresTest#deferredConstraintFailureAtCommitRestoresAllEffects
com.kartaguez.pocoma.infra.tx.spring.v3.V3TransactionFoundationPostgresTest#jpaAndJdbcSharePhysicalTransactionAndBecomeVisibleOnlyAfterCommit
com.kartaguez.pocoma.infra.tx.spring.v3.V3TransactionFoundationPostgresTest#observerFailureAfterCommitDoesNotMeanRollback
com.kartaguez.pocoma.infra.tx.spring.v3.V3TransactionFoundationPostgresTest#rollbackInBothWriteOrdersLeavesNoJpaOrJdbcEffect
```

### INTEGRATION — V3PotCommandCompositionPostgresTest

Source : [app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/v3/V3PotCommandCompositionPostgresTest.java](../../app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/v3/V3PotCommandCompositionPostgresTest.java).

```text
com.kartaguez.pocoma.runtime.command.v3.V3PotCommandCompositionPostgresTest#compositionUsesOneWriteManagerAndExplicitAuthority
```

### INTEGRATION — V3WorkerCompositionPostgresTest

Source : [app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/v3/V3WorkerCompositionPostgresTest.java](../../app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/v3/V3WorkerCompositionPostgresTest.java).

```text
com.kartaguez.pocoma.runtime.command.v3.V3WorkerCompositionPostgresTest#i5_c01
com.kartaguez.pocoma.runtime.command.v3.V3WorkerCompositionPostgresTest#i5_c02
com.kartaguez.pocoma.runtime.command.v3.V3WorkerCompositionPostgresTest#i5_c03
com.kartaguez.pocoma.runtime.command.v3.V3WorkerCompositionPostgresTest#i5_c04
com.kartaguez.pocoma.runtime.command.v3.V3WorkerCompositionPostgresTest#i5_c05
```

## Quarantaine, hors des trois recettes Identity

```text
com.kartaguez.pocoma.runtime.command.v3.V3WorkerResourcesPostgresTest#i5_r05
com.kartaguez.pocoma.runtime.command.v3.V3WorkerResourcesPostgresTest#i5_r12
```

Elles restent incluses dans les commandes non filtrées quand leur module est
sélectionné. Leur XML indépendant contient respectivement un PASS et un FAIL.

## Les 46 fichiers du diff exact

Les empreintes intégrales et tailles sont dans `git-and-files.json` de l'archive
de preuve indépendante. Tous les JSON sont relus ; les neuf archives sont ouvertes
et leurs fichiers rehashés. Les configurations et sources exécutables sont revues
séparément des observations historiques.

- [docs/testing/Reactor_Verification_Policy.md](../../docs/testing/Reactor_Verification_Policy.md)
- [docs/testing/Verification_Optimization_Report.md](../../docs/testing/Verification_Optimization_Report.md)
- [docs/testing/evidence/verification-optimization/archive-checks.json](../../docs/testing/evidence/verification-optimization/archive-checks.json)
- [docs/testing/evidence/verification-optimization/baseline-builds.json](../../docs/testing/evidence/verification-optimization/baseline-builds.json)
- [docs/testing/evidence/verification-optimization/baseline-builds.tar.gz](../../docs/testing/evidence/verification-optimization/baseline-builds.tar.gz)
- [docs/testing/evidence/verification-optimization/baseline-inputs.json](../../docs/testing/evidence/verification-optimization/baseline-inputs.json)
- [docs/testing/evidence/verification-optimization/baseline-physical.json](../../docs/testing/evidence/verification-optimization/baseline-physical.json)
- [docs/testing/evidence/verification-optimization/baseline-physical.tar.gz](../../docs/testing/evidence/verification-optimization/baseline-physical.tar.gz)
- [docs/testing/evidence/verification-optimization/baseline-summary.json](../../docs/testing/evidence/verification-optimization/baseline-summary.json)
- [docs/testing/evidence/verification-optimization/certification-selection.json](../../docs/testing/evidence/verification-optimization/certification-selection.json)
- [docs/testing/evidence/verification-optimization/change-identity/plan.json](../../docs/testing/evidence/verification-optimization/change-identity/plan.json)
- [docs/testing/evidence/verification-optimization/change-identity/provenance.json](../../docs/testing/evidence/verification-optimization/change-identity/provenance.json)
- [docs/testing/evidence/verification-optimization/change-identity/raw.tar.gz](../../docs/testing/evidence/verification-optimization/change-identity/raw.tar.gz)
- [docs/testing/evidence/verification-optimization/change-identity/result.json](../../docs/testing/evidence/verification-optimization/change-identity/result.json)
- [docs/testing/evidence/verification-optimization/comparable-test-observations.json](../../docs/testing/evidence/verification-optimization/comparable-test-observations.json)
- [docs/testing/evidence/verification-optimization/compilation-observations.json](../../docs/testing/evidence/verification-optimization/compilation-observations.json)
- [docs/testing/evidence/verification-optimization/compile-plugin-safety.json](../../docs/testing/evidence/verification-optimization/compile-plugin-safety.json)
- [docs/testing/evidence/verification-optimization/delivery-checks.json](../../docs/testing/evidence/verification-optimization/delivery-checks.json)
- [docs/testing/evidence/verification-optimization/delivery-files.json](../../docs/testing/evidence/verification-optimization/delivery-files.json)
- [docs/testing/evidence/verification-optimization/fast/plan.json](../../docs/testing/evidence/verification-optimization/fast/plan.json)
- [docs/testing/evidence/verification-optimization/fast/provenance.json](../../docs/testing/evidence/verification-optimization/fast/provenance.json)
- [docs/testing/evidence/verification-optimization/fast/raw.tar.gz](../../docs/testing/evidence/verification-optimization/fast/raw.tar.gz)
- [docs/testing/evidence/verification-optimization/fast/result.json](../../docs/testing/evidence/verification-optimization/fast/result.json)
- [docs/testing/evidence/verification-optimization/followup-builds.json](../../docs/testing/evidence/verification-optimization/followup-builds.json)
- [docs/testing/evidence/verification-optimization/followup-builds.tar.gz](../../docs/testing/evidence/verification-optimization/followup-builds.tar.gz)
- [docs/testing/evidence/verification-optimization/incremental-mutation.json](../../docs/testing/evidence/verification-optimization/incremental-mutation.json)
- [docs/testing/evidence/verification-optimization/integration-identity/plan.json](../../docs/testing/evidence/verification-optimization/integration-identity/plan.json)
- [docs/testing/evidence/verification-optimization/integration-identity/provenance.json](../../docs/testing/evidence/verification-optimization/integration-identity/provenance.json)
- [docs/testing/evidence/verification-optimization/integration-identity/raw.tar.gz](../../docs/testing/evidence/verification-optimization/integration-identity/raw.tar.gz)
- [docs/testing/evidence/verification-optimization/integration-identity/result.json](../../docs/testing/evidence/verification-optimization/integration-identity/result.json)
- [docs/testing/evidence/verification-optimization/inventory.json](../../docs/testing/evidence/verification-optimization/inventory.json)
- [docs/testing/evidence/verification-optimization/measurement-drivers.tar.gz](../../docs/testing/evidence/verification-optimization/measurement-drivers.tar.gz)
- [docs/testing/evidence/verification-optimization/module-graph.json](../../docs/testing/evidence/verification-optimization/module-graph.json)
- [docs/testing/evidence/verification-optimization/quarantine-i5_r05/plan.json](../../docs/testing/evidence/verification-optimization/quarantine-i5_r05/plan.json)
- [docs/testing/evidence/verification-optimization/quarantine-i5_r05/provenance.json](../../docs/testing/evidence/verification-optimization/quarantine-i5_r05/provenance.json)
- [docs/testing/evidence/verification-optimization/quarantine-i5_r05/raw.tar.gz](../../docs/testing/evidence/verification-optimization/quarantine-i5_r05/raw.tar.gz)
- [docs/testing/evidence/verification-optimization/quarantine-i5_r05/result.json](../../docs/testing/evidence/verification-optimization/quarantine-i5_r05/result.json)
- [docs/testing/evidence/verification-optimization/quarantine-i5_r12/plan.json](../../docs/testing/evidence/verification-optimization/quarantine-i5_r12/plan.json)
- [docs/testing/evidence/verification-optimization/quarantine-i5_r12/provenance.json](../../docs/testing/evidence/verification-optimization/quarantine-i5_r12/provenance.json)
- [docs/testing/evidence/verification-optimization/quarantine-i5_r12/raw.tar.gz](../../docs/testing/evidence/verification-optimization/quarantine-i5_r12/raw.tar.gz)
- [docs/testing/evidence/verification-optimization/quarantine-i5_r12/result.json](../../docs/testing/evidence/verification-optimization/quarantine-i5_r12/result.json)
- [docs/testing/evidence/verification-optimization/selector-tests.txt](../../docs/testing/evidence/verification-optimization/selector-tests.txt)
- [scripts/testing/inventory-verification.py](../../scripts/testing/inventory-verification.py)
- [scripts/testing/test_verification.py](../../scripts/testing/test_verification.py)
- [scripts/testing/verification-campaigns.json](../../scripts/testing/verification-campaigns.json)
- [scripts/testing/verify.py](../../scripts/testing/verify.py)
