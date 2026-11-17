# Requêtes READ et projections

Les lectures sont servies exclusivement par `http-read` (`READ_URL/api/v3`) depuis la base matérialisée `pocoma_read`. Toutes les routes de lecture portent `Cache-Control: no-store`.

## 1. En-têtes requis

- `Authorization: Bearer <access_token>` sur toutes les routes `/api/v3`.
- `X-Pocoma-Binding-Token: <bindingToken>` sur les routes métier (cagnottes, soldes, dépenses, liste `/me/pots`, recherche d'invitations).

## 2. Lire l'état courant d'un Pot (`minVersion`)

```bash
curl -sS "$READ_URL/api/v3/pots/$POT_ID?minVersion=1" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "X-Pocoma-Binding-Token: $BINDING_TOKEN"
```

- `minVersion=V` garantit que la version retournée `W` satisfait `W >= V`.
- Si une écriture concurrente a avancé le Pot à `W > V`, utilisez ce `W` retourné pour vos consultations associées (historique ou soldes) afin de conserver une vue cohérente.
- Tant que la projection n'a pas atteint `minVersion=V`, ou si l'utilisateur n'est pas autorisé, l'API retourne `404 Not Found`.

## 3. Consulter l'historique exact et les soldes

### Version historique exacte

```bash
curl -sS "$READ_URL/api/v3/pots/$POT_ID/versions/$VERSION" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "X-Pocoma-Binding-Token: $BINDING_TOKEN"
```

Retourne l'état exact de la version `VERSION` (sans jamais substituer une version plus récente).

### Soldes exacts d'une version

```bash
curl -sS "$READ_URL/api/v3/pots/$POT_ID/versions/$VERSION/balance" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "X-Pocoma-Binding-Token: $BINDING_TOKEN"
```

Les soldes sont retournés en rationnels `{"n":"5","d":"3"}`. Un solde positif indique un créancier ; la somme exacte des soldes d'un Pot est toujours nulle.

### Détail des dépenses par références opaques

Les références retournées dans la vue d'un Pot sont des objets opaques à recopier tels quels (batch de `1` à `50` références) :

```bash
curl -sS -X POST "$READ_URL/api/v3/pots/$POT_ID/versions/$VERSION/expenses/query" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "X-Pocoma-Binding-Token: $BINDING_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"references":[<référence opaque lue sur le Pot@VERSION>]}'
```

## 4. Paginer ses cagnottes (`/me/pots`)

```bash
curl -sS "$READ_URL/api/v3/me/pots?limit=20" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "X-Pocoma-Binding-Token: $BINDING_TOKEN"
```

- `limit` : défaut `20`, maximum `100`.
- `cursor` : valeur opaque retournée dans `nextCursor` (ne jamais la modifier ou la reconstruire).

## 5. Confidentialité (`404` opaque) et protection de charge (`503`)

- **Confidentialité `404`** : un objet inexistant, une projection non encore matérialisée et une ressource appartenant à un tiers produisent tous `404 Not Found` avec un corps `{code, message, traceId}` non discriminant.
- **Suppression (`DeletePot`)** : après un `DeletePot` confirmé `COMPLETED` à la version `D`, l'état courant et la version `D` retournent `404`, tandis que les versions antérieures `V < D` restent consultables pour les membres autorisés historiques.
- **Protection `503`** : `http-read` valide le protocole et le jeton **avant** d'acquérir l'un des `8` permis de concurrence (`Q=8`) ; en cas de saturation ou de maintenance READ, l'API répond `503 Service Unavailable`.
