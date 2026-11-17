# L5 — Calibration D4-READ — preuves isolées

Source : `5193c540c5be2ea6bf31d4fe159c02490ab352aa`, 4 novembre 2026. Une campagne synthétique de **2,152463 secondes** ; aucune relance, aucun PostgreSQL, Maven, Spring, HTTP, T1/B1 ou gate globale. **D4 PARTIALLY CALIBRATED — ADDITIONAL DECISION REQUIRED**. Aucun nombre nouveau ratifié, aucun GO I1.

Voir l'[acte humain](../../../v3/L5_D1_D6_Ratification_and_D4_Calibration_Decision.md) et le [rapport](../../../v3/L5_D4_Read_Calibration_Report.md). Les décisions et propositions n'ont pas le même statut.

| Fichier | Rôle |
|---|---|
| [Calibration.java](Calibration.java) | Reçu exécutable de l'expérience synthétique ; deux sources READ pures existantes réutilisées, sérialiseurs locaux de mesure, aucune implémentation HTTP |
| [run_once.py](run_once.py) | Pilote unique avec budget monotone, préflight, compilation isolée, collecte des PID/terminaisons et empreintes ; refuse une seconde campagne au même emplacement |
| [environment.json](environment.json) | Environnement non sensible, JAR/outils, SHA-256 sources/pilote et toutes les entrées suivies `app/` + `scripts/v3/` avant campagne |
| [Source_Provenance.json](Source_Provenance.json) | Référentiel documentaire/code et état Git initial/final attribués au SHA de départ |
| [campaign.json](campaign.json) | Compteur mural cumulé, argv exécutés normalisés, PID, exit codes et absence physique des groupes de processus |
| [compile.log](compile.log) | Sortie brute javac, vide : compilation sans diagnostic |
| [measurements.json](measurements.json) | Sortie brute JVM ; tailles UTF-8 et cinq échantillons préparés, CPU/mural/allocations, construction, tampon et concurrence d'encodage |
| [Calculated_Summary.json](Calculated_Summary.json) | Dérivations arithmétiques des résultats bruts et des constantes source ; aucune nouvelle mesure |
| [Documentary_Verification.json](Documentary_Verification.json) | Contrôles de livraison documentaire, chemins/liens/empreintes et absence de modification applicative |
| [SHA256SUMS.json](SHA256SUMS.json) | Empreintes de tous les autres fichiers du dossier, y compris scripts et reçus ; ne s'auto-hache pas |

Les tailles sont **MEASURED sur la représentation synthétique**, jamais une mesure du contrôleur futur. Les allocations de thread sont cumulées, sans confusion avec heap retenu/pic. Les calculs de SELECT et réserve pool sont **CALCULATED**, pas des observations SQL. Mémoire HTTP et délais proposés sont **ESTIMATED / NOT MEASURED** pour leur preuve physique. Aucune compression/transmission mesurée.

Commande réellement exécutée depuis le dépôt : `python3 docs/testing/evidence/L5-D4/run_once.py`. Le reçu détaille l'unique `javac` puis l'unique `java -Xms64m -Xmx256m … Calibration`. Aucun artefact applicatif précompilé réutilisé. Les classes temporaires ont été supprimées après récolte des deux processus ; les deux groupes sont absents. Les deux threads du sous-cas concurrent ont terminé dans la même JVM.

Pour une reproduction ultérieure **sous un mandat distinct**, utiliser un checkout isolé de la source exacte, les JAR et outils d'empreintes documentées, copier uniquement les deux fichiers de reproduction à cet emplacement dans ce checkout neuf, sans les reçus antérieurs, puis lancer la commande. Conserver les nouveaux reçus séparément ; ne pas écraser les preuves présentes pour contourner le refus de relance. Aucun replay supplémentaire n'est demandé ou autorisé par la livraison actuelle. Le protocole ne requiert aucun serveur ni secret ; il ne certifie ni fournisseur AuthN, ni READ réel, ni SLO SPEC-09/10.

Les chemins machine des argv sont normalisés en `<repo>`, `<temporary-classes>` et `~`. Les fixtures sont synthétiques sans identité personnelle, token réel ou autorité de référence. Documents et preuves soumis à revue, sans commit/push automatique.
