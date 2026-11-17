# L4/I8 — Dossier de preuves

Le [rapport d’exécution](../../../v3/L4_I8_Execution_Report.md) porte le verdict et ses limites ; le [manifeste](../../../v3/L4_I8_Proof_Manifest.json) attribue les résultats au SHA applicatif réellement testé. L4 reste ouvert jusqu’à audit indépendant et décision humaine distincte.

**Qualification composite explicitement autorisée : 2 024 tests hors Architecture PASS (`a3470df6…`) +167 Architecture PASS (`8a007768…`, seuls oracles modifiés). Le dernier full complet reste FAIL 2 191/4/0/0. Quatre full ont été démarrés : trois terminés FAIL, un interrompu dès la dispense reçue. Aucun full PASS ni clôture L4.** [Décision humaine](Human_Composite_Qualification_Decision.json).

## Attribution

- [Application_Input_Comparison.json](Application_Input_Comparison.json) : 1 372 entrées identiques octet par octet ; une différence LF/CRLF normalisée du seul wrapper Windows non exécuté.
- [Application_Files.json](Application_Files.json) : inventaire exact des 29 fichiers applicatifs modifiés, SHA de code et empreintes.
- [Commands_Actually_Executed.json](Commands_Actually_Executed.json) : commandes Maven, types de processus et nombre réel d’invocations ; les découvertes et vérifications de version ne sont pas des tests.
- [SPEC10_Final_Matrix.json](SPEC10_Final_Matrix.json) : les 26 lignes du plan, méthodes, autorités, SHA, commande, artefact, verdict et obligations différées.
- [Source_Boundary_Check.json](Source_Boundary_Check.json) : plan publié de 431 lignes et comparaisons nominatives initial/I2/recovery, sans changement des chemins protégés.
- [Historical_Reuse_Comparison.json](Historical_Reuse_Comparison.json) : empreintes historiques vérifiées et différences ; aucun ancien PASS réattribué au nouveau SHA.
- [Evidence_Integrity_Check.json](Evidence_Integrity_Check.json) et [Archive_Index.json](Archive_Index.json) : intégrité des archives et index documentaire. L’index ne s’inclut pas lui-même.

## Campagnes

Chaque dossier de [runs](runs/) conserve `result.json`, `timings.json` et `raw.tar.gz`, ainsi que `plan.json` et `provenance.json` lorsqu’ils ont pu être établis. Les archives contiennent modèles effectifs, logs Maven, reçus de processus, rapports Surefire XML/TXT et témoins physiques modifiés par l’invocation. Les résultats sont ceux du collecteur existant, confrontant les identités attendues aux XML frais avec ID et répertoire propres. Les campagnes en échec restent en échec ; les méthodes non atteintes ne sont pas des SKIP JUnit ni des PASS.

Le préflight global refusé ne contient que le modèle et le log de découverte, zéro exécution. La [revue de l’alias top-level](Collector_Alias_Review.json) explique l’index en mémoire employé par [l’invocation revue](Invocation_FULL_Alias_Reviewed.py). Aucun nouveau sélecteur ou registre permanent n’a été créé. Ces fichiers sont des reçus d’invocation ; ils ne constituent pas une nouvelle commande canonique de projet.

Les [modèles de comparaison](Gate_Effective_Models.tar.gz), leurs [empreintes et l’arbitrage humain](Gate_Equivalence.json) démontrent l’équivalence C-I8/F-L4. L’autorisation de mutualisation n’autorise aucun PASS sans exécution ; le premier full en échec et son correctif nécessaire sont comptés séparément.

## Ressources et transactions

[Resource_Observations.json](Resource_Observations.json) indexe les observations de T09/T10/T11. [physical-final](physical-final/) contient des copies exactes des membres correspondants du full complet FULL-04-FAIL au code productif `a3470df6…`, notamment les traces enfant, PID/XID, échéances PostgreSQL, compteurs de renouvellement, omissions et retours partiels. Les oracles complets restent dans les méthodes applicatives et les rapports archivés.

[Server_Capacity_Observation.json](Server_Capacity_Observation.json) est une observation SQL en lecture seule pendant T11, sur le même serveur de fixture. Son [reçu de commande](Server_Capacity_Observation_Command.py) ne lance aucun test ni Maven. L’essai d’observation antérieur non obtenu reste conservé sous `Server_Capacity_FULL03_NOT_OBSERVED.json`. Une absence d’observation n’est jamais convertie en PASS.

Lease180 reste une hypothèse locale qualifiée, pas un dimensionnement de production accepté. Les valeurs et mécanismes historiques L2 sont inchangés. Aucune confiance de production, certification de performance globale ou clôture SPEC-10 n’est inférée des fixtures.

Les [instantanés de santé](health/) (dumps bruts conservés octet pour octet dans `Thread_Dumps_Raw.tar.gz`) documentent processus, threads et attentes PostgreSQL pendant l’exécution historique longue. Aucun arrêt ni rejeu n’a été engagé sur la base d’un silence ou d’une estimation.

## Feedback de développement

[Development_Commands.json](Development_Commands.json) et [feedback](feedback/) conservent une compilation et treize processus de tests ciblés. Leur worktree était dirty au SHA documentaire initial, sans snapshot complet des entrées ; ces traces servent au diagnostic, pas à la certification d’un SHA applicatif. Les témoins `physical-13` restent attribués au feedback correspondant. Les preuves de qualification proviennent des deux segments et SHAs explicitement consignés dans le manifeste, selon la dispense humaine.
