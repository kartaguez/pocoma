# Pocoma V3 — L5/I1 — Décision humaine d’acceptation et de clôture

Date : **4 novembre 2026**. Branche de publication : **`feat/v3-architecture`**.

## 1. Décision humaine et références

Source de la décision : instruction explicite du responsable du programme dans la conversation, « Pocoma V3 — L5/I1 — Clôture humaine et publication documentaire », du 4 novembre 2026. Le responsable accepte L5/I1 pour son checkpoint interne, avec les réserves identifiées, et autorise l’enregistrement et la publication documentaire de cette décision.

**L5/I1 — CLOSED — LOCAL BOUNDED PASS WITH EXPLICIT RESERVATIONS.**

| Référence | SHA |
|---|---|
| Départ initial I1, plan ratifié | `f46d63a9bc4505c43610376eeba40a5854431230` |
| Sources qualifiées | `46b94ce69a4b0f42474d38ac10b26daa78bb6b23` |
| Livraison publiée et HEAD initial de cette clôture | `a2c83d3e9ea651b8cb3015692fede6652677ddfd` |

L’[acte préalable de ratification et GO](L5_I1_Human_Ratification_and_GO.md) conserve son rôle historique. Le [rapport I1](L5_I1_Execution_Report.md), le [manifeste figé](L5_I1_Proof_Manifest.json) et le [dossier de preuves publié](../testing/evidence/L5-I1/README.md) attribuent les résultats techniques à leurs exécutions. Le commit de livraison ajoute les documents et preuves aux sources qualifiées ; cette clôture documentaire ne réattribue pas les tests à un nouveau SHA.

L’audit indépendant du code, des contrats, de l’architecture, des tests et des preuves publiées a rendu un avis favorable avec réserves explicites, selon le mandat humain. Cet avis est rapporté comme fondement de l’acceptation. Aucun auteur, rapport autonome, commande ou campagne non fourni n’est attribué à cet audit ; les campagnes ci-dessous sont celles des preuves de livraison, sans prétendre qu’elles ont été exécutées par l’auditeur indépendant.

## 2. Périmètre accepté

L’acceptation porte exclusivement sur :

1. Les huit endpoints HTTP READ V3 inventoriés dans le rapport I1.
2. Le Resource Server JWT/OIDC qualifié localement.
3. L’isolation des compositions V2/V3.
4. Les ports READ typés.
5. La propriété des résultats Command et Identity par EUI.
6. Les consultations Pot courant et historique à V.
7. Les droits et balances historiques à V.
8. Le chargement groupé des Expenses.
9. La navigation READ.
10. Les erreurs publiques et le masquage des ressources.
11. La prévalidation pure avant acquisition du quota.
12. Le quota SQL partagé de deux permis.
13. La sérialisation complète et bornée des réponses HTTP.
14. Les plafonds initiaux D4 ratifiés : batch 50, entrée 65 536 octets, sortie 4 194 304 octets, page 100 (défaut 20).
15. La livraison et l’exécution de Bruno B1.
16. Les preuves locales T1 et Architecture attribuées au SHA source qualifié.

Les admissions I2, Link I3, Invitation I4 et la qualification finale Q5 sont exclues. Cette acceptation ne clôt pas L5 et ne constitue ni certification globale SPEC-10, ni qualification de production.

## 3. Inventaire exact des preuves reprises sans rejeu

Les compteurs ci-dessous décrivent des contributions existantes qui se recouvrent ; ils ne sont pas additionnés en un nouveau total de qualification.

| Contribution existante | Attribution et résultat | Références publiées |
|---|---|---|
| T1 | **40/40 PASS**, H24 + Q10 + C6, sur `46b94ce69a4b0f42474d38ac10b26daa78bb6b23` | [Matrice des identités](../testing/evidence/L5-I1/Requirements_Test_Evidence_Matrix.md), [résumé machine](../testing/evidence/L5-I1/Qualification_Summary.json) |
| Reprise ciblée finale `optional-port-01` | **63/63 PASS**, soit les 40 I1 et les 23 méthodes COMMAND affectées par le chargement de la dépendance READ optionnelle, même SHA | [Métadonnées de reprise](../testing/evidence/L5-I1/archives/pocoma-l5-i1-optional-port-01.json), [archive](../testing/evidence/L5-I1/archives/pocoma-l5-i1-optional-port-01.tar.gz) |
| Bruno B1 complet, même reprise finale | **32/32 scénarios PASS, 66 requêtes**, même SHA ; `READ_FIXTURE — NO PUBLIC ADMISSION PROOF` | [Compteurs B1](../testing/evidence/L5-I1/Bruno_Observed_Counters.json), archive de reprise ci-dessus |
| Gate Architecture native non filtrée `architecture-08` | **2 231 PASS, 0 failure, 0 error, 0 skip**, même SHA ; inclut 167 oracles du module Architecture et les tests amont | [Métadonnées natives](../testing/evidence/L5-I1/archives/pocoma-l5-i1-architecture-08.json), [archive native](../testing/evidence/L5-I1/archives/pocoma-l5-i1-architecture-08.tar.gz), manifeste I1 |
| Full reactor natif | **NOT RUN** en I1 ; obligation conservée à Q5 | [Rapport I1](L5_I1_Execution_Report.md), [ratification D6](L5_I1_Human_Ratification_and_GO.md), [rapport de mutualisation](L5_D6_Gates_Mutualization_Report.md) |

L’[inventaire des commandes historiques](../testing/evidence/L5-I1/Executed_Command_Inventory.json), l’[index des archives](../testing/evidence/L5-I1/Archive_Index.json), les [temps observés](../testing/evidence/L5-I1/Observed_Timings.md), les [mesures D4](../testing/evidence/L5-I1/D4_Observed_Measurements.json) et l’[analyse des horloges](../testing/evidence/L5-I1/Clock_Measurement_Analysis.json) restent inchangés. Les FAIL, interruptions, corrections, durées et mesures physiques antérieurs sont intégralement conservés. Aucun verdict de campagne n’est modifié rétroactivement ; aucune nouvelle campagne n’est exécutée pour cet acte.

## 4. Réserves explicites maintenues

### R-I1-01 — Mémoire et disponibilité des grands Pots

Le plafond de sortie de 4 Mio borne le JSON sérialisé, mais ne démontre pas une borne universelle de mémoire pour la construction préalable des vues, leurs sous-objets et les DTO.

Les mesures de heap et RSS sont issues d’un banc local incluant serveur, clients et fixtures. Un Pot légitime dépassant le plafond peut être indisponible avec **503, sans troncature**.

Suite : conserver la réserve de capacité et prévoir une qualification adaptée en **L6**, ou une instruction ciblée plus tôt si un profil légitime révèle une saturation. **Aucune refonte préventive en I1.**

### R-I1-02 — Complexité de composition Spring

La séparation V2/V3 est conforme dans les compositions qualifiées. Les règles de profils, de sélection des beans, d’allowlists et de ressources restent néanmoins sensibles aux changements de classpath et aux nouveaux composants.

Suite : préserver les frontières validées ; ne modifier les compositions qu’en cas de besoin concret. Reporter toute rationalisation générale au lot approprié, selon le plan directeur.

### R-I1-03 — Fournisseur AuthN réel

Les JWT et contrôles cryptographiques ont été qualifiés localement. Le fournisseur d’identité réel, ses paramètres de confiance et son intégration externe ne sont pas qualifiés.

Suite : conserver **EXTERNAL AUTHN NOT QUALIFIED** jusqu’à preuve réelle. Ne pas exposer publiquement les parcours concernés sur la seule base des fixtures locales. Le consentement Link réel demeure à qualifier en **I3**.

### R-I1-04 — Coût de qualification Architecture

La gate obligatoire Architecture a finalement réussi avec **2 231 tests PASS**. Sa dernière exécution représente **86 min 53,849 s** en temps civil. Un écart de **940,599 s** entre horloges civile et monotone reste inexpliqué. Plusieurs échecs intermédiaires, leurs causes et leurs corrections sont conservés.

Suite : éviter les rejeux globaux sans obligation normative nouvelle ; privilégier les preuves ciblées et la réutilisation attribuée des preuves non invalidées. Le budget de campagne est un seuil de suivi, non un motif automatique d’interruption lorsqu’une campagne progresse normalement. **Aucun rejeu Architecture pour cette clôture documentaire.**

## 5. Réserves historiques transférées intégralement

Les actes historiques restent les autorités de leurs réserves, sans réduction ni clôture implicite : [L1](L1_Documentary_Closure.md) et compléments [F](L1_F_Closure_Report.md)/[F2](L1_F2_Closure_Report.md), [L2 Consumption](L2_Documentary_Closure.md), [L3 Identity](L3_Identity_Closure.md), [L4 READ](L4_Read_Closure_Decision.md). Le [plan global L5 §3.2](L5_Supra_Entry_Audit_and_Implementation_Plan.md#32-état-amont-et-réserves) en conserve le transfert.

Sont notamment maintenus :

- Les limites de qualification composite L4 : contributions attribuées à deux SHAs, aucun full natif PASS L4 et dispense humaine du quatrième full ; aucun résultat I1 ne réécrit ces verdicts.
- Les réserves Consumption **R-L2-01 à R-L2-05**, G1/G2 **INCONCLUSIVE**, G3 **NOT PASSED / NOT COMPLETED**, et les réserves Identity **R1–R4**, avec leurs limites et résultats historiques.
- Le **lease de 180 secondes non homologué pour la production**, les conditions de disponibilité, de reprise et d’autorité administrative.
- Les limites de volumes, ressources, mémoire, coûts, disponibilité et environnements ; aucune homologation générale de capacité, performance ou SLO.
- Les obligations finales L5/Q5 et les éventuelles obligations SPEC-10 encore non satisfaites, ainsi que les transferts L6/L7/L8.

La prévalidation pure avant quota, suivie historiquement sous `AUD-FINAL-I8-01`, est acceptée ici dans le seul périmètre I1 et ses preuves. Elle ne lève aucune autre réserve L4. L’approbation d’I1 ne dispense d’aucune gate ultérieure.

## 6. Obligations non couvertes et transition

**L5/I2 — ELIGIBLE FOR PLANNING — NOT IMPLEMENTED.** La décision autorise la poursuite du programme vers I2 : après publication vérifiée, I2 peut entrer directement en cadrage opérationnel en réutilisant les fondations et contrats validés. Aucun plan I2 n’est approuvé par cet acte ; I2 n’est ni démarré ni implémenté dans cette mission.

Le cadrage I2 reprend les réserves R-I1-01…04 et historiques, les décisions d’admission/idempotence/rétention, les plafonds d’admission D4 et les interprétations D7 à leur échéance selon le plan L5. Il doit préserver les frontières I1 et déclarer ses propres vérifications avant implémentation. Aucune nouvelle admission n’est acceptée ici.

I3 doit encore qualifier Link, le fournisseur réel et le consentement ; I4 doit encore livrer et qualifier Invitation et les obligations associées. **L5 reste OPEN**, sans acceptation globale de ses parcours externes.

La stratégie **D6 reste un full reactor natif unique à Q5**, avec mutualisation G5/Architecture **uniquement si la couverture effective de toutes les obligations est démontrée**. Le PASS Architecture I1 ne dispense pas de cette démonstration finale. **Bruno B5 et les preuves externes applicables restent dus indépendamment**, ainsi que les variantes, inventaires, provenance et obligations SPEC-10 applicables. Aucun full ni certificat global n’est acquis par cette clôture.

## 7. Vérification documentaire et publication

Frontière déclarée avant écriture : **slices primaire/secondaires NONE ; DB NONE ; impact documentaire uniquement**. Franchissement inattendu : **NONE**. Gate globale Architecture : **NOT REQUIRED / NOT RUN** pour cette mission. Full reactor : **NOT REQUIRED / NOT RUN**, car aucun comportement applicatif ni jalon global n’est changé. Aucune campagne Maven, PostgreSQL, Docker, Bruno ou Architecture ; aucune modification applicative, de test/oracle, Bruno, migration, politique Reactor, SPEC, Canon ou preuve historique.

Contrôles documentaires : inspection Git (`pwd`, `git status --short --branch`, `git branch --show-current`, `git rev-parse HEAD`, `git remote -v`, `git log -5 --oneline`, `git show`, `git ls-remote origin refs/heads/feat/v3-architecture`), lectures `rg`/`sed`/`cat`, contrôle Python des références locales, SHAs, statuts, réserves et du périmètre des six Markdown, revue du diff et **`git diff --check`**. Les commandes applicatives du rapport et des plans restent des références historiques ou futures, sans exécution dans cette mission.

Résultat des contrôles : **PASS documentaire**, six Markdown dans le périmètre, **435 liens locaux dont 22 ancres valides**, 48 tableaux à colonnes cohérentes, 16 éléments acceptés et quatre réserves explicites. SHAs et compteurs concordants avec Git et le manifeste ; corps historique du rapport I1 inchangé à partir de « Décisions et périmètre livré ». `git diff --check` sans erreur, y compris contrôle des espaces du nouveau document ; preuves et fichiers applicatifs inchangés.

Publication autorisée par le mandat humain : ajout des six documents prévus seulement, commit documentaire et `git push origin feat/v3-architecture` **sans force**, puis contrôle du SHA distant, de la divergence local/suivi et de la propreté du worktree. Le SHA final et les résultats effectifs de publication sont communiqués dans le rapport final ; aucun SHA autoréférent n’est inventé dans cet acte.
