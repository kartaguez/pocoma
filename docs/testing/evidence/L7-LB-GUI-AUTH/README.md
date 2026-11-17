# L7-LB — preuve ciblée du correctif GUI OAuth

[Rapport et portée](../../../v3/L7_Local_Playground_GUI_Auth_Fix_Report.md).
SHA propre testé : e54cadd40b6595f017927a1ae4cccd48511af1fe.
Verdict de jalon maintenu : LOCAL_PLAYGROUND_PARTIAL.

- `unit.log` :17 contrôles locaux PASS.
- `bruno-421-protocol.json` :23 contrôles sur app.asar4.2.1 installé, digest,
  parsing, VM de scripts et PG01 HTTP réel. Sessions dans la mémoire du test,
  pas de clic native Get Access Token ni d'acceptation humaine revendiqués.
- `bruno-421-requests.json` : trace méthode/route/statut, aucun body/header/token.
- `gui-prepare.json`, `login-alias.json`, `Instance-public.bru` : préparation de
  seules URLs publiques. L'alias login n'exporte plus de JWT.
- `cli.json`, `bruno.xml`, `bruno-receipt.json`, `scenarios.jsonl` : sélection
  PG01 CLI4.3.0, JUnit1/1 et inventaire non vide strict.
- `ui-observation.json` : reproduction du défaut puis blocage d'accès UI ;
  rendu/login corrigés toujours dus à la validation humaine.
- `attempts.json`, `attempts/` : échecs de scaffolding/disponibilité et reprise
  CURRENT du cluster possédé observé vierge. Aucun reset/down/rm/prune exécuté
  par ce correctif ; aucune continuité de données antérieures certifiée.
- `provenance.json`, `publication-comparison.json`, `SHA256SUMS.json` : attribution,
  commandes, scopes, comparaison des entrées et intégrité des artefacts.

Les hashes ne s'auto-hashent pas. Les secrets et les données brutes privées de
session ne sont pas publiés. Les logs expurgés restent des observations, pas
une certification exhaustive du produit ou de l'environnement Docker.
