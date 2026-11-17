# Pocoma V3 — L3/I2 — Décision d'acceptation

**Date :** 2026-10-30.
**Verdict : ACCEPTED WITH EXPLICIT RESERVATIONS.**
**Source :** arbitrage explicite du porteur du projet, dont le présent document finalise la publication. Dossier examiné : `8ea1068de36dec717ff26f84556f6377f2c94801` ; code testé : `49ae298d879a41b29d1caf14e7597dffb947713a`. Le [rapport I2](L3_I2_Execution_Report.md), le [reçu canonique](../testing/evidence/L3-I2/canonical-final.json) et le [feedback ciblé corrigé](../testing/evidence/L3-I2/corrected-feedback.json) conservent les résultats. Ils n'ont pas été réexécutés lors de cette publication. Le constat d'audit statique ci-dessous est rapporté par l'arbitrage ; aucun rapport détaillé distinct d'audit indépendant I2 n'est ajouté ni présenté comme une reproduction des tests.

## 1. Périmètre accepté

L'implémentation I2 de l'autorité Identity PostgreSQL est acceptée dans son périmètre technique borné.

Les **27 tests I2 PostgreSQL** et les **21 contrôles d'architecture ciblés** sont rapportés **PASS**. L'audit statique n'a identifié aucun défaut bloquant dans l'autorité JDBC, le générateur de tokens ou le schéma V6.

Cette acceptation humaine conserve les réserves de certification ; elle ne constitue pas une certification V3 ni une autorisation d'activation.

## 2. État des preuves et réserves explicites

| Preuve | État rapporté | Portée et limite |
|---|---|---|
| Harness I2 PostgreSQL | 27 tests PASS | Autorité Identity dans le périmètre du harness I2 |
| Contrôles d'architecture ciblés | 21 contrôles PASS | Contrôles ciblés uniquement |
| Audit statique JDBC, tokens et schéma V6 | Aucun défaut bloquant identifié | Analyse statique du périmètre I2 |
| Gate canonique COMMAND + Architecture | **FAIL sur `i5_r12`** | 1 822 tests / 1 failure / 0 error / 1 skip ; module Architecture SKIPPED dans cette gate |
| Qualification « I2 LOCAL BOUNDED PASS » | **NON PRONONCÉE** | L'acceptation avec réserves ne transforme pas la gate en PASS |

Aucun PASS technique canonique ou global I2 n'est revendiqué. Les résultats du harness I2 **ne certifient pas le participant `IdentityRequest` ni la composition runtime future**. Les 21 contrôles ciblés ne valent pas exécution du module Architecture dans la gate canonique. Les réserves de certification restent ouvertes.

## 3. Autorisation de progression

**GO L3/I3 après publication documentaire du présent arbitrage**, sans modification des mécanismes L1/L2 et sans dispense des vérifications applicables à I3.

Cet arbitrage supplante uniquement l'arrêt procédural « aucun GO I3 / audit avant décision I3 » de la livraison I2 ; il ne réécrit aucun résultat historique. Cette décision constitue une autorisation bornée de progression vers L3/I3. Elle ne lève pas globalement les autres restrictions V3 et ne prononce ni clôture de certification I2, ni succès de la gate COMMAND + Architecture, ni certification du participant ou du runtime à venir.

Avant toute implémentation I3, son périmètre et ses slices de vérification doivent être déclarés conformément à la [politique normative de vérification](../testing/Reactor_Verification_Policy.md), puis les vérifications applicables doivent être exécutées et rapportées. La présente publication n'implémente pas I3.

## 4. Vérification de la publication

- Impact : documentation uniquement ; aucun changement sous `app/` ni dans les mécanismes L1/L2.
- Slices applicatives déclarées : aucune ; contrôle documentaire des liens, du contenu et du diff.
- Franchissement inattendu de slice : aucun.
- Gate globale : non requise pour cette publication ; la gate I2 rapportée demeure FAIL.
- Réacteur complet : non exécuté, car aucun comportement applicatif n'est modifié.
- Vérification de base de données : aucune ; aucun test ni migration réexécuté.
- Contrôles de publication : script Python documentaire borné aux cinq fichiers (liens locaux, cohérence des statuts et des reçus existants, périmètre exact, préservation du checkout principal), puis `git diff --check -- <les cinq fichiers>` et contrôle identique de l’index avant commit. Les commandes et résultats finaux sont communiqués avec le SHA après push, sans auto-référence dans ce document.

Le [plan directeur](V3_Implementation_Master_Plan.md) porte la progression opérationnelle ; le [canon V3](V3_Canon_and_Roadmap.md), le [plan L3](L3_Identity_Inventory_and_Implementation_Plan.md) et l'[index documentaire](../README.md) référencent cet arbitrage. Le GO I3 est enregistré ; I3 n'est pas commencé par cette publication. Aucun GO I4/I5, L4/L5 ou production n'en découle.
