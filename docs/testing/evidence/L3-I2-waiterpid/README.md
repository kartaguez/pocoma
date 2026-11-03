# Correctif ciblé L3/I2 — waiterPid — 3 novembre 2026

**PASS : 11/0/0/0**, 11 identités attendues exactement une fois, zéro test supplémentaire.
Le [rapport](../../../v3/L3_I2_WaiterPid_Fix_Report.md) décrit le périmètre et les cas limites.

`run-targeted.py` crée uniquement une recette filtrée en mémoire pour la classe entière,
ancrée à `infra-persistence-primary-jpa -am`, et appelle sans modification les mécanismes
`plan`/`run` du sélecteur normatif. Le registre, les POM et les sources du collecteur restent
inchangés. Il n’existe aucun mode de gate globale dans cet opérateur.
Lancer depuis le dépôt : `python3 -B docs/testing/evidence/L3-I2-waiterpid/run-targeted.py`.
Le répertoire de destination doit être absent ; ne jamais écraser les preuves publiées.

- `sandbox-fail/` : premier lancement, accès au socket Docker refusé par le sandbox ;
  FAIL Maven/collecteur, un testcase synthétique d’erreur beforeAll, aucune méthode exécutée.
  Déplacé depuis `targeted/` après arrêt complet ; les chemins absolus originaux sont conservés.
- `targeted/` : même sélection relancée avec accès autorisé au socket Docker, sans changement
  des entrées applicatives ; PASS. Il s’agit d’une correction d’accès à l’environnement,
  pas d’un retry d’une assertion échouée.
- Chaque dossier garde `plan.json`, `provenance.json`, `result.json`, `budget.json`,
  `raw.tar.gz`. L’archive garde les logs Maven/découverte, le modèle effectif, les XML frais
  et les traces physiques. Les empreintes de toutes ses pièces et de l’archive ont été
  vérifiées avant suppression des copies décompressées redondantes.

Le profil POM existant `verification-provenance` produit directement les rapports dans
les répertoires isolés, avec ID d’invocation/module/producteur. Le collecteur vérifie
la découverte effective, les identités et compteurs exacts, les empreintes avant/après,
les settings/outils/SQL et refuse toute exécution supplémentaire ou skip. Les anciens XML
partagés ne participent jamais au verdict. `raw.tar.gz` et les JSON sont les preuves durables.

Budget : PASS en 15,291 s tout compris (Maven 12,817 s, découverte 1,597 s), lancement
sandbox 5,184 s. Fenêtre totale incluant diagnostic et relance inférieure à 60 s, sous 300 s.
La protection de l’opérateur interrompt à 290 s et termine/récolte les processus Maven.
Aucune gate COMMAND, PROJECTION, Architecture ni full reactor n’a été exécutée.
Les migrations sont celles CURRENT V3 de la fixture existante ; aucun rejeu V1–V23 ajouté.
Aucune requalification globale L3/L4 ni certification SPEC-10 n’est revendiquée.
