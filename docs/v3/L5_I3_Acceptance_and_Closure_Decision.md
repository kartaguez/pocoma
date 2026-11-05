# Pocoma V3 — L5/I3 — Décision humaine d'acceptation et de clôture

Date : **5 novembre 2026**. Dépôt : **`kartaguez/pocoma`**. Branche : **`feat/v3-architecture`**.

## 1. Décision humaine et portée

Source : mandat humain explicite « Mission Codex — Pocoma V3 — L5/I3 — Clôture documentaire avec réserves explicites », transmis dans la conversation. Après implémentation, publication et audit indépendant du code, des invariants architecturaux, de la migration SQL et de la stratégie de qualification, le responsable autorise la clôture sur le périmètre de qualification ciblée et la publication documentaire par commit/push sans force. Les constats de l'audit ci-dessous sont consignés selon ce mandat ; aucune nouvelle campagne ni conclusion technique autonome d'audit n'est produite par cet acte.

**L5/I3 — CLOSED — TARGETED LOCAL PASS WITH EXPLICIT RESERVATIONS.**

La décision accepte une composition ciblée de preuves existantes. Elle ne constitue ni certification globale SPEC-10 ou Pocoma V3, ni homologation de production, ni clôture de L5, ni dispense des obligations de qualification différées. Elle ne rouvre ni H1 ni H2–H5 et ne modifie pas les exigences ratifiées sur le fond.

**Règle architecturale préservée : E1 propose, E2 accepte, Identity WRITE décide.** Aucune vérification synchrone de l'autorité métier WRITE n'est exigée à la création ou à l'admission.

## 2. Références Git et provenance

| Référence | SHA / constat |
|---|---|
| Implémentation publiée, référence humaine et HEAD initial de cette clôture | `0d415a6761476d2edba2ed0ae59e5d12a1e8c1ec` |
| HEAD distant contrôlé avant modification | `origin/feat/v3-architecture` au même SHA ; interrogation directe par `git ls-remote` |
| État initial | Branche active correcte, worktree propre, divergence locale/suivi **0/0**, aucun verrou Git observé ; autres worktrees recensés, non modifiés par cette mission |
| Départ historique des exécutions I3 | `8ff6722220049f3ce537b2f4aeeb2ffa725f4619` + worktree d'implémentation inventorié dans le manifeste |

Le SHA d'implémentation publié ne remplace pas les HEAD, dirty states, hashes et deltas de chaque contribution. Le SHA final du présent commit documentaire est donné au reçu Git de livraison, sans auto-référence dans cet acte. **Aucune qualification nouvelle n'est revendiquée au SHA de publication documentaire.** Les recontrôles Git encadrent la publication ; ils ne prétendent pas verrouiller tous les auteurs externes.

Sources d'exécution conservées : [rapport I3](L5_I3_Execution_Report.md), [index des preuves originales](../testing/evidence/L5-I3/README.md), [manifeste original](../testing/evidence/L5-I3/manifest.json), [plan historique et actualisations](L5_I3_Simplified_Headless_Link_Analysis_and_Plan.md). Les archives, manifestes et résultats producteurs restent inchangés, y compris leurs mentions de statut antérieures à cette décision.

## 3. Périmètre exact accepté

1. Le protocole Link headless simplifié.
2. La création de proposition par E1 authentifiée.
3. L'acceptation explicite par E2 authentifiée.
4. La comparaison exacte `(issuer, subject)`.
5. La persistance technique des propositions, distinctes d'IdentityRequest.
6. L'admission atomique de l'IdentityRequest après acceptation.
7. L'idempotence et l'anti-rejeu.
8. La conservation de E1 comme initiatrice et propriétaire du résultat.
9. La séparation stricte entre admission déclarative et exécution métier.
10. La revalidation autoritative uniquement dans Identity WRITE L3.
11. Le réemploi des mécanismes L2 et L3 existants.
12. La qualification locale ciblée effectivement réalisée, dans les limites ci-dessous.

La création capture l'identité authentifiée de E1, son bindingToken déclaré et la cible exacte E2. Aucun contrôle CurrentBinding READ n'est implémenté à la création ; aucun contrôle WRITE synchrone ne le remplace. Une projection obsolète est acceptée et une intention admise peut être rejetée normalement par L3. Le POST d'E2 sur la proposition précise constate le consentement ; login et GET seuls ne suffisent pas. L'IdP authentifie, sans serveur d'autorisation externe obligatoire pour ce protocole. E2 découvre exclusivement son propre binding ; l'acceptation ne lui transfère pas le résultat E1.

## 4. Preuves réellement acceptées, sans rejeu

| Campagne | Résultat accepté |
|---|---|
| FAST | **13/13 PASS** |
| PostgreSQL / HTTP | **25/25 oracles couverts par preuve composite** |
| Bruno Keycloak réel | **10/10 scénarios PASS, 70 requêtes** |
| Durée cumulée de qualification | **165,687 secondes** (valeur originale **165.686726 s**, arrondie au millième) |

Historique intégral : **91 invocations JUnit cumulées, 86 réussites, 3 failures, 2 errors, 0 skip**. Les lanceurs JUnit Bruno et les requêtes HTTP ne sont pas additionnés comme s'il s'agissait des mêmes unités.

La preuve PostgreSQL/HTTP combine **24 réussites du passage `pg-fresh`** et **un oracle HTTP corrigé, repris séparément avec succès dans `http-correction`**. Le passage de 25 tests avec un échec reste historiquement **FAIL (25/1/0/0)**. **Aucune campagne intégrale PostgreSQL 25/25 PASS n'est affirmée.** La clôture accepte cette composition et ses deltas documentés sans réécrire les verdicts.

Les huit passages originaux restent attribués dans le rapport, le manifeste et leurs archives : FAST PASS ; PG initial FAIL ; PG corrigé FAIL ; FAST final PASS ; PG reconstruit FAIL ; correction HTTP PASS ; premier lanceur Bruno FAIL ; lanceur Bruno corrigé PASS. Les diagnostics et corrections sont conservés : oracle de logs initial trop large et observation L3 en transaction READ ; index initial token/EUI trop large remplacé par références UUID et comparaison différée ; bytecode préexistant invalide et reconstruction ciblée ; verrou HTTP `ACCESS EXCLUSIVE` remplacé par `EXCLUSIVE` dans l'oracle ; correction du lanceur `Path.open(opener=...)` et de son attribution JUnit. Aucun échec initial n'est effacé, ni converti rétroactivement en PASS.

Bruno exécutés : **B301, B302, B303, B304, B305, B307, B308, B309, B310, B313** ; **10 requêtes principales + 60 helper**. Keycloak réel avec trois sessions indépendantes E1/E2/E3 ; preuves SQL indépendantes et reçus expurgés archivés. Les négatifs issuer/audience/expiration signés disposent de preuves HTTP locales ; ils ne sont pas présentés comme des jetons Keycloak réellement expirés. La validité du réemploi et l'absence de delta productif entre contributions restent celles documentées dans le manifeste ; cet acte n'est pas un nouveau run.

## 5. Réserves de l'audit indépendant

### AUD-I3-01 — Croissance des propositions Link

**Sévérité : MODÉRÉE — ACCEPTÉE AVEC RÉSERVE.** Une E1 authentifiée peut créer plusieurs propositions avec différentes clés d'idempotence. Le stockage conserve les propositions, y compris expirées, sans purge I3. Les protections de taille et de concurrence existent, mais aucun quota durable par EUI ne borne actuellement le nombre de propositions.

Décision : limite acceptée pour I3 ; aucun quota supplémentaire ni purgeur ajouté pendant cette clôture. **Obligation ultérieure de dimensionnement, de rétention et de maîtrise de croissance**, notamment **L6/Q5**, en préservant reprise, intégrité et anti-rejeu. Le stockage n'est ni dimensionné ni homologué pour une charge de production par cette acceptation.

### AUD-I3-02 — Contrôle READ indicatif non implémenté à la création

**Sévérité : MINEURE — ACCEPTÉE.** Le service capture E1 authentifiée et son bindingToken déclaré sans interroger `CurrentBinding` en READ. Cette réalisation conserve l'admission déclarative. **Aucun défaut d'autorisation métier retenu.**

Décision : aucune consultation READ ajoutée pour satisfaire une formulation historique du plan ; obsolescence éventuelle des projections acceptée ; vérification métier exclusivement au traitement Identity WRITE. Le plan est clarifié pour distinguer ses anciens oracles prévisionnels du code livré. **Aucune réouverture de H1, aucun contrôle WRITE synchrone.**

### AUD-I3-03 — Capacité HTTP d'admission partagée

**Sévérité : MINEURE — ACCEPTÉE AVEC RÉSERVE.** Les consultations Link utilisent le mécanisme de capacité du transport d'admission HTTP. La capacité initiale est commune à plusieurs opérations ; une contention est possible entre consultations Link et admissions Command/Identity.

Décision : fonctionnement accepté pour I3 ; aucun sémaphore, pool ou runtime modifié pendant la clôture. **Mesure sous charge et dimensionnement ultérieurs requis**, notamment L6 et le périmètre pertinent de Q5. Le débit et la disponibilité de production ne sont pas qualifiés.

## 6. Réserves de qualification obligatoires

### R-Q1 — Bruno incomplet

**NOT RUN en Bruno : B306 — expiration ; B311 — perte d'autorité E1 ; B312 — changement de disponibilité E2 ; B314 — concurrence et commit incertain.** Certains invariants correspondants disposent de preuves PostgreSQL ciblées. Ces preuves ne constituent pas automatiquement une équivalence de bout en bout avec Keycloak réel. Couverture et qualification à arbitrer au jalon approprié, notamment Q5.

### R-Q2 — Gates canoniques différées

**NOT RUN / DEFERRED — obligations non acquittées :** gate **WEB + COMMAND non filtrée**, **Architecture globale**, **full reactor**. Elles n'ont pas été exécutées dans I3 conformément au mandat humain ; aucune n'est lancée pendant cette clôture. L'extension du contrat technique/ACL maintient l'obligation Architecture §17 **REQUIRED — NOT RUN — DEFERRED TO Q5 BY EXPLICIT HUMAN INSTRUCTION**.

Qualification et couverture effectives à arbitrer notamment à **Q5** : full natif unique toujours dû sous couverture effective G5/Architecture selon [D6](L5_D6_Gates_Mutualization_Report.md) et la [politique normative](../testing/Reactor_Verification_Policy.md). Mutualisation sous conditions d'inventaire, variantes, provenance et hypothèses ; aucun acquittement automatique. B5 et la matrice ID-02 A…Q/C-01 complète restent dus indépendamment. Aucun déclenchement automatique par cet acte.

### R-Q3 — Qualification AuthN externe bornée

Bruno a utilisé un **Keycloak réel avec plusieurs identités distinctes**. Cette contribution ne certifie ni la compatibilité opérationnelle avec plusieurs issuers, ni les autres IdP, ni tous les scénarios de rotation des clés, ni l'ensemble des comportements et défaillances de production. Les réserves AuthN héritées ne sont pas globalement levées par ce parcours borné.

### R-Q4 — Interface de consentement

**Pocoma est headless.** La qualification backend atteste un POST d'acceptation authentifié d'E2 sur une proposition précise. Elle ne démontre ni la présentation correcte du récapitulatif par un futur frontend, ni sa lecture effective par une personne. Conception, sécurité et qualification du parcours frontend restent ultérieures, sous mandat propre.

### R-Q5 — Exploitation et disponibilité

Les mesures actuelles ne qualifient pas la production pour : **disponibilité, charge soutenue, dimensionnement des pools, rétention longue, restauration, canaux de logs et proxy externes, déploiement multi-instance**. Ces obligations restent à instruire aux étapes pertinentes L6/Q5 et de distribution/exploitation, sans promesse de SLO ou de capacité déduite des succès locaux.

### Réserves héritées

Toutes les réserves de [L1](L1_Documentary_Closure.md), [L1/F](L1_F_Closure_Report.md), [L1/F2](L1_F2_Closure_Report.md), [L2](L2_Documentary_Closure.md), [L3](L3_Identity_Closure.md), [L4](L4_Read_Closure_Decision.md), [L5/I1 R-I1-01…04](L5_I1_Acceptance_and_Closure_Decision.md) et [L5/I2 R-I2-01 et R-CLO-I2-01…04](L5_I2_Acceptance_and_Closure_Decision.md) restent conservées dans leurs périmètres. Cela inclut les FAIL/INCONCLUSIVE historiques, R-L2-01…05, G1/G2 INCONCLUSIVE, G3 NOT PASSED / NOT COMPLETED, R1–R4 Identity, lease180, qualification composite L4, disponibilité, mémoire, Spring, AuthN et coût global. **La seule réussite ciblée d'I3 ne lève aucune de ces réserves.**

## 7. Cohérence normative et suite de L5

Revue documentaire des textes publiés : [SPEC-02 §§5–6](SPEC-02_Identity_Domain.md), [SPEC-06 §§1–3, 7–8 et 12](SPEC-06_Supra_Contracts.md), [SPEC-10 ID-02, S-03, A10-D et C-01](SPEC-10_Test_Certification.md). Ils décrivent déjà le POST E2 authentifiée, l'absence de serveur d'autorisation externe obligatoire, la proposition technique distincte, la création/admission déclaratives, l'autorité exclusivement WRITE, l'idempotence/anti-rejeu, le résultat E1 et la découverte du seul binding E2. SPEC-02 §5 précise qu'I3 n'exige aucun contrôle READ de création. **Aucune incohérence normative résiduelle démontrée ; aucune SPEC modifiée.**

| Élément / obligation | Statut et jalon |
|---|---|
| I1 et I2 | **CLOSED WITH EXPLICIT RESERVATIONS**, libellés et décisions historiques préservés |
| I3 | **CLOSED — TARGETED LOCAL PASS WITH EXPLICIT RESERVATIONS** |
| L5 | **OPEN** ; clôture globale et dossier d'acceptation encore dus |
| I4 | **ELIGIBLE FOR FRAMING, NO GO** ; aucun lancement par cette mission |
| Préconditions propres d'I4 | Cadrage Invitation ; décisions D5 sur expiration/secret et contrat de résultat ; D6 sur topologie et budget de connexions ; déclaration des slices, frontières, qualification et GO humain propre avant implémentation |
| Croissance/rétention AUD-I3-01 ; capacité AUD-I3-03 ; exploitation R-Q5 | Dimensionnement, mesures et décisions aux étapes pertinentes, notamment **L6/Q5** ; aucune mise en œuvre ici |
| R-Q1/R-Q2/R-Q3 | Arbitrage de couverture et qualification **Q5** et jalons pertinents ; obligations non acquittées |
| R-Q4 | Futur parcours frontend sous cadrage, décisions et qualification propres |
| Q5 | **DÛ — NON EXÉCUTÉ** ; aucun lancement par cet acte |
| SPEC-10 globale / production | **NON CERTIFIÉE / NON HOMOLOGUÉE** |

I4 n'a pas de dépendance métier au consentement Link. Les réserves I3 restent suivies ; elles ne sont pas transformées en blocages artificiels d'I4 lorsqu'elles n'affectent pas son périmètre. Ses préconditions propres et décisions humaines restent requises. Ni I4 ni Q5 n'est engagé par la clôture.

## 8. Contrôles et publication documentaires

Déclaration avant rédaction : **DOCUMENTARY CLOSURE ONLY — NO IMPLEMENTATION — NO TEST EXECUTION**. Slices primaire/secondaires **NONE** ; DB **NONE** ; franchissement inattendu **NONE**. Aucun gate global requis pour cette publication : **Architecture NOT REQUIRED / NOT RUN**, **full reactor NOT REQUIRED / NOT RUN**, car le delta est exclusivement documentaire. Ces statuts de mission ne remplacent pas les obligations historiques **NOT RUN / DEFERRED** de R-Q2.

Périmètre documentaire : cet acte, bandeaux additifs du rapport et du plan I3, actualisations du plan global L5, du Master Plan et de l'index. Aucun code, migration SQL, test, Keycloak, POM, script ou configuration de campagne modifié ; aucun Maven, PostgreSQL, Docker, Bruno, Architecture ou full reactor lancé, aucun rejeu ni correctif technique. Corps historiques du rapport et du plan I3, archives, manifestes et résultats originaux préservés.

Contrôles de livraison : lectures `cat`/`sed`, recherches `rg`, comparaison des statuts et réserves, contrôle statique Python des liens/ancres locaux, SHAs et compteurs repris, hashes des archives et sources référencées, intégrité des corps historiques ; revue `git diff`, inventaire des fichiers et `git diff --check`. Contrôles Git : `git status --short`, `git branch --show-current`, `git rev-parse HEAD`, `git remote -v`, `git branch -vv`, `git worktree list`, `git rev-list --left-right --count HEAD...origin/feat/v3-architecture`, `git ls-remote origin refs/heads/feat/v3-architecture`, inspection de l'index puis commit documentaire dédié et `git push origin feat/v3-architecture` sans force. Le réseau Git a nécessité l'accès hors sandbox après erreur DNS. Résultats effectifs, SHA final et état local/distant donnés dans le rapport final.

Résultats documentaires avant publication : **425 liens locaux valides, dont 22 avec ancres ; 28 SHAs complets résolus dans Git ; hashes conformes des huit archives de campagne, de l'archive de préparation et des 33 sources du manifeste**. Compteurs, durée, statuts et réserves concordants ; les deux corps historiques I3 sont identiques octet pour octet. Vérification statique exécutée par `python3 -B /tmp/pocoma-i3-documentary-check.py` ; seuls six Markdown dans le delta. `git diff --check` sans erreur. Ces contrôles d'intégrité documentaire ne sont pas une exécution ni une requalification applicative.

**L5/I3 — CLOSED — TARGETED LOCAL PASS WITH EXPLICIT RESERVATIONS.**

**L5 — OPEN. I4 — ELIGIBLE FOR FRAMING, NO GO.**

**NO IMPLEMENTATION — NO TEST EXECUTION — DOCUMENTARY CLOSURE ONLY.**
