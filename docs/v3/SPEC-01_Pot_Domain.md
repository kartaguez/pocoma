# Pocoma SPEC-01 — Domaine Pot

**Statut : CONCEPTION VALIDÉE (conceptuelle), non implémentée.**  
**Consolidation normative :** 2026-10-24. **Autorité de progression :** [V3_Canon_and_Roadmap.md](V3_Canon_and_Roadmap.md).  
**Principe :** simplicité des structures, concepts et algorithmes, sans sacrifier la correction.

## 1. Identités et découpage

Chaque Pot possède un `PotId` métier stable et une version globale V, incrémentée de 1 pour chaque mutation métier effective et atomiquement validée. Les objets enfants ont chacun une identité métier stable (`ExpenseId`, `ShareholderId`) et sont rattachés à un Pot. Une instance **historique** persistée d'un sous-objet possède son identifiant technique propre, qui ne sert jamais de référence métier.

| Objet | Sous-objet | Données métier | Version locale |
|---|---|---|---|
| Pot | `PotHeader` | `PotId`, `deleted` | h |
| Pot | `PotDetails` | `label`, `creatorId` obligatoire et immuable | d |
| Shareholder | `ShareholderHeader` | `ShareholderId`, `deleted` | h |
| Shareholder | `ShareholderDetails` | `name`, `userId` facultatif et historisé | d |
| Shareholder | `ShareholderWeight` | `defaultWeight` strictement positif | w |
| Expense | `ExpenseHeader` | `ExpenseId`, `deleted` | h |
| Expense | `ExpenseDetails` | `label`, `date` | d |
| Expense | `ExpenseFinancials` | `amount`, `payerId`, participants et leur `specificWeight` facultatif | f |

Les composantes d'un même enfant référencent directement son identifiant métier. Une relation Expense→Shareholder utilise `ShareholderId`, jamais une instance de version, et se résout à la version globale du Pot demandée. `PotDetails.creatorId` peut différer de tout Shareholder ; plusieurs Shareholders d'un même Pot peuvent être associés au même `userId`. Aucun objet Membership ni unicité Shareholder→User ne sont ajoutés.

## 2. Versions et intervalles

Toute composante possède sa propre version locale, incrémentée de 1 **uniquement quand cette composante change**. Une même mutation peut changer plusieurs composantes, mais produit une seule nouvelle version globale V du Pot. Les composantes inchangées ne sont ni recopiées ni reversionnées.

Chaque instance historisée possède `activeFrom` et `activeTo`, exprimés en **versions globales du Pot**, avec intervalle semi-ouvert `[activeFrom, activeTo)` (`activeTo = null` signifie aucune fin connue). À la mutation effective V→V+1, l'ancienne instance des composantes modifiées ferme son intervalle à V+1 ; la nouvelle instance l'ouvre à V+1, avec version locale +1. Une version locale est indépendante de la version globale et des autres composantes.

Création : tous les sous-objets d'un nouvel objet naissent atomiquement en version locale 1 à la même V globale. Un objet absent avant sa création n'a aucune composante applicable. Le `PotDetails.creatorId` est conservé à l'identique lorsque le libellé change.

## 3. Cycle de vie et suppressions

`deleted` appartient **exclusivement au Header**. Les Details, Financials et Weight ne portent pas ce champ.

- Suppression Expense : nouvelle version d'`ExpenseHeader` avec `deleted=true` ; `ExpenseDetails` et `ExpenseFinancials` inchangés. Toutes les trois composantes doivent être inchangées depuis la version observée. Une évolution de `ShareholderWeight` seule ne rend pas la suppression de l'Expense obsolète.
- Suppression Pot : nouvelle version de `PotHeader` avec `deleted=true`, **sans modifier les enfants** ; opération terminale. Elle exige `observedVersion == currentPotVersion` : toute mutation effective intermédiaire du Pot, de ses Shareholders ou de ses Expenses rend la demande obsolète.
- Shareholder : le `ShareholderHeader` possède le champ `deleted` pour homogénéité du modèle, et sa suppression fonctionnelle est réservée au créateur, uniquement si aucune Expense active ne le référence.
- Les composantes non-Header d'un objet supprimé peuvent conserver `activeTo=null` : le Header détermine si l'objet est fonctionnellement actif. Aucune mutation métier n'est autorisée après suppression de l'objet ou de son Pot. L'historique n'est jamais effacé.

**Décision normative :** `DeletePot` est une suppression logique terminale, non un effacement de l'historique et non une révocation rétroactive des droits historiques. À la version globale D de suppression, seul le Header du Pot devient `deleted=true` ; les composantes enfants ne sont pas modifiées. Toute mutation ultérieure est refusée. Le Pot disparaît de la navigation courante et n'est plus consultable comme Pot courant.

`Pot@D` est reconstruit et matérialisé comme état terminal, mais n'est **pas consultable fonctionnellement** via l'API de Pot complet, même en lecture historique explicite : réponse `404`. Toutes les versions antérieures V<D restent consultables, sous réserve des droits du créateur ou des rattachements Shareholder **à V**, de l'EUI authentifiée et du `CurrentBinding` actif à la consultation (SEC-01). Les droits sur D et la visibilité actuelle ne sont pas une condition supplémentaire de l'accès historique à V. Aucune version ou composante ancienne n'est purgée par `DeletePot`.

L'état `deleted` de D est nécessaire à la matérialisation et au contrôle READ ; son existence interne ne confère aucune permission de consulter D. Les effets Balance et Visibility sont précisés par SPEC-04 — §10, les oracles par SPEC-09 — §5 et SPEC-10 — A11-A à A11-N. Cet amendement prévaut sur toute règle antérieure supposant une révocation historique par suppression.


Le contrôle d'existence active d'un Header parent est distinct du contrôle de non-obsolescence d'une composante cible.

## 4. Finances et résolution à V

`ExpenseFinancials` porte le montant, le payeur (`ShareholderId`) et la liste non vide des participants (`ShareholderId → specificWeight?`). Le payeur n'a pas besoin d'être participant. Payeur et participants appartiennent au même Pot. `specificWeight`, lorsqu'il est fourni, et `defaultWeight` sont strictement positifs. Le montant est non négatif et les calculs conservent les fractions rationnelles exactes normalisées.

À une version globale V : `effectiveWeight(participant,V) = specificWeight` s'il est présent, sinon `ShareholderWeight.defaultWeight` **applicable à V**. La part = `amount × effectiveWeight / somme(effectiveWeight)`; le payeur reçoit le crédit du montant, les participants le débit de leurs parts. Les soldes se compensent exactement.

Changer un poids par défaut **modifie rétroactivement la répartition des Expenses encore actives utilisant ce défaut, au niveau des soldes à la nouvelle version globale** ; il ne modifie ni la liste de participants ni `ExpenseFinancials`, et ne réécrit pas les balances historiques calculables aux anciennes V. Une Expense supprimée ne contribue plus aux soldes à partir de sa suppression.

## 5. Droits métier

**Décision fonctionnelle validée :** `PotDetails.creatorId` désigne le créateur immuable et lui confère des droits de gestion indépendamment de tout rattachement à un Shareholder. Un `PocomaUser` actuellement rattaché à un Shareholder actif a les mêmes droits de consultation et de mutation **sauf les suppressions du Pot et d'un Shareholder**, réservées au créateur.

| Action | Créateur | Utilisateur rattaché à un Shareholder actif | Autre |
|---|---|---|---|
| Lire le Pot | Oui | Oui | Non |
| Modifier les données du Pot | Oui | Oui | Non |
| Ajouter un Shareholder | Oui (libre ou auto-rattaché) | Oui (libre ou auto-rattaché) | Non |
| Modifier un Shareholder (nom, poids) | Oui | Oui | Non |
| Rattacher / détacher `ShareholderDetails.userId` | Auto-rattachement sur Shareholder libre ; détachement de tout Shareholder | Auto-rattachement sur Shareholder libre ; auto-détachement de son propre Shareholder uniquement | Auto-rattachement sur Shareholder libre via invitation valide (§8) |
| Supprimer un Shareholder | **Oui, si aucune Expense ne le référence** | Non | Non |
| Ajouter/modifier/supprimer une Expense | Oui | Oui | Non |
| Supprimer le Pot | **Oui** | Non | Non |

**Règles de rattachement et de détachement (`ShareholderDetails.userId`) :** aucun acteur (pas même le créateur) ne peut renseigner ou imposer le `PocomaUserId` d'un tiers sur un Shareholder. Lors de `CreateShareholder` ou `ModifyShareholderDetails`, un rattachement (`userId: null -> U`) ne peut associer qu'un Shareholder libre (`userId == null`) au propre `PocomaUserId` de l'auteur de la commande (résolu en WRITE depuis son `Binding` actif). Un détachement (`userId: U_target -> null`) ne peut être exécuté que par **le créateur du Pot (`creatorId`)** ou par **l'utilisateur `U_target` lui-même** (auto-détachement) ; un participant ne peut jamais détacher un autre participant. Le détachement dissocie uniquement l'utilisateur sans supprimer l'entité Shareholder ni altérer les Expenses qui la référencent.

La condition « aucune Expense liée » est un invariant métier vérifié **au moment de l'exécution WRITE** de `DeleteShareholder`, sur l'état courant : refuser la suppression si le Shareholder apparaît comme payeur ou participant dans une Expense **active**. Les Expenses supprimées ne constituent pas une référence active bloquante ; leurs versions historiques restent conservées. La suppression du Pot est distincte de celle d'un Shareholder.

**Frontière temporelle :** à l'exécution de toute Command autre que `CreatePot`, vérifier dans WRITE le `bindingToken` et l'identité authentifiée, puis les droits correspondant à la version métier courante avant mutation. Le rôle de créateur ne dépend pas de sa présence comme Shareholder. `observedVersion` et les règles de concurrence restent des contrôles complémentaires, non un droit d'accès.

**Effet READ à harmoniser :** à V, `PotVersion(P,V).authorizedUserIds` doit inclure le `creatorId` et les utilisateurs liés à des Shareholders de V ; le seul fait de figurer dans une liste de navigation n'est pas en soi une permission. La visibilité du créateur sans Shareholder est ici intentionnellement une conséquence de son rôle de gestion. Pour les versions historiques, les droits de Shareholder sont évalués à V sous réserve de `CurrentBinding` actif, et ceux du créateur découlent de son rôle immuable.



## 6. Préconditions métier et concurrence

`observedVersion` indique la version globale que l'auteur de la commande avait observée ; la version globale **de travail** est la version courante utilisée pour valider et écrire. Le CAS de version globale protège l'ordonnancement transactionnel ; sa collision ne constitue pas automatiquement une obsolescence métier. En cas de collision, reprendre à partir d'un état courant rechargé, conserver l'observation initiale et réévaluer les dépendances (protocole d'exécution détaillé en V3-4).

| Commande | Sous-versions dont les changements depuis l'observation bloquent | Composantes modifiées |
|---|---|---|
| Modifier PotDetails | PotDetails | PotDetails |
| Ajouter Shareholder | Pas de cible antérieure ; contrôler état du Pot et références utiles | Nouveau Header+Details+Weight v1 |
| Modifier ShareholderDetails | ShareholderDetails | ShareholderDetails |
| Modifier ShareholderWeight | ShareholderWeight | ShareholderWeight |
| Créer Expense | Pas de cible antérieure ; contrôler état du Pot et validité des Shareholders | Nouveau Header+Details+Financials v1 |
| Modifier ExpenseDetails | ExpenseDetails | ExpenseDetails |
| Modifier ExpenseFinancials | ExpenseFinancials | ExpenseFinancials |
| Supprimer Expense | ExpenseHeader, ExpenseDetails, ExpenseFinancials | ExpenseHeader |
| Supprimer Pot | Pot global complet (égalité de V observée et courante) | PotHeader |
| Supprimer Shareholder | Créateur exclusivement, aucune Expense active le référençant | ShareholderHeader |
| Auto-rattacher Shareholder sur invitation (§8) | Pas de `observedVersion` requis ; vérifier Pot actif, invitation valide non expirée, Shareholder actif et libre (`userId == null`) à la version courante | ShareholderDetails |

**Décision validée :** une Expense référence des `shareholderId` métier stables, non les `PocomaUserId` qui leur sont rattachés. Le changement de `ShareholderDetails.userId` (ou du nom affiché) **ne rend pas à lui seul OBSOLETE** une Command d'Expense qui référence ce Shareholder. Les données financières continuent de porter sur la même entité Shareholder.

**Contrôles de référence indépendants de l'obsolescence locale de l'Expense :** lors de l'exécution WRITE, toute création/modification de Financials référençant un Shareholder exige que son Header existe déjà à `observedVersion` (`activeFrom <= observedVersion`) et que le Shareholder soit encore actif à l'exécution. Le seul changement de `ShareholderDetails` entre ces versions n'est pas une raison de rejet. Les droits de l'auteur sont validés séparément à la version courante avec l'EUI authentifiée et le bindingToken explicite.

**Suppression :** `DeleteShareholder` reste réservée au créateur et refusée s'il apparaît dans toute Expense active. Ce refus d'invariant n'exige pas de requalifier comme obsolètes les commandes portant sur son rattachement.



En plus : vérifier les Headers actifs, l'autorisation et les invariants de références sur l'état courant. Pour une composante existant déjà à la V observée, une instance courante dont `activeFrom <= observedVersion` n'a pas changé depuis l'observation ; cela n'élimine pas la validation d'existence et d'appartenance.

Une commande multi-cibles est entièrement atomique : une cible obsolète entraîne rejet complet ; si elle est valide, seules les composantes réellement modifiées prennent une version locale supplémentaire, sous **une seule** nouvelle V globale. Une commande valide, non obsolète, sans changement de données réussit **sans** nouvelle V globale ni sous-version ; sa complétion externe retourne `resultingPotVersion = V_courant` (la version globale courante inchangée du Pot, cf. V3-2/V3-3). Une commande obsolète reste rejetée même si sa valeur demandée égale la valeur présente.

Une modification des Financials et une modification du Weight peuvent toutes deux réussir si leurs dépendances métier respectives restent valides ; le calcul résultant utilise l'état composé à la nouvelle V. Une suppression d'Expense qui suit un changement de ses Details ou Financials observés est, au contraire, obsolète.

## 7. Reconstruction historique exacte

Pour `Pot@V`, lire la version exacte des Header, Details et composantes spécialisées applicables, en utilisant `activeFrom <= V && (activeTo == null || V < activeTo)`. Toutes les références inter-objets se résolvent à **cette même V globale**, y compris le payeur, les participants, les poids par défaut et `ShareholderDetails.userId` pour les faits d'autorisation. Ne pas substituer les données courantes à celles de V.

Chaque composante d'un objet déjà créé doit avoir **exactement une** instance applicable à V. Avant la création, l'absence de toutes ses composantes est normale ; Header présent mais Details/Financials/Weight manquants, multiples intervalles simultanés ou références incohérentes sont des anomalies techniques à signaler, non à réparer silencieusement. Un objet supprimé est toujours reconstructible, mais son Header interdit son activité fonctionnelle. L'ordre des collections doit être canonisé lorsque requis pour un digest/projection.

Exemple condensé :
- V1 création du Pot (Pot h1/d1) ;
- V2 création groupée de deux Shareholders (chacun h1/d1/w1) ;
- V3 création Expense (h1/d1/f1) ;
- V4 modification libellé Expense (d2 uniquement) ;
- V5 modification montant (f2 uniquement) ;
- V6 changement poids par défaut (Shareholder w2 uniquement) : balance nouvelle, Financials f2 ;
- V7 modification multi-cibles des noms (deux ShareholderDetails d2) ;
- V8 suppression Expense (h2 deleted, Details d2 et Financials f2 conservés) ;
- V9 suppression Pot (PotHeader h2 deleted, aucun enfant modifié).

Chaque V reconstruit exactement son état ; V6 n'altère pas les soldes de V5, V8 exclut l'Expense des soldes mais préserve ses composantes historiques, et V9 préserve l'intégralité de l'historique.


## 8. Invitations et auto-rattachement

**Décision de principe exprimée :** le créateur peut générer un lien d'invitation temporaire pour son Pot. Toute personne authentifiée munie du lien peut choisir **n'importe quel Shareholder actif et libre** de ce Pot et réclamer son rattachement, **à condition de ne pas être déjà liée à un Shareholder actif de ce Pot**. Le lien transporte un secret opaque qui référence une invitation persistée avec `potId` et `expiresAt`. Ce n'est ni une authentification ni un droit permanent sur le Pot.

**Modèle minimal candidat :** enregistrement WRITE `PotInvitation(tokenHash, potId, expiresAt)`, secret aléatoire fort côté client, seule empreinte conservée. Plusieurs utilisations possibles pendant la validité, puisque l'invitation concerne le Pot, pas une personne ni un Shareholder. La possibilité pour le créateur de révoquer l'invitation avant expiration est **recommandée, à confirmer** ; aucun compteur d'usages nécessaire par défaut.

**Règle d'exécution :** authentifier EUI et résoudre son PocomaUserId/Binding actif dans WRITE, puis, dans une transaction atomique, vérifier l'invitation non expirée, Pot actif, Shareholder cible actif/libre, absence de tout autre Shareholder actif déjà rattaché à ce même PocomaUserId dans le Pot, et procéder à la mutation versionnée de ShareholderDetails. Garantir l'exclusion des deux courses : plusieurs personnes visant le même Shareholder et une même personne visant simultanément plusieurs Shareholders. Une invitation ne délègue pas au porteur le droit d'attribuer un autre utilisateur : **auto-rattachement uniquement**. Une admission structurale n'anticipe pas ces décisions WRITE du Worker.

**Décision validée :** lorsqu'un invité ouvre le lien de son Pot, il s'authentifie ; s'il ne possède pas encore de `PocomaUser` / Binding actif, il effectue **Registration (ou Link vers son `PocomaUser` existant) d'abord**, via le protocole Identity existant, attend que son Binding soit utilisable, puis sélectionne un Shareholder libre et demande son auto-rattachement avec le token d'invitation. Il n'existe pas de transaction trans-domaines « Registration + mutation du Pot » ni de nouveau type de lien identitaire. L'invitation n'accorde des droits qu'après rattachement réussi.

**Revalidation à l'exécution WRITE du rattachement :** invitation toujours valable, Pot actif, Shareholder toujours libre (`userId == null`), User pas déjà lié à un autre Shareholder actif de ce Pot (sans exiger `observedVersion`, l'invité n'ayant pas accès au Pot complet avant rattachement). Le passage préalable par Registration ou Link ne réserve ni Shareholder ni validité du lien ; si l'invitation expire entre l'ouverture de la page et l'exécution, la mutation est rejetée sans effet. Un retry de la même demande déjà admise conserve sa sémantique idempotente, mais ne garantit pas la réussite métier.

**Choix des Shareholders libres avant rattachement (respect strict de l'isolation READ / WRITE) :** un utilisateur authentifié disposant d'une invitation encore valide peut obtenir depuis READ une vue minimale de choix comprenant **identifiants et noms des Shareholders libres uniquement**, sans accès au Pot complet, Expenses ni balances, et **sans aucune lecture synchrone de WRITE**. À cette fin, `PotVersion(P,V)` en READ expose sur chaque référence de Shareholder actif le seul indicateur booléen public `isFree` (`userId == null`, sans jamais exposer `userId`), et la validité du jeton d'invitation est vérifiée par READ (jeton d'invitation signé `(potId, expiresAt)` ou empreinte active projetée dans `PotVersion`). La possession du lien seule, sans authentification, est insuffisante. La liberté et les droits sont revérifiés en WRITE à l'exécution de la Command d'auto-rattachement ; la liste affichée ne réserve aucune place.

La politique de révocation anticipée du lien reste à préciser. La vue limitée d'invitation ne transforme pas le porteur du lien en utilisateur autorisé à consulter le Pot complet.


**Ouvert :** révocation anticipée de l'invitation. Le schéma `PotInvitation(tokenHash, potId, expiresAt)` est candidat, non une obligation physique ; l'invitation ne donne pas accès au Pot complet.

## 9. Statut et références

Conception normative uniquement, sans code ni certification V3. Contrats complémentaires : [V3-2](SPEC-03_Events_Requests_Consumables.md), [V3-3](SPEC-04_Read_Projections.md), [V3-4](SPEC-05_Consumption_Discovery.md), [V3-5E](SPEC-06_Supra_Contracts.md) ; preuves dans [V3-5D](SPEC-10_Test_Certification.md).
