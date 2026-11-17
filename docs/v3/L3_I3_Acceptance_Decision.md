# L3/I3 — Décision d'acceptation après audit contradictoire

Décision humaine consignée le **31 octobre 2026**, branche `feat/v3-architecture`.

**L3/I3 — ACCEPTED WITH EXPLICIT RESERVATIONS.**

## 1. Objet et périmètre

Le responsable du programme accepte l'implémentation IdentityRequest I3 et ses
corrections de sécurité pour poursuivre Pocoma V3. Les quatre findings sont clos
dans leur périmètre qualifié ; aucune nouvelle correction I3 n'est requise à ce
stade. Cette acceptation de programme ne prononce ni PASS canonique, ni
certification globale, ni clôture de L3. Les exigences des SPEC restent inchangées.

## 2. Références Git

| Référence | SHA |
|---|---|
| Livraison I3 initiale soumise à l'audit | `2b3567b1f77051c399772ee7ac787f7dd61ba407` |
| Corrections de sécurité I3, code gelé | `c1172bf4a758a9077415d827ca88b359b6360ebb` |
| Rapport de résolution et preuves ; HEAD de départ de cette clôture et du ré-audit | `08febf1c6ec56e4ee052d14b7b2e18d4356437e3` |

Le SHA de publication de cette décision est celui du commit documentaire qui
l'introduit (`git log -1 --format=%H -- docs/v3/L3_I3_Acceptance_Decision.md`) ;
aucun SHA autoréférent ni succès anticipé du push n'est inscrit ici.

## 3. Historique et attribution des preuves

1. L'[implémentation initiale](L3_I3_Execution_Report.md) démontre le jalon
   R-ID interne (Registration/Link/Unlink via le moteur L2, Worker et Monolithe),
   avec une gate canonique en échec et des réserves explicites.
2. L'[audit contradictoire initial](L3_I3_Contradictory_Audit_Report.md), attribué
   à Gemini par le responsable, conclut **FAIL — CORRECTIONS REQUIRED** sur deux
   MAJOR et deux MINOR. Ce verdict historique et ses preuves restent inchangés.
3. Codex livre les [corrections et preuves avant/après](L3_I3_Findings_Resolution_Report.md)
   au code gelé ci-dessus. Les résultats ciblés ne remplacent pas la gate non filtrée.
4. Gemini effectue une [contre-vérification indépendante sur PostgreSQL 17.11](L3_I3_Independent_Reaudit_Report.md)
   au SHA documentaire `08febf1c…` : **CONTRADICTORY RE-AUDIT PASS**, borné aux
   quatre findings et au profil DEV fermé. Les deux MAJOR sont neutralisés dans
   ce périmètre ; les deux MINOR sont traités ou circonscrits sans nouveau
   développement requis. Le rapport reçu est publié avec sa provenance ; ses
   scripts propres, journaux bruts et XML indépendants n'ont pas été fournis.
5. Le responsable prononce l'acceptation avec réserves de la présente décision.
   Les observations expérimentales restent attribuées à Codex ou Gemini selon
   leur rapport ; aucune n'a été réexécutée pour cette clôture documentaire.

## 4. Décision humaine et findings

Implémentation I3 et corrections de sécurité acceptées ; findings contradictoires
clôturés dans leur périmètre qualifié. La matrice suivante fixe les libellés de
décision, y compris les limitations explicites des deux MINOR.

| Finding | Gravité initiale | Décision | Portée et réserve |
|---|---|---|---|
| AUD-L3-I3-CT-002 — Accès indirect aux tokens | MAJOR | **CLOSED** | Profil DEV fermé, contrôles des objets indirects et refus de démarrage ; R2 |
| AUD-L3-I3-CT-003 — TRUNCATE des rôles auxiliaires | MAJOR | **CLOSED** | Certification administrative des rôles, révocation explicite des excès, refus PostgreSQL sous profil légitime ; R2 |
| AUD-L3-I3-CT-001 — Holder JDBC prélié | MINOR | **CLOSED WITH SCOPE LIMITATION** | Compositions Worker/Monolithe JPA livrées ; composition JDBC manuelle non certifiée ; R1 |
| AUD-L3-I3-CT-004 — Journalisation PostgreSQL | MINOR | **CLOSED WITH SCOPE LIMITATION** | Serveur PostgreSQL et journaux Docker du banc ; R3 |

Le refus d'un préflight ou d'un script ne révoque pas un grant déjà présent.
L'administrateur retire l'excès avant requalification ; aucune surveillance
continue ni neutralisation automatique d'ACL arbitraires n'est certifiée.

## 5. État exact des tests et gates

Ces résultats sont historiques, attribués aux rapports cités. Le mot PASS du
ré-audit ne requalifie aucune gate canonique.

| Contribution / gate | Résultat conservé | Limite |
|---|---|---|
| Livraison I3 initiale, gate finale non filtrée | **FAIL** : 1 859 tests, 1 failure (`i5_r12`), 0 error, 1 skip historique ; 37 cas I3 PASS | Architecture **SKIPPED** après échec amont ; [rapport initial](L3_I3_Execution_Report.md) |
| Résolution, COMMAND + Monolithe + Architecture non filtré | **FAIL** : 1 867 tests, 1 failure (`i5_r12`), 0 error, 1 skip historique | Architecture **SKIPPED** ; sorties compilées préexistantes, qualification source/bytecode conservée |
| Résolution, Architecture ciblée sur ces sorties | **FAIL** : 162 contrôles, 2 failures, 0 error/skip | Écart source/bytecode documenté, échecs conservés |
| Résolution, compilation fraîche au même code gelé | **232 tests ciblés PASS**, 0 failure/error/skip (70 Identity/DEV + 162 Architecture) | Contribution bornée, sans relance des tests longs L2 ; aucune substitution à la gate non filtrée |
| Ré-audit Gemini, suites des deux racines | Worker : 8 tests PASS ; Monolithe : 5 tests PASS ; 0 failure/error/skip annoncés | Exécutions attribuées à Gemini, rapport transmis ; pas une gate canonique |
| Gate Architecture canonique requise lors des campagnes I3 | **NON ACQUISE** | Exécution composée arrêtée en amont ; contrôles ciblés distincts |
| Full reactor I3 / correction bornée | **NOT RUN / NOT REQUIRED** pour cette correction bornée | Ni Wave majeure, ni intégration main, ni preuve globale déduite |
| Présente clôture documentaire | **Aucun test ni gate exécuté** | Slices Maven NONE ; contrôles documentaires uniquement |

Le skip historique de la gate est
`TerminalSequencingPostgresTest.zExploratoryContentionSeparateFromCorrectness`
(`step3b.measure` absent), pas un skip Identity. Les reçus complets, commandes et
limites de compilation sont dans le [rapport de résolution](L3_I3_Findings_Resolution_Report.md)
et son [dossier de preuves](../testing/evidence/L3-I3-resolution/README.md).

## 6. Réserves conservées

### R1 — Composition transactionnelle

Le contre-exemple du holder JDBC prélié reste reproductible avec une composition
manuelle non certifiée utilisant `DataSourceTransactionManager`. Les compositions
Worker et Monolithe livrées utilisent `JpaTransactionManager` et refusent ce
scénario avant le callback. Toute future substitution du gestionnaire
transactionnel impose une nouvelle qualification de cette propriété avant
autorisation ; le contre-exemple n'est pas déclaré corrigé universellement.

### R2 — Frontière de confiance PostgreSQL

Les contrôles de privilèges et d'objets indirects certifient le profil DEV fermé,
sans surveillance continue des ACL. Administrateurs PostgreSQL, propriétaires
d'objets et provisionneurs SQL restent dans la frontière de confiance. Toute
évolution des rôles, grants, vues, fonctions ou schémas exige une requalification
appropriée. Les contrôles administratifs des rôles auxiliaires restent obligatoires.

### R3 — Confidentialité des journaux

La politique a été vérifiée sur le serveur PostgreSQL et les journaux Docker du
banc qualifié. Collecteurs externes, extensions d'audit, outils APM et
environnements de production ne sont pas certifiés par cette preuve ; leur
qualification propre reste nécessaire. Aucune absence universelle de fuite
n'est revendiquée.

### R4 — Certification historique

- `i5_r05` : **réserve historique non levée**.
- `i5_r12` : **échec historique de la gate non filtrée**, non résolu ici.
- Gate Architecture canonique : **non acquise** lors de l'exécution composée
  ayant échoué en amont.
- Full reactor : **non exécuté**, non requis pour la correction bornée.
- Les **232 tests ciblés réussis après compilation fraîche ne remplacent pas
  une gate canonique**. FAIL, SKIP, NOT RUN et INCONCLUSIVE restent conservés.

Les acceptations et réserves historiques I1/I2 et L1/L2 restent inchangées,
notamment les [réserves L2 et statuts G1/G2/G3](L2_Documentary_Closure.md).

## 7. Conditions de réouverture d'I3

Une preuve nouvelle d'accès indu aux tokens, de mutation destructive Identity,
de protection transactionnelle invalide ou de divulgation sur le canal qualifié
justifie un examen borné du finding concerné. Un changement d'hypothèse R1–R3
impose d'abord sa requalification ; si la propriété acquise n'est plus démontrée,
la décision concernée doit être réexaminée explicitement. Les autres findings ne
sont pas rouverts automatiquement. La réserve historique `i5_r12` n'autorise
aucune correction ni nouvelle campagne dans cette intervention.

## 8. Progression vers I4

**GO pour le cadrage et la préparation d'I4.** Cette décision de progression
ne certifie pas globalement L3 et ne constitue pas un GO d'implémentation I4.
I4 est la prochaine étape : autorité Identity réelle consommée par Pot selon
le [plan L3 §6](L3_Identity_Inventory_and_Implementation_Plan.md#6-développement-ordonné-et-fichiers-exacts).
I5 et les étapes suivantes restent à réaliser selon le plan en vigueur et leurs
décisions propres ; aucune portée de lot n'est modifiée.

I4 doit préserver les garanties I1–I3 et les frontières transactionnelles L2 :

- Ne pas réintroduire de composition transactionnelle non certifiée.
- Ne pas affaiblir les protections des tokens Identity.
- Ne pas modifier implicitement les frontières de confiance.
- Ne pas masquer les réserves historiques ni considérer leurs gates comme PASS.

Le cadrage I4 devra déclarer son périmètre de vérification avant code selon la
[politique Reactor](../testing/Reactor_Verification_Policy.md). La présente
intervention s'arrête après publication documentaire, sans commencer I4.

## 9. Navigation et contrôle de la publication

- [Plan directeur V3](V3_Implementation_Master_Plan.md), [canon et roadmap](V3_Canon_and_Roadmap.md),
  [plan L3](L3_Identity_Inventory_and_Implementation_Plan.md).
- [Livraison I3](L3_I3_Execution_Report.md), [preuves initiales](../testing/evidence/L3-I3/README.md),
  [runbook DEV](L3_I3_Identity_Development_Runbook.md).
- [Audit contradictoire initial](L3_I3_Contradictory_Audit_Report.md) et
  [preuves originales](../testing/evidence/L3-I3-CT/README.md).
- [Résolution Codex](L3_I3_Findings_Resolution_Report.md),
  [preuves et manifestes](../testing/evidence/L3-I3-resolution/README.md),
  [contre-vérification Gemini transmise](L3_I3_Independent_Reaudit_Report.md).
- [SPEC-10](SPEC-10_Test_Certification.md) : protocoles et critères normatifs
  inchangés ; l'acceptation ne dispense pas des gates applicables ultérieures.

Le manifeste CT conserve notamment l'empreinte de `docs/README.md` lors de sa
publication `00cdc16253ef9900224c7c6be90a0a814d2baf73`. Cet index évolue avec le
programme : vérifier son empreinte sur `git show 00cdc162:docs/README.md`, sans
réécrire le manifeste historique pour le faire correspondre à l'index courant.
Les rapports et archives de preuve gardent leurs octets d'origine.

Périmètre déclaré : **documentation seule**, impact productif interdit,
slice primaire/secondaires **NONE**, DB **NONE**, gate globale et full reactor
**NOT REQUIRED / NOT RUN**. Contrôles : Git/SHAs, liens et cohérence des statuts,
empreintes des preuves existantes, `git diff --check`, diff et liste des fichiers.
Aucun franchissement de slice observé. Les contrôles documentaires exécutés
(`git status`, `git branch --show-current`, `git rev-parse HEAD`, `git ls-remote`,
`git cat-file`, `git merge-base --is-ancestor`, `git diff`, `git diff --check`
et vérifications en lecture seule par `python3 -B -`) confirment trois SHA de
référence valides, 331 liens/ancres locaux résolus, 44 entrées de manifestes
vérifiées en longueur/SHA-256 et la fidélité du corps Gemini reçu. Le diff porte
sur six fichiers Markdown exclusivement. Les rapports et manifestes historiques
restent intacts. Le reçu final fournit le SHA effectivement publié et la
synchronisation distante, sans succès anticipé du push dans cette décision.
