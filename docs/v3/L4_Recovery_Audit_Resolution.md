# L4/I3–I7 — instruction ciblée des preuves de recovery

## Frontière déclarée avant correction

Départ : `b3b7b8c4f0ebc7c1bfb946c614359a4a8ea787bf`, branche `feat/v3-architecture` propre.
PROJECTION principale ; BINDING secondaire (preuve CurrentBinding), sans changement de son
runtime. Impact prévu : oracle PotVersion/CurrentPot et contre-tests des trois participants.
Interdits : moteur L2, owners, frontières transactionnelles, producteurs WRITE, identifiants,
familles, dépendances entre projections et activation productive. Aucun changement de module
ou de dépendance ; gate Architecture supplémentaire NOT REQUIRED, full reactor NOT REQUIRED.
Les gates historiques G-I3…G-I7/Architecture restent reportées à I8.

Ancrage canonique : `./mvnw -pl runtime-task-consumption-worker -am test` (PROJECTION),
`./mvnw -pl runtime-binding-consumption-worker -am test` (BINDING). Ces gates non filtrées ne
sont pas exécutées : mandat explicite de qualification strictement ciblée. Sélection effective
sur le premier ancrage, qui contient la composition réelle des trois participants, sans runtime
BINDING modifié. PostgreSQL CURRENT V3 via fixtures existantes, sans nouvelle migration,
sans chaîne historique V2 ni baseline TRUSTED inventée. Escalade : besoin de changement L2,
de transaction ou de contrat majeur => arbitrage, aucune extension silencieuse.

## Instruction contradictoire préalable

SPEC-04 §3/8/10 et SPEC-10 PRJ-01 exigent ensemble les versions historiques exactes et
l'avance conditionnelle CurrentPot. `MaterializePotVersion.prepareRecovery()` contrôle les
racines et sous-versions exactes, mais `JdbcPotVersionStore.evidence()` omet CurrentPot.
Le writer et `requireStored()` contrôlent déjà le pointeur dans la transaction de publication.
Un préfixe durable sans pointeur n'est donc pas produit par une finalisation L2 normale ; il
requiert corruption/injection ou restauration dissociée interdite. R9-04/09 impose néanmoins
de ne pas accepter une preuve incohérente : insuffisance d'oracle à reproduire avant correction.

SPEC-04 §5 impose explicitement de n'avancer Visibility que si V est supérieure à la garde ;
§6 impose CurrentBinding monotone. SPEC-05 §1/8 et SPEC-10 P03/ID-02-F admettent les arrivées
hors ordre. Un ancien événement peut donc être traité sans écraser l'état supérieur ; aucun
reçu métier par événement n'est exigé pour ces deux projections mutables.

SPEC-05 §3 et consolidation L2 R9-01/04/06/07/09 : le participant démontre l'effet métier
admissible et sa cohérence ; L2 vérifie publication pertinente, checkpoint de la bonne partition
et slot dans le snapshot READ final. SATISFIED + slot présent = NOT_COMPLETED ; SATISFIED +
checkpoint couvrant + slot absent = COMPLETED dérivé. V2 seule ne prouve jamais l'exécution
historique de V1. Sous les invariants d'enrôlement/suppression, checkpoint + absence démontrent
la terminalisation, y compris celle d'un traitement obsolète. Supprimer administrativement un
slot viole ces hypothèses ; aucune observation sans nouveau journal ne peut reconstituer ce
fait. Ne pas inventer un reçu durable pour distinguer deux états intentionnellement identiques.

Les sources exactes de V2 sont relues dans la préparation ; l'observation finale compare tout
l'ensemble visible ou toute l'EUI (identité, version, User, token). Une avance au-delà de la
version préparée donne UNAVAILABLE. Un checkpoint insuffisant donne INCONSISTENT ; un slot
présent reste non terminalisé. Les conformités archivées et le test de commit incertain ont
été lus ; leurs PASS restent attribués à leurs anciennes entrées, jamais rafraîchis implicitement.

## Exécution

En cours : contre-tests avant correction, puis correction et qualification ciblée au SHA propre.
Nominal estimé UNKNOWN ; aucun plafond ni retry automatique. Résultats, durées et réserves
seront consignés ici après les observations PostgreSQL réelles.
