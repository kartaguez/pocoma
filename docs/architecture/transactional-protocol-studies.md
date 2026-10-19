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
| Chantier A : versionnement et obsolescence des commandes Pot | [Audit temporel et matrice courante](Pot_Command_Versioning_Obsolescence_Design_2026-10-18.md#ag-décision-courante--obsolescence-par-versions-temporelles-18-octobre-2026) | **READY_WITH_CONSERVATIVE_RESTRICTIONS**, audit de `c9adae1f` ; cible unique `started_at_version`, aucun suivi parallèle ni migration ; versions S communes, shares vides stale refusées, DeletePot O=V ; A–AF et ancien plan historiques uniquement ; aucun runtime modifié |
| Séquencement terminal Events — étape 3A | [Audit](Event_Terminal_Sequencing_Step_3A_Audit_2026-10-19.md) | **TARGET**, faisabilité conditionnelle du suffixe E, sans intégration |
| Séquencement terminal Events — étape 3B | [Preuve PostgreSQL](Event_Terminal_Sequencing_Step_3B_PostgreSQL_Proof_2026-10-19.md) | **PASS expérimental borné**, scénarios A–J et contention ; universalité runtime non démontrée ; aucune Discovery ni intégration runtime |
| Séquencement terminal Events — étape 3C | [Intégration transactionnelle étudiée](Event_Terminal_Sequencing_Step_3C_Transactional_Integration_2026-10-19.md) | **FEASIBLE WITH CONDITIONS**, preuves Spring/JPA/PostgreSQL ; limites JDBC/savepoints/flush résiduel explicites ; aucune intégration runtime |
| Commandes Pot — étape 2C | [Fence Binding partagé intégré](Pot_Command_Binding_Shared_Fence_Integration_Step_2C_2026-10-18.md) | Intégration COMMAND ; protocole P → B → S conservé ; détails des preuves et limites dans le rapport |
| Commandes Pot — étape 2B | [Exceptions, rollback et retry CAS Consumption](Pot_Command_Cas_Consumption_Step_2B_2026-10-18.md) | **IMPLÉMENTÉ ET VÉRIFIÉ**, COMMAND, PostgreSQL ; budget 4 essais ; ordre P → B → S inchangé |

La décision courante abandonne toute lecture historique des BusinessEvents pour l’obsolescence, ainsi que la certification et son ancien plan. Les sections AG–AO du rapport définissent la seule cible : versions temporelles des sous-objets, avec restrictions conservatrices. Le transport et l’obsolescence métier ont été livrés à l’étape 1 ; la classification CAS et la reprise Consumption sont livrées à l’étape 2B ci-dessus. **Aucun runtime cible n’est implémenté par cet audit. EventPublication reste une proposition distincte non implémentée.** Les anciennes conclusions A/A.2/A.3 sont uniquement historiques.

## Preuves R03 conservées

- [Preuve rouge initiale](../testing/evidence/UpdateExpenseShares_R03_2026-10-18.txt) : défaut reproduit avant correction, volontairement conservé.
- [Résolution](../testing/evidence/Expense_Context_Coherence_Fix_2026-10-18.txt) : 76 tests historiques, dont 23 PostgreSQL.
- [Intégration](../testing/evidence/Expense_Context_Integration_2026-10-18.txt) et [rapport](../testing/evidence/Expense_Context_Integration_Report_2026-10-18.md) : 91 tests historiques, dont 34 PostgreSQL.
- [Test de régression validé](../../app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/context/UpdateExpenseSharesConcurrencyPostgresTest.java) : version intégrée par `675533e7`, inchangée ici.

## Traçabilité Git

[Registre de consolidation](../testing/evidence/Git_Consumption_Consolidation_2026-10-18.md) : carte initiale/finale, provenance des documents, vérifications et travaux à préserver. Les autres dettes gardent leur statut établi dans leurs dossiers ; cette opération n'en ouvre ni n'en clôture aucune.
