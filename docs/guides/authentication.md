# Authentification et liaison (`Binding`)

L'accès aux API Pocoma (`/api/v3`) combine une authentification **OIDC** (Keycloak) et une liaison applicative explicite (`Binding`) entre votre identité externe et un utilisateur interne Pocoma.

## 1. Authentification OIDC (Keycloak)

L'infrastructure locale déploie **Keycloak 26.7.0** avec le royaume [`docker/keycloak/pocoma-realm.json`](../../docker/keycloak/pocoma-realm.json) :

- **Flux** : Authorization Code + PKCE (`S256`) avec vérification de `state`.
- **Découverte OIDC** : `ISSUER/.well-known/openid-configuration` (`ISSUER` est affiché par `./pocoma-local status`).
- **Client public** : `pocoma-functional-tests` (sans `Client Secret`), callback `http://127.0.0.1:8765/callback`, scope `openid`.
- **Audience attendue du Bearer JWT** : `pocoma-api`.
- **Comptes de test locaux** : `third`, `guest` et `bruno` (mot de passe `pocoma`).

> [!WARNING]
> Ces comptes et ce royaume sont réservés au playground local. L'API vérifie l'issuer exact, l'audience `pocoma-api`, la signature et l'expiration du jeton ; le scope `openid` ne porte pas de rôle métier (les autorisations dépendent de l'identité liée et des droits sur chaque Pot).

### En ligne de commande ou via Bruno GUI

- `./pocoma-local login --instance demo` ouvre/renouvelle la session OIDC en ligne de commande.
- `./pocoma-local gui --instance demo` ouvre Bruno préconfiguré avec OAuth natif PKCE (utilisez `00 Login Owner` pour `third` et `01 Login Guest` pour `guest`). Aucune copie manuelle de JWT n'est nécessaire.

## 2. Consulter ou créer sa liaison (`Binding`)

Un jeton OIDC valide ne suffit pas pour manipuler des cagnottes : votre identité externe doit être liée à un utilisateur Pocoma via un `bindingToken` actif.

### Vérifier le binding courant

```bash
curl -sS -i "$READ_URL/api/v3/me/binding" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

- **`200 OK`** : retourne `active`, `euiVersion` et `bindingToken`.
- **`404 Not Found`** : votre identité n'est pas encore enregistrée.

### Enregistrer son identité (`Registration`)

Soumettez une demande d'inscription côté WRITE (le client ne choisit jamais son `userId` ni son `subject` : ils sont extraits du JWT) :

```bash
curl -sS -X POST "$WRITE_URL/api/v3/identity/requests" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(python3 -c 'import uuid; print(uuid.uuid4())')" \
  -d '{"operation":"Registration"}'
```

La réponse `202 Accepted` retourne `{"requestId":"..."}`. Interrogez ensuite le résultat côté READ jusqu'à obtenir `COMPLETED` :

```bash
curl -sS "$READ_URL/api/v3/identity/requests/<requestId>/result" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

Relisez enfin `GET $READ_URL/api/v3/me/binding` jusqu'à observer le `bindingToken` actif correspondant à `targetVersion`. Une nouvelle `Registration` sur une identité déjà liée est rejetée avec `outcome: "REJECTED"` (`TARGET_ALREADY_BOUND`).

## 3. Utiliser le `bindingToken` dans les appels API

- **En écriture (`POST /api/v3/commands` ou `Unlink`)** : transmettez `"bindingToken": "..."` dans le corps JSON.
- **En lecture (`GET/POST` sur les cagnottes et invitations)** : transmettez l'en-tête HTTP `X-Pocoma-Binding-Token: <bindingToken>`.

> [!IMPORTANT]
> Conservez le bearer JWT, le `bindingToken` et les secrets d'invitation exclusivement côté serveur/session protégée (ou en mémoire Bruno) ; ne les placez jamais dans une URL d'API ni dans les journaux.

## 4. Désaffiliation (`Unlink`)

Pour détacher votre identité externe courante :

```json
{
  "operation": "Unlink",
  "bindingToken": "<BINDING_TOKEN>",
  "target": {
    "issuer": "<ISSUER>",
    "subject": "<SUBJECT>"
  }
}
```

Soumis sur `POST $WRITE_URL/api/v3/identity/requests` avec `Idempotency-Key`, `Unlink` révoque le `bindingToken` courant. Un jeton de binding révoqué reste invalide même avec un nouveau JWT ; toutefois, les résultats historiques des commandes que vous avez soumises restent consultables par votre identité historique.
