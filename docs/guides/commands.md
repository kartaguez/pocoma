# Commandes et résultats asynchrones

Toute modification métier dans Pocoma passe par l'admission HTTP sur `http-write` (`WRITE_URL/api/v3/commands`), suivie d'une exécution asynchrone par `command-worker` et d'une matérialisation du résultat par `results-worker`.

## 1. Règles d'admission et d'idempotence

Chaque requête `POST /api/v3/commands` doit inclure :
- `Authorization: Bearer <access_token>`
- `Content-Type: application/json`
- `Idempotency-Key: <clé unique par intention>`

### Comportement de `Idempotency-Key`

- Réutiliser **la même clé et exactement le même corps** permet de rejouer une admission dont la réponse réseau a été perdue et retrouve le même `requestId`.
- Réutiliser **la même clé avec un corps différent** retourne **`409 Conflict`**.
- La clé d'idempotence est isolée par identité appelante.

### Signification de `202 Accepted`

La réponse `202 Accepted` contient uniquement `{"requestId":"<UUID>"}` (et l'en-tête `Location`). Elle garantit la persistance durable de la demande dans `pocoma_write`, **jamais** son acceptation métier ni la disponibilité immédiate de la projection READ.

## 2. Consulter le résultat terminal

Interrogez le runtime READ (`READ_URL`) avec le `requestId` obtenu :

```bash
curl -sS "$READ_URL/api/v3/commands/$REQUEST_ID/result" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

- Tant que le résultat n'est pas matérialisé (ou si l'identifiant est inconnu / appartient à un autre utilisateur), l'endpoint retourne **`404 Not Found`**.
- Une fois matérialisé (`200 OK`), `outcome` vaut :
  - **`COMPLETED`** : commande appliquée, avec `resultingPotVersion` (entier positif).
  - **`REJECTED`** : refus métier explicite (`rejection`), sans erreur de transport.

> [!TIP]
> Après un `COMPLETED` donnant `resultingPotVersion = V`, passez `?minVersion=V` lors de la lecture du Pot pour garantir que la projection lue intègre bien votre mutation.

## 3. Enveloppe commune et contrôle de version (`observedVersion`)

Toutes les commandes partagent la même enveloppe JSON :

```json
{
  "operation": "CreatePot",
  "potId": "<UUID généré par le client>",
  "observedVersion": 0,
  "bindingToken": "<BINDING_TOKEN>",
  "payload": {
    "label": "Voyage"
  }
}
```

- Pour `CreatePot`, `CreateInvitation` et `AttachShareholderViaInvitation`, `observedVersion` vaut `0`.
- Pour les mutations sur un Pot existant, `observedVersion` doit correspondre à la version courante observée ; en cas de conflit concurrent, la commande est `REJECTED` et le client doit relire le Pot avant de soumettre une nouvelle intention.

## 4. Exemples de commandes métier

### Ajouter un membre (`AddShareholders`)

```json
{
  "operation": "AddShareholders",
  "potId": "<POT_ID>",
  "observedVersion": 1,
  "bindingToken": "<BINDING_TOKEN>",
  "payload": {
    "targets": [
      {
        "id": "<SHAREHOLDER_UUID>",
        "name": "Alice",
        "attachment": "FREE",
        "weight": {"numerator": "1", "denominator": "1"}
      }
    ]
  }
}
```

Valeurs d'`attachment` à l'ajout : `FREE` ou `SELF`. Pour `UpdateShareholderDetails` : `KEEP`, `SELF`, `DETACH`.

### Créer une dépense avec montants rationnels (`CreateExpense`)

Pocoma n'utilise **aucun nombre flottant** pour les montants : tous les montants et poids utilisent des rationnels exacts `{"numerator":"...","denominator":"..."}` en entrée (et `{"n":"...","d":"..."}` en sortie READ). Un `weight` à `null` sur un participant utilise son poids par défaut.

```json
{
  "operation": "CreateExpense",
  "potId": "<POT_ID>",
  "observedVersion": 2,
  "bindingToken": "<BINDING_TOKEN>",
  "payload": {
    "id": "<EXPENSE_UUID>",
    "label": "Courses",
    "date": "2026-11-15",
    "amount": {"numerator": "10", "denominator": "3"},
    "payerId": "<PAYER_SHAREHOLDER_UUID>",
    "participants": [
      {"id": "<SHAREHOLDER_1_UUID>", "weight": null},
      {"id": "<SHAREHOLDER_2_UUID>", "weight": null}
    ]
  }
}
```

### Inviter un participant (`CreateInvitation` → `AttachShareholderViaInvitation`)

1. Le propriétaire soumet `CreateInvitation` (`observedVersion: 0`, `payload: {"expiresAt": "2026-11-20T12:00:00Z"}` — instant UTC suffixé `Z`, précision ≤ microseconde, strictement futur à l'exécution).
2. Une fois le résultat `COMPLETED`, le créateur récupère le lien `pocoma://invitation/<secret>` via `GET /api/v3/invitations/by-creation-request/{requestId}`.
3. L'invité liste les places éligibles via `POST /api/v3/invitations/available-shareholders/query` puis soumet `AttachShareholderViaInvitation` (`observedVersion: 0`, `payload: {"invitationToken": "<secret>", "shareholderId": "<UUID>"}`).

Pour la liste complète des 13 opérations et payloads : [Référence API `/api/v3`](../reference/api.md).
