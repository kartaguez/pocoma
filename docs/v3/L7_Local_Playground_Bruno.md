# Playground — Bruno GUI et CLI

La collection livrée est `scripts/bruno-playground`, distincte des corpus de
qualification historiques `bruno-v3`. CLI verrouillée **4.3.0** via le lockfile
existant ; Node 24. L'inventaire est `scripts/bruno-playground/inventory.json` :
un scénario PG01, un test nommé asynchrone, plusieurs contrôles et requêtes
réelles. Le reçu strict exige PG01 exactement une fois et un JUnit frais avec
un test PASS, sans failure/error ; zéro scénario/test n'est jamais PASS.

## CLI automatisée

```sh
./pocoma-local start --instance demo
./pocoma-local bruno --instance demo
```

La façade utilise les mots de passe synthétiques privés pour **soumettre le
formulaire d'authentification Keycloak dans un flux Authorization Code**, avec
cookies, state aléatoire, verifier aléatoire et challenge SHA-256. Elle échange
le code avec `grant_type=authorization_code`. Aucun password grant, client secret,
token dans argv ou fixture READ. Les bearer sont transmis au seul processus
Bruno par environnement ; ne pas lancer avec un shell en mode trace ni imprimer
l'environnement. Les tokens expirent normalement ; relancer la commande ouvre
de nouvelles sessions, sans élargir leur durée.

Le compte `third` est propriétaire du parcours CLI, `guest` l'invité ; ils
sont séparés des objets demo. À la première exécution, Registration de third
réussit ; aux suivantes, la suite vérifie la réponse terminale explicite
`TARGET_ALREADY_BOUND`, puis utilise le binding courant. Le jeu créé par PG01
est supprimé à la fin via DeletePot, son histoire demeure ; un parcours échoué
reste en place pour diagnostic, aucune suppression automatique.

Le dossier retourné contient `bruno.xml`, `bruno-receipt.json`,
`scenarios.jsonl` (méthodes/routes/statuts et contrôles), et le log privé.
Le reporter JSON complet Bruno n'est pas utilisé : il stockerait les bodies
contenant les bindings. Une erreur de transport/assertion arrête le scénario et
le reçu attendu manque ; la façade sort 1. La collection vide sort également 1.

## GUI et authentification interactive

Ouvrir la collection `scripts/bruno-playground` dans Bruno. Employer un sandbox
**Developer** pour ce helper local audité (`bru.sendRequest`, Node crypto/fs).
La qualification automatique utilise le sandbox de la CLI 4.3.0 ; la version
GUI installée sur un autre poste demeure à vérifier, sans désactiver PKCE.

```sh
./pocoma-local login --instance demo
```

La façade réserve `127.0.0.1:8765/callback`, ouvre le navigateur sur le vrai
issuer de l'instance et attend 180 s. Se connecter avec `third` (ou `bruno`)
et le mot de passe synthétique de
`.pocoma-local/instances/demo/secrets.json` → `users`. Le navigateur ne reçoit
pas de client secret. La callback vérifie exactement son chemin et state ; les
logs HTTP de callback sont désactivés. Le client public Keycloak n'accepte que
cette redirect et impose PKCE S256. Si le port est occupé ou le délai expire,
la commande échoue ; arrêter l'autre helper, puis relancer.

La commande retourne le chemin privé `GUI-private.bru`. Dans Bruno, créer un
environnement **local non partagé** en reprenant ses quatre variables :
`writeUrl`, `readUrl`, `bearerOwner`, `bearerGuest`. Le guest synthétique est
pré-authentifié par le même flux Code+PKCE ; le propriétaire provient du login
interactif. Ne pas enregistrer cet environnement dans la collection Git, ne pas
activer sa synchronisation. Le fichier reste 0600 sous un répertoire 0700.
Après expiration, relancer login et actualiser cet environnement privé.

Choisir cet environnement et exécuter **PG01 Public Journey**. Le panneau Tests
doit annoncer le test PG01 réussi. Aucun succès n'est déduit d'une simple
réponse 200 à Current Binding. Pour utiliser le navigateur tout en conservant
l'exécution CLI et les preuves strictes :

```sh
./pocoma-local bruno --instance demo --interactive
```

Cette variante exige la même interaction de connexion avant de lancer le CLI.
L'authentification interactive GUI fait partie de la procédure de revue humaine ;
consulter le rapport pour distinguer son état de preuve des flux automatisés.

## Parcours et attentes

PG01 exécute Registration → résultat terminal → binding, CreatePot et replay
idempotent, lecture V1, deux actionnaires, refus d'accès du tiers avant invitation,
création/récupération confidentielle d'invitation, consultation des places,
acceptation et visibilité du guest, CreateExpense (10/3) puis correction (20/3),
balances indépendantes (+5/3,−5/3 puis +10/3,−10/3), histoire exacte V4 et batch
Expense référencé, UpdatePotDetails vérifié, DeletePot terminal V7, courant/V7
404 et histoire V6 conservée. Le test vérifie `Cache-Control: no-store` sur
chaque réponse, 401 sans bearer, et confidentialité de l'invitation.

Les autres opérations publiques sont décrites dans le [contrat BFF](L7_Local_Playground_Public_API.md).
Les corpus historiques B2/B4 couvrent des variantes supplémentaires dans leurs
propres campagnes ; leur présence ne certifie pas ces variantes au présent SHA.
