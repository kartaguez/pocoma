# Dossier de preuves de qualification L5/Q5-B

Date : **5 novembre 2026**  
Dépôt : **`kartaguez/pocoma`**  
Branche : **`feat/v3-architecture`**  
SHA qualifié : **`ac32a7db58545bf147b244a2fe528636b4bb464f`** (`ac32a7db`)  
Statut : **QUALIFICATION FAILED — DIAGNOSIS RECORDED**  
Invocation ID : **`61160296705d42239d26995b8fb5c0b6`**  

---

## 1. Contenu du dossier

Le présent dossier contient les artefacts d'exécution de la campagne Maven CERTIFICATION globale lancée dans le cadre de la mission L5/Q5-B :

| Fichier | Description | Empreinte SHA-256 |
|---|---|---|
| `manifest.json` | Manifeste d'intégrité et inventaire des preuves | *Voir ci-dessous* |
| `result.json` | Synthèse terminale du collecteur `verify.py` (1 532 tests, 1 failure, 0 error, 0 skip) | `9eefee0396fd0a4f5cb13ef419cb7d4eb832962eb7dd826f04646700df13d508` |
| `plan.json` | Plan de run et commande canonique exécutée | `8ee75b8ba1704e6c99c9da6060c5a3597d83dd27ce5b7d60f9ef465c4ecab9f9` |
| `provenance.json` | Empreintes Git, versions Java/Maven/Docker et inventaire des inputs | `e358bfa79fe5fc5b3511eb9bc76317bc2d44fc3777d5ea8c919d7d242ef99827` |
| `timings.json` | Chronométrie détaillée des phases de collecte et d'exécution | `b99d6350f555c82ff2f61a1532f7823f668270529d892e850b5514f04c6d2fe2` |
| `surefire-reports/TEST-*.xml` | Rapport Surefire XML du test défaillant `IdentityDiscoveryPostgresTest` | `105a3068e547342fb882193b0b561c92ba1ca1b69850e05697669d0d9370732b` |
| `surefire-reports/*.txt` | Traces Surefire d'exécution | `739a8c7929d20c52bbcb391e0a84e27f4d8a1be573a6976b29f03222dc23e660` |

---

## 2. Résumé de l'incident de qualification

La campagne Maven CERTIFICATION a exécuté avec succès 1 531 tests à travers les 23 premiers modules du réacteur.
Au module 24 (`pocoma-infra-persistence-primary-jpa`), le test d'intégration PostgreSQL suivant a échoué :
- **Classe** : `com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.IdentityDiscoveryPostgresTest`
- **Méthode** : `l3_identity_windows` (ligne 98)
- **Erreur** : `org.opentest4j.AssertionFailedError: Expected java.lang.IllegalArgumentException to be thrown, but nothing was thrown.`

### Diagnostic
- **Origine** : Test unitaire/intégration rédigé lors du lot L3 (`f905a3c9`), époque où `ConsumableFamily.INVITATION_EVENT` n'était pas supporté par `JdbcEventDiscoveryReferences`.
- **Évolution légitime** : L'incrément L5/I4 (`d722e9d5`) a implémenté le protocole d'invitation et ajouté le support complet de `INVITATION_EVENT` dans `JdbcEventDiscoveryReferences`.
- **Cause de l'échec** : L'oracle du test de L3 exigeait une exception qui n'a plus lieu d'être depuis l'introduction de la fonctionnalité d'invitation.
- **Conséquence** : Maven a interrompu le réacteur au module 24, empêchant l'exécution des modules avals (`pocoma-runtime-monolith`, `pocoma-runtime-web-api`, `pocoma-architecture-tests`).

Conformément à la politique d'audit et aux consignes impératives de Q5-B, aucune modification opportuniste de test n'a été effectuée. Le statut de qualification est formellement consigné comme **FAILED** avec diagnostic complet dans le rapport [`docs/v3/L5_Q5B_Qualification_Execution_Report.md`](../../v3/L5_Q5B_Qualification_Execution_Report.md).
