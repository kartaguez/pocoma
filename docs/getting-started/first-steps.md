# Premiers pas

Ce parcours autonome vous fait exécuter votre premier cycle complet sur Pocoma : **démarrer l'instance**, **vous authentifier**, **soumettre une commande métier**, **retrouver son résultat** et **lire la projection correspondante**.

> [!TIP]
> Pour explorer visuellement sans écrire de requêtes à la main, vous pouvez aussi lancer `./pocoma-local gui --instance demo` (Bruno GUI préconfiguré avec OIDC PKCE) ou `./pocoma-local bruno --instance demo` (collection automatisée). Voir [Développement local et Bruno](../guides/local-development.md).

## 1. Démarrer Pocoma et récupérer les URL locales

Démarrez l'instance `demo` et vérifiez qu'elle est `READY` :

```bash
./pocoma-local start --instance demo
./pocoma-local status --instance demo
```

Relevez dans la sortie les trois URL de votre instance (les ports hôtes sont attribués par instance) :
- `WRITE_URL` (ex. `http://127.0.0.1:<port_write>`)
- `READ_URL` (ex. `http://127.0.0.1:<port_read>`)
- `ISSUER` (ex. `http://localhost:<port_keycloak>/realms/pocoma`)

## 2. S'authentifier et obtenir son `bindingToken`

Pocoma utilise **Keycloak** (OIDC Authorization Code + PKCE S256, client public `pocoma-functional-tests`, scope `openid`). Les comptes de test locaux sont `third`, `guest` et `bruno` (mot de passe `pocoma`).

### Option A — Via le CLI `./pocoma-local login` ou Bruno GUI

```bash
./pocoma-local login --instance demo
```

Ou ouvrez Bruno GUI (`./pocoma-local gui --instance demo`) et lancez la connexion `00 Login Owner` avec le compte `third` / `pocoma` (voir [Authentification et liaison](../guides/authentication.md)).

### Option B — Liaison applicative (`Registration` → `bindingToken`)

Une fois votre jeton d'accès `ACCESS_TOKEN` obtenu auprès de `ISSUER`, vérifiez si votre identité OIDC est déjà liée à un utilisateur Pocoma :

```bash
curl -sS -i "$READ_URL/api/v3/me/binding" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

- Si la réponse est **`200 OK`**, récupérez la valeur `bindingToken` du JSON retourné.
- Si la réponse est **`404 Not Found`**, enregistrez votre identité côté WRITE :

```bash
IDEMP_REG=$(python3 -c 'import uuid; print(uuid.uuid4())')
curl -sS -X POST "$WRITE_URL/api/v3/identity/requests" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $IDEMP_REG" \
  -d '{"operation":"Registration"}'
```

La réponse **`202 Accepted`** retourne `{"requestId":"<REG_REQUEST_ID>"}`. Consultez son résultat asynchrone jusqu'au statut terminal `COMPLETED` :

```bash
curl -sS "$READ_URL/api/v3/identity/requests/$REG_REQUEST_ID/result" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

Relisez ensuite `GET $READ_URL/api/v3/me/binding` pour obtenir votre `bindingToken` actif.

## 3. Soumettre une commande métier (`CreatePot`)

Générez un UUID pour la nouvelle cagnotte (`POT_ID`) et une clé d'idempotence unique (`IDEMP_CMD`), puis soumettez la commande `CreatePot` avec `observedVersion: 0` et votre `BINDING_TOKEN` :

```bash
POT_ID=$(python3 -c 'import uuid; print(uuid.uuid4())')
IDEMP_CMD=$(python3 -c 'import uuid; print(uuid.uuid4())')

curl -sS -i -X POST "$WRITE_URL/api/v3/commands" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $IDEMP_CMD" \
  -d "{
    \"operation\": \"CreatePot\",
    \"potId\": \"$POT_ID\",
    \"observedVersion\": 0,
    \"bindingToken\": \"$BINDING_TOKEN\",
    \"payload\": {\"label\": \"Voyage\"}
  }"
```

La réponse **`202 Accepted`** contient `{"requestId":"<CMD_REQUEST_ID>"}` (ainsi que l'en-tête `Location`). Elle confirme la persistance durable de l'admission dans `pocoma_write`, pas encore l'exécution métier.

## 4. Retrouver le résultat de la commande

Interrogez l'endpoint de résultat côté READ avec le `CMD_REQUEST_ID` jusqu'à obtenir `200 OK` (tant que le résultat n'est pas matérialisé, l'API retourne `404`) :

```bash
curl -sS "$READ_URL/api/v3/commands/$CMD_REQUEST_ID/result" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

Une exécution réussie retourne :

```json
{
  "requestId": "...",
  "outcome": "COMPLETED",
  "resultingPotVersion": 1
}
```

Notez `resultingPotVersion` (`1` ici).

## 5. Lire la projection correspondante

Consultez la projection courante du Pot en exigeant au minimum la version produite (`minVersion=1`) et en transmettant votre `bindingToken` dans l'en-tête `X-Pocoma-Binding-Token` :

```bash
curl -sS "$READ_URL/api/v3/pots/$POT_ID?minVersion=1" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "X-Pocoma-Binding-Token: $BINDING_TOKEN"
```

La réponse `200 OK` retourne la vue matérialisée complète dans `pocoma_read` (`potVersion`, `label`, `shareholders`, `expenses`, `balances`).

## Pour aller plus loin

- [Authentification et liaison](../guides/authentication.md)
- [Commandes et résultats asynchrones](../guides/commands.md)
- [Requêtes READ et projections](../guides/queries.md)
- [Référence API `/api/v3`](../reference/api.md)
