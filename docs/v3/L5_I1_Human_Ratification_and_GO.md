# Pocoma V3 — L5/I1 — Ratification humaine et GO

Date : **4 novembre 2026**. Source : mandat humain explicite dans la conversation de livraison. Référence publiée : **`f46d63a9bc4505c43610376eeba40a5854431230`**, branche **`feat/v3-architecture`**. Worktree initial propre, références locale et distante alignées après `git fetch origin` ; autres worktrees préservés.

Cet acte enregistre des décisions humaines, **aucun résultat technique ni clôture humaine d'I1**. Il complète, sans le réécrire, l'[acte historique D1/D6](L5_D1_D6_Ratification_and_D4_Calibration_Decision.md).

## Décisions ratifiées

- Plan global L5 et [plan opérationnel I1](L5_I1_Implementation_and_Qualification_Plan.md), périmètre, contrats, invariants et couverture : **APPROVED**.
- D4-READ : **APPROVED FOR INITIAL I1 IMPLEMENTATION WITH PHYSICAL RESERVATIONS**. Batch maximum 50 références ; corps HTTP READ maximum 65 536 octets ; réponse maximum 4 194 304 octets UTF-8 ; navigation maximum 100, défaut 20 ; quota SQL READ 2 partagé dans l'allocation existante.
- D6-gates, option B : **FULL-NATIVE MUTUALIZATION APPROVED, SUBJECT TO EFFECTIVE COVERAGE AT Q5**. Un full natif à Q5 doit satisfaire §35, G5 et Architecture intégrale sous couverture effective des méthodes/variantes/environnements. Aucune dispense. Bruno B5, AuthN/consentement réels et preuves externes non couvertes restent dus séparément. Checkpoints I1–I4 locaux, sans clôture canonique autonome. Aucun full/Architecture demandé à I1 sauf déclencheur effectif immédiat de la politique, alors identifié et appliqué.
- GO I1 : **IMPLEMENTATION AUTHORIZED**. Ratification documentaire puis implémentation complète, T1, Bruno B1, collecte et publication sur la branche prévue autorisés sans nouvelle confirmation. I2, I3, I4 et Q5 exclus.

## Conditions D4 maintenues

Aucune troncature, pagination de manifeste, limite métier implicite, nouveau moteur/module/cache ; sérialisation entière avant succès HTTP ; prévalidation pure avant quota, aucun emprunt Control ni hausse automatique du quota. Un Pot légitime >4 Mio peut être temporairement indisponible, jamais tronqué. Le banc initial borné D4 est autorisé (deux HTTP en vol, délais locaux et protections expérimentales). Mémoire réelle, copies, concurrence, annulation, durée, restitution et coût JDBC restent à qualifier par T1. Une mesure ne modifie pas silencieusement les seuils approuvés.

## Règle de budget substitutive

T1+B1 : **15 minutes indicatives**, estimation et seuil d'alerte. Une campagne lancée qui progresse normalement va jusqu'à son résultat terminal, même à 18, 25 ou 35 minutes. Aucun arrêt ni relance pour le seul dépassement. Mesurer les durées de compilation, démarrage/fixtures, PostgreSQL, JUnit, Bruno et collecte ; analyser ensuite les coûts sans modifier les oracles.

Arrêt justifié : risque données/ressources, sûreté/confidentialité, deadlock ou absence de progression objectivement constatée, blocage physique durable, processus hors contrôle, borne technique de sûreté indépendante ou échec déterminant rendant la poursuite dangereuse/inutile. Préserver les preuves et observer la terminaison physique. Aucune attente indéfinie. Les timeouts d'oracle/sûreté restent applicables et distincts du budget de planification. Relance uniquement après correction concrète ou changement d'environnement identifié, preuves invalidées nommées ; aucun retry inchangé pour obtenir PASS.

Cette règle remplace les arrêts purement temporels des dispositions opérationnelles L5 concernées ; les campagnes historiques ne sont pas réécrites. La politique Reactor reste normative et inchangée ; tout conflit réel doit être signalé et arbitré explicitement. Les objectifs temporels de §35 ne sont pas des plafonds de campagne.

## Réserves et publication

Réserves L1–L4, AuthN réelle, consentement et performances/capacité production conservées. Aucune certification SPEC-10 globale ni clôture L5. Publication des seuls fichiers du mandat autorisée, sans force push ni secrets, après revue des références, preuves, résultats exacts et `git diff --check`. La clôture humaine définitive d'I1 reste au responsable.
