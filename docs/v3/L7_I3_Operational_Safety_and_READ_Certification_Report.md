# L7.3 — Qualification de sûreté opérationnelle et reconstruction READ

**Verdict provisoire `L7_I3_NOT_CERTIFIED`.** L7 reste ouvert. Les preuves locales doivent être rapprochées du gel candidat et de leurs frontières physiques.

## 1. Périmètre et matrice Consumption

COMMAND et PROJECTION vérifient les Burnables Command/IdentityRequest et les sept NonBurnables. FOUNDATION porte le contexte et le classement des transactions. WEB est concerné par la maintenance, les droits d'admission et les diagnostics. Le tracker Micrometer commun contribue aux trois tranches consommatrices. Les propriétaires L2, la gestion des claims, leases, fencing, UNKNOWN, slots et quarantaines restent dans les composants existants. Les gates Architecture et réacteur complet sont exigées. [Politique](../testing/Reactor_Verification_Policy.md).

| Obligations de la matrice | Oracles à rapprocher et limites |
|---|---|
| L2M-C01/C02, discovery, lease, claim, renew, fencing | `DiscoveryPostgresTest`, `IdentityDiscoveryPostgresTest`, `SlotNavigationPostgresTest`, `ConsumptionLifecyclePostgresTest`, `ReadParticipantsIntegrationPostgresTest`. Lire preuves des positions denses, checkpoints et refus de claims perdus par variante. |
| L2M-E02 et TX, pannes avant transaction, rollback, poisoning, commit confirmé ou UNKNOWN | `InterAuthorityPreparationPostgresTest`, `InterAuthorityRecoveryPostgresTest`, `V3TransactionFoundationPostgresTest`, `IdentityRequestExecutionPostgresTest`, oracles W14. Distinguer état observé depuis un autre canal, classification, restitution de capacité et publication. |
| L2M-C05, I5 et réserves de disponibilité | `V3WorkerResourcesPostgresTest` (dont `i5_r05`, `i5_r12`), W14 et `ReadWorkerResourcesPostgresTest`. Qualifier maintien de capacité et reprise, puis caractériser les délais sous la contention réellement exercée. |
| Isolement SQL, privilèges et pools | `WriteSourceReadPrivilegesPostgresTest`, `IdentityPrivilegesPostgresTest`, `V3ReadQuotaPostgresTest`, `V3SchemaIsolationPostgresTest` et refus SQL de la recette vierge. Les traces historiques d'ACL restent liées à l'ancien déploiement. |

La référence L7.1 fournit un réacteur de 1 088 tests, six frontières W14 et des observations physiques. La portée de chaque garantie est celle de son sujet et de la fenêtre SQL observée. Les FAIL et UNKNOWN historiques restent dans leurs campagnes. [Reçu historique](../testing/evidence/L7-full-execution/certification-966eb25a-awake/result.json), [W14](../testing/evidence/L7-full-execution/executions/w14-six-physical-boundaries.tar.gz), [allocations L2](L2_Certification_Coverage_Manifest.md).

Le rapprochement exploitable par ID contient les 380 lignes L2M, 136 correspondances nominatives avec le réacteur L7.1, 136 contributions bornées L2/I2, 46 lignes couvertes au moins partiellement par les allocations L2/I3, 78 rapprochements d’obligations aux méthodes I4 passées, et 11 allocations R10 du plan I5 reliées à leurs méthodes du réacteur L7.1. Le premier gate intégré contient 21 tests du Worker, 13 tests de ses ressources, 15 tests de préparation interautorités et 10 tests de reprise interautorités, tous réussis. Les six fichiers W14 y distinguent admission, acquisition, publication, suppression, commit et cession. L'index courant rattache les méthodes à leurs limites de fixture et au reçu d'invocation. [Oracle par obligation](../testing/evidence/L7-I2-I3/operational-current-oracles.json), [témoins L2](../testing/evidence/L7-I2-I3/l2-current-oracles.json), [tentative intégrée](../testing/evidence/L7-I2-I3/architecture-attempt-01/result.json). Les familles non rapprochées au niveau de la fenêtre restent en réserve. [Rapprochement par ID](../testing/evidence/L7-I2-I3/l2-oracle-reconciliation.json).

## 2. PostgreSQL, Runtimes et diagnostics

La recette démarre sur WRITE et READ séparés, avec les principals d'admission, de traitement, de source READ, d'effets READ, de consultation et de statut. Le bootstrap applique les ACL d'admission sans les droits de proposition Link. Le reçu du candidat observe six principals applicatifs sans attribut administratif et cinq refus SQL effectifs, tous `42501`. Il couvre le SELECT historique Link, la mutation via source READ, la mutation via HTTP READ, la lecture métier via le rôle de statut et l'insertion d'un routage de replay via le Runtime READ. Les trois Workers ont journalisé leur arrêt et la fermeture des pools. Les deux serveurs HTTP ont fermé leurs ressources après SIGTERM. [Reçu et journaux](../testing/evidence/L7-I2-I3/bruno-fresh-06).

Le contrôleur de diagnostic expurge les UUID de commandes, Pots et événements pour les deux routes. L'API métier exige un JWT. Le chemin Actuator de l'application sert un instantané expurgé avec ou sans JWT. Les deux routes refusent `clear=true`, plafonnent les réponses à 1 000 entrées et interdisent leur mise en cache. Le tracker borne le buffer indépendant d'événements à 10 000 et ramène les labels de Consumption inconnus à une valeur fixe. Le nouvel oracle HTTP exerce les deux routes sur un tracker peuplé, le rejet de la réinitialisation et la stabilité du contenu après ce rejet. Les journaux de corrélation locaux restent une source distincte pour le banc de mesure.

## 3. Reconstruction et reprise

Le reçu nominal `08014591` établit 28/28 checkpoints et 23 secondes dans la topologie historique. Il observe des vecteurs WRITE et des compteurs READ. Les preuves complémentaires portent sur la procédure exclusive, les données et les transitions d'autorité. [Rejeu nominal](../testing/evidence/L7-full-execution/executions/release-rebuild.log.gz).

| Obligation normative | Oracle et portée |
|---|---|
| RCV01-H | `rcv01H_quarantineAndIntegrityViolations_blockReady` exerce la quarantaine et une version courante privée de balance/visibilité. Le reset partiel coordonné doit être rapproché séparément. |
| RCV01-J/K | La composition standard sélectionne sept participants et leurs identifiants canoniques. Les tests nommés J/K de `V3ReadReconstructionPostgresTest` portent respectivement sur les droits et la reprise après DDL. Ils ne démontrent pas le rejeu concurrent à nouvel ID décrit dans SPEC-10. |
| RCV01-L | Le nouvel oracle de rejeu porte sur un préfixe capturé et une publication WRITE produite avant reprise. Le scénario d'arrivée continue demande un oracle temporel distinct. |
| RCV01-N | Les empreintes de tables métier et d'historiques complètent les compteurs. Le corpus contient un Pot multiversion, un Pot supprimé, leurs balances actives, les composants historiques, la visibilité, le binding et les résultats. La table Invitation est vide dans ce test. Un oracle dédié du même candidat a comparé les valeurs exactes de deux Invitations après rejeu sur READ neuf. |
| REC-01 | `V3ReadReconstructionPostgresTest`, `ReadMaintenanceBarrierTest`, `ReadinessOracleTest` et la preuve nominale se recoupent sur l'exclusion et le retour READY. Les exercices de panne et de relance sont crédités selon leur frontière effective. |

### Anomalie du contrôle de disponibilité

Le contrôle de cohérence Java et celui du runbook exigeaient une balance pour tout Pot courant. Un Pot supprimé devait donc rester bloqué avant READY alors que sa version terminale n'a aucune balance attendue. Les deux contrôles vérifient désormais le statut du manifeste et la présence exacte de balances pour toutes les versions actives. Une balance terminale ou un manifeste dont le marqueur de suppression est invalide bloquent le retour READY. Le test de rejeu contient les versions actives et terminale et observe les publications postérieures à la borne B après le retour READY. Il a passé en 4 min 12 s, avec 239 610 ms de bail restant après le drain. Un observateur SQL indépendant a constaté les 28 baux encore détenus alors que la base était READY. La reprise a porté POT et COMMAND_COMPLETION de 4 à 5 et terminé avec 28 checkpoints et zéro slot READ. Les scripts et les tests ont des portées distinctes. [Valeurs et préfixes](../testing/evidence/L7-I2-I3/read-replay-values.txt), [observation du bail](../testing/evidence/L7-I2-I3/read-replay-live-lease.json).

## 4. Réserves et qualification intégrée

Le premier gate intégré a terminé les modules applicatifs avec succès. Deux erreurs du banc Architecture provenaient d'une ancienne intention Link et ont été corrigées dans sa fixture. Le réacteur complet et le gate Architecture non filtré sont relancés sur le candidat corrigé. Les limites historiques de disponibilité sous contention, les variantes de reprise non attribuées, et les conditions de qualification hors topologie locale restent dans le registre. [Réserves](L7_I1_Residual_Reservations_Register.md), [preuves de campagne](../testing/evidence/L7-I2-I3).
