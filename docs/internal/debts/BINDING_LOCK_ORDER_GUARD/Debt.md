# DEBT-WA6-01 — Guard global d'ordre des locks Binding

- Statut : **RESOLVED**
- Severity : **LOW**
- Défaut runtime actuel : **NO**
- Impact sur le modèle canonique : **NONE**
- Urgence : non bloquante

## Écart et risque historiques

L'ordre canonique des locks Binding est `stream -> authority`. Le guard [`Wa67BindingArchitectureTest`](../../../app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/Wa67BindingArchitectureTest.java) couvre plusieurs régressions connues : inventaire des accès directs aux tables Binding, séparation des repositories authority et stream, et contrôle que le writer canonique prend le stream avant l'autorité.

Cette preuve est principalement structurelle et textuelle. Elle ne démontre pas exhaustivement qu'un futur composant ne pourrait pas composer les deux repositories dans l'ordre `authority -> stream` sans violer les checks actuels. Une telle inversion pourrait créer un risque de deadlock ou enfreindre la discipline de locks. Aucun bug de cette nature n'est constaté aujourd'hui.

## Direction de résolution

- Empêcher architecturalement qu'un composant non canonique connaisse simultanément les deux repositories.
- Faire du writer Binding canonique l'unique composition autorisée.
- Renforcer le contrôle par ArchUnit ou une analyse structurelle des dépendances et appels, au-delà du scan textuel.
- Prouver explicitement l'unicité du chemin `stream -> authority`.

Ces pistes décrivent la direction historique ; la résolution ci-dessous impose désormais l’unicité du compositeur par CI.

## Provenance

Cette fiche est la source vivante de suivi. Le [Step debt WRITE_ADMISSION](../../steps/completed/WRITE_ADMISSION/Step_debt.md#debt-wa6-01--global-binding-lock-order-guard-is-not-semantically-exhaustive) conserve le finding et sa classification historiques ; voir aussi l'[audit WA.6](../../steps/completed/WRITE_ADMISSION/WA6_Audit.md).

## Réévaluation Consumption — 2026-10-16

**OPEN/LOW conservé.** Le [bilan D17 et WP0–WP6](Resolution_D17_2026-10-16.md) distingue la correction runtime Command/Consumption du critère structurel de cette fiche. La suppression du prélock terminal Consumption ne démontre pas l'impossibilité d'une future composition Binding authority→stream ; elle ne clôture pas DEBT-WA6-01.

## Résolution du guard — 2026-10-16

**RESOLVED / PASS**, au périmètre local de composition Binding :
[rapport de résolution](Resolution_Guard_2026-10-16.md) et
[preuves](Verification_Guard_2026-10-16.txt).

`BindingLockOwnershipTest` analyse les dépendances bytecode de toute la production, vérifie la
complétude des imports et réserve les repositories authority/occurrence au writer canonique.
Les accès stream sont bornés ; les delegates et façades indépendantes non autorisés sont rejetés.
Des contre-exemples vérifient les règles. Le guard SQL historique reste complémentaire ; une preuve
PostgreSQL déterministe montre Detach attendant stream sans détenir authority.

Résultats : slices BINDING/COMMAND **895 PASS**, dont **203 PostgreSQL** ; gate structurel global
filtré **113 PASS**. Aucun changement de production, protocole, schéma ou verrou.
Cette clôture ne prouve pas une absence universelle de deadlock, ne couvre pas les callbacks
multiclés arbitraires ni tout SQL dynamique masqué, et ne clôture pas DEBT-WA6-02.
La réévaluation Consumption ci-dessus reste la décision historique antérieure au renforcement.
