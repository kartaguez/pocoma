# D17 Command/Consumption — correction bornée et distinction avec DEBT-WA6-01

Date : 2026-10-16. D17 est un défaut runtime de finalisation Command/Consumption constaté dans l'[audit PostgreSQL](Audit_2026-10-16.md), différent de la dette structurelle [DEBT-WA6-01](Debt.md).

CURRENT : C1 détient outcome Q ; après takeover C2, la failure prend S FOR UPDATE puis attend Q ; la FK de provenance C1 demande KEY SHARE S. Cycle Q→S→Q, SQLSTATE 40P01 conservé dans la [preuve WP0](../../plans/2026-10-16-consumption-generic-transactional-protocol-wp0-baseline.md).

Correction WP2 `72687053` : toutes les familles et recoveries publient avant le suffixe flush/CAS/clôture, aucune finalisation terminale ne prélocke S. C2 attend Q sans détenir S ; C1 flush sa provenance puis perd CAS et rollbacke ; C2 peut terminer. Les anciennes API de prélock/finalisation transitoire sont retirées au WP5 `5bb8fc5d`.

**D17 : CORRECTED / PROVEN dans les huit entrelacements caractérisés** failure/recovery × inputs/results/les deux/aucun. [WP2](../../plans/2026-10-16-consumption-generic-transactional-protocol-wp2-implementation.md), [WP4](../../plans/2026-10-16-consumption-generic-transactional-protocol-wp4-concurrency.md), [WP6](../../plans/2026-10-16-consumption-generic-transactional-protocol-wp6-closure.md) : takeover réellement committé, attentes outcome constatées, probe KEY SHARE accordée, C1 LostClaim/rollback et unique outcome/event gagnant exact. Les constraints de provenance et S/C sont conservées.

Cette correction n'établit ni absence universelle de deadlock, ni délai/fairness, ni sûreté d'un déploiement mixte de workers anciens/nouveaux. Le futur déploiement doit drainer et aligner les workers concernés ; aucun déploiement n'est réalisé ici.

**DEBT-WA6-01 reste OPEN/LOW** : son critère est l'unicité structurelle/exhaustive du chemin stream→authority et l'impossibilité d'une composition inverse future. Le chantier Consumption ne renforce pas le guard ArchUnit/textuel jusqu'à ce critère. La disparition de D17 ne justifie donc aucune clôture de cette dette.

**DEBT-WA6-02 reste OPEN/LOW**, hors périmètre. **DEBT-TEST-01 reste RESOLVED**, avec diagnostic de son incident historique INCONCLUSIVE. Les preuves WP4 renforcent la couverture locale, sans attribuer rétrospectivement la cause de cet incident. Les trois échecs de fixture WP4 sont corrigés et conservés dans son archive ; aucun défaut runtime nouveau n'a été observé dans ces campagnes.

Décision documentaire : consigner la correction D17 et proposer sa clôture au périmètre expérimental ci-dessus ; aucune modification du statut officiel d'une dette existante. Les audits historiques restent intacts. Les pannes réseau réelles au commit/rollback, contraintes futures, DDL concurrent, callbacks multiclés et charge restent NOT TESTED ; UNKNOWN ne devient jamais un rollback confirmé sur la seule base d'une exception.
