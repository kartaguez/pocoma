# L2 — Clôture documentaire officielle — Consumption & Discovery

**30 octobre 2026 — L2 : ACCEPTED WITH EXPLICIT RESERVATIONS.**
Dépôt `kartaguez/pocoma`, branche `feat/v3-architecture`, SHA initial de référence
et tête distante vérifiée : `0c6376a13a0a3c123f022ffff21918e6fca1e581`.

## 1. Décision humaine officielle et hiérarchie des statuts

Décision humaine du 30 octobre 2026, enregistrée sans modification :

> L2 — Consumption & Discovery est accepté et clos avec réserves explicites.
>
> Statut officiel : ACCEPTED WITH EXPLICIT RESERVATIONS.
>
> Les mécanismes livrés constituent une fondation technique suffisante pour poursuivre Pocoma V3.
>
> Cette acceptation ne vaut ni PASS technique global, ni certification de production, ni levée des réserves encore ouvertes.

Cette décision est définitive pour la poursuite du programme. Elle autorise la
clôture documentaire et le passage à L3 ; elle ne modifie aucune exigence des
[SPEC-03](SPEC-03_Events_Requests_Consumables.md),
[SPEC-05](SPEC-05_Consumption_Discovery.md) et
[SPEC-10](SPEC-10_Test_Certification.md), autorités des invariants et des preuves.
Le [plan directeur](V3_Implementation_Master_Plan.md) porte le statut de programme.
**ACCEPTED est une acceptation humaine, distincte de PASS technique.**

Le présent document est l'enregistrement unique de cette clôture. Il prévaut pour
les statuts de programme sur les mentions antérieures « L2 non accepté »,
« I7 à réaliser » ou « G1/G2/G3 futurs » des dossiers historiques, notamment le
[dossier I6](L2_I6_I7_Consolidation.md). Ceux-ci restent inchangés et décrivent
leur date et leur périmètre. Aucun FAIL, SKIP, NOT RUN ou INCONCLUSIVE historique
ne devient PASS ; les critères techniques de G3 ne sont ni satisfaits ni supprimés
par cette décision. I5 n'est pas rouvert.

## 2. Périmètre exact accepté et contributions livrées

L'acceptation porte sur la fondation technique Consumption/Discovery disponible,
avec le pilote Command Pot réel en WRITE et les bancs READ techniques identifiés.
Elle conserve les limites de chaque campagne, de chaque fixture et de chaque SHA.

| Contribution | Acquis accepté dans son périmètre borné |
|---|---|
| I1 — fondations | Familles et identités, routage stable, quatre tables techniques par autorité, bootstrap administratif, droits et isolation des compositions |
| I2 — transaction et participant | Engine propriétaire de l'unité physique ; participant Command Pot ; finalisation R1, observation R9, guards et poisoning ; effets/résultat/terminalisation/publications/suppression du slot atomiques |
| I3 — lifecycle | Ownership de partition, claims et fencing individuels, capacité, cession/retry, quarantaine persistante et réactivation explicite ; corrections R-AUD-I3-01/02 vérifiées localement |
| I4 — Discovery | Fenêtre non-burnable cohérente et préfixe couvert ; checkpoint et slots atomiques dans l'autorité des effets ; parcours burnable volatil, revalidation PENDING et régulation |
| I5 — workers | Deux boucles, navigation, capacité unique, renouvellements indépendants, séparation des ressources, suspension/drain conservateur, pilote et outils DEV ; disponibilité et temps non certifiés globalement |
| I6 — consolidation | Rapprochement documentaire, provenance, matrice des allocations et index des preuves existantes ; aucune nouvelle certification applicative |

Les frontières WRITE/READ et les protections transactionnelles restent les
contrats réutilisables. L'acceptation ne transforme ni les analogues SQL des
familles absentes en producteurs réels, ni les probes READ en projections métier.

## 3. Statuts officiels de clôture

| Élément | Statut de clôture |
|---|---|
| I1 | CLOSED / LOCAL BOUNDED PASS |
| I2 | CLOSED / LOCAL BOUNDED PASS |
| I3 | CLOSED / LOCAL BOUNDED PASS, avec réserves documentées |
| I4 | CLOSED / LOCAL BOUNDED PASS |
| I5 | CLOSED BY HUMAN ACCEPTANCE WITH AVAILABILITY RESERVE |
| I6 | COMPLETED — consolidation documentaire et des preuves |
| I7 | Revue indépendante favorable sur le périmètre examiné, non exhaustive |
| G1 | INCONCLUSIVE |
| G2 | INCONCLUSIVE |
| G3 | NOT PASSED / NOT COMPLETED |
| L2 | ACCEPTED WITH EXPLICIT RESERVATIONS |

Le statut I7 enregistre l'avis indépendant existant tel que transmis par le
mandat humain de clôture du 30 octobre. Il n'atteste ni une exécution exhaustive
du programme d'audit proposé par I6, ni un certificat technique global. Aucun
rapport supplémentaire n'est inventé ou produit ici. Les rapports indépendants
historiques et les corrections I3/I5 référencés dans I6 conservent leur objet ;
les audits conceptuels antérieurs ne sont pas réattribués à I7.

## 4. Réserves ouvertes, identifiants stables

### R-L2-01 — Certification temporelle

- AUD-I5-01 : garantie temporelle conditionnelle non certifiée globalement.
- R-AUD-I3-03 : réserve non levée techniquement.
- Les résultats FAIL des bancs temporels v1, v2 et final sont conservés.
- Aucun nouveau défaut de sûreté n'est démontré par ces campagnes ; cela ne prouve pas une disponibilité générale.
- La configuration de renewal à 30 secondes reste non certifiée ; aucun certificat global 90 secondes n'est acquis.
- Aucune garantie générale de renouvellement dans un délai borné sous contention arbitraire.

### R-L2-02 — Disponibilité et reprise

- Suspension et retrait local des leases acceptés en cas d'indisponibilité.
- Drainage conservateur acceptable en présence d'une issue physique incertaine.
- Délai de reprise, expiration et réacquisition non garantis universellement.
- Aucun contournement des tokens de fencing, claims ou protections transactionnelles autorisé.

### R-L2-03 — Certification globale

- G1/G2 restent INCONCLUSIVE ; G3 n'a pas été franchi : NOT PASSED / NOT COMPLETED.
- Gate Architecture globale et Full Reactor de clôture non exécutés sur le gel final. Les exécutions historiques sur d'autres SHAs restent attribuées à ceux-ci.
- Les cinq allocations NOT RUN et les autres allocations incomplètement couvertes gardent leur statut.
- Aucun FAIL, SKIP ou INCONCLUSIVE historique ne devient PASS.
- AUD-I5-02 conserve le correctif et le PASS local de `i5_m03` ; la gate composée corrigée finale reste NOT RUN, sans clôture technique indépendante ajoutée.

### R-L2-04 — Périmètre fonctionnel

- Command Pot réel disponible dans le périmètre du pilote.
- IdentityRequest réel non certifié ; les fixtures d'autorité Identity ne prouvent pas l'autorité réelle.
- Projections métier READ non livrées/certifiées par L2 ; les probes READ techniques ne les certifient pas.
- Identity, Invitation et fonctionnalités HTTP complètes différées aux lots correspondants.
- Performances intégrées et SLO à qualifier ultérieurement.

### R-L2-05 — Déploiement

- Aucun GO de production et aucun certificat de release V3.
- Les démonstrations, le profil opt-in et les outils DEV existants conservent leurs limites et leur provenance historique.
- La reprise des critères G3, de l'intégration et des gates de release sera examinée aux jalons appropriés, sans prétendre qu'ils ont déjà été satisfaits.

**Ces cinq réserves restent ouvertes et ne bloquent pas la poursuite de L3.**
L'acceptation ne prononce aucune dispense des protections ni aucune qualification
supplémentaire de disponibilité, de performance ou d'environnement.

## 5. Preuves existantes et SHAs réellement testés

Le [manifeste d'allocation](L2_Certification_Coverage_Manifest.md) reste historique.
La qualification documentaire existante est dans
[Coverage.tsv](../testing/evidence/L2-I6/Coverage.tsv) et
[Proof-index.json](../testing/evidence/L2-I6/Proof-index.json), avec
[Provenance.json](../testing/evidence/L2-I6/Provenance.json) et
[Verification.json](../testing/evidence/L2-I6/Verification.json).
Les 380 allocations ne sont pas recopiées : 26 PASS bornés, 293 INCONCLUSIVE,
5 NOT RUN et 56 NOT APPLICABLE pour L2 (reports restant NOT RUN dans leur lot futur).
Ces comptes ne remplacent ni les FAIL des bancs ni les oracles normatifs.

Les cinq NOT RUN sont `L2M-E01-D`, `L2M-E01-E`, `L2M-E01-G-INV-COMP`,
`L2M-E01-G-ID-IDCOMP` et `L2M-E01-E-IDENTITY-REJECT`.

| Observation historique | SHA réellement testé | Source et limite |
|---|---|---|
| I1 COMMAND/READ/monolithe | `12eaa5ae983c23a4b07c0299ce59c4d7933bfb5d` | [Clôture I1](L2_I1_G0_Readiness_and_Implementation_Plan.md), [index I1](../testing/evidence/L2-I1/Gate-index.json) |
| I1 architecture | `6835b5c15cc40d09c22e0b0a4f3b6538ac49992d` | Même index ; ne pas attribuer les quatre gates à un seul SHA |
| I2 | `4f2264ae6e11e7e6e1511577064bca5e31ad1336` | [Rapport I2](L2_I2_Execution_Report.md), quatre gates locaux |
| I3 initial | `1d7cf51c13671c7c85a5c9c1281f526be2b2abfb` | [Rapport I3](L2_I3_Execution_Report.md) |
| I3 corrections | `3ee575940669feb56b73b10122b5a3406bce6a08` | [Corrections post-audit](L2_I3_Post_Audit_Corrections_Report.md), R-AUD-I3-03 non levée |
| I4 | `0b08d3bf0bfce1334260cfd5249b13c3040a67bb` | [Rapport I4](L2_I4_Execution_Report.md), producteurs disponibles et fixtures distingués |
| I5 initial | `9a315e16567042d0b9bd017486d9043216b3ecd7` | [Rapport I5](L2_I5_Execution_Report.md), gates historiques ; ancien C temporel invalidé par l'audit |
| I5 v1 / gate corrective | `05b74814abe810a22f1f6a4c5125d4374325f6fa` | [Rapport correctif](L2_I5_Audit_Remediation_Report.md), gate FAIL |
| I5 ciblage métrique et contre-exemple | `8990737ce0b6f7df155d7e80aa698657815dc5ef` | Même rapport : m03 PASS local, r12 FAIL |
| I5 v2 | `5eab2e94379ef40c3f9ff54c14a4d04a8f13dcbd` | [Rapport v2](L2_I5_Temporal_V2_Report.md), FAIL de qualification |
| I5 final | `a2133b6b43a74999374bc26a222f70c7500fadda` | [Rapport final](L2_I5_Temporal_Final_Report.md), banc FAIL et conclusion INCONCLUSIVE |

Le SHA initial de la présente clôture est une référence documentaire, pas un
nouveau SHA applicatif testé. Le SHA du commit de clôture est communiqué après
publication ; aucune auto-référence Git circulaire n'est créée.

La [politique rationalisée](../testing/Certification_Evidence_Rationalization.md),
la [procédure de récupération Git](../testing/Evidence_Recovery.md) et
[l'inventaire de rétention](../testing/Evidence_Retention.json) conservent les
empreintes et les localisateurs exacts. SHA de stockage et SHA testé sont distincts.
Aucune archive volumineuse, restauration permanente ou nouveau manifeste exhaustif.
Exemple de résolution seule, depuis la racine :

```sh
python3 -B scripts/testing/evidence-storage.py resolve docs/testing/evidence/L2-I5/F-9a315e16.tar.gz
```

Pour une lecture ultérieure, employer `restore` vers une destination temporaire
neuve selon la procédure liée, puis vérifier l'empreinte attendue ; ne pas
exécuter les membres d'archive. Les ancêtres Git de rétention restent nécessaires.
Les reçus et empreintes I6 restent figés sur leurs entrées historiques : les trois
points d'entrée de programme actualisés ici ne sont pas réempreintés dans I6.

## 6. Exigences reportées et dépendances pour L3 — Identity

**L3 est autorisé pour la suite du programme, sous son propre cadrage, ses propres
critères de vérification et ses décisions d'implémentation.** Cette intervention
ne commence ni son implémentation ni un nouvel audit conceptuel global.

| Dépendance ou exigence à reprendre | Portée de la transition |
|---|---|
| Invariants Consumption/Discovery | Réutiliser routage stable, ownership pour Discovery/acquisition, claim individuel au verrou final, atomicité et R1/R9, retry/quarantaine sans purge ; conserver les limites des preuves |
| Contrat générique d'exécution IdentityRequest | Demande burnable PENDING, découverte asynchrone sans slot à l'admission, une tentative complète par acquisition ; vraie autorité Identity à cadrer et vérifier en L3, sans importer le PASS de la fixture |
| Publications Identity et complétion | Mutations Identity, résultat, terminalisation, événements et suppression du slot dans une transaction WRITE ; IDENTITY puis IDENTITY_COMPLETION, sans ordre global inter-séquences ; absence de mutation = complétion seule. Contrats SPEC-03/05, oracles SPEC-10 à prouver sur producteurs réels |
| Burnable / non-burnable | IDENTITY_REQUEST burnable ; IDENTITY_EVENT et IDENTITY_COMPLETION_EVENT non-burnables, séquencés et conservés ; checkpoint d'examen distinct de la complétion |
| Frontières WRITE/READ | Slots/checkpoints dans l'autorité des effets ; lecture préparatoire WRITE possible pour READ, aucune transaction distribuée ; matérialisations réelles et résultats READ relèvent de L4 |
| Fixtures historiques | Remplacer l'autorité Identity de fixture et revérifier ses consommateurs Command selon le futur scope L3 ; les analogues de séquences et probes READ ne certifient pas les fonctionnalités absentes |
| Disponibilité L2 | Prendre en compte R-L2-01/02 et la suspension/drain conservateur ; ces réserves ne deviennent pas des exigences de redesign automatique de L3 |

Les reports nominatifs du manifeste restent rattachés à leurs identifiants :
Identity réelle et preuves associées L3 ; projections métier READ L4 ; Invitation,
consentement externe et HTTP complets L5 ; intégration, reconstruction, performance
et SLO L6, puis confirmation sur code convergé L7 et distribution/release L8 selon
le plan directeur. Les allocations partiellement couvertes de L2 restent visibles
pour les jalons de qualification appropriés ; aucun transfert ne vaut PASS ni
suppression d'exigence. Le cadrage L3 déterminera ses dépendances effectives sans
présupposer ses choix physiques ou son plan d'exécution.

## 7. Vérification et publication de cette clôture

Impact attendu et observé : **DOCUMENTATION ONLY / NONE en production**.
Slice applicative primaire et secondaires : **NONE** ; gate Architecture :
**NOT REQUIRED / NOT RUN** ; Full Reactor : **NOT REQUIRED / NOT RUN** ;
PostgreSQL et migrations : **NOT RUN**. Aucun nouveau test Maven ou PostgreSQL,
aucun franchissement inattendu de slice, aucun changement applicatif, SQL, POM,
test ou script de production. Le G3 technique historique reste non franchi.

Contrôles documentaires : état branche/HEAD/distant/worktrees, préservation des
travaux étrangers, `git diff --check`, vérificateur existant
`python3 -B docs/v3/check_l2_manifest.py`, cohérence des statuts/réserves,
liens/ancrages, références de preuves et absence de requalification historique.
Le contrôle existant `python3 -B scripts/testing/check-v3-l2-i6.py` a été exécuté
avec succès sur le SHA initial avant modification (86 empreintes).
Après actualisation, les empreintes historiques sont comparées aux blobs du SHA
initial ; seules les trois entrées de programme volontairement actualisées
s'écartent de ces empreintes dans le checkout. Le vérificateur figé n'est pas
adapté pour leur faire représenter une nouvelle qualification I6.

Publication autorisée par le mandat : un commit documentaire explicite, push
normal sur `feat/v3-architecture`, puis contrôle d'égalité exacte de la tête
distante et du commit final. Worktree cible propre au départ ; checkout principal
et autres worktrees préservés. Aucun reset, clean, stash ni force push.

**Décision de passage : L2 officiellement clos et accepté avec R-L2-01 à R-L2-05
ouvertes ; prochaine intervention exclusivement consacrée au cadrage L3 — Identity.**
