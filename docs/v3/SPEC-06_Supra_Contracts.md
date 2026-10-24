# Pocoma SPEC-06 — Contrats des Supras

**Statut : contrats conceptuels consolidés, non implémentés ni certifiés en V3.**  
**Consolidation :** 2026-10-24. [Canon](V3_Canon_and_Roadmap.md).

## 1. Frontières des Supras

Les Supras sont des frontières API d'authentification, validation de forme, admission durable et lecture des projections, non une seconde autorité métier. Les lectures HTTP ne consultent jamais WRITE de manière synchrone ; l'admission ne lit pas le modèle métier WRITE pour deviner disponibilité, droits ou résultat final. Les décisions de domaine et d'exécution résident dans SPEC-01/02/05 ; les projections dans SPEC-04. Un refus pré-admission ne crée aucune demande ni résultat terminal.

## 2. Admission, provenance et idempotence

**Contrat d'admission validé ; les seuils chiffrés restent à qualifier.** Les Supras d'admission rejettent avant persistance les demandes qui ne respectent pas le **contrat autonome d'entrée**, sans chercher l'état métier courant dans WRITE. La persistance de l'admission et la gestion de l'idempotence utilisent leur propre autorité transactionnelle ; l'interdiction concerne la lecture du **domaine métier** WRITE pour décider de l'acceptation.

| Couche de validation | Contrôles admissibles immédiatement | Ce qui reste au Worker |
|---|---|---|
| Transport / désérialisation | taille maximale des octets reçus et décompressés, profondeur JSON, champs/types requis, nombres finis, format des identifiants, champs inconnus selon contrat | existence de Pot, Expense, Shareholder ou EUI en état courant |
| Forme de l'opération | type de commande reconnu, nombre maximum de cibles et de participants, longueur de textes, bornes numériques intrinsèques, cohérence interne entre champs d'un même payload | droits sur Pot, référence de version observée, compatibilité avec les versions actuelles et transitions métier |
| Identité / protocole | authentification réelle, audience, identité EUI, présence et forme du bindingToken, pour Link preuve externe d'accord transactionnel valide et non rejouée | lien EUI/bindingToken **courant**, permission effective et effets de Registration/Link/Unlink à l'exécution |
| Admission durable | anti-rejeu, corrélation, idempotence et création **unique** de la demande lorsqu'elle est valide | succès/rejet métier terminal et événements de complétion |

**Précisions :**
- Le domaine vérifie toujours ses invariants au Worker ; les contrôles anticipés sont un filtre UX/coût, **pas une nouvelle autorité métier**. Exemple : montant non négatif / poids positif peuvent être rejetés dès l'entrée mais doivent également être validés au domaine.
- Ne pas introduire de contrôle de « Pot connu » ou de « Shareholder autorisé actuellement » à l'admission sur base d'une projection READ potentiellement retardée.
- Un **refus préalable** ne crée ni demande, ni résultat terminal, ni slot, ni événement. Un rejet métier d'une demande admise reste un **résultat terminal durable** avec complétion.
- Éviter d'imposer la même limite de taille à tous les types de mutations : une Command multi-cibles et un Link n'ont pas les mêmes risques. Fixer un plafond global de requête **et**, si nécessaire, des limites propres à des collections/champs. Respecter les profils moyens et grands V3-5C sans imposer d'emblée des nombres injustifiés.
- Protéger contre l'amplification : limite de taille du corps **avant** parsing, limite décompressée si compression acceptée, nombre d'éléments et octets textuels, temps d'admission et nombre de requêtes en vol. Réponses d'erreur bornées sans divulguer l'existence d'un objet métier.
- Règles d'évolution : changer une limite ou ajouter un contrôle ne requalifie jamais une demande **déjà admise** ; la nouvelle règle s'applique aux nouvelles admissions, sans toucher Consumption ni les transitions WRITE.

**Cas ADM-01 à certifier :** (A) malformed/oversized → refus immédiat zéro durable ; (B) cas à la limite accepté et limite+1 refusé ; (C) payload formellement admissible mais objet absent ou conflit de version → demande admise puis rejet terminal ; (D) tentative d'usurper un bindingToken sans identité correspondante → refus de sécurité sans fuite ; (E) même requête concurrente/idempotence → une admission effective ; (F) règle ajoutée après l'admission sans modification du destin de cette demande ; (G) injection de payload coûteux et mesures CPU/mémoire/latence ; (H) Link sans attestation de consentement métier vérifiable → aucune admission.

**À décider ensuite :** limites numériques par type, conventions d'erreurs, politique pour champs inconnus, syntaxe des identifiants publics, corps compressés et traitement HTTP précis des répétitions. **Le principe et le périmètre sont définis ; la matrice de seuils n'est pas validée.** 

**DÉCISION :** une Command ou IdentityRequest soumise par un client possède une **clé d'idempotence fournie par le client**, liée à une portée d'identité authentifiée et à une **intention canonique** de la demande. L'admission réserve durablement cette association **atomiquement avec la création de la demande**, sans consultation de l'état métier courant WRITE.

- **Première soumission** : contrôler le contrat d'admission et les preuves exigées (notamment consentement transactionnel pour Link), puis créer une unique demande durable et son association d'idempotence dans la même transaction physique.
- **Répétition, même clé et même intention dans la même portée** : retrouver la **même demande durable et son identifiant**, sans recréer de demande ni réévaluer rétroactivement de nouvelles limites structurelles. La relecture peut indiquer son statut actuel sans inventer de nouvel événement métier.
- **Même clé, intention différente dans la même portée** : **409 Conflict**, sans nouvelle admission ni fuite du contenu de l'intention antérieure.
- **Même clé dans une portée distincte** : ne pas permettre l'accès à la demande de l'autre identité ; définir la portée via l'identité authentifiée, l'opération et le client appelant, sans s'appuyer sur un simple bindingToken non authentifiant. Une clé seule n'est ni une capacité d'accès ni un identifiant métier global.
- **Concurrence** : deux requêtes simultanées ne peuvent créer deux demandes pour la même paire portée/clé. Contrainte durable d'unicité et transaction atomique, avec issue déterministe après commit connu, rollback ou réponse perdue.
- **Link** : une clé d'idempotence ne remplace **jamais** l'attestation externe de consentement de E2. Un retour de demande déjà admise n'autorise pas une nouvelle intention Link ; l'association doit rester liée à la preuve et à sa cible non substituable.
- **Canonicalisation** : comparer les intentions métier (type d'opération, cible, payload pertinent, contexte d'identité) de façon déterministe, sans dépendre d'ordre arbitraire JSON ni de champs purement techniques. Le format/digest précis reste un détail à spécifier avant implémentation.
- **Durée de rétention** : conserver l'association au moins pendant toute la durée où une répétition est supportée. **Ne pas annoncer une idempotence illimitée** sans politique de rétention ; durée contractuelle et purge éventuelle restent à fixer avant activation. Tant qu'une association est conservée, aucune nouvelle demande n'est créée avec cette paire portée/clé.
- **Admission invalide** : une requête refusée avant admission ne crée ni Command/IdentityRequest, ni association durable d'idempotence ; ne pas transformer une erreur HTTP préalable en résultat métier terminal.

**Tests S-03 :** première admission et réponse ; répétition avant et après terminalisation ; même clé/payload différent 409 ; JSON équivalent ; appelant/identité différente ; deux admissions concurrentes ; rollback de la première ; commit réussi mais réponse perdue ; règles de validation ajoutées entre deux appels ; Link avec attestation non substituable et anti-rejeu.

**Statut : S-03 RÉSOLU conceptuellement.** Le détail du format des clés, de leur durée contractuelle et des réponses HTTP nominales est à figer pendant la préparation des tests et de la réalisation. S-01/S-02/S-03 sont désormais arbitrés ; la revue de clôture de V3-5E peut reprendre.

L'EUI initiatrice authentifiée est capturée immuablement en `requesterEui` dès admission ; toute Command/IdentityRequest sauf Registration transporte un `bindingToken` explicite. La Supra ne crée pas de slot : Discovery asynchrone en SPEC-05. La revalidation du Binding et des droits courants demeure en WRITE lors de l'exécution.

## 3. Authentification et attestation

Toute lecture Pot ou Expense exige EUI authentifiée, bindingToken concordant et CurrentBinding actif en READ. Le token seul n'est jamais une authentification. La consultation de son propre CurrentBinding et celle de ses résultats terminalisés sont deux exceptions explicites à l'obligation d'un bindingToken entrant, pas à l'authentification. Link exige un accord actif, explicite et vérifiable de E2, lié à l'intention précise, audience, expiration et anti-rejeu ; le serveur d'autorisation peut être co-opéré avec l'IdP ou séparé. Pocoma ne fournit pas d'interface de consentement ; Keycloak et RAR RFC9396 ne sont pas des obligations de produit ou de protocole.

## 4. Lecture courante et historique du Pot

**Contrat normatif de lecture :** une fois la version terminale D matérialisée dans READ, `GET /pots/{potId}` retourne `404` pour le Pot supprimé ; la lecture explicite `Pot@D` retourne également `404`. Les versions V<D restent consultables par un demandeur authentifié et doté d'un Binding actif autorisé **à V** (créateur ou Shareholder lié à V), sans imposer des droits sur D ; `CurrentUserPotList` demeure strictement navigationnelle. Toute lecture des détails d'Expenses à V doit vérifier ces mêmes droits et l'appartenance des références au manifeste `PotVersion(P,V)`. Le contrat ne crée pas d'endpoint supplémentaire.

**Ordre observable des contrôles, après validation sûre de la requête :**
1. Authentifier l'EUI : défaut d'authentification => `401`. Vérifier le `bindingToken` requis pour les lectures de Pot et le `CurrentBinding` actif, sans exposer l'existence de la ressource ciblée. Le cas particulier de lecture de son propre résultat de demande (AUD-V3-010) demeure indépendant du Binding.
2. En lecture courante, lire `CurrentPot(P)` puis `PotVersion(P,W)` et établir les droits du demandeur **à W**. Pot absent de READ, version terminale ou droits non établis => `404` uniforme. En historique, lire `PotVersion(P,V)` et établir les droits **à V**, sans exiger ceux de W ; version absente, terminale ou non autorisée => `404`.
3. **Seulement après autorisation établie**, contrôler `minVersion` pour une lecture courante puis la disponibilité des `SubobjectVersion` et `PotBalance` attendues pour la version active effectivement servie. Version pas encore atteinte ou dépendance incomplète => `503` ; sinon `200`. Aucun `503` lié à une version, un solde ou un sous-objet d'un Pot non autorisé.

READ n'interroge jamais WRITE synchroniquement. Un Pot créé en WRITE mais encore absent de READ retourne `404` faute de preuve READ d'autorisation ; ce cas peut donc être temporaire, et le polling après admission peut rencontrer des `404`. L'état READ asynchrone peut temporairement refléter d'anciens droits, dans les limites SEC-01/02 déjà admises. Une indisponibilité globale de READ, indépendante du potId, peut retourner `503` ; l'enveloppe d'erreur ne distingue ni absence ni refus d'accès. Les contrôles de disponibilité ne doivent pas créer d'oracle d'existence via `minVersion` extrême, dépendance manquante, corps d'erreur ou cache.

L'absence normale de `PotBalance(P,D)` pour la version terminale n'est jamais une indisponibilité de projection. Voir SPEC-04 — §10, SPEC-09 — §5, SPEC-10 — A11-A à A11-N. Cet arbitrage supplante l'ancien S-02 évoquant une autorisation courante supplémentaire pour les lectures historiques.

## 5. Expenses et manifeste complet

**DÉCISION :** la réponse principale d'un Pot à V contient l'en-tête, la totalité des Shareholders et de leurs soldes exacts à V, **et la liste complète ordonnée des références versionnées de toutes les Expenses appartenant à `PotVersion(P,V)`**. Aucun second mécanisme de pagination des références en V3. Le front établit ses pages d'affichage à partir de cette liste et charge les détails par lots bornés via le Supra (§11).

**Complétude et sécurité :** une réponse principale ne peut être tronquée silencieusement ; si le contrat de sécurité empêche réellement une réponse complète, erreur explicite, pas de `200` partiel. `PotVersion` n'expose **pas** ses `pocomaUserId` internes d'autorisation : seul le manifeste des références d'Expenses destiné au client est transmis, après AuthN et vérification de l'accès à V. Les références ne sont jamais des capacités d'accès.

**Bornage :** mesurer le volume encodé de la réponse pour le Pot grand profil (100 Shareholders, 2 000 Expenses), avec identifiants et versions locales réels, puis fixer un plafond **compatible avec les tailles fonctionnelles supportées**. Le candidat 1 Mio de §13 concerne les réponses de **détails groupés**, pas une limite adoptée sur le manifeste principal. Si des Pots légitimes dépassent le plafond, reconsidérer explicitement la forme de l'API ; ne pas déclarer ces Pots interdits par effet secondaire.

**Cohérence :** toutes les références appartiennent à V ; les détails demandés ultérieurement utilisent exactement ces versions immuables, même si `CurrentPot` a avancé. L'UI contrôle le rafraîchissement de la version affichée.

**Statut : S-02 RÉSOLU conceptuellement ; taille réelle et paramètres de protection à qualifier.** S-03 (idempotence d'admission) est défini au §2.

**Contrat validé :** une lecture groupée demande `potId`, `potVersion=V` et une **liste ordonnée et bornée de références d'Expenses** issues du manifeste `PotVersion(P,V)`. Chaque référence indique l'identité métier Expense et les versions locales nécessaires (`Header/Details/Financials`), ou un identifiant opaque équivalent résolu strictement dans le manifeste. Les détails sont servis exclusivement depuis `SubobjectVersion` ; le Supra ne contacte pas WRITE.

**Bornage symétrique :**
- Refuser avant requête coûteuse une liste vide, des doublons si non normalisés, des références malformées, des volumes excessifs et des combinaisons `potId/V` incohérentes. **Plafond serveur configurable**, avec maximum contractuel publié, indépendant du nombre de références du manifeste principal. Un plafond ne se négocie pas à la hausse par le client.
- Borne également de taille totale de réponse et de temps de traitement ; si un ensemble de références autorisé dépasse le budget, répondre par **erreur explicite**, jamais par troncature silencieuse d'Expenses ou sous-objets. Tester l'interaction avec un Expense à contenu volumineux.
- **Ordre du résultat** : restituer les items dans l'ordre demandé (ou documenter un autre ordre canonique), avec identité/version et signal explicite pour chaque élément non résolu. Aucune réussite partielle trompeuse : soit le lot complet est servi, soit une erreur cohérente est fournie.

**Sécurité et cohérence :** EUI authentifiée, binding actif contrôlé dans `CurrentBinding`, utilisateur autorisé à `PotVersion(P,V)` ; chaque référence fournie doit appartenir au **manifeste de cette version exacte**. Une liste de références n'est jamais une preuve d'autorisation ; ne jamais accepter une référence d'un autre Pot ou d'une autre version même si sa valeur existe. Toute sous-version nécessaire à une Expense doit être présente ; sinon indisponibilité explicite, et aucune reconstruction à partir de `CurrentPot` plus récent.

**Contrat UX :** la lecture principale sert le manifeste complet des références d'Expenses, le front en découpe les groupes à la demande ; il peut continuer à V alors que le courant atteint V+1. Un nouvel état courant est signalable séparément ; aucun basculement implicite en cours de lot.

**Scénarios SUPRA-READ-01 proposés :** 0/1/N références ; juste sous/à/au-dessus du plafond ; référence dupliquée ; référence étrangère au Pot ou à V ; EUI non autorisée ; Unlink matérialisé dans CurrentBinding ; V ancien et CurrentPot avancé ; sous-version non encore matérialisée ; Expense avec payload extrême ; 50 Expenses chargées sans N+1 ; réponse non partielle et ordre reproductible.

**Point non arbitré :** taille effective du plafond, borne en octets, ordre du manifeste et représentation des références ; confronter aux profils V3-5C. Éviter d'introduire une pagination serveur *en plus* du découpage client sans besoin démontré.

**Transport et coût — AUD-V3-013 :** le manifeste des références d'Expenses reste complet, ordonné et versionné dans la réponse principale, sans pagination serveur de ces références. Les détails restent chargés par lots bornés. Une représentation compacte des références, une sérialisation efficace et la compression HTTP sont permises si elles préservent exactement les identités et versions locales, l'ordre, l'autorisation et la complétude. Aucun raccourci vers le seul `expenseId` ne peut perdre la résolution historique. Mesurer octets JSON non compressés et octets réellement transférés, coûts de sérialisation/lecture et fréquence des consultations du Grand Pot. Un plafond de ressources compatible avec les Pots légitimes doit être qualifié ; jamais de troncature ni de `200` partiel. L'optimisation du transport ne change pas le contrat S-02.

## 6. Navigation CurrentUserPotList

**DÉCISION :** ordre total stable par `potId` croissant, pagination keyset avec curseur opaque représentant au minimum le dernier `potId` servi ; aucune session, aucun snapshot inter-pages, aucun offset. À chaque requête, résoudre l'utilisateur authentifié via `CurrentBinding` puis consulter les entrées `CurrentUserPotList` actuellement visibles de cet utilisateur, avec `potId > lastPotId`, `ORDER BY potId` et limite serveur. Revalider l'identité à chaque page ; la liste est une navigation, **non une autorité d'AuthZ Pot**.

**Sémantique des évolutions :** un Pot nouvellement visible avec un ID inférieur ou égal au curseur ne sera pas vu dans ce parcours ; un nouvel ID supérieur peut apparaître ; un Pot devenu invisible disparaît des pages suivantes. Aucun doublon causé par le déplacement d'un Pot, puisque `potId` ne change pas. Pour retrouver l'état de navigation récent, le client recommence depuis le début. Ne pas promettre un inventaire complet à un instant donné.

**Curseur :** opaque et non assimilable à une capacité de lecture. Sa forme concrète (encodage, signature éventuelle, paramètres de filtre/page et erreur si altéré) relève du contrat Supra de réalisation ; son interprétation ne doit jamais permettre d'élargir l'identité autorisée ou la limite côté serveur. Le serveur peut récupérer `limit+1` entrées pour déterminer `nextCursor` sans COUNT global.

**Preuves S-01 :** pages successives sans duplication à liste fixe ; Pot ajouté avant/après curseur ; Pot devenant invisible ; cursor altéré/non valide ; demande au-delà du plafond ; changement de binding ; profil utilisateur avec beaucoup de Pots et index compatible. Vérifier l'absence de fuite inter-utilisateurs et l'absence de scan de toutes les entrées.

**Statut : S-01 résolu conceptuellement ; dimensionnement et preuves encore à réaliser.**

## 7. Consultation de son CurrentBinding

La Supra prend uniquement l'EUI issue de l'authentification, sans paramètre de sélection d'une EUI tierce ni bindingToken entrant. La réponse contient euiVersion, état actif et **son seul** bindingToken si actif, jamais de PocomaUserId. Après Link, E2 doit s'authentifier elle-même pour recevoir son token ; E1 ne peut le récupérer depuis sa propre session. Une projection en retard peut justifier `minVersion` et indisponibilité temporaire seulement lorsque l'appelant est autorisé. Pas de cache partagé ni de journalisation de tokens.

## 8. Résultats de demandes et polling

**DÉCISION NORMATIVE, prioritaire sur le « À préciser » du §17 et les propositions HTTP antérieures.** Pour `GET /commands/{requestId}/result` et la consultation équivalente d'`IdentityRequestResult`, la Supra READ exige une EUI authentifiée et consulte exclusivement la projection READ terminale. Elle ne demande aucun `bindingToken` et ne résout ni Binding courant ni PocomaUser. Le `requesterEui` immuable est présent dans chaque projection de résultat, recopié par la Consumption depuis la demande durable WRITE à l'occasion de la matérialisation ; aucune lecture WRITE synchrone à l'appel HTTP.

| Situation | HTTP | Contrat |
|---|---|---|
| Non authentifié | `401` | Aucun renseignement sur la demande |
| Résultat terminal READ présent et `requesterEui` identique à l'EUI authentifiée | `200 OK` | Résultat terminal unique, succès ou rejet métier |
| Résultat absent de READ, quelle qu'en soit la cause (PENDING WRITE, complétion non projetée, inconnu) | `404 Not Found` | Pas d'oracle d'existence |
| Résultat READ présent mais appartenant à une autre EUI | **même `404`** | Même enveloppe publique ; aucune fuite d'existence |
| Service READ réellement indisponible | `503 Service Unavailable` | Incident technique, non substitut à l'absence d'une ligne |

Après le `202 Accepted` d'admission, le client conserve le `requestId` et poursuit le polling malgré les `404` tant qu'il attend le résultat, selon une politique cliente bornée de délai/backoff. `404` ne certifie ni inexistence ni échec métier et n'est pas assimilé à `503`. `200` avec `REJECTED` est un résultat terminal valide, non un rejet HTTP d'admission. Un `requestId` d'autrui n'ouvre jamais accès, même si connu. La propriété demeure celle de l'EUI initiatrice après Unlink/re-Link ; l'EUI reste tenue de s'authentifier. Les résultats ne comportent pas d'expiration fonctionnelle V3 : aucune nouvelle purge ni statut `EXPIRED`. La rétention opérationnelle éventuelle nécessite une décision distincte, cohérente avec idempotence et reconstruction. Voir V3-2/V3-3 et SPEC-10 — A10-A à A10-K.

## 9. Autorisation des ressources et masquage

**Principe validé : 404 par défaut pour toute ressource métier dont l'existence ne doit pas être divulguée.** Une ressource absente et une ressource existante mais non autorisée répondent de manière indiscernable pour le client non autorisé, sans exposer la raison interne. Inclut Pot, Expense et références de sous-objets non autorisées ; la possession d'une référence versionnée ne vaut jamais droit d'accès.

**403 reste possible uniquement pour une interdiction indépendante de l'existence d'une ressource particulière**, par exemple un privilège fonctionnel global connu du client authentifié. Aucun cas 403 concret n'est imposé en V3 tant qu'il n'est pas identifié. **401** demeure réservé à l'absence/invalidité d'authentification. Une Command formellement valide peut être admise sans lecture métier WRITE puis rejetée par le Worker : ne pas convertir cette décision ultérieure en 403 d'admission.

**Preuves SUPRA-READ-01/SEC-01 à ajouter :** ressource absente vs interdite sans distinction observable pertinente (statut, corps, détails et comportement contrôlable), refus de références étrangères sans fuite, réponses sensibles non mises en cache partagé, et cas 403 éventuels prouvant l'indépendance de la ressource. Le cache et les timings sont à qualifier de façon réaliste, sans exiger une constante cryptographique de temps pour chaque lecture SQL.

**Statut : politique conceptuellement validée ; les éventuels cas `403` et conventions d'enveloppe doivent respecter SPEC-06 et seront qualifiés avant certification. Les choix `401/404/503` ne sont pas rouverts.**

**SEC-01 :** autoriser la lecture du Pot et des références Expenses sur la base du créateur immuable ou des Shareholders à la version demandée V, après résolution du Binding actif actuel et de l'EUI authentifiée. Une version ultérieure ne retire pas le droit historique, et la liste de navigation n'accorde aucun droit. Authentifier et autoriser **avant** tout contrôle de complétude ou minVersion susceptible de révéler la ressource. La lecture des résultats suit exclusivement requesterEui, distinctement de SEC-01.

## 10. DeletePot et ordre des contrôles

Pour DeletePot(P) à la version terminale D, la lecture courante et la lecture historique explicite à D retournent 404 dès matérialisation dans READ ; le passé V<D reste autorisable selon SEC-01. Le jalon C-03 de suppression est une observation interne READ de CurrentPot→PotVersion(P,D).deleted=true, sans attendre Balance(D), visibilité ni utiliser la seule réponse HTTP 404 comme oracle. Voir SPEC-04 et SPEC-09/10.

## 11. Protection des ressources

**Décision de cadrage :** distinguer explicitement deux catégories de bornes, sans imposer une limite métier simplement parce qu'une charge d'essai comporte 100 Shareholders ou 2 000 Expenses.

| Catégorie | Où et pourquoi | Exemples | Politique d'évolution |
|---|---|---|---|
| **Sécurité et protection de ressources** | Supra / infrastructure : limiter coût de parsing, taille d'entrée/sortie, cardinalité d'un lot, concurrence et durée d'une requête | Corps HTTP, tailles de texte transportées, 50 références groupées comme valeur **candidate**, budget de réponse, limite d'in-flight | Configurable côté serveur, documentée et testée ; ne doit jamais tronquer silencieusement ni invalider une demande déjà admise |
| **Fonctionnelle / invariant de domaine** | Domaine : déterminer ce qu'une commande métier peut signifier et quelles transformations sont admissibles | Montant non négatif, poids strictement positif, règles de cibles et cohérence de mutation | Identique au niveau du Worker, éventuellement prévalidée à l'admission quand autonome et indépendante de WRITE |

**Commands multi-cibles :** ne pas instituer de maximum **métier** de cibles à 100 sans besoin démontré. Une limite technique de cardinalité ou d'octets peut être nécessaire pour une admission bornée, mais doit être déterminée à partir de la taille réellement sérialisée et du coût de traitement des opérations admises, compatible avec le grand Pot. La valeur « 100 cibles » de §13 est **une hypothèse de test, non une borne validée**. En cas de dépassement d'un plafond de transport, le client doit pouvoir soumettre une opération métier adaptée si celle-ci est effectivement sécable **sans changer sa sémantique atomique** ; ne jamais promettre une division automatique d'une Command atomique multi-cibles.

**READ :** le fait de servir intégralement les Shareholders et soldes, et de transmettre la liste des références des Expenses dans la lecture principale, représente un **contrat fonctionnel de complétude**. Les limites de transport ne peuvent produire un Pot partiel présenté comme complet. Les chargements détaillés sont bornés par lots ; si une réponse principale devient trop grosse, le problème doit être traité comme un **arbitrage de contrat** (format ou découpage) et non masqué par une troncature.

**Refus et résultats :** dépassement de limite de transport/cardinalité = refus à l'admission sans demande durable ; invariant métier incohérent à l'exécution = rejet terminal durable. Les validations intrinsèques du domaine (montants/poids) peuvent être prévalidées sans affaiblir leur enforcement au Worker.

**Tests discriminants :** grande Command légitime au-dessus de 100 cibles (ne pas la déclarer métier invalide par défaut) ; Command atomique non sécable sous plafond ; corps volumineux invalidé avant parsing ; Pot 100/2 000 complet malgré limites de lot ; limites configurées à la baisse pour une nouvelle admission sans effet sur les demandes déjà persistées ; absence de requête WRITE métier à l'admission.

**Statut : séparation conceptuelle VALIDÉE ; les seuils chiffrés des protections demeurent à calibrer avant qualification, sans ajout de règle métier arbitraire.** Les preuves V3 restent à exécuter.

Contrôles transport/configuration : octets bruts et décompressés, profondeur JSON, cardinalités des lots, budget de réponse et timeouts ; aucune troncature silencieuse de la réponse fonctionnellement complète. Les valeurs candidates de volume ou cardinalité des premières études ne sont **pas** des plafonds métier validés. Les limites concrètes doivent être qualifiées et publiées avant exécution.

## 12. Garanties, paramètres et qualification

Garanties à certifier : admissions atomiques et idempotentes (ADM-01, S-03), navigation non autorisante et keyset (S-01), manifeste complet et lots bornés (S-02), droits historiques et masquage (SEC-01), consentement externe Link (ID-02, C-01), résultats A10-A à A10-K et suppression A11-A à A11-N. Les valeurs opérationnelles de plafonds, budgets et transport, la qualification du serveur d'autorisation et le dimensionnement grand Pot restent à éprouver ; aucune certification V3 n'est revendiquée. Voir [SPEC-10](SPEC-10_Test_Certification.md).

## Amendement contrats Invitation

L'admission de `CreateInvitation` retourne `202` et `requestId`, jamais le lien. WRITE décide si l'appelant est le créateur et exécute la mutation. `CommandResult` indique seulement succès/rejet. La consultation READ de `CurrentInvitation` par `creationRequestId` restitue le lien exclusivement à l'EUI créatrice authentifiée ; un identifiant absent ou appartenant à autrui répond indistinctement `404`. L'invité authentifié présentant le token peut consulter exclusivement les Shareholders libres du Pot actif, à condition de n'être déjà lié à aucun Shareholder actif de ce Pot. `CurrentBinding` doit être actif. Jamais de lecture synchrone WRITE ni accès au Pot complet avant rattachement. Le résultat READ n'est pas une réservation ; la commande WRITE d'auto-rattachement revalide tout. Protéger le lien contre cache partagé, logs, substitution de requête et lecture par une autre EUI.

