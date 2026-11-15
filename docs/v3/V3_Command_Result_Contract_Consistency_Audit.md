# V3 — Audit transversal de cohérence COMMAND / RESULT

Date : 15 novembre 2026. Mandat : `GO_V3_COMMAND_RESULT_CONTRACT_CONSISTENCY_AUDIT`.
Mode : **analyse statique et documentation uniquement ; aucune implémentation**.
Branche observée : `feat/l7-lb-local-playground`.
HEAD initial et référence : `0a3246b9bc58bb813163caf22c6a2108c6f88db9`.
HEAD final observé : `60b12fc3cb81f42a89db0688b0406a4a986f6ffc`.
Les dix fichiers initialement modifiés sont inclus dans l'état lu et préservés.
Les références de lignes portent sur cet état de travail, pas sur des binaires déployés.

## 1. Résumé exécutif

**Verdict : `V3_COMMAND_RESULT_CONTRACTS_REQUIRE_CORRECTIONS`.**

L'hypothèse de départ doit être rectifiée : **CreatePot exige déjà un `potId`
fourni par le client**, comme CreateExpense exige `payload.id` et AddShareholders
exige `payload.targets[].id`. Le script Bruno génère les quatre UUID de scénario
en une fois, avant le POST CreatePot. L'absence de saisie manuelle ne signifie
donc pas génération serveur. Il n'existe pas de commande publique nommée
CreateShareholder : la création est portée par **AddShareholders**, en lot.
Preuves : [C-IN] lignes 59–61, 101–120, 162–180 ; [B-WORK] lignes 40–43 ; [B-POT]
lignes 19–37.

Quinze intentions sont actuellement accessibles : treize par
`POST /api/v3/commands`, deux par `POST /api/v3/identity/requests`. Les propositions
Identity Link sont **retirées de l'offre publique** ; leurs types et résultats
historiques ne constituent pas une seizième commande. [HTTP-W] lignes 41–75 ;
[C-IN] lignes 70–199 ; [I-IN] lignes 33–59 ; [LINK-DEC] lignes 3–18.

Les écarts principaux sont :

| Référence | Niveau | Conclusion démontrée | Conséquence |
| --- | --- | --- | --- |
| CR-01 | P1 | Attribution cliente des IDs de nouveaux Pot, Expense et Shareholders | Responsabilité injustifiée au regard du principe demandé ; utilisable avec génération UUID cliente, donc pas automatiquement P0 |
| CR-02 | **P0 pour le parcours invité avec le lien seul** | Le lien contient seulement un token opaque ; les places ne donnent pas le potId pourtant obligatoire pour accepter | Un invité indépendant ne peut pas construire son acceptation à partir des seules informations publiques reçues |
| CR-03 | P1 | `CHANGED` et `UNCHANGED` sont tous deux exposés comme `COMPLETED` | Le client ne sait pas distinguer mutation et absence de changement |
| CR-04 | P2 aujourd'hui ; dépendance obligatoire de CR-01 | RESULT ne contient pas les IDs Expense/Shareholders concernés | Actuellement connus par soumission ; insuffisant pour une future création serveur sans extension |
| CR-05 | P2 | `observedVersion:0` imposé aux trois intentions qui ne portent pas d'observation | Convention technique inutile à la formulation métier |
| CR-06 | P2 | Nommage Identity et rationnels différent entre entrée et sortie | Adaptations clientes supplémentaires, sans blocage |
| CR-07 | P3 | Bruno masque allocation cliente, partage owner/guest et renouvellement de clé | Une recette réussie ne prouve pas un parcours Web indépendant ou un retry réseau sûr |

CR-02 est borné : le parcours fonctionne si le créateur transmet aussi le potId
par un canal supplémentaire. Ce canal n'est ni contenu dans le lien généré ni
fourni à l'invité par la consultation des places. Le défaut démontré est celui
du **parcours autonome depuis le lien actuel**, pas une impossibilité absolue
d'accepter toute invitation. [INV-W] lignes 28–51 ; [INV-CRYPTO] lignes 33–35 ;
[HTTP-INV] lignes 57–80 ; [C-IN] lignes 59–87.

L'idempotence durable et la séparation admission / traitement / READ sont
cohérentes dans les sources examinées. Les corrections proposées conservent CQRS,
les workers, le fencing et les règles de confidentialité. Les tests cités sont
**lus, jamais exécutés** dans cette mission ; ce rapport n'est pas une nouvelle
qualification L7-LB ni une certification L8.

## 2. Périmètre et inventaire exhaustif des commandes publiques

### 2.1 Méthode et frontières

L'inventaire croise les annotations HTTP de tout `app/`, les filtres de transport,
les décodeurs fermés, les interfaces scellées, l'admission, les codecs persistés,
les décisions métier, les publications, les sources de matérialisation, les DTO
READ, les tests et les trois corpus Bruno. Il ne repose pas sur le seul nom des
classes. Aucun autre contrôleur de mutation métier ni routeur fonctionnel public
n'a été trouvé dans les sources actives examinées.

Le contrat HTTP exécutable est décrit par les décodeurs et DTO Java : aucun
OpenAPI/JSON Schema courant de `app/` n'a été trouvé. Le document OpenAPI sous
`graft/` est une archive, pas l'autorité de cette offre. [SPEC-06] s'applique avec
la décision de retrait [LINK-DEC], qui prévaut sur les clauses Link historiques.

Les POST READ `/pots/{potId}/versions/{version}/expenses/query` et
`/invitations/available-shareholders/query` sont des **consultations**, pas des
COMMAND. Les endpoints actuator/diagnostics ne sont pas des intentions métier.
[HTTP-R] lignes 56–60 ; [HTTP-INV] lignes 57–80.

### 2.2 Conventions des matrices

- **C** = `POST /api/v3/commands`. **I** = `POST /api/v3/identity/requests`.
- **H** = Bearer authentifié, JSON avec Content-Type approprié, exactement un
  `Idempotency-Key` de 1 à 128 caractères `[A-Za-z0-9._~:-]`. L'EUI initiatrice
  vient du Bearer, jamais d'un champ libre. [HTTP-W] lignes 48–54, 73–75, 91–95.
- **E(n)** = enveloppe fermée `{operation,potId,observedVersion:n,bindingToken,payload}`
  et H. **E(v)** : version observée strictement positive issue d'une lecture
  antérieure ; **E(0)** : zéro imposé. Chaque clé est obligatoire.
- **RC** = admission `202 {requestId}` + Location `/api/v3/commands/{requestId}/result`,
  puis résultat Command détaillé au §4. **RI** = même admission, Location
  `/api/v3/identity/requests/{requestId}/result`, puis résultat Identity.
- **P@V** = lire `/api/v3/pots/{potId}?minVersion=V`, ou une version historique
  autorisée ; utiliser les références Expense du manifeste pour la lecture en lot.
  Le RESULT peut précéder la disponibilité du Pot READ.
- **Q serveur** = `requestId` attribué à l'admission. « Client » dans la colonne
  IDs concerne les IDs métier nouveaux ou les références existantes, pas Q.
- **Conforme** porte sur l'intention d'entrée ; le défaut transversal RC/CR-03
  reste applicable aux commandes pouvant aboutir à UNCHANGED.

### 2.3 Matrice exhaustive

| Famille | Commande contractuelle | Endpoint | Champs requis | Champs facultatifs | Connaissance client | Origine des identifiants | Résultat | Parcours suivant | Cohérence | Preuves fichiers/lignes/tests |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Pot | CreatePot | C | E(0), payload.label | Aucun | Libellé, binding ; doit inventer le nouveau potId | Nouveau potId client ; Q serveur | RC, potId, version 1 si créé | P@1 puis AddShareholders | Incohérent au principe : CR-01 ; CR-05 | [C-IN] 59–95 ; [T-IN] 31–48, 168–187 ; [B-POT] 19–37 |
| Pot | UpdatePotDetails | C | E(v), payload.label | Aucun | Pot/version lus, libellé demandé | potId existant ; Q serveur | RC, version nouvelle ou courante | P@V | Conforme en entrée ; RC discutable CR-03 | [C-IN] 89–95 ; [P-DOM] 14–23 ; [T-IN] 168–187 |
| Pot | DeletePot | C | E(v), payload:{} | Aucun | Pot et dernière version ; droit de créateur vérifié serveur | potId existant ; Q serveur | RC, version terminale si supprimé | Current devient masqué ; histoire autorisée antérieure | Conforme ; CR-05 seulement pour le corps vide à simplifier éventuellement | [C-IN] 97–99 ; [P-DOM] 25–29 ; [T-READ] 111–119 |
| Shareholder | AddShareholders | C | E(v), targets non vides ; chacun id,name,attachment,weight | Aucun | Noms, poids, FREE/SELF ; doit inventer chaque nouvel id | IDs Shareholder clients ; pot existant ; Q serveur | RC ; pas de liste créée | P@V, puis réutiliser les IDs soumis | Incohérent au principe : CR-01 ; CR-04 | [C-IN] 101–120 ; [S-DOM] 10–34 ; [T-IN] 190–217 |
| Shareholder | UpdateShareholderDetails | C | E(v), targets[]: id,name,attachment | Aucun | IDs/noms lus ; intention KEEP/SELF/DETACH | IDs existants ; acteur déduit du binding ; Q serveur | RC | P@V ; après DETACH les droits courants peuvent disparaître | Conforme en entrée ; CR-03 | [C-IN] 122–141 ; [EXEC-C] 430–455 ; [S-DOM] 62–105 |
| Shareholder | UpdateShareholderWeights | C | E(v), targets[]: id,weight | Aucun | IDs existants et poids voulus | IDs existants ; Q serveur | RC | P@V / balance à la même version | Conforme en entrée ; CR-03 | [C-IN] 143–156 ; [S-DOM] 36–60 ; [T-IN] 168–187 |
| Shareholder | DeleteShareholder | C | E(v), payload.id | Aucun | ID existant ; Pocoma vérifie droit et usage dans les dépenses | ID existant ; Q serveur | RC | P@V ; rejet REFERENCED si usage actif | Conforme | [C-IN] 158–160 ; [S-DOM] 107–115 ; [T-IN] 168–187 |
| Expense | CreateExpense | C | E(v), payload.id,label,date,amount,payerId,participants[] | Valeur participants[].weight nullable ; clé obligatoire | Montant/date/libellé, payeur et participants existants ; doit inventer l'ID Expense | Nouvel ExpenseId client ; références Shareholder existantes ; Q serveur | RC ; pas d'expenseId dédié | P@V → manifeste → batch Expense | Incohérent au principe : CR-01 ; CR-04 | [C-IN] 162–180, 215–234 ; [X-DOM] 20–29 ; [T-IN] 70–83 |
| Expense | UpdateExpenseDetails | C | E(v), payload.id,label,date | Aucun | ID et version lus, valeurs voulues | Expense et Pot existants ; Q serveur | RC | P@V puis batch exact | Conforme en entrée ; CR-03 | [C-IN] 182–188 ; [X-DOM] 31–40 ; [T-IN] 84 |
| Expense | UpdateExpenseFinancials | C | E(v), payload.id,amount,payerId,participants[] | Valeur participants[].weight nullable ; clé obligatoire | Expense existante et nouvelles données financières | Références existantes ; Q serveur | RC | P@V, batch et balance au même V | Conforme en entrée ; CR-03 | [C-IN] 190–193, 215–234 ; [X-DOM] 42–53 |
| Expense | DeleteExpense | C | E(v), payload.id | Aucun | ID existant et version lue | Références existantes ; Q serveur | RC | P@V : manifeste avec tombstone ; histoire selon droits | Conforme | [C-IN] 195–197 ; [X-DOM] 55–63 |
| Invitation | CreateInvitation | C | E(0), payload.expiresAt | Aucun | Pot existant, binding ; expiration souhaitée UTC | invitationId et secret serveur au traitement ; Q à l'admission | RC, V null ; lecture privée distincte du lien | GET /invitations/by-creation-request/{Q} par créateur | Conforme pour IDs ; discutable CR-05 | [C-IN] 71–80 ; [EXEC-C] 163–174 ; [INV-W] 28–51 ; [T-INV] 215–250 |
| Invitation | AttachShareholderViaInvitation | C | E(0), invitationToken,shareholderId | Aucun | Token reçu, place choisie ; potId aussi exigé mais non restitué par places | Références existantes ; aucun nouvel ID métier ; Q serveur | RC, version après rattachement | P@V après projection des droits | Incohérent pour lien seul : CR-02 ; CR-05 | [C-IN] 59–87 ; [HTTP-INV] 57–80 ; [T-INV] 244–267 |
| Identity | Registration | I | H, {operation:Registration} | Aucun | Seulement son authentification | UserId, EUI ID et binding serveur au traitement ; Q à l'admission | RI, targetEui et targetVersion ; pas de UserId | GET /me/binding?minVersion=targetVersion | Conforme | [I-IN] 40–43 ; [EXEC-I] 58–89 ; [T-I-IN] 18–29 |
| Identity | Unlink | I | H, operation, bindingToken, target:{issuer,subject} | Aucun | Binding courant ; EUI cible connue, éventuellement soi | EUI externe existante ; Q serveur | RI, targetEui/version si succès | Pour soi : /me/binding?minVersion=targetVersion, puis éventuelle Registration | Conforme ; cible redondante seulement en self-Unlink | [I-IN] 44–49 ; [I-DOM] 36–47 ; [T-I-IN] 31–54 |

Les mutations Shareholder ciblées ne sont pas des commandes de création
d'identité. FREE/SELF/KEEP/DETACH sont des **intentions** : le client ne transmet
pas de UserId ni d'état futur calculé. L'exécution déduit SELF de son acteur,
KEEP de l'état courant et DETACH de l'intention de départ. [EXEC-C] lignes
414–455. Une cible Unlink différente de l'initiateur reste autorisée seulement
si elle appartient au même User ; l'explicitation de `target` a donc un sens
pour l'histoire multi-EUI. Ne pas la supprimer sur la seule base du cas Bruno
self-Unlink. [I-DOM] lignes 36–47.

### 2.4 Types internes et historiques exclus

| Élément rencontré | Statut réel | Preuve |
| --- | --- | --- |
| IdentityRequest.Link, opération de résultat LINK | Historique persistant et relisible ; admission directe refusée ; exécution d'un Link encore pending refusée | [I-REQ] 53–63 ; [I-IN] 40–50 ; [EXEC-I] 43–46, 75–76 |
| Création/consultation/acceptation Link Proposal | Trois anciennes routes retirées ; pas de commande publique courante | [LINK-DEC] 3–18 ; [T-LINK] 20–36 |
| AdmissionContext, Origin, intentionFingerprint, grantOrCorrelationReference | Provenance construite par le serveur ; jamais payload à compléter par l'utilisateur | [I-REQ] 71–107 ; [ADMIT-I] 78–96 ; [T-I-IN] 70–78 |
| PotCommand avec userId et sans map d'attachments | Compatibilité d'appelants internes/durables ; pas le DTO HTTP | [C-REQ] 27–65 ; [C-CODEC] 33–61, 93–136 ; [T-IN] 263–286 |
| Command publication, slots, recovery, checkpoints, partitions, event IDs | Mécanismes internes ; aucun champ d'entrée publique correspondant | [EXEC-C] 68–139 ; [C-STORE] 218–273 |
| Anciennes routes /api/v2 et corpus scripts/bruno | Historique ; ne pas les compter dans V3 courant | [B-GUIDE] 8–12 ; [B-README] 3–6 |

## 3. Matrice des champs d'entrée et des connaissances exigées

### 3.1 Enveloppe, authentification et concurrence

| Champ / métadonnée | Requis pour | Source légitime / sens | Analyse de cohérence |
| --- | --- | --- | --- |
| Authorization Bearer | Les quinze intentions | Authentification ; issuer/subject capturés côté serveur | Conforme ; ni bindingToken ni ID métier ne remplacent AuthN |
| Idempotency-Key | Les quinze | Identifiant technique d'une demande logique produit et conservé par le client | Conforme ; distinct de requestId et de tout ID de ressource |
| operation | Les quinze | Choix métier dans le vocabulaire fermé | Conforme ; ne pas accepter arbitrairement les types internes |
| potId | Les treize C | Référence existante, sauf CreatePot où il identifie le nouveau Pot | CR-01 pour CreatePot ; CR-02 pour le parcours invité au lien seul |
| bindingToken | C et Unlink | Opaque, lu par /me/binding ; protège l'occurrence d'affiliation | Légitime malgré AuthN : protège une occurrence, pas une identité à choisir |
| observedVersion | Les treize C | Version métier observée du Pot, pas checkpoint de worker | Positive pour dix intentions ; zéro pour CreatePot/CreateInvitation/Attach ; CR-05 |
| payload | Les treize C | Données métier spécifiques ; même {} est exigé pour DeletePot | Convention utilisable, faible coût ; aucun état calculé imposé |
| target.issuer, target.subject | Unlink | EUI externe à désaffilier ; soi ou EUI historique du même User | Ne pas confondre avec UUID interne EUI/User ; autorité finale serveur |
| requestId, commandId | Jamais en entrée HTTP | requestId serveur à l'admission ; command_id SQL est une référence à ce même ID | Un seul handle public de demande, pas deux IDs clients requis |
| requester(Eui), userId, admission, attestations | Jamais fournis par le client | Contexte authentifié / preuve serveur | Champs supplémentaires refusés par formes fermées ; bon cloisonnement |

Preuves : [HTTP-W] lignes 41–107 ; [C-IN] lignes 59–66, 203–206 ; [C-REQ] lignes
68–76 ; [I-IN] lignes 40–54 ; [FIELDS] lignes 13–44 ; [ADMISSION-DDL] lignes 2–19.

`observedVersion` est bien une information que le client **lit**, pas un résultat
qu'il calcule. Elle ne signifie pas systématiquement « égalité de version Pot » :
les contrôles d'obsolescence portent selon l'opération sur les composants modifiés
depuis l'observation ; DeletePot exige en particulier l'égalité avec la version
courante. Il serait incorrect de supprimer toute observation sous prétexte
d'ergonomie. [CHECKS] lignes 10–26 ; [P-DOM] lignes 14–29 ; [X-DOM] lignes 31–63.

### 3.2 Payloads et optionnalité exacte

| Opération(s) | Champs et contraintes structurelles | Nature de chaque donnée |
| --- | --- | --- |
| CreatePot / UpdatePotDetails | label chaîne acceptée par Label | Intention de libellé |
| DeletePot | Objet vide exact | Aucune donnée métier supplémentaire |
| AddShareholders | targets[] non vide, chaque objet exact {id,name,attachment,weight}, IDs uniques ; attachment FREE ou SELF | ID nouveau alloué client ; nom et poids choisis ; mode d'attachement demandé |
| UpdateShareholderDetails | targets[] non vide, objets exacts {id,name,attachment}, IDs uniques ; KEEP/SELF/DETACH | ID existant ; nouveau nom ; intention d'attachement, pas UserId |
| UpdateShareholderWeights | targets[] non vide, objets exacts {id,weight}, IDs uniques | ID existant ; poids souhaité |
| DeleteShareholder / DeleteExpense | Objet exact {id} | ID d'une ressource existante |
| CreateExpense | {id,label,date,amount,payerId,participants} | ID nouveau ; données saisies ; IDs payeur/participants existants |
| UpdateExpenseDetails | {id,label,date} | ID existant ; valeurs de remplacement |
| UpdateExpenseFinancials | {id,amount,payerId,participants} | ID existant ; valeurs financières demandées |
| CreateInvitation | {expiresAt}, UTC terminé par Z, précision microseconde exacte et borne représentable | Expiration choisie ; aucun secret/ID à produire |
| AttachShareholderViaInvitation | {invitationToken,shareholderId} | Secret reçu et place existante sélectionnée |
| Registration | Pas de payload ; objet exact {operation} | Identité déduite de l'authentification |
| Unlink | Pas de payload ; {operation,bindingToken,target:{issuer,subject}} | Occurrence initiatrice et EUI cible existantes |

Les rationnels d'entrée sont des objets **{numerator,denominator}**, chaînes
entières normalisées ; Amount est non négatif, Weight strictement positif.
Une liste participants est non vide, sans ID dupliqué ; chaque objet contient
obligatoirement `id` **et `weight`**. `weight:null` signifie absence de surcharge
spécifique et recours au poids par défaut ; omettre la clé n'est pas autorisé.
Les clés supplémentaires sont refusées à tous les niveaux. [C-IN] lignes
215–260 ; [FIELDS] lignes 13–49 ; [FIN] lignes 9–34 ; [AMOUNT] lignes 5–8 ;
[WEIGHT] lignes 5–8 ; [C-REQ] lignes 150–179.

### 3.3 Redondances, sources d'autorité et détails internes

Les routes d'admission étant génériques, **aucun potId/expenseId/shareholderId
n'est répété à la fois dans l'URL de mutation et son payload**. Les IDs Pot et
Expense présents dans les deux composants internes ExpenseDetails/Financials
proviennent d'une seule entrée HTTP ; le décodeur les injecte. Ce n'est pas une
double source d'autorité publique. [C-IN] lignes 162–193.

AuthN et bindingToken ne sont pas redondants : l'EUI est authentifiée, le token
atteste l'occurrence présentée, et WRITE revalide son actualité. Le mode SELF
évite justement de demander un UserId connu seulement du serveur. En revanche,
Unlink de soi répète l'EUI de la session ; une facilité self pourrait être
discutée plus tard, sans supprimer la capacité explicite de désaffilier une
autre EUI du même User. [EXEC-C] lignes 142–147, 414–455 ; [I-DOM] lignes 36–47.

Aucun statut futur, solde, numéro d'événement, partition, position, checkpoint,
worker ou version de projection n'est demandé pour commander. Les versions
header/details/financials du POST de **lecture** Expense sont des références
opaques issues du manifeste, pas des versions techniques à inventer dans une
commande. Les différences entre suppression Pot, Shareholder et Expense sont
métier : créateur/usage actif/obsolescence et tombstones. Leur uniformisation
aveugle ferait perdre ces garanties. [HTTP-R] lignes 113–130 ; [DTO-R] lignes
11–18 ; [P-DOM] lignes 25–29 ; [S-DOM] lignes 107–115 ; [X-DOM] lignes 55–63.

## 4. Matrice des résultats et cycle complet

### 4.1 Admission, terminalisation et visibilité

```text
Client -- H + intention --> admission atomique (association + demande + position)
       <-- 202 {requestId}, Location .../result --
Worker : relecture / autorité / protection / effets + terminal + complétion
Materializer : complétion publiée -> résultat READ immuable
Client -- GET Location, Bearer de l'EUI initiatrice --> 404 ou 200 résultat
Client -- lecture métier au moins à la version retournée --> état et références
```

L'admission n'atteste pas la création de la ressource. Elle renvoie **uniquement**
`requestId`, avec `Location` relative et `Cache-Control:no-store`. Ni binding,
ni ID métier nouvellement attribué, ni statut métier ne sont présents dans ce
202. [HTTP-W] lignes 98–107.

RC expose exactement `{requestId,operation,potId,outcome,resultingPotVersion,rejection}`.
RI expose `{requestId,operation,outcome,targetEui,targetVersion,rejection}`.
Les propriétés nulles restent présentes avec le sérialiseur courant. Les
résultats sont privés à l'EUI initiatrice, **sans bindingToken entrant**, y
compris après Unlink. [DTO-R] lignes 24–26 ; [HTTP-R] lignes 69–78 ; [GET-RC]
lignes 12–13 ; [GET-RI] lignes 12–13 ; [T-READ] lignes 96–109.

| Situation | Ce que voit le client | Interprétation et suite |
| --- | --- | --- |
| Admission durable | 202 + requestId + Location | Conserver le handle ; demander le résultat |
| Refus de forme ou sécurité avant admission | 400/401/413/415 selon cause ; enveloppe {code,message,traceId} | Corriger/authentifier ; aucun succès métier à déduire |
| Même clé avec autre intention dans la même portée | 409 IDEMPOTENCY_CONFLICT | Ne pas traiter comme un refus métier terminal |
| Résultat absent/non projeté/inconnu/étranger | Même 404 NOT_FOUND | Ne distingue pas pending, inconnu et masqué ; polling borné |
| READ indisponible | 503 UNAVAILABLE | Incident technique, pas REJECTED métier |
| Traitement terminal positif | 200, outcome COMPLETED | Mutation ou no-op : actuellement indiscernables pour RC |
| Refus métier terminal | 200, outcome REJECTED, rejection, version nulle | Raison métier ; ne pas assimiler à erreur HTTP d'admission |
| Traitement technique non terminal / reprise | Aucun FAILED public ; peut rester 404 au résultat | Timeout client = issue inconnue ; garder handle/clé et reprendre, sans recréer l'intention |

Ces règles de 404 et l'absence de FAILED/PENDING public sont des **choix
normatifs**, pas des défauts de projection inventés ici. Une panne durable ne
peut pas être distinguée d'un retard par ce seul endpoint ; l'exploitation et
un éventuel futur contrat de statut restent des questions distinctes. Ne pas
exposer des claims/checkpoints pour résoudre ce besoin. [SPEC-06] lignes
133–145 ; [T-RC] lignes 10–16 ; [ERR-W] lignes 148–172 ; [ERR-R] lignes 134–143.

### 4.2 Résultats par commande

**R** ci-dessous signifie refus métier possible : notamment INVALID_BINDING
pour toute C, NOT_FOUND si le Pot/cible requis manque, et raisons de transition
selon droits/observation. Aucune commande n'accepte un `outcome` client.

| Commande | Issues WRITE possibles | RESULT HTTP positif / identifiants | Refus utile / poursuite |
| --- | --- | --- | --- |
| CreatePot | CHANGED / R | COMPLETED, potId soumis, resultingPotVersion=1 | ALREADY_EXISTS notamment ; lire le Pot créé |
| UpdatePotDetails | CHANGED / UNCHANGED / R | COMPLETED, potId, V nouvelle ou courante | FORBIDDEN/DELETED/OBSOLETE ; P@V |
| DeletePot | CHANGED / R | COMPLETED, potId, V de suppression | Créateur requis, égalité de version ; Current masqué après projection |
| AddShareholders | CHANGED / R | COMPLETED, potId, V ; aucun shareholderId dans RC | ALREADY_EXISTS/INVALID_ATTACHMENT notamment ; IDs soumis puis P@V |
| UpdateShareholderDetails | CHANGED / UNCHANGED / R | COMPLETED, potId, V ; pas de liste des cibles modifiées | INVALID_ATTACHMENT/OBSOLETE ; KEEP peut être sans effet ; P@V selon droits |
| UpdateShareholderWeights | CHANGED / UNCHANGED / R | COMPLETED, potId, V | Référence/obsolescence ; P@V et balance |
| DeleteShareholder | CHANGED / R | COMPLETED, potId, V | REFERENCED, FORBIDDEN notamment ; P@V |
| CreateExpense | CHANGED / R | COMPLETED, potId, V ; aucun expenseId dans RC | ALREADY_EXISTS/INVALID_REFERENCE notamment ; manifeste puis batch |
| UpdateExpenseDetails | CHANGED / UNCHANGED / R | COMPLETED, potId, V | Cible/obsolescence ; manifeste puis batch |
| UpdateExpenseFinancials | CHANGED / UNCHANGED / R | COMPLETED, potId, V | Cible/références/obsolescence ; batch et balance |
| DeleteExpense | CHANGED / R | COMPLETED, potId, V | Cible/obsolescence ; manifeste historique/tombstone |
| CreateInvitation | INVITATION_CREATED / R | COMPLETED, potId, V null ; invitationId et lien au GET privé by-creation-request | FORBIDDEN/DELETED/INVALID_INVITATION notamment ; pas d'incrément Pot |
| AttachShareholderViaInvitation | CHANGED / R | COMPLETED, potId, V après attachement ; shareholderId connu de la sélection | INVALID_INVITATION/INVALID_ATTACHMENT notamment ; P@V après convergence |
| Registration | CHANGED / REJECTED | COMPLETED, operation REGISTRATION, targetEui et targetVersion | TARGET_ALREADY_BOUND ; consulter son binding courant plutôt que répéter l'inscription |
| Unlink | CHANGED / REJECTED | COMPLETED, operation UNLINK, targetEui et targetVersion | INVALID_BINDING/TARGET_NOT_BOUND/TARGET_BOUND_TO_ANOTHER_USER ; binding propre si self |

Preuves par famille : [EXEC-C] lignes 142–410, 459–496 ; [P-DOM], [S-DOM],
[X-DOM], [ATTACH-DOM] lignes 19–44 ; [I-DOM] lignes 23–47. Les enums [P-RESULT]
et [I-RESULT] donnent les raisons de transition ; INVALID_INVITATION et
INVALID_BINDING sont aussi produits dans l'exécution applicative.

### 4.3 Ce que le RESULT conserve et ce qu'il perd

WRITE persiste outcome/version/rejection puis publie une complétion reliée au
requestId. La source RC lit cette complétion, la demande et le résultat exacts,
avec les positions publiées ; le materializer stocke ou vérifie la même valeur.
READ conserve `writeOutcome` **CHANGED/UNCHANGED/REJECTED/INVITATION_CREATED**.
La perte est dans `CommandTerminalResult.outcome()` puis le DTO HTTP, pas dans
la source ou la projection. [C-STORE] lignes 145–163, 218–273 ; [SRC-RC] lignes
20–40 ; [MAT-RC] lignes 10–19 ; [DB-RC] lignes 11–17 ; [MODEL-RC] lignes 22–40.

La version retournée ne suffit pas à reconstruire la distinction : un no-op
peut retourner une version supérieure à l'observation parce qu'une autre
commande a avancé le Pot. Une comparaison « V résultat > V observée » ne prouve
donc pas que **cette** commande l'a modifié. [EXEC-C] lignes 468–488.

Il n'y a pas lieu de dupliquer tous les soldes, détails Expense ou secrets dans
chaque RESULT. potId/V puis P@V suffisent à relire l'état métier. Pour Invitation,
le GET privé corrélé par requestId fournit déjà invitationId/potId/expiresAt/link ;
son absence dans RC n'est pas un défaut. Pour Registration, ne pas restituer
UserId/EUI ID interne est conforme à la confidentialité : le client poursuit
avec targetVersion puis /me/binding. [HTTP-INV] lignes 25–53 ; [HTTP-R] lignes
32–39 ; [DTO-R] lignes 21–26 ; [SRC-RI] lignes 33–42.

## 5. Analyse approfondie des créations

### 5.1 CreatePot

**Où est généré potId ?** Dans le client courant, notamment
`crypto.randomUUID()` de Bruno avant le POST. Le serveur décode l'UUID et
l'inscrit tel quel dans v3_command ; AdmitCommandV3 génère seulement requestId.
L'exécution appelle PotTransitions.create avec ce potId et protège son absence
ou sa version. Aucun générateur serveur de potId n'intervient dans ce chemin.
[B-WORK] lignes 40–43 ; [C-IN] lignes 59–61 ; [ADMIT-C] lignes 64–82 ; [ADMIT-DB]
lignes 86–103 ; [EXEC-C] lignes 149–160.

**Disponibilité et restitution.** L'ID est connu du client avant l'admission,
mais le Pot n'existe pas pour autant. Le 202 ne le renvoie pas ; le RESULT le
renvoie sous `potId`, puis le DTO Pot sous `id`. La création effective est à
la version 1 ; le client attend RESULT puis la disponibilité READ avant de
modifier le Pot. La version positive ne doit pas être inventée après un 202.
[HTTP-W] lignes 98–107 ; [DTO-R] lignes 13, 24 ; [P-DOM] lignes 10–12 ; [B-POT]
lignes 50–52.

**Répétitions.** Même clé/portée/intention canonique : même requestId, donc même
potId durable ; aucune nouvelle commande. Même clé avec un potId regénéré :
autre intention, 409. Nouvelle clé et même potId : autre commande, pouvant être
rejetée ALREADY_EXISTS. Nouvelle clé et nouvel ID : autre création possible,
pas une répétition idempotente. [ADMIT-C] lignes 51–98 ; [C-IN] lignes 59–61,
201–207 ; [P-DOM] lignes 10–12.

### 5.2 CreateExpense

**Confirmation.** Le champ public s'appelle `payload.id`, pas `expenseId`.
Il est injecté dans ExpenseDetails et ExpenseFinancials, puis persisté dans
le JSON durable. Le worker l'utilise pour sélectionner l'existence, empêcher
la réutilisation d'une ressource, créer les trois composants et publier leurs
références. [C-IN] lignes 162–180 ; [C-CODEC] lignes 71–74, 194–197 ; [EXEC-C]
lignes 327–354, 561–566 ; [X-DOM] lignes 20–29.

**Pourquoi client ?** Le fait établi est que le contrat public réutilise un
modèle interne déjà identifié. Une motivation historique obligatoire
(offline, ID externe stable, import, références avant admission) n'est pas
établie par ce chemin ou les tests lus. Lui attribuer une telle motivation
serait une hypothèse. Les tests démontrent un contrat existant, pas sa nécessité
comme responsabilité cliente. [T-IN] lignes 70–83, 168–187.

**Nécessité avant admission/traitement.** L'UUID est requis avant admission
par le décodeur actuel, mais l'admission n'utilise aucun état Expense. Le
traitement doit disposer d'un ID stable pour ses composants ; il n'a aucune
exigence démontrée que cet ID soit *choisi par le client*. Il entre actuellement
dans l'empreinte de l'intention parce qu'il est dans le payload canonique.
L'idempotence est portée par l'association S-03, pas par cet ID. Le fencing
observé protège la demande, l'autorité Identity et la version du **Pot** ;
l'ID Expense est une référence d'effet, pas un claim token. [ADMIT-C] lignes
51–83 ; [EXEC-C] lignes 106–139 ; [C-STORE] lignes 116–141.

**Attribution serveur envisageable.** Oui, à l'admission dans la commande
durable, ou au traitement avec une stratégie stable avant tout effet. Ce
constat de faisabilité n'est pas une implémentation validée. Avec l'option A
recommandée au §6, retirer l'ID nouvellement créé de l'intention externe et
ajouter au résultat durable puis READ une référence typée à l'Expense créée.
Le client ne doit pas la retrouver par différence entre deux manifestes,
ce qui serait ambigu sous concurrence. Actuellement il connaît l'ID soumis ;
il n'est donc pas bloqué par son absence du RESULT. [DTO-R] lignes 14–24.

### 5.3 AddShareholders, équivalent réel de CreateShareholder

**Confirmation et usages.** Chaque cible impose `id`. L'ID sert à la map
attachments, à la sélection d'existence, aux trois composants Shareholder
et à leur publication ; il identifie ensuite payeur, participant ou place
d'invitation. Les IDs de **nouveaux** shareholders sont à distinguer des
références **existantes** payerId/participants[].id/shareholderId.
[C-IN] lignes 101–120 ; [C-REQ] lignes 44–65 ; [EXEC-C] lignes 240–273,
414–426, 541–558 ; [S-DOM] lignes 19–34.

**Avant admission / asynchronisme / fencing.** Même conclusion que pour
Expense : l'ID est obligatoire aujourd'hui pour la forme, la validation
d'unicité et l'intention durable ; ni l'unicité S-03 ni le claim du worker
n'exigent une allocation cliente. Des IDs générés distincts et persistés dans
la même admission permettent de conserver le traitement interne identifié.
Il faut aussi construire la map d'attachement avec ces IDs et conserver les
garanties de liste non vide, ordre et unicité. Ne pas effacer la validation
des intentions sous prétexte de génération serveur. [C-REQ] lignes 150–179 ;
[ADMIT-C] lignes 75–83 ; [EXEC-C] lignes 106–139.

**Résultat cible.** Une liste de ressources créées, liée de façon déterministe
aux éléments d'entrée (par exemple leur index dans cette liste ordonnée),
permet de poursuivre vers Expense ou Invitation. Des noms identiques peuvent
être légitimes : le nom ne doit pas devenir une clé de rapprochement. Aucun
index ni clientRef n'est aujourd'hui un champ public ; leur éventuelle
convention de sortie reste à approuver. Pas de nouvelle obligation de
fournir un UUID métier sous un autre nom.

### 5.4 Autres créations et absence de généralisation forcée

| Création | Allocateur actuel | Disponibilité et récupération | Répétition |
| --- | --- | --- | --- |
| Invitation | Worker : UUID et token aléatoire ; lien chiffré | Après commit et projection, GET privé by-creation-request donne invitationId et link | Même demande durable ; unicité creation_request_id ; résultat terminal évite de retraiter |
| User / occurrence de binding par Registration | Worker : User UUID et token ; autorité réserve un EUI ID stable par issuer/subject | RI donne targetVersion ; /me/binding donne euiVersion/active/token ; IDs internes cachés | Même requestId rejoué ne recrée pas ; autre clé = autre Registration, rejet si déjà lié |
| Rattachement par invitation | Pas de nouvelle ressource identifiée ; nouvelle révision du Shareholder existant | RC fournit V ; consultation métier après convergence | Même clé = même demande ; autre clé après attachement peut être rejetée INVALID_ATTACHMENT |
| Versions/composants/événements | Serveur uniquement | Références READ et infrastructure interne | Hors responsabilité d'allocation cliente |

Preuves : [INV-W] lignes 28–51, 89–105 ; [INV-DDL] lignes 7–24 ; [EXEC-C]
lignes 74–76, 176–202 ; [EXEC-I] lignes 43–89 ; [AUTH-I] lignes 39–73 ;
[I-DOM] lignes 23–47 ; [ATTACH-DOM] lignes 19–44.

Dans l'inventaire courant, les trois intentions CreatePot/CreateExpense/
AddShareholders épuisent les créations publiques imposant un ID métier nouveau
au client. Registration et CreateInvitation montrent déjà que la génération
pendant traitement fonctionne sans exposer les IDs internes ni avancer leur
existence à l'admission.

## 6. Identifiants, idempotence et options d'allocation

### 6.1 Taxonomie et garanties actuelles

| Identifiant / version | Qui le connaît / attribue ? | Rôle exact |
| --- | --- | --- |
| potId de CreatePot | Client avant POST | ID d'une nouvelle ressource ; distinct de son existence effective |
| payload.id de CreateExpense et targets[].id de AddShareholders | Client avant POST | IDs nouveaux, utilisés par les composants et références futures |
| potId des autres C, IDs d'update/delete, payerId, participants[].id, shareholderId d'acceptation | Obtenus par lecture/selection ou contexte existant | Références de ressources existantes ; légitimement clientes sauf chemin manquant CR-02 |
| requestId | UUID serveur à l'admission | ID public de commande/demande et corrélation du résultat ; le SQL command_id de l'association référence ce même ID |
| Idempotency-Key | Client, stable pour la demande logique | Clé technique ; protégée par portée et empreinte, non stockée brute dans l'association |
| EUI issuer/subject | Fournisseur d'identité via authentification ; cible explicite pour Unlink | Identité externe, pas UserId ni UUID de ressource métier |
| EUI ID, UserId | Autorité Identity serveur | IDs internes stables ; non requis ni exposés au client métier |
| bindingToken | Serveur ; consultable par sa propre EUI | Token d'occurrence d'affiliation ; ni identité ni clé d'idempotence |
| invitationId / invitationToken / tokenHash | Serveur au traitement / secret partagé / empreinte interne | Trois objets distincts ; accepter exige le secret, pas l'UUID Invitation ni son hash |
| observedVersion / resultingPotVersion | Observation cliente / résultat serveur | Versions métier du Pot ; pas versions de concurrence de worker |
| targetVersion / euiVersion | Résultat Identity / binding READ | Version métier de l'EUI cible ; minVersion permet de rattraper la lecture propre |
| headerVersion/detailsVersion/financialsVersion/weightVersion | Références données par READ | Versions locales de composants ; le client les retransmet dans les lectures exactes |
| eventId, positions, checkpoints, claimToken, leases | Infrastructure | Corrélation/publication/concurrence internes, absents de la commande HTTP |
| traceId | UUID serveur pour la réponse d'erreur | Diagnostic de transport ; ne remplace ni requestId ni clé conservée |

### 6.2 Portée et persistance de l'idempotence

AdmitCommandV3 et AdmitIdentityRequestV3 calculent une portée HMAC avec
`issuer,subject,attestedClient=ABSENT,category,operation,clientKey`. **potId
n'appartient pas à la portée** : il appartient à l'intention. La même clé pour
la même opération de la même EUI sur un autre Pot entre donc en conflit ; la
même clé pour une autre opération ou catégorie peut admettre une autre demande.
Le contrat courant ne déduplique pas toutes les opérations par une clé globale.
[ADMIT-C] lignes 51–58 ; [ADMIT-I] lignes 48–55 ; [HMAC] lignes 44–73.

L'association conserve requestId, catégorie/opération, version canonique, IDs
de clés HMAC, scopeFingerprint, intentFingerprint et date. Une contrainte unique
sur scope_key_fingerprint arbitre la concurrence. `reserve` puis `insert`
commande/demande et publication de position sont dans la même transaction ;
les FKs différées relient l'association à la demande. Un concurrent perdant
relit l'association et compare l'intention, puis restitue le même requestId
ou 409. [ADMIT-DB] lignes 31–103, 108–146 ; [ADMISSION-DDL] lignes 2–19 ;
[ADMIT-C] lignes 59–98 ; [ADMIT-I] lignes 56–113.

La canonicalisation normalise UUID, rationnels, date et ordre des clés JSON ;
elle trie les participants Expense. L'ordre des targets Shareholder reste
significatif pour la comparaison, même si certains effets métier commutent.
Le bindingToken et observedVersion font partie de l'intention canonique. Un
retry doit conserver **clé et intention originale**, sans remplacer le token,
la version ou les IDs. [C-IN] lignes 59–66, 201–260 ; [FIELDS] lignes 63–105 ;
[T-CANON] lignes 169–210, 260–271. Ces choix sont explicitement testés dans les
sources ; ce rapport ne les assimile pas à une corruption d'idempotence.

Après timeout réseau ou réponse perdue : resoumettre le même appel original
avec la même clé et une authentification valide ; retrouver le même requestId,
puis GET résultat. Un nouveau JWT de la même EUI n'est pas un nouveau bindingToken
dans le corps. Un commit ambigu ne justifie pas une nouvelle commande ; le
traitement transactionnel échoue fermé et l'unicité arbitre le retry après
résolution physique. Les sources de tests couvrent première admission/replay,
concurrence, rollback et acquittement perdu : [T-ADMIT] lignes 247–353,
488–505, 559–642, 1058–1096. **Aucune de ces preuves n'a été rejouée ici.**

Pas de purge introduite dans le périmètre examiné. La stabilité est conditionnée
à la conservation des associations, demandes, historiques et clés nécessaires
à la comparaison/restauration ; ne pas promettre une idempotence éternelle sans
politique de rétention. Une même intention avec **une autre clé** n'est pas
garantie unique. [SPEC-06] lignes 37–49 ; [HMAC] lignes 64–78.

### 6.3 Comparaison des options A/B/C

| Critère | A — génération à l'admission | B — génération au traitement | C — génération cliente actuelle |
| --- | --- | --- | --- |
| Atomicité | IDs inclus dans la demande et l'association atomiques ; aucune ressource métier créée au 202 | Allocation + effets + terminal/publication doivent être atomiques, ou allocation durable préalable | IDs déjà fournis ; admission atomique identique, effets ultérieurs |
| Idempotence | Comparer l'intention externe sans les IDs serveur ; retrouver la même demande avec les IDs réservés | Même demande unique ; retrouver l'ID terminal, jamais réallouer après commit | Même clé et mêmes IDs requis ; regénérer l'ID casse le replay |
| Retries / déterminisme | IDs durables avant worker ; retries worker naturellement stables | Des candidats rollbackés peuvent changer sans effet visible ; après commit, résultat existant doit faire autorité | Client doit conserver IDs/clé/body ; oubli = conflit ou duplication selon la clé |
| Références entre commandes | Peut offrir un ID tôt si prévu, mais aucune existence/droit avant succès ; garder l'attente du RESULT | Il faut attendre RESULT pour connaître l'ID | IDs connus tôt, sans preuve d'existence ; attente toujours nécessaire |
| Résultat | ID alloué n'est pas succès ; RESULT doit confirmer et rendre les références utiles | RESULT ou lecture corrélée indispensable pour découvrir les IDs | IDs déjà connus ; leur répétition peut améliorer traçabilité |
| Compatibilité | Décodeur, canonicalisation, construction durable à faire évoluer ; garder replay ancien | Modèle durable à rendre non identifié ou allocation intermédiaire ; plus intrusif pour Pot/Expense/Shareholders actuels | Aucun changement technique, mais principe demandé non satisfait sans exception explicite |
| Complexité | Faible relative pour les trois intentions déjà identifiées ; responsabilité d'allocation dans l'admission | Bonne pour objets déjà créés au worker ; plus de travail pour sélection et maps actuellement indexées par IDs | Infrastructure simple ; responsabilité et stockage déplacés au client |

**Recommandation pour V3 : A pour CreatePot, CreateExpense et AddShareholders ;
conserver B pour Invitation et Registration.** Cette asymétrie de *moment*
interne est justifiée ; l'interface cliente reste cohérente : Pocoma attribue
les IDs nouveaux et le client retrouve les ressources au résultat ou à la
lecture corrélée. A évite de transformer les commandes durables actuellement
identifiées et leurs sélections. B est déjà bien adapté au secret Invitation,
au User interne et à l'autorité EUI ; les avancer à l'admission serait un coût
sans bénéfice client démontré. C reste une option de conception si un besoin
offline/import/référence externe est ultérieurement démontré et approuvé ; il
ne constitue pas la cible par défaut de ce mandat.

Pour A, générer les IDs après validation de l'intention externe, les inclure
dans la demande persistée **dans la même unité atomique que la réservation**.
Un concurrent perdant peut jeter ses candidats non persistés ; seule la
demande gagnante fait autorité. Il faut séparer explicitement canonicalisation
de l'intention cliente et construction de la commande identifiée : ajouter un
UUID aléatoire dans `input.canonical()` rendrait tout replay conflictuel.
Ne pas réutiliser aveuglément requestId comme potId/expenseId : les rôles et
espaces de noms sont distincts, et le batch Shareholder exige plusieurs IDs.

Le 202 peut rester `{requestId}` : les IDs alloués ne doivent pas être relus
depuis le payload par l'admission pour enrichir son replay. Cette option évite
d'élargir les lectures d'autorité et n'oblige pas à dupliquer un mapping dans
v3_admission_association. La commande durable garde les IDs alloués ; RESULT
les restitue après succès. Une disponibilité immédiate des IDs au 202 serait
un **autre choix** de contrat et de privilèges à justifier, pas une conséquence
obligatoire de A. [ADMIT-DB] lignes 31–55, 86–103.

## 7. Incohérences : preuves, corrections, impacts et tests nécessaires

Les niveaux distinguent les défauts prouvés des choix discutables. Les tests
ci-dessous sont des **obligations proposées pour un futur mandat**, pas des
tests ajoutés ou exécutés pendant l'audit.

### CR-01 — P1 : IDs de ressources nouvelles attribués au client

**Preuve.** [C-IN] lignes 59–61, 101–120, 162–180 ; [B-WORK] lignes 40–43 ;
[T-IN] lignes 31–83. Pot, Expense et Shareholders suivent tous C, alors que
Invitation et Identity suivent B. Aucun besoin public actuel justifiant C
n'a été établi.

**Conséquence.** Le client doit fabriquer/conserver un ID métier et coordonner
son cycle avec clé/body. Cela contredit la cible demandée mais n'est pas P0 :
une application sait techniquement générer un UUID.

**Correction envisagée.** Contrat public sans IDs nouveaux ; génération A et
construction durable identifiée ; restitution CR-04. Pas de changement des IDs
existants ni de migration de leur valeur.

**Impacts/compatibilité.** WEB (formes et admission), COMMAND (construction,
résultat exact), PROJECTION (nouveaux résultats), tests/Bruno/docs. Préserver
l'ancien format canonique et son replay ; retrait immédiat des champs casse
les clients et les commandes/replays existants. Admission versionnée à décider.

**Tests.** Création sans ID ; stabilité ID/requestId sous replay avant/après
terminal ; double admission concurrente ; rollback et commit ambigu ; worker
repris après interruption ; plusieurs shareholders rapprochés par index ;
anciens payloads durables et anciennes clés ; aucune lecture métier à l'admission.

### CR-02 — P0 borné : acceptation d'une invitation depuis le lien seul

**Preuve.** [INV-W] lignes 28–51 produit `pocoma://invitation/<token>` ;
[INV-CRYPTO] lignes 33–35 produit 32 octets aléatoires, sans potId encodé.
[HTTP-INV] lignes 69–80 renvoie seulement shareholders, chaque place étant
{shareholderId,name} [INV-READ] lignes 95–102. [C-IN] lignes 59–87 exige potId
et shareholderId pour accepter. Le GET privé du lien est masqué à l'invité,
et le Pot complet reste masqué avant attachement. [T-INV] lignes 215–267.

**Conséquence.** Avec sa session, son binding et le lien seul, l'invité connaît
le token puis sa place, mais manque d'une donnée obligatoire. Les tests
existants utilisent `f.pot` dans leur helper d'acceptation ; ils ne prouvent
donc pas la suffisance des seules réponses invitées. [T-INV-FIX] lignes 186–197.
Bruno réutilise de même le potId global de l'owner. [B-ACCEPT] lignes 21–37 ;
[B-WORK] lignes 154–155, 206–213.

**Correction minimale proposée.** Retourner aussi potId dans l'enveloppe des
places après validation du token et du binding, puis conserver l'acceptation
et ses vérifications WRITE actuelles. Cet ajout requiert une **décision humaine
de confidentialité** : la réponse limitée à shareholderId/name est aujourd'hui
un allowlist explicite et testée. Une alternative consiste à faire résoudre
le Pot depuis le token par le serveur et supprimer potId de cette intention ;
elle touche davantage le contrat durable/admission. Ne pas décoder le secret
comme un potId ni faire lire WRITE synchroniquement par le contrôleur READ.

**Impacts/compatibilité.** Option minimale : contrat/DTO et façade de requête
READ, tests et Bruno ; la projection possède déjà potId, sans nouveau modèle
métier WRITE. Les clients à validation stricte peuvent refuser le champ ajouté.
Les règles de masquage, d'expiration et de non-réservation doivent rester intactes.

**Tests.** Deux contextes clients isolés : le second n'a initialement que sa
session et le lien ; tout champ accepté provient ensuite des réponses publiques.
Token erroné/expiré, mauvais binding, étranger au GET privé, Pot supprimé,
concurrence sur une place, revalidation WRITE, refus du Pot complet avant
attachement, lecture après convergence. Modifier l'oracle fermé existant par
décision explicite, pas le contourner avec une fixture ou variable owner.

### CR-03 — P1 : perte du succès sans changement dans le RESULT HTTP

**Preuve.** [MODEL-RC] lignes 22–40 conserve writeOutcome puis réduit tout
succès à COMPLETED ; [HTTP-R] lignes 69–72 n'envoie que outcome() ; [DTO-R]
ligne 24 exclut writeOutcome. [T-RC] lignes 10–12 encode cette perte.

**Conséquence.** Les modifications de libellés/détails/poids/finances ne
permettent pas de distinguer CHANGED de UNCHANGED. Relire l'état ne prouve pas
l'effet exact d'une commande sous concurrence.

**Correction envisagée.** Garder outcome COMPLETED/REJECTED pour compatibilité
et ajouter un indicateur public d'effet, par exemple `effect:CHANGED|UNCHANGED`
pour les intentions concernées ; définir CREATED pour Invitation si la même
convention est choisie. Ne pas exposer aveuglément le code interne
INVITATION_CREATED. Pour un refus, effect absent/null et rejection renseigné.

**Impacts/compatibilité.** DTO/contrat WEB et tests ; writeOutcome est déjà
durable et projeté, donc aucune migration des résultats n'est nécessaire pour
la seule distinction actuelle. Compatibilité des clients stricts à arbitrer.

**Tests.** Deux modifications identiques avec clés distinctes : premier CHANGED,
second UNCHANGED ; version observée ancienne mais état cible déjà identique ;
replay du résultat inchangé ; refus ; Invitation ; propriété privée historique.

### CR-04 — P2 actuel : corrélation implicite des sous-ressources dans RC

**Preuve.** [DTO-R] ligne 24 ; [SRC-RC] lignes 23–40 ne transporte que potId,
outcome/version/rejection. Les références de composants existent dans la
publication interne [C-STORE] lignes 231–244, sans liste publique de créations.

**Conséquence.** Aujourd'hui les IDs créés sont connus par la soumission,
donc pas de blocage démontré. Après CR-01, le client ne doit pas déduire une
Expense ou un Shareholder créé par comparaison de READ ou par le nom.

**Correction envisagée.** Pour les créations, résultat durable avec références
typées des ressources effectivement créées, dont l'index de cible pour un lot.
Les propager jusqu'à RC. Pour les modifications, répéter chaque ID connu est
facultatif tant que requestId et intention cliente suffisent ; ne pas gonfler
tous les résultats avec les détails de projection.

**Impacts/compatibilité.** Contrat public, résultat WRITE, schéma CURRENT,
source SELECT, projection READ, reconstruction et oracles d'égalité. Le rôle
source n'a pas accès au payload Command ; préserver cette frontière plutôt
que lui donner un SELECT global. [ACL-SRC] lignes 14–26. Des références
publiques peuvent être portées par un résultat dédié sans exposer les
événements de composants internes. Définir la compatibilité historique
des anciens résultats, sans inventer de nouvelles références inexactes.

**Tests.** IDs exacts dans résultats et READ, ordre/mapping de batch, absence
de création lors d'un rejet, reconstruction d'une base READ vide, doublons et
divergence détectée, anciennes lignes sans extension, privilèges minimaux.

### CR-05 — P2 : enveloppe identique imposant une observation fictive

**Preuve.** [C-REQ] lignes 68–76 impose zéro à CreatePot, CreateInvitation,
AttachShareholderViaInvitation ; [EXEC-C] lignes 149–202 ne les traite pas comme
les intentions à observedVersion positive. [B-WORK] lignes 46–50 gère déjà ces
exceptions. DeletePot exige aussi payload:{} [C-IN] lignes 97–99.

**Conséquence.** Le client apprend une convention de sentinelle sans décision
métier. Ce n'est pas une version inconnue impossible à produire, donc pas P0.

**Correction envisagée.** Omettre l'observation pour ces trois intentions dans
une future forme versionnée ; l'admission adapte vers zéro interne. Conserver
les vraies observations sur les dix autres C. Le corps vide de suppression
peut rester pour limiter le changement ; son retrait n'est pas prioritaire.

**Impacts/compatibilité.** Contrat/décodeur et canonicalisation WEB, tests,
Bruno/docs ; pas de nouvelle version WRITE par cet ajustement seul. Ne pas
appliquer la nouvelle forme rétroactivement aux empreintes déjà conservées.

**Tests.** Formes nouvelle/ancienne selon version ; champ d'observation absent
ou interdit pour les trois exceptions ; observation positive maintenue ailleurs ;
replay d'ancien zéro et conflit sur vraie intention changée.

### CR-06 — P2 : conventions de noms à clarifier, sans uniformisation métier

**Preuve.** Registration/Unlink en entrée [I-IN] lignes 40–49 deviennent
REGISTRATION/UNLINK en RI [MODEL-RI] lignes 6–16 ; C conserve ses noms CamelCase.
Les rationnels d'entrée numerator/denominator [C-IN] lignes 242–260 deviennent
n/d en READ [DTO-R] lignes 10, 27. Les cibles sont nommées `id` dans les commandes
Expense et `shareholderId` dans l'acceptation/DTO participant.

**Conséquence.** Adaptation explicite dans le client, risque de traiter à tort
les corps READ comme des payloads COMMAND. Pas d'information inaccessible.

**Correction envisagée.** Documenter d'abord le mapping complet ; pour une
nouvelle version de contrat, choisir des conventions lisibles cohérentes.
Conserver resultingPotVersion et targetVersion, dont les sujets diffèrent ;
ne pas renommer ces deux versions en un champ générique ambigu.

**Impacts/compatibilité.** Une documentation de mapping ne change rien en
production ; un renommage est une rupture publique à versionner, avec adaptation
de DTO/décodeurs, tests et Bruno. Aucun besoin de refaire CQRS.

**Tests.** Mapping entrée/résultat par opération, précision rationnelle sans
float, option weight:null, compatibilité des noms/alias choisis, clients stricts.

### CR-07 — P3 : Bruno prouve des scénarios, pas toutes les responsabilités Web

**Preuve.** Allocation anticipée [B-WORK] lignes 40–43 ; nouvelle clé à chaque
Send d'admission lignes 82–84 ; snapshot explicite du replay lignes 73–81,
148–161 ; contexte d'invitation commun lignes 206–213 ; exemples [B-SHARE],
[B-EXPENSE], [B-ACCEPT].

**Conséquence.** « Rejouer le dossier » et « réessayer la même demande après
timeout » sont des actions différentes. Un Send de CreatePot refait IDs et
clé ; pour CreateExpense, il renouvelle la clé en gardant généralement l'ID
du scénario, ce qui peut mener à ALREADY_EXISTS plutôt qu'au résultat original.
Le bouton générique Send n'est donc pas le mécanisme de retry S-03. A04, en
revanche, réutilise intention et clé et contrôle le même requestId.

**Correction future.** Expliquer distinctement nouvelle intention/retry,
capturer IDs créés depuis RESULT si CR-01 est adopté, isoler les variables des
clients owner/guest dans le test au lien seul. Conserver les GET résultats
visibles séparés et l'absence de POST caché. Ne pas corriger Bruno dans cet audit.

**Impacts/compatibilité.** Scripts/collections, synchronisation des copies de
parcours et documentation ; aucun changement applicatif pour la clarification
seule. Les dix modifications locales restent intégralement préservées.

**Tests futurs.** Vérifications GUI/CLI des helpers et parcours, simulation
réponse perdue avec retry identique, aucun ID préfabriqué pour les nouvelles
créations, aucune variable owner nécessaire à l'acceptation guest.

## 8. Proposition de contrat cible minimal

Cette section est une **proposition à arbitrer**, pas une modification de SPEC
ni l'annonce d'un nouveau contrat déjà exposé.

### 8.1 Répartition des responsabilités

1. Pocoma attribue les IDs des nouvelles ressources ; les commandes client ne
   portent que les données métier connues et les références existantes.
2. Le client attribue et persiste Idempotency-Key pour chaque nouvelle demande
   logique. Authentification et cette clé sont des métadonnées d'admission.
3. Le bindingToken reste un contexte d'occurrence opaque fourni après lecture,
   distinct des données métier. Le garder à son emplacement actuel pour ce
   correctif minimal ; un déplacement en header n'est pas nécessaire.
4. Les observations métier positives restent jointes aux intentions qui en
   ont besoin ; les sentinelles zéro peuvent être omises dans la nouvelle forme.
5. Admission conserve `202 {requestId}` et Location, sans annoncer une création
   métier ni une version future. Les IDs alloués sont persistés dans la demande.
6. RESULT conserve outcome et ajoute l'effet utile, les ressources créées et
   leur corrélation de lot. Il donne la version de lecture et les refus métier.
   Aucune obligation de dupliquer soldes, détails complets, liens secrets ou
   IDs internes Identity.
7. READ reste la source de l'état et des références exactes ; Invitation garde
   sa lecture privée du lien, et les places donnent le contexte suffisant pour
   accepter après approbation de l'élargissement limité CR-02.
8. Erreurs de forme/transport restent HTTP ; refus métier restent RESULT
   REJECTED avec un code stable ; problèmes techniques non terminalisés ne
   deviennent pas des rejets métier fabriqués.

### 8.2 Exemples de nouvelle forme proposée

Noms d'extension `effect`, `createdResources`, `inputIndex` **illustratifs et
non approuvés**. Ces exemples n'ajoutent aucun endpoint au dépôt.

```json
{
  "operation": "CreatePot",
  "bindingToken": "<occurrence lue>",
  "payload": { "label": "Voyage" }
}
```

```json
{
  "operation": "CreateExpense",
  "potId": "<Pot existant>",
  "observedVersion": 4,
  "bindingToken": "<occurrence lue>",
  "payload": {
    "label": "Transport",
    "date": "2026-11-15",
    "amount": { "numerator": "10", "denominator": "3" },
    "payerId": "<Shareholder existant>",
    "participants": [{ "id": "<Shareholder existant>", "weight": null }]
  }
}
```

Pour AddShareholders, conserver targets[] et ses valeurs name/attachment/weight,
retirer seulement l'ID nouveau. Le résultat de lot pourrait fournir :

```json
{
  "requestId": "<demande admise>",
  "operation": "AddShareholders",
  "potId": "<Pot existant>",
  "outcome": "COMPLETED",
  "effect": "CHANGED",
  "resultingPotVersion": 5,
  "rejection": null,
  "createdResources": [
    { "kind": "Shareholder", "id": "<ID attribué>", "inputIndex": 0 }
  ]
}
```

Le refus aurait outcome REJECTED, rejection renseigné, version nulle et aucune
ressource **créée** déclarée ; un ID réservé par admission mais non créé ne
doit pas apparaître comme création réussie. Pour CreatePot, potId suffit déjà
à découvrir le Pot : pas d'obligation de dupliquer le même ID dans une liste.
Pour CreateInvitation, maintenir le lien hors RESULT et sa découverte par Q.

### 8.3 Enchaînement client et retries

Le client conserve la nouvelle intention et sa clé avant le POST. Au 202,
il conserve Q/Location, puis consulte le RESULT sous la même EUI avec polling
borné. Au succès, il récupère l'ID attribué et V, attend P@V et conserve les
références nécessaires. Une commande suivante sur un Pot existant porte sa
version observée et une **nouvelle** clé. Les déclarations de bénéficiaire SELF
restent déduites de l'acteur, sans recherche d'un UserId interne.

Après perte réseau, conserver **l'ancienne** clé et **l'ancienne** intention,
sans changer version/token/ordre des targets ; retrouver Q puis le même
résultat. Après REJECTED métier, une intention corrigée est une nouvelle
demande avec nouvelle clé. Après Unlink, les résultats historiques restent
accessibles par EUI ; le binding courant doit être relu pour de futures
intentions. En cas de 404 prolongé, déclarer une attente/issue inconnue et
conserver la corrélation, pas fabriquer un résultat ni créer une copie.

## 9. Impacts techniques et compatibilité

| Changement proposé | Contrat public | Implémentation | Projection | Tests | Bruno | Documentation |
| --- | --- | --- | --- | --- | --- | --- |
| IDs serveur des trois créations | Retirer les IDs nouveaux de la nouvelle forme ; références existantes inchangées | Séparer input canonique et commande identifiée, générer/persister atomiquement | Extension de résultats selon CR-04 | Admission/replay/worker/compatibilité | Retirer préallocation, capturer résultats | Allocateur, attente et retries |
| Ressources créées dans RC | Références typées et mapping lot | Étendre résultat WRITE exact et SQL CURRENT | Source, schéma READ, égalité, reconstruction | Cohérence exacte et anciens résultats | Propager IDs sans inférence READ | Schéma et corrélation de lot |
| Effet CHANGED/UNCHANGED | Champ additif ou version publique choisie | Exploiter writeOutcome déjà disponible | Aucune nouvelle donnée pour la distinction seule | HTTP + no-op concurrent | Assertions d'effet | Sens de COMPLETED et de l'effet |
| Contexte invitation suffisant | potId dans réponse places, si approuvé | Exposer une donnée déjà disponible dans la façade READ | Pas de nouveau stockage avec cette option | Confidentialité, parcours isolé, concurrence existante | Capturer potId côté guest depuis réponse | Prérequis lien seul / nouvelle allowlist |
| Observations zéro omises | Nouvelle forme spécifique par intention | Adaptation vers représentation interne existante | Aucune | Décodage/replay versions | Retirer sentinelles de nouvelle forme | Observations métier vs conventions |
| Conventions de noms | Mapping documenté d'abord ; renommage éventuel versionné | Aucune si documentation seule | Aucune si documentation seule | Contrats de noms si changement | Adapter aux noms retenus | Tableau entrée/sortie |
| Clarification de Send/retry | Aucun | Aucune application | Aucune | Tests helpers GUI/CLI | Distinguer nouvelle demande et retry | Exemples explicites |

Ne pas modifier rétroactivement la représentation persistée des anciennes
commandes ni les empreintes S-03. Les commandes admises restent exécutables et
leurs résultats historiques relisibles. Une forme publique nouvelle exige une
stratégie explicite : version d'API/contrat, coexistence de décodeurs ou fenêtre
de transition approuvée. Accepter silencieusement deux formes sans leur donner
une sémantique de replay non ambiguë serait dangereux. La version canonique
actuelle n'accepte que 1 ; toute extension nécessite compatibilité de comparaison
et conservation des clés anciennes. [C-IN] lignes 31–34 ; [HMAC] lignes 64–78.

L'ajout de createdResources est un changement de données de résultat et peut
nécessiter migrations CURRENT WRITE/READ et compatibilité de reconstruction.
L'ajout de l'effet utilise déjà une donnée conservée et ne doit pas déclencher
la même campagne SQL sans raison. Les données métier et IDs existants ne
nécessitent aucune réattribution. Ne pas renommer requestId en commandId pour
des raisons esthétiques ni réintroduire Link dans ce lot.

## 10. Plan de correction priorisé et vérification future

| Ordre | Lot proposé et condition d'entrée | Sortie attendue | Vérification à déclarer avant implémentation |
| --- | --- | --- | --- |
| 0 | Décision humaine sur A/B/C, compatibilité et confidentialité des places | Contrat cible approuvé ; aucune implémentation implicite par ce rapport | Documentation statique, slices NONE |
| 1 | CR-02 : parcours invité depuis le lien seul | Données publiques suffisantes, sans contexte partagé owner/guest | WEB primaire pour l'option READ minimale ; proofs d'interaction/concurrence Invitation existantes à préserver ; ajouter COMMAND/PROJECTION seulement si effets réellement changés |
| 2 | CR-03 : effet du succès | CHANGED et UNCHANGED distinguables dans HTTP sans casser outcome | WEB primaire ; donnée déjà disponible, pas de migration injustifiée |
| 3 | CR-01 + CR-04 atomiquement du point de vue du contrat | IDs serveur et résultats permettant de continuer tous les parcours | WEB + COMMAND + PROJECTION ; preuves réelles PG d'admission/rollback/commit ambigu/recovery/publication/reconstruction et compatibilité |
| 4 | CR-07 et mise à jour des scénarios après contrat approuvé | Bruno représente le nouveau parcours, retry distinct et contextes clients séparés | Tests de l'outillage modifié et parcours autorisés sous nouveau mandat |
| 5 | CR-05/06, si inclus dans la nouvelle version | Formes et conventions simplifiées | WEB si code/DTO changés ; NONE si mapping documentaire seul |

Commandes canoniques de référence pour ces **futures** tranches, à lancer depuis
`app/` seulement sous un nouveau mandat d'implémentation/vérification :

```sh
./mvnw -pl runtime-web-write,runtime-web-read -am test
./mvnw -pl runtime-worker-process -am test
./mvnw -pl runtime-worker-materialize -am test
```

Composer les ancres réellement affectées selon la politique au lieu de lancer
un réacteur complet par défaut. Aucun besoin FOUNDATION établi pour ces
propositions tant que le propriétaire des transactions ne change pas. Le gate
global architecture n'est requis que si ses déclencheurs réels sont atteints
(frontière, dépendance, module, privilèges/isolement architectural modifiés),
pas pour le seul nombre de contrats lus. Une clôture de lot majeure ou une
intégration globale conserve ses propres obligations ; ce rapport ne les
dispense pas. Une éventuelle migration sera vérifiée dans son périmètre CURRENT,
sans rejouer par défaut toute l'histoire et sans présumer un baseline TRUSTED.
[POLICY] §§4, 14–19, 22–27, 34–35.

## 11. Conséquences pour L7-LB, Bruno et L8

### 11.1 Lecture des collections Bruno

Le corpus interactif de référence est `scripts/bruno-playground`. L'inventaire
statique trouve les quinze opérations publiques dans ses corps explicites,
y compris leurs copies de parcours. Le comptage de **189 fichiers .bru** inclut
les fichiers de dossiers/environnements : ce n'est pas un nombre de requêtes
exécutées. Le guide annonce 171 requêtes visibles ; ce chiffre historique
n'est pas une preuve d'exécution acquise par cet audit. [B-GUIDE] lignes 8–12.

| Élément | Pratique observée | Conclusion contractuelle |
| --- | --- | --- |
| UUID artificiels | potId, shareholderOwnerId, shareholderGuestId et expenseId générés ensemble avant CreatePot | Ce sont des IDs métier choisis par le client, pas des résultats Pocoma ; CR-01 |
| Valeurs codées en dur | « Voyage Bruno », « Owner/Guest », « Transport », date 2026-11-14, fractions 1/1 et 10/3 | Exemples synthétiques légitimes, pas des états calculés imposés à un utilisateur ; ne pas les copier comme valeurs produit |
| Expiration | Date.now + invitationTtlSeconds, défaut 600 | Valeur souhaitée calculée client ; l'API exige expiresAt, pas un secret calculé ; choix ergonomique possible plus tard |
| Version Pot | Défaut 0 puis resultingPotVersion lu par R01 ; ajout local du garde avant mutations | Bonne séparation 202/résultat ; modifications locales utiles mais hors correction de cet audit |
| Binding / identité | /me/binding et session réelle ; actorSubject issu de session | Connaissances clientes légitimes, aucun UserId à deviner |
| Expense / shareholders | IDs de scénario, puis références READ et version ; sélection de la première place libre | Permet la recette, mais la sélection automatique n'est pas une décision utilisateur universelle |
| Invitation | Lien récupéré privé par owner ; token extrait ; guest utilise le même potId global | Contournement de la connaissance manquante par partage de contexte ; CR-02/07 |
| Idempotence | Clé fraîche pour une nouvelle admission ; A04 réutilise snapshot/clé ; A05 modifie label pour provoquer conflit | Les scénarios explicites couvrent replay/conflit ; un Send ordinaire n'est pas un retry réseau |
| Asynchronisme | GET du même endpoint répété jusqu'à 30 s ; résultats et READ séparés | Conforme au headless asynchrone ; limite d'attente = limite cliente, pas échec métier terminal |

Preuves : [B-WORK] lignes 6–9, 34–108, 138–213 ; [B-POT] lignes 19–37 ;
[B-SHARE] lignes 19–47 ; [B-EXPENSE] lignes 19–44 ; [B-INV] lignes 19–37 ;
[B-ACCEPT] lignes 19–37 ; [B-PLACES] lignes 19–45 ; [B-UNLINK] lignes 19–35.

`scripts/bruno-v3` est un banc spécialisé avec fixtures et scripts de
qualification ; ses appels secondaires et observations SQL ne sont pas une
preuve de parcours Web naturel. Son README distingue déjà cette finalité et
annonce la génération des IDs de scénario. `scripts/bruno` est historique.
Ne pas ramener leurs endpoints/types retirés dans l'inventaire courant, ni
exécuter ces bancs dans ce mandat statique. [B-QUAL] lignes 1–23 ; [B-README]
lignes 3–6.

### 11.2 À corriger avant validation définitive des parcours

La qualification technique du playground et la correction de reprise L7-LB
restent des observations de leurs campagnes d'origine. Les présentes
incohérences ne prouvent pas une régression du mécanisme de reprise.

En revanche, la validation **contractuelle définitive des parcours clients**
doit attendre : décision puis résolution du lien seul CR-02 ; décision d'effet
CR-03 ; décision sur l'allocation des trois familles et restitution complète
CR-01/04 si le principe demandé est retenu ; adaptation/documentation Bruno
CR-07 après ce choix. Une validation provisoire du contrat historique à IDs
clients serait une **exception humaine explicite**, pas un constat de conformité
obtenu par le succès des recettes.

### 11.3 Ce qui peut attendre L8 ou un lot ultérieur

Le renommage généralisé des champs/opérations/rationnels, une facilité
self-Unlink, le retrait des sentinelles zéro s'il complique la compatibilité,
l'exposition précoce des IDs au 202, les besoins offline/import et un éventuel
statut technique public peuvent attendre. La politique de rétention/restauration
et les migrations de formats doivent être décidées avant d'annoncer leurs
garanties en L8. Le retrait des tables historiques Link relève du chantier
L8 déjà réservé, sans réactivation de l'ancien use case. [LINK-DEC] ligne 18.

## 12. Réserves et décisions humaines nécessaires

| Décision / réserve | Proposition de cet audit | Limite ou alternative |
| --- | --- | --- |
| Allocateur des nouveaux IDs | A pour Pot/Expense/Shareholders ; B conservé pour Invitation/Identity | Besoin offline/import non établi ; C seulement avec justification et exception explicite |
| Compatibilité publique et idempotence | Version explicite, conservation des anciens formats et fingerprints | Pas de suppression immédiate des champs ni de comparaison rétroactive nouvelle |
| Suffisance du lien d'invitation | potId dans réponse places après contrôle | Élargit une allowlist de confidentialité aujourd'hui testée ; alternative résolution serveur au traitement |
| Distinction d'effet | Garder outcome, ajouter effet public | Noms exacts et traitement Invitation à approuver ; aucune nécessité d'unifier artificiellement les versions métier |
| Batch Shareholder | Mapping stable par index de la liste d'entrée | clientRef éventuel à discuter seulement s'il résout un vrai besoin ; pas d'UUID métier déguisé |
| Résultats historiques sans createdResources | Maintenir les lectures existantes, définir extension/version | Toute reconstruction doit être exacte ; ne pas inférer des créations à partir de l'état courant |
| Erreurs techniques non terminales | Conserver confidentialité, handle et procédures d'exploitation | Pas de FAILED métier inventé ; éventuel statut séparé hors lot minimal |
| État déployé et campagnes | Sources au SHA indiqué et dix changements locaux lus | Aucun JAR, environnement, HTTP réel, Maven ou Docker vérifié pendant cet audit |

**Écart bloquant explicite : CR-02 pour le parcours depuis le seul lien actuel.**
Les IDs clients sont P1, car générables ; le RESULT sans distinction d'effet
est P1. CR-04 devient une condition de livraison de la génération serveur,
pas la preuve d'un blocage actuel du client qui a lui-même fourni les IDs.

L'audit statique est complet pour les quinze intentions publiques trouvées
dans les sources courantes, y compris leur chaîne de résultat et les
consultations nécessaires. Il ne revendique aucune preuve dynamique nouvelle.
**Aucune implémentation ne doit être déduite de ce rapport : attendre la
décision humaine sur le contrat cible.**

## Annexe A — Vérification documentaire et préservation

Politique normative lue : `docs/testing/Reactor_Verification_Policy.md`.
Déclaration faite avant production du rapport :

| Élément | Déclaration / résultat |
| --- | --- |
| Modification autorisée | Uniquement ce nouveau rapport |
| Impact de production attendu / observé | Aucun |
| Slices de vérification Maven déclarées | **NONE** : documentation/analyse statique, politique §34 |
| Capacités examinées | WEB, COMMAND, PROJECTION ; examen transversal sans modification de ces capacités |
| Vérification | Inventaire sources, références de fichiers/lignes, comparaison hash des dix fichiers initiaux et du diff Git |
| Franchissement inattendu de slice | Aucun |
| Gate global architecture | Non requis, non exécuté |
| Réacteur complet | Non requis, non exécuté : aucune implémentation ni clôture d'intégration |
| Base / migrations | Aucune connexion, aucune migration exécutée ; lecture SQL seulement |
| Maven / Docker / commandes métier | Aucune exécution |
| Commit / push par cet audit | Aucun ; un commit concurrent des dix fichiers initiaux a été observé, voir ci-dessous |
| Certification | NOT RUN ; aucune réutilisation de rapports de tests comme preuve fraîche |

Commandes réellement exécutées, regroupées par finalité :

```sh
pwd
git status --short
git branch --show-current
git rev-parse HEAD
git diff --stat
rg --files -g 'AGENTS.md' -g 'Reactor_Verification_Policy.md' -g '!node_modules' -g '!target'
rg -n '@(Post|Put|Patch|Delete|Request|Get)Mapping|RouterFunction|route\(' app --glob '*.java' --glob '!**/test/**'
```

Lectures complémentaires par `cat`, `nl -ba`, `sed -n`, `rg --files` et `rg -n`
sur les sources, politiques, tests, SQL et collections référencés ci-dessous ;
aucun des scripts de qualification lus n'a été lancé. Scripts Python locaux
exécutés seulement pour : (1) inventorier les opérations écrites dans les
corps .bru ; (2) calculer SHA-256 des fichiers initiaux et du `git diff --binary` ;
(3) contrôler liens/lignes, inventaire des quinze intentions et préservation
finale. Ils ne chargent aucun runtime applicatif ni base de données.

Les dix fichiers initiaux sont :

```text
docs/v3/L7_Local_Playground_Bruno.md
scripts/bruno-playground/02 - Pots/P01 Create Pot.bru
scripts/bruno-playground/02 - Pots/P02 Update Pot Details.bru
scripts/bruno-playground/90 - End-to-End Journeys/J01 Complete Ledger and Invitation/08 P01 Create Pot.bru
scripts/bruno-playground/90 - End-to-End Journeys/J01 Complete Ledger and Invitation/59 P02 Update Pot Details.bru
scripts/bruno-playground/90 - End-to-End Journeys/J02 Errors Privacy and Idempotency/08 P01 Create Pot.bru
scripts/bruno-playground/90 - End-to-End Journeys/J03 Unlink Re-register and Historical Ownership/08 P01 Create Pot.bru
scripts/bruno-playground/README.md
scripts/bruno-playground/lib/workspace.js
scripts/local/test_bruno_gui.js
```

Empreinte SHA-256 initiale de `git diff --binary` :
`547a2de7e7b524af83975bcb643f39d0b784beddf05605879dc3630356770c31`.

Pendant l'audit, HEAD a avancé vers `60b12fc3` (`fix(bruno): require positive Pot
version before mutation`). Ce commit, effectué hors des actions de l'audit,
enregistre les dix fichiers initiaux : leur absence finale de `git diff` ne
signifie pas perte des modifications. Les dix contenus sont identiques octet
pour octet à leurs empreintes initiales ; le diff entre la référence et le
HEAD final permet de vérifier la même empreinte de patch. Aucun fichier sous
app/ ne change entre ces deux SHA. Le seul ajout de cette mission est ce rapport.

Contrôle final : quinze lignes de commande inventoriées et soixante-huit
références de fichiers/lignes valides ; dix empreintes initiales conservées ;
aucun espace final signalé par le contrôle documentaire. Ce sont des contrôles
statiques, pas des résultats de tests applicatifs.

## Annexe B — Référentiel des preuves

Les liens pointent vers le fichier et sa première ligne pertinente ; les
paragraphes/matrices indiquent les intervalles précis. Tous les tests ci-dessous
sont des **oracles source examinés, non exécutés**.

[HTTP-W]: ../../app/supra-web-write/src/main/java/com/kartaguez/pocoma/supra/web/write/v3/V3AdmissionController.java#L41
[C-IN]: ../../app/contract-write-admission/src/main/java/com/kartaguez/pocoma/contract/writeadmission/CommandAdmissionInput.java#L56
[I-IN]: ../../app/contract-write-admission/src/main/java/com/kartaguez/pocoma/contract/writeadmission/IdentityAdmissionInput.java#L33
[C-REQ]: ../../app/contract-write-admission/src/main/java/com/kartaguez/pocoma/contract/writeadmission/PotCommand.java#L12
[I-REQ]: ../../app/contract-write-admission/src/main/java/com/kartaguez/pocoma/contract/writeadmission/IdentityRequest.java#L15
[FIELDS]: ../../app/contract-write-admission/src/main/java/com/kartaguez/pocoma/contract/writeadmission/AdmissionFields.java#L13
[ADMIT-C]: ../../app/engine-admit-command/src/main/java/com/kartaguez/pocoma/engine/admit/command/v3/AdmitCommandV3.java#L41
[ADMIT-I]: ../../app/engine-admit-identity/src/main/java/com/kartaguez/pocoma/engine/admit/identity/v3/AdmitIdentityRequestV3.java#L41
[ADMIT-DB]: ../../app/infra-postgres-write/src/main/java/com/kartaguez/pocoma/infra/postgres/write/v3/JdbcAdmissionStore.java#L31
[HMAC]: ../../app/infra-postgres-write/src/main/java/com/kartaguez/pocoma/infra/postgres/write/v3/HmacAdmissionProtection.java#L44
[ADMISSION-DDL]: ../../app/infra-postgres-write/src/main/resources/db/v3-admission/migration/V7__public_admission.sql#L2
[EXEC-C]: ../../app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/v3/ExecutePotCommand.java#L68
[C-STORE]: ../../app/infra-postgres-write/src/main/java/com/kartaguez/pocoma/infra/postgres/write/v3/JdbcPotCommandStore.java#L60
[C-CODEC]: ../../app/infra-postgres-write/src/main/java/com/kartaguez/pocoma/infra/postgres/write/v3/PotCommandJson.java#L22
[P-DOM]: ../../app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/v3/PotTransitions.java#L10
[S-DOM]: ../../app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/v3/ShareholderTransitions.java#L10
[X-DOM]: ../../app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/v3/ExpenseTransitions.java#L14
[CHECKS]: ../../app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/v3/TransitionChecks.java#L10
[P-RESULT]: ../../app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/v3/TransitionResult.java#L7
[FIN]: ../../app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/v3/ExpenseFinancials.java#L9
[AMOUNT]: ../../app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/v3/Amount.java#L5
[WEIGHT]: ../../app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/v3/Weight.java#L5
[INV-W]: ../../app/infra-postgres-write/src/main/java/com/kartaguez/pocoma/infra/postgres/write/v3/JdbcInvitationStore.java#L28
[INV-CRYPTO]: ../../app/infra-security-spring/src/main/java/com/kartaguez/pocoma/infra/security/spring/v3/InvitationCryptography.java#L33
[INV-DDL]: ../../app/infra-postgres-write/src/main/resources/db/v3-invitation/migration/V10__invitation_history.sql#L7
[ATTACH-DOM]: ../../app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/v3/AttachShareholderViaInvitation.java#L19
[EXEC-I]: ../../app/engine-write-identity/src/main/java/com/kartaguez/pocoma/engine/write/identity/v3/ExecuteIdentityRequest.java#L39
[I-DOM]: ../../app/domain-identity/src/main/java/com/kartaguez/pocoma/domain/identity/v3/IdentityTransitions.java#L23
[I-RESULT]: ../../app/domain-identity/src/main/java/com/kartaguez/pocoma/domain/identity/v3/IdentityTransitionResult.java#L26
[AUTH-I]: ../../app/infra-postgres-write/src/main/java/com/kartaguez/pocoma/infra/postgres/write/v3/identity/JdbcIdentityAuthority.java#L39
[HTTP-R]: ../../app/supra-web-read/src/main/java/com/kartaguez/pocoma/supra/web/read/v3/V3ReadController.java#L32
[DTO-R]: ../../app/supra-web-read/src/main/java/com/kartaguez/pocoma/supra/web/read/v3/V3ReadDtos.java#L10
[HTTP-INV]: ../../app/supra-web-read/src/main/java/com/kartaguez/pocoma/supra/web/read/v3/V3InvitationController.java#L25
[INV-READ]: ../../app/engine-read-pot/src/main/java/com/kartaguez/pocoma/engine/read/pot/v3/ReadInvitation.java#L49
[MODEL-RC]: ../../app/domain-projection/src/main/java/com/kartaguez/pocoma/domain/projection/v3/CommandTerminalResult.java#L8
[MODEL-RI]: ../../app/domain-projection/src/main/java/com/kartaguez/pocoma/domain/projection/v3/IdentityTerminalResult.java#L6
[GET-RC]: ../../app/engine-read-results/src/main/java/com/kartaguez/pocoma/engine/read/results/v3/GetCommandTerminalResult.java#L12
[GET-RI]: ../../app/engine-read-results/src/main/java/com/kartaguez/pocoma/engine/read/results/v3/GetIdentityTerminalResult.java#L12
[SRC-RC]: ../../app/infra-postgres-write-source-read/src/main/java/com/kartaguez/pocoma/infra/postgres/source/v3/JdbcCommandTerminalSource.java#L20
[SRC-RI]: ../../app/infra-postgres-write-source-read/src/main/java/com/kartaguez/pocoma/infra/postgres/source/v3/identity/JdbcIdentityTerminalSource.java#L17
[MAT-RC]: ../../app/engine-materialize-results/src/main/java/com/kartaguez/pocoma/engine/materialize/results/v3/MaterializeCommandResult.java#L10
[DB-RC]: ../../app/infra-postgres-read/src/main/java/com/kartaguez/pocoma/infra/postgres/read/v3/JdbcCommandTerminalResultStore.java#L11
[ACL-SRC]: ../../scripts/v3/grant-l7-write-source-read.sql#L14
[ERR-W]: ../../app/supra-web-write/src/main/java/com/kartaguez/pocoma/supra/web/write/v3/V3AdmissionTransport.java#L148
[ERR-R]: ../../app/supra-web-read/src/main/java/com/kartaguez/pocoma/supra/web/read/v3/V3ReadTransport.java#L134
[T-IN]: ../../app/compose-web-write-postgres/src/test/java/com/kartaguez/pocoma/engine/admit/command/v3/V3AdmissionInputTest.java#L31
[T-I-IN]: ../../app/engine-admit-identity/src/test/java/com/kartaguez/pocoma/engine/admit/identity/v3/V3IdentityAdmissionInputTest.java#L18
[T-CANON]: ../../app/compose-web-write-postgres/src/test/java/com/kartaguez/pocoma/engine/admit/command/v3/V3AdmissionCanonicalizationTest.java#L169
[T-ADMIT]: ../../app/infra-postgres-write/src/test/java/com/kartaguez/pocoma/infra/postgres/write/v3/V3AdmissionPostgresTest.java#L247
[T-RC]: ../../app/engine-materialize-results/src/test/java/com/kartaguez/pocoma/engine/materialize/results/v3/CommandResultV3ContractTest.java#L8
[T-READ]: ../../app/runtime-web-read/src/test/java/com/kartaguez/pocoma/V3ReadHttpContractTest.java#L78
[T-INV]: ../../app/runtime-monolith/src/test/java/com/kartaguez/pocoma/V3InvitationHttpTest.java#L215
[T-INV-FIX]: ../../app/runtime-monolith/src/test/java/com/kartaguez/pocoma/V3InvitationFixture.java#L186
[T-LINK]: ../../app/runtime-web-read/src/test/java/com/kartaguez/pocoma/V3LinkRetirementHttpTest.java#L20
[LINK-DEC]: L7_I2_Link_Retirement.md#L3
[SPEC-06]: SPEC-06_Supra_Contracts.md#L37
[POLICY]: ../testing/Reactor_Verification_Policy.md#L913
[B-WORK]: ../../scripts/bruno-playground/lib/workspace.js#L34
[B-POT]: <../../scripts/bruno-playground/02 - Pots/P01 Create Pot.bru#L19>
[B-SHARE]: <../../scripts/bruno-playground/03 - Shareholders/S01 Add Owner and Free Guest.bru#L19>
[B-EXPENSE]: <../../scripts/bruno-playground/04 - Expenses/X01 Create Expense.bru#L19>
[B-INV]: <../../scripts/bruno-playground/08 - Invitations/V01 Create Invitation.bru#L19>
[B-ACCEPT]: <../../scripts/bruno-playground/08 - Invitations/V04 Guest Accept Invitation.bru#L19>
[B-PLACES]: <../../scripts/bruno-playground/08 - Invitations/V03 Guest Available Shareholders.bru#L19>
[B-UNLINK]: <../../scripts/bruno-playground/01 - Identity and Registration/I07 Unlink Guest Self.bru#L19>
[B-GUIDE]: L7_Local_Playground_Bruno.md#L8
[B-README]: ../../scripts/bruno-playground/README.md#L3
[B-QUAL]: ../../scripts/bruno-v3/README.md#L1
