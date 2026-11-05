# Pocoma V3 — L5/Q5-A — Rapport d'amendement du plan de qualification finale

Date : **5 novembre 2026**
Dépôt : **`kartaguez/pocoma`**
Branche : **`feat/v3-architecture`**
Référence d'entrée : **`65b0fee97cf7ba2c58ca861e687e8346f1ec52c9`** (`65b0fee9`)
Statut : **FINAL AMENDED PLAN DELIVERED — Q5-A READY FOR HUMAN GO REVIEW — Q5-B NO GO**

---

## 1. Contexte et mandat d'amendement

Le plan initial de qualification finale Q5 a été préparé et publié au commit `a4a92cee`, puis complété au commit `65b0fee9` ([docs/v3/L5_Q5_Final_Qualification_Plan.md](L5_Q5_Final_Qualification_Plan.md)).
L'audit indépendant a confirmé la robustesse de l'approche composite en quatre corpus pour Bruno B5, tout en identifiant une opportunité majeure d'optimisation :

> **Constat d'optimisation d'audit :** Le full reactor Maven `verify.py CERTIFICATION` exécute déjà les trois méthodes porteuses JUnit de Bruno B2, B3 et B4 dans `runtime-monolith`.
> Les relancer systématiquement après le full produit des tests redondants sans gain probatoire.
> La décision humaine autorise Gemini à amender le plan Q5-A pour **exploiter les preuves Bruno déjà générées lors du full Maven**, et restreindre l'exécution ultérieure au **seul complément autonome B1**, suivi de la consolidation des 70 scénarios.

---

## 2. Traitement des observations initiales d'audit (AUD-Q5A-01 à 04)

1. **AUD-Q5A-01 (BLOCKER — Exécutabilité réelle de Bruno B5) :**
   - Établissement de l'incompatibilité absolue d'une instance unique intégrée (conflits dans `collection.bru`, divergence des états de base de données entre B1 lecture seule et B2/B3/B4 transactionnelles, et divergence d'authentification entre clés locales et Keycloak OIDC réel).
   - Remplacement par une stratégie composite en 4 volets étanches.
2. **AUD-Q5A-02 (MAJOR — Nuance de la couverture du full reactor) :**
   - Distinction formelle entre l'hypothèse structurelle de sélection (60 modules au POM) et les 10 contrôles dynamiques d'exécution post-run (zéro skip, 178 oracles ArchUnit, couverture Surefire).
3. **AUD-Q5A-03 (MAJOR — Traçabilité de l'audit post-L4) :**
   - Rapprochement documentaire avec [docs/v3/L4_Read_Closure_Decision.md](L4_Read_Closure_Decision.md) (§5, `AUD-FINAL-I8-01`) et [docs/v3/L5_Supra_Entry_Audit_and_Implementation_Plan.md](L5_Supra_Entry_Audit_and_Implementation_Plan.md) (§11).
   - Matrice exhaustive des 8 recommandations post-L4 justifiant leur renvoi à Q5-C / L6 / L7 sans risque pour Q5-B.
4. **AUD-Q5A-04 (MINOR — Statut non fonctionnel des budgets) :**
   - Dissociation formelle entre critères d'acceptation fonctionnelle (oracles satisfaits, 0 échec) et repères budgétaires d'organisation (durées prévisionnelles et seuils de diagnostic d'incident).

---

## 3. Mutualisation effective Bruno B5 au sein du full Maven (Amendement final)

### 3.1 Vérification statique des trois méthodes porteuses
L'analyse approfondie du code source dans `app/runtime-monolith/src/test/java/` confirme que lors de la Phase 1 (`verify.py CERTIFICATION`), Surefire exécute :

1. **Corpus B2 (24 scénarios B201–B224) :**
   - `V3AdmissionFlowPostgresTest#brunoB2PublicProtocol`
   - Démarre PostgreSQL 17 et l'API HTTP d'admission V3.
   - Lance le sous-processus `node_modules/.bin/bru run -r "02 Admission and Commands"`.
   - Écrit directement ses artefacts dans `${pocoma.verification.reports}/../bruno-b2/` (`bruno.json`, `bruno.log`, `requests.jsonl`, `receipt.json`).
   - Valide les 24 scénarios, l'exit code 0 et exécute des oracles SQL indépendants sur `v3_command`, `v3_identity_request`, `v3_admission_association`.
2. **Corpus B3 (10 scénarios B301–B313) :**
   - `V3LinkBrunoPostgresTest#realKeycloakLinkProtocol`
   - Requiert Keycloak 26.7.0 actif sur `http://localhost:8081` (précondition Phase 0).
   - Acquiert 3 sessions réelles E1, E2, E3 via `scripts/testing/l5-i3-auth.py`.
   - Lance `node_modules/.bin/bru run -r "03 Link Requests"`.
   - Écrit ses artefacts dans `${pocoma.verification.reports}/../bruno-i3/` (`bruno.json`, `requests.jsonl`, `receipt.json`, `real-jwt-claims.json`).
   - Valide les 10 scénarios, l'absence de fuite de tokens dans `bruno.log`, et les oracles SQL indépendants sur `v3_link_proposal` et `v3_identity_request`.
3. **Corpus B4 (4 scénarios B401–B404) :**
   - `V3InvitationHttpTest#brunoB4StandaloneCorpus`
   - Instancie la topologie d'invitation V10 et lance `node_modules/.bin/bru run -r "04 Invitations"`.
   - Écrit ses artefacts dans `${pocoma.verification.reports}/../bruno-b4/` (`bruno.json`, `requests.jsonl`, `receipt.json`).
   - Valide les 4 scénarios B401–B404 et le cycle complet (création confidentielle, places libres, rattachement, désaffiliation sans perte d'historique).

### 3.2 Matrice opérationnelle d'acquisition des preuves Bruno

| Corpus | Scénarios | Acquisition principale | Condition de réutilisation | Complément en Phase 2 |
|---|:---:|---|---|---|
| **B1** | 32 (B01–B32) | Fixture READ autonome (`V3ReadHttpFixture.main`) | 32 scénarios et oracles B30 validés | **Exécution nominale obligatoire** (absent du réacteur Surefire) |
| **B2** | 24 (B201–B224) | Full Maven (`V3AdmissionFlowPostgresTest`) | Méthode exécutée PASS, 24 scénarios, reçus et SQL conformes | **Aucun (réutilisation)** ; complément ciblé uniquement si preuve manquante |
| **B3** | 10 (B301–B313) | Full Maven (`V3LinkBrunoPostgresTest`) | Keycloak 8081 actif, méthode PASS, 10 scénarios, SQL conformes | **Aucun (réutilisation)** ; complément ciblé uniquement si preuve manquante |
| **B4** | 4 (B401–B404) | Full Maven (`V3InvitationHttpTest`) | Méthode exécutée PASS, 4 scénarios, reçus et oracles conformes | **Aucun (réutilisation)** ; complément ciblé uniquement si preuve manquante |

### 3.3 Procédure directement exécutable pour le complément B1
1. **Origine du classpath :** Lors de la Phase 1, `V3ReadHttpContractTest` écrit automatiquement le classpath de test complet dans `app/runtime-web-api/target/i1-classpath.txt` (`@BeforeAll static void retainClasspath()`).
2. **Commande candidate B1 :**
   ```sh
   java -Xmx512m \
     -cp $(cat app/runtime-web-api/target/i1-classpath.txt) \
     com.kartaguez.pocoma.V3ReadHttpFixture \
     $(pwd) \
     /tmp/pocoma-q5b-evidence/bruno-b1
   ```
3. **Oracles :** `V3ReadHttpFixture` démarre son PostgreSQL 17 jetable, peuple le corpus synthétique READ, gère le handshake virtuel B30 (`POCOMA_I1_PUBLISH_SIGNAL`), exécute les 32 scénarios, valide l'absence de fuite de secrets et écrit `receipt.json`.

---

## 4. Révision budgétaire sans double comptage

Grâce à la déduplication des exécutions B2, B3 et B4, le budget global de Q5-B est rationalisé :

| Poste de qualification | Estimation nominale | Point de diagnostic (Watchdog) | Seuil de vigilance |
|---|:---:|:---:|:---:|
| **Phase 1 — Full Maven (G5 + Arch + Bruno B2, B3, B4)** | 70–90 min | 100 min | 120 min |
| **Phase 2 — Complément autonome B1** | 3–5 min | 8 min | 10 min |
| **Phase 2 — Consolidation B5 & Manifeste** | 1–2 min | 3 min | 5 min |
| **TOTAL QUALIFICATION Q5-B CONSOLIDÉ** | **75–95 min** | **105 min** | **130 min** |

---

## 5. Statut terminal officiel

```
================================================================================
Q5-A — FINAL AMENDED PLAN READY FOR HUMAN GO REVIEW
================================================================================
Q5-B — NO GO PENDING HUMAN APPROVAL
L5 — OPEN — NO GLOBAL CERTIFICATION
================================================================================
```
