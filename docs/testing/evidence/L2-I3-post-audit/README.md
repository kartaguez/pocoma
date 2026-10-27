# Preuves fraîches — corrections post-audit I3

Baseline distante : `222e06c21a5f0b0ebe35befc693db02d9b919cd2`. Code/tests figés et testés : **`3ee575940669feb56b73b10122b5a3406bce6a08`**. Les preuves I3 historiques ne sont pas réutilisées. [Rapport dédié](../../../v3/L2_I3_Post_Audit_Corrections_Report.md), [manifeste obligatoire](../../../v3/L2_I3_Post_Audit_Proof_Manifest.json).

| Gate | Testcases XML | Échecs/erreurs | Skips | Méthodes obligatoires | Verdict |
|---|---:|---:|---:|---:|---|
| COMMAND | 1513 | 0 | 1 | 265 | PASS |
| READ | 363 | 0 | 0 | 1 | PASS |
| MONOLITH | 1272 | 0 | 0 | 252 | PASS |
| ARCHITECTURE | 1777 | 0 | 1 | 286 | PASS |

Ces comptes ne s'additionnent pas entre gates. Le seul skip, COMMAND/ARCHITECTURE, est `TerminalSequencingPostgresTest.zExploratoryContentionSeparateFromCorrectness`, exploratoire historique hors obligations I3 ; il ne vaut pas PASS. Les quatre gates utilisent exactement les commandes canoniques sans filtre/exclusion depuis `app/`, enregistrées dans [Gate-index.json](Gate-index.json). Pas de `./mvnw test` full reactor.

Chaque archive `<gate>-3ee57594.tar.gz` contient `<gate>/result.json`, `maven.log`, les XML Surefire frais et les traces PostgreSQL physiques fraîches sous `physical/`. `result.json` conserve toutes les méthodes/états, horodatages, chemins et SHA256 d'artefacts ; Gate-index conserve les empreintes des archives et reçus. [audit-archives.py](audit-archives.py) recontrôle indépendamment les empreintes, XML/méthodes exactes, sous-variantes physiques, fraîcheur et égalité app/scripts avec le SHA testé ; sa sortie est [Integrity-check.json](Integrity-check.json).

Rejouer depuis la racine (destination neuve, code app/scripts propre) :

```sh
python3 -B docs/testing/evidence/L2-I3-post-audit/run-gate.py command /private/tmp/i3-audit-new-command
python3 -B docs/testing/evidence/L2-I3-post-audit/run-gate.py read /private/tmp/i3-audit-new-read
python3 -B docs/testing/evidence/L2-I3-post-audit/run-gate.py monolith /private/tmp/i3-audit-new-monolith
python3 -B docs/testing/evidence/L2-I3-post-audit/run-gate.py architecture /private/tmp/i3-audit-new-architecture
python3 -B docs/testing/evidence/L2-I3-post-audit/audit-archives.py
```

[Code-freeze.json](Code-freeze.json) : SHA Git, empreintes du code figé et environnement Java. [Invariant-preservation.json](Invariant-preservation.json) : corps I2/inspection inchangés, deux seuls fichiers productifs modifiés. [Non-regression-inventory.json](Non-regression-inventory.json) : test `i3_capacity_uncertainty` identique octet pour octet, toutes les obligations et traces historiques préservées. [Observed-interleavings.json](Observed-interleavings.json) : extraits verbatim datés des traces fraîches COMMAND, y compris PID/XID de cession ouverte, tickets, rollback et ACK perdus. Les snapshots complets restent dans les archives.

[Diagnostic-commands.json](Diagnostic-commands.json) liste les commandes et résultats de reproduction/ciblage. `diagnostics.tar.gz` conserve logs, deux XML de reproduction échoués et empreintes ; les reproductions utilisaient les nouveaux tests encore non commités contre la production de baseline, puis les corrections avant gel. Les diagnostics ne remplacent aucun gate. Les tests du collecteur (6), manifeste L2 et 21 vecteurs de routage sont également archivés.

[Preserved-user-worktree.txt](Preserved-user-worktree.txt) conserve l'état du checkout utilisateur laissé intact. [Delivery-files.txt](Delivery-files.txt) est la liste exacte des fichiers livrés depuis la baseline. Les scripts de test utilisent des rôles/credentials de fixtures jetables ; aucune donnée d'un environnement déployé n'est incluse.

Verdict : **PASS local borné des corrections I3**. Ressources d'un Worker actif : **I5 INCONCLUSIVE** ; pas d'activation, d'I4/I5, de G1/G2/G3 ni d'ACCEPTED L2. Les migrations historiques figurant dans les logs sont celles des tests transitifs existants imposés par les gates ; aucune migration nouvelle ni base existante modifiée.
