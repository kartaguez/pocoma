# Reprise de l'étude — numérotation tardive des Events

Statut : étude de conception, aucune implémentation à cette étape.
Base inspectée : merge `322042ded0191596c29348500fd23ed68d280d8c`, contenant
`675533e7`, `4158de69` et `2eef3ab9`.

## Invariant de préparation

Toute décision métier préparée avant l'acquisition de l'autorité d'écriture
doit être fondée sur un état versionné cohérent ; le CAS final doit valider
précisément cette version. La résolution R03 garantit ce contrat pour les
contextes Expense Delete/Details/Shares. Elle ne prouve pas automatiquement
la même propriété pour chaque autre contexte ou producteur.

La lecture historique repose sur l'immutabilité logique respectée par les
writers applicatifs : fermeture à W > V, nouveau payload à W, pas de purge ni
réécriture rétroactive. La protection SQL des historiques reste incomplète.
Voir [résolution J](Transactional_Authorities_Fencing_Locks_Audit_2026-10-18.md#j-résolution--contextes-expense-cohérents-à-v-2026-10-18).

## Point de composition confirmé dans les sources

La [configuration Command](../../app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/command/consumption/CommandConsumptionRuntimeConfiguration.java)
construit un `ExecuteConsumptionService`, le place dans
`TransactionalExecuteConsumptionUseCase`, puis dans
`BindingFenceRecoveryExecuteUseCase`. Le participant brut est aussi transmis
à la recovery. Décorer seulement le premier chemin transactionnel laisserait
donc la recovery sans numérotation.

Le [wrapper transactionnel](../../app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/service/transaction/consumption/TransactionalExecuteConsumptionUseCase.java)
retourne après `runInTransaction`. Le suffixe doit s'exécuter dans le supplier,
après la finalisation du participant et avant son retour au runner.
Le [runner propriétaire](../../app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/ConsumptionOwnerTransactionRunner.java)
ne confirme le résultat qu'après confirmation du commit physique.

La [finalisation générique](../../app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/service/consumption/FinalizeConsumptionService.java)
applique effet durable et provenance avant `lifecycle.terminalize`.
Le [lifecycle JPA](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/consumption/JpaConsumptionLifecycleAdapter.java)
réalise le CAS du slot puis la clôture du claim. Le suffixe envisagé vient
après ces deux opérations ; une erreur doit encore annuler tous les effets.
Ce serait une évolution explicite du contrat terminal actuel, à concevoir
avant tout changement du moteur générique.

L'[append Pot](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/outbox/JpaPotCommandEventAppendAdapter.java)
fait `saveAllAndFlush` et retourne des artifacts avec `OptionalLong.empty()`.
Le CAS du [repository Slot](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/repository/consumption/JpaConsumptionSlotRepository.java)
flush puis clear le contexte JPA. Une future attribution tardive ne doit donc
pas dépendre du dirty checking des entités Event déjà détachées.

## Décisions à trancher avant un prototype

| Sujet | Exigence / décision restante |
|---|---|
| Familles | Inventorier BusinessEvents, terminal Events, BindingFacts et UserCreatedFacts ; décider explicitement lesquelles numéroter et quels outcomes restent hors périmètre. |
| Enveloppe | Comparer position attribuable une fois sur Event et table de publication séparée, avec immutabilité du payload dans les deux options. Aucun choix arrêté. |
| Exhaustivité | Registre local à la transaction des IDs réellement insérés, couvrant JPA et JDBC, succès, rejet, failure, recovery et producteurs sans Consumption. |
| Commit incomplet | Protection SQL de l'état final refusant tout Event concerné sans publication ; oublier le finaliseur doit faire échouer le commit. |
| Ordre des locks | Autorités métier, contraintes uniques, provenance, CAS slot et clôture claim avant compteurs ; ordre total des familles ; aucun retour vers un lock métier après le premier compteur. |
| Historique | Définir bootstrap, compatibilité et conservation ; ne pas reconstruire un ancien ordre de commit à partir des timestamps. |
| Discovery | Étude séparée du curseur et des trous de traitement ; une position de publication ne prouve pas la consommation des positions précédentes. |

## Prochaines preuves nécessaires

Avant production, un prototype PostgreSQL devra rendre réfutables : rollback
après allocation, oubli de finaliseur au commit, transaction longue versus
transaction courte de même famille, plusieurs Events/familles, claim perdu,
recovery Binding et commit refusé. Il faudra observer positions, compteurs,
mutations, provenance et terminal Events après résolution physique.

Mesurer ensuite attente et durée de détention des compteurs ainsi que
propagation entre Pots indépendants. Aucun gain de débit n'est établi.
L'étude [F](Transactional_Authorities_Fencing_Locks_Audit_2026-10-18.md#f-faisabilité-dun-compteur-event-transactionnel-tardif)
reste la référence conceptuelle ; cette reprise précise la composition
Command et le contrat de préparation, sans nouvelle preuve runtime Event.

## Verification scope

Étape actuelle : documentation et lecture des sources uniquement ; aucune
modification sous `app/`. Les tests de l'intégration Expense/Binding ne sont
pas une preuve de numérotation, qui n'existe pas encore.

Pour un futur prototype, déclarer les slices après inventaire des producteurs :
COMMAND pour les producteurs Command, BINDING pour les faits Binding, WEB si
un producteur hors worker est concerné, EVENT si la discovery change.
Le gate structurel sera requis si contrats, dépendances ou frontières sont
modifiés. Un changement transverse non bornable ou une nouvelle baseline
globale imposera le full reactor selon la politique. Le périmètre précis sera déclaré avant les modifications de production.
