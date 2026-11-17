# L7-LB — preuves du durcissement des prérequis

Mandat `GO_L7_LB_PREREQUISITES_HARDENING`, 15 novembre 2026.

SHA corrigé et qualifié : `da7912e3707a72c801c39c08858f65e801a508cb`.
Le rapport final est `../../../L7_LB_Prerequisites_Hardening_and_Requalification_Report.md`.

- `unit-tests.txt`, `unit-test-inventory.json` : 120 tests Python, dont 40 nouveaux.
- `clone-initial.json`, `clone-final.json` : vrai clone réseau, checkout propre,
  sans artefact Pocoma initial, source inchangé pendant le parcours.
- `commands.json` : commandes publiques d'installation/start/Bruno/status/stop
  et suspension/reprise explicite des conteneurs demo.
- `clean-start`, `clean-bruno`, `clean-status`, `clean-stop` : liste explicite de
  reçus sans secrets, avec readiness, partitions, canari et assertions Bruno.
- `manifest-public.json`, `resources.json` : versions/digests et ressources du
  clone, sans configuration privée ni secrets.
- `package.log.gz` : log Maven de packaging, compression sans perte ; hash des
  octets décompressés dans `package-encoding.json`. Tests Maven non exécutés.
- `demo-before/final-*`, `demo-worker-recovery.json`, `preservation.json` :
  conteneurs réutilisés, fichiers comparés en mémoire (octets et modes), données
  SQL stables comparées par empreintes en mémoire. Seuls comptes et booléens de
  comparaison sont publiés ; aucun hash de ligne/secret n'est exporté.
- `qualification-orchestration.py.txt` : collecteur externe à la distribution,
  non nécessaire à l'installation. Aucun reset/destroy/down ni retrait de volume.
- `redaction-check.json`, `SHA256SUMS` : contrôle des preuves contre les secrets
  techniques réels des deux instances et les formes JWT, puis manifeste.

Les caches hôte Maven/Wrapper/images étaient partagés. Ce n'est pas une preuve
sur un deuxième poste ou avec tous les caches réseau froids. La collection
Bruno publiée est utilisée telle quelle, sans les dix changements locaux du
checkout historique. Aucune nouvelle recette GUI humaine n'est revendiquée.

Le canari demo est volontairement inchangé : après reprise de ses conteneurs,
sa preuve ancienne n'est pas une nouvelle preuve métier de cette génération.
L'état technique et les partitions sont contrôlés séparément ; le rapport
conserve les verdicts avant/après sans les promouvoir artificiellement en READY.
