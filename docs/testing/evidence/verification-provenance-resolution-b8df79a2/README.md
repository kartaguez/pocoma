# Preuves — correction de provenance AUD-VERIFY-001-R1

Le [rapport de résolution distinct](../../../v3/Verification_AUD_VERIFY_001_Provenance_Resolution.md)
explique les limites et la clôture. Le contre-audit initial reste intact.

- `summary.json` : campagnes fraîches, contrôles, mesures et commandes.
- `manifest.json` : tailles et SHA-256 des fichiers publiés et de tous les membres.
- `provenance-resolution-evidence.tar.gz` : dossier autonome, préfixe `resolution/`.

Le manifeste ne se référence pas lui-même ; le commit de publication l'identifie.
Vérifier les hashes des fichiers publiés puis les noms, tailles et SHA-256 de
chaque membre avant extraction. Une recopie historique utilisée pour une attaque
reste explicitement une attaque ; son ancien PASS ne certifie aucune nouvelle campagne.

```sh
mkdir /private/tmp/pocoma-provenance-review
tar -xzf docs/testing/evidence/verification-provenance-resolution-b8df79a2/provenance-resolution-evidence.tar.gz -C /private/tmp/pocoma-provenance-review
```

Contenu :

| Chemin sous `resolution/` | Rôle |
|---|---|
| `before/` | Reproduction avant correction par le pilote CLI indépendant original : Maven 4, XML 28, faux PASS ; sources des cinq XML historiques et transferts |
| `foundation/`, `fast/`, `change/`, `integration/` | Nouvelles campagnes : plans, provenance, XML produits directement, modèles effectifs, logs et archives brutes |
| `cli-tests/` | T1 à T10 contre CLI/Maven réels ; copies dans target et producteur isolé, fautes XML, refus, interruptions, concurrence, six forks |
| `perf-*`, `performance.json`, `incremental-*`, `incremental.json` | Alternances avant/après et mutation/restauration de bytecode dans un clone jetable |
| `failsafe-probe/` | Expérience Maven directe multi-modules ; aucune nouvelle liaison Failsafe dans la CLI |
| `independent-checks.py`, `independent-checks.json` | Lecteur distinct du collecteur : hashes, XML, propriétés, identités/module, compteurs Maven, inclusions strictes et comparaison historique |
| `source/`, `implementation.diff`, `*.py`, `*.log`, `*manifests*.json` | Sources finales, pilotes, commandes, sorties et vérification des références |

Le snapshot `source/` contient les sources `app/`, les SQL externes utilisés et
les archives de référence nécessaires à la relecture. Après extraction,
`python3 -B /private/tmp/pocoma-provenance-review/resolution/independent-checks.py`
reconstruit les quatre campagnes hors du checkout original. Cette restauration
a été exécutée : mêmes résultats de contre-vérification.

Les reçus nomment les chemins absolus de cette exécution sous
`/private/tmp/pocoma-provenance-resolution`. Pour un rejeu, utiliser un nouveau
répertoire et adapter seulement les racines documentaires des pilotes. Les tests
livrés sont directement rejouables depuis le dépôt :

```sh
python3 -B -m unittest -v scripts/testing/test_verification.py
POCOMA_PROVENANCE_EVIDENCE=/private/tmp/pocoma-provenance-new-tests python3 -B -m unittest -v scripts/testing/test_verification_cli.py
```

La deuxième commande crée son propre clone et exige Java/Maven et Docker.
Les injections sont confinées au clone. Le dossier d'évidence doit être inexistant.
Les anciennes tentatives de mise au point restent identifiées dans `exploration/` ;
elles ne remplacent pas les résultats finaux.
