# L3/I3 — audit contradictoire indépendant

Référence auditée : `2b3567b1f77051c399772ee7ac787f7dd61ba407`.
Verdict : **FAIL — CORRECTIONS REQUIRED**, exclusivement sur les deux axes demandés.

- [Rapport structuré](../../../v3/L3_I3_Contradictory_Audit_Report.md) : invariants, résultats, quatre findings, limites et recommandation d'arbitrage.
- [Résultats compacts](results.json) : deux MAJOR et deux MINOR, sans nouvelle certification globale.
- [Manifeste de publication](manifest.json) : empreintes de chaque fichier livré et de l'archive originale.
- [Inventaire des sources auditées](sources-manifest.json) : 49 fichiers identiques à leur contenu Git au SHA de référence.
- [Archive originale des preuves](independent-audit.tar.gz) : conservée octet pour octet, 102 789 octets, SHA-256 `f6c083ccc298ec16a722961f55089d14af4a1b15813d0e4bbfc5f3dbf2791a4c`.
- [Manifeste original des membres](evidence-manifest.json) : ses chemins désignent les membres **dans l'archive**, sous `pocoma-l3-i3-audit-evidence/`, et non des fichiers tous extraits dans ce dossier Git.
- [Banc de reproduction](reproduction/README.md) et [source Java indépendante](reproduction/IndependentAudit.java) : copies inchangées des entrées expérimentales, pour revue directe sans extraction. Les chemins absolus correspondent au banc original ; les adapter dans un autre environnement.

L'archive contient les sources expérimentales, résultats et diagnostics expurgés,
journaux des essais (y compris les refus d'environnement et corrections de banc),
deux XML Surefire frais, versions et métadonnées SQL. Les journaux serveur bruts
contenant le marqueur de token test **n'ont pas été exportés** : seule l'observation
expurgée y figure. Aucun token utilisateur, credential ou fichier de configuration
privé n'est inclus. Le marqueur constant dans le code Java est un token synthétique
de test, pas un secret utilisateur. Le serveur jetable a été supprimé.

Le rapport conservé dans l'archive est la version originale remise à l'utilisateur.
La copie sous `docs/v3/` ne change que les liens pour la navigation dans le dépôt ;
son contenu technique et son verdict sont inchangés. Les journaux/manifestes originaux
ne sont pas réécrits. Les copies de scripts en clair ne remplacent pas leur provenance
dans l'archive.

## Vérification sans exécution applicative

Depuis la racine du dépôt :

```python
import hashlib, json, tarfile
from pathlib import Path
root = Path.cwd()
base = root / 'docs/testing/evidence/L3-I3-CT'
publication = json.loads((base / 'manifest.json').read_text())
for entry in publication['files']:
    data = (root / entry['path']).read_bytes()
    assert len(data) == entry['bytes']
    assert hashlib.sha256(data).hexdigest() == entry['sha256']
original = json.loads((base / 'evidence-manifest.json').read_text())
with tarfile.open(base / 'independent-audit.tar.gz', 'r:gz') as archive:
    for entry in original['files']:
        data = archive.extractfile('pocoma-l3-i3-audit-evidence/' + entry['path']).read()
        assert len(data) == entry['bytes']
        assert hashlib.sha256(data).hexdigest() == entry['sha256']
```

Pour reproduire les contrôles PostgreSQL, suivre le protocole dans `reproduction/`
sur un serveur **neuf et jetable**. Les probes finales commettent volontairement
des TRUNCATE sur leur jeu synthétique ; elles ne doivent jamais viser une base existante.

## Portée de cette publication

Impact productif : **NONE**. Slices Maven : **NONE**, documentation/conservation
uniquement. Gate Architecture globale : **NOT REQUIRED / NOT RUN**. Full reactor :
**NOT REQUIRED / NOT RUN**. Aucune nouvelle vérification DB ni relance des six tests
cités par l'audit : leurs résultats restent attribués au SHA de référence. Contrôles
de publication : SHA-256, membres TAR, copies de scripts identiques, adaptation des
liens sans changement technique, `git diff --check` et périmètre Git documentaire.
Franchissement inattendu : **NONE**. Aucun correctif, aucune levée des réserves
historiques ou autorisation I4/L4/L5 n'est déduit du push documentaire.
