# L5/I2 — GO humain d’implémentation

Décision humaine du **4 novembre 2026**, transmise dans la mission « Mission Codex — Pocoma V3 — L5/I2 — GO humain — Implémentation et qualification ciblée » : **GO L5/I2 — IMPLEMENTATION AUTHORIZED** sur le [plan opérationnel V2.1](L5_I2_Operational_Implementation_and_Qualification_Plan_V2.md).

Référence : branche `feat/v3-architecture`, SHA initial attendu et effectivement constaté `6a27abeade86c7b9a0f3f6430685daf9d819fc73`. Le GO autorise l’implémentation, la qualification ciblée et la traçabilité, y compris la calibration D4-A minimale nécessaire, D4-B sur le réel dans T2, et la gate Architecture pendant I2. Les candidats D4 ne sont pas déclarés qualifiés par la seule décision ; leur sélection et leurs mesures sont attribuées au rapport d’exécution.

L’option SQL A de V2.1 est imposée : une transaction physique WRITE, table d’association commune, unicité durable et FK prévues, rôles séparés, aucun commit intermédiaire ni intégrité inverse supplémentaire. SELF est résolu dans WRITE ; les moteurs existants sont réutilisés. La simplification supplémentaire des mécanismes d’idempotence/transversaux est reportée et ne bloque pas I2.

Link public, consentement/fournisseur simulé, Invitation, réorganisation des runtimes, nouveau moteur/framework et modification des SPEC restent hors mandat. Aucun objet grant Link n’est ajouté ; l’anti-rejeu réel reste dû en I3 avant activation. Les preuves historiques et leurs réserves restent attribuées à leurs sources.

Livraison autorisée sur `feat/v3-architecture`, sans force push. **Aucune acceptation anticipée des tests, clôture humaine I2, certification SPEC-10, autorisation de production ou exécution du full reactor natif Q5 n’est donnée.** Le statut technique et les commandes réellement exécutées figurent dans le rapport/manifeste I2 ; ils ne remplacent pas une décision humaine ultérieure.
