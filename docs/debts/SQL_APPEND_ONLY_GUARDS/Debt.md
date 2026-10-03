# DEBT-WA6-02 — Guards SQL ownership / append-only

- Statut : **OPEN**
- Severity : **LOW**
- Défaut runtime actuel : **NO**
- Impact sur le modèle canonique : **NONE**
- Urgence : non bloquante

## Écart et risque

Les guards actuels, notamment [`Wa67BindingArchitectureTest`](../../../app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/Wa67BindingArchitectureTest.java), inspectent le code pour repérer les accès directs à certaines tables, maintenir les whitelists de propriétaires SQL et interdire des motifs tels que `UPDATE external_identity_binding_facts` et `DELETE FROM external_identity_binding_facts`.

Ces tests sont des gardes de non-régression utiles, mais restent des tripwires syntaxiques. Ils ne prouvent pas formellement que tous les futurs chemins SQL respecteront l'ownership et l'append-only. Une régression pourrait théoriquement échapper aux scans par du SQL dynamique, une concaténation, un fichier resource SQL, une nouvelle abstraction ou un accès indirect masquant le nom physique de la table. Aucun de ces scénarios n'est constaté aujourd'hui.

## Direction de résolution

- Renforcer les frontières d'accès DB par module et API.
- Centraliser les primitives de persistence et expliciter leur ownership architectural.
- Analyser les resources SQL si elles deviennent utilisées pour ces accès.
- Conserver les tests syntaxiques actuels comme défense complémentaire, sans en faire la preuve unique.

Ces pistes ne sont pas implémentées dans cette session documentaire.

## Provenance

Cette fiche est la source vivante de suivi. Le [Step debt WRITE_ADMISSION](../../steps/completed/WRITE_ADMISSION/Step_debt.md#debt-wa6-02--sql-ownership--append-only-guards-are-syntactic-tripwires) conserve le finding et sa classification historiques ; voir aussi l'[audit WA.6](../../steps/completed/WRITE_ADMISSION/WA6_Audit.md).
