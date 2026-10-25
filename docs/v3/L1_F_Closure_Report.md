# L1-F — Clôture corrective post-audit L1

2026-10-25. **L1-F CLOSED / PASS — périmètre interne correctif défini ci-dessous.** L1 reste ACCEPTED sur sa fondation interne ; ses campagnes A…E, leur manifeste et leurs verdicts historiques ne sont pas réécrits. **Aucune certification V3 globale.** La couverture complète des deux gates est INCONCLUSIVE pour un skip exploratoire V2 conservé ; toutes les obligations internes L1-F sont PASS. Le parcours Invitation complet reste explicitement à L5, conformément à la frontière autorisée.

## 1. Références, portée et provenance

- Baseline documentaire/technique et HEAD distant initial vérifié : **`2fb9a536f3894e797923c0b49aeba1a9b5adfda2`**.
- SHA final d'implémentation et de tests, utilisé par les deux gates frais : **`c94e7a4bec479ccbf22b058fd341ae0350c2ca67`** (H). Aucun changement Java, SQL, test, POM, script ou configuration exécutable après H.
- Le commit de livraison ajoute uniquement ce bilan, les statuts documentaires et les archives/preuves. Son SHA exact se retrouve sans source externe par `git log -1 --format=%H -- docs/v3/L1_F_Closure_Report.md` ; le constat de publication/final HEAD est également donné dans le rapport de livraison Git. Cela évite un SHA autoréférent inventé dans son propre commit.
- [F0 contradictoire avant production](L1_F_Contradictory_Inventory.md), [méthodes et commandes fixées avant gates](L1_F_Proof_Manifest.json), [index des preuves](../testing/evidence/L1-F/Gate-index.json), [reproduction/archives](../testing/evidence/L1-F/README.md).

Le checkout utilisateur est resté sur `feat/runtime-monolith` à `90a3e9bc1a1fdd325d0c36e543824239cd85a431`. La branche locale V3 occupée à ddca2827 et ses dix fichiers indexés ont été préservés. Travail dans un clone indépendant sans hardlinks `/private/tmp/pocoma-v3-l1-f`, sur **feat/v3-architecture**, issu du HEAD distant exact. Aucun reset, clean, stash, suppression de branche/worktree ni force push. Inventaires finaux `preserved-*.txt` dans le dossier de preuves ; toutes les mutations de ce mandat ont visé le seul clone.

**Slices déclarées avant code : COMMAND primaire ; EVENT et PROJECTION secondaires.** Impact de production : domain-pot, engine-write-pot, infra-persistence-primary-jpa et index CURRENT V3. Aucun changement de dépendance/module, autorité Identity réelle, runtime, HTTP, READ ou moteur Consumption. **Franchissement inattendu : aucun.** La fermeture Maven plus large n'est pas un changement de production dans ses runtimes.

**Gate global d'architecture requis et exécuté** pour la clôture demandée et les gardes de pureté/frontières. **Full reactor non requis et non exécuté** : correctif borné après L1 accepté, sans restructuration ni intégration main. Aucun `./mvnw test` nu ni `-amd`.

DB métier : PostgreSQL réel, schémas neufs, Flyway explicitement localisé sur `db/v3-pot/migration` et, pour C, `db/v3-pot-execution/migration`. B applique V1+V3 ; C applique V1+V2+V3. Aucun fichier V1/V2 modifié. Les migrations historiques PRIMARY V1–V31 et READ V1–V14 ont été exécutées **transitivement** par les suites préexistantes des gates non filtrés ; aucun gate HISTORICAL séparé, aucune baseline V2 TRUSTED supposée.

## 2. Conclusions contradictoires et statut exact des AUD

| AUD-L1 | Conclusion initiale et action | Statut final / limite |
|---|---|---|
| 01 | Confirmé : chaînes complètes chargées par les lectures ponctuelles. Métadonnée exacte/dernière par index, sélection temporelle SQL, index GiST, vérificateur indépendant ; writers complets conservés | **PASS interne** : versions/frontières/suppressions/références, corruption à V et hors V, sélection, EXPLAIN et profondeur |
| 02 | Confirmé : rejet Identity lisait/verrouillait le Pot. Branche INVALID_BINDING désormais indépendante du Pot à préparation/finalisation | **PASS** : zéro appel Pot, verrou Pot tenu ailleurs, Identity concurrente/maintenue jusqu'au commit, complétion unique |
| 03 | Partiel : null/null élément déjà refusés, vide/doublon acceptés. Validation commune dans les trois constructeurs, copie immuable | **PASS interne** : admission de fixture sans durable, désérialisation valide/corrompue, exécution métier ; HTTP V3 à L5 |
| 04 | Slot spécifique confirmé, frontière L1 assumée. Unification L2 prescrite dans SPEC-05 §11 ; garanties C inventoriées | **PASS documentaire**, migration **DEFERRED L2** ; aucun second moteur ni DDL générique créé |
| 05 | Aucun défaut arithmétique confirmé. Profilage JFR puis simplification mesurée du calcul exact | **PASS mesures internes/non-régression** ; aucun SLO ni enveloppe universelle certifiés |
| 06 | Catalogue interrogé à chaque publication confirmé. Liste statique des mêmes 22 contraintes, point de validation conservé | **PASS** : égalité catalogue/noms/définitions/propriétés, publication différée, vraie FK invalide → rollback technique |
| 07 | Défaut WRITE infirmé : no-op et rejets déjà distincts et atomiques. Mapping normatif explicité | **PASS WRITE/documentaire** ; projection READ réelle **DEFERRED L4/L5** |
| 08 | Cycle Invitation V3 absent, seule éligibilité pure livrée en D ; limitation déclarée, pas régression historique | **PASS socle** : intention pure dédiée ajoutée, règles et CAS des primitives éprouvés ; parcours durable/token/POT_EVENT effectif **DEFERRED L5** |
| 09 | Aucun consumed/réservation existant ; réutilisation insuffisamment explicite dans les contrats | **PASS documentaire** : invitation par Pot réutilisable jusqu'à expiration ; preuve du même lien réel entre bénéficiaires **DEFERRED L5** |
| 10 | Huit composantes vérifiées ; huit codes enum + INVALID_BINDING, donc neuf motifs initiaux | **PASS documentaire** : SPEC-01 §10 et SPEC-03 §10, aucun registre dynamique |
| 11 | Risque confirmé de branche/worktrees occupés et sales | **PASS préservation** : clone isolé, aucun nettoyage des travaux préexistants ; livraison par avance rapide sans force |

Les réserves de parcours ne sont pas présentées comme des fonctionnalités livrées. La permission de borner Invitation au socle L1 est appliquée explicitement ; INV-01 complet reste non certifié.

## 3. Invariants → implémentation → tests → preuves

Les 17 méthodes nouvelles sont énumérées exactement dans le manifeste L1-F. Les 91 méthodes obligatoires historiques L1 sont toutes réexécutées au gate architecture, sans suppression ; ne pas additionner les répétitions des gates comme méthodes uniques.

| Invariant / garantie | Implémentation | Tests frais et preuve décisive |
|---|---|---|
| Résolution directe `[from,to)` à V, sans V−1 ni chaîne globale | PotHistoryRows.version/component, JdbcPotHistoryAdapter ; métadonnée par PK, courant par intervalles ouverts, identités nées à V, participants groupés | b02/b03/b04/b06/b12, finances et intégration E ; f01PointQueriesUseIndexesAndBoundedBuffersAcrossHistoryDepth ; SQL émis et `F-point-plans.txt` |
| Tous les anciens contrôles globaux restent exécutables | `verifyHistory(PotId)` en snapshot, `versions/checkedComponent` conservés dans les writers ; FK/checks/unicité inchangés | b09 déplacé explicitement entre anomalie à V et audit global ; f01MaintenanceAuditRetainsEveryGlobalChainCheck : saut local, naissance, absence d'ouverte, historique absent, trou global, identité sans versions |
| Pas d'hypothèse silencieuse d'intégrité | Point : absence/ambiguïté/bornes de ligne et références refusées ; audit : continuité/local/bornes globales ; WRITE : création/fermeture/insertion/transaction/CAS | Matrice exhaustive F0, b09/b10/b15/b17/b18/b19 ; anomalie étrangère à V laisse le point valide lisible mais l'auditeur échoue |
| INVALID_BINDING indépendant du Pot, Identity toujours protégée | ExecutePotCommand.prepare/finalize ; workingVersion sans signification Pot dans cette seule branche | f02 unitaire : history/currentVersion/protectPot interdits ; PG f02 sous verrou Pot et deux finaliseurs ; f02 Identity retenue ; c07 mutation entre phases et réévaluation ; c09/c22 régression |
| Payload structurel invalide ≠ rejet métier ≠ corruption durable | PotCommand.targets commun et copies immuables ; codec durable existant classifie InconsistentPotFactsException | PotCommandIntentTest (trois méthodes), PotCommandJsonTest ; PG f03 (zéro demande/slot/résultat à refus, trois corruptions techniques, trois cibles métier impossibles rejetées normalement) |
| Auto-rattachement au seul acteur, sans accorder des droits ordinaires | `AttachShareholderViaInvitation` pur, résultat map Details compatible avec le writer existant ; canMutate inchangé | InvitationAttachmentTest (trois méthodes) : extérieur/créateur non attaché, libre/occupé/supprimé/absent/déjà attaché, faits mixtes ; f04 deux utilisateurs/même cible et même utilisateur/deux cibles, CAS unique et histoire préservée |
| Contraintes B forcées avant suffixe, sans englober publication | SET CONSTRAINTS statique de 18 FK + quatre triggers, après flush et avant closePublication | f05 catalogue exact et f05 FK active_to invalide (23503, même classification TECHNICAL, état antérieur restauré, slot en reprise) ; c10/c11/c17/c18/c20 gardent rollback/poisoning/suffixe |
| Soldes exacts, reproductibles, somme zéro, aucun arrondi/cache | Deux passes sur les participations, facteur rationnel négatif amount/total calculé une fois par Expense ; Fraction inchangée | Six tests PotBalanceCalculatorTest avec oracle rationnel indépendant/permutation/>long, finances PG et E ; profilage apparié trois profils |
| CHANGED/UNCHANGED/REJECTED et événements corrects | WRITE inchangé, correspondance READ normée en SPEC-03 §10 | u02, c01/c09, e02/e03/e04 : UNCHANGED sans V/POT_EVENT, complétion unique ; READ réel non simulé |
| Pureté/direction/protocole owner conservés | Aucun POM/nouvelle dépendance/activation ni infrastructure cachée | Tous les gardes L0/L1, dont V3FoundationArchitectureTest et V3PotExecutionArchitectureTest, réexécutés au gate global |

### Lectures et coût historique

Le SQL historique sélectionne `active_from <= V AND (active_to IS NULL OR V < active_to)` et ajoute l'équivalent `int8range(...) @> V` pour exploiter GiST. Le courant sélectionne `active_to IS NULL`. Les huit index d'intervalles sont créés sans extension ; les index uniques partiels ouverts et les PK restent. La requête ne ramène que les révisions applicables et ne charge pas les composantes NotLoaded.

Banc d'un Pot à **100 / 1 000 / 10 000** révisions PotDetails, sans forcer le planner : respectivement **2 / 4 / 4 buffers** d'exécution pour la sélection historique, une ligne retournée ; index `v3_pot_details_interval` aux deux grandes profondeurs et `v3_pot_details_open` pour le courant. Les petits volumes peuvent légitimement utiliser un scan de table. Durées SQL et lectures de bout en bout, plans JSON complets et SQL réels dans les deux archives. Ce banc démontre le comportement des requêtes de ce profil, pas un SLO multi-Pots universel.

Les writers conservent les scans complets nécessaires à leurs contrôles initiaux ; leur coût avec la profondeur n'est pas optimisé par L1-F. `verifyHistory` n'est pas exécuté implicitement dans chaque lecture. Les FK/unicités seules ne sont jamais prétendues suffisantes pour toute continuité ; nouveaux writers et opérations de réparation doivent respecter le protocole et employer l'auditeur hors chemin critique.

### Contraintes : équivalence de la simplification

Ensemble exact : les FK `(pot_id,created_at)` des deux identités enfant ; `(pot_id,active_from)` et `(pot_id,active_to)` des huit tables de composantes ; `v3_version_has_effect`, `v3_pot_complete`, `v3_shareholder_complete`, `v3_expense_complete`. Les 22 sont DEFERRABLE INITIALLY DEFERRED. Le test conserve leurs définitions dans `F-constraint-catalog.txt`. Les FK/checks sont des prédicats d'intégrité ; les quatre fonctions de complétude lisent les lignes finales sans mutation. La conjonction est indépendante de l'ordre antérieurement non spécifié des noms découverts.

Un SET explicite remplace une requête pg_constraint/pg_class/pg_namespace + 22 SET séparés. `v3_command_unit`, `v3_result_unit`, `v3_pot_event_request` restent exclus et différés au commit physique. Aucune utilisation de ALL IMMEDIATE. Toute future contrainte métier différable doit faire évoluer la liste et le test d'égalité du catalogue avant livraison.

## 4. Campagnes et résultats exacts

Même H pour les deux campagnes, targets effacés avant chacune, compilation/XML frais, comparaison Git et modes avant/après, empreintes des classes/ressources produites, JARs/classpaths, JVM/Maven/configurations et image PostgreSQL stables. Les rapports XML attestent exactement une exécution PASS de chaque méthode obligatoire. Les seuls changements après H sont documentaires/probatoires ; le manifeste et le vérificateur historiques L1 sont conservés intacts.

| Campagne | Tests déclarés / failures / errors / skips | Obligatoires PASS | Verdict |
|---|---|---|---|
| COMMAND+EVENT+PROJECTION | **1306 / 0 / 0 / 1** | **100**, dont les 17 nouvelles | BUILD SUCCESS ; obligatoire PASS, couverture complète INCONCLUSIVE |
| Architecture globale et fermeture Maven | **1511 / 0 / 0 / 1** | **108**, dont les 17 nouvelles | BUILD SUCCESS ; obligatoire PASS, couverture complète INCONCLUSIVE |
| Diagnostic final ciblé historique/codec/exécution | **53 / 0 / 0 / 0** | Diagnostic intermédiaire, distinct du gate figé | PASS |
| Tests des collecteurs existants | **7 + 7 PASS** | Tests de summarize-v3 et de provenance L1 | PASS, ne remplacent pas les preuves métier |

**SKIP conservé, jamais compté PASS :** `TerminalSequencingPostgresTest.zExploratoryContentionSeparateFromCorrectness`, propriété `step3b.measure` absente. Aucun skip obligatoire L1 ou L1-F, aucun nouveau skip. Aucun test obligatoire FAIL dans les deux campagnes finales.

**FAIL / non-exécutions antérieurs conservés :** premier diagnostic PG sous sandbox, 2 cas d'initialisation / 0 failure / 2 errors / 0 skip, Docker inaccessible (aucune méthode métier exécutée) ; relance autorisée sur vrai Docker PASS. Une tentative d'autorisation antérieure n'a pas exécuté l'action, revue automatique indisponible sur limite d'usage puis reprise après demande du responsable. Le premier précontrôle consommateurs a refusé à juste titre le seul `.pyc` généré par notre diagnostic ; zéro exécution Maven, fichier propre à ce mandat retiré, relance entière sur H. Première mesure RSS sandbox refusée ; mesure système autorisée ensuite obtenue. Ces incidents ne sont pas effacés ni transformés en PASS.

Autres diagnostics intermédiaires : domaine avant 229/0/0/0 ; engine 352/0/0/0 ; unités 356 puis 359 puis 359/0/0/0 ; PG première relance 46/0/0/0 ; historique/codec 21/0/0/0. Ils sont détaillés dans [Diagnostics.json](../testing/evidence/L1-F/Diagnostics.json) et l'archive, sur leurs entrées intermédiaires ; ne pas additionner ces répétitions comme couverture unique finale.

Commandes canoniques **réellement exécutées depuis app/**, par le collecteur L1-F :

```bash
./mvnw -pl runtime-command-consumption-worker,runtime-event-consumption-worker,runtime-task-consumption-worker -am test
./mvnw -pl architecture-tests -am test
```

Commandes de diagnostics **réellement exécutées depuis la racine** :

```bash
./app/mvnw -f app/pom.xml -pl engine-write-pot -am test
./app/mvnw -f app/pom.xml -pl domain-pot -am test
./app/mvnw -f app/pom.xml -pl domain-pot,engine-write-pot -am test
./app/mvnw -f app/pom.xml -pl infra-persistence-primary-jpa -am test -Dtest=PotHistoryPostgresTest,CommandExecutionPostgresTest -Dsurefire.failIfNoSpecifiedTests=false
./app/mvnw -f app/pom.xml -pl infra-persistence-primary-jpa -am test -Dtest=PotHistoryPostgresTest,PotCommandJsonTest -Dsurefire.failIfNoSpecifiedTests=false
./app/mvnw -f app/pom.xml -pl infra-persistence-primary-jpa -am test -Dtest=PotHistoryPostgresTest,PotCommandJsonTest,CommandExecutionPostgresTest -Dsurefire.failIfNoSpecifiedTests=false
python3 -B -m unittest discover -s scripts/testing -p test_summarize_v3.py
python3 -B -m unittest discover -s scripts/testing -p test_l1_provenance.py
python3 -B scripts/testing/verify-v3-l1-f.py consumers
python3 -B scripts/testing/verify-v3-l1-f.py architecture
bash scripts/testing/profile-v3-l1-f.sh
bash -n scripts/testing/profile-v3-l1-f.sh
git diff --check
```

Les deux invocations Python de gate enveloppent les commandes canoniques, ce ne sont pas deux campagnes supplémentaires. Le profil utilise javac --release 21 et java -Xms256m -Xmx1g -XX:+UseSerialGC ; mesures supplémentaires par `/usr/bin/time -l java` avec les deux classpaths appariés. JFR initial `-XX:StartFlightRecording=...,settings=profile,dumponexit=true`, lecture `jfr print --events jdk.ObjectAllocationSample --stack-depth 8`. Formatage Java via le JAR google-java-format 1.24.0 déjà présent, avant H ; aucun outil/POM ajouté.

Environnement : **Temurin 21+35-LTS**, Maven **3.9.14**, PostgreSQL **17.11**, Docker **29.8.2**, macOS **27.0 aarch64**. Image postgres:17-alpine, digest **sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73**. Données exactes et configuration non secrète dans chaque environment.json.

## 5. Mesures rationnelles reproductibles

Source initiale du calculateur extraite depuis le SHA baseline, compilée séparément ; mêmes classes de valeurs et même harnais pour avant/après. Trois JVM indépendantes par variante, **8 échauffements puis 10 mesures par profil par JVM**, soit **30 échantillons** par ligne/variante, sans concurrence avec Maven. Seed **731** ; défauts rationnels déterministes, spécifiques pour un participant sur trois, chaque Expense contient tous les Shareholders, montant n/7. Pas de cache. Temps monotone du calcul pur ; octets cumulés sur le thread par ThreadMXBean, hors construction des entrées. Heap absolu MemoryMXBean après GC complet explicitement observé, entrée et dernier résultat gardés par racines fortes ; inclut le heap JVM, pas seulement le graphe métier.

Unités décimales : Mo = 1 000 000 octets. Médianes ; distributions brutes et empreintes dans [Performance.json](../testing/evidence/L1-F/Performance.json) et l'archive appariée.

| Profil S / E / participations | Calcul avant → après (ms) | Allocation cumulée avant → après (Mo) | Heap occupé avec état/résultat après GC avant → après (Mo) |
|---|---:|---:|---:|
| Petit 5 / 20 / 100 | **0,189 → 0,174** | **0,440 → 0,309** | **3,741 → 3,733** |
| Moyen 20 / 200 / 4 000 | **2,201 → 1,875** | **15,464 → 9,223** | **2,749 → 2,738** |
| Grand 100 / 2 000 / 200 000 | **138,584 → 95,252** | **768,208 → 427,887** | **18,657 → 18,635** |

Grand : environ **31 % de temps et 44 % d'allocations en moins** dans ce protocole. Le heap retenu reste pratiquement identique. Une mesure système supplémentaire par variante (une JVM parcourant les trois profils) donne un **pic RSS 157 843 456 → 160 464 896 octets** ; aucune réduction RSS n'est démontrée. Ce pic n'est pas attribué artificiellement à un profil individuel. L'observation Gemini ≈826 Mo/176 ms était indicative, pas un budget ni un résultat reproduit à l'identique.

JFR a localisé les allocations dans Fraction/BigInteger et les opérations par participation. L'optimisation reste deux passes simples : sommer les poids effectifs, puis appliquer le même facteur exact négatif amount/total, sans map de poids temporaire. Aucun changement de Fraction, arrondi, cache, état partagé ou algorithme financier différentiel. Les oracles indépendants, sommes zéro, permutations, fractions >long et histoires passent après optimisation.

**Limites :** mesure locale de calcul pur, pas de coût lecture/écriture complet, de p95 E2E, ni de profil arbitraire de grands entiers/dénominateurs. Une première exploration à poids hétérogènes par Expense a été interrompue avant résultat Grand ; elle est archivée et exclue du bilan, jamais présentée comme PASS. Aucun défaut majeur de complexité ou de mémoire démontré dans les trois profils achevés ; variabilité des données, GC, index/WAL et très longs historiques restent à qualifier à L6.

## 6. Réalisations, reports et entrée L2

Neuf fichiers de production modifiés/ajoutés dans trois modules et la migration d'index : nouveau domaine pur AttachShareholderViaInvitation et calculateur ; constructeurs PotCommand et ExecutePotCommand ; quatre classes JDBC ; V3__point_history_indexes.sql. Sept fichiers Java de test modifiés/ajoutés, deux scripts de mesure/collecte et le manifeste L1-F. SPEC-01/03/04/05/06/09/10, contrats B/C, canon et plan directeur consolidés. [Inventaire intégral des fichiers](../testing/evidence/L1-F/Changed-files.json), y compris preuves, fait autorité pour la liste ; aucun fichier préexistant hors mandat publié.

**Non exécutés / non livrés, et non requis comme preuve L1-F interne :** endpoints HTTP/Bruno, matérialisations READ et neuf Consumptions réelles, fournisseur Identity, consentement OAuth/RAR, Discovery/acquisition génériques, k6/SLO/reconstruction READ, full reactor. Les tests de fixture sont nommés comme tels ; ils ne deviennent pas ces garanties.

**Invitation à L5 :** créer uniquement par créateur, conserver hash et lien récupérable protégé, restituer via CurrentInvitation au seul creatorEui, bénéficiaire authentifié et Binding actif, liste libre sans réservation, intention durable dédiée sans observedVersion imposée, token/expiration/Pot/cible/absence de rattachement revalidés atomiquement et protégés en WRITE, writer Details + nouvelle V + POT_EVENT + complétion. Le même lien doit réussir pour plusieurs bénéficiaires distincts tant qu'il n'est pas expiré ; un utilisateur déjà rattaché est refusé. Ajouter le raccord du domaine pur au codec/admission/exécuteur, pas une permission artificielle canMutate. **Ces garanties complètes, dont le POT_EVENT d'un vrai rattachement et l'expiration concurrente, restent à prouver ; INV-01 est globalement INCONCLUSIVE.**

**Prêt pour le cadrage L2, sans son implémentation.** Conditions : plan directeur revu, plan L2 conçu/audité/validé puis autorisation distincte ; reprendre SPEC-05 §11 et le contrat C corrigé. Unifier v3_command_slot(request_id) avec ConsumptionSlot(consumptionId,consumableId), inventaire des writers/états existants et bascule sans deux moteurs ; préserver tous les claims/fences/échecs/quarantaines/complétions et l'observation immuable. Réexécuter le pilote Command sur Pot réel et un pilote READ indépendant avec leurs autorités physiques. Les fixtures Identity restent explicitement des fixtures jusqu'à L3. Aucun lancement de L2, nettoyage V2, nouvelle projection ni empaquetage final dans ce mandat.

## 7. Livraison Git

| Commit créé | Contenu |
|---|---|
| `2d461959d9503ec2c03beb8e3804d6009d958b07` | docs(v3): inventory L1-F audit findings and verification boundaries |
| `1deb04e95618a3b91140a27d43593f8d2c5a448f` | fix(v3): correct L1 point reads, authority isolation and intention invariants |
| `049e51f48fe0d91d11201606dba6cfe55083a73b` | test(v3): prove L1-F corrections, contention and exact balance costs |
| `c94e7a4bec479ccbf22b058fd341ae0350c2ca67` | docs(v3): consolidate post-audit contracts and L2 prerequisites |

Le commit documentaire/probatoire portant ce rapport clôt la livraison ; son identifiant est résolu par Git comme indiqué au §1. Avant publication : diff et fichiers non suivis inventoriés, sources exécutables identiques à H, archives vérifiées, tête distante contrôlée ; publication uniquement sur feat/v3-architecture, sans force. Le constat distant exact accompagne la livraison.
