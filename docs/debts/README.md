# Registre des dettes techniques

Une dette décrit un écart connu, parfois volontairement accepté. Elle n'implique pas nécessairement un défaut runtime actuel. Ce registre n'est ni une source **CURRENT** du comportement du système, ni automatiquement une architecture **TARGET**. Une dette devient un Step actif uniquement lorsqu'un chantier d'exécution est explicitement ouvert. Les Steps terminés restent des preuves **HISTORICAL-COMPLETED** ; les documents remplacés restent **SUPERSEDED**.

Statuts : **OPEN** (à traiter ultérieurement), **IN_PROGRESS** (chantier explicitement ouvert), **RESOLVED** (résolution vérifiée). La criticité est conservée lorsqu'elle provient d'un audit ; **UNRATED** signifie qu'aucune criticité n'a été établie.

| Identifiant | Dette | Criticité | Statut | Origine | Résumé | Liens |
|---|---|---|---|---|---|---|
| DEBT-WA6-01 | [Guard global d'ordre des locks Binding](BINDING_LOCK_ORDER_GUARD/Debt.md) | LOW | OPEN | Audit WA.6 clos | Le guard ne prouve pas exhaustivement l'absence d'un chemin `authority -> stream`. | [Dette historique](../steps/completed/WRITE_ADMISSION/Step_debt.md#debt-wa6-01--global-binding-lock-order-guard-is-not-semantically-exhaustive) |
| DEBT-WA6-02 | [Guards SQL ownership / append-only](SQL_APPEND_ONLY_GUARDS/Debt.md) | LOW | OPEN | Audit WA.6 clos | Les scans syntaxiques protègent des régressions connues sans prouver tous les futurs chemins SQL. | [Dette historique](../steps/completed/WRITE_ADMISSION/Step_debt.md#debt-wa6-02--sql-ownership--append-only-guards-are-syntactic-tripwires) |
| DEBT-MOD-01 | [Taxonomie et familles de modules](MODULE_TAXONOMY/Debt.md) | UNRATED | OPEN | Révision architecturale préparatoire | Les noms et frontières des 51 modules mêlent plusieurs types de responsabilités. | [Réflexion TARGET](../steps/current/ARCHITECTURE/Three_Engine_Families_Revision.md) |

## Décisions ouvertes de modularité

`TBD-COMMAND-CONTRACT` est une **décision ouverte**, pas une dette de criticité évaluée. Sa source de vérité est le [POST-WP4 checkpoint du plan de migration](../steps/current/ARCHITECTURE/Modularity_Migration_Plan.md). Il reste **OPEN** pendant WP4 ; la consolidation finale ne peut pas le clore silencieusement. Le checkpoint doit inventorier les consommateurs réels après WP4 et décider de l’ownership de `CommandId`, `CommandOutcome`, `RecordedCommand` et des contrats Result partagés.
