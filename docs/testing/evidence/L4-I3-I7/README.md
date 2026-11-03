# Preuves L4/I3–I7

25 campagnes instrumentées, neuf recettes, une seule intégration complète. `runs/*/result.json`
conserve tous les verdicts, y compris les premiers échecs. `raw.tar.gz` conserve les XML,
modèles effectifs, logs Maven et preuves PostgreSQL collectées. Les autres logs sont gzip.
Les archives ne dépendent pas de la survie de `/private/tmp`.

- [Manifeste exécutable](Executable_Manifest.json) : commandes exactes depuis app/, modules,
  fermeture Maven observée, identités JUnit et invocations.
- [Scénarios et oracles](Manifest.md), [format structuré](Invariant_Methods.json).
- [Comparaison des entrées](Input_Comparisons.json) : empreintes finales, différences des
  runs et preuve de copie identique dans le checkout isolé. Les reçus sont sur HEAD dirty,
  aucun SHA propre n’est prétendu testé directement. Les empreintes identifient le code testé.
- [Réutilisation](Reuse_Attribution.json) : périmètre inchangé et limites, jamais un PASS au nouveau SHA.
- [Rapport](../../../v3/L4_I3_I7_Execution_Report.md),
  [manifeste des preuves](../../../v3/L4_I3_I7_Proof_Manifest.json).
- `initial/` préserve les octets préexistants d’outillage avant cette mission ;
  `tooling/baseline-*` préexistaient. `tooling/*final.log.gz` : 31 + 8 tests Python PASS.
- `development/` : compilations et feedback natifs, sans assimilation à une campagne/gate.
- `replay-selection.py` : opérateur explicite borné aux méthodes enregistrées, motif obligatoire,
  sans retry ni élargissement. Deux appels d’intégration correctifs : 7 puis 6 invocations.

Les durées des subprocessus Maven et de la campagne entière sont différentes et toutes deux
conservées. Les enveloppes ne sont pas des timeouts. Aucun PASS SPEC-10, canonique I3–I7 ou
full reactor n’est déduit. Environnements Docker/JDK/Maven/settings et images pinned dans les
preuves. Les rôles/mots de passe apparaissant dans les fixtures sont des identités de test.

`Archive_Index.json` contient taille/SHA-256 de chaque fichier publié de ce dossier, sauf lui-même.
