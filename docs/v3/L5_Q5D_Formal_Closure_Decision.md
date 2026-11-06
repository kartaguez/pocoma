# Pocoma V3 — L5/Q5-D — Décision Humaine Définitive de Clôture Formelle du Lot L5

**Date :** 6 novembre 2026  
**Dépôt :** `kartaguez/pocoma`  
**Branche :** `feat/v3-architecture`  
**Référence Git d'entrée :** `f4f5d973242e5d4980813afcf2c5b24af6ad2897` (`f4f5d973`)  
**SHA qualifié sous test (Q5-B6) :** `2890d88f6fa94ff2262d08a2809f48512ce27c9d` (`2890d88f`)  
**Décision humaine :** `GO_L5_Q5D_FORMAL_CLOSURE_WITH_EXPLICIT_RESERVATIONS`  
**Statut officiel terminal :** `L5 — CLOSED WITH EXPLICIT RESERVATIONS — GLOBAL CERTIFICATION PASS, Q5-C AUDIT ACCEPTED, OPTIMIZATIONS DEFERRED UNTIL AFTER L6`

---

## 1. Décision Humaine et Motifs Fondateurs

Le responsable du programme Pocoma V3 prononce formellement la décision suivante :

> **Le lot L5 (« Supras et parcours externes V3 ») est formellement accepté et définitivement clos avec réserves explicites.**

### Motifs de la décision :
1. **Certification globale Q5-B6 irréfutable :** Exécution canonique unique du réacteur complet Maven sous `verify.py CERTIFICATION` aboutissant à **61/61 modules SUCCESS**, **2 344/2 344 tests PASS** (0 failure, 0 error, 0 skip), Gate Architecture globale **170/170 tests PASS**, Gate Bruno consolidée B5 **70/70 scénarios PASS**, et Gate PostgreSQL V1–V10 validée sans erreur Flyway.
2. **SHA technique qualifié immuable :** La campagne Q5-B6 a été rigoureusement exécutée sur le commit technique immuable `2890d88f6fa94ff2262d08a2809f48512ce27c9d`, sans aucune modification de code productif (`PRODUCTION_FILES_MODIFIED = 0`) ni de migration SQL (`PRODUCTION_MIGRATIONS_MODIFIED = 0`).
3. **Audit indépendant Q5-C mené à terme et publié :** L'audit indépendant transversal de qualité, simplicité, performances anticipées et restructuration a été complété et publié sous [L5_Q5C_Quality_Performance_and_Restructuring_Audit.md](L5_Q5C_Quality_Performance_and_Restructuring_Audit.md) (`f4f5d973`).
4. **Écarts documentaires de certification réconciliés :** L'audit Q5-C a élucidé l'évolution du SHA entre Q5-B4 et Q5-B6 comme une déviation d'oracles de test indispensable et régularisée, et a démontré que l'écart arithmétique 170 vs 178 oracles d'Architecture relevait d'une erreur documentaire de comptage textuel (`DOCUMENTARY_ERROR`), 100% des tests unitaires réels ayant été exécutés avec succès.
5. **Absence d'anomalie bloquante :** Aucune anomalie bloquante (BLOCKER ou MAJOR non résolu) n'a été identifiée sur le périmètre fonctionnel et contractuel accepté de L5.
6. **Réserves résiduelles identifiées et attribuées :** Les réserves restantes concernent exclusivement les performances sous grand volume, l'exploitation, le dimensionnement, la transition et les opportunités de simplification industrielle.

### Décision stratégique supplémentaire :
> **Les ajustements architecturaux, les restructurations et les optimisations proposés par le rapport d'audit Q5-C sont formellement différés après le lot L6, lorsque le socle V3 sera rendu observable et que des mesures physiques permettront de prendre des décisions d'ingénierie fondées.**
> 
> **Le lot L6 (« Performance & Exploitation ») doit donc prioritairement rendre le système observable et mesurable, dans le respect strict de son périmètre normatif existant.**
> 
> **Aucune recommandation formulée dans le rapport Q5-C ne vaut autorisation d'implémentation.**

---

## 2. Signification et Portée du Statut Final

Le statut officiel enregistré est :
```
L5 — CLOSED WITH EXPLICIT RESERVATIONS — GLOBAL CERTIFICATION PASS, Q5-C AUDIT ACCEPTED, OPTIMIZATIONS DEFERRED UNTIL AFTER L6
```

Ce statut emporte les significations normatives suivantes :
- **L5 est techniquement qualifié :** La complétude des contrats, des parcours HTTP, des invariants de domaine et de concurrence est prouvée sur la suite complète de certification.
- **L5 est formellement clos par décision humaine :** Le lot L5 quitte l'état actif et entre dans l'état clos, mettant fin aux incréments de développement de L5.
- **Les réserves sont conservées et attribuées :** Les risques de performance, de dimensionnement et de dette historique sont intégralement tracés et assignés aux étapes compétentes.
- **Les travaux futurs restent ouverts :** Les chantiers d'observabilité, de mesure, de maintenance, de convergence et de distribution demeurent planifiés dans leur séquence programmatique.
- **La clôture ne constitue pas une homologation de production :** Le système n'est ni homologué pour une exploitation en production multi-nœuds, ni validé sous SLA/SLO réels.
- **Aucune restructuration n'est approuvée par cette décision :** Aucun chantier d'effondrement de modules, de modification de sémaphores ou de découpage de workers n'est autorisé.

---

## 3. Preuves de Certification Acceptées (Synthèse Q5-B6)

Les preuves physiques certifiant le lot L5 au SHA `2890d88f6fa94ff2262d08a2809f48512ce27c9d` sont archivées sous [docs/testing/evidence/L5-Q5B6/](../testing/evidence/L5-Q5B6/README.md) et consignées dans le rapport officiel [L5_Q5B6_Final_Certification_Execution_Report.md](L5_Q5B6_Final_Certification_Execution_Report.md) :

| Dimension de preuve | Constat de certification | Statut officiel |
|---|---|:---:|
| **Réacteur Maven global** | 61/61 modules Maven franchis avec succès (`BUILD SUCCESS`) | **SUCCESS** |
| **Tests JUnit / Surefire** | **2 344 tests exécutés, 2 344 réussis**, 0 failure, 0 error, 0 skip | **100% PASS** |
| **Gate Architecture globale** | **170 / 170 tests PASS** (`pocoma-architecture-tests`) | **PASS** |
| **Gate Base de données** | Schémas V1–V10 validés sur PostgreSQL 16.6, 0 erreur Flyway | **PASS** |
| **Gate Surface HTTP** | 10 routes du monolithe validées, contrats Web-API conformes | **PASS** |
| **Bruno B1 (READ)** | 32/32 scénarios exécutés et réussis sur `V3ReadHttpFixture` (66 requêtes) | **PASS** |
| **Bruno B2 (Commandes / Admission)** | 24/24 scénarios exécutés et réussis sous `runtime-monolith` (468 requêtes) | **PASS** |
| **Bruno B3 (Link OIDC réel)** | 10/10 scénarios exécutés sous Keycloak 26.7.0 réel (70 requêtes) | **PASS** |
| **Bruno B4 (Invitations / Détachement)**| 4/4 scénarios exécutés et réussis sous `runtime-monolith` (24 requêtes) | **PASS** |
| **Suite consolidée Bruno B5** | **70 / 70 scénarios certifiés** (`bruno-b5-consolidated.json`) | **B5 COMPOSITE PASS** |
| **Budget d'exécution** | **4 984,58 s** (~1h 23 min 05 s) pour un budget plafond de 130 min | **CONFORME** |
| **Intégrité de production** | Zéro ligne productive, zéro fichier de configuration, zéro migration altéré | **PRÉSERVÉ** |

---

## 4. Statut et Réconciliation de l'Audit Indépendant Q5-C

Le rapport [L5_Q5C_Quality_Performance_and_Restructuring_Audit.md](L5_Q5C_Quality_Performance_and_Restructuring_Audit.md), publié au commit `f4f5d973`, est formellement approuvé et versé au dossier de clôture.

Ses conclusions majeures sont actées :
1. **Élucidation de l'écart d'Architecture (170 vs 178) :** L'analyse statique prouve de manière irréfutable que le module `pocoma-architecture-tests` contient exactement 170 méthodes annotées `@Test`. Les 8 occurrences supplémentaires annoncées historiquement provenaient d'un comptage textuel naïf incluant 4 `@Testcontainers`, 2 `@TestConfiguration` et 2 `@TestBean`. **Aucune régression de couverture n'a eu lieu ; 100% des tests d'architecture réels ont réussi.**
2. **Traçabilité du SHA qualifié :** La transition vers le commit `2890d88f` a été rigoureusement justifiée par la nécessité d'intégrer des correctifs d'oracles et de fixtures (notamment le lease de `V3InvitationFixture` et des exceptions de réflexion). Le code de production est demeuré strictement inviolé.
3. **Clarification du statut de clôture :** Le rapport Q5-B6 avait qualifié techniquement le lot (`CERTIFICATION_PASS`). Le présent acte Q5-D concrétise la clôture de gouvernance programmatique.

---

## 5. Inventaire Exhaustif des Réserves Conservées et Attribution

Aucune réserve n'est déclarée levée sans preuve tangible. Le tableau ci-dessous formalise l'état et l'attribution des réserves ouvertes à la clôture de L5 :

| Référence | Intitulé & Description | Origine | Statut formel | Lot responsable | Décision de gouvernance & Justification |
|---|---|:---:|:---:|:---:|---|
| **FIND-L4-01** | Quota de lecture bridé à $Q=2$ permis via sémaphore | L4 / Q5-B | **OPEN** | **L6** | Mesurer avant dimensionnement. Aucun dégel arbitraire à 16 permis sans profilage physique sous charge. |
| **FIND-L2-01** | Inflation mécanique du lease L2 à 180s/240s dans le Worker READ | L2 / L4 / Q5-B5 | **OPEN** | **L6** | Maintenu à 240 s pour garantir la coordination séquentielle des 7 participants. Observer les reprises après panne avant ajustement. |
| **FIND-L4-03** | Recalcul intégral des soldes en $O(N)$ sur grands Pots | L4 / SPEC-04 / SPEC-09 | **RISK RETAINED** | **L6** | Risque algorithmique conservé pour préserver la pureté sans état. Caractériser physiquement les volumes lors du test Grand Pot (PERF-01). |
| **FIND-L1-01** | Coût SQL de la requête multi-jointures `openComponent` | L1-F2 / L4 | **OPEN** | **L6** | Différé jusqu'à disponibilité de mesures pertinentes. À profiler sous `EXPLAIN ANALYZE` lors des campagnes de charge L6. |
| **RES-ENV-01** | Limites de qualification environnementale et dimensionnement distribué | L4 / L5 | **OPEN** | **L6 / L8** | Les tests locaux sur conteneurs Docker/PostgreSQL ne préjugent pas du comportement sous réseau distribué et latences réelles. |
| **FIND-TRANS-01**| Hyper-fragmentation en 61 modules Maven et coexistence du DDL V2 | L4 / Q5-A / Master Plan | **TRANSFERRED** | **L7** | Prévu contractuellement au Master Plan L7. Zéro module créé en L5. Rationalisation différée à la convergence finale. |
| **FIND-L2-02** | Contamination du moteur L2 par des branches WRITE/READ dures | L2 / L4 | **TRANSFERRED** | **L7** | Moteur L2 stable et qualifié. La factorisation et le découplage des stratégies d'autorité relèvent du chantier de convergence L7. |
| **AUD-Q5C-07** | Couplage des résultats rapides et projections lourdes dans le Worker READ | Q5-C | **DEFERRED** | **Post-L6 / L7** | Piste de restructuration architecturale différée. À réévaluer à la lumière des mesures d'observabilité de L6. |
| **AUD-Q5C-08** | Coexistence passive du DDL V2 (Flyway V1–V31) et entités legacy | Q5-C | **DEFERRED** | **L7** | À instruire exclusivement dans le cadre de la transition et de la purge finale L7. |
| **Réserves historiques L1–L4** | Réserves de volume, durées de rétention, mémoires bornées, poisoning tardif | L1, L2, L3, L4 | **RETAINED** | **L6 / L7** | Les réserves non expressément soldées dans les actes L1–L4 restent actives avec leurs attributions d'origine. |

### Réserves expressément soldées avec preuve vérifiée :
- **`FIND-L4-02` / `AUD-FINAL-I8-01` (Prévalidation pure en amont du quota READ) :** **RÉSOLU EN L5** (validé dans `V3ReadFacadeComposition:179-202`).
- **`FIND-L3-01` (Qualification du consentement et OIDC réel) :** **RÉSOLU EN L5/I3 & Q5-B6** (70 requêtes Bruno B3 réussies sur Keycloak 26.7.0 réel).
- **`R-Q5B5-01` (Atteinte de la Gate Architecture globale) :** **RÉSOLU EN Q5-B6** (170/170 tests PASS).
- **`R-Q5B5-02` (Complétude de la suite Bruno B5) :** **RÉSOLU EN Q5-B6** (70/70 scénarios certifiés B5).

---

## 6. Traitement des Recommandations Q5-C et Arbitrages Enregistrés

Les arbitrages humains formels suivants sont enregistrés à l'encontre des propositions du rapport Q5-C :

| Proposition d'audit Q5-C | Arbitrage humain formel | Motif & Principe directeur |
|---|:---:|---|
| **Dégel du quota READ ($Q=2 \rightarrow 16$)** (`CH-01`) | **NON APPROUVÉ** | Mesurer avant dimensionnement. Le sémaphore à 2 permis protège le pool Hikari et les ressources CPU dans l'état actuel. Un dégel sans profilage physique préalable risquerait de déstabiliser les workers de projection. |
| **Réduction immédiate du lease READ (240 s $\rightarrow$ 60 s)** (`CH-07`) | **MAINTENU (NON APPROUVÉ)** | Le lease de 240 s garantit la stabilité mathématique du renouvellement séquentiel des 35 entités (7 participants $\times$ 4 partitions + 7). Observer les comportements réels de reprise sur panne sous L6 avant tout ajustement. |
| **Optimisation incrémentale des balances $O(N)$** (`CH-06`) | **RISQUE CONSERVÉ** | La pureté sans état du recalcul des soldes à $V$ est un choix de robustesse architecturale. Caractériser les volumes et profiler le comportement sous charge L6 avant toute remise en cause. |
| **Découplage des résultats rapides et projections** (`CH-04`) | **DIFFÉRÉ** | Piste de restructuration différée après L6. L'isolation des résultats rapides ne doit pas complexifier les runtimes avant que le besoin ne soit démontré par les mesures de latence L6. |
| **Sanctuarisation de threads IdentityRequests** (`CH-05`) | **À ÉVALUER SUR MESURES** | Piste intéressante mais à évaluer uniquement sur la base de métriques réelles de contention sous charge. |
| **Suppression des 6 modules workers dits « zombies »** (`CH-03`) | **NON APPROUVÉE** | Non approuvée sans un examen approfondi des frontières de composition et des impacts sur les builds et packaging existants. |
| **Effondrement de 54 modules Maven en 7 modules** (`CH-08`) | **NON APPROUVÉE** | Non approuvée. La modularité actuelle répond à une logique de séparation des responsabilités. Le nombre de modules Maven ne saurait être considéré, en soi, comme un défaut architectural. |
| **Purge immédiate du DDL V2 (Flyway V1–V31)** (`CH-09`) | **DIFFÉRÉE À L7** | À instruire exclusivement dans le cadre du chantier de convergence et de démolition du socle V2 au lot L7. |
| **Optimisations SQL sur `openComponent`** (`AUD-Q5C-05`) | **DIFFÉRÉES** | Différées jusqu'à ce que des profils de requêtes en conditions de charge révèlent un coût réel justifiant une retouche. |

### Principe architectural fondamental à préserver :
> **La modularité V3 doit permettre de composer des capacités et des runtimes à partir de briques réutilisables, sans modifier profondément les moteurs existants.**
> 
> **Le nombre de modules Maven n'est pas, à lui seul, un indicateur de mauvaise architecture.**

---

## 7. Report des Restructurations et Orientation Prioritaire de L6

Conformément à la décision stratégique formulée :
1. **Report strict des chantiers structurels :** Aucun des chantiers de restructuration `CH-01` à `CH-09` n'est ouvert par le présent acte.
2. **Priorité absolue de L6 : Observabilité et Métrologie :**
   - Le lot L6 a pour mission fondamentale de rendre le socle V3 entièrement observable et mesurable.
   - Les travaux de L6 doivent implémenter et qualifier l'instrumentation [SPEC-08](SPEC-08_Observability.md), les protocoles de mesures [SPEC-09](SPEC-09_Performance.md) et les exigences de reconstruction et résilience [SPEC-10 §§10–12](SPEC-10_Test_Certification.md).
   - C'est uniquement sur la base de données quantitatives fiables (débits réels, latences p95/p99, taux d'utilisation des pools Hikari, temps de calcul sous grands Pots, latences de reprise de lease) que des ajustements de dimensionnement ou des refontes structurelles pourront être instruits.

---

## 8. Conditions d'Ouverture du Lot L6

Le lot L6 (« Performance & Exploitation ») est autorisé à entrer dans sa phase préparatoire :

- **Statut de programme pour L6 :** **`DESIGN PENDING` — Éligible au cadrage**.
- **Conditions préalables à toute implémentation :**
  1. Établissement et approbation formelle d'un plan de qualification et de mesure L6 (`L6_Design_and_Verification_Plan.md`), définissant les bancs de tests, les protocoles de charge (k6), les métriques Micrometer/Prometheus et les critères d'acceptation.
  2. Ratification préalable des protocoles de mesures par le responsable du programme.
  3. Décision explicite d'autorisation d'implémentation (**GO L6**).
  4. Respect strict de l'interdiction de modifications d'architecture ou de découpage modulaire tant que les mesures de référence n'ont pas été établies.

---

## 9. Limites Formelles de la Clôture

La présente clôture formelle du lot L5 :
1. **Établit l'achèvement contractuel de L5 :** Elle clôt les engagements pris dans le plan d'entrée L5 et les incréments I1 à I4.
2. **Ne constitue pas une homologation pour la production :**
   - Elle ne valide pas la tenue sous des charges distribuées ou des latences réseau multi-centres.
   - Elle ne garantit pas de SLA/SLO de production.
   - Elle n'autorise pas le déploiement en environnement de production.
3. **Ne dispense pas des étapes ultérieures :** La certification globale du système V3 reste subordonnée à la réalisation et à la qualification complète des lots L6, L7 et L8 selon les termes du Master Plan.

---

## 10. Déclarations Normatives et Vérifications de Clôture

Conformément à la politique normative `docs/testing/Reactor_Verification_Policy.md` et aux consignes `AGENTS.md` :

- **Nature de la mission :** `DOCUMENTARY CLOSURE — NO IMPLEMENTATION — NO TEST REPLAY`.
- **Tranches de vérification déclarées :** **`NONE`** (strictement documentaire sous `docs/`).
- **Commandes Maven exécutées :** **`AUCUNE`** (`NONE`).
- **Franchissement inattendu de tranche :** **`AUCUN`** (`NONE`).
- **Gate globale requise :** **`NON`** (aucune modification de code sous `app/`).
- **Full reactor exécuté :** **`NON`** (la certification Q5-B6 a déjà fourni la preuve irréfutable du réacteur complet sur le SHA `2890d88f`).
- **Fichiers productifs ou tests modifiés :** **`0`**.
- **Contrôles Git effectués :** Validation du worktree propre, contrôle d'intégrité `git diff --check`, vérification des références distantes `origin/feat/v3-architecture`.

---

**Verdict Terminal de la Décision Humaine :**
```
================================================================================
POCOMA V3 — DÉCISION FORMELLE DE CLÔTURE L5/Q5-D : L5_CLOSED_WITH_EXPLICIT_RESERVATIONS
Décision : GO_L5_Q5D_FORMAL_CLOSURE_WITH_EXPLICIT_RESERVATIONS
SHA certifié : 2890d88f6fa94ff2262d08a2809f48512ce27c9d
Certification Q5-B6 : 61/61 SUCCESS, 2 344 tests PASS, 170 Arch PASS, B5 70/70 PASS
Audit Q5-C : APPROUVÉ ET VERSÉ AU DOSSIER (Écarts documentaires réconciliés)
Restructurations : TOUTES DIFFÉRÉES APRÈS L6 (Mesures avant restructuration)
Lot L5 : FORMELLEMENT CLOS AVEC RÉSERVES EXPLICITES
Lot L6 : ÉLIGIBLE AU CADRAGE (Priorité Observabilité et Mesures)
================================================================================
```
