# L7-LB Jalon 1 — preuves ciblées

Référence initiale2a4e7fd8 ; SHA technique propre testé
`69f327f6707635cea8a74f329f7d2d9c3ce5d3d6`.
[Rapport et limites](../../../v3/L7_Local_Playground_Qualification_Report.md).

- `69f327f6/qualification.json`, `commands.json`, `provenance.json` : campagne
  deux installations, contrôles réels et présence initiale des conteneurs externes.
- `first-*`, `second-*`, `critical-worker-stopped`, `observability-stopped` :
  reçus, traces méthode/route/statut, JUnit Bruno et fenêtre physique de reprise.
- `manifest-expurgated.json`, `platform.json` : images/digests/ARM64, JAR,
  toolchain, ressources et empreintes de catalogue ; aucun credential.
- `interactive-protocol.json` : vrai Keycloak et vrai serveur callback, state
  forgé refusé ; navigateur HTTP automatisé, pas de rendu GUI/humain revendiqué.
- `unit-*.log`, `integration.log`, `demo-*`, `stopped-status.json` : observations
  complémentaires. Une invocation Bruno=1 test nommé PG01, plusieurs oracles,
  jamais un succès sur collection vide. Aucun JSON response reporter.
- `publication-input-comparison.json` : attribution SHA testé/publication et
  copie exacte du test de callback. Les entrées opérationnelles sont inchangées.
- `attempts/` : essais échoués, préconditions, diagnostics et historique des
  corrections ; ils ne certifient pas le SHA final.

Les diagnostics complets et tous les volumes restent privés et conservés dans
leurs instances. Après collecte, des caches/outputs de build temporaires
régénérables ont été libérés pour restituer de l'espace disque ; aucun volume
ni diagnostic n'a été supprimé. Les caches Docker/Maven et les objets Git sont
partagés ; le checkout B n'avait ni target, JAR ni état Pocoma local.

`SHA256SUMS.json` inventorie les octets des artefacts de ce dossier, sans
s'auto-hasher. Les hashes garantissent l'intégrité de l'archive publiée, pas
une certification globale ou l'authenticité contre un auteur malveillant.
