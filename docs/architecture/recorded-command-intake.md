# Admission HTTP des Commands durables

## Sémantique asynchrone

`POST /api/v1/commands` enregistre une demande durable et répond `202 Accepted` uniquement après
le commit de `recorded_commands`. La réponse contient `commandId` et `status: ACCEPTED`.

`ACCEPTED` ne signifie jamais `SUCCESS` : aucun use case Pot, decoder, dispatcher, locator ou
moteur de consommation n'est appelé dans la requête HTTP. Aucun Event, `ConsumptionSlot` ou
`Claim` n'est créé. Les résultats ultérieurs possibles restent `PENDING`/retry, `SUCCESS`,
`REJECTED` et `FAILED`; aucun endpoint de consultation de ce résultat n'est fourni par le Lot 6.6.

Le payload de l'envelope HTTP doit être un nœud JSON présent et la requête est bornée par
`pocoma.command-admission.max-request-bytes`. Il n'est pas décodé comme Command métier avant
l'enregistrement. Un `CommandType` inconnu ou un payload métier invalide peuvent donc être
acceptés, puis échouer techniquement pendant la consommation.

Les limites natives Tomcat de formulaire ou de multipart ne bornent pas un body JSON arbitraire.
Le runtime conserve donc un filtre servlet ciblé sur cette route : `Content-Length` est refusé tôt
lorsqu'il est disponible, et le flux reste compté pour les transferts sans longueur fiable.

## Frontière d'authentification

```text
Bearer token
  -> Spring Security OAuth2 Resource Server
  -> JwtAuthenticationToken authentifié
  -> SpringSecurityExternalPrincipalAdapter
  -> AuthenticatedExternalPrincipal provider-neutral
  -> admission Pocoma
```

Pocoma ne possède pas la validation JWT. Dans le runtime Spring, OAuth2 Resource Server vérifie la
signature, l'issuer, l'audience, `exp` et `nbf`, et gère discovery/JWK/rotation. La configuration
opérationnelle utilise les propriétés Spring standard :

- `spring.security.oauth2.resourceserver.jwt.issuer-uri` (obligatoire en environnement réel) ;
- `spring.security.oauth2.resourceserver.jwt.audiences` (`pocoma-api` par défaut) ;
- les mécanismes standards Spring pour le clock skew lorsqu'une valeur différente est requise.

Le supra Spring est seul autorisé à connaître `Jwt`, `JwtAuthenticationToken`, Spring Security et
le provider. Keycloak est une implémentation possible du serveur d'autorisation, jamais un contrat
applicatif.

Pocoma possède uniquement le contrat attendu après authentification : issuer, subject, `iat`,
`exp`, `auth_time` et autorités externes. `auth_time` est obligatoire et ne possède aucun fallback
vers `iat` ou l'heure courante. Le profil OIDC déployé doit donc exposer `auth_time` dans l'access
token. Un autre supra (gateway, mTLS, gRPC, autre framework) peut produire le même
`AuthenticatedExternalPrincipal` sans modifier l'orchestrateur d'admission.

## Identité, permissions et snapshot

L'identité externe est résolue par la clé exacte `(issuer, subject)` de `external_identities` vers
un `PocomaUserId`. Plusieurs identités externes peuvent cibler le même utilisateur, mais une clé
externe ne peut cibler qu'un seul utilisateur. Une identité absente retourne
`403 USER_NOT_PROVISIONED`; aucun auto-provisioning n'est effectué.

En 6.6, les fixtures de test sont insérées directement. Les environnements réels doivent utiliser
une procédure opératoire temporaire de provisioning. Une API d'administration est hors scope.

Le translator applicatif reçoit seulement `Set<String> externalAuthorities`, jamais un JWT. Les
autorités syntaxiquement valides `pocoma:<objectType>:<action>` deviennent des
`Permission(objectType, action)`. Les autorités étrangères ou mal formées sont ignorées et
n'accordent aucun droit. Les valeurs Pocoma futures valides restent représentables ; les policies
actuelles ne leur attribuent aucun droit connu.

Le snapshot durable contient le `PocomaUserId`, ces permissions, issuer, `authenticatedAt`,
`issuedAt` et :

```text
validUntil = min(token.expiresAt, submittedAt + PocomaAuthorizationTTL)
```

`submittedAt` et le UUID `CommandId` sont générés par le serveur. Ni bearer token, ni refresh token,
ni timestamp client ne sont persistés. L'expiration du snapshot reste contrôlée par
`ExecuteRecordedCommandService` au moment de la consommation.

## Transaction et voie write officielle

`SubmitRecordedCommandService` ouvre une transaction courte via `TransactionRunner`. La résolution
d'identité et `RecordedCommandPort.insert` participent au même commit. Une erreur de persistence ne
produit pas de `202`.

Les anciennes mutations synchrones sous `/api/pots` et `/api/expenses` ont été retirées. Les routes
de lecture historiques peuvent encore utiliser temporairement leurs headers legacy, mais la seule
entrée du write model primaire est désormais l'admission Bearer `/api/v1/commands`.

Après le commit de l'admission, `runtime-command-consumption-worker` découvre et exécute la demande
de façon indépendante. Le controller ne possède aucune référence vers un use case Pot, le locator
ou le worker. L'idempotency key et un endpoint de consultation de statut restent hors scope.
