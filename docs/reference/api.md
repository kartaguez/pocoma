# Référence API publique (`/api/v3`)

L'API publique de Pocoma est exposée sous `/api/v3` par deux runtimes web distincts :
- **`WRITE_URL`** (`http-write`, port interne `8080`) : admission des commandes et demandes d'identité (`POST`).
- **`READ_URL`** (`http-read`, port interne `8082`) : consultation des liaisons, résultats, cagnottes et invitations (`GET` / `POST`).

Les URL exactes d'une instance locale sont affichées par `./pocoma-local start` et `./pocoma-local status`.

## 1. En-têtes et authentification

| En-tête | Portée | Rôle |
| :--- | :--- | :--- |
| `Authorization: Bearer <token>` | Toutes les routes `/api/v3` | Jeton JWT OIDC émis par Keycloak (`audience=pocoma-api`, `scope=openid`) |
| `Content-Type: application/json` | Requêtes avec corps (`POST`) | JSON strict (champs inconnus refusés par `400`) |
| `Idempotency-Key: <clé>` | Toutes les admissions WRITE | Clé unique par intention ; même clé + même corps → même `requestId` ; corps différent → `409` |
| `X-Pocoma-Binding-Token: <token>` | Routes READ métier (cagnottes, soldes, dépenses, `/me/pots`, invitations) | Jeton de liaison actif obtenu via `GET /api/v3/me/binding` |

Toutes les réponses API portent `Cache-Control: no-store`.

## 2. Catalogue des endpoints

### Écriture (`WRITE_URL/api/v3`)

| Méthode & Route | Entrée | Réponse nominale |
| :--- | :--- | :--- |
| `POST /identity/requests` | `{"operation":"Registration"}` ou `{"operation":"Unlink","bindingToken":"...","target":{"issuer":"...","subject":"..."}}` | `202 Accepted` : `{"requestId":"<UUID>"}` + `Location` |
| `POST /commands` | Enveloppe `{"operation","potId","observedVersion","bindingToken","payload"}` | `202 Accepted` : `{"requestId":"<UUID>"}` + `Location` |

> [!NOTE]
> Une réponse `202 Accepted` confirme uniquement la persistance durable de l'admission dans `pocoma_write`, jamais son acceptation métier ni la disponibilité immédiate de la projection READ.

### Lecture (`READ_URL/api/v3`)

| Méthode & Route | Rôle | Réponse nominale |
| :--- | :--- | :--- |
| `GET /me/binding` | Consulte la liaison active de l'identité authentifiée | `200` `{active, euiVersion, bindingToken}` ou `404` si inconnu |
| `GET /identity/requests/{id}/result` | Résultat terminal d'une demande d'identité | `200` `{outcome, operation, targetVersion, rejection}` ou `404` |
| `GET /commands/{id}/result` | Résultat terminal d'une commande | `200` `{outcome, resultingPotVersion, rejection}` ou `404` |
| `GET /me/pots?limit=N&cursor=OPAQUE` | Liste paginée des cagnottes accessibles (`limit` défaut `20`, max `100`) | `200` `{potIds, nextCursor}` |
| `GET /pots/{id}?minVersion=V` | État courant complet d'un Pot à une version `W >= V` | `200` `{potVersion, label, shareholders, expenses, balances}` ou `404` |
| `GET /pots/{id}/versions/{V}` | État historique exact à la version `V` (sans substitution) | `200` `{potVersion, label, shareholders, expenses, balances}` ou `404` |
| `GET /pots/{id}/versions/{V}/balance` | Soldes exacts d'un Pot à la version `V` | `200` `{potId, potVersion, balances}` ou `404` |
| `POST /pots/{id}/versions/{V}/expenses/query` | Détail de `1` à `50` dépenses via `{"references":[...]}` (objets opaques lus sur le Pot) | `200` `{expenses:[...]}` ou `404` |
| `GET /invitations/by-creation-request/{id}` | Lien d'invitation (accessible uniquement au créateur) | `200` `{link}` (`pocoma://invitation/<secret>`) ou `404` |
| `POST /invitations/available-shareholders/query` | Places éligibles pour `{"token":"<secret>"}` (+ header binding) | `200` `{shareholders:[{shareholderId, name}]}` ou `404` |

## 3. Opérations métier (`POST /api/v3/commands`)

Enveloppe commune :

```json
{
  "operation": "CreatePot",
  "potId": "<UUID>",
  "observedVersion": 0,
  "bindingToken": "<BINDING_TOKEN>",
  "payload": {}
}
```

| Opération | `observedVersion` | Payload exact |
| :--- | :--- | :--- |
| `CreatePot` | `0` (nouvel UUID Pot) | `{"label": "..."}` |
| `UpdatePotDetails` | Version courante | `{"label": "..."}` |
| `DeletePot` | Version courante | `{}` |
| `AddShareholders` | Version courante | `{"targets": [{"id": "<UUID>", "name": "...", "attachment": "FREE\|SELF", "weight": {"numerator":"1","denominator":"1"}}]}` |
| `UpdateShareholderDetails` | Version courante | `{"targets": [{"id": "<UUID>", "name": "...", "attachment": "KEEP\|SELF\|DETACH"}]}` |
| `UpdateShareholderWeights` | Version courante | `{"targets": [{"id": "<UUID>", "weight": {"numerator":"...","denominator":"..."}}]}` |
| `DeleteShareholder` | Version courante | `{"id": "<UUID>"}` |
| `CreateExpense` | Version courante | `{"id": "<UUID>", "label": "...", "date": "YYYY-MM-DD", "amount": {"numerator":"...","denominator":"..."}, "payerId": "<UUID>", "participants": [{"id": "<UUID>", "weight": null\|{...}}]}` |
| `UpdateExpenseDetails` | Version courante | `{"id": "<UUID>", "label": "...", "date": "YYYY-MM-DD"}` |
| `UpdateExpenseFinancials` | Version courante | `{"id": "<UUID>", "amount": {...}, "payerId": "<UUID>", "participants": [{"id": "<UUID>", "weight": null\|{...}}]}` |
| `DeleteExpense` | Version courante | `{"id": "<UUID>"}` |
| `CreateInvitation` | `0` | `{"expiresAt": "YYYY-MM-DDTHH:MM:SSZ"}` (UTC suffixe `Z`, précision ≤ microseconde, strictement futur) |
| `AttachShareholderViaInvitation` | `0` | `{"invitationToken": "<secret>", "shareholderId": "<UUID>"}` |

### Rationnels et contraintes

- **Aucun flottant** : les montants et poids utilisent `{"numerator":"10","denominator":"3"}` en entrée et `{"n":"10","d":"3"}` en sortie READ. Les soldes positifs sont créanciers et leur somme est toujours nulle.
- **Bornes de requête** : corps d'admission max `262 144` octets ; batch d'expenses `1`–`50` ; pagination `/me/pots` défaut `20`, max `100`.

## 4. Codes HTTP et format d'erreur

Les erreurs suivent le format `{"code": "...", "message": "...", "traceId": "..."}`.

| Statut | Signification |
| :---: | :--- |
| `400` | JSON invalide, champs inconnus, valeurs/curseurs/références invalides |
| `401` | Jeton JWT absent, invalide, expiré, ou issuer/audience incorrects |
| `404` | Ressource absente, résultat pas encore matérialisé, ou accès non autorisé (indiscernables par confidentialité) |
| `409` | `Idempotency-Key` réutilisée avec un corps différent |
| `413` / `415` | Taille de requête ou `Content-Type` refusé |
| `503` | Capacité HTTP/SQL saturée ou maintenance READ active ; conserver le `requestId` en cas d'admission déjà envoyée |
