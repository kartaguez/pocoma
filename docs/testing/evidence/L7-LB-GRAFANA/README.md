# Preuves L7-LB Grafana

Mandat `GO_L7_LB_GRAFANA_INTEGRATION`.
Rapport : [implémentation et qualification](../../../v3/L7_Local_Playground_Grafana_Report.md).

- `docker/` : premier refus de migration, sans mutation du manifeste/de la
  topologie, dû à l'alias de casse macOS ; conservé comme diagnostic.
- `docker-qualified/` : campagne intermédiaire, incluant reset/destroy de la
  fixture. Échec de l'oracle final d'égalité d'inventaire (volumes anonymes
  Prometheus ajoutés et préservés) ; ce répertoire ne porte pas le verdict final.
- `final/` : phases réelles finales sur l'empreinte de distribution
  `711336711899a0aa04074515ab11cc9bc11d79c910ba9975a47c61f4eab85e4a`.
  `commands.json` conserve chaque commande/code et son reçu. `metrics.json`
  prouve 31 requêtes finies après trafic. Le verdict brut reste bloqué sur
  l'oracle qui confondait la TSDB Prometheus attachée à demo et un volume extérieur.
- `completion/` : **verdict final L7_LB_GRAFANA_READY**, après réconciliation
  explicite de cet oracle et start/status final. Les phases précédentes sont
  référencées, non rejouées ni réécrites. Les octets des vrais volumes extérieurs
  sont identiques ; la seule différence initiale concerne la TSDB attachée.
- `python-tests.txt` : 62 tests locaux, zéro échec/erreur/skip.
- `bruno-composition.txt` : 118 requêtes et 236 tests nommés.
- `image-platforms.txt` : manifestes officiels AMD64/ARM64 de Grafana 12.3.2.
- `final-source-provenance.json`, `manifest-public.json` : sources et déploiement
  expurgés ; les modifications Bruno préexistantes sont identifiées et conservées.

Les `grafana.json` de chaque phase conservent les contrôles et la classification
PRESENT/ZERO/ABSENT/NONFINITE des requêtes. Les phases de panne ont les codes
non nuls attendus : leur présence ne signifie pas un échec de la campagne.
Les sessions et réponses métier sensibles ne sont pas publiées. Le Compose
complet et les sauvegardes privées du manifeste restent dans les runs d'instance.
Aucun fichier de secrets techniques n'est livré.

`publication-audit.json` décrit l'expurgation des chemins personnels et les
hashes avant/après des copies publiques. Les logs Maven bruts restent privés ;
les `package-summary.json` conservent leur digest et leur résultat. Les reçus de
commandes ont des chemins relatifs équivalents ; les originaux restent privés.
Audit : aucun secret technique de demo, JWT ou chemin personnel absolu publié.
