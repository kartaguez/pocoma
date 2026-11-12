# Pocoma SPEC-02 — Domaine Identity

> **Décision L7.2.** Les obligations exclusivement liées au use case Link sont retirées par `GO_L7_I2_I3_FULL_EXECUTION`. Les références qui suivent conservent la traçabilité historique. Les contrats Registration, Unlink et la relecture de l’historique s’appliquent. [Décision et données](L7_I2_Link_Retirement.md).

> **Lecture des statuts (clôture L1, 2026-10-25) :** les mentions de non-implémentation/non-exécution ci-dessous décrivent l’état du cadrage conceptuel lors de sa rédaction, pas un relevé actuel des tests. Les contrats et oracles restent normatifs ; contributions internes L1 et limites dans le [dossier de clôture](L1_Documentary_Closure.md), programme et statuts dans le [plan directeur](V3_Implementation_Master_Plan.md). Aucun PASS global de cette SPEC n’est acquis par cette note.

**Statut : conception conceptuelle validée dans les échanges ; non implémentée, non testée en V3.**  
**Consolidation normative :** 2026-10-24. **Document directeur :** [V3_Canon_and_Roadmap.md](V3_Canon_and_Roadmap.md).

## 1. Frontières d'autorité

Pocoma ne possède **aucun domaine Entitlements** ni gestion d'attribution/révocation de ces droits. Le fournisseur externe atteste l'identité et les entitlements ; Pocoma vérifie ces attestations et applique ses règles d'autorisation, y compris les relations métier de Pot. Identity possède seulement l'existence des PocomaUsers et la relation des identités externes à ces Users. Un droit externe ne devient pas une entité Pocoma.

## 2. Objets et identités

- **PocomaUser** : `PocomaUserId` stable, existence autonome ; aucune propriété mutable ou version évolutive identifiée. Il peut exister sans identité externe liée ; le détachement de la dernière ne le supprime pas.
- **ExternalUserIdentity (EUI)** : `externalUserIdentityId` stable propre à Pocoma, `issuer` et `subject` externes opaques et immuables, `pocomaUserId?` facultatif, `version` historique entière et `bindingToken?` facultatif. Le couple `(issuer, subject)` est unique dans toute l'autorité Identity, y compris après Unlink.
- Cardinalités : une EUI est liée à **zéro ou un** PocomaUser ; un User est lié à **zéro à N** EUI.
- **Binding** nomme cette relation (EUI→User lorsqu'elle existe) ; **ce n'est pas un objet métier autonome**. Pas de collection de bindings portée par le User, ni d'entité Binding/BindingId séparée.
- `bindingToken` : témoin opaque, aléatoire, non prédictible, d'une occurrence précise du rattachement. Il est neuf à chaque Link, y compris vers le même User après Unlink ; il devient `null` lors d'Unlink, ne peut pas être réutilisé et ne suffit jamais, à lui seul, à autoriser une opération. Son accès READ doit être protégé par l'authentification de l'identité concernée et les contrôles nécessaires. Le terme définitif de représentation technique est à choisir lors des contrats V3-2/3, sans recréer un objet Binding.

## 3. États, versions et histoire

La version EUI commence à **1**, avance de **1** à chaque transition effective Link/Unlink et identifie un état historique immuable. `issuer`, `subject` et l'identifiant interne restent immuables ; la seule propriété métier mutable identifiée est le rattachement (`pocomaUserId?` et `bindingToken?`).

Une EUI peut être créée **liée ou non liée**, bien qu'aucune commande autonome publique de création ne soit actuellement requise. Si elle est créée liée, son premier état est directement la version 1 : pas de version intermédiaire artificielle. Si une EUI libre existe, Link crée sa version suivante.

Invariants de chaque état :
- `pocomaUserId == null` si et seulement si `bindingToken == null`.
- Un Link validé rend `pocomaUserId=U` et `bindingToken` neuf ; Unlink rend les deux nuls.
- Les anciennes versions et leurs valeurs restent reconstructibles ; les témoins historiques ne sont pas nécessairement exposés publiquement.
- Une mutation rejetée ne crée aucune version ; le retry d'une demande déjà terminalisée ne reproduit pas la mutation.
- Pas de version globale Identity ni de sous-objets EUI. L'histoire `EUI@version` se reconstruit directement ; une reconstruction synchronisée de **toutes** les EUI à une « version globale Identity » n'est pas définie et n'est pas un besoin établi.

## 4. Transitions applicatives

**Trois opérations**, sans commandes publiques autonomes `CreatePocomaUser` ni `CreateExternalUserIdentity` à ce stade :

| Opération | Origine / préconditions | Effet atomique |
|---|---|---|
| **Registration** | EUI authentifiée non liée, qu'elle soit inconnue ou déjà présente libre | Créer un **nouveau PocomaUser**, créer EUI directement liée v1 ou produire sa prochaine version liée ; nouveau token |
| **Link** | EUI initiatrice E1 authentifiée déjà liée à U et encore valide ; preuve fiable de contrôle/consentement de l'EUI cible E2 ; E2 libre ou inconnue | Déduire U de E1 (pas du choix arbitraire d'un UserId), créer E2 directement liée v1 ou sa prochaine version liée ; nouveau token |
| **Unlink** | E1 initiatrice authentifiée liée à U (présentant son propre `bindingToken` actif), E2 actuellement liée **au même U** (sans exiger le `bindingToken` secret de E2 lorsque `E1 != E2`) | Produire la nouvelle version de E2 non liée et token nul |

- `Link` et `Unlink` sont des **transitions strictes**, pas des affectations idempotentes d'état : une identité déjà liée, même au même User, entraîne rejet d'un nouveau Link ; une EUI cible déjà libre, liée à un autre User, ou un initiateur E1 dont le `bindingToken` est obsolète entraîne rejet d'un nouvel Unlink.
- Une réexécution technique de **la même demande durable** retrouve son résultat terminal original sans nouvel effet. La complétion de la demande et les faits de mutation sont des natures distinctes (contrats en V3-2).
- Aucun transfert direct d'EUI de U1 vers U2 n'est retenu : Unlink puis Link.
- E1 peut Unlink E2 du même User, ou elle-même ; elle peut aussi Unlink la **dernière** identité du User, qui persiste alors sans EUI liée (abandon définitif et irrévocable de ce `PocomaUser`, aucune opération ne rattachant une EUI à un `PocomaUser` dépourvu d'EUI active).
- La preuve d'autorité sur E2 dans Link **ne se réduit jamais** à la connaissance de `(issuer,subject)`, d'un UserId ou d'un token divulgué. Le consentement est constaté par le POST explicite d'E2 authentifiée sur la proposition immuable du §5.

## 5. Admission, consentement et autorité à l'exécution

**Décision humaine L5/I3 du 5 novembre 2026 (H1–H5 ratifiées, GO d'implémentation) :** le protocole minimal remplace l'exigence antérieure d'un serveur d'autorisation externe. E1 authentifiée crée une proposition technique immuable et expirante, distincte d'IdentityRequest, avec son `bindingToken` déclaré et la cible exacte `(issuer, subject)` E2. E2 s'authentifie auprès de son propre IdP ; le futur client affiche le récapitulatif et E2 accepte par un **POST authentifié explicite**. Ce POST sur la proposition constitue la preuve de consentement constatée par Pocoma. L'IdP authentifie, sans octroi JWT Link, extension Keycloak, scope dynamique, RAR ou challenge supplémentaire. Aucun frontend n'est livré par I3. Les décisions et clôtures historiques restent conservées dans leurs documents et dans Git.

**Contrat maintenu :** intention E1/E2 non substituable, compte résolu de E1 seulement à l'exécution, JWT validés (signature, issuer, audience, claims et dates), expiration serveur, anti-rejeu et unique admission physique atomique avec association S-03. Login ou GET seul ne vaut pas consentement. E1 reste `requesterEui` et propriétaire du résultat malgré le transport de l'acceptation par E2 ; E2 obtient seulement son propre binding. Aucun faux principal E1 n'est construit. Keycloak est le premier IdP ; le modèle conserve les couples exacts, sans prétendre une configuration multi-issuer opérationnelle.

**A-READ :** la création peut utiliser un contrôle READ indicatif, avec obsolescence acceptée ; I3 n'en exige aucun. Pas de refus métier définitif sur projection en retard, aucun recours à WRITE, verrou métier ou nouveau port d'autorité WRITE. Une intention admise sur données périmées peut être normalement rejetée par L3. Seul Identity WRITE revalide E1, son occurrence/token et la disponibilité de E2.

**Récapitulatif :** identité E1, cible E2, action `LINK_TO_INITIATOR_ACCOUNT`, dates et état technique. `potCount=null` et `potCountAsOf=null` sont autorisés lorsque le compteur est indisponible ; aucune projection nouvelle ni énumération des Pots. Le compteur éventuel demeure indicatif, sans rôle d'autorisation.

**Cycle technique :** TTL initial 10 minutes configurable, non renouvelé au retry. La première acceptation/admission expire ; la demande admise et ses résultats n'expirent pas à cette échéance. S-03 à la création ; idempotence naturelle de l'acceptation par `linkRequestId`, même après expiration/terminalisation. Une proposition produit au plus une IdentityRequest. Aucune purge I3 : croissance acceptée ; la rétention future exige une décision préservant reprise, intégrité et anti-rejeu. Les détails HTTP et preuves effectives sont dans le [rapport I3](L5_I3_Execution_Report.md).

**Entitlements :** attestations et droits externes contrôlés à l'admission seulement ; une révocation ultérieure du fournisseur ne retire pas rétroactivement l'admission. Les préconditions et tokens de rattachement Pocoma sont contrôlés à l'exécution.

**BindingToken :** `(issuer,subject)` authentifié de la requête doit correspondre à l'EUI qui porte le bindingToken. READ contrôle CurrentBinding, WRITE contrôle l'autorité Identity pour les mutations. Jamais un droit fondé sur la connaissance du token seule, ni sur un pocomaUserId déclaré.

Ces décisions ferment les arbitrages conceptuels reportés ci-dessus, sans prétendre spécifier ici les formats des preuves, les tests ni l'implémentation.


L'admission est **déclarative**, sans lecture du modèle WRITE Identity : elle capture l'identité externe authentifiée, les attestations externes et les témoins de binding déclarés, puis enregistre l'intention durable. Elle ne certifie ni la disponibilité de la cible ni la fraîcheur du rattachement.

À l'exécution seulement, Pocoma consulte l'autorité Identity : l'identité externe du demandeur doit correspondre à celle authentifiée à l'admission, le `bindingToken` déclaré de E1 doit être courant, U est résolu de E1 ; les préconditions de E2 (libre pour Link, activement liée au même U pour Unlink) sont contrôlées ainsi que les droits externes et règles applicables. Le token opaque identifie l'occurrence revendiquée de E1, **sans se substituer à l'authentification**. Le mode de consultation propre de CurrentBinding est défini au §6 et en V3-3 ; aucune lecture WRITE n'est ajoutée à l'admission.

## 6. Binding explicite et récupération de son propre CurrentBinding

**Décision :** toute `Command` et toute `IdentityRequest` **sauf `Registration`** porte obligatoirement un `bindingToken` désignant explicitement le Binding au nom duquel l'EUI authentifiée agit. L'identité externe seule n'autorise pas le domaine à sélectionner ou deviner le `PocomaUserId` opérant.

À l'exécution WRITE, vérifier la correspondance `(EUI authentifiée, bindingToken, Binding actif, PocomaUserId)` avant toute décision métier. Une EUI authentifiée présentant le token d'une autre EUI est refusée. La vérification intrinsèque/présence du token à l'admission ne remplace pas cette validation de l'état WRITE par le Worker. Les retries et la comparaison des intentions idempotentes sont liés à ce rattachement explicite.

**Cas Link :** `bindingToken` de E1 (initiateur) obligatoire ; E2 doit être authentifiée et consentir explicitement au Link particulier, mais n'a pas besoin de Binding initial. **Unlink :** `bindingToken` actif de l'EUI initiatrice E1 obligatoire (qui est aussi celui de E2 lorsque `E1 == E2` ; lorsque `E1 != E2`, E1 n'a jamais à fournir ni connaître le `bindingToken` de E2). **Invitation/auto-rattachement :** demande avec Binding actif du bénéficiaire lui-même. **Registration :** exception, car elle crée un nouveau Binding. Le mécanisme de récupération du token après Registration/Link doit être documenté sans permettre d'accéder au token d'une autre EUI.

Aucune inférence implicite de `PocomaUserId` par le domaine à partir du seul `issuer/subject`. Toute formulation antérieure laissant entendre une résolution implicite est supplantée.



**Décision :** la consultation de `CurrentBinding` est une **lecture de découverte de son propre rattachement**, distincte de toute Command et IdentityRequest. Elle exige une EUI authentifiée, mais **n'exige pas de `bindingToken` entrant**, puisqu'elle sert à récupérer le token de ce Binding. La cible est exclusivement l'EUI issue de l'authentification `(issuer, subject)` ; le client ne peut choisir aucune autre EUI par paramètre.

Le READ Supra renvoie à cet appelant sa version EUI projetée, l'état actif/inactif et **son propre `bindingToken` si actif** ; aucun token d'une autre EUI, aucun `PocomaUserId` exposé. Après `Registration(E1)`, E1 consulte son Binding. Après `Link(E1,E2)`, E1 consulte le résultat de sa demande, tandis qu'**une session authentifiée en E2** consulte ensuite `CurrentBinding(E2)` pour récupérer le nouveau token de E2. Une session E1 ne peut pas utiliser la réussite du Link pour obtenir le token E2.

**Source de matérialisation :** `IDENTITY_EVENT(EUI,V)` ne transporte pas le token ; la Consumption `MaterializeCurrentBinding` lit le Binding autoritatif **à cette version EUI** depuis WRITE et écrit conditionnellement `CurrentBinding(EUI)` en READ (sans recul de version). En particulier, ne pas lire simplement le Binding WRITE *courant* lors d'un événement ancien si cela peut attribuer sa valeur à la mauvaise version.

**Fraîcheur :** une lecture `minVersion` attend la présence de la projection cible ou retourne une indisponibilité temporaire explicite, sans inventer d'identité ni exposer un token obsolète comme s'il était nouveau. Les réponses contenant un token sont sensibles : pas de cache partagé, pas de journalisation du token et contrôle d'authentification à chaque appel.

**Portée :** cette exception de lecture ne modifie pas la règle selon laquelle toute Command/IdentityRequest sauf Registration contient un bindingToken explicite et est revalidée dans WRITE.



## 7. Concurrence et atomicité

- Deux Registrations concurrentes sur la même EUI : **une seule** crée User+link ; l'autre ne crée aucun User. Fonctionne aussi pour EUI auparavant inconnue via unicité `(issuer,subject)`.
- Registration et Link concurrents visant la même EUI : **aucune priorité fonctionnelle** ; la première transition autoritativement validée gagne, l'autre est rejetée sans effet partiel.
- Deux Links sur la même EUI : un seul succès.
- Link/Unlink : vérifier **atomiquement** l'autorité initiatrice (E1 et son token courant) et les préconditions de E2 jusqu'au commit ; ne pas laisser Unlink de E1 passer entre contrôle et mutation de E2.
- Deux Unlinks croisés, E1 demandant l'Unlink d'E2 et inversement : **un seul** peut réussir ; l'autre perd son autorité initiatrice. Des Unlinks dans une même direction peuvent réussir successivement seulement si leurs initiateurs et leurs cibles restent valides sous leurs témoins déclarés.
- Une opération Unlink portant E1==E2 contrôle son propre rattachement puis le supprime atomiquement.
- Les transitions sur EUI indépendantes n'ont pas besoin de version globale, mais les opérations à deux EUI (`Link(E1, E2)` et `Unlink(E1, E2)` avec `E1 != E2`) verrouillent exclusivement `E1` et `E2` dans l'ordre lexicographique canonique `(issuer, subject)` au sein de la transaction WRITE finale (cf. SPEC-05 §4).
- Les éventuels rejets métier ne sont pas des erreurs d'infrastructure : aucune mutation ni fait Identity n'en résulte. La complétion terminale reste enregistrable séparément.

## 8. Reconstruction et exemples

Exemple d'histoire locale : E2 v1 liée U1/token B, v2 libre, v3 liée U2/token C, v4 libre, v5 liée U1/token D. E1 peut rester en v1 liée U1/token A durant ces opérations ; U1 et U2 demeurent existants. B, C et D sont distincts, même lorsque l'owner redevient U1. Chaque état exact d'E2 se retrouve par son numéro de version, sans intervalle global ni état binding séparé.

Une transition effective EUI et son nouvel état historique sont validés **dans la même transaction** ; aucun événement ne doit annoncer un état non committé. Registration crée User et le premier rattachement atomiquement, sans User orphelin issu d'une tentative perdante.


## 9. Références et statut

La sécurité des Supras, l'attestation Link et les réponses HTTP relèvent de [V3-5E](SPEC-06_Supra_Contracts.md) ; événements et provenance de [V3-2](SPEC-03_Events_Requests_Consumables.md) ; projections de [V3-3](SPEC-04_Read_Projections.md) ; exécution transactionnelle de [V3-4](SPEC-05_Consumption_Discovery.md). Les preuves ID-01/02 et AUD-V3-008/009 restent à implémenter et certifier dans [V3-5D](SPEC-10_Test_Certification.md). Aucun code ou test V3 exécuté.
