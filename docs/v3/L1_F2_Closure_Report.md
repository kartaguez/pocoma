# L1-F2 — Rapport de clôture corrective WRITE

2026-10-25. **IMPLEMENTING — gate final non encore exécuté au moment de ce commit.** Aucun L2 commencé, aucun verdict global V3. L1-F reste historiquement CLOSED/PASS sur son périmètre.

Baseline : `b3df3f3e619eeed6fc551177c778b412daa4fa4b`. Inventaire préalable versionné `d1333628`, harnais apparié `35448983` (production identique à baseline). [Inventaire / portée déclarée](L1_F2_Contradictory_Inventory.md), [matrice des invariants](L1_F2_Invariant_Matrix.md), [méthodes et preuves attendues](L1_F2_Proof_Manifest.json).

Implémentation bornée : dernières versions et propriété xid8 directes ; révisions ouvertes groupées, première/prédécesseur/dernière locales et absence de chevauchement avec l'ouverte ; fermeture CAS exacte puis insertion atomique. Huit index V4 `(pot_id,active_from)` servent les effets de candidate, sans nouvelle contrainte d'intégrité. Aucun changement domaine/port/engine/protocole ; audit exhaustif explicite conservé. COMMAND primaire, aucune secondaire ; architecture/full non requis et non exécutés, aucun franchissement inattendu.

Diagnostics PostgreSQL avant gate : 63 tests ciblés PASS puis 6 tests F2 PASS, dont index ; mesure exploratoire avant/après. Une première initialisation Docker a échoué sous sandbox avant toute méthode métier ; la relance autorisée a passé. Ces diagnostics ne remplacent pas le gate propre ni ses résultats, à compléter après exécution. La preuve avant est reproduite sur le checkout propre 35448983.

Le rapport final complétera SHA testé, commandes/counts/skips, mesures brutes, réserves, provenance, fichiers/commits et conditions d'entrée L2. La mise à jour après gate restera documentaire uniquement.
