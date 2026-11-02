# L3 — Acte officiel de clôture Identity WRITE Engine

**Ratification : 1er novembre 2026 — L3 — Identity WRITE Engine : CLOSED WITH EXPLICIT RESERVATIONS.**

## Autorité, dossier et décision humaine

Le responsable du programme Pocoma V3 a explicitement approuvé la clôture dans
le mandat « L3/I5 — Publication de la clôture documentaire définitive » et donné
GO pour sa publication sur `feat/v3-architecture`. Ce document est l’acte de
clôture unique ; le rapport, le manifeste et les index en répercutent la décision.
Baseline documentaire examinée : `ece3f5057808c4c99afd31d276acf043fe7872b4`.
Aucun nom personnel, signature ou rapport original non fourni n’est inventé.

La décision s’appuie sur le [plan I5](L3_I5_Qualification_and_Closure_Plan.md),
le [rapport et ses matrices 27/17](L3_Identity_Execution_Report.md), le
[manifeste](L3_Identity_Proof_Manifest.json), le [dossier probatoire](../testing/evidence/L3-I5/README.md)
et les décisions [I1](L3_I1_Arbitration_and_I2_GO.md),
[I2](L3_I2_Acceptance_Decision_2026-10-30.md), [I3](L3_I3_Acceptance_Decision.md),
[I4](L3_I4_Acceptance_Decision.md). Les acceptations historiques restent bornées.
ARB-I5-01 autorisait la préparation ; la présente décision distincte prononce la clôture.

| Obligation | Statut final ratifié |
|---|---|
| O20 — Architecture globale | **SATISFIED WITH EXPLICIT RESERVATIONS** |
| O21 — COMMAND + Monolithe | **SATISFIED WITH RESERVATIONS** |
| O25 — Audit final et décision humaine | **SATISFIED WITH EXPLICIT RESERVATIONS** |

Ces statuts sont des décisions de recevabilité interne ; ils ne changent aucun
verdict d’exécution strict ni la règle zéro-SKIP de la politique Reactor.

## Ratification nominative du SKIP

Le responsable classe
`com.kartaguez.pocoma.runtime.command.consumption.sequencing.TerminalSequencingPostgresTest#zExploratoryContentionSeparateFromCorrectness`
**OPTIONAL_EXPLORATORY_PERFORMANCE** pour la qualification interne **L3/I5**.
Son SKIP conditionnel est accepté nominativement, sans dispense générale.
La source identifiée porte le SHA-256
`2c44f3350ee5f79a8284b811c980411e276f2dc2d7660c715dfdb49bc782668f`.

La décision repose sur la condition `step3b.measure=true`, la mesure exploratoire
de contention et ses assertions de validité expérimentale, distinctes des
obligations de correction. Les **treize tests indépendants de correction PASS dans
chacune des deux gates** et l’absence d’autre SKIP rapporté dans ces campagnes
sont documentés dans l’[analyse nominative](L3_Identity_Execution_Report.md#qualification-nominative-du-skip--proposition-du-1er-novembre-2026).
Les assertions ne sont ni supprimées ni réputées absentes. La méthode reste
incluse dans les commandes canoniques ; aucune propriété de test n’est changée.

| Campagne conservée au SHA `cfd3f6458f7de61945bd3f73cb71c794dddf9e57` | Tests | Failures | Errors | SKIP | Verdict strict |
|---|---:|---:|---:|---:|---|
| O20 / `cf3c434170594c8597027aca4cdabe2e` | 2057 | 0 | 0 | 1 | **INCONCLUSIVE** |
| O21 / `2dd51d57245e44bfb794dd0bb74e387e` | 1791 | 0 | 0 | 1 | **INCONCLUSIVE** |

O20 compte 61 modules SUCCESS et Architecture 163 PASS ; O21 compte 52 modules
SUCCESS avec Worker COMMAND et Monolithe exécutés. Les XML, archives, reçus,
comptes et verdicts historiques sont conservés aux octets d’origine. La décision
ne vaut ni certification globale ni exception réutilisable pour d’autres tests.
Les obligations SPEC-09/SPEC-10 PERF-01 restent dues. Toute évolution du source,
des assertions ou du contrat impose son propre examen.

### Résolution définitive post-clôture du SKIP exploratoire (2 novembre 2026)

Conformément au mandat « L3/I5 — Résolution définitive du SKIP exploratoire », la
tolérance temporaire `OPTIONAL_EXPLORATORY_PERFORMANCE` est levée pour les exécutions
courantes et futures par application de l’issue **B — Conversion en benchmark +
couverture fonctionnelle déterministe** depuis la baseline
`3bba9dbaa4fedfe040ed9f7e5d7ca2239b1ddab7` :

1. **Extraction en benchmark explicite hors découverte Surefire/JUnit :** la campagne
   exploratoire de contention (grille `concurrence ∈ {1,4} × events ∈ {1,8} × familles ∈ {1,2}`,
   3 répétitions alternées `enabled ∈ {false,true}`, chauffe 8, mesure 40, sortie
   `STEP3B CSV`) est extraite dans
   [`TerminalSequencingContentionBenchmark`](../../app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/consumption/sequencing/TerminalSequencingContentionBenchmark.java)
   (point d’entrée `main`, sans annotation JUnit ni suffixe `*Test`).
2. **Couverture fonctionnelle déterministe inconditionnelle :** dans
   [`TerminalSequencingPostgresTest`](../../app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/consumption/sequencing/TerminalSequencingPostgresTest.java),
   l’annotation `@EnabledIfSystemProperty(named = "step3b.measure", matches = "true")`
   est supprimée et remplacée par le test inconditionnel
   `kConcurrentMultiWorkerBatchContentionAndUnsequencedControl`, qui vérifie de
   manière déterministe (a) le témoin `sequence = false` sous concurrence (4 workers,
   barrières explicites `CountDownLatch`, 128 événements persistés, 0 position et
   compteurs à 0) et (b) la contention réelle multi-workers/multi-familles
   `sequence = true` (observation directe dans `pg_stat_activity` de la file d’attente
   PostgreSQL `wait_event_type = 'Lock'` des 3 workers suiveurs derrière le détenteur
   de `experiment.authority`, continuité exacte `1..64` par famille active, ordre
   canonique `E:BUSINESS_EVENT → E:COMMAND_TERMINAL`, `terminalTrace` sur les 16
   transactions).
3. **Résultat de qualification :** `TerminalSequencingPostgresTest` exécute désormais
   ses **14 tests inconditionnels (`14/0/0/0 PASS`)** sans propriété système
   `-Dstep3b.measure=true`. Plus aucun `@EnabledIf*` ou `@Disabled*` ne subsiste sous
   `app/`. Les archives et verdicts historiques O20/O21 au SHA `cfd3f645` demeurent
   inchangés.

## O25 — portée réelle de l’audit et de la décision

L’avis favorable de l’audit indépendant final O25 est **transmis par le responsable
dans le mandat de publication**. Il s’agit d’un audit sur pièces favorable à la
clôture interne. Son rapport original, l’identité de l’auditeur, sa date exacte et
le relevé détaillé des contrôles réalisés ne sont pas fournis dans le dépôt ni
dans ce mandat : aucune liste exhaustive de contrôles indépendants n’est donc
attestée ici. L’avis est enregistré avec cette limite de provenance.

Les preuves expérimentales proviennent des campagnes existantes : Q-ID (trois
probes PASS, sept demandes, oracles SQL, deux drains et restart), contributions
I3/I4 et campagnes canoniques O20/O21. Les vérificateurs existants permettent de
contrôler les octets, membres, attributions, compteurs et reçus ; leurs résultats
ne sont pas attribués à l’auditeur O25 sans source. Les contrôles documentaires
exécutés pour cette publication sont consignés séparément dans le
[rapport](L3_Identity_Execution_Report.md#publication-de-la-clôture-ratifiée--1er-novembre-2026).

**Limites de vérification indépendante : aucune nouvelle exécution Maven ou
PostgreSQL et aucun recalcul indépendant exhaustif des archives lors de l’audit
O25.** Cet avis sur pièces n’est pas une certification expérimentale exhaustive.
La décision humaine finale approuve expressément la clôture malgré ces limites
et toutes les réserves documentées : **O25 SATISFIED WITH EXPLICIT RESERVATIONS**.
Les contrôles de publication par Codex ne constituent pas un nouvel audit indépendant.

## Périmètre et réserves maintenues

La clôture est limitée au WRITE Identity qualifié : autorité EUI/User,
Registration/Link/Unlink, histoire et tokens, atomicité/publication et raccord
au moteur L2 dans les compositions Worker/Monolithe JPA testées. A1 conserve
le seul SELECT technique anti-effets de `JdbcPotCommandStore.requireCompletionReady`
sur `v3_pot_version`, sans lecture, verrou ou mutation métier Pot.

Toutes les réserves [L2](L2_Documentary_Closure.md) et celles du
[rapport L3](L3_Identity_Execution_Report.md#réserves-conservées-et-proposition-soumise-à-décision)
restent explicites : R-L2-01…05, I3 R1…R4, limites temporelles et de disponibilité,
composition JPA seule, confiance administrateurs PostgreSQL, canaux PG/Docker
qualifiés seulement, H3 expurgé et provenance limitée des audits historiques.
Les anciens FAIL, SKIP et INCONCLUSIVE ne sont pas réécrits ; G1/G2 restent
INCONCLUSIVE et G3 NOT PASSED / NOT COMPLETED. Les limitations de découverte
46/67 restent historiques, sans limitation résiduelle rapportée à `cfd3f645`.

La clôture ne signifie ni certification complète SPEC-10, ni qualification des
performances de production, ni certification universelle des hypothèses de
contention et de renouvellement des leases, ni qualification des parcours READ,
HTTP ou consentement futurs, ni autorisation de déploiement productif.

## Reports nominatifs conservés et ouverture de L4

- L4 : CurrentBinding monotone à version exacte, retard/rattrapage READ, contribution I01-Q.
- L5 : AuthN/sessions propres E1/E2, accès autorisé au token, consentement externe,
  HTTP/Bruno et SEC-01/02 ; aucun endpoint ou format HTTP défini par cet acte.
- L6–L8 : capacité/SLO, reconstruction, convergence, intégration majeure et release.

Les obligations WRITE internes I01-I/P restent L3 et sont complétées par Q-ID.
Les tables d’autorité sont `v3_pocoma_user`, `v3_eui_authority`, `v3_eui_version` ;
publication via `v3_identity_event`, `v3_identity_completion_event`, compteurs et
`v3_event_position`. Aucun nom de table inventé n’est imposé aux lots futurs.

**L4 peut désormais être ouvert à son cadrage du fait de la clôture L3, après
publication et vérification de cet acte.** Le
[plan directeur](V3_Implementation_Master_Plan.md#orientation-à-instruire-au-cadrage-l4--qualification-réutilisable-de-l2-fondation)
porte l’orientation Fondation/Branchement à instruire. Cette mission ne commence
aucune implémentation L4 et n’autorise aucune réduction des gates existantes.

L’ancienne clôture prématurée `0ba18ec1` reste rectifiée et récupérable via
[Historical_Rectification.json](../testing/evidence/L3-I5/Historical_Rectification.json).
La ratification présente n’en valide pas rétroactivement les déclarations.
Le SHA documentaire publié est fourni après commit/push, sans autoréférence du manifeste.
