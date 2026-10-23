# Pocoma V3-5C — Hypothèses et objectifs de performance

**Statut : EN CONCEPTION — tailles de Pot validées ; débits, concurrence et budgets à arbitrer.**
**Date :** 2026-10-23. **Références :** [Canon](V3_Canon_and_Roadmap.md), [Exigences V3-5A](V3-5A_Global_Review_and_Requirements.md), [Observabilité V3-5B](V3-5B_Observability_Contract.md).

## 1. Tailles de Pots de référence — VALIDÉES

| Profil | Shareholders | Expenses |
|---|---:|---:|
| Petit | 5 | 20 |
| Moyen | 20 | 200 |
| Grand | 100 | 2 000 |

Ces profils sont des dimensions de jeux de données, ni des limites fonctionnelles ni des objectifs de temps.

## 2. Axes de charge distincts

- **Taille locale :** trois profils de Pot.
- **Volume global :** nombre de Pots, utilisateurs, ancienneté des événements et versions.
- **Débit :** admissions par seconde, répartition des mutations.
- **Contention :** commandes concurrentes sur le même Pot versus Pots indépendants.
- **Backlog :** stock initial, nouvelles arrivées pendant le rattrapage.
- **Capacité :** ressources PostgreSQL, Workers, partitions, pools, machine et réseau.

La capacité d'admission HTTP ne prouve pas la stabilité : il faut vérifier débit terminal et progression READ.

## 3. Scénarios et SLIs

Scénarios : **nominal**, **pointe**, **rattrapage**. Mesurer l'attente Discovery/Engine, la préparation, la validation transactionnelle, la matérialisation indépendante des résultats, CurrentPot, tâches, balances et visibilité, les parcours Identity, et les disponibilités bout en bout. Rapporter p50/p95/p99, backlog et âge, temps de résorption, ressources PostgreSQL/CPU/mémoire/WAL ; séparer succès, rejets, erreurs et reprises. Les hypothèses matérielles et tailles d'échantillon seront fixées avant les seuils.

**Aucun budget de latence ni débit n'est encore validé.** Les chiffres proposés pendant le cadrage sont des hypothèses de test, non des mesures V3.

## 4. Prochain arbitrage

Fixer des niveaux de débit de charge et la contention sur un même Pot, puis les volumes globaux et l'environnement de référence. Ne pas confondre objectifs SLO, taux offerts et résultats mesurés.

## 5. Arbitrages de charge et budget Balance — 2026-10-23

**Charges offertes validées :** nominal 1 commande/s, soutenu 10 commandes/s, pointe 50 commandes/s ; distinguer dispersion entre Pots et contention sur le même Pot. **Volumes globaux validés :** 100 Pots / 10 000 événements ; 10 000 Pots / 1 million d'événements ; 100 000 Pots / 10 millions d'événements.

**Configuration A (moteur) :** PostgreSQL sur machine/VM dédiée 4 vCPU / 8 Go RAM ; API et tous les runtimes Pocoma sur seconde machine/VM 4 vCPU / 8 Go RAM. Générateur k6 et monitoring sur ressources isolées si possible. Authentification externe simulée, sans contourner l'AuthZ Pocoma. **Configuration B (intégrée) :** même base avec Keycloak réel sur ressources isolées, campagnes ciblées sur AuthN, Identity et accès réels ; ne pas multiplier automatiquement toutes les campagnes.

**SLO nominal accepté comme premier objectif :** pour un Pot moyen (20 shareholders, 200 expenses), à 1 commande/s sur configuration A, **p95 ≤ 500 ms depuis l'admission de la commande jusqu'à PotBalance(P,V) disponible**. Ce temps inclut commande, Discovery et Engine, création des deux tâches, acquisition et matérialisation Balance. Ce SLO est **un objectif non mesuré**, non une preuve. Budget technique **candidat**, pas encore validé : p95 ≤ 50 ms pour calcul et écriture des balances, hors attente et propagation. Les percentiles des étapes ne s'additionnent pas pour déduire le percentile E2E. Définir clairement les campagnes (mutations appliquées, échantillon et sondage READ).

**Prochain arbitrage :** budgets E2E Pot complet et Identity binding utilisable ; puis objectifs par phase et charges plus fortes. Ne pas étendre automatiquement le SLO de 500 ms aux Pots grands ou aux autres profils.

**SLO nominal complémentaire VALIDÉ :** p95 ≤ 500 ms depuis l'admission de la commande appliquée jusqu'à la matérialisation de l'ensemble des projections de la version cible du Pot moyen : CurrentPot et sous-objets nécessaires, PotBalance(P,V) exacte, PotVisibilityState(P) ayant atteint au moins V. Les branches sont indépendantes, aucune somme de p95 par étape. Si CurrentPot a déjà avancé au-delà de V, la preuve de progression ne prétend pas offrir une API historique V via la projection courante. Même charge et configuration A que le SLO Balance. Pas d'extension implicite aux grands Pots.

**SLO Identity nominal VALIDÉ :** p95 ≤ 500 ms entre admission durable d'une Registration ou d'un Link et première lecture READ réellement autorisée par le nouveau bindingToken, avec identité authentifiée correspondante et Pot cible dont les projections sont déjà disponibles. Mesure E2E côté client, avec résolution via CurrentBinding ; le test isole le délai Identity du délai de matérialisation Pot. Définir la précision du sondage et les populations de mesures en V3-5D. Objectif non mesuré, à prouver.
