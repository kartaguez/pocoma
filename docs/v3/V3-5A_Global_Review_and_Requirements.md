# Pocoma V3-5A — Revue globale et matrice des exigences

**Statut : PREMIÈRE CONSOLIDATION — EN REVUE ; cadrage uniquement, aucun code ni preuve V3.**
**Date :** 2026-10-23. **Autorité de progression :** [V3_Canon_and_Roadmap.md](V3_Canon_and_Roadmap.md).
**Sources normatives examinées :** [Pot V3-1](V3-1_Domain_and_Versioning.md), [Identity V3-1](V3-1_Identity_Domain_and_Versioning.md), [V3-2](V3-2_Events_Requests_and_Consumables.md), [V3-3](V3-3_Read_and_Projections.md), [V3-4](V3-4_Consumption_and_Discovery.md).

## 1. Périmètre et statut des preuves

V3-5A inventorie les parcours et exigences **avant** V3-5B (observabilité), V3-5C (budgets de performance) et V3-5D (spécification des tests). Les désignations BRUNO, k6 et JUnit/PostgreSQL indiquent des **supports de preuve prévus**, pas des tests existants ou exécutés. Aucun benchmark V3, DDL, runtime ou certification n'est revendiqué.

Définition de statut : **ACQUIS** = arbitrage conceptuel documenté, non prouvé ; **OUVERT** = arbitrage ou détail structurant restant à clore ; **À PROUVER** = exigence décidée dont la démonstration relève de V3-6/V3-7. Ne jamais confondre ces statuts.

## 2. Matrice unique des exigences

| ID | Exigence contractuelle | Source | Preuve envisagée | Statut |
|---|---|---|---|---|
| DOM-01 | Pot@V reconstruit à V exacte, indépendamment de V−1 ; sous-versions locales et globale cohérentes | V3-1 Pot | JUnit/PostgreSQL ; Bruno lecture | ACQUIS / À PROUVER |
| DOM-02 | Commandes sans effet, multi-cibles, suppressions et dépendances observedVersion respectent obsolescence métier | V3-1 Pot | Bruno ; JUnit/PostgreSQL | ACQUIS / À PROUVER |
| DOM-03 | Calcul de soldes rationnels exacts, poids par défaut à V ; balance complète par Pot/V | V3-1, V3-3 | JUnit ; Bruno | ACQUIS / À PROUVER |
| ID-01 | Registration/Link/Unlink conservent unicité EUI, identité de la cible, tokens renouvelés, et aucune mutation partielle | V3-1 Identity | Bruno ; JUnit/PostgreSQL | ACQUIS / À PROUVER |
| ID-02 | Link exige une preuve fiable de contrôle/consentement de l'EUI cible ; entitlements externes vérifiés selon contrat explicite | V3-1 Identity | Tests sécurité/Bruno ; JUnit | **ACQUIS / À PROUVER** |
| EVT-01 | Quatre séquences globales denses, publication transactionnelle sans trou ni réordonnancement de commits | V3-2 | JUnit/PostgreSQL concurrent | ACQUIS / À PROUVER |
| EVT-02 | Mutation, événement, résultat terminal et complétion atomiques ; aucune mutation événementielle sans requestId | V3-2 | JUnit/PostgreSQL ; Bruno | ACQUIS / À PROUVER |
| EVT-03 | Six familles sources externes/événementielles + deux familles burnables techniques READ ; tâches déterministes et uniques | V3-2, V3-4 | JUnit/PostgreSQL | ACQUIS / À PROUVER |
| CON-01 | Discovery enrôle les non-burnables avec checkpoint global inspecté par partition, et les burnables PENDING sans checkpoint | V3-4 | JUnit/PostgreSQL crash/fenêtres | ACQUIS / À PROUVER |
| CON-02 | Checkpoint avancé atomiquement avec tous ses slots ; absence de slot ne vaut complétion que sous ses conditions de preuve | V3-4 | JUnit/PostgreSQL crash/restart | ACQUIS / À PROUVER |
| CON-03 | Propriété de partition autorise Discovery/acquisition ; claim individuel protège le commit, même après transfert de partition | V3-4 | JUnit/PostgreSQL interleavings | ACQUIS / À PROUVER |
| CON-04 | Un claim ne finalise qu'une fois ; validité au verrou final, transaction atomique, reprise sans effet double après commit incertain | V3-4 | JUnit/PostgreSQL fault injection | ACQUIS / À PROUVER |
| CON-05 | Une acquisition = une tentative complète ; échec technique → rollback, cession conditionnelle et retryAfter, sans retry immédiat | V3-4 | JUnit/PostgreSQL ; k6 contention | ACQUIS / À PROUVER |
| CON-06 | Slots, checkpoints, propriété de partition et effets résident dans les frontières transactionnelles physiques correctes, sans 2PC | V3-4 | Tests d'architecture ; JUnit/PostgreSQL | ACQUIS / À PROUVER |
| PRJ-01 | CurrentPot et SubobjectVersion avancent indépendamment, sans perdre les versions historiques ni mélanger les références | V3-3 | JUnit/PostgreSQL ; Bruno | ACQUIS / À PROUVER |
| PRJ-02 | Balance(P,V) immuable et idempotente ; versions hors ordre convergentes | V3-3 | JUnit/PostgreSQL | ACQUIS / À PROUVER |
| PRJ-03 | PotVisibilityState sérialise le différentiel entre utilisateurs actuellement visibles et visibles à V ; versions hors ordre sûres | V3-3, V3-4 | JUnit/PostgreSQL interleavings | ACQUIS / À PROUVER |
| PRJ-04 | Deux tâches produites atomiquement ; finalisation de chaque tâche, de ses effets READ et du slot atomique | V3-2, V3-4 | JUnit/PostgreSQL crash | ACQUIS / À PROUVER |
| READ-01 | Lecture complète ou temporairement indisponible, minVersion et preuve de présence des composants nécessaires ; pas de cohérence globale fictive | V3-3 | Bruno ; JUnit | ACQUIS / À PROUVER |
| SEC-01 | bindingToken + authentification, CurrentBinding actif → pocomaUserId ; droit sur Shareholder de Pot@V ; liste Pot non autoritative | V3-1, V3-3, V3-4 | Bruno sécurité ; JUnit | ACQUIS / À PROUVER |
| SEC-02 | Retard de révocation CurrentBinding accepté explicitement ; aucune promesse de révocation WRITE immédiatement effective dans READ | V3-3, V3-4 | Bruno propagation | ACQUIS / À PROUVER |
| REC-01 | READ totalement réinitialisé de façon cohérente : mêmes consumptionIds possibles ; replay indépendant = nouvelle Consumption | V3-4 | JUnit/PostgreSQL E2E | ACQUIS / À PROUVER |
| OPS-01 | Absence de famine durable admissible, backlog et reprise des claims ; sans surcharge catastrophique au polling | V3-4 | k6 ; métriques serveur ; PostgreSQL | **À PROUVER**, budgets V3-5B/C |
| PERF-01 | Délais d'attente Discovery/Engine, exécution et propagation, bout en bout, débit et résorption mesurables | V3-5B/C à définir | k6 + instrumentation + SQL | **OUVERT (V3-5B/C)** |

## 3. Parcours représentatifs

1. **Commande Pot :** admission durable → Discovery/claim → version de travail rechargée → décision métier → mutation/événement/résultat/completion atomiques → matérialisations CurrentPot, Balance, visibilité et résultat indépendantes ; collision CAS = nouvelle acquisition, pas rejet automatique.
2. **Identité :** Registration / Link / Unlink, initiateur authentifié, preuve EUI cible quand exigée, validation sur WRITE, publication d'événements et complétion → CurrentBinding et résultat READ indépendants.
3. **Projection :** POT_EVENT → deux Consumptions indépendantes ; préparation atomique des deux tâches READ, puis matérialisations propres ; rejet des versions déjà dépassées.
4. **Lecture :** présentation bindingToken par sujet authentifié → CurrentBinding → User → Shareholder lié dans Pot@V ; minVersion et composants nécessaires ; navigation non autoritative.
5. **Crashes/reconstruction :** avant/après enrôlement, acquisition, rollback, commit incertain, changement de propriétaire, reset READ à blanc, rejeu indépendant et disparition de slot non réinscriptible.

## 4. Revue de cohérence : acquis et points ouverts

**Pas de contradiction de principe relevée** entre les six documents examinés sur les responsabilités Pot/Identity, les huit familles, la séparation WRITE/READ, les deux tâches READ, les claims et la reprise. Les points ci-dessous sont des limites réelles à qualifier, pas des anomalies runtime constatées.

- **BLOC-01 — Link EUI cible (décision de sécurité) :** définir preuve de contrôle/consentement de l'identité cible et sa liaison sûre à la demande durable à l'admission, avant V3-CADRE.
- **BLOC-02 — Entitlements externes (décision de contrat) :** préciser lesquels sont revérifiés à l'exécution et avec quelle fraîcheur quand la demande attend en file, sans introduire de domaine Entitlements Pocoma.
- **BLOC-03 — Autorisation READ (précision) :** le token ne remplace pas l'authentification de l'identité émettrice ; préciser comment l'authentification est liée à la ligne CurrentBinding résolue. La propagation retardée d'un Unlink est explicitement acceptée.
- **TECH-01 — Vérification PostgreSQL :** verrou final et horloge d'expiration, propriété de partition, publications denses, absence de mort-vivant et atomicités interleavées sont à spécifier précisément et à prouver lors de la réalisation ; ne pas inventer de garantie sur un SQL encore non écrit.
- **TECH-02 — Visibilité :** création initiale de PotVisibilityState et toutes les voies d'écriture doivent utiliser la même protection transactionnelle ; pas de CAS individuel seul.
- **TECH-03 — Restauration READ :** définir l'enveloppe de reset atomique/coordonné et la barrière de remise en service afin de ne pas présenter un READ partiellement reconstruit comme complet.
- **QUAL-01 — Équité, performance et observabilité :** détailler budgets, instrumentation, profils de charge et preuves dans V3-5B/C/D, sans imposer d'algorithme SQL ou de seuil non justifié dans V3-5A.

## 5. Livrable et gate V3-5A

La matrice ci-dessus est le **référentiel initial** des exigences V3 ; elle sera raffinée avec les budgets V3-5B/C et les cas vérifiables V3-5D. V3-5A ne requiert pas la création immédiate des tests exécutables, mais requiert la résolution explicite des décisions BLOC-01/02/03 ou leur qualification comme blocages empêchant le jalon V3-CADRE. Les détails de preuve TECH peuvent être reportés à V3-6/7 si leur contrat et leur vérifiabilité sont fermes.

**Verdict : V3-5A REVUE CONCEPTUELLE CLÔTURÉE pour les arbitrages BLOC-01/02/03 ; exigences encore À PROUVER, pas de V3-CADRE, pas de code.**


## 6. Arbitrages de sécurité validés — 2026-10-23

- **BLOC-01 RÉSOLU — Link :** parcours d'admission immédiat réunissant authentification de E1 et de E2, et consentement explicite de E2 lié à la demande précise. Une seule IdentityRequest durable est admise après vérification des preuves. Aucun workflow de consentement différé, aucune entité Consent. Un retry conserve la preuve initiale ; l'exécution contrôle l'autorité courante Identity, sans nouvel accord E2.
- **BLOC-02 RÉSOLU — Entitlements :** vérification des droits/attestations externes à l'admission, non répétée à l'exécution. Révocation externe postérieure non rétroactive pour la demande admise ; les rattachements Pocoma restent vérifiés sur WRITE à l'exécution.
- **BLOC-03 RÉSOLU — bindingToken :** chaque requête possède une identité externe authentifiée `(issuer,subject)` ; le bindingToken présenté doit correspondre **à cette même EUI**. Les lectures résolvent le token dans CurrentBinding (retard de révocation accepté), les mutations vérifient WRITE à l'exécution. Le token n'est jamais une authentification et le pocomaUserId est dérivé du rattachement, jamais choisi arbitrairement.

**Les questions d'implémentation des preuves et de vérification cryptographique restent à spécifier pour les tests ; leur choix technique ne doit pas contredire ces contrats.**
