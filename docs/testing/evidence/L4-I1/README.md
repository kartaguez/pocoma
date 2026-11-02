# L4/I1 — Preuves d’implémentation et de qualification

[Rapport d’exécution](../../../v3/L4_I1_Execution_Report.md) et
[manifeste](../../../v3/L4_I1_Proof_Manifest.json).
Les résultats de qualification définitifs figurent dans ces documents et dans
les reçus de chaque campagne ; ce README n’accorde aucune certification globale.

- `targeted/` : campagne finale des cinq classes I1, **37/0/0/0 PASS**, invocation
  `bc033ccb299f41e2aec0411aacf208b7`.
- `projection-command/` : gates PROJECTION + COMMAND non filtrées après le
  correctif owner, **1833/0/0/0 PASS**, résultat dans `result.json`.
- `foundation/` : 29 tests FOUNDATION PASS ; `owner-defect-before-fix/` :
  contre-preuve PostgreSQL FAIL conservée (1/1/0/0).
- `architecture/` : gate Architecture non filtrée **2096/0/0/0 PASS**, dont
  **164/0/0/0** dans le module Architecture, avec
  `-Dstep3b.measure=true` pour exécuter le test conditionnel historique ; résultat
  dans `result.json`. Aucune exclusion ni dispense de SKIP.
- Chaque campagne conserve `raw.tar.gz` natif, `plan.json`, `provenance.json`,
  `result.json` et `Archive_Members.json`. Le brut contient modèle Maven effectif,
  journal de découverte, journal Maven, XML frais et preuves physiques.
- `Physical_Index.json` : 60 scénarios physiques de la contribution ciblée,
  chemins de membres du brut, copies directement consultables sous `physical/` et SHA-256. Les témoins PID/XID, barrières, snapshots,
  contre-oracles, pertes d’ACK et catalogues neuf/extension sont dans ces fichiers.
- `Implementation_Files.json` : les 23 fichiers applicatifs modifiés et leurs
  empreintes ; `L2_Input_Comparison.json` : comparaison avec le SHA initial de 13 entrées L2,
  dont 12 inchangées et l’owner corrigé pour le poisoning tardif.
- `development-logs.tar.gz` : feedback et échecs de développement conservés.
- `development-targeted-before-hardening/`, `development-targeted-before-owner-fix/`,
  `development-projection-before-owner-fix/` et `projection-interrupted.tar.gz` :
  preuves antérieures au durcissement de la garde interschéma, non réutilisées
  pour la qualification finale ; l’interruption conserve son verdict FAIL.
- `architecture-preflight-refusal/` : refus INCONCLUSIVE de la première attribution
  de source, aucun test exécuté ; attribution explicite résolue dans l’opérateur.
- `development-h2-probe/` : diagnostic initial H2, sans prétention de preuve L2/PG.
- `architecture-stale-bytecode-failure/` : FAIL 1500/0/2/0 ;
  `web-bytecode-diagnostic/` : classes erronées et désassemblages. La qualification
  Architecture finale est compilée dans un checkout temporaire isolé aux mêmes
  octets applicatifs ; aucune assertion ni source WEB n’est modifiée.
- `operator/capture.py.txt` : opérateur effectivement utilisé, utilisant le
  collecteur existant sans modifier ses règles, ses recettes ou son code.

Les campagnes sont séquentielles. Le modèle effectif et les sources déterminent
l’inventaire attendu avant Maven. Les producteurs Surefire écrivent dans des
répertoires uniques et enregistrent l’UUID/module/producteur dans les XML.
Le collecteur exige une exécution fraîche et unique de chaque identité, sans
échec/erreur/skip, et compare les empreintes d’entrée avant/après.
Les anciens rapports sous `target/` ne sont pas réutilisés.

Le verdict final est **IMPLEMENTED — LOCAL BOUNDED PASS**, sous réserve de
l’audit indépendant et de la clôture humaine définitive I1.

Le SHA de HEAD pendant les campagnes est le SHA initial avec les modifications
applicatives non committées. La provenance conserve cet état et toutes les
empreintes. Le manifeste identifie le commit applicatif ultérieur et vérifie ses
blobs contre ces mêmes octets : il ne prétend pas que le commit final existait
avant les campagnes. Le commit documentaire est identifié par Git et par le
reçu final de publication, sans référence autoréférente dans ses propres octets.

Les triggers de témoin, la table de témoins, les contraintes différées, le
participant technique et le proxy d’ACK sont exclusivement des fixtures.
Le contre-oracle utilise volontairement une écriture indépendante et prouve que
l’oracle détecte cette violation ; le store productif n’a aucun chemin autonome.
Les rôles/schémas/données sont jetables, créés explicitement par les tests.

Pour inspecter les preuves sans relancer Maven :

```sh
mkdir /private/tmp/pocoma-l4-i1-proof
# Depuis la racine du dépôt, vers un répertoire vide
tar -xzf docs/testing/evidence/L4-I1/targeted/raw.tar.gz -C /private/tmp/pocoma-l4-i1-proof
```

Vérifier les membres/tailles/empreintes avec `Archive_Members.json` et les
attributions XML avec `plan.json`, `provenance.json` et `result.json`.
Une archive historique, une extraction ou un hash ne constitue pas un nouveau
rejeu PostgreSQL. Ces preuves sont bornées à I1 ; I2–I8, HTTP et la certification
READ/PRJ/SEC/OPS/PERF globale restent hors de cette livraison.
