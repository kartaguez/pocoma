# Pocoma V3 — L5/I3 — Rapport d'exécution

> **Clôture humaine du 5 novembre 2026 : L5/I3 — CLOSED — TARGETED LOCAL PASS WITH EXPLICIT RESERVATIONS.** L’[acte autonome](L5_I3_Acceptance_and_Closure_Decision.md) accepte exclusivement les preuves ciblées historiques de ce rapport : FAST 13/13 PASS ; PostgreSQL/HTTP 25/25 oracles par preuve composite, jamais une campagne intégrale 25/25 PASS ; Bruno Keycloak réel 10/10, 70 requêtes ; 165,687 s cumulées. AUD-I3-01/02/03, R-Q1…R-Q5 et réserves héritées maintenues. Gates WEB+COMMAND non filtrée, Architecture et full **NOT RUN / DEFERRED**, obligations non acquittées. **L5 OPEN ; I4 ELIGIBLE FOR FRAMING, NO GO.** Aucun rejeu ni qualification au SHA documentaire.
>
> **Rapport historique inchangé ci-dessous :** les mentions I3 NON CLOSED et de décision attendue décrivent l’état avant cet acte ; elles ne sont plus le statut courant. Les résultats FAIL, diagnostics, corrections et provenance restent intacts.

5 novembre 2026. **IMPLEMENTED — TARGETED QUALIFICATION PASS — QUALIFICATION CANONIQUE INCOMPLETE.** I3 **NON CLOSED** ; décision humaine distincte de clôture attendue, L5 OPEN. Aucun certificat SPEC-10 global.

## Mandat et Git

GO humain I3 explicite sur le [plan](L5_I3_Simplified_Headless_Link_Analysis_and_Plan.md), H1 A-READ et H2–H5 ratifiées ; aucune nouvelle approbation demandée. Départ local/distant vérifié identique : **`8ff6722220049f3ce537b2f4aeeb2ffa725f4619`**, branche `feat/v3-architecture`, worktree propre. Aucun changement intervenu depuis le commit documentaire communiqué. Commandes initiales : `git status --short`, `git branch --show-current`, `git rev-parse HEAD`, `git remote -v`, `git ls-remote origin refs/heads/feat/v3-architecture`. Le réseau Git a nécessité une sortie du sandbox, puis le contrôle distant a réussi.

Le commit qui porte ce rapport fige les sources qualifiées ; son SHA de publication, égalité locale/distante, divergence et état final sont donnés au reçu Git de livraison. Les tests ont réellement tourné sur HEAD initial + modifications inventoriées : on ne prétend pas un run ultérieur au SHA de commit. Hashes des sources finales et deltas de réemploi dans le [manifeste](../testing/evidence/L5-I3/manifest.json).

## Livraison et frontières

Slices déclarées **avant code** : primaire **WEB** (`runtime-web-api`), secondaire **COMMAND** (`runtime-command-consumption-worker`), monolithe racine d'assemblage ciblé. DB **CURRENT V3**. Impact prévu/observé : proposition/ACL/admission/HTTP/composition. Franchissement inattendu : **NONE**. Aucun POM/module/runtime ajouté, aucune projection modifiée, aucune transition Identity WRITE ni mécanisme L2 modifié. `V3ConsumptionWorkerConfiguration` n'ajoute que les deux types autorisés dans l'assemblage HTTP monolithe.

Fichiers productifs applicatifs (chemins depuis la racine) :

- `app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/v3/V3AdmissionHttpConfiguration.java`
- `app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/v3/V3ConsumptionWorkerConfiguration.java`
- `app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/v3/V3ReadHttpConfiguration.java`
- `app/contracts-registration/src/main/java/com/kartaguez/pocoma/contracts/registration/v3/LinkProposal.java`
- `app/engine-admit-registration/src/main/java/com/kartaguez/pocoma/engine/admit/registration/v3/AdmitLinkProposal.java`
- `app/engine-admit-registration/src/main/java/com/kartaguez/pocoma/engine/admit/registration/v3/LinkProposalInput.java`
- `app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/AdmissionSqlAuthority.java`
- `app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/JdbcAdmissionStore.java`
- `app/infra-persistence-primary-jpa/src/main/resources/db/v3-admission/migration/V9__link_proposal.sql`
- `app/supra-http-read/src/main/java/com/kartaguez/pocoma/supra/http/read/v3/V3ReadTransport.java`
- `app/supra-http-write/src/main/java/com/kartaguez/pocoma/supra/http/write/v3/V3AdmissionTransport.java`
- `app/supra-http-write/src/main/java/com/kartaguez/pocoma/supra/http/write/v3/V3LinkProposalController.java`

Le script `scripts/v3/grant-l5-admission.sql` fournit les droits V9 minimaux. La fixture Keycloak existante reçoit seulement deux utilisateurs locaux supplémentaires (E1 reste `bruno`, E2 `link-e2`, E3 `link-e3`). `scripts/functional-auth-proof.py` réemploie le login PKCE existant avec paramètres d'utilisateur ; `scripts/testing/l5-i3-auth.py` obtient trois sessions indépendantes et écrit le fichier éphémère mode 0600, supprimé après parcours. Aucun serveur d'autorisation nouveau ni configuration multi-issuer prétendue.

**Protocole livré :** create E1 201 avec clé S-03 et `{bindingToken,target:{issuer,subject}}` ; GET aux seules E1/E2 ; accept E2 exclusivement, corps strictement vide sans nouvelle clé client, 202 après commit confirmé. Location pointe vers la proposition. Résumé allowlist E1/E2/action/dates/état/requestId éventuel ; `potCount`/date null. JWT et tokens exclus des DTO. Même id UUID pour proposition puis IdentityRequest à la seule admission ; deux natures distinctes. `POST /identity/requests` n'accepte toujours pas Link.

**H1/R1 :** aucun contrôle READ métier bloquant ou fallback WRITE ; capture du token déclaré, obsolescence acceptée. Proposition et admission possibles même après perte d'autorité E1 ; L3 rejette normalement. **H2 :** le POST explicite E2 authentifiée constitue le consentement sur cette intention immuable. E1 reste `requesterEui` et propriétaire du résultat ; jamais de faux principal E1. L'acceptation ne fait qu'une admission PENDING, sans slot/événement/effet métier synchrone ; Discovery/Consumption puis L3 et READ existants réalisent le parcours.

## Migration, ACL et garanties

V9 additive après V8, migration historique inchangée : table `v3_link_proposal`, PK id, scope HMAC unique, occurrence E1 et EUI exactes immuables, métadonnées AuthN E1 sans JWT, création/expiration, acceptation/id d'admission liés par CHECK de nullité/id/temps. FKs différées vers demande et association S-03 ; association UNIQUE sur id/catégorie/opération/empreinte. Colonne technique nullable `v3_identity_request.link_proposal_id`, CHECK spécifique Link HTTP et FK inverse : une admission publique Link exige la proposition exacte ; les origines internes restent valides sans proposition.

**R3 — simplification :** références UUID et empreinte fixe, aucun index incluant le token ou les EUI opaques longs. Deux triggers seulement : (1) immutabilité/absence de suppression/transition unique/échéance au marquage ; (2) contrôle différé sur INSERT IdentityRequest Link HTTP, comparant exactement E1/token/E2, corrélation, empreinte, instant et provenance E2 à la proposition acceptée. Les FK/unique/CHECK assurent le reste ; pas de trigger différé redondant côté proposition. Le trigger inverse ne s'exécute pas sur la transition de statut L3. Le guard existant d'IdentityRequest protège aussi la nouvelle colonne immuable.

Rôle admission distinct, aucun droit d'autorité EUI/token métier ; lecture de la proposition technique, INSERT et UPDATE limités aux deux colonnes d'acceptation. Préflight effectif colonnes/PUBLIC/héritage/objets indirects et réglages logs réemployés/étendus. Aucun SECURITY DEFINER nouveau. Token E1 stocké sous ACL, non retourné ; token E2 seulement par son CurrentBinding authentifié. Lecture résultat E1 inchangée et E2 masquée. Autorisation exacte avant état/expiration ; UUID seul sans pouvoir.

Acceptation : propriétaire physique I2 inchangé, verrou de ligne proposition → association → demande, horloge PostgreSQL `clock_timestamp()` après attente et revalidation de l'expiration JWT E2. Première acceptation expirée refusée ; reprise admise retrouve les mêmes ids sans réappliquer le TTL/les nouvelles règles, même après terminal/Unlink. Une seule admission physique concurrente ; rollback sans demi-acceptation ; commit perdu/inconnu rend 503 sans résultat inventé et réutilise l'observation physique/le maintien de permis I2 si nécessaire.

**R2 — rétention :** aucune purge, croissance acceptée. Future décision nécessaire sur rétention/restauration/anti-rejeu ; aucune maintenance ajoutée. TTL initial **600 s**, configurable `pocoma.v3.http.admission.link-proposal-ttl-seconds` **1–86400 s**. Pas de projection/compteur de Pots nouveau. **R4 :** campagnes discriminantes seulement, budget strict ci-dessous ; aucune gate large.

## Qualification effective et budget

[Preuves, archives et reçus exacts](../testing/evidence/L5-I3/README.md). Java Temurin 21, Maven 3.9.14, PostgreSQL de test **17.11**, image digest retenu du dépôt ; Keycloak **26.7.0** et PostgreSQL IdP **16.6** du compose existant, projet jetable `pocoma-l5-i3`. Aucun baseline TRUSTED supposé : fixtures rejouent la chaîne CURRENT V3 pertinente V1–V9 (READ V3 V1–V7), pas les migrations historiques V2 V1–V23.

Toutes les durées ci-dessous incluent découverte, compilation éventuellement requise, Maven et collecte, mesurées par le superviseur externe. Les plafonds s'appliquent cumulativement aux reprises. La compilation préparatoire sans test (7,150 s) et la reconstruction propre sans test (20,160 s) sont séparées du budget de campagnes.

| Passage | JUnit tests/failures/errors/skips | Durée s | Résultat exact |
|---|---|---:|---|
| 1 — FAST `pocoma-l5-i3-l5-i3-fast` | 13/0/0/0 | 4.149570 | PASS |
| 2 — PG `pocoma-l5-i3-l5-i3-pg` | 18/1/1/0 | 21.627728 | FAIL |
| 3 — PG `pocoma-l5-i3-l5-i3-pg-corrected` | 19/0/1/0 | 35.982907 | FAIL |
| 4 — FAST `pocoma-l5-i3-l5-i3-fast-final` | 13/0/0/0 | 4.300984 | PASS |
| 5 — PG `pocoma-l5-i3-l5-i3-pg-fresh` | 25/1/0/0 | 53.422646 | FAIL |
| 6 — PG `pocoma-l5-i3-l5-i3-http-correction` | 1/0/0/0 | 13.093016 | PASS |
| 7 — BRUNO `pocoma-l5-i3-l5-i3-bruno` | 1/1/0/0 | 10.740757 | FAIL |
| 8 — BRUNO `pocoma-l5-i3-l5-i3-bruno-corrected` | 1/0/0/0 | 22.369118 | PASS |

**Cumul : FAST 8.450554 s / 120 ; PG 124.126297 s / 480 ; Bruno 33.109874 s / 600 ; total 165.686726 s / 1200 (2 min 45,69 s).** Aucune enveloppe dépassée, aucune extension. Total réellement exécuté : **26 invocations FAST**, **63 PG/HTTP**, **2 lanceurs JUnit Bruno**, soit **91 invocations JUnit : 86 réussies, 3 failures, 2 errors, 0 skip**. Ces totaux conservent les tentatives en échec, sans les convertir en PASS.

**Couverture finale composite : 13/13 FAST ; 25/25 scénarios PG/HTTP (24 PASS `pg-fresh` + 1 oracle corrigé PASS séparé) ; 10/10 scénarios Bruno réels, 70 requêtes HTTP (10 principales + 60 helper), lanceur final PASS.** Le run 25 tests reste historiquement FAIL (24 réussites/1 échec), il n'est pas rebaptisé PASS. Aucun delta productif/POM/migration entre le FAST final, les 24 contributions PG réemployées et le lanceur final ; seuls le test de verrou HTTP et la signature du lanceur Bruno changent ensuite. Les comparaisons de hashes et l'attribution nominative sont conservées, conformément au §34.

Les dix méthodes FAST nouvelles prouvent formes fermées/EUI exactes/absence de consentement implicite/masquage/provenance deux acteurs/expiration/reprise/S-03/DTO/absence de port WRITE ; trois méthodes L3 existantes conservées. PG couvre intention longue sans index large, contraintes/substitution, ACL/logs runtime, course par connexions indépendantes, expiration proposition/JWT après verrou, rollback aux frontières et commit, commit perdu/observateur indisponible ; L3 réel couvre E1 obsolète avant création/après création/après admission (réoccurrence ne réhabilite pas le token), cible devenue liée, propositions distinctes et replay après terminal/Unlink. HTTP vérifie JWT signés/falsifiés, issuer/audience/temps, masquage, transport et absence d'effet synchrone ; compositions WEB/monolithe réelles passent.

Bruno exécutés : **B301, B302, B303, B304, B305, B307, B308, B309, B310, B313**. Sessions Keycloak réellement indépendantes, issuer `http://localhost:8081/realms/pocoma`, audience `pocoma-api`, claims `sub/iat/exp/auth_time` contrôlés, signatures validées via JWKS par Resource Server ; altération de signature et absence de bearer refusées. Les négatifs mauvais issuer/audience/expiration signés sont qualifiés localement en HTTP, **pas comme jetons Keycloak expirés réels**. Aucun multi-issuer opérationnel inventé. SQL indépendant final : **1 proposition, 1 demande Link, 1 association Link, 2 résultats CHANGED et 2 versions (Registration E1 + Link E2)**. E1 lit son résultat ; E2 est refusée sur ce résultat et lit son seul binding.

## Diagnostics et corrections conservés

1. PG initial : 18 exécutés, 16 PASS, un FAIL d'oracle logs portant sur l'ensemble du conteneur et un ERROR dû à une lecture d'autorité L3 dans une transaction READ. Correction : oracle runtime à canary distinct (avec faute sous rôle admission réellement protégé) et observation SQL indépendante de l'absence de mutation E2. Les canaries administrateur des premiers essais restent archivées ; aucune qualification des logs admin/proxy n'est inférée.
2. Références SQL corrigées avant la suite : l'index composite initial token/EUI aurait pu dépasser la taille d'index PostgreSQL ; remplacé par références UUID et comparaison différée. Test discriminant d'intention longue ajouté, sans nouvelle slice.
3. PG suivant : 18 contributions SQL/L3 PASS, échec composition monolithe sur bytecode préexistant `JdbcPotVisibilityStore` contenant `Unresolved compilation problems`. Sources READ inchangées ; `clean test-compile` de la fermeture ciblée puis preuve fraîche des deux compositions, sans refactor READ.
4. Passage reconstruit : 24/25 PASS ; oracle HTTP `ACCESS EXCLUSIVE` trop fort pour les métadonnées `%rowtype` des constraints existantes. Correction vers `EXCLUSIVE`, interdisant les RowShare/verrous métier et écritures, autorisant seulement AccessShare de métadonnées ; ACL interdisant toujours les lectures métier. Reprise de **ce seul test**, PASS. Aucun oracle métier supprimé.
5. Premier lanceur Bruno : FAIL avant Bruno, `Path.open(opener=...)` invalide ; corrigé avec `open(..., opener=...)`. Paramètre `TestInfo` retiré du nom JUnit via `@BeforeEach` pour permettre l'attribution exacte du collecteur. Aucun PASS de scénario sur ce passage ; lanceur corrigé, dix scénarios frais PASS.

## Commandes et reports

Commandes préparatoires depuis `app/` (aucun test) :

```sh
./mvnw --batch-mode --no-transfer-progress -pl runtime-web-api,runtime-monolith -am test-compile
./mvnw --batch-mode --no-transfer-progress -pl runtime-web-api,runtime-monolith -am clean test-compile
```

Commandes de qualification (sélections exactes natives et répertoires isolés dans chaque `plan.json`) :

```sh
python3 -B scripts/testing/verify.py FAST L5-I3-FAST --dry-run
python3 -B scripts/testing/verify.py INTEGRATION L5-I3-PG --dry-run
python3 -B scripts/testing/verify.py INTEGRATION L5-I3-BRUNO --dry-run
python3 -B scripts/testing/verify.py FAST L5-I3-FAST --output <repertoire-neuf>             # 2 passages
python3 -B scripts/testing/verify.py INTEGRATION L5-I3-PG --output <repertoire-neuf>        # 3 passages corrigés
python3 -B scripts/testing/verify.py INTEGRATION L5-I3-HTTP-CORRECTION --output <repertoire-neuf>
python3 -B scripts/testing/verify.py INTEGRATION L5-I3-BRUNO --output <repertoire-neuf>     # 2 lanceurs
```

Le superviseur archivé `/tmp/pocoma-l5-i3-budget.py` applique les plafonds cumulatifs ; argv/répertoires/durées réellement utilisés sont conservés dans le manifeste, sans masquer les commandes Maven. Aucune exécution `-amd`. Les anchors PG sont WEB+monolithe avec **filtre positif**, pas la gate WEB+COMMAND non filtrée. Bruno natif, depuis `scripts/bruno-v3`, avec variables bearer hors argv/Git :

```sh
node_modules/.bin/bru run -r '03 Link Requests' --env Local --sandbox developer --noproxy --reporter-json <sortie-absolue-neuve>/bruno.json --reporter-skip-all-headers --reporter-skip-body
```

Le mode Local désigne le transport vers le monolithe jetable ; **les JWT sont ceux du vrai Keycloak**, pas le JWKS local de la fixture. Le helper enregistre méthode/route/statut sans corps ni headers ; les réponses sont réellement vérifiées, sans PASS fondé sur le seul exit Maven/CLI. Claims expurgés, XML, reçus nominaux et SQL archivés ; aucun fichier bearer conservé, scan des archives sans JWT sérialisé. `git diff --check` vérifié avant livraison ; contrôle final des fichiers/contrats et Git après publication.

Gates **NOT RUN / DEFERRED** : canonical `./mvnw -pl runtime-web-api,runtime-command-consumption-worker -am test`, Architecture globale `./mvnw -pl architecture-tests -am test`, full `./mvnw test` ; **aucune de ces commandes n'a été exécutée**. Obligation globale §17 du contrat technique/ACL maintenue à Q5 par instruction humaine. Full réservé à Q5 sous couverture effective D6 ; B5 indépendant. Pas de campagne globale L2/L3/L4, rejeu intégral I1/I2 ou certification SPEC-10.

Réserves : B306/B311/B312/B314 Bruno **NOT RUN** dans ce delta (oracles temporels/concurrence correspondants PG ciblés, sans équivalence externe complète) ; ID-02 A…Q/C-01 intégral et Q5 dus ; présentation humaine du récapitulatif relève du futur client, la preuve backend porte sur le POST ; production, multi-issuer, coût/rétention longue et canaux proxy non qualifiés. Réserves historiques I1/I2/L1–L4 conservées ; le parcours réel I3 est une contribution AuthN bornée, sans lever globalement ces réserves.

**Statut proposé : IMPLEMENTED — TARGETED QUALIFICATION PASS — QUALIFICATION CANONIQUE INCOMPLETE ; I3 NON CLOSED.**
