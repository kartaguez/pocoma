# L5/I2 — Correctif post-audit AUD-I2-01 / AUD-I2-02

Date : 5 novembre 2026. Départ : `df0c3a221ce20b4eb569616d7df621fa4090a32b`, branche `feat/v3-architecture`, arbre propre. Mandat humain : correction ciblée et publication sans force push ; aucune clôture I2 ou qualification globale.

## Périmètre déclaré avant implémentation

Impact : ACL d’admission et projections de lecture dans deux fonctions SQL SECURITY INVOKER partagées, sans changement de prédicat. Module modifié : `infra-persistence-primary-jpa`. Primaire **WEB**, secondaires **NONE** (comportement terminal inchangé). Aucun changement HTTP, Consumption/Discovery, READ, Worker, frontière, dépendance Maven, propriétaire ou transaction. Canonique de référence : `./mvnw -pl runtime-web-api -am test` ; sa gate complète est **NOT RUN**, interdite dans cette mission. Feedback CHANGE positivement filtré sur 9 méthodes de `V3AdmissionPostgresTest`, avec le même ancrage WEB et `-am`.

Architecture §17 : **NOT REQUIRED / NOT RUN** : ni dépendance, ni frontière/isolation, ni règle structurelle modifiée ; les contrôles d’autorité existants sont resserrés dans la même frontière. Si un tel déclencheur apparaît : **ARCHITECTURE REQUIRED — NOT RUN — DEFERRED TO Q5 BY EXPLICIT HUMAN INSTRUCTION** ; les tests ciblés ne pourraient pas l’acquitter. Full reactor : **NOT REQUIRED / NOT RUN** pour ce correctif ; Q5 reste dû. Aucun franchissement inattendu prévu.

DB : **CURRENT V3 WRITE**, PostgreSQL réel, rôle LOGIN dédié et observateur administrateur indépendant. Aucune baseline V3 TRUSTED disponible : le bootstrap de ces seules méthodes applique V1..V8 V3 ; aucune chaîne historique V2 ni campagne L1–L4/I1 complète. Budget indicatif : 3–8 minutes, aucun arrêt artificiel d’une campagne saine.

## AUD-I2-02 — Graphe et correction minimale

Application : `JdbcAdmissionStore.find` lit seulement les neuf colonnes de l’association ; `reserve` insère onze colonnes et RETURNING request_id, avec conflit sur scope_key_fingerprint. `insert` écrit les huit colonnes Command ou les neuf colonnes Identity déclarées dans le script. Aucune application ne relit les demandes ni n’utilise ces permissions pour une décision métier.

Graphe d’un INSERT PENDING :

- Command : CHECK/PK, puis `v3_command_unit` différé → `v3_require_command_unit` (définition effective V5 remplaçant V2). Le guard d’immutabilité V2 ne s’exécute que pour UPDATE/DELETE. Lecture Command, puis résultat ; PENDING sans résultat retourne. Les chemins terminaux restent intégralement présents.
- Identity : CHECK/PK et `v3_identity_request_guard` BEFORE INSERT (NEW uniquement), puis `v3_identity_request_unit` différé → `v3_identity_require_unit` → `v3_identity_check_unit(rid,true)` V6. Lecture demande/résultat, compteurs User/Version/Event, completion, ouverture position/slot, puis rejet d’effets sur PENDING et retour. Aucun lookup EUI courant ni chargement de token n’est nécessaire dans ce préfixe.
- Association : CHECK/PK/UNIQUE et FK différées vers Command/Identity ; PostgreSQL assure son intégrité référentielle interne sans GRANT SELECT des demandes au client. Le trigger d’immutabilité V7 concerne UPDATE/DELETE seulement.

Lectures effectives désormais accordées (colonnes WHERE incluses) :

| Relation | SELECT application/contrainte |
|---|---|
| v3_admission_association | request_id, category, operation, canonical_version, lookup_key_id, comparison_key_id, scope_key_fingerprint, intent_fingerprint, created_at — application uniquement |
| v3_command | request_id, status, pot_id |
| v3_command_result | request_id, outcome, resulting_pot_version |
| v3_identity_request | request_id, status, operation, target_issuer, target_subject |
| v3_identity_request_result | request_id, outcome, target_eui_id, target_version |
| v3_pocoma_user | created_request_id |
| v3_eui_version | request_id |
| v3_identity_event | request_id (count du préfixe) |
| v3_identity_completion_event | event_id, subject_type, subject_id |
| v3_event_position | event_id |
| consumption_slot | consumption_id, consumable_id |

Les projections avant la bifurcation PENDING portent aussi les champs utilisés par les chemins terminaux (par exemple pot_id/outcome), ce qui évite de réordonner les contrôles. Les autres SELECT * de V6 sont strictement après le retour PENDING : Version, Authority, Event, User ; ils restent inchangés et ne justifient aucun droit supplémentaire pour l’admission. Leur accès reste réservé à l’exécution autorisée.

V8 remplace **cinq projections seulement** dans les deux fonctions, sans changement de corps logique, ordre, statut, verrous ou trigger. Les `%rowtype` sont conservés ; les champs non lus ne sont utilisés par aucun prédicat. Les migrations V1..V7 restent byte-identiques. Ce changement supprime effectivement les lectures `binding_token`, `payload`, `admission_context`, requester et rejection superflues ; aucun SECURITY DEFINER ni rôle/table/framework n’est ajouté. Les INSERT gardent les tokens nécessaires aux intentions, sans autoriser leur lecture.

Le script est à appliquer **après V8**, comme administrateur, puis ouvrir de nouvelles connexions d’admission. Il révoque aussi les ACL directes par colonne : un simple REVOKE au niveau table ne les retire pas. Cette réapplication élimine un ancien GRANT sensible direct ; les privilèges PUBLIC/hérités inattendus restent refusés par `AdmissionSqlAuthority`. Celui-ci vérifie exactement les ensembles SELECT/INSERT par colonne, y compris les auxiliaires, et conserve tous les refus préexistants. Les rôles Worker/READ ne changent pas ; aucune promesse de retirer leurs droits propres n’est faite.

## AUD-I2-01 — Décision d’évolution, réserve explicite

Aucun constructeur métier ni règle canonique ne change ici. Pas de nouveau codec ni de copie de domaine. La solution immédiate retenue est documentaire, conformément à l’option autorisée par le mandat ; **le découplage exécutable face à un futur durcissement de constructeur n’est pas livré ni revendiqué**.

Chemin actuel : HTTP → `CommandAdmissionInput.decode` → Label/Name, IDs, Fraction/Amount/Weight, ShareholderTransitions, ExpenseDetails/ExpenseFinancials, intentions et PotCommand → `AdmitCommandV3.admit` → `input.command(0).requester()` → lookup d’association → `canonical(association.canonicalVersion())` et `AdmissionProtection.matches`. Le simple déplacement de `validateCurrent` ne coupe donc pas la dépendance aux constructeurs. `AdmissionFields` fixe objets fermés, UUID normalisés, UTF-16 valide, framing typé/longueurs ; le décodeur fixe dates, rationnels exacts réduits, participants triés, autres listes ordonnées et champs requester/token/attachement. `AdmissionProtection` lie version et empreinte avec clé historique ; canonical_version=1 et les anciennes clés demeurent requis.

Contrainte d’évolution V1 : le langage historiquement admis et ses octets canoniques sont immuables. En particulier Label/Name acceptent actuellement toute chaîne non nulle (y compris vide/blanche) ; Amount autorise zéro, Weight exige >0, Fraction normalise le signe et le PGCD, les participants sont non vides et uniques, PotCommand fixe observation et attachements. Toute restriction nouvelle de ces constructeurs ou des shapes, UUID, dates, fractions, limites de parsing, listes et principal doit être revue contre ce chemin **avant** sa livraison. Une nouvelle règle autonome doit s’appliquer uniquement à une intention nouvelle, après lookup ; elle ne doit pas restreindre le décodeur historique V1. Si un constructeur doit malgré tout être durci, la même livraison doit isoler le décodage/comparaison V1 de ce constructeur (et enlever la construction pré-lookup dans `admit`), en conservant exactement les règles V1, sans changer les intentions persistées.

Preuve alors obligatoire : fixture réellement admise avant le durcissement, entrée re-décodée sous le nouveau code, même clé → même UUID sans réinsertion ; intention distincte → 409 ; même ancienne entrée avec nouvelle clé → refus de la règle courante. Le test doit exercer le constructeur effectivement modifié. `oldIntentAfterRuleChange` et `restoreAndOldKeyCompatibility` ne prouvent que la baisse de la limite configurable et les clés ; ils ne prouvent pas un changement de constructeur. Aucun tel changement n’existant ici, aucun scénario artificiel ni test de limite supplémentaire n’est ajouté/rejoué. **AUD-I2-01 reste une réserve d’évolution documentée ; aucune garantie générale indépendante des constructeurs n’est revendiquée.**

## Simplicité — invariant, contre-exemple, coût

| Modification | Invariant / échec sans correction | Pourquoi plus simple ne suffit pas | Coût |
|---|---|---|---|
| V8, cinq projections | confidentialité des tokens/contextes sans affaiblir l’unité différée ; SELECT * impose encore leur lecture | Retirer les GRANT seuls fait échouer INSERT PENDING au commit ; ignorer le trigger affaiblirait l’intégrité | deux fonctions SQL reprises pour migration additive ; aucune ressource runtime nouvelle |
| ACL et inventaire exact | autorité minimale ; rôle pouvant lire un token actuellement accepté | droit SELECT de table ou inventaire par table autorise toujours les secrets ; REVOKE de table seul laisse les ACL de colonne | map par colonne et révocation convergente ; aucune requête d’inventaire supplémentaire |
| Tests ciblés | refus effectifs, S-03 et contraintes différées | une vérification de catalogue seule ne prouve ni refus physique ni commit/rollback | quatre nouvelles méthodes PG ; cinq méthodes existantes sélectionnées |
| Documentation V1 | réutilisation historique lors d’une future évolution ; par exemple Label refusant une ancienne chaîne blanche avant lookup | baisse de limite configurable ne touche pas cette frontière ; une abstraction préventive dupliquerait les contrats sans règle modifiée | aucune ligne métier/runtime, réserve explicite et obligation sur la future modification |

## Inventaire avant tests et oracles attendus

Classe unique : `com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.V3AdmissionPostgresTest`.

| Méthode | Oracle |
|---|---|
| sensitiveRequestReadsDeniedAndPendingAdmissionsCommit | Command + Registration + Unlink PENDING commit, replay Identity stable, huit lectures interdites SQLSTATE 42501, ACL surnuméraire refusée et réapplication convergente |
| identityInsertFailureRollsBackAssociation | observateur indépendant ne voit aucun des deux INSERT non committés ; panne après insert Identity → 503 et zéro durable |
| deferredPendingChecksStillRejectEffects | résultat Command, résultat Identity ou User illégal : contraintes différées sous rôle admission → 23514, aucun effet visible/durable |
| narrowedFunctionsPreserveInheritedIntegrityPredicates | corps déployés identiques aux V5/V6 après normalisation des cinq seules projections |
| rollbackAndOwnerBoundaries | rollback entre inserts, propriétaire physique, concurrent rollback/winner, zéro orphelin |
| associationForeignKeysAndImmutability | FK différée sans demande rejetée, association valide atomique, mutation refusée |
| admissionRoleAndIndirectPrivileges | inventaire effectif : MAINTAIN, token, vue PUBLIC, SECURITY DEFINER refusés ; Worker/READ ne peuvent admettre |
| admissionNeverReadsBusinessTables | lookup Pot/EUI interdit ; admission sans lecture d’autorité métier réussit |
| concurrentSameKeySameIntent | deux transactions physiques, même clé → même demande et association unique |

Prérequis vérifiés : Docker 29.9.2 ; image locale PostgreSQL épinglée `postgres@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73`, JDK 21. La sélection source puis le modèle effectif Maven doivent exclure architecture-tests avant exécution. Le collecteur inchangé `scripts/testing/verify.py` est chargé avec une recette temporaire explicite, conservée avec les preuves ; registre de campagnes/politique inchangés. Identifiant de run et XML isolés obligatoires ; pas de réemploi de target/*-reports. Une seule invocation de qualification prévue ; aucun retry inchangé.

## Résultats et publication

**PASS : 9/9, 0 FAIL, 0 ERROR, 0 SKIP ; aucun résultat INCONCLUSIVE.** Maven test : 14,016498 s ; découverte : 2,203614 s ; campagne complète collecteur : 17,252554 s. PostgreSQL réellement observé : **17.11**. Une invocation unique ; aucun retry. La fermeture effective est de 50 modules non parents (+ parent), aucun architecture-tests, Worker runtime ou Monolithe ; seuls les neuf oracles déclarés ont été exécutés. Les autres modules sont compilation/amont, pas des campagnes qualifiées.

Commandes Maven effectivement exécutées depuis `app/` (sélection vérifiée avant chaque commande de build ; `./mvnw -v` est une lecture de version sans sélection/build) :

```sh
./mvnw --batch-mode --no-transfer-progress -pl runtime-web-api -am '-Dtest=com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.V3AdmissionPostgresTest#sensitiveRequestReadsDeniedAndPendingAdmissionsCommit+identityInsertFailureRollsBackAssociation+deferredPendingChecksStillRejectEffects+narrowedFunctionsPreserveInheritedIntegrityPredicates+rollbackAndOwnerBoundaries+associationForeignKeysAndImmutability+admissionRoleAndIndirectPrivileges+admissionNeverReadsBusinessTables+concurrentSameKeySameIntent' -Dsurefire.failIfNoSpecifiedTests=false -Dpocoma.verification.id=4a117bac13c0406599ba055e6dc15b5f -Dpocoma.verification.reports=/private/tmp/pocoma-l5-i2-audit-01/reports help:effective-pom -Doutput=/private/tmp/pocoma-l5-i2-audit-01/effective-pom.xml
./mvnw --batch-mode --no-transfer-progress -pl runtime-web-api -am '-Dtest=com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.V3AdmissionPostgresTest#sensitiveRequestReadsDeniedAndPendingAdmissionsCommit+identityInsertFailureRollsBackAssociation+deferredPendingChecksStillRejectEffects+narrowedFunctionsPreserveInheritedIntegrityPredicates+rollbackAndOwnerBoundaries+associationForeignKeysAndImmutability+admissionRoleAndIndirectPrivileges+admissionNeverReadsBusinessTables+concurrentSameKeySameIntent' -Dsurefire.failIfNoSpecifiedTests=false -Dpocoma.verification.id=4a117bac13c0406599ba055e6dc15b5f -Dpocoma.verification.reports=/private/tmp/pocoma-l5-i2-audit-01/reports test
./mvnw -v
```

Lancement : `python3 -B /private/tmp/pocoma-i2-audit-run.py` ; la recette exacte, les fermetures source/effectives et le lanceur sont conservés dans l’archive. Le collecteur original assure la découverte, la provenance, les empreintes avant/après, les neuf identités attendues, les XML neufs et les résultats ; la recette temporaire est explicitement distincte du registre permanent inchangé. Les variantes `--plan` de ce lanceur ne lancent pas Maven. Contrôles auxiliaires : `docker version`, `docker image inspect`, `java -version`, formatage Google Java Format 1.24.0 des deux fichiers Java modifiés ; `git diff --check` ; comparaison des empreintes testées/courantes et restauration de chaque membre de l’archive avec SHA-256 identique.

Preuves nouvelles : [registre additif](L5_I2_Post_Audit_Proof_Manifest.json), [résultat détaillé](../testing/evidence/L5-I2-post-audit/result.json), [plan exact](../testing/evidence/L5-I2-post-audit/plan.json), [archive complète](../testing/evidence/L5-I2-post-audit/pocoma-l5-i2-audit-01.tar.xz). Logs, modèle effectif, XML, observations SQL, toolchain, inputs et empreintes s’y trouvent. Aucune expurgation nécessaire : valeurs d’essai et provenance de fixture uniquement ; les octets du run sont conservés sans modification.

**AUD-I2-02 : CORRECTED / preuve locale ciblée PASS. AUD-I2-01 : contrainte d’évolution documentée, réserve conservée.** Propriétés affectées : autorité SQL d’admission, projections de contraintes, schéma CURRENT V8. Propriétés dont les sources sont inchangées : domaine et canonique V1, HTTP, services d’admission et transaction owner, Worker/READ, Consumption/Discovery, graphe Maven. Aucun franchissement inattendu : **NONE**. Aucun défaut détecté masqué par exclusion.

Réemploi historique : les 1409 inputs C-04 sont comparés à la livraison corrigée : deux chemins changent (inventaire ACL et classe de tests), plus V8 ajouté ; le script ACL externe change aussi. L’archive C-04 existante est disponible et son SHA-256 `8f0dbca15d33e8c6c91ebf6fb738ea45a5f21fd16a033a9d2d6b348ff69cc546` a été revérifié. Les observations inchangées sont des contributions historiques, attribuées au snapshot de qualification original et à sa livraison `df0c3a221ce20b4eb569616d7df621fa4090a32b`. Le [manifeste original](L5_I2_Proof_Manifest.json) et tous ses fichiers restent intacts. **C-04 2291 PASS, Architecture 170 PASS, T2 85 identités et B2 24 scénarios / 468 requêtes ne sont jamais présentés comme frais sur le correctif.** Les preuves SQL/ACL affectées sont remplacées par les neuf méthodes fraîches pour leur seule portée ; aucun rejeu terminal Worker global n’est revendiqué.

Obligations non rejouées : gates complètes WEB/COMMAND/Monolithe, Architecture, full reactor, campagnes L1–L4/I1, Bruno B2. Aucune gate §17 nouvellement requise n’a été identifiée ; aucune dette Architecture déclenchée par ce diff n’est réputée acquittée. Q5, les réserves historiques et l’acceptation humaine I2 restent dus. La présente mission ne vaut ni clôture de lot ni certificat global.

Avant publication : SHA initial confirmé, diff limité aux quatre fichiers exécutables nécessaires et au rapport/registre/preuves additifs ; aucune SPEC, politique, POM, migration V1..V7 ou preuve historique modifiée. Les inputs testés sont inchangés, sauf suppression d’une ligne blanche à la fin de V8, hors des corps de fonctions, détectée par `git diff --cached --check` avant commit. Les octets publiés suivis d’un seul retour ligne reproduisent exactement le fichier testé et son SHA-256. Le registre et l’archive conservent les deux empreintes, le fichier V8 testé et ce reçu de correction purement typographique ; aucune instruction SQL ne change et aucune campagne saine n’est relancée. Publication prévue sur `feat/v3-architecture`, commit normal et push sans force ; le SHA final, le résultat distant et l’état propre seront donnés dans le reçu de livraison, sans auto-référence dans le commit.
