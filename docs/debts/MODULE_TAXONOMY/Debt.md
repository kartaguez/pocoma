# DEBT-MOD-01 — Taxonomie et familles de modules

- Statut : **OPEN**
- Severity : **UNRATED** — aucune criticité n'a été établie pour cette dette architecturale.
- Exécution : aucun Step de refonte ouvert par cette fiche.

## Écart connu

Le reactor actuel contient **51 modules, dont 9 runtimes**. Son organisation résulte de plusieurs vagues successives. Les noms et frontières mêlent capacité applicative, ownership métier, moteur ou use case, fonction pure, port, orchestrateur, worker, producteur de projection, infrastructure et composition runtime. Le préfixe `engine-*` couvre notamment des responsabilités de natures différentes.

Cette taxonomie rend moins immédiates les réponses à ces questions : qui possède quoi ? Qu'est-ce qui est un moteur exécutable, un port, une fonction pure ou un orchestrateur ? Quel module ne fait que composer un runtime ? Quelles dépendances sont légitimes ?

## Direction architecturale à instruire

La [réflexion TARGET existante](../../steps/current/ARCHITECTURE/Three_Engine_Families_Revision.md) distingue trois familles conceptuelles de capacités exécutables :

- **WRITE** applique une intention au primaire : admission et exécution de Command, Registration, mutations métier.
- **READ** répond à une question depuis le modèle READ : lecture exacte Pot, Command Result, Registration Result, Current Binding.
- **CONSUMPTION** traite durablement un consommable par discovery, claim, lease, retry, fencing et finalisation. Les spécialisations envisagées couvrent Command, Registration, Event, ProjectionTask, Binding, Results et LKV selon sa destination finale.

Ces familles n'imposent pas exactement trois modules. La question est celle de la cohérence des responsabilités, de l'ownership et des frontières, pas d'une réduction mécanique du nombre de modules.

## Audit requis avant exécution

Le futur chantier doit repartir des 51 modules réels et établir, pour chacun, une matrice : module actuel, responsabilité réelle, ownership, famille conceptuelle, dépendances entrantes et sortantes, puis décision cible **KEEP**, **RENAME**, **MOVE**, **SPLIT**, **MERGE** ou **REMOVE**.

Cette matrice doit préserver les décisions CURRENT récentes : Command Result direct hors ProjectionTask générique, Registration Result direct, CURRENT_BINDING direct depuis Binding Fact, projections versionnées Pot via Event → ProjectionTask, distinction runtime / engine / infra / domain et les 9 runtimes présents. La source du comportement livré reste l'[Architecture CURRENT](../../architecture/Architecture.md).

Le document des [trois familles](../../steps/current/ARCHITECTURE/Three_Engine_Families_Revision.md) est **TARGET** : ses tableaux 47→47 sont historiques. Son en-tête a été rebaseliné sur 51 modules et 9 runtimes, mais il ne peut pas être exécuté tel quel sans nouvel audit de la matrice actuelle. Cette dette peut donner lieu à un Step actif ultérieur ; aucune refonte n'est lancée ici.
