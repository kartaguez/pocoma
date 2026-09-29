# CCR — Command Completion Result — implementation tracker

La référence normative est [`Step_Canon.md`](Step_Canon.md).

| Lot | Sujet | Statut |
|-----|-------|--------|
| CCR.1 | Command Consumption proof | DONE |
| CCR.2 | Durable Command Outcome | DONE |
| CCR.3 | Command terminal Events | DONE |
| CCR.4 | COMMAND_RESULT projection | DONE |
| CCR.5 | Minimal READ exposure | DONE |
| CCR.6 | WRITE result-query cleanup | NOT APPLICABLE |
| CCR.7 | E2E proofs | DONE |

## CCR.1 — Command Consumption proof

Le chemin Execute existant est conservé. Les tests PostgreSQL prouvent acquisition, transaction
fenced, retry, terminalisation et takeover. Le seul ajout générique est un
`FencedDurableEffect` optionnel lors d'un failure terminal ; les resources sans cet effet gardent
le comportement antérieur.

## CCR.2 — Durable Command Outcome

La migration V17 ajoute `command_outcomes`, avec une ligne au plus par Command et des contraintes
de forme par issue. Les use cases Command réussis rendent désormais leur cible Pot et leur version
explicitement. Retry n'écrit aucun outcome.

## CCR.3 — Command terminal Events

V17 ajoute `command_terminal_events`. Outcome et Event sont publiés ensemble par
`JdbcCommandOutcomeAdapter`. Les trois types terminaux sont routés explicitement.

## CCR.4 — COMMAND_RESULT projection

`engine-command-result` porte définition, input, loader contract, projector et lecture interprétée.
Le runtime ProjectionTask déclare ce producer aux côtés des producers canoniques existants.

## CCR.5 — Minimal READ exposure

Le runtime web compose la lecture exacte canonique et expose uniquement
`GET /api/v1/command-results/{commandId}` avec contrôle de propriété depuis la projection `READY`.
Une correction post-implémentation a remplacé le contrat initial `202 NOT_READY` / `503
PROJECTION_FAILED` par un contrat fondé sur la visibilité : seul un résultat terminal `READY`
appartenant au caller retourne `200`; tout autre cas retourne `404`. Aucun index ou lookup
d'ownership intermédiaire n'a été introduit et la frontière WRITE/READ reste stricte.

## CCR.6 — WRITE result-query cleanup

`NOT APPLICABLE` : l'audit n'a trouvé aucun endpoint WRITE de polling/résultat à supprimer.

## CCR.7 — Proofs

La matrice suivante identifie les preuves durables qui rendent CCR clôturable. `PROVED` signifie
que le test cité échoue si l'invariant est rompu ; les tests PostgreSQL observent l'état commité,
pas l'ordre d'appels interne.

| Invariant | Preuve permanente | Niveau | État |
|-----------|-------------------|--------|------|
| admission durable, sans effet CCR prématuré | `CommandAdmissionPostgresTest.authenticatedProvisionedIdentityDurablyAcceptsWithoutAnySynchronousEffects` | HTTP + PostgreSQL | PROVED |
| atomicité APPLIED, `resultingVersion` explicite | `CommandConsumptionPostgresTest.discoversAcquiresReloadsAndTerminalizesASuccessfulCommand` | PostgreSQL | PROVED |
| atomicité REJECTED, sans mutation partielle | `CommandCompletionE2EPostgresTest.admissionThroughCommandEventTaskAndExactReadProducesAllTerminalResultsDurably` | E2E PostgreSQL | PROVED |
| rollback Execute puis publication FAILED fenced | `CommandConsumptionPostgresTest.unexpectedRuntimeFailsImmediatelyWithoutRetry` et E2E CCR | PostgreSQL | PROVED |
| claim perdu pendant Execute | `CommandConsumptionPostgresTest.takeoverRollsBackTheLosingCommandAndOnlyTheWinnerCommits` | concurrence PostgreSQL | PROVED |
| claim perdu pendant Finalize FAILED | `CommandConsumptionPostgresTest.staleClaimCannotPublishATerminalFailureOutcomeOrEvent` | PostgreSQL | PROVED |
| retry sans outcome, terminal Event, task ou résultat public | `CommandConsumptionPostgresTest.recognizedTransientSqlFailureSchedulesTheGenericRetry` et `CommandResultTest` | PostgreSQL + unit | PROVED |
| fairness : C1 retryable ne bloque pas C2 du même Pot | `CommandConsumptionPostgresTest.retryableOlderCommandDoesNotBlockLaterEligibleCommandForTheSamePot` | runtime PostgreSQL | PROVED |
| unicité outcome/Event et reprise | `CommandCompletionE2EPostgresTest` relance les deux workers puis vérifie les cardinalités | E2E PostgreSQL | PROVED |
| trois terminal Events vers `COMMAND/{commandId}/1` | `CommandCompletionE2EPostgresTest` et `PocomaProjectionMaterializationPolicyTest` | E2E PostgreSQL + unit | PROVED |
| task charge l'outcome durable et publie les trois variantes | `CommandCompletionE2EPostgresTest` ; les terminal Events ne portent que l'identité | E2E PostgreSQL | PROVED |
| idempotence `projection_root` / artifact | redémarrage du task worker dans `CommandCompletionE2EPostgresTest` | E2E PostgreSQL | PROVED |
| READ ownership et visibilité 404 | `CommandResultTest` et `CommandResultControllerTest` | unit HTTP | PROVED |
| GET limité au READ exact | `HexagonalArchitectureTest.commandResultReadDependsOnlyOnTheExactReadProjectionPath` | architecture | PROVED |
| APPLIED, REJECTED et FAILED bout-en-bout | `CommandCompletionE2EPostgresTest` | E2E PostgreSQL | PROVED |

Le test E2E part de l'admission normale et ne seed que l'identité externe nécessaire à
l'authentification. `recorded_commands`, les Consumptions, la mutation et le Business Event,
`command_outcomes`, `command_terminal_events`, les `projection_tasks`, `projection_root` et les
artifacts sont tous produits par les composants de production. Les contextes Event et
ProjectionTask sont fermés puis recréés sur le même PostgreSQL pour prouver la reprise depuis le
seul état durable, sans duplication logique.

Les canons EPT et PCL restent inchangés : leurs règles sur les Business Events Pot et la chaîne
canonique sont référencées, tandis que CCR ajoute une seconde source durable d'Events de trigger à
la discovery commune.
