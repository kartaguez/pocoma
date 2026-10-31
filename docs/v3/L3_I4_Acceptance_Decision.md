# L3/I4 — Décision d'acceptation et clôture documentaire

**31 octobre 2026 — décision humaine du responsable du programme Pocoma V3**,
transmise par le mandat de clôture documentaire. Dépôt `kartaguez/pocoma`, branche
`feat/v3-architecture`.

- **L3/I4 — DOCUMENTARY CLOSED / ACCEPTED WITH EXPLICIT RESERVATIONS.**
- **L3/I5 — READY FOR FRAMING ONLY ; NEXT — cadrage/préparation autorisés,
  implémentation non autorisée.**
- **L3 — OPEN.**

Cette décision accepte la livraison I4 dans son périmètre interne. Aucun
développement correctif I4 n'est requis. Elle autorise uniquement le cadrage et
la préparation d'I5, sans certification globale, clôture L3 ou GO d'implémentation I5.

## 1. Références Git et état de départ

| Référence | SHA |
|---|---|
| Référence initiale I4 / plan publié | `def1c11eda27bd5a846c9d7563b6a2c545652e9e` |
| Code I4 testé | `145f42731a4e35488e9aaa56a7de3c3f453a9231` |
| Livraison I4 et preuves ; HEAD initial de cette clôture | `126dc10ef6ac3fc304ad098b5d9dc85a24f40f17` |

À l'ouverture, la branche locale effectivement consultée est celle indiquée
ci-dessus ; HEAD local et `origin/feat/v3-architecture` après fetch sont identiques
au SHA de livraison, divergence **0/0**. Worktree et index propres ; aucun travail
préexistant dans ce checkout ni commit concurrent distant constaté. Les quatre
autres worktrees détachés inventoriés restent intacts. Le dossier de livraison
`docs/testing/evidence/L3-I4/` est présent avec ses archives, reçus et diagnostics.

Le SHA de publication de cette décision est celui du commit qui l'introduit ;
il est fourni dans le reçu final après publication, sans auto-référence dans
ses propres octets. Le SHA testé ne désigne pas un nouveau rejeu au SHA documentaire.

## 2. Sources consultées et autorité

- [Canon V3](V3_Canon_and_Roadmap.md), [plan directeur](V3_Implementation_Master_Plan.md),
  [plan L3](L3_Identity_Inventory_and_Implementation_Plan.md),
  [plan I4, notamment A1 §16](L3_I4_Implementation_Plan.md#16-arbitrages-bloquants-avant-go).
- [Rapport I4](L3_I4_Execution_Report.md), [manifeste I4](L3_I4_Proof_Manifest.json),
  [dossier de preuves](../testing/evidence/L3-I4/README.md),
  [compteurs et commandes](../testing/evidence/L3-I4/Campaign_Summary.json),
  [reçus nominatifs](../testing/evidence/L3-I4/Variant_Receipts.json).
- Décisions [I1](L3_I1_Arbitration_and_I2_GO.md),
  [I2](L3_I2_Acceptance_Decision_2026-10-30.md),
  [I3](L3_I3_Acceptance_Decision.md) et [réserves L2](L2_Documentary_Closure.md).
- [Politique Reactor](../testing/Reactor_Verification_Policy.md), notamment
  vérification documentaire, preuves bornées, gates canoniques et réemploi.
- [SPEC-01](SPEC-01_Pot_Domain.md) §§5–6 (droits Pot),
  [SPEC-02](SPEC-02_Identity_Domain.md) §§3–7 (tokens, autorité, concurrence),
  [SPEC-03](SPEC-03_Events_Requests_Consumables.md) (demandes/publications),
  [SPEC-04](SPEC-04_Read_Projections.md) §§6–8 (frontière READ),
  [SPEC-05](SPEC-05_Consumption_Discovery.md) (transactions et Consumption),
  [SPEC-06](SPEC-06_Supra_Contracts.md) (admission/consentement),
  [SPEC-07](SPEC-07_Transverse_Requirements.md) (exigences),
  [SPEC-08](SPEC-08_Observability.md) (mesures/confidentialité),
  [SPEC-09](SPEC-09_Performance.md) (SLO),
  [SPEC-10](SPEC-10_Test_Certification.md) §§4–6 et critères de certification.

Les SPEC restent normatives. Le plan directeur porte la progression ; cette
décision enregistre l'autorité humaine. Les rapports et preuves historiques
restent inchangés et attribués à leur SHA et leur campagne.

**Limite documentaire du contre-audit I4 :** aucun fichier de rapport Gemini I4
n'a été trouvé dans le dépôt. Ses conclusions ci-dessous proviennent exclusivement
de la retranscription humaine du mandat du 31 octobre 2026 ; le rapport original
n'a pas été lu directement. Cette contribution externe n'est pas une nouvelle
source normative. Les rapports Gemini I3 présents ne sont pas réattribués à I4.

## 3. Périmètre accepté

L'acceptation est strictement bornée au runtime interne et aux configurations
réellement testées :

- Adaptateur `JdbcIdentityPotActorAuthority` et utilisation de l'autorité
  Identity réelle par les commandes Pot.
- Chaîne réelle **Registration → Binding → Command**, avec raccord des
  compositions Worker et Monolithe.
- Tokens propres à chaque EUI ; témoins négatifs stables et `INVALID_BINDING`.
- Collisions réelles et reprise selon le protocole L2.
- Sérialisation des commandes avec Registration/Link/Unlink.
- Conservation de l'atomicité transactionnelle et du suffixe fermé.
- Arbitrage A1 : maintien du contrôle technique anti-effets dans
  `JdbcPotCommandStore`, précisé au §6.

## 4. Conclusions du contre-audit indépendant, rapportées par le mandat

Le contre-audit Gemini du **31 octobre 2026** a examiné le code I4 et le dossier
de preuves publié. Verdict rapporté : **PASS WITH EXPLICIT RESERVATIONS**.
Aucun **BLOCKER**, aucun **MAJOR**, aucun **MINOR** ; trois **OBSERVATIONS**
non bloquantes, consignées au §7.

Les constats rapportés sont : R-ID-POT conforme dans le périmètre interne I4 ;
A1 correctement respecté ; architecture hexagonale et propriétaire transactionnel
conservés ; témoins Identity et collisions conformes ; verrous EUI et atomicité
corroborés par les preuves ; raccord Worker et Monolithe conforme. Le mandat
rapporte également l'intégrité de **686 rapports XML** vérifiée, **64 reçus
nominatifs** contrôlés contre les archives physiques et la correspondance du code
testé avec le code publié confirmée. Les **deux gates canoniques restent en échec**.

Gemini **n'a exécuté ni nouvelle campagne PostgreSQL ni gates Maven canoniques**.
Son audit s'appuie sur l'analyse des sources, l'inspection des tests et de leurs
oracles, les traces PostgreSQL archivées, la contre-vérification des archives et
de leur provenance, les reçus nominatifs et la vérification statique des contrats
et transactions. Il ne constitue pas un nouveau rejeu indépendant de tous les
scénarios PostgreSQL. La décision humaine d'acceptation reste distincte de cet avis.

## 5. Résultats de vérification conservés exactement

Résultats publiés du code gelé `145f42731a4e35488e9aaa56a7de3c3f453a9231`,
issus du rapport, du manifeste et des reçus ; aucune campagne relancée ici.

| Campagne | Tests | Failures | Errors | Skips | Verdict |
|---|---:|---:|---:|---:|---|
| FAST Identity | 65 | 0 | 0 | 0 | PASS borné |
| CHANGE Identity | 149 | 0 | 0 | 0 | PASS borné |
| INTEGRATION Identity | 187 | 0 | 0 | 0 | PASS borné |
| Architecture bornée | 163 | 0 | 0 | 0 | PASS borné |
| Sélection/provenance | 40 | 0 | 0 | 0 | PASS |
| COMMAND + Monolithe non filtrée | 1 789 | 1 | 0 | 1 | FAIL |
| Architecture non filtrée | 1 892 | 2 | 0 | 1 | FAIL |

L'échec COMMAND + Monolithe est attribué à `i5_r12` (`runtime condition deadline`).
`i5_r05` est PASS dans cette observation, sans levée de sa réserve historique.
L'échec Architecture non filtrée est attribué à `i5_r05` (fenêtre indépendante
disponible manquante) et `i5_r12` ; son module Architecture est **SKIPPED**, non
exécuté après échec amont. Le skip exploratoire des deux gates est
`TerminalSequencingPostgresTest.zExploratoryContentionSeparateFromCorrectness`
(`step3b.measure` absent), sans requalification en succès.

Le **PASS borné Architecture ne remplace pas la gate globale**. Les 163 tests
filtrés du module entier sont une contribution séparée au même code gelé. Les
limites de découverte JUnit non supportée restent explicites : **46** pour
COMMAND + Monolithe, **67** pour Architecture. La revue nominative de la classe
secondaire `CurrentTransactionManagerPostgresTest` corrige uniquement les totaux
initiaux 1 788→1 789 et 1 891→1 892 ; elle ne qualifie aucune forme Jupiter
supplémentaire. Les évaluations initiales, XML et reçus corrigés sont conservés.
Aucune couverture canonique complète n'en est déduite. Les diagnostics
intermédiaires FAIL demeurent historiques dans le dossier I4.

## 6. Arbitrage A1 accepté

A1 a été accepté explicitement dans le GO humain I4 et son respect est accepté
par cette décision. Pour un témoin négatif stable, « zéro accès Pot » dans le
plan L3 interdit toute lecture métier, reconstruction, décision de droits,
protection/verrou métier, mutation ou accès métier aux révisions Pot.

La seule exception est le **SELECT technique préexistant** de
`JdbcPotCommandStore.requireCompletionReady` sur `v3_pot_version`, avant
completion et suffixe : il contrôle qu'une tentative déclarée sans effet n'a
produit aucune version Pot dans la transaction (`writer_xid=pg_current_xact_id()`).
Il ne résout aucune autorisation, ne prend aucun verrou de ligne métier et ne
mute rien. La garde reste inchangée ; A1 n'autorise aucun autre accès métier Pot.
Les traces isolent ce SELECT et les preuves adverses publiées conservent le refus
de completion et le rollback total après injection d'un effet illicite.
Aucun invariant du canon ou des SPEC n'est modifié par cette clôture.

## 7. Observations non bloquantes

| Identifiant | Constat rapporté et portée |
|---|---|
| OBS-L3-I4-01 | `ExternalUserIdentity.compareKeys(key, key)` valide la clé avec un résultat de comparaison ignoré. Fonctionnement conforme ; une méthode explicitement nommée serait éventuellement plus expressive, sans correction exigée. |
| OBS-L3-I4-02 | Les variantes d'absence sans ancre et avec ancre zéro préexistante ont deux lignes distinctes de trace de contenu identique. Positions et empreintes vérifiées ; aucun défaut de preuve établi. |
| OBS-L3-I4-03 | L'exception de dépendance technique vers `domain.pot.value.UserId` est limitée au seul `JdbcIdentityPotActorAuthority`. Elle confirme le confinement attendu, sans correction. |

Ces observations ne créent aucune obligation de développement correctif I4.

## 8. Réserves maintenues

Les décisions I1/I2/I3 et les acceptations L1/L2 restent inchangées. Les réserves
suivantes demeurent ouvertes ; aucune n'est convertie en garantie nouvelle.

### L2 — R-L2-01 à R-L2-05

#### R-L2-01 — Certification temporelle

- AUD-I5-01 : garantie temporelle conditionnelle non certifiée globalement.
- R-AUD-I3-03 : réserve non levée techniquement.
- Les résultats FAIL des bancs temporels v1, v2 et final sont conservés.
- Aucun nouveau défaut de sûreté n'est démontré par ces campagnes ; cela ne prouve pas une disponibilité générale.
- La configuration de renewal à 30 secondes reste non certifiée ; aucun certificat global 90 secondes n'est acquis.
- Aucune garantie générale de renouvellement dans un délai borné sous contention arbitraire.

#### R-L2-02 — Disponibilité et reprise

- Suspension et retrait local des leases acceptés en cas d'indisponibilité.
- Drainage conservateur acceptable en présence d'une issue physique incertaine.
- Délai de reprise, expiration et réacquisition non garantis universellement.
- Aucun contournement des tokens de fencing, claims ou protections transactionnelles autorisé.

#### R-L2-03 — Certification globale

- G1/G2 restent INCONCLUSIVE ; G3 n'a pas été franchi : NOT PASSED / NOT COMPLETED.
- Gate Architecture globale et Full Reactor de clôture non exécutés sur le gel final. Les exécutions historiques sur d'autres SHAs restent attribuées à ceux-ci.
- Les cinq allocations NOT RUN et les autres allocations incomplètement couvertes gardent leur statut.
- Aucun FAIL, SKIP ou INCONCLUSIVE historique ne devient PASS.
- AUD-I5-02 conserve le correctif et le PASS local de `i5_m03` ; la gate composée corrigée finale reste NOT RUN, sans clôture technique indépendante ajoutée.

#### R-L2-04 — Périmètre fonctionnel

- Command Pot réel disponible dans le périmètre du pilote.
- IdentityRequest réel non certifié ; les fixtures d'autorité Identity ne prouvent pas l'autorité réelle.
- Projections métier READ non livrées/certifiées par L2 ; les probes READ techniques ne les certifient pas.
- Identity, Invitation et fonctionnalités HTTP complètes différées aux lots correspondants.
- Performances intégrées et SLO à qualifier ultérieurement.

#### R-L2-05 — Déploiement

- Aucun GO de production et aucun certificat de release V3.
- Les démonstrations, le profil opt-in et les outils DEV existants conservent leurs limites et leur provenance historique.
- La reprise des critères G3, de l'intégration et des gates de release sera examinée aux jalons appropriés, sans prétendre qu'ils ont déjà été satisfaits.

Les limites fonctionnelles ci-dessus décrivent les preuves L2 ; les acquis
Identity internes I3/I4 sont attribués à leurs dossiers propres, sans réécrire
l’acceptation L2 ni certifier les parcours externes.

### I3 — R1 à R4, avec leurs limites exactes

#### R1 — Composition transactionnelle

Le contre-exemple du holder JDBC prélié reste reproductible avec une composition
manuelle non certifiée utilisant `DataSourceTransactionManager`. Les compositions
Worker et Monolithe livrées utilisent `JpaTransactionManager` et refusent ce
scénario avant le callback. Toute future substitution du gestionnaire
transactionnel impose une nouvelle qualification de cette propriété avant
autorisation ; le contre-exemple n'est pas déclaré corrigé universellement.

#### R2 — Frontière de confiance PostgreSQL

Les contrôles de privilèges et d'objets indirects certifient le profil DEV fermé,
sans surveillance continue des ACL. Administrateurs PostgreSQL, propriétaires
d'objets et provisionneurs SQL restent dans la frontière de confiance. Toute
évolution des rôles, grants, vues, fonctions ou schémas exige une requalification
appropriée. Les contrôles administratifs des rôles auxiliaires restent obligatoires.

#### R3 — Confidentialité des journaux

La politique a été vérifiée sur le serveur PostgreSQL et les journaux Docker du
banc qualifié. Collecteurs externes, extensions d'audit, outils APM et
environnements de production ne sont pas certifiés par cette preuve ; leur
qualification propre reste nécessaire. Aucune absence universelle de fuite
n'est revendiquée.

#### R4 — Certification historique

- `i5_r05` : **réserve historique non levée**.
- `i5_r12` : **échec historique de la gate non filtrée**, non résolu ici.
- Gate Architecture canonique : **non acquise** lors de l'exécution composée
  ayant échoué en amont.
- Full reactor : **non exécuté**, non requis pour la correction bornée.
- Les **232 tests ciblés réussis après compilation fraîche ne remplacent pas
  une gate canonique**. FAIL, SKIP, NOT RUN et INCONCLUSIVE restent conservés.

Les résultats I4 ajoutent les deux gates **FAIL** du §5 ; ils ne lèvent ni
`i5_r05`, ni `i5_r12`, ni l'absence de gate Architecture canonique complète et
PASS. L'absence de full reactor et les limites de découverte et d'attribution
des tests restent explicites.

## 9. Limites de certification

L'acceptation couvre uniquement le runtime interne et les configurations testées.
Elle ne couvre pas l'authentification externe, le consentement et les invitations
via HTTP, les projections READ L4, les parcours Bruno/API L5, la distribution L8,
les performances et SLO de production, la certification globale SPEC-10, ni la
clôture de L3/I5. Elle ne prononce aucun GO production ou certificat de release.
Un résultat borné ou une acceptation humaine ne dispense d'aucune gate obligatoire
ultérieure. FAIL, SKIP, NOT RUN et INCONCLUSIVE restent conservés.

## 10. Décision de progression vers I5

**I5 — NEXT : cadrage et préparation autorisés, implémentation non autorisée.**
**L3/I5 — READY FOR FRAMING ONLY. L3 — OPEN.** L3/I4 est documentairement clos
avec acceptation humaine **ACCEPTED WITH EXPLICIT RESERVATIONS** ; aucun correctif
I4 ni nouveau rejeu n'est demandé par cette décision.

Le cadrage I5 devra déterminer quelles obligations sont réellement bloquantes
pour ses critères de sortie. Il ne présume ni que tous les défauts historiques
doivent être corrigés dans I5, ni qu'ils peuvent être ignorés. L'audit du futur
plan et un GO humain distinct restent nécessaires avant toute implémentation.
Cette intervention ne produit aucun plan d'implémentation I5.

## 11. Conditions de reprise autonome dans un nouveau chat I5

Point d'entrée : cette décision, puis le plan directeur. Vérifier d'abord la
branche, les HEAD local/distant, la synchronisation, l'index et les travaux
préexistants ; retrouver les trois SHA du §1 et le dossier I4 sans le réécrire.
Le mandat autorisé est exclusivement le cadrage et la préparation I5 :

1. Relire le canon, le plan L3 et les décisions I1–I4 liés au §2, ainsi que les
   réserves L2/I3 et la politique Reactor en vigueur.
2. Inventorier les obligations I5 réellement restantes dans le plan L3 §§6–9 et
   les SPEC pertinentes ; confronter les attendus historiques aux livraisons
   I1–I4 sans déduire un manque du seul nom d'un fichier prospectif.
3. Vérifier si les critères de qualification L3 sont complets : scénarios,
   variantes, compositions, démonstration reproductible, provenance, dossier
   auditable, audit et décision humaine. Identifier précisément les manques.
4. Distinguer les garanties bornées déjà démontrées des gates canoniques encore
   FAIL, avec les limites de découverte/attribution et la portée de chaque SHA.
5. Examiner les conséquences des échecs temporels historiques `i5_r05` et
   `i5_r12` pour une éventuelle clôture L3 ; qualifier leur lien avec les critères
   de sortie, sans les déclarer corrigés, dispensés ou automatiquement à corriger.
6. Déterminer les campagnes nécessaires selon la politique actuelle : slices,
   racines, PostgreSQL, gate Architecture et caractère conditionnel du full
   reactor. Distinguer réemploi admissible et preuve fraîche obligatoire, et
   examiner les tests historiques transitifs avant toute future exécution.
7. Préparer ensuite un plan I5 cohérent et proportionné, avec obligations,
   limites, critères de sortie, preuves requises et décisions bloquantes explicites.
8. Soumettre ce plan à audit **avant tout GO d'implémentation**. Aucun code I5,
   aucune correction temporelle ni campagne applicative n'est autorisé par la
   présente décision ou par la seule reprise de cadrage.

Les protections transactionnelles L2, la composition JPA qualifiée, les tokens
Identity, les frontières de confiance et A1 doivent être préservés. Un changement
d'hypothèse exige sa requalification dans le périmètre futur explicitement autorisé,
sans réouverture artificielle des travaux clos.

## 12. Contrôles documentaires et publication

| Élément de cette intervention | Déclaration |
|---|---|
| Slice primaire | NONE |
| Slice secondaire | NONE |
| Impact productif | NONE |
| Gate Architecture | NOT REQUIRED / NOT RUN |
| Full reactor | NOT REQUIRED / NOT RUN |
| PostgreSQL | NOT RUN |
| Franchissement inattendu | NONE |

La politique Reactor prévoit des contrôles documentaires pour ce changement sans
entrée exécutable modifiée. Aucun Maven, PostgreSQL, campagne Architecture ou test
applicatif n'est relancé. Les contrôles portent sur Git et les SHA, les liens et
chemins, les compteurs publiés, les statuts I1–I5, A1, les réserves et l'attribution
des conclusions d'audit ; vérification en lecture seule des manifestes et
`git diff --check`. Le diff et l'index doivent contenir exclusivement la présente
décision, le plan directeur, le plan L3, le statut/références du canon et l'index
existant `docs/README.md`. Aucun code, test, POM, migration, grant, script, recette,
archive ou preuve I4 n'est modifié.

Contrôles exécutés avant publication : `git branch --show-current`,
`git rev-parse`, `git fetch origin feat/v3-architecture`,
`git rev-list --left-right --count HEAD...origin/feat/v3-architecture`,
`git status --porcelain=v1`, `git worktree list --porcelain`, `git log`,
`git cat-file`, `git merge-base --is-ancestor`, `git diff` et `git diff --check` ;
`python3 -B /private/tmp/pocoma-i4-check-docs.py` valide les **341 liens/ancres
locaux**, les SHA, les compteurs, la copie exacte des réserves L2/I3, le périmètre
des cinq documents et la conservation des décisions I1–I3, des preuves I4 et du
corps normatif du canon. La relecture confirme les statuts, A1 et l'attribution
exclusive au mandat des conclusions Gemini I4.

`python3 -B docs/testing/evidence/L3-I4/verify_evidence.py` réussit en lecture
seule : empreintes, membres exacts des archives, provenance des producteurs et
modules, sources au SHA testé, compteurs de **686 XML** et lignes des reçus de
variantes vérifiés. Ce contrôle des octets archivés n'exécute ni Maven ni
PostgreSQL et ne requalifie aucune gate FAIL en PASS.

Publication autorisée sur `feat/v3-architecture` sans force push, après nouveau
contrôle de la tête distante et de l'index. Le reçu final fournit le commit
réellement publié, la liste exacte des fichiers, la divergence et l'état du
worktree après contrôle local/distant. Une avancée concurrente incompatible
impose un arrêt propre, sans écrasement ni succès de publication anticipé.
