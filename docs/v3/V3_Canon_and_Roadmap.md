# Pocoma V3 — Canon et feuille de route

**Statut : cadrage V3 en cours — V3-1, V3-2 et V3-3 CONCEPTION VALIDÉE ; jalon V3-CADRE OUVERT.**  
**Branche directrice :** `feat/v3-architecture`, créée depuis `feat/consumption-transaction-protocol`.  
**Date d'ouverture :** 2026-10-22.  
**Nature :** cadrage uniquement ; aucune implémentation V3 autorisée à ce stade.

## 1. Objectif et règle d'or

**Construire une V3 plus simple dans ses structures, ses concepts et ses algorithmes.**

Partir du domaine et des usages, non des mécanismes historiques. Toute couche, abstraction, donnée persistée, conversion, état, mécanisme de synchronisation ou algorithme supplémentaire doit être justifié par un invariant concret ou un besoin fonctionnel démontré. Préférer un petit nombre de concepts expressifs à une généricité préventive. Pouvoir expliquer les principaux flux de bout en bout sans ouvrir du SQL.

La simplicité ne dispense pas de correction : préserver, ou remplacer par une garantie explicite équivalente, l'atomicité, les invariants métier, le fencing, la cohérence du séquencement, l'absence de perte et les reprises après incident. Les preuves qui ne correspondent plus au modèle sont remplacées, non simplement abandonnées.

### Résultat attendu

1. Un **domaine** cohérent : objets, sous-objets, identités, frontières d'agrégats et versions définis.
2. Des **événements et consommables** qui représentent proprement les changements du domaine et la complétion des demandes externes, dans les deux royaumes.
3. Un **modèle READ** répondant aux besoins sans projections ou jointures inutiles.
4. Un **moteur Consumption** qui exécute les transformations nécessaires avec une grammaire applicative explicite, une Discovery efficiente et des garanties transactionnelles.
5. Un parcours V3 bout en bout testé et documenté, sans chemins historiques redondants.

## 2. Règles de conduite et compatibilité

- **Aucun système V1/V2 à maintenir en production.** Les contrats Java, API non déployées, schémas, migrations de développement, modules et données de test peuvent être rompus ou reconstruits. Il n'existe aucune obligation d'exécuter simultanément V2 et V3.
- **Pas de compatibilité fictive.** Ne pas ajouter de codecs, adaptateurs, flags, migration à chaud, double écriture ou protection d'API obsolète au seul motif que le code historique existe. Préserver les données réellement nécessaires uniquement si leur nécessité est démontrée.
- **Tests existants :** inventorier la propriété que chacun prouve ; maintenir le test si son contrat survit, le remplacer si le contrat change, le supprimer explicitement si la propriété n'existe plus. Ne jamais masquer une régression d'invariant sous une rupture volontaire. Pendant les branches de refonte, les anciens tests peuvent temporairement échouer, avec liste des ruptures intentionnelles et des garanties non encore rétablies. À l'intégration V3, les suites applicables doivent passer.
- **Documentation existante :** demeure historique, pas normative pour V3. Marquer les plans A2/A2-1 précédents comme **supplantés pour leur stratégie de migration** ; préserver les décisions conceptuelles encore valides. Ne pas réécrire les rapports PASS anciens ni présenter leurs preuves comme des tests V3.
- **Acquis techniques :** traiter 4A–4E, CAS/claims/transactions/rollback comme preuves et savoir-faire réutilisables, non comme formes obligatoires de tables et ports. Toute modification de ces mécanismes exige des preuves au moins équivalentes.
- **Pas de mise en production implicite :** aucun résultat documentaire ni gate local ne certifie l'activation.
- **Après chaque étape :** revenir à CE document, mettre à jour statut, décisions prises, livrables, preuves, écarts, questions résiduelles, dépendances et prochaine action ; lier les SHAs. Ce document reste l'unique autorité pour la progression V3.

## 3. Ordre de travail et dépendances

**Ordre directeur : V3-0 → V3-1 (Pot + Identity) → V3-2 → V3-3 → V3-4 → V3-5 (cadrage final) → V3-CADRE → V3-6 (réalisation) → V3-7 (qualification).**

Le **cadrage cohérent de toutes les étapes précède toute implémentation** : ne pas commencer les modifications runtime/DDL tant que les frontières, contrats structurants, invariants et dépendances des étapes V3-1 à V3-5 n'ont pas fait l'objet d'une revue globale. Les détails strictement locaux (classe précise, index, taille de batch) peuvent être tranchés lors de l'implémentation, si cela ne remet pas en cause le modèle partagé.

| Étape | Objet | Dépend de | Statut initial |
|---|---|---|---|
| **V3-0** | Canon, objectifs, questions, jalon de cadrage | Acquis et difficultés V2 | **EN CONCEPTION** |
| **V3-1** | Domaine, sous-objets et versions | V3-0 | **CONCEPTION VALIDÉE (non implémentée)** |
| **V3-2** | Événements, demandes et consommables | Modèle V3-1 | **CONCEPTION VALIDÉE (non implémentée)** |
| **V3-3** | Projections, matérialisation cible et READ | V3-1 et V3-2 | **CONCEPTION VALIDÉE (non implémentée)** |
| **V3-4** | Consumption, Discovery et Engine (sans Locator autonome) | Sources V3-2 et sorties V3-3 | À CADRER |
| **V3-5** | Cadrage final : exigences, observabilité, objectifs de performance et plan de preuves | V3-1 à V3-4 | À CADRER |
| **V3-CADRE** | Jalon obligatoire de validation du cadrage global avant code | V3-1 à V3-5 | OUVERT |
| **V3-6** | Implémentation V3 et construction des tests/instruments | V3-CADRE validé | NON DÉMARRÉ |
| **V3-7** | Exécution des preuves, qualification performance et certification | V3-6 | NON DÉMARRÉ |

Les étapes sont successives **pour trancher les contrats conceptuels** ; leur implémentation peut ensuite se découper en lots/branches coordonnés. Ne pas introduire de dépendances circulaires : les besoins des étapes suivantes peuvent rétroagir sur le cadrage précédent **avant le jalon de validation globale**.

## 4. Questions et livrables par étape

### V3-0 — Cadrage transverse

**Questions :** frontières des royaumes et des responsabilités ; vocabulaire partagé ; invariants non négociables ; niveau de preuve ; politique de rupture, de données et de documentation ; dépendances et critères de cohérence globale.

**Livrables :** ce canon/roadmap ; inventaire raisonné des garanties existantes ; critères du jalon global ; décisions et risques transversaux.

**Sortie :** ordre et périmètres de toutes les étapes compris ; la cible reste à concevoir en V3-1 à V3-4.

### V3-1 — Domaine et sous-objets versionnés

**Questions à résoudre :**
- Délimiter les agrégats et sous-objets (Pot, Expense, Shareholder, ShareholderWeight, etc.) ; distinguer identités propres, valeurs intégrées et références.
- Définir les versions autonomes nécessaires, notamment pour Expense et éventuellement ShareholderWeight ; quel changement incrémente quelle version ?
- Quel rôle exact pour la version globale du Pot : cohérence historique, révision d'agrégat, ordre des mutations, lien aux versions des sous-objets ?
- Comment reconstruire un Pot cohérent à une version globale donnée à partir de sous-objets versionnés ? Quelles références ou révisions doivent être conservées ?
- Clarifier `expectedVersion` / `observedVersion`, conflits métier vs techniques, concurrence et CAS.
- Définir les invariants des relations et de la modification/suppression des sous-objets.

**Livrables :** modèle de domaine V3, tableau objets/identités/versions/owners, invariants, exemples de mutations et reconstructions ; questions résolues et décisions reportées. **Livré (cadrage Pot) :** [V3-1_Domain_and_Versioning.md](V3-1_Domain_and_Versioning.md) — huit sous-objets (Headers/Details et Weight/Financials), versions locales indépendantes, version globale Pot, intervalles historiques, suppression portée par Header, références métier stables, conditions d'obsolescence, commandes sans effet ou multi-cibles, poids par défaut dynamiques et reconstruction à V. Aucune implémentation ni preuve test V3.

**Livré (cadrage Identity) :** [V3-1_Identity_Domain_and_Versioning.md](V3-1_Identity_Domain_and_Versioning.md) — PocomaUser autonome, ExternalUserIdentity versionnée, binding comme relation non autonome, bindingToken non devinable, Registration/Link/Unlink, admission déclarative, contrôle atomique des rattachements, concurrence et reconstruction locale. **Entitlements fournis par une autorité extérieure, sans domaine Entitlements Pocoma.** Reste à définir le protocole de preuve de contrôle de l'identité cible lors d'un Link, les contrats des événements/complétions, les READ et le protocole d'exécution. Aucune implémentation ni preuve test V3.

### V3-2 — Événements, demandes et objets consommables

**Questions à résoudre :**
- Définir les deux royaumes (métier et identité) et deux natures : **modification du domaine** et **complétion d'une demande externe**. Quelles sont les vraies frontières et familles techniques nécessaires ?
- Clarifier `BUSINESS_EVENT`, complétion de commande, `IDENTITY_EVENT` (PocomaUserCreated, ExternalUserIdentityLinked/Unlinked…) et complétion des demandes Registration/Link/Unlink ; distinguer types de faits, demandes, décisions et résultats.
- Pour chaque type, établir identité, sujet, version/révision éventuelle, payload minimal, causalité et règles de publication.
- Identifier les objets consommables et leur cycle de vie : **burnable** (disponibilité portée par la source) ou **non-burnable** (pas d'état de consommation sur la source) ; ne pas confondre burnability et exclusivité.
- Définir séquences E / familles, densité, préfixes stables, frontières de transaction, ordre de publication, complétude, reprise et partitionnement, en réutilisant ou remplaçant explicitement les acquis 4A–4E.
- Déterminer si les deux journaux d'identité actuels sont fusionnés et comment représenter les demandes/complétions d'identité sans familles artificielles.

**Livrables :** catalogue conceptuel minimal de familles et types, contrats des événements et demandes, table des consommables/cycles de vie, invariants transactionnels et stratégie de remplacement des journaux de développement. **Livré (conception) :** [V3-2_Events_Requests_and_Consumables.md](V3-2_Events_Requests_and_Consumables.md) — six familles (deux demandes burnables avec statut explicite, quatre événements non-burnables), quatre séquences denses, eventId UUID, causalité requestId obligatoire pour les mutations, résultats terminaux et événements de complétion distincts, publication atomique, interdiction de mutation inter-domaines et matrice de cohérence V3-1. Réserves : READ, Consumption, protocole de preuve EUI cible pour Link, tests et schémas.

### V3-3 — Projections et READ cible

**Questions à résoudre :**
- Identifier d'abord les besoins réels des API et écrans : consultation courante, historique, liste et cohérence par version.
- Définir `CurrentPotHeader` (métadonnées, shareholders, références d'Expenses et de leurs versions), `ExpenseVersion`, balances, current binding, résultats de Command et Registration, liste des Pots et éventuelles autres vues justifiées.
- Déterminer quelles projections sont mutables, immuables ou historisées, avec leur clé exacte et leur source autoritative.
- Quelles garanties de cohérence entre header, expenses et balances ? Quels liens avec version globale Pot et versions des sous-objets ?
- Définir les transformations et leurs sorties sans les coupler à l'orchestration Consumption ; mécanismes de reconstruction/rattrapage et coût de lecture.
- Réduire duplications, cardinalités, index et jointures JSONB inutiles ; choisir selon besoins, non selon le pipeline actuel.

**Livrables :** inventaire exhaustif minimal des projections V3, schémas logiques, contrats READ, mapping sources→transformations→sorties, exemples de lecture/version. **Livré (conception) :** [V3-3_Read_and_Projections.md](V3-3_Read_and_Projections.md) — sept projections logiques, lecture Pot courant complète ou indisponible, versions de sous-objets immuables, balance complète immuable par V, liste utilisateur monotone avec entrées retirées, unique CurrentBinding, résultats terminaux, minVersion pour READ asynchrone. Deux Consumptions Pot : matérialisation directe CurrentPot/sous-objets et préparation de deux tâches Balance/Visibilité ; Identity et résultats directement matérialisés. Consultation historique et READ_REQUEST hors périmètre présent. Risques/contrats d'exécution renvoyés à V3-4.

### V3-4 — Consumption applicative et exécution

**Questions à résoudre :**
- Consacrer **Consumption = identité UUID stable + famille de consommables + transformation**, exprimée en Java et référençable par son seul ID.
- Définir les contrats explicites, peu nombreux, de **Discovery** et **Engine**, tous liés à une Consumption. Pas d'exécution croisée.
- Discovery des burnables par disponibilité source ; non-burnables par enrôlement/checkpoint quand un journal le justifie. Checkpoint `(consumptionId, partitionId)` ; slot logique `(consumptionId, consumableId)`, partition hors identité.
- Définir la concurrence, le routage, le parcours E, les slots, claims, fencing, retry, terminalisation, atomicité des effets et résolution de COMMIT UNKNOWN.
- Composer plusieurs Consumptions dans des Workers intégrés ou séparés, sans validateur global, manifeste, digest ni framework administratif.
- Supprimer l'identité polymorphe et la provenance générique obligatoire si elles ne répondent à aucun invariant cible ; laisser chaque Consumer porter ses liens causaux utiles.
- Vérifier coût PostgreSQL : nombre de lignes lues, jointures, index, backlogs et reprise ; préférer les algorithmes les plus simples qui satisfont les invariants.

**Livrables :** grammaire applicative et ports, modèle durable minimal, protocoles transactionnels, cas complets (Event→ProjectionTasks, identité, burnable), stratégies de déploiement et preuve de correction.

### V3-5 — Cadrage final et vérifiabilité (sans implémentation)

**V3-5A — Revue globale et exigences :** matrice unique des parcours, invariants, arbitrages résiduels, dépendances et contrats vérifiables V3-1→V3-4. Sortie : aucune contradiction structurante non arbitrée.

**V3-5B — Observabilité :** inventorier l'existant ; définir les phases temporelles (admission, attente Discovery, attente acquisition, exécution, validation, propagation READ et disponibilité bout en bout), traces, métriques, corrélations et instrumentation minimale. Sortie : chaque délai cible est mesurable ou ses limites explicitées.

**V3-5C — Objectifs de performance :** hypothèses reproductibles de charge, volume et ressources ; budgets d'attente/travail/propagation ; objectifs p50/p95/p99, débit, backlog, temps de rattrapage et seuils d'acceptation. Sortie : objectifs chiffrés et vérifiables, sans revendiquer de mesures V3 non exécutées.

**V3-5D — Plan de preuves et clôture :** lier chaque exigence structurante à une vérification adaptée : Bruno (API et parcours), k6 (charge et bout en bout), JUnit/PostgreSQL (atomicité, CAS/fencing, séquences, reprises et crash). Définir les jeux de données, les scénarios, les critères de réussite et l'ordre des lots de réalisation. Sortie : dossier V3-CADRE prêt pour revue.

**Livrables V3-5 :** matrice d'exigences unique, contrat d'observabilité, budgets/performance, spécifications des scénarios Bruno/k6/JUnit, stratégie de réalisation, décision explicite au jalon V3-CADRE. Les fichiers de test exécutables peuvent être conçus ici mais **leur réalisation et exécution ne sont pas exigées** et ne constituent pas le livrable principal.

**Exclusions :** aucun runtime, DDL, stack monitoring, optimisation prématurée, benchmark V3 prétendument mesuré ou certification.

### V3-6 — Réalisation après V3-CADRE

Implémenter domaine, consumables, Consumptions, Discovery/Engine, READ, API et observabilité minimale ; construire les collections Bruno, scénarios k6 et tests JUnit/PostgreSQL ; découper en lots avec preuves de non-régression et suppression des chemins obsolètes. Les changements de contrats structurants rouvrent le cadrage concerné.

### V3-7 — Vérification, performance et clôture

Exécuter les preuves fonctionnelles, transactionnelles, concurrentes, crash/UNKNOWN, Bruno et k6 ; mesurer ressources et performance (PostgreSQL, WAL, requêtes, latence, débit, backlog, rattrapage) dans des conditions reproductibles ; corriger les écarts, documenter limites et prendre une décision explicite de certification/activation. Une conception validée n'est pas une certification runtime.

## 5. Jalon obligatoire — cadrage V3 cohérent avant code

Le jalon **V3-CADRE** est atteint uniquement quand :
- [x] V3-1 : domaines **Pot et Identity**, identités, versions autonomes/globales lorsque nécessaires et reconstruction historique arbitrés **au niveau conceptuel**, sous réserve de la revue de cohérence globale et des interfaces de sécurité Link.
- [x] V3-2 : familles/types d'événements, demandes et consommables définis conceptuellement ; dépendances V3-3/4 et revue globale restent ouvertes.
- [x] V3-3 : projections et contrats READ stabilisés conceptuellement ; garanties d'exécution/transfert à prouver en V3-4/5.
- [ ] V3-4 : contrats et garanties de Consumption sont définis à partir de ces sources/sorties.
- [ ] V3-5 : revue globale, matrice d'exigences et plan de preuves, contrat d'observabilité et objectifs mesurables de performance terminés.
- [ ] Les interfaces et invariants transversaux des quatre étapes sont compatibles.
- [ ] Les ruptures assumées, tests de remplacement et modalités d'intégration sont consignés.
- [ ] Une revue globale conclut explicitement **CADRAGE VALIDÉ** avec références documentaires.

**Tant que ce jalon est ouvert : documentation, prototypes jetables ou expériences isolées uniquement ; pas de chantier d'implémentation structurelle V3.** Ne pas confondre « cadrage détaillé » avec conception exhaustive des classes ou requêtes.

## 6. Registre de suivi vivant

| Étape | Statut | Décisions et résultats acquis | Preuves / référence | Questions ou risques restants | Prochaine action |
|---|---|---|---|---|---|
| V3-0 | EN CONCEPTION | Règle de simplicité, ordre domaine→événements→READ→Consumption→intégration, rupture V2 acceptée | Ce document | Revue et validation du cadrage directeur | Lancer l'étude V3-1 |
| V3-1 | **CONCEPTION VALIDÉE** | **Pot :** huit sous-objets, versions locales/globales, suppressions, concurrence et histoire à V. **Identity :** User autonome, EUI versionnée, binding relationnel, token non devinable, Registration/Link/Unlink, concurrence et reconstruction. Entitlements externes. | [Pot](V3-1_Domain_and_Versioning.md) ; [Identity](V3-1_Identity_Domain_and_Versioning.md) | Sécuriser preuve de contrôle EUI cible au Link ; interfaces V3-2/3/4/5 et revue globale ; aucune preuve runtime V3 | Cadrer V3-2 avec les deux domaines |
| V3-2 | **CONCEPTION VALIDÉE** | Six familles : COMMAND, IDENTITY_REQUEST burnables PENDING→TERMINAL ; POT_EVENT, IDENTITY_EVENT et deux événements de complétion non-burnables. Quatre séquences denses, UUID eventId, mutations liées obligatoirement à requestId, atomicité résultats/événements, deux domaines strictement séparés. Revue des cas Pot/Identity réalisée. | [Contrat V3-2](V3-2_Events_Requests_and_Consumables.md) ; commit `886ff35fc2b9db55c97313d7fdeffc3e3d212475` | Détails résultats, matérialisations READ, protocole Consumption, contrôle de EUI cible et preuves techniques ; aucun code V3 | **Cadrer V3-3 READ/projections** |
| V3-3 | **CONCEPTION VALIDÉE** | Sept projections logiques ; Pot courant complet ou indisponible, sous-objets immuables, balances immuables pour chaque V, liste de visibilité monotone avec historique d'appartenance, CurrentBinding unique, résultats terminaux seuls ; minVersion READ asynchrone. Deux Consumptions sur POT_EVENT : direct CurrentPot/sous-objets et deux tâches Balance/Visibilité. Direct pour Identity et complétions. | [READ V3-3](V3-3_Read_and_Projections.md), `6ad790f1b5e31a6c71e881a2aa2da214ec23a47e` | Reprise transactionnelle et concurrence, suivi des tâches, accès sécurisés, coût SQL/reconstruction, qualification ; READ_REQUEST et historique hors périmètre | **Cadrer V3-4 Consumption/Discovery** |
| V3-4 | **CONCEPTION CONSOLIDÉE — REVUE TRANSVERSE EN COURS** | Slots durables, Discovery/checkpoints, fencing individuel et ownership partition, reprise unique par `retryAfter`, tâches internes READ, visibilité contrôlée par `PotVisibilityState`, READ asynchrone sécurisé par CurrentBinding, reconstruction READ à blanc. | [Cadrage V3-4](V3-4_Consumption_and_Discovery.md) ; harmonisation [V3-2](V3-2_Events_Requests_and_Consumables.md) et [V3-3](V3-3_Read_and_Projections.md), commit `d73dded7` | Preuve du protocole PostgreSQL, sécurité/consentement du Link, vérification exhaustive des flux et coûts en V3-5 ; aucune preuve runtime V3 | Cadrer V3-5 et mener revue V3-CADRE |
| V3-5 | **V3-5A REVUE CONCEPTUELLE CLÔTURÉE** | Matrice initiale de 24 exigences ; parcours et arbitrages BLOC-01/02/03 ; 5B observabilité, 5C objectifs et 5D plan de preuves restant à cadrer | [Revue V3-5A](V3-5A_Global_Review_and_Requirements.md), `970abf99` | BLOC-01/02/03 arbitrés ; preuves PostgreSQL, instrumentation, performances et tests encore ouverts | Lancer V3-5C et chiffrer les budgets |
| V3-6 | NON DÉMARRÉ | Réalisation et construction de la suite de tests | Après V3-CADRE | Implémentation et intégration | Attendre V3-CADRE |
| V3-7 | NON DÉMARRÉ | Validation et qualification, mesures réelles | Après V3-6 | Certification et critères d'activation | Attendre réalisation |

**Statuts autorisés :** À cadrer → En conception → Conception validée → En implémentation → Implémentée → Vérifiée. Ajouter BLOQUÉ avec motif si nécessaire. Distinguer systématiquement décision, proposition, réalisation et vérification.

### Journal des mises à jour

| 2026-10-23 | V3-5B clôture conceptuelle | O-01 option B validée : mesures d'étape fiables, E2E obligatoires, diagnostic par traitement selon observations conservées ; aucun timestamp durable de slot imposé. Branchement métrique à qualifier en V3-5D et vérifier en V3-7. | [V3-5B](V3-5B_Observability_Contract.md), `9f637bfb` |

| 2026-10-23 | V3-5B | Inventaire documentaire de Micrometer/Prometheus/Grafana, traces, audit historique de latence et scripts k6 historiques ; contrat initial des phases, SLIs, horloges, cardinalité et quatre questions O-01 à O-04. Pas de benchmark ni implémentation V3. | [V3-5B](V3-5B_Observability_Contract.md), `a39d5de3` |

| 2026-10-23 | V3-5A sécurité | BLOC-01 Link à double authentification et consentement immédiat ; BLOC-02 entitlements vérifiés à admission seulement ; BLOC-03 liaison stricte entre bindingToken et EUI authentifiée. Revue conceptuelle V3-5A clôturée, preuves ultérieures. | [V3-5A](V3-5A_Global_Review_and_Requirements.md) |

| 2026-10-23 | V3-5A | Première revue globale et matrice traçable de 24 exigences documentées ; BLOC-01/02/03 à arbitrer, preuves V3 non exécutées, V3-CADRE ouvert. | [V3-5A](V3-5A_Global_Review_and_Requirements.md), `970abf99` |

| 2026-10-23 | Feuille de route | Redécoupage confirmé : V3-5A à 5D = cadrage final (exigences, observabilité, performance, preuves) ; V3-CADRE avant code ; V3-6 = réalisation ; V3-7 = vérification et qualification. | Ce canon |

| 2026-10-23 | V3-4 transversal | Revue croisée V3-1 à V3-4 : ajout explicite des deux tâches internes en V3-2, visibilité par `PotVisibilityState`, autorisation READ via `CurrentBinding` et bindingToken authentifié, protocole unique de retry, reconstruction READ à blanc. V3-4 consolidée conceptuellement ; **V3-CADRE toujours ouvert**, aucune implémentation/test V3. | [V3-4](V3-4_Consumption_and_Discovery.md), commits `a904afc1`, `16496b9f`, `d73dded7` |

| 2026-10-23 | V3-4 | Consolidation des arbitrages Consumption/Discovery, slots, checkpoint de découverte, partition ownership, reprise, Worker mixte ; revue critique R1–R8 ouverte. Cadrage non validé, aucune implémentation ni preuve runtime V3. | [V3-4](V3-4_Consumption_and_Discovery.md), `0e93bf33d4a24bb70ac7093373c9b25c61b6f165` |
| Date | Étape | Changement / décision | Référence |
|---|---|---|---|
| 2026-10-22 | V3-0 | Création du document directeur ; cadrage initial, aucune implémentation | Commit de création de ce fichier |
| 2026-10-22 | V3-1 Pot | Conception conceptuelle validée : 8 sous-objets, règles de versions, suppressions, concurrence, finances et reconstruction ; aucune implémentation ni gate V3 ; jalon global toujours ouvert | [Pot](V3-1_Domain_and_Versioning.md), `c3749981c47712a3f07b0b42b9557f2697f5911c` |
| 2026-10-22 | V3-3 | Sept projections logiques, parcours WRITE→READ avec matérialisations directes et deux tâches Pot ; cohérence par version, monotonie, minVersion, sécurité/fraîcheur ; conception sans tests V3. | [V3-3](V3-3_Read_and_Projections.md), `6ad790f1b5e31a6c71e881a2aa2da214ec23a47e` |
| 2026-10-22 | V3-2 | Six familles de consommables, quatre séquences transactionnelles, résultats + événements de complétion, causalité requestId et matrice de cohérence V3-1 ; conception seulement, aucune preuve runtime V3. | [V3-2](V3-2_Events_Requests_and_Consumables.md), `886ff35fc2b9db55c97313d7fdeffc3e3d212475` |
| 2026-10-22 | V3-1 Identity | Conception conceptuelle validée : User, EUI versionnée, binding relationnel, témoin non devinable, Registration/Link/Unlink, concurrence/histoire ; entitlements externes. Protocole de contrôle de E2 et conception V3-2/3/4 ouverts ; aucun code ni gate V3. | [Identity](V3-1_Identity_Domain_and_Versioning.md), `6371ce9521e9a7b849e18bbdecc907cea586e100` |

À **chaque clôture d'étape**, ajouter une entrée datée avec SHA du livrable, résultats mesurables, invariants prouvés, exclusions, nouveaux arbitrages et statut. Réviser les étapes aval si une décision amont change leur hypothèse. Ne jamais revendiquer une preuve non exécutée.

## 7. Héritage documentaire et sources

- Branche technique source : `feat/consumption-transaction-protocol`, travaux de séquencement 4A–4E (PREPARED/ACTIVE et garanties transactionnelles), sans activation V3.
- `docs/steps/current/DISCOVERY/Step_Canon.md` et `Step_Plan.md` : références historiques du chantier Discovery, **pas le plan d'implémentation V3**.
- `docs/plans/2026-10-22-a2-minimal-consumption-discovery.md` : source de principes conceptuels Composition/Discovery/Engine utiles à V3, à revalider après domaines/événements/READ.
- `docs/plans/2026-10-22-a2-1-consumption-contract-implementation-plan.md` : **stratégie de migration A2-1 suspendue/supplantée pour V3** ; ne pas l'exécuter.
- Les rapports, tests et documents V2 conservent leur valeur historique. Pas de modification rétrospective de leurs verdicts.

**Prochaine action :** lancer V3-5C (hypothèses de charge et budgets chiffrés), puis V3-5D, vérifier les preuves et les derniers invariants du [cadrage V3-4](V3-4_Consumption_and_Discovery.md) depuis les six familles de sources V3-2 et les sorties directes/tâches [V3-3](V3-3_Read_and_Projections.md), dans V3-5 et la revue V3-CADRE ; résoudre les preuves d'exécution et le contrôle de l'EUI cible avant implémentation.

## Journal V3 — revue V3-5C, 2026-10-23

- **V3-5C : CADRAGE CONCEPTUEL CLÔTURÉ (non mesuré, non certifié).** Document normatif d'objectifs : [V3-5C_Performance_Objectives.md](V3-5C_Performance_Objectives.md). Trois tailles de Pot et volumes historiques ; charges offertes 1/10/50 commandes/s ; deux machines dédiées PostgreSQL et runtimes (4 vCPU, 8 Go chacune), deuxième configuration avec Keycloak réel isolé ; sondes de test isolées.
- SLO Pot moyen indépendant : p95 ≤ 500 ms nominal, ≤ 1 s soutenu, ≤ 3 s pointe ; grand Pot nominal p95 ≤ 2 s ; nouveau binding utilisable en READ p95 ≤ 500 ms nominal ; lot de 10 000 commandes avec travaux READ à rattraper en ≤ 5 minutes sous 1 nouvelle commande/s. Contention sur même Pot : correction, progression, résorption, sans SLO de latence arbitraire.
- **Preuves :** tous ces seuils sont des objectifs, pas des résultats observés. Les mesures et tests sont à définir en V3-5D, puis à construire/exécuter en V3-6/V3-7.
- **Prochaine action : V3-5D — plan de preuves détaillé et traçabilité V3-5A/B/C.** Jalon **V3-CADRE toujours OUVERT** ; aucune implémentation V3/DDL autorisée.
- Commit documentaire de clôture V3-5C : `78705de755fbbc0a06d476318da2c7435d41df79`.

## Journal V3 — arbitrage C-03 et préparation de la revue V3-CADRE (2026-10-23)

**C-03 — VALIDÉ CONCEPTUELLEMENT :** la performance utilisateur Command→Pot est mesurée jusqu'à la première lecture READ réellement complète, authentifiée et autorisée, satisfaisant `minVersion=V` avec une version servie W≥V. Les budgets p95 de V3-5C sont conservés. La matérialisation exacte des balances historiques V, de la visibilité et des tâches est une **exigence distincte de correction/progression**, non une condition artificielle de lecture d'un Pot historique. Le SLO technique nominal de Balance(P,V) est maintenu à part.

**État du cadrage :** C-01 a un contrat conceptuel de consentement explicite attesté par l'IdP (qualification fournisseur à effectuer) ; C-02 adopte une reconstruction READ avec indisponibilité totale et maintenance exclusive ; C-03 est simplifié et arbitré. **V3-CADRE reste OUVERT** : fusion documentaire du doublon CON-06, traçabilité des preuves et revue finale avant décision explicite. Aucune implémentation/DDL ni exécution de test V3.

## Journal V3 — consolidation de V3-5D avant le gate V3-CADRE (2026-10-23)

**V3-5D CONSOLIDÉ DOCUMENTAIREMENT, NON CERTIFIÉ.** Les 24 exigences V3-5A disposent de protocoles détaillés. Deux versions de la fiche CON-06 ont été fusionnées en un protocole unique §14, avec correspondance complète des cas et conservation des assertions spécifiques, dans [V3-5D](V3-5D_Test_and_Certification_Plan.md) (commit `87fb2cf7`). La revue pré-gate est consignée au §41.

**Décisions structurantes :** C-01 = consentement métier attesté par un fournisseur d'identité qualifié, aucune IdentityRequest persistée avant preuve ; indicateurs de Pots accessibles/EUI liées purement informatifs sans contrainte de fraîcheur. C-02 = reconstruction READ à blanc en maintenance exclusive et READY fondé sur préfixes sûrs et effets dérivés terminés. C-03 = SLO utilisateur jusqu'à première lecture Pot READ complète pour W≥resultingPotVersion ; preuve des balances de chaque version historique et SLO technique Balance(P,V) distincts.

**Réserves :** qualifier réellement l'attestation du fournisseur (notamment Keycloak), prouver l'exclusivité du reset sur les connexions/transactions, harmoniser les anciens paragraphes supplantés lors de la constitution du plan d'implémentation, fixer les paramètres de campagnes avant leur exécution.

**Statut du jalon : V3-CADRE OUVERT.** Une décision explicite de validation reste nécessaire après revue des réserves. Aucune modification de runtime ou de DDL V3 ; aucune campagne de test V3 exécutée.

## Journal V3 — revue formelle du gate V3-CADRE (2026-10-23)

**VERDICT DE REVUE : GO RECOMMANDÉ POUR LE CADRAGE CONCEPTUEL, GATE EN ATTENTE DE DÉCISION EXPLICITE.** Voir [V3-5D, §42](V3-5D_Test_and_Certification_Plan.md). Les huit critères du §5 ont été passés en revue : V3-1/2/3/4 conçus, V3-5 traçable à 24/24 exigences et plans de preuve ; ruptures V2 et suites de remplacement cadrées ; compatibilité des invariants revue, doublon CON-06 résolu.

**Réserves d'exécution — non bloquantes pour la suffisance conceptuelle, impératives pour leur qualification :** attestation de consentement transactionnel réellement délivrée par un fournisseur d'identité compatible ; démonstration de la barrière physique et du READY de reconstruction READ ; fiabilité des mesures E2E et des tests de charge ; harmonisation des paragraphes V3-5D supplantés avant réalisation ; passage effectif des suites PostgreSQL, Bruno et k6. Ne pas assimiler ces réserves à des garanties déjà prouvées.

**Statut inchangé à cette revue : V3-CADRE OUVERT**, pas de démarrage V3-6/DDL avant validation explicite du gate. **Prochaine action : décider GO/NO-GO du jalon**, puis seulement si GO dresser le plan d'implémentation V3-6 dans l'ordre des dépendances et des preuves.

## Journal V3 — ouverture V3-5E : cadrage des Supras (2026-10-23)

**Décision validée :** un Supra exerce les contrôles de frontière (structure, format, taille, identité/attestation, pagination, bornage des réponses) à partir de la requête et de ses contrats, **sans lire l'état métier WRITE pour anticiper les décisions du Worker**. Le domaine conserve l'autorité transactionnelle et ses propres invariants. Les sorties peuvent lire les projections READ, sous contrat d'autorisation et de cohérence. Admission refusée ≠ demande durable rejetée à l'exécution.

**Nouvelle étape V3-5E OUVERTE** : [Contrats Supras et bornage](V3-5E_Supra_Boundary_Contracts.md) (commit `ac449d12`). Inventorier les opérations, fixer une politique minimale de validation d'entrée, décider pagination/cohérence et limites de sortie, ajouter les tests puis revoir V3-CADRE.

**Conséquence :** malgré le verdict favorable de la revue V3-5D §42, **V3-CADRE reste OUVERT** jusqu'à la clôture cohérente de V3-5E et à une validation explicite ; aucune réalisation/DDL V3 n'est autorisée.

## Journal V3 — décision PotVersion historique et archivage optionnel (2026-10-23)

**V3-5E, décision validée :** une projection immuable complète `PotVersion(P,V)` porte le manifeste versionné, les références Shareholders/Expenses et les droits historiques dérivés des Shareholders. Le coût de duplication est accepté à ce stade pour simplifier pagination, autorisation et lectures stables. `CurrentPot` devient un simple pointeur mutable ; `MaterializeCurrentPot` produit la version historique et avance ce pointeur atomiquement sans nouveau Worker, tandis que Balance et Visibility restent indépendantes. Cf. [V3-5E §10](V3-5E_Supra_Boundary_Contracts.md).

**Archivage :** étudier une politique future d'archivage des versions devenues obsolètes depuis n heures, **sans purge implicite de l'historique**. Aucun mécanisme automatique retenu pour le socle initial. Contrat de consultation/expiration, ordre de matérialisation et stratégie de reconstruction sont à préciser avant activation.

**V3-CADRE toujours OUVERT :** harmoniser V3-3 et les protocoles READ/SEC/PRJ de V3-5D avec cette décision, puis terminer les autres contrats Supra et leur certification. Aucun runtime/DDL V3 implémenté.

## Journal V3 — harmonisation READ / preuves de PotVersion (2026-10-23)

**V3-3 amendée :** `PotVersion(P,V)` immuable de composition et d'autorisations historiques ; `CurrentPot` réduit à un pointeur. Huit projections logiques désormais, et non sept. `MaterializeCurrentPot` écrit PotVersion, sous-versions et pointeur en une transaction READ ; tâches Balance et Visibility inchangées. Voir [V3-3 §9](V3-3_Read_and_Projections.md), commit `1d9384b8`.

**V3-5D amendée :** preuves PRJ-01, READ-01 et SEC-01 adaptées au manifeste et aux lectures groupées à V ; tests de coût de stockage et de transfert ajoutés. Voir [V3-5D §43](V3-5D_Test_and_Certification_Plan.md), commit `2f8a7c26`.

**Suite V3-5E :** arbitrer limites d'admission, bornes de chargements groupés et erreurs de frontière. **Gate V3-CADRE toujours ouvert**, aucun runtime/DDL/test V3 exécuté.

## Journal V3 — revue de couverture V3-5E (2026-10-23)

**Revue menée, clôture NON PRONONCÉE.** L'inventaire de couverture Admission Command/Identity, résultats individuels, CurrentBinding, PotVersion, chargements groupés, liste des Pots, erreurs et surcharge est consigné en [V3-5E §17](V3-5E_Supra_Boundary_Contracts.md), commit `a352b68d`.

**Deux arbitrages structurants subsistent :** (S-01) pagination de la liste mutable `CurrentUserPotList` sans snapshot et comportement sous concurrence ; (S-02) autorisation d'une version historique après retrait du Shareholder dans une version plus récente (SEC-01 actuel) ou révocation rétroactive par les droits courants. Les plafonds numériques restent à mesurer et ne doivent pas induire de bornes métier arbitraires.

**V3-CADRE demeure OUVERT ; aucun code ni test V3 exécuté.**

## Journal V3 — revue de clôture V3-5E (2026-10-23)

**REVUE EFFECTUÉE, V3-5E NON CLÔTURÉE.** Matrice de couverture et réserves dans [V3-5E §17](V3-5E_Supra_Boundary_Contracts.md). Les admissions Command/Identity, lectures Pot versionnées, chargements groupés d'Expenses, contrôle de sécurité et surcharge ont un cadrage conceptuel. Pas de plafonds numériques arbitraires retenus comme règles métier.

**Trois arbitrages de contrat encore structurants :** S-01 pagination/ordre et cohérence de `CurrentUserPotList` ; S-02 taille et découpage éventuel du manifeste de références renvoyé avec `PotVersion` ; S-03 idempotence observable d'une admission après commit/réponse incertaine et réemploi de clé avec payload différent. Les paramètres de limites et de quotas peuvent être calibrés plus tard, sans changer le contrat.

**V3-CADRE demeure OUVERT** jusqu'à résolution de ces choix et revue cohérente des preuves. Aucun runtime, DDL ou test V3 réalisé dans ce chantier.
