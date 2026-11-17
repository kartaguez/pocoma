# Pocoma V3 — Clôture des corrections documentaires L2 après audit

**Référence auditée :** branche `feat/v3-architecture`, commit `1e5d2146b35b941bf36feda9bfaa4969cd0dfbf4`. Le [rapport indépendant](L2_Post_Consolidation_Independent_Audit_Report.md) reste inchangé. Cette clôture traite son FAIL documentaire et ne déclare ni G0 validé, ni L2 implémenté, ni PASS de certification V3.

## Matrice POSTAUD-001 à 006

| Constat | Correction et preuve documentaire | Fichiers | Statut documentaire |
|---|---|---|---|
| POSTAUD-001 MAJOR | P0 avant G0, I1–I5 producteurs de preuves techniques, G1/G2 qualification, I6/I7 puis G3 ; table obligation/artefact/producteur/gate/prérequis et ordre topologique sans cycle | [plan L2 §14](L2_Design_and_Verification_Plan.md#14-vérification-future-gates-provenance-et-critères-de-sortie), [consolidation §12](L2_R1_R10_Normative_Consolidation.md#12-revue-de-cohérence-documentaire--points-bloquants-avant-g0) | Corrigé, soumis à revue indépendante |
| POSTAUD-002 MAJOR | Manifeste exhaustif à identifiants stables, méthodes prévisionnelles, oracles, autorités, sujets, allocations et reports ; contrôle inverse statique. La réserve sémantique du contre-audit a motivé le catalogue et sa revue contradictoire ci-dessous. | [manifeste](L2_Certification_Coverage_Manifest.md), [catalogue](L2_Independent_Test_Oracles.md), [vérificateur](check_l2_manifest.py), [SPEC-10](SPEC-10_Test_Certification.md) | Corrigé documentaire, aucune preuve exécutée |
| POSTAUD-003 MINOR | Unique appel de `SlotCompletion.complete()` après effets/résultat, avant fermeture/suffixe ; absent/double/tardif/incorrect = poisoning et rollback ; contraintes différées et verrou final conservés | [plan L2 §8](L2_Design_and_Verification_Plan.md#8-acquisition-et-transaction-finale--point-critique-r1) | Corrigé |
| POSTAUD-004 MINOR | Oracle CON-06 commun à WRITE ou READ selon la Consumption, même unité physique que JDBC/JPA et suppression du slot ; poisoning, absence d'imbrication/2PC et refus de composition incohérente conservés | [SPEC-10 CON-06](SPEC-10_Test_Certification.md#con-06--protocole-et-oracles) | Corrigé |
| POSTAUD-005 MINOR | Objectifs PERF-01 acquis conceptuellement ; mesures et qualification V3 attendues ; aucun PASS, aucun budget changé ni seuil L1 importé | [SPEC-07](SPEC-07_Transverse_Requirements.md), [SPEC-09](SPEC-09_Performance.md), [SPEC-10](SPEC-10_Test_Certification.md), [plan directeur](V3_Implementation_Master_Plan.md) | Corrigé |
| POSTAUD-006 OBSERVATION | P4/P7 et manifeste séparent S1 verrouillé/S2 à renouveler et équité sous `M_W=1` avec deux Consumptions ; attente bornée/évitement, sans SQL unique, FIFO strict ou égalité de débit | [plan L2 §§10/12](L2_Design_and_Verification_Plan.md#10-polling-capacité-et-équité), [manifeste](L2_Certification_Coverage_Manifest.md) | Corrigé |

## Couverture et limites

Le manifeste porte **253 identifiants normatifs distincts** : 105 variantes SPEC-10 CON/EVT, 93 clauses R1–R10, les compléments CON-05 et 54 entrées L1-C/F/F2 (trois familles plus leurs garanties détaillées). Il contient **380 lignes de preuve**, dont **87 lignes de sous-scénarios**, **324 obligations L2** et **56 reports nominatifs**, avec **380 champs d’oracle renseignés**. Les reports restent attachés aux identifiants d'origine et aux lots receveurs L3–L6. Les scripts de contrôle recalculent ces nombres ; les comptes ne sont pas des verdicts de test.

La portée L2 demeure le vrai pilote Command Pot WRITE et un banc technique READ explicitement étiqueté. Identity réelle, Invitation et matérialisations métier READ restent aux lots receveurs du plan directeur. Les PASS historiques L1 ne sont pas importés comme preuves L2. Aucune méthode du manifeste n'existe encore en tant que preuve exécutée.

## Contrôles documentaires

Contrôles effectués sur ce dossier : `python3 docs/v3/check_l2_manifest.py` compare les identifiants de SPEC-10, R1–R10 et L1-C/F/F2 au manifeste, vérifie les sous-scénarios requis, l'unicité des ID/méthodes, oracles, allocations, reports, campagnes/gates et statuts, la table de dépendances, les formulations supersédées et la cohérence PERF-01. Résultat : **253 identifiants, 380 lignes, 324 obligations L2, 56 reports, 380 champs d’oracle renseignés, 87 sous-scénarios ; 579 liens relatifs et 49 ancrages valides ; zéro échec**. `git diff --check` et la recherche `rg` des formulations R1/R3/R5/CON-06/PERF-01 supersédées n'ont signalé aucune anomalie active. Le diff est limité aux documents `docs/v3/` et au vérificateur documentaire ; aucun test Java, PostgreSQL ni benchmark n'a été exécuté.

**Verdict : PASS documentaire pour les six corrections.** Le dossier doit encore faire l'objet d'**une nouvelle revue indépendante**, puis d'une **validation humaine explicite de G0**. L'audit/validation L1-F2 et les paramètres opérationnels à fixer aux étapes indiquées restent des prérequis du programme, sans être déclarés satisfaits par cette correction. Aucun PASS technique ou global n'en découle.

## Historique de la réserve POSTAUD-002 et levée documentaire

Le PASS documentaire des six corrections ci-dessus portait sur leur **couverture structurelle**. Le contre-audit ultérieur a rendu **PASS WITH RESERVATIONS** : 276 formulations d'oracle sur 380 reprenaient directement la propriété vérifiée, sans démontrer partout un calcul attendu et un verdict indépendants. Ce fait historique n'est pas effacé par la présente correction.

La présente intervention raccorde chaque ligne à un catalogue de 33 oracles paramétrés et documente une revue contradictoire des huit familles, notamment R1/R2/R3/R4/R5/R9/R10 et les variantes composées CON-05, CON-06, EVT-02. Le vérificateur contrôle références, unicité, champs et baseline ; il **ne prétend pas** établir la suffisance sémantique par expression régulière. Le contrôle de cette nouvelle intervention compte **253 identifiants normatifs, 380 lignes, 324 obligations L2, 56 reports, 87 sous-scénarios, 33 oracles distincts, 783 références, zéro référence orpheline et zéro ligne sans oracle**. Le vérificateur couvre aussi 584 liens relatifs et 49 ancrages. Les comptes 579 liens et 49 ancrages du paragraphe précédent restent ceux de la clôture historique.

Contrôles documentaires de cette intervention : vérificateur du manifeste, compilation syntaxique Python, liens/ancrages, comparaison cellule par cellule avec le manifeste de base (seule la colonne oracle change), recherche des statuts et formulations obsolètes, et contrôle du diff. Aucun gate global ou full reactor n’est requis pour ces seuls fichiers documentaires ; aucun Maven, Java, PostgreSQL ou migration n’a été lancé.

**Verdict de cette intervention : PASS documentaire sur la précision du protocole d'oracles ; réserve POSTAUD-002 levée dans le dossier documentaire.** Aucun essai PostgreSQL/Java, PASS technique, G0 ni ACCEPTED L2 n'est acquis. Une revue indépendante du dossier et une décision humaine explicite restent nécessaires avant G0.
