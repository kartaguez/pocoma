# Pocoma V3 — L5/I1 — Implementation and Qualification Plan

Date : **4 novembre 2026**. Branche : **`feat/v3-architecture`**. Référence publiée lue : **`9dd1c44720af61b66abdfb4fdaa7f3076ced8737`**. **APPROVED — GO I1 IMPLEMENTATION AUTHORIZED**, par [acte humain du 4 novembre](L5_I1_Human_Ratification_and_GO.md), référence ratifiée `f46d63a9`.

Ce plan gouverne la réalisation autorisée d'I1. Les noms et commandes restent des obligations à réaliser ; seuls le rapport d'exécution et ses preuves établissent leur état technique. Le §10 conserve le compte rendu historique de la préparation documentaire.

## 1. Référentiel, décisions et périmètre

Ordre d'autorité : [Canon](V3_Canon_and_Roadmap.md), [SPEC-01](SPEC-01_Pot_Domain.md)/[SPEC-02](SPEC-02_Identity_Domain.md)/[SPEC-04](SPEC-04_Read_Projections.md)/[SPEC-06](SPEC-06_Supra_Contracts.md)/[SPEC-07](SPEC-07_Transverse_Requirements.md)/[SPEC-08](SPEC-08_Observability.md)/[SPEC-09](SPEC-09_Performance.md)/[SPEC-10](SPEC-10_Test_Certification.md), [Reactor Verification Policy](../testing/Reactor_Verification_Policy.md), [Master Plan](V3_Implementation_Master_Plan.md), [plan global L5](L5_Supra_Entry_Audit_and_Implementation_Plan.md), [acte D1/D6](L5_D1_D6_Ratification_and_D4_Calibration_Decision.md). Les [mesures D4](L5_D4_Read_Calibration_Report.md) et [preuves](../testing/evidence/L5-D4/README.md) du SHA publié sont synthétiques Java/Jackson, pas HTTP.

Documents de décision associés : [proposition D4](L5_D4_Read_Limits_Arbitration_Proposal.md), [rapport D6](L5_D6_Gates_Mutualization_Report.md). D1-HTTP/représentation et D6-composition sont enregistrés ; D1-AuthN porte une condition de fournisseur réel ; **D4 numérique et D6-gates sont ratifiés** par l'acte humain I1 ; les conditions physiques et de couverture restent dues.

I1 livre uniquement : composition HTTP V3, AuthN externe, propre CurrentBinding, Pot courant/historique à V, Balance à V, Expenses groupées à V, navigation de l'utilisateur, résultats Command/Identity, erreurs/masquage, prévalidation pure avant quota, sérialisation complète bornée et **Bruno B1 livré et exécuté dès I1**.

Hors I1 : admission publique V3, Registration/Unlink publiques, Link/consentement, Invitation, nouveau participant READ, nouveau module/runtime/moteur, migrations SQL, algorithmes métier/Consumption/Discovery, calcul Balance, topologie sept participants. Aucun accès WRITE métier par la consultation ; aucun endpoint V2 ni service V2 sélectionné dans la composition V3. Les composants V2 restent hors parcours, sans chantier de retrait L7.

Les actes de clôture L1–L4 restent inchangés. Réserves transférées du plan L5 §3.2 : coûts Grand Pot/histoire L1, R-L2-01…05 et G1/G2 INCONCLUSIVE/G3 NOT PASSED, L3 R1–R4, lease 180 non homologué production et limites HTTP L4. I1 traite la prévalidation `expenses/visible` (AUD-FINAL-I8-01), sans prétendre fermer les autres réserves. Aucune certification globale SPEC-10, PERF-01, REC-01, OPS-01 ou production.

## 2. Verification scope déclaré avant réalisation

| Champ | Déclaration future I1 |
|---|---|
| Impact attendu | Adaptateurs HTTP/AuthN/DTO V3, ports d'appel READ, sélection Spring des deux racines existantes, prévalidation et ressources de consultation. Pas de changement des producteurs READ. |
| Primaire | **WEB**, ancre `runtime-web-api`. |
| Secondaire | **PROJECTION**, ancre `runtime-task-consumption-worker`, pour composition/façades READ exposées et preuve PG de quota ; aucune modification d'algorithme de matérialisation. |
| Ancre complémentaire | **runtime-monolith**, montage HTTP+READ et coexistence Worker ; pas une nouvelle slice. |
| COMMAND/BINDING/EVENT/LKV | Pas de comportement productif modifié ; dépendances amont et coexistence ne valent pas sélection comme capacités changées. |
| Preuve locale | **T1 filtrée + B1, checkpoint**, inventaire §7 ; niveau opérationnel INTEGRATION pour assemblage/sécurité/ressources, contribution bornée. Aucun PASS canonique de slice revendiqué. |
| Canoniques de référence, depuis `app/` | WEB : `./mvnw -pl runtime-web-api -am test` ; PROJECTION : `./mvnw -pl runtime-task-consumption-worker -am test`. Elles restent dues dans la fermeture Q5, sans les lancer séparément à chaque étape I1. |
| Architecture globale | **REQUIRED pour les frontières/isolations L5 (§17), allouée à Q5 sous ratification D6**. T1 prouve localement beans/ports, pas la gate globale. Aucune clôture canonique I1 indépendante. |
| Full reactor | **REQUIRED à Q5 (§35 CERTIFICATION), NOT RUN à I1**. Full natif unique proposé couvrant G5/Architecture, conditions du rapport D6. |
| DB | **CURRENT READ** et contexte CURRENT WRITE seulement pour racine/coexistence ; rôles et autorités séparés. Baseline TRUSTED actuellement NONE : conserver les bootstraps V3 actuels. Pas de campagne historique V2 ajoutée ; annoncer toute histoire effectivement transitive. |
| Impact interdit | Admissions, business WRITE depuis HTTP READ, changements domaines/calculs/publication/Consumption/renewal/lease, nouveaux modules/runtimes, SQL de production, routage/participants et configuration Worker pour gagner du temps. |
| Escalation | Fichier/capacité hors déclaration, contrat partagé changé, nouvelle dépendance/cycle, coût des vues non maîtrisable, besoin de nouveau rôle/migration : isoler le point, suspendre ses écritures et faire revoir impact/gates/budget avant poursuite (§15). Un test en échec ne déclenche pas automatiquement full. |

Déclaration active avant code : **WEB primaire, PROJECTION secondaire, runtime-monolith complémentaire ; DB CURRENT READ/WRITE de fixture**, T1 filtrée et B1. Aucune clôture canonique autonome. Gates canoniques de référence conservées ci-dessus. **Déclencheur effectif déclaré avant code : modification des sélections de racines et allowlists de préflight (règles d’isolation runtime), §17. Architecture globale non filtrée REQUIRED pour cette livraison I1**, commande `./mvnw -pl architecture-tests -am test` depuis `app/`, en plus de T1+B1 ; ce contrôle ne démarre pas Q5. Aucun nouveau module ni restructuration large, full NOT REQUIRED à I1, dû à Q5. Pas d'impact domaine, matérialisation, Consumption/Discovery, leases, migrations ni admission.

## 3. Réemploi réel et architecture minimale

Les chemins ci-dessous sont relatifs à `app/`. Les liens pointent vers les composants existants au SHA publié. Aucun nom de composant proposé n'impose une classe supplémentaire si un contrôleur, mapper ou bean existant adapté suffit.

### 3.1 Inventaire par capacité

| Capacité / contrat public (§4) | Réemploi effectif et fichiers probables | Adaptation minimale / composant nouveau justifié | Transaction et autorité | Régression / test discriminant / Bruno |
|---|---|---|---|---|
| Composition V3, huit routes | [racine WEB](../../app/runtime-web-api/src/main/java/com/kartaguez/pocoma/PocomaWebApiApplication.java), [racine Monolithe](../../app/runtime-monolith/src/main/java/com/kartaguez/pocoma/PocomaRuntimeMonolithApplication.java), configurations de ces racines et [V3BootstrapGuard](../../app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/v3/V3BootstrapGuard.java) | Imports V3 explicites, scan non susceptible de sélectionner les controllers V2 ; branche HTTP READ autorisée par la garde avec inventaire exact. Contrôleurs V3 minces dans `supra-http-read`, pas de moteur/racine nouveau. | Composition propriétaire ; aucun owner/JDBC injecté au controller. | Scan V2 involontaire, profils mal sélectionnés ; `webRoot/monolithRoot/legacyComponentsRejected` ; tous B1. |
| AuthN sur `/api/v3/**` | [WebApiSecurityConfiguration](../../app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/web/authentication/WebApiSecurityConfiguration.java), [adapter principal](../../app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/web/authentication/SpringSecurityExternalPrincipalAdapter.java), `contracts-authentication/AuthenticatedExternalPrincipal` | Resource Server stateless, matcher V3 et audience/issuer/algorithmes/clefs/dates explicites ; EUI = issuer+subject vérifiés. Réutiliser le principal et resolver ; si séparation V3 nécessaire, petit adaptateur dans le même module, sans exception d'admission V2 comme dépendance de contrat V3. | Aucune transaction métier ; autorité exclusivement JWT validé. | Configuration actuelle protège surtout `/api/v1` et `anyRequest().permitAll()` : sélection V3 doit être fermée explicitement. Tests H01–H07 ; B06/B31. |
| Binding propre | [GetCurrentBindingV3](../../app/engine-read-current-binding/src/main/java/com/kartaguez/pocoma/engine/read/currentbinding/v3/GetCurrentBindingV3.java), [V3ReadFacadeComposition.Services.binding](../../app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/v3/V3ReadFacadeComposition.java) | Port fonctionnel typé dans `engine-read-current-binding` ; mapper Answer/View en DTO public. Aucun lookup par EUI client, UserId ou token entrant. | Un snapshot READ RR, quota commun ; EUI du principal. | ABSENT/NOT_READY confondus ; token tiers ; H08/H23 ; B01–B06. |
| Pot courant, historique et Balance | [ReadPotV3](../../app/engine-read-pot/src/main/java/com/kartaguez/pocoma/engine/read/pot/v3/ReadPotV3.java), [ReadPotV3Result](../../app/engine-read-pot/src/main/java/com/kartaguez/pocoma/engine/read/pot/v3/ReadPotV3Result.java), mêmes façades Services | Port typé Pot/navigation dans `engine-read-pot`, mapper explicite ; aucune modification de `main/authorized` métier attendue. DTO version servie, Shareholders/soldes/manifeste, jamais ensembles d'autorité. | Composition ouvre READ RR unique ; binding courant puis droits à V ; aucun WRITE, pas de visibilité comme barrière. | Droit actuel appliqué au passé, Balance future ou mix V/V+1 ; H12–H15 ; B07–B14. |
| Expenses groupées | `ReadPotV3.expenses`, `ReadLimitsV3`, `JdbcSubobjectVersionStore.readAll` | Prévalidation pure partagée (fonction dans module existant), copie immuable avant quota ; liste de DTO, versions exactes et ordre demandé. Pas d'autre reader ni pagination manifeste. | Même snapshot, autorisation à V et appartenance exacte avant `readAll`. | Liste modifiée après validation, ref étrangère, N+1 ; H15–H17/Q01/Q09 ; B15–B20. |
| Navigation | [ReadNavigationV3](../../app/engine-read-pot/src/main/java/com/kartaguez/pocoma/engine/read/pot/v3/ReadNavigationV3.java), `JdbcPotVisibilityStore` / `port-projection/PotVisibilityStore` | Prévalidation `limit`/curseur avant quota ; codec opaque versionné en HTTP, sans pouvoir d'autorité. `limit` lignes seulement ; curseur si page pleine, dernière page vide autorisée. | READ RR par page, binding revalidé ; keyset potId strictement croissant. | Oubli revalidation, scan/COUNT global, curseur autorisant ; H18/H19/Q01 ; B21–B24. |
| Résultats Command et Identity | [CommandTerminalResult](../../app/engine-read-command-result/src/main/java/com/kartaguez/pocoma/engine/read/commandresult/v3/CommandTerminalResult.java), [IdentityTerminalResult](../../app/engine-read-registration-result/src/main/java/com/kartaguez/pocoma/engine/read/registrationresult/v3/IdentityTerminalResult.java), lecteurs `GetCommandTerminalResult`/`GetIdentityTerminalResult` et stores JDBC existants | Deux petits ports fonctionnels dans leurs modules READ ; DTO terminaux explicites, pas sérialisation du record interne contenant requesterEui. Aucun changement shape CreateInvitation I4 ici. | READ seul sous quota ; propriété requesterEui, aucun binding ni User courant. | Partage de résultats entre EUI du même User ; résultat révélant token/EUI initiatrice ; H10/H11 ; B25–B30. |
| Erreurs, prévalidation, encodage | [Services.query](../../app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/v3/V3ReadFacadeComposition.java), [configuration limites](../../app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/v3/V3ReadFacadeConfiguration.java), `supra-http-read` | Mapping unique D1 + sérialisation UTF-8 bornée avant succès, admission HTTP native bornée et petite erreur séparée ; quelques fonctions/beans, aucun pool de threads maison. | SQL libéré après callback réellement résolu ; capacité HTTP gardée jusqu'à fin/abandon physique d'envoi. | Faux 200 partiel, libération logique prématurée, grosses erreurs ; H20–H24/Q02–Q10 ; B32. |
| B1 | Aucune collection V3 existante ; conventions L5 §13.6 | Livrer seulement l'arbre §6 et ses fixtures de test, pas des admissions simulées publiques ni des appels V2. | Préparation fixture par autorité de test séparée ; compte HTTP incapable de la muter. | Faux E2E d'admission ; C06 et contrôles B1. |

### 3.2 Graphe Maven et propriété des ressources

Lecture statique des POM au SHA publié : **60 modules enfants, aucun cycle de dépendance déclaré** (tous scopes/optional inclus). `supra-http-read` dépend déjà de `contracts-authentication` et des quatre modules engines READ nécessaires ; **aucune dépendance vers composition**. `composition-runtime-spring` dépend déjà des engines, JDBC/transactions et HTTP READ (optionnel), les racines déclarent HTTP et les adaptateurs nécessaires. Ne pas importer `Services` depuis HTTP : cela créerait HTTP→composition→HTTP.

Implémentation recommandée : petits ports d'appel dans les modules READ propriétaires, utilisant ExternalIdentity/résultats existants ; composition les fournit par références de méthodes autour de Services. Aucun port de transaction, DataSource ou contexte READ ne traverse HTTP. Les engines ne dépendent ni de Spring MVC, ni de composition. Pas de façade universelle READ/admission. Les dépendances de production existantes doivent suffire ; vérifier les optional dans WEB/Monolithe. Seule dépendance de test envisagée : `runtime-monolith → runtime-web-api` **test scope**, pour charger la vraie racine WEB dans C01, sans reflection de classe manquante ; WEB n'a aucun chemin vers Monolithe au SHA publié. Recalculer ce graphe avant ajout ; pas d'arête inverse. Pas de nouveau module ni nécessité d'install préalable.

Réalisation I1 : dépendances ajoutées **en test uniquement**, Task Worker→HTTP READ + starters Web/Resource Server pour les dix Q HTTP/JDBC réels. Pas de nouvelle dépendance de production. L’arête Monolith→Web envisagée initialement est finalement **absente**, conformément à l’oracle historique PCL6 inchangé. C01/C03–C06 sont dans `runtime-web-api/V3HttpCompositionPostgresTest`, C02 dans `runtime-monolith/V3MonolithHttpCompositionPostgresTest`, sans réflexion ni duplication de racine. Les six noms de méthode et oracles C sont conservés ; C05 interdit explicitement l’arête prohibée. Le classpath HTTP a révélé un scan Legacy indésirable dans le Worker par défaut : racine explicitement sans HTTP, fixture seule en Servlet, deux classes historiques et Q01–Q10 rejouées. Gate globale §17 maintenue.

Conserver le même owner READ et son `RecoveryReadContext` à l'intérieur du callback RR. DTO détachés : aucun lazy, ResultSet, Stream JDBC, transaction ou connexion après retour. Garder Q=2 dans Services, partagé **binding/Pot/Balance/batch/navigation/résultats**, `tryAcquire()` sans attente, zéro Control. Facteur d'allocation inchangé `Q ≤ R_business − M − H`, Q=2 seulement avec la réserve actuelle et H=0 ; WEB autonome déclare explicitement son pool READ et Q ensemble, sans démarrer quatre pools Worker pour le seul HTTP.

La correction de prévalidation de `expenses/visible` intervient **avant** `query` : null/vide/doublons/cardinalité/versions/limite, copie figée. L'autorisation et l'appartenance au manifeste restent après quota, dans READ. La validation d'un token vide/malformé est 400 ; token non concordant de forme valide est 404. Aucun contrôle de données métier n'est déplacé hors transaction pour gagner du temps.

## 4. Contrats HTTP exhaustifs I1

### 4.1 Conventions communes D1

Huit endpoints ci-dessous, tous sous `/api/v3` et AuthN bearer obligatoire. Pas d'admission, `202`, `Location` ou endpoint administratif en I1. Absence/invalidation cryptographique/AuthN **401** ; aucune EUI libre dans URL/query/corps. `X-Pocoma-Binding-Token` seulement pour Pot/Balance/Expenses/navigation ; jamais dans URL/log. Binding propre et résultats n'en demandent aucun.

UUID canoniques ; versions JSON exactes entières non négatives pour minimum et positives pour versions explicites/locales. Parseur exact jusqu'à `Long.MAX_VALUE`, refus overflow/flottant/exposant non entier ambigu ; aucun passage en double dans le codec. Rationnels DTO **`{n:"entier",d:"entier positif"}`**, normalisés/exacts ; date Expense ISO `YYYY-MM-DD`. Aucun maximum métier de chiffres. Champs de corps inconnus refusés ; paramètres inconnus de sélection EUI/User refusés **400**. Paramètres répétés ambigus refusés. Les noms DTO ci-dessous sont les choix de réalisation proposés à l'approbation du plan, conformes à D1, pas une décision normative supplémentaire déjà acquise.

Erreur commune `{code,message,traceId}`, messages génériques et traceId de format/longueur fixes ; enveloppe cible ≤1 Kio. **404 identique** absence/interdit/token non concordant/terminal/résultat tiers, sans identifiant ni motif interne ; aucune différenciation par en-têtes/cache. `400 INVALID_INPUT`, `401 INVALID_TOKEN`, `404 NOT_FOUND`, `413 PAYLOAD_TOO_LARGE`, `415 UNSUPPORTED_MEDIA_TYPE`, `503 UNAVAILABLE` proposés comme codes stables. Pas de 403 ressource ni 429 implicite. `Cache-Control: no-store` sur succès sensibles et erreurs. Aucun secret/UserId/SQL/reflet arbitraire d'entrée dans logs, URLs, erreurs ou traces. Le seul BindingToken public est celui de la réponse propre active, nécessaire au contrat ; il est expurgé des preuves exportées.

**D4 ratifié pour I1 avec réserves physiques** : corps ≤65 536 octets, réponse ≤4 194 304 octets UTF-8 non compressés/enveloppe incluse, batch ≤50, page ≤100 (défaut 20), SQL Q=2 déjà acquis. Entrée dépassée 413 avant parsing coûteux/quota ; média/encodage non supporté 415 ; sortie dépassée/échec tardif 503 **avant tout commit HTTP 200**. Content-Type JSON pour POST ; pas de corps compressé ni compression de réponse I1. Corps GET non vide refusé 400 ; transport serveur peut protéger une taille excessive par 413. Aucun seuil appliqué aux futures admissions I2.

### 4.2 Routes, DTO, autorité et retard READ

| ID / méthode et chemin exacts | Entrée, AuthN/token | 200 nominal | Négatifs, autorisation/retard et bornes | B1 |
|---|---|---|---|---|
| E1 `GET /api/v3/me/binding?minVersion=` | EUI authentifiée seule ; minimum optionnel **0**, pas de BindingToken ni sélecteur tiers | `{euiVersion,active,bindingToken}` ; token propre si actif, sinon null | ABSENT→404 même avec minimum élevé ; présent sous minimum→503 ; actif/inactif au minimum→200. Panne/quota→503. Limites communes. | B01–B06 |
| E2 `GET /api/v3/pots/{potId}?minVersion=` | EUI + header token ; minimum optionnel **0** | `{potVersion,id,label,headerVersion,detailsVersion,shareholders,balances,expenses}`. Tous Shareholders, soldes et manifeste ordonné complets de W≥minimum. Aucun détail Expense. | Binding absent/inactif/non concordant ou Pot absent/interdit/terminal→404 **avant** fraîcheur/complétude ; actif autorisé mais W<minimum ou dépendance manquante→503. Aucun lookup WRITE ni barrière visibilité. Pas de pagination manifeste. | B07/B10/B11/B14/B32 |
| E3 `GET /api/v3/pots/{potId}/versions/{V}` | EUI + token ; V≥1 exacte | Même DTO, `potVersion=V` | Droits à V avec binding courant, sans droit exigé à V+1. V absente/interdite/terminale D→404 ; dépendances actives V autorisée absentes→503 ; V<D autorisée→200 même après suppression courante. | B08/B12/B13 |
| E4 `GET /api/v3/pots/{potId}/versions/{V}/balance` | EUI + token, V≥1 | `{potId,potVersion,balances}` ; vecteur complet de V, montants exacts | Même autorisation à V ; D→404 **sans attendre Balance(D)** ; Balance V absente/incomplète→503 ; aucune Balance V−1/V+1. | B09/B12/B13 |
| E5 `POST /api/v3/pots/{potId}/versions/{V}/expenses/query` | EUI + header token ; JSON `{references:[{id,headerVersion,detailsVersion,financialsVersion,deleted}]}` ; ordre conservé | `{potId,potVersion,expenses:[{reference,label,date,amount,payerId,participants:[{shareholderId,specificWeight}]}]}` ; poids spécifique rationnel ou null, lot complet | 0/null/doublons/>50/versions malformées→400 avant quota ; corps >64 Kio→413 ; chaque ref doit appartenir exactement au manifeste P,V, sinon 404. Sous-version nécessaire absente→503. Si DTO trop gros→503, jamais succès partiel. Courant avancé n'altère pas V. | B15–B20/B32 |
| E6 `GET /api/v3/me/pots?cursor=&limit=` | EUI + token ; limit 1..100/défaut 20 ; cursor optionnel | `{potIds,nextCursor}` ; UUID ordonnés croissants, curseur string opaque versionnée ou null | Curseur invalide/limite hors borne→400 avant quota ; binding absent/inactif/non concordant→404 ; panne/quota→503. Liste vide→200. Retard de visibilité accepté, ne bloque pas une lecture directe autorisée. Page pleine peut conduire à page vide ; pas de snapshot entre pages. | B21–B24 |
| E7 `GET /api/v3/commands/{requestId}/result` | EUI seule, pas de token/binding/User courant | `{requestId,operation,potId,outcome,resultingPotVersion,rejection}` ; outcome COMPLETED ou REJECTED ; version succès seulement/rejection rejet seulement ; pas requesterEui | Absent READ ou tiers→même 404 ; panne/quota→503 ; rejet métier terminal→200. Résultat avant Pot possible. Aucune consultation durable WRITE ni statut PENDING/EXPIRED public. | B25/B26/B29/B30 |
| E8 `GET /api/v3/identity/requests/{requestId}/result` | EUI seule, pas de token/binding | `{requestId,operation,outcome,targetEui,targetVersion,rejection}` ; targetEui `{issuer,subject}` et version seulement si succès selon contrat ; pas de token ni UserId | Propriété requesterEui, y compris après Unlink/re-Link, EUI du même User non autorisée. Même 404 absent/tiers ; 503 incident réel ; succès/rejet 200. Une cible de résultat n'est pas un moyen de consulter son Binding. | B27–B30 |

`shareholders` E2/E3 : `{id,headerVersion,detailsVersion,weightVersion,deleted,isFree,name,defaultWeight}` ; `balances` : objet indexé par Shareholder UUID, rationnels exacts ; `expenses` principal : seulement les références E5, dans l'ordre du manifeste. Aucun `userId`, `authorizedUserIds`, `attachedActiveUserIds`, objet de persistance ou donnée de consentement. Les DTO ne sérialisent jamais automatiquement les records de résultat internes.

Curseur : encodage opaque version 1 du dernier potId canonique, sans identité ni secret ; décodage borné et validation stricte avant quota. Signature non requise selon D1, aucune confiance d'autorisation tirée du curseur, même importé d'un autre utilisateur. Un code version inconnu est 400. Choix de ne pas utiliser `limit+1` pour éviter une modification du plafond moteur ; ne pas promettre `nextCursor != null` comme preuve qu'une ligne suivante existe.

### 4.3 Sécurité et qualification externe

Valider réellement la signature et la confiance (algorithmes autorisés explicitement, pas `none`), issuer, audience, exp/nbf et claims d'identité. EUI = couple issuer/subject, pas `subject` seul ; conserver les contrôles iat/auth_time du principal existant si son contrat les requiert, sans exiger de claim métier inventé. Refuser les identités incomplètes en 401. Deux EUI liées au même User gardent résultats/tokens distincts.

T1 signe ses JWT avec clefs de test distinctes, puis les altère ; il utilise le vrai décodeur Resource Server, **pas `mockJwt` comme oracle cryptographique**. Propriété tests locaux ≠ confiance fournisseur. L'issuer, audience, source/rotation de clefs et comptes de Qualification doivent être documentés et vérifiés réellement avant **acceptation externe AuthN** (SPEC-10 S01-O/configuration B). Si le fournisseur est encore indisponible, checkpoint interne possible avec réserve visible ; acceptation externe reste NOT QUALIFIED. Une fixture ne remplace jamais cette preuve ; tout mapping autre que Keycloak suit la décision explicite requise par le plan L5, sans réécrire les SPEC ici.

## 5. D4 : protections avant T1 et mesures pendant T1

Banc initial autorisé par la ratification courante : [banc D4 §2](L5_D4_Read_Limits_Arbitration_Proposal.md) : quatre candidats, HTTP en vol 2, SQL Q=2, délai logique consultation 5 s/envoi 5 s, statement local 2 s/transaction consultation 3 s, erreur≤1 Kio, JVM de mesure `-Xmx512m`, compression désactivée. T1 fixe ce plafond au fork ; si une gate native non filtrée impose un fork plus large, Q08 exécute le même oracle dans un enfant `-Xmx512m`, avec reçu, mesures et terminaison observée ; aucun SKIP ni changement de seuil. Les limites SQL/logiques sont activées explicitement par les compositions HTTP, pas par la fabrique READ historique hors HTTP ; les transactions Worker conservent leur contrat. Les limites SQL sont locales aux consultations HTTP, pas aux transactions Worker ; ne pas modifier les timeouts hérités pour faire passer T1. Socket Business 70 s hérité : annulation logique ne prouve pas restitution ; garder les capacités jusqu'à résolution physique, contrôle jusqu'à 80 s puis UNKNOWN/arrêt. Pas d'homologation production, pas de 32 Mio présenté comme borne prouvée.

Avant exécution : protection transport **entrée finie, profondeur JSON 8 proposée pour la forme DTO fermée**, parser streaming borné ; corps reçu 64 Kio, DTO final 4 Mio, queue applicative nulle, serveur/threads/connexions et tailles de headers finis, corpus contrôlé, propriétaire des enfants/PG et arrêt connus. Profondeur 8 est un paramètre de banc explicite, pas un invariant métier. Bound headers/bearer = limite serveur finie **16 Kio pour le bloc d'en-têtes de test**, URI **2 Kio**, curseur codec **256 octets** ; header trop grand traité par rejet transport 400 avant applicatif, curseur surlong 400. Vérifier la longueur réelle JWT/BindingToken du banc sous ces seuils ; tokens fournisseur réels plus longs imposent décision ciblée avant qualification externe. Ne pas tronquer un token. Ces valeurs de protection de banc sont proposées à approbation, pas mesurées ni imposées à un fournisseur inconnu.

Les deux permis SQL et deux capacités HTTP ne s'additionnent pas comme quatre lecteurs : acquisition transport couvre parsing/vue/encodage/transfert, quota SQL couvre uniquement le callback READ résolu. Rejet sans gros tampon et sans attente ajoutée, y compris en rafale ; Le banc propose au plus **4 threads HTTP, 16 connexions transport et un backlog d'acceptation de 4**, avec aucune queue applicative ; enregistrer la configuration serveur effective et le refus à saturation. Ces paramètres protègent le test isolé et ne sont pas des valeurs de production homologuées. Une transaction résolue libère SQL même si le client lent garde HTTP ; une ressource incertaine ne se libère pas simplement à l'expiration du timer. Ne pas reconstituer WRITE pour répondre à une annulation ou un retard.

Dans T1, démarrer par profils petits puis Grand Pot/batch renforcé, enfin deux appels lourds seulement si marge du processus constatée. Corpus fini revu et isolation du trafic permettent la mesure sans exiger déjà un pic connu. Collecter : heap actif et pic **échantillonné**/résolution, allocations par phase (JDBC/décodage/construction/mapper/encodage), copies/buffers, RSS, pause GC, mémoire gardée pendant envoi lent et après abandon. `fits(responseBudget)` actuel intervient après construction : ce n'est pas un plafond HTTP ou heap ; conserver/proposer explicitement son budget de fixture **16 000 000**, sans le confondre avec 4 Mio, ni hausser automatiquement en cas de refus. Un refus interne d'un profil passe par 503 et devient une réserve à instruire.

Nombre/coût JDBC : trace des statements sans paramètres secrets, compte d'exécutions et lignes/octets/temps ; différencier contrôle transactionnel et SELECT métier. Hypothèses D4 à confirmer : batch 50=3 SELECT, Grand Pot courant 6/historique 5, selon chunk 256. Résultat/binding/navigation mesurés sans leur inventer un coût. EXPLAIN ANALYZE BUFFERS navigation sous rôle/jeu qualifiés, coût d'un lot 50 mesuré ; une forme N+1 est FAIL, delta expliqué et borné exige revue. Rien ne qualifie un SLO E2E de charge.

Les frontières octet exactes utilisent de vrais DTO finaux pour la sortie (champ texte ajusté par calcul indépendant, contrôlé en bytes ; aucune classe de production synthétique). Entrée : espaces JSON pour obtenir 65 535/536/537 sans champ inconnu. Tester le tampon HTTP avec DTO de fixture et le chemin JDBC séparément si le budget `fits` refuse auparavant : documenter ce refus, ne pas le désactiver pour prétendre que le chemin complet a réussi. Batch extrême légitime peut donner 503 ; un principal légitime trop gros reste un problème de disponibilité nécessitant décision, pas une invalidité métier.

## 6. Collection Bruno B1 à livrer en I1

### 6.1 Fichiers, environnement et données

```text
scripts/bruno-v3/
  bruno.json
  README.md
  Coverage.md
  environments/Local.bru
  environments/Qualification.bru
  examples/                         # JSON fictifs/expurgés seulement
  01 Identity and READ/
    01 Own Binding/                 # B01..B06
    02 Pot and Balance/             # B07..B14
    03 Expense Batches/             # B15..B20
    04 Navigation and Results/      # B21..B32
```

Noms de requêtes préfixés Bxx et scénario ci-dessous ; un scénario peut avoir plusieurs étapes `.bru` ordonnées. **32 scénarios logiques attendus**, nombre de requêtes/pollings réellement exécutées séparé dans le reçu. Aucun dossier I2–I4 factice pour faire croire à leur livraison. README fixe avant B1 **version exacte CLI Bruno/runtime et installation verrouillée**, imprime `bru --version` au run, pas `latest`. Le plan ne prétend connaître une version locale qualifiée sans l'avoir exécutée. Choisir une seule version et vérifier ses scripts/rapports lors de la réalisation, avant campagne ; incompatibilité CLI ne justifie pas une assertion ignorée.

Variables suivies **non secrètes** : baseUrl, issuer de test/qualification, audience, potIdMedium/potIdGrand/potIdDeleted/potIdForbidden, V/W/D, versions locales de références, requestId succès/rejet/absent/tiers, cursor de fixture public, pageLimit 20/max 100, expected counts/hash du corpus public, délai/attempts/backoff de polling. Local = localhost sur port déclaré par le banc ; Qualification = URL autorisée et fournisseur réel si disponible. Pas de DSN/password/admin, clé privée, bearer ou BindingToken suivi.

Secrets injectés hors Git via environnement de processus/dispositif secret qualifié : bearerOwner/bearerOther/bearerSameUser/bearerUnbound, JWT invalides de test, token de binding propre et tokens des fixtures privées. Le header Authorization et le header BindingToken sont construits sans mise en URL. Valeurs récupérées par E1 restent en mémoire de session B1, jamais exemple/export. Rapports CLI/console expurgés avant archivage, absence de dumps des corps contenant token. Aucun secret en argv ou historique shell.

Fixtures futures test-only décrites au §7.2 : manifeste P,V, grand 100/2000, résultats READ terminaux alimentés explicitement, navigation 205 entrées, EUI distinctes dont deux même User, binding absent/inactif/retardé. Initialisation par autorité d'administration de fixture avant campagne HTTP, **jamais endpoint de test public ou appel V2**. Manifestement marquer `READ_FIXTURE — NO PUBLIC ADMISSION PROOF` pour résultats ; B1 ne prétend prouver ni 202, ni publication, ni exécution WRITE. Même jeu attendu et mêmes contrats Local/Qualification ; comptes/secrets diffèrent.

Chaque scénario autonome reçoit ses propres IDs/état et prérequis ; aucune mutation cachée d'un dossier précédent. Seuls parcours de navigation et consultation chaînée partagent des variables **dans leur dossier** : E1 own binding→Pot V→balance/batch exact à V ; navigation page 1→page 2→page 3 ; résultat terminal→lecture courant avec minimum. Une seconde exécution ne dépend pas d'une admission antérieure ou de V2 ; données en lecture immuables, états négatifs dans schémas distincts préchargés. Une faute d'environnement est NOT RUN/INCONCLUSIVE, jamais `if missing then skip`.

### 6.2 Inventaire Bruno → contrat → exigence

Chaque ligne impose ses assertions (statut **et** corps/versions/confidentialité/no-store), pas seulement un code HTTP. Les fixtures de panne/taille sont isolées, sans paramètre public permettant de désactiver une protection.

| Scénario futur | Requête(s)/oracle obligatoire | Contrat / exigences |
|---|---|---|
| B01 activeOwnBinding | E1, actif 200, propre token/version, aucun UserId | E1 ; SPEC-06§7/SEC-01 |
| B02 inactiveOwnBinding | EUI inactive 200, active=false/token=null | E1 ; SPEC-04§6 |
| B03 absentOwnBinding | EUI absente 404, même enveloppe publique | E1 ; D1/READ-01 |
| B04 bindingMinimum | Présent sous minimum 503, aucun token ; absent avec minimum 404 | E1 ; SPEC-06§7 |
| B05 foreignBindingSelector | Query EUI/UserId rejetée 400, aucun binding tiers | E1 ; SEC-01 |
| B06 noBearer | BindingToken seul et bearer absent 401 | E1–E8 ; SEC-01 S01-B |
| B07 currentComplete | Pot courant 200 à W≥minimum, liste/soldes/manifeste exacts | E2 ; READ-01/S-02 |
| B08 exactHistory | V exacte, courant W>V ne change ni références ni soldes V | E3 ; DOM-01/READ-01 |
| B09 exactBalance | Balance V exacte, comparaison rationnelle indépendante | E4 ; DOM-03 |
| B10 forbiddenAndAbsent | Pot tiers+minimum énorme et UUID absent : même 404 | E2 ; SEC-01/A11-H |
| B11 deletedCurrent | Courant terminal 404, pas 503 pour balance absente | E2 ; A11-G |
| B12 terminalVersion | D explicite et Balance D 404 | E3/E4 ; A11-G |
| B13 historyBeforeDeletion | V<D autorisée 200, manifeste/batch et balance exacts | E3/E4/E5 ; A11-F/N |
| B14 authorizedLag | Minimum non atteint et dépendance manquante 503 après droits ; résultat peut déjà être 200 | E2 ; R01-B/D/E/H |
| B15 singleExpense | Une ref exacte 200, identité/versions/poids/date exacts | E5 ; SUPRA-READ-01 |
| B16 fiftyExpenses | 50 références 200 complètes, ordre demandé exact | E5 ; S-02 |
| B17 emptyBatch | Liste vide 400 sans réponse de saturation | E5 ; SPEC-06§5 |
| B18 oversizedBatch | 51 références valides 400, pas limite de manifeste | E5 ; D4/S-02 |
| B19 duplicateBatch | Référence répétée 400, pas de normalisation silencieuse | E5 ; SUPRA-READ-01 |
| B20 foreignReference | Pot/V/sous-version étrangère bien formée 404 uniforme | E5 ; SEC-01/A11-N |
| B21 navigationPages | Jeu fixe 205 : pages 100/100/5, union 205 sans doublon, curseurs opaques ; ouvrir un Pot via ses droits propres | E6→E2 ; S-01 |
| B22 invalidCursor | Codec malformé/version inconnue 400 | E6 ; S-01 |
| B23 pageLimits | Limit 0/101→400, 100 accepté, défaut 20 | E6 ; D4/S-01 |
| B24 mismatchedToken | Token valide d'une autre EUI 404, nul/malformé 400 ; EUI étrangère ne récupère pas l'autre token | E2/E6 ; SEC-01 |
| B25 commandCompleted | Fixture terminale 200 COMPLETED/version ; pas requesterEui | E7 ; A10-A (lecture seulement) |
| B26 commandRejected | 200 REJECTED/motif borné/versionnull, pas de secret | E7 ; A10-B (lecture seulement) |
| B27 identityCompleted | Registration/Unlink fixtures 200, cible/version autorisées, aucun token | E8 ; A10-C/D (lecture seulement) |
| B28 identityRejected | 200 REJECTED sans User/binding requis | E8 ; A10-C |
| B29 foreignAndSameUserResult | Autre EUI et EUI même User 404 pour E7/E8, identique UUID inconnu | E7/E8 ; A10-E/G |
| B30 boundedResultPolling | Absent 404 puis terminal 200 après publication contrôlée de fixture READ ; 503 distinct, aucun PENDING/EXPIRED | E7/E8 ; A10-H/I/K, part READ |
| B31 invalidBearer | JWT falsifié 401 sans fuite ; corpus JWT complet reste H01–H07 | E1–E8 ; S01-O contribution locale |
| B32 transportAndPrivacy | Corps >64 Kio 413, type/encodage 415, sortie trop grosse 503 sans préfixe 200, vérification no-store/DTO sans UserId | E2/E5 ; D1/D4/S-02 |

Polling B30 : au plus **4 tentatives par résultat**, délais **1/2/4 s**, délai total **20 s** incluant réponses ; chaque appel timeout≤5 s et arrêt avant dépasser le total. 401/400/413/415 ou 200 terminal arrêtent ; 404 continue sans conclure à un rejet métier, 503 reste indisponibilité ; échéance→TIMEOUT/INCONCLUSIVE selon propriété, jamais succès. Le plan d'exécution fixe le tick de publication de la fixture (après première observation 404), par autorité test séparée, sans endpoint public ni runner de remplacement. Hors B30, résultats terminaux préchargés donc scénarios indépendants ; ne pas poller un ID toujours absent jusqu'au timeout par principe. Utiliser les mécanismes de séquencement/polling de la version Bruno retenue, avec compteur et horloge monotone testés ; aucun test externe dupliqué pour un nom JUnit.

### 6.3 Commandes futures B1

Depuis `scripts/bruno-v3/`, choisir **un** environnement déclaré pour la campagne partagée :

```sh
node_modules/.bin/bru run -r "01 Identity and READ" --env Local --sandbox developer --noproxy --reporter-json /absolute/new/bruno.json --reporter-skip-all-headers --reporter-skip-body
```

Mode Qualification (remplace Local, pas seconde exécution systématique) :

```sh
node_modules/.bin/bru run -r "01 Identity and READ" --env Qualification --sandbox developer --noproxy --reporter-json /absolute/new/bruno.json --reporter-skip-all-headers --reporter-skip-body
```

Parcours autonome : `node_modules/.bin/bru run -r "01 Identity and READ/01 Own Binding" --env Local`. Le format de rapport disponible dans la version retenue sera figé dans README et le reçu ; conserver commande effective, compteur 32 scénarios, nombre de requêtes/assertions et codes intermédiaires expurgés. Exécution d'un seul parcours ne satisfait pas B1 entier. B1 Local ne vaut pas preuve AuthN externe ; une future campagne fournisseur réclame son mandat/budget, sans forcer deux B1 dans 15 min.

## 7. T1 : tests définis avant implémentation

### 7.1 Inventaire nominatif et oracles indépendants

**40 méthodes JUnit ordinaires attendues, 40 invocations** : 24 H + 10 Q + 6 C. Pas de tests dynamiques/paramétrés masquant les comptes : les variantes de chaque ligne sont explicitement comptées comme assertions dans le manifeste, toutes obligatoires. Zéro failure/error/skip et chaque identité exactement une fois. Les noms de travail sont conservés ; la réalisation utilise quatre classes pour garder C02 dans sa racine Monolith et les cinq autres C dans Web, sans arête runtime interdite. Les anciens scénarios regroupés sont développés ci-dessous sans supprimer leur couverture.

**`V3ReadHttpContractTest`**, futur `runtime-web-api/src/test/java/com/kartaguez/pocoma/` ; vrai serveur sur port éphémère, vrai Resource Server et PostgreSQL READ. Aucun test n'utilise le mapper/reader testé pour fabriquer l'attendu.

| ID / méthode | Stimulus et oracle discriminant | Fixture / lien normatif / B1 |
|---|---|---|
| H01 `missingBearer` | Bearer absent, token binding seul : 401 sur les huit routes, aucune requête READ | F-Auth ; S01-B ; B06 |
| H02 `forgedSignature` | Signature modifiée et algorithme non autorisé : 401, pas identité de secours | F-Auth ; S01-O ; B31 |
| H03 `wrongIssuer` | JWT signé par clef de test mais iss non approuvé : 401 ; même subject deux issuers jamais confondus | F-Auth ; S01-M/O |
| H04 `wrongAudience` | Audience erronée/absente : 401 avant READ | F-Auth ; S01-O |
| H05 `expiredJwt` | exp passé, clock/skew explicites : 401 | F-Auth ; S01-O |
| H06 `notYetValidJwt` | nbf futur au-delà du skew : 401 | F-Auth ; S01-O |
| H07 `missingEui` | sub/iss absent ou vide, claims du principal invalides : 401 ; pas de sélecteur client de secours | F-Auth ; SEC-01 |
| H08 `ownBindingAndResults` | Binding actif/inactif/absent/minimum présent en retard ; seule EUI connectée, pas de token tiers. Résultat peut être prêt avant binding | F-Identity ; SPEC-06§7 ; B01–B05 |
| H09 `bindingTokenAndForeignEui` | Token incorrect/ancien/d'une autre EUI ; binding absent/inactif ; EUI étrangère, même subject autre issuer ; 404 de forme valide, pas contenu | F-Identity ; S01-C/D/E/M ; B24 |
| H10 `terminalResultsOwnership` | Command/Identity succès et rejet, tiers et même User, UUID absent ; 200 propriétaire ou 404 indiscernable | F-Results ; A10-B/C/D/E/G/I ; B25–B29 |
| H11 `terminalResultsWithoutBinding` | Révoquer puis relier E1 à un autre User dans READ ; anciens résultats immuables restent à E1, sans token ni résolution de binding | F-Results ; A10-F/K ; B25–B30 |
| H12 `historicalAccessAndDeletion` | Créateur sans Shareholder, droit à V perdu à V+1 ; D/courant 404 sans Balance D, V<D 200, batch historique autorisé ; aucun droit via navigation trompeuse | F-Pot ; S01-A/F/G/H/I/J, A11-F/G/H/K/N ; B08–B13 |
| H13 `minimumAndReadDependencies` | Autorisé W<minimum 503, W>minimum 200 ; Balance V−1/V+1 ne remplace pas V, sous-objet manquant 503 ; tiers+minimum extrême 404 ; WRITE connu/READ absent 404 | F-Lag ; R01-A/B/C/D/E/F/H/I/J/K/L ; B10/B14 |
| H14 `coherentSnapshotDuringAdvance` | Barrières après CurrentPot et pendant binding/commit/rollback injecteur ; réponse complète à V ou 503, jamais mix V/V+1 ni données non committées | F-Lag ; R01-G/M, S01-N |
| H15 `completeManifestAndExactBatches` | 0/200/2000 références principales complètes, poids/rationnels/UTF8 exacts ; lots 1/49/50 dans ordre demandé à V malgré courant V+1 | F-Volume ; S-02/DOM-03 ; B07/B15/B16 |
| H16 `batchCardinalityAndMalformedRefs` | 0/51/null/élément null/doublon/versions≤0/overflow, inconnus : 400, 1/49/50 admissibles ; copie immuable de liste | F-Volume ; SUPRA-READ-01 ; B17–B19 |
| H17 `foreignRefsAndMissingSubversions` | Pot/V ou versions locales substituées 404 ; vraie sous-version autorisée absente 503 ; aucun item partiel | F-Pot/F-Lag ; S-02/A11-N ; B20 |
| H18 `navigationCursorAndBindingChange` | Pages 100/100/5 de 205 IDs, défaut 20, curseur altéré et limite 0/101 → 400 ; changement binding entre pages : revalidation effective | F-Navigation ; S-01 ; B21–B24 |
| H19 `navigationMutationsAndPlans` | Insertion avant/après curseur, retrait, cursor d'autre EUI, sans doublon ni autorisation ; index utilisé et pas scan/COUNT de tous utilisateurs | F-Navigation ; S-01 ; B21/B22 |
| H20 `inputByteBoundaries` | Corps valides à 65535/65536,65537→413 ; Content-Length absent/chunked et annoncé faux, avant parsing/SQL ; profondeur/header/URI bornés | F-Transport ; D4 ; B32 |
| H21 `outputByteBoundaries` | DTO finaux à 4194303/4194304 complets ; 4194305→503 ; octets réseau et statut observés, pas de préfixe 200, pas simple exception Jackson | F-Transport ; S-02/D4 ; B32 |
| H22 `lateSerializationFailure` | Erreur sur dernier champ/élément et réponse extrême, budget interne refusé ; 503 petite enveloppe, aucun succès déjà committé ni ressources perdues | F-Transport ; S-02 ; B32 |
| H23 `exactDtoAndSecretLogs` | Schéma public par allowlist, vérification brute et logs app/serveur/JDBC observés ; UserId/tokens canaris absents sauf propre token réponse autorisée ; no-store ; rationnels sans conversion flottante | Toutes ; SEC-01/S-02 ; tous B1 |
| H24 `mediaAndUnknownFields` | Type/Content-Encoding non supportés 415 ; champs inconnus, query EUI/UserId, paramètres dupliqués, UUID non canonique, GET avec corps 400 ; erreurs≤1 Kio | F-Transport ; D1/D4 ; B05/B32 |

**`V3ReadQuotaPostgresTest`**, futur `runtime-task-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/task/v3/` ; réutiliser les techniques de [ReadFacadesPostgresTest](../../app/runtime-task-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/task/v3/ReadFacadesPostgresTest.java), sans modifier ses anciens oracles. HTTP réel de test peut charger la configuration HTTP déjà présente en composition/`supra-http-read`, sans dépendre de `runtime-web-api` en retour. Connexion d'observation indépendante, barrières/latches, pas sleeps probabilistes.

| ID / méthode | Oracle physique exigé | Lien |
|---|---|---|
| Q01 `invalidInputBeforeQuota` | Deux callbacks READ retenus ; batch vide/null/doublon/>50 et page 0/101 invalides via port interne puis HTTP→400/InvalidInput **avant quota et zéro acquisition JDBC**, et pas 503 ; test de mutation de liste capturée | AUD-FINAL-I8-01/D4 |
| Q02 `twoReadersAndImmediateRefusal` | Deux ports typés retiennent des transactions réelles ; troisième→Unavailable sans emprunt/attente. Tester directement le port pour ne pas confondre rejet HTTP 2 et SQL Q=2 ; binding/résultats partagent le même quota | D4 Q/Q+1 |
| Q03 `permitsAndConnectionsReleased` | Succès/404/503/exception technique : fin physique transaction/connexion observée avant restitution ; deux nouveaux appels possibles ; aucun cumul après boucle finie | D4/ressources |
| Q04 `sqlTimeoutPhysicalResolution` | Statement bloqué réellement, timeout local ; pg_stat_activity/PID et compte pool prouvent fin/rollback. Délai logique 5 s seul ne libère rien ; >80 s→UNKNOWN/arrêt | D4 durée |
| Q05 `cancellationDuringJdbc` | Annulation/déconnexion pendant SQL, attente barrière et observation backend/pool/permis ; aucun retour anticipé sous issue incertaine | D4 annulation |
| Q06 `disconnectDuringEncodingAndSend` | Rupture pendant encodage puis transfert réel ; HTTP capacity jusqu'à abandon physique, aucun tampon/connexion retenu ensuite ; rupture réseau≠503 garanti après 200 émis | D4 copies/transport |
| Q07 `httpInFlightUntilSendEnds` | Deux envois lents après libération SQL ; troisième HTTP 503 sans SQL/gros buffer ; erreur bornée, aucune queue applicative, récupération après terminaison | D4 en-vol |
| Q08 `memoryAndCopies` | Corpus renforcé puis extrême, un puis deux appels sous marge autorisée ; mémoire par phases, pic échantillonné/résolution/limites et copies. Budget dépassé→503 avant 200 ; aucune assimilation allocations cumulées→pic | D4 mémoire |
| Q09 `jdbcCountsAndCosts` | Lot 50 sans N+1, hypothèses SELECT 3/6/5 comparées aux exécutions ; lignes/octets/temps/plan sous rôles réels, pas compte d'objets Java pris pour trace SQL | D4 JDBC/S-02 |
| Q10 `workersProgressWithoutControlBorrow` | Worker existant progresse sous deux lecteurs retenus, zéro emprunt HTTP Control/WRITE ; état/pool et progression durable indépendamment observés, sans stress historique full | Allocation I8/D4 |

**`V3HttpCompositionPostgresTest`**, futur `runtime-monolith/src/test/java/com/kartaguez/pocoma/` ; réutiliser techniques de [ReadWorkerResourcesPostgresTest](../../app/runtime-monolith/src/test/java/com/kartaguez/pocoma/ReadWorkerResourcesPostgresTest.java) et fixtures I8, pas leur campagne complète.

| ID / méthode | Oracle | Lien |
|---|---|---|
| C01 `webRoot` | Démarrer vraie `PocomaWebApiApplication` V3 : huit mappings, principal/ports réels, inventaire pools/beans, aucune admission ni worker implicite pour un WEB READ autonome | D1/D6 ; tous B1 |
| C02 `monolithRoot` | Vraie racine Monolithe, HTTP+façades partagent propriétaire/quota Q=2, participants existants seulement ; les ports n'exportent aucun contexte | D6-composition |
| C03 `legacyComponentsRejected` | Injecter sélection d'un bean HTTP/service V2 dans configuration V3 : refus préflight ; aucun `/api/v1`/V2 mapping exposé en parcours V3 nominal | D1/D6 |
| C04 `readOnlyAuthorityAndNoWriteFallback` | Consultation sous rôle READ de fixture sans permissions de mutation : résultats nominaux ; journal des acquisitions WRITE=0, READ manquant ne consulte pas WRITE ; tentative SQL interdite observée refusée | SPEC-04§1/READ-01 |
| C05 `typedPortsAndDependencies` | Imports/signatures et graphe POM local du changement : HTTP→ports READ, aucun retourcomposition/WRITE, pas module nouveau ; scopes de l'arête de test vérifiés. Preuve locale≠Architecture globale | §§14/17 politique |
| C06 `brunoFixtureProvenance` | État READ alimenté par injecteur séparé, snapshot attendu comparé via SQL indépendant ; succès/rejet/propriété immuables ; HTTP n'a ni accès injecteur ni dépendance aux admissions V2 | B1/A10 contribution READ |

### 7.2 Fixtures et autorités physiques

Un petit harnais de fixture I1 test-only peut réutiliser `ReadRuntimeI8Fixture` et `ReadMonolithI8Fixture` existants **sans changer leurs oracles**, ni rendre leur mot de passe de test public. Pas de framework générique nouveau. Bootstrap CURRENT V3 via migrations existantes : READ `db/v3-consumption-read/migration`, `db/v3-read-business/migration` ; WRITE seulement pour montage/coexistence `db/v3-pot/migration`, `db/v3-pot-execution/migration`, `db/v3-consumption-write/migration`, `db/v3-identity/migration`. Aucune nouvelle migration, aucun historiqueV2 ajouté ; identité READ/WRITE vérifiée par DB/schema/role/server_version/backend PID, jamais supposée d'après le nom du container.

Image fixture existante épinglée : `postgres@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73` ; observer version réelle (I8 rapportait PG 17.11). Deux bases/autorités READ et WRITE séparées, schémas uniques par classe/scénario, administration hors connexions runtime. Pas de baseline TRUSTED inventée, pas de reset partiel d'une READ partagée, pas de campagne PG concurrente. Tests sériels ; les classes ne partagent ni targets de rapports ni fixtures mutables en concurrence. Le banc Bruno reste actif sous un propriétaire de campagne, données en schéma dédié ; son démarrage/collecte sont comptés dans 15 min.

| Fixture future | Contenu fini et oracle indépendant |
|---|---|
| F-Auth | Issuer/clef/audience de test fixes, JWT valides puis variantes signées/altérées, horloge/skew explicites ; secrets hors Git. Attendus définis avant appels, pas par les claims acceptés du décodeur. |
| F-Identity | E1 propriétaire, E2 autre User, E3 même User qu'E1, E4 inactif, E5 absent ; issuer B même subject queE1. Versions/bindings privés, évolution projetée indépendante. |
| F-Pot | Pot moyen 20/200, grand 100/2000, créateur sans Shareholder, membre détaché à V+1, Pot interdit, Pot terminalD et V<D. Manifestes/versions/soldes attendus calculés par vecteurs rationnels de référence et données immuables, sans appeler calcul Balance/mapper de production. |
| F-Volume | Profils D4 renforcés (UTF8/échappements, versions larges, rationnels de 1024 chiffres ; batch 50×20/r 256 et 50×100/r 1024). Génération déterministe, tailles d'entrée/corpus vérifiées statiquement avant test. Mesures réelles nouvelles attribuées T1, jamais ajoutées au reçu D4 historique. |
| F-Lag | Sous-version/Balance V manquante, V−1/V+1 présentes, pointeur avancé entre barrières, commit/rollback injecteur séparé ; attendu à V connu avant requête. R01-N chronométrage du sondage de fixture, sans SLO ni timestamp de commit déduit d'HTTP. |
| F-Navigation | 205 IDs ordonnés pour parcours 100/100/5, jeu élargi fini de 2000 entrées pour plan/index ; mutations avant/après curseur et disparition. Un autre User possède IDs intercalés pour détecter scan/fuite. |
| F-Results | Command succès/rejet, Identity Registration/Unlink succès/rejet, propriétéE1/E2/E3 et UUID absents ; insertions READ test-only sous injecteur admin, comparaison SQL directe attendue sans store testé. Aucun workflow public Registration/Link/Unlink exécuté enI1. |
| F-Transport | DTO finaux valides ajustés aux frontières exactes ; entrée padding JSON ; erreur du dernier champ, client lent/déconnecté, SQL bloqué par barrière. Injection strictement test-only, inaccessible via endpoint/paramètre public. |

Pour C04, le rôle lecteur HTTP test-only reçoit SELECT sur les projections, aucune mutation ; il ne change pas les grants de production. Pour Q10/C02, conserver rôles/pools Worker réels existants ; observation depuis un rôle séparé, et instrumentation des sources attribue chaque emprunt au caller. L'injecteur et l'observateur ne sont jamais le principal HTTP. Tables témoins éventuelles restent strictement dans la fixture, pas en migration applicative. État WRITE inchangé par un appel HTTP READ, preuve SQL et compteur d'acquisitions à l'appui.

Ces oracles qualifient le consommateur HTTP et les ressources. Ils **ne certifient pas à nouveau** A10-A/J (admission/publication), A11-A…E/L/M (exécution/projection/latence), S02-C/F/K complets (WRITE/Consumption/chronologie), REC-01 ou PERF-01. Leur preuve amont n'est réutilisable que sous comparaison SHA/SQL/config/hypothèses selon §34 ; attribution historique conservée. Les aspects de ces scénarios directement exposés par I1 sont testés ici sans revendiquer le scénario end-to-end entier.

### 7.3 Commande exacte T1 et provenance

Depuis `app/`, après GO et création/revue des 40 méthodes :

```sh
./mvnw -pl runtime-web-api,runtime-task-consumption-worker,runtime-monolith -am test -Dtest=V3ReadHttpContractTest,V3ReadQuotaPostgresTest,V3HttpCompositionPostgresTest,V3MonolithHttpCompositionPostgresTest -Dsurefire.failIfNoSpecifiedTests=false -DargLine=-Xmx512m
```

Commande T1 positive identique à l'ancre du plan global. La gate Architecture requise au §2 est exécutée séparément, sans filtre. **Dans T1 : aucun `architecture-tests`, aucun full, aucun `-amd`, `install`, filtre négatif, skip ou extension des quarantaines**. `failIfNoSpecifiedTests=false` permet les modules amont sans classe sélectionnée ; ne permet jamais de manquer une des 40 identités. Les test-compile amont peuvent coûter même si leurs tests ne s'exécutent pas : inclure ce coût dans le budget, et ne pas le présenter comme test historique exécuté.

À la réalisation, déclarer cette sélection dans le registre/collecteur existant `scripts/testing/verification-campaigns.json`/`verify.py`, **sans modifier les anciens oracles/recettes historiques**. La commande ci-dessus fixe la sélection/lifecycle ; le reçu consigne les deux propriétés de provenance ajoutées, avec le drapeau explicite de sûreté `-DargLine=-Xmx512m` pour le banc et les rapports isolés par invocation/module/producer. L'implémentation doit rendre les formes JUnit simples supportables par le collecteur ; si refus de découverte, INCONCLUSIVE et décision ciblée, pas de remplacement par anciens XML. Ne pas inventer maintenant une sous-commande de recette non livrée.

Plan réel de campagne : SHA propre testé, dirty state et empreintes application/POM/tests/SQL/scripts/configuration sémantique avant/après, versions JDK/Maven/Bruno/PG/images, JVM et headers/timeouts, commandes argv sans secrets, modèle effectif, inventaire **40 identités nominatives** et 32 scénarios B1. Aucun XML partagé `target/` antérieur n'est preuve. Chaque identité exactement une exécution fraîche réussie, zéro skip/manquant/doublon ; chaque variante et assertion explicitement exécutée. Une exception aval masquée en 503 ne devient pas une preuve PASS du profil nominal. Aucun double lancement T1 par collecte puis commande native nue.

### 7.4 Budget partagé T1+B1 et arrêt

**Estimation et seuil d'alerte : 900 secondes murales cumulées**, T1+B1 partagés. Préparation effective, découverte, démarrage, compilation, PostgreSQL, JUnit, Bruno et collecte sont mesurés. Développement hors campagne. Une campagne saine progresse jusqu'à son résultat terminal ; aucun arrêt ni retry à 900 secondes.

| Phase prévue | Estimation non mesurée |
|---|---:|
| Préflight, modèle/inventaire, autorités et fixture | 120 s |
| T1, compilation et 40 tests/observations physiques | 420 s |
| B1, 32 scénarios sur un environnement | 180 s |
| Collecte expurgée et terminaison physique | 120 s |
| **Total indicatif partagé** | **900 s** |

Au dépassement : laisser poursuivre, conserver les durées effectives par phase, analyser les causes puis proposer les simplifications utiles sans modifier les oracles. Ne pas relancer une campagne inchangée pour dépassement. Correction concrète ou changement d'environnement identifié : rejouer seulement les preuves invalidées et conserver l'initial.

Arrêt justifié seulement pour risque données/ressources, violation de sûreté/confidentialité, deadlock ou absence de progression constatée, blocage physique durable, processus hors contrôle, borne technique de sûreté indépendante ou échec déterminant rendant la poursuite dangereuse/inutile. Préserver les preuves, terminer/récolter les processus et observer PG/pools/permis. Inconnu physique : UNKNOWN, aucune campagne dépendante avant résolution. Aucun blocage indéfini. Timeouts d'oracle et de sûreté inchangés ; manquant/SKIP/FAIL/timeout ne vaut jamais PASS.

## 8. Séquencement futur et sorties observables

Un seul incrément, pas de sous-lots documentaires ni validation globale à chaque pas. Les vérifications locales de développement restent ciblées et attribuées comme feedback ; aucune campagne supplémentaire obligatoire cachée. La campagne formelle T1+B1 n'est lancée qu'une fois tous prérequis et preuves à collecter prêts.

| Étape | Réalisation future | Critère de sortie observable avant suite |
|---|---|---|
| 1 — Contrats/composition V3 | Ports typés dans modules propriétaires, sélection racines/profils et DTO/contracts §4 ; revue graphe POM test scope prévu | Huit mappings planifiés sur ports existants, aucun cycle HTTP/composition, aucun V2/admission ; diff dans périmètre déclaré. |
| 2 — AuthN et Binding propre | Resource Server V3, claims validés, mapping Binding, erreurs communes | EUI vient uniquement du principal validé, contrat binding sans sélection tierce/UserId, configuration fournisseur/fixture séparées ; négatifs H01–H09 implémentés. |
| 3 — READ/résultats/DTO | Façades réemployées, autorisation à V, curseur keyset et résultats requesterEui | DTO explicites complets, pas de WRITE, retards/404/503 conformes ; corpus attendu indépendant et tests correspondants prêts. |
| 4 — Prévalidation/quota/sérialisation | Correction avant quota, caps/timeout/admission HTTP bornée, encodage avant succès, annulation/arrêt | Protections banc §5 configurées et revues ; aucune affirmation de mémoire/temps avant T1 ; instrumentation physique/barrières prête. |
| 5 — B1 et qualification T1 | Collection de 32 scénarios, fixtures et 40 tests ; revue inventaire/provenance puis une campagne partagée | Reçu complet ou arrêt non-PASS selon §7.4, B1 livré/exécuté, coûts/limites physiques documentés. |
| 6 — Revue preuves et checkpoint I1 | Rapport/manifeste futurs, audit ciblé des résultats/écarts, décision humaine | Contrats prouvés dans leur portée, réserves et acceptation AuthN externe explicitement distinguées ; aucune certification canonique L5. |

Sorties de développement ne dispensent pas des tests d'acceptation ; elles évitent de lancer les campagnes avant que leurs préconditions soient établies. Pas de changement de l'ordre sauf dépendance réelle identifiée et expliquée.

## 9. GO, checkpoint, audit et décisions

### 9.1 GO d'implémentation — décisions acquises

L'[acte humain I1](L5_I1_Human_Ratification_and_GO.md) enregistre les approbations du plan global, de D4, de D6 option B, du présent plan et le **GO I1 explicite**. Aucune nouvelle confirmation nécessaire. Qualifier les secrets et les autorités du banc avant campagne ; fournisseur réel distinct de la fixture locale.

D2/D3/D5, D4-admission, D6-topologie des sept READ et D7 d'admission restent à leur échéance I2–I4. Ils ne deviennent pas des prérequis artificiels de ce sous-ensemble READ. Un problème nouveau est isolé avec sa propriété manquante et une décision ciblée, sans rouvrir le cadrage L5/L1–L4.

### 9.2 Checkpoint d'exécution I1

Critères cumulés : huit endpoints implémentés, aucun composant V2 sélectionné, pas d'admission nouvelle ; contrats AuthN/READ/erreurs/masquage/exactitude respectés ; pas de fuite de UserId, d'autorité ou de secret dans les DTO et logs observés ; prévalidation avant quota, SQL Q=2 et refus HTTP bornés ; sérialisation complète avant succès ; **Bruno B1 livré et 32 scénarios exécutés**, T1 **40/0/0/0** sur inventaire exact avec durées réelles consignées et comparaison aux 900 s indicatives ; pics, coûts, annulation et JDBC observés et limites exposées. Tout profil nominal non servi ou mesure manquante reste une réserve à décider, pas une acceptation implicite.

La preuve physique peut confirmer ou infirmer D4 ; les valeurs restent initiales tant que la décision humaine après mesure n'est pas enregistrée. Une condition de sûreté non tenue bloque l'acceptation correspondante et l'exposition, même si d'autres tests passent. Checkpoint interne et **acceptation externe AuthN** sont deux statuts : fournisseur réel non qualifié → acceptation externe **NOT QUALIFIED**. Pas de certification canonique L5 par T1/B1 ; Architecture/full/G5 restent **NOT RUN** jusqu'à Q5.

### 9.3 Dossier futur prêt pour audit

À créer seulement après GO : `docs/v3/L5_I1_Execution_Report.md`, `docs/v3/L5_I1_Proof_Manifest.json`, preuves sous `docs/testing/evidence/L5-I1/`. Rapport : SHA/entrées/périmètre du diff, modifications et composants réemployés, commandes effectives, résultats des 40 identités et 32 scénarios avec variantes, durées totales/phases, ressources/autorités physiques, erreurs/annulation, statuts D4/AuthN et réserves. Manifeste : exigence → contrat E1…E8 → H/Q/C/B → commande/invocation → SHA/configuration/SQL → artefact/empreinte → verdict ; chaque attribution doit être vérifiable indépendamment du total.

Le reviewer vérifie notamment : références/soldes attendus indépendants ; droits à V contre navigation, propriété EUI des résultats ; impossibilité d'accéder à WRITE/V2 ; borne de sortie avant commit ; SQL Q/Q+1 distingué de HTTP en vol ; backend/permis réellement libérés ; accès actuel/inactif/historique/terminal ; zéro secret dans les artefacts ; campagne menée à son résultat terminal avec dépassement analysé le cas échéant ; aucune acceptation fournisseur déduite des JWT locaux. Audit humain ultérieur distinct de cette auto-vérification documentaire ; aucun audit indépendant fictif créé ici.

Décisions D4, D6, plan et GO acquises par l'acte humain. Le checkpoint technique ne constitue aucune clôture humaine d'I1.

## 10. Historique — contrôles de la mission documentaire de préparation

Départ vérifié : branche attendue, HEAD=`9dd1c447…` publié, worktree propre, divergence HEAD/suivi local origin **0/0**, cinq autres worktrees recensés/préservés. Aucune interrogation réseau distante fraîche revendiquée ; le SHA de référence fourni est exactement HEAD local. Lectures au SHA publié avant rédaction, empreintes des dix fichiers référencés par `L5-D4/SHA256SUMS.json` concordantes ; la calibration elle-même reste attribuée à `5193c540…` et son commit documentaire `9dd1c447…`.

Contrôles statiques de livraison : `git status`, `git branch --show-current`, `git rev-parse HEAD`, `git rev-list --left-right --count HEAD...origin/feat/v3-architecture`, `git worktree list --porcelain`, lectures `rg`/`sed`/`cat`, analyse XML des POM et JSON/empreintes D4 via Python ; revue du diff/périmètre, liens/ancres/sections/tableaux Markdown et **`git diff --check`**. Seuls trois nouveaux Markdown D4/D6/I1 et mises à jour ciblées du plan L5/index sont autorisés. Aucune commande Maven (même modèle/dry-run), PostgreSQL, Docker, Bruno, HTTP, nouvelle calibration, gate historique, Architecture ou full ; aucune modification app/SQL/tests/configuration/SPEC/oracle/acte historique. Aucun commit/push.

Déclaration de rapport : **slices NONE ; DB NONE ; franchissement inattendu NONE ; Architecture globale NOT REQUIRED / NOT RUN pour le documentaire ; full reactor NOT REQUIRED / NOT RUN pour le documentaire**, aucune preuve applicative ni PASS fonctionnel. Les commandes applicatives écrites dans ce plan restent à exécuter après approbation/GO, sans préjuger de leur résultat. Contrôles terminés : **227 liens locaux dont 5 ancres, 44 tableaux à colonnes cohérentes**, blocs de code équilibrés, inventaires H24/Q10/C6 et B32 exacts ; **5 Markdown autorisés seulement**, les dix empreintes de preuve D4 inchangées. `git diff --check` sans erreur, complété par un contrôle whitespace des trois fichiers nouveaux non suivis. Remise à revue humaine uniquement.
