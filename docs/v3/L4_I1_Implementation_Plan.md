# Pocoma V3 — L4/I1 — Plan d’implémentation du stockage immuable READ

**Consolidation : 2 novembre 2026. Référence vérifiée : `e9729ae20531f688a7eb16a2642c0f3e7b82fece`, branche `feat/v3-architecture`.**

**Statut documentaire : READY FOR I1 IMPLEMENTATION REVIEW. Aucun GO d’implémentation.**
L’avis indépendant **GO WITH RESERVATIONS L4/I1** et les amendements A01–A10
sont transmis par le mandat humain ; aucun rapport original d’audit L4 n’est
présent sous un nom identifiable. Ce plan fige une proposition technique à revoir,
pas une preuve de code livré. Il complète le [plan global L4](L4_Read_Inventory_and_Implementation_Plan.md).

## 1. Autorités, périmètre et frontière déclarée

Lire avant toute réalisation : [AGENTS](../../AGENTS.md), [politique Reactor](../testing/Reactor_Verification_Policy.md),
[Master Plan §§1/3–5](V3_Implementation_Master_Plan.md), [canon](V3_Canon_and_Roadmap.md),
[SPEC-01 §§1–4](SPEC-01_Pot_Domain.md), [SPEC-04 §§3/8](SPEC-04_Read_Projections.md),
[SPEC-05 §§3–5/8/11](SPEC-05_Consumption_Discovery.md), [R1/R9 L2](L2_R1_R10_Normative_Consolidation.md)
et [SPEC-10 CON-04/06 et PRJ-01](SPEC-10_Test_Certification.md).
Les SPEC, puis les décisions transversales et le programme, conservent leur
hiérarchie ; ce contrat de réalisation ne les amende pas. D1–D4 du plan global
restent acquis : modules/runtimes existants ; trois familles fonctionnelles sans
prescription de trois processus ; rationalisation après qualification L6 ; analyse
des preuves affectées avant toute adaptation.

**I1 livre seulement** les valeurs et le port des sous-versions immuables, le
store JDBC, son codec strict, une migration métier READ et les preuves de
participation au protocole L2 existant. Il ne livre ni PotVersion/CurrentPot
métier, ni Balance, Visibility, Binding, résultats, sources WRITE historiques,
préparation/recovery interautorités I2, activation I8, HTTP ou rationalisation.
Les huit types stockables sont du contenu ; leur matérialisation métier reste I3.

| Déclaration préalable pour la future implémentation | Engagement |
|---|---|
| Slice primaire | **PROJECTION**, `runtime-task-consumption-worker` |
| Slice secondaire canonique | Aucune par défaut ; dépendances amont déterminées par Maven |
| Impact prévu | `domain-projection`, `port-projection`, `infra-persistence-read-jdbc`, tests techniques dans le runtime PROJECTION, règle d’architecture ciblée |
| Gate Architecture | **REQUIRED**, dépendances compile et contrat de port explicitement adaptés (§2) |
| Full reactor | **NOT REQUIRED**, ni fermeture L4 ni restructuration globale |
| PostgreSQL | **CURRENT V3**, installation neuve et extension L2 peuplée, vraies transactions READ |
| Impact interdit | Production Engine/owners/slots/Discovery/participants WRITE, quatre tables L2, activation/compositions et sources WRITE |
| Escalade | Si une garde L2 manque ou doit changer, documenter le défaut, suspendre cette partie et redéclarer consommateurs/preuves avant correction ; ne pas absorber I2 |

La mission présente reste **documentaire : slices NONE, Architecture NOT REQUIRED / NOT RUN,
full reactor NOT RUN, PostgreSQL NOT RUN**. Les gates ci-dessus sont futures.

## 2. Inventaire et fichiers prévus (A04/A05)

Tous les chemins ci-dessous partent de la racine du dépôt. « Nouveau » signifie
prévu, inexistant à la référence ; aucun de ces fichiers applicatifs n’est créé ici.

| Existant à réutiliser | Responsabilité / limite |
|---|---|
| `app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/consumption/v3/ConsumptionEngine.java` | Préparation actuelle dans snapshot des effets ; protection finale, capacité de complétion, résolution et observation. Inchangé en I1 |
| `app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/consumption/v3/ConsumptionParticipant.java` | `Optional<Prepared> prepare(UUID)`, `finalizeIn`, `requireCompletionReady`, observation ; Prepared imbriqué |
| `app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/consumption/v3/SlotCompletion.java` | Capacité unique et bornée ; seul Engine supprime le slot |
| `app/port-transaction/src/main/java/com/kartaguez/pocoma/port/transaction/v3/AttemptTransactions.java` | Owner physique, poisoning, résolutions COMMITTED/ROLLED_BACK/UNKNOWN |
| `app/port-transaction/src/main/java/com/kartaguez/pocoma/port/transaction/v3/RecoveryReadContext.java` | Capacité de snapshot liée au callback/thread propriétaire |
| `app/infra-tx-spring/src/main/java/com/kartaguez/pocoma/infra/tx/spring/v3/SpringAttemptTransactions.java` | `requireSource`, `guarded`, `requireRead`, `closePublication` ; READ RR et final READ_COMMITTED |
| `app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/consumption/JdbcConsumptionSlotStore.java` | SQL générique L2 existant réutilisable sur datasource READ, malgré son module historique ; aucune copie de son protocole |
| `app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/v3/dev/V3TechnicalReadParticipant.java` | Exemple de séquence guarded → complete → close, pas un oracle métier ni le participant à activer |
| `app/infra-persistence-read-jdbc/src/main/resources/db/v3-consumption-read/migration/V1__consumption_foundation.sql` | Bootstrap L2 neuf, refuse les tables peuplées ; ne jamais modifier ni rejouer sur extension |
| `scripts/v3/bootstrap-l2-routing.sql`, `scripts/v3/grant-l2-lifecycle.sql`, `scripts/v3/grant-l2-discovery.sql` | Routage et privilèges techniques réels de fixture ; conserver leurs préconditions |
| `app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/v3/` | Les huit records métier, `Fraction`, `Amount`, `Weight` et identités associées : contenu exact à réutiliser, sans logique WRITE |
| `app/infra-persistence-primary-jpa/src/main/resources/db/v3-pot/migration/V1__pot_components.sql` | Schéma source vérifié : identité Pot et versions locales ; aucune exécution/mutation source I1 |
| `app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/ConsumptionLifecyclePostgresFixture.java` | Inspiration pour vrais claims, rôles, SQL indépendant ; fixture package-private, pas d’import de tests impossible entre modules |
| `app/infra-tx-spring/src/test/java/com/kartaguez/pocoma/infra/tx/spring/v3/AttemptTransactionsPostgresTest.java` | Oracles existants PID/XID, contraintes différées, poisoning et UNKNOWN ; ne prouve pas le nouveau store |
| `app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/CommitAcknowledgementProxy.java` | Banc filaire de perte d’ACK : adaptation test-only locale si inaccessible ; aucun proxy productif |

### Fichiers nouveaux proposés

Les packages Java suivent exactement les répertoires indiqués.

| Chemin exact | Contenu |
|---|---|
| `app/domain-projection/src/main/java/com/kartaguez/pocoma/domain/projection/v3/SubobjectKind.java` | Enum fermé des huit types, rang explicite |
| `app/domain-projection/src/main/java/com/kartaguez/pocoma/domain/projection/v3/SubobjectKey.java` | Clé immuable, ordre total explicite |
| `app/domain-projection/src/main/java/com/kartaguez/pocoma/domain/projection/v3/SubobjectContent.java` | Interface sealed avec huit records imbriqués contenant chacun le record Pot correspondant ; pas de `Object`, map JSON arbitraire ou registre extensible |
| `app/domain-projection/src/main/java/com/kartaguez/pocoma/domain/projection/v3/SubobjectVersion.java` | Paire clé/contenu validée, égalité complète |
| `app/port-projection/src/main/java/com/kartaguez/pocoma/port/projection/v3/SubobjectVersionStore.java` | Port étroit décrit au §3 |
| `app/port-projection/src/main/java/com/kartaguez/pocoma/port/projection/v3/SubobjectConflictException.java` | Divergence technique de contenu, clé sans payload sensible |
| `app/port-projection/src/main/java/com/kartaguez/pocoma/port/projection/v3/CorruptSubobjectException.java` | Forme persistée invalide ou incomplète ; jamais une absence normale |
| `app/infra-persistence-read-jdbc/src/main/java/com/kartaguez/pocoma/infra/persistence/read/jdbc/v3/JdbcSubobjectVersionStore.java` | JDBC dans owner READ appelant ; aucun begin/commit/rollback interne |
| `app/infra-persistence-read-jdbc/src/main/java/com/kartaguez/pocoma/infra/persistence/read/jdbc/v3/SubobjectJson.java` | Codec explicite schema_version=1, sans noms de classes Java |
| `app/infra-persistence-read-jdbc/src/main/resources/db/v3-read-business/migration/V2__subobject_version.sql` | Extension additive de la chaîne V3 READ V1, même historique Flyway (§4) |
| `app/domain-projection/src/test/java/com/kartaguez/pocoma/domain/projection/v3/SubobjectVersionTest.java` | Types, identité, égalité et ordre UUID |
| `app/infra-persistence-read-jdbc/src/test/java/com/kartaguez/pocoma/infra/persistence/read/jdbc/v3/SubobjectJsonTest.java` | Codecs positifs et corruptions |
| `app/runtime-task-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/task/v3/SubobjectStorePostgresTest.java` | Batches, conflits, concurrence et immutabilité physique |
| `app/runtime-task-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/task/v3/SubobjectMigrationPostgresTest.java` | Neuf/extension, catalogue et privilèges effectifs |
| `app/runtime-task-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/task/v3/SubobjectAtomicityPostgresTest.java` | Engine réel, readiness, fautes, résolution et témoins physiques |
| `app/runtime-task-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/task/v3/SubobjectPostgresFixture.java` | Participant technique privé/test-only, owners/slots réels, barrières, collecte SQL et proxy ACK local si nécessaire |

Adaptations **prévues**, sans nouveau module Maven :

- `app/domain-projection/pom.xml` : dépendance pure unidirectionnelle vers `domain-pot` pour réutiliser les records ; ne pas dupliquer Fraction ni modifier les transitions.
- `app/port-projection/pom.xml` : dépendance pure vers `port-transaction` pour le contexte read explicite.
- `app/infra-persistence-read-jdbc/pom.xml` : porter `port-projection` du scope test au scope compile, ajouter `infra-tx-spring` et le codec JSON utilisé par les adaptateurs V3 (dépendance Jackson explicite si absente de la fermeture compile).
- `app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/HexagonalArchitectureTest.java` : la règle actuelle `projectionContractsEngineDependsOnlyOnTheJdkAndProjectionDomain` exclut aussi `RecoveryReadContext`. Autoriser **ce seul contrat transactionnel pur dans le nouveau package de stockage v3**, avec contrôle négatif maintenant l’interdiction des frameworks/owners/Engine et des dépendances externes pour les ports historiques. Cette adaptation de frontière est déclarée ; elle ne modifie pas le protocole L2.

Le runtime PROJECTION possède déjà les dépendances L2, JDBC READ, PRIMARY et
infra-tx nécessaires au banc ; aucun export de test-jar PRIMARY ni ajout de
règle autorisant une dépendance de domaine vers infrastructure. Les constructions
sont inactives en production. L’emplacement historique des adaptateurs techniques
L2 n’est pas rationalisé maintenant (D1).

## 3. Contrats figés I1 (A04, partie I1 de A3)

### 3.1 Identité, contenu et égalité

`SubobjectKey(UUID potId, SubobjectKind kind, UUID objectId, long localVersion)` :
aucun null, version dans `[1, Long.MAX_VALUE]`. La clé SQL est exactement
**`(pot_id, kind, object_id, local_version)`**. `localVersion` est la révision de
la composante, jamais la version globale Pot, `revision_id` ou `active_from`.
Pour POT_HEADER/POT_DETAILS, **objectId=potId**, contrôlé en valeur et SQL.
Les modèles source ont bien huit tables de révisions avec cette identité ;
SPEC-01 §1 ne demande aucun nouvel identifiant métier pour les deux composantes Pot.

| kind (rang d’insertion) | Record Pot v3 réutilisé | Champs contractuels complets, outre l’identité Pot/objet portée et vérifiée |
|---|---|---|
| POT_HEADER (0) | PotHeader | deleted |
| POT_DETAILS (1) | PotDetails | label, creatorId obligatoire |
| SHAREHOLDER_HEADER (2) | ShareholderHeader | deleted |
| SHAREHOLDER_DETAILS (3) | ShareholderDetails | name, userId explicitement présent ou absent |
| SHAREHOLDER_WEIGHT (4) | ShareholderWeight | defaultWeight rationnel strictement positif |
| EXPENSE_HEADER (5) | ExpenseHeader | deleted |
| EXPENSE_DETAILS (6) | ExpenseDetails | label, date LocalDate |
| EXPENSE_FINANCIALS (7) | ExpenseFinancials | amount rationnel non négatif, payerId, ensemble non vide participantId → specificWeight optionnel strictement positif |

Chaque wrapper `SubobjectContent` expose le kind fixé par son type ; la valeur
`SubobjectVersion(SubobjectKey key, SubobjectContent content)` refuse tout
mauvais kind, Pot ou objet. Les collections sont détachées, copiées et immuables.
Les participants financiers sont des identités Shareholder, pas User ; les doublons
sont rejetés **avant** construction d’une map, suivant `ExpenseFinancials.of`.
I1 vérifie les identités contenues et le format ; la preuve d’appartenance du payeur
et des participants au Pot et à WRITE@V relève de la source I3, sans FK vers WRITE.

Égalité = clé et **tous** les champs typés ci-dessus, y compris absence/présence,
créateur, rattachement, date, payeur et toutes les participations/poids. Comparer
les valeurs exactes : chaînes sans trim/casse/normalisation Unicode ajoutée,
UUID par valeur, dates par date, rationnels canoniques BigInteger, maps comme
ensembles de couples. Aucun nouveau plafond métier sur les chaînes ou rationnels.
Le modèle Label/Name existant n’impose pas de non-vacuité : I1 n’en invente pas.

Exclus du contenu stocké et de l’égalité : `revision_id`, `activeFrom`, `activeTo`,
writer_xid source, PotVersion globale consommatrice, eventId/requestId,
projectedAt, horloge, compteur/token de tentative et métadonnées de transport.
`schema_version` décrit l’encodage, pas une différence métier ; I1 n’accepte que 1.
Aucun timestamp technique n’est nécessaire dans cette table. Une même clé
réutilisée par plusieurs événements garde strictement le même contenu.

### 3.2 Codec et contraintes de représentation

Colonnes d’identité séparées, `schema_version=1`, `payload` JSONB objet contenant
**seulement** les champs de la dernière colonne du tableau. Exemples de formes :
`{"deleted":false}`, `{"label":"Voyage","creatorId":"…uuid…"}`,
`{"name":"Alice","userId":null}`, `{"defaultWeight":{"n":"1","d":"2"}}`.
Financials : `{"amount":{"n":"0","d":"1"},"payerId":"…uuid…","participants":[{"shareholderId":"…uuid…","specificWeight":null}]}`.
La date suit `DateTimeFormatter.ISO_LOCAL_DATE` (exemple `2026-11-02`, sans plafond métier ajouté) ; UUID canoniques minuscules avec tirets.
Les tableaux sont triés par UUID PostgreSQL, sans doublon. Un poids présent est
un objet `{n,d}`, jamais un nombre JSON flottant. Entiers décimaux canoniques
(`0` ou entier non nul sans zéro initial, éventuellement négatif : `0|-?[1-9][0-9]*` ; pas de `+`, `-0`, exposant ni décimale), dénominateur >0, pgcd=1, zéro=0/1. Les bornes de signe
d’Amount/Weight restent celles du domaine ; aucune conversion via long/double.

Encodage/décodage explicites par les huit types. Rejeter schema inconnu, kind
inconnu, champ absent/inconnu, type incorrect, doublon de propriété JSON à
l’entrée du codec, null illégal, fraction non canonique, doublon de participant,
date/UUID mal formé et ordre non canonique des tableaux persistés. PostgreSQL
JSONB efface les doublons de propriétés d’un texte avant stockage : le codec doit
les refuser **avant** envoi ; il ne prétend pas détecter après coup ce que JSONB
a déjà effacé. Les modifications administratives cohérentes restent hors de
l’oracle runtime et sous la réserve de confiance PG existante.

Décoder vers les valeurs métier puis comparer ; **ni égalité de texte JSON,
ni égalité syntaxique JSONB, ni digest seul** ne vaut égalité métier. Des ordres
de propriétés objet différents décodent la même valeur. Les formes corrompues
ne sont jamais réparées/normalisées au chargement. Les constructeurs Fraction
normalisent les valeurs nouvelles ; le codec vérifie la forme persistée avant
de les appeler, pour ne pas cacher une corruption.

### 3.3 Port proposé et responsabilités transactionnelles

```java
interface SubobjectVersionStore {
    Map<SubobjectKey, SubobjectVersion> readAll(
        Collection<SubobjectKey> keys, RecoveryReadContext context);
    void insertOrVerifyAll(Collection<SubobjectVersion> candidates);
    void requireStored(Collection<SubobjectVersion> expected);
}
```

Les imports de valeurs viennent de `domain.projection.v3`, le contexte du port
transactionnel pur existant. Pas de méthode UPDATE/DELETE, de transaction dans
le port, de moteur générique ou de reçu de complétion.

- `readAll` : `SpringAttemptTransactions.requireRead(source, context)` obligatoire ; snapshot L2 READ RR actif, contexte exact de cette datasource, thread et callback. Retour map immuable des seules clés trouvées ; absentes = différence avec les clés demandées. Toute ligne invalide lève `CorruptSubobjectException`, jamais une fausse absence. Ce read READ-only suffit à I1, sans source WRITE et sans PreparationReads I2.
- `insertOrVerifyAll` et `requireStored` : intégralité de l’opération, y compris validation/codec, dans `SpringAttemptTransactions.guarded(source, ...)`. Constructeur concret `(DataSource source, SpringAttemptTransactions owner)` appelant `owner.requireSource(source)` ; JDBC utilise exactement cette source et le holder lié, sans connexion autonome/REQUIRES_NEW. L’owner possède seul la résolution. Aucune annotation transactionnelle du store.
- Collections sans null ni clé dupliquée (même valeur dupliquée refusée à la frontière, aucune déduplication silencieuse) ; vide autorisé sous garde active. Trier/copier une fois puis traiter par chunks de **256 clés maximum par requête** ; ce seuil de transport n’est ni limite métier du batch ni sous-transaction. Une erreur au dernier chunk invalide tout le batch et l’owner.
- `requireStored` relit réellement toutes les lignes attendues dans l’unité finale et vérifie leur égalité complète ; manque, divergence ou corruption = échec. Aucun succès sur un compteur d’insertions, une liste locale ou le simple retour de `insertOrVerifyAll`.
- Validation d’entrée : `IllegalArgumentException`/null refusé ; divergence : `SubobjectConflictException` ; corruption : `CorruptSubobjectException` ; SQL/owner : exception technique d’origine conservée. Toutes les erreurs de writer traversent la garde et empoisonnent irréversiblement l’owner, même interceptées par le participant. Les diagnostics donnent clé/type, sans copier le contenu.

La **readiness appartient au Prepared**, pas au store ni à un booléen fourni
par l’appelant : `requireCompletionReady()` appelle `store.requireStored(expected)`.
L2 l’invoque depuis `complete()` **avant** DELETE, puis exige l’appel unique et la
fermeture de publication. Le participant ne ferme qu’après `complete()` via
`SpringAttemptTransactions.closePublication(readSource)` ; ni événements READ,
ni suffixe dense à créer. Le store ne ferme pas l’unité et ne supprime jamais le slot.

### 3.4 Insertion concurrente et ordre

Sous le verrou final du slot déjà protégé par L2, traiter toutes les clés dans
l’ordre total : pot UUID, rang kind 0–7, objet UUID, localVersion croissante.
UUID = comparaison des **16 octets non signés** (équivalente à la chaîne UUID
canonique de largeur fixe), compatible `ORDER BY uuid` PostgreSQL.
`UUID.compareTo` Java signé est interdit pour cet ordre ; tester les frontières
`7fff…`/`8000…` et les parties basses. Tous les writers utilisent le même ordre,
y compris les chunks ; aucune acquisition de pointeur/garde avant les immuables.
I3 ajoutera PotVersion puis CurrentPot après ces insertions, pas en I1.

Pour chaque clé : `INSERT ... ON CONFLICT (pot_id,kind,object_id,local_version)
DO NOTHING`, puis **une nouvelle instruction SELECT** à READ_COMMITTED pour
lire/décoder/comparer la ligne effective. Même discipline pour les lignes
nouvellement insérées. L’unicité peut attendre le concurrent ; si celui-ci
committe, le SELECT suivant voit le contenu committé ; s’il rollback, l’insertion
peut gagner. Ne pas utiliser un unique CTE INSERT+SELECT dont le snapshot peut
précéder le concurrent. Une violation SQL 23505 provoquée autrement laisse la
transaction en erreur : pas de catch-and-continue dans une transaction abortée.
Aucun `DO UPDATE`, aucune réparation ni retry interne au claim.
Même clé/contenu = idempotence ; même clé/contenu divergent = poisoning et rollback
de tous les nouveaux effets et du DELETE du slot. Les lignes déjà committées
restent intactes. Deux slots distincts peuvent réutiliser une même sous-version.

## 4. Migration PostgreSQL CURRENT V3

**Version proposée disponible à la référence : V2**, localisation nouvelle
`classpath:db/v3-read-business/migration`, fichier du §2. La chaîne technique
READ V3 contient seulement V1 ; les V1–V14 historiques de `db/read-store/migration`
constituent une **autre chaîne**, jamais concaténée à celle-ci. Recontrôler les
versions au démarrage d’I1 et réserver la prochaine version libre si HEAD a évolué.

Employer le **même schéma READ non-public et le même historique Flyway V3
`flyway_schema_history`** avec les localisations cumulatives V1 L2 + V2 métier.
Pas de second historique non initialisé dans un schéma peuplé, de baselineOnMigrate,
clean, repair, modification de checksum ni réexécution de V1. L’initialisation
est administrative explicite ; aucune autoconfiguration historique ReadStore
activée pour installer cette extension. Le branchement runtime est I8.

| Objet | Définition attendue |
|---|---|
| `read_subobject_version` | `pot_id uuid NOT NULL`, `kind text NOT NULL`, `object_id uuid NOT NULL`, `local_version bigint NOT NULL`, `schema_version integer NOT NULL`, `payload jsonb NOT NULL` |
| PK | `(pot_id,kind,object_id,local_version)`, index btree exact/batch suffisant pour I1 ; aucun index GIN préventif |
| CHECK | local_version>0 ; schema_version=1 ; kind dans les huit valeurs exactes ; object_id=pot_id pour POT_HEADER/POT_DETAILS ; jsonb_typeof(payload)='object' |
| Formes SQL | Clés obligatoires/exclusives par kind, nullité et types de premier niveau conformes au §3.2 (boolean deleted, strings label/name/date/creatorId/payerId, userId string ou null, rationnel objet, participants array non vide). Écrire ces CHECK sans trou SQL NULL : `IS TRUE` sur les prédicats et présence explicite. Le codec garde les validations profondes, doublons, pgcd, UUID/date et signes |
| Immutabilité | Trigger BEFORE UPDATE OR DELETE toujours refusant, même UPDATE identique ; refus TRUNCATE par droits et trigger BEFORE TRUNCATE. Fonction trigger invoker avec search_path maîtrisé, aucune fonction SECURITY DEFINER ouvrant un contournement |
| FK | Aucune vers WRITE, PotVersion ou les slots ; les références métier externes ne sont pas des FK cross-authority |
| Permissions | Admin DDL propriétaire distinct ; rôle `expectedRuntime` writer métier SELECT/INSERT seulement sur cette table ; aucun nouveau rôle de consultation productif I1 (futur lecteur : SELECT seulement) ; PUBLIC sans privilège ; pas de CREATE de schéma ni ownership/héritage donnant UPDATE/DELETE/TRUNCATE/TRIGGER au runtime. Contrôle effectif de droits de table/colonnes, memberships et fonctions auxiliaires |

La migration exige les cibles explicites `expectedDatabase`, `expectedSchema`,
`expectedAdmin`, `expectedRuntime` suivant le bootstrap L2 ; refus des cibles
incorrectes et rôles dangereux. Les droits lifecycle/Discovery techniques demeurent
ceux des scripts L2 existants ; le nouveau refus métier ne doit pas retirer le
DELETE légitime de L2 sur **consumption_slot**. Aucun rôle source WRITE à ajouter I1.

Deux parcours indépendants dans `SubobjectMigrationPostgresTest` :

1. **Neuf** : cible jetable vide et rôle admin correct ; V1 L2 puis V2 métier avec localisations cumulatives ; scripts administratifs de routage/grants L2 ; contrôle du catalogue et des permissions ; tentative L2 réelle avec effets. Le rôle lecteur SELECT-only du test est une fixture créée explicitement par l’admin, pas une activation productive.
2. **Extension** : installer V1 seulement et les scripts existants, peupler routes/ownership/checkpoints/slots valides et prendre leurs empreintes ; relancer Flyway avec V1+V2. Seule V2 est appliquée. Vérifier mêmes lignes L2, définitions/contraintes/index/droits conservés et historique V1 intact ; V2 enregistrée une fois. Réexécution sans pending = zéro changement. Prouver séparément que lancer V1 sur la cible peuplée sans historique valide est refusé, sans reset ni contournement.

Comparer catalogues neuf/extension indépendamment (hors données et historiques
attendus), essayer les droits avec de vraies connexions runtime, puis lire des
sous-versions et slots conservés après reconnexion. Aucune migration V2 historique,
aucune baseline TRUSTED inventée. Les migrations historiques tirées par les gates
Maven restent rapportées comme transitive history, sans les masquer.

## 5. Preuve transactionnelle avec L2 réel (A05)

Banc dans le runtime PROJECTION, sans démarrer de Worker métier : PostgreSQL
réel, autorité READ V3, `SpringAttemptTransactions` réel avec
`DataSourceTransactionManager` READ non nested et la datasource READ unique ;
`JdbcConsumptionSlotStore` et `ConsumptionEngine` réels. La capacité/claim et
les leases du banc utilisent les ports L2 existants, pas une suppression SQL
manuelle. Le participant est technique et **test-only** : son prepare actuel
retourne des candidats détachés, sans lire WRITE ; son observation bornée
contrôle ces effets dans le contexte READ. Aucun changement du recovery L2 pour
faire passer ce test. Il n’imite pas les futurs cas de contenu préexistant I2.

Séquence nominative minimale :

1. Bootstrap, route READ native, lease valide, slot enrôlé et claim acquis via stores/Engine L2 ; archiver token/partition et SQL extérieur. Les seeds événement/checkpoint sont déclarés fixtures techniques ; ils ne qualifient pas Discovery métier.
2. Engine ouvre sa transaction finale READ_COMMITTED et `protectFinal` verrouille le slot, contrôle token/état/horloge fraîche ; une session concurrente démontre que le verrou reste tenu jusqu’à résolution.
3. Prepared appelle `insertOrVerifyAll`. Pas d’owner ouvert par le store, de datasource secondaire ou de transaction simulée.
4. `completion.complete()` appelle la vraie readiness Prepared : `requireStored` relit le contenu présent dans l’owner ; en cas d’échec, L2 poison et refuse la complétion.
5. L2 exécute `deleteProtected`, exactement le slot/token protégé et une ligne ; puis le participant ferme la publication READ.
6. Engine exige complétion unique et publication fermée ; l’owner seul résout physiquement. Une lecture extérieure après résolution constate simultanément les effets exacts et le slot absent, ou l’absence des nouveaux effets et le slot conservé au rollback.

### Oracle physique indépendant

Installer **après V1/V2**, dans la seule fixture, une table de témoins et des
triggers de test : AFTER INSERT de `read_subobject_version` et AFTER DELETE de
`consumption_slot`, enregistrant opération/clé, `pg_backend_pid()` et
`pg_current_xact_id()::text` (XID complet), base/schéma et isolation. Pas de colonne,
trigger ni reçu supplémentaires dans la migration productive. Les témoins sont
transactionnels : ils doivent disparaître au rollback. Capturer aussi le PID/XID
à l’intérieur de l’owner (journal externe de fixture) pour les cas rollback.

Exiger pour chaque nouvelle ligne et DELETE le **même PID et même XID complet**
que l’owner, et le même READ authority. Deux lectures extérieures fraîches en
snapshot cohérent : pendant la barrière avant commit, ancien slot et aucun
nouvel effet visibles ; après commit, tous effets et aucun slot. Ne pas observer
le slot et les effets dans deux snapshots indépendants de part et d’autre du commit.
Au rollback, comparer l’état antérieur entier (y compris immuables préexistants).

**Contre-oracle obligatoire** : une variante test-only volontairement fautive
écrit l’effet par une connexion/transaction indépendante, puis fait rollback de
l’owner après la tentative DELETE ; l’oracle doit rejeter le PID/XID différent
et l’effet orphelin. Une seconde variante indépendante committant les deux unités
est également rejetée par les témoins, même si l’état final semble correct.
Cela qualifie l’oracle, sans introduire ce chemin dans le store productif.

### Frontières de faute obligatoires

Injecter avant insertion, après premier chunk, après insertions, pendant readiness,
avant/après DELETE, après fermeture et avant commit ; ne pas modifier l’ordre
productif. Ajouter violation de contrainte **DEFERRABLE INITIALLY DEFERRED**
installée par la fixture, rejetée au vrai commit après DELETE, puis perte d’ACK
sur protocole PostgreSQL réel avant COMMIT et après commit serveur. Une simple
exception Java après un commit connu n’est pas une preuve de perte d’ACK.

Observer après UNKNOWN, conserver la résolution historique UNKNOWN même si les
effets sont ensuite constatés ; aucune cession, nouvel essai ou libération de
capacité déduits du seul retour local. Une observation impossible reste UNAVAILABLE.
Ces contributions bornées utilisent les garanties L2 actuelles ; les tables de
décision générales et préparation source de recovery restent I2. Un défaut L2
réel découvert bloque le critère I1 concerné et exige déclaration de correction,
plutôt qu’une simulation favorable ou un report silencieux.

## 6. Matrice de tests discriminants

Classes : **V**=`SubobjectVersionTest`, **J**=`SubobjectJsonTest`,
**S**=`SubobjectStorePostgresTest`, **M**=`SubobjectMigrationPostgresTest`,
**A**=`SubobjectAtomicityPostgresTest` (chemins exacts §2).
Les identifiants ci-dessous sont les méthodes prévisionnelles à nommer avant code.
Tous sont **NOT RUN**, aucune absence d’exception ne suffit à établir PASS.

| Invariant / méthode prévue | Positif | Contre-exemple négatif | Oracle indépendant | Classe | Preuve PostgreSQL attendue |
|---|---|---|---|---|---|
| Clé/type — `keyAndKind` | Huit types, Pot objectId=Pot | Type/identité/version locale incorrects | Tableau SPEC-01 + tuples littéraux | V, M | PK/CHECK refusent 0, null, kind inconnu et mauvais objet Pot |
| Contenu complet — `allFieldsMatter` | Même valeur détachée | Changer un seul champ de chacune des huit variantes | Fixtures explicites attendues, pas JSON du writer | V, S | Ligne originale byte/contenu conservée |
| Codec — `strictCodec` | Dates, null optionnels, grands rationnels, ordre des propriétés différent | Champ inconnu/manquant, doublon, faux type, fraction non canonique, UUID/date corrompus | JSON littéral indépendant et valeurs rationnelles attendues | J, S | JSONB injecté par admin refusé au décodage, pas absence |
| Ordre — `postgresUuidOrder` | Octets non signés, plusieurs Pots/kinds | Comparator Java signé, collision 7fff/8000 | `ORDER BY uuid` réel | V, S | Même ordre DB/valeurs ; batches inversés ne prennent pas de verrous opposés |
| Idempotence — `identicalInsert` | Seconde insertion identique | Différence de payeur/poids/userId/date/label | SELECT par connexion extérieure et contenu littéral | S | Une ligne, aucun UPDATE ; slot courant peut encore être présent |
| Divergence — `divergentInsert` | Clé différente acceptée | Même clé contenu différent | État avant/après et owner rollback | S, A | Aucun nouveau contenu ni DELETE committé |
| Courses — `concurrentIdentical` | Deux slots, même clé/contenu | Pas de comparaison après conflit | Barrières + vrai index unique + compteurs extérieurs | S, A | Une ligne, deux complétions indépendantes conformes |
| Courses — `concurrentDivergent` | Gagnant commit ou rollback contrôlé | Perdant accepte contenu divergent | SQL committé gagnant, vérification champ sentinelle | S, A | Gagnant conservé, perdant rollback/slot présent ; après rollback gagnant, insertion de l’autre possible |
| Snapshot SQL — `conflictThenCommittedRead` | Concurrent commit après début INSERT perdant | CTE à snapshot antérieur / candidat local comparé à lui-même | Barrière de commit et SELECT séparé observé | S | Lecture du **contenu réellement committé**, divergence détectée ; ni faux manque ni faux succès |
| Batch — `batchAllOrNothing` | >256 clés, lecture absentes explicite | Doublon ou dernière clé divergente | Inventaire attendu complet et état préexistant | S, A | Zéro nouveau chunk au rollback ; aucune sous-transaction |
| Immutabilité — `physicalImmutability` | SELECT/INSERT runtime | UPDATE identique/différent, DELETE/TRUNCATE, héritage/PUBLIC excessif | Sessions aux rôles réels + pg_catalog | M, S | Refus SQL, données inchangées |
| Owner — `ownerAndContext` | Contexte READ exact et final owner READ | Autocommit, owner WRITE/second manager, contexte étranger/expiré/autre thread | PID/XID et holder source, état extérieur | S, A | Aucun effet hors owner ; writer refusé empoisonne l’unité active |
| Readiness — `actualReadiness` | Préfixe complet relu | Omettre une ligne, falsifier contenu ou tenter complete sans readiness effective | Espion de test sur ordre + SQL réel | A | Aucun DELETE/effet partiel committé ; aucune confiance en booléen local |
| Erreur interceptée — `caughtFailurePoisons` | Flux normal | Catch divergence/validation/SQL puis tentative complete/close | Résolution owner et état SQL après | A | ROLLED_BACK ; slot persistant et préexistants inchangés |
| Atomicité — `effectsAndSlotSameUnit` | Engine réel jusqu’au commit | Connexion/transaction indépendante, variantes §5 | Témoins triggers PID/XID + snapshots extérieurs | A | Même PID/XID pour nouveaux effets et DELETE, contre-oracle rejette les deux unités |
| Rollback — `rollbackAfterDelete` | Commit complet | Faute après tentative DELETE et aux autres coupures §5 | Ancien état et verrou extérieur | A | Slot restauré et nouvelles sous-versions/témoins absents |
| Différé — `deferredCommitRejection` | Contrainte satisfaite | Violation différée après DELETE | SQLSTATE 23 et observation fraîche | A | ROLLED_BACK, ni effet ni DELETE durable |
| Publication — `publicationClosure` | complete puis close une fois | Fermeture précoce/omise, writer tardif, double/missing complete, capacité expirée ou autre thread | Engine et état SQL, erreur même interceptée | A | Rollback de toute unité incorrecte |
| Incertitude — `lostCommitAck` | Observation d’unité committée | Perte d’ACK avant/après COMMIT, observateur indisponible | Proxy filaire + backend/SQL extérieur | A | Zéro ou une unité complète ; UNKNOWN conservé ; pas de replay, double effet ou cession supposée |
| Migration — `freshAndExtension` | Neuf + V1 peuplée → V2 | Mauvaise cible, V1 rejouée, droit dangereux | Catalogue/empreintes et connexion runtime | M | V1 intacte, V2 une fois, lignes L2 préservées et schémas métier équivalents |

Contributions aux obligations CON-04/06 et immutabilité PRJ-01 seulement : I1 ne
certifie pas PRJ-01 complet ni les six matérialisations. Ajouter aux tests les
variantes de faute, type et concurrence énumérées, sans les réduire à une méthode
nominale unique. Les tests d’architecture conservent aussi un témoin négatif de
dépendance interdite, sans élargir globalement leurs allowlists.

## 7. Ordre de réalisation futur et gates

Après **GO humain distinct**, recontrôler Git/versions/dépendances, déclarer la
frontière §1, puis réaliser : valeurs/tests purs → port et règles de dépendance →
codec → migration neuf/extension et droits → store idempotent/concurrent →
preuve Engine/owner/slot → fautes physiques → gates et dossier de preuves → audit
indépendant du code. Aucun de ces pas n’attend I2 ; I2 consomme le stockage validé,
puis I3 réalise les participants. I4/I5/I6/I7 et activation I8 restent alloués au
plan global ; aucun chemin WRITE de préparation I2 n’est caché dans la fixture I1.

Commandes **futures**, depuis `app/`, sans paralléliser les campagnes PostgreSQL :

```sh
# Feedback pur, borné, ne remplace aucune gate
./mvnw -pl domain-projection -am -Dtest=SubobjectVersionTest -Dsurefire.failIfNoSpecifiedTests=false test
# Feedback codecs, après ajout des dépendances et compilation dans le même réacteur
./mvnw -pl infra-persistence-read-jdbc -am -Dtest=SubobjectJsonTest -Dsurefire.failIfNoSpecifiedTests=false test
# Campagne CURRENT V3 nominative : toutes les méthodes/variantes des trois classes
./mvnw -pl runtime-task-consumption-worker -am -Dtest=SubobjectStorePostgresTest,SubobjectMigrationPostgresTest,SubobjectAtomicityPostgresTest -Dsurefire.failIfNoSpecifiedTests=false test
# Gate d’acceptation I1 primaire, non filtrée
./mvnw -pl runtime-task-consumption-worker -am test
# Gate globale justifiée par les dépendances/règle de port modifiées
./mvnw -pl architecture-tests -am test
```

Les sélections `-Dtest` sont du feedback/contributions, jamais une dispense des
deux gates non filtrées. `failIfNoSpecifiedTests=false` sert seulement aux modules
amont sans classe sélectionnée : vérifier la présence fraîche de **chaque** méthode
et variante attendue dans les XML ; zéro test ne vaut jamais PASS. Les méthodes
peuvent déjà être incluses dans la slice : attribuer cette exécution au lieu de
relancer mécaniquement la même campagne. `verify.py FAST read` n’existe pas ;
aucune recette/outillage nouveau en I1. Un refus d’inventaire ou skip demeure
INCONCLUSIVE/NOT RUN selon la politique, sans dérogation L3 transposée.

**Publication de preuves I1** : commandes exactes, SHA exécuté et dirty state,
inventaire source/tests/SQL/configuration, JDK/Maven/PG/image/rôles, XML frais,
logs, catalogue neuf/extension et témoins PID/XID avant/après, contre-exemples,
verdicts et limites, empreintes et artefacts accessibles dans le dépôt selon les
conventions de rétention. Aucun reçu productif supplémentaire. Les archives
historiques ne prouvent pas ces nouveaux tests. La [réutilisation A09 du plan global](L4_Read_Inventory_and_Implementation_Plan.md#115-capitalisation-fondationbranchement)
reste conditionnelle et ne remplace aucune gate fraîche I1.

**Reporté** : PreparationReads, recovery généralisé et non-régression WRITE liée
à cette évolution en I2 ; manifestes/participants I3–I7 ; ressources six participants,
vrais Workers PROJECTION/monolithe et démarrage/drainage/reprise en I8 ; HTTP L5,
reconstruction/capacité/SLO complets L6. Full reactor non systématique I1 ; si
l’impact réel devient global au sens §§17/19, arrêter, expliquer et redéclarer
le gate requis avant poursuite. Une simple difficulté de test ne justifie pas FULL.

## 8. Clôture objective et arbitrages restants

I1 est clôturable seulement si : huit types et clés conformes ; codecs stricts ;
insertion identique idempotente, divergence détectée (y compris après conflit
concurrent) ; aucune mutation silencieuse ; batch entier atomique ; permissions
et protections immuables réelles ; effets et DELETE prouvés dans **la même unité
physique READ L2** ; erreurs interceptées, readiness manquante, mauvaise fermeture,
rollback, contrainte différée et ACK perdu éprouvés ; migrations neuve/extension
vérifiées ; gates obligatoires PASS selon la politique ; aucun franchissement
inattendu non résolu ; preuves et limitations archivées et attribuées ; audit
indépendant du code et des preuves, puis décision humaine distincte.

**A3 partie I1 est figée** par §3 : clé, types, égalité, codecs, SQL et ordre.
Le manifeste PotVersion complet du plan global est une spécification anticipée
pour I3, sans classe/table PotVersion en I1. A1 concerne I2, A2 I8, A4 la
réutilisation probatoire à chaque incrément ; aucun ne force I2 avant I1.
Le choix du numéro V2 est à revérifier si la branche avance ; c’est un contrôle
de collision, pas un contrat métier ouvert. Aucun arbitrage normatif bloquant I1
n’a été identifié. Les écarts historiques d’attribution A09 sont documentés,
sans rectification des rapports originaux, ni certification générale L2/L3.

Ce document est un contrat proposé **directement implémentable après revue et GO**,
ni un résultat de test ni une autorisation. **READY FOR I1 IMPLEMENTATION REVIEW.**
