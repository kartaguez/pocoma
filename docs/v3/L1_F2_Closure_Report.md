# L1-F2 — Clôture corrective des écritures versionnées

2026-10-25. **L1-F2 CLOSED / PASS — périmètre interne WRITE vérifié.** Les chemins WRITE concernés ne reconstruisent plus de chaînes historiques ; les garanties locales, transactionnelles et concurrentes applicables sont démontrées. L1/L1-F conservent leurs verdicts et preuves historiques. **Aucune certification V3 globale ; L2 non commencé.** Le dossier est publiable pour revue indépendante avant entrée en L2 ; cette autoévaluation technique ne prétend pas être une acceptation humaine indépendante.

## 1. Références et provenance

- Baseline, HEAD distant initial et dernier contrôle avant publication : **`b3df3f3e619eeed6fc551177c778b412daa4fa4b`**.
- Inventaire contradictoire avant tout code : **`d1333628`** ; harnais et matrice : **`3544898353076d6839bd6f78122d52a5932a64b8`**. La production de ce deuxième SHA est strictement identique à la baseline ; mesure avant reproduite sur ce checkout propre.
- Implémentation, tests, manifeste et collecteur figés : **`e154276aa4f89a6619893e8d27150a8b2442d718`** (H). Gate frais et mesure après sur H ; aucune modification de `app/` ou `scripts/` après H.
- Le commit de livraison ajoute uniquement documentation et preuves. Son SHA est donné dans le compte rendu de publication et se retrouve durablement par `git log -1 --format=%H -- docs/v3/L1_F2_Closure_Report.md`, sans inventer un SHA autoréférent. [Concordance des entrées](../testing/evidence/L1-F2/Publication-provenance.json).
- [Inventaire F2-0 et modèle](L1_F2_Contradictory_Inventory.md), [matrice exhaustive des protections](L1_F2_Invariant_Matrix.md), [manifeste fixé avant gate](L1_F2_Proof_Manifest.json), [index des archives](../testing/evidence/L1-F2/Gate-index.json), [reproduction](../testing/evidence/L1-F2/README.md).

Travail isolé `/private/tmp/pocoma-v3-l1-f2`, HEAD détaché de la référence distante pour préserver la branche locale V3 occupée à ddca2827 et ses dix fichiers indexés. Le checkout utilisateur reste `feat/runtime-monolith` à 90a3e9bc, avec ses modifications préexistantes. Publication ciblée `HEAD:refs/heads/feat/v3-architecture`, sans force. Le checkout propre avant mesure est `/private/tmp/pocoma-v3-l1-f2-before`. Aucun reset, stash, clean ou déplacement de branche préexistante. [États préservés](../testing/evidence/L1-F2/Preservation.json).

## 2. Portée déclarée et réalisation

**COMMAND primaire ; secondaires NONE.** Impact prévu/réel : `infra-persistence-primary-jpa`, préconditions JDBC et index CURRENT V3. Domaine, engine, ports, dépendances, modules et protocole physique inchangés. Franchissement inattendu : **aucun**. La fermeture Maven compile/teste aussi ses dépendances ; ce n'est pas un changement de production dans leurs capacités.

**Architecture globale NOT REQUIRED / non exécutée** : aucune frontière/port/dépendance affectée. **Full reactor NOT REQUIRED / non exécuté** : complément correctif borné, ni clôture majeure ni intégration main. Aucun `-amd`, aucun `./mvnw test` nu. Prochain gate global selon le plan L2 puis intégration.

DB **CURRENT** : PostgreSQL réel 17.11, schémas neufs ; B V1+V3+V4, C V1+V2+V3+V4 par lieux Flyway explicites. V4 ajoute seulement des index, aucune migration historique modifiée. Les suites préexistantes transitives du gate non filtré exécutent également PRIMARY V1–V31 et READ V1–V14 ; aucune campagne HISTORICAL séparée ni baseline V2 TRUSTED supposée.

## 3. Inventaire des anciens parcours et substitution

| Ancien chemin | Coût artificiel | Accès après |
|---|---|---|
| recordVersion | Toutes les métadonnées 1..V et chaîne PotHeader | Dernière métadonnée par PK inverse LIMIT1 ; Header ouvert et clés locales bornées |
| proposal, chaque création/révision | Métadonnées 1..candidate à nouveau | Dernière candidate et égalité writer_xid=pg_current_xact_id() en une requête |
| check de chaque composante | Toutes ses révisions, reconstruction/validation de chaîne | Ouverte, première locale1, prédécesseur immédiat et dernière locale ; contrôle de jonction/bornes/naissance et d'un chevauchement avec l'ouverte |
| ShareholderDetails / Weights multi-cibles | Une lecture exhaustive par cible | Identités groupées et une requête de préconditions pour le lot ; toutes validations avant close/insert |
| FinancialReferences | Chaînes Header de tous les payeurs/participants référencés | Identités et Headers ouverts groupés, mêmes préconditions locales et refus des références supprimées/invalides |
| changedComponents / trigger version_has_effect | Filtre exact ne transférant pas l'histoire, mais sans index adapté `(pot_id,active_from)` | Huit index B-tree de V4 sélectionnent directement les effets de candidate |

`createPot` et créations multiples conservent locales1, naissance/candidate et contraintes de complétude ; aucune chaîne préalable nécessaire. Les autres créations sont protégées par PK/FK et transaction. Suppressions restent Header seul ; composantes non chargées et non modifiées ne sont pas réécrites. Les méthodes historiques `versions` et `checkedComponent` n'ont plus que `verifyHistory` comme appelant externe au helper. Lectures ponctuelles, ouverte WRITE, mutation et audit restent distincts.

**SQL/index :** dernières versions `(pot_id,version)` ; identités `(pot_id,object_id)` ; ouvertes par UNIQUE partiel ; première/prédécesseur/dernière locales par UNIQUE `(pot_id,object_id,local_version)` ; chevauchement avec l'ouverte via GiST int8range existant ; fermeture exacte par Pot/objet/revision_id/locale/active_from/ouverte, une ligne obligatoire ; effets par les huit index V4 `(pot_id,active_from)`. Aucune extension, cache, réparation, nouvelle couche ou procédure SQL de reconstruction. Les index de performance ne sont pas présentés comme contraintes d'intégrité.

## 4. Invariants et revue contradictoire

La [matrice F2-2](L1_F2_Invariant_Matrix.md) couvre explicitement continuités globales/locales, naissance, bornes, chevauchement, unicité/existence de l'ouverte, identités/références, effet global, complétude, fermeture/insertion, rollback, concurrence, claim/Identity/Pot et publication atomique. Pour chaque ligne : domaine, protocole WRITE, contraintes PostgreSQL et pouvoir exact de l'audit.

Induction locale : création complète à locale1 ; ouverte = dernière, premier point à naissance, prédécesseur N−1 fermé exactement à son début, aucun autre intervalle ne la chevauche ; close CAS sur cette révision précise puis nouvelle locale N+1 à candidate dans la même unité. Une ancienne corruption étrangère à ces limites peut être laissée au vérificateur historique ; la mutation ne la répare pas. Une corruption de l'ouverte, de sa jonction, naissance ou identité bloque WRITE.

Relecture du diff et des appels réels : aucun appel indirect d'audit depuis ExecutePotCommand/CommandStore/writers ; pas de transfert historique dissimulé ; préconditions batch groupées ; mêmes guards/poisoning ; même ordre claim→Identity→Pot ; mêmes contrôles avant suffixe fermé. Les triggers différés et la comparaison des huit composantes publiées sont inchangés. `verifyHistory` vérifie les chaînes, pas toutes les sémantiques de payloads ni les événements ; ne pas lui attribuer les garanties propres aux FK, lecteurs complets et tests C/E.

## 5. Tests et concurrence PostgreSQL

| Campagne | Tests / failures / errors / skips | Verdict et portée |
|---|---|---|
| Gate COMMAND propre H | **1265 / 0 / 0 / 1** | BUILD SUCCESS ; **107 méthodes obligatoires PASS**, aucune absente ; transferts bornés et plans locaux PASS ; couverture complète **INCONCLUSIVE** |
| Diagnostic B/C/finances + F2 initial | 63 / 0 / 0 / 0 | PASS intermédiaire, ne remplace pas le gate |
| Diagnostic six F2 finaux | 6 / 0 / 0 / 0 | PASS intermédiaire incluant index |
| Mesure avant propre 35448983 | 1 / 0 / 0 / 0 | PASS, même harnais source et cinq classes compilées identiques à après |
| Collecteurs réutilisés | 7 + 7 PASS | Tests d'évaluation XML/provenance ; distincts du métier |

Le gate réexécute notamment 26 unités domaine V3, 10 unités engine V3, 70 tests du package persistance V3 (dont 69 PostgreSQL et un codec), plus la composition explicite PostgreSQL. Il couvre créations Pot/Shareholders/Expense, Details/Weights/Financials, suppressions, UNCHANGED/REJECTED, anciennes versions et NotLoaded, avec oracles littéraux B et unités C/E.

Nouveaux tests : f201 profondeur ; f202 huit corruptions locales (ouverte manquante/dupliquée, locale, prédécesseur, naissance/bornes/futur/chevauchement) avec état durable complet identique après rollback ; f203 ancien trou étranger : WRITE V7 réussit, audit refuse ; f204 quatre identités/références invalides bloquent atomiquement ; f205 une requête groupée deux cibles, aucune composante Expense non chargée ; f206 deux connexions ferment la même révision, attente réelle observée par pg_blocking_pids, première fermeture=1, seconde=0, un successeur complet auditable ; f207 effet indexé comparé sans index, sans réglage du planner.

Les 31 méthodes C conservent les preuves déterministes : c02 deux propositions même Pot, collision puis retry/obsolescence ; c03 dépendances compatibles ; c04 huit courses référence/suppression ; c07 Identity ; c09 deux finaliseurs, une publication ; c10/c11 coupures et contraintes différées, rollback intégral ; c13/c23 vrais commits réseau incertains ; c14/c15 horloge après verrou et takeover ; c16 publication dense ; c17/c18/c20 owner physique/poisoning ; c22 Identity tenue jusqu'au commit. Les chronologies et états SQL indépendants sont dans l'archive. La course f206 est une fixture SQL ciblée, pas un remplacement du protocole C réellement testé.

**SKIP conservé :** TerminalSequencingPostgresTest.zExploratoryContentionSeparateFromCorrectness, propriété exploratoire step3b.measure absente. Il n'est jamais PASS ; aucune obligation F2 ignorée. **Échecs diagnostiques conservés :** initialisation Docker refusée sous sandbox avant méthode métier, relance autorisée PASS ; complément EXPLAIN initial exigeant à tort un index à 100 lignes, corrigé uniquement dans ce harnais de preuve pour accepter le Seq Scan légitime. Aucun échec de production masqué, aucune modification H pour cela.

Commandes réellement exécutées depuis la racine, variantes diagnostiques détaillées dans les logs :

```bash
./app/mvnw -f app/pom.xml -pl infra-persistence-primary-jpa -am test -Dtest=WriteRevisionPerformancePostgresTest -Dsurefire.failIfNoSpecifiedTests=false -Df2.variant=before
./app/mvnw -f app/pom.xml -pl infra-persistence-primary-jpa -am test -Dtest=WriteRevisionPerformancePostgresTest -Dsurefire.failIfNoSpecifiedTests=false -Df2.variant=after
./app/mvnw -f app/pom.xml -pl infra-persistence-primary-jpa -am test -Dtest=WriteRevisionPostgresTest,PotHistoryPostgresTest,CommandExecutionPostgresTest,PotFinancePostgresTest -Dsurefire.failIfNoSpecifiedTests=false
./app/mvnw -f app/pom.xml -pl infra-persistence-primary-jpa -am test -Dtest=WriteRevisionPostgresTest -Dsurefire.failIfNoSpecifiedTests=false
python3 -B -m unittest discover -s scripts/testing -p test_summarize_v3.py
python3 -B -m unittest discover -s scripts/testing -p test_l1_provenance.py
python3 -B scripts/testing/verify-v3-l1-f2.py command
python3 -B docs/testing/evidence/L1-F2/reproduce-close-plans.py
git diff --check
```

Le collecteur exécute réellement depuis `app/` la commande canonique **`./mvnw -pl runtime-command-consumption-worker -am test`** ; ce n'est pas une campagne supplémentaire. Formatage des deux sources modifiées et du nouveau test de correction par google-java-format 1.24.0 avant H ; le harnais apparié est inchangé. Le complément fermeur compile ClosePlans.java via javac --release21 puis java avec les classes/JARs du gate ; chaque diagnostic est annulé.

## 6. Mesure de profondeur avant/après

Même machine, **Temurin21+35-LTS, Maven3.9.14, PostgreSQL17.11, Docker29.8.2, macOS27.0 aarch64**. Image postgres:17-alpine, digest **sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73**. 110 JARs utilisés avant ont les mêmes empreintes qu'après. Aucun Maven concurrent pendant les mesures ; harnais réel ExecutePotCommand, autorité/acquisition initiales explicitement fixtures.

Chaque profil commence à N versions ; cinq échauffements puis dix modifications PotDetails réellement committées. Les dix versions initiales mesurées sont N+5…N+14, et non dix essais faussement attribués à une profondeur exactement inchangée. Même croissance avant/après. Un Pot, trois Shareholders fixes, une seule composante modifiée. Fixtures et admission/acquisition hors mutation ; chronomètre monotone, préparation snapshot et finalisation incluant commit physique. Compteur d'exécutions SQL réel, lignes effectivement consommées par ResultSet.next ; aucune estimation à partir du nombre de requêtes.

Médianes ; millisecondes, avant → après. [Distributions brutes et plans](../testing/evidence/L1-F2/README.md).

| N | Préparation | Finalisation | Mutation totale | SQL | Lignes Java |
|---|---:|---:|---:|---:|---:|
| 100 | 10,072 → 9,692 | 19,780 → 18,885 | 29,759 → 28,728 | 44 → 43 | 354,5 → **28** |
| 1 000 | 9,837 → 10,102 | 20,883 → 19,624 | 30,631 → 29,664 | 44 → 43 | 3 054,5 → **28** |
| 10 000 | 9,969 → 9,890 | 39,215 → 19,039 | 49,045 → 29,462 | 44 → 43 | 30 054,5 → **28** |

Lignes avant : plages 341–368 / 3041–3068 / 30041–30068 ; après **28 pour chacune des 30 observations**. Les médianes fractionnaires viennent de dix comptages entiers. Construction des fixtures, séparée : 636/354/3237 ms avant, 302/331/2837 ms après ; ce n'est pas une amélioration métier revendiquée.

Préconditions locales PotDetails : une ligne retournée ; **21 buffers d'exécution** aux profondeurs 1 000 et 10 000, accès ouvert, clés locales et GiST. À faible volume, le planner peut légitimement scanner une petite table. Effet exact à V=10 000 : index V4 **3 buffers**, sans cet index **143 buffers / 9999 lignes filtrées** ; prédicat de publication et d'effet inchangé. Fermeture exacte nouvelle : une ligne trouvée, scan séquentiel à100 (2 buffers de sélection), index ouvert aux profondeurs1000/10000 (2 buffers de sélection) ; UPDATE complet **26/32/35 buffers**, incluant entretien des index. Plans complets ANALYZE BUFFERS conservés, sans forçage d'index. Les buffers sont par requête/DML expliquée ; aucun total artificiel de transaction n'est calculé en additionnant des plans indépendants.

**Critère central PASS :** plus aucun volume d'histoire croissant transféré vers Java pour cette mutation équivalente. La baisse temporelle à10k est observée, pas un contrat de temps constant absolu. Index, connexion, commit, contention, cardinalité de la population métier et environnement restent variables. Aucun SLO/API/READ ni campagne PERF-01 globale certifiée.

## 7. Fichiers, commits, réserves et entrée L2

Commits : d1333628 inventaire préalable ; 35448983 matrice/harnais avant ; e154276a préconditions/index/tests/contrats/collecteur ; commit documentaire de livraison de ce rapport/preuves. Liste exacte et exhaustive : [Changed-files.json](../testing/evidence/L1-F2/Changed-files.json). Production : JdbcPotRevisionAdapter, PotHistoryRows et V4__write_effect_indexes.sql. Tests : PotHistoryPostgresTest (catalogue V4), WriteRevisionPostgresTest, WriteRevisionPerformancePostgresTest. Outillage : verify-v3-l1-f2.py. Documents : les quatre livrables F2, contrats B/C, canon, plan directeur, SPEC-01/03/05/09/10, preuves dans evidence/L1-F2. Aucun fichier historique de preuve L1/L1-F altéré.

Opérations restant volontairement proportionnelles : `verifyHistory` à toutes versions/révisions, reconstruction complète à la population d'objets à V, autorisation/usage exhaustif aux composantes courantes requises ; ce sont des nécessités explicites, pas des scans de préconditions WRITE cachés. Pas de nouveau défaut d'implémentation historique identifié dans les chemins concernés. Les mesures restent un profil interne, pas une enveloppe universelle multi-Pots ni une preuve de tous les coûts PostgreSQL.

**L1-F2 CLOSED / PASS interne.** Dossier à auditer indépendamment avant validation du prochain lot. L2 reste DESIGN PENDING : plan de conception vérifié et autorisation explicite nécessaires. Il devra préserver préconditions locales groupées, close exact+insert atomiques, audit explicite, claim/Identity/Pot, observation immuable, CAS et reprise complète, xid8/poisoning, contraintes avant suffixe, événements/résultats/slot atomiques et publication dense. Aucune implémentation L2, aucune nouvelle acceptation globale V3 ou activation.
