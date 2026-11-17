# Intégration contrôlée du correctif R03

## Base et résultat

Start HEAD : `675533e742cda398e418da385014ae9e32658676`, branche
`fix/expense-context-version-coherence`, worktree `/private/tmp/pocoma-expense-context`.
Après actualisation d'origin, les deux commits distants sont bien `4158de69`
et `2eef3ab9`. Leurs sept chemins ne recoupent aucun des six chemins du
correctif. Ils ajoutent guard structurel, test d'identité et documentation ;
aucun changement de production distant.

Merge local sans conflit : `322042ded0191596c29348500fd23ed68d280d8c`.
Il conserve les trois commits et leur historique. Aucun rebase, reset, stash
ni déplacement du checkout initial. Aucun push ni mise à jour de la branche
initiale `feat/runtime-monolith`.

## Verification scope déclaré avant fusion

- Impact production attendu et observé : les deux fichiers du correctif
  Expense uniquement, identiques au commit `675533e7` après fusion.
- Primaire : COMMAND. Secondaire : BINDING pour le test distant d'autorité.
- Interdit : nouveau changement use case/CAS, Consumption, Discovery, autres
  runtimes, schéma ou dépendance hors apports distants déjà examinés.
- Preuve structurelle : ancre globale `architecture-tests`, sélection
  `BindingLockOwnershipTest`, requise pour le guard et sa dépendance introduits
  par le commit distant. Ce n'est pas la suite structurelle intégrale.
- Full reactor : NOT REQUIRED et non exécuté ; intégration locale bornée,
  hors main, hors clôture Wave et sans restructuration supplémentaire.
- DB : CURRENT, PostgreSQL 17.11 ; baseline trusted NONE selon la politique.
  Bootstrap Flyway existant conservé ; aucune campagne de migrations dédiée.
- Franchissement inattendu : NONE. Prochain gate global : intégration
  significative à main, selon la politique normative.

## Commandes réellement exécutées

Depuis `/private/tmp/pocoma-expense-context/app` :

```sh
./mvnw -pl runtime-command-consumption-worker,runtime-binding-consumption-worker -am test '-Dtest=UpdateExpenseSharesConcurrencyPostgresTest,JpaExpenseContextAdapterTest,JpaExpenseHeaderAdapterTest,JpaExpenseSharesAdapterTest,JpaPotGlobalVersionAdapterTest,*Expense*ContextTest,*Expense*ServiceTest,TerminalPotDeleteExpenseContextsTest,CommandConsumptionRuntimePostgresTest,JpaUserIdentityAuthorityAdapterPostgresTest' -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -pl architecture-tests -am test -Dtest=BindingLockOwnershipTest -Dsurefire.failIfNoSpecifiedTests=false
```

Première commande : **87 tests PASS**, zéro failure/error/skipped, 19.499 s,
dont 34 tests PostgreSQL (15 R03, 11 identité, 8 runtime Command).
Deuxième : **4 tests PASS**, zéro failure/error/skipped, 7.601 s.
Total : **91 tests distincts PASS**. Les sélections Surefire bornent la preuve ;
aucune suite complète COMMAND/BINDING ni full reactor n'est revendiquée.
Voir [extraits bruts](Expense_Context_Integration_2026-10-18.txt).

Autres opérations : `git fetch origin` (première tentative sandbox refusée,
puis succès autorisé), status/log/diff/worktree/branch, lecture politique et
sources, `rg`, SHA-256 des 17 chemins initiaux, `git merge --no-ff`,
`git diff --check`, staging explicite et commit documentaire.

## Conservation et livraison

Le correctif reste dans son worktree isolé. Les 17 chemins préexistants du
checkout initial sont comparés à l'inventaire SHA-256 avant livraison, y
compris l'absence du fichier supprimé. Le HEAD initial reste `90926a5e`.
La branche intégrée est en avance de deux commits sur origin immédiatement
après merge ; le commit documentaire de clôture ajoute un troisième commit.
Son identifiant, la divergence finale et le résultat de la comparaison des
17 chemins sont fournis dans le compte rendu de livraison.

## Clôture et reprise architecturale

R03 est clôturé dans le périmètre des writers applicatifs actuels. La limite
des réécritures rétroactives reste en section J de l'audit ; aucune protection
SQL complète n'est revendiquée. Les sections historiques ne sont pas réécrites
pour effacer le rouge initial ou leur ancien état des dettes Binding.

La [reprise de l'étude Events](../../architecture/Event_Late_Numbering_Study_2026-10-18.md)
documente l'invariant de préparation à V, la composition transactionnelle
Command et les décisions/preuves restantes. Aucun compteur, changement de
schéma ou changement du moteur générique n'est implémenté.
