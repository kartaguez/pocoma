# Preuves L3/I4 — R-ID-POT

Code gelé : `145f42731a4e35488e9aaa56a7de3c3f453a9231`.
[Rapport autoporteur](../../../v3/L3_I4_Execution_Report.md),
[manifeste et empreintes](../../../v3/L3_I4_Proof_Manifest.json),
[commandes complètes et compteurs](Campaign_Summary.json).
L'acceptation I4 par audit indépendant reste distincte de cette livraison.

## Contributions finales bornées

| Campagne | Tests | Failures | Errors | Skips | Résultat |
|---|---:|---:|---:|---:|---|
| FAST Identity | 65 | 0 | 0 | 0 | PASS |
| CHANGE Identity | 149 | 0 | 0 | 0 | PASS |
| INTEGRATION Identity | 187 | 0 | 0 | 0 | PASS |
| Module Architecture complet, filtre nominatif | 163 | 0 | 0 | 0 | PASS borné, pas gate canonique |
| Contrôles de sélection/provenance | 40 | 0 | 0 | 0 | PASS ; reçus CLI conservés séparément |
| Gate COMMAND + Monolithe non filtrée | 1 789 | 1 | 0 | 1 | FAIL : `i5_r12` |
| Gate Architecture non filtrée | 1 892 | 2 | 0 | 1 | FAIL : `i5_r05`, `i5_r12` ; module Architecture SKIPPED |

Les deux gates non filtrées sont collectées séparément dans `native-command`
et `native-architecture`. Leurs verdicts exacts figurent dans le rapport et
`Campaign_Summary.json`, avec les évaluations initiales et corrections nominatives conservées. Une gate FAIL ou INCONCLUSIVE
ne devient pas PASS parce que les contributions bornées réussissent.

## Contenu et attribution

Chaque répertoire `campaigns/<nom>/` conserve :

- `raw.tar.gz` : octets exacts de l'archive du collecteur, avec log Maven,
  effective POM, découverte, rapports des producteurs et traces physiques fraîches.
- `result.json.gz`, `plan.json.gz`, `provenance.json.gz` : encodage gzip réversible
  des JSON originaux, sans réécriture de leur contenu. Le plan contient les
  invocations attendues et modules producteurs ; la provenance conserve les
  entrées, SHA, outils et état du worktree.
- Pour les gates natives : inventaire supplémentaire des sources, avec limites
  de découverte explicitement conservées.

Seuls les XML sous le dossier neuf `reports/<artifactId>/<producer>-reports`
avec ID d'invocation, module et producteur concordants sont attribués à une
campagne. Les anciens `target/*-reports` ne contribuent pas. Les fichiers gzip
peuvent être lus avec `gzip -dc fichier.json.gz`. Aucun stockage externe n'est
présumé : les bruts nécessaires sont conservés ici sans perte.

[64 reçus nominatifs](Variant_Receipts.json) relient les variantes aux lignes des
traces fraîches ; les libellés de séquence du test opaque sont explicitement dérivés
des appels fixes du source gelé. Les 35 traces I4 de l'INTEGRATION finale sont
indexées par membre d'archive et
SHA-256 dans le manifeste : PRIMARY `l3-i2-evidence/IdentityPotAuthorityPostgresTest-*`,
Worker `l3-i4-evidence/*`, et racines `l3-i3-evidence/l3_i4_*` (réemploi des helpers
historiques, sans changement de leur protocole). Les preuves I2/I3/foundation
réexécutées gardent leurs propres noms et attribution. Chaque trace expose les
phases, identifiants non sensibles, SQL sans binds secrets, bloqueurs physiques
ou relectures selon le scénario. Les méthodes et variantes sont allouées dans
le manifeste ; le seul total JUnit n'est pas l'oracle métier.

A1 conserve la garde anti-effets de `JdbcPotCommandStore`, sans modification.
Les témoins négatifs stables n'accèdent pas au métier Pot. Le SELECT technique
est isolé ; des probes indépendantes contrôlent l'absence de verrou métier Pot,
et l'injection d'une version illicite interdit la completion avec rollback.
Le rapport consigne la décision humaine et la formulation stricte L3 précisée.

## Collecte des gates et limitations

`collect_native.py` exécute exactement les deux ancres autorisées, sans filtre,
avec le profil `verification-provenance`, IDs et dossiers neufs. Il réutilise la
découverte du modèle et les contrôles producteurs de `verify.py`. Les formes
JUnit historiques non prises en charge par le sélecteur restent des limites de
couverture ; elles ne bloquent pas l'exécution demandée et interdisent un PASS
canonique non démontré. Les compteurs observés et producteurs restent vérifiés. Les 1 898 classes
compilées restent identiques avant/après toutes les preuves finales.

`qualify_native.py` réalise une revue nominative complémentaire pour l'unique
classe top-level `CurrentTransactionManagerPostgresTest` déclarée dans le fichier
`JpaTerminalSequencingPostgresTest.java`. Le mapping initial par nom de fichier
omettait ce test effectivement exécuté. `qualified-result.json.gz` conserve le
recalcul, `source-attribution-review.json.gz` son source/empreinte et sa méthode ;
le `result.json.gz` original et les XML restent inchangés. Le total COMMAND
corrigé est 1 789, contre 1 788 dans la première évaluation ; Architecture est
corrigé de 1 891 à 1 892. Failures et skips sont inchangés. Cette revue ne qualifie aucune forme Jupiter supplémentaire ni un
PASS global.

`collect_architecture.py` qualifie séparément les 163 méthodes `@Test` ordinaires
du module entier. Sa revue est limitée aux annotations de fixtures Spring de six
classes explicitement nommées ; aucune annotation Jupiter n'est soustraite,
aucun test ni oracle n'est exclu. Le framework de vérification, les POM et les
recettes ne sont pas élargis pour cela. Cette preuve filtrée ne remplace pas la
gate Architecture non filtrée.

`selector-cli-final.tar.gz` conserve les 40 contrôles d'outillage, dont les vrais
replays Maven dans un checkout jetable. Les XML historiques qu'il contient sont
les témoins adverses de rejet de provenance, jamais des preuves I4 nouvelles.
Les diagnostics initiaux restent disponibles : restrictions Docker du premier
contrôle et feedbacks d'implémentation échoués. Les sources intermédiaires sales
n'ont pas été archivées comme diff complet : ces feedbacks ne sont pas attribués
au code final. Toutes les contributions finales ci-dessus portent sur le code
gelé, avec arbre exécutable propre et empreintes d'entrées concordantes.

## Vérification indépendante de l'intégrité

Depuis la racine du dépôt :

```sh
python3 -B docs/testing/evidence/L3-I4/verify_evidence.py
```

Ce contrôle en lecture seule vérifie tailles/SHA-256, membres exacts des archives,
compteurs XML, ID/module/producteur et correspondance des sources aux blobs Git
du SHA testé. Les blobs sont lus sans transformation de checkout : `mvnw.cmd`
a une règle CRLF déclarée et `git archive` peut en changer la représentation.
Les entrées exécutées sous Unix correspondent aux octets LF du blob Git.
Les empreintes assurent l'intégrité, pas une authentification cryptographique
d'un processus possédant les mêmes droits d'écriture.

PostgreSQL réel 17.11, image Identity épinglée
`postgres@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73`.
CURRENT V3 WRITE V1–V6 / READ technique V1 ; migrations historiques uniquement
transitives des suites natives. Aucune migration, contrainte, index ou grant
productif modifié. Réserves L2, I3 R1–R4 et disponibilité `i5_r05`/`i5_r12`
conservées. Aucune clôture I5/L3, qualification HTTP/READ/consentement externe,
SLO ou certification globale déduite de ce dossier.
