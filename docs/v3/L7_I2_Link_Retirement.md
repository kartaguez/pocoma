# L7.2 — Retrait du use case Link

Le mandat humain `GO_L7_I2_I3_FULL_EXECUTION` retire la création, la consultation et l'acceptation des propositions Identity Link. Cette décision supplante, pour l'offre V3 courante, les clauses exclusivement Link de SPEC-02/03/06/07/09/10 et de l'acte L5/I3. L'ancien consentement C-01, ID-02 et le volet Link de F01-E restent des références historiques. Le volet Registration de F01-E conserve son budget de 500 ms. Les résultats historiques restent lisibles par leur EUI initiatrice. Les transitions Registration et Unlink, les EUI, leurs occurrences et les Invitations gardent leurs contrats.

## Données et déploiement

La table `v3_link_proposal`, les contraintes, l'ancien contexte d'admission, `v3_identity_request.link_proposal_id` et les valeurs historiques `LINK` restent dans les schémas WRITE et READ. Les propriétaires sont les administrateurs des schémas concernés. V9 conserve les FKs dans les deux sens et les références à l'association d'admission. La relecture des événements, des bindings et des résultats historiques ne dépend pas d'un accès à la table de propositions. L'ACL d'admission courante révoque les anciens droits directs à la table et à la colonne. Les anciennes propositions non acceptées restent inertes.

Pour une base existante, couper l'admission de l'ancien HTTP WRITE, vérifier que les demandes Link déjà admises ont terminé sous le Runtime qui les a admises, puis déployer la version de retrait. Observer en lecture avec l'autorité administrative existante.

```sql
select count(*) from v3_write.v3_identity_request
where operation = 'LINK' and status = 'PENDING';
```

Un résultat non nul bloque la mise à niveau de la composition Identity. Le Worker livré refuse aussi de préparer une demande Link non terminale introduite après son démarrage. Le retrait n'annule aucune intention durable et n'écrit aucun résultat fabriqué. Un backlog déjà en quarantaine exige la procédure de résolution existante avant bascule. Réappliquer `scripts/v3/grant-l5-admission.sql` sous le propriétaire de schéma avant de démarrer WEB WRITE. Le contrôle des privilèges effectifs de WEB refuse une ancienne ACL encore ouverte.

Les tables, colonnes et historiques sont candidats à une analyse de consolidation L8. L'analyse d'upgrade L8 doit préserver le rejeu des données historiques pertinentes.
