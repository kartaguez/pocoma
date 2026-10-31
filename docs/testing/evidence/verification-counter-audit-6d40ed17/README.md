# Preuves — contre-audit AUD-VERIFY-001 au SHA 6d40ed17

**CHANGES REQUIRED — faux PASS démontré**, voir le
[rapport autonome](../../../v3/Verification_AUD_VERIFY_001_Independent_Counter_Audit.md).
Le PASS de `c4-cli-replay` est le défaut recherché : quatre tests frais seulement,
24 anciens résultats recopiés. Il ne valide pas FOUNDATION.

- [summary.json](summary.json) : résultats et verdict indépendant.
- [manifest.json](manifest.json) : hashes des créations et de chaque membre.
- [counter-audit-evidence.tar.gz](counter-audit-evidence.tar.gz) : bruts et pilotes
  documentaires, préfixe `counter-audit/`.

| Pièce dans l’archive | Rôle |
|---|---|
| `evidence/c1-hidden-filter.json`, `implicit-environment-probes.json` | Refus CLI réel du contre-exemple exact et des cinq variables interdites |
| `evidence/c2-foundation/` | Première tentative, échec Docker sandbox conservé |
| `evidence/c2-foundation-docker/`, `c5-fast-identity/` | Nominal 28/28 et FAST 65/65 : plans, provenance, logs, modèle effectif, XML archivés et hashes |
| `evidence/negative-assessments.json` | Suppression, ajout, doublon à total égal, rapport tronqué/absent, invocation boolean et chemins de modules |
| `evidence/c4-partial-no-replay/`, `c4-stale-preserved-mtime/`, `c4-stale-copyfile-fresh-mtime/`, `negative-pipeline.json` | Premiers probes par injection explicite à la frontière du sous-processus ; Maven réel, restauration contrôlée |
| `evidence/c4-cli-implicit-base-filter/`, `c4-cli-replay/`, `cli-replay-results.json` | Preuve décisive par la CLI inchangée, sans monkeypatch : filtre externe seul rejeté ; filtre + anciens XML recopiés accepté |
| `baseline-xml/` | XML du nominal précédent utilisés pour la restauration, octets inchangés, mtime ancien |
| `cli-replay-probe.py.txt`, `audit-probes.py.txt`, `verify-receipts.py.txt` | Sources documentaires des pilotes externes ; aucune installation dans `scripts/` |
| `evidence/receipt-verification.json`, `verify-receipts.log`, `independent-reconstruction.json` | Vérification sans importer le sélecteur : hashes, sources, identités, multiplicité, résumé Maven et origine des 24 résultats |
| `evidence/source-discovery-limit.json`, `QualifiedTest.java.txt` | Réserve sur l’annotation Java qualifiée, fixture hors dépôt |
| `evidence/author-regression-tests.log` | 30 tests de l’auteur PASS, indépendants des nouveaux contre-exemples |
| `evidence/fix.diff`, `initial-state.txt`, `remote-before-publication.txt` | Diff exact audité, checkout initial propre et SHA distant réellement lu |

Restaurer dans une destination neuve :

```sh
mkdir /private/tmp/pocoma-counter-audit-review
tar -xzf docs/testing/evidence/verification-counter-audit-6d40ed17/counter-audit-evidence.tar.gz \
  -C /private/tmp/pocoma-counter-audit-review
```

Pour rejouer uniquement la preuve CLI dans un **nouveau** répertoire temporaire B :
cloner le dépôt dans `B/checkout`, détacher au SHA
`6d40ed17496709ce75175c4651dbaaaedc78343e`, puis copier depuis l’archive
`baseline-xml/`, `evidence/c2-foundation-docker/` et `cli-replay-probe.py.txt` sous B,
en conservant ces chemins relatifs. Les dossiers `evidence/c4-cli-*` doivent être
absents. Exécuter `python3 -B B/cli-replay-probe.py.txt`. Le script ne lance que
FOUNDATION filtrée H2 ; le nominal PostgreSQL ancien reste explicitement attribué
à son run. Il crée un `external-maven-base/.mvn/jvm.config` temporaire **hors dépôt**
et recopie cinq XML dans les seuls `target` du clone. Ne pas l’exécuter contre un
checkout de travail partagé.

La vérification indépendante complète `verify-receipts.py.txt` requiert également
`evidence/c5-fast-identity/` et les sources du checkout au SHA audité. Les chemins
absolus enregistrés dans les anciens reçus restent ceux de l’exécution originale ;
ils ne constituent pas de nouveaux résultats sur le checkout de relecture.
Les artefacts historiques accessibles dans le dépôt restent inchangés.
