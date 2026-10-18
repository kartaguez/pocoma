# Consolidation Git avant évolution du protocole Consumption — 2026-10-18

Verdict : **PASS — consolidation documentaire locale, prête pour revue architecturale**.

## Périmètre déclaré avant consolidation

Impact production attendu et observé : **NONE par rapport à `116f4e4a`**. Slices primaire et secondaires : **NONE** (vérification documentaire/Git). Impact interdit : toute modification runtime, test, schéma, migration, dépendance ou configuration. Franchissement inattendu : **NONE**. Gate global : **NOT REQUIRED, non exécuté**. Full reactor : **NOT REQUIRED, non exécuté**. DB : **NONE**, aucun bootstrap ni migration. Politique normative lue avant vérification de `app/` : [Reactor_Verification_Policy](../Reactor_Verification_Policy.md).

Les résultats historiques ne sont pas réexécutés ni étendus : **76 tests dont 23 PostgreSQL** au correctif ; **91 tests dont 34 PostgreSQL** à l'intégration (dont 4 gardes structurels Binding dans le total 91). Les suites sont bornées par les sélections historiques, pas des suites complètes. Aucun nouveau test exécuté ici.

## Carte initiale vérifiée avant consolidation

`git fetch origin` : premier essai refusé sur `.git/FETCH_HEAD` par le sandbox, puis succès avec autorisation d'exécution. La référence serveur vérifiée reste `origin/feat/runtime-monolith = 2eef3ab9785c524c2fab86f586fc12580c8b1c2e`. `main` reste `cf18d766`, inchangée.

```text
90926a5e  feat/runtime-monolith (checkout initial modifié)
├── 4158de69  guard ownership / ordre des verrous Binding
│   └── 2eef3ab9  résolution documentaire Binding
│       origin/feat/runtime-monolith, fix/binding-lock-order-guard,
│       origin/fix/binding-lock-order-guard ; un worktree détaché
└── 675533e7  R03 validé
    └── 322042de  merge (parents ordonnés : 675533e7, 2eef3ab9)
        └── 116f4e4a  clôture documentaire / intégration
            fix/expense-context-version-coherence
```

Parent local : `90926a5ec37d1ca841e3de017bec0d1f705d8839`, divergence local/origin **0/2**. Les deux commits distants sont exactement `4158de69`, `2eef3ab9`. `116f4e4a` contient chacun d'eux et `675533e7` par ascendance, divergence origin/intégration **0/3**.

## Création et ascendance finale

Base parent distante vérifiée : `2eef3ab9785c524c2fab86f586fc12580c8b1c2e`.
Base de création réutilisée sans rejeu : `116f4e4ab2c45b44d418f8b54cab960e3c51c2d2`.
Branche : `feat/consumption-transaction-protocol`.
Worktree neuf : `/private/tmp/pocoma-consumption-transaction-protocol`.

La création à `116f4e4a` est un prolongement du parent vérifié par ascendance ; le merge historique reste conservé. Aucun nouveau merge ni cherry-pick ni rebase. L'historique n'est donc pas artificiellement rendu linéaire ; les deux nouveaux commits documentaires suivent linéairement l'intégration existante.

```text
90926a5e ── 675533e7 ───────────────┐
     └──── 4158de69 ── 2eef3ab9 ───┤
                              322042de
                                  │
                              116f4e4a  (réutilisé)
                                  │
                              35bfcbb6  (études)
                                  │
                              HEAD cible (index et présent registre)
```

Le SHA du dernier commit est fourni dans la livraison ; ce document appartient à ce même commit (auto-référence désignée par HEAD cible). Divergence finale attendue puis contrôlée : **origin/cible = 0/5**, parent local/cible **0/7**. Aucun commit du parent distant n'est absent.

## Documents intégrés et provenance

Les chemins ci-dessous sont relatifs à `docs/`. Les documents déjà committés sont réutilisés sans copie parallèle ni modification. Les trois études inédites proviennent du même chemin dans le checkout initial `/Users/julien.guezennec/Dev/projects/pocoma`.

| Document | Origine / état Git initial | Traitement |
|---|---|---|
| `architecture/Concurrency_Authorities_Fencing_Locks_Audit.md` | Suivi, commit `2919a7c6`, hérité du parent | Référence existante conservée |
| `architecture/Consumption_Finalization_Unification_Study_2026-10-16.md` | Suivi, `78e861ab`, hérité du parent | Étude historique conservée |
| `architecture/Consumption_Generic_Transactional_Protocol_Audit_2026-10-16.md` | Suivi, `056ec136`, hérité du parent | Audit historique conservé |
| `architecture/consumption-transactional-execution.md` | Suivi, hérité du parent | Contrat existant conservé |
| `architecture/Transactional_Authorities_Fencing_Locks_Audit_2026-10-18.md` | Non suivi dans le checkout initial ; déjà committé par `675533e7`, enrichi par `116f4e4a` | Version intégrée conservée ; analyse `90926a5e`, sections I/J/K et limites inchangées |
| `architecture/Event_Late_Numbering_Study_2026-10-18.md` | Déjà suivi par `116f4e4a` | Reprise après R03 conservée, distincte de la comparaison A/B |
| `architecture/Event_Late_Transactional_Numbering_Study_2026-10-18.md` | Non suivi, checkout initial | Copie identique, étude A/B historique |
| `architecture/Event_Late_Locking_Protocol_Viability_Audit_2026-10-18.md` | Non suivi, checkout initial | Copie identique, viabilité conditionnelle historique |
| `architecture/Mutation_Authorities_Ordered_Publication_Consolidated_Study_2026-10-18.md` | Non suivi, checkout initial ; référence analysée `116f4e4a` | Dernière référence de revue TARGET ; seuls 53 liens sources absolus deviennent relatifs, lignes conservées |
| `testing/evidence/UpdateExpenseShares_R03_2026-10-18.txt` | Non suivi dans le checkout initial, déjà intégré par `675533e7` | Identique à l'original local, preuve rouge conservée |
| `testing/evidence/Expense_Context_Coherence_Fix_2026-10-18.txt` | Suivi par `675533e7` | Preuve de résolution conservée |
| `testing/evidence/Expense_Context_Integration_2026-10-18.txt` | Suivi par `116f4e4a` | Preuve d'intégration conservée |
| `testing/evidence/Expense_Context_Integration_Report_2026-10-18.md` | Suivi par `116f4e4a` | Rapport et commandes historiques conservés |
| `debts/BINDING_LOCK_ORDER_GUARD/Resolution_D17_2026-10-16.md` | Suivi, hérité du parent | Résolution D17 existante conservée |
| `debts/BINDING_LOCK_ORDER_GUARD/Resolution_Guard_2026-10-16.md` et `Verification_Guard_2026-10-16.txt` | Suivis par `2eef3ab9` | Résolution Binding et preuves existantes conservées |

Index mis à jour : `docs/README.md`, `docs/debts/README.md`. Nouvel index spécialisé : [transactional-protocol-studies](../../architecture/transactional-protocol-studies.md). Nouveau registre : le présent document. Le registre des dettes est aligné sur le statut DEBT-WA6-01 déjà RESOLVED dans sa fiche par `2eef3ab9` ; aucune nouvelle clôture de cette dette.

Le README initial non committé ajoutait le lien de l'audit à `90926a5e` ; son intention est reprise dans l'index avec la portée historique explicite. Son fichier original reste intact. Aucun autre travail Keycloak/Bruno n'est intégré.

**R03 est corrigé et clôturé** dans le périmètre des writers examinés. La preuve rouge ne redevient pas le statut courant. Les études avant R03 conservent leur SHA et leurs réserves ; le rapport consolidé est une **étude TARGET non implémentée**, aucune décision livrée. Obsolescence par BusinessEvents : **conception en cours**. EventPublication : **proposition non implémentée**. Aucun autre statut de dette n'est ouvert ou clos par cette opération.

### Empreintes des trois études inédites

| Document | SHA-256 original | SHA-256 intégré |
|---|---|---|
| `Event_Late_Transactional_Numbering_Study_2026-10-18.md` | `a23bd31776aefacb4f36f1c7fa567403fede327c4c7f4c3a6f05c61d228e676d` | `a23bd31776aefacb4f36f1c7fa567403fede327c4c7f4c3a6f05c61d228e676d` |
| `Event_Late_Locking_Protocol_Viability_Audit_2026-10-18.md` | `eb561a0eaf2139dc8d95555dd56b2604d5226e875c888d0b117a9c51ffd72fa4` | `eb561a0eaf2139dc8d95555dd56b2604d5226e875c888d0b117a9c51ffd72fa4` |
| `Mutation_Authorities_Ordered_Publication_Consolidated_Study_2026-10-18.md` | `8f776a6ee9ec7250a31f2cc5d036446e05ed8a793682b9e49aa386063884dbc9` | `bf6ee31f9c5419960876447a3ff0aa226becc5188f2e2fbccfa01586c02fa058` |

## Vérification des changements fonctionnels et preuves

- `git diff --exit-code 116f4e4a HEAD -- app` : vide ; tout `app/`, y compris tests et POM, identique à l'intégration validée.
- Diff de `675533e7` à la cible sur `app/engine-write-pot/src/main` et `app/infra-persistence-primary-jpa/src/main` : vide ; correction R03 non réimplémentée.
- Diff du parent distant à la cible dans `app/` : seulement `ExpenseContextPort.java`, `JpaExpenseContextAdapter.java` et le test `UpdateExpenseSharesConcurrencyPostgresTest.java`, apportés par le correctif existant.
- La classe de test corrigée est identique à `675533e7` et `116f4e4a`. Le test non suivi du checkout initial est l'ancienne version de reproduction (3 scénarios), conservée sur place ; il ne doit pas écraser la version validée (15 scénarios PostgreSQL).
- La version locale initiale de l'audit est le corps historique avant ses compléments de clôture ; aucune information n'en est supprimée par la réutilisation de l'audit enrichi.
- Les preuves historiques restent identiques à leurs blobs dans les commits ; aucun test existant modifié par cette mission.
- Contrôle des liens documentaires locaux (chemins, plus validité des numéros de ligne sources) ; contrôle des SHA-256 des copies et de la seule transformation des liens.
- `git diff --cached --check` avant chaque commit et `git diff --check 116f4e4a HEAD` : aucune erreur introduite.

## Worktrees initiaux et préservation

| Worktree | HEAD / branche initiaux et finaux | État initial et final |
|---|---|---|
| `/Users/julien.guezennec/Dev/projects/pocoma` | `90926a5e`, `feat/runtime-monolith` | 10 chemins suivis (9 M, 1 D), 10 non suivis ; intacts |
| `/private/tmp/pocoma-binding-lock-order-guard` | `2eef3ab9`, `fix/binding-lock-order-guard` | Propre |
| `/private/tmp/pocoma-expense-context` | `116f4e4a`, `fix/expense-context-version-coherence` | Propre |
| `/Users/julien.guezennec/.codex/worktrees/debt-wa6-integration/pocoma` | `2eef3ab9`, détaché | Propre |
| `/private/tmp/pocoma-consumption-transaction-protocol` | HEAD cible, `feat/consumption-transaction-protocol` | Propre après commit final |

Comparaison des quatre HEAD, des quatre statuts porcelain et de **5058 empreintes/absences** (tous les fichiers suivis et non suivis non ignorés des quatre worktrees initiaux) : identiques avant et après consolidation. Aucun artefact ignoré n'est nettoyé ni modifié par cette mission. L'inventaire suivant conserve également les empreintes des 20 chemins locaux modifiés/non suivis et l'absence du fichier supprimé.

| État initial | Chemin | SHA-256 / absence |
|---|---|---|
| ` M` | `docker/keycloak/pocoma-realm.json` | `51e75e9e7b97690ada5e678951c6380a603579b3f5f7b2b35210bca946a2f38a` |
| ` M` | `docs/README.md` | `ee36d36ac556056853118c706173ab8912cc9546c0b229d8258167b4498e8a9d` |
| ` D` | `scripts/bruno/00 Pot E2E/01 Create Pot.bru` | `ABSENT` |
| ` M` | `scripts/bruno/00 Pot E2E/02 Get Create Command Result.bru` | `7e59cd1d281598e77792f943d0d96e6af28744b392a862e1916000b0e69d9a89` |
| ` M` | `scripts/bruno/00 Pot E2E/03 Read Pot V1.bru` | `5790e19705bdd035c6b1c3a8a0a8aad98f540034d67ce7a2bff8a3bf3be9277f` |
| ` M` | `scripts/bruno/00 Pot E2E/04 Update Pot Details.bru` | `187debad8129f66c1a9f07af071b0467c1efdd8dc14733f952db655b6897df62` |
| ` M` | `scripts/bruno/00 Pot E2E/05 Get Update Command Result.bru` | `895023d87d932cd834fd17f32a87dd1f666e2346cb35db0bbbee9d3cd0371fd2` |
| ` M` | `scripts/bruno/00 Pot E2E/06 Read Pot V2.bru` | `a17a51b1b654c849e647b166b272c8bd849335f48f070874c34708cc2f26b776` |
| ` M` | `scripts/bruno/00 Pot E2E/07 Re-read Pot V1.bru` | `7462f34388ab840c906b4b428766b916942a62198e5313aecdf7d7d2de0dba78` |
| ` M` | `scripts/bruno/00 Pot E2E/folder.bru` | `130da57df3deb8b4eb3dcddae82a2e1ca2b83c3b49a365dce6bd35d79492a95a` |
| `??` | `app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/context/UpdateExpenseSharesConcurrencyPostgresTest.java` | `13ccfca8dbdfe2a51a7469c6d9ed3b7670c25e0073d189cdbb1ef8b4c9f96069` |
| `??` | `docs/architecture/Event_Late_Locking_Protocol_Viability_Audit_2026-10-18.md` | `eb561a0eaf2139dc8d95555dd56b2604d5226e875c888d0b117a9c51ffd72fa4` |
| `??` | `docs/architecture/Event_Late_Transactional_Numbering_Study_2026-10-18.md` | `a23bd31776aefacb4f36f1c7fa567403fede327c4c7f4c3a6f05c61d228e676d` |
| `??` | `docs/architecture/Mutation_Authorities_Ordered_Publication_Consolidated_Study_2026-10-18.md` | `8f776a6ee9ec7250a31f2cc5d036446e05ed8a793682b9e49aa386063884dbc9` |
| `??` | `docs/architecture/Transactional_Authorities_Fencing_Locks_Audit_2026-10-18.md` | `9946491d247f55a255d6b319974ab5e3efaeecc4daef4d7ce6c253c8254409ec` |
| `??` | `docs/testing/evidence/UpdateExpenseShares_R03_2026-10-18.txt` | `81ce198fa5c655f8bc0bae3809796c0c0dcdc0123afc2c9a7c844565b937d839` |
| `??` | `scripts/bruno/00 Pot E2E/001 Registrations.bru` | `63cb115432d281f96e474c8a047964a29d203a30be1f426631cfcabd09be3259` |
| `??` | `scripts/bruno/00 Pot E2E/002 RegistrationResult.bru` | `e3216851c28e753e42f7a35dedaff63b53fa2ee81e26bf77d330432be9c97888` |
| `??` | `scripts/bruno/00 Pot E2E/003 Me-Binding.bru` | `1f059624b2376d460245ca4af6436c8ff42add2bae3aa2915009662fc2e9373a` |
| `??` | `scripts/bruno/00 Pot E2E/004 Create Pot.bru` | `6a240e9744ece37f35bf67fec954600b9e8d8e96c48cfbddb1000140f8e60935` |

## Branches et ressources potentiellement redondantes

Contrôle d'ascendance par `git merge-base --is-ancestor <branche> HEAD` :

| Branche locale initiale | SHA | Commits déjà intégrés | Recommandation |
|---|---|---|---|
| `audit/takeover-flakiness` | `eef30128b21222c74a20f85d9145cf62167d20a7` | Oui | Conserver (hors nettoyage ciblé) |
| `feat/runtime-monolith` | `90926a5ec37d1ca841e3de017bec0d1f705d8839` | Oui | Conserver ; parent et checkout contenant des travaux non intégrés |
| `fix/binding-lock-order-guard` | `2eef3ab9785c524c2fab86f586fc12580c8b1c2e` | Oui | Candidate à suppression ultérieure après revue ; aucun commit exclusif |
| `fix/expense-context-version-coherence` | `116f4e4ab2c45b44d418f8b54cab960e3c51c2d2` | Oui | Candidate à suppression ultérieure après revue ; aucun commit exclusif |
| `main` | `cf18d7669ff220b31afa1247b7d6b22b19604fb6` | Oui | Conserver impérativement, inchangée |
| `split-runtimes` | `f8328c4b3e692b0b599e8b7e9c6c465ed566b6f5` | Oui | Conserver (hors nettoyage ciblé) |
| `test/takeover-fencing` | `4a07b1117ed56f0f5a44ba6ae2a888de730b2d84` | Oui | Conserver (hors nettoyage ciblé) |
| `test/takeover-races` | `e0bd47a40727b850927fa37cd15dde509073f9c7` | Oui | Conserver (hors nettoyage ciblé) |
| `v2-make-it-pull` | `ec88ecb57f92edbba96f19b540ff0dd81277d400` | Oui | Conserver (hors nettoyage ciblé) |

Worktrees candidats ultérieurs : les deux worktrees `fix/*` propres et le worktree détaché à `2eef3ab9`, dont les HEAD sont ancêtres de la cible. Le checkout initial **ne doit pas être supprimé** : les travaux Keycloak/Bruno ne sont pas intégrés ; le README initial et les anciennes copies d'audit/test restent à préserver tant qu'un nettoyage explicite n'est pas décidé. Les références `origin/fix/binding-lock-order-guard` et `origin/feat/runtime-monolith` désignent le même commit intégré ; aucun nettoyage distant demandé. Les autres branches historiques peuvent être conservées même si leurs commits sont intégrés. Rien n'est supprimé, déplacé ou poussé.

## Commandes effectivement exécutées

Lectures : `cat docs/testing/Reactor_Verification_Policy.md`, `cat AGENTS.md`, lectures ciblées des rapports et preuves, recherches `rg/rg --files`, `sed`, `head`.
Git : `git fetch origin` (sandbox puis autorisé), `git worktree list --porcelain`, `git branch -avv`, `git log --all --graph --oneline --decorate`, `git status --short --untracked-files=all` et `--porcelain=v1 -z`, `git show`, `git ls-files`, `git rev-parse`, `git rev-list --left-right --count`, `git for-each-ref`, `git merge-base --is-ancestor`, `git diff`, `git diff --name-status`, `git diff --stat`, `git diff --check` et `git diff --cached --check`.
Création : `git worktree add -b feat/consumption-transaction-protocol /private/tmp/pocoma-consumption-transaction-protocol 116f4e4a`.
Documentation : scripts Python de copie, normalisation des seuls liens sources, index, registre, SHA-256, validation de préservation et liens ; `diff -u` pour comparer les versions de l'audit.
Commits : staging explicite des trois études puis `git commit -m "docs: consolidate transactional authority and Event publication studies"` (`35bfcbb6`) ; staging explicite des index et du registre puis `git commit -m "docs: index protocol studies and record Git consolidation evidence"` (HEAD cible).

Aucune commande Maven, aucun test, aucune migration, aucun gate global ni reactor complet exécuté : diff documentaire uniquement par rapport au code déjà validé. Aucun reset, clean, stash, rebase, force push, merge, suppression ou déplacement de worktree. Aucun changement de `main`. Commits locaux seulement ; **push NON effectué**, aucune intégration distante.
