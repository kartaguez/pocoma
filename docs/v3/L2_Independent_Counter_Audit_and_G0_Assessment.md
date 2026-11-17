# Pocoma V3 — Contre-audit indépendant L2 et état de G0

**Référentiel audité :** `39aecafd8eb2b43ce56a3c23cb4f10a0b4babfa4` sur `feat/v3-architecture`.

**Rapport contredit :** ajout publié au commit `055e34eb97286d020cc0ba2fa6fdaa50451b9a61`.

**Statut :** audit documentaire et inspection statique ; aucun test PostgreSQL ou Maven L2 exécuté, aucun G0 franchi.
**Portée :** ce rapport évalue les décisions ; il ne les modifie pas et n'autorise pas l'implémentation.

L'ancien rapport `L2_Contradictory_Independent_Architectural_Audit_Report.md` a été retiré de l'arbre courant parce que ses matrices attribuaient des sujets incorrects aux décisions L2 et recommandaient des protocoles contraires à R3 et R10. Il reste dans [l'historique Git au commit de publication](https://github.com/kartaguez/pocoma/blob/055e34eb97286d020cc0ba2fa6fdaa50451b9a61/docs/v3/L2_Contradictory_Independent_Architectural_Audit_Report.md), uniquement comme pièce d'audit historique. Ses verdicts et recommandations ne font pas autorité.

## 1. Provenance et verdict exécutif

Le parent direct de `055e34eb` est `39aecafd`. Leur seul écart est l'ajout de l'ancien rapport. Au début du contrôle, `origin/feat/v3-architecture` désignait `055e34eb`. Le checkout de travail initial était une autre branche, `feat/runtime-monolith`, avec des modifications préexistantes ; il n'a pas été utilisé pour interpréter les décisions ni modifié. Le présent retrait a été préparé dans un worktree isolé depuis `origin/feat/v3-architecture`.

**Verdict :** les douze lignes A01–A12 de l'ancien rapport ont un sujet erroné. R8 est correctement identifié ; R4 seulement en partie ; R3 et R10 sont contredits techniquement. Les constats matériels sur l'index PENDING et le trigger L1 restent utiles. Le deadlock déterministe allégué n'est pas prouvé pour l'ordre R1. La prétendue validation indépendante `PASS` de L1-F2 dépasse les vérifications effectivement rapportées. Le dossier L2 peut être **soumis** à la décision humaine G0, mais G0 n'est pas acquis.

Sources de contrôle : [registre A01–A12](L2_Decision_Register.md), [consolidation R1–R10](L2_R1_R10_Normative_Consolidation.md), [plan L2](L2_Design_and_Verification_Plan.md), [revue contradictoire](L2_Contradictory_Review.md), [SPEC-03](SPEC-03_Events_Requests_Consumables.md), [SPEC-05](SPEC-05_Consumption_Discovery.md), [SPEC-10](SPEC-10_Test_Certification.md), [canon](V3_Canon_and_Roadmap.md), [plan directeur](V3_Implementation_Master_Plan.md), [manifeste](L2_Certification_Coverage_Manifest.md) et [oracles](L2_Independent_Test_Oracles.md). Ces fichiers sont lus à `39aecafd`, non dans un checkout implicite.

## 2. Matrice normative A01–A12

Les intitulés et options sont ceux du registre. La dernière colonne indique les consolidations applicables ; le registre §« Décisions complémentaires » confirme notamment l'amendement **trois → quatre tables** d'A03.

| Décision exacte | Contrat et invariants validés | Amendement ; source précise |
|---|---|---|
| **A01 — Unification des slots — B** | Remplacer `v3_command_slot` par `(consumptionId, consumableId)` dans un V3 neuf ; un seul Engine, aucune PENDING perdue ni TERMINAL réenrôlée. | R7 ; registre l. 9–23, consolidation l. 83–93. |
| **A02 — Frontière Engine / Consumption — B** | Engine : claims, transaction, slot, issues techniques ; Consumption : validations, faits et effets métier. Pas de transaction imbriquée. | R1/R9 ; registre l. 25–39, consolidation l. 7–18 et 103–114. |
| **A03 — Modèle durable PostgreSQL minimal — A** | Slot, propriété et checkpoint initialement ; aucun DONE, workId, payload ou compteur durable de softCap. Modèle **en vigueur à quatre tables** après R3. | R2/R3/R8 ; registre l. 41–55 et 202–204, consolidation l. 20–43 et 95–101. |
| **A04 — Identités et familles — B** | `consumptionId` UUID fixe ; `consumableId` UUID natif ; une des sept familles SPEC-03 par Consumption, sans `family_id` de slot. | R3 emploie la famille canonique ; registre l. 57–71, consolidation l. 32–43. |
| **A05 — Partitionnement, routage et propriété — A** | P logique stable, hash déterministe, candidats statiques, lease PostgreSQL ; perdre la partition n'annule pas un claim valide. | R3 ; registre l. 73–87, consolidation l. 32–43. |
| **A06 — Discovery événementielle — A** | Cinq séquences denses ; fenêtre cohérente `(K,H]`, K dernière position examinée ; slots requis et K committent atomiquement. | R2 ; registre l. 89–103, consolidation l. 20–30. |
| **A07 — Discovery burnable — B** | COMMAND/IDENTITY_REQUEST PENDING sans slot, keyset UUID, curseur circulaire volatil, revalidation transactionnelle ; aucun checkpoint burnable. | R4/R7 ; registre l. 105–119, consolidation l. 45–56 et 83–93. |
| **A08 — Acquisition, fencing et renouvellements — B** | Lots bornés, `SKIP LOCKED`/CAS, token individuel frais, renouvellement avant expiration, verrou final et heure PostgreSQL après attente. | R10 ; registre l. 121–135, consolidation l. 116–128. |
| **A09 — Transaction finale commune — B** | Une transaction Engine pour claim, effets métier et suppression du slot ; poisoning, rollback et résolution d'UNKNOWN sans réexécution aveugle. | R1/R5/R9 ; registre l. 137–151, consolidation l. 7–18, 58–70, 103–114. |
| **A10 — Retries, quarantaine, réactivation — A** | Une tentative par claim ; délai fixe configurable ; cession séparée après rollback confirmé ; budget N et réactivation administrative CAS. | R2/R5/R8 ; registre l. 153–167, consolidation l. 20–30, 58–70, 95–101. |
| **A11 — Polling, capacité, équité — A** | Boucles Discovery/Engine indépendantes, tourniquet des Consumptions, softCap/M_C/M_W distincts, aucun scheduler durable. | R4/R10 ; registre l. 169–183, consolidation l. 45–56, 116–128. |
| **A12 — Pilotes et certification bornée — B** | Command Pot réel ; Identity fixture ; banc READ technique avec effets PostgreSQL ; PASS L2 strictement borné. | R6 ; registre l. 185–199, consolidation l. 72–81. |

SPEC-03 §2 compte **sept** familles : COMMAND, IDENTITY_REQUEST, POT_EVENT, INVITATION_EVENT, IDENTITY_EVENT, COMMAND_COMPLETION_EVENT et IDENTITY_COMPLETION_EVENT. Il n'existe pas de famille normative `PROJECTION_REBUILD_TASK`.

## 3. Matrice normative R1–R10

| Arbitrage exact | Option et règles essentielles | Modifie ; source |
|---|---|---|
| **R1 — Finalisation du slot** | B ; `SlotCompletion.complete()` une fois après effets/résultat et **avant** fermeture et suffixe dense ; suppression d'exactement un slot dans la même transaction. | A02/A09 ; consolidation l. 7–18. |
| **R2 — SoftCap** | A ; verrou de `partition_ownership`, comptage non-QUARANTINED, préfixe K intégralement couvert ; réactivation CAS pouvant dépasser temporairement le cap. | A03/A06/A10 ; l. 20–30. |
| **R3 — Contrat durable de routage** | B ; SHA-256 versionné, P commun par famille, quatrième table administrée, compatibilité contrôlée dans l'acquisition **et le renouvellement** de propriété. | A03/A05 ; l. 32–43. |
| **R4 — Discovery burnable** | A ; keyset, cursor/U volatils, U fixé par tour, revisite des références sautées et pause sous saturation. | A07/A11 ; l. 45–56. |
| **R5 — Classification et cession unique** | A ; Engine seul cède après rollback certain ; échec confirmé compté une fois, UNKNOWN/crash non présumés ; N et délai fixe. | A09/A10 ; l. 58–70. |
| **R6 — Certification bornée** | A ; toutes les variantes SPEC-10, portée L2 explicite, fixtures limitées, absence/skip jamais PASS. | A12 ; l. 72–81. |
| **R7 — Bootstrap neuf isolé** | A ; autorités WRITE/READ neuves et isolées, inventaire, données existantes préservées, contrat R3 installé administrativement. | A01 ; l. 83–93. |
| **R8 — Interdiction de purge** | Aucun slot incomplet purgé ; suppression normale au seul succès atomique ; reset READ total coordonné distinct. | A03/A10 ; l. 95–101. |
| **R9 — Observations durables** | A ; `RecoveryObservation` sans mutation, quatre résultats non persistés ; NOT_COMPLETED ne prouve pas un rollback. | A02/A09 ; l. 103–114. |
| **R10 — Équité intra-Consumption** | B ; tourniquet Consumptions/partitions puis parcours circulaire des slots, U volatil, revisite après `SKIP LOCKED`, réservations M_C/M_W ; threads **et capacités PostgreSQL** indépendants pour les renouvellements. | A08/A11 ; l. 116–128. |

## 4. Divergences du rapport retiré

L'[ancien tableau A, l. 54–67](https://github.com/kartaguez/pocoma/blob/055e34eb97286d020cc0ba2fa6fdaa50451b9a61/docs/v3/L2_Contradictory_Independent_Architectural_Audit_Report.md#L54-L67) attribue respectivement à A01–A12 : « périmètre L2 », « granularité claims », « topologie WRITE/READ », « checkpoint », « softCap », « retry à six classes/cinq paliers », « coordination », « scheduler Q », « emplacement Maven », « observabilité », « certification » et « ownership de partition ». Aucun n'est l'intitulé correspondant du registre. C'est une **confusion systématique de numérotation** ; `attemptFence`, le quota Q, les cinq paliers de backoff et `PROJECTION_REBUILD_TASK` ajoutent des éléments **inventés ou contraires** au contrat. Les douze verdicts individuels `VALIDATED` ne valident donc pas les décisions annoncées ; des observations transversales peuvent néanmoins être réexaminées sous leur bon identifiant.

L'[ancien tableau R, l. 71–82](https://github.com/kartaguez/pocoma/blob/055e34eb97286d020cc0ba2fa6fdaa50451b9a61/docs/v3/L2_Contradictory_Independent_Architectural_Audit_Report.md#L71-L82) présente :

| ID | Énoncé Gemini | Écart et conséquence |
|---|---|---|
| R1 | Suppression après suffixe. | **Contradiction** de R1-03/04 : `complete()` avant suffixe. Le besoin d'adapter le trigger reste réel. |
| R2 | Barrière des séquences WRITE. | **Confusion** : R2 est softCap ; la barrière dense appartient au contrat événementiel. |
| R3 | `MurmurHash3_x86_32`, table consultée seulement au démarrage. | **Contradiction factuelle** : SHA-256 et contrôle transactionnel R3-06/08. Recommandation à écarter. |
| R4 | Keyset burnable, mais règle `< B` réputée normative. | **Partiellement fidèle** ; le critère validé est l'avancement des clés effectivement examinées. |
| R5 | Autorité temporelle PostgreSQL. | **Confusion** : R5 est la classification et cession unique ; le temps DB est exigé ailleurs aussi. |
| R6 | Dimensionnement et profils CI. | **Confusion** : R6 est la certification bornée. |
| R7 | Gouvernance humaine G0. | **Confusion** : R7 est le bootstrap isolé. |
| R8 | Non-purge. | **Correspondance recevable**. |
| R9 | Quarantaine. | **Confusion** : R9 concerne l'observation durable ; quarantaine relève d'A10/R5/R8. |
| R10 | `maxRenewals`, `maxTotalHoldDuration`, sans heartbeat indépendant. | **Contradiction technique** : R10 impose la revisite circulaire et les ressources indépendantes de renouvellement. |

Ces divergences invalident les matrices de validation de l'ancien rapport, ses propositions MurmurHash3, démarrage seul et renouvellement coopératif, ainsi que le raisonnement qui déclare les trois axes globalement PASS. Elles n'invalident pas par simple erreur d'identifiant les risques matériels ci-dessous. `POSTAUD-006` dans le [post-audit initial](L2_Post_Consolidation_Independent_Audit_Report.md) traite de l'équité/renouvellement L2, **pas** du prérequis L1-F2.

## 5. Les huit constats, requalifiés

| Réf. | Validité factuelle / normative ; sévérité | Scénario, correction minimale, échéance, arbitrage et preuve |
|---|---|---|
| **V3-GEM-AUD-001** | **Partiellement confirmé** : cycle AB-BA possible avec un autre ordre, non démontré sous R1 et le plan ; **MAJOR conditionnel à une implémentation fautive, MINOR documentaire aujourd'hui**. | Source verrouillée puis attente sur une suppression de slot non committée **antérieure** à l'UPDATE source formerait un cycle si Finalizer attendait la source. Préciser en I3/I4 « doublon visible ⇒ abandon sans attendre » ; test PostgreSQL à barrières et inspection `40P01`/états. Aucun arbitrage pour respecter R1/R4 ; ordre insert-first universel serait un changement à revoir. |
| **002** | **Partiellement confirmé**, **MINOR** : index PENDING absent du prototype ; famine `< B` non démontrée pour R4 réel. | Des TERMINAL nombreux peuvent augmenter le scan de rares PENDING. Choisir l'index en I1, mesurer avec `EXPLAIN (ANALYZE, BUFFERS)` ; en I4/P3 prouver cursor/U, filtrage partition, slots existants et revisite. Pas d'arbitrage. |
| **003** | **Confirmé**, déjà tracé par R1-10 et I1/I2 ; **MINOR**. | `v3_require_command_unit()` référence `v3_command_slot` dans le DDL L1. Adapter vers `consumption_slot`, conserver `v3_command_unit`/`v3_result_unit` différés et vérifier les 22 contraintes métier immédiates séparément. I1/I2 ; test de commit TERMINAL avec/sans slot ; pas d'arbitrage. |
| **004** | **Réfuté comme citation du plan**, pression SQL possible selon réglage ; **OBSERVATION**. | K avancé hors partition couvre un préfixe sans admission ; ne présume pas une accélération. Mesurer requêtes/délai sous saturation en I5/P7 et borner le polling. Paramétrage, pas arbitrage normatif. |
| **005** | **Partiellement confirmé**, **MINOR** : méthodes prévisionnelles ; la prétendue simple vérification `len >= 12` et le compte 323/324 sont erronés. | Un manifeste statique n'atteste pas un test exécuté. Avant G1/G2, raccorder preuves exigibles aux méthodes/rapports et vérifier les oracles SQL. I6 étant **après G1/G2**, la recommandation de calendrier Gemini est inexécutable. Pas de blocage G0 ni d'arbitrage. |
| **006** | **Observation de lisibilité**, pas défaut architectural démontré ; **OBSERVATION**. | Index de lecture éventuel, non bloquant, sans preuve technique ni arbitrage. |
| **007** | **Confirmé** pour scories éditoriales ; **OBSERVATION**. | Nettoyage avec maintien des ancres du manifeste, non bloquant, sans arbitrage. |
| **008** | **Réfuté normativement**, **MAJOR pour la recommandation**. | Si contrat R3 vérifié au seul démarrage, un lease peut être acquis/renouvelé sous configuration devenue incompatible. Garder contrôle transactionnel R3-06/08 en I2/I3 ; test d'administration concurrente. Supprimer ce contrôle demanderait un nouvel arbitrage humain. |

Le [script de manifeste](check_l2_manifest.py) vérifie identifiants, scénarios, oracles et liens ; il n'atteste pas les résultats Java ou PostgreSQL. Les méthodes `prévisionnelle : L2ManifestProof…` sont déclarées comme telles à P0/G0. L'ancien rapport les transforme à tort en urgence d'I6 avant G1/G2.

## 6. Concurrence Discovery burnable / Finalizer

### 6.1 Verrous et ordre validé

R1-01/03/04 impose : Engine verrouille le slot, Consumption produit effets/résultat et terminalise la source, `SlotCompletion.complete()` supprime le slot, puis le suffixe dense est fermé. Le code L1 de référence [`JdbcPotCommandStore`](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/JdbcPotCommandStore.java) verrouille également le slot avant d'actualiser la source et avant de supprimer le slot. Le plan L2 §7 avertit de ne **jamais attendre un slot existant après prise d'une source** : constater le doublon et abandonner.

Les [verrous de ligne PostgreSQL 17](https://www.postgresql.org/docs/17/explicit-locking.html) bloquent les lockers/writers concurrents, mais pas une lecture MVCC ordinaire. Un simple `FOR UPDATE` du slot n'est pas la preuve que tout `INSERT ... ON CONFLICT DO NOTHING` sur sa clé attend. L'[unicité PostgreSQL](https://www.postgresql.org/docs/17/index-unique-checks.html) fait en revanche attendre l'issue d'une **insertion ou suppression non committée** de la même clé. Sous [`READ COMMITTED`](https://www.postgresql.org/docs/17/transaction-iso.html), `ON CONFLICT DO NOTHING` peut renoncer à insérer sur un conflit encore invisible au snapshot. Gemini confond ces situations et suppose une suppression du slot alors que Finalizer n'a pas encore verrouillé/mis à jour la source, contrairement à R1.

### 6.2 Trois entrelacements théoriques

| Cas | Verrous, attente, issue | État durable possible |
|---|---|---|
| **Discovery gagne** | D verrouille `partition_ownership`, puis la source PENDING, sonde sans verrou le slot absent, insère et commit ; F n'acquiert un claim que plus tard. | PENDING + un seul slot disponible. |
| **Finalizer gagne** | F verrouille le slot, actualise source TERMINAL, supprime le slot et commit ; D attend ou saute la source, puis voit TERMINAL et n'insère rien. | TERMINAL + effets/résultat/suffixe cohérents, sans slot. |
| **Croisement** | F verrouille le slot ; D verrouille la source avant l'UPDATE de F, lit sans verrouillage le slot existant, abandonne et commit ; F poursuit. Une tentative d'`INSERT` aveugle n'est pas requise par le plan. | Après F : TERMINAL sans slot ; attente unidirectionnelle, pas de cycle dans cet entrelacement. |

Un vrai AB-BA serait possible **si** F supprimait le slot avant son UPDATE source pendant que D tiendrait la source puis attendrait la résolution de cette suppression unique. Cet ordre est exclu par R1 ; il faut l'interdire dans l'implémentation et le tester. Aucun deadlock n'a été reproduit pendant cet audit.

### 6.3 Solution minimale et questions de sûreté

En I3/I4 : sous le verrou `partition_ownership` de R2, mesurer la place non-QUARANTINED, verrouiller ou sauter la source PENDING, puis faire une **lecture MVCC sans attente du slot exact**. Tout slot présent, même QUARANTINED, est un doublon : abandonner et laisser R4 revisiter. Insérer le slot unique seulement s'il est absent et si PENDING est encore protégé ; commit atomique. Respecter l'ownership, les quotas, l'unicité et la borne U. Ceci fixe un protocole compatible R1/R2/R4/R8/R9 sans nouveau stockage ni suppression provisoire.

L'insert-first Gemini n'est pas nécessaire. Il peut être sûr si, après attente éventuelle d'une suppression concurrente, la source est reverrouillée, TERMINAL provoque rollback/retrait **dans la même transaction**, et aucun slot provisoire n'est committé. Sans cette revalidation, une suppression/commit par F suivie de réinsertion pourrait laisser un slot pour TERMINAL. L'insert-first ajoute des cas de trigger, de capacité et de règle R8 à examiner. Le choix SQL précis peut être arrêté en I3/I4 ; G0 doit garder les invariants, pas prétendre à une reproduction PostgreSQL. Les campagnes devront forcer les trois courses sur plusieurs connexions, observer `pg_locks`/`pg_blocking_pids`, `40P01`, doublons et absence de slot TERMINAL après commit.

## 7. R3 et R10 : contrôles non négociables sans arbitrage

**R3.** Octets : ASCII `pocoma-consumable-v1`, `0x00`, nom de famille ASCII canonique, `0x00`, UUID natif sur 16 octets réseau big-endian. SHA-256, quatre premiers octets en int32 signé big-endian, puis `floorMod(hash, P_famille)`. Version `v1`. Le [plan §6](L2_Design_and_Verification_Plan.md#6-identités-routage-leases-et-configuration-reproductible) donne 21 vecteurs, sept familles × UUID ordinaire/nul/maximal ; recalcul Python indépendant : **21 vérifiés, zéro écart**. Exemple COMMAND/`123e4567-e89b-12d3-a456-426614174000` : `-805145351`, P=4 → 1 et P=16 → 9. P=4/16 sont **illustratifs**, pas les P de production. P doit être fixe par famille et partagé entre ses Consumptions. Bootstrap administratif seul, droits Worker restreints, version/empreinte/vecteurs contrôlés, incompatibilité bloquante. R3-06/08 impose vérification transactionnelle lors de chaque acquisition et renouvellement de propriété ; cela n'impose pas une jointure à chaque claim individuel ou finalisation. MurmurHash3 et « démarrage seul » sont donc contraires aux normes.

**R10.** Round-robin des Consumptions/partitions **et** parcours circulaire volatil par `(C,p)` des clés de slots ; U fixée au tour, clés réellement examinées, revisite après `SKIP LOCKED`, `retryAfter`, verrous ou quarantaine. `softCap`, M_C et M_W sont distincts ; les places M_C/M_W se réservent atomiquement avant soumission et se restituent. Les renouvellements ont threads **et accès PostgreSQL réservés** même sous saturation ; perdre le lease partition arrête les nouvelles acquisitions, pas le renouvellement de claims individuels valides. Le modèle coopératif sans indépendance de l'ancien rapport est contradictoire et exigerait un arbitrage humain.

## 8. L1-F2, axes d'audit et actions

Le [rapport de clôture L1-F2](L1_F2_Closure_Report.md) et l'[index d'archives](../testing/evidence/L1-F2/Gate-index.json) documentent le retrait des lectures d'historique complet du chemin WRITE, huit index `(pot_id, active_from)` et 28 lignes Java transférées aux profondeurs 100/1 000/10 000. Les archives correspondent aux empreintes de l'index ; les méthodes obligatoires du gate historique sont PASS. Le gate complet reste **INCONCLUSIVE** en raison d'un skip exploratoire V2. L'ancien rapport a relu code, DDL et mesures historiques, mais n'a pas réexécuté Maven ou les benchmarks ; les index ne garantissent pas à eux seuls une complexité universelle `O(1)`. Son « PASS indépendant » est donc trop fort. Le prérequis documentaire est crédible et favorable, mais sa levée formelle exige une revue indépendante bornée des preuves, de leur périmètre et de leurs réserves. `POSTAUD-006` n'est pas ce prérequis.

| Axe | Verdict corrigé et périmètre |
|---|---|
| **A — architecture L2** | **PASS WITH RESERVATIONS conceptuel** : les vraies décisions sont cohérentes et implémentables ; preuve concurrente réelle encore due. Les matrices Gemini ne valident pas cet axe. |
| **B — dossier L2** | **PASS WITH RESERVATIONS pour soumission G0** : autorité documentaire et plan de preuve lisibles ; méthodes prévisionnelles et paramètres d'activation à figer, aucun PASS L2 exécuté. |
| **C — corpus V3 global** | **INCONCLUSIVE pour un PASS global** : interfaces ciblées de SPEC-01–10, canon et lots L0–L8 examinées, pas de nouvel audit exhaustif de toutes les dépendances. Le PASS Gemini ne doit pas servir de preuve. |

| Échéance | Correction ou preuve réellement nécessaire |
|---|---|
| **Avant décision G0** | Utiliser les 22 décisions ci-dessus, écarter les recommandations Gemini contraires à R3/R10, obtenir l'audit indépendant borné L1-F2 ; figer UUID littéraux, familles/autorités, P par famille, contrat/empreinte et hypothèses de capacité/renouvellement que le plan §6 déclare bloquants pour G0. Aucune valeur de production finale de P n'est fixée au SHA audité. |
| **I1/I2** | Quatre tables et droits/bootstrap R3/R7 ; index des sources PENDING choisi sur plan SQL ; inventaire `v3_command_slot`, adaptation de `v3_require_command_unit()` et maintien des triggers terminaux différés. |
| **I3/I4** | Protocole de verrous Discovery/Finalizer sans attente de doublon sous verrou source ; contrats de lease R3 ; R4 cursor/U, softCap et unicité. |
| **G1/G2 et campagnes** | Tests PostgreSQL à barrières, plans de scan avec TERMINAL nombreux, renouvellements sous saturation et après perte de partition, mapping des lignes exigibles du manifeste aux tests exécutés et oracles indépendants. |
| **Non bloquant** | Nettoyage de numérotation/ancres, index documentaire, réglage mesuré du polling. |

**G0 :** le corpus `39aecafd` est suffisamment fiable pour être **soumis** à validation humaine sur le référentiel rectifié ; il n'est pas déjà approuvé. Les seuls empêchements démontrés à une approbation immédiate sont la validation indépendante L1-F2 non établie au niveau revendiqué par Gemini et les paramètres que le plan lui-même impose de figer pour G0. Le deadlock déterministe allégué et quatre « microcorrections » Gemini ne justifient pas un nouveau cycle complet de conception.

## 9. Vérification effectuée pour ce contre-audit

Tranche déclarée : `NONE`, aucun changement sous `app/`. Commandes de contrôle : `git ls-remote`, `git merge-base --is-ancestor`, `git diff --name-status`, `git show`, inspections statiques des sources, et recalcul Python en mémoire des 21 vecteurs SHA-256. Aucun franchissement inattendu de tranche ; aucune porte globale `architecture-tests` requise ; aucun Maven, test PostgreSQL ni full reactor exécuté. Aucun PASS technique L2, franchissement G0 ou arbitrage humain n'est revendiqué.
