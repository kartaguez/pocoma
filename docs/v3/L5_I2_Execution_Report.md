# L5/I2 — Rapport d’exécution et de qualification

## Clôture humaine du 5 novembre 2026 — statut courant

**L5/I2 — CLOSED — LOCAL BOUNDED PASS WITH EXPLICIT RESERVATIONS.** L’[acte autonome](L5_I2_Acceptance_and_Closure_Decision.md) enregistre l’acceptation humaine après audits et correctif SQL. Les preuves initiales C-04 (2 291 PASS dont 170 Architecture, T2 85 identités, B2 24 scénarios/468 requêtes, onze reçus Pot) restent attribuées à la livraison `df0c3a221ce20b4eb569616d7df621fa4090a32b`. Le [correctif post-audit](L5_I2_Post_Audit_Correction_Report.md), publié à `1fc07000e586394298b2f2cf7acbd5e84ff1c5e6`, apporte **9/9 PASS PostgreSQL**, sans rejeu Architecture, terminal complet ou full reactor ; aucune preuve initiale n’est présentée comme fraîche au SHA corrigé.

**R-I2-01 conserve sa formulation historique ci-dessous** ; R-CLO-I2-01…04 dans l’acte portent compatibilité future V1/AUD-I2-01, preuve terminale non fraîche après V8, issue physique indéterminée à A=1 et qualification/environnement bornés. Les réserves L1–L4 et I1 restent visibles dans leurs actes. I3 à cadrer, I4 ultérieur et Q5 restent non clos ; L5 OPEN, aucune certification SPEC-10 globale ni homologation production. Le report humain Architecture I3/I4 à Q5 conserve toute obligation normative différée, sans exemption implicite.

Cette actualisation est **DOCUMENTATION ONLY — SLICES NONE** ; franchissement inattendu NONE ; Architecture et full reactor NOT REQUIRED / NOT RUN ; WEB/COMMAND/Monolithe NOT REQUIRED / NOT RUN ; PostgreSQL et Bruno NOT RUN. Contrôles Git/documentaires et commandes de publication consignés dans l’acte ; aucun changement ni test applicatif.

## Récit historique de livraison initiale — sections A–G conservées

Les verdicts techniques et mentions d’absence de clôture ci-dessous décrivent l’état initial avant correctif et décision humaine ; ils ne révoquent pas le statut courant ci-dessus. Les exécutions dites fraîches dans ce récit sont celles du snapshot initial uniquement.

Date : **5 novembre 2026**. Verdict technique : **IMPLEMENTED WITH RESERVATIONS**. Le mandat est le [GO humain du 4 novembre](L5_I2_Human_GO.md), exécuté sur le [plan V2.1](L5_I2_Operational_Implementation_and_Qualification_Plan_V2.md). **Aucune clôture humaine I2, certification SPEC-10 ou autorisation de production n’est prononcée.**

Les treize intentions publiques sont implémentées. Les **85 identités T2** sont toutes couvertes par des exécutions fraîches réussies dans la gate C-04, les **24 scénarios B2** passent avec **468 requêtes réellement comptées**, et la gate non filtrée passe **2291/2291, zéro failure/error/skip**, dont **170 oracles Architecture**. La même gate conserve les **onze reçus de sous-cas Pot**, sans rejeu supplémentaire du flux. Les FAIL/INCONCLUSIVE antérieurs restent intacts dans leurs archives.

## A. Git et périmètre déclaré

Départ attendu, HEAD réel et distant vérifié : `6a27abeade86c7b9a0f3f6430685daf9d819fc73`, branche `feat/v3-architecture`, worktree initial propre, aucun delta depuis le SHA approuvé. Le premier contrôle distant sandbox a échoué en DNS ; la lecture réseau autorisée a confirmé le même SHA avant modification. Les publications documentaires antérieures du plan ne sont pas réattribuées à ces nouvelles preuves.

Diff de livraison : **132 fichiers**, dont **46 sous app/** (production, contrats, tests, POM et migration), **53 documentaires** (dont les 34 archives de preuve) et **33 scripts/recettes/Bruno**. Aucune SPEC ni Reactor Verification Policy modifiée.

Qualification dans `/private/tmp/pocoma-l5-i2-qualification`, worktree détaché du même SHA, puis copie contrôlée des changements. Motif : JDT/VS Code réécrivait `target` dans le worktree principal, produisant des stubs « Unresolved compilation problems » puis des classes disparaissant pendant Maven. Aucun réglage de l’éditeur n’a été modifié. **Les 1409 inputs applicatifs du worktree de livraison correspondent octet pour octet à C-04** ; [inventaire et provenance](../testing/evidence/L5-I2/qualified-app-inputs.json). Le seul écart initial de checkout était `mvnw.cmd` LF/CRLF : aligné sur `.gitattributes`, blob Git normalisé inchangé, jamais exécuté sous Unix. Le SHA final et l’état distant après publication sont donnés par le reçu Git de livraison pour éviter une auto-référence de commit.

**Slices déclarées avant implémentation : WEB primaire, COMMAND secondaire, racine Monolithe complémentaire.** Impact interdit : lookup métier à l’admission, changement des algorithmes Consumption/Discovery/claims/leases, `PotTransitions`/`IdentityTransitions`, publication et producteurs READ. **Franchissement inattendu de slice de production : NONE ; complément test-only COMMAND pour le helper d’attente historique, déclaré au diagnostic avant correction.** Le défaut de scan historique du nouveau contrôleur a été corrigé dans le nouveau composant, sans réorganisation des runtimes.

**Architecture globale : REQUIRED / RUN / PASS (C-04). Full reactor natif : NOT RUN, non autorisé à I2, dû à Q5.** La fermeture de la gate couvre effectivement les 60 modules ; elle est globale. Elle n’est pas présentée comme la commande native `./mvnw test` de certification Q5. Les migrations historiques tirées transitivement par cette fermeture restent exécutées ; les protocoles I2 ciblés utilisent le CURRENT V3 WRITE/READ et V7, sans prototype SQL pré-GO ni certification de baseline.

## B. Fonctionnel livré

`POST /api/v3/commands` couvre CreatePot, UpdatePotDetails, DeletePot, AddShareholders, UpdateShareholderDetails, UpdateShareholderWeights, DeleteShareholder, CreateExpense, UpdateExpenseDetails, UpdateExpenseFinancials et DeleteExpense. `POST /api/v3/identity/requests` couvre Registration et Unlink, soi ou autre EUI du même User avec le seul token initiateur. L’EUI authentifiée est capturée immuablement ; aucun UserId/client attesté/attestation libre n’est accepté.

Association S-03 commune, HMAC séparés recherche/intention, version canonique 1, clé logique de recherche stable, clés de comparaison conservées, unicité durable et FK différées ; réservation et demande partagent la même transaction WRITE. Rejeu équivalent : même requestId/202 avant/après terminalisation ; différence comparable : 409. Rollback, concurrence SQL réelle, accusé de commit perdu, réponse client perdue et récupération même clé sont qualifiés par observations indépendantes. Aucun cache, état de coordination, trigger inverse, marqueur d’origine SQL, tentative durable, compensation ou retry non borné.

SELF reste durablement symbolique. L’acteur WRITE protégé résout SELF ; KEEP utilise les faits WRITE courants ; DETACH/FREE demandent la valeur vide. Les transitions existantes gardent leurs droits. Le constructeur concret historique et son codec restent lisibles ; l’enveloppe nouvelle version 2 fait échouer l’ancien consommateur plutôt que d’ignorer les modes. Mettre à niveau Command et Identity avant activation, notamment pour AUTHENTICATED_HTTP.

Les refus préalables ne créent aucun durable. Une intention valide de forme mais invalide métier est admise puis rejetée par WRITE avec résultat terminal. Les lecteurs I1/L4 sont réutilisés, sans fallback WRITE. Un résultat terminal et CurrentBinding/Pot/Balance progressent indépendamment ; le propriétaire du résultat reste l’EUI après Unlink/re-Registration.

## C. Composants et simplicité

Le contrat/port d’association et ses helpers purs sont dans `contracts-command/v3`; les deux services V3 et leurs décodeurs dans les modules d’admission existants. L’existant ne couvrait que les admissions historiques, pas S-03 ni Unlink public. `JdbcAdmissionStore`, `AdmissionSqlAuthority` et V7 fournissent l’unité et les privilèges réels. Deux classes HTTP, une configuration HTTP explicite et l’extension fermée des allocations/preflights suffisent. Aucun module Maven, moteur, participant READ, workflow, cache ou framework nouveau.

L’owner partagé `SpringAttemptTransactions` est inchangé. `AdmissionSqlTransactions` capture le PID localement via le driver et observe indépendamment une terminaison UNKNOWN avant restitution du permis. Sans observation, 503 et permis retenu ; diagnostic physique puis reprise du runtime, jamais nouvelle admission de réparation. Cette exigence concrète n’était pas satisfaite par le seul vocabulaire UNKNOWN de l’owner. Coût : un adaptateur local, information technique d’erreur, deux tests transport ; aucune requête SQL nominale ajoutée. Le driver PostgreSQL existant devient une dépendance de compilation explicite. Les fonctions et vues indirectes, PUBLIC, colonnes, MAINTAIN, memberships et ownership sont contrôlés ; les paramètres de journalisation sont provisionnés administrativement, pas par un droit SET donné au runtime.

L’option A garantit la paire sur les chemins publics de confiance. Elle ne prétend pas empêcher un INSERT isolé arbitraire sous les credentials du service ou d’un administrateur. Ces credentials et l’injecteur interne ne sont pas exposés au client. Aucune colonne/index grant Link, table Consent, fournisseur ou consentement simulé, route Link ou Invitation ajouté.

## D. D4 : valeurs, provenance et limites

Configuration initiale retenue : **Command 256 Kio, Identity 16 Kio, profondeur 16, texte 4 Kio UTF-8, entier rationnel 1024 chiffres, clé 1–128 octets ASCII `[A-Za-z0-9._~:-]`, A=1**. Aucun maximum métier indépendant de 100 cibles. Ce sont des candidats humains sélectionnés techniquement après inventaire et mesures ; aucune ratification humaine supplémentaire n’est inventée.

| Protection | Valeur et provenance | Décision / preuve / réserve |
|---|---|---|
| Corps Command | Candidat humain 262144 octets | Retenu : 1000 cibles 132043 octets et 101 fractions coûteuses 219718 octets admis ; HTTP exact 262144/262145 et chunked |
| Corps Identity | Candidat humain 16384 octets | Retenu : formes Registration/Unlink du corpus ; HTTP exact 16384/16385 ; aucune garantie pour toute identité externe future |
| Profondeur | Candidat humain 16 | Retenue : profondeur utile maximale 5 ; frontière HTTP 16/17 ; transport ferme avant décodage |
| Texte | Candidat humain 4096 octets UTF-8 | Retenu : profil 4096 admis, 65536 refusé ; frontière UTF-8 HTTP ; valeur technique initiale, aucune troncature |
| Rationnel | Candidat humain 1024 chiffres par entier | Retenu : 101 fractions coûteuses de 1024 chiffres admises ; 4096 refusé avant BigInteger/GCD ; rationnels exacts conservés |
| Clé | Candidat humain 1–128 octets ASCII | Retenu avec alphabet fermé ; frontières 128/129 et entrées malformées qualifiées ; pas un plafond métier |
| Admission en vol | Candidat humain A=1 | Retenu : allocation conservatrice, vrai pool/saturation/timeout qualifiés ; capacité commerciale et disponibilité réseau globale non déduites |
| Nombre de cibles | Aucun maximum indépendant 100 | Lots 101 et 1000 admis dans le corpus ; limites d’octets et de forme restent applicables, pas de garantie illimitée |

D4-A autonome : **22 profils**, treize opérations, lots 1/100/101/1000, textes 4096/65536 octets, fractions 1024/4096 chiffres, dont 101 fractions coûteuses de 1024 chiffres. JVM de calibration **512 Mio**, 5 préparations puis 30 échantillons/profil, sans SQL/Spring/service d’admission. Le lot 1000 occupe 132043 octets ; le lot 101 avec rationnels coûteux 219718 octets, admis sous 262144. Profondeur utile maximale 5. Textes 65536 et rationnels 4096 sont refusés, jamais transformés en plafonds métier. Observations détaillées dans le manifeste ; allocations cumulées distinctes de la mémoire échantillonnée après chaque appel. Les pics transitoires exhaustifs, percentiles et SLO ne sont pas mesurés.

La calibration finale du profil admis le plus coûteux du corpus, `targets-101-rational-1024`, observe **537.178 ms CPU cumulées sur 30 échantillons**, 541.558 ms écoulées, 341449960 octets alloués cumulés et 100532752 octets de heap au maximum des sondages après appel, sous un heap maximal de 536870912 octets. Ce maximum échantillonné n’est pas un pic transitoire exhaustif ; les allocations cumulées ne sont pas une consommation simultanée.

D4-B est intégré aux vrais tests SQL/HTTP : première admission/rejeu/conflit, index/FK, deux transactions indépendantes sur même clé et clés distinctes, WAL/stockage, spans owner incluant acquisition/commit, saturation réelle du pool/permis et timeout, issue incertaine/observation indisponible et grand lot SELF101. Le protocole SQL indépendant utilise DriverManager ; les tests HTTP utilisent le pool Hikari final. La mesure fraîche `firstAndReplay` observe pour la table d’association commune un tuple moyen de 176 octets, une page heap de 8192 octets et 65536 octets d’index après la première admission ; WAL attribué au rôle +1399 octets, puis +0 pour rejeu/conflit. Ce sont des échantillons de cette fixture, pas des coûts universels par requête. Aucune extrapolation de débit/capacité commerciale ou de SLO. L’équation historique 64+A+12=77≤97 à A=1 est une allocation conservatrice sous cette hypothèse, pas une disponibilité observée de deux Workers ou un pic universel en cas de partition réseau.

## E. Qualification, commandes et coûts

A1 : 41 invocations pures, sans Spring/PostgreSQL, processus Maven mesuré 4,371 s sur la première qualification isolée. A2 : trois oracles codec distincts, exécutés avec SQL puis dans C. T2 : **85 identités** déclarées ; la commande T2-02 reste **FAIL (9 erreurs de fixture, 76 succès)**. Les reprises ciblées corrigent chaque cause ; C-04 apporte ensuite une exécution fraîche réussie de chacune des 85 identités, sans reconstruire un PASS à partir d’un total ou de rapports anciens. B2 : 24 scénarios, 468 requêtes dans la dernière gate, zéro scénario manquant/doublon. Les onze reçus Pot finaux sont livrés sous `physical/allElevenPotOperationsThroughWorker-I2-*.json` dans l’archive C-04, émis par le parcours exécuté dans cette même gate ; le premier rejeu d’observation antérieur reste historique.

Gate C : équivalence préalable des modèles effectifs WEB, COMMAND, Architecture et Monolithe avec leur union mutualisée, absence de filtre/skip, sources/oracles/producers/configurations revus ; **2291 identités, dont 170 Architecture**. Commande réellement exécutée :

```sh
POCOMA_I1_PHYSICAL=/private/tmp/pocoma-l5-i2-c-04/physical python3 -B scripts/testing/verify.py INTEGRATION WEB,COMMAND,ARCHITECTURE --output /private/tmp/pocoma-l5-i2-c-04
```

Elle produit l’invocation Maven non filtrée `./mvnw --batch-mode --no-transfer-progress -pl runtime-web-api,runtime-command-consumption-worker,architecture-tests,runtime-monolith -am test`, enrichie uniquement des deux propriétés de provenance consignées intégralement au manifeste. Pas de full natif, `install`, `package`, exclusion, quarantaine active ni skip. Le nettoyage effectué au diagnostic initial concerne uniquement la fermeture FAST affectée, pas le reactor complet.

Chaque campagne a son dry-run, inventaire effectif, prérequis et destination neufs. Les argv exacts et SHA/dirty state sont dans les archives et le manifeste. Les compilations diagnostiques exécutées sont listées dans `native-commands.json` ; leurs logs sont retenus, sans transformer une compilation en test PASS. Les 32 tests du sélecteur passent ; les recettes d’observation et de diagnostic sont déclarées avant C-04, puis le registre et le sélecteur restent identiques après cette gate, avec comparaison explicite.

C-04 : Maven civil **4315.023 s**, monotone **4314.992 s**, campagne monotone **4323.343 s**. Coût cumulé des campagnes de vérification retenues, **échecs/diagnostics compris : 11768.629 s**. Préflights de mutualisation seuls : quatre invocations sans tests (les trois premières : 10,916 s, 10,927 s et 11,033 s ; quatrième : 11,427 s), retenues séparément du total des campagnes. Compilation native, analyse, préparation et compression ne sont pas cachées dans ce total ; durées monotones natives non mesurées, pas inventées. Le coût des tests PostgreSQL hérités tirés par C reste dû.

| Campagne | Verdict CLI historique | T/F/E/S | Maven civil s | Maven monotone s | Campagne monotone s |
|---|---|---|---:|---:|---:|
| `pocoma-l5-i2-a1-01` | FAIL | 10/2/4/0 | 1.521864 | 1.522 | 4.021 |
| `pocoma-l5-i2-a1-02` | FAIL | 0/0/0/0 | 2.964828 | 2.964 | 5.715 |
| `pocoma-l5-i2-a1-isolated-01` | INCONCLUSIVE | 0/0/0/0 | — | — | 1.152 |
| `pocoma-l5-i2-a1-isolated-02` | PASS | 41/0/0/0 | 4.370698 | 4.37 | 6.685 |
| `pocoma-l5-i2-d4-01` | INCONCLUSIVE | 1/0/0/0 | — | 2.777 | 4.64 |
| `pocoma-l5-i2-d4-02` | PASS | 1/0/0/0 | 3.382047 | 3.382 | 5.618 |
| `pocoma-l5-i2-d4-isolated-01` | PASS | 1/0/0/0 | 2.522574 | 2.522 | 4.478 |
| `pocoma-l5-i2-sql-01` | FAIL | 17/0/14/0 | 13.645151 | 13.645 | 16.84 |
| `pocoma-l5-i2-sql-02` | PASS | 17/0/0/0 | 510.708394 | 510.702 | 513.991 |
| `pocoma-l5-i2-http-01` | FAIL | 7/0/6/0 | 43.795706 | 43.795 | 47.259 |
| `pocoma-l5-i2-http-02` | FAIL | 7/3/0/0 | 99.579274 | 99.579 | 102.678 |
| `pocoma-l5-i2-t2-01` | FAIL | 41/0/0/0 | 6.274106 | 6.274 | 9.537 |
| `pocoma-l5-i2-t2-02` | FAIL | 85/0/9/0 | 130.156005 | 130.155 | 133.359 |
| `pocoma-l5-i2-http-fix-01` | PASS | 22/0/0/0 | 17.281626 | 17.281 | 20.575 |
| `pocoma-l5-i2-d4-final` | PASS | 1/0/0/0 | 3.259987 | 3.26 | 5.361 |
| `pocoma-l5-i2-acl-final` | PASS | 1/0/0/0 | 7.209967 | 7.21 | 10.003 |
| `pocoma-l5-i2-c-01` | FAIL | 1067/0/0/0 | — | 182.982 | 185.87 |
| `pocoma-l5-i2-legacy-profile-fix` | PASS | 2/0/0/0 | 11.973618 | 11.973 | 15.248 |
| `pocoma-l5-i2-c-02` | FAIL | 2120/1/0/0 | 4258.876671 | 4258.854 | 4266.539 |
| `pocoma-l5-i2-worker-await` | PASS | 1/0/0/0 | 4.497596 | 4.497 | 7.857 |
| `pocoma-l5-i2-worker-w17` | PASS | 1/0/0/0 | 56.642604 | 56.642 | 59.851 |
| `pocoma-l5-i2-flow-receipts` | PASS | 1/0/0/0 | 25.767368 | 25.767 | 28.876 |
| `pocoma-l5-i2-c-03` | FAIL | 1675/0/0/0 | — | 1964.813 | 1967.648 |
| `pocoma-l5-i2-unicode-fast` | PASS | 1/0/0/0 | 1.943598 | 1.943 | 4.4 |
| `pocoma-l5-i2-unicode-http` | PASS | 1/0/0/0 | 8.471692 | 8.471 | 11.703 |
| `pocoma-l5-i2-d4-final-02` | PASS | 1/0/0/0 | 3.048043 | 3.048 | 5.383 |
| `pocoma-l5-i2-c-04` | PASS | 2291/0/0/0 | 4315.023492 | 4314.992 | 4323.343 |

Les tests hérités sont attribués au [registre H](../testing/evidence/L5-I2/historical-reuse.json). Les deux archives I1 requises ont les SHA-256 attendus. Six groupes de production sont byte-identiques : domaines Pot/Identity, algorithmes Consumption, owner partagé, producteurs/stores READ, SQL V3 V1–V6. Le helper de test Worker corrigé et sa régression sont attribués au diagnostic C-02, sans réemploi de ce test comme preuve fraîche. JDK, Maven et empreinte settings concordent avec I1. Les deltas admission/SELF/codec/origine HTTP/composition sont requalifiés ; la gate historique ne satisfait pas C. Les campagnes L1–L4 ne sont pas relancées séparément ; leurs tests peuvent être tirés par la fermeture canonique requise.

## F. Incidents et reprises causales

1. Compilation initiale : import Amount ambigu corrigé, puis collision avec JDT et import UserId manquant : stubs de compilation puis disparition de classes ; import corrigé, nettoyage local, worktree isolé. Le refus de cache Maven dans la sandbox a ensuite été résolu par accès autorisé. Les premiers FAIL/INCONCLUSIVE restent conservés.
2. D4 : fichier physique écrit à tort dans la racine réservée aux rapports producteurs ; collecteur correctement INCONCLUSIVE. Destination corrigée. Un essai lancé par erreur dans le worktree JDT est PASS CLI mais **non retenu comme qualification** ; la reprise isolée puis le corpus final sont les preuves utilisées.
3. SQL : signature `has_column_privilege` exige `smallint` pour attnum ; correction puis PASS. Le coût observé du contrôle N+1 a motivé une collecte de privilèges en requête groupée, sans réduction d’allowlist ni d’oracle. SQL-02 avait pris 510,708 s ; la classe de 15 tests SQL dans T2-02 a pris 6,884 s après regroupement et autres adaptations. Portées/fixtures différentes : comparaison diagnostique, pas benchmark contrôlé ni SLO.
4. HTTP : paramètres SUSET de logs passés à tort par le runtime ; provisioning administratif du rôle/base et contrôle des valeurs, sans droits SET supplémentaires. Puis fixture de Registration supposant CurrentBinding immédiatement disponible : attente bornée de la version terminale corrigée, sans changer le contrat asynchrone ni augmenter les délais.
5. T2 : import manquant dans l’instrumentation SQL de test, puis helper READ recherchant un pool unique au lieu du pool nommé sous I2. Compilation vérifiée ; reprise des seules propriétés affectées (22 tests PASS), sans rejouer immédiatement tout T2.
6. Scan historique : profil manquant sur le nouveau contrôleur détecté en revue. C-01 arrêté proprement par SIGTERM du collecteur ; Maven/forks récoltés, lock libéré, **FAIL d’exécution conservé** (1067 tests partiels sans failure/error/skip). Annotation et oracle d’architecture renforcés ; les deux tests legacy/V3 passent, équivalence actualisée, puis C-02 dont l’échec distinct est décrit au point 8.
7. Livrables d’observation : la sortie optionnelle existante des onze reçus Pot n’était pas activée. Reprise d’une seule méthode avec `POCOMA_I1_PHYSICAL` déclaré ; aucun changement d’implémentation ou d’assertion, onze reçus distincts conservés. Ce simple complément d’observation ne déclenche pas à lui seul une gate.

8. C-02 : 2120 tests exécutés, un échec historique `i5_w17` à l’attente `renewalCalls()==0`, les 170 oracles Architecture non exécutés après arrêt Maven ; verdict FAIL intégralement conservé. Le helper historique relisait un prédicat instantané après son succès, ce qu’un contre-exemple Java extrait du code reproduit de façon déterministe. Correction limitée au helper pour conserver l’observation réussie ; délai et assertions de sûreté inchangés, régression pure ajoutée (succès transitoire, succès différé, timeout toujours bloquant), puis rejeu physique du seul i5_w17. L’échantillon exact ayant déclenché l’échec C-02 n’était pas instrumenté : le défaut du helper est établi, l’attribution exhaustive de cet incident reste limitée. Après les deux preuves ciblées, équivalence renouvelée et C-03 non filtrée lancée sur 2291 identités ; aucune modification des algorithmes Consumption/renouvellement ni exclusion d’oracle.

9. Revue S-03 : encodage UTF-8 Java avec remplacement permissif, alias démontré sur les vraies classes originales entre surrogate isolé et point d’interrogation, dans l’intention et la portée. C-03 arrêté proprement : FAIL d’exécution conservé, 1675 tests réussis dans les rapports partiels, aucun échec/error/skip observé dans ces rapports partiels. Contrôle pur et linéaire des scalaires Unicode dans les deux framings ; octets des textes valides inchangés, aucune normalisation nouvelle ni module/framework. Les méthodes pures et HTTP existantes sont enrichies : surrogates hauts/bas/mal appariés refusés, caractère supplémentaire valide distinct, Command et Identity en 400 sans durable. Recalibration D4-A des 22 profils sous 512 Mio après ce contrôle ; équivalence puis C-04 fraîche. Le flag d’observation existant est activé dans C-04 pour produire directement les onze reçus Pot finaux, sans nouvelle campagne de flux.

Aucune relance inchangée destinée à fabriquer un PASS. Les preuves non-PASS ne sont ni supprimées ni réétiquetées ; les reprises sont de nouvelles invocations attribuées à leur correction/hypothèse.

## G. Réserves et décision attendue

Les réserves historiques L1–L4 et R-I1-01…04 restent ouvertes dans leur portée : mémoire/disponibilité des grands Pots, complexité Spring, fournisseur AuthN réel non qualifié, coût de qualification globale. Les statuts antérieurs G1/G2 INCONCLUSIVE, G3 NOT PASSED / NOT COMPLETED et autres réserves ne sont pas corrigés rétroactivement.

**R-I2-01 — attribution du diagnostic C-02 :** les lectures originales du prédicat i5_w17 ne sont pas disponibles. Le défaut TOCTOU du helper est établi par contre-exemple et sa correction qualifiée ; le rejeu ciblé et la gate fraîche réussissent, mais ne reconstituent pas rétroactivement cet échantillon. Cette limite est conservée sans conclure à un défaut de production ni le nier.

I2 établit une qualification interne bornée des admissions et une gate Architecture fraîche ; il ne certifie pas toutes les variantes de restauration, panne réseau réelle, débit, concurrence commerciale ou production. Link réel/consentement/anti-rejeu restent à I3 avant activation ; Invitation à I4 ; full natif, B5, variantes et preuves externes applicables à Q5/SPEC-10. Une terminaison physique indémontrable retient le permis et exige une reprise après diagnostic : ce comportement fermé ne promet pas de disponibilité pendant l’incident.

**Verdict : IMPLEMENTED WITH RESERVATIONS.** Résultats ciblés et gate C satisfaits dans leurs preuves ; **acceptation/clôture humaine I2 non prononcée**, L5 OPEN et Q5 dû. Recette complète : [exploitation](L5_I2_Admission_Operations.md). [Matrice](L5_I2_Requirement_Evidence_Matrix.md), [manifeste](L5_I2_Proof_Manifest.json), [archives et empreintes](../testing/evidence/L5-I2/README.md).
