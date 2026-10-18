# Études transactionnelles : index de revue

Cet index distingue les preuves du runtime et les propositions TARGET. Les SHA et limites des rapports historiques restent applicables ; leur présence sur cette branche ne change pas leur référence d'analyse.

| Sujet | Référence | Statut et portée |
|---|---|---|
| Autorités et fencing | [Audit des autorités de concurrence](Concurrency_Authorities_Fencing_Locks_Audit.md) | Référence existante, historique |
| Autorités transactionnelles, ordre des verrous, Binding / Consumption | [Audit transversal](Transactional_Authorities_Fencing_Locks_Audit_2026-10-18.md) | Analyse initiale `90926a5e`, compléments J/K de résolution R03 conservés |
| R03 : contexte Expense cohérent à V | [Rapport d'intégration](../testing/evidence/Expense_Context_Integration_Report_2026-10-18.md) | **CORRIGÉ ET CLÔTURÉ** pour les writers examinés ; aucune garantie SQL universelle contre les réécritures rétroactives |
| Reprise après R03 | [Étude de numérotation tardive](Event_Late_Numbering_Study_2026-10-18.md) | Étude historique après correction, aucune optimisation implémentée |
| Variantes A/B de numérotation Event | [Comparaison](Event_Late_Transactional_Numbering_Study_2026-10-18.md) | Étude historique avant report de R03 sur le checkout analysé |
| Verrouillage tardif | [Viabilité](Event_Late_Locking_Protocol_Viability_Audit_2026-10-18.md) | Viabilité conditionnelle, aucune nouvelle preuve PostgreSQL |
| Protocole transactionnel consolidé | [Mutation, autorités et publication ordonnée](Mutation_Authorities_Ordered_Publication_Consolidated_Study_2026-10-18.md) | **RÉFÉRENCE ARCHITECTURALE DE REVUE LA PLUS RÉCENTE — TARGET NON IMPLÉMENTÉ**, analyse de `116f4e4a` |
| Chantier A : versionnement et obsolescence des commandes Pot | [Contrat, inventaire et matrice](Pot_Command_Versioning_Obsolescence_Design_2026-10-18.md) | **A.3 — READY_FOR_IMPLEMENTATION**, analyse de `0062e5e4` ; F1–F11 ACCEPTED, certification prospective bornée, décodage strict, types durables V2 observedVersion et traitement technique Undetermined ; matrice A.2 conservée ; A/A.2 historiques, aucune implémentation |

La clôture documentaire A.3 fixe les décisions fonctionnelles F1–F11 et les contrats cibles `observedVersion` / `workingVersion`, de certification historique et de traitement `Undetermined`. **Le runtime cible n’est pas implémenté et les historiques déployés ne sont pas certifiés par cette étude. EventPublication reste une proposition architecturale non implémentée**, hors périmètre A.3. Les conclusions des études précédentes conservent leur référence historique.

## Preuves R03 conservées

- [Preuve rouge initiale](../testing/evidence/UpdateExpenseShares_R03_2026-10-18.txt) : défaut reproduit avant correction, volontairement conservé.
- [Résolution](../testing/evidence/Expense_Context_Coherence_Fix_2026-10-18.txt) : 76 tests historiques, dont 23 PostgreSQL.
- [Intégration](../testing/evidence/Expense_Context_Integration_2026-10-18.txt) et [rapport](../testing/evidence/Expense_Context_Integration_Report_2026-10-18.md) : 91 tests historiques, dont 34 PostgreSQL.
- [Test de régression validé](../../app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/context/UpdateExpenseSharesConcurrencyPostgresTest.java) : version intégrée par `675533e7`, inchangée ici.

## Traçabilité Git

[Registre de consolidation](../testing/evidence/Git_Consumption_Consolidation_2026-10-18.md) : carte initiale/finale, provenance des documents, vérifications et travaux à préserver. Les autres dettes gardent leur statut établi dans leurs dossiers ; cette opération n'en ouvre ni n'en clôture aucune.
