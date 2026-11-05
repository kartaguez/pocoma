# Pocoma V3 — L5/I3 — Link headless simplifié : analyse et plan soumis

> **Clôture humaine du 5 novembre 2026 : L5/I3 — CLOSED — TARGETED LOCAL PASS WITH EXPLICIT RESERVATIONS.** L’[acte autonome](L5_I3_Acceptance_and_Closure_Decision.md) fixe le statut courant et conserve AUD-I3-01/02/03, R-Q1…R-Q5 et les réserves héritées. **L5 OPEN ; I4 ELIGIBLE FOR FRAMING, NO GO ; Q5 DÛ.** Aucun lancement I4/Q5, aucun code ni test exécuté pour cette clôture.
>
> **Clarification du réalisé — AUD-I3-02 :** création sans consultation `CurrentBinding` READ, capture de E1 authentifiée et du bindingToken déclaré ; obsolescence acceptée, autorité métier exclusivement Identity WRITE L3. Aucun défaut d’autorisation métier retenu, aucune réouverture H1 ni contrôle WRITE synchrone. Les anciennes formulations de contrôle READ et l’oracle prévisionnel F02 du §11 restent historiques ; ils ne décrivent pas le test livré et n’imposent aucun ajout de READ. [Réalisation et preuves](L5_I3_Execution_Report.md).
>
> **Historique du plan et du GO conservé ci-dessous :** ses attentes de ratification, NO GO et clôture attendue sont supersédées par les décisions datées, sans réécriture des exigences ni des preuves.

> **Actualisation du 5 novembre 2026 — GO I3 IMPLEMENTATION ACCORDÉ, H1–H5 RATIFIÉES.** La mission humaine « TARGETED TESTS ONLY — NO BROAD GATES » remplace les attentes d'approbation ci-dessous. Le corps initial reste la preuve historique du plan et de sa révision H1 ; ses mentions NO GO / H2–H5 à ratifier ne gouvernent plus l'exécution. [Rapport et reçus I3](L5_I3_Execution_Report.md). Aucune nouvelle étude Keycloak ni serveur d'autorisation externe. Aucune clôture I3 définitive implicite.
>
> **Scope déclaré avant code :** primaire WEB, secondaire COMMAND ; monolithe racine d'assemblage ciblé ; DB CURRENT V3 additive V9. Aucun impact métier L2/L3/PROJECTION/BINDING/Pot ni dépendance Maven nouvelle. L'extension de contrat technique/ACL conserve l'obligation globale §17 **REQUIRED — NOT RUN — DEFERRED TO Q5**. WEB+COMMAND canonique non filtré, Architecture et full expressément interdits pendant la mission ; Q5 conserve ses obligations.
>
> **Budget strict supersédant le §11 :** FAST ≤120 s, PostgreSQL/intégration ≤480 s, Bruno ≤600 s et cumul ≤1200 s, corrections comprises. Arrêter les lancements suivants si une enveloppe est dépassée. Sélections exactes réelles dans `scripts/testing/verification-campaigns.json` : `L5-I3-FAST`, `L5-I3-PG`, `L5-I3-BRUNO`. Pas de relance inchangée ni d'extension implicite.
>
> **Revue R1–R4 et décisions concrètes :** aucun contrôle READ métier bloquant ; stockage des intentions/token déclarés et revalidation L3 seulement. Croissance des propositions acceptée sans purge, rétention future à décider en préservant anti-rejeu/restauration. FK/unique/CHECK standards et deux triggers restants (immutabilité/expiration puis intégrité inverse HTTP), sans trigger de cohérence redondant côté proposition. Correction SQL technique : colonne nullable `v3_identity_request.link_proposal_id` et FK UUID, pour éviter un index portant les tokens/EUI longs ; l'intention exacte est contrôlée au commit par trigger sur INSERT Link HTTP. Aucun changement des records/opérations/transitions L3. Les tests ciblés remplacent les anciennes enveloppes T3/B3, sans constituer une clôture canonique.


Date : **5 novembre 2026**. Dépôt : `kartaguez/pocoma`. Branche vérifiée : `feat/v3-architecture`.

**ANALYSIS + PLANNING ONLY — NO IMPLEMENTATION — NO GO I3.**

HEAD effectivement inspecté : **`2a775a414637684d77485ff3fe1b9c5bf92e8f56`**, identique à la référence initiale fournie ; arbre initial propre. Les constats de code ci-dessous portent sur ce SHA. Le plan initial a été publié au commit documentaire `0b1e8be7dc5e7417ff7fc2d9f10cd001aeb4d760`, également HEAD vérifié au début de la révision H1, avec arbre propre. Cette révision reste documentaire et ne produit aucun SHA applicatif qualifié.

## 1. Synthèse et verdict

Retenir **une proposition persistée distincte d'IdentityRequest** : une table nouvelle, une intention immuable, une expiration calculée par le serveur et un unique passage vers l'admission Identity lorsque E2 appelle explicitement `accept`. Réutiliser Link L3, Discovery/Consumption L2, les résultats et CurrentBinding existants. Aucun serveur d'autorisation, extension Keycloak, scope dynamique, RAR, JWT Link, challenge, page web, moteur ou worker supplémentaire.

E1 demeure l'initiatrice et la propriétaire du résultat. E2 est l'actrice de l'acceptation, exclusivement identifiée par son JWT validé ; elle ne remplace jamais `requesterEui=E1` dans IdentityRequest. L'admission conserve séparément ces deux provenances. E1 n'intervient plus après l'acceptation. Un `202` signifie uniquement une admission durable ; le succès métier est `CHANGED` dans le résultat terminal projeté.

**Décision humaine H1 — A-READ APPROUVÉ.** Source : instruction explicite du responsable dans la conversation « Décision humaine L5/I3 — H1 : A-READ approuvé ». L'autorité métier est évaluée exclusivement au traitement effectif dans **Identity WRITE**, jamais à la création ou à l'admission d'une intention. Le contrôle READ éventuel du Binding E1 est indicatif ; son obsolescence est acceptée. Cette décision réaffirme la séparation admission/exécution, sans créer d'exception architecturale.

**Verdict réévalué : READY FOR PLAN APPROVAL.** Le blocage H1 est levé ; aucun contrôle WRITE à la création n'est requis. A-READ est le chemin retenu du plan. WEB n'accède pas synchroniquement à l'autorité métier Identity WRITE, ne prend aucun verrou métier à la création et n'introduit aucun port d'autorité WRITE. Si E1 perd son autorité avant l'exécution, L3 rejette normalement Link. Les transactions et verrous techniques de proposition/admission restent nécessaires à l'atomicité et à l'anti-rejeu.

Le plan reste soumis à validation humaine globale ; **H2 à H5 demeurent à ratifier séparément**, notamment les amendements de consentement, le contrat HTTP et le GO d'exécution. L'approbation de H1 ne ratifie pas ces autres décisions, ne publie aucune SPEC et ne constitue **aucun GO d'implémentation**.

## 2. Autorités et frontière de vérification

Références lues : [SPEC-02](SPEC-02_Identity_Domain.md), [SPEC-06](SPEC-06_Supra_Contracts.md), parties Identity/S-03/A10/C-01 de [SPEC-10](SPEC-10_Test_Certification.md), [plan global L5](L5_Supra_Entry_Audit_and_Implementation_Plan.md), clôtures [I1](L5_I1_Acceptance_and_Closure_Decision.md) et [I2](L5_I2_Acceptance_and_Closure_Decision.md), [runbook I2](L5_I2_Admission_Operations.md), code et SQL inventoriés ci-dessous. La [Reactor Verification Policy](../testing/Reactor_Verification_Policy.md), notamment §§3, 14, 17, 22–27, 34/35, gouverne les preuves.

**Mission présente, déclaration avant écriture :** impact documentaire uniquement ; slices primaire/secondaires **NONE** ; DB **NONE** ; aucun code, SPEC, migration ou preuve historique modifié ; Architecture globale **NOT REQUIRED / NOT RUN** ; full reactor **NOT REQUIRED / NOT RUN**. Aucun Maven, Docker, PostgreSQL, Bruno, serveur ou expérimentation exécuté. Le mandat final autorise exceptionnellement le commit et le push normaux du plan seul malgré l'interdiction initiale de publication.

**Déclaration prévisionnelle I3, à confirmer au GO :**

| Élément | Périmètre envisagé |
|---|---|
| Impact productif | HTTP proposition/acceptation, admission Link dédiée, stockage de proposition, ACL/préflights et composition V3 ; résumé READ éventuel |
| Slice primaire | **WEB** — `runtime-web-api` |
| Slice secondaire | **COMMAND** — admission et consommation Identity, interaction avec autorité L3 ; pas le runtime BINDING historique |
| PROJECTION | Aucun comportement modifié prévu : CurrentBinding et IdentityResult existants ; ajouter PROJECTION seulement si leur matérialisation change effectivement |
| Monolithe | Racine d'assemblage à vérifier par quelques méthodes ciblées, pas une nouvelle slice ni motif d'ajouter tous ses tests à chaque feedback |
| Interdit | Accès synchrone WEB à l'autorité métier Identity WRITE, verrou métier à la création, nouveau port d'autorité WRITE, exception admission/exécution ; nouveau moteur/worker, changement L2 claims/lease/routing, nouvelle transition Identity, modifications Pot, Invitation, LKV, publication/suffixe partagés, POM/module nouveau sans redéclaration |
| DB | **CURRENT V3**, migration additive après V8 ; aucune baseline TRUSTED supposée |
| Architecture | Lecture de provenance à deux acteurs et nouvelle frontière de proposition ; tests structurels locaux ciblés. Toute modification de frontière architecturale ou d'ACL relevant de §17 déclenche la gate globale ; H1 n'introduit aucune exception d'autorité. Statut des obligations ainsi déclenchées : **REQUIRED — NOT RUN — DEFERRED TO Q5 BY EXPLICIT HUMAN INSTRUCTION** selon clôture I2 §5 |
| Full | Aucun full en I3 ; full natif unique à **Q5**, D6, sous couverture effective. Pas de dispense acquise pour une nouvelle restructuration large |
| Escalade | Dépendance/POM nouveau, transaction partagée modifiée, traitement READ/projection nouveau, changement de contrat L3 : arrêter, expliquer et redéclarer avant code concerné. Un accès WEB à l'autorité métier WRITE contreviendrait à H1 et ne peut être introduit par simple extension du scope |

Les obligations canoniques et filtrées sont distinguées au §11. Il n'est proposé **aucun rejeu de tous les modules amont d'Architecture en I3**.

## 3. Décision fonctionnelle proposée et limites

1. E1 authentifiée propose de rattacher E2 au compte que désigne **son occurrence de Binding déclarée**, jamais à un UserId choisi par le client. Le contrôle READ de cette occurrence reste indicatif ; seule l'exécution Identity WRITE vérifie l'autorité métier courante et résout U pour effectuer Link. La création ou l'admission ne garantit jamais que le Link sera autorisé à l'exécution.
2. La proposition n'est pas une demande métier déjà admise : ni slot, ni événement, ni résultat Identity avant acceptation.
3. E2 s'authentifie chez son propre IdP ; le client affiche le récapitulatif puis envoie le POST d'acceptation. La possession du lien/UUID ou un login/GET seul ne vaut pas accord.
4. Pocoma constate cette action HTTP explicite de l'EUI cible et en conserve la provenance serveur. Un JWT ordinaire sert à AuthN, pas à porter un octroi Link autonome.
5. Acceptation et admission sont une seule transaction ; aucun second accord E1 ni requête de confirmation.
6. L3 revalide E1, le token d'occurrence initial, E2 et les préconditions, sous ses verrous et protections actuels. Une révocation de Binding E1 avant exécution entraîne rejet, même si elle est ensuite rattachée avec un autre token.
7. Une révocation d'entitlement externe après son admission ne déclenche pas de nouvel appel IdP au Worker (SPEC-02 §5, I02-H/K). « E1 perd ses droits » doit distinguer cet événement de la perte d'autorité Pocoma courante.
8. E1 consulte le résultat par son AuthN, même après Unlink ; E2 consulte uniquement son propre CurrentBinding. Aucun token E2 dans proposition, acceptation ou résultat E1.

Keycloak reste le premier IdP du banc. Le code d'intention et la comparaison EUI restent fondés sur les chaînes exactes `(issuer, subject)`, sans email, nom, alias Keycloak, trim, case folding ou normalisation Unicode. La composition V3 actuelle ne fait confiance qu'à **un issuer configuré** : I3 peut qualifier deux comptes de ce même issuer. La fédération multi-issuer effective nécessite une décision et des validateurs par issuer supplémentaires ; elle n'est pas acquise par l'indépendance du modèle EUI.

Le backend ne peut prouver qu'un humain a réellement lu l'écran d'un futur client. Le nouveau contrat prouverait un POST explicite authentifié sur une intention immuable, sous la confiance accordée au client et au bearer. Le durcissement de la présentation/consentement relève d'un travail séparé ; ne pas annoncer une attestation externe ni une preuve de présence humaine.

## 4. Inventaire réutilisable et écarts concrets du code

Chemins relatifs au dépôt ; classes Java sous `src/main/java/com/kartaguez/pocoma/` du module indiqué.

| Module / fichiers existants | Réemploi et adaptation nécessaire |
|---|---|
| `contracts-registration/.../contracts/registration/v3/IdentityRequest.java` | `Link(target)`, token E1, E1 requester, AdmissionContext avec empreinte/corrélation/E1/E2. `AUTHENTICATED_HTTP` existe déjà ; ne pas utiliser `EXTERNAL_AUTHORIZATION_SERVER` pour le POST E2. Capturer une version de contrat propre au protocole à deux acteurs |
| `engine-admit-registration/.../v3/IdentityAdmissionInput.java` | Décode seulement Registration/Unlink ; conserver le refus Link sur `/identity/requests`. Nouvelle entrée de proposition/acceptation typée, pas ajout d'un champ client `consent=true` dans ce décodeur |
| Même module, `AdmitIdentityRequestV3.java` | S-03 : portée HMAC, comparaison canonique historique avant validation actuelle, réserve + insertion atomique. **Non appelable tel quel avec E2** : impose principal=requester. Ne pas fabriquer un principal E1 depuis les données stockées ; service dédié utilisant provenance E1 déjà vérifiée + principal E2 courant |
| `contracts-command/.../v3/AdmissionProtection.java`, `AdmissionAssociation.java`, `AdmissionFields.java` | Réutiliser canonicalisation, versions, séparation de portées, HMAC et anciennes clés. Nouveau type canonique Link séparé, sans changer les octets V1 Registration/Unlink/Command (réserve AUD-I2-01) |
| `infra-persistence-primary-jpa/.../v3/JdbcAdmissionStore.java` | Réservations S-03 et insertion physique ; refuse explicitement tout Link public aujourd'hui. Ajouter un chemin dédié étroitement couplé à une proposition acceptée ; ne pas supprimer simplement cette garde |
| Même module, `AdmissionSqlTransactions.java` / `AdmissionSqlAuthority.java` | Observation physique des issues incertaines, propriétaire de transaction, contrôles effectifs de privilèges/logs par colonne. Étendre seulement l'inventaire nécessaire, jamais rétablir SELECT global sur les demandes ou tokens d'autorité |
| `engine-consume-registration/.../v3/ExecuteIdentityRequest.java` | Link existe, intention rechargée/immuable, E1/E2 verrouillées en ordre canonique, autorité courante, résultat/completion/publication. Réemploi sans nouveau participant ni traitement dans HTTP |
| `domain-user-identity/.../v3/IdentityTransitions.java` | `link` contrôle token E1 courant et E2 non liée, déduit U de E1, token neuf ; rejet strict même si E2 déjà au même U. Aucune modification fonctionnelle prévue |
| Persistence `.../v3/identity/JdbcIdentityAuthority.java`, `JdbcIdentityRequestStore.java`, `IdentityRequestJson.java` | Autorité/histoire/token conservés exclusivement dans le traitement WRITE existant ; aucune de ces capacités d'autorité n'est appelée à la création/admission WEB. Réutiliser la sérialisation d'admission sans exposer le port d'autorité (§7.4) |
| `supra-http-write/.../v3/V3AdmissionController.java`, `V3AdmissionTransport.java` | Bearer, corps borné, champs fermés, médias, quotas jusqu'à issue physique et envoi. Étendre à routes Link : patterns de filtre et `shouldNotFilter` sont tous deux actuellement limités aux deux POST I2 |
| `composition-runtime-spring/.../v3/V3AdmissionHttpConfiguration.java`, `V3ReadHttpConfiguration.java` | Assemblage WEB/Monolithe explicite, pools séparés, préflight, JWT RS256, signature/JWKS, issuer exact, audience, temps et claims requis ; `/api/v3/**` authentifié. Ne pas réutiliser la configuration V1 historique |
| `engine-read-current-binding/.../v3/GetCurrentBindingV3.java` et `supra-http-read/.../v3/V3ReadController.java` | Binding propre sans token entrant ; résultat Identity propriété E1, 404 avant projection/non-autorisé et 503 de disponibilité ; réemploi |
| `port-projection/.../v3/PotVisibilityStore.java`, façade READ V3 | `visiblePots(user,after,limit,context)` existe, **aucun count dédié**. Ne pas compter une page et la présenter comme total. Compteur optionnel à décider au §6 |
| SQL `db/v3-identity/migration/V6__identity_authority_and_requests.sql` | IdentityRequest PENDING/TERMINAL, contraintes/faits atomiques et événements existants ; aucun état WAITING_CONSENT à ajouter |
| SQL `db/v3-admission/migration/V7__public_admission.sql`, `V8__admission_column_reads.sql` | Association FK vers demande **existante à commit**, immuable, provenance HTTP autorisée ; V8 réduit les lectures des triggers. Pré-proposition ne peut être insérée dans cette association sans créer IdentityRequest ou changer ce contrat |
| `scripts/v3/grant-l5-admission.sql` | Réutiliser resserrement des grants/logs et préflight, ajouter seulement droits de proposition. Le rôle actuel n'a pas accès aux tokens de l'autorité métier |
| Tests I1/I2 et `scripts/bruno-v3/` | AuthN, ownership, canonicalisation, S-03, commit incertain, fixtures réelles HTTP/PG et CLI 4.3.0. B221 et assertions `linkIsNotPublic` doivent évoluer de façon ciblée : Link brut toujours refusé, nouveau parcours explicitement disponible |

Aucune simple présence de JSONB de provenance ne prouve AuthN ou consentement. `AdmissionContext.attestations` est une map générique, pas un validateur. La nouvelle provenance doit être produite uniquement par le service d'acceptation et testée avec le véritable Resource Server.

## 5. Clarification H1 et amendements SPEC proposés — NON APPLIQUÉS AUX SPEC

**H1 est approuvée comme invariant explicite de ce plan** : les formulations ambiguës sur « Binding actif à la création » se lisent comme un contrôle READ indicatif, sans autorisation métier préalable. Cette clarification réaffirme la séparation déjà prescrite par SPEC-02 §5 et SPEC-06 §§1–2. Les autres textes ci-dessous restent des propositions de remplacement ciblé, **non ratifiées et non publiées dans les SPEC** ; leurs exigences actuelles, notamment d'attestation externe de consentement, restent à amender séparément.

| Référence précise | Incompatibilité / conservation | Clarification approuvée ou amendement à soumettre |
|---|---|---|
| SPEC-02 §4 dernier tiret, §5 « Décision validée » et « Contrat maintenu » | Exige serveur d'autorisation qualifié délivrant une preuve ; RAR déjà facultatif. Nouvelle action directement sur Pocoma | « Pour ce protocole, le consentement est constaté par un POST explicite de l'EUI cible authentifiée sur une proposition immuable, expirante et identifiée. L'IdP authentifie ; aucun serveur d'autorisation ni octroi JWT spécifique n'est requis. » Conserver intégrité, audience JWT, expiration, anti-rejeu, admission unique et revalidation L3 |
| SPEC-02 §4 précondition E1 liée, §5 admission déclarative sans lecture WRITE ; SPEC-06 §§1–2 | La précondition métier E1 active s'applique à l'exécution ; ne pas la transformer en contrôle WRITE à la création/admission | **H1 APPROUVÉE — clarification du plan, aucune exception à proposer.** « La création peut contrôler indicativement le Binding E1 depuis READ, avec obsolescence acceptée. Seul Identity WRITE vérifie l'autorité métier lors du traitement effectif et rejette Link si E1 a perdu son autorité. » Aucun accès synchrone WEB à l'autorité WRITE, verrou métier ou nouveau port d'autorité |
| SPEC-02 §5 récapitulatif | Identité E1 et nombre indicatif de Pots par CurrentUserPotList ; source existante mais count non fourni | Conserver identité E1/action/E2/expiration ; autoriser explicitement `potCount=null` et date/source lorsque compteur indisponible, conformément à la nouvelle décision « lorsque disponible ». Pas de liste de Pots ni UserId ; absence du compteur n'invalide pas le consentement |
| SPEC-06 §2 tableau Identité/protocole, ADM-01(H), premier dépôt et paragraphe Link S-03, §3 | Attestation **externe** non rejouée obligatoire ; « aucune admission sans preuve » reste essentiel | Remplacer la nature de preuve par action E2 authentifiée liée à proposition ; distinguer création de proposition (durable technique) et admission Identity. Refus d'acceptation = zéro **nouvelle** IdentityRequest/association/événement, pas forcément zéro proposition déjà existante |
| SPEC-06 §2 S-03 / §7 binding propre / §8 résultats | S-03 exige clé client ; résultat appartient à E1, binding à E2 | Conserver S-03 client à création ; préciser idempotence naturelle de `accept` par linkRequestId et association interne (§7.2), sans nouvelle clé libre. Conserver propriété résultat E1 et impossibilité E1→token E2 |
| SPEC-06 §1 lectures HTTP, §12 synthèse de garanties (ligne consentement externe ID-02/C-01) | Qualification du serveur devenue hors protocole cible | Remplacer par qualification JWT IdP réel + POST E2 + atomicité/anti-rejeu ; autoriser explicitement le GET de la **propre autorité technique de proposition** sur le stockage primaire, sans accès au domaine métier WRITE. Les résultats/bindings/Pots restent exclusivement READ ; ne pas supprimer les preuves de sécurité |
| SPEC-10 matrice ID-02, section ID-02 « Statut », frontières confiance et audit, I02-A…Q, méthode/PASS, amendement « Rôles » et scénarios | Interdit mécanisme différé/workflow et prescrit données dans seule demande admise/serveur qualifié | Autoriser **une proposition technique**, sans nouvel état métier/Consent/workflow ; deux preuves temporellement distinctes E1 puis E2, conservées sans JWT brut. Adapter les oracles au POST ; conserver A…Q, notamment H/I/J/K/L/N/O/P/Q |
| SPEC-10 I02-C/D/E/F/O | Absence consentement, substitutions, attestation capturée, signature/émetteur, fournisseur réel | C : GET/login seuls ne créent pas IdentityRequest ; D : accept ne reçoit aucune EUI ; E : une proposition ne peut admettre qu'un id ; F : JWT falsifié/expiré/mauvais issuer/audience ou proposition expirée refusé ; O : deux authentifications Keycloak réelles + POST explicite. Signature d'octroi spécialisée sans objet ; vérification cryptographique JWT conservée |
| SPEC-10 S-03 et A10-D, conclusion C-01 | Preuve d'octroi externe et consentement séparé, qualification serveur | Définir l'idempotence de ressource `accept` et conserver tous les cas S-03 applicables ; A10-D reste E1 propriétaire malgré transport E2 ; actualiser C-01 sans déclarer sa certification acquise |
| Plan global L5 §§6/8/12/13/14/15/16 (Link, T3/B3, D3, D7, R5-01) | Contexte/grant signés, D3-fournisseur et anti-rejeu par émetteur+grant, pas de proposition persistée | Remplacer après approbation par A et protocole ci-dessous ; retirer la sélection d'un serveur d'autorisation du chemin critique, garder qualification IdP réel et frontières transactionnelles. T3 ne valide plus de grants Link |
| Clôtures I1/I2 | Réserves AuthN réel, I3 non clos ; report Architecture à Q5 | **Aucune réécriture historique**. Distinguer exigences futures supersédées par décision publiée et preuves historiques ; conserver toutes les réserves non levées |

Proposition commune de formulation : « La provenance d'admission Link capture l'initiateur E1 authentifié lors de la proposition, le témoin de son occurrence de Binding, la cible exacte E2, l'identifiant, l'échéance et l'empreinte d'intention ; elle capture ensuite le principal E2 validé et l'instant de son action explicite. Ces données serveur sont liées atomiquement à l'unique IdentityRequest. Le Worker ne redemande ni JWT E1 encore valide ni consentement E2 ; il revalide l'autorité métier courante E1/E2. »

L'expiration de la proposition est une limite à la **première acceptation/admission**, pas un délai d'exécution L3 ni une expiration des résultats. Aucun résultat terminal EXPIRED ajouté. Après admission, les retries techniques conservent le consentement validé, y compris après l'échéance.

## 6. Contrat HTTP proposé

Chemins au pluriel cohérents avec `/identity/requests`, proposition nommée `link-requests` pour l'utilisateur ; documentation explicite de sa distinction avec IdentityRequest interne. Bearer stateless, TLS du déploiement, confiance issuer/JWKS/audience fixe, pas de cookies locaux ni de JWT en URL. Un UUID imprévisible identifie la proposition mais **n'autorise rien**.

| Route | Appelant / entrée fermée | Retour nominal / autorité |
|---|---|---|
| `POST /api/v3/identity/link-requests` | E1 ; `Idempotency-Key` format I2 ; corps `{bindingToken,target:{issuer,subject}}` ; E1 depuis principal, id/heure/expiration serveur | **201** `{linkRequestId,expiresAt}` ; `Location` vers proposition. Aucun requestId d'admission ni promesse métier |
| `GET /api/v3/identity/link-requests/{linkRequestId}` | E2 cible pour récapitulatif ; E1 initiatrice aussi, pour reprendre le parcours ; aucun token entrant, aucune EUI sélectionnable | **200** DTO allowlist : `linkRequestId`, `initiator`, `target`, action fixe `LINK_TO_INITIATOR_ACCOUNT`, `createdAt`, `expiresAt`, état technique ; `requestId` seulement si admise ; `potCount` nullable et `potCountAsOf` nullable |
| `POST /api/v3/identity/link-requests/{linkRequestId}/accept` | **E2 exactement**, corps vide, aucun bindingToken, EUI, UserId, nouvelle expiration, empreinte ou attestation client ; pas d'Idempotency-Key libre | **202** `{linkRequestId,requestId}` uniquement après commit confirmé. Rejeu autorisé retourne les mêmes ids, y compris après terminalisation ou expiration ultérieure |
| `GET /api/v3/identity/requests/{requestId}/result` | E1 seulement, AuthN existante | Contrat I1 inchangé : 404 uniforme avant matérialisation/non-propriétaire ; 200 terminal CHANGED/REJECTED ; 503 panne. E2 ne devient pas propriétaire du résultat |
| `GET /api/v3/me/binding` | E2 dans sa propre session, `minVersion` éventuel | Contrat I1 existant, son seul token si actif ; le token ne figure pas dans les quatre premières réponses |

`Location` de `accept` : URI de **proposition**, accessible E2 et E1, et non un résultat E1 qu'E2 serait autorisée à lire. E1 obtient le requestId dans la proposition ; l'URI de résultat est documentée. Deux identifiants publics distinctement nommés, même si égaux par construction (§7).

**Compte de Pots :** recommandation initiale `potCount=null`, puisque l'API existante offre une navigation paginée, pas un compteur total borné. Aucune énumération paginée exhaustive à chaque consultation, aucun total déduit d'une page. Si le responsable exige une valeur non nulle en I3, ajouter une requête READ dédiée `COUNT` de Pots distincts visibles du User résolu par le Binding E1, avec date de snapshot, quota Q=2 et timeout ; aucun id/libellé de Pot ne sort. C'est un delta READ à déclarer, pas une nouvelle projection/worker par défaut. Le snapshot peut être capturé à la création et conservé ; pas d'appel READ sous verrou d'acceptation. Une panne du compteur donne null, jamais un fallback WRITE. L'affichage d'un compte inconnu doit être explicite.

**Erreurs et protections :**

- 401 : bearer absent/invalide, signature, issuer non approuvé, audience/temps/claims invalides ; avant accès aux propositions.
- 400 : UUID/corps/clé invalide, champs inconnus ou tentative d'altération ; 413 taille ; 415 médias/encodage selon I2. Aucun nouveau durable.
- 404 identique : proposition absente ou appelant différent de E1/E2 ; E1 qui tente `accept` reçoit 404 comme E3. Contrôler l'autorisation avant de révéler expiration/état.
- 410 : proposition non acceptée expirée, **uniquement** pour E1/E2 autorisées. Expiration calculée, pas purge ; GET peut rendre 410 plutôt qu'un récapitulatif utilisable. Proposition déjà acceptée reste consultable et rejouable.
- 409 : même clé de création pour intention différente. Nouvelle cible = nouvelle proposition, pas PATCH. E1=E2 refusé 400 à création : ce parcours exige deux EUI ; ne pas modifier la transition interne L3.
- 503 : dépendance indisponible, saturation, timeout ou issue physique non résolue. Ce code n'affirme pas rollback. Retry borné du **même** id/clé ; aucune création de remplacement automatique.

Toutes les réponses `Cache-Control: no-store`, JSON borné, erreurs génériques, aucun JWT/token/admission_context/UserId ni données d'autres EUI. Pas de liste globale/recherche par cible, d'envoi de mail, de notification, de cancel/decline, de purgeur ou de GET avec effets. E1 transmet le linkRequestId à E2 hors API ; le futur client gère affichage et geste explicite.

## 7. Représentation et transactions

### 7.1 Comparaison A / B

| Critère | A — proposition persistée | B — intention signée sans persistance préalable |
|---|---|---|
| Surface initiale | Une table, create/get/accept | Signature/validation/version/key rotation ; transport obligatoire d'un blob à chaque GET/accept |
| Tables/migrations | 1 table + 1 migration additive ; association d'admission réutilisée | 0 table avant acceptation, mais anti-rejeu durable à acceptation ; table/contrainte de grant ou adaptation association existante ; migration probable |
| États | Non acceptée/acceptée ; expiration dérivée | Pas d'état avant acceptation ; état durable consommé malgré tout nécessaire après |
| Confidentialité | Token E1 stocké avec ACL/logs protégés, jamais sorti | Signature seule laisse token E1 lisible par E2/client : chiffrement authentifié ou référence protégée supplémentaire nécessaire |
| Intention/occurrence de Binding E1 | Capture durable d'AuthN E1 + occurrence/token, indépendante de validité future de JWT | Contexte E1 signé et éventuellement chiffré à conserver ; JWT E1 ordinaire expiré ne suffit pas ; nouvelle responsabilité cryptographique |
| Concurrence/anti-rejeu | Verrou de ligne, FK/unique, même id ; admission indivisible | Vérification signature ne consomme rien ; unique digest/jti durable atomique avec admission toujours nécessaire |
| Idempotence create | Clé E1 + empreinte dans même ligne ; retrouve id après réponse perdue | Sans persistance, retry doit redonner même id/intent/expiration ou retourner un autre grant : dérivation déterministe, fenêtre de clés et règles supplémentaires |
| Expiration | Heure DB après verrou, pas de tâche | Heure serveur à vérifier avant insertion durable ; expiration de blob vs replay consommé à ordonner |
| Reprise interruption | GET/id + clé E1 retrouvent contexte et admission | Blob perdu avant acceptation = intention perdue ; id seul ne permet pas le GET demandé, à moins de réintroduire stockage ou blob dans URL/corps |
| Relation ids | Égalité proposée contrôlée par FK à acceptation | jti→identityRequestId à figer, unicité à persister ; même id ne dispense pas de registre |
| Réemploi | Table admission séparée ; IdentityRequest existante seulement après accord | AdmissionContext existant peut capturer preuve validée, mais ne fournit ni signature ni consommation |
| L2/L3 | Aucun travail en attente de consentement ; seul Link admis devient source existante | Idem si blob validé avant admission ; aucun avantage d'exécution |
| Coût global | Un durable clair, pas de clés Link nouvelles | Économie d'une table pré-admission compensée par crypto, confidentialité, registre et récupération |

**Choix : A.** B n'est pas intrinsèquement interdit ni forcément insûr ; il coûte davantage pour fournir GET par id, reprise, secret E1 et consommation unique. Il ressemble précisément au contexte/octroi spécialisé que cette mission veut éviter. La proposition A n'est pas IdentityRequest et n'introduit ni état d'attente dans L2 ni objet Consent métier.

### 7.2 Schéma logique minimal A et S-03

Une nouvelle relation proposée `v3_link_proposal` :

- UUID `link_request_id` PK, généré serveur une seule fois ; E1 issuer/subject, E2 issuer/subject exacts, token d'occurrence E1 ; version de contrat et données minimales d'AuthN E1 vérifiées (issuer/sub, auth_time/iat/exp et instant de capture, **sans JWT brut**).
- `created_at`, `expires_at` immuables. TTL **proposé 10 minutes**, non mesuré, à ratifier ; échéance fixée une fois par l'heure DB, aucune extension au retry.
- Champs techniques de création S-03 : scope fingerprint unique E1/LinkProposal/clé client, canonical version, comparaison/HMAC key id et intention fingerprint. **Pas d'IdentityRequest/FK d'admission avant acceptation.** Ne pas détendre V7 pour y loger une proposition.
- `accepted_at` et `identity_request_id` initialement null, renseignés ensemble une seule fois. `identity_request_id` UNIQUE, FK différée vers `v3_identity_request(request_id)` ; CHECK nullité conjointe et `identity_request_id=link_request_id` quand présent. Nom technique PENDING/ACCEPTED dérivé ; EXPIRED dérivé seulement si non acceptée et heure dépassée. Aucun PROCESSING/REJECTED métier supplémentaire.
- Colonnes d'intention/provenance E1 inaltérables ; une seule transition d'acceptation, pas de delete V3. Trigger de cohérence différé vérifie le Link E1/token/E2/corrélation exact et son association, dans les deux sens pour les nouveaux Links HTTP de ce contrat. Les origines internes historiques restent valides sans proposition.

La même valeur UUID devient `identityRequestId` **à l'admission seulement**. Avant celle-ci, le GET résultat ne doit inventer aucune demande/réponse. Cette égalité supprime une allocation et facilite reprise ; elle ne fusionne pas les deux types de demandes.

La création a sa propre unicité S-03 dans la proposition ; empreinte inclut E1, occurrence token E1, E2, opération, contrat. TTL serveur, id et instants générés ne sont pas de nouveaux champs client à comparer ; leur valeur initiale est retrouvée. Même clé/intention équivalente retrouve l'id, même après expiration ; différente donne 409 ; nouvelle clé permet une nouvelle proposition sous règles actuelles. Après expiration on ne réanime jamais l'ancienne.

À `accept`, l'adresse de ressource est la clé naturelle immuable : **pas de deuxième clé client libre**. Proposition connue = au plus une IdentityRequest. Réutiliser aussi `v3_admission_association` pour le Link, portée serveur E1/IDENTITY/Link/linkRequestId, client attesté absent comme I2, intention canonique liée à proposition. L'association et IdentityRequest portent le même UUID. E1 provient de l'acte de création vérifié ; E2 du principal courant. Ce n'est pas un appel d'AdmitIdentityRequestV3 avec faux principal. Le contrat de S-03 doit explicitement ratifier cette idempotence de ressource, au lieu de prétendre que son header de clé client actuel est déjà implémenté ici.

### 7.3 Acceptation : unité physique et reprise

Ordre proposé, dans un service d'admission dédié, jamais dans le contrôleur :

1. Valider réellement JWT E2, claims/issuer/audience/temps ; entrée fermée. Acquérir le quota admission existant et garder sa propriété jusqu'à issue physique/envoi selon I2.
2. Ouvrir **une** transaction physique d'admission via l'owner existant. Charger la proposition `FOR UPDATE` ; absente → 404 ; comparer **les deux chaînes exactes** de E2 avant tout retour d'état ou replay. Une empreinte ou un id connu n'autorise rien.
3. Si déjà acceptée et cohérence durable vérifiée, retrouver les ids ; ne pas réappliquer expiration, règles d'admission nouvelles ou consentement. JWT actuel valide toujours obligatoire. Une corruption → erreur technique, pas création de remplacement.
4. Sinon vérifier heure DB courante **après attente du verrou** : `now < expiresAt`. Utiliser une heure réellement courante telle que `clock_timestamp()`, pas le timestamp de début de transaction qui pourrait précéder une longue attente. Revalider la non-expiration du JWT E2 au point de validation final ; pas d'acceptation par un bearer devenu expiré pendant l'attente.
5. Calculer l'empreinte versionnée de l'intention stockée, créer AdmissionContext HTTP propre au protocole, E1 initiateur/E2 cible et accepteur, instants, référence linkRequestId, empreinte et version de validateur. Données issues du serveur uniquement, pas de preuve client opaque acceptée sans vérification.
6. Réserver l'association S-03 et insérer IdentityRequest Link PENDING avec **requester E1/token E1/cible E2**, puis marquer la proposition acceptée. Contraintes/FK/guards vérifient l'unité et l'immutabilité à commit. Ordre de verrous commun **proposition → association → demande** sur ce chemin ; aucun verrou d'autorité métier EUI ni accès READ sous ce verrou.
7. Commit confirmé → 202. Aucun appel d'ExecuteIdentityRequest, transition EUI, slot/claim manuel ni événement de mutation dans cette unité. Discovery L2 découvre ensuite la demande, indépendamment du succès de l'envoi HTTP.

Deux acceptations simultanées sérialisent sur la même ligne ; la seconde relit la valeur committée. Des propositions distinctes vers E2 peuvent être admises ; L3 n'accorde qu'un succès de rattachement, conformément à I02-N. L'acceptation unique ne rend pas un Link strict idempotent par affectation.

Rollback avant commit : proposition non acceptée, aucune nouvelle association/demande. Commit réussi/réponse perdue : retry du même id retrouve l'admission. Commit inconnu : 503, observation physique indépendante selon AdmissionSqlTransactions ; aucune compensation, nouvel id ou relance concurrente. Le verrou PG arbitre l'issue résiduelle ; si résolution non observable, conserver le permis comme I2 (A=1 peut rendre le service indisponible, réserve conservée). Ne pas déduire d'un timeout HTTP que PG a terminé. Après résolution, retry du même id : admission retrouvée, ou nouvelle première admission seulement si encore dans l'échéance ; sinon 410.

Aucune nouvelle fenêtre entre « acceptée » et « admise » ; les deux sont committées ensemble. La réussite métier et sa publication restent dans une **autre transaction L2/L3**, entièrement autoritative et inchangée.

### 7.4 Création : invariant H1 approuvé — A-READ

**L'autorité métier est évaluée exclusivement au traitement effectif dans Identity WRITE.** La création et l'admission capturent une intention ; elles ne certifient ni le Binding WRITE courant de E1, ni la disponibilité de E2, ni l'autorisation métier future du Link. Un contrôle READ ne devient jamais une seconde autorité métier.

Le chemin A-READ du plan utilise le propre CurrentBinding READ de E1 pour un contrôle indicatif actif/token concordant, par port typé ; E1 reste déduite du principal et le token déclaré est conservé immuable. Une incohérence visible dans READ peut entraîner un refus de création, une indisponibilité READ une réponse technique, sans recherche de confirmation dans WRITE. Rechercher d'abord une proposition déjà créée sous la même clé S-03 : sa répétition conserve l'id et l'intention initiaux sans refaire ce contrôle indicatif contre des projections nouvelles. AuthN et comparaison de l'intention restent exigées pour le replay.

**Obsolescence READ acceptée :** E1 peut déjà avoir perdu son Binding dans WRITE alors que READ le présente encore actif. La proposition peut alors être créée puis admise après acceptation E2 ; cela n'autorise aucun effet Identity. Au traitement, L3 vérifie son autorité courante et le token d'occurrence initial sous ses protections transactionnelles normales, puis rejette Link si E1 n'a plus cette autorité. Une perte après création ou admission suit la même règle ; aucun rejet anticipé WRITE n'est requis.

**Interdictions invariantes :** aucun accès synchrone à l'autorité Identity WRITE depuis WEB, aucun verrou métier à la création, aucun nouveau port d'autorité WRITE et aucune exception à la séparation admission/exécution. Les verrous techniques de proposition/admission nécessaires à l'unicité et à l'atomicité (§7.3) ne sont pas des verrous d'autorité métier EUI. Les verrous métier L3 existants restent exclusivement dans le traitement effectif.

La variante de contrôle WRITE à la création précédemment envisagée est retirée du plan. Aucun complément de source/owner/role/pool d'autorité ni preuve de sérialisation métier create/Unlink n'est nécessaire. **L'absence de vérification WRITE à la création ne bloque pas I3.** Les tests prouvent à la place l'obsolescence READ acceptée, l'absence de consultation d'autorité WRITE par WEB et le rejet métier normal par L3.

## 8. Garanties : propriétaires et preuves

Les identifiants de tests futurs sont définis au §11 ; ce tableau n'affirme aucune exécution.

| Invariant / risque | Garantie proposée et propriétaire | Preuve décisive |
|---|---|---|
| 1. Création E1 authentifiée / contrôle READ indicatif | HTTP AuthN + token déclaré ; contrôle READ sans autorisation métier, obsolescence acceptée (**H1 APPROUVÉE**). Seul L3 vérifie le Binding WRITE courant | F01/F02, P01/P10, H04 ; READ périmé peut permettre la proposition/admission, puis rejet WRITE sans effet E2 |
| 2–4. E2 propre IdP, couple exact, aucune sélection client | Resource Server vérifie signature, trust, audience/temps ; principal E2 confronté à cible immuable ; accept sans EUI entrante | F03/F04, H01/H02 ; B302/B303/B304/B305 |
| 5. Intention immuable | Service + PG colonnes/triggers, occurrence E1 conservée ; aucun PATCH ; données serveur | F05, P02, B308 ; aucune substitution requester=E2 |
| 6. Expiration serveur | Heure DB après verrou ; date initiale inchangée ; replay déjà admis avant contrôle d'expiration | F06, P03, B306/B307 |
| 7–8. Unique acceptation / anti-rejeu | PG verrou + PK/FK/unique ; même id de demande ; tombstone conservée après rejet/succès/Unlink ultérieur | P04/P05/P06, B307 ; compter demandes/versions/token/events indépendamment |
| 9. Acceptation/admission atomiques | Même owner physique, association+IdentityRequest+acceptance ; rollback/cross-check différé | P05/P07/P08 ; pas deux tx nested ou afterCommit |
| 10. Absence fuite binding/token | DTO allowlist, résultat E1, binding propre E2, ACL par colonne, logs/cache/rapports protégés | F09, P09, H03/H04, B309/B310 |
| 11. Revalidation WRITE | L3 token E1 courant, E2 libre, U depuis E1, verrous canoniques jusqu'au commit | P10/P11, méthodes L3 réutilisées ; B311/B312 |
| 12. S-03 / transactions | Clé création E1 + canonical version ; accept clé naturelle ratifiée ; PG arbitre, historique V1 intact | F07/F08, P04…P08/P12 ; tests I2 ciblés |
| 13. Pas de métier synchrone à accept | Uniquement persistance d'admission ; aucun port d'exécution/autorité WRITE dans service accept | H04 et test structurel local F10 ; Worker arrêté : 202 et zéro effet EUI |
| 14. Absent/expiré/interdit | AuthN puis masquage uniforme, expiration révélée seulement aux parties | H01/H02, P03, B303…B306/B313 |
| 15. Panne/commit incertain | 503 non conclusif, observation physique, conservation permis et id, replay état durable | P07/P08, B314 ; panne observer non transformée en réussite |
| Autorité E1 jusqu'à exécution | AuthN E1 capturée vérifiée, token d'occurrence immuable ; pas de JWT brut/revalidation IdP différée | F05, P10 ; expiration ultérieure JWT E1 ne change pas requester ; perte Binding = rejet |
| Ordre/atomicité L2/L3 | Réutiliser claim/fencing/recovery et suffixe fermé existants ; aucune modification de lease | P10/P11 + preuves héritées exactes, pas campagne complète L2 |

L'anti-rejeu ne dépend pas du seul fait qu'E2 reste liée : rejouer après un Unlink de E2 doit toujours retrouver la **même demande**, jamais créer un nouveau Link. Les propositions, associations et preuves nécessaires ne sont pas purgées en V3 ; rétention/restauration et coûts longs restent ouverts à L6/Q5, sans nouvelle tâche de nettoyage.

## 9. Inventaire prévisionnel des fichiers et migrations

**Aucun de ces fichiers n'est créé/modifié par cette mission**, à l'exception du présent plan.

| Travail futur | Fichiers/modules envisagés ; portée |
|---|---|
| Contrats | `contracts-registration/.../v3/LinkProposal.java` et DTO/provenance typés ou contrats imbriqués ; conserver IdentityRequest et son format historique. Si validation typée impose un delta AdmissionContext, versionner et tester décodage historique |
| Services/ports | `engine-admit-registration/.../v3/CreateLinkProposalV3.java`, `GetLinkProposalV3.java`, `AcceptLinkProposalV3.java`, `LinkProposalStore.java`, contrat de vérification READ minimal ; aucun module engine neuf |
| HTTP | `supra-http-write/.../v3/V3LinkRequestController.java`, adaptation `V3AdmissionTransport.java` ; DTO fermés sans sérialisation de records persistence/domain |
| SQL adapter | `infra-persistence-primary-jpa/.../v3/JdbcLinkProposalStore.java`, chemin Link dédié de `JdbcAdmissionStore.java` ou adapter dédié utilisant la même source/owner ; `AdmissionSqlAuthority.java` |
| Migration | **Une** migration additive proposée `db/v3-admission/migration/V9__link_proposals.sql`, numéro à revérifier au GO. Table, checks/indexes/immutabilité/liaison différée ; aucune modification V6/V7/V8, aucune migration READ par défaut |
| Grants/runtime | `scripts/v3/grant-l5-admission.sql`, `V3AdmissionHttpConfiguration.java`, préflights/allowlists des racines V3 affectés. Pas d'ouverture Link brute dans `IdentityAdmissionInput.decode` |
| READ | A-READ retenu : adaptation typée du contrôle indicatif de propre Binding existant, sans fuite du token dans réponse ni accès à l'autorité WRITE. Si compteur exigé : `PotVisibilityStore`, adapter JDBC, contrat READ spécialisé et façade typée ; delta partagé à redéclarer |
| Tests | Classes et méthodes futures §11 ; adaptations ciblées B221, `linkIsNotPublic`, `linkAndInvitationNotExposed`, tests de transport/allowlists et fixtures de composition |
| Bruno/outillage | `scripts/bruno-v3/03 Link Requests/`, Coverage/README/environnements sans secrets ; recettes `l5-i3-fast`, `l5-i3-integration` dans `scripts/testing/verification-campaigns.json` après revue du collecteur |
| Normatif, mission distincte | Amendements SPEC-02/06/10, synchronisation plan global L5/Master/index, reprise de H1 approuvée et ratification distincte de H2…H5 ; préserver clôtures/manifestes/archives |

La migration CURRENT devra s'appliquer au V3 actuel V1…V8 par les loaders V3 réellement utilisés, puis V9 ; cette chaîne courte V3 n'est pas une baseline certifiée. La politique indique **Trusted baseline NONE** : ne pas inventer un snapshot de confiance. Pas de campagne des migrations historiques V2 V1…V23 par défaut ; si une commande canonique les entraîne effectivement, les compter et les rapporter, sans prétendre les avoir exclues.

## 10. Plan opérationnel séquencé et décisions humaines

| Étape | Sortie attendue / condition de passage |
|---|---|
| 0 — revue du présent cadrage | **H1 acquise : A-READ**, contrôle indicatif et autorité métier exclusivement à l'exécution. Soumettre le reste du plan, le protocole, le compte nullable et la rétention ; aucun code |
| 1 — ratification normative distincte | Publier les amendements restant à ratifier au §5 et synchroniser D3/T3/B3 du plan L5 ; reprendre H1 sans exception à la séparation admission/exécution et sans réécrire les clôtures. **Pas de GO implicite par approbation documentaire** |
| 2 — préparation des preuves A-READ | Figer les oracles de READ périmé à la création/admission puis rejet par L3 ; vérifier l'absence d'accès WEB à l'autorité WRITE et préserver les ACL I2. Aucun port d'autorité, pool ou rôle métier ajouté |
| 3 — préparation GO I3 | Revérifier HEAD/diff et hypothèses ; figer JWT issuer/audience/claims, identités E1/E2/E3 et banc réel disponible ; recenser méthodes attendues, commandes/closure, approbation plan puis **GO I3 explicite** |
| 4 — proposition + contraintes | Implémenter dans modules existants, migration additive/grants/préflight, création/get bornés ; FAST puis PG ciblé ; aucun Link admis automatiquement |
| 5 — acceptation/admission | Un seul owner, checks E2/heure/immutabilité, association et request ; tests concurrence/rollback/commit incertain avant ouverture HTTP |
| 6 — HTTP et assemblage | Routes, transport et sécurisation, refus Link brut, reprise E1 et binding E2 ; preuves ciblées WEB/Monolithe et cas hérités invalidés par nouveau chemin |
| 7 — parcours B3 réel | E1/E2 via Keycloak, B301…B314 ; observateur PG séparé pour cardinalités et contrôles de fautes hors API publique ; aucune fixture injectant directement le résultat nominal |
| 8 — preuve locale et revue | Compléter gates canoniques locales requises, rapport exact par invariant/SHA ; audit/revue, réserves ; aucun claim SPEC-10 global ni production |
| 9 — jalon Q5 | Full natif unique selon D6 + couverture Architecture effective et B5/fournisseur/variantes dus ; obligations reportées explicitement non acquittées jusque-là |

Décision acquise et décisions humaines restant à ratifier séparément :

- **H1 — APPROUVÉE : A-READ.** Autorité métier exclusivement au traitement Identity WRITE ; contrôle READ de création indicatif, obsolescence acceptée. Aucun accès synchrone WEB à l'autorité WRITE, verrou métier de création, port d'autorité supplémentaire ou exception admission/exécution. **Ancien blocage H1 levé**, aucun complément de vérification WRITE à fournir.
- **H2 — bloquante normative :** ratifier le remplacement de l'attestation externe par POST E2 et la proposition durable technique, les deux provenances, la consultation technique de proposition sur le stockage primaire (sans accès au domaine métier WRITE), le compteur nullable et l'idempotence naturelle d'acceptation. Pas de ratification supposée par ce plan.
- **H3 — contrat :** approuver routes, 201/202/404/410, durée initiale 10 min, égalité des UUID, aucune purge V3 et compte null initialement. Si compte réel exigé, approuver son delta READ/budget avant code.
- **H4 — environnement :** désigner banc Keycloak réel et configuration de confiance/audience/claims, deux comptes distincts et E3, injections de secrets hors Git ; owner de qualification externe. Ni valeur d'issuer ni disponibilité du banc inventées.
- **H5 — exécution :** approuver le plan, la sélection et les budgets, puis donner un GO I3 séparé. Report Architecture Q5 déjà autorisé dans clôture I2, mais déclencheurs nouveaux doivent y être tracés ; aucune reconduction automatique d'un budget échoué.

## 11. Tests courts, discriminants et budgets — trois niveaux maximum

**Plan A-READ retenu conformément à H1 approuvée.** Les preuves distinguent contrôle READ indicatif, obsolescence acceptée et décision métier au seul traitement WRITE. Aucune preuve d'activité WRITE à la création n'est exigée. Les noms suivants sont **proposés et absents du dépôt aujourd'hui**. Chaque méthode désigne un scénario JUnit non paramétré pour rendre les comptes explicites ; le collecteur vérifiera les identités réelles, pas seulement les totaux. Aucun `PASS` n'est annoncé ici.

### FAST — 10 scénarios futurs + 3 existants = 13

Classe future `V3LinkConsentContractTest` dans `engine-admit-registration` :

| ID / méthode future | Oracle |
|---|---|
| F01 `creationBindsAuthenticatedInitiator` | E1 du principal, token requis, aucun UserId/requester client |
| F02 `creationChecksBindingReadIndicatively` | contrôle typé du propre Binding READ : incohérence détectable refusée sans accès WRITE ; projection active périmée non traitée comme autorisation métier ; replay S-03 existant conservé |
| F03 `acceptanceUsesExactTargetPrincipal` | même sub autre issuer, E1/E3, différences de casse/Unicode ne correspondent pas |
| F04 `acceptanceHasNoClientSelectedIdentity` | corps cible/token/consent/admission_context rejeté |
| F05 `immutableTwoActorProvenance` | E1 conservée, E2 accepteur, token initial ; expiration JWT E1 ultérieure ne transforme pas l'initiateur |
| F06 `expiryAndAdmittedReplayOrdering` | limite exacte, replay admis après expiration autorisé ; non admis expiré refusé |
| F07 `proposalCanonicalIntentAndScope` | équivalences JSON, nouvelle cible/token sous même clé 409, scopes E1 séparées |
| F08 `historicalAdmissionCompatibility` | aucun changement aux octets/constructeurs V1, contrat Link séparé ; ancienne clé/intention originale retrouve id |
| F09 `publicViewsExcludeSecrets` | seulement allowlist, token E2/JWT/UserId jamais sortis |
| F10 `httpIntentsCannotAccessIdentityWriteAuthority` | dépendances locales de création/admission limitées aux ports READ et techniques de proposition/admission, aucun port d'autorité métier WRITE ni moteur d'exécution |

Réexécuter les **3 méthodes existantes** de `ExecuteIdentityRequestTest` : `l3_participant_order`, `l3_technical_not_rejected`, `l3_rejection_terminal`, car la nouvelle provenance ne doit pas changer le participant.

Commande native envisagée **depuis `app/`**, après création des tests :

```sh
./mvnw -pl engine-admit-registration,engine-consume-registration -am test '-Dtest=V3LinkConsentContractTest,ExecuteIdentityRequestTest' -Dsurefire.failIfNoSpecifiedTests=false
```

Recette future revue depuis racine : `python3 -B scripts/testing/verify.py FAST l5-i3-fast --dry-run`, puis même commande sans `--dry-run`. Aucune recette I3 n'existe actuellement. Objectif **30–90 s**, point d'arrêt/diagnostic à **2 min** si absence de progression ; pas de retry inchangé. PASS : 13 identités fraîches, zéro manquante/error/failure/skip ; n'est pas une gate canonique runtime.

### INTEGRATION — 16 scénarios futurs + 6 régressions existantes = 22 ciblés

Classe future `V3LinkAdmissionPostgresTest` dans `infra-persistence-primary-jpa` :

| ID / méthode future | Oracle PG indépendant |
|---|---|
| P01 `proposalCreateReplayAndOwnership` | création retrouvée, cible immuable, zéro IdentityRequest/slot avant acceptation |
| P02 `proposalAndAdmissionIntegrity` | corruption token/cible/requester/corrélation, updates/deletes et acceptation sans demande refusés à commit ; nouvelle demande HTTP Link sans proposition refusée |
| P03 `expiryAfterLockWait` | accept attend verrou puis échéance passée ; zéro admission ; inclure expiration JWT pendant attente |
| P04 `simultaneousAcceptance` | deux connexions/barrière, même id, 1 association + 1 request, puis au plus 1 transition |
| P05 `replayAfterTerminalAndUnlink` | retry avant/après terminal puis Unlink E2 = même id, jamais deuxième Link/token |
| P06 `distinctProposalsSameTarget` | deux propositions admises, une seule mutation cible, seconde rejetée |
| P07 `rollbackAtEveryAdmissionBoundary` | injection réserve/insert/marque/commit : zéro demi-acceptation et aucun slot manuel |
| P08 `unknownCommitAndObserverUnavailable` | commit perdu puis lecture indépendante/retry, aucun remplacement ; observer indisponible garde permis, issue non inventée |
| P09 `effectiveAclAndSecretChannels` | ACL colonnes, PUBLIC/indirect, SQL logs/app logs/DTO ; pas d'accès token d'autorité par admission ordinaire |
| P10 `initiatorBindingLostBeforeExecution` | READ volontairement périmé à la création/admission après perte d'autorité E1, puis variantes de perte après création et après admission ; proposition/admission possibles, Link REJECTED par L3, zéro effet E2. Un re-Link ne réhabilite pas le token initial ; pas de renouvellement JWT E1 imposé |
| P11 `targetBoundBeforeExecution` | E2 Registration/Link gagnant ; rejet tardif sans token/état partiel |
| P12 `proposalCanonicalHistoryAndRestore` | ancienne intention/clé, JSON équivalent, scopes, restauration proposition+association/demande, ancienne clé comparaison ; pas de nouvelle demande |

Classe future `V3LinkRequestHttpTest` dans `runtime-web-api` : H01 `jwtValidationAndTargetMasking` ; H02 `missingExpiredAndForbiddenProposal` ; H03 `ownBindingAndResultOwnership` ; H04 `boundedTransportAndNoSynchronousLink`. H04 vérifie aussi que création/admission n'accèdent pas à l'autorité métier WRITE et ne prennent pas de verrou métier, sans confondre ceux-ci avec les verrous techniques de proposition/admission. Chaque méthode couvre les entrées indiquées, avec JWT signés/falsifiés réels du banc, sans remplacer le contrôle cryptographique par un mock d'AuthN.

Régressions exactes existantes, à exécuter dans le même feedback ciblé :

- `V3AdmissionPostgresTest#associationForeignKeysAndImmutability+admissionRoleAndIndirectPrivileges` (2) : nouvelle table/grants ne régressent pas V8.
- `IdentityRequestExecutionPostgresTest#l3_atomic_link_unlink+l3_unknown_commit` (2) : unité L3 et recovery.
- `V3HttpCompositionPostgresTest#webRoot` et `V3MonolithHttpCompositionPostgresTest#monolithRoot` (2) : racines réelles après assemblage modifié.

Sélection native envisagée (une invocation série, depuis `app/`) :

```sh
./mvnw -pl runtime-web-api,runtime-monolith -am test '-Dtest=V3LinkAdmissionPostgresTest,V3LinkRequestHttpTest,V3AdmissionPostgresTest#associationForeignKeysAndImmutability+admissionRoleAndIndirectPrivileges,IdentityRequestExecutionPostgresTest#l3_atomic_link_unlink+l3_unknown_commit,V3HttpCompositionPostgresTest#webRoot,V3MonolithHttpCompositionPostgresTest#monolithRoot' -Dsurefire.failIfNoSpecifiedTests=false
```

Le monolithe est ici une ancre de **compilation/assemblage du test** avec filtre positif ; cela n'ordonne aucun test de tous ses modules amont. Recette future : `python3 -B scripts/testing/verify.py INTEGRATION l5-i3-integration --dry-run`, puis exécution après revue. Le collecteur doit attribuer les 22 identités à leurs modules exacts ; zéro source manquante tolérée malgré `failIfNoSpecifiedTests=false` dans les autres modules.

Budget ciblé **4–8 min**, seuil **10 min** de décision. Barrières explicites/connexions indépendantes, pas de sleeps comme preuve de course ; versions PG/configuration et traces physiques conservées. Dépassement : diagnostiquer progression et owner physique ; suspendre le lancement de la suite suivante, aucune extension ni répétition inchangée automatique. Une campagne arrêtée reste NOT RUN/INCONCLUSIVE/FAIL selon ses observations, jamais PASS. Ne pas tuer une transaction puis déclarer ses ressources libres sans observation.

**Gate canonique locale, même niveau INTEGRATION, distincte du feedback 22 tests :** si l'incrément est livré/clos séparément, la politique conserve les anchors WEB + COMMAND, en une invocation composée pour ne tester les dépendances communes qu'une fois :

```sh
./mvnw -pl runtime-web-api,runtime-command-consumption-worker -am test
```

Ce gate est **non filtré** ; son inventaire et son coût ne sont pas les 22 scénarios ci-dessus. Estimation de planification **10–20 min, incertitude haute**, sans mesure au HEAD inspecté ; revue préalable du modèle effectif et de l'inventaire par le collecteur. Au seuil **20 min**, point de décision avant autre campagne, sans suppression d'oracles ni arrêt aveugle d'un run qui progresse. Si le coût prévisible dépasse l'enveloppe approuvée, revenir avec l'inventaire et un report explicite à ratifier ; **ne pas le remplacer silencieusement par le feedback filtré**. Si ce gate n'est pas exécuté, rapport de checkpoint local seulement, fermeture canonique NOT RUN. Pas d'Architecture ajouté, pas de `-amd`, pas de full. Des tests inchangés peuvent être entraînés par ces slices ; ce n'est pas le graphe amont d'Architecture.

### BRUNO — B3 ciblé, 14 scénarios, fournisseur réel

Dossier futur `03 Link Requests`, réutilisant CLI **4.3.0** et helpers de polling/reçus I1/I2. E1/E2/E3 authentifiées dans des sessions distinctes ; pas d'EUI tirée d'email ou choisie au POST accept.

| Scénario | Parcours et oracle |
|---|---|
| B301 | Nominal complet autonome : Registration E1 si nécessaire → binding E1 → create → GET E2 → accept E2 → résultat CHANGED E1 → binding propre E2 actif ; Worker/projection réels, pas d'injection de résultat |
| B302 | E2 connexion indépendante, contrat JWT réel (issuer/audience/claims/expiration) ; réponse GET contient récapitulatif E1/E2/action/échéance, compteur inconnu explicitement représenté ; aucune interface web à tester en I3 |
| B303 | E3 tente GET/accept : même 404 que demande inconnue, zéro admission |
| B304 | E1 tente accept à la place de E2 : 404, proposition non consommée |
| B305 | JWT E2 absent/invalide/falsifié, mauvais issuer, mauvaise audience, expiré : 401. JWT valide d'un autre issuer accepté par un banc multi-issuer éventuel mais non cible : 404, pas de comparaison sub seul |
| B306 | Proposition expirée : 410 aux parties, aucun requestId admis ; attente contrôlée du banc ou date du jeu de test, aucune route publique de changement d'heure |
| B307 | Rejouer accept avant/après terminal/échéance : mêmes ids ; inclure retry après timeout côté client ; aucun nouvel effet observé |
| B308 | Altérer cible/E1/token/expiration dans create/accept, champ interdit : 400 ou 409 sur même clé différente, aucune substitution |
| B309 | E1 tente sélection E2 sur `/me/binding` et résultat/accept pour obtenir token E2 : refus/DTO sans token tiers |
| B310 | E2 tente résultat E1 : 404 ; E1 retrouve résultat même après perte Binding ; E2 découvre son seul token dans sa session |
| B311 | E1 perd Binding avant exécution : variantes READ encore actif à création/admission et perte entre accept/exécution ; proposition/admission possibles, REJECTED terminal par L3 et zéro effet E2 |
| B312 | E2 devient liée ailleurs avant exécution : REJECTED terminal, binding gagnant préservé |
| B313 | Id inconnu, non autorisé, S-03 create identique/JSON équivalent/différent : codes et absence de nouveaux durables |
| B314 | Deux acceptations en parallèle par deux clients du banc + commit/réponse perdue : même id, zéro doublon ; contrôle PG indépendant. Oracle profond de commit incertain reste P08 |

Commande **envisagée**, depuis `scripts/bruno-v3/`, après livraison du dossier et revue du banc :

```sh
node_modules/.bin/bru run -r '03 Link Requests' --env Qualification --sandbox developer --noproxy --reporter-json /absolute/new/bruno.json --reporter-skip-all-headers --reporter-skip-body
```

Chemin absolu de sortie à fournir, nouveau à chaque campagne ; variables JWT/tokens uniquement hors Git et hors argv. Inclure `-r` (la CLI peut sortir 0 avec zéro requête sinon). Les variantes B305 sont des requêtes négatives au sein d'un scénario nommé, pas un compteur inventé d'invocations JUnit ; nombre de requêtes réelles collecté séparément. B314 nécessite un helper de banc pour les appels simultanés/timeout contrôlé : **deux clics séquentiels Bruno ne prouvent pas une course**. Aucun endpoint de contrôle métier ajouté pour les fautes.

Budget **5–10 min**, seuil de décision **12 min**, cible globale feedback FAST+PG+B3 **≤ 20 min hors préparation et gate canonique** ; ce sont des estimations, pas des mesures ni plafonds normatifs. Polling borné, par exemple 20 s par résultat avec backoff 1/2/4 s et appels ≤5 s ; dépassement = échec/inconclusif, pas boucle infinie. IdP réel indisponible/claim requis manquant : arrêter B3, `EXTERNAL AUTHN NOT QUALIFIED` conservé ; ne pas transformer des JWT locaux en preuve fournisseur réelle.

### Réutilisation, exclusions et Q5

Les suites I1 (40/63 contributions et B1 32 scénarios), I2 (85 identités/B2 24 scénarios) et post-audit V8 (9 tests) sont des **preuves historiques attribuées à leurs SHAs**, pas des campagnes à rejouer intégralement ni un certificat au SHA futur. Avant réemploi, vérifier archives/hashes accessibles et comparer code, SQL, configuration, clés/formats, dépendances, oracles et environnement réels (politique §34). Un changement de provenance, ACL ou transport invalide précisément les preuves de ce chemin ; réexécuter leurs méthodes affectées, pas tous les tests des modules dont Architecture dépend.

Garder notamment les réserves I1 R-I1-01…04, I2 R-I2-01 et R-CLO-I2-01…04, L1–L4 et lease180 ; l'exécution terminale I3 devra être fraîche après sa migration et ne pas s'abriter derrière l'ancien B2 non frais après V8. Aucun PASS global L2/L3/READ/Architecture dérivé des seuls 13+22+14 scénarios.

**Q5 reste dû :** full natif unique et preuve de couverture effective G5/Architecture selon D6 ; B5, variantes/fournisseur réel et preuves externes indépendantes. Tout test structural global nouvellement exigé par §17 porte le statut reporté explicite, jamais NOT REQUIRED par commodité. Aucun full ni Architecture global n'est lancé pendant le présent cadrage, ni programmé automatiquement dans I3. Une modification large future déclenchant §19 exige une nouvelle décision de périmètre avant poursuite.

## 12. Reçu documentaire de la présente mission

Livrable unique : ce plan, comprenant décisions proposées, inventaire, écarts/amendements non appliqués, protocole, comparaison A/B, architecture recommandée, décision H1 approuvée et blocage correspondant levé, fichiers prévisionnels, matrice d'invariants, séquence et stratégie de preuve avec budgets.

Commandes réellement exécutées pour l'analyse : `pwd`, `git status --short`, `git branch --show-current`, `git rev-parse HEAD`, `git remote -v`, `git ls-remote origin refs/heads/feat/v3-architecture`, recherches `rg`/`rg --files`, lectures `cat`/`sed`/`head` et `wc -l`, vérification statique par `python3 -B`. Contrôles documentaires de livraison : contrôle des références locales et des identités existantes, revue du seul diff de ce fichier, `git diff --check`, inspection du staging et comparaison finale des chemins modifiés. Le contrôle statique initial a validé les huit liens locaux, les tableaux et neuf méthodes existantes citées ; les noms de tests futurs restent des propositions. Publication autorisée : ajout explicite de ce seul fichier, commit documentaire et `git push origin feat/v3-architecture` sans force, puis contrôle distant/état. Le distant a été vérifié avant publication au même HEAD initial `2a775a414637684d77485ff3fe1b9c5bf92e8f56`. Le reçu de conversation donnera le SHA effectif et le résultat du push ; aucun SHA autoréférent inventé ici.

Révision H1 : enregistrement de la seule décision humaine A-READ, retrait du blocage et de la variante WRITE à la création, correction des formulations ambiguës du plan et des oracles futurs. H2 à H5, les budgets et les commandes applicatives envisagées restent à approuver ; aucune SPEC, aucun code, migration ou historique de preuve modifié. Contrôles de cette révision : état/HEAD Git, lectures et recherches documentaires, comparaison des blocs de commandes et budgets avec le plan initial, vérification Python des liens/tableaux/statuts et `git diff --check`, puis inspection du seul fichier indexé. Publication du seul plan par commit/push normaux dans la continuité du mandat initial, sans GO d'implémentation.

Slices présentes : **NONE**. Franchissement inattendu présent : **NONE**. H1 maintient la séparation admission/exécution et interdit un accès WEB à l'autorité métier WRITE. Global gate présent : **NOT REQUIRED / NOT RUN**. Full reactor présent : **NOT REQUIRED / NOT RUN**, mission documentaire. DB : **NONE**. Qualification applicative/SPEC-10 : **NOT RUN**. Les commandes de tests du §11 sont exclusivement envisagées, aucune n'a été exécutée.

**READY FOR PLAN APPROVAL — H1 A-READ APPROUVÉE — H2 À H5 À RATIFIER SÉPARÉMENT — NO IMPLEMENTATION — NO GO I3.**
