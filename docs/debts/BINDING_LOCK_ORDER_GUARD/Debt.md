# DEBT-WA6-01 — Guard global d'ordre des locks Binding

- Statut : **OPEN**
- Severity : **LOW**
- Défaut runtime actuel : **NO**
- Impact sur le modèle canonique : **NONE**
- Urgence : non bloquante

## Écart et risque

L'ordre canonique des locks Binding est `stream -> authority`. Le guard [`Wa67BindingArchitectureTest`](../../../app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/Wa67BindingArchitectureTest.java) couvre plusieurs régressions connues : inventaire des accès directs aux tables Binding, séparation des repositories authority et stream, et contrôle que le writer canonique prend le stream avant l'autorité.

Cette preuve est principalement structurelle et textuelle. Elle ne démontre pas exhaustivement qu'un futur composant ne pourrait pas composer les deux repositories dans l'ordre `authority -> stream` sans violer les checks actuels. Une telle inversion pourrait créer un risque de deadlock ou enfreindre la discipline de locks. Aucun bug de cette nature n'est constaté aujourd'hui.

## Direction de résolution

- Empêcher architecturalement qu'un composant non canonique connaisse simultanément les deux repositories.
- Faire du writer Binding canonique l'unique composition autorisée.
- Renforcer le contrôle par ArchUnit ou une analyse structurelle des dépendances et appels, au-delà du scan textuel.
- Prouver explicitement l'unicité du chemin `stream -> authority`.

Ces pistes ne sont pas implémentées dans cette session documentaire.

## Provenance

Cette fiche est la source vivante de suivi. Le [Step debt WRITE_ADMISSION](../../steps/completed/WRITE_ADMISSION/Step_debt.md#debt-wa6-01--global-binding-lock-order-guard-is-not-semantically-exhaustive) conserve le finding et sa classification historiques ; voir aussi l'[audit WA.6](../../steps/completed/WRITE_ADMISSION/WA6_Audit.md).
