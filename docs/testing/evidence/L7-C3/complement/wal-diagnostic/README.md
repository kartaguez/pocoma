# Diagnostic WAL L7-C3

Le harnais sur le clone isolé rejoue les quatre profils à un worker, cent finalisations mesurées par variante. Il lit `pg_walinspect` après chaque fenêtre en bornant exactement les LSN. Le réacteur tournait sur le même hôte pendant cet essai. Les deux processus utilisaient des conteneurs PostgreSQL distincts. Les temps de ce diagnostic ne contribuent pas aux comparaisons de performance.

Le delta LSN de PotVersion passe de 211 664 à 268 616 octets, soit +569,52 octets par événement, avec zéro image complète dans ses deux fenêtres. La variante PotBalance OFF contient 151 188 octets d’images complètes de pages sur 412 950 octets d’enregistrements WAL recensés. La variante ON n’en contient aucun. Les octets du LSN comprennent aussi alignements et en-têtes de pages. Les enregistrements physiques ne sont pas additionnés aux valeurs `pg_stat_statements`, qui constituent une autre vue sur une partie des mêmes octets.

- [Relevé physique par type d’enregistrement](wal.tsv)
- [Fenêtres et delta LSN brut](windows.tsv)
- [Relevé SQL par rôle et instruction](sql.tsv)
- [Journal comprimé](wal.log.gz)
