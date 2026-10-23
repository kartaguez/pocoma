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

**Arbitrage d'étude :** la consultation affichée par le client reste à la version globale `V` ouverte jusqu'à une action explicite de rafraîchissement. Les pages d'Expenses demandent explicitement ou implicitement **le même V**. L'évolution de `CurrentPot` vers V+1 ne change jamais la composition des pages à V.

**Comparaison :**
- **Manifeste complet immuable par Pot@V** : déterministe mais duplique à chaque mutation de longues listes de références pour des Pots de 2 000 Expenses ; complexité de stockage et amplification d'écritures.
- **Cache de composition au moment de l'ouverture** : optimisation facultative, mais non autorité de cohérence (éviction, déploiement distribué, redémarrage).
- **Reconstruction directe depuis les sous-versions READ** : **préférence actuelle**, car le domaine définit déjà l'appartenance au Pot, les Headers d'Expenses, les versions locales et la validité à une version globale `activeFrom/activeTo`. Retrouver le Header applicable à V par Expense, exclure `deleted`, paginer suivant un ordre stable, puis résoudre Details/Financials applicables à V. Aucun manifeste supplémentaire tant que l'indexation rend ce parcours acceptable.

**Nuance technique fondamentale :** V3-3 qualifie les `SubobjectVersion` de **strictement immuables**. En conséquence, ne pas présumer qu'un `activeTo` historique est modifié sur READ lors de l'arrivée d'une version ultérieure, notamment en présence de matérialisation hors ordre. Étudier une résolution fondée sur les `activeFrom` immuables des versions successives, ou une représentation équivalente permettant de prouver sans mise à jour d'une ancienne version l'unicité de l'état applicable à V. Les intervalles WRITE ne doivent pas être transposés naïvement aux rows READ.

**Accès sécurisé :** à chaque page, authentifier l'EUI, vérifier `CurrentBinding`/bindingToken, puis l'autorisation réelle via Shareholder lié à l'utilisateur **à V** (contrat SEC-01), jamais via le seul curseur ou `CurrentUserPotList`. L'accès historique à V peut survivre à la disparition d'un Shareholder à V+1 conformément au contrat READ actuel, tant que le binding est actif ; la propagation de révocation READ reste asynchrone.

**Critère de décision définitive :** éprouver la correction sur création/suppression/évolution d'Expense et hors ordre des matérialisations, puis le coût d'une page à V ancien pour 2 000 Expenses et historique long : SQL borné/indexé, pas de N+1 ni scan incontrôlé. Si cela échoue, reconsidérer une matérialisation minimale du manifeste historique plutôt qu'introduire une nouvelle Consumption.

**Statut : consultation fixée à V VALIDÉE ; mécanisme de reconstruction historique PRÉFÉRÉ mais pas encore définitivement validé.** Aucune nouvelle projection ou cache obligatoire décidé.
