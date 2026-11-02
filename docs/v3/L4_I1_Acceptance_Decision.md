# Pocoma V3 — L4/I1 — Décision humaine définitive de clôture

**Décision du 2 novembre 2026**, accordée par le responsable du programme et
transmise par le mandat de clôture documentaire. Branche : `feat/v3-architecture`.
SHA de référence du dossier accepté :
`3bba9dbaa4fedfe040ed9f7e5d7ca2239b1ddab7`.

**L4/I1 — CLOSED — LOCAL BOUNDED PASS WITH EXPLICIT RESERVATIONS.**

La décision est définitive dans le périmètre I1. Elle autorise la poursuite du
programme vers **L4/I2 après son propre cadrage technique**. L4 reste ouvert ;
aucune certification globale de L4 ni des contrats futurs L5/L6 n’est accordée.
La présente mission enregistre la décision ; elle ne réalise ni audit nouveau,
ni implémentation, ni campagne de qualification.

## 1. Autorités et distinction des verdicts

| Niveau | Attribution et portée |
|---|---|
| Verdict d’implémentation | **IMPLEMENTED — LOCAL BOUNDED PASS**, conclusion de l’auteur dans le [rapport d’exécution](L4_I1_Execution_Report.md) ; stockage applicatif `533c14746b61395691845edf19f40e5444f9edd9`, correctif owner L2 `9e8c18a9830fa151808f6aef8a96ae978a199c87` |
| Qualification locale | PASS des campagnes archivées et attribuées dans le [manifeste I1](L4_I1_Proof_Manifest.json), avec leurs limites et leurs échecs antérieurs conservés ; aucun nouveau PASS produit par cet acte |
| Recommandation d’audit | Avis indépendant distinct de l’autoévaluation et de la décision humaine. Le mandat de clôture transmet les réserves de l’audit, sans fournir de rapport autonome identifiable ni d’intitulé exact de recommandation à reproduire. Aucun verdict d’audit supplémentaire n’est inventé. L’avis antérieur **GO WITH RESERVATIONS L4/I1** cité dans les plans concernait le cadrage, pas la qualification du code livré |
| Décision humaine définitive | **CLOSED — LOCAL BOUNDED PASS WITH EXPLICIT RESERVATIONS**, accordée le 2 novembre 2026 sur le dossier au SHA de référence ci-dessus ; autorité de clôture portée par le présent acte |

L’audit indépendant n’a pas constitué un nouveau rejeu physique PostgreSQL.
L’acceptation des preuves publiées ne lui attribue ni exécution Maven, ni campagne
PostgreSQL, ni vérification nouvelle de l’atomicité physique. Les anciennes
mentions « audit/clôture à venir » décrivent la livraison avant cette décision.
Les rapports, reçus et manifestes conservent leurs verdicts historiques.

## 2. Périmètre I1 accepté et preuves attribuées

La clôture accepte :

- le stockage immuable des huit catégories de sous-versions, leurs clés et valeurs ;
- les codecs stricts et la comparaison intégrale du contenu, y compris après conflit d’insertion concurrente ;
- la migration READ V2, en installation neuve et en extension d’une L2 peuplée, avec protections SQL d’immutabilité et permissions réelles ;
- l’atomicité physique des effets READ et de la suppression du slot L2, **attestée par les campagnes publiées** : même owner/PID/XID, snapshots extérieurs, rollback, contre-oracles et pertes d’ACK ;
- le correctif indispensable de poisoning tardif dans l’owner transactionnel L2 et ses preuves de régression attribuées à I1 ;
- les gates et contributions bornées I1 consignées ci-dessous.

| Campagne archivée | Invocation | Résultat accepté (tests/failures/errors/skips) |
|---|---|---|
| [I1 ciblée](../testing/evidence/L4-I1/targeted/result.json) | `bc033ccb299f41e2aec0411aacf208b7` | **37/0/0/0 PASS**, cinq classes I1 ; contribution filtrée |
| [FOUNDATION](../testing/evidence/L4-I1/foundation/result.json) | `e3ea8ce8a578468f804eb93ca3301c58` | **29/0/0/0 PASS**, contribution ciblée à l’owner corrigé |
| [PROJECTION + COMMAND](../testing/evidence/L4-I1/projection-command/result.json) | `d8eade5a0ea24492b2fbbdcce0efc6b1` | **1833/0/0/0 PASS**, gates non filtrées après correctif owner |
| [Architecture en checkout isolé](../testing/evidence/L4-I1/architecture/result.json) | `5779c9b92c5b45ae877ff1367625b7ea` | **2096/0/0/0 PASS**, dont 164 dans le module Architecture ; gate non filtrée |

Les commandes exactes, configurations et attributions sont dans le manifeste,
les reçus et l’[index des preuves](../testing/evidence/L4-I1/README.md).
Les gates finales comportent `-Dstep3b.measure=true` pour exécuter le test
conditionnel historique sans SKIP ; aucune certification de performance n’en
est déduite. Les [60 traces physiques](../testing/evidence/L4-I1/Physical_Index.json)
restent celles de la contribution ciblée archivée.

Les campagnes ont exécuté le HEAD initial
`4028f2fa9f053e75dd60bb0ccefd091014a4fb9d` avec modifications applicatives non
committées et empreintes d’entrées complètes. Le manifeste attribue les 1282 blobs
applicatifs du commit final à ces octets testés. Le SHA `3bba9dba…` est celui de
la publication du dossier accepté ; **aucune exécution en checkout propre à ce
SHA ni au futur commit documentaire de clôture n’est revendiquée**.

La frontière d’implémentation initiale PROJECTION a été élargie explicitement à
COMMAND pour le correctif indispensable `infra-tx-spring`. La contribution
FOUNDATION et la gate globale Architecture restent attribuées à cette livraison.
Le full reactor était **NOT REQUIRED / NOT RUN** pour cette qualification bornée.
Le mode PostgreSQL était CURRENT V3 ; les migrations historiques entraînées
transitivement par les gates restent attribuées à celles-ci.

La clôture **ne certifie pas** la préparation interautorités I2, les
matérialisations métier I3–I7, leur activation I8, PRJ/READ/SEC/OPS/PERF ou SPEC-10
globalement, la qualification globale de L4, HTTP ni les futurs contrats L5/L6.
Les fixtures techniques ne deviennent pas des participants métier acceptés.

## 3. Réserves explicites conservées

1. **Preuves PostgreSQL archivées.** Les preuves acceptées sont celles des
   campagnes publiées. L’audit indépendant n’a pas constitué un nouveau rejeu
   physique ; cette mission documentaire n’en réalise aucun non plus.
2. **FAIL Architecture initial conservé.** Le [FAIL 1500/0/2/0](../testing/evidence/L4-I1/architecture-stale-bytecode-failure/result.json),
   dû aux bytecodes WEB suspects, reste accessible avec son
   [diagnostic](../testing/evidence/L4-I1/web-bytecode-diagnostic/Inputs.json).
   La qualification ultérieure en checkout isolé aux mêmes octets applicatifs
   est acceptée dans ses limites ; elle n’efface ni ne requalifie ce FAIL.
3. **Mémoire du codec et volumes métier.** Les bornes mémoire du codec et les
   volumes réels des matérialisations devront être examinés dans les incréments
   métier concernés. Cette obligation future ne constitue pas un défaut I1
   établi et ne vaut aucune certification de capacité ou de SLO.
4. **Régression L2 en I2.** Le correctif I1 de poisoning tardif devra figurer
   dans l’analyse de régression I2, notamment lors de l’introduction de
   `PreparationReads`. Sa propriété de refus du commit après erreur invalidante
   interceptée en callback tardif doit être préservée.

Ces réserves ne créent ni certification générale ni blocage artificiel de la
poursuite. Les réserves historiques L2/L3, les FAIL, contre-preuves, refus de
collecte et résultats antérieurs restent conservés, sans levée implicite.

## 4. Transition vers L4/I2

**I2 peut désormais être engagé après son propre cadrage technique**, dans le
séquencement du [plan global L4](L4_Read_Inventory_and_Implementation_Plan.md#i2--préparation-et-recovery-interautorités-dans-l2).
Le prérequis d’acceptation I1 est satisfait. L’arbitrage A1, l’API exacte et le
périmètre de requalification restent à arrêter lors de cette mission distincte.
Cet acte ne crée pas de plan I2 et n’en démarre pas l’implémentation.

Obligations à reprendre au cadrage I2 :

- `PreparationReads` et snapshots source/effets **séquentiels**, à durée de vie
  explicite, sans lecture WRITE cachée pendant la transaction finale READ ;
- état d’invalidation ou d’incertitude **irréversible possédé par L2** : un
  UNKNOWN ou scope mal résolu interdit la suite et la finalisation, même après
  interception d’exception ; aucune libération spéculative de capacité ;
- préservation des préparations Command et Identity : snapshot WRITE unique
  pour Command ; décision Identity sous verrous EUI en ordre canonique ;
  transactions finales READ_COMMITTED et contrats métier WRITE préservés ;
- recovery articulant checkpoint de la consommation, slot natif et preuves de
  publication dans un même snapshot READ cohérent ; références source immuables
  préparées séparément, sans capturer de verdict de commit ;
- **absence de transaction distribuée**, d’imbrication source/effets et de
  protocole parallèle contournant l’owner L2 ;
- requalification ciblée des contrats L2 affectés : préparation, scopes/owners,
  recovery, UNKNOWN et capacité, avec non-régression réelle Command/Identity et
  comparaison explicite avant réutilisation de preuves ;
- préservation et analyse de régression du **poisoning tardif corrigé en I1**,
  notamment avec les nouveaux scopes et callbacks de `PreparationReads`.

D1–D4 restent acquis : aucune rationalisation Maven/runtimes dans cette clôture ;
le chantier structurel reste après qualification fonctionnelle de L6.

## 5. Vérification documentaire et publication

Périmètre déclaré : **slices NONE** ; aucun changement sous `app/`, de POM,
migration, configuration, test ou script. Gate globale **NOT REQUIRED / NOT RUN** ;
full reactor **NOT REQUIRED / NOT RUN**, car mission strictement documentaire ;
PostgreSQL **NOT RUN**. Aucun franchissement inattendu de slice.

Les contrôles portent sur les instructions et l’état Git initial, les références
du dossier publié, la cohérence des statuts et des attributions, les liens locaux
et ancres, le diff et le whitespace. Commandes de contrôle : `git status --short
--branch`, `git branch --show-current`, `git rev-parse HEAD`, `git worktree list`,
`git remote -v`, `git log`, `git ls-remote origin refs/heads/feat/v3-architecture`,
lectures `rg`/`sed` et Python en lecture seule pour les reçus/liens, puis
`git diff --check` et revue du diff. Aucune campagne Maven/PostgreSQL, aucun
collecteur de qualification, aucune modification des preuves archivées.

Publication sur `feat/v3-architecture` : commit documentaire, push sans force,
contrôle du SHA distant, de la synchronisation **0/0** et du worktree propre.
Le SHA final et le résultat effectif de ces contrôles figurent dans le rapport de
livraison après publication, sans référence autoréférente dans cet acte.
