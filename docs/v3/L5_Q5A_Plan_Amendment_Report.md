# Pocoma V3 — L5/Q5-A — Rapport d'amendement du plan de qualification finale

Date : **5 novembre 2026**
Dépôt : **`kartaguez/pocoma`**
Branche : **`feat/v3-architecture`**
Référence d'entrée : **`a4a92cee482058bdd8e07d551f0346a3c2c2bf16`** (`a4a92cee`)
Statut : **AMENDED PLAN DELIVERED — Q5-A READY FOR HUMAN REVIEW — Q5-B NO GO**

---

## 1. Contexte et mandat d'amendement

Le plan initial de qualification finale Q5 a été préparé et publié au commit `a4a92cee` ([docs/v3/L5_Q5_Final_Qualification_Plan.md](L5_Q5_Final_Qualification_Plan.md)).
Un audit indépendant a validé l'architecture générale du plan et la pertinence de la mutualisation D6 des gates Maven, tout en émettant quatre observations impératives avant approbation humaine de Q5-B :

1. **AUD-Q5A-01 (BLOCKER) :** La procédure Bruno B5 supposait un banc unique capable d'exécuter directement les 70 scénarios en une seule passe, sans démontrer la compatibilité entre les quatre corpus B1–B4.
2. **AUD-Q5A-02 (MAJOR) :** La couverture du full reactor était formulée de manière trop catégorique avant exécution effective.
3. **AUD-Q5A-03 (MAJOR) :** Les huit recommandations issues de l'audit qualité post-L4 n'étaient pas suffisamment rattachées au rapport original et aux textes du dépôt.
4. **AUD-Q5A-04 (MINOR) :** Des durées prévisionnelles étaient qualifiées à tort de critères de succès fonctionnel.

Le responsable du programme a formellement autorisé Gemini à amender le plan documentaire et à publier cette version révisée dans le dépôt.

---

## 2. Traitement d'AUD-Q5A-01 (BLOCKER — Exécutabilité réelle de Bruno B5)

### 2.1 Constat technique et incompatibilités démontrées
L'analyse statique détaillée des fichiers de test et des scripts de collection révèle qu'une exécution globale directe `node_modules/.bin/bru run -r` sur un monolithe unique est **techniquement impossible** sans altération des oracles :
- **Conflits de pre-request (`scripts/bruno-v3/collection.bru`) :** Le bloc `script:pre-request` applique des branches mutuellement exclusives. Si `POCOMA_I4_BASE_URL` ou `POCOMA_I2_BASE_URL` est présent, les secrets `POCOMA_I1_*` ne sont pas chargés. Inversement, en l'absence de ces variables, le script exige obligatoirement 8 variables d'environnement `POCOMA_I1_*` sous peine d'échec immédiat.
- **Incompatibilités d'état de base de données :**
  - **B1 (32 scénarios) :** Nécessite une base pré-injectée en lecture seule (`read_pot` avec UUIDs spécifiques `...0300`, `...0301`, `...0302`, `...0304`, soldes, 205 lignes de navigation) et un mécanisme de signal en tâche de fond pour B30 (`POCOMA_I1_PUBLISH_SIGNAL`).
  - **B2 (24 scénarios) :** Nécessite une base initialement vide de commandes, un moteur d'écriture et de consommation actif, et un thread virtuel de synchronisation pour le lien interne contrôlé (`controlled-internal-link` / B220).
  - **B3 (10 scénarios) :** Nécessite des sessions délivrées par un **Keycloak OIDC réel** (port 8081) pour trois utilisateurs distincts (`bruno`, `link-e2`, `link-e3`) et une base propre pour les propositions de liaison.
  - **B4 (4 scénarios) :** Nécessite les tables d'invitation V10 et 3 porteurs locaux pour dérouler le cycle de vie de l'invitation et la désaffiliation.
- **Autorités d'authentification divergentes :** B1, B2 et B4 reposent sur des jetons signés par des paires de clés RSA éphémères locales, tandis que B3 requiert impérativement les jetons émis et signés par Keycloak 26.7.0.

### 2.2 Arbitrage : Option B (Campagne B5 composite orchestrée)
- **Option A (Instance unique intégrée) :** Rejetée. Elle exigerait une refonte lourde de `collection.bru`, la fusion artificielle de corpus incompatibles et la réécriture des tests, ce qui est contraire à la stabilité de qualification.
- **Option B (Campagne composite orchestrée) :** **RETENUE**. B5 est formalisé comme une campagne séquentielle en 4 sous-campagnes étanches utilisant leurs orchestrateurs et fixtures réels :
  1. **B5-1 (READ) :** 32 scénarios via `V3ReadHttpFixture` (porteur Java dédié).
  2. **B5-2 (Admission & Commandes) :** 24 scénarios via `V3AdmissionFlowPostgresTest#brunoB2PublicProtocol`.
  3. **B5-3 (Link Keycloak) :** 10 scénarios via `V3LinkBrunoPostgresTest#realKeycloakLinkProtocol` (sous Keycloak 8081 actif).
  4. **B5-4 (Invitations) :** 4 scénarios via `V3InvitationHttpTest#brunoB4StandaloneCorpus`.

Les 70 scénarios sont ainsi tous qualifiés sur leurs environnements réels, et leurs résultats consolidés dans un rapport de preuves unique B5.

---

## 3. Traitement d'AUD-Q5A-02 (MAJOR — Nuance de la couverture du full reactor)

### 3.1 Rectification des formulations
Toutes les affirmations péremptoires de « couverture intégrale acquise d'avance » ont été éliminées du plan. La présence des 60 modules dans `app/pom.xml` constitue une garantie structurelle de sélection dans le reactor, mais la couverture effective dépend de l'exécution dynamique.

### 3.2 Contrôles post-run formalisés
Le plan intègre désormais la liste explicite des 10 vérifications à effectuer pendant et après l'exécution de `verify.py CERTIFICATION` :
1. Résolution dynamique du graphe effectif et ordre topologique.
2. Vérification que les 60 modules ont atteint le statut `SUCCESS`.
3. Contrôle de l'inventaire nominatif JUnit des méthodes découvertes vs exécutées.
4. Absence totale de skips, exclusions POM non documentées ou annotations `@Disabled`.
5. Analyse des rapports Surefire dans chaque sous-répertoire de module.
6. Confirmation que `architecture-tests` a été exécuté intégralement (178 oracles) sans court-circuit.
7. Validation de la provenance (`pocoma.verification.id`, module, producer).
8. Réconciliation des obligations différées issues de I1, I2, I3 et I4.
9. Preuve de non-régression sur les modules historiques hérités (L1–L4).
10. Séparation claire entre preuves acquises par Maven et preuves extérieures (Bruno, IdP réel).

---

## 4. Traitement d'AUD-Q5A-03 (MAJOR — Traçabilité de l'audit post-L4)

### 4.1 Provenance documentaire clarifiée
Le plan clarifie formellement la provenance des recommandations :
- L'audit final indépendant de L4 a été consigné dans l'acte de clôture [docs/v3/L4_Read_Closure_Decision.md](L4_Read_Closure_Decision.md) (§5) et dans [docs/v3/L4_I8_Execution_Report.md](L4_I8_Execution_Report.md). Le seul identifiant normatif formellement enregistré dans la clôture L4 est **`AUD-FINAL-I8-01`** (prévalidation pure avant quota).
- Les sept autres recommandations correspondent aux constats d'audit transmis par le mandat de cadrage L5 et instruits dans [docs/v3/L5_Supra_Entry_Audit_and_Implementation_Plan.md](L5_Supra_Entry_Audit_and_Implementation_Plan.md) (§11).
- Pour éviter toute ambiguïté, le plan distingue formellement les constats originaux des identifiants de suivi de qualification (`FIND-L4-01`, etc.).

### 4.2 Matrice détaillée des 8 recommandations
Chaque point fait l'objet d'une analyse complète (constat initial, recommandation, situation post-L5, risque Q5-B, décision de renvoi à Q5-C, et SHA de correction si applicable). Aucune recommandation ne bloque l'exécution de Q5-B.

---

## 5. Traitement d'AUD-Q5A-04 (MINOR — Statut non fonctionnel des budgets)

Le plan rectifie expressément la nature des indicateurs temporels :
- Les durées prévisionnelles (**70–90 min** pour le full natif, **15–25 min** pour Bruno) sont des **estimations d'organisation et des repères de dimensionnement**.
- Les seuils de **100 min** (full natif) et **40 min** (Bruno) constituent des **points de décision et de diagnostic d'incident**, et non des plafonds normatifs coupant une campagne saine.
- **La durée n'est en aucun cas un critère de succès ou d'échec fonctionnel.** Un dépassement temporel dû à la charge machine, à des accès disque lents ou à des oracles intégrant un délai de temporisation physique (ex: `V3InvitationTopologyPostgresTest` durant ~207 s) n'invalide pas une preuve dès lors que le test réussit.
- Seuls des dysfonctionnements techniques caractérisés (deadlock, absence d'émission de log pendant >5 min, crash conteneur, boucle infinie) justifient une interruption anticipée.

---

## 6. Synthèse des modifications apportées au dépôt

1. **`docs/v3/L5_Q5_Final_Qualification_Plan.md` :**
   - Refonte de la section Bruno B5 (§6) adoptant la stratégie composite en 4 volets et détaillant la matrice des bancs.
   - Amendement de la section D6 (§4) introduisant les 10 contrôles dynamiques de couverture.
   - Refonte de la section post-L4 (§8) avec traçabilité exacte des sources documentaires et distinctions d'identifiants.
   - Correction de la politique budgétaire (§7) dissociant critères fonctionnels et seuils de suivi.
   - Mise à jour du plan opérationnel (§9) aligné sur l'orchestration B5.
2. **`docs/v3/L5_Q5A_Plan_Amendment_Report.md` :** Création du présent rapport de traçabilité d'amendement.
3. **`docs/README.md` :** Indexation du rapport d'amendement et mise à jour de la documentation.

---

## 7. Statut terminal de la mission

```
================================================================================
Q5-A — AMENDED PLAN READY FOR HUMAN REVIEW
================================================================================
Q5-B — NO GO PENDING HUMAN APPROVAL
L5 — OPEN — NO GLOBAL CERTIFICATION
================================================================================
```
