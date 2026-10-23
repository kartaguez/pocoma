# Pocoma V3-5D — Plan des preuves et de qualification

**Statut : OUVERT — conception des campagnes, aucun test V3 exécuté.**
**Date :** 2026-10-23. **Références :** [Canon](V3_Canon_and_Roadmap.md), [V3-5A — exigences](V3-5A_Global_Review_and_Requirements.md), [V3-5B — observabilité](V3-5B_Observability_Contract.md), [V3-5C — performance](V3-5C_Performance_Objectives.md).

## 1. Règles

- Planifier et documenter maintenant les preuves ; **ne pas coder de runtime/DDL V3 avant V3-CADRE validé**.
- Une exigence reçoit un oracle vérifiable, un environnement, des cas positifs/négatifs et une preuve à collecter. Reprendre les identifiants V3-5A.
- Distinguer réussite fonctionnelle, correction concurrente, tenue en charge, qualité de la mesure et opérabilité.
- Les anciennes suites servent de catalogue de propriétés, **pas de preuve V3**. Test survivant : conserver ; contrat remplacé : remplacer ; propriété retirée : suppression documentée.

## 2. Répartition initiale des preuves

| Axe | Outil prévu | Propriété / oracle |
|---|---|---|
| Admission Command et Identity | Bruno + JUnit E2E | AuthN/EUI, consentement Link E2 authentifié à l'admission, attestation externe à l'admission ; décisions WRITE, résultats durables |
| Domaine versions et historiques | JUnit + PostgreSQL | Sous-objets/version globale V, observedVersion, balances rationnelles exactes pour chaque V, lectures historiques |
| Événements et Consumption | JUnit + PostgreSQL | Publication dense/atomique ; slot et checkpoint Discovery ; claim/fencing ; rollback, crash, incertitude de commit, handover, redécouverte |
| READ et sécurité | Bruno + JUnit PostgreSQL | Matérialisations indépendantes, deux tasks créées ensemble, CurrentPot, Balance, visibilité, CurrentBinding, token associé à EUI authentifiée, minVersion, reset/replay |
| Intégration Keycloak réelle (configuration B) | Bruno + k6 ciblé | OIDC, parcours authentifiés, Registration/Link/Unlink jusqu'au binding utilisable, sans confondre coût d'émission et vérification de jeton |
| Performance configuration A | k6 + observabilité + PostgreSQL | Profil Pot moyen : p95 ≤ 500 ms à 1/s, ≤ 1 s à 10/s, ≤ 3 s à 50/s ; grand Pot : p95 ≤ 2 s nominal ; Identity : p95 ≤ 500 ms |
| Rattrapage | k6 + JUnit de contrôle + mesures serveur | 10 000 commandes initiales et toutes leurs matérialisations, arrivée 1 nouvelle commande/s, rattrapage ≤ 5 min |
| Contention sur Pot unique | JUnit concurrent PostgreSQL + k6 ciblé | Aucun effet perdu/double, ni commit partiel, CAS/retry corrects, convergence et résorption ; pas de SLO chiffré |
| Validité de l'observabilité | Assertions test et mesures corrélées | Placement réel des timers, issue des tentatives, rollback/retry, latence READ visible, cardinalité maîtrisée et histogrammes exploitables |

## 3. Précisions obligatoires avant gate

1. Construire la **matrice exhaustive identifiant V3-5A → cas → oracle → environnement → type de preuve → étape d'exécution V3-6/V3-7**.
2. Fixer échantillons, durées, échauffement, distribution des opérations, paramètres de pool/partition/Workers, isolation des sondes et reproductibilité des jeux de données.
3. Définir les **bornes de mesure côté client** (premier résultat READ prouvant la disponibilité, cadence de sondage, timeout, coût des sondes, clock skew), éviter de confondre p95 observé et moment exact du commit.
4. Prouver la **version cible** des projections immuables, et la progression au moins V des projections courantes, y compris si V+1 survient avant l'observation ; ne pas traiter une version courante écrasée comme une version historique accessible.
5. Fixer les conditions de génération et vidange des 10 000 commandes et de leurs tâches dérivées, en évitant un faux succès obtenu par la seule vidange des Command slots.
6. Détailler les cas de sécurité (Identity E1/E2, révocation READ retardée acceptée, token ≠ AuthN) et les crash points transactionnels.
7. Définir un gate de fiabilité des métriques : chaque frontière instrumentée testée positivement et négativement ; pas seulement présence d'une série Prometheus.
8. Conserver le diagnostic des écarts performance, sans rendre les objectifs « réussis » en modifiant les budgets a posteriori.

## 4. Sortie de V3-5D

**Non atteinte.** Sortie requise : matrice exhaustive, protocoles reproductibles, oracles et critères de PASS/FAIL, revue croisée avec V3-1 à V3-5C ; seulement ensuite proposer le jalon global V3-CADRE. Aucun PASS V3-7 anticipé.
