# Pocoma SPEC-07 — Exigences transversales (nom physique V3-5A conservé provisoirement)

**Statut : consolidation conceptuelle AUD-V3-012, non certifiée.**

## 1. Portée et statut

Ce document définit les exigences transversales sans dupliquer leurs règles normatives : SPEC-01/02 Domaine, SPEC-03 événements, SPEC-04 READ, SPEC-05 Consumption et SPEC-06 Supras (noms physiques historiques jusqu'à migration ARB-08). Les supports Bruno, JUnit/PostgreSQL et k6 sont des **preuves à réaliser**, non exécutées. `ACQUIS` qualifie un contrat conceptuellement décidé ; `À PROUVER` sa certification V3 manquante.

## 2. Catalogue d'exigences actives

Les 23 identifiants initiaux encore valides sont conservés. `PRJ-04` est **retiré et non réutilisable** : ses garanties utiles relèvent de CON-02/04/06, PRJ-02/03 et EVT-03.

| ID | Exigence contractuelle | Source historique | Preuve prévue | Statut |
|---|---|---|---|---|
| DOM-01 | Pot@V reconstruit à V exacte, indépendamment de V−1 ; sous-versions locales et globale cohérentes | V3-1 Pot | JUnit/PostgreSQL ; Bruno lecture | ACQUIS / À PROUVER |
| DOM-02 | Commandes sans effet, multi-cibles, suppressions et dépendances observedVersion respectent obsolescence métier | V3-1 Pot | Bruno ; JUnit/PostgreSQL | ACQUIS / À PROUVER |
| DOM-03 | Calcul de soldes rationnels exacts, poids par défaut à V ; balance complète par Pot/V | V3-1, V3-3 | JUnit ; Bruno | ACQUIS / À PROUVER |
| ID-01 | Registration/Link/Unlink conservent unicité EUI, identité de la cible, tokens renouvelés, et aucune mutation partielle | V3-1 Identity | Bruno ; JUnit/PostgreSQL | ACQUIS / À PROUVER |
| ID-02 | Link headless avec consentement externe E2 explicite, transactionnel, audience, durée et anti-rejeu | V3-1 Identity | Tests sécurité/Bruno ; JUnit | **ACQUIS / À PROUVER** |
| EVT-01 | Quatre séquences globales denses, publication transactionnelle sans trou ni réordonnancement de commits | V3-2 | JUnit/PostgreSQL concurrent | ACQUIS / À PROUVER |
| EVT-02 | Mutation, événement, résultat terminal et complétion atomiques ; aucune mutation événementielle sans requestId | V3-2 | JUnit/PostgreSQL ; Bruno | ACQUIS / À PROUVER |
| EVT-03 | Six familles de sources, sans tâches READ ; trois Consumptions autonomes et directes de POT_EVENT | V3-2, V3-4 | JUnit/PostgreSQL | ACQUIS / À PROUVER |
| CON-01 | Discovery enrôle les non-burnables avec checkpoint global inspecté par partition, et les burnables PENDING sans checkpoint | V3-4 | JUnit/PostgreSQL crash/fenêtres | ACQUIS / À PROUVER |
| CON-02 | Checkpoint avancé atomiquement avec tous ses slots ; absence de slot ne vaut complétion que sous ses conditions de preuve | V3-4 | JUnit/PostgreSQL crash/restart | ACQUIS / À PROUVER |
| CON-03 | Propriété de partition autorise Discovery/acquisition ; claim individuel protège le commit, même après transfert de partition | V3-4 | JUnit/PostgreSQL interleavings | ACQUIS / À PROUVER |
| CON-04 | Un claim ne finalise qu'une fois ; validité au verrou final, transaction atomique, reprise sans effet double après commit incertain | V3-4 | JUnit/PostgreSQL fault injection | ACQUIS / À PROUVER |
| CON-05 | Une acquisition par tentative complète ; N échecs techniques durables constatés puis QUARANTINED, réactivation administrative explicite et indépendance des slots | V3-4 | JUnit/PostgreSQL ; k6 contention | ACQUIS / À PROUVER |
| CON-06 | Slots, checkpoints, propriété de partition et effets résident dans les frontières transactionnelles physiques correctes, sans 2PC | V3-4 | Tests d'architecture ; JUnit/PostgreSQL | ACQUIS / À PROUVER |
| PRJ-01 | PotVersion(P,V) immuable, sous-versions exactes, CurrentPot pointeur monotone, y compris version de suppression D | V3-3 | JUnit/PostgreSQL ; Bruno | ACQUIS / À PROUVER |
| PRJ-02 | Balance(P,V) immuable pour chaque V active ; traitement terminal réussi sans Balance(P,D) ; matérialisation hors ordre | V3-3 | JUnit/PostgreSQL | ACQUIS / À PROUVER |
| PRJ-03 | MaterializePotVisibility calcule directement WRITE@V, garde collective PotVisibilityState, navigation monotone, indépendance | V3-3, V3-4 | JUnit/PostgreSQL interleavings | ACQUIS / À PROUVER |
| READ-01 | Lecture Pot complète autorisée à V, aucune barrière de visibilité ; 404 absence/interdit/supprimé avant 503 de fraîcheur/dépendances | V3-3 | Bruno ; JUnit | ACQUIS / À PROUVER |
| SEC-01 | EUI authentifiée + bindingToken et CurrentBinding actif ; droits du créateur ou Shareholders à V ; historique non rétroactivement révoqué ; 404/503 après autorisation | V3-1, V3-3, V3-4 | Bruno sécurité ; JUnit | ACQUIS / À PROUVER |
| SEC-02 | Retard de révocation CurrentBinding accepté explicitement ; aucune promesse de révocation WRITE immédiatement effective dans READ | V3-3, V3-4 | Bruno propagation | ACQUIS / À PROUVER |
| REC-01 | Reconstruction READ en maintenance exclusive, reset coordonné projections/slots/checkpoints sans tâches | V3-4 | JUnit/PostgreSQL E2E | ACQUIS / À PROUVER |
| OPS-01 | Progression et équité malgré quarantaine exclue du softCap, sans dépendance entre slots | V3-4 | k6 ; métriques serveur ; PostgreSQL | **À PROUVER**, budgets V3-5B/C |
| PERF-01 | C-03 : première lecture active W≥V complète autorisée ; DeletePot oracle interne PotVersion(D), budgets inchangés | V3-5B/C à définir | k6 + instrumentation + SQL | **OUVERT (V3-5B/C)** |
| ADM-01 | Admission bornée, sans lecture métier WRITE, sans slot synchrone | SPEC-06/05 | Bruno ; tests d'admission | ACQUIS / À PROUVER |
| S-01 | Navigation CurrentUserPotList keyset, non autorisante | SPEC-06 | Bruno ; tests de pagination | ACQUIS / À PROUVER |
| S-02 | Manifeste complet et ordonné des références Expenses, détails par lots | SPEC-06/04 | Bruno ; tests de volumes | ACQUIS / À PROUVER |
| S-03 | Idempotence atomique admission par identité et intention canonique | SPEC-06 | JUnit/PostgreSQL ; Bruno | ACQUIS / À PROUVER |

## 3. Parcours transversaux

1. Command : admission durable sans slot → Discovery burnable → validation WRITE de l'EUI et du bindingToken → mutation/événement/complétion atomiques → quatre résultats READ indépendants (PotVersion, Balance, Visibility, CommandResult).
2. Identity : Registration ou Link/Unlink, consentement E2 vérifiable quand requis, état WRITE autoritatif → CurrentBinding et IdentityRequestResult indépendants ; E2 récupère exclusivement son propre token.
3. Projection : un même POT_EVENT(P,V) possède trois Consumptions autonomes, chacune capable d'avancer hors ordre et sans dépendance.
4. Lecture : CurrentBinding actif et droits à V → lecture PotVersion et projections nécessaires, navigation non autorisante, ordre anti-oracle 404/503 ; résultats autorisés par requesterEui uniquement.
5. Incidents : fenêtre sûre de Discovery, claim expiré sans échec compté, quarantaine N échecs, réactivation explicite et reconstruction READ exclusive.

## 4. Décisions transversales et réserves

BLOC-01 (Link), BLOC-02 (Entitlements externes à l'admission) et BLOC-03 (bindingToken et authentification) sont **résolus conceptuellement** dans SPEC-02/06. Aucun nouveau domaine Entitlements ni page Pocoma de consentement. Les points restant à qualifier sont physiques : acquisition PostgreSQL, verrou final, accès READ en retard, charge, bornes de protection, mesures et qualification de l'autorité de consentement.

## 5. Certification

La matrice normative exigences → scénarios → oracles figure **uniquement** dans SPEC-10 (ancien V3-5D). Ce catalogue n'est pas une seconde matrice de certification. C-01, C-02, C-03 et scénarios A10/A11 sont repris dans SPEC-10. Aucun test V3 exécuté et aucun statut PASS affirmé.
