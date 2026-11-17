# Consolidation canonique Discovery → READ — inventaire et revue

Date : 2026-10-20. Livraison **documentaire exclusivement**.
SHA initial vérifié : `b7aacef54457583eccd405ef03acc21e05bb156c` ; branche
`feat/consumption-transaction-protocol`, divergence initiale **0/0 après fetch réel**.
Worktree existant utilisé : `/private/tmp/pocoma-consumption-transaction-protocol`.
La clôture Git (SHA final/push/divergence) sera donnée dans la restitution de livraison pour éviter
une autoréférence au commit de ce rapport.

## Inventaire préalable et choix d'emplacement

[AGENTS](../../../../AGENTS.md), [politique](../../../testing/Reactor_Verification_Policy.md),
[contribution](../../../../CONTRIBUTING.md), [gouvernance](../../../Documentation_Rationalization_Audit.md#15-gouvernance-documentaire)
et [validation documentaire antérieure](../../../Documentation_Truth_Validation.md) examinés avant écriture.
Aucun trio actif current_canon/current_plan/next_plan ni équivalent du chantier n'existait.
Les anciens Steps emploient `Step_Canon.md` / `Step_Plan.md` ; le nouveau dossier actif
`docs/steps/current/DISCOVERY/` reprend cette convention et ajoute `Next_Plan.md`.
Le propriétaire des arbitrages est le responsable du dépôt ; prochaine unité concrète **4B**.

| Sujet inventorié | Sources conservées / rôle après consolidation |
|---|---|
| Architecture et garanties | [Architecture](../../../architecture/Architecture.md), [System Guarantees](../../../guarantees/System_Guarantees.md), modèle fonctionnel lié depuis le canon : autorités CURRENT transversales |
| Consumption P/B/S, owner, fencing, rollback/retry | [Protocole CURRENT](../../../architecture/consumption-transactional-execution.md), WP0–WP6 et clôture, 2B CAS et 2C fence partagé : détails et preuves liés par le canon |
| Autorités, numérotation et Discovery | [Index transactionnel](../../../architecture/transactional-protocol-studies.md), études numérotation tardive/verrouillage et protocole consolidé : hypothèses/baselines historiques, subordonnées à l'Étape 4 |
| Publication et E | [Plan 4 détaillé](../../../architecture/Event_Transactional_Sequencing_Step_4_Implementation_Plan_2026-10-19.md), [contrats 4A](../../../architecture/Event_Publication_Contracts_Step_4A_2026-10-19.md), études/prototypes 3A–3D : détail conservé, statuts actualisés |
| Validation 4A récente | Diagnostic différentiel, diagnostic causal Consumption, correction des cinq fixtures, correction bootstrap et JSON versionnés : chronologie rouge→vert, liens depuis Step Plan |
| Discovery CURRENT | [Runtime Event](../../../architecture/consumption-event-pull-runtime.md), source/requête JDBC, recherches Binding/LKV/Results citées par les études ; métadonnées sans payload, anti-jointures slots/claims/JSONB et curseurs locaux distingués du futur checkpoint |
| READ et matérialisation | [Contrat exact](../../../architecture/read-side-target.md), [snapshot PCL](../../../architecture/read-side-current-state.md), [projection engine](../../../architecture/projection-engine.md), [ProjectionTask](../../../architecture/projection-task-execution.md), Steps EPT/CCR/REGISTRATION et ARCHITECTURE clos : contrats spécialisés et histoire, sans nouveau plan courant concurrent |
| Anciennes roadmaps | [Plans exécutés](../../../plans/completed/), [archives](../../../archive/), matrice de rationalisation : traçabilité conservée ; pas de résurrection des plans head/serving/LatestKnownVersion |

Les signatures de `PublishedEvents`/`EventRef`, le query Discovery, les définitions/projectors
READ_POT/POT_BALANCES/AUTH et le service READ E→U ont été relus statiquement dans `app/`.
Aucun fichier de code n'est modifié ou exécuté.

## Créations et mises à jour

Créés : [Step Canon](Step_Canon.md), [Step Plan](Step_Plan.md), [Next Plan](Next_Plan.md) et ce rapport.
Les trois premiers sont les points d'entrée demandés ; ce rapport conserve l'inventaire et la revue,
sans ajouter une autorité de conception.

Mis à jour : `docs/README.md`, gouvernance §15 de l'audit de rationalisation, Architecture,
System Guarantees, index transactionnel, plan détaillé 4, rapport 4A, runtime Event et les deux
références READ. Aucun déplacement/suppression ; aucun contenu historique volumineux recopié.

## Contradictions résolues et décisions consolidées

- 4A initialement livré avec gate rouge : **CLOSED** selon la présente demande, au SHA initial,
  après fixtures Consumption et bootstrap corrigés, **1 257 déclarés / 0 failure / 0 error / 1 skip**.
  Les anciens rapports gardent leurs résultats datés ; le dernier gate et son JSON restent l'autorité
  de preuve. Aucune nouvelle exécution revendiquée.
- Le skip est la campagne exploratoire 3B conditionnée par `step3b.measure=true`, déjà documentée
  et activable ; aucun gate 0 skip, seuil de capacité ni preuve de runtime E n'en est déduit.
- Les choix finaux du plan 4 (déclaration applicative après acceptation, ordre des appels par famille,
  suffixe dans callback standard) prévalent sur les mécanismes expérimentaux 3B/3C. P/B/S reste livré ;
  E est une cible supplémentaire. 4B–4E restent inchangés de sens et numérotation.
- Discovery : progression d'enrôlement distincte de l'acquisition et de la terminaison ; backlog durable
  incluant les acquis en cours, continuité prouvée, consommateurs/routes indépendants ; un worker par
  partition reste une hypothèse. 4E ne livre pas le checkpoint/les requêtes optimisées.
- READ : état CURRENT et nouveau cadrage séparés. Snapshot post-PCL étiqueté historique ; contrats
  exacts conservés ; cible header mutable référençant les expenses/version sans leur contenu complet,
  expenses et balances séparées. AUTH/LKV explicitement dans la correspondance à arbitrer.
- Deux erreurs documentaires CURRENT corrigées statiquement : matrice des garanties E→U désormais
  via CURRENT_BINDING READ selon Architecture/service existants ; runtime Event sans ancien prélock
  de finalisation, effet rollbackable puis CAS/clôture selon protocole WP6. Aucun comportement changé.

## Historiques préservés et questions ouvertes

Études 3A–3D, numérotation tardive, protocole consolidé, diagnostics 4A rouges, résultats intermédiaires,
plans clos et archives sont conservés à leur emplacement ; les liens d'entrée précisent leur statut.
Le plan détaillé 4 demeure une spécification active subordonnée au suivi courant, pas une archive.
Le rapport non suivi `docs/architecture/Architecture_Last_Skip_Audit_2026-10-20.md` est lu pour contexte,
**inchangé, non déplacé et exclu du commit**. Sa preuve n'est pas nécessaire pour lire les nouveaux plans.
Les autres worktrees et tous leurs travaux préexistants restent hors livraison.

Ouvert : preuve owner physique/fermeture/UNKNOWN en 4B ; coût E/DDL et qualification cutover ; checkpoint,
partitionnement, réexamen, requêtes/index et critères de performance ; identités des versions d'expense,
complétude/visibilité du header, autorisation/liste des pots, reconstruction et rattrapage READ.
Les évolutions expectedVersion/poids/invitation/attach/detach restent des lots fonctionnels distincts.
Le Next Plan indique leurs dépendances sans les rendre obligatoires pour toute refonte READ.

## Vérification documentaire

Périmètre déclaré avant écriture : impact production/test/DB **NONE** ; slices primaire/secondaires
**NONE — documentation et liens statiques** ; franchissement inattendu **NONE**.
Global architecture gate **NOT REQUIRED/non exécuté**. Full reactor **NOT REQUIRED/non exécuté**,
car aucun runtime/test/schéma/dépendance modifié. Aucun Maven, test applicatif, migration ou SQL exécuté.
Les commandes Maven dans Step Plan sont futures ; celles des rapports 4A sont des preuves historiques.

Commandes de cette livraison : lectures `cat`, `sed`, `rg`/`rg --files`, `shasum`, scripts Python de
comparaison/liens et snapshots ; `git status --short --branch`, `git rev-parse HEAD`,
`git worktree list`, `git log`, `git fetch origin feat/consumption-transaction-protocol`,
`git rev-list --left-right --count HEAD...origin/feat/consumption-transaction-protocol`,
`git diff --check`, revue du diff et staging nominatif documentaire, commit et push normal.
Le premier fetch sandboxé refusait l'écriture FETCH_HEAD partagé ; sa reprise autorisée a réussi.

Contrôles finaux : liens locaux et ancres des entrées du Step, liens ajoutés/modifiés contre baseline,
cohérence 4A–4E/compteurs de preuve/Git, diff exclusivement sous `docs/`, absence de doublon de plan
courant, empreinte du rapport non suivi et comparaison des autres worktrees. Résultats chiffrés de
la vérification statique consignés à la clôture ci-dessous.


Clôture statique : **PASS**. **2 095 liens locaux contrôlés, dont 74 dans le nouveau Step**,
aucun lien/fragment invalide ajouté et aucun défaut dans les documents modifiés/créés. Le même
contrôle sur la baseline et l'état final retrouve 152 anomalies préexistantes ailleurs (cibles/ancres
historiques), sans extension du périmètre pour les réparer. `git diff --check` PASS ; compteurs du
JSON final, propriété JUnit et SHA de baseline vérifiés. Revue des critères §§6–9 contre le Step Plan :
numérotation, limites PREPARED/ACTIVE, critères de cutover et gates conservés. Aucune nouvelle garantie
d'exécution annoncée. Snapshots avant/après : les cinq autres worktrees sont identiques (HEAD,
statuts et empreintes des travaux), rapport non suivi identique et exclu. Diff limité à **14 fichiers
Markdown sous docs/**, dont quatre créés ; aucun fichier runtime, test, migration ou configuration.
