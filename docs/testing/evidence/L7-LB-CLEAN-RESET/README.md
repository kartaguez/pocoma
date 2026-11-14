# L7-LB — nettoyage confirmé et nouvelle demo

**DEMO_READY_WITH_CLEANUP_DEVIATION** : une seule demo exploitable, avec un
écart irréversible de sept anciens volumes Prometheus supprimés alors que
le plan les annonçait exclus. Leur préservation n’est pas revendiquée.
[Rapport](../../../v3/L7_Local_Playground_Clean_Reset_Report.md).

| Artefact | Attribution |
| --- | --- |
| authorization.json | Réponse humaine « oui » au plan des treize anciennes instances |
| before.json, preflight.json | Relecture avant destruction : mêmes instances/UUID/IDs que le plan, source 47a0c094 |
| destroy-commands.json, destroy/ | Treize destroy ciblés, argv, durées et reçus réels ; ancienne version utilisant --volumes |
| after-destroy.json | Aucun ancien manifeste/ressource possédée ; sept volumes annoncés exclus également absents |
| cleanup-deviation.json | Noms des sept volumes, montages exclusivement Prometheus, cause, impact et notification utilisateur |
| compose-down-help.txt, prometheus-image-volumes.json | Preuves locales : --volumes retire les anonymes ; image avec /prometheus |
| public-environments-removed.json | Six environnements publics explicitement listés, retirés après confirmation |
| destruction-verification.json | Résultat de suppression **avec écart**, pas un PASS de préservation des volumes exclus |
| unit-controls.log.gz | 43 tests Python PASS, dont garde de propriété et préservation des volumes non possédés |
| volume-proof/before.json | Nouvelle fixture synthétique : anonyme classé unownedAttachedVolumes |
| volume-proof/proof.json, volume-removal.json | Helper réel corrigé : nommé possédé retiré, anonyme et son contenu conservés |
| volume-proof/fixture-cleanup.json | Retrait de la seule ressource synthétique après preuve, aucun ancien volume sélectionné |
| frozen-inputs.json | Sources/configurations/tests figés pour la qualification finale demo |
| demo-commands.json, demo/ | start/status/gui/bruno sur nouvelle demo : READY/READY/GUI_CONFIGURED/BRUNO_PASS |
| demo/package.log.gz | Assemblage ciblé des quatre runtimes, -am package -DskipTests, aucune preuve de tests Maven |
| demo-manifest-public.json | Nouvelle identité demo, ports, digests/JARs/toolchain sans secrets |
| after-demo.json | Seule demo possède manifeste et ressources ; douze archives ; nouveau volume Prometheus non possédé correctement identifié |
| finder-metadata.json | .DS_Store créé par Finder archivé sans perte ; aucun affaiblissement de garde |
| verification.json | Résultat fonctionnel final et déviation explicite, 43 tests, preuve Docker et identités |
| publication-input-comparison.json | Comparaison des sources/configurations/tests qualifiés à la publication |
| SHA256SUMS.json | Sommes des artefacts retenus, hors ce fichier et logs bruts compressés |

Empreinte de la correction et de la nouvelle demo :
`c6325dc490b0383a85c7baa855dc2687b1e0bcb7062400ffa1f18b035ae5735e`.
La suppression des anciennes ressources est attribuée à 47a0c094 ; la preuve
de protection et la qualification demo portent sur le diff correctif figé.
Les observations historiques ne sont pas transformées en PASS du nouveau code.

Les journaux, reçus et contrôles d’identité ne contiennent aucun mot de passe
technique ou JWT ; les diagnostics privés restent ignorés de Git.
Les sept volumes perdus ne sont pas restaurés. Aucun résultat de GUI rendu,
certification L7 ou full reactor n’est déclaré. Le périmètre est la
distribution locale ; aucun fichier app/, SQL, Java ou POM n’a changé.
