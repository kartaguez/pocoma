# Preuves de qualification locale L5/I4 — Invitation et désaffiliation volontaire

## 1. Contexte et statut

- **Incrément :** L5/I4 — Invitation et désaffiliation volontaire
- **Statut terminal documentaire :**
  ```
  L5/I4 — POST-AUDIT DOCUMENTATION FINALIZED — READY FOR HUMAN CLOSURE REVIEW
  ```
- **Politique normative :** `docs/testing/Reactor_Verification_Policy.md`
- **Périmètre d'exécution :** Preuves locales archivées issues des campagnes d'intégration initiale (avant `d722e9d5`) et des corrections ciblées post-audit (sur base `2b1271d0`).
- **Absence de rejeu :** Aucun rejeu applicatif, de test de lease 180s ou de gate globale n'a été déclenché pour cet archivage.

---

## 2. Traçabilité des versions Git

| Jalon | SHA | Description |
|---|---|---|
| **Baseline antérieure (I3 close)** | `ca21034f7873ed5c16f296665ae2aaf63d4090d7` | Point de départ avant introduction du code I4 |
| **Introduction du code I4** | `7a284405093c0c7593f8ca4f9ba70b5e605e1df3` | Migrations V10, contrôleurs, moteurs et tests initiaux |
| **Commit audité (I4 implémenté)** | `d722e9d5858c9e4eba5aa1883cf72988b463d780` | SHA soumis à l'audit indépendant |
| **Corrections ciblées post-audit** | `2b1271d0ca3db6c6e9dfddd74f2b877cf4631dd2` | Résolution technique AUD-I4-01 et AUD-I4-02 |
| **Rapport post-audit initial** | `a087dd8b3f07e2c6116533f98b597f78080da5eb` | Publication du compte rendu post-audit |

---

## 3. Inventaire des preuves archivées et empreintes SHA-256

Toutes les preuves conservées ont été vérifiées et ne contiennent aucun secret, token sensible ou JWT non expurgé (Bruno CLI a été exécuté avec `--reporter-skip-all-headers --reporter-skip-body`).

### Rapports Surefire XML & TXT (`surefire-reports/`)

| Fichier archivé | Empreinte SHA-256 | Tests / Bilan | Durée | Horodatage | Nature de la preuve |
|---|---|:---:|:---:|:---:|---|
| `TEST-com.kartaguez.pocoma.V3AdmissionFlowPostgresTest.xml` | `72e606323f28e230623493a778c8692477727f8b16df41c49780750a09c609d6` | 1 / 0 fail / 0 err | 19.187s | 05-11-2026 13:40 | PASS historique réutilisé (`TEST_RUN_SHA — NOT ESTABLISHED`) |
| `com.kartaguez.pocoma.V3AdmissionFlowPostgresTest.txt` | `c30bbe3a4e14293623e02f65b12870baa9a9a83016b1d818bf522be6e312ecbf` | - | - | - | Rapport texte associé |
| `TEST-com.kartaguez.pocoma.domain.pot.v3.InvitationAttachmentTest.xml` | `d1ca85a4a5a131230b1eea9843e2922481ef065e8f9a23a1191a41f6866b67b1` | 3 / 0 fail / 0 err | 0.039s | 05-11-2026 13:57 | PASS historique réutilisé (`TEST_RUN_SHA — NOT ESTABLISHED`) |
| `com.kartaguez.pocoma.domain.pot.v3.InvitationAttachmentTest.txt` | `264c53a408d5f48fa66c1a826c247058a1f4b5bd514c9b9086e8ff171e2b3ded` | - | - | - | Rapport texte associé |
| `TEST-com.kartaguez.pocoma.V3InvitationReadPostgresTest.xml` | `8777e4687f9ea64d28f29d2442eefaaef6e37808d864477737d513946d7ae29e` | 3 / 0 fail / 0 err | 28.327s | 05-11-2026 14:07 | PASS historique réutilisé (`TEST_RUN_SHA — NOT ESTABLISHED`) |
| `com.kartaguez.pocoma.V3InvitationReadPostgresTest.txt` | `7cf42ca65a3229761d60015c5b98242d11f918b08f0faa952ca28934883d029c` | - | - | - | Rapport texte associé |
| `TEST-com.kartaguez.pocoma.V3InvitationTopologyPostgresTest.xml` | `cbad0f5bd869ad13508d81b0a991a9814159f970cd48a51717ad2a8ee5fd7e74` | 2 / 0 fail / 0 err | 207.42s | 05-11-2026 14:12 | PASS historique réutilisé (`TEST_RUN_SHA — NOT ESTABLISHED`) |
| `com.kartaguez.pocoma.V3InvitationTopologyPostgresTest.txt` | `5211bd41bb791389e5631281e944653f7e47e1ec7bf0843c0a9f68f5a3654bcd` | - | - | - | Rapport texte associé |
| `TEST-com.kartaguez.pocoma.V3InvitationStandaloneWebPostgresTest.xml` | `5b518514bcf9a1d06057de94adf99e941dd5b2bdbd7afcea785964fbd3bd0c3e` | 2 / 0 fail / 0 err | 7.116s | 05-11-2026 14:31 | **PASS observé et attribué** (`TEST_RUN_SHA` : `2b1271d0`) |
| `com.kartaguez.pocoma.V3InvitationStandaloneWebPostgresTest.txt` | `539a745c7b9956c48f643e09756400031d3bdf0a0b26dfeb160c0d9cdbb6a273` | - | - | - | Rapport texte associé |
| `TEST-com.kartaguez.pocoma.V3InvitationWritePostgresTest.xml` | `c83c06fc2d0eae4e3fa7e920df51634ca50884625b10a10c49d3d40e384f1f24` | 1 / 0 fail / 0 err | 17.834s | 05-11-2026 14:31 | **PASS observé et attribué** (`TEST_RUN_SHA` : `2b1271d0`) |
| `com.kartaguez.pocoma.V3InvitationWritePostgresTest.txt` | `8d7f181c5aff3d867fd6ddae45a25dee7be1f64a078e4630d12562c6d3bf5d90` | - | - | - | Rapport texte associé |
| `TEST-com.kartaguez.pocoma.V3InvitationHttpTest.xml` | `6c3cc78ea9a6d1328529c9aa93f003b10e57401e38f1111b91b2b6ed51a65710` | 5 / 0 fail / 0 err | 67.68s | 05-11-2026 14:33 | **PASS observé et attribué** (`TEST_RUN_SHA` : `2b1271d0`) |
| `com.kartaguez.pocoma.V3InvitationHttpTest.txt` | `951a411f6cf43ca291f028315db299fc510cecb3d98d7b9719c67c053b8a5dfa` | - | - | - | Rapport texte associé |

> **Note sur `V3InvitationWritePostgresTest` méthodes 1 à 5 :** Le fichier XML initial contenant les 6 méthodes (durée ~74s, horodaté 14:06) a été écrasé sur le système de fichiers par l'exécution ciblée de la seule méthode 6 (`expirationAfterPublicationCounterWait`) lors de la passe de correction post-audit à 14:31. En application stricte de la politique de non-rejeu, aucun test n'a été régénéré artificiellement. Les méthodes 1 à 5 sont donc qualifiées : `EVIDENCE NOT AVAILABLE FOR INDEPENDENT INSPECTION (OVERWRITTEN BY POST-AUDIT RUN)`, résultat historique rapporté : PASS.

### Rapports Bruno B4 (`bruno-b4/`)

| Fichier archivé | Empreinte SHA-256 | Description |
|---|---|---|
| `bruno.log` | `746ce398afc8358f748117355b25ba3c418c24f3d5d257b4e70ab61c4c486654` | Sortie console Bruno CLI : 4/4 passed (B401–B404), durée 11738 ms |
| `bruno.json` | `19d5a20983e4e68c9fedb961a084599aca94640e79cc6f27593482af41969753` | Rapport structuré Bruno (sans en-têtes ni corps sensibles) |
| `receipt.json` | `1ea2cbfcd1be20da528ff7887f727dcd236e8837368704878ec1a936a1a99a73` | Reçu de synthèse d'exécution (scénarios, requêtes primaires et auxiliaires) |
| `requests.jsonl` | `c8115ecbfd23991f5f9cad064b7cffd0b4d55631f2858af213ddd905ef8619dc` | Traces structurées des requêtes HTTP (méthode, chemin, statut, sans payload sensible) |

---

## 4. Manifeste JSON

Le fichier [`manifest.json`](manifest.json) regroupe l'ensemble des métadonnées de provenance, d'environnement et de hachage de façon automatisable et vérifiable.
