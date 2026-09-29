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

Les preuves permanentes couvrent :

- propagation explicite de `resultingVersion` ;
- publication APPLIED/REJECTED/FAILED ;
- rollback d'un Claim perdu sans outcome ;
- retry sans outcome ni terminal Event ;
- outcome et Event uniques après reprise ;
- discovery metadata-only des Command terminal Events ;
- routing des trois types vers `COMMAND_RESULT` ;
- projection des trois variantes et clé `COMMAND/{commandId}/1` ;
- lecture exacte, ownership et mapping HTTP ;
- migration V17 et validation du schéma complet.

Les canons EPT et PCL restent inchangés : leurs règles sur les Business Events Pot et la chaîne
canonique sont référencées, tandis que CCR ajoute une seconde source durable d'Events de trigger à
la discovery commune.
