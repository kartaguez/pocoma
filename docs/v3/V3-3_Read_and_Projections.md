# Pocoma V3-3 — READ, projections et parcours WRITE → READ

**Statut : CONCEPTION CONCEPTUELLE VALIDÉE, sous réserve des mécanismes V3-4, tests V3-5 et revue globale V3-CADRE. Non implémentée.**  
**Date :** 2026-10-22. **Canon :** [V3_Canon_and_Roadmap.md](V3_Canon_and_Roadmap.md).  
**Fondations :** [Pot V3-1](V3-1_Domain_and_Versioning.md), [Identity V3-1](V3-1_Identity_Domain_and_Versioning.md), [événements/consommables V3-2](V3-2_Events_Requests_and_Consumables.md).

## 1. But et périmètre

READ répond aux consultations courantes **sans appeler WRITE**. Les données peuvent être en retard sur WRITE ; elles portent leurs versions, sont exactes à ces versions et ne sont jamais présentées comme plus fraîches qu'elles ne le sont. Le Pot courant est rendu **complet ou temporairement indisponible** : aucune réponse partielle trompeuse.

**Hors périmètre actuel :** consultation d'une version historique arbitraire, `READ_REQUEST`, génération asynchrone de composition `HistoricalPot` et nouvelle famille de consommables afférente. Les versions de sous-objets restent historisées dans READ, mais cela n'engage pas la disponibilité d'une API historique.

Règle de simplicité : pas de snapshots complets Pot par version, pas de chaîne d'indirection pour les balances, pas de `CurrentBalance` mutable, pas d'annuaire inverse Identity, pas de copie de l'état du Pot dans la liste utilisateur.

## 2. Inventaire des projections

| Projection logique | Nature | Clé / contenu minimal |
|---|---|---|
| `CurrentPot` | Mutable monotone | `potId`, `potVersion`, références aux versions locales des Headers/Details/Weight/Financials et objets enfants ; état du Pot incluant suppression |
| `SubobjectVersion` | Immuable | Identité métier de composante + version locale, valeur historique et informations de validité utiles ; toutes les nouvelles versions sont conservées |
| `PotBalance` | Immuable | `(potId,potVersion)`, soldes complets calculés sur `Pot@V` |
| `CurrentUserPotList` | Mutable monotone | `pocomaUserId` → entrées `(potId,potVersion,visible)` de tous les Pots ayant été visibles au moins une fois |
| `CurrentBinding` | Mutable monotone | Identité EUI `(issuer,subject)`, `externalUserIdentityId`, `pocomaUserId?`, `bindingToken?`, `identityVersion` |
| `CommandResult` | Immuable, terminal seul | `requestId`, issue et informations de résultat ; version Pot résultante en cas de succès |
| `IdentityRequestResult` | Immuable, terminal seul | `requestId`, issue et informations de résultat ; pour succès, identifiant de l'EUI **cible** et version EUI résultante |

Les sept entrées sont des **projections logiques**, pas une obligation de sept tables. Les résultats `PENDING` n'existent pas en READ : résultat absent ≠ demande encore pendante (la matérialisation peut être en retard).

## 3. CurrentPot, sous-objets et balances

Chaque événement `POT_EVENT(P,V)` permet la reconstruction complète de l'état autoritatif `Pot@V`, indépendamment de `Pot@(V−1)`. Les versions immuables des composantes sont alimentées au fil de l'eau, y compris quand un événement ancien arrive après un événement plus récent.

`CurrentPot` représente la **plus haute version effectivement matérialisée**, sans garantie qu'elle soit la dernière WRITE. Son remplacement est conditionné atomiquement par `incomingVersion > storedPotVersion`. La matérialisation des sous-objets historiques ne doit **pas** être supprimée lorsque la mise à jour du pointeur courant est écartée pour version inférieure. Les versions immuables contenues dans une même transaction et le pointeur courant sont écrits de manière atomique, sans présumer que les versions référencées issues d'autres événements soient déjà toutes présentes. Lecture complète uniquement si toutes les références sont résolues.

**Balances :** recalcul **intégral** des soldes à **chaque** nouvelle version globale Pot V, y compris mutation de libellé ou autre changement non financier. `PotBalance(P,V)` est immuable ; aucune réutilisation d'une version antérieure, aucun raccourci par indirection. Les calculs à différentes V peuvent terminer hors ordre. La consultation de `CurrentPot(P,V)` ne peut présenter comme complète que `PotBalance(P,V)` et les sous-objets correspondant aux références de ce même V. Le coût assumé doit être qualifié en V3-5.

Une lecture capture une fois `CurrentPot.potVersion=V`, puis résout exclusivement les références et balances applicables à V. Le déplacement concurrent de `CurrentPot` à V+1 n'autorise pas un mélange de versions ; les objets immuables doivent rester accessibles pour les lectures en cours.

## 4. CurrentUserPotList et autorisation

Entrée conceptuelle : `(pocomaUserId, potId, potVersion, visible)`. Une entrée est créée à la **première visibilité métier** et conservée même lorsqu'elle devient invisible. `potVersion` représente la **dernière version à laquelle la visibilité a été évaluée et enregistrée** pour cette entrée, pas forcément la dernière mutation du Pot. Une version inférieure ou égale ne peut plus changer la ligne. Il faut aussi pouvoir rechercher efficacement toutes les entrées existantes par `potId`, y compris celles avec `visible=false`.

Règle de visibilité métier pour un Pot actif : créateur immuable **ou** utilisateur associé à au moins un Shareholder. Pot supprimé : invisible pour tous. Un même utilisateur peut être associé à plusieurs Shareholders ; détacher l'un ne le rend pas invisible si un autre lien subsiste. Une entrée absente ne signifie pas que l'utilisateur peut accéder au Pot. La projection sert la navigation ; l'autorisation de sécurité reste séparée, notamment pour les entitlements externes.

**Mise à jour indépendante par version :** `PotVisibilityTask(P,V,visibleUserIds[])` contient la **liste complète** des utilisateurs visibles à V, pas un delta. La matérialisation READ prend l'union (a) des utilisateurs dans la tâche et (b) des utilisateurs ayant déjà une entrée pour P, y compris invisibles ; pour chacun, pose `visible=(user ∈ visibleUserIds)` et V **seulement si V est supérieure à la version enregistrée**. L'évaluation de visibilité provient intégralement de `Pot@V`, pas d'un état précédent de la projection. L'écriture doit être sûre en concurrence, y compris contre les nouvelles entrées insérées pendant l'opération ; le protocole SQL/transaction sera spécifié V3-4.

Consentement : seul l'utilisateur lui-même peut rattacher son `PocomaUser` à un Shareholder ; lui-même ou une autorité Pot adéquate peut le détacher. Le domaine Pot porte `ShareholderDetails.userId` mais l'API de commande ne permet pas de fournir arbitrairement un autre `PocomaUserId`. Identité de l'auteur résolue à l'exécution depuis EUI + bindingToken vérifié. `PocomaUserId` n'a pas besoin d'être exposé publiquement pour les usages présents. Les historiques des rattachements restent immuables.

## 5. CurrentBinding et résultats terminaux

`CurrentBinding` est **l'unique projection Identity retenue**, alimentée depuis les événements de mutation EUI, par lecture de l'état EUI@version complète et écriture directe READ monotone. Les événements de création du `PocomaUser` ne nécessitent aucune projection READ. Le `bindingToken` ne doit être révélé qu'après authentification/autorisation appropriées ; READ peut être en retard après Link/Unlink, **WRITE Identity reste l'autorité de validation à l'exécution**.

`CommandResult` et `IdentityRequestResult` sont copiés **directement** depuis le résultat terminal autoritatif à la consommation des événements de complétion, sans Projection Task. Succès et rejet sont terminaux et immuables. `CommandResult` réussi indique `potId` et `resultingPotVersion` (même pour succès sans effet : version courante au moment de l'exécution). Un succès Identity (Registration, Link, Unlink) indique la **cible EUI**, identifiant stable et `targetVersion` ; Registration crée aussi un User v1, mais aucune cible supplémentaire n'est nécessaire pour suivre `CurrentBinding`. Les échecs portent les motifs adéquats, sans version résultante inventée.

## 6. Lecture « read your writes » asynchrone

Le résultat terminal peut apparaître dans READ avant ou après la projection du domaine concernée. Le client utilise la version cible fournie par son résultat :
- Command réussie → lire `CurrentPot(P)` avec `minVersion=resultingPotVersion`.
- IdentityRequest réussie → lire `CurrentBinding(EUI cible)` avec `minVersion=targetVersion`.

Le READ sert une **version >= minVersion** ou indique l'indisponibilité temporaire ; atteindre une version supérieure signifie que la mutation a été intégrée à la progression de la projection, **pas que son effet est toujours présent** (par exemple Link suivi d'Unlink). Pour une consultation complète du Pot, la version réellement servie V exige aussi toutes ses composantes et `PotBalance(P,V)`. Aucun appel WRITE synchrone depuis les API READ. La version affichée peut rester plus ancienne que WRITE.

## 7. Consumptions et Projection Tasks — responsabilités conceptuelles

Un `POT_EVENT(P,V)` est le consommable déclencheur de **deux Consumptions indépendantes** :
1. `MaterializeCurrentPot` : reconstitue Pot@V, écrit directement **dans une transaction READ** les nouvelles `SubobjectVersion` et la mise à jour conditionnelle de `CurrentPot` ; la conservation des versions anciennes est indépendante de l'avancement du pointeur courant.
2. `PreparePotProjectionTasks` : reconstitue Pot@V de son côté et produit **atomiquement deux tâches durables**, `PotBalanceTask(P,V,balances)` et `PotVisibilityTask(P,V,visibleUserIds[])`. Les deux tâches ont des matérialisations READ indépendantes.

Cette séparation assume **deux reconstructions possibles de Pot@V** pour éviter le couplage entre écriture directe READ et création des Projection Tasks. Le nombre de tâches est constant (deux) par événement Pot, indépendamment du nombre d'Expenses et d'utilisateurs ; leur **taille**, elle, dépend des données nécessaires.

| Source | Transformation conceptuelle | Destination |
|---|---|---|
| `POT_EVENT` | MaterializeCurrentPot | Direct READ : CurrentPot + sous-objets |
| `POT_EVENT` | PreparePotProjectionTasks | Deux tâches durables : balances et visibilité |
| `PotBalanceTask` | MaterializePotBalance | Direct READ : PotBalance immuable |
| `PotVisibilityTask` | MaterializePotVisibility | Direct READ : CurrentUserPotList monotone |
| `IDENTITY_EVENT` EUI | MaterializeCurrentBinding | Direct READ : CurrentBinding |
| `IDENTITY_EVENT` PocomaUser | Aucune projection requise | Aucun effet READ, consommation néanmoins terminable |
| `COMMAND_COMPLETION_EVENT` | MaterializeCommandResult | Direct READ : CommandResult immuable |
| `IDENTITY_COMPLETION_EVENT` | MaterializeIdentityResult | Direct READ : IdentityRequestResult immuable |

Les noms sont descriptifs, pas des classes ou des identifiants UUID imposés. Les frontières physiques entre WRITE, tâches et READ, atomicités locales, idempotence, fencing, commit incertain, compatibilité des transactions et absence de perte à travers les écritures sont **à définir et démontrer en V3-4**. Ne pas inférer qu'une mutation WRITE et une écriture READ distante sont dans une même transaction.

## 8. Risques, exigences V3-4/5 et clôture conceptuelle

- **Discovery/Consumption :** progression indépendante des quatre familles d'événements V3-2 ; reprise sûre pour les écritures READ directes et les tâches durables ; événements hors ordre, répétitions et commits inconnus.
- **Concurrence :** CAS/monotonie sur CurrentPot/CurrentBinding/visibilité ; protection de la lecture de l'ensemble des anciennes entrées par Pot contre les insertions concurrentes ; conservation des composants immuables même après perte de la course CurrentPot.
- **Fraîcheur et correction :** API « complet ou temporairement indisponible », `minVersion` et séparations entre absence, retard de matérialisation et accès refusé.
- **Sécurité :** bindingToken READ protégé ; autorisation WRITE Identity ; accès aux Pots décidé hors liste de navigation ; preuve de contrôle EUI cible lors d'un Link reste ouverte.
- **Performance :** reconstructions complètes indépendantes (deux possibles par événement), balances recalculées à chaque V, lectures historiques WRITE groupées/indexées sans N+1, contention des écritures visibilité, taille des tâches, rétention des versions immuables et cohérence des plans PostgreSQL. Mesures V3-5 obligatoires, optimisations seulement si justifiées.
- **Cohérence V3-1/V3-2 :** deux domaines transactionnellement disjoints ; requestId dans les événements de mutation, deux séquences par domaine ; pas de dépendance d'ordre entre événements de mutation, complétions et projections READ.

**La conception V3-3 est validée conceptuellement, non implémentée et non prouvée par tests V3. V3-CADRE reste ouvert.**
