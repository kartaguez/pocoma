# L7-LB Jalon 1 — qualification

État avant gel technique : **LOCAL_PLAYGROUND_PARTIAL**. Implémentation livrée ;
campagne indépendante sur SHA figé en préparation. Les premiers essais dirty
ont obtenu READY et PG01/JUnit PASS ; ils ne certifient pas le commit figé.

Référence initiale : `2a4e7fd8a79d798b4bcb983a0d5f9466bc5241aa`.
Branche : `feat/l7-lb-local-playground`. Aucun tag de référence.

Protocole reproductible : `python3 -B scripts/local/test_installation.py
--second-checkout /absolute/fresh/clone --output /absolute/new/evidence`.
Tests de contrôle : `python3 -B -m unittest discover -s scripts/local -p 'test_*.py'`.
La qualification finale doit remplacer cet état préparatoire par les résultats
observés, sans effacer les essais échoués ni leur attribution.

Aucune acceptation humaine ni V3_REFERENCE_BASELINE_ACCEPTED.
Voir [implémentation](L7_Local_Playground_Implementation_Report.md),
[guide de lancement](L7_Local_Playground_Quickstart.md) et
[réserves](L7_Local_Playground_Reservations.md).
