# Pocoma V3-5E — Contrats des Supras et bornage des frontières

**Statut : CADRAGE OUVERT — principe de séparation validé, opérations et limites à préciser.**
**Date :** 2026-10-23. **Références :** [Canon](V3_Canon_and_Roadmap.md), [READ](V3-3_Read_and_Projections.md), [Preuves](V3-5D_Test_and_Certification_Plan.md).

## 1. Décision structurante validée

Un Supra protège les frontières à partir **des paramètres présentés, de l'identité authentifiée, des attestations externes et du contrat d'interface**. Il **ne lit pas le domaine WRITE pour préjuger de l'état métier courant**.

À l'entrée, il peut refuser avant toute persistance une requête malformée, disproportionnée, non authentifiée ou dépourvue d'une preuve externe exigée. À l'exécution, le domaine et le Worker conservent l'autorité sur l'existence des objets, leurs versions, les rattachements et les transitions. Une validation anticipée ne remplace **jamais** les invariants métier.

À la sortie, le Supra impose des limites de volumes, une autorisation effective et des contrats de complétude/cohérence READ ; il peut **lire des projections READ** pour répondre aux consultations. L'interdiction de lecture WRITE ne signifie pas interdiction des contrôles transactionnels WRITE au sein du Worker, ni impossibilité de fournir des métadonnées d'admission autoritatives sur son propre protocole (idempotence / corrélation) lorsque la persistance de la demande l'exige.

## 2. Invariants à cadrer

- **Rejet d'admission** : aucune Command / IdentityRequest durable n'est créée ; erreur stable et compréhensible, distincte d'un rejet métier terminal d'une demande déjà admise.
- **Validation minimale** : formats, champs obligatoires, nombres/tailles et cohérence intrinsèque des données ; protéger aussi contre les requêtes coûteuses (parsing, profondeurs, collections, chaîne excessive).
- **Sécurité** : identité et bindingToken liés, preuves externes de consentement Link qualifiées et anti-rejeu à l'admission ; aucun contrôle READ faible utilisé pour autoriser une écriture WRITE.
- **Bornage des collections READ** : pagination et ordre stable ; borne supérieure de page imposée par le serveur, pas seulement suggérée au client. Choix du curseur et de son éventuel attachement à une version à arbitrer.
- **Lecture Pot** : distinguer en-tête/références, détails d'Expenses et balance. Une pagination inter-pages ne doit pas promettre un snapshot stable si les sous-versions nécessaires ne sont pas effectivement accessibles et conservées. La lecture `minVersion` doit rester complète et autorisée ou indisponible.
- **Protection des ressources** : limites d'entrées et de sorties, délais, quotas éventuels et comportement de surcharge proportionné au coût réel des opérations ; seuils numériques à justifier avec les profils moyens/grands de V3-5C.
- **Compatibilité avec l'évolution** : enrichir les validations structurelles sans modifier le domaine, les Consumptions et le contrat de traitement des demandes déjà admises ; ne jamais requalifier rétroactivement une demande durable à partir d'une règle nouvellement introduite.

## 3. Inventaire à constituer avant V3-CADRE

| Famille de Supra | Entrées à vérifier | Sorties à borner | Questions ouvertes |
|---|---|---|---|
| Command | authentification, type et taille des mutations, champs, nombres, IDs, clés de répétition | admission et lecture du résultat individuel | tailles par type, distinction erreurs structurelles/métier |
| Identity | Registration/Link/Unlink, EUI, autorité externe et preuve de consentement | résultats individuels, présentation de contexte Link | contexte affiché, TTL, anti-rejeu, limites de payload |
| READ Pot | Pot ciblé, identité, `minVersion`, options de réponse | en-tête, sous-objets référencés, Expenses et Balance | réponse complète ou découpage, cohérence de version et pagination |
| READ liste des Pots | binding authentifié, taille de page et éventuel curseur | liste bornée avec ordre stable | périmètre « visible », pagination et contrôle AuthZ |
| Suivi / autres collections | paramétrage de liste si endpoint réel | listes paginées uniquement si exposées | ne pas inventer d'API de liste non prévue |

## 4. Ordre de travail

1. Inventorier les opérations HTTP réellement prévues ou déjà présentes, sans les présumer publiques en V3.
2. Définir les règles d'admission minimales et les erreurs par famille, y compris coût d'entrée et idempotence.
3. Décider les formats READ collection (ordre, limite, curseur, cohérence des pages) et le découpage de la lecture Pot ; arbitrer l'interaction avec `minVersion`.
4. Définir surcharge/timeouts/autorisation, tout en évitant des contrôles métier sur WRITE.
5. Ajouter les exigences ADM-01 et SUPRA-READ-01 à la matrice de preuve V3-5D, tester séparément correction, performances et ajout ultérieur d'une validation.
6. Reprendre la revue V3-CADRE. **Ne pas déclarer le gate validé avant cette revue.**

## 5. Cas de preuve candidats

- Une entrée malformée ou hors limites est refusée immédiatement, sans demande durable, événement ou slot.
- Une entrée valide formellement mais caduque métier est admise puis rejetée terminalement par WRITE ; aucune fuite d'état métier pendant l'admission.
- L'ajout d'une borne configurable ne modifie pas les demandes anciennes ni les traitements Consumption ; les mêmes invariants domaine restent vérifiés.
- Une collection de grande taille n'est jamais renvoyée sans bornage ; parcours paginé déterministe, absence de doublons/trous dans le modèle de cohérence explicitement retenu.
- Une mutation concurrente pendant la pagination ne produit ni mélange non expliqué de versions ni promesse implicite de snapshot.
- Requête READ insuffisamment matérialisée avec `minVersion` : réponse indisponible explicite, jamais données incomplètes faussement déclarées complètes.
- Les seuils sont confrontés aux profils V3-5C (Pot moyen et grand Pot), aux coûts SQL et aux risques d'amplification.

**Aucun code, DDL ni test V3 exécuté pour cette étape.**

## 6. Arbitrage validé — lecture principale du Pot et collection Expenses (2026-10-23)

**Décision conceptuelle :** la réponse principale d'un Pot contient **l'en-tête, la liste complète des Shareholders et leurs soldes exacts**, tous cohérents à la **même version globale V**. La liste détaillée des **Expenses est consultée séparément et paginée** ; le client n'a pas à charger toutes les Expenses pour afficher la situation financière du Pot.

- **Lecture principale `Pot@V`** : métadonnées et version V, Shareholders dans leur état à V et soldes provenant exclusivement de `PotBalance(P,V)`. Aucune pagination des Shareholders/soldes en V3, **sous réserve de définir et tester une limite de taille compatible** avec les profils moyens/grands.
- **Expenses** : collection paginée avec ordre stable, limites de page côté serveur et possibilité de demander les pages rattachées à **la même version V** que celle de la lecture principale. Ne pas composer silencieusement des pages provenant de versions différentes ; les références de versions locales nécessaires sont déjà historisées côté READ, mais la faisabilité complète (versions des affiliations/éligibilités et suppressions, contrôle d'accès à V) reste à éprouver.
- **Accès et fraîcheur** : la lecture principale reste `minVersion` avec version courante complète ou indisponibilité explicite. La consultation paginée à V est **un contrat de collection versionnée**, pas une API générale de reconstruction historique `Pot@V` ; sa portée, son autorisation et sa durée de disponibilité doivent être précisées avant V3-CADRE.
- **Bornage symétrique** : encadrer les tailles maximales de liste Shareholders, de page Expenses et le coût des scans, en restant compatible avec l'objectif de grand Pot (100 Shareholders, 2 000 Expenses). Aucun seuil chiffré n'est fixé par cet arbitrage.

**Cas de certification :** Pot@V renvoie simultanément Shareholders@V et Balance(P,V), jamais un mix V/V+1 ; absence de Balance(P,V) interdit la lecture complète ; pagination de 2 000 Expenses sans réponse monolithique ; mutation V→V+1 entre pages, qui doivent rester liées à V ou échouer explicitement sans mix ; authorization contrôlée sans assimiler liste de navigation et droit de lecture.

**Statut : structure de réponse VALIDÉE ; pagination versionnée, plafonds et sécurité des pages à préciser.** Aucun endpoint, SQL ou DTO imposé à cette étape.

## 7. Composition des Expenses à V — privilégier la reconstruction depuis les versions déjà historisées (2026-10-23)

**Hypothèse initiale désormais non retenue comme préférence (voir §8) :** la consultation affichée par le client reste à la version globale `V` ouverte jusqu'à une action explicite de rafraîchissement. Les pages d'Expenses demandent explicitement ou implicitement **le même V**. L'évolution de `CurrentPot` vers V+1 ne change jamais la composition des pages à V.

**Comparaison :**
- **Manifeste complet immuable par Pot@V** : déterministe mais duplique à chaque mutation de longues listes de références pour des Pots de 2 000 Expenses ; complexité de stockage et amplification d'écritures.
- **Cache de composition au moment de l'ouverture** : optimisation facultative, mais non autorité de cohérence (éviction, déploiement distribué, redémarrage).
- **Reconstruction directe depuis les sous-versions READ** : **préférence actuelle**, car le domaine définit déjà l'appartenance au Pot, les Headers d'Expenses, les versions locales et la validité à une version globale `activeFrom/activeTo`. Retrouver le Header applicable à V par Expense, exclure `deleted`, paginer suivant un ordre stable, puis résoudre Details/Financials applicables à V. Aucun manifeste supplémentaire tant que l'indexation rend ce parcours acceptable.

**Nuance technique fondamentale :** V3-3 qualifie les `SubobjectVersion` de **strictement immuables**. En conséquence, ne pas présumer qu'un `activeTo` historique est modifié sur READ lors de l'arrivée d'une version ultérieure, notamment en présence de matérialisation hors ordre. Étudier une résolution fondée sur les `activeFrom` immuables des versions successives, ou une représentation équivalente permettant de prouver sans mise à jour d'une ancienne version l'unicité de l'état applicable à V. Les intervalles WRITE ne doivent pas être transposés naïvement aux rows READ.

**Accès sécurisé :** à chaque page, authentifier l'EUI, vérifier `CurrentBinding`/bindingToken, puis l'autorisation réelle via Shareholder lié à l'utilisateur **à V** (contrat SEC-01), jamais via le seul curseur ou `CurrentUserPotList`. L'accès historique à V peut survivre à la disparition d'un Shareholder à V+1 conformément au contrat READ actuel, tant que le binding est actif ; la propagation de révocation READ reste asynchrone.

**Critère de décision définitive :** éprouver la correction sur création/suppression/évolution d'Expense et hors ordre des matérialisations, puis le coût d'une page à V ancien pour 2 000 Expenses et historique long : SQL borné/indexé, pas de N+1 ni scan incontrôlé. Si cela échoue, reconsidérer une matérialisation minimale du manifeste historique plutôt qu'introduire une nouvelle Consumption.

**Statut : consultation fixée à V VALIDÉE ; mécanisme de reconstruction historique PRÉFÉRÉ mais pas encore définitivement validé.** Aucune nouvelle projection ou cache obligatoire décidé.

## 8. Réouverture de la pagination — manifeste courant au client (2026-10-23)

**Rectification de §7 :** la préférence antérieure pour une **requête serveur reconstruisant la composition historique des Expenses** a été documentée **prématurément**. Elle n'est **pas arbitrée**. Deux solutions plus simples sont à comparer :

- **A. Pagination sur CurrentPot courant :** si la version change, le serveur l'indique explicitement et le client recommence depuis la nouvelle version, sans prétendre servir un instantané historique. Simplicité serveur, mais instabilité UX lorsque les mutations sont fréquentes.
- **B. Références chargées en bloc, détails à la demande (nouvelle proposition privilégiée à examiner) :** la lecture principale `CurrentPot(P,V)` fournit, outre en-tête, Shareholders et `PotBalance(P,V)`, **la liste complète et ordonnée des références nécessaires** à l'assemblage des Expenses à V ; le front découpe cette liste en pages et demande les valeurs immuables correspondantes par **lecture groupée, bornée en nombre de références**. Une Expense contient plusieurs sous-objets versionnés (`Header/Details/Financials`) ; conserver les références locales exactes de chacun. Le client ne réinterroge pas `CurrentPot` pour composer chaque page.

**Intérêt de B :** `CurrentPot` porte déjà la composition courante ; aucune nouvelle projection du manifeste historique, aucune reconstruction historique des listes côté serveur, aucun cache/snapshot serveur requis pour des références déjà récupérées. Il faut mesurer la taille du manifeste initial pour les Pots de 2 000 Expenses et valider la forme de la sélection groupée.

**Point de sécurité à résoudre** : un identifiant de sous-objet versionné ne constitue pas une autorisation. À chaque appel de détail, authentifier l'EUI, vérifier le binding actif et appliquer une autorisation **sur le Pot et sur la version V du manifeste initial**, en empêchant d'accéder à des composantes arbitraires d'autres Pots ou d'autres versions. Vérifier que cette autorisation et le contrôle des références demandées sont réalisables avec les projections READ existantes, sans réintroduire de manifeste historique serveur. Définir le comportement lorsque le User perd son accès après V et celui du décalage de révocation READ, conformément à SEC-01.

**Arbitrage non clos :** retenir ou non B, définir si les références reçues du client sont vérifiables efficacement, contrôler que toutes les sous-versions demandées sont matérialisées avant réponse et choisir les limites de taille. Les paragraphes de §7 qui privilégient une reconstruction historique sont **supplantés par cette réouverture**. Aucun mécanisme, endpoint ni coût n'est validé avant discussion.

## 9. Étude d'impact — PotVersion(P,V) immuable complet (2026-10-23)

**Étude comparative, pas une décision définitive.** Au lieu de reconstruire les appartenances à V lors de chaque page (ancienne piste §7) ou de se reposer sur un manifeste exclusivement détenu par le client (§8), envisager une projection **`PotVersion(potId,V)` immuable** portant uniquement la **composition versionnée**, et non les données complètes.

### Modèle candidat

- `CurrentPot(potId,currentVersion)` : simple pointeur mutable monotone, sans recopier le manifeste.
- `PotVersion(potId,V)` : manifeste exact des références versionnées nécessaires à l'assemblage des Headers/Details/Weight/Financials du Pot, de ses Shareholders et Expenses, y compris les inclusions et suppressions à V ; liste distincte des `pocomaUserId` ayant accès via Shareholder à V, **distincte de la liste de navigation** (qui inclut aussi le créateur).
- `SubobjectVersion` : valeurs immuables ; `PotBalance(potId,V)` : balances complètes et immuables, calculées indépendamment ; `CurrentBinding` actif et identité authentifiée toujours requis pour chaque lecture.
- La même `MaterializeCurrentPot` reconstruit l'état autoritatif Pot@V depuis WRITE et insère **dans la même transaction READ** `PotVersion(P,V)`, les nouvelles `SubobjectVersion` et, si V est supérieure, avance conditionnellement le pointeur `CurrentPot`. Aucun nouveau Worker, tâche ou événement. Hors ordre : `PotVersion(P,V)` et les sous-versions anciennes sont conservées même si le pointeur courant ne bouge pas. Le pointeur ne garantit pas que `PotBalance(P,V)` est prête : la lecture complète vérifie les dépendances.
- Le manifeste `PotVersion(P,V)` est autoritatif pour l'appartenance des références à V ; le client charge des pages de références puis leurs sous-versions, en requêtes bornées. Le Supra vérifie à chaque lecture le binding courant et l'autorisation à V, puis que les références demandées sont incluses au manifeste de V. Ne pas exposer directement les listes de `PocomaUserId` au client : ce sont des données d'autorisation internes.
- Vérifier explicitement que l'utilisateur ayant eu accès à V mais retiré en V+1 conserve l'accès à V tant que son binding actif persiste, conformément à SEC-01 ; si la politique voulue est une révocation immédiate des anciennes versions, c'est **un changement distinct de contrat de sécurité à arbitrer**, et non un comportement implicite.

### Coût approximatif du stockage (non mesuré)

Pour un Pot à 2 000 Expenses, si chaque entrée de références versionnées occupe **100 à 200 octets** dans une représentation illustrative, le manifeste des Expenses représente ~**200 à 400 Ko par version**, soit **200 à 400 Mo pour 1 000 versions**, avant références de Shareholders, listes d'autorisation, overhead et index PostgreSQL. Exemple central : 2 000 × 1 000 × 150 o = **300 Mo**. Le vrai coût dépend fortement de la représentation relationnelle/JSONB, des UUID, de TOAST/compression, des index et du nombre de changements ; ces chiffres ne sont **ni un dimensionnement ni un benchmark**. Les octets complets sont réécrits à chaque version si aucune factorisation n'est faite, même sur une mutation d'en-tête.

### Variantes à comparer et simplification

| Variante | Lecture | Écriture | Mémoire/stockage et complexité |
|---|---|---|---|
| Aucun historique de manifeste, reconstruction à V depuis SubobjectVersion | SQL historique à chaque consultation/page | Peu de duplication | CPU/SQL/indices/autorisation complexes ; faisabilité à prouver |
| Manifeste immuable complet par V (candidat) | Accès direct et borné ; lecture stable | O(nombre de références) par version | Stockage O(versions × références), aucune récurrence ni nouveau Worker |
| Manifeste complet en cache côté client, sans PotVersion | Lectures par références à valider | Pas de nouveau manifeste READ | Difficulté de prouver appartenance/autorisation à V côté serveur ; pas de garantie après perte du manifeste client |
| Factorisation des compositions identiques | Indirection contrôlée si référence directe vers composition canonique | Comparaison/partage à l'écriture | Optimisation **éventuelle** si volume mesuré problématique, pas d'indirection récursive V→V-1→... |

**Prudence sur la factorisation :** un simple `V17→V16→V15` induit une profondeur croissante ; si le partage devient nécessaire, préférer pointer directement vers la composition effective et éviter le Worker supplémentaire de compactage, sa synchronisation et la mutation a posteriori de projections immuables. Il n'est **pas adopté** à ce stade.

### Preuves avant arbitrage définitif

1. Exactitude : composition à V et droits via Shareholder à V, y compris révisions locales indépendantes, suppressions et matérialisations Pot V+1 avant V.
2. Sécurité : EUI AuthN/bindingToken actif ; aucune fuite de la liste interne des UserIds, aucune lecture de sous-version d'autre Pot ou non incluse à V ; retrait Shareholder, Unlink et fenêtres READ.
3. Performance : prix de **création de PotVersion(P,V)** sur Pots moyens et grands, amplification WRITE→READ, WAL, TOAST, stockage réel sur 100/1 000/10 000 versions, lecture paginée de références et de 50 détails sans N+1.
4. UX/SLO : lecture principale = en-tête + Shareholders + Balance(P,V) + manifeste d'Expenses (éventuellement complet en références) ; mesurer taille du transfert au client, sans confondre avec les données détaillées des Expenses.
5. Cohérence avec V3-3 : amendement formel à l'inventaire de projections (nouvelle projection logique `PotVersion`, `CurrentPot` simplifié), à PRJ-01/READ-01/SEC-01 et aux épreuves V3-5D **si cette option est adoptée**. Ne pas présenter ce changement comme déjà validé.

**Statut : option prometteuse à comparer ; aucun nouveau Worker, projection ni DDL V3 approuvé/implémenté. V3-CADRE reste ouvert.**

## 10. Décision — PotVersion immuable complet, stockage assumé ; archivage différé à étudier (2026-10-23)

**ARBITRAGE VALIDÉ :** accepter explicitement le coût de stockage linéaire d'une projection **`PotVersion(P,V)` complète et immuable** à chaque version globale V du Pot. `CurrentPot(P)` devient un **pointeur mutable monotone** vers la version maximale matérialisée. Le manifeste de `PotVersion` contient les références versionnées de tous les composants, les listes de Shareholders et Expenses et les identités internes autorisées à lire cette version (dérivées des Shareholders), sans dupliquer les valeurs des sous-objets ni les balances. Le consentement/binding actif et l'AuthN sont toujours revérifiés à chaque requête, même si l'utilisateur figure dans le manifeste historique.

**Même Consumption** `MaterializeCurrentPot`, avec insertion atomique de `PotVersion(P,V)` et des nouvelles sous-versions, avancée conditionnelle du pointeur et finalisation de son slot dans READ. Les tâches Balance et Visibility restent séparées et produisent tous leurs résultats. Les événements hors ordre n'entraînent ni écrasement de versions immuables ni recul de CurrentPot. Aucun mécanisme d'indirection ni Worker de factorisation n'est requis pour V3.

**Effets sur les autres contrats :** la lecture principale du Pot assemble en-tête + Shareholders + Balance(P,V) et retourne la liste des références d'Expenses à V ; le client découpe cette liste et charge les sous-objets par lots bornés. Un `PotVersion` matérialisé n'implique pas que sa balance soit déjà disponible : lecture complète ou indisponibilité explicite. Le Supra vérifie l'appartenance des références fournies au manifeste à V et l'accès à V. V3-3, PRJ-01/READ-01/SEC-01 et les objectifs de tests sont à harmoniser avant clôture V3-5E.

### Archivage des anciennes versions — opportunité, non décision d'implémentation

**Piste approuvée à étudier :** après une durée configurable de **n heures**, déplacer du stockage READ chaud les `PotVersion` qui ne sont plus courantes. Ne jamais assimiler « archivé » à « supprimé » : les historiques utiles à la correction/reconstruction, les sous-versions et balances immuables ne peuvent être effacés sans politique séparée.

**Questions à trancher avant toute activation :**
1. Que garantit la consultation ouverte à V après passage à V+1 ? Un **contrat de durée minimale** (V consultable pendant n heures après remplacement) exige une heure de remplacement durable et une méthode de consultation de l'archive ; sinon le Supra doit retourner un état explicite « version archivée/non disponible », que l'UI traite par rafraîchissement.
2. Comment éviter qu'une version soit archivée tandis qu'un chargement groupé de ses références est en cours ? Définir une barrière transactionnelle ou assurer la lecture de l'archive, sans sessions ni leases nouveaux si possible.
3. Comment gérer l'ordre hors séquence ? Une vieille `PotVersion(P,V)` peut être matérialisée tardivement après que `CurrentPot` pointe V+k. L'âge pertinent de rétention ne peut être déduit naïvement de l'heure de l'événement ; ne pas archiver immédiatement une version tout juste matérialisée nécessaire à une consultation.
4. Une reconstruction complète de READ (§38/39 V3-5D) doit conserver une source de vérité suffisante et une politique de reconstitution des archives ; ne pas confondre archivage de projections READ et purge des événements WRITE.
5. Mesurer le coût réel de stockage chaud, de compression éventuelle et de restitution avant d'introduire un Worker/Consumption d'archivage supplémentaire. Première V3 **sans archivage automatique**, avec métriques pour décider plus tard.

**Statut : PotVersion immuable complet VALIDÉ CONCEPTUELLEMENT ; archivage des versions obsolètes = piste ultérieure, non mécanisme introduit en V3.** V3-CADRE demeure ouvert.

## 11. Lectures groupées d'Expenses — proposition de contrat Supra (2026-10-23)

**À VALIDER :** une lecture groupée demande `potId`, `potVersion=V` et une **liste ordonnée et bornée de références d'Expenses** issues du manifeste `PotVersion(P,V)`. Chaque référence indique l'identité métier Expense et les versions locales nécessaires (`Header/Details/Financials`), ou un identifiant opaque équivalent résolu strictement dans le manifeste. Les détails sont servis exclusivement depuis `SubobjectVersion` ; le Supra ne contacte pas WRITE.

**Bornage symétrique :**
- Refuser avant requête coûteuse une liste vide, des doublons si non normalisés, des références malformées, des volumes excessifs et des combinaisons `potId/V` incohérentes. **Plafond serveur configurable**, avec maximum contractuel publié, indépendant du nombre de références du manifeste principal. Un plafond ne se négocie pas à la hausse par le client.
- Borne également de taille totale de réponse et de temps de traitement ; si un ensemble de références autorisé dépasse le budget, répondre par **erreur explicite**, jamais par troncature silencieuse d'Expenses ou sous-objets. Tester l'interaction avec un Expense à contenu volumineux.
- **Ordre du résultat** : restituer les items dans l'ordre demandé (ou documenter un autre ordre canonique), avec identité/version et signal explicite pour chaque élément non résolu. Aucune réussite partielle trompeuse : soit le lot complet est servi, soit une erreur cohérente est fournie.

**Sécurité et cohérence :** EUI authentifiée, binding actif contrôlé dans `CurrentBinding`, utilisateur autorisé à `PotVersion(P,V)` ; chaque référence fournie doit appartenir au **manifeste de cette version exacte**. Une liste de références n'est jamais une preuve d'autorisation ; ne jamais accepter une référence d'un autre Pot ou d'une autre version même si sa valeur existe. Toute sous-version nécessaire à une Expense doit être présente ; sinon indisponibilité explicite, et aucune reconstruction à partir de `CurrentPot` plus récent.

**Contrat UX :** la lecture principale sert le manifeste complet des références d'Expenses, le front en découpe les groupes à la demande ; il peut continuer à V alors que le courant atteint V+1. Un nouvel état courant est signalable séparément ; aucun basculement implicite en cours de lot.

**Scénarios SUPRA-READ-01 proposés :** 0/1/N références ; juste sous/à/au-dessus du plafond ; référence dupliquée ; référence étrangère au Pot ou à V ; EUI non autorisée ; Unlink matérialisé dans CurrentBinding ; V ancien et CurrentPot avancé ; sous-version non encore matérialisée ; Expense avec payload extrême ; 50 Expenses chargées sans N+1 ; réponse non partielle et ordre reproductible.

**Point non arbitré :** taille effective du plafond, borne en octets, ordre du manifeste et représentation des références ; confronter aux profils V3-5C. Éviter d'introduire une pagination serveur *en plus* du découpage client sans besoin démontré.

## 12. Admission Command / IdentityRequest — contrat minimal proposé (2026-10-23)

**PROPOSITION À DISCUTER, pas encore un choix de seuils.** Les Supras d'admission rejettent avant persistance les demandes qui ne respectent pas le **contrat autonome d'entrée**, sans chercher l'état métier courant dans WRITE. La persistance de l'admission et la gestion de l'idempotence utilisent leur propre autorité transactionnelle ; l'interdiction concerne la lecture du **domaine métier** WRITE pour décider de l'acceptation.

| Couche de validation | Contrôles admissibles immédiatement | Ce qui reste au Worker |
|---|---|---|
| Transport / désérialisation | taille maximale des octets reçus et décompressés, profondeur JSON, champs/types requis, nombres finis, format des identifiants, champs inconnus selon contrat | existence de Pot, Expense, Shareholder ou EUI en état courant |
| Forme de l'opération | type de commande reconnu, nombre maximum de cibles et de participants, longueur de textes, bornes numériques intrinsèques, cohérence interne entre champs d'un même payload | droits sur Pot, référence de version observée, compatibilité avec les versions actuelles et transitions métier |
| Identité / protocole | authentification réelle, audience, identité EUI, présence et forme du bindingToken, pour Link preuve externe d'accord transactionnel valide et non rejouée | lien EUI/bindingToken **courant**, permission effective et effets de Registration/Link/Unlink à l'exécution |
| Admission durable | anti-rejeu, corrélation, idempotence et création **unique** de la demande lorsqu'elle est valide | succès/rejet métier terminal et événements de complétion |

**Précisions :**
- Le domaine vérifie toujours ses invariants au Worker ; les contrôles anticipés sont un filtre UX/coût, **pas une nouvelle autorité métier**. Exemple : montant non négatif / poids positif peuvent être rejetés dès l'entrée mais doivent également être validés au domaine.
- Ne pas introduire de contrôle de « Pot connu » ou de « Shareholder autorisé actuellement » à l'admission sur base d'une projection READ potentiellement retardée.
- Un **refus préalable** ne crée ni demande, ni résultat terminal, ni slot, ni événement. Un rejet métier d'une demande admise reste un **résultat terminal durable** avec complétion.
- Éviter d'imposer la même limite de taille à tous les types de mutations : une Command multi-cibles et un Link n'ont pas les mêmes risques. Fixer un plafond global de requête **et**, si nécessaire, des limites propres à des collections/champs. Respecter les profils moyens et grands V3-5C sans imposer d'emblée des nombres injustifiés.
- Protéger contre l'amplification : limite de taille du corps **avant** parsing, limite décompressée si compression acceptée, nombre d'éléments et octets textuels, temps d'admission et nombre de requêtes en vol. Réponses d'erreur bornées sans divulguer l'existence d'un objet métier.
- Règles d'évolution : changer une limite ou ajouter un contrôle ne requalifie jamais une demande **déjà admise** ; la nouvelle règle s'applique aux nouvelles admissions, sans toucher Consumption ni les transitions WRITE.

**Cas ADM-01 à certifier :** (A) malformed/oversized → refus immédiat zéro durable ; (B) cas à la limite accepté et limite+1 refusé ; (C) payload formellement admissible mais objet absent ou conflit de version → demande admise puis rejet terminal ; (D) tentative d'usurper un bindingToken sans identité correspondante → refus de sécurité sans fuite ; (E) même requête concurrente/idempotence → une admission effective ; (F) règle ajoutée après l'admission sans modification du destin de cette demande ; (G) injection de payload coûteux et mesures CPU/mémoire/latence ; (H) Link sans attestation de consentement métier vérifiable → aucune admission.

**À décider ensuite :** limites numériques par type, conventions d'erreurs, politique pour champs inconnus, syntaxe des identifiants publics, corps compressés et traitement HTTP précis des répétitions. **Le principe et le périmètre sont définis ; la matrice de seuils n'est pas validée.**

## 13. Politique commune d'erreurs et plafonds candidats (2026-10-23)

**PROPOSITION À VALIDER**, complément de §11 et §12. La politique distingue le refus à la frontière, la demande admise mais rejetée par le Worker, et le défaut temporaire de READ. Pas d'état durable Command/IdentityRequest ni d'événement de complétion pour un refus avant admission ; les retries idempotents d'une demande déjà admise doivent toujours retrouver leur demande plutôt que subir rétroactivement une nouvelle limite.

| Situation | Comportement HTTP candidat | Sémantique |
|---|---|---|
| Payload malformé / paramètre invalide | `400 Bad Request` | Rejet sans admission, code d'erreur stable et champ concerné si sûr |
| Structure valable mais violation de contrat intrinsèque (longueur, poids, cardinalité) | `422 Unprocessable Content` | Rejet sans admission, jamais un rejet métier terminal |
| Corps supérieur au plafond en octets | `413 Content Too Large` | Stopper avant désérialisation coûteuse |
| Liste groupée dépassant la limite permise | `422` | Aucun sous-objet retourné, pas de troncature silencieuse |
| Non authentifié | `401 Unauthorized` | Aucun état métier divulgué |
| Authentifié mais non autorisé | `403 Forbidden` ou `404` uniforme anti-énumération à arbitrer | Ne pas révéler l'existence d'un Pot/Expense protégé |
| Quota/débit excédé | `429 Too Many Requests` | `Retry-After` si pertinent, sans promettre un délai précis |
| Version insuffisamment matérialisée / READ en reconstruction | `503 Service Unavailable` avec erreur stable | Le client peut réessayer ; ne jamais répondre avec des données partielles trompeuses |
| Demande durable exécutée et rejetée | Admission réussie ; `CommandResult` / `IdentityRequestResult` terminal `REJECTED` | Rejet métier durable, distinct du HTTP initial |
| Erreur serveur imprévue | `500` | Diagnostic interne corrélé ; aucun détail sensible exposé |

**Enveloppe d'erreur** : `code` stable, `message` générique, `correlationId`, éventuellement `field` et `retryable` lorsque leur sémantique est assurée. Aucune exception SQL, identité non autorisée, preuve IdP brute ou token dans les détails d'erreur. Pour une répétition idempotente, préserver l'identité et l'issue durable de la demande initiale.

### Paramètres de départ candidats — à mesurer

| Limite | Candidat initial | Justification et réserve |
|---|---:|---|
| Corps HTTP Command / IdentityRequest non compressé | **256 KiB** | Borne globale simple, à vérifier sur plus grosse Command multi-cibles ; éviter les requêtes démesurées |
| Taille de texte individuel | **4 KiB** | Aucun champ libre ne doit pouvoir produire une amplification disproportionnée ; seuil dépend du champ métier |
| Nombre de cibles d'une Command multi-cibles | **100** | Candidat de protection, **ne déduit pas** la limite fonctionnelle du nombre de Shareholders/Expenses |
| Références dans un lot READ Expenses | **50** | Favorise découpage UI et vérification bornée de manifeste ; mesurer nombre d'appels et coût |
| Taille d'une réponse READ groupée | **1 MiB** | Échec explicite si le lot est trop lourd ; éviter la troncature |
| Shareholders servis sans pagination | **100 pour le profil grand Pot**, **pas une limite fonctionnelle** | Tester ce profil et déterminer séparément la borne contractuelle maximale |
| Taille de manifeste `PotVersion` transmis | **Pas de seuil adopté** | Vérifier la taille réelle du Pot 2 000 Expenses et borner la ressource avant d'imposer un plafond |

Les chiffres sont des **hypothèses d'essai**, non des contraintes normatives. Toute limite susceptible de refuser une requête fonctionnellement légitime doit être confrontée aux contrats métier. Configurer séparément les ressources de sécurité (octets, éléments, concurrence) sans exposer au client le moyen de les augmenter. Limiter la complexité : privilégier des garde-fous d'infrastructure et des validateurs de payload simples, sans pipeline générique de règles.

**Questions restantes :** stratégie pour les corps compressés (interdire à l'admission V3 ou borner octets comprimés et décomprimés), choix uniforme 403/404, taille maximale de l'objet financier individuel et compatibilité du plafond de 100 cibles avec les commandes métier.

## 14. Arbitrage — masquage des ressources métier (2026-10-23)

**Principe validé : 404 par défaut pour toute ressource métier dont l'existence ne doit pas être divulguée.** Une ressource absente et une ressource existante mais non autorisée répondent de manière indiscernable pour le client non autorisé, sans exposer la raison interne. Inclut Pot, Expense et références de sous-objets non autorisées ; la possession d'une référence versionnée ne vaut jamais droit d'accès.

**403 reste possible uniquement pour une interdiction indépendante de l'existence d'une ressource particulière**, par exemple un privilège fonctionnel global connu du client authentifié. Aucun cas 403 concret n'est imposé en V3 tant qu'il n'est pas identifié. **401** demeure réservé à l'absence/invalidité d'authentification. Une Command formellement valide peut être admise sans lecture métier WRITE puis rejetée par le Worker : ne pas convertir cette décision ultérieure en 403 d'admission.

**Preuves SUPRA-READ-01/SEC-01 à ajouter :** ressource absente vs interdite sans distinction observable pertinente (statut, corps, détails et comportement contrôlable), refus de références étrangères sans fuite, réponses sensibles non mises en cache partagé, et cas 403 éventuels prouvant l'indépendance de la ressource. Le cache et les timings sont à qualifier de façon réaliste, sans exiger une constante cryptographique de temps pour chaque lecture SQL.

**Statut : politique validée ; inventaire d'éventuels 403 et conventions exactes d'erreur à finir dans V3-5E.**

## 15. Synthèse des bornes et surcharge — propositions à arbitrer (2026-10-23)

**Statut : proposition de simplification, aucun seuil numérique ratifié par le seul présent ajout.** L'objectif est de maintenir deux familles de protections : (a) contrôles déterministes avant admission ou lecture (formats, tailles, nombre d'éléments, autorisation) ; (b) limitation d'utilisation des ressources sous charge (débit, concurrence et délais), sans introduire un ordonnanceur ni un moteur générique de validation.

### Options recommandées pour le socle initial

1. **Corps d'admission compressés non acceptés** initialement ; une seule borne en octets sur les données reçues avant le parsing. Éviter de devoir maintenir deux limites et de se prémunir contre les bombes de décompression. Si compression ajoutée ultérieurement, exiger plafond sur données encodées ET décodées.
2. **Limites en octets et en cardinalité** : adopter en première intention les valeurs d'essai §13 (256 Kio HTTP, 4 Kio de texte, 50 références READ, 1 Mio de réponse groupée) seulement après tests de conformité aux usages ; le plafond de **100 cibles multi-Command** reste à évaluer séparément, car les Command multi-cibles légitimes ne doivent pas être refusées silencieusement.
3. **Aucun plafond artificiel à 100 Shareholders** : 100 désigne seulement le profil de grand Pot testé en V3-5C. Si besoin, définir plus tard une limite métier explicite et compatible avec la structure sans pagination retenue.
4. **Limiter la concurrence avant d'accumuler une file HTTP** : budgets configurables de requêtes en vol par type d'opération ; dépassement rapide et explicite (`429` pour quota client, `503` pour saturation du service), avec `Retry-After` seulement lorsqu'il est honnête. Les Consumptions admises continuent d'être traitées durablement selon leurs propres garanties.
5. **Timeouts explicites** et réponse bornée : un timeout READ ne transforme pas un état incomplet en `404` ; retourner une indisponibilité temporaire. Si l'admission a pu être commitée mais sa réponse HTTP perdue, le retry retrouve **la demande d'origine** par la clé d'idempotence ; ne pas créer de deuxième Command.
6. **Pas de traitement « partiel réussi » des lots READ** : si la réponse estimée dépasse le plafond ou manque un sous-objet, refuser le lot entier. Proposer un lot plus petit sans fuite d'information sensible.
7. **Politique d'erreur** : la décision validée §14 prévaut sur l'ancienne cellule `403/404` §13 ; `404` pour ressource métier absente/non autorisée, `403` seulement pour interdiction globale explicite.

### Cas à intégrer dans la certification ADM-01 / SUPRA-READ-01 / PERF-01

- Corps de taille juste en deçà / égale / au-dessus du plafond, contenu JSON malformé et champ surdimensionné, compression interdite ; aucune création durable pour rejet préalable.
- Multiplication de requêtes courtes/concurrentes : consommation bornée de CPU/mémoire/connections, rejet ou ralentissement explicite, pas d'effondrement du Worker et pas de pertes de demandes déjà admises.
- Admission commitée puis timeout/perte de réponse : retry idempotent, identité de la demande préservée.
- Lot READ de 50 Expenses à données habituelles ou extrêmes ; rejet explicite sans résultat tronqué, coût des lectures borné.
- Pot de 100 Shareholders / 2 000 Expenses : lecture principale complète (Shareholders + balances + manifeste de références), mesurer la taille réelle de réponse et le nombre de requêtes ; ne pas en inférer un plafond de domaine.
- READ en maintenance/reconstruction ou retard de sous-version : `503` ou code temporaire stable, jamais `404` trompeur.

**Arbitrages encore ouverts :** seuils en octets réels, maximum de cibles multi-Command, politiques détaillées de quotas et timeout, enveloppe d'erreur finale. Les paramètres de déploiement sont ajustables mais **ne peuvent affaiblir les plafonds contractuels publiés** sans évolution du contrat. V3-CADRE demeure ouvert.

## 16. Arbitrage de principe — limites de sécurité versus limites fonctionnelles (2026-10-23)

**Décision de cadrage :** distinguer explicitement deux catégories de bornes, sans imposer une limite métier simplement parce qu'une charge d'essai comporte 100 Shareholders ou 2 000 Expenses.

| Catégorie | Où et pourquoi | Exemples | Politique d'évolution |
|---|---|---|---|
| **Sécurité et protection de ressources** | Supra / infrastructure : limiter coût de parsing, taille d'entrée/sortie, cardinalité d'un lot, concurrence et durée d'une requête | Corps HTTP, tailles de texte transportées, 50 références groupées comme valeur **candidate**, budget de réponse, limite d'in-flight | Configurable côté serveur, documentée et testée ; ne doit jamais tronquer silencieusement ni invalider une demande déjà admise |
| **Fonctionnelle / invariant de domaine** | Domaine : déterminer ce qu'une commande métier peut signifier et quelles transformations sont admissibles | Montant non négatif, poids strictement positif, règles de cibles et cohérence de mutation | Identique au niveau du Worker, éventuellement prévalidée à l'admission quand autonome et indépendante de WRITE |

**Commands multi-cibles :** ne pas instituer de maximum **métier** de cibles à 100 sans besoin démontré. Une limite technique de cardinalité ou d'octets peut être nécessaire pour une admission bornée, mais doit être déterminée à partir de la taille réellement sérialisée et du coût de traitement des opérations admises, compatible avec le grand Pot. La valeur « 100 cibles » de §13 est **une hypothèse de test, non une borne validée**. En cas de dépassement d'un plafond de transport, le client doit pouvoir soumettre une opération métier adaptée si celle-ci est effectivement sécable **sans changer sa sémantique atomique** ; ne jamais promettre une division automatique d'une Command atomique multi-cibles.

**READ :** le fait de servir intégralement les Shareholders et soldes, et de transmettre la liste des références des Expenses dans la lecture principale, représente un **contrat fonctionnel de complétude**. Les limites de transport ne peuvent produire un Pot partiel présenté comme complet. Les chargements détaillés sont bornés par lots ; si une réponse principale devient trop grosse, le problème doit être traité comme un **arbitrage de contrat** (format ou découpage) et non masqué par une troncature.

**Refus et résultats :** dépassement de limite de transport/cardinalité = refus à l'admission sans demande durable ; invariant métier incohérent à l'exécution = rejet terminal durable. Les validations intrinsèques du domaine (montants/poids) peuvent être prévalidées sans affaiblir leur enforcement au Worker.

**Tests discriminants :** grande Command légitime au-dessus de 100 cibles (ne pas la déclarer métier invalide par défaut) ; Command atomique non sécable sous plafond ; corps volumineux invalidé avant parsing ; Pot 100/2 000 complet malgré limites de lot ; limites configurées à la baisse pour une nouvelle admission sans effet sur les demandes déjà persistées ; absence de requête WRITE métier à l'admission.

**Statut : séparation conceptuelle VALIDÉE ; les seuils chiffrés des protections demeurent à calibrer avant qualification, sans ajout de règle métier arbitraire.** V3-CADRE reste ouvert.

## 17. Revue finale de couverture V3-5E — 2026-10-23

**Verdict : cadrage avancé, MAIS CLÔTURE V3-5E NON ENCORE PRONONÇABLE.** L'approche est cohérente (Supras sans consultation de l'état métier WRITE, PotVersion immuable, lecture courante Shareholders + soldes + références Expenses, lecture détaillée en lots, refus 404 anti-énumération, protections de ressources sans limites métier arbitraires). Les seuils d'octets et de concurrence restent des candidats à tester, non des blocages conceptuels.

### Couverture par opération

| Opération / Supra | Établi | À préciser avant clôture |
|---|---|---|
| Admission Command | séparation structure/sécurité/métier ; zéro demande durable en refus préalable ; idempotence | format minimal précis par type de commande et réponse de retry |
| Admission Registration | principe de validation structurelle et authentification | erreurs, correspondance EUI et admission idempotente |
| Admission Link | preuve explicite du fournisseur liée à l'intention, anti-rejeu | format et réalisation chez fournisseur qualifié (preuve V3-6/7) |
| Admission Unlink | séparation admission/exécution, identité AuthN | périmètre de contrôle au Supra, refus vs rejet métier |
| Consultation CommandResult / IdentityRequestResult | résultat unitaire terminal et immuable | contrat HTTP quand résultat pas encore matérialisé, sans fuite d'existence |
| Lecture du Pot | CurrentPot pointe PotVersion(P,V), Shareholders et balances exacts à V, manifeste de références | taille/transfert du manifeste et politique si une dépendance V n'est pas prête |
| Chargement groupé Expenses | références appartenant à PotVersion(P,V), lots tout-ou-erreur, autorisation à V | plafond et enveloppe d'erreur ; surtout correction en présence de références venant d'autres versions |
| Liste des Pots de l'utilisateur | CurrentUserPotList navigation seulement | **choix de pagination et stabilité sous évolution concurrente à finaliser** |
| Consultation CurrentBinding après Identity | minVersion et identité vérifiée | réponse publique minimale, présence et exposition du bindingToken à qualifier |
| Sécurité et surcharge | 401/404, 403 uniquement si privilège global, 429/503, contrôle du coût | protections des réponses, cache, mesures, paramétrage infra |

### Deux questions réellement structurantes encore ouvertes

**S-01 — Liste des Pots paginée.** Le front ne reçoit pas ici un manifeste figé comparable à PotVersion. CurrentUserPotList est mutable et peut évoluer entre deux pages. Définir une sémantique explicite : pages sur **liste vivante** avec risque annoncé de décalage, ou snapshot stable nécessitant un support supplémentaire. La règle de simplicité suggère un ordre déterministe et une pagination par clé stable, sans promesse d'instantané, mais la politique anti-doublons/manquants reste à arbitrer.

**S-02 — Revocations entre deux lectures historiques.** Le modèle SEC-01 actuellement documenté autorise un utilisateur ayant accès à Pot@V à continuer à consulter V après retrait de son Shareholder à V+1, tant que son CurrentBinding demeure actif. Cette propriété peut surprendre en sécurité/UX. Confirmer expressément s'il faut préserver cet accès historique ou appliquer l'autorisation à la version courante également ; ce choix a des implications de confidentialité et ne peut pas être caché dans la mécanique de pagination.

### Actions documentaires et preuves

1. Arbitrer S-01 et S-02, puis réécrire les sections historiques §7–§9 supplantées pour qu'elles ne laissent pas de préférence technique concurrente.
2. Ajouter la couverture ADM-01 et SUPRA-READ-01 à la matrice d'exigences et aux preuves V3-5D ; étendre la matrice 24/24 en précisant la distinction « exigences transverses supplémentaires ».
3. Harmoniser les représentations PotVersion et CurrentPot dans la documentation normative et la table de suivi ; préciser l'enveloppe d'erreur HTTP sans exposer le domaine.
4. Tester les seuils candidats sur profils V3-5C avant de figer le dimensionnement ; aucun PASS V3 revendiqué.

**Gate V3-CADRE reste OUVERT. Aucune implémentation V3 ni test exécuté.**

## 17. Revue de clôture V3-5E — couverture et derniers arbitrages (2026-10-23)

**Verdict : CONCEPTION AVANCÉE, CLÔTURE DIFFÉRÉE.** Les frontières majeures sont décrites ; **trois décisions fonctionnelles de Supra restent à trancher avant un V3-CADRE sans ambiguïté**. Ne pas confondre des plafonds chiffrés calibrables en V3-6/7 et des choix de sémantique qui déterminent les API.

### Matrice de couverture

| Opérations / frontière | Couvert par | Statut |
|---|---|---|
| Admission `COMMAND` | §§1–2,12–16 | Principe défini ; idempotence/erreur après commit incertain à éprouver |
| Admission `IDENTITY_REQUEST` (Registration, Link, Unlink) | §§1,12–16 et V3-5D §37 | Principe défini ; adaptation du contrat de preuve de Link au fournisseur concret ultérieure |
| Résultat individuel Command/Identity | V3-3 et V3-5D | Lecture individuelle sans pagination ; erreurs et attente asynchrones à harmoniser |
| Pot courant (en-tête, Shareholders, soldes, manifeste) | §§6,9–10 et V3-3 §9 | Structure décidée ; taille/complétude de la réponse initiale à traiter |
| Expenses par groupe de références de V | §§10–11 | Contrat proposé, plafond numérique non arbitré |
| Liste de Pots visible pour User | §3 et V3-3 | **OUVERT : ordre, pagination, cohérence entre pages, visibilité vs autorisation** |
| READ retardé, maintenance et surcharge | §§13–16, V3-5D §§38–40 | Politique proposée, tests d'erreurs à compléter |
| AuthN/AuthZ/masquage 404 | §§1,11,14 | Politique de principe validée ; 403 exceptionnel |
| Archivage d'anciennes PotVersion | §10 | Hors socle V3 ; ne pas imposer d'archivage automatique |
| Limites numériques, quotas et timeouts | §§13,15–16 | **Calibrage reportable** si les contraintes fonctionnelles sont préservées |

### Trois arbitrages à clôturer

**S-01 — Pagination de `CurrentUserPotList`.** Choisir ordre de tri stable et mécanisme de reprise ; déterminer le contrat lorsque des entrées deviennent invisibles/visibles pendant la navigation. La liste sert à naviguer, **pas** à donner l'autorisation. Une réponse potentiellement grande ne peut rester sans borne.

**S-02 — Complétude et budget du manifeste `PotVersion`.** La lecture principale promet en-tête + tous Shareholders/soldes + liste complète des références d'Expenses. C'est un engagement fonctionnel : le Supra ne peut couper silencieusement la liste si la réponse dépasse son plafond. Décider explicitement si l'on maintient le manifeste complet initial (puis limite maximale publiée et testée) ou si les références elles-mêmes doivent être récupérables par lots **sans refaire apparaître les problèmes de version**. Le stockage immuable de `PotVersion` permet les deux ; ne pas décider à partir des seuls 2 000 Expenses de charge.

**S-03 — Identité d'une admission répétée et codes d'erreur.** Fixer un principe d'idempotence observable après réponse HTTP perdue, distinguer `409` éventuel de conflit de clé d'idempotence avec payload différent, `400/413/422` et refus avant persistence, sans confondre avec un conflit de version métier traité par le Worker. Le contrat d'idempotence peut rester simple, sans imposer de format UUID ou de stockage supplémentaire à cette étape.

### Choix déjà acquis et recommandations

La projection `PotVersion` immuable, CurrentPot pointeur, la version de consultation stable côté UI, les Shareholders/soldes complets, la sécurité sur les sous-références et le masquage 404 **ne sont pas remis en cause**. Pour S-01, pagination keyset ordonnée par identifiant de Pot serait une candidate simple, mais **pas une photographie stable de toute la liste**. Pour S-02, mesurer taille de manifeste et envisager références récupérées par lots seulement si nécessaire. Pour S-03, préserver l'identité durable d'une demande déjà admise avant toute nouvelle validation et refuser le réemploi d'une clé avec une intention différente.

**Statut : S-01/S-02/S-03 OUVERTS ; pas de nouveaux mécanismes runtime ou DDL décidés ici. V3-CADRE reste OUVERT.**

## 18. S-01 validé — pagination vivante de CurrentUserPotList (2026-10-23)

**DÉCISION :** ordre total stable par `potId` croissant, pagination keyset avec curseur opaque représentant au minimum le dernier `potId` servi ; aucune session, aucun snapshot inter-pages, aucun offset. À chaque requête, résoudre l'utilisateur authentifié via `CurrentBinding` puis consulter les entrées `CurrentUserPotList` actuellement visibles de cet utilisateur, avec `potId > lastPotId`, `ORDER BY potId` et limite serveur. Revalider l'identité à chaque page ; la liste est une navigation, **non une autorité d'AuthZ Pot**.

**Sémantique des évolutions :** un Pot nouvellement visible avec un ID inférieur ou égal au curseur ne sera pas vu dans ce parcours ; un nouvel ID supérieur peut apparaître ; un Pot devenu invisible disparaît des pages suivantes. Aucun doublon causé par le déplacement d'un Pot, puisque `potId` ne change pas. Pour retrouver l'état de navigation récent, le client recommence depuis le début. Ne pas promettre un inventaire complet à un instant donné.

**Curseur :** opaque et non assimilable à une capacité de lecture. Sa forme concrète (encodage, signature éventuelle, paramètres de filtre/page et erreur si altéré) relève du contrat Supra de réalisation ; son interprétation ne doit jamais permettre d'élargir l'identité autorisée ou la limite côté serveur. Le serveur peut récupérer `limit+1` entrées pour déterminer `nextCursor` sans COUNT global.

**Preuves S-01 :** pages successives sans duplication à liste fixe ; Pot ajouté avant/après curseur ; Pot devenant invisible ; cursor altéré/non valide ; demande au-delà du plafond ; changement de binding ; profil utilisateur avec beaucoup de Pots et index compatible. Vérifier l'absence de fuite inter-utilisateurs et l'absence de scan de toutes les entrées.

**Statut : S-01 RÉSOLU conceptuellement.** Reste S-02 (taille du manifeste principal) puis S-03 (idempotence d'admission). 

## 19. S-02 validé — manifeste complet dans la lecture principale (2026-10-23)

**DÉCISION :** la réponse principale d'un Pot à V contient l'en-tête, la totalité des Shareholders et de leurs soldes exacts à V, **et la liste complète ordonnée des références versionnées de toutes les Expenses appartenant à `PotVersion(P,V)`**. Aucun second mécanisme de pagination des références en V3. Le front établit ses pages d'affichage à partir de cette liste et charge les détails par lots bornés via le Supra (§11).

**Complétude et sécurité :** une réponse principale ne peut être tronquée silencieusement ; si le contrat de sécurité empêche réellement une réponse complète, erreur explicite, pas de `200` partiel. `PotVersion` n'expose **pas** ses `pocomaUserId` internes d'autorisation : seul le manifeste des références d'Expenses destiné au client est transmis, après AuthN et vérification de l'accès à V. Les références ne sont jamais des capacités d'accès.

**Bornage :** mesurer le volume encodé de la réponse pour le Pot grand profil (100 Shareholders, 2 000 Expenses), avec identifiants et versions locales réels, puis fixer un plafond **compatible avec les tailles fonctionnelles supportées**. Le candidat 1 Mio de §13 concerne les réponses de **détails groupés**, pas une limite adoptée sur le manifeste principal. Si des Pots légitimes dépassent le plafond, reconsidérer explicitement la forme de l'API ; ne pas déclarer ces Pots interdits par effet secondaire.

**Cohérence :** toutes les références appartiennent à V ; les détails demandés ultérieurement utilisent exactement ces versions immuables, même si `CurrentPot` a avancé. L'UI contrôle le rafraîchissement de la version affichée.

**Statut : S-02 RÉSOLU conceptuellement ; taille réelle et paramètres de protection à qualifier.** S-03 (idempotence d'admission) reste ouvert.

## 20. S-03 validé — idempotence des admissions (2026-10-23)

**DÉCISION :** une Command ou IdentityRequest soumise par un client possède une **clé d'idempotence fournie par le client**, liée à une portée d'identité authentifiée et à une **intention canonique** de la demande. L'admission réserve durablement cette association **atomiquement avec la création de la demande**, sans consultation de l'état métier courant WRITE.

- **Première soumission** : contrôler le contrat d'admission et les preuves exigées (notamment consentement transactionnel pour Link), puis créer une unique demande durable et son association d'idempotence dans la même transaction physique.
- **Répétition, même clé et même intention dans la même portée** : retrouver la **même demande durable et son identifiant**, sans recréer de demande ni réévaluer rétroactivement de nouvelles limites structurelles. La relecture peut indiquer son statut actuel sans inventer de nouvel événement métier.
- **Même clé, intention différente dans la même portée** : **409 Conflict**, sans nouvelle admission ni fuite du contenu de l'intention antérieure.
- **Même clé dans une portée distincte** : ne pas permettre l'accès à la demande de l'autre identité ; définir la portée via l'identité authentifiée, l'opération et le client appelant, sans s'appuyer sur un simple bindingToken non authentifiant. Une clé seule n'est ni une capacité d'accès ni un identifiant métier global.
- **Concurrence** : deux requêtes simultanées ne peuvent créer deux demandes pour la même paire portée/clé. Contrainte durable d'unicité et transaction atomique, avec issue déterministe après commit connu, rollback ou réponse perdue.
- **Link** : une clé d'idempotence ne remplace **jamais** l'attestation externe de consentement de E2. Un retour de demande déjà admise n'autorise pas une nouvelle intention Link ; l'association doit rester liée à la preuve et à sa cible non substituable.
- **Canonicalisation** : comparer les intentions métier (type d'opération, cible, payload pertinent, contexte d'identité) de façon déterministe, sans dépendre d'ordre arbitraire JSON ni de champs purement techniques. Le format/digest précis reste un détail à spécifier avant implémentation.
- **Durée de rétention** : conserver l'association au moins pendant toute la durée où une répétition est supportée. **Ne pas annoncer une idempotence illimitée** sans politique de rétention ; durée contractuelle et purge éventuelle restent à fixer avant activation. Tant qu'une association est conservée, aucune nouvelle demande n'est créée avec cette paire portée/clé.
- **Admission invalide** : une requête refusée avant admission ne crée ni Command/IdentityRequest, ni association durable d'idempotence ; ne pas transformer une erreur HTTP préalable en résultat métier terminal.

**Tests S-03 :** première admission et réponse ; répétition avant et après terminalisation ; même clé/payload différent 409 ; JSON équivalent ; appelant/identité différente ; deux admissions concurrentes ; rollback de la première ; commit réussi mais réponse perdue ; règles de validation ajoutées entre deux appels ; Link avec attestation non substituable et anti-rejeu.

**Statut : S-03 RÉSOLU conceptuellement.** Le détail du format des clés, de leur durée contractuelle et des réponses HTTP nominales est à figer pendant la préparation des tests et de la réalisation. S-01/S-02/S-03 sont désormais arbitrés ; la revue de clôture de V3-5E peut reprendre.

## 21. Revue finale de clôture documentaire V3-5E (2026-10-23)

**Verdict : V3-5E CLOSED — cadrage conceptuel et contrats de frontière stabilisés ; aucune certification d'exécution.** Les trois décisions identifiées dans la revue §17 sont résolues par §18 (S-01 pagination de la liste utilisateur), §19 (S-02 manifeste PotVersion complet), §20 (S-03 idempotence atomique des admissions). La politique 404/403 (§14) et la séparation protection technique/invariant métier (§16) sont retenues. La projection READ V3-3 §10 et les preuves V3-5D §§43–49 sont alignées.

**Réconciliation de l'ambiguïté documentaire :** un ancien journal du canon appelle également « S-02 » la question de la révocation rétroactive des droits. Ce sujet est **distinct** du S-02 des §§17–19 et **déjà arbitré** dans V3-3 §9 et SEC-01 : l'autorisation à la version historique V dépend des Shareholders de V, sous réserve d'un `CurrentBinding` actif à la lecture. Le retrait ultérieur d'un Shareholder ne révoque pas rétroactivement les anciennes versions. Des tests dédiés doivent le prouver.

**Réserves transférées à la préparation de réalisation/certification (non décisions de domaine) :** seuils numériques réels et essais de charge, format exact du curseur, tailles et formats des clés/digests d'idempotence, durée publiée de rétention d'une clé et stratégie de purge, sémantique détaillée des erreurs et succès HTTP, tests de fournisseur de consentement Link et normalisation de la documentation historique. Ces points **doivent être fixés avant de prétendre les API prêtes à être activées** ; ils ne créent pas d'invariant métier supplémentaire. En particulier, sans durée explicite de conservation d'une clé, ne pas promettre la répétition indéfinie d'une admission.

**V3-CADRE demeure OUVERT tant que sa propre revue/gate n'est pas explicitement prononcé.** Aucun runtime/DDL/test V3 construit ni exécuté au titre de cette clôture documentaire.

## Amendement post-audit AUD-V3-010 — résultats de demandes, propriété EUI et polling (2026-10-24)

**DÉCISION NORMATIVE, prioritaire sur le « À préciser » du §17 et les propositions HTTP antérieures.** Pour `GET /commands/{requestId}/result` et la consultation équivalente d'`IdentityRequestResult`, la Supra READ exige une EUI authentifiée et consulte exclusivement la projection READ terminale. Elle ne demande aucun `bindingToken` et ne résout ni Binding courant ni PocomaUser. Le `requesterEui` immuable est présent dans chaque projection de résultat, recopié par la Consumption depuis la demande durable WRITE à l'occasion de la matérialisation ; aucune lecture WRITE synchrone à l'appel HTTP.

| Situation | HTTP | Contrat |
|---|---|---|
| Non authentifié | `401` | Aucun renseignement sur la demande |
| Résultat terminal READ présent et `requesterEui` identique à l'EUI authentifiée | `200 OK` | Résultat terminal unique, succès ou rejet métier |
| Résultat absent de READ, quelle qu'en soit la cause (PENDING WRITE, complétion non projetée, inconnu) | `404 Not Found` | Pas d'oracle d'existence |
| Résultat READ présent mais appartenant à une autre EUI | **même `404`** | Même enveloppe publique ; aucune fuite d'existence |
| Service READ réellement indisponible | `503 Service Unavailable` | Incident technique, non substitut à l'absence d'une ligne |

Après le `202 Accepted` d'admission, le client conserve le `requestId` et poursuit le polling malgré les `404` tant qu'il attend le résultat, selon une politique cliente bornée de délai/backoff. `404` ne certifie ni inexistence ni échec métier et n'est pas assimilé à `503`. `200` avec `REJECTED` est un résultat terminal valide, non un rejet HTTP d'admission. Un `requestId` d'autrui n'ouvre jamais accès, même si connu. La propriété demeure celle de l'EUI initiatrice après Unlink/re-Link ; l'EUI reste tenue de s'authentifier. Les résultats ne comportent pas d'expiration fonctionnelle V3 : aucune nouvelle purge ni statut `EXPIRED`. La rétention opérationnelle éventuelle nécessite une décision distincte, cohérente avec idempotence et reconstruction. Voir V3-2/V3-3 et V3-5D §60.
