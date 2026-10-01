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

Le body JSON conserve `commandType` et `payload` et exige désormais `bindingId`, représenté par une
chaîne UUID JSON (désérialisée en `UUID`, puis enveloppée dans le value object opaque `BindingId`).
Ce choix est local au contrat existant, n'ajoute aucun endpoint V2 et rend l'absence, la chaîne vide
ou une valeur non UUID structurellement rejetables avant toute persistence.

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

## Identité, binding et évidence d'authentification

L'admission capture l'`ExternalIdentity(issuer, subject)` attestée et le `BindingId` syntaxiquement
valide présenté par le client. Elle ne consulte pas l'autorité User/Identity primaire, ne résout pas
de `PocomaUserId` et ne vérifie ni l'existence ni l'actualité du binding. Une identité authentifiée
mais inconnue est admise comme toute intention structurellement valide ; la décision fonctionnelle
appartient au worker.

`BindingId` absent ou mal formé provoque un rejet HTTP structurel sans enregistrement. Un ID bien
formé mais faux, ancien ou inexistant produit une Command durable et `202 Accepted`.

L'admission capture exactement les autorités externes attestées provider-neutral et `validUntil`
dans `CommandAuthenticationEvidence`. Elle ne les traduit pas en décision d'AuthZ métier. Aucun JWT
brut, token, `PocomaUserId`, `Permission` métier, `authenticatedAt`, `issuedAt` ou claim inutilisé
n'est persisté dans l'envelope `TARGET_V2`.

L'évidence durable peut notamment borner sa validité par :

```text
validUntil = min(token.expiresAt, submittedAt + PocomaAuthorizationTTL)
```

`submittedAt` et le UUID `CommandId` sont générés par le serveur. Ni bearer token, ni refresh token,
ni timestamp client ne sont persistés. L'expiration de l'évidence reste contrôlée par
`ExecuteRecordedCommandService` au moment de la consommation.

## Transaction et voie write officielle

`SubmitRecordedCommandService` ouvre une transaction courte via `TransactionRunner` pour le seul
insert de l'intention. Aucune lecture métier primaire et aucune consultation READ ne participent à
l'admission. Une erreur de persistence ne produit pas de `202`.

Les anciennes mutations synchrones sous `/api/pots` et `/api/expenses` ont été retirées. Les routes
de lecture historiques peuvent encore utiliser temporairement leurs headers legacy, mais la seule
entrée du write model primaire est désormais l'admission Bearer `/api/v1/commands`.

Cette règle est transversale à toute admission HTTP WRITE : AuthN, validation structurelle,
capture durable, puis acceptation technique. L'AuthZ et les invariants métier sont toujours décidés
par le worker autoritatif.

Après le commit de l'admission, `runtime-command-consumption-worker` découvre et exécute la demande
de façon indépendante. Le controller ne possède aucune référence vers un use case Pot, le locator
ou le worker. L'idempotency key et un endpoint de consultation de statut restent hors scope.
