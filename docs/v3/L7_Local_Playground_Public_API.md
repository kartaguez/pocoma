# Pocoma V3 — contrat public consommateur playground v1

API `/api/v3`. Ce document et la collection `scripts/bruno-playground` décrivent
un consommateur BFF serveur, sans accès SQL ni endpoint d'administration.
La version est celle du SHA livré ; aucune stabilité illimitée des versions
futures n'est promise. Obtenir **WRITE_URL**, **READ_URL**, **ISSUER** dans la
sortie `pocoma-local start` ; ne pas supposer le même port WRITE et READ.

## Identité, confidentialité et cohérence

OIDC discovery : `ISSUER/.well-known/openid-configuration`. Client public
`pocoma-functional-tests`, redirect `http://127.0.0.1:8765/callback`, scopes
`openid`, Authorization Code + PKCE S256, state vérifié. Le bearer doit porter
issuer exact, audience `pocoma-api`, sujet et dates valides. Le playground
n'exige pas de permission produit additionnelle au scope openid ; ne pas
inventer de mapping scope → droit métier. Les autorisations dépendent de
l'identité liée, du Pot et de la version. Le client ne choisit jamais son
requester/subject/userId pour une Registration.

Conserver bearer, binding et invitation exclusivement côté serveur/session
protégée du BFF ; ne pas les journaliser, mettre en URL d'API ou en stockage
navigateur persistant. Un BFF web doit protéger ses cookies, sa callback et les
mutations contre CSRF. Les services API livrés sont des ressources OAuth ; la
configuration CORS d'une SPA n'est pas fournie par ce playground.

`GET READ_URL/api/v3/me/binding` retourne 404 si inconnu, sinon un objet avec
`active`, `euiVersion`, et `bindingToken` pour une occurrence active. Si inconnu :
POST Registration, poller son résultat puis relire le binding jusqu'à sa
`targetVersion`. Registration déjà liée peut être REJECTED/TARGET_ALREADY_BOUND.
Un token de binding obsolète ne devient pas valide parce que le JWT est neuf.
Les consultations métier portent `X-Pocoma-Binding-Token`; les commandes portent
`bindingToken` dans le JSON. Ne pas envoyer le binding d'un autre utilisateur.

Toutes les admissions ont `Authorization: Bearer <access_token>`,
`Content-Type: application/json`, `Idempotency-Key: <clé unique par intention>`.
Réutiliser **la même clé et le même corps** pour reprendre une admission dont
la réponse a été perdue ; changer l'intention sous cette clé produit 409.
La clé est scoped par identité et ne confère aucun droit sur un résultat tiers.
Une réponse 202 contient seulement `{"requestId":"UUID"}` et Location résultat.
Elle prouve l'admission durable, jamais l'exécution métier ni la disponibilité READ.

## Routes publiques

Tous les chemins ci-dessous sont préfixés `/api/v3`.

| Hôte / méthode / route | Entrée et sortie utiles |
|---|---|
| WRITE POST `/identity/requests` | `{"operation":"Registration"}` ou Unlink ci-dessous ; 202 requestId |
| WRITE POST `/commands` | Enveloppe Command ci-dessous ; 202 requestId |
| READ GET `/identity/requests/{id}/result` | `outcome`, `operation`, `targetVersion`, `rejection` selon résultat |
| READ GET `/commands/{id}/result` | `outcome`, `resultingPotVersion`, `rejection` selon résultat |
| READ GET `/me/binding` | Occurrence propre ; aucun sélecteur d'identité tiers |
| READ GET `/pots/{id}?minVersion=V` | Pot complet à une version courante W≥V, `potVersion`, `label`, `shareholders`, `expenses`, `balances` |
| READ GET `/pots/{id}/versions/{V}` | Histoire exacte V, jamais un substitut courant |
| READ GET `/pots/{id}/versions/{V}/balance` | `potId`, `potVersion`, `balances` exacts de V |
| READ POST `/pots/{id}/versions/{V}/expenses/query` | `{"references":[<référence Expense lue sur ce Pot@V>]}` ; `expenses` avec `reference`, détails et montant |
| READ GET `/me/pots?limit=N&cursor=OPAQUE` | `potIds`, `nextCursor` ; pagination indépendante de la disponibilité de lecture directe |
| READ GET `/invitations/by-creation-request/{id}` | Accessible au créateur seulement, retourne `link` une fois matérialisé ; pas de secret dans le résultat Command |
| READ POST `/invitations/available-shareholders/query` | `{"token":"<secret extrait du lien>"}` et header binding ; `shareholders:[{shareholderId,name}]` uniquement |

Les références Expense et curseurs se recopient comme objets/valeurs opaques ;
ne pas les reconstruire à partir d'un schéma interne. Batch Expense 1–50,
page navigation défaut 20/max100. UUID canoniques, versions entières positives
(sauf observation 0 explicitement admise). Le JSON est strict : champs inconnus,
identité forgée, mauvais media type et enveloppes hors limites sont refusés.
Corps admission max262144 octets dans le profil courant. Les chaînes/rationnels
sont également bornés par le profil public ; un 400 n'autorise pas un retry
infini avec le même corps. Aucun flottant pour les montants.

## Commandes et payloads

Enveloppe commune :

```json
{"operation":"CreatePot","potId":"UUID généré par le client","observedVersion":0,"bindingToken":"BINDING_PRIVE","payload":{"label":"Voyage"}}
```

| Operation | Payload (clés exactes) |
|---|---|
| CreatePot | `{label}` ; observation 0, UUID Pot nouveau |
| UpdatePotDetails | `{label}` ; version courante observée |
| DeletePot | `{}` ; version courante observée |
| AddShareholders | `{targets:[{id,name,attachment,weight}]}` ; attachment `FREE` ou `SELF` |
| UpdateShareholderDetails | `{targets:[{id,name,attachment}]}` ; `KEEP`, `SELF`, `DETACH` |
| UpdateShareholderWeights | `{targets:[{id,weight}]}` |
| DeleteShareholder | `{id}` |
| CreateExpense | `{id,label,date,amount,payerId,participants:[{id,weight}]}` |
| UpdateExpenseDetails | `{id,label,date}` |
| UpdateExpenseFinancials | `{id,amount,payerId,participants:[{id,weight}]}` |
| DeleteExpense | `{id}` |
| CreateInvitation | `{expiresAt:"2026-11-14T12:00:00Z"}` ; observation0, instant UTC suffixe Z, précision ≤microseconde, strictement futur à l'exécution |
| AttachShareholderViaInvitation | `{invitationToken,shareholderId}` ; observation0, secret obtenu via lien `pocoma://invitation/<secret>` |

Rationnels en entrée : `{"numerator":"10","denominator":"3"}` ; poids strictement
positifs, participant weight `null` pour le poids par défaut. Dates Expense
`YYYY-MM-DD`. Rationnels en sortie : `{"n":"10","d":"3"}`. Les soldes positifs
sont créanciers ; somme exacte zéro. Exemples exécutables dans PG01 : deux parts
égales, payeur10/3 → soldes +5/3,−5/3 ; correction20/3 → +10/3,−10/3.

Unlink se soumet sur `/identity/requests` :

```json
{"operation":"Unlink","bindingToken":"BINDING_PRIVE","target":{"issuer":"ISSUER","subject":"SUBJECT"}}
```

Unlink ne permet pas de révoquer une identité étrangère sans autorité ; le
binding et l'autorité sont vérifiés à l'exécution. Les anciennes routes Link
sont retirées et ne doivent pas être utilisées. CreateInvitation est une
Command Pot, pas une opération IdentityRequest. Ce guide ne rajoute aucun
endpoint de raccourci ou de découverte d'identité étrangère.

## Polling, erreurs et suppression

Résultats terminaux : `COMPLETED` ou `REJECTED`, raison métier explicite.
Une rejection n'est pas un échec de transport. Pour une mutation appliquée,
attendre le résultat, récupérer V puis lire courant `minVersion=V`; contrôler
le W retourné et lire balance/histoire à **ce même W**. Une course avec un autre
writer peut produire W>V. L'histoire demandée à V reste exacte V. Les mutations
ordinaires se soumettent à la dernière version observée ; sur conflit métier,
relire et demander une nouvelle décision utilisateur avant une nouvelle intention.

Le playground applique polling borné30s, intervalle100ms ; le BFF peut utiliser
backoff100ms→1s avec budget30s et interruption utilisateur. Ce budget est un
choix client, pas une garantie SPEC de convergence. Un timeout demeure une issue
indéterminée à diagnostiquer avec requestId ; ne pas dupliquer l'intention.

| Statut | Sens consommateur |
|---|---|
| 400 | JSON/valeur/curseur/référence invalide, champs inconnus |
| 401 | JWT absent/invalide/expiré ou mauvais issuer/audience |
| 404 | Inconnu ou masqué ; résultat pas encore matérialisé, accès tiers et absence restent indiscernables |
| 409 | Clé d'idempotence réutilisée avec intention différente |
| 413 / 415 | Taille / type de contenu refusés |
| 503 | Capacité/indisponibilité ou maintenance/retard autorisé selon route ; conserver requestId et borne de retry |

Les erreurs ont `{code,message,traceId}` ; ne pas déduire l'existence d'un objet
à partir de leur message. Les réponses API portent `Cache-Control: no-store`.
Après DeletePot à D **terminalement confirmé**, attendre courant404 et D404,
puis vérifier l'histoire V<D autorisée ; un 404 seul n'est pas une preuve de
suppression. Les résultats sont liés à l'identité historique du requester,
pas rendus publics par un binding partagé ou révoqué.

La collection démontre un sous-ensemble représentatif, pas une certification
exhaustive de toutes les variantes publiques. Les limites non qualifiées et
les verdicts historiques restent dans le [registre](L7_Local_Playground_Reservations.md).
