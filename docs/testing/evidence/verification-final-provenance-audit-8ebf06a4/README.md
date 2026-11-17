# Preuves — contre-audit final indépendant de provenance Maven

SHA audité : `8ebf06a477bfb19cbe2bc57be6aae070b05945df`.
Verdict : **PASS WITH RESERVATIONS**. Adoption opérationnelle bornée Identity /
FOUNDATION validée ; aucune certification SPEC-10.

[Rapport autonome](../../../v3/Verification_AUD_VERIFY_001_Final_Provenance_Counter_Audit.md).

- `summary.json` : nominaux, scénarios, commandes, statuts, réserves.
- `manifest.json` : tailles et SHA-256 des fichiers publiés et de chaque membre.
- `final-provenance-audit-evidence.tar.gz` : archive autonome, préfixe `final-audit/`,
  1752 fichiers réguliers. Aucun lien actif embarqué.

Le manifeste ne s'auto-référence pas ; le commit de publication fixe ses octets.
Avant extraction, comparer les fichiers publiés et tous les membres en taille,
nom et SHA-256 avec le manifeste. Les archives historiques de référence restent
inchangées dans le dépôt ; leur vérification complète est enregistrée dans
`final-audit/evidence/reference-integrity.json`.

| Chemin dans l'archive | Contenu |
|---|---|
| `evidence/nominal-foundation/`, `evidence/nominal-fast-identity/` | Nouveaux PASS 28/65, sources d'obligations, XML, modèles effectifs, logs, reçus |
| `evidence/S1-*` à `evidence/S14-*` et logs voisins | Scénarios indépendants, preuves négatives et sorties ; les préflight refusés ne créent pas de dossier |
| `evidence/independent-cli-records.json` | Préparations exactes, argv, codes, transferts, résultats par lancement |
| `evidence/P1-*`, `evidence/producer-probes.json` | Surefire 65 avec neuf JVM distinctes ; Failsafe H2 4 |
| `evidence/independent-checks.json`, `evidence/source/` | Lecture indépendante des identités, modules, hashes, modèles et compteurs ; snapshot des sources du SHA audité |
| `evidence/link-observations.json`, `evidence/S14-manual-unlock.json` | État original des liens et inspection des processus avant déverrouillage manuel |
| `reference-extract/` | Contre-exemple original et captures publiées pertinentes, explicitement historiques |
| `*.py.txt` | Pilotes documentaires exacts, reprise et lecteur indépendant ; aucun changement sous scripts/ |

Restauration dans un nouveau répertoire puis relecture hors du checkout d'origine :

```sh
mkdir /private/tmp/pocoma-final-provenance-review
tar -xzf docs/testing/evidence/verification-final-provenance-audit-8ebf06a4/final-provenance-audit-evidence.tar.gz -C /private/tmp/pocoma-final-provenance-review
python3 -B /private/tmp/pocoma-final-provenance-review/final-audit/verify-results.py.txt /private/tmp/pocoma-final-provenance-review/final-audit
```

Le lecteur n'importe ni la CLI ni le lecteur de l'auteur. Il peut utiliser le
snapshot `evidence/source` sans checkout Git, Java ou Docker. Il distingue les
nouveaux nominaux des reçus historiques 28/65/127/162 et du T9 archivé.
Pour rejouer les injections, créer un **nouveau** clone détaché au SHA audité et
adapter uniquement la constante documentaire `B` des pilotes. Les sorties doivent
être inexistantes. Java/Maven, Docker pour FOUNDATION et les droits de signaler les
processus du clone sont nécessaires. Ne pas rejouer sur un checkout partagé actif.

Les erreurs/interruptions et la première assertion du pilote S9 restent visibles.
S9 conserve un ancien reçu PASS dans un dossier refusé : aucun nouveau verdict,
code 2. Ce PASS historique ne doit jamais être assimilé au résultat du nouveau
lancement. Les signaux réels et copies d'XML n'ont modifié aucun fichier suivi du
checkout audité. Le rapport décrit les limites et la portée exacte de S1.
