# Audit de rationalisation documentaire — Pocoma

Date : 2026-10-03. Portée : lecture seule du dépôt, sauf création de ce rapport. Aucun déplacement, suppression, réécriture de document existant ou changement de code. Aucune commande Maven.

## 1. Baseline

| Élément | Constat |
|---|---|
| Branche | `v2-make-it-pull` |
| HEAD local et `origin/v2-make-it-pull` après `git fetch origin` | `c2000e56d7b03a9fc39040f4892e562b36a4e0f3` — `docs: close registration wave 3` |
| Divergence initiale | `0` commit distant seulement / `0` commit local seulement |
| Working tree initial | `?? docs/architecture/mermaid-diagram.png` seulement |
| PNG utilisateur | Non suivi, non lu comme source normative, laissé intact et exclu du commit |

Commandes de baseline : `git fetch origin`, `git status --short`, `git rev-list --left-right --count origin/v2-make-it-pull...HEAD`, `git log -1 --oneline`, `git branch --show-current`, `git rev-parse HEAD`, `git rev-parse origin/v2-make-it-pull`.

## 2. Executive summary

**GO pour une migration documentaire par lots, sous condition de validation de la vérité globale avant tout déplacement.** La Registration fonctionnelle est close au HEAD ; son ancien en-tête de cadrage et des constats pré-implémentation ne sont plus actuels. POT_E2E est livré ; sa mesure de latence reste exploratoire. ARCHITECTURE contient un vrai chantier de refonte des frontières de modules, mais aussi des audits et la Wave R1/R2 terminée : statut MIXED. `docs/plans/` contient 43 documents : 0 plan actif fiable, 37 plans exécutés ou dossiers de réalisation, 6 plans ou designs globalement dépassés. Les sous-parties périmées de plans exécutés doivent être signalées avant conservation historique.

La source actuelle est fragmentée entre 15 notes `docs/architecture`, des Steps, des plans longs, `README.md` et des procédures. L'index `docs/README.md` est lui-même périmé. Le futur contrat de lecture devrait être : **Functional Model** (comportement), **Architecture** (mécanismes présents), **System Guarantees** (propriétés et limites), puis des références spécialisées et des historiques explicitement étiquetés. Les trois textes sont une cible à écrire et valider, non des garanties déjà documentées en un endroit unique.

## 3. Topologie documentaire actuelle

`find docs -type f` donne 98 fichiers : 94 Markdown, 3 SQL de runbook et 1 PNG non suivi ; l'inventaire des Markdown significatifs figure intégralement en §12. Répartition relevée par entrée immédiate : `architecture/` 16 fichiers dont le PNG ; `plans/` 43 ; `steps/` 27 (12 `current`, 15 `completed`) ; `operations/` 6 dont 3 SQL ; `testing/` 2 ; `development/` 1 ; trois Markdown racine (`README.md`, `projection-workers.md`, `use-case-families.md`). S'ajoutent `README.md`, `AGENTS.md`, `scripts/bruno/README.md`, `scripts/k6/README.md` hors `docs/`. Aucun ADR sous un répertoire dédié n'a été trouvé par l'inventaire récursif ; les décisions sont insérées dans canons, plans et audits.

La politique normative de vérification est **`docs/testing/Reactor_Verification_Policy.md`**, chemin effectif dans ce checkout. L'instruction d'agent mentionne `app/docs/testing/...`, absent ici ; aucun changement sous `app/` n'est entrepris. Slice déclarée : **audit documentaire seul**, sans slice Maven, base ni gate architecture. Un gate global et le full reactor ne sont pas requis.

## 4. Problèmes de taxonomie

1. `current` porte des sujets terminés (`REGISTRATION`, `POT_E2E`) et un sujet mixte (`ARCHITECTURE`). Un chemin n'indique donc ni activité ni autorité.
2. Des plans datés sont cités comme références courantes ; le plan directeur Lot 7 affirme encore `7.10 NOT_STARTED` et `7.11 NOT_STARTED`, alors que l'autorisation et le parcours Pot E2E existent. Les notions `latestKnownVersion`, tête et génération `serving` de ses sections Query sont explicitement remplacées par `architecture/read-side-target.md`.
3. Les notes d'architecture alternent description en production, cible non livrée et historique. `authorization-kernel-contracts.md` dit lui-même décrire une cible « pas nécessairement le code déjà livré » ; son contenu ne doit pas être recopié comme état actuel sans confrontation.
4. Des archives parlent au présent : `REGISTRATION/Step_Canon.md` annonce « FRAMING CLOSED / next implementation planning » ; `ARCHITECTURE/Read_Materialization_Gap_and_Migration_Plan.md` annonce Registration absente du code, alors que les modules, migrations V27–V29, controllers et runtimes existent.
5. `docs/README.md` dirige vers `docs/steps/EPT/*`, désormais sous `steps/completed/EPT`, et vers `consumption-task-balance-runtime.md`, absent. `README.md` répète ce dernier lien. Les liens historiques et absolus de machine aggravent la confusion.

## 5. Taxonomie cible proposée

```text
docs/
  README.md                         # porte d'entrée, très courte
  product/Functional_Model.md       # contrat fonctionnel actuel
  architecture/Architecture.md      # architecture exécutée, synthèse
  guarantees/System_Guarantees.md  # propriétés, modèle de cohérence, limites
  architecture/references/          # détails techniques réellement maintenus
  engineering/                      # vérification, CI, procédures de développement
  operations/                       # runbooks valides et SQL associé
  steps/current/                    # chantiers effectivement actifs
  steps/completed/                  # lots livrés, preuves et décisions historiques
  archive/                          # propositions abandonnées ou supplantées
```

`docs/archive/` est préférable à `steps/completed/archive/` : les plans Lot 7, anciens designs d'architecture et documents racine supplantés ne sont pas tous des Steps exécutés. `steps/archived/` suggérerait à tort qu'ils sont tous des Steps. `completed` signifie **travail effectué et clos**, même si son design n'est plus le design courant ; `archive` signifie **proposition non exécutée, abandonnée, ou document dont la thèse centrale a été remplacée**. Un plan exécuté avec un paragraphe obsolète reste en `completed`, avec bandeau historique et pointeur vers la règle actuelle. La présence sous `archive` ou `completed` n'est jamais une autorité normative actuelle.

## 6. Définition des trois documents globaux

| Document | Question / contenu | Exclusions et règle d'autorité |
|---|---|---|
| `product/Functional_Model.md` | Que fait Pocoma ? User, E, occurrence Binding B, RegistrationRequest/Result, Command/Result, Pot, membres, droits, ownership historique, parcours Registration→première Command, visibilité, HTTP observable, rejets. | Aucun détail Maven, JPA ou worker. Distinguer `202` de l'issue métier ; préciser la lecture 404 opaque et les `409/503` exacts là où ils sont réellement exposés. |
| `architecture/Architecture.md` | Comment ? WRITE/READ, chaîne Command→outcome/Event, Result direct, Event→ProjectionTask→projections versionnées, Binding Fact→CURRENT_BINDING direct, Consumption/claims/fencing, transactions, retries, neuf modules `runtime-*` présents, dont les workers Result et Registration, modules/ports/adapters, sécurité HTTP. | Décrire **le code présent**, avec renvoi aux références spécialisées. Isoler les refontes de modules et READ futures dans un Step actif, sans les présenter comme exécutées. |
| `guarantees/System_Guarantees.md` | Quelles propriétés ? invariant et portée, preuve, limite, panne/reprise, cohérence et visibilité. | Nom préféré à `Service_Commitments` (évoque un SLA) et `Operational_Contract` (trop étroit). Aucun SLO inventé. Chaque affirmation distingue **guaranteed by design/code**, **proven by tests**, **measured**, **target**, **not specified**. |

Ces trois synthèses sont normatives après validation contre le code et les tests. Une note spécialisée peut détailler une règle sans contredire la synthèse ; un Step clos ne peut plus ajouter silencieusement une règle courante.

## 7. Audit de `steps/current`

| Sujet | Verdict | Preuves et action cible |
|---|---|---|
| `ARCHITECTURE` | **MIXED** | `Consumption_Workers_Runtimes_Audit` est un snapshot ; `Three_Engine_Families_Revision` remplace explicitement sa cible de 43 modules et laisse un ordre de migration futur. `Read_Materialization_Gap...` consigne R1/R2 DONE (`7eb1ea69`) puis garde des tableaux Registration pré-implémentation désormais faux. `Read_Usage...` précède la migration Result. Conserver en `current` seulement un Step rebaseliné sur le travail de frontières réellement ouvert, après synthèse des faits actuels ; verser les audits/Wave exécutée à l'historique. |
| `POT_E2E` | **COMPLETED** | `Step_Plan` décrit tests PostgreSQL HTTP→workers→Result→AUTH/READ_POT@V et Bruno `00 Pot E2E`; `app/architecture-tests/.../CommandCompletionE2EPostgresTest.java` est présent. Le plan énonce ses exclusions ; elles ne sont pas une dette du Step. `E2E_Latency_Observability_Audit` est un diagnostic de mesure approximative, sans instrument de latence exacte ni SLO. Synthétiser parcours et limites, puis déplacer le dossier en completed. |
| `REGISTRATION` | **COMPLETED** | HEAD `c2000e56` clôt Wave 3 ; `Step_Plan` journalise REG.1–REG.5/E1, R1/R2, C1, architecture gate et full reactor passés à la clôture. Code présent : `engine-registration`, admission/GET HTTP, workers Registration et Result, migrations V27–V29, test E2E Registration→Command. FIX.1 (locking Binding optimiste) a eu un NO-GO PostgreSQL, est **différé à un chantier distinct** ; ce n'est pas un reliquat de Registration fonctionnelle. Le canon garde un en-tête ancien : signaler l'historicité, extraire les règles, puis déplacer tout le dossier en completed. |

## 8. Audit exhaustif de `docs/plans`

43 fichiers ; **CURRENT NORMATIVE 0, ACTIVE PLAN 0, COMPLETED PLAN 37, SUPERSEDED 6, ABANDONED 0, DUPLICATE 0, HISTORICAL REFERENCE comme usage de tous les fichiers conservés**. Le statut principal de chaque fichier figure dans la matrice §12. `COMPLETED PLAN` indique une réalisation identifiable par code, tests, notes DONE ou suite de commits ; il n'affirme pas que chaque phrase est encore vraie. Les 6 `SUPERSEDED` sont `20260828-000000-v2-plan`, `20260828-234057-step-3-plus-roadmap`, `lot-7-read-side-implementation-plan`, `lot-7.2-read-store-boundary-plan` (objectif partiellement livré, plan global ancien), `lot-7.9.2-query-version-resolution-design` (marqué IMPLEMENTED mais contrat latest-known/serving désormais supplanté), `pipeline-event-materialization-plan` (bandeau superseded explicite). Les corrections de nommage 2.3 et de 7.9.1 sont des décisions exécutées, non des doublons à supprimer. Le Lot 7.7 et le Lot 7.1 sont exécutés mais portent des segments explicitement superseded ; les conserver avec avertissement historique. La réduction de `docs/plans/` comme source d'autorité est possible après extraction et reclassification, jamais par suppression en masse.

## 9. Sources architecturales, recouvrements et lacunes

| Sujet | Meilleure source actuelle à confronter au code | Recouvrements / contradiction / consolidation |
|---|---|---|
| Consumption, fencing, transactions, retry | `architecture/consumption-transactional-execution.md`, `command-consumption-runtime.md`, Steps EPT/PCL/WA clos | Plans 2.x/3.x et Lot 1–5 répètent les contrats ; réunir claim CAS, effet atomique, takeover, terminal failure. |
| Command, Events, Results | `recorded-command-intake.md`, `recorded-command-persistence.md`, `write-side-closure.md`, `steps/current/ARCHITECTURE/Read_Materialization_Gap...` journal R1/R2 et `steps/completed/CCR` | `recorded-command-intake.md` prétend qu'aucun GET Result n'est fourni par son lot ; vrai historiquement, faux comme description globale. L'ancien Event→Task COMMAND_RESULT décrit plus bas dans l'audit ARCHITECTURE est retiré. |
| Projections, READ | `read-side-target.md` pour le modèle exact actuel, `projection-engine.md`, `projection-task-execution.md`, `read-side-current-state.md` pour le snapshot | Plan directeur Lot 7 / design 7.9.2 conservent latest-known/head/serving ; cible `read-side-target` les remplace. Distinguer READ actuel de sa cible de migration restant à livrer. |
| Binding, Registration | `steps/completed/WRITE_ADMISSION/Step_Canon.md`, `steps/current/REGISTRATION/Step_Plan.md` section de clôture, `Step_Canon.md` corrigé par le plan global, code V27–V29 | `ARCHITECTURE/Read_Materialization_Gap...` dit encore Registration absente ; `Current_Binding_Registration_Architecture_Audit` conserve hypothèse abandonnée. `CURRENT_BINDING` est direct depuis Binding Fact, non une ProjectionTask standard. |
| Modules, ports/adapters, runtimes | `module-dependency-matrix.md`, `type-ownership.md`, `ARCHITECTURE/Three_Engine_Families_Revision.md` pour la **cible**, POM/modules présents pour l'actuel | Matrices et révision proposent des propriétaires futurs : marquer cible versus existant. Le nombre « six runtimes » est antérieur à Result et Registration ; refaire l'inventaire au moment de synthèse. |
| HTTP/authentication | `recorded-command-intake.md`, `REGISTRATION/Step_Plan.md`, controllers et security config | Consolider E attestée, B capturé, autorité primaire au WRITE, Result owner historique E, GET Pot exact et capacités ; éviter de confondre authenticité du token et autorisation métier. |

## 10. Sources fonctionnelles

| Domaine | Source forte et conflit à résoudre |
|---|---|
| User / ExternalIdentity / Binding | `REGISTRATION/Step_Canon.md` et `WRITE_ADMISSION/Step_Canon.md` ; premier document garde un en-tête et quelques phrases d'avant Wave 3, second précise B et le fence. Les migrations et `engine-registration` arbitrent l'état exécuté. |
| Registration | `REGISTRATION/Step_Plan.md` journal récent + `engine-registration`, controllers et E2E ; audits antérieurs ne décrivent pas le pipeline Result actuel. Registered crée U/B/faits/outcome atomiquement ; Rejected n'en crée pas ; Result et CURRENT_BINDING convergent séparément. |
| Command / Result | `write-side-closure.md`, `recorded-command-intake.md`, CCR canon et journal R1/R2 ; Result immutable est maintenant matérialisé directement depuis le terminal Event, owner E historique. Les anciens textes qui l'adressent comme `ProjectionKey@1` sont périmés. |
| Pot, membership, rights, ownership, visibility | `POT_E2E/Step_Plan.md`, `authorization-kernel-contracts.md`, `pot-historical-reconstruction.md`, `read-side-target.md` et tests de lecture. Le contrat exact Pot nécessite `POT_VIEW`, AUTH@V puis READ_POT@V ; 404 cache l'absence d'identité/autorisation. Ne pas attribuer au système livré les endpoints liste/CURRENT ou `VIEW_ARCHIVE` explicitement hors POT_E2E. |

## 11. Garanties, cohérence et performance

| Affirmation | Classe / preuve / limite à écrire dans System Guarantees |
|---|---|
| Command et Registration admises après commit, ensuite asynchrones | **PROVEN BY TESTS** pour admission ; `202` n'est pas succès. Result/Binding/Projection peuvent temporairement manquer. |
| RecordedCommand/Request, outcomes/Results, Events et artifacts immuables ou append-only, résultat logique 0..1 | **GUARANTEED BY code/schema** dans leurs périmètres, **PROVEN BY TESTS** ciblés ; préciser les clés et le cas mismatch/divergence, ne pas transformer la cardinalité en délai de livraison. |
| Claim CAS `PENDING + current_claim_id`, effets gagnants et terminalisation dans une transaction ; takeover/retry/restart | **PROVEN BY TESTS** Consumption, Command, Result, Binding, Registration et ProjectionTask ; lease expiré n'annule pas seul le claim ; aucune publication stale si l'effet et le CAS partagent la transaction. |
| B courant et occurrence historique, révision Binding contiguë, CURRENT_BINDING monotone, tombstone | **PROVEN BY TESTS** WA.6/C1 et migrations ; publication READ asynchrone, donc 404 temporaire possible. FIX.1 optimiste reste proposition bloquée, **DESIGN INTENT**, pas garantie. |
| Projections `AUTH/READ_POT/POT_BALANCES` exactes et immuables ; convergence READ | Identité et publication **PROVEN BY TESTS** ; convergence dépend de workers en exécution, de retries et de pannes réparées. Aucun temps maximal garanti. La disponibilité et la continuité d'une version `CURRENT` ne sont pas promises. |
| Latence Command acceptée→READ_POT READY | **MEASUREMENT METHOD ONLY**, pas valeur mesurée dans le corpus audité. `POT_E2E/E2E_Latency_Observability_Audit` classe le proxy `submitted_at`→slot `done_at` comme approximatif (horloges, instants pré-commit). |
| Débit, p95/p99, disponibilité, SLA/SLO | **NOT CURRENTLY SPECIFIED**. `scripts/k6` est historique pour d'anciennes routes ; une mention de SLO dans `pipeline-event-materialization-plan.md` est dans un plan superseded et ne fixe ni nombre ni engagement actuel. Aucun benchmark actuel validé trouvé. |

`PROVEN BY TESTS` décrit la portée des scénarios, pas une preuve universelle ; `GUARANTEED` doit rester borné par les hypothèses de transaction, stockage et runtime. `DESIGN INTENT`, `TARGET` et `STALE/OBSOLETE` ne doivent pas être promus en garantie. Le modèle de cohérence doit exposer séparément le Result terminal, la Projection exacte et CURRENT_BINDING ; 404 owner/absence/non-prêt sur certains GET et 409 Pot not-ready ne sont pas interchangeables.

## 12. Matrice documentaire complète

Convention : `oui/partiel/non` dans « exacte ? » juge l'usage comme **description de l'état actuel**, pas la fidélité historique. `F`, `A`, `G`, `P` désignent Functional Model, Architecture, System Guarantees et Process ; `—` signifie aucune extraction globale obligatoire. `Lien` vaut oui si le chemin doit bouger ou si des liens entrants/sortants doivent être revus. `KEEP COMPLETED` signifie conserver le fichier déjà au bon emplacement historique ; les chemins exacts sont en §13. Les lignes `docs/plans/*` portent toutes le verdict détaillé du §8.

| path | current purpose | actual status | normative today? | still accurate? | overlaps with | target disposition | target global document | link migration required? | notes |
|---|---|---|---|---|---|---|---|---|---|
| README.md | présentation/exécution | mixte | oui | partiel | docs/README;Steps | KEEP LIVE | F/A/G | oui | liens Task Balance absents; scripts k6 historiques |
| AGENTS.md | instruction agent | process normatif | oui | oui | testing policy | KEEP PROCESS DOC | P | oui | chemin policy cité dans session à vérifier |
| docs/README.md | index architecture | index périmé | oui | non | README;plans;Steps | KEEP LIVE | F/A/G/P | oui | EPT et Task Balance cassés |
| docs/architecture/authorization-kernel-contracts.md | contrat AUTH cible | référence spécialisée mixte | oui | partiel | POT_E2E;read-side-target | MERGE THEN ARCHIVE | F/A/G | oui | cible et exécuté à distinguer |
| docs/architecture/command-consumption-runtime.md | runtime Command | référence spécialisée | oui | oui | consumption-transactional-execution | KEEP LIVE | A/G | oui | actualiser Result direct |
| docs/architecture/consumption-event-pull-runtime.md | runtime Event | référence spécialisée | oui | partiel | EPT;projection-task-execution | KEEP LIVE | A | oui | lien EPT cassé |
| docs/architecture/consumption-transactional-execution.md | fencing/transaction | référence spécialisée | oui | oui | plans Consumption | KEEP LIVE | A/G | oui | borne du claim |
| docs/architecture/lot-7.10.1-preallocated-ids-impact-analysis.md | analyse option IDs | historique décision | non | partiel | authorization-kernel-contracts | MOVE ARCHIVE | A | oui | non décision canonique |
| docs/architecture/module-dependency-matrix.md | frontières modules | référence spécialisée | oui | partiel | type-ownership;ARCHITECTURE | KEEP LIVE | A | oui | réconcilier modules ajoutés |
| docs/architecture/pot-historical-reconstruction.md | historique Pot | référence spécialisée | oui | oui | read-side-target | KEEP LIVE | F/A/G | oui | règle temporelle |
| docs/architecture/projection-engine.md | préparation projection | référence spécialisée | oui | partiel | projection-task-execution | KEEP LIVE | A | oui | cible à confronter au code |
| docs/architecture/projection-task-execution.md | exécution Task | référence spécialisée | oui | partiel | consumption-transactional-execution | KEEP LIVE | A/G | oui | lien Task Balance absent |
| docs/architecture/read-side-current-state.md | snapshot READ | snapshot évolutif | non | partiel | read-side-target;PCL | MERGE THEN ARCHIVE | A | oui | baseline après PCL.8 |
| docs/architecture/read-side-target.md | cible READ | normatif cible | oui | partiel | plans Lot 7 | KEEP LIVE | A/G | oui | distinguer cible et état livré |
| docs/architecture/recorded-command-intake.md | admission HTTP | référence spécialisée | oui | partiel | write-side-closure;CCR | KEEP LIVE | F/A | oui | absence GET était bornée au Lot 6.6 |
| docs/architecture/recorded-command-persistence.md | Command durable | référence spécialisée | oui | oui | write-side-closure | KEEP LIVE | A/G | oui | immutabilité |
| docs/architecture/type-ownership.md | ownership types | référence spécialisée | oui | partiel | module-dependency-matrix | KEEP LIVE | A | oui | vérifier changements Registration |
| docs/architecture/write-side-closure.md | mutation Command | référence spécialisée | oui | partiel | WA;CCR | KEEP LIVE | F/A/G | oui | compléter Registration et Result |
| docs/development/ci.md | CI et gate | process normatif | oui | partiel | testing policy | KEEP PROCESS DOC | P | oui | ne pas diluer le gate |
| docs/operations/cmd-start-runtimes.md | démarrage runtimes | runbook | oui | partiel | README | KEEP PROCESS DOC | A/P | oui | ajouter runtimes Result/Registration |
| docs/operations/event-consumption-cutover.md | cutover Event | runbook historique | non | partiel | EPT | MOVE COMPLETED | A | oui | préflight SQL associé |
| docs/operations/latest-known-version-cutover.md | cutover LKV | runbook historique | non | partiel | Lot 7.4 | MOVE COMPLETED | A | oui | préflight SQL associé |
| docs/plans/2026-08-31-consumption-engine-lot-4-event-pull-orchestration.md | plan de réalisation | COMPLETED PLAN | non | oui | architecture;Steps | MOVE COMPLETED | A/G | oui | Event pull livré; recouper EPT |
| docs/plans/20260828-000000-v2-plan.md | plan de réalisation | SUPERSEDED | non | non | architecture;Steps | MOVE ARCHIVE | A | oui | roadmap V2 initiale, étapes ultérieures la remplacent |
| docs/plans/20260828-121854-step-2-domain-modules.md | plan de réalisation | COMPLETED PLAN | non | partiel | architecture;Steps | MOVE COMPLETED | A | oui | découpage initial exécuté |
| docs/plans/20260828-132854-step-2-1-generic-consumption-domain.md | plan de réalisation | COMPLETED PLAN | non | partiel | architecture;Steps | MOVE COMPLETED | A/G | oui | domaine Consumption livré |
| docs/plans/20260828-134845-step-2-2-generic-consumption-engine.md | plan de réalisation | COMPLETED PLAN | non | partiel | architecture;Steps | MOVE COMPLETED | A/G | oui | engine Consumption livré |
| docs/plans/20260828-140224-step-2-3-command-processing-engine.md | plan de réalisation | COMPLETED PLAN | non | partiel | architecture;Steps | MOVE COMPLETED | A | oui | ancien nom corrigé par plan suivant |
| docs/plans/20260828-142538-step-2-3-command-processing-naming-correction.md | plan de réalisation | COMPLETED PLAN | non | partiel | architecture;Steps | MOVE COMPLETED | A | oui | correction de nommage exécutée |
| docs/plans/20260828-144844-step-2-4-event-task-processing-engines.md | plan de réalisation | COMPLETED PLAN | non | partiel | architecture;Steps | MOVE COMPLETED | A | oui | engines Event/Task livrés puis refondus |
| docs/plans/20260828-152635-step-2-4-task-processing-continuation.md | plan de réalisation | COMPLETED PLAN | non | partiel | architecture;Steps | MOVE COMPLETED | A | oui | continuation exécutée, ancien graphe |
| docs/plans/20260828-154149-step-2-5-pot-domain.md | plan de réalisation | COMPLETED PLAN | non | partiel | architecture;Steps | MOVE COMPLETED | A | oui | domaine Pot livré |
| docs/plans/20260828-172330-step-2-6-pot-domain-events.md | plan de réalisation | COMPLETED PLAN | non | partiel | architecture;Steps | MOVE COMPLETED | A | oui | Events Pot livrés |
| docs/plans/20260828-174431-step-2-7-pipeline-task-domains.md | plan de réalisation | COMPLETED PLAN | non | partiel | architecture;Steps | MOVE COMPLETED | A | oui | séparation livrée puis legacy nettoyé |
| docs/plans/20260828-185643-step-2-8-policy-balance-projection-engine-core.md | plan de réalisation | COMPLETED PLAN | non | partiel | architecture;Steps | MOVE COMPLETED | A/G | oui | policies/calcul livrés puis déplacés |
| docs/plans/20260828-214107-step-2-9-consumption-processing-tests.md | plan de réalisation | COMPLETED PLAN | non | partiel | architecture;Steps | MOVE COMPLETED | A/G | oui | preuves initiales historiques |
| docs/plans/20260828-232647-step-2-10-architecture-closure.md | plan de réalisation | COMPLETED PLAN | non | partiel | architecture;Steps | MOVE COMPLETED | A | oui | clôture étape 2 historique |
| docs/plans/20260828-234057-step-3-plus-roadmap.md | plan de réalisation | SUPERSEDED | non | non | architecture;Steps | MOVE ARCHIVE | A | oui | roadmap 3–7 remplacée par contrats et réalisations ultérieurs |
| docs/plans/20260828-234822-step-3-1-single-item-pull-loop.md | plan de réalisation | COMPLETED PLAN | non | partiel | architecture;Steps | MOVE COMPLETED | A | oui | polling générique livré |
| docs/plans/20260829-093008-step-3-2-command-worker.md | plan de réalisation | COMPLETED PLAN | non | partiel | architecture;Steps | MOVE COMPLETED | A | oui | worker Command livré |
| docs/plans/20260829-133952-consumption-slot-authoritative-reconciliation.md | plan de réalisation | COMPLETED PLAN | non | partiel | architecture;Steps | MOVE COMPLETED | A/G | oui | slot autoritatif/fencing livré |
| docs/plans/20260829-150300-step-3-3-event-worker.md | plan de réalisation | COMPLETED PLAN | non | partiel | architecture;Steps | MOVE COMPLETED | A | oui | worker Event livré puis adapté EPT |
| docs/plans/20260829-151500-step-3-4-task-worker.md | plan de réalisation | COMPLETED PLAN | non | partiel | architecture;Steps | MOVE COMPLETED | A | oui | worker Task livré puis adapté PCL |
| docs/plans/20260829-234056-step-3-5-balance-pipeline-migration.md | plan de réalisation | COMPLETED PLAN | non | partiel | architecture;Steps | MOVE COMPLETED | A | oui | migration Balance historique, PCL ultérieur |
| docs/plans/20260829-235730-step-3-6-worker-contract-tests.md | plan de réalisation | COMPLETED PLAN | non | partiel | architecture;Steps | MOVE COMPLETED | A | oui | preuves worker historiques |
| docs/plans/20260830-215122-consumption-engine-lot-1.md | plan de réalisation | COMPLETED PLAN | non | partiel | architecture;Steps | MOVE COMPLETED | A/G | oui | modèle Consumption livré |
| docs/plans/20260831-081632-consumption-engine-lot-2-postgresql-persistence.md | plan de réalisation | COMPLETED PLAN | non | partiel | architecture;Steps | MOVE COMPLETED | A/G | oui | persistence Consumption livrée |
| docs/plans/20260831-094527-consumption-engine-lot-3-transactional-execution.md | plan de réalisation | COMPLETED PLAN | non | partiel | architecture;Steps | MOVE COMPLETED | A/G | oui | transaction/fencing livré |
| docs/plans/20260831-consumption-engine-lot-5-task-balance.md | plan de réalisation | COMPLETED PLAN | non | partiel | architecture;Steps | MOVE COMPLETED | A/G | oui | migration Task/Balance historique |
| docs/plans/lot-7-read-side-implementation-plan.md | plan de réalisation | SUPERSEDED | non | non | architecture;Steps | MOVE ARCHIVE | A/G | oui | table de statuts 7.9–7.16 dépassée; target READ plus récent |
| docs/plans/lot-7.1-read-side-documentation-alignment-plan.md | plan de réalisation | COMPLETED PLAN | non | partiel | architecture;Steps | MOVE COMPLETED | A/G | oui | réalignement exécuté, règle current ancienne |
| docs/plans/lot-7.10.1-authorization-kernel-implementation-plan.md | plan de réalisation | COMPLETED PLAN | non | partiel | architecture;Steps | MOVE COMPLETED | A | oui | marqué IMPLEMENTED, preuves AUTH à reprendre |
| docs/plans/lot-7.14.1-pipeline-version-lifecycle-design.md | plan de réalisation | COMPLETED PLAN | non | partiel | architecture;Steps | MOVE COMPLETED | A | oui | design implémenté historiquement; serving non cible actuelle |
| docs/plans/lot-7.14.1-pipeline-version-lifecycle-implementation-plan.md | plan de réalisation | COMPLETED PLAN | non | partiel | architecture;Steps | MOVE COMPLETED | A | oui | marqué DONE, lifecycle historique |
| docs/plans/lot-7.2-read-store-boundary-plan.md | plan de réalisation | SUPERSEDED | non | non | architecture;Steps | MOVE ARCHIVE | A/G | oui | frontière partielle puis cible READ révisée |
| docs/plans/lot-7.3-generic-projection-foundation-plan.md | plan de réalisation | COMPLETED PLAN | non | partiel | architecture;Steps | MOVE COMPLETED | A/G | oui | fondation livrée; Coverage superseded |
| docs/plans/lot-7.3.1-pipeline-applicability-alignment-plan.md | plan de réalisation | COMPLETED PLAN | non | partiel | architecture;Steps | MOVE COMPLETED | A | oui | applicabilité livrée dans ancien modèle |
| docs/plans/lot-7.4-source-version-watermark-plan.md | plan de réalisation | COMPLETED PLAN | non | partiel | architecture;Steps | MOVE COMPLETED | A | oui | LKV livré; rôle Query changé |
| docs/plans/lot-7.5-applicable-projection-task-scheduling-plan.md | plan de réalisation | COMPLETED PLAN | non | partiel | architecture;Steps | MOVE COMPLETED | A/G | oui | scheduling livré; Task admin superseded |
| docs/plans/lot-7.6-canonical-pot-projection-plan.md | plan de réalisation | COMPLETED PLAN | non | partiel | architecture;Steps | MOVE COMPLETED | A/G | oui | Pot projection livrée; rebuild changé |
| docs/plans/lot-7.7-pot-version-user-indexes-and-keyset-pagination-plan.md | plan de réalisation | COMPLETED PLAN | non | partiel | architecture;Steps | MOVE COMPLETED | A | oui | index livré; sélection shadow superseded |
| docs/plans/lot-7.9.1-monoprojection-contract-revision-plan.md | plan de réalisation | COMPLETED PLAN | non | partiel | architecture;Steps | MOVE COMPLETED | A/G | oui | DONE explicite, contrat ensuite révisé |
| docs/plans/lot-7.9.1-versioned-query-contracts-plan.md | plan de réalisation | COMPLETED PLAN | non | partiel | architecture;Steps | MOVE COMPLETED | A | oui | DONE explicite, ancien modèle de résolution |
| docs/plans/lot-7.9.2-query-version-resolution-design.md | plan de réalisation | SUPERSEDED | non | non | architecture;Steps | MOVE ARCHIVE | A | oui | IMPLEMENTED explicite mais latest-known/serving supplantés |
| docs/plans/pipeline-event-materialization-plan.md | plan de réalisation | SUPERSEDED | non | non | architecture;Steps | MOVE ARCHIVE | A | oui | bandeau superseded explicite par EPT |
| docs/projection-workers.md | ancien worker | superseded | non | non | PCL;EPT | MOVE ARCHIVE | A | oui | bandeau historique; lien Task Balance absent |
| docs/steps/completed/CCR/RegisterUser_Identity_Audit.md | preuve/plan Step livré | COMPLETED | non | partiel | Steps;architecture | KEEP COMPLETED | F/A/G | oui | pré-audit identité avant Registration |
| docs/steps/completed/CCR/Step_Canon.md | preuve/plan Step livré | COMPLETED | non | partiel | Steps;architecture | KEEP COMPLETED | F/A/G | oui | canon Result historique |
| docs/steps/completed/CCR/Step_Plan.md | preuve/plan Step livré | COMPLETED | non | partiel | Steps;architecture | KEEP COMPLETED | F/A/G | oui | DONE explicite |
| docs/steps/completed/EPT/Legacy_Cleanup_Inventory.md | preuve/plan Step livré | COMPLETED | non | partiel | Steps;architecture | KEEP COMPLETED | A/G | oui | inventaire cleanup historique |
| docs/steps/completed/EPT/Step_Canon.md | preuve/plan Step livré | COMPLETED | non | partiel | Steps;architecture | KEEP COMPLETED | A/G | oui | canon Event→Task historique |
| docs/steps/completed/EPT/Step_Plan.md | preuve/plan Step livré | COMPLETED | non | partiel | Steps;architecture | KEEP COMPLETED | A/G | oui | DONE explicite |
| docs/steps/completed/PCL/Step_Canon.md | preuve/plan Step livré | COMPLETED | non | partiel | Steps;architecture | KEEP COMPLETED | A/G | oui | canon cleanup historique |
| docs/steps/completed/PCL/Step_Plan.md | preuve/plan Step livré | COMPLETED | non | partiel | Steps;architecture | KEEP COMPLETED | A/G | oui | DONE explicite |
| docs/steps/completed/WRITE_ADMISSION/Binding_Canon_Alignment_Audit.md | preuve/plan Step livré | COMPLETED | non | partiel | Steps;architecture | KEEP COMPLETED | F/A/G | oui | audit alignement historique |
| docs/steps/completed/WRITE_ADMISSION/Step_Canon.md | preuve/plan Step livré | COMPLETED | non | partiel | Steps;architecture | KEEP COMPLETED | F/A/G | oui | CLOSED WA.1–8 |
| docs/steps/completed/WRITE_ADMISSION/Step_Plan.md | preuve/plan Step livré | COMPLETED | non | partiel | Steps;architecture | KEEP COMPLETED | F/A/G | oui | CLOSED WA.1–8 |
| docs/steps/completed/WRITE_ADMISSION/Step_debt.md | preuve/plan Step livré | COMPLETED | non | partiel | Steps;architecture | KEEP COMPLETED | F/A/G | oui | dette explicitée, vérifier si encore ouverte |
| docs/steps/completed/WRITE_ADMISSION/WA6_Audit.md | preuve/plan Step livré | COMPLETED | non | partiel | Steps;architecture | KEEP COMPLETED | F/A/G | oui | preuves Binding |
| docs/steps/completed/WRITE_ADMISSION/WA7_Readiness_Audit.md | preuve/plan Step livré | COMPLETED | non | partiel | Steps;architecture | KEEP COMPLETED | F/A/G | oui | snapshot superseded explicite |
| docs/steps/completed/WRITE_ADMISSION/step_audit.md | preuve/plan Step livré | COMPLETED | non | partiel | Steps;architecture | KEEP COMPLETED | F/A/G | oui | audit ancien, liens Registration cassés |
| docs/steps/current/ARCHITECTURE/Consumption_Workers_Runtimes_Audit.md | audit graphe ancien | historique mixte | non | partiel | Three_Engine_Families_Revision | MERGE THEN ARCHIVE | A | oui | cible modules remplacée |
| docs/steps/current/ARCHITECTURE/Read_Materialization_Gap_and_Migration_Plan.md | plan trois READ + journal R1/R2 | historique mixte | oui | partiel | REGISTRATION;read-side-target | MERGE THEN ARCHIVE | A/G | oui | R1/R2 DONE; Registration absent est faux |
| docs/steps/current/ARCHITECTURE/Read_Usage_and_Derived_Materialization_Audit.md | audit avant Result direct | snapshot ancien | non | partiel | Read_Materialization_Gap | MOVE COMPLETED | A | oui | pré-R1/R2 |
| docs/steps/current/ARCHITECTURE/Three_Engine_Families_Revision.md | refonte modules proposée | ACTIVE PLAN | oui | partiel | Consumption_Workers_Runtimes_Audit | KEEP LIVE | A | oui | garder en current après rebaseline |
| docs/steps/current/POT_E2E/E2E_Latency_Observability_Audit.md | audit proxy latence | preuve historique | non | oui | Step_Plan;scripts/k6 | MOVE COMPLETED | G | oui | aucune mesure/SLO |
| docs/steps/current/POT_E2E/Step_Plan.md | parcours E2E livré | COMPLETED | non | oui | README;CCR | MOVE COMPLETED | F/A/G | oui | test HTTP/PostgreSQL présent |
| docs/steps/current/REGISTRATION/Current_Binding_Registration_Architecture_Audit.md | audit ancienne chaîne | superseded | non | non | Read_Materialization_Gap | MOVE ARCHIVE | A | oui | hypothèse Event→Task abandonnée |
| docs/steps/current/REGISTRATION/Step_Canon.md | canon métier initial | COMPLETED | oui | partiel | WA;Step_Plan | MOVE COMPLETED | F/A/G | oui | en-tête FRAMING ancien |
| docs/steps/current/REGISTRATION/Step_Plan.md | journal Wave 3 | COMPLETED | non | partiel | Read_Materialization_Gap | MOVE COMPLETED | F/A/G | oui | clôture au sommet, anciens statuts en bas |
| docs/steps/current/REGISTRATION/Wave2_Optimistic_Binding_and_Module_Boundary_Audit.md | audit FIX.1 | historique bloqué | non | oui | WA;Step_Plan | MOVE ARCHIVE | A/G | oui | NO-GO; chantier distinct |
| docs/steps/current/REGISTRATION/domain_audit.md | cadrage initial | historique | non | partiel | Step_Canon | MOVE COMPLETED | F | oui | avant D31–D33 et implémentation |
| docs/steps/current/REGISTRATION/step_audit.md | pré-audit initial | historique | non | non | Step_Canon;Step_Plan | MOVE ARCHIVE | F/A | oui | liens absolus machine |
| docs/testing/Reactor_Verification_Policy.md | règles verification | process normatif | oui | oui | AGENTS;ci | KEEP PROCESS DOC | P | oui | source normative effective |
| docs/testing/worker-contract-matrix.md | matrice tests workers | preuve ancienne | non | partiel | plans workers | MERGE THEN ARCHIVE | G/P | oui | PostgreSQL deferred historiquement |
| docs/use-case-families.md | inventaire use cases | référence spécialisée | oui | partiel | README;architecture | MERGE THEN ARCHIVE | F/A | oui | évaluer Registration/Result |
| scripts/bruno/README.md | parcours manuel | process test | non | partiel | POT_E2E;Registration | KEEP PROCESS DOC | F/P | oui | collections actuelles et anciennes à séparer |
| scripts/k6/README.md | charge historique | process test ancien | non | non | POT_E2E latency | KEEP PROCESS DOC | G/P | oui | ne prouve aucun SLO actuel |

Les trois scripts SQL de `docs/operations/sql/` ne sont pas des documents Markdown autonomes : `event-consumption-preflight.sql`, `event-consumption-validate.sql`, `latest-known-version-preflight.sql` suivent respectivement leurs runbooks, avec vérification des chemins lors d'un éventuel déplacement. Le PNG utilisateur n'entre dans aucune disposition. Aucun fichier n'est proposé comme `DELETE CANDIDATE` : même les plans remplacés expliquent des décisions ou des migrations.

## 13. Matrice de migration des liens et références

Analyse statique des liens Markdown locaux de `README.md`, `docs/**/*.md` et des deux README de scripts : **372 liens Markdown, 49 cibles absentes avant migration** ; 50 lignes comportent une référence textuelle vers `docs/plans/`, `docs/steps/current/` ou `plans/` dans 19 sources. Ces chiffres sont des compteurs d'occurrences, pas de fichiers distincts. Le parseur de liens est simple ; les liens complexes et les références en texte/code demandent une seconde passe à la migration.

| Sources / motifs | État actuel et dépendance | Action lors de migration |
|---|---|---|
| `docs/README.md` → `steps/EPT/Step_Canon.md`, `Step_Plan.md` | 2 liens cassés : EPT est dans `steps/completed/EPT/` | Index neuf vers les trois global docs ; EPT seulement depuis historique. |
| `docs/README.md`, `README.md`, `docs/projection-workers.md`, `docs/architecture/projection-task-execution.md`, plans Lot 7 → `architecture/consumption-task-balance-runtime.md` | Fichier absent ; plusieurs références de rôle « runtime Task Balance » | Remplacer par `Architecture.md` ou la référence Task actuelle, après validation du contenu. |
| `docs/architecture/consumption-event-pull-runtime.md`, `docs/plans/pipeline-event-materialization-plan.md` → `steps/EPT/*` | Ancien emplacement ; trois liens cassés | Pointer vers `steps/completed/EPT/*` ou synthèse A ; garder l'histoire explicitement. |
| `docs/steps/completed/WRITE_ADMISSION/step_audit.md` → `../REGISTRATION/*` | 4 liens déjà cassés depuis le déplacement WA | Corriger vers le futur emplacement Registration en completed ; éviter un second déplacement aveugle. |
| `docs/steps/current/REGISTRATION/step_audit.md` | 24 liens absolus `/Users/julien.guezennec/...` ou ancienne structure, cassés ici | Garder comme archive avec note de provenance ; convertir seulement les renvois encore utiles en chemins repo relatifs. |
| `REGISTRATION/domain_audit.md`, `Current_Binding_Registration_Architecture_Audit.md`, `ARCHITECTURE/Read_Usage_and_Derived_Materialization_Audit.md` | 10+ liens vers classes supprimées ou déplacées, surtout ancien `CommandResultProjector` | Conserver comme sources historiques ; bandeau « lien vers ancien code » ou ancre vers commits, sans prétendre réparer vers un symbole différent. |
| Plans Lot 7 et `docs/README.md` → `docs/plans/*` | Nombreux renvois vers design old Query/serving ; index présente plans réalisés comme références actives | Remapper vers F/A/G si la connaissance est durable ; sinon vers completed/archive avec label historique. |
| `docs/steps/current/ARCHITECTURE/*` ↔ `REGISTRATION/*` | Liens relatifs de voisinage `../REGISTRATION` casseront si Registration seule bouge | Mettre à jour atomiquement après synthèse ; conserver liens de preuve vers completed si nécessaires. |
| Tous documents déplacés avec liens relatifs `../architecture`, `../plans`, `../../../../app` | Profondeur variable ; un déplacement de plan ou Step cassera aussi les liens sortants actuellement valides | Générer inventaire entrant/sortant avant move, réécrire selon la destination, lancer vérification de liens après chaque lot. |
| `README.md` et commentaires de code | `README.md` cite l'index comme canonique et un fichier Task absent. `rg` sur `app/` n'a trouvé **aucune** référence `docs/steps`, `docs/plans`, `docs/architecture` ou `docs/testing` dans les fichiers source suivis. | Corriger README au lot liens ; refaire `rg` à la migration, sans présumer qu'aucun autre commentaire libre n'existe. |

Les 49 erreurs actuelles ne doivent pas être attribuées aux futurs déplacements. La migration doit établir une baseline de liens existants, puis exiger zéro nouveau lien cassé et traiter les erreurs actuelles utiles. Les liens absolus pointant vers une autre machine ne doivent jamais servir d'autorité documentaire.

## 14. Index documentaire proposé

`docs/README.md` doit tenir en une page courte : une phrase sur la portée, trois liens en tête vers **Functional Model**, **Architecture**, **System Guarantees** ; puis trois liens vers `steps/current/`, `steps/completed/` et `archive/` avec « actif / historique livré / abandonné ou supplanté » ; enfin la politique de vérification, les références techniques spécialisées et les runbooks. L'index ne reproduit pas les décisions ni des listes de 43 plans. `README.md` racine garde présentation et démarrage, puis renvoie à cet index.

## 15. Gouvernance documentaire

1. Un Step n'entre dans `steps/current/` que lorsqu'un chantier possède un objectif, un owner de décision et une prochaine action concrète. Les audits, canons temporaires, plans, journaux et matrices de preuve peuvent vivre avec lui.
2. À la clôture, intégrer toute règle fonctionnelle, architecturale ou de garantie encore vraie dans F/A/G ; citer les preuves et les limites. Vérifier le code et les tests concernés.
3. Marquer le Step DONE et dater son état final ; déplacer en `steps/completed/`. Classer séparément les hypothèses abandonnées et plans supplantés dans `archive/`.
4. Vérifier les liens entrants et sortants, l'index, le README racine et les références de code. Aucun Step clos ni plan historique n'est normatif après clôture ; les trois global docs priment.
5. Une proposition non livrée demeure sous un Step actif clairement marqué **TARGET** ; les changements de règle transversale exigent la mise à jour des global docs au moment de la livraison.

Cette règle est compatible avec les Steps EPT, CCR, PCL et WA déjà dans `completed/`. Elle impose un rattrapage : EPT/CCR/WA y conservent aujourd'hui des canons détaillés que l'on cite encore comme autorité. Les synthèses globales doivent absorber ces règles avant de retirer cette autorité. REGISTRATION et POT_E2E peuvent alors quitter `current` ; le dossier ARCHITECTURE doit d'abord être scindé/rebaseliné pour ne pas enterrer son chantier restant.

## 16. Plan de migration proposé

| Lot | Résultat concret | Précondition / gate documentaire |
|---|---|---|
| DOC.1 — inventaire et taxonomie | Baseline de tous chemins/liens ; décisions `completed`/`archive` ; index cible, bandeaux de statut | Ce rapport, validation des catégories et de la portée des travaux ARCHITECTURE restants. |
| DOC.2 — Functional Model | Texte courant + table de traçabilité vers code/tests et anciens canons | Vérifier Registration/Binding/Command/Pot, HTTP, ownership et visibilité ; revue métier. |
| DOC.3 — Architecture | Chaînes runtime actuelles et frontières module/transaction, schémas simples, référence vers détails | Vérifier modules récents Result/Registration et distinguer read-side target non livré ; revue code. |
| DOC.4 — System Guarantees | Matrice invariant→hypothèse→preuve→limite ; modèle de cohérence et performance non spécifiée | Vérifier tests, migrations et runbooks ; ne publier aucun SLO implicite. |
| DOC.5 — validation croisée | F/A/G, README, code/tests et contradictions résolus ; statuts des notes spécialisées | Gate humain de vérité globale **avant** tout déplacement. |
| DOC.6 — histoire et current | POT_E2E/REGISTRATION vers completed ; ARCHITECTURE recentré ; 37 plans réalisés vers completed, 6 supplantés vers archive ; notes historiques classées | Préserver commits, preuves et alternatives ; aucun déplacement massif sans mapping unitaire. |
| DOC.7 — liens et index | `docs/README.md`, `README.md`, renvois entrants/sortants, chemins absolus utiles, références historiques étiquetées | Vérification automatique des liens et revue des 49 erreurs préexistantes. |
| DOC.8 — audit final | Diff documentaire, absence de norme contradictoire, `current` actif seulement, gouvernance publiée | `git diff --check`, contrôles de liens et revue de chaque assertion F/A/G. Maven seulement si changement ultérieur de code le justifie selon la politique. |

Ordre strict : **inventaire → synthèse → validation de vérité → classement historique → réparation des liens → audit final**. Les moves et réparations peuvent partager un commit par lot, mais pas précéder la validation des trois documents globaux.

## 17. Effort relatif (WA6A+B+C = 100)

| Nature | Lots | Unités relatives |
|---|---|---:|
| Audit / taxonomie | Audit présent + DOC.1 | 6 (dont 4 déjà engagées par ce rapport) |
| Synthèse | DOC.2, DOC.3, DOC.4 | 19 |
| Validation de vérité | DOC.5 | 5 |
| Moves, bandeaux et réparation des liens | DOC.6, DOC.7 | 9 |
| Audit final | DOC.8 | 3 |
| **Total programme** | | **42** |
| **Restant après ce rapport** | | **38** |

Il s'agit d'effort comparatif, pas de jours. La synthèse Architecture et le traitement des contradictions READ/Registration dominent ; l'inventaire des liens en place rend le déplacement moins coûteux que l'établissement de la vérité. La fourchette raisonnable est **34–52**, selon la profondeur nécessaire pour réconcilier le READ cible et les anciennes notes Lot 7.

## 18. Risques

- **Promouvoir un design ancien en vérité actuelle** : particulièrement `lot-7-read-side-implementation-plan`, 7.9.2, anciennes routes COMMAND_RESULT et l'hypothèse ProjectionTask pour CURRENT_BINDING. Mitigation : preuve code/test par assertion de synthèse.
- **Masquer une dette active** : FIX.1 optimiste est différé, tandis que la refonte de modules ARCHITECTURE reste proposée ; ne pas les déclarer livrées avec Registration. Garder un Step ciblé si ce chantier a réellement un prochain lot.
- **Perdre les preuves et alternatives** : conserver plans exécutés et audits à valeur explicative, avec statut et commit d'origine ; pas de suppression générale.
- **Casser des liens déjà fragiles** : 49 cibles absentes avant migration, liens relatifs sensibles à la profondeur et liens absolus machine. Capturer entrants/sortants et valider chaque lot.
- **Surpromettre la disponibilité ou la performance** : workers asynchrones et proxy de latence ne créent pas de SLO ; documenter les états non prêts et l'absence de borne temporelle.
- **Rendre le README contradictoire** : corriger les liens et la description des runtimes ajoutés en même temps que l'index, après validation globale.

## 19. Recommandation

Adopter la taxonomie F/A/G, `steps/completed` pour le travail exécuté et `docs/archive` pour les hypothèses supplantées. Commencer par écrire les trois synthèses avec leurs preuves et limites, puis reclasser l'histoire. Conserver les notes techniques encore utiles comme références subordonnées, et publier un index minimal. Le statut de `ARCHITECTURE` doit être rebaseliné sur les seuls travaux restants ; `REGISTRATION` et `POT_E2E` sont prêts à sortir de `current` **après** transfert des règles durables.

## 20. GO / NO-GO

**GO pour DOC.1–DOC.5 et pour préparer la migration. NO-GO pour déplacer immédiatement `REGISTRATION` seul ou annoncer les anciens plans comme vérité actuelle.** Le gate de DOC.5 conditionne les déplacements DOC.6–DOC.7 : validation explicite de F/A/G contre code, tests et contrats HTTP/SQL, puis mapping des liens. Aucun changement de code ni test Maven n'est requis par ce rapport. Commandes réellement exécutées pour l'audit : `find`, `rg`, lectures ciblées via `python3`/`sed`, `git log`, commandes Git de baseline et `git diff --check` à la clôture ; aucun slice inattendu, aucun gate global et aucun full reactor.
