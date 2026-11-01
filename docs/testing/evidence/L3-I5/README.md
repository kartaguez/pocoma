# L3/I5 — Preuves de remédiation et dossier provisoire gelé

**QUALIFICATION IN PROGRESS — CLOSURE PENDING HUMAN ACCEPTANCE.**
Audit indépendant de cette livraison et décision humaine finale PENDING ; L3 OPEN,
aucun GO L4. Le [rapport](../../../v3/L3_Identity_Execution_Report.md), le
[runbook](../../../v3/L3_Identity_Runtime_Runbook.md), le
[manifeste](../../../v3/L3_Identity_Proof_Manifest.json) et le
[projet d’acte](../../../v3/L3_Identity_Closure.md) forment le dossier.

## Campagne canonique option B — fraîche au SHA aff7a70d

Deux invocations non filtrées, séquentielles, même SHA gelé
`aff7a70d1e468ac92fd4e8fc91fed8ba7986d3f7`, worktree d'exécution propre avant/après,
aucun delta exécutable. [Commandes, compteurs et modules](canonical/Campaigns.json) :

- O20 `39583d8bd0e94554bfa55761c9d0372c` : 61 modules SUCCESS,
  **2057/0/0/1**, 324 XML ; Architecture **163/0/0/0**, Worker **320/0/0/1**,
  Monolithe **14/0/0/0**. BUILD SUCCESS / code 0 ; verdict strict **INCONCLUSIVE**.
- O21 `e322cc9bc42e4e2fb1fc397b4bf0d4dc` : 52 modules prévus,
  42 SUCCESS / PRIMARY FAILURE / 9 SKIPPED ; **1414/0/22/0**, 221 XML.
  Deux ancres non exécutées ; BUILD FAILURE / code 1 ; verdict **FAIL**.
- r05/r12, entrelacement Discovery/renewal, quatre règles Architecture Identity
  et A1 PASS dans O20 ; NOT RUN dans O21. Aucun transfert de PASS.
- Le SKIP O20 réel est `zExploratoryContentionSeparateFromCorrectness`, condition
  `step3b.measure` absente ; `measureStep3bLatencyUnderLoad`/@Disabled ne sont pas
  dans le source gelé. Politique §35 / plan I5 §10.3 : zéro SKIP requis pour PASS.
  Les 67/46 limites du collecteur sont conservées ; aucune satisfaction avec
  réserves des gates n'en est déduite. O20/O21 restent NOT DEMONSTRATED.

Chaque `canonical/{architecture,command}/complete.tar.gz` contient le
`raw.tar.gz` natif **inchangé**, plus `collector/` (plan, provenance, résultat
initial, revue secondaire O20), `metadata/` (SHA/status/entrées avant et après,
environnement sur allowlist, observations images/versions PG, méthodes, modules,
compteurs et contrôles) et la source opérateur `operator/capture.py.txt`.
Le modèle Maven, Surefire effectif, logs complets, XML et traces physiques sont
dans le brut natif imbriqué. `metadata/Discovered_Methods.json` indexe chaque
méthode observée, son module/source haché/UUID et sa ligne XML ; il ne prétend
pas à une découverte exhaustive des providers JUnit. `metadata/diagnostic.json`
O21 conserve les 22 méthodes ERROR, leurs traces et SQLSTATE 08P01 : échec SSL
à la connexion Flyway de la fixture historique Pot dans `L2ManifestProof`.
Cause racine non établie ; aucune correction ni relance. Les résultats anciens
ne sont pas requalifiés. Tout correctif exécutable nécessite un mandat distinct.

`Archive_Index.json:C5-A/C5-CM` donne les tailles/SHA-256 de l'archive complète,
de ses membres et de tous les membres natifs imbriqués (`nested_raw.members`),
avec les testcases et lignes XML. Les copies compactes `Summary.json` permettent
la lecture directe des compteurs de **chaque** module. Le checkout neuf applique
CRLF au wrapper Windows conformément à `.gitattributes` ; le worktree principal
est LF. Ce wrapper n'est pas exécuté et reste identique entre les gates.

Vérification indépendante des octets/attributions, sans test applicatif ni O25 :

```sh
python3 -B docs/testing/evidence/L3-I5/canonical/verify_archives.py.txt
python3 -B docs/testing/evidence/L3-I5/verify_dossier.py --self-test
# Lecture seule des bruts (vérifier d'abord les empreintes) :
tar -xOf docs/testing/evidence/L3-I5/canonical/architecture/complete.tar.gz raw.tar.gz | tar -tzf -
tar -xOf docs/testing/evidence/L3-I5/canonical/command/complete.tar.gz metadata/diagnostic.json
```

Les vérificateurs I4/I5 et le harnais Maven existants sont inchangés. Le texte
probatoire additionnel `verify_archives.py.txt` vérifie aussi les archives
imbriquées, le modèle/ordre Maven, les compteurs réels, les modules non exécutés,
les méthodes/lignes/producteurs et les mêmes entrées gelées entre gates.
Ce contrôle de preuves n'est pas l'audit indépendant O25. Archives conservées
dans Git ; aucun `/tmp` indispensable à leur audit. SHA de publication fourni
après commit/push normal ; SHA exécuté distinct, sans autoréférence documentaire.
L3 OPEN ; audit indépendant et décision humaine PENDING ; L4 NOT AUTHORIZED.

## Remédiation Q-ID et historique conservés

Q-ID est une preuve fraîche au code/outils
`0ba18ec1e2fb9d9ead4e6ff84b9c839048c82dc3`, invocation
`46873469f44042c5b43d0a0a30b10481`. Sept demandes uniques, trois reçus PASS,
SQL normatif avant/après, onze booléens cumulatifs vrais, deux arrêts qualifiés
et restart sans effet supplémentaire. Deux diagnostics avant stimuli sont
conservés (allowlist physique /32 ; décodage JSON multiligne). Une seule
préparation réussie, aucune répétition de demande ou de prepare après succès.

`Q-ID-public.tar.gz` conserve les membres exacts de l’export expurgé :

- `campaign.json`, `commands.json`, `prepare-commands.json`, diagnostics et build.
- `Q-UFREE-receipt.json`, `Q-UOTHER-receipt.json`, `Q-REGNEW-receipt.json` ; les trois
  copies directement accessibles ici ont exactement les mêmes octets.
- `*-before.json`, `*-after.json`, `*-fingerprints.tsv`, `*-oracle.tsv`, trois
  SQL normatifs et snapshots de stop/restart. Tokens : présence/null/SHA-256 seulement.
- `operator/` : versions exactes des scripts opérateur locaux et observateur RO,
  conservées comme sources de provenance ; ce ne sont pas de nouvelles API/outils
  productifs ni une invitation à rejouer les stimuli sur la même base.
- `private-evidence-index.json` : chemins privés, modes, tailles et hashes, sans
  octets privés. Configuration/credentials/intentions/tokens/lignes exactes/logs
  bruts restent dans le répertoire opérateur 0700 ; leur examen nécessite l’accès
  autorisé. L’intégrité publique se vérifie sans accéder aux secrets.

Les six dossiers `reused/gemini-*` sont **historiques, pas de nouvelles campagnes
I5**. Ils conservent sans changement `raw.tar.gz` et les octets JSON originaux
compressés sans perte (`plan`, `result`, `provenance`). Les invocations propres
INTEGRATION/FOUNDATION indiquent le vrai SHA `028930e09c680823dc2ee1f24da3e48df1d0ceae`.
FAST/CHANGE/r05/r12 indiquent leur HEAD `0092b4d1bafe644017717865a620f0a35b68fbb5`
et leur worktree dirty ; leurs 1 265 entrées `app/` exécutées correspondent au gel
`32504fca` et au code courant. Ne pas convertir ce constat en commit historique
propre. Commandes développées, modules, tests nominatifs, modèles effectifs,
SQL, toolchains/settings, compteurs et limites : `Recovered_Campaigns.json` et
les métadonnées originales.

`Input_Equivalence.json` compare toutes les entrées suivies app/scripts depuis
I4, pas seulement src/main : trois tests/harnais temporels et trois entrées du
sélecteur ont changé ; production/POM/migrations/ressources/outils DEV inchangés.
E4/X4 et les deux gates FAIL I4 restent dans leurs archives originelles et à leur
SHA d’exécution. Les 64 reçus sont référencés dans `L3-I4/Variant_Receipts.json`
avec leur archive INTEGRATION, membre et ligne ; aucune duplication ni série
fictive r01…r64. La gate COMMAND seule annoncée 1777, l’Architecture tardive 163
et la composition autonome 10 sans brut attribuable restent **UNPROVEN** ; les
contributions E4 correspondantes conservent leurs preuves propres. Une sélection
Architecture 163 PASS ne satisfait pas la gate non filtrée. O20/O21/O25 ND.

`Archive_Index.json` donne tailles/SHA-256 de chaque archive/membre, noms JUnit
qualifiés, méthode, module/producteur/invocation et ligne de testcase. Les trois
reçus Q-ID ajoutent le request UUID, oracle et vecteur avant/après. Les matrices
27/17 sont dans le manifeste ; toutes leurs réserves restent explicites.
`Historical_Rectification.json` permet de retrouver les quatre originaux Gemini
dans Git et explique les SHA longs erronés. `Preflight.json` et `Confidentiality.json`
conservent le périmètre déclaré et les contrôles de confidentialité.
`Runtime_Lifecycle.json` exporte les lignes exactes non secrètes de démarrage et
de callback, leurs numéros/hashes et les sorties du helper stop. L’instance PG
jetable a été arrêtée après qualification ; son stockage et les snapshots privés
sont conservés, aucun Worker n’est laissé actif.

Vérification depuis la racine, en lecture seule :

```sh
python3 -B docs/testing/evidence/L3-I5/verify_dossier.py --self-test
tar -tzf docs/testing/evidence/L3-I5/Q-ID-public.tar.gz
tar -xOf docs/testing/evidence/L3-I5/Q-ID-public.tar.gz final-oracle.tsv
gzip -dc docs/testing/evidence/L3-I5/reused/gemini-integration/provenance.json.gz
```

Le vérificateur réutilise `verify.assess` pour les XML récupérés et le vérificateur
I4 pour les archives originales ; il ne lance ni Maven, ni PostgreSQL, ni aucun
stimulus. Le self-test refuse des bytes modifiés/tronqués/allongés. Les hashes
prouvent l’intégrité des octets, pas l’authenticité d’un producteur malveillant ni
une décision indépendante. Le manifeste n’a pas de hash autoréférent et ne se
certifie pas lui-même. Le reçu de contrôle est communiqué séparément après gel.

Stockage public : Git sur `feat/v3-architecture`, sans stockage externe supposé.
Le commit de livraison se résout après publication par
`git log --diff-filter=A -1 --format=%H -- docs/testing/evidence/L3-I5/README.md`.
Pour restaurer depuis ce commit, `git show SHA:docs/testing/evidence/L3-I5/Q-ID-public.tar.gz`
restitue les octets ; vérifier ensuite taille/hash du manifeste avant extraction.
Le SHA documentaire ne remplace pas le SHA du code exécuté. Publication et égalité
local/distant seront rapportées après push normal, sans force ni acceptation anticipée.
