# L2 / I5 — dernière tentative et décision de clôture

**Conclusion : INCONCLUSIVE / réserve de disponibilité. Résultat technique du
banc : FAIL. Aucun défaut productif de sûreté démontré.** Cette dernière
itération autorisée est terminée, sans modification après gel ni nouvelle
campagne proposée. Les phases nominale et de contention admissible ont passé
leurs oracles physiques et temporels WRITE/READ ; le scénario excessif a produit
ses témoins de retrait. La fermeture intégrée a ensuite échoué après expiration
des transactions Business retenues. Cela interdit un certificat global PASS90s.
**AUD-I5-01 et R-AUD-I3-03 ne sont pas requalifiés PASS.**

Décision humaine proposée : poursuivre le développement I6/I7 avec une réserve
explicite sur la disponibilité et le délai de reprise, en acceptant suspension,
retrait local et drainage conservateur. Cette proposition ne donne ni GO de
production, ni garantie de reprise bornée sous contention défavorable.

## Provenance et périmètre

| Référence | SHA / constat |
|---|---|
| Initial, baseline et tête distante vérifiée | `e0a7ec3bf3ce41bf599df915236a64bc1c599b05` |
| Gel v2 historique conservé | `5eab2e94379ef40c3f9ff54c14a4d04a8f13dcbd` |
| Gel contractuel et applicatif testé | `a2133b6b43a74999374bc26a222f70c7500fadda` |
| Publication | Commit contenant ce rapport, SHA exact dans le reçu de livraison ; le rapport ne peut inscrire son propre hash Git |
| Destination exclusive | `kartaguez/pocoma`, `feat/v3-architecture`, push normal |
| Worktree réutilisé | `/private/tmp/pocoma-v3-postaud`, propre au départ et au gel |

HEAD, branche, tête distante et worktrees ont été inventoriés avant modification.
Le checkout principal reste sur `feat/runtime-monolith`, HEAD `90a3e9bc`, avec ses
modifications préexistantes intégralement préservées ; son inventaire détaillé
avant intervention est archivé et comparé après intervention. Aucun reset,
clean, stash, force push, déplacement ou suppression de worktree.

Politique Reactor normative, plans I3/I4/I5, plan correctif, SPEC-05, rapports et
preuves F01–F05 pertinents examinés. Déclaration préalable dans l'[addendum
gelé](L2_I5_Temporal_Final_Addendum.md) : **COMMAND primaire**, MONOLITH pour la
gate composée conditionnelle ; autres slices NONE. Impact productif attendu et
observé : **NONE**. Aucun changement de POM, port, owner productif, pool,
scheduler, protocole, table ou migration. Franchissement inattendu : **NONE**.
Architecture globale **NOT REQUIRED / NOT RUN** ; full reactor **NOT REQUIRED /
NOT RUN**, car il s'agit du seul banc sans changement de frontière ni intégration
main. DB **CURRENT V3**, fixtures existantes ; aucune campagne historique de
migrations ajoutée. La prochaine intégration globale reste soumise à ses gates
propres ; cette livraison ne les remplace pas.

## Témoin physique corrigé et indépendance

Le [contrat v3](../testing/evidence/L2-I5-temporal-final/temporal-contract-v3.properties)
change seulement `version` et `availability` ; l'égalité de toutes les autres
propriétés avec v2 est contrôlée avant les Workers. TTL90s, cadence10s,
revalidation5s, T250ms, P1s, q10ms, J1s cumulé, delta5s et sécurité2s sont
inchangés. D(N)=N×(T+q)+P+J vaut **2,52s WRITE / 3,04s READ par Worker W2**.
Ni seuil relevé, ni R arbitraire, ni recalcul d'acceptation depuis les résultats.
**30s reste NON CERTIFIÉ.**

Le helper `I5PhysicalWindow` couvre les owners Control Engine et Discovery des
deux Workers. Avant leur action réelle, les transactions non-renewal prennent
un advisory lock partagé transactionnel PostgreSQL. La fermeture locale des
entrées avant owner attend leurs retours ; un verrou exclusif PostgreSQL de
session par base confirme leur résolution et empêche une réentrée silencieuse.
L'absence des anciens PID/XID actifs est vérifiée extérieurement.

Les voies renewal sont brièvement drainées pour le probe initial puis rendues
aux threads existants. Un `FOR UPDATE NOWAIT` extérieur contrôle chaque ligne,
son token et sa validité PostgreSQL ; son rollback précède l'ouverture. La
fenêtre est donc définie avant le résultat attendu du renewal. Le helper
n'appelle jamais le service à la place du Worker. Les durées mesurées du vrai
appel incluent les éventuels délais introduits par le banc.

Pendant la fenêtre, **Discovery, acquisition et maintenance Control sont
suspendues avant owner**. Cette réduction de concurrence est explicite. Les
renewals claims/leases des deux Workers et les préparations Business RO restent
concurrents. Répartition statique disjointe 0|1 et 2|3, B32, M_W4 ; 64 connexions
de rôles, serveur100 et réserve16 contrôlés. Les proxies refusent tout appel
ownership non couvert et toute clé renewal franchissant cette répartition.
Aucune garantie n'est transférée à des partitions concurrentes communes ou au
chevauchement arbitraire des autres owners.

En contention admissible, chaque détenteur distinct a PID/XID, FOR UPDATE acquis
et COMMIT contrôlé. Sa cible est déclarée indisponible avant injection ; après
libération, résolution physique extérieure et nouveau probe ouvrent sa fenêtre,
indépendamment du succès de la prochaine revalidation. Le retryUntil original
reste conservé. Transactions, clés et tentatives sont journalisées ; un journal
Control scellé précède le nettoyage. Les négatifs refusent fenêtre absente,
fermée, mauvais token, conflit et couverture incomplète. Le lanceur exige aussi
XML frais, sources inchangées, traces complètes **et code de sortie zéro**.

## Résultats observés et limite finale

| Scénario W2/B32/M_W4 | WRITE | READ | Portée |
|---|---|---|---|
| Nominal | 4 leases +2 claims prolongés, mêmes tokens | 8 leases +6 claims prolongés, mêmes tokens | Oracles positifs PASS avant nettoyage |
| Contention admissible | 2 détenteurs identifiés/libérés ; revalidation | 2 détenteurs identifiés/libérés ; revalidation | Oracles positifs PASS avant nettoyage |
| Contention excessive | 2 suspensions puis retraits locaux | 2 suspensions puis retraits locaux | Échéances/tokens inchangés sous détenteur encore actif ; pas de renewal revendiqué |
| Claims après retrait local des leases | 2 mêmes tokens prolongés | 6 mêmes tokens prolongés | Observation SQL indépendante des leases |
| Fermeture intégrée | Résultat global non acquis | Erreurs explicites de timeout Business | **FAIL technique** : attente de STOPPED échouée |
| Gate COMMAND + MONOLITH sans filtre | NOT RUN | NOT RUN | Arrêt obligatoire après FAIL |

Les deux phases positives contrôlent chacune les **12 leases et 8 claims**.
Marge SQL extérieure minimale avant ancienne échéance : **76,635878s pour les
leases**, **46,611938s pour les claims**, supérieure à2s. Âge physique initial
maximal : **3,429190s**, inférieur à5s. Maximum d'opération positive :
**18,098833ms** ; maximum de prochaine tentative complète calculé par les mêmes
règles v2 : **128,805708ms**, sous D. Le budget de jitter cumulé ne présente pas
de dépassement. Ces mesures sont descriptives, sans effet sur les seuils.
Les quatre omissions injectées admissibles sont conservées ; aucune règle R=1
n'est imposée. Le journal final contient **1 202 tentatives leases**, y compris
les revalidations répétées de la phase excessive.

Les quatre détenteurs excessifs restent ouverts au moins6s après omission ;
suspension et retrait local sont constatés avant leur libération. Les claims
continuent à avancer après ces retraits. Cela ne prouve aucun délai universel
de réacquisition : l'attente d'expiration durable et un redémarrage peuvent
rester nécessaires.

Le [contre-exemple de clôture](../testing/evidence/L2-I5-temporal-final/counterexample.json)
`FINAL-W2-PREPARATION-LIFETIME-01` distingue les faits suivants :

- Les préparations Business sont physiquement RO au constat initial, mais leur
  maintien pendant toute la longue séquence n'est pas établi. Leur rétention
  dépasse **71,297318s**, alors que la configuration existante impose
  `transaction_timeout=65000` (socket70s). Ce défaut de durée de fixture n'a pas
  été anticipé avant le gel.
- À la libération, les logs du driver READ indiquent explicitement
  `FATAL: terminating connection due to transaction timeout`, puis erreurs
  commit/rollback. Ce n'est pas une indisponibilité réseau inventée.
- Le helper `Runtime.close()` demande le drain puis exige STOPPED sous15s ; son
  assertion échoue. Le marqueur interne `V3 QUALIFICATION PASS`, écrit **avant**
  ce nettoyage, est donc insuffisant. Le lanceur garde FAIL et n'exécute pas la
  gate. Aucun faux PASS final n'est produit.
- La conservation locale d'une préparation devenue UNKNOWN et le refus de
  STOPPED sont compatibles avec F01. Le banc ne capture pas de snapshot Worker
  après libération : la responsabilité locale résiduelle exacte n'est donc pas
  présentée comme nouvellement prouvée. Aucune violation du fencing, double
  effet, token invalide, réactivation spéculative, restitution prématurée ou
  résolution inventée d'UNKNOWN n'est démontrée.

**Classement : hypothèse de durée physique de fixture non satisfaite et preuve
de clôture insuffisante ; pas FAIL productif.** Les fenêtres positives corrigées
sont effectivement observées, mais l'ensemble requis reste **INCONCLUSIVE**.
Le gel, les seuils, le helper et le contrat sont conservés tels que testés.
Aucune correction du timeout, aucun rejeu et aucune version suivante proposée.

## Réutilisation, commandes, coût et preuves

Les **15 tests déterministes v2 PASS** sont réutilisés : leurs sources, les
sources productives et le contrat numérique sont identiques. W1 reste un
**PASS historique v2**, avec le chemin `controlled=false` conservé ; aucun
nouveau certificat W1/v3 n'est inventé. Les deux fichiers de banc modifiés sont
énumérés dans [reuse.json](../testing/evidence/L2-I5-temporal-final/reuse.json).
A/B et F01–F05 restent des preuves héritées dans leurs limites, sans campagne
autonome. Les anciens rapports, contrats et FAIL v1/v2 restent byte-identiques.
Le contrôle de1 300 entrées historiques/productives et le reçu complet des
entrées avant intervention sont archivés.

Depuis `app/`, trois invocations préalables de la même commande de helper :

```sh
./mvnw -pl runtime-command-consumption-worker -am -Dtest=I5PhysicalWindowTest -Dsurefire.failIfNoSpecifiedTests=false test
```

La première a échoué à testCompile sur un nom d'accesseur du banc, corrigé avant
le gel ; les deux suivantes passent les2 nouveaux tests, zéro skip. Un mauvais
cwd préparatoire a également été corrigé avant gel ; aucun PostgreSQL de
qualification n'avait été lancé. Durées Maven : 6,117s +6,174s +6,062s =**18,353s**.

Unique lanceur depuis la racine :

```sh
python3 scripts/testing/run-i5-temporal-final.py /private/tmp/pocoma-i5-final-a2133b6b
```

Il a exécuté uniquement :

```sh
cd app
./mvnw -pl runtime-command-consumption-worker -am '-Dtest=V3WorkerResourcesPostgresTest#i5_r12' -Dsurefire.failIfNoSpecifiedTests=false test
```

Coût mural réel de cette campagne : **107,229028s** ; JUnit :1 test,1 échec,
0 erreur,0 skip ; temps du cas96,788s. Les18,353s précédentes sont des durées
Maven, distinctes du temps mural de campagne et hors lecture/conception/rédaction.
Coût additionnel de gate : **zéro**. Pas de campagne historique ni full reactor.

Le [reçu compact](../testing/evidence/L2-I5-temporal-final/qualification.json)
relie commandes, gel, résultats, métriques, intégrité et archive.
L'[archive de preuve](../testing/evidence/L2-I5-temporal-final/qualification-proof.tar.gz)
contient les trois traces, log Maven, XML frais, reçu des sources/classes,
diff baseline→gel, provenance, logs des helpers et extraction descriptive.

| Preuve | SHA-256 |
|---|---|
| Contrat v3 | `a17342a7a6edcaf72838168614a70402d48c46096f733753c306884d658e73ac` |
| Archive finale,351 562 octets | `3432e9c930a4a6aa0f69de590fda2b0ca0747e46a34e8d297b86a417c86a46e2` |
| Diff gelé dans l'archive | `3a67b6c6826b1a9ce7c7c4f7de7f7432ee2adca5957c892372d54ee587e2bdee` |

Inventaire exact baseline→livraison,12 fichiers :

| État | Chemin relatif au dépôt |
|---|---|
| A | `app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/v3/I5PhysicalWindow.java` |
| A | `app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/v3/I5PhysicalWindowTest.java` |
| M | `app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/v3/I5TemporalV2Postgres.java` |
| M | `app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/v3/V3WorkerResourcesPostgresTest.java` |
| A | `scripts/testing/run-i5-temporal-final.py` |
| A | `docs/v3/L2_I5_Temporal_Final_Addendum.md` |
| A | `docs/v3/L2_I5_Temporal_Final_Report.md` |
| A | `docs/testing/evidence/L2-I5-temporal-final/temporal-contract-v3.properties` |
| A | `docs/testing/evidence/L2-I5-temporal-final/reuse.json` |
| A | `docs/testing/evidence/L2-I5-temporal-final/counterexample.json` |
| A | `docs/testing/evidence/L2-I5-temporal-final/qualification.json` |
| A | `docs/testing/evidence/L2-I5-temporal-final/qualification-proof.tar.gz` |

## Proposition finale de décision humaine

Les risques résiduels portent sur la disponibilité physique, les pauses et coûts
hors hypothèses, les autres formes de concurrence, la rétention UNKNOWN et le
délai d'expiration/réacquisition. Les paramètres90s ne sont pas un SLO général.
La clôture de cette dernière tentative est exploitable sans nouveau cycle de
certification : **accepter ou refuser explicitement la poursuite du programme
avec réserve de disponibilité**, sans attribuer rétroactivement PASS à
R-AUD-I3-03. Le tableau sépare les décisions :

| Objet | Conclusion technique | Décision proposée / limite |
|---|---|---|
| 1. Sûreté transactionnelle et fencing | Preuves héritées conservées ; aucun nouveau défaut démontré | Maintenir les garanties existantes ; aucune sûreté sacrifiée pour la disponibilité |
| 2. Renouvellement observé avant échéance | OUI WRITE/READ, mêmes tokens et marges SQL contrôlées dans les phases positives | Conserver ces observations ; drainage/retrait ne vaut jamais renewal |
| 3. Garantie temporelle conditionnelle | **NON CERTIFIÉE globalement ; INCONCLUSIVE** malgré oracles positifs | Clore l'itération avec réserve ; AUD-I5-01 et R-AUD-I3-03 ne deviennent pas PASS |
| 4. Disponibilité / reprise sous contention excessive | Suspension/retrait observés ; délai de reprise général non garanti ; fermeture finale FAIL | Accepter explicitement attente d'expiration et drainage conservateur si poursuite |
| 5. Développement I6/I7 | Éligible à une décision de poursuite avec réserve, sans nouveau GO I5 demandé | **Proposition : poursuite du programme avec réserve**, sans activation implicite |
| 6. Déploiement DEV | Usage de développement existant et contrôlé seulement, opt-in et réserve explicite ;90s expérimental | Aucune nouvelle homologation DEV générale ;30s non certifié |
| 6. Déploiement production | **NON ÉLIGIBLE sur cette preuve** | La fin d'I5 ne constitue pas une certification de production |
