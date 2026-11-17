# Preuves ciblées L4 — recovery AUD-L4-01/02

[Rapport et décision](../../../v3/L4_Recovery_Audit_Resolution.md),
[manifestes, argv et diagnostics](Manifest.json), [attribution des entrées](Input_Attribution.json).

`runs/before` conserve le FAIL contradictoire (3 tests, un échec PotVersion) au SHA propre
`23e9c1758b36d166e8e0ad02a5bf77e715d32a06`, production identique à `b3b7b8c4`.
`runs/after` conserve le PASS (5 invocations JUnit, dont un wrapper des trois conformités)
au SHA propre `f30891541787decf308675aeabd57a0577db11c6`.

Chaque run contient reçu, plan, provenance et mesures. `raw.tar.gz` préserve les XML frais
avec invocationId, modèle effectif, logs/processus Maven et traces physiques SQL/PID/XID
des fixtures. Les hashes du reçu ont été vérifiés contre chaque membre archivé. Les chemins
temporaires enregistrés identifient l'exécution ; la rétention ne dépend pas de leur survie.
`Archive_Index.json` inventorie tous les fichiers de ce dossier sauf lui-même, taille/SHA-256.

`run-targeted.py` utilise le vérificateur existant inchangé ; la sélection `counterexamples`
contient trois méthodes, `qualification` y ajoute la conformité bornée et le commit incertain.
Aucun retry. Les neuf campagnes historiques et leurs archives restent intactes. Ces preuves
ne sont ni une gate canonique ni une certification SPEC-10 ; les obligations I8 demeurent.
