# Pocoma V3 — L4/I8 — Plan définitif d’implémentation et de qualification

**Suivi du 3 novembre 2026 : implémentation et qualification composite livrées avec dispense humaine explicite.**
[Rapport](L4_I8_Execution_Report.md), [manifeste](L4_I8_Proof_Manifest.json), [preuves](../testing/evidence/L4-I8/README.md).
T01–T11 PASS ; qualification autorisée : **2 024 tests hors Architecture PASS au code `a3470df6…`
+ 167 Architecture PASS aux seuls oracles corrigés `8a007768…`**. Aucun code productif/POM/SQL
ne diffère entre ces deux SHAs. Dernier full complet **FAIL 2 191/4/0/0**, conservé ;
le quatrième déjà lancé a été interrompu dès réception de la dispense. Aucun full PASS inventé.
Écarts bornés : T09/T10 au monolithe pour charger les deux vraies mains sans cycle Maven ;
C-I8/F-L4 mutualisés après équivalence et accord humain ; corrections de scan legacy puis ordre
historique ; deux fichiers d’oracles Architecture alignés sur les contrats approuvés après findings.
Lease180 reste une hypothèse de qualification, sans acceptation de production.
**L4 reste ouvert : audit indépendant et décision humaine de clôture restent dus.**
Le cadrage publié `a2b4e2a0…`, les commandes prévues et constats ci-dessous restent historiques ;
les commandes réellement exécutées et l’arbitrage sont dans le dossier.


**3 novembre 2026 — cadrage documentaire ; verdict proposé : GO WITH RESERVATIONS pour l’implémentation I8.**
Ce document est la référence opérationnelle unique d’I8. Il fixe une passe d’implémentation, puis sa qualification et son audit. Aucun code I8, test applicatif ou gate n’est exécuté par sa publication. **L4 reste ouvert et non certifié.** Les réserves concernent des preuves à produire, notamment les ressources ; aucun nouvel arbitrage métier bloquant n’est identifié.

## 1. Références, SHA et état initial

| Élément vérifié avant écriture | Constat |
|---|---|
| Branche, HEAD | `feat/v3-architecture`, `12c748e719839cdfc897459eb7b244d60c6535df` |
| Distant | `origin`, `https://github.com/kartaguez/pocoma.git` ; `git ls-remote` confirme le même SHA ; référence de suivi identique, divergence 0/0 |
| Worktree courant | `/Users/julien.guezennec/Dev/projects/pocoma` ; aucun changement suivi/non suivi initial |
| Autres worktrees | Cinq worktrees inventoriés et préservés ; aucune opération sur leurs fichiers |
| Vérification de cette mission | Slices **NONE** ; liens, chemins, empreintes historiques, POM en lecture statique, cohérence et `git diff --check`. Maven/PostgreSQL/Docker/gates **NOT RUN**, aucune traversée de slice |

Référentiel normatif : [SPEC-04](SPEC-04_Read_Projections.md), [SPEC-05](SPEC-05_Consumption_Discovery.md), [SPEC-10](SPEC-10_Test_Certification.md), [ordre des contrôles SPEC-06](SPEC-06_Supra_Contracts.md), [politique Reactor §§3, 11, 17–27, 34–35](../testing/Reactor_Verification_Policy.md). Pilotage : [plan L4](L4_Read_Inventory_and_Implementation_Plan.md) et [Master Plan](V3_Implementation_Master_Plan.md). Les SPEC et décisions humaines publiées priment sur les propositions techniques.

États acquis, sans réinterprétation des anciennes attentes :

- I1 **CLOSED — LOCAL BOUNDED PASS WITH EXPLICIT RESERVATIONS** : [décision](L4_I1_Acceptance_Decision.md), [rapport](L4_I1_Execution_Report.md), [plan technique](L4_I1_Implementation_Plan.md), [manifeste](L4_I1_Proof_Manifest.json).
- I2 **CLOSED — LOCAL BOUNDED PASS**, **I2-QUAL-01 RESOLVED** : [décision en fin de rapport](L4_I2_Execution_Report.md#clôture-définitive-du-3-novembre-2026), [contrat](L4_I2_Implementation_Plan.md), [manifeste](L4_I2_Proof_Manifest.json). Aucune réouverture d’I2.
- I3–I7 livrés inertes, **PASS local filtré avec réserves**, sans clôture canonique : [mandat humain consolidé](L4_I3_I7_Consolidated_Execution.md), [rapport](L4_I3_I7_Execution_Report.md), [manifeste](L4_I3_I7_Proof_Manifest.json), [fichiers livrés](L4_I3_I7_Delivery_Files.json).
- Recovery **CLOSED — LOCAL BOUNDED PASS WITH EXPLICIT RESERVATIONS** : [AUD-L4-01 corrigé, AUD-L4-02 non confirmé](L4_Recovery_Audit_Resolution.md), [preuves](../testing/evidence/L4-Recovery/Manifest.json). Ne créer aucun reçu métier supplémentaire pour Visibility/CurrentBinding.

Les mentions anciennes « non implémenté », « I2 à cadrer » ou de gate intermédiaire non exécutée ne constituent pas de nouvelles préconditions. L’entrée I8 est la livraison locale I3–I7 autorisée par le mandat consolidé ; leur qualification canonique est précisément une obligation d’I8, sans imposer une clôture préalable circulaire.

## 2. Inventaire réel de l’existant

**REUSE** : réutilisation directe du contrat/comportement ; **ADAPT** : modification bornée nécessaire ; **CREATE** : absence constatée ; **EXCLUDE** : inactif dans la composition métier V3 ; **DEFER** : L5/L6/L7. Une présence de source n’est pas un PASS au HEAD.

Les chemins ci-dessous sont relatifs au dépôt. Pour éviter les longues répétitions, les préfixes sont définis exactement :

| Préfixe | Répertoire existant |
|---|---|
| C | [composition V3](../../app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/v3/) |
| R | [stores READ V3](../../app/infra-persistence-read-jdbc/src/main/java/com/kartaguez/pocoma/infra/persistence/read/jdbc/v3/) |
| W | [sources WRITE V3](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/) |
| P | [ports READ](../../app/port-projection/src/main/java/com/kartaguez/pocoma/port/projection/v3/) |
| E | [moteur L2](../../app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/consumption/v3/) |
| O | [Worker L2](../../app/orchestrator-poll-consumption/src/main/java/com/kartaguez/pocoma/orchestrator/poll/consumption/v3/) |

### 2.1 Composition, cycle de vie et ressources

| Fichier exact | Classement et constat utile |
|---|---|
| C/`V3ReadParticipants.java` | **REUSE**. Factory explicite complète des six participants, sans bean ni démarrage ; valide les owners séparés. La construire une fois n’active aucune Consumption. Ne pas en créer une seconde. |
| C/`V3ConsumptionCatalogConfiguration.java` | **REUSE** pour `definitions()` : neuf UUID déjà présents. La configuration Spring du profil inerte reste distincte de la composition active. |
| C/`V3ConsumptionLifecycleComposition.java` | **REUSE**. Surcharge READ complète : source WRITE, effets READ, control READ, références de recovery et filtre explicites ; réserves business/control. |
| C/`V3ConsumptionDiscoveryComposition.java` | **REUSE**. `events()` couvre les quatre familles nécessaires ; `relevant()` exclut déjà `POCOMA_USER`. `requests()` conserve Command/IdentityRequest. |
| C/`V3ConsumptionWorkerSettings.java` | **ADAPT**. Aujourd’hui profil DEV unique, noms limités à deux WRITE/deux probes, `definition()` fermé ; allouer les six noms, activation et préflight temporel du nouveau profil. |
| C/`V3ConsumptionWorkerConfiguration.java` | **ADAPT**. `Preflight`, `Active`, `Resources`, `Lifecycle` : liste fermée, racines Command/monolithe seulement, quatre pools, une capacité, démarrage/fermeture sûrs déjà présents. Brancher la factory READ et la troisième racine autorisée PROJECTION. |
| C/`V3BootstrapGuard.java` | **ADAPT**. Contrôle des autorités/routage/droits effectifs/objets indirects existant ; `inspectDevelopment()` refuse actuellement les tables READ métier comme non allouées. Ajouter une matrice exacte pour elles dans le nouveau mode, sans ouvrir l’allowlist par package. |
| C/`MicrometerV3ConsumptionObservation.java` | **REUSE**. Phases/capacité/Worker existants ; ne pas créer un système de métriques concurrent. |
| C/`V3IdentityRequestComposition.java` | **REUSE**. Autorité réelle dans le monolithe mixte ; pas de changement métier. |
| `app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/command/v3/V3PotCommandComposition.java` | **REUSE**. Command réel, autorité Identity réelle ; partage de capacité dans la racine mixte. |
| E/`WorkerCapacity.java` | **REUSE**. Une capacité par Worker, commune WRITE/READ, bornes par Consumption ; seul L2 libère les tickets. |
| E/`LifecycleSettings.java` | **REUSE**. Paramètres existants, sans changement de protocole ni mutation des échéances durables par configuration. |
| E/`ConsumptionEngine.java`, E/`DiscoveryEngine.java`, E/`PreparationReads.java` | **REUSE**, chacun. Préparation séquentielle, recovery, claims/checkpoints et UNKNOWN restent propriétaires L2. |
| O/`V3ConsumptionWorker.java` | **REUSE**. Une instance single-use, deux boucles de polling, maintenance, drainage conservateur. |
| O/`V3DiscoveryLoop.java`, O/`V3ConsumptionLoop.java`, O/`V3RenewalService.java` | **REUSE**, chacun. Tour des paires, deux lanes claims et une lane leases par autorité ; lots de leases d’un seul binding, omissions partielles et budget local déjà gérés. |
| `app/infra-tx-spring/src/main/java/com/kartaguez/pocoma/infra/tx/spring/v3/SpringAttemptTransactions.java` | **REUSE**. WRITE business : `JpaTransactionManager` strict ; READ business et deux control : `DataSourceTransactionManager` stricts, encapsulés par `Resources`, pas de managers Spring supplémentaires. `read()` fournit le snapshot RR et le contexte de lecture. |
| `app/runtime-task-consumption-worker/src/main/java/com/kartaguez/pocoma/PocomaTaskConsumptionWorkerApplication.java` | **ADAPT**. Actuellement scan global et import historique ; reprendre la séparation Legacy/imports explicites utilisée par Command/monolithe, dans ce module existant. |
| `app/runtime-monolith/src/main/java/com/kartaguez/pocoma/PocomaRuntimeMonolithApplication.java` | **ADAPT**. Déjà scan bootstrap et Legacy exclu pour pilote/DEV ; ajouter le profil READ, partager l’unique factory active. |
| `app/runtime-command-consumption-worker/src/main/java/com/kartaguez/pocoma/PocomaCommandConsumptionWorkerApplication.java` | **REUSE**. Racine WRITE conservée. Le nouveau profil READ doit y être refusé par le préflight ; pas de déplacement du WRITE. |
| `app/runtime-task-consumption-worker/src/main/resources/application.properties` | **REUSE** hors profil V3 ; les valeurs historiques Flyway ne doivent jamais s’appliquer au nouveau profil. |
| `app/runtime-monolith/src/main/resources/application-v3-consumption-worker-dev.properties` | **REUSE** comme référence explicite des exclusions auto-config, OSIV/health JDBC/Flyway et arrêt ; ne pas copier ses autorisations fixtures dans le mode métier. |
| `app/runtime-command-consumption-worker/src/main/resources/application-v3-consumption-worker-dev.properties` | **REUSE** inchangé ; qualification des effets partagés par COMMAND. |
| `app/runtime-monolith/src/main/resources/application-v3-consumption-pilot.properties` | **REUSE** pour le pilote inerte ; profil incompatible avec l’activation métier. |

### 2.2 Participants, sources, ports et consultations

| Fichier exact | Classement et conséquence |
|---|---|
| `app/engine-consume-projection-task/src/main/java/com/kartaguez/pocoma/engine/consume/projectiontask/v3/MaterializePotVersion.java` | **REUSE**. Publication SubobjectVersion/PotVersion/CurrentPot atomique. |
| Même répertoire : `MaterializePotBalance.java` | **REUSE**. Recalcul exact à V ; terminal D sans Balance(D). |
| Même répertoire : `MaterializePotVisibility.java` | **REUSE**. Garde collective et différentiel indépendant. |
| `app/engine-materialize-current-binding/src/main/java/com/kartaguez/pocoma/engine/materialize/currentbinding/v3/MaterializeCurrentBinding.java` | **REUSE**. EUI historique exacte et progression monotone. |
| `app/engine-materialize-command-result/src/main/java/com/kartaguez/pocoma/engine/materialize/commandresult/v3/MaterializeCommandResult.java` | **REUSE**. Demande/terminal WRITE, provenance immuable. |
| `app/engine-materialize-registration-result/src/main/java/com/kartaguez/pocoma/engine/materialize/registrationresult/v3/MaterializeIdentityRequestResult.java` | **REUSE**. Registration/Link/Unlink, initiateur distinct de cible. |
| W/`JdbcPotVersionSource.java`, W/`JdbcPotBalanceSource.java`, W/`JdbcPotVisibilitySource.java` | **REUSE**, chaque fichier. Composition sélective, finances groupées et membres historiques sont déjà implémentés ; pas de mutualisation de préparation métier. |
| W/`identity/JdbcCurrentBindingSource.java`, W/`JdbcCommandTerminalSource.java`, W/`identity/JdbcIdentityTerminalSource.java` | **REUSE**, chaque fichier. Lectures sources exactes sous owner WRITE explicite. |
| W/`consumption/JdbcEventDiscoveryReferences.java`, W/`consumption/JdbcDiscoveryAdmissionStore.java`, W/`consumption/JdbcPartitionOwnershipStore.java`, W/`consumption/JdbcConsumptionSlotStore.java`, W/`consumption/JdbcSlotKeyNavigation.java` | **REUSE**, chaque fichier. SQL générique L2 utilisable sur la base d’effets READ ; aucune copie READ du protocole. |
| P/`PotVersionStore.java`, R/`JdbcPotVersionStore.java` | **ADAPT** seulement pour exposer `currentVersion(potId, context)` en consultation. `read(P,V)` et la preuve recovery corrigée existent ; préserver intégralement leurs oracles. |
| P/`SubobjectVersionStore.java`, R/`JdbcSubobjectVersionStore.java` | **REUSE**, chaque fichier. `readAll(keys, context)` groupé et exact existe déjà. |
| P/`PotBalanceStore.java`, R/`JdbcPotBalanceStore.java` | **REUSE**, chaque fichier. Consultation exacte `(P,V)` déjà disponible. |
| P/`PotVisibilityStore.java`, R/`JdbcPotVisibilityStore.java` | **REUSE**, chaque fichier. `visiblePots(user, after, limit, context)` keyset existe. |
| `app/engine-materialize-current-binding/src/main/java/com/kartaguez/pocoma/engine/materialize/currentbinding/v3/CurrentBindingStore.java`, R/`JdbcCurrentBindingStore.java` | **REUSE**, chaque fichier. Lecture propre EUI avec contexte ; ne pas créer un annuaire inverse. |
| R/`JdbcCommandTerminalResultStore.java`, R/`JdbcIdentityTerminalResultStore.java` | **REUSE**, chacun. `find()` exige une transaction READ appelante ; l’envelopper dans `readOwner.read`, pas dans une transaction autonome cachée. |
| R/`ReadJdbc.java`, R/`SubobjectJson.java`, R/`PotVersionJson.java` | **REUSE**, chaque fichier. Owner/poisoning et décodage stricts. |
| `app/engine-read-current-binding/src/main/java/com/kartaguez/pocoma/engine/read/currentbinding/v3/GetCurrentBindingV3.java` | **REUSE complet** : propre EUI, `ABSENT/NOT_READY/AVAILABLE`, minVersion et vue sans User ; il manque le raccordement au store/owner. |
| `app/engine-read-command-result/src/main/java/com/kartaguez/pocoma/engine/read/commandresult/v3/GetCommandTerminalResult.java` | **REUSE complet** : filtrage requesterEui ; absence/tiers identiques. Raccordement uniquement. |
| `app/engine-read-registration-result/src/main/java/com/kartaguez/pocoma/engine/read/registrationresult/v3/GetIdentityTerminalResult.java` | **REUSE complet**, même principe ; aucune dépendance au Binding courant. |
| `app/engine-read-pot/src/main/java/com/kartaguez/pocoma/engine/read/pot/ReadPotService.java`, même répertoire `ReadPotForExternalIdentityService.java` | **EXCLUDE** des façades V3 : AuthProjection/TokenCapabilities et agrégat historique. Ils ne réalisent pas PotVersion/manifeste V3. |
| `app/engine-read-projection/src/main/java/com/kartaguez/pocoma/engine/read/projection/service/ExactProjectionReadService.java` | **EXCLUDE** du chemin V3 ; conserver physiquement le lecteur historique. |
| Façades Pot courant/historique, lots Expense et navigation authentifiée V3 | **CREATE**, contrats/fichiers fixés au §6 ; pas de façade V3 complète correspondante au HEAD. |

### 2.3 Configurations SQL, permissions et exclusions

| Fichier exact | Traitement I8 |
|---|---|
| `scripts/v3/bootstrap-l2-routing.sql` | **REUSE**. Catalogue et quatre partitions `sha256-v1` ; Invitation enregistrée reste inactive. |
| `scripts/v3/grant-l2-lifecycle.sql`, `scripts/v3/grant-l2-discovery.sql` | **REUSE**, chacun. Permissions techniques exactes et WRITE réel ; le principal READ n’acquiert aucun droit sur l’autorité Identity WRITE. |
| `scripts/v3/bootstrap-l2-worker-dev.sql`, `scripts/v3/grant-l2-worker-dev.sql`, `scripts/v3/worker-dev.py` | **EXCLUDE** du démarrage métier V3 (fixtures/probes DEV). Réutiliser les protocoles de bootstrap dans les fixtures de tests, sans introduire de launcher I8. |
| `scripts/v3/reactivate-l2-slot.sql`, `scripts/v3/configure-l3-identity-logging.sql` | **REUSE**, chacun, dans leur rôle existant ; aucune réactivation automatique ni élargissement du logging sensible. |
| `app/infra-persistence-read-jdbc/src/main/resources/db/v3-consumption-read/migration/V1__consumption_foundation.sql` | **REUSE**, schéma L2 READ. |
| `app/infra-persistence-read-jdbc/src/main/resources/db/v3-read-business/migration/V2__subobject_version.sql` | **REUSE**, stockage immuable I1. |
| Même répertoire : `V3__pot_version.sql`, `V4__pot_balance.sql`, `V5__pot_visibility.sql`, `V6__current_binding.sql`, `V7__terminal_results.sql` | **REUSE**, chaque migration. Tables, contraintes et grants au runtime déjà livrés ; aucune migration ni nouveau grant métier nécessaire par défaut. Le manque constaté est le préflight qui les refuse, pas l’absence de droits SQL. |
| `app/infra-persistence-primary-jpa/src/main/resources/db/v3-pot/migration/V1__pot_components.sql`, `V3__point_history_indexes.sql`, `V4__write_effect_indexes.sql` | **REUSE**, chaque fichier, sources exactes WRITE. |
| `app/infra-persistence-primary-jpa/src/main/resources/db/v3-pot-execution/migration/V2__command_execution.sql`, `db/v3-consumption-write/migration/V5__consumption_foundation.sql`, `db/v3-identity/migration/V6__identity_authority_and_requests.sql` | **REUSE**, chaque fichier, sous le préfixe resources du module ; bootstrap CURRENT V3 explicite et administratif uniquement. |
| `app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/task/consumption/CanonicalProjectionTaskRuntimeConfiguration.java`, même préfixe `runtime/event/consumption/EventConsumptionRuntimeConfiguration.java`, `runtime/binding/BindingRuntimeConfiguration.java`, `runtime/result/CommandResultRuntimeConfiguration.java`, `runtime/registrationresult/RegistrationResultRuntimeConfiguration.java` | **EXCLUDE**, chacun, des deux racines V3 actives ; pas de Task/Binding/Result legacy ni scheduler indirect. Les racines historiques hors profil ne sont pas supprimées. |
| `app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/PotReadConfiguration.java`, même répertoire `ProjectionReadConfiguration.java`, `CommandResultReadConfiguration.java`, `RegistrationResultReadConfiguration.java` | **EXCLUDE** du profil READ V3 ; pas de scan global réintroduisant les façades HTTP historiques. |
| C/`dev/V3TechnicalReadParticipant.java`, C/`dev/V3FixturePotActorAuthority.java` | **EXCLUDE** de la composition métier ; restent disponibles dans leur DEV historique explicite. |
| CurrentInvitation, OIDC/HTTP/consentement ; reset/reconstruction administratifs ; rationalisation Maven/runtimes | **DEFER**, respectivement L5, L6, L7 selon D1–D4. |

## 3. Périmètre I8 et exclusions

**Livrer** activation indépendante des six participants dans les deux racines existantes, Discovery/Consumption réelles, façades internes READ, readiness exploitable, tests physiques d’assemblage, gates reportées et dossier final auditable.

**Verification scope de la future implémentation, déclaré avant code :**

| Rubrique | Déclaration |
|---|---|
| Impact productif | Compositions/settings/préflights, racines PROJECTION et monolithe, services Pot/navigation internes, lecture du pointeur READ ; dépendances minimales des modules existants |
| Primary | **PROJECTION** : `runtime-task-consumption-worker` |
| Secondaires | **BINDING** pour G-I6 ; **COMMAND** car assemblage commun, ressources et paramètres du monolithe mixte affectés |
| Ancres complémentaires | `runtime-command-result-consumption-worker`, `runtime-registration-result-consumption-worker`, `runtime-monolith` |
| Interdits | HTTP/OIDC/consentement, nouveaux modules/runtimes, producteurs/faits WRITE, routage durable, moteur/recovery/transactions L2, dépendance de progression interprojection |
| Niveau | INTEGRATION pour assemblage ; qualification canonique majeure L4 après feedback ciblé |
| Architecture / full | **REQUIRED / REQUIRED** au jalon I8, sans nouvelle dérogation humaine présumée |
| Base | **CURRENT V3** réelle ; aucune baseline TRUSTED inventée. Historique transitif des gates conservé et déclaré ; pas de campagne historique indépendante |
| Escalade | Changement nécessaire de publication/éligibilité/Discovery partagée => analyser EVENT ; contrat/composition HTTP touché => WEB. Redéclarer avant modification ; aucun ajout par prudence |

WRITE reste l’autorité des faits, READ celle des projections. L2 possède les slots, leases, claims, checkpoints, finalisations et recovery. Ni nouveau moteur, ni 2PC, ni règlement spéculatif après UNKNOWN, ni reconstruction WRITE dans une façade. Les dépendances d’une consultation composée ne deviennent jamais des dépendances de matérialisation.

## 4. Architecture runtime, activation et dossier A07

### 4.1 Composition retenue

Réutiliser **le même `V3ConsumptionWorkerConfiguration` et `V3ReadParticipants.create`** dans PROJECTION et le monolithe. Ajouter un profil métier explicite **`v3-read-worker`**, distinct du pilote inerte et du DEV historique. Ce nom est une création I8, pas une option déjà disponible. Le profil conserve l’autorisation fermée `environment=DEV` et les cibles physiques explicites ; il ne vaut pas homologation de production. Aucun changement de frontière Maven/processus exigé par D1–D4.

PROJECTION héberge les READ sélectionnés, sans participant WRITE actif. Le monolithe peut héberger les six READ et les deux participants WRITE réels dans **un Worker et une capacité partagés**. Le runtime Command existant demeure une racine WRITE séparée ; il refuse le nouveau profil READ. Ne pas importer simultanément la configuration Spring inerte du catalogue : appeler sa liste statique suffit.

Dans chaque contexte : une factory READ, au plus un Worker, une capacité, quatre pools et leurs quatre owners, un propriétaire de fermeture. Un second appel `start()` sur la même instance reste interdit. Deux contextes ou processus correctement configurés sont des instances légitimes : leurs candidatures peuvent se chevaucher ; les CAS/leases L2 garantissent un propriétaire durable effectif unique par `(consumptionId, partitionId)`. Ne jamais refuser cette coexistence au motif d’un « double démarrage ». Pas de namespace de slots propre à la racine ; UUID et destination READ sont identiques.

### 4.2 Configuration exacte à implémenter

Sous `pocoma.v3.worker.` conserver `enabled`, `environment`, `worker-name`, `dev-targets`, les quatre groupes de pools et les settings temporels existants. Ajouter **`read-participants`**, liste séparée par virgules des noms canoniques du tableau ci-dessous. Liste vide = aucune activation READ. Refuser doublons, inconnus, alias historiques et Invitation. Dans le profil historique, cette propriété est interdite ; les six participants n’y sont pas activés implicitement.

Pour chaque nom sélectionné : exactement une entrée positive `consumption-limits` et une candidature `partitions`. Les clés doivent être exactement celles des participants effectivement activés, avec ajout des WRITE explicitement activés dans le monolithe. Désactiver une projection = retirer son nom et ses deux entrées, puis drainer/redémarrer l’instance ; aucune mutation de slots/checkpoints ni hot reload. Les données déjà committées restent consultables sous les contrats du §5, sans promesse de rattrapage. Une configuration sans aucune paire active omet Worker/capacité/lifecycle de démarrage ; les ressources de consultation ont alors une fermeture explicite sans attente de Worker.

Exemple de sélection complète READ, sans secrets ni cible implicite :

```properties
pocoma.v3.worker.enabled=true
pocoma.v3.worker.environment=DEV
pocoma.v3.worker.identity-authority=real
pocoma.v3.worker.fixture-identity=false
pocoma.v3.worker.technical-probes=false
pocoma.v3.worker.identity-request=false
pocoma.v3.worker.command-enabled=false
pocoma.v3.worker.read-participants=MaterializePotVersion,MaterializePotBalance,MaterializePotVisibility,MaterializeCurrentBinding,MaterializeCommandResult,MaterializeIdentityRequestResult
pocoma.v3.worker.consumption-limits=MaterializePotVersion=1,MaterializePotBalance=1,MaterializePotVisibility=1,MaterializeCurrentBinding=1,MaterializeCommandResult=1,MaterializeIdentityRequestResult=1
pocoma.v3.worker.partitions=MaterializePotVersion=0|1|2|3;MaterializePotBalance=0|1|2|3;MaterializePotVisibility=0|1|2|3;MaterializeCurrentBinding=0|1|2|3;MaterializeCommandResult=0|1|2|3;MaterializeIdentityRequestResult=0|1|2|3
```

`worker-limit`, `worker-name`, les sept champs de chaque cible et URLs/identifiants/secrets de pools restent obligatoires et externes. Pour la qualification chargée : `M_W=4`, `M_C=1` par READ et par WRITE activé ; ce sont des paramètres de scénario fondés sur le profil MW4 historique, **pas des valeurs de production certifiées**. Le monolithe mixte ajoute `Command=1; IdentityRequest=1` sous la syntaxe existante (virgules pour limites, point-virgule pour candidatures).

### 4.3 Identités A07, sources et séparation des niveaux

| Nom de configuration | UUID inchangé | Famille source WRITE durable | Autorité effets / filtre |
|---|---|---|---|
| MaterializePotVersion | `3dec1c8b-6e3e-57e1-82aa-aa0b5f297a4a` | POT_EVENT ; `v3_pot_event` + positions publiées, histoire Pot@V | READ ; références de la partition |
| MaterializePotBalance | `190a93c6-b8af-5ac7-8001-72e2ec53c2df` | Même famille/journal, propre lecture finances WRITE@V | READ ; même routage, progression distincte |
| MaterializePotVisibility | `b4d04cce-b5da-5d1b-9698-5ff9af9a46fa` | Même famille/journal, créateur/rattachements WRITE@V | READ ; aucune attente PotVersion |
| MaterializeCurrentBinding | `7b8d2838-bf7a-5117-868a-e5c14b4c0221` | IDENTITY_EVENT ; `v3_identity_event` + EUI@V | READ ; **EXTERNAL_USER_IDENTITY seulement** avant enrôlement, User ignoré |
| MaterializeCommandResult | `63664303-d33e-5d42-8cce-c4dae8023311` | COMMAND_COMPLETION_EVENT ; `v3_command_completion_event` + demande/terminal | READ ; provenance initiatrice, pas de Binding actuel |
| MaterializeIdentityRequestResult | `1d8ff246-358f-5ddd-a7f5-416efcfd7445` | IDENTITY_COMPLETION_EVENT ; `v3_identity_completion_event` + demande/terminal/cible | READ ; indépendant CurrentBinding |

Les cinq séquences de publication L2 ne changent pas ; I8 utilise ces quatre familles. Routage par famille + eventId natif, `sha256-v1`, **P=4** fixe. Chacun a ses 4 checkpoints, slots et leases ; le filtre User peut avancer la position examinée sans créer de slot EUI fictif.

Ordre conceptuel à vérifier : **enregistrement** dans le catalogue ≠ **construction** de la factory ≠ **activation** par binding Worker ≠ **démarrage** des threads ≠ **progression** durable ≠ **disponibilité d’une lecture**. La registration des neuf tuples n’active ni Invitation ni un READ désélectionné. La factory inerte peut construire les six objets sans I/O métier ; seuls les bindings sélectionnés obtiennent Discovery/Engine/capacité.

### 4.4 Budgets et insuffisance temporelle constatée

| Ressource / paramètre | Base conservée et preuve attendue |
|---|---|
| M_C, M_W | Limites locales par Consumption et Worker ; total des tickets ≤ M_W et chaque total ≤ M_C. Deux racines n’ont pas une capacité JVM commune : sommer leurs allocations côté serveur |
| Batch / quantum | Batch/page 32, quantum 1 ; batch 1 comme variante adverse existante. Discovery : fenêtre 128, limites références/enrôlement = batch, softCap 64 par paire, hors QUARANTINED |
| Claim / renewal | Claim 60 s, intervalle 10 s, budget d’appel renewal 5 s ; lock 500 ms, statement 2 s ; retry N=3/délai 30 s. Pas de changement de retry/quarantaine |
| Poll / settlement | Poll 10–1000 ms, erreur 1000 ms, settlement 1 s ; aucun ordonnanceur de dépendances |
| Drain | 90 s, délai Spring 120 s ; dépassement = diagnostic, jamais preuve de STOPPED ; issue non résolue conservée |
| Pools par instance | WRITE business = M_W+2, WRITE control = M_W+6 ; mêmes tailles READ. Minimum idle = maximum et réserve physique établie avant start |
| Cas M_W=4 | 6+10 connexions WRITE et 6+10 READ, soit **32 par instance**, même si certaines lanes sont inactives. Deux racines simultanées = 64, dont 32 par base si bases sur serveurs distincts ; ajouter toute instance WRITE séparée |
| Business partagé | Au plus M_W préparations/finalisations actives, séquentielles source/effets ; deux connexions supplémentaires au maximum pour consultations. Bornage des appels façade, refus temporaire si quota occupé ; aucun emprunt READ control pour servir un utilisateur |
| Control partagé | Deux lanes claims + une leases par autorité ; Discovery/acquisition/cleanup/observations techniques utilisent les réserves existantes. Observer les emprunts réels et le pire tour ; maximum configuré seul insuffisant |
| Serveur / observations | Avant campagne : `max_connections` disponible ≥ somme des maxima de toutes instances + budget explicite fixtures/admin/observateurs + réserves serveur. Mesurer ce budget et les PID ; sa valeur dépend du serveur, aucune réserve numérique fictive |
| Timeouts de pools | Conserver acquisition 1000 ms/validation 500 ms ; control socket 5 s et transaction 3 s ; business statement 60 s/transaction 65 s/socket 70 s, owners business 60 s. Échec ou retard physique hors enveloppe reste une faute/UNKNOWN, pas une réussite masquée |

**Insuffisance certaine avant code :** la garde actuelle calcule `10 + 5 × leaseLots + 3 < leaseDuration` en secondes. Six bindings READ avec quatre partitions et batch 32 donnent six lots, donc **43 < 30 est faux** ; monolithe huit bindings : 53 < 30 faux aussi. Une activation complète ne doit pas simplement contourner cette garde. Le comptage nominal ne couvre en outre pas les retours partiels.

**Choix technique I8 :** garder le défaut L2/DEV historique à 30 s ; le nouveau profil exige une lease explicitement dimensionnée, avec **180 000 ms comme configuration de qualification complète**. Justification conservatrice : lane READ unique, au pire 24 candidatures servies en lots partiels de taille 1, 5 s par appel résolu, intervalle 10 s, six fenêtres locales d’omission de 5 s au plus (une par binding et par tour admis), polls `24 × 0,01 s`, observation/maintenance 1 s et marge existante 3 s donnent **164,24 s < 180 s**. Cette enveloppe admet des lots partiels progressants (au moins un candidat renouvelé par appel utile) et au plus une fenêtre d’omission sans progrès par binding dans le tour qualifié. Elle ne promet pas de progrès sous omissions arbitrairement répétées. Le nominal batch32 reste 43 s. Dans le mixte, compter par autorité : READ 24 candidats, WRITE 8 ; ne pas confondre les lanes indépendantes. Claims : la borne existante `10 + 5 × M_W + 3` vaut 33 s à MW4, sous 60 s, à compléter par les observations de chemin entier.

180 s est une **hypothèse calculée à qualifier**, pas une performance L2 acquise ni un allongement d’un timeout de test. Elle n’est pas un nouveau mécanisme : le setting existe. Le préflight du nouveau profil doit vérifier les candidats réels par autorité, les lots partiels, polls/omissions et marges ; refuser une configuration dont la borne ne tient pas. Le préflight vérifie la borne calculée, pas la réalité de la latence : celle-ci est l’oracle physique T10. Les interruptions arbitraires, omissions répétées sans borne ou appels physiques >5 s ne sont pas couverts par l’hypothèse de progression : suspension/DRAINING restent la réponse sûre. T10 doit mesurer les durées depuis les échéances PostgreSQL et chaque candidat, pas seulement le nombre d’appels. Si l’enveloppe calculée ne tient pas dans le scénario admis, corriger localement le budget/configuration justifié et requalifier T01/T10 concernés avant activation ; pas de relâchement de l’oracle.

En **DRAINING**, plus de Discovery, nouvelle acquisition ni tentative non commencée ; cleanup unique des abandons, claims actifs encore renouvelables, pas de renouvellement de propriété perdu. En **UNKNOWN**, conserver tickets, connexions et appels non résolus ; aucun transfert/retry/règlement par supposition. Fermer seulement après témoins physiques et critères L2 ; sinon CRASH explicite, reprise par les mécanismes durables existants. Une lease de 180 s augmente le délai potentiel de takeover après crash ; mesurer ce coût à T09, sans promettre une reprise en 30 s ni augmenter les délais normatifs de drainage.

## 5. Readiness et façades READ

### 5.1 Trois notions séparées

| Observation | Interprétation exploitable |
|---|---|
| Préflights passés, pools/owners réels alloués, Worker RUNNING | Readiness **du worker** seulement ; aucune déclaration de fraîcheur globale |
| Projection désactivée | Pas de progression promise. Exposer activation/configuration séparément ; une donnée immuable committée peut encore servir une consultation satisfaisant son contrat |
| READ neuf, checkpoint absent, aucun slot | État non initialisé/observation insuffisante ; ne pas conclure « à jour » ni « toutes sources vides » |
| Source légitimement vide | Consultation propre vide/absente possible. Démonstration bornée : photographie source publiée, borne sûre H et checkpoints des partitions couvrant H ; elle n’engage pas les publications futures |
| Slots connus présents, différés ou QUARANTINED | Travail connu non terminé ; le checkpoint peut être avancé. Afficher backlog connu sans l’assimiler au backlog total |
| Checkpoint K, aucun slot connu | Dernière position globale examinée, pas un checkpoint de traitement. Des événements déjà publiés peuvent rester non découverts au-delà de K |
| Événement précis publié, pertinent, position≤K de sa bonne paire, slot absent | Preuve dérivée de complétion **de cet événement**, seulement sous les invariants SPEC-05 §3 et preuve métier compatible ; ni maximum global ni complétude d’une consultation composée |
| Restart/rattrapage | Conserver mêmes IDs, routage, checkpoints, slots, effets et quarantaines ; pas de reset. Observer chaque branche et version demandée séparément |
| READ indisponible ou état décodé incohérent | Consultation indisponible/incident technique explicite ; ne jamais fabriquer un contenu ni confondre corruption et absence normale |

Pas de booléen durable « projections READY » ajouté. Rendre disponibles dans le record de services les états Worker existants et l’activation par nom. La progression **bornée** (K/slots/borne H si effectivement observée, avec sa date) est établie par les ports L2 existants et les traces SQL de qualification ; aucun nouveau service de watermark n’est requis. Les snapshots WRITE de diagnostic restent hors chemin de consultation ; ne pas ajouter une lecture WRITE à chaque health check. La readiness administrative après reconstruction totale C-02 relève de L6, pas de cette observation.

### 5.2 Contrats internes à livrer

Toutes les consultations utilisent exclusivement READ, un snapshot `readOwner.read(...)` RR et le contexte valide du callback. Les Results `find()` fonctionnent déjà dans cette transaction appelante ; `GetCurrentBindingV3` reçoit une fonction fermée sur le contexte. Ne pas échapper le contexte ni imbriquer une transaction WRITE. Le service Pot reçoit les ports existants et un lecteur de Binding `(EUI, contexte)` ; aucun adapter WRITE dans son constructeur.

| Consultation | Contrat interne I8 |
|---|---|
| Pot courant `current(EUI, token, P, minVersion)` | Résoudre binding actif de cette EUI, capturer CurrentPot une fois puis PotVersion(P,V), autoriser à V ; si actif et autorisé, exiger V≥minVersion et dépendances exactes. Retourner la version réellement servie |
| Pot historique `at(EUI, token, P, V)` | PotVersion exacte, mêmes contrôles de binding/droits à V ; aucun droit requis à une version ultérieure, ni entrée de navigation. V<D reste lisible après D |
| Vue principale Pot | Header/Details Pot, Shareholders complets nécessaires, Balance(P,V) et manifeste **complet ordonné** des références Expense avec versions locales. Pas de détail Expense chargé globalement ; aucune fuite `authorizedUserIds`, `attachedActiveUserIds`, userId ou token |
| PotVersion / sous-versions exactes | Ports typés existants sous contexte READ ; exposer au service interne de confiance, pas une route de contournement des droits. Ordre canonique UUID PostgreSQL de `SubobjectKey.compareUuid` |
| Lots Expense `expenses(EUI, token, P, V, refs)` | Vérifier droits à V puis appartenance exacte de **chaque** triplet Header/Details/Financials au manifeste de V et au bon Pot ; `readAll` groupé, réponse dans l’ordre demandé, complète ou erreur cohérente. Aucune substitution par CurrentPot/V±1 |
| Balance à V | Valeur rationnelle exacte de `(P,V)`, sous les mêmes droits ; jamais balance voisine. D terminale ne requiert ni ne retourne Balance(D) |
| Navigation `visible(EUI, token, afterPotId, limit)` | Résoudre à chaque page le User du CurrentBinding de l’EUI, appeler `visiblePots` keyset ; aucune autorisation de Pot conférée, aucun snapshot entre pages ni entre Pots |
| CurrentBinding propre | Raccorder `GetCurrentBindingV3` inchangé ; pas de token entrant, minVersion de cette **même EUI**, vue version/actif/son token seulement ; absence normale conserve ABSENT |
| CommandResult / IdentityRequestResult | Raccorder les deux lecteurs inchangés ; EUI initiatrice authentifiée uniquement, sans Binding ni User. Absent et tiers donnent même absence ; résultats indépendants du retard métier, pas de PENDING/EXPIRED/purge |

**Ordre normatif** : identité externe attestée par l’appelant de confiance → binding/token pour Pot/navigation → version pertinente et droit READ à V → terminal/absent/non autorisé masqués → minVersion/dépendances seulement pour une version active autorisée. Un Pot absent de READ ne peut pas être déclaré « en retard mais existant » depuis WRITE. Terminal D donne l’issue masquée même si `minVersion` est immense, sans attendre Balance(D). Une panne globale READ peut donner indisponibilité globale. Une référence étrangère reste masquée, pas une révélation par un diagnostic de dépendance manquante.

Les issues internes Pot sont `Available(value, servedVersion)`, `NotFound` uniforme, `NotReady` après autorisation, `Unavailable` pour panne réelle et `InvalidInput` pour forme/bornes indépendantes de l’existence. En L5 elles seront mappées aux règles 401/404/503 et erreurs d’entrée ; I8 ne certifie aucun statut HTTP. Chaque lecture observe un Binding et une version committés cohérents dans son snapshot : elle ne promet pas une révocation rétroactive d’une requête déjà commencée. SEC-02 accepte le retard d’Unlink jusqu’à matérialisation ; une **nouvelle** consultation après propagation refuse l’ancien token.

Les lots/pages sont bornés par un objet de limites serveur obligatoire à la construction : nombre de références, limite de page et budget de réponse interne ; refus avant accès coûteux, aucune troncature. T06/T08 testent limite−1/limite/limite+1 avec paramètres de fixture, ainsi qu’un contenu très volumineux. **Aucune valeur publique nouvelle n’est ratifiée** : plafond HTTP, octets encodés, curseur opaque, compression, authentification réelle/Keycloak, consentement Link et polling appartiennent à L5 ; profils de charge, Grand Pot/histoire longue exhaustifs, SLO et reconstruction/reset à L6. Le manifeste principal ne subit pas le plafond des lots.

## 6. Plan d’implémentation fichier par fichier

Six étapes de travail dans **une passe** ; aucune nouvelle validation humaine entre choix techniques ordinaires. La qualification vient après assemblage complet et feedback discriminant. Les fichiers **CREATE** ci-dessous sont des chemins futurs en code, volontairement sans lien vers une cible inexistante. Les préfixes Java C/R/P sont ceux du §2 ; pour les nouveaux lecteurs, `Q = app/engine-read-pot/src/main/java/com/kartaguez/pocoma/engine/read/pot/v3`.

| Étape | Fichiers à modifier/créer et réemploi | Acceptation, dépendances et risque nouveau |
|---|---|---|
| **1. Configuration/activation** | **ADAPT** C/`V3ConsumptionWorkerSettings.java`, C/`V3BootstrapGuard.java`. **REUSE** catalogue/UUID/migrations/grants. Ajouter sélection canonique et validation du mode READ, permissions exactes des neuf tables métier, interdiction probes/fixtures/profils superposés. Préflight temporel §4.4 et bornes de lectures | T01/T02 ; refus avant thread/DML, ensemble activé exact, quatre autorités de pools vérifiées. Risque : ouvrir trop largement les types/ACL ou accepter le 30 s insuffisant. Aucune migration par défaut |
| **2. Workers et deux racines** | **ADAPT** C/`V3ConsumptionWorkerConfiguration.java`, les deux `Pocoma*Application.java` PROJECTION/monolithe du §2. **CREATE** `app/runtime-task-consumption-worker/src/main/resources/application-v3-read-worker.properties` et `app/runtime-monolith/src/main/resources/application-v3-read-worker.properties` : mêmes exclusions auto-config et arrêt borné que le DEV, sans cible/secret/activation implicites. **REUSE** factory six participants, surcharges lifecycle/discovery et Worker | T02/T03/T09/T10/T11. Un seul Worker/capacité par contexte, READ sélectionnés seulement, Legacy absent, sources WRITE et effets READ séquentiels ; Command/IdentityRequest restent réels dans le mixte. Risque : démarrage Spring indirect ou emprunts hors budget. Dépend de 1 |
| **3. Façades/readiness** | **ADAPT** P/`PotVersionStore.java`, R/`JdbcPotVersionStore.java` pour `currentVersion` seulement. **CREATE** Q/`ReadPotV3.java` (courant/historique/balance/lots et petit port fonctionnel Binding), Q/`ReadPotV3Result.java` (issues et vues publiques internes filtrées), Q/`ReadNavigationV3.java`, Q/`ReadLimitsV3.java`. **CREATE** C/`V3ReadFacadeComposition.java` (factory et record de services, encapsule `readOwner.read`, trois lecteurs existants), C/`V3ReadFacadeConfiguration.java` (un bean de services explicitement alloué au préflight ; quota local de deux consultations concurrentes par `Semaphore.tryAcquire`, sans réservation Consumption ni attente sur control). **REUSE** tous autres ports/stores et trois Get*V3/TerminalResult. Observation d’activation bornée dans la composition, sans nouveau checkpoint | T04–T08 ; ordre d’autorisation, zéro accès WRITE de consultation, exactitude V et absence de fuite, pas de disponibilité globale inférée. Risque : exposition de domaines internes sensibles, mélange V ou confusion absent/retard. Dépend de 1/2 |
| **3, dépendances nécessaires** | **ADAPT** `app/engine-read-pot/pom.xml` : dépendance `port-projection` (et `port-transaction` directement si importé). **ADAPT** `app/runtime-task-consumption-worker/pom.xml` : `engine-read-pot` existant en scope test à rendre compile, ajouter directement les moteurs Results/current-binding réellement importés par sa composition si Maven les exige (optional du module C). **ADAPT** `app/composition-runtime-spring/pom.xml` seulement si une dépendance directe manque réellement ; ses dépendances lecteur/stores sont déjà présentes. Monolithe possède déjà les lecteurs | Pas de nouveau module ni dépendance d’un moteur de lecture vers infrastructure/WRITE ; Architecture requise. Vérifier le graphe Maven final, éviter de promouvoir des dépendances historiques sans usage. Les fichiers réellement nécessaires sont consignés au diff |
| **4. Intégration physique** | **CREATE** `app/runtime-task-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/task/v3/ReadActivationSettingsTest.java`, `ReadRuntimeAssemblyPostgresTest.java`, `ReadFacadesPostgresTest.java`, `ReadWorkerResourcesPostgresTest.java`, `ReadRuntimeI8Fixture.java`. **CREATE** `app/runtime-monolith/src/test/java/com/kartaguez/pocoma/ReadRootsCoexistencePostgresTest.java`. Réutiliser les producteurs/scénarios des fixtures `ReadParticipantsFixture`, `InterAuthorityPostgresFixture`, `V3WorkerPostgresTest` et `V3IdentityRequestMonolithPostgresTest` ; celles-ci sont souvent package-private : reprendre leurs mécanismes localement, sans extraction de module de test | Onze méthodes §7 ; vraie application principale pour chaque racine, vrais Workers/Discovery ; ne pas se contenter d’un `new ConsumptionEngine` de fixture. Les témoins administratifs restent dans les schémas jetables. Risque nouveau : fixtures qui testeraient une composition différente. Dépend de 1–3 |
| **4, coexistence/structure** | **ADAPT** `app/runtime-monolith/pom.xml` avec dépendance **test** sur `pocoma-runtime-task-consumption-worker` pour charger sa vraie main dans T11 (pas de dépendance productive entre runtimes). **ADAPT** `app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/v3/V3ConsumptionEngineArchitectureTest.java` pour les assertions d’imports/absence legacy/lecture sans WRITE nécessaires à I8 ; conserver les règles historiques | T11 et assertions structurelles dans la gate ; aucune création de six tests L2 identiques. La dépendance test n’ajoute pas de service ni de famille runtime |
| **5. Qualification** | Aucun chantier d’outillage. Exécuter F-I8, puis C-I8 et F-L4 §8 ; conserver les gates historiques non filtrées dans la fermeture. **CREATE lors de la livraison**, `docs/v3/L4_I8_Execution_Report.md`, `docs/v3/L4_I8_Proof_Manifest.json`, `docs/testing/evidence/L4-I8/` avec index physique/SPEC-10/réutilisation/coûts | Toutes identités attendues fraîches, zéro fail/error/skip non arbitré, témoins physiques archivés ; écarts/coûts réels explicites. Risque : verdict du collecteur confondu avec succès Maven. Dépend de 4 |
| **6. Audit/publication/décision** | Mettre à jour ce plan seulement pour écarts justifiés, plan L4/Master/index pour état réel ; publier rapport/manifeste et audit indépendant du code/preuves avant décision humaine de clôture L4. Ne pas réécrire les historiques | Pas de fermeture automatique de L4 par un PASS. Décision humaine finale distincte, réserves L5/L6/L7 attribuées. Une correction locale entraîne son seul rejeu nécessaire, puis analyse de validité des gates déjà acquises |

Le diff I8 ne doit toucher ni `ConsumptionEngine`, `SpringAttemptTransactions`, algorithmes des six participants, source publication, schéma de routage ni migrations historiques pour réaliser ce plan. Si une preuve révèle un défaut concret, documenter le franchissement et sa qualification avant de l’intégrer ; aucune extension silencieuse. Une dépendance POM minimale permise par D1 n’est pas une rationalisation L7.

## 7. Manifeste exécutable de tests avant implémentation

### 7.1 Invariants nouveaux seulement

I8 introduit : **N1** assemblage productif exact et exclusion legacy ; **N2** activation indépendante ; **N3** progression des sources réelles par Workers Discovery/Consumption ; **N4** cohérence/autorisation des lectures composées à V ; **N5** séparation readiness/retard/absence ; **N6** budget partagé de ressources/renouvellements ; **N7** drainage/restart de cet assemblage ; **N8** coexistence des racines sous propriété durable exclusive. Les algorithmes internes des six matérialisateurs, rationalité, fencing, retry et recovery inchangés ne créent pas de nouveaux invariants I8.

### 7.2 Onze scénarios discriminants

Méthodes ordinaires `@Test`, sous-cas explicitement nommés **dans** la méthode : **11 méthodes / 11 invocations nouvelles** attendues, dont 10 PostgreSQL et une pure. Pas de multiplication JUnit implicite par six participants/deux racines. Toutes les classes sauf T11 résident dans `runtime-task-consumption-worker`, package `com.kartaguez.pocoma.runtime.task.v3` ; T11 dans `runtime-monolith`, package `com.kartaguez.pocoma`. Les quatre classes PostgreSQL de PROJECTION partagent les mécanismes de fixture, jamais une base mutable concurrente entre campagnes.

| ID / classe#méthode proposée | Stimulus | Oracle discriminant / nécessité | Invariant ; mode |
|---|---|---|---|
| **T01** `ReadActivationSettingsTest#independentSelectionAndTemporalPreflight` | Sélection complète puis retrait de chacun des six ; liste vide, clés inconnues/dupliquées/incomplètes, mélange profils, READ sur racine WRITE, MW et candidats excessifs ; 30 s et budget calculé | Ensemble exact nom↔UUID↔famille/capacité/partitions ; absence de binding pour tout désactivé ; refus des configurations temporelles insuffisantes, y compris batch1/retours partiels. Nécessaire car settings actuels refusent tous les READ métier | N1/N2/N6 ; **pure** |
| **T02** `ReadRuntimeAssemblyPostgresTest#rootsAndPrivilegesAreClosed` | Démarrer successivement les deux vraies racines avec profil métier ; sous-cas tout READ désactivé ; injecter Legacy indirect, probe, datasource/manager en plus, mauvaise cible, droit READ/WRITE excessif ou PUBLIC indirect | Un Worker/une capacité/quatre pools dans le cas actif, zéro Worker inactif, services internes présents ; exactement les types alloués. Refus avant DML/thread, vecteur coordination inchangé. Nécessaire car contrôle d’ACL et scan Spring réels ne sont pas prouvés par T01 | N1/N2/N5 ; **PG** |
| **T03** `ReadRuntimeAssemblyPostgresTest#sixParticipantsProgressThroughWorkers` | Publier vrais Command/Identity et événements, puis traitement par boucles ; retenir/reprendre un READ à tour de rôle ; publications Pot hors ordre dont D ; User Identity intercalé | Cinq autres branches progressent, trois checkpoints Pot séparés et slot par ID ; effets et suppression du slot même PID/XID, témoins externes avant/après commit ; aucun slot User ; Balance(D) absente légitimement ; chaque branche reprend sans reset. Nécessaire pour passer des moteurs de fixture historiques au Worker assemblé | N2/N3/N5 ; **PG** |
| **T04** `ReadFacadesPostgresTest#currentHistoryAndConcurrentAdvanceStayExact` | Courant V avec minVersion inférieur/égal/supérieur ; manifestes de 0/200/2 000 Expenses en fixtures READ de consultation à oracle indépendant ; V+1 avance pendant capture/lecture V ; histoire V<D après D ; deuxième Pot décalé ; lecteur pendant commit et rollback | Vue complète à version capturée, manifeste ordonné entier, aucune fuite des IDs internes, jamais V/V+1 ; historique autorisé disponible, courant/D masqués sans Balance(D), version supérieure satisfait le plancher. Nécessaire pour P01-I et lecture réelle concurrente | N4 ; **PG** |
| **T05** `ReadFacadesPostgresTest#readinessDoesNotInventFreshness` | READ neuf, source vide puis événement publié non découvert ; K couvrant avec slot en attente/quarantaine ; aucun slot mais publication>K ; projection désactivée ; balance V−1/V+1 seule ; dépendance exacte absente puis disponible ; panne READ | Worker RUNNING ne rend rien globalement frais ; absence/non autorisé masqués avant retard ; `NotReady` seulement après droits, aucune balance voisine, disponibilité rétablie à V sans attendre Visibility ; panne séparée. Nécessaire pour P01-H/P02-K et cas non découvert souvent occulté par un checkpoint | N4/N5 ; **PG** |
| **T06** `ReadFacadesPostgresTest#expenseBatchesRespectExactManifestAndBounds` | Lots ordonnés à V (dont 50 Expenses, taille de fixture sans ratification de plafond public) puis CurrentPot V+1 ; référence d’un autre Pot, autre révision, doublons/vide, manque de sous-version ; limites−1/égale/+1 et gros contenu | Appartenance vérifiée avant lecture de détails ; lot entier dans l’ordre ou erreur, aucune troncature/substitution/fuite ; SQL groupé, pas un appel par Expense ; manifeste principal non plafonné par limite de lot. Nécessaire car aucun lecteur batch V3 au HEAD | N4 ; **PG** |
| **T07** `ReadFacadesPostgresTest#bindingAndResultsKeepTheirOwnAuthorities` | EUI même subject/deux issuers, deux EUI du même User et une d’un autre User, absence d’EUI attestée/User arbitraire fourni, créateur sans Shareholder, retrait d’un/deux Shareholders ; token étranger/inactif ; Unlink WRITE retenu en READ puis propagé/re-Link ; résultats avant domaine/Binding et relecture après re-Link | User dérivé du seul Binding ; droit à version servie, ancien token refusé par nouvelle lecture après propagation ; propre Binding sans token entrant, minVersion de la cible exacte ; Results accessibles seulement au requester, tiers et absence identiques, aucune exposition du token d’E2 à E1. Nécessaire pour raccordement des trois lecteurs et ordre SEC interne | N4/N5 ; **PG** |
| **T08** `ReadFacadesPostgresTest#navigationIsKeysetAndNeverAuthorizes` | Pages à limite bornée, insertion avant/après curseur, retrait, changement de Binding ; visible frauduleux sans droit, absent de liste mais droit à V | Aucun doublon, sémantique sans snapshot interpages, isolation User ; accès Pot refusé malgré visible frauduleux, accès direct permis hors liste ; bornes entrée et réponse. Nécessaire pour P03-M/S-01 et wrapper d’identité au-dessus du port existant | N4 ; **PG** |
| **T09** `ReadWorkerResourcesPostgresTest#drainAndRestartPreserveDurableProgress` | Chaque racine : préparations/finalisations longues, backlog et un slot en quarantaine ; drain normal puis arrêt/crash contrôlé d’une instance et restart sans reset | Plus d’admissions après DRAINING, claims actifs renouvelés, fermeture seulement après drain physique ; effets/slots/checkpoints/quarantaine conservés, rattrapage et mêmes IDs ; mesurer attente de lease au takeover, pas de réactivation implicite. Nécessaire car nouvelles préparations six READ et lease diffèrent du pilote | N3/N7 ; **PG** |
| **T10** `ReadWorkerResourcesPostgresTest#allParticipantsFitPoolsAndRenewalEnvelope` | Monolithe six READ + deux WRITE, MW4/MC1, batch32 puis batch1 ; saturation business et deux lectures, chaque candidature, omission partielle contrôlée ; UNKNOWN avant/après commit et renewal retenu | Tickets ≤ MW/MC, quatre réserves physiques établies, renouvellements/Discovery/observations possibles ; pour chaque candidat mesure échéance/lag/tour entier, enveloppe du §4.4 tenue sous hypothèses ; UNKNOWN retient capacité/appel et interdit faux STOPPED/close, aucun effet spéculatif. Nécessaire pour A07, sans six copies de test de lease | N3/N6/N7 ; **PG** |
| **T11** `ReadRootsCoexistencePostgresTest#projectionAndMonolithSharePartitionsSafely` | Vraies racines simultanées sur mêmes autorités, noms/pools distincts ; candidatures chevauchantes puis distribution disjointe ; stopper le propriétaire d’une partition | Une propriété durable effective par paire, pas d’exclusion artificielle de deux instances ; progression et reprise après échéance, effets immuables uniques, total connexions de deux instances observé ; aucune isolation déduite du seul succès Maven. Nécessaire : scénario absent des conformités I3–I7 | N1/N3/N6/N8 ; **PG**, module monolithe |

T04–T08 instrumentent les DataSources : **zéro acquisition/SQL WRITE pendant les consultations**, après préparation des fixtures. Les vues sont comparées à des valeurs attendues indépendantes, pas à une seconde lecture du même résultat. Les grands manifestes de T04 peuvent être installés administrativement dans le READ jetable pour mesurer le nouveau lecteur ; la preuve de chaîne réelle demeure T03, sans refaire le calcul interne des six participants sur ces volumes. T03/T09/T10/T11 utilisent barrières, horloges SQL, témoins PID/XID et connexions extérieures ; attendre par condition métier, sans sleep de succès ni retry d’une assertion échouée. Les fautes de corruption administrative ne sont jamais des capacités du rôle runtime. Les assertions structurelles d’Architecture sont dans la gate ; elles ne deviennent pas onze scénarios PG supplémentaires.

### 7.3 Réutilisation conditionnelle, contrôlée au HEAD documentaire

Contrôle statique réalisé : index [I3–I7](../testing/evidence/L4-I3-I7/Archive_Index.json) et [recovery](../testing/evidence/L4-Recovery/Archive_Index.json), plus archives ciblées/canoniques ci-dessous : **332 fichiers/indexations d’archives et 4 281 membres référencés hachés, zéro écart**. Ce compte inclut les index et raw contrôlés, pas un nombre de tests. Aucun rejeu ni audit physique nouveau. Les fichiers ne sont pas modifiés.

| Preuve accessible / SHA testé | Propriété réutilisable et comparaison réelle | Limites / conséquence I8 |
|---|---|---|
| [I1 ciblée 37/0/0/0](../testing/evidence/L4-I1/targeted/result.json), HEAD `4028f2fa…` dirty, attribution applicative `533c1474…` et owner `9e8c18a9…` | Clés/contenus immuables, conflits et atomicité store/slot ; attributions du [manifeste](L4_I1_Proof_Manifest.json). Le store sous-objets a ensuite reçu la lecture de preuve corrompue et la fixture est bornée à V2 : utiliser aussi les conformités ultérieures, pas prétendre toutes entrées I1 identiques | Réserve mémoire/volumes, bytecode WEB initial et qualification isolée conservés. Aucune certification de la nouvelle façade ou des pools |
| [I2 canonique 1873/0/0/0](../testing/evidence/L4-I2/canonical/result.json), [Architecture 2137/0/0/0](../testing/evidence/L4-I2/architecture/result.json), `6496e6d0144ecfda9e58ec6d12e259445e7344ad`, propre | Préparation/poisoning/UNKNOWN/recovery propriétaires L2. `git diff` des modules `domain-consumption`, `engine-consumption`, `infra-tx-spring` entre ce SHA et HEAD : vide. Six entrées anciennes existantes diffèrent dans la provenance canonique : helper waiterPid, POM READ, store/port sous-objets, fixture et normalisation Windows ; nouveaux fichiers non contenus dans l’ancien inventaire | Contrats génériques inchangés attribués ; aucune équivalence globale de graphe, assemblage, environnement ou charge. I2-QUAL-01 reste résolu, pas rejoué comme chantier nouveau. T09/T10 qualifient les hypothèses nouvelles |
| [Manifeste I3–I7 : sources/stores/conformités](../testing/evidence/L4-I3-I7/Manifest.md), [comparaison des entrées](../testing/evidence/L4-I3-I7/Input_Comparisons.json), reçus `final-fast-1`, `fast3-01`, `fast4-01`, `final-change-1…5`, `integration-01`, `final-integration-recovery/filter` | 32 méthodes/37 invocations locales composites : exactitude WRITE@V, fractions, SQL/ACL V3–V7, immutabilité, keyset, indépendance et six conformités. Comparaison des **1 357** entrées finales au HEAD : seulement `JdbcPotVersionStore.java` et `ReadParticipantsIntegrationPostgresTest.java` diffèrent ; les deux sont couverts par recovery ultérieur | Les onze reçus listés portent le HEAD `3b7a3115723a3848a84e7c7b88031ecd6306b187` **dirty**, avec empreintes propres à chaque run dans son `provenance.json` ; aucun checkout propre au SHA de livraison n’est prétendu testé. [Publication](../testing/evidence/L4-I3-I7/Publication_Receipt.json). Source/store tests locaux réutilisables, pas leur PASS transformé en G-I3…G-I7 ; la nouvelle méthode de pointeur impose T04/T05 et gates fraîches |
| [Recovery après correctif, 5/0/0/0](../testing/evidence/L4-Recovery/runs/after/result.json), `f30891541787decf308675aeabd57a0577db11c6`, propre | CurrentPot/cible inclus dans preuve de recovery ; supersession Visibility/Binding sous garde K/slot ; trois conformités et UNKNOWN. Les 1 357 entrées de provenance sont identiques au HEAD sauf `app/mvnw.cmd`, normalisation CRLF déjà documentée, wrapper Windows non exécuté. Deux SQL externes hachés identiques | Pas de garantie contre corruption administrative arbitraire ; branches UNAVAILABLE non rejouées dans ce correctif ; pas de réouverture AUD-L4-02 ni reçu durable nouveau |
| [Attribution L1/L2/L3](../testing/evidence/L4-I3-I7/Reuse_Attribution.json), [A09 L4](L4_Read_Inventory_and_Implementation_Plan.md#a09--attribution-nominative-et-limites-vérifiées-sur-pièces) et [clôture L3](L3_Identity_Closure.md) | Calcul rationnel/histoire, mécanismes L2 et décisions humaines dans leurs seuls périmètres inchangés. r12 historique = W2/B32/MW4, lease90, deux probes, candidatures disjointes ; jamais six READ métier | r05/r12, réserves de disponibilité L2/L3, O20/O21 strict INCONCLUSIVE et confiance admin ne sont ni levés ni recréés comme blocages nouveaux. Aucun SLO ni transfert des ressources du pilote |

Environnement historique consulté dans les provenances : Temurin 21+35, Maven 3.9.14 ; recovery Docker 29.9.2/PostgreSQL 17.11 et image épinglée `postgres@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73`. Image/cache chauds et fixtures isolées ne prouvent rien sur la future machine. Les SQL CURRENT V3, grants/scripts, POM, contrats, tests/oracles, settings et dépendances pertinents doivent être comparés **après implémentation** aux empreintes archivées, avec configuration/toolchain/PG/charge/isolation effectives dans la matrice de réutilisation I8. Une hypothèse modifiée ou non vérifiée rend la preuve inapplicable à cette propriété. Aucun ancien PASS ne remplace une gate fraîche prescrite.

## 8. Gates, commandes exactes et coût prévisionnel

### 8.1 Campagnes à lancer seulement pendant l’implémentation autorisée

Depuis `app/`, en checkout isolé du SHA applicatif à qualifier, sans targets hérités d’un éditeur ou d’un autre SHA. Une seule campagne Maven/PG propriétaire des sorties à la fois ; pas de `clean/install/-amd/-T` systématiques. Environnement : Java21/Maven wrapper, Docker disponible pour Testcontainers, PostgreSQL épinglé des fixtures ; cibles jetables distinctes WRITE/READ, admin séparé. Variables d’injection Maven/JVM recensées par politique §35 vides, `.mvn`/settings examinés et hachés ; aucune sélection cachée. Pas de secret dans les preuves.

Pour **chaque** campagne, préparer un nouvel identifiant et un répertoire absolu neuf (ce bloc et les Maven ci-dessous sont des commandes futures, non exécutées ici) :

```sh
i8_invocation="$(python3 -c 'import uuid; print(uuid.uuid4().hex)')"
i8_reports="/private/tmp/pocoma-l4-i8-${i8_invocation}/reports"
mkdir -p "$i8_reports"
```

**F-I8 — une invocation ciblée**, les 11 méthodes nouvelles uniquement (comptes vérifiés avant lancement). Ce n’est pas une gate canonique :

```sh
./mvnw -pl runtime-task-consumption-worker,runtime-monolith -am \
  -Dtest=ReadActivationSettingsTest,ReadRuntimeAssemblyPostgresTest,ReadFacadesPostgresTest,ReadWorkerResourcesPostgresTest,ReadRootsCoexistencePostgresTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dpocoma.verification.id="${i8_invocation:?}" \
  -Dpocoma.verification.reports="${i8_reports:?}" test
```

**C-I8 — une invocation non filtrée mutualisée**, ancres de toutes les slices/Results/compositions et Architecture explicitement sélectionnées :

```sh
./mvnw -pl runtime-task-consumption-worker,runtime-binding-consumption-worker,runtime-command-consumption-worker,runtime-command-result-consumption-worker,runtime-registration-result-consumption-worker,runtime-monolith,architecture-tests -am \
  -Dpocoma.verification.id="${i8_invocation:?}" \
  -Dpocoma.verification.reports="${i8_reports:?}" test
```

**F-L4 — l’unique invocation full reactor obligatoire**, non filtrée, après la précédente :

```sh
./mvnw -Dpocoma.verification.id="${i8_invocation:?}" \
  -Dpocoma.verification.reports="${i8_reports:?}" test
```

Ces trois commandes sont les **Maven test prévus**, hors feedback local diagnostiqué et commandes de découverte/version/provenance explicitement comptées à part. Avec un `help:effective-pom` de même sélection et un `./mvnw -v` par campagne : trois tests + six appels de découverte/version, soit neuf processus Maven de référence, toujours seulement deux gates. C-I8 et F-L4 passent sur les mêmes octets applicatifs et configuration sémantique. Le profil existant `verification-provenance` est activé quand `pocoma.verification.id` et `pocoma.verification.reports` sont fournis : attribuer un UUID et un répertoire absolu **neufs pour chaque invocation**, par les options existantes, puis archiver modèle effectif, inventaire attendu, logs, XML et traces. Ces options ne filtrent aucun test. Aucun nouveau sélecteur n’est requis pour exécuter les commandes natives.

Le collecteur `verify.py` est réutilisable **s’il sait attribuer toutes les formes de tests sélectionnées**. Les refus de découverte, alias de classe top-level ou formes JUnit inconnues doivent être signalés INCONCLUSIVE ; valider nominativement contre les sources/effective POM avec les mécanismes existants, sans fabriquer PASS depuis des XML anciens. Ne pas démarrer un chantier d’outillage préventif. `-Dstep3b.measure=true` utilisé dans des gates I1 historiques n’est pas nécessaire au HEAD : l’exploration a été extraite en benchmark, le test déterministe inconditionnel reste dans la gate. Ne pas ajouter ce paramètre comme prétendue qualification PERF.

### 8.2 Dette canonique recensée individuellement

| Obligation due | Ancrage canonique / commande de référence depuis app/ | Invocation satisfaisante prévue |
|---|---|---|
| **G-I3** PotVersion/Subobject/CurrentPot | `./mvnw -pl runtime-task-consumption-worker -am test` | C-I8, PROJECTION non filtrée, complétée T04/T05 |
| **G-I4** PotBalance | Même commande PROJECTION | Même C-I8, modules partagés une seule fois |
| **G-I5** Visibility/navigation | Même commande PROJECTION | Même C-I8, T08 pour façade/autorisation |
| **G-I6** CurrentBinding | `./mvnw -pl runtime-binding-consumption-worker,runtime-task-consumption-worker -am test` | Même C-I8 avec les deux ancres |
| **G-I7** Results | `./mvnw -pl runtime-task-consumption-worker,runtime-command-result-consumption-worker,runtime-registration-result-consumption-worker -am test` | Même C-I8, deux ancres Results explicites |
| **G-I8** assemblage actif | `./mvnw -pl runtime-task-consumption-worker,runtime-binding-consumption-worker,runtime-command-consumption-worker,runtime-command-result-consumption-worker,runtime-registration-result-consumption-worker,runtime-monolith -am test` | Même C-I8, COMMAND car factory/settings/pools mixtes affectés, monolithe pour vraie composition |
| **Architecture reportée I3–I7** | `./mvnw -pl architecture-tests -am test` | Même C-I8 : les deux dépendances READ Results et nouvelles règles/dépendances I8 sont couvertes |
| **Architecture I8** | Même commande Architecture | Même C-I8, aucune seconde gate Architecture séparée |
| **Full reactor I8 / clôture majeure L4** | `./mvnw test` | F-L4 une seule fois ; conserver toutes variantes PostgreSQL transitives |

La politique §34 autorise la composition d’ancres sans filtrage en l’absence d’obligation d’exécutions isolées. Les commandes de référence du tableau **ne sont pas des invocations supplémentaires**. C-I8 conserve explicitement l’ancre Architecture et toutes les ancres dues ; F-L4 conserve la preuve majeure à commande complète. Le graphe statique au HEAD compte **60 modules enfants + parent** ; PROJECTION seule 50, PROJECTION+monolithe 51, les six ancres métier/composition 55, Architecture 60. La fermeture d’Architecture est donc déjà globale : le signaler dans les coûts plutôt que le présenter comme « quelques tests ArchUnit ». Aucun troisième full reactor, aucun doublon de gate par incrément.

EVENT/WEB ne sont pas des slices de changement ajoutées. Leurs modules passent néanmoins dans la fermeture obligatoire Architecture/full : ce passage transitif ne prouve pas une isolation architecturale et n’autorise pas à modifier leurs comportements. Recalculer le graphe effectif final avant campagne ; les comptes ci-dessus sont une lecture XML, pas un `help:effective-pom` exécuté dans cette mission.

### 8.3 Coûts, mutualisation et traitement des écarts

| Campagne | Tests attendus / modules traversés | Invocations test | Comparable historique | Basse / centrale / haute **indicatives**, machine/cache comparables |
|---|---|---:|---|---|
| F-I8 | 11 nouveaux, 5 classes ; PROJECTION+monolithe, fermeture statique 51 enfants + parent avant changements POM | 1 | I3–I7 intégration 10 invocations : 59,854 s Maven / 63,043 s campagne ; nouveaux tests plus longs (renewal/restart) | **10 / 15 / 30 min** campagne ; incertitude forte, pas de nominal mesuré |
| C-I8 | Tous tests de 60 enfants + parent au graphe actuel ; inclut les 11 nouveaux et Architecture. Nombre exact UNKNOWN jusqu’à inventaire effectif, pas 37 seulement | 1 | I2 PROJECTION+COMMAND **3127,319 s** ; I2 Architecture **3344,163 s**, fermeture globale 60 | **50 / 70 / 100 min** campagne ; nouvelles ressources, compilations et tests historiques dominent |
| F-L4 | Tous tests du reactor, 60 enfants + parent au HEAD ; même inventaire final revérifié | 1 | Full diagnostique **3079,751 s, FAIL** : repère de coût, aucune preuve de succès | **50 / 70 / 100 min** campagne ; très incertain, échec antérieur conservé |
| **Somme de planification** | Trois campagnes séquentielles, **deux gates non filtrées**, un full à commande complète | **3** | I2 deux gates : 6471,482 s ; neuf premiers PASS I3–I7 cumulés **148,391 s** (123,377 s Maven), non transposables aux gates | **110 / 155 / 230 min** (1 h 50 / 2 h 35 / 3 h 50), hors diagnostic/développement/audit |

Ces intervalles sont des **enveloppes d’organisation**, pas des quantiles statistiques ni des limites d’exécution. Le temps Maven est le subprocessus `test` ; la durée murale de campagne ajoute inventaire/modèle/version/collecte ; le temps de développement inclut édition, diagnostic, audit et publication et reste UNKNOWN. Machine froide, téléchargements, image absente, contention PG, coût de la lease 180 s/restart et éventuel défaut historique peuvent sortir de ces enveloppes. Mesurer séparément Maven/fixtures/tests/collecte lorsque observable, sans sommer des durées JUnit parallèles en temps mural.

Économie retenue : pas de rejeu autonome des neuf campagnes I3–I7 ni des conformités inchangées ; une sélection discriminante ; une gate composée pour G-I3…G-I8 et Architecture ; un seul full obligatoire. Les tests historiques exécutés transitivement restent inclus, sans exclusion de r05/r12 ni filtre de confort. Les deux anciens scénarios en quarantaine opérationnelle ne sont pas retirés des gates canoniques.

Un dépassement sain **se termine et se mesure**. Un échec arrête l’enchaînement des campagnes suivantes, conserve toutes preuves et se diagnostique localement ; rejouer seulement le scope nécessaire après correction. Aucun retry inchangé, relancement global automatique ni augmentation de timeout pour atteindre PASS. Examiner l’impact du correctif sur les entrées/oracles de gates déjà exécutées ; seule une obligation concrètement invalidée appelle une nouvelle gate correspondante, distinctement comptée. Pour un blocage présumé, collecter processus/CPU/threads/attentes PG avant décision d’interruption : silence ou prévision dépassée ne suffit pas.

## 9. Matrice SPEC-10 et conditions de clôture

Statuts de cette matrice : **R = déjà prouvé, réutilisable sous comparaison/hypothèses §7.3** ; **N = nouveau test I8 requis** ; **G = gate canonique reportée à exécuter** ; **D = explicitement réservé à L5/L6/L7**. Chaque ligne finale de livraison devra porter lien méthode/variante, SHA, autorité réelle ou fixture, commande, artefact et verdict. R n’est pas un PASS au SHA I8.

| Obligation | Statut et contribution attendue | Limite préservée |
|---|---|---|
| PRJ-01 P01-A…G/J/K | **R** sources/stores/conformités I3 + recovery corrigé ; **G** G-I3 | Pas six nouvelles recertifications de L2 |
| PRJ-01 P01-H/I | **N** T04/T05, dépendances absentes/capture V/lecteurs concurrents ; **G** G-I8 | Réponse interne complète ou indisponible |
| PRJ-01 P01-L, manifeste/stockage | **R** compteurs SQL et chargement sélectif local ; **N** T06 taille/absence N+1 de nouvelle façade ; **D L6** profils exhaustifs/histoire longue/WAL/SLO | Réserve mémoire/volumes I1 conservée, pas de défaut I1 supposé |
| PRJ-02 P02-A…J/L, DOM-03 | **R** calcul rationnel/source/store/participant, D sans Balance ; **G** G-I4 | Oracle indépendant exact, pas de performance inférée |
| PRJ-02 P02-K | **N** T04/T05, balance exacte manquante puis arrivée ; **G** G-I8 | Jamais V−1/V+1 ; Visibility non requise |
| PRJ-02 P02-M | **R** contributions SQL locales ; **D L6** profils, budgets/saturation transversale | Aucun certificat PRJ-02 global sans variante physique requise |
| PRJ-03 P03-A…L/N | **R** règles/guard/store/lecteurs MVCC ; **G** G-I5 | Monotonie/tombstones, ensemble exact conservés |
| PRJ-03 P03-M et S-01 interne | **N** T07/T08 ; **G** G-I8 | Navigation non autorisante ; curseur HTTP **D L5** |
| PRJ-03 P03-O | **R** 10 000 tombstones/plan SQL local ; **D L6** charge/performance globale | Pas de scan historique dans le diff ; pas de SLO revendiqué |
| READ-01 R01-A…G/I/K/L/M | **N** T04/T05/T07, version servie, droits puis minVersion/dépendances, concurrent commit/rollback ; **G** G-I8 | Part interne L4 ; HTTP/Bruno/AuthN réel **D L5** |
| READ-01 R01-H/J | **N** T07 résultats avant métier et T08 Pots/navigation décalés ; **R** indépendance locale antérieure | Aucun snapshot global imposé |
| READ-01 R01-N | **D L5/L6** sondage/polling et mesure E2E | Horodatage de polling ≠ commit exact |
| SEC-01 S01-A/C…N interne | **N** T04–T08 : binding exact, droits à V, masquage avant indisponibilité, créateur sans Shareholder, issuer distinct, références étrangères | S01-B/O authentification cryptographique réelle et HTTP **D L5** ; l’absence d’EUI attestée est refusée au contrat interne |
| SEC-02 S02-A/B/D…J interne, I01-Q | **R** source/store/hors ordre EUI ; **N** T07 fenêtre Unlink/rattrapage, nouveau token, deux EUI ; **G** G-I6/I8 | S02-C autorité WRITE attribuée à L3 ; S02-K E2E/SLO **D L5/L6**, aucun délai de révocation inventé |
| CommandResult / IdentityRequestResult A10-A…F/J/K | **R** sources, mapping, provenance et immutabilité I7 ; **N** T07 raccordement/sessions internes d’EUI, sans Binding requis ; **G** G-I7 | Consentement/sessions authentifiées/exposition HTTP **D L5** |
| A10-G/H/I | **N** T05/T07 absence/tiers uniforme, résultats retardés et panne réelle ; **D L5** codes/enveloppes/polling/401 | Pas de 503 de commodité sur absence normale |
| A11-B…E, suppression/histoire | **R** indépendance et D ; **N** T03/T04/T05 pour chaîne Worker→façade ; **G** G-I3…I8 | A11-A WRITE attribué à L1 ; pas de Balance(D) attendue |
| A11-F…K/N, S-02 interne | **N** T04/T06/T07 : manifeste complet, V<D autorisé, D masqué, lots exacts ; **D L5** transport/HTTP et plafonds publics | A11-L/M budgets E2E/charge **D L6**, observation interne D disponible |
| EVT-03 E03-A…G/J, six identités/progression | **R** sources/routage/filtre ; **N** T01/T03/T11 vraie composition ; **G** G-I8 | E03-H/I reset/rejeu indépendant **D L6** ; Invitation **D L5** |
| CON-03…06, atomicité et recovery | **R** I1/I2/conformités/recovery, y compris CurrentPot ; **N** T03/T10 témoins dans assemblage, UNKNOWN conservateur ; **G** C-I8 | Pas de réouverture AUD-L4-02 ni de règlement spéculatif |
| Ressources, isolation, restart/retard | **N** T02/T05/T09/T10/T11, A07 ; **G** G-I8 | L2/L3 disponibilité sous hypothèses, confiance admin et risques 30 s historiques conservés ; aucune homologation production |
| G-I3, G-I4, G-I5, G-I6, G-I7, G-I8 | **G** C-I8, obligations individuelles §8.2 | PASS locaux antérieurs ne les satisfont pas |
| Architecture reportée + Architecture I8 | **G** C-I8 avec ancre explicite | Nouvelle dépendance/règle auditée, D1–D4 respectées |
| Full reactor jalon majeur L4 | **G** F-L4 | Un FAIL historique n’est ni un PASS neuf ni une exemption |
| Réserves I1/I2/I3–I7 et recovery | **R** décisions bornées conservées ; **N/G** seules propriétés affectées et dettes ci-dessus ; **D** limites L5/L6/L7 attribuées | I2-QUAL-01 résolu ; aucune réserve historique rouverte sans preuve contraire |
| CurrentInvitation, ID-02/C-01, INV-01 ; REC-01/C-02, OPS/PERF complets ; convergence physique | **D L5**, **D L6**, **D L7** respectivement | Aucune rationalisation exigée comme préalable à I8 |

**Sortie technique I8 :** les 11 scénarios et obligations internes sont couverts avec leurs variantes, les deux invocations canoniques réussissent sous leurs inventaires non filtrés, les preuves réutilisées sont comparées et attribuées, A07 mesure les ressources/temporisations réelles dans les deux racines et en coexistence, les écarts/réserves sont documentés. Une partie HTTP/performance différée n’empêche pas la qualification interne explicitement bornée, mais interdit de proclamer SPEC-10 globalement certifiée.

Ensuite : **audit indépendant du code, de la couverture et des preuves**, résolution locale des findings affectant la clôture, publication du dossier complet, puis **décision humaine de clôture L4** précisant portée et réserves. Ce plan n’accorde ni cette clôture ni un GO de déploiement.

## 10. Risques, arbitrages et décision proposée

| Risque nouveau établi / à prouver | Traitement et condition bloquante technique |
|---|---|
| Profil actuel incapable d’accepter les READ et ACL métier | Préflight fermé et profil explicite, T01/T02 ; pas de start sur configuration ambiguë |
| Lease30 insuffisante, tour/retours partiels sous-estimés | Dossier A07, lease180 de qualification justifiée, mesure de chaque candidat T10 ; activation refusée tant que budget non vérifié |
| Lectures exposant User/token ou disponibilité avant autorisation | Vues filtrées, même snapshot READ, ordre normatif, T04–T08 ; aucune façade WRITE de secours |
| Lectures consommant la réserve nécessaire aux traitements | Quota business limité aux deux connexions supplémentaires, T10 ; aucune consommation de control par façade |
| Deux racines activées sur mêmes partitions | Coordination L2 existante et T11, budgets serveur sommés ; pas de faux verrou global de démarrage |
| Coût des gates / échec historique non local | Deux campagnes non filtrées mutualisées, mesure et diagnostic proportionné ; aucune coupure sur estimation |
| Ancienne preuve trop largement attribuée | Comparaison nominative sources/SQL/POM/tests/configuration/environnement et statut R limité ; gates fraîches conservées |

**GO WITH RESERVATIONS proposé pour lancer l’implémentation complète I8** : l’architecture et les contrats sont suffisamment déterminés ; les réserves sont la qualification des hypothèses de ressources et les obligations canoniques non exécutées. **Aucun arbitrage humain métier/structurel bloquant restant identifié.** Le dimensionnement serveur et les mesures sont des entrées techniques à relever, pas une demande de rouvrir les SPEC. Une exception D1, une modification normative/gate ou un défaut nécessitant un autre moteur exigerait une décision distincte fondée sur une preuve ; aucune n’est proposée ici.

Publication de cette mission : uniquement ce plan et les documents de pilotage/indexation nécessaires, commit documentaire sur la branche existante, push sans force, contrôle SHA local/distant/divergence/worktree. Commandes effectivement utilisées : lectures `cat`/`sed`/`rg`, commandes Git `status`, `branch`, `rev-parse`, `remote -v`, `worktree list`, `log`, `ls-remote`, `rev-list`, `show`, `diff` et `diff --check` ; Python en lecture statique pour POM, chemins/liens/ancres et SHA-256 des preuves. Les lectures et contrôles documentaires n’ont déclenché aucune campagne, migration, gate Architecture ou full reactor : **NOT REQUIRED / NOT RUN pour cette mission**, **REQUIRED futurs pour I8**. Le SHA de publication et l’état distant effectivement constaté sont rapportés dans la conversation après push, sans autoréférence dans le contenu du commit.
