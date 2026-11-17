# Exploitation

Cette section couvre le diagnostic, le suivi d'état et la récupération des instances locales Pocoma :

- **[Diagnostic et dépannage](troubleshooting.md)** — interprétation des états (`operationalState` / `qualificationState`), codes d'erreur (`PRE-*`, `DB-*`, `INCOMPATIBLE-*`, `KEYCLOAK_*`, `GRAFANA-*`) et limites connues de la distribution locale.
- **[Reprise après panne et cycle de vie](recovery.md)** — redémarrage gracieux, reprise après arrêt brutal (`270 s`), récupération après coupure PostgreSQL (`draining` / capacité d'admission), `reset`, `destroy` et `upgrade-grafana`.
