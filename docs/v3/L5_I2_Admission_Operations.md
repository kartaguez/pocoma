# L5/I2 — Admission publique V3 : exploitation et qualification

Mandat : GO humain du 4 novembre 2026, plan opérationnel V2.1. Ce document décrit I2 ; il ne vaut ni clôture humaine, ni certification SPEC-10, ni autorisation de production. Link public et Invitation restent fermés.

## Activation et autorités

Les racines WEB et Monolithe importent explicitement `V3AdmissionHttpConfiguration`. Activer `v3-http-read,v3-http-admission` sur WEB ; ajouter `v3-read-worker` sur le Monolithe avec sa configuration Worker DEV existante. Les autres racines ne reçoivent aucun import ni pool d’admission. Mettre à niveau les consommateurs Command et Identity avant l’activation (notamment pour la nouvelle origine Identity AUTHENTICATED_HTTP) : l’enveloppe durable contenant SELF/KEEP/DETACH est versionnée 2 et l’ancien décodeur la refuse.

Appliquer la migration additive CURRENT WRITE `db/v3-admission/migration/V7__public_admission.sql` après les locations V3 WRITE jusqu’à V6. Ne pas ajouter cette location à READ et ne pas réécrire les migrations historiques. Le bootstrap de qualification demeure CURRENT V3 ; les migrations historiques présentes dans les fermetures canoniques restent exécutables.

Créer un LOGIN dédié sans appartenance, ownership, droits de création ou privilèges d’administration ; ses credentials sont distincts de Worker, READ, injecteur et administrateur. Depuis une session administrative au schéma WRITE exact :

```sh
psql -X -v ON_ERROR_STOP=1 \
  -v expected_database="$I2_DATABASE" -v expected_schema="$I2_SCHEMA" \
  -v expected_admin="$I2_ADMIN" -v admission_role="$I2_ADMISSION_ROLE" \
  -f scripts/v3/grant-l5-admission.sql
```

La session doit déjà cibler cette base et ce schéma (`search_path`), hors `public`. Le script donne les INSERT de colonnes PENDING uniquement, SELECT sur l’association/demandes et les lectures auxiliaires nécessaires aux triggers SECURITY INVOKER. Aucun UPDATE(status), DML métier, création de slot, lecture de token d’autorité ou accès Pot. Les fonctions/vues indirectes, PUBLIC, appartenances, ownership et droits effectifs de colonnes sont vérifiés au démarrage. Une autre vue applicative accessible ou un SECURITY DEFINER exécutable fait échouer ce préflight.

Les paramètres de journalisation réservés sont provisionnés administrativement **pour le rôle dans cette base** par ce script, puis vérifiés sur les nouvelles connexions. Ils ne sont pas passés comme options de connexion par le runtime et n’impliquent aucun droit SET supplémentaire. Reconnecter les pools après modification. Les logs sensibles SQL/paramètres/détails sont désactivés ; les erreurs HTTP sont génériques et bornées.

Configurer hors Git les propriétés suivantes sous `pocoma.v3.http.admission.` :

| Propriété | Contrat |
|---|---|
| `url`, `database`, `schema`, `username`, `password` | Cible WRITE et LOGIN contrôlés physiquement |
| `lookup-key-id`, `lookup-key` | Identifiant et secret Base64 d’au moins 32 octets ; **valeur logique stable** tant que les associations existent |
| `comparison-current`, `comparison-key-ids` | Identifiant courant et liste CSV fermée de clés disponibles |
| `comparison-keys.<id>` | Secret Base64 d’au moins 32 octets pour chaque identifiant |
| `command-bytes`, `identity-bytes` | Initialement 262144 / 16384 octets reçus |
| `depth`, `text-bytes`, `rational-digits`, `capacity` | Initialement 16 / 4096 / 1024 / 1 |

Ne jamais remplacer la valeur logique de la clé de recherche sous le même identifiant, ni changer cet identifiant sans migration contrôlée des associations. Ce n’est pas une rotation ordinaire : cela changerait les adresses HMAC et permettrait de manquer des associations. La rotation de protection externe du secret est distincte. Conserver les anciennes clés de comparaison ; un retry dont la version/clé manque répond 503, sans fallback ni nouvelle admission. Sauvegarder/restaurer ensemble demandes, associations, effets/résultats et matériel cryptographique compatible. Les fixtures de restauration I2 ne sont pas une homologation de sauvegarde de production.

## Contrat HTTP fermé V1

Deux POST seulement : `/api/v3/commands` et `/api/v3/identity/requests`. JWT signé selon I1, audience/issuer configurés, `Content-Type: application/json` (charset UTF-8 facultatif), aucun Content-Encoding, un seul `Idempotency-Key` de 1–128 octets dans `[A-Za-z0-9._~:-]`. Aucun paramètre de query, champ inconnu, doublon de propriété JSON ou deuxième document JSON. Les UUID sont complets ; les rationnels sont des objets `{ "numerator": "2", "denominator": "3" }`, avec entiers exacts en chaînes, jamais des flottants.

L’EUI initiatrice vient exclusivement de l’authentification. Aucun requester, UserId, client attesté libre, attestation ou contexte d’admission n’est accepté dans le corps. Le contrat I1 n’expose pas de client attesté : la portée initiale utilise explicitement son absence.

Une Command possède exactement `operation`, `potId`, `observedVersion`, `bindingToken`, `payload`. `observedVersion=0` pour CreatePot ; entier positif pour les autres. Les payloads sont :

| Opération | Champs exacts du payload |
|---|---|
| CreatePot, UpdatePotDetails | `label` |
| DeletePot | Objet vide |
| AddShareholders | `targets`: liste non vide de `{id,name,attachment,weight}`, attachment FREE ou SELF |
| UpdateShareholderDetails | `targets`: liste non vide de `{id,name,attachment}`, attachment KEEP, SELF ou DETACH |
| UpdateShareholderWeights | `targets`: liste non vide de `{id,weight}` |
| DeleteShareholder | `id` |
| CreateExpense | `id,label,date,amount,payerId,participants` |
| UpdateExpenseDetails | `id,label,date` |
| UpdateExpenseFinancials | `id,amount,payerId,participants` |
| DeleteExpense | `id` |

`participants` est une liste non vide de `{id,weight}`, avec weight rationnel positif ou null pour le défaut. Les identifiants dupliqués sont refusés avant construction de map. Les listes multi-cibles conservent leur ordre ; les participants financiers ont une sémantique de map et sont canonisés par identifiant. Les textes/identités opaques ne sont ni trimés ni normalisés en casse/Unicode. Les surrogates isolés ou mal appariés sont refusés en 400 avant toute recherche/insertion : aucun remplacement UTF-8 permissif ne peut assimiler une identité ou une intention à un autre texte. Les octets des scalaires Unicode valides sont conservés.

Identity accepte exactement `{ "operation": "Registration" }`, ou `{ "operation": "Unlink", "bindingToken": "…", "target": {"issuer":"…","subject":"…"} }`. Registration cible l’appelant authentifié et ne reçoit aucun token. Unlink reçoit seulement le token initiateur, y compris pour détacher une autre EUI du même User ; l’autorité courante est vérifiée dans WRITE.

Réponse 202 : `{ "requestId": "<uuid>" }`, avec Location du résultat existant (`/api/v3/commands/<id>/result` ou `/api/v3/identity/requests/<id>/result`) et Cache-Control no-store. Rejeu équivalent : même 202 et même identifiant, avant/après terminalisation. Intention différente comparable dans la même portée : 409. Refus de forme/transport : 400/413/415 ; AuthN invalide : 401 ; saturation, clé/version indisponible ou incident : 503. Aucun refus préalable ne devient un résultat métier durable.

Après 202, conserver requestId **et clé**. Les résultats READ peuvent encore répondre 404 ; leur publication et CurrentBinding/Pot/Balance progressent indépendamment. Un résultat 200 COMPLETED ou REJECTED est terminal. Attendre la version EUI annoncée par IdentityRequestResult pour récupérer un nouveau token ; une ancienne projection active ne prouve pas ce nouveau binding.

## Transactions, ressources et issue incertaine

Le service prépare intention/HMAC hors transaction WRITE, cherche l’association avant la nouvelle validation autonome, puis réserve et insère sous un seul `SpringAttemptTransactions`. L’unicité SQL arbitre deux transactions réellement concurrentes. Aucun commit intermédiaire, verrou distribué, cache d’idempotence, inverse SQL, table de tentative ou mécanisme Link anticipé.

Les FK différées empêchent l’association orpheline ; la composition de confiance et l’unité physique empêchent la demande orpheline sur les chemins publics autorisés. Le rôle de service possédant INSERT peut techniquement émettre un INSERT SQL isolé : l’option A ne prétend pas une intégrité inverse universelle contre ce détenteur de credentials. Aucun accès SQL client ni injecteur interne n’est exposé par HTTP.

Le pool est distinct, de taille A ; un permis HTTP couvre lecture du corps, parsing, SQL et envoi synchrone. Délais initiaux : acquisition 1 s, statement 2 s, verrou 500 ms, transaction serveur 3 s, socket 5 s. La capacité ne prouve pas un débit commercial. Un timeout ou une déconnexion ne vaut pas rollback.

`AdmissionSqlTransactions` conserve l’owner partagé inchangé. Il capture localement le PID via le driver, sans requête SQL supplémentaire sur le chemin nominal. Si la résolution est UNKNOWN, une observation indépendante et bornée de `pg_stat_activity` vérifie la terminaison avant restitution du permis. **Si cette observation est impossible, le 503 reste générique et le permis reste retenu.** Diagnostiquer/résoudre physiquement l’incident avant de réinitialiser ce runtime ; il n’existe ni réadmission de réparation, ni boucle non bornée, ni nouveau statut métier. Après résolution/reprise, la même clé retrouve la demande unique.

Justification de ce raccord : l’invariant est la rétention des ressources jusqu’à terminaison physique ; le cas concret est l’accusé de commit perdu alors que PostgreSQL peut encore terminer. Le vocabulaire COMMITTED/ROLLED_BACK/UNKNOWN existant ne fournit pas à lui seul cette observation HTTP. Le coût est un adaptateur local, une information technique d’erreur et deux oracles transport, sans changement de transaction partagée, module, table, workflow ou requête nominale. Le driver PostgreSQL déjà utilisé par les runtimes devient une dépendance de compilation explicite de cet adaptateur.

## D4 et recettes

Les nombres initiaux viennent du mandat et du corpus D4-A mesuré, pas de limites de domaine. Aucun maximum indépendant de 100 cibles. Le corpus de 22 profils contient les treize opérations, 1/100/101/1000 cibles, textes UTF-8 à 4096/65536 octets, rationnels à 1024/4096 chiffres, dont 101 cibles avec fractions de 1024 chiffres dans un seul corps admissible. Le rapport/manifeste distingue allocations cumulées et mémoire échantillonnée, ainsi que D4-A et coûts SQL D4-B. Un corps dépassant une protection actuelle peut refuser un ancien retry en 413 sans modifier son durable ; une nouvelle limite autonome ne requalifie pas l’association retrouvée.

Depuis la racine, préflight dry-run avant chaque exécution :

```sh
python3 -B scripts/testing/verify.py FAST l5-i2-fast --dry-run
python3 -B scripts/testing/verify.py FAST l5-i2-codec --dry-run
python3 -B scripts/testing/verify.py FAST l5-i2-d4 --dry-run
python3 -B scripts/testing/verify.py INTEGRATION l5-i2 --dry-run
python3 -B scripts/testing/preflight-l5-i2-gates.py --output /private/tmp/pocoma-i2-equivalence-new
python3 -B scripts/testing/verify.py INTEGRATION WEB,COMMAND,ARCHITECTURE --dry-run
```

Exécuter ensuite la sélection nécessaire avec un `--output` neuf. `ARCHITECTURE` est une **gate explicite**, pas une nouvelle slice automatique ; I2 lui ajoute explicitement la racine Monolithe. Le script de préflight compare les quatre fermetures canoniques effectives à la mutualisation, inventories de sources/producers/configurations inclus. Sans équivalence, conserver les commandes canoniques séparées. Aucun full reactor Q5 n’est autorisé ici.

T2 intègre le CLI Bruno verrouillé 4.3.0 et ses 24 scénarios B201–B224 avec reçus réels et empreintes des sources avant/après. Le test de flux utilise les moteurs Worker existants ; son seul Link est une fixture interne contrôlée L3, déclenchée par un handshake borné. L’admission publique des autres états utilise les nouvelles routes. Polling : au plus 200 appels et 20 s par attente, requête de 5 s maximum, intervalle 50 ms ; CLI borné à 180 s. Les observations SQL indépendantes demeurent les oracles d’atomicité/autorité.
