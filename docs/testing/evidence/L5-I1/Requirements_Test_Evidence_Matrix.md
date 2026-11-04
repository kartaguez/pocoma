# L5/I1 — Exigences, tests et preuves

Les identités retenues conservent leur SHA et leur état exact ; les FAIL et interruptions initiaux restent dans les archives. Aucun total ne vaut certification globale.

| ID | Méthode | Exigences / référence du plan | Preuve retenue |
|---|---|---|---|
| H01 | `missingBearer` | F-Auth ; S01-B ; B06 | architecture-08 / `46b94ce6` / PASS |
| H02 | `forgedSignature` | F-Auth ; S01-O ; B31 | architecture-08 / `46b94ce6` / PASS |
| H03 | `wrongIssuer` | F-Auth ; S01-M/O | architecture-08 / `46b94ce6` / PASS |
| H04 | `wrongAudience` | F-Auth ; S01-O | architecture-08 / `46b94ce6` / PASS |
| H05 | `expiredJwt` | F-Auth ; S01-O | architecture-08 / `46b94ce6` / PASS |
| H06 | `notYetValidJwt` | F-Auth ; S01-O | architecture-08 / `46b94ce6` / PASS |
| H07 | `missingEui` | F-Auth ; SEC-01 | architecture-08 / `46b94ce6` / PASS |
| H08 | `ownBindingAndResults` | F-Identity ; SPEC-06§7 ; B01–B05 | architecture-08 / `46b94ce6` / PASS |
| H09 | `bindingTokenAndForeignEui` | F-Identity ; S01-C/D/E/M ; B24 | architecture-08 / `46b94ce6` / PASS |
| H10 | `terminalResultsOwnership` | F-Results ; A10-B/C/D/E/G/I ; B25–B29 | architecture-08 / `46b94ce6` / PASS |
| H11 | `terminalResultsWithoutBinding` | F-Results ; A10-F/K ; B25–B30 | architecture-08 / `46b94ce6` / PASS |
| H12 | `historicalAccessAndDeletion` | F-Pot ; S01-A/F/G/H/I/J, A11-F/G/H/K/N ; B08–B13 | architecture-08 / `46b94ce6` / PASS |
| H13 | `minimumAndReadDependencies` | F-Lag ; R01-A/B/C/D/E/F/H/I/J/K/L ; B10/B14 | architecture-08 / `46b94ce6` / PASS |
| H14 | `coherentSnapshotDuringAdvance` | F-Lag ; R01-G/M, S01-N | architecture-08 / `46b94ce6` / PASS |
| H15 | `completeManifestAndExactBatches` | F-Volume ; S-02/DOM-03 ; B07/B15/B16 | architecture-08 / `46b94ce6` / PASS |
| H16 | `batchCardinalityAndMalformedRefs` | F-Volume ; SUPRA-READ-01 ; B17–B19 | architecture-08 / `46b94ce6` / PASS |
| H17 | `foreignRefsAndMissingSubversions` | F-Pot/F-Lag ; S-02/A11-N ; B20 | architecture-08 / `46b94ce6` / PASS |
| H18 | `navigationCursorAndBindingChange` | F-Navigation ; S-01 ; B21–B24 | architecture-08 / `46b94ce6` / PASS |
| H19 | `navigationMutationsAndPlans` | F-Navigation ; S-01 ; B21/B22 | architecture-08 / `46b94ce6` / PASS |
| H20 | `inputByteBoundaries` | F-Transport ; D4 ; B32 | architecture-08 / `46b94ce6` / PASS |
| H21 | `outputByteBoundaries` | F-Transport ; S-02/D4 ; B32 | architecture-08 / `46b94ce6` / PASS |
| H22 | `lateSerializationFailure` | F-Transport ; S-02 ; B32 | architecture-08 / `46b94ce6` / PASS |
| H23 | `exactDtoAndSecretLogs` | Toutes ; SEC-01/S-02 ; tous B1 | architecture-08 / `46b94ce6` / PASS |
| H24 | `mediaAndUnknownFields` | F-Transport ; D1/D4 ; B05/B32 | architecture-08 / `46b94ce6` / PASS |
| Q01 | `invalidInputBeforeQuota` | AUD-FINAL-I8-01/D4 | architecture-08 / `46b94ce6` / PASS |
| Q02 | `twoReadersAndImmediateRefusal` | D4 Q/Q+1 | architecture-08 / `46b94ce6` / PASS |
| Q03 | `permitsAndConnectionsReleased` | D4/ressources | architecture-08 / `46b94ce6` / PASS |
| Q04 | `sqlTimeoutPhysicalResolution` | D4 durée | architecture-08 / `46b94ce6` / PASS |
| Q05 | `cancellationDuringJdbc` | D4 annulation | architecture-08 / `46b94ce6` / PASS |
| Q06 | `disconnectDuringEncodingAndSend` | D4 copies/transport | architecture-08 / `46b94ce6` / PASS |
| Q07 | `httpInFlightUntilSendEnds` | D4 en-vol | architecture-08 / `46b94ce6` / PASS |
| Q08 | `memoryAndCopies` | D4 mémoire | architecture-08 / `46b94ce6` / PASS |
| Q09 | `jdbcCountsAndCosts` | D4 JDBC/S-02 | architecture-08 / `46b94ce6` / PASS |
| Q10 | `workersProgressWithoutControlBorrow` | Allocation I8/D4 | architecture-08 / `46b94ce6` / PASS |
| C01 | `webRoot` | D1/D6 ; tous B1 | architecture-08 / `46b94ce6` / PASS |
| C02 | `monolithRoot` | D6-composition | architecture-08 / `46b94ce6` / PASS |
| C03 | `legacyComponentsRejected` | D1/D6 | architecture-08 / `46b94ce6` / PASS |
| C04 | `readOnlyAuthorityAndNoWriteFallback` | SPEC-04§1/READ-01 | architecture-08 / `46b94ce6` / PASS |
| C05 | `typedPortsAndDependencies` | §§14/17 politique | architecture-08 / `46b94ce6` / PASS |
| C06 | `brunoFixtureProvenance` | B1/A10 contribution READ | architecture-08 / `46b94ce6` / PASS |

| Scénario | Exigences / contrat | État et origine | Requêtes |
|---|---|---|---|
| B01 activeOwnBinding | E1 ; SPEC-06§7/SEC-01 | PASS — pocoma-l5-i1-optional-port-01 | 1 |
| B02 inactiveOwnBinding | E1 ; SPEC-04§6 | PASS — pocoma-l5-i1-optional-port-01 | 1 |
| B03 absentOwnBinding | E1 ; D1/READ-01 | PASS — pocoma-l5-i1-optional-port-01 | 1 |
| B04 bindingMinimum | E1 ; SPEC-06§7 | PASS — pocoma-l5-i1-optional-port-01 | 2 |
| B05 foreignBindingSelector | E1 ; SEC-01 | PASS — pocoma-l5-i1-optional-port-01 | 2 |
| B06 noBearer | E1–E8 ; SEC-01 S01-B | PASS — pocoma-l5-i1-optional-port-01 | 1 |
| B07 currentComplete | E2 ; READ-01/S-02 | PASS — pocoma-l5-i1-optional-port-01 | 1 |
| B08 exactHistory | E3 ; DOM-01/READ-01 | PASS — pocoma-l5-i1-optional-port-01 | 1 |
| B09 exactBalance | E4 ; DOM-03 | PASS — pocoma-l5-i1-optional-port-01 | 1 |
| B10 forbiddenAndAbsent | E2 ; SEC-01/A11-H | PASS — pocoma-l5-i1-optional-port-01 | 2 |
| B11 deletedCurrent | E2 ; A11-G | PASS — pocoma-l5-i1-optional-port-01 | 1 |
| B12 terminalVersion | E3/E4 ; A11-G | PASS — pocoma-l5-i1-optional-port-01 | 2 |
| B13 historyBeforeDeletion | E3/E4/E5 ; A11-F/N | PASS — pocoma-l5-i1-optional-port-01 | 3 |
| B14 authorizedLag | E2 ; R01-B/D/E/H | PASS — pocoma-l5-i1-optional-port-01 | 3 |
| B15 singleExpense | E5 ; SUPRA-READ-01 | PASS — pocoma-l5-i1-optional-port-01 | 1 |
| B16 fiftyExpenses | E5 ; S-02 | PASS — pocoma-l5-i1-optional-port-01 | 1 |
| B17 emptyBatch | E5 ; SPEC-06§5 | PASS — pocoma-l5-i1-optional-port-01 | 1 |
| B18 oversizedBatch | E5 ; D4/S-02 | PASS — pocoma-l5-i1-optional-port-01 | 1 |
| B19 duplicateBatch | E5 ; SUPRA-READ-01 | PASS — pocoma-l5-i1-optional-port-01 | 1 |
| B20 foreignReference | E5 ; SEC-01/A11-N | PASS — pocoma-l5-i1-optional-port-01 | 1 |
| B21 navigationPages | E6→E2 ; S-01 | PASS — pocoma-l5-i1-optional-port-01 | 4 |
| B22 invalidCursor | E6 ; S-01 | PASS — pocoma-l5-i1-optional-port-01 | 2 |
| B23 pageLimits | E6 ; D4/S-01 | PASS — pocoma-l5-i1-optional-port-01 | 4 |
| B24 mismatchedToken | E2/E6 ; SEC-01 | PASS — pocoma-l5-i1-optional-port-01 | 4 |
| B25 commandCompleted | E7 ; A10-A (lecture seulement) | PASS — pocoma-l5-i1-optional-port-01 | 1 |
| B26 commandRejected | E7 ; A10-B (lecture seulement) | PASS — pocoma-l5-i1-optional-port-01 | 1 |
| B27 identityCompleted | E8 ; A10-C/D (lecture seulement) | PASS — pocoma-l5-i1-optional-port-01 | 2 |
| B28 identityRejected | E8 ; A10-C | PASS — pocoma-l5-i1-optional-port-01 | 1 |
| B29 foreignAndSameUserResult | E7/E8 ; A10-E/G | PASS — pocoma-l5-i1-optional-port-01 | 9 |
| B30 boundedResultPolling | E7/E8 ; A10-H/I/K, part READ | PASS — pocoma-l5-i1-optional-port-01 | 4 |
| B31 invalidBearer | E1–E8 ; S01-O contribution locale | PASS — pocoma-l5-i1-optional-port-01 | 1 |
| B32 transportAndPrivacy | E2/E5 ; D1/D4/S-02 | PASS — pocoma-l5-i1-optional-port-01 | 5 |

Chaque identité complète, oracle, commande et chemin interne d’archive est conservé dans [Qualification_Summary.json](Qualification_Summary.json). Les assertions détaillées figurent au plan I1 §7 et dans les sources versionnées. Les mesures mémoire/JDBC et les réserves d’attribution restent distinctes du résultat JUnit.
