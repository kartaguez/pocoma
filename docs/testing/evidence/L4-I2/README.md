# L4/I2 — dossier de preuves

Les verdicts, comptes et limites définitifs figurent dans le
[rapport d’exécution](../../../v3/L4_I2_Execution_Report.md) et le manifeste de livraison
`docs/v3/L4_I2_Proof_Manifest.json`. Les archives de développement ne qualifient pas les octets finaux.

## Production des gates

`run-campaign.py` importe le sélecteur normatif inchangé de `scripts/testing/verify.py` dans
le checkout de qualification. Il ajoute uniquement l’attribution nominative du second test
top-level existant `CurrentTransactionManagerPostgresTest` à son fichier
`JpaTerminalSequencingPostgresTest.java`. Cette attribution était déjà utilisée pour I1.
La déclaration de classe est validée avant attribution. Aucun test n’est exclu, aucune méthode
attendue n’est supprimée, aucune configuration de découverte ou de verdict n’est relâchée.

Modes : `projection-command` et `architecture`. Les sélections/lifecycles sont exactement les
deux commandes canoniques autorisées, augmentées des options de sortie batch et des deux
propriétés de provenance des producteurs. Chaque répertoire de campagne conserve :

- `plan.json` : modèle effectif, modules, méthodes attendues, argv exact et identifiant d’invocation ;
- `provenance.json` : HEAD, état dirty, outils, empreintes applicatives, SQL externes et contrôles ;
- `result.json` : verdict, identités réellement observées, comptes et empreintes des pièces ;
- `raw.tar.gz` : mêmes octets XML évalués, logs, modèle effectif et preuves physiques fraîches.

Les sources `app/` et les contrôles sont comparés avant/après ; toute mutation interdit PASS.
Les rapports partagés de `target/` ne contribuent jamais au verdict des gates. Une méthode
manquante, un doublon, un skip, une mauvaise attribution ou un échec empêche PASS.
L’opérateur ne change ni POM, ni migration, ni runtime, ni gate Maven.

Les sources sont qualifiées dans le worktree détaché isolé
`/private/tmp/pocoma-l4-i2-qualification`, à partir du commit applicatif propre
`6496e6d0144ecfda9e58ec6d12e259445e7344ad` (`dirty = false`). Les 1288 entrées applicatives
de `canonical/` (`a51dbdc33d474f6da594a415230a9925`, `1873/0/0/0`) et `architecture/`
(`db612392a6a143cda26be68bc2f5a209`, `2137/0/0/0`) sont identiques aux octets de livraison.
Ce dispositif évite les sorties compilées partagées suspectes dont le désassemblage est
conservé dans `development/`.

## Résultats non-PASS conservés

`preflight/` garde le refus de traçabilité initial du sélecteur standard, sans test exécuté.
`canonical-fail-navigation/` garde la gate échouée avec cinq erreurs du fixture de navigation.
`canonical-fail-store-owner/` garde la gate échouée sur l’ancien retour sain attendu après un
refus intercepté de mauvaise autorité dans le test de stockage I1.
`canonical-fail-renewal/` garde la gate canonique initiale échouée (`1873/1/0/0`, `I2-QUAL-01`)
avant établissement de la réserve physique du pool Hikari dans le banc de test
`ConsumptionLifecycleCompositionPostgresTest#renewalsHaveReservedDatabaseAccess`.
`development/canonical-interrupted/` garde la campagne arrêtée pour renforcer l’oracle K=position :
les comptes partiels ne sont pas une preuve de couverture complète.
Les autres diagnostics et contre-preuves restent dans `development/` avec leurs logs bruts,
notamment le feedback Worker 70/1/1/0, l’historique non gardé 1/1/0/0, la reproduction causale
`renewal-diagnostic-reproduced.tar.gz`, le contre-oracle `renewal-unreserved-counteroracle-fail.tar.gz`,
le replay ciblé corrigé `renewal-fixed-targeted-pass.tar.gz` et les tentatives intermédiaires de
requalification `requalification-l3-waiterpid-cme-fail.tar.gz`.
Aucun échec obligatoire n’est transformé en PASS par agrégation avec une autre campagne.

Les contre-oracles intentionnellement incorrects restent identifiés comme tels : lookup source
filtrant, snapshots d’effets mélangés, forwarder perdant la Guard et pool Hikari sans réserve
suffisante pour trois renouvellements simultanés (6 connexions retenues sur 8). Leurs résultats
interdits sont distingués par les mêmes témoins PostgreSQL et Hikari que les implémentations correctes.

Les réserves historiques I1 et r05/r12 restent dans leurs documents d’origine, avec leur
attribution. Ce dossier n’en prononce aucune levée ni clôture définitive de I2.


## Décision postérieure de clôture — 3 novembre 2026

**L4/I2 — CLOSED — LOCAL BOUNDED PASS**, selon le
[rapport et la décision humaine](../../../v3/L4_I2_Execution_Report.md#clôture-définitive-du-3-novembre-2026).
La phrase précédente et le manifeste sont historiques, antérieurs à cette décision.
Les archives, résultats et empreintes restent inchangés et attribués à `6496e6d…`,
avec publication `2a81d49…`. Aucun rejeu des deux gates acquises n’a lieu ici.
Le correctif `waiterPid` possède ses [preuves ciblées distinctes](../L3-I2-waiterpid/README.md)
et ne conditionne pas la clôture I2. L4 reste ouvert ; qualification globale finale différée.
