# L3 / I2 — Autorité Identity PostgreSQL

État : IN PROGRESS. Aucun verdict PASS ni GO I3.

## Référence et préalable

HEAD initial local/distant vérifié : `02c7bfbf85936f1f1a633e9097bc2b49f878708e`.
Aucun commit intervenu depuis. Checkout principal `feat/runtime-monolith` et
travaux étrangers conservés intégralement. Worktree utilisé :
`/private/tmp/pocoma-v3-postaud`, `feat/v3-architecture`.
Les documents préexistants du diagnostic I1 ont été lus, l'analyse des archives
rejouée avec succès (intégrité uniquement), les liens contrôlés. Clôture I1
publiée séparément : `a93a83d5cdbe6c043b5f6618d6b6297f40a59241`.
I1 ACCEPTED WITH EXPLICIT RESERVATIONS ; gate COMMAND + Architecture FAIL,
`i5_r05`/`i5_r12` conservés, aucun I1 LOCAL BOUNDED PASS.

## Verification scope — déclaré avant code

- Impact production attendu : `JdbcIdentityAuthority`, générateur SecureRandom,
  migration additive WRITE V6 ; réemploi des contrats et transitions I1.
- Slice primaire : COMMAND (`runtime-command-consumption-worker`).
- Slices secondaires : NONE.
- Preuve locale : les quatre classes I2 prévues, sur PostgreSQL réel ; histoire,
  absence/ancre zéro, concurrence avec barrières et observations serveur,
  rollback, contraintes sous rôle runtime, fermeture et catalogue des triggers.
- Gate Architecture globale : REQUIRED, nouvelles règles de writers.
- Full reactor : NOT REQUIRED ; ni clôture Wave majeure ni intégration main.
- DB : CURRENT V3 WRITE V1…V6 ; V6 disponible au HEAD réel, aucun V6 préexistant.
  Bootstrap V1…V5 historique des tests L1/L2 conservé. Aucune baseline V2 TRUSTED
  présumée, aucune campagne historique séparée.
- Interdit : I3 participant/store actif, I4 Pot, I5, runtime, READ, HTTP, migrations
  publiées, owner physique, mécanismes/seuils/tests temporels L2.
- Escalade : redéclaration avant tout changement d'owner/routage/contrat V2,
  consommateur supplémentaire ou frontière imprévue.

Commandes prévues depuis `app/` (leur exécution sera enregistrée séparément) :

```
./mvnw -pl infra-persistence-primary-jpa -am -Dtest=IdentityAuthorityPostgresTest,IdentityConcurrencyPostgresTest,IdentitySchemaPostgresTest,BindingTokenGeneratorTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -pl runtime-command-consumption-worker,architecture-tests -am test
```

La première est un feedback local filtré, jamais un substitut à la seconde.
La seconde peut réexécuter les campagnes L2 transitivement, sans modification
ni exclusion. FAIL, SKIP et INCONCLUSIVE seront conservés.

## Implémentation candidate

Deux classes productives, une migration, quatre classes de tests PostgreSQL/token
et une garde d'architecture I2. Aucun POM à modifier : les dépendances nécessaires
existent déjà. Aucun fichier métier/owner/test temporel L1/L2 modifié.
L'autorité JDBC impose l'acquisition du jeu complet avant current/apply, utilise
le comparateur I1 et la garde SpringAttemptTransactions existante ; pas de
callback ni ressource transactionnelle propriétaire. Les protections Java sont
associées par référence faible au holder déjà possédé par l'owner ; les verrous
PostgreSQL restent détenus physiquement jusqu'à résolution.
Les erreurs JDBC sont réduites à leur SQLSTATE sans conserver DETAIL/cause SQL
pouvant révéler un token. Les événements et résultats n'en contiennent pas.

V6 contient les sept tables prévues, les huit FK immédiates nommées, PK/UNIQUE/
CHECK, gardes d'immuabilité, attribution writer_xid réelle, succession locale et
les dix triggers structurels/terminaux différés. Les compteurs IDENTITY et
IDENTITY_COMPLETION sont ajoutés sans altérer les autres données. Les gardes
additionnelles counter/position empêchent des allocations orphelines ou à trous ;
elles ne consultent que les structures de séquence dans le suffixe. Le premier
état des opérations livrées doit être lié (pas de v1 libre fictive). La phase
structurelle est forcée par ses deux noms, jamais SET CONSTRAINTS ALL.
Aucune incompatibilité du DDL avec R1/suffixe L2 identifiée par les preuves I2.

Feedback candidat avant gate : 25 tests I2 et 21 contrôles d'architecture ciblés,
46/0/0/0, PostgreSQL 17.11 aarch64, image épinglée par digest. C'est un feedback
filtré, aucune gate canonique n'en est déduite. Les premières itérations ont aussi
produit des FAIL de compilation/harness/oracles I2 ; leurs journaux sont conservés.
La gate non filtrée reste à exécuter ; son résultat peut empêcher tout verdict
technique canonique. L'arbitrage I1 ne la dispense pas.
