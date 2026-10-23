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
