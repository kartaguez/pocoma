# DEBT-MOD-01 — Taxonomie et familles de modules

- Statut : **RESOLVED** par le re-audit final PASS
- Severity : **UNRATED** — aucune criticité n'a été établie pour cette dette architecturale.
- Exécution : WP1–WP6, correction POST-WP6 et re-audit final terminés.

## Écart connu

L'écart historique portait sur 51 modules aux frontières hétérogènes. WP1–WP6 ont établi des owners explicites par famille, supprimé les shells et bridges migratoires et fermé les trois décisions architecturales. Le reactor WP6 possède 58 POM enfants, dont 57 de production et le gate `architecture-tests`, sans cycle, dépendance interne manquante, arc TARGET→legacy ou POM provisoire.

Cette taxonomie rend moins immédiates les réponses à ces questions : qui possède quoi ? Qu'est-ce qui est un moteur exécutable, un port, une fonction pure ou un orchestrateur ? Quel module ne fait que composer un runtime ? Quelles dépendances sont légitimes ?

## Direction architecturale à instruire

La [réflexion TARGET historique](../../steps/completed/ARCHITECTURE/Three_Engine_Families_Revision.md) distingue trois familles conceptuelles de capacités exécutables :

- **WRITE** applique une intention au primaire : admission et exécution de Command, Registration, mutations métier.
- **READ** répond à une question depuis le modèle READ : lecture exacte Pot, Command Result, Registration Result, Current Binding.
- **CONSUMPTION** traite durablement un consommable par discovery, claim, lease, retry, fencing et finalisation. Les spécialisations envisagées couvrent Command, Registration, Event, ProjectionTask, Binding, Results et LKV selon sa destination finale.

Ces familles n'imposent pas exactement trois modules. La question est celle de la cohérence des responsabilités, de l'ownership et des frontières, pas d'une réduction mécanique du nombre de modules.

## Résolution vérifiée

La chaîne de preuve est conservée par la [topologie TARGET](../../steps/completed/ARCHITECTURE/Modularity_Target_Topology.md), le [rapport WP6](../../steps/completed/ARCHITECTURE/Modularity_WP6_Execution_Report.md), l'[audit indépendant FAIL](../../steps/completed/ARCHITECTURE/Modularity_Final_Independent_Audit.md), la [correction POST-WP6](../../steps/completed/ARCHITECTURE/Modularity_Post_WP6_Closure_Report.md) et le [re-audit final PASS](../../steps/completed/ARCHITECTURE/Modularity_Final_Reaudit.md).

Cette matrice doit préserver les décisions CURRENT récentes : Command Result direct hors ProjectionTask générique, Registration Result direct, CURRENT_BINDING direct depuis Binding Fact, projections versionnées Pot via Event → ProjectionTask, distinction runtime / engine / infra / domain et les 9 runtimes présents. La source du comportement livré reste l'[Architecture CURRENT](../../architecture/Architecture.md).

Le document des [trois familles](../../steps/completed/ARCHITECTURE/Three_Engine_Families_Revision.md) est désormais **HISTORICAL migration material**. La topologie CURRENT obtenue et ses spécialisations C2 sont décrites par la topologie finale et le rapport WP6.
