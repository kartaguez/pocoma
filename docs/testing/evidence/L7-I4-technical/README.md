# L7.4 — Preuves d'exécution technique

Mandat reçu le12 novembre2026, bilan finalisé le13 novembre2026. Aucune certification automatique.

- [Qualification](../../../v3/L7_I4_SPEC09_Technical_Qualification_Report.md), [architecture](../../../v3/L7_I4_Bench_Architecture_and_Instrumentation.md), [contribution](../../../v3/L7_I4_Contribution_to_L7_I3_Report.md).
- [Contrats NOT_RUN](contractual-results.json), [commandes réellement exécutées](commands.txt), [toolchain](toolchain.json), [observations E1 CSV](e1-measurements.csv.gz).
- [Reçu final E1/E3](e1-e3-06/receipt.json), SHA230feb05 ; [contribution L7.3](l73-contribution.json).
- [Inventaire de candidats writers](writer-inventory-final/receipt.json) :2704 références/239 fichiers ; candidates uniquement, pas audit exhaustif.
- Contrôles :11 Python nouveaux,17 scénarios Identity/Keycloak/stock,8 oracles exacts ; signatures/claims de deux JWT de test vérifiés par Node. Les anciens replays10 tests restent conservés sans double comptage.

## Conservation et contrôle

Les six reçus originaux sont préservés byte pour byte. E1-01 (JWT), E1/E3-04 (bootstrap)
et le FAIL brut E1/E3-05 (suivi60s inférieur au bail240s) ne sont pas supprimés ni
réécrits. Le reçu06 atteste la reprise après échéance SQL réelle, sans modification
de lease. Les populations E1 ne sont pas confondues avec E3 ou avec SPEC-09.

Les scrapes Prometheus sont conservés en gzip déterministe. L'[archive-manifest.json](archive-manifest.json)
associe nom original, fichier stocké, encodage, SHA-256 des octets originaux, SHA-256
stocké et tailles. Les références `.prom` des reçus se résolvent par ce manifeste.
Les grands logs/CSV sont également compressés sans perte. Les tokens/configurations
privées ne sont pas archivés. Les limites des hashes d'intégrité ne changent pas :
ils n'authentifient pas un acteur pouvant réécrire preuves et manifestes ensemble.

```sh
python3 -B scripts/testing/check-l74-evidence.py
```

Le contrôleur vérifie octets, références métriques, compteurs/slots/checkpoints,
barrières et génération tuée/redémarrée, attribution des quinze lignes, treize familles
NOT_RUN et équivalence des entrées du run06 avec l'arbre local (JAR inclus).
Si les JAR ont été supprimés/reconstruits différemment, la vérification d'équivalence
refuse ; ce n'est pas une nouvelle défaillance du Runtime. Les preuves du run03 et06
correspondent à des commits accessibles ; les premiers essais dirty sont des traces
de développement, pas une certification d'un SHA ultérieur.

Le manifeste exclut son propre fichier et les sorties `evidence-check.json` et
`integrity-negative-control.json` afin d'éviter une référence circulaire. Ces sorties
rapportent l'exécution du contrôle, sans être des certificats applicatifs.
