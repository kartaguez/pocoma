# Bruno B1 — Identity and READ

**État L7.2.** B3 désigne le dossier historique du use case Link retiré par `GO_L7_I2_I3_FULL_EXECUTION`. La recette d’installation joue B2, B4, B501 et B502, avec les oracles B1 spécialisés sous fixture. [Lancement et portées](README.md).

**32 scénarios logiques. READ_FIXTURE — NO PUBLIC ADMISSION PROOF.** Les états de résultat et de binding sont injectés avant le HTTP par une autorité de fixture distincte. Aucun parcours Registration/Link/Invitation public.

| Scénario | Requête principale | Oracle / JUnit |
|---|---|---|
| B01 activeOwnBinding | `/me/binding` → 200 | Plan I1 §6.2 ; H01–H24, Q01–Q10, C01–C06 ; assertions dans le fichier et appels secondaires |
| B02 inactiveOwnBinding | `/me/binding` → 200 | Plan I1 §6.2 ; H01–H24, Q01–Q10, C01–C06 ; assertions dans le fichier et appels secondaires |
| B03 absentOwnBinding | `/me/binding` → 404 | Plan I1 §6.2 ; H01–H24, Q01–Q10, C01–C06 ; assertions dans le fichier et appels secondaires |
| B04 bindingMinimum | `/me/binding?minVersion=2` → 503 | Plan I1 §6.2 ; H01–H24, Q01–Q10, C01–C06 ; assertions dans le fichier et appels secondaires |
| B05 foreignBindingSelector | `/me/binding?eui=other` → 400 | Plan I1 §6.2 ; H01–H24, Q01–Q10, C01–C06 ; assertions dans le fichier et appels secondaires |
| B06 noBearer | `/me/binding` → 401 | Plan I1 §6.2 ; H01–H24, Q01–Q10, C01–C06 ; assertions dans le fichier et appels secondaires |
| B07 currentComplete | `/pots/00000000-0000-0000-0000-000000000300?minVersion=1` → 200 | Plan I1 §6.2 ; H01–H24, Q01–Q10, C01–C06 ; assertions dans le fichier et appels secondaires |
| B08 exactHistory | `/pots/00000000-0000-0000-0000-000000000300/versions/1` → 200 | Plan I1 §6.2 ; H01–H24, Q01–Q10, C01–C06 ; assertions dans le fichier et appels secondaires |
| B09 exactBalance | `/pots/00000000-0000-0000-0000-000000000300/versions/1/balance` → 200 | Plan I1 §6.2 ; H01–H24, Q01–Q10, C01–C06 ; assertions dans le fichier et appels secondaires |
| B10 forbiddenAndAbsent | `/pots/00000000-0000-0000-0000-000000000301?minVersion=9223372036854775807` → 404 | Plan I1 §6.2 ; H01–H24, Q01–Q10, C01–C06 ; assertions dans le fichier et appels secondaires |
| B11 deletedCurrent | `/pots/00000000-0000-0000-0000-000000000302` → 404 | Plan I1 §6.2 ; H01–H24, Q01–Q10, C01–C06 ; assertions dans le fichier et appels secondaires |
| B12 terminalVersion | `/pots/00000000-0000-0000-0000-000000000302/versions/2` → 404 | Plan I1 §6.2 ; H01–H24, Q01–Q10, C01–C06 ; assertions dans le fichier et appels secondaires |
| B13 historyBeforeDeletion | `/pots/00000000-0000-0000-0000-000000000302/versions/1` → 200 | Plan I1 §6.2 ; H01–H24, Q01–Q10, C01–C06 ; assertions dans le fichier et appels secondaires |
| B14 authorizedLag | `/pots/00000000-0000-0000-0000-000000000303` → 503 | Plan I1 §6.2 ; H01–H24, Q01–Q10, C01–C06 ; assertions dans le fichier et appels secondaires |
| B15 singleExpense | `/pots/00000000-0000-0000-0000-000000000300/versions/1/expenses/query` → 200 | Plan I1 §6.2 ; H01–H24, Q01–Q10, C01–C06 ; assertions dans le fichier et appels secondaires |
| B16 fiftyExpenses | `/pots/00000000-0000-0000-0000-000000000300/versions/1/expenses/query` → 200 | Plan I1 §6.2 ; H01–H24, Q01–Q10, C01–C06 ; assertions dans le fichier et appels secondaires |
| B17 emptyBatch | `/pots/00000000-0000-0000-0000-000000000300/versions/1/expenses/query` → 400 | Plan I1 §6.2 ; H01–H24, Q01–Q10, C01–C06 ; assertions dans le fichier et appels secondaires |
| B18 oversizedBatch | `/pots/00000000-0000-0000-0000-000000000300/versions/1/expenses/query` → 400 | Plan I1 §6.2 ; H01–H24, Q01–Q10, C01–C06 ; assertions dans le fichier et appels secondaires |
| B19 duplicateBatch | `/pots/00000000-0000-0000-0000-000000000300/versions/1/expenses/query` → 400 | Plan I1 §6.2 ; H01–H24, Q01–Q10, C01–C06 ; assertions dans le fichier et appels secondaires |
| B20 foreignReference | `/pots/00000000-0000-0000-0000-000000000300/versions/1/expenses/query` → 404 | Plan I1 §6.2 ; H01–H24, Q01–Q10, C01–C06 ; assertions dans le fichier et appels secondaires |
| B21 navigationPages | `/me/pots?limit=100` → 200 | Plan I1 §6.2 ; H01–H24, Q01–Q10, C01–C06 ; assertions dans le fichier et appels secondaires |
| B22 invalidCursor | `/me/pots?cursor=malformed` → 400 | Plan I1 §6.2 ; H01–H24, Q01–Q10, C01–C06 ; assertions dans le fichier et appels secondaires |
| B23 pageLimits | `/me/pots?limit=0` → 400 | Plan I1 §6.2 ; H01–H24, Q01–Q10, C01–C06 ; assertions dans le fichier et appels secondaires |
| B24 mismatchedToken | `/pots/00000000-0000-0000-0000-000000000300` → 404 | Plan I1 §6.2 ; H01–H24, Q01–Q10, C01–C06 ; assertions dans le fichier et appels secondaires |
| B25 commandCompleted | `/commands/00000000-0000-0000-0000-000000000401/result` → 200 | Plan I1 §6.2 ; H01–H24, Q01–Q10, C01–C06 ; assertions dans le fichier et appels secondaires |
| B26 commandRejected | `/commands/00000000-0000-0000-0000-000000000402/result` → 200 | Plan I1 §6.2 ; H01–H24, Q01–Q10, C01–C06 ; assertions dans le fichier et appels secondaires |
| B27 identityCompleted | `/identity/requests/00000000-0000-0000-0000-000000000403/result` → 200 | Plan I1 §6.2 ; H01–H24, Q01–Q10, C01–C06 ; assertions dans le fichier et appels secondaires |
| B28 identityRejected | `/identity/requests/00000000-0000-0000-0000-000000000404/result` → 200 | Plan I1 §6.2 ; H01–H24, Q01–Q10, C01–C06 ; assertions dans le fichier et appels secondaires |
| B29 foreignAndSameUserResult | `/commands/00000000-0000-0000-0000-000000000401/result` → 404 | Plan I1 §6.2 ; H01–H24, Q01–Q10, C01–C06 ; assertions dans le fichier et appels secondaires |
| B30 boundedResultPolling | `/commands/00000000-0000-0000-0000-000000000405/result` → 404 | Plan I1 §6.2 ; H01–H24, Q01–Q10, C01–C06 ; assertions dans le fichier et appels secondaires |
| B31 invalidBearer | `/me/binding` → 401 | Plan I1 §6.2 ; H01–H24, Q01–Q10, C01–C06 ; assertions dans le fichier et appels secondaires |
| B32 transportAndPrivacy | `/pots/00000000-0000-0000-0000-000000000304` → 503 | Plan I1 §6.2 ; H01–H24, Q01–Q10, C01–C06 ; assertions dans le fichier et appels secondaires |

## B2 — L5/I2 admissions publiques

Dossier `02 Admission and Commands`, B201–B224. B201 et B223 couvrent Registration inconnue/libre ; B202–B212 les onze commandes ; B213–B216 reprise terminale, conflit, JSON équivalent et portées ; B217–B218 refus sans admission et rejet métier différé ; B219 le lot atomique SELF de 101 ; B220 Unlink autre EUI/soi, autorité périmée et propriété des résultats ; B221 ferme Link/Invitation ; B222 média/corps ; B224 ownership READ après Unlink/re-Registration.

Fixture exécutable : `V3AdmissionFlowPostgresTest#brunoB2PublicProtocol`, dans T2. JWT/JWKS locaux signés ; fournisseur AuthN réel non qualifié. Le seul Link est interne contrôlé L3, jamais une admission publique ou une preuve I3. Le helper `lib/i2-admission.js` conserve un reçu par scénario et compte les appels de polling réels. Les oracles SQL de T2 restent indépendants. Sources et package-lock empreintés avant/après ; reporters sans corps/en-têtes et scan des exports. Modalités et limites : [recette I2](../../docs/v3/L5_I2_Admission_Operations.md).
