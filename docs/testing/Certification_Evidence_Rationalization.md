# V3 — rationalisation des certifications et conservation des preuves

Mandat humain du 29 octobre 2026 ; dépôt `kartaguez/pocoma`, branche
`feat/v3-architecture`, référence initiale
`9a284bcd765ff1ec4f21d7971cfc65214a1c77de`.
Ce dossier unique porte la décision de conservation et le compte rendu du chantier.
L'[inventaire exhaustif](Evidence_Inventory.json) porte les listes exactes, tailles,
empreintes, campagnes, métadonnées de verdict/SHA, références entrantes et décisions
fichier par fichier. Ce n'est pas un manifeste de nouveaux résultats applicatifs.

## Décision humaine I5

**I5 — CLOSED BY HUMAN ACCEPTANCE WITH AVAILABILITY RESERVE.** Le présent mandat
accepte explicitement la réserve temporelle et de disponibilité et autorise la
poursuite **I6/I7**. Il ne transforme aucun résultat historique en PASS.

- **AUD-I5-01 : garantie temporelle non certifiée.**
- **R-AUD-I3-03 : réserve non levée techniquement.**
- Aucun nouveau défaut de sûreté productif démontré par la dernière tentative ;
  cette absence de démonstration ne certifie pas la disponibilité.
- Configuration **30 secondes NON CERTIFIÉE** ; aucun certificat global 90s acquis.
- **Aucune homologation production.** Suspension, retrait local, drainage
  conservateur et reprise potentiellement non bornée restent des limites acceptées.
- L2 global reste **INCONCLUSIVE** ; G1/G2/G3 et les obligations reportées conservent
  leurs exigences propres. Le GO I6/I7 ne les franchit pas.

| Référence de décision | SHA / preuve conservée |
|---|---|
| Publication initiale examinée | `9a284bcd765ff1ec4f21d7971cfc65214a1c77de` |
| Gel de la dernière tentative | `a2133b6b43a74999374bc26a222f70c7500fadda` |
| Gel v2 historique | `5eab2e94379ef40c3f9ff54c14a4d04a8f13dcbd` |
| Qualification I5 initiale, de portée distincte | `9a315e16567042d0b9bd017486d9043216b3ecd7` |
| Dernier constat technique | [Rapport final historique](../v3/L2_I5_Temporal_Final_Report.md) : FAIL du banc, conclusion INCONCLUSIVE |
| Contre-exemple de fermeture | [FINAL-W2-PREPARATION-LIFETIME-01](evidence/L2-I5-temporal-final/counterexample.json) |
| Métadonnées de dernière qualification | [qualification.json](evidence/L2-I5-temporal-final/qualification.json) |

Les rapports, manifestes de campagne, reçus, contre-exemples et contrats historiques
restent inchangés. Seuls les points d'entrée courants enregistrent cette décision.

## Vérification déclarée avant intervention

Impact production attendu et observé : **NONE**. Slices Maven : **NONE** ; portée
DOCUMENTATION / GIT / STORAGE-SCRIPTS. Impact interdit : runtime, tests/oracles
applicatifs, POM, SQL, pools, algorithmes de renewal et invariants métier.
DB : **NONE**. Gate architecture globale : **NOT REQUIRED / NOT RUN**.
Full reactor : **NOT REQUIRED / NOT RUN**, car aucune frontière applicative,
intégration main ou certification de release n'est effectuée.
Franchissement inattendu : **NONE**. Escalade préalable requise si l'un de ces
périmètres doit changer. Aucun test PostgreSQL, gate I5 ni migration relancé.

## Dispositifs examinés et simplification

L0 utilise le manifeste foundation, les oracles de transaction/isolation et le
collecteur `summarize-v3.py`. L1 utilise les manifestes de méthodes et variantes,
les contrôles de provenance `verify-v3-l1.py`, les reçus A/B/C/D/E et les corrections
F/F2. L2 ajoute les manifestes I1–I5, les collecteurs dédiés, les traces physiques
et le catalogue de 380 allocations/253 identifiants. Le manifeste de couverture
L2 est prospectif : ses 324 obligations L2 et 56 reports restent inchangés.
Les scripts de campagne historiques restent reproductibles au SHA de leur gel.

La [matrice des gates futures](Reactor_Verification_Policy.md#34-proportionate-certification-and-evidence-reuse)
applique le graphe Maven existant et les conditions globales de la politique.
Une invocation multi-ancres `-pl a,b -am test` partage une seule fermeture Maven :
les modules communs n'y sont pas réexécutés pour chaque ancre. Elle ne prouve pas
l'isolation de deux runtimes quand le contrat réclame deux exécutions distinctes.
Ni retrait de méthodes obligatoires, ni sélection filtrée présentée comme gate
non filtrée, ni suppression d'une gate explicitement exigée par un contrat de lot.
Les gates historiques restent les commandes réellement exécutées.

**SPEC-10 : clarification procédurale seulement.** Aucun oracle, scénario,
précondition, seuil, exigence PostgreSQL réel, variante, allocation ou critère PASS
ne change. Modifier l'une de ces normes ou dispenser une gate contractuelle reste
un arbitrage humain explicite ; le présent chantier ne l'autorise pas.

## Réutilisation des preuves

Pour ce delta de stockage/documentation, aucune hypothèse d'une preuve applicative
n'est modifiée : sources, tests/oracles, SQL, dépendances et composition `app/`
restent identiques à la référence initiale. Toutes les campagnes L0/L1/L2 sont
consultées comme **observations historiques sur leur SHA**, avec leur portée et
leurs réserves. Aucun nouveau PASS ni baseline applicative n'en découle.

La réutilisation déjà décidée des 15 déterministes v2 et de W1 reste exactement
celle de [reuse.json](evidence/L2-I5-temporal-final/reuse.json) : PASS historique
borné, aucune certification W1 v3 inventée. Les campagnes I5 remplacées ne
contribuent pas au verdict du gel final ; les FAIL v1/v2/final, UNKNOWN et gates
NOT RUN sont conservés. I6/I7 devront comparer leurs propres entrées et hypothèses,
identifier les scénarios affectés et exécuter leurs nouvelles preuves pertinentes.

Une réutilisation future exige : SHA testé, artefact intègre et accessible,
contrat/oracle identiques, dépendances et inputs sémantiques identifiés (sources,
tests, SQL, configuration, JDK/Maven/PG/image si pertinents), analyse du delta,
portée bornée et réserves explicites. Une hypothèse modifiée ou non vérifiable
rend la preuve non réutilisable pour le delta ; elle ne devient pas PASS par
absence de résultat. Réutiliser une observation ne remplace pas une exécution
fraîche expressément obligatoire à la release ou au jalon.

## Conservation et catégories

| Catégorie | Décision et contenu |
|---|---|
| A — Git | Tests/oracles, contrats, rapports/décisions, manifestes/reçus compacts, SHA-256, agrégats, contre-exemples minimaux et références d'archives. Leur contenu est conservé. |
| B — externe vérifié | **AUCUN** : aucun stockage dont existence, accès, intégrité et rétention soient vérifiés dans ce mandat. Volume externalisé : **0 octet**. |
| C — enveloppe redondante | Seulement les gros gzip dont le flux TAR intégral et l'enveloppe gzip exacte sont reconstructibles depuis le XZ conservé, vérification SHA-256 réalisée avant suppression. Chaque entrée donne motif, ancienne/nouvelle taille et empreintes. Aucun membre de preuve supprimé. |
| D — conserver en attente | Archives brutes, logs, XML et diagnostics uniques sans stockage durable externe vérifié, dont les remplacements XZ. Une mention SUPERSEDED/DIAGNOSTIC ne suffit jamais à supprimer une observation unique. |

Les catégories sont conservatrices : une petite preuve brute peut rester en D
plutôt que de décider prématurément qu'elle est dispensable. Les documents de
suivi modifiés ont leur empreinte avant et après dans l'inventaire ; aucun rapport
historique n'est réécrit. Les autres doublons exacts sont recensés, conservés tant
que leur retrait compliquerait les reçus et les liens existants.

Les nouvelles campagnes collectent leurs bruts dans un répertoire dédié hors
checkout (destination déjà acceptée par les collecteurs), conservent les agrégats,
reçus, commandes/SHA et contre-exemples dans Git et appliquent le transfert vérifié
ci-dessous. En l'absence de destination externe validée, conserver les bruts
indispensables dans Git sous compression sans perte, avec inventaire. Les logs
exploratoires et copies temporaires ne deviennent pas des preuves officielles.
Aucun changement de collecteur ne peut réduire les traces exigées par son oracle.

## Résolution et restauration des anciens chemins

Le mécanisme prolonge `Raw-artifact-encoding.json` déjà présent : il consigne un
encodage, sans redéfinir la certification. Les gros TAR gzip sans lien Markdown
historique direct sont réencodés en XZ preset 3. **Le flux TAR complet est identique**,
ainsi que noms, contenus, dates, modes, XML, logs et reçus embarqués. L'en-tête gzip,
la bande-annonce, le niveau zlib et les empreintes permettent aussi de reconstruire
**les octets gzip originaux**, ce qui conserve les SHA des manifestes historiques.
Les archives liées directement dans les rapports restent au chemin original.

Depuis la racine du dépôt :

```sh
python3 scripts/testing/evidence-storage.py resolve docs/testing/evidence/L2-I5/A-9a315e16.tar.gz
python3 scripts/testing/evidence-storage.py restore docs/testing/evidence/L2-I5/A-9a315e16.tar.gz --destination /private/tmp/A-9a315e16-restored.tar.gz
python3 scripts/testing/evidence-storage.py check
```

`resolve` indique le fichier courant (TAR lisible par `tarfile.open` en autodétection).
`restore` exige une destination inexistante et vérifie TAR, XZ et gzip historique ;
les scripts historiques exigeant le nom/enveloppe gzip s'utilisent sur une copie
restaurée ou dans leur checkout de gel. Le reçu ancien ne devient pas l'empreinte
XZ : les deux identités sont distinctes. Le contrôle refuse les corruptions,
les reconstructions non exactes et l'écrasement de fichiers existants.
Le blob gzip initial reste également consultable par `git show <SHA initial>:<chemin>` ;
ceci est une référence Git conservée, **pas un stockage externe**.

Une évolution de zlib peut rendre la reconstruction exacte indisponible ; le
contrôle l'annonce et refuse le fichier, sans accepter un hash différent. Le TAR
intégral reste auditable sans zlib et le blob historique fournit alors l'enveloppe.
La version utilisée est consignée pour chaque transformation.

## Migration externe restant à décider

1. Désigner un propriétaire et un stockage durable déjà provisionné (objet immuable
   ou équivalent), avec accès audit en lecture et politique de rétention approuvée.
2. Fixer une rétention couvrant le programme, ses audits et les releases qui citent
   ces preuves ; conserver sans expiration automatique les FAIL/UNKNOWN/décisions
   tant qu'aucun contre-exemple minimal suffisant n'est accepté. La durée exacte
   relève du responsable ; aucune durée de service n'est présumée.
3. Copier les archives de D, enregistrer URI réelle, taille, version d'objet,
   SHA-256, propriétaire et date de fin de rétention ; vérifier les droits réels.
4. Télécharger indépendamment chaque objet, recomparer SHA-256 et flux TAR,
   tester une restauration et vérifier la configuration effective de rétention.
5. Passer D→B et publier les références seulement après ces quatre contrôles,
   puis retirer les bruts du HEAD dans une livraison distincte conservant les reçus.

Aucune suppression irréversible, réécriture d'historique, GC destructive,
suppression de tag ou force push ne fait partie de ce chantier.

## Mesures et contrôles de livraison

Les mesures sont exprimées en octets. `bytes` est la taille logique du blob Git du
fichier suivi ; `allocated_bytes` mesure ses blocs locaux ; `gzip9_bytes` est une
compression indépendante par fichier (elle n'est pas le poids d'un pack Git).
Le périmètre large inclut toute la documentation afin de ne pas omettre les
preuves anciennes ; `docs/testing/evidence/` est aussi mesuré séparément.
Les empreintes de membres d'archives servent à mesurer les doublons exacts ;
la ressemblance entre deux logs de campagnes différentes n'autorise aucun retrait.

| Mesure | Avant | Après / décision |
|---|---:|---:|
| Preuves sous `docs/testing/evidence/` | 1 249 172 463 | 737 970 498 |
| Périmètre large des 745 artefacts initiaux | 1 263 567 494 | 752 374 731, avant ajout du présent dossier/inventaire/outils |
| Enveloppes gzip concernées (37 fichiers) | 1 151 793 785 | 640 591 820 en XZ |
| Gain brut du HEAD sur les archives | — | **511 201 965** (40,92 % des preuves de ce répertoire) |
| Externalisation | 0 | **0** |
| Membres de preuve supprimés | 0 | **0** |
| Gzip retirés / XZ conservés | — | **37 / 37**, liste exacte dans l'inventaire |
| Catégories des artefacts initiaux | — | A503 / B0 / C37 / D205 ; les 37 remplacements sont D en plus |

Les nouveaux documents/outils occupent environ 1 Mo : le gain net de l'ensemble
du HEAD est donc légèrement inférieur au gain brut des archives. Le bilan exact
du tree publié est donné dans le reçu de livraison ci-dessous.
Les consommateurs principaux deviennent I5 **533 034 251** (avant 954 150 182),
I4 **132 061 660** (avant 207 553 722), remédiation I5 **20 237 117**
(avant 34 831 089). Les six groupes de doublons de fichiers hors archives et
408 groupes de membres identiques (24 626 124 octets décompressés redondants)
sont recensés mais conservés. Les logs physiques dominants se ressemblent souvent
sans être identiques : PID, horloges, interleavings et observations diffèrent.

L'historique initial accessible depuis la branche contient **8 393 blobs**,
**1 343 003 323 octets décompressés** ; les représentations disque des objets
accessibles totalisent **1 174 934 024 octets**. Le magasin physique partagé avec
les autres branches/worktrees mesure **1 177 324 634 octets** (mesure locale,
non attribuable exclusivement à cette branche). Hors blobs présents dans le tree
initial : **6 342 blobs / 73 831 981 octets**. Ces mesures sont distinctes du
volume logique des fichiers du HEAD et des tailles gzip de l'inventaire.
**Tous ces objets historiques restent présents et accessibles** ; les nouveaux
XZ ajoutent 640 591 820 octets de payload de blobs à l'histoire, avant compression
Git. La taille d'un clone complet et du magasin Git peut augmenter : aucune
réduction historique ou économie de clone complet n'est revendiquée.

Commandes exécutées pour cette livraison, depuis la racine sauf indication :

```sh
python3 -B -m unittest scripts/testing/test_evidence_storage.py
python3 -B -m unittest scripts/testing/test_i5_evidence.py scripts/testing/test_i4_evidence.py scripts/testing/test_summarize_v3.py scripts/testing/test_l1_provenance.py
python3 docs/v3/check_l2_manifest.py
python3 -B scripts/testing/evidence-storage.py check
```

Le relevé initial utilise `git ls-tree -rl`, `git rev-list --objects`,
`git cat-file --batch-check` et `git count-objects -v`, sans maintenance Git.
L'inventaire scanne les fichiers suivis et les membres TAR, relève leurs hashes,
les reçus embarqués et les références des documents/scripts. Les 37 réencodages
ont été vérifiés **avant** retrait du gzip initial ; le contrôle final recalcule
XZ, TAR et gzip reconstruit. Les tests du codec couvrent aussi corruption,
en-tête tronqué, reconstruction erronée, destination existante et effacement
d'une sortie partielle rejetée. Aucun résultat d'application n'est revendiqué.

Le vérificateur L2 contrôle 253 identifiants, 380 lignes, 324 obligations L2,
56 reports, 33 oracles et 87 sous-scénarios ; les liens/ancrages du corpus V3
restent résolubles. Pour les anciens chemins gzip des reçus, « résoluble » signifie
via l'alias vérifié de l'inventaire et sa restauration exacte, ou via le blob
Git initial : les anciens noms ne sont pas tous des fichiers physiques au HEAD.
Les liens Markdown historiques directs restent des fichiers physiques inchangés.
Les exécutables de campagne n'ont pas été modifiés ; leur reproduction reste au
SHA de gel, avec les bruts restaurés si nécessaire.

Économies futures **attendues, non mesurées** : suppression des exécutions communes
dans une gate multi-ancres autorisée, absence de rerun applicatif pour un delta
documentaire, campagnes PostgreSQL limitées aux hypothèses touchées et stockage
brut compressé/durable. Aucun pourcentage de temps futur n'est déduit des seules
tailles d'archives. L0/L1 clôturés ne sont pas rouverts par ce nettoyage ; les
exigences de release et les scénarios absents restent dus.


Les contrôles ciblés sont terminés : **2 tests du codec + 26 tests des
collecteurs PASS**, contrôle documentaire L2 PASS (888 liens relatifs,
64 ancres), contrôle de stockage 745/745 et 37/37 reconstructions gzip exactes.
Une restauration physique de A a également contrôlé les empreintes de ses
**1 247 artefacts embarqués**. Les verdicts d'application y restent historiques.
`git diff --check` est sans anomalie. Les empreintes du statut/diff du checkout
utilisateur et les autres worktrees sont comparées avant livraison.

Le relevé et le réencodage ponctuels ont été exécutés par
`python3 -u /private/tmp/pocoma-evidence-inventory.py` (outil d'inventaire local,
aucun exécuteur de certification ajouté). Le reçu reproductible est l'inventaire ;
le codec livré permet de vérifier/restaurer chaque transformation sans ce script.
La commande de restauration effectivement éprouvée est :

```sh
python3 -B scripts/testing/evidence-storage.py restore docs/testing/evidence/L2-I5/A-9a315e16.tar.gz --destination /private/tmp/pocoma-rationalization-A-9a315e16.tar.gz
```

Publication : commit contenant ce dossier et l'inventaire ; son SHA exact et
la tête distante vérifiée figurent dans le reçu de livraison de la conversation.
Aucun SHA auto-référent n'est inventé dans le document qu'il identifierait.

Le volume de tous les fichiers du HEAD utilise les tailles des blobs Git ;
les fins de ligne CRLF du checkout de `app/mvnw.cmd` ajoutent 189 octets locaux
qui ne sont pas imputés au tree Git (fichier inchangé).


### Bilan exact du tree livré

| Mesure finale | Octets / nombre |
|---|---:|
| Tous fichiers du HEAD initial | 1 269 392 774 |
| Tous fichiers du HEAD livré (2 066 fichiers) | 759 145 260 |
| Gain net du HEAD, nouveaux documents/outils et modifications inclus | **510 247 514** |
| Surcoût documentaire/outils et modifications inclus dans le gain net | 954 451 |
| Artefacts du périmètre large, ajouts compris (749 fichiers) | 753 319 878 |
| Références inventoriées non résolubles après alias/restauration | **0** |

Les 37 anciens noms gzip sont des alias d'audit, pas des copies physiques
supplémentaires au HEAD. Les observations restent toutes dans Git.
