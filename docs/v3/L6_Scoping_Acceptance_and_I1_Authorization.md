# Pocoma V3 — L6 — Acceptation du cadrage et autorisation ciblée I1

**Date :** 6 novembre 2026  
**Dépôt :** `kartaguez/pocoma`  
**Branche :** `feat/v3-architecture`  
**SHA initial avant décision :** `8b462cd1f6f1ad90cffaed8482e3e158483c0236`  
**Décision humaine :** `GO_L6_SCOPING_ACCEPTED_AND_I1`  
**Verdict de cadrage accepté :** `L6_SCOPING_READY_FOR_HUMAN_REVIEW` → **ACCEPTED FOR I1**  
**Statut I1 :** **AUTHORIZED — IMPLEMENTATION NOT YET QUALIFIED**  
**Statut L6 :** **OPEN — I1 AUTHORIZED; I2/I3/G6 NOT AUTHORIZED**

## 1. Acte de décision

Le responsable du programme accepte le cadrage L6 consigné dans [L6_Design_and_Verification_Plan.md](L6_Design_and_Verification_Plan.md), publié au commit `8b462cd1f6f1ad90cffaed8482e3e158483c0236`, et autorise le passage à **I1 — Instrumentation & preuve des métriques**. Aucun arbitrage supplémentaire n'est nécessaire sur les principes fonctionnels d'I1. Le plan demeure une proposition sur ses commandes et durées de qualification, qui doivent être rendues exécutables et figées avant la première modification de production.

Cette autorisation ne vaut ni acceptation anticipée d'I1, ni certification globale, ni permission de modifier des comportements métier, de reconstruire READ (I2), de conduire les campagnes normatives de performance (I3), ou de réaliser les chantiers de restructuration Q5-C. Les protocoles de charge de SPEC-09 et la procédure REC-01 restent à ratifier aux jalons qui les concernent.

## 2. Mandat exécutable d'I1

1. Lire le Master Plan, le plan L6, SPEC-08 O-01, SPEC-09, SPEC-10 et la politique de vérification ; enregistrer HEAD local/distant, worktree et provenance avant édition.
2. Inventorier **sur la branche de travail** les points d'instrumentation réels, instruments V2 réutilisables, contrats d'observabilité, modules Spring/JDBC et graphes Maven, ainsi que les tests déjà disponibles. Ne pas supposer que les métriques existantes sont actives dans les runtimes V3.
3. Publier dans le rapport I1 une matrice **frontière O-01 → point de mesure → classe/port/adaptateur → type de métrique → oracle de test**, incluant T0…T6, résultats, chacune des branches READ, Identity, retries, rollback, quarantaine, disponibilités et bornes de mesure.
4. Compléter l'instrumentation **minimale**, en conservant hexagonalité, autorité WRITE/READ, sémantique transactionnelle, sécurité, idempotence, composition des runtimes et moteurs ; préférer adaptateurs aux modifications invasives. Éviter toute nouvelle infrastructure et tout module Maven sans nouveau mandat.
5. Prouver par tests ciblés que les métriques sont effectivement reliées aux bonnes frontières et ne trahissent pas rollback/retry/erreur ; éviter les labels haute cardinalité, les identifiants et tokens sensibles. Documenter coût de l'instrumentation, biais d'horloges et précision du polling externe.
6. Déclarer **avant code** les slices primaires et secondaires et l'inventaire des noms de tests réels, avec commandes exactes, budgets à partir du plan, critères d'arrêt, une invocation par gate, zéro retry automatique. Pas de full Maven ni Architecture globale dans l'inner loop ; réserver les gates majeures au jalon G6 conformément à la politique.
7. Archiver commande, SHA sous test, durées, XML, logs, échecs, preuves de cardinalité/qualité, et documenter les réserves. Audit contradictoire local avant clôture d'I1 ; aucune clôture automatique sur seule implémentation.

## 3. Limites et conditions

Les valeurs `<tests-vérifiés>` du plan L6 sont des **placeholders** et ne doivent jamais être exécutées littéralement. Les budgets I1 proposés de 4 et 8 minutes doivent être confrontés au graphe réel avant travaux ; tout dépassement appelle un arrêt et un diagnostic, non une boucle de relances. Si une instrumentation exige une migration, un changement de frontières architecturales ou une modification de comportement métier, **stopper l'extension**, publier un constat et solliciter une décision humaine.

**Réserves conservées sans correction en I1 :** quota READ Q=2, lease READ 240 s, Balance O(N), requête SQL `openComponent`, ressources partagées, coûts et biais des métriques, environnements non homologués, rationalisation et DDL V2 réservés à L7.

## 4. Publication et statut probatoire

Cet acte est une **décision de gouvernance documentaire**, sans exécution de Java, Maven, PostgreSQL, Bruno ou k6. Les résultats historiques Q5-B6 ne qualifient pas I1. Une future livraison I1 devra comporter un SHA technique testé et un rapport de preuves distinct.

**Décision finale :** `GO_L6_I1_ONLY`.  
**Prochain état attendu :** `L6/I1 IMPLEMENTED — TARGETED EVIDENCE — HUMAN REVIEW` après implémentation, preuves et audit.
