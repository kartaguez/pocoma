# Pocoma V3 — L5/I4 — Décision humaine d'acceptation et de clôture

Date : **5 novembre 2026**. Dépôt : **`kartaguez/pocoma`**. Branche : **`feat/v3-architecture`**.

---

## 1. Décision humaine et portée

Source : mandat humain explicite « Mission Gemini — Pocoma V3 — L5/I4 — Clôture documentaire définitive avec réserves explicites ».

Après implémentation de l'incrément, qualification locale, audits indépendants du code et de l'architecture, corrections ciblées des observations (AUD-I4-01, AUD-I4-02, AUD-I4-03), contrôle complémentaire SQL et finalisation de l'archivage probatoire, le responsable humain approuve formellement la clôture de l'incrément L5/I4 :

```
L5/I4 — CLOSED — LOCAL BOUNDED PASS WITH EXPLICIT RESERVATIONS
```

**Portée de la décision :**
- La clôture est strictement bornée aux fonctionnalités, contrats et propriétés effectivement démontrés dans le périmètre local I4.
- Elle ne vaut pas clôture du lot L5 : **`L5 — OPEN — Q5/G5 STILL REQUIRED`**.
- Elle ne constitue ni homologation de production, ni certification globale SPEC-10 ou Pocoma V3.
- Elle n'emporte aucune dispense des obligations de qualification globale différées (Architecture globale, full reactor natif unique G5, Bruno B5).

---

## 2. Références Git et traçabilité de provenance

| Jalon | SHA | Description |
|---|---|---|
| **Baseline antérieure (clôture L5/I3)** | `ca21034f7873ed5c16f296665ae2aaf63d4090d7` | Point de départ avant introduction du code I4 |
| **Introduction du code I4** | `7a284405093c0c7593f8ca4f9ba70b5e605e1df3` | Migrations V10, contrôleurs, moteurs et tests initiaux |
| **Commit initial audité** | `d722e9d5858c9e4eba5aa1883cf72988b463d780` | SHA soumis au premier audit indépendant |
| **Corrections ciblées post-audit** | `2b1271d0ca3db6c6e9dfddd74f2b877cf4631dd2` | Résolution technique AUD-I4-01 et AUD-I4-02 |
| **Rapport post-audit initial** | `a087dd8b3f07e2c6116533f98b597f78080da5eb` | Publication du compte rendu d'audit |
| **Finalisation documentaire et preuves** | `17129919f2a71d7943d0f04c6001157922d9b23b` | Archivage des preuves dans `evidence/L5-I4` et alignement SQL |
| **Référence humaine de clôture** | `17129919f2a71d7943d0f04c6001157922d9b23b` | Base exacte de la décision d'acceptation |

**Règle de traçabilité :** Aucune qualification nouvelle n'est revendiquée au SHA de publication documentaire du présent acte. Les preuves conservées conservent leur attribution d'origine sans fabrication rétroactive.

---

## 3. Périmètre fonctionnel et technique accepté

Sont acceptés dans leur portée locale bornée :

### 1. Protocole d'invitation
- **Création asynchrone :** Réservée au créateur du Pot via `CreateInvitation`, vérifiée en amont par `AdmitCommandV3` et exécutée par `ExecutePotCommand`.
- **Persistance atomique et immuabilité :** Enregistrement dans `v3_invitation` et `v3_invitation_event`, triggers d'immutabilité interdisant `UPDATE`, `DELETE` et `TRUNCATE`.
- **Séquencement des événements :** Publication ordonnée `INVITATION_EVENT` (famille `'INVITATION'`) et `COMMAND_COMPLETION_EVENT` (famille `'COMMAND_COMPLETION'`) sans publication d'événement Pot.
- **Absence de fausse mutation de Pot :** `resultingPotVersion` nul, aucune ligne dans `v3_pot_version`, résultat terminal `INVITATION_CREATED` strictement borné à l'opération.
- **Chiffrement et protection du lien :** Chiffrement AES-GCM avec trousseau configurable, identifiant de clé préfixé, empreinte cryptographique SHA-256 (`token_hash`) pour lookup indexé.
- **Restitution confidentielle :** Endpoint `GET /api/v3/invitations/by-creation-request/{requestId}` réservé à l'EUI créatrice authentifiée ; masquage uniforme (404) pour les tiers ou en cas d'absence.
- **Consultation des places disponibles :** Endpoint `POST /api/v3/invitations/available-shareholders/query` authentifié par l'invité avec son `bindingToken` et le token secret dans le corps JSON ; restitution restreinte aux identifiants et libellés des Shareholders libres sans fuite de solde ou d'historique.
- **Auto-rattachement :** Commande `AttachShareholderViaInvitation` réutilisant le token avant expiration ; réévaluation autoritative complète dans le moteur WRITE (existence, non-expiration, disponibilité de la place).
- **Concurrence et idempotence :** Rejet déterministe d'une seconde affiliation sur la même place (`ALREADY_ATTACHED`), concurrence sérialisée par les verrous de version Pot.
- **Matérialisation et projection READ :** Projection dans la table `read_current_invitation` par `MaterializeInvitation`, contrôle monotone par trigger `read_invitation_monotone`, découplage total des projections Pot existantes.
- **Topologie cible :** Déploiement et qualification physique locale bornée sur topologie 7 participants READ (3 Monolithe + 4 PROJECTION) sous pool maximal 65 connexions.

### 2. Désaffiliation volontaire
- **Réutilisation de commande existante :** Réemploi direct de `UpdateShareholderDetails` avec `attachment=DETACH` et `userId=null`.
- **Autorisation stricte :** Autorisée pour l'utilisateur affilié lui-même ou par le créateur du Pot.
- **Préservation de l'intégrité financière :** Le Shareholder redevient libre (`isFree=true`) sans suppression physique ; conservation intégrale de l'historique des Expenses, des parts et des soldes.
- **Départ du dernier affilié :** Autorisé sans suppression du Pot ni altération du cycle de vie.
- **Indépendance des identités :** Maintien des affiliations sur les autres Pots, préservation des EUI et des Binding Tokens Identity sans nouvelle commande Identity.

### 3. Surface HTTP
- **Routes contractuelles ratifiées :**
  - `GET /api/v3/invitations/by-creation-request/{requestId}`
  - `POST /api/v3/invitations/available-shareholders/query`
- **Sécurité et transport :** En-tête `Cache-Control: no-store`, masquage d'existence (404), absence d'alias rétrocompatible, conformité stricte aux codes d'erreur 400, 401, 404, 503.
- **Parcours Bruno :** Collection B401–B404 validée de bout en bout sur environnement d'intégration.

---

## 4. Preuves locales acceptées, sans rejeu

Les preuves retenues sont issues des campagnes documentées dans [`docs/v3/L5_I4_Implementation_Report.md`](L5_I4_Implementation_Report.md), [`docs/v3/L5_I4_Post_Audit_Correction_Report.md`](L5_I4_Post_Audit_Correction_Report.md) et archivées dans [`docs/testing/evidence/L5-I4/`](../testing/evidence/L5-I4/README.md) :

### 1. Preuves JUnit avec XML archivé (17 méthodes / 17 PASS)
- **`InvitationAttachmentTest` (3 tests) :** `domain-pot` pur domaine, 0.039s. SHA-256 : `d1ca85a4a5a131230b1eea9843e2922481ef065e8f9a23a1191a41f6866b67b1`.
- **`V3AdmissionFlowPostgresTest` (1 test) :** Désaffiliation volontaire, 19.187s. SHA-256 : `72e606323f28e230623493a778c8692477727f8b16df41c49780750a09c609d6`.
- **`V3InvitationReadPostgresTest` (3 tests) :** Projections et chiffrement READ, 28.327s. SHA-256 : `8777e4687f9ea64d28f29d2442eefaaef6e37808d864477737d513946d7ae29e`.
- **`V3InvitationTopologyPostgresTest` (2 tests) :** Topologie 7 READ et renouvellement lease 180s (T4-L), 207.42s. SHA-256 : `cbad0f5bd869ad13508d81b0a991a9814159f970cd48a51717ad2a8ee5fd7e74`.
- **`V3InvitationStandaloneWebPostgresTest` (2 tests) :** Tranche Web autonome, 7.116s. SHA-256 : `5b518514bcf9a1d06057de94adf99e941dd5b2bdbd7afcea785964fbd3bd0c3e`. Run post-audit attribué à `2b1271d0`.
- **`V3InvitationWritePostgresTest#expirationAfterPublicationCounterWait` (1 test) :** Test d'expiration fiabilisé, 17.834s. SHA-256 : `c83c06fc2d0eae4e3fa7e920df51634ca50884625b10a10c49d3d40e384f1f24`. Run post-audit attribué à `2b1271d0`.
- **`V3InvitationHttpTest` (5 tests) :** Routes HTTP, confidentialité, concurrence et intégration Bruno, 67.68s. SHA-256 : `6c3cc78ea9a6d1328529c9aa93f003b10e57401e38f1111b91b2b6ed51a65710`. Run post-audit attribué à `2b1271d0`.

### 2. Preuves WRITE historiques rapportées sans XML indépendant (5 méthodes)
- Méthodes 1 à 5 de `V3InvitationWritePostgresTest` (`atomicCreationAndIdempotence`, `creatorOnlyAndExpiredCreation`, `twoGuestsOnePlaceAndReusableToken`, `oneGuestCannotTakeTwoPlaces`, `rollbackAfterInvitationPositionKeepsDensePublication`) :
  - Exécutées avec succès lors de la campagne initiale à 14:06 CET (durée cumulée de classe ~74s).
  - Reçus XML écrasés sur disque lors de la re-qualification ciblée de la seule méthode 6 à 14:31 CET.
  - Statut accepté : PASS historique rapporté, sans fausse preuve d'inspection indépendante (qualifié `EVIDENCE NOT AVAILABLE FOR INDEPENDENT INSPECTION`).

### 3. Preuves d'intégration Bruno B4 (4 scénarios / 4 PASS)
- Scénarios B401, B402, B403, B404 validés via CLI Bruno local v4.3.0 en 11738 ms.
- Reçus et traces expurgés archivés dans `docs/testing/evidence/L5-I4/bruno-b4/`.

---

## 5. Synthèse des audits indépendants

| Constat d'audit | Sévérité | Verdict | Résolution acceptée |
|---|---|:---:|---|
| **AUD-I4-01** — Routes HTTP | MAJOR | **RESOLVED** | Rétablissement strict de `/invitations/by-creation-request/{requestId}` et `/invitations/available-shareholders/query`. Aucun alias rétrocompatible. |
| **AUD-I4-02** — Test expiration | MINOR | **RESOLVED WITH MINOR RESERVATION** | Suppression du délai de 400 ms pré-soumission ; soumission immédiate avec marge de 4 s, synchronisation sur verrou `v3_event_counter` et trigger différé. Réserve de sensibilité résiduelle sous charge extrême consignée. |
| **AUD-I4-03** — Provenance | MODERATE | **RESOLVED** | Inventaire rectifié à 22 méthodes JUnit + 4 Bruno, matrice T4-A à T4-L alignée sur les noms réels, archivage exhaustif dans `docs/testing/evidence/L5-I4/`. |
| **Contrôle SQL** — 10 questions | CONTRÔLE | **ACCEPTABLE** | Vérification formelle de non-régression : correction du nom `v3_require_command_unit()`, distinction de `read_current_invitation`, qualification des contraintes modifiées. |

---

## 6. Réserves explicites impératives

Les réserves suivantes sont formellement enregistrées et maintenues actives :

### R-I4-01 — Preuves XML écrasées sur `V3InvitationWritePostgresTest` (méthodes 1 à 5)
Les reçus XML Surefire d'origine pour les cinq premières méthodes de `V3InvitationWritePostgresTest` ont été écrasés lors du rejeu ciblé de la méthode 6. Les résultats historiques sont acceptés comme rapportés ; aucune preuve indépendante d'inspection XML n'est revendiquée pour ces cinq tests. En application de la politique, aucun test n'a été régénéré artificiellement.

### R-I4-02 — Provenance d'exécution des campagnes initiales (`TEST_RUN_SHA`)
Pour les campagnes initiales exécutées avant la fixation du commit `d722e9d5`, le SHA exact du run physique n'est pas formellement traçable par le système de build (`TEST_RUN_SHA — NOT ESTABLISHED`). L'équivalence stricte des sources avec le SHA audité est documentée sans affirmation de provenance infondée.

### R-I4-03 — Sensibilité temporelle résiduelle du test d'expiration
Le test `V3InvitationWritePostgresTest#expirationAfterPublicationCounterWait` réduit fortement la sensibilité temporelle initiale et vérifie effectivement l'entrée dans l'attente transactionnelle. Un ralentissement exceptionnel de la machine avant ce point d'attente peut encore provoquer un échec de qualification ; il ne permet pas un faux PASS de l'oracle recherché.

### R-I4-04 — Portes globales canoniques différées à Q5
Conformément aux instructions et à la politique de réacteur, aucune porte globale n'a été jouée dans le périmètre local I4. Restent obligatoires et différées au jalon global **Q5/G5** :
- La porte `architecture-tests` globale.
- Le full reactor natif unique sous couverture effective G5/Architecture (option D6 ratifiée).
- La collection complète Bruno B5.
- La certification globale SPEC-10.

### R-I4-05 — Réserves historiques maintenues
Sont intégralement reportées et maintenues sans affaiblissement :
- L'ensemble des réserves héritées de L1–L4 et L5/I1–I3.
- `EXTERNAL AUTHN NOT QUALIFIED` : le fournisseur d'identité réel externe hors mock JWT local reste à qualifier globalement.
- Les limites physiques de dimensionnement, de saturation et de gestion mémoire READ issues de D4.
- L'absence de purge automatique des propositions Link (R-I3-01).

---

## 7. Statut terminal officiel et suites du programme

### Statut terminal de l'incrément I4
```
L5/I4 — CLOSED — LOCAL BOUNDED PASS WITH EXPLICIT RESERVATIONS
```

### Statut du lot L5
```
L5 — OPEN — Q5/G5 STILL REQUIRED
```

### Conséquences opérationnelles
1. L'ensemble des quatre incréments fonctionnels de L5 (I1, I2, I3, I4) est désormais clos avec réserves explicites.
2. Le lot L5 reste ouvert jusqu'à l'exécution et l'instruction des obligations de qualification globale différées à la phase **Q5/G5** (full reactor natif, architecture-tests, B5).
3. Aucune modification productive ou nouvelle campagne de test n'est engagée par cet acte.
