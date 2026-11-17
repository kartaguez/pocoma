# Pocoma V3 — nettoyage final du HEAD avant I6/I7

Mandat humain intégré du 29 octobre 2026, branche `feat/v3-architecture`.
SHA initial et référence de comparaison du contrôle de stockage : `3c6315cfd52dfee9d764a3cb00c16248793cdd8a`.
**105 archives TAR retirées du HEAD après restauration vérifiée ; aucune preuve
historique détruite, aucun réencodage et aucun résultat applicatif modifié.**
Ce dossier actualise la seule politique de stockage du [rapport précédent](Certification_Evidence_Rationalization.md),
qui demeure un compte rendu historique de la première livraison.

## Mesures exactes

Octets logiques des blobs suivis, MB décimaux ; ni `.git`, ni fichiers ignorés,
ni allocation des blocs du checkout. Les mesures du HEAD incluent tous les ajouts.

| Périmètre | Initial | Final | Gain | Gain relatif |
|---|---:|---:|---:|---:|
| `docs/testing/evidence/` | 737970498 | 49211617 | 688758881 | 93.33149 % |
| Tous fichiers du HEAD | 759145260 | 72323946 | 686821314 | 90.47298 % |

Les 7 609 754 octets de deux logs locaux préexistants ignorés dans le worktree
restent intacts ; ils ne sont pas dans le HEAD. Le CRLF local de `app/mvnw.cmd`
n’est pas imputé au tree Git. La cible de moins de 100 MB est atteinte sans risque
supplémentaire ni capacité externe à provisionner.

- Archives TAR suivies : **108 → 3** ; retrait **105**, soit **688 758 881 octets**
  désormais consultables uniquement par l’historique pour ces chemins. Les petits
  logs gzip, patchs et contre-exemples F01–F05 restent dans le HEAD.
- Les trois archives compactes conservées sont les qualifications temporelles
  v2 et finale et le contre-exemple ciblé R2. Les JSON de contre-exemples, contrats
  v1/v2/v3, reçus, réserves, décisions et références des autres campagnes sont conservés.
- Externalisation : **0 octet** ; suppression classée duplication D : **0 octet**.
  Les copies de XML/logs appartiennent aux captures historiques déplacées en B ;
  elles ne sont pas présentées comme des observations interchangeables.
- Blobs accessibles depuis le commit initial : **1 984 944 654 octets** (8 440 blobs),
  dont **1 226 020 826** déjà hors HEAD initial. Tous restent accessibles ; les nouveaux
  documents/outils s’y ajoutent. Représentations disque des objets accessibles :
  **1 816 016 219 octets** au relevé initial (ce n’est pas un poids exclusif de pack).
  **Aucune économie du stockage Git historique, du clone complet ou des packs revendiquée.**

## Politique appliquée et auditabilité

L’[inventaire exhaustif](Evidence_Retention.json) classe les **433 fichiers initiaux**
(A : 328, B : 105, C : 0, D : 0). Pour chacun : rôle, volume, empreinte, statut
observé, phase finale/intermédiaire, SHA connus, provenance, copies, références
entrantes et dépendance aux outils. Les ensembles de campagne sont disjoints :
I5 diagnostics regroupés par gel, autres dossiers par campagne ; leurs 20 plus
gros ensembles figurent ci-dessous. Les 17 groupes de premier niveau ne suffisent
pas à former un classement de vingt campagnes sans détailler les gels I5.

A conserve les rapports de clôture, manifestes, agrégats, entrées indépendantes
(notamment `Performance-inputs.json`), contrats et contre-exemples minimaux. B
conserve les captures complètes finales **et** intermédiaires, FAIL/SKIP/UNKNOWN/
INCONCLUSIVE compris. Aucun retrait n’est motivé par le seul échec d’une campagne.
Le lien exigence → scénario → méthode → commande → SHA testé → résultat → artefact
récupérable → décision reste porté par les manifestes et rapports d’origine, puis
la [table de récupération](Evidence_Recovery.md). Les commandes des reçus sont
historiques et ne sont pas de nouvelles instructions de campagne.

L’inventaire antérieur décrit 6 groupes de fichiers identiques et 408 groupes de
membres TAR identiques (24 626 124 octets décompressés redondants) dans son périmètre
large ; ces observations ne justifient aucune suppression de contre-exemple.
Il reste accessible inchangé dans [Evidence_Inventory.json](Evidence_Inventory.json).

Les commits de stockage sont des ancêtres accessibles de la branche publiée.
**B est un archivage transitoire Git**, sans SLA de sauvegarde externe. Aucun
stockage externe fiable, accès et rétention vérifiés n’est disponible ; aucun n’a
été créé. Avant toute future purge/réécriture, une décision humaine devra imposer
une migration vérifiée vers un stockage durable ; elle devra préserver les blobs
et références de ce catalogue. Le volume physique du dépôt reste un sujet distinct.

9 diagnostics anciens n’ont pas de SHA testé univoque établi par les reçus
compacts examinés : liste exacte dans `summary.sha_provenance_unresolved_archives`.
Cette lacune de provenance préexistante est déclarée ; aucune réexécution ni aucun
SHA déduit du seul nom de fichier n’est inventé. Leurs captures exactes sont
restaurées et vérifiées comme les autres. Les reçus des gates obligatoires gardent
leur SHA, les commandes et leurs résultats distincts ; plusieurs SHAs dans une
capture signifient plusieurs observations, pas une certification globale.

## Points de sûreté et décisions préservés

| Preuve | Sources actuelles et accès aux bruts |
|---|---|
| Sûreté transactionnelle L1 | Rapports A/B/C/D/E, contrats L1-C, manifestes F/F2 et reçus A ; captures exactes en B |
| I2/I3 claims, leases, fencing | Manifestes et Gate-index conservés ; archives exactes et auditeur I3 inchangé |
| F01–F05 | Patchs/logs gzip minimaux, manifestes et décisions conservés ; gates I5 en B |
| Discovery I4 | Reçus finaux, agrégats et entrées indépendantes dans A ; XML/physiques complets en B |
| I5, AUD-I5-01 et R-AUD-I3-03 | Décision humaine et rapports inchangés ; réserves maintenues |
| Contre-exemples temporels v1/v2/v3 | R1/R2, qualification v2 et finale, contrats et réutilisation bornée conservés en A |

**I5 — CLOSED BY HUMAN ACCEPTANCE WITH AVAILABILITY RESERVE.**
L2 global reste INCONCLUSIVE. Aucun PASS rétroactif, aucune homologation production,
aucune certification de la configuration 30 secondes. SPEC-10 et la matrice des
gates ne sont pas modifiés. I6/I7 peuvent commencer sans nouveau nettoyage ; leurs
preuves et gates propres restent dues.

## Restauration et scripts historiques

La [table](Evidence_Recovery.md) donne les 105 chemins, tailles et SHA-256.
L’outil `evidence-storage.py restore` récupère exactement une archive retirée ou
un ancien gzip, refuse un fichier existant et contrôle les octets avant succès.
Les 37 anciens gzip ont aussi été réellement restaurés individuellement depuis
leur commit d’origine : aucune dépendance à une reconstruction zlib.

Les scripts historiques qui exigent les fichiers physiques sont **inchangés** :
restaurer les chemins demandés avant un audit au HEAD quand il le permet, ou
utiliser leur publication historique pour les scripts qui exigent leur ancien
code/provenance. Ne pas déplacer silencieusement une campagne au code actuel.
Par exemple, la procédure reproductible d’audit I3 est :

```sh
git worktree add --detach /private/tmp/pocoma-i3-audit-restored 834da05fe3ee547177bfdb9bcc5dcbd8c358d1d3
cd /private/tmp/pocoma-i3-audit-restored
python3 -B docs/testing/evidence/L2-I3/audit-archives.py
```

Pour cette livraison, la même publication a été matérialisée par `git archive`
dans un répertoire temporaire, avec métadonnées Git temporaires indépendantes
utilisant les objets existants en lecture seule. Aucun worktree existant n’a été
modifié/enregistré/supprimé. L’auditeur a vérifié les quatre archives, méthodes,
XML frais, sources et variantes physiques au SHA code `1d7cf51c13671c7c85a5c9c1281f526be2b2abfb`.
Ses PASS sont ceux des observations historiques ; aucune campagne Maven relancée.

## Vérifications et portée déclarée avant intervention

Slices primaires/secondaires : **NONE / NONE**, périmètre DOCUMENTATION / STORAGE.
Impact productif attendu et observé : **NONE** ; delta `app/` vide, scripts/oracles
métier et scripts de campagne historiques identiques. Seuls le résolveur de stockage
et ses tests évoluent. Gate architecture globale : **NOT REQUIRED / NOT RUN**.
Full Reactor : **NOT REQUIRED / NOT RUN** ; aucune frontière applicative ou gate
d’intégration franchie. PostgreSQL et migrations : **NONE / NOT RUN**.
Franchissement inattendu de slice : **NONE**.

Commandes exécutées depuis la racine du worktree :

```sh
python3 -u /private/tmp/create-retention-inventory.py
python3 /private/tmp/apply-retention-cleanup.py
python3 -B -m unittest scripts/testing/test_evidence_storage.py scripts/testing/test_i2_evidence.py scripts/testing/test_i3_evidence.py scripts/testing/test_i4_evidence.py scripts/testing/test_i5_evidence.py scripts/testing/test_summarize_v3.py scripts/testing/test_l1_provenance.py
python3 docs/v3/check_l2_manifest.py
python3 -B scripts/testing/evidence-storage.py check
python3 -B scripts/testing/audit-evidence-retention.py --restore-all --sample-members
python3 -B scripts/testing/evidence-storage.py restore docs/testing/evidence/L2-I5/A-9a315e16.tar.gz --destination /private/tmp/pocoma-final-cleanup-A-original.tar.gz
python3 -B scripts/testing/evidence-storage.py restore docs/testing/evidence/L2-I5/A-9a315e16.tar.xz --destination /private/tmp/pocoma-final-cleanup-A-stored.tar.xz
python3 -B scripts/testing/evidence-storage.py resolve docs/testing/evidence/L2-I5/A-9a315e16.tar.xz
git diff --check
git diff 3c6315cfd52dfee9d764a3cb00c16248793cdd8a -- app
git ls-remote origin refs/heads/feat/v3-architecture
```

Les deux premiers scripts sont les outils locaux ponctuels de relevé et de retrait,
non des exécuteurs de certification. Les contrôles livrés et le manifeste restent
rejouables sans eux. Les tailles initiales sont reproduites par `git ls-tree -rl`
au SHA initial, les finales par `git ls-tree -rl HEAD` après publication.
Le contrôle d’intégrité de 745 entrées de l’ancien inventaire et des 433 classements
courants réussit. **39 tests outils passent** ; le manifeste L2 retrouve 253
identifiants, 380 lignes, 324 obligations, 56 reports, 33 oracles, 875 liens et 64 ancres.

Le [reçu des contrôles](Evidence_Retention_Checks.json) enregistre 105/105 restaurations
physiques après retrait, 37/37 gzip originaux, et les empreintes de **3 652 membres**
des quatre échantillons L1/I3/I4/I5. Les 13 liens d’archives modifiés sont vérifiés,
sans autre changement du texte des quatre rapports. Aucun nouveau lien local invalide ;
aucun lien manquant dans le corpus `docs/v3` et `docs/testing`. Les 150 liens obsolètes
préexistants ailleurs dans la documentation restent listés comme dette distincte.
Les références des JSON, SHA et scripts ne sont pas réécrites : elles se résolvent
par le manifeste/la restauration exacte. Un ancien chemin n’est pas déclaré fichier
physique présent au HEAD.

Les modifications préexistantes du checkout principal et les autres worktrees sont
préservés. SHA final et synchronisation distante : reçus par `git rev-parse HEAD`
et `git ls-remote` après commit/push normal, fournis avec la livraison ; aucun SHA
auto-référent n’est inventé dans ce document. Les SHAs applicatifs testés restent
ceux des campagnes dans l’inventaire ; cette livraison teste le stockage, pas l’application.

## Vingt plus gros artefacts avant

| Chemin après préfixe des preuves | Octets | % des preuves initiales |
|---|---:|---:|
| `L2-I5-audit-remediation/gate-1-failed-05b74814.tar.xz` | 19898560 | 2.696 % |
| `L2-I5/A-9a315e16.tar.xz` | 19625660 | 2.659 % |
| `L2-I5/F-9a315e16.tar.xz` | 19619660 | 2.659 % |
| `L2-I5/diagnostics/A-e257c381-superseded.tar.xz` | 19609556 | 2.657 % |
| `L2-I5/diagnostics/F-3efcb8cc-superseded.tar.xz` | 19594676 | 2.655 % |
| `L2-I5/diagnostics/A-3efcb8cc-superseded.tar.xz` | 19585796 | 2.654 % |
| `L2-I5/C-9a315e16.tar.xz` | 19572752 | 2.652 % |
| `L2-I5/diagnostics/F-e257c381-superseded.tar.xz` | 19562308 | 2.651 % |
| `L2-I5/diagnostics/C-e257c381-superseded.tar.xz` | 19530300 | 2.646 % |
| `L2-I5/diagnostics/C-3efcb8cc-superseded.tar.xz` | 19495664 | 2.642 % |
| `L2-I5/diagnostics/A-ac9f7642-superseded.tar.xz` | 19463340 | 2.637 % |
| `L2-I5/diagnostics/A-c8b0815b-superseded.tar.xz` | 19461260 | 2.637 % |
| `L2-I5/diagnostics/C-c8b0815b-superseded.tar.xz` | 19428212 | 2.633 % |
| `L2-I5/diagnostics/C-ac9f7642-superseded.tar.xz` | 19428008 | 2.633 % |
| `L2-I5/diagnostics/F-ac9f7642-superseded.tar.xz` | 19382636 | 2.626 % |
| `L2-I5/diagnostics/M-3efcb8cc-superseded.tar.xz` | 19372656 | 2.625 % |
| `L2-I5/diagnostics/M-e257c381-superseded.tar.xz` | 19367100 | 2.624 % |
| `L2-I5/diagnostics/M-c8b0815b-superseded.tar.xz` | 19351368 | 2.622 % |
| `L2-I5/M-9a315e16.tar.xz` | 19348156 | 2.622 % |
| `L2-I5/diagnostics/C-22362595-superseded.tar.xz` | 19344464 | 2.621 % |

Cumul : **390042132 octets**, **52.853 %** des 737 970 498 octets initiaux.

## Vingt plus gros ensembles avant

| Ensemble disjoint | Octets | % des preuves initiales |
|---|---:|---:|
| `L2-I4` | 132061660 | 17.895 % |
| `L2-I5` | 78996219 | 10.705 % |
| `L2-I5 / e257c381` | 78214536 | 10.599 % |
| `L2-I5 / 3efcb8cc` | 78194036 | 10.596 % |
| `L2-I5 / ac9f7642` | 77929642 | 10.560 % |
| `L2-I5 / c8b0815b` | 77727947 | 10.533 % |
| `L2-I5 / 22362595` | 77465802 | 10.497 % |
| `L2-I5 / 7296e11c` | 44241972 | 5.995 % |
| `L2-I5-audit-remediation` | 20237117 | 2.742 % |
| `L2-I5 / 8e6a3601` | 19050604 | 2.581 % |
| `L2-I2` | 11089984 | 1.503 % |
| `L1-DE` | 8376359 | 1.135 % |
| `L2-I3` | 8122835 | 1.101 % |
| `L1-AB` | 4563264 | 0.618 % |
| `L2-I1` | 4452721 | 0.603 % |
| `L1-C` | 4390493 | 0.595 % |
| `L2-I3-post-audit` | 3868803 | 0.524 % |
| `L1-F` | 2758416 | 0.374 % |
| `root historical evidence` | 2195137 | 0.297 % |
| `L1-F2` | 2006338 | 0.272 % |

Cumul : **735943885 octets**, **99.725 %** des 737 970 498 octets initiaux.

## Vingt plus gros artefacts après

| Chemin après préfixe des preuves | Octets | % des preuves initiales |
|---|---:|---:|
| `L2-I4/Performance-inputs.json` | 6160591 | 0.835 % |
| `L2-I4/final-architecture/maven.log` | 5655876 | 0.766 % |
| `L2-I4/I4-7-complements-command.log` | 5235201 | 0.709 % |
| `L2-I4/final-command/maven.log` | 5014190 | 0.679 % |
| `L2-I4/final-monolith/maven.log` | 4635446 | 0.628 % |
| `L2-I4/I4-3-initial-command.log` | 3066004 | 0.415 % |
| `L2-I4/Performance-Summary.json` | 1733307 | 0.235 % |
| `L2-I4/I4-6-all49-diagnostic.log` | 1258080 | 0.170 % |
| `L2-I4/final-architecture/result.json` | 1059412 | 0.144 % |
| `L2-I4/final-command/result.json` | 993972 | 0.135 % |
| `L2-I4/final-monolith/result.json` | 930718 | 0.126 % |
| `L2-I4/I4-4-5-discovery-retry.log` | 736819 | 0.100 % |
| `L2-I4/I4-4-5-discovery-diagnostic.log` | 731155 | 0.099 % |
| `Architecture_Postgres_Bootstrap_Six_Errors_Fix_2026-10-20.json` | 680381 | 0.092 % |
| `L2-I4/final-read/result.json` | 537216 | 0.073 % |
| `L2-I4/I4-7-last-variants-diagnostic.log` | 534387 | 0.072 % |
| `L2-I1/ARCHITECTURE-summary.json` | 506862 | 0.069 % |
| `L1-DE/Compiler-and-SQL-provenance.json` | 461223 | 0.062 % |
| `L1-DE/SPEC10-matrix.json` | 449280 | 0.061 % |
| `L2-I4/I4-7-last-variants-retry.log` | 430119 | 0.058 % |

Cumul : **40810239 octets**, **5.530 %** des 737 970 498 octets initiaux.

## Liste exacte des fichiers de la livraison

Les 105 suppressions sont exhaustivement listées dans la [table de récupération](Evidence_Recovery.md).
Le champ `delivery_files` de l’[inventaire](Evidence_Retention.json) donne chaque
chemin et son opération, y compris les ajouts. Autres fichiers :

- `docs/testing/Evidence_HEAD_Cleanup.md` — add.
- `docs/testing/Evidence_Recovery.md` — add.
- `docs/testing/Evidence_Retention.json` — add.
- `docs/testing/Evidence_Retention_Checks.json` — add.
- `docs/v3/L1_AB_Execution_Report.md` — modify.
- `docs/v3/L1_C_Execution_Report.md` — modify.
- `docs/v3/L1_D_Execution_Report.md` — modify.
- `docs/v3/L1_E_Execution_Report.md` — modify.
- `scripts/testing/audit-evidence-retention.py` — add.
- `scripts/testing/evidence-storage.py` — modify.
- `scripts/testing/test_evidence_storage.py` — modify.
