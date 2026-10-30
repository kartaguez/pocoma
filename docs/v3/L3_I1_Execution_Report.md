# Pocoma V3 — L3 Identity — Rapport I1

**30 octobre 2026 — I1 implémenté ; gates obligatoires non satisfaites.**

Verdict **I1 LOCAL BOUNDED PASS : NON PRONONCÉ**. Les contrats et tests I1 sont
livrés ; la clôture de vérification reste non acquise.

GO humain limité à I1, suivant le §6 du
[plan approuvé](L3_Identity_Inventory_and_Implementation_Plan.md).
Baseline / Start HEAD : `140d13c3101a4e297bdea5141d167d189b7dcd02`.
Commit d’implémentation : `ddffcb44c92d02d335f41b55122aa6c7d885d0e2`.
Le commit documentaire qui contient ce rapport est communiqué avec son SHA final
et la confirmation distante dans la livraison, sans auto-référence circulaire.

## Périmètre déclaré avant implémentation

- Impact production attendu et observé : valeurs/transitions pures et contrats
  applicatifs dans `domain-user-identity`, `contracts-registration` et
  `engine-consume-registration` ; aucune implémentation d’adaptateur ou de participant.
- Slice primaire : **COMMAND** (`runtime-command-consumption-worker`).
- Slices secondaires : **NONE**. Le graphe Maven transitif de la gate architecture
  compile/teste d’autres consommateurs ; ce n’est pas un changement de leur production.
- Feedback local : les trois modules ci-dessus et leur fermeture `-am`.
- Gate architecture globale : **REQUIRED / ATTEMPTED / NOT PASSED**, car les deux
  arêtes Maven prévues au plan sont ajoutées. COMMAND et architecture sont composées
  selon la politique Reactor §34, sans filtre ni skip ajouté. COMMAND échoue et Maven
  saute architecture-tests. Le monolithe est exécuté transitivement avec succès.
  Une contribution ciblée exécute ensuite les 27 classes architecture sur le graphe
  courant ; elle est explicitement filtrée et ne remplace pas la gate non satisfaite.
- Full reactor canonique `./mvnw test` : **NOT REQUIRED / NOT RUN**. Aucune
  restructuration large, clôture de Wave majeure ou intégration main dans I1.
  La gate architecture reste une preuve globale et sa fermeture est large.
- DB : **NONE pour I1 pur** ; PostgreSQL et migrations historiques exécutés
  transitivement par les tests existants des gates, sans campagne séparée.
- Impacts interdits : SQL/migration nouvelle, raccord Consumption Identity,
  activation runtime, endpoint/consentement externe, modification L1/L2 ou
  Binding V2, démarrage I2–I5.
- Escalade prévue : réviser le scope avant toute modification d’un contrat V2,
  d’un autre consommateur, de l’owner ou d’une frontière non prévue.
- Franchissement inattendu : **NONE**. Clôture globale I1 encore non acquise ;
  I2 requiert audit indépendant et GO distinct.

## Résultat des gates et classification

La commande composée non filtrée termine en **BUILD FAILURE** : 1 795 tests,
2 échecs, 0 erreur, 1 skip. `architecture-tests` est SKIPPED dans son résumé reactor.
Les 35 nouveaux tests I1 passent dans cette exécution et dans la preuve locale finale.

- `V3WorkerResourcesPostgresTest.i5_r05` : `B missing independent available window` ;
  appel de `I5TemporalV2Postgres.campaign(PG, 1, 1)` et assertion du Journal.
- `V3WorkerResourcesPostgresTest.i5_r12` : `runtime condition deadline` ;
  campagne à deux workers puis expiration de l’attente de fermeture du runtime.

Les trois sources des tests et du helper sont identiques octet pour octet à la
baseline (empreintes dans le reçu). Ces tests appartiennent aux campagnes L2
couvertes par les réserves R-L2-01/02 ; cela ne prouve pas que ces deux issues
exactes se reproduisent à la baseline : **aucune reproduction sur baseline n’a
été exécutée**. Aucun nouveau défaut de sûreté ni absence de régression L2 n’est
déduit de ce seul classement. Réserves et acceptations L1/L2 restent inchangées.

La contribution architecture ciblée termine en **BUILD SUCCESS : 159 tests,
27 classes, zéro échec/erreur/skip**, sur la fermeture Maven courante. Le filtre
ne sélectionne que les classes du module architecture-tests ; les tests amont
ne sont pas réexécutés dans cette contribution. Ce résultat ne transforme pas
la gate composée en PASS. Les échecs initiaux, leurs messages et le skip sont
conservés intégralement. Aucun seuil, oracle, mécanisme L1/L2 ou règle d’architecture
n’est modifié ; aucune relance destinée à obtenir un PASS probabiliste.

La levée de cette non-clôture requiert une décision distincte sur la gate et ses
réserves ; elle n’est pas arbitrée par cette livraison I1. Aucun I2 commencé.

## Contrats et décisions

`ExternalUserIdentityId` est une valeur UUID stable, sans ordre ni génération.
`BindingToken` conserve exactement sa valeur opaque et masque son `toString`.
`ExternalUserIdentity` est un état historique immuable de version strictement
positive, avec User/token simultanément présents ou absents. L’absence est un
`Optional.empty()`, séparé de l’ID réservé ; aucune ancre zéro ne devient un état
métier. `ExternalIdentity`, `User` et `PocomaUserId` V2 sont réutilisés sans modification.

`IdentityTransitions` expose trois fonctions déterministes, recevant des états
connus/absents et les candidats fournis par l’appelant. Elles contrôlent la
cohérence clé/état/ID, y compris E1==E2, et retournent `Changed` (exactement un
nouvel état EUI, plus un User uniquement à Registration) ou `Rejected` avec motif
fermé. Aucun Unchanged, Binding autonome, version globale, horloge, aléa ou
transaction dans le domaine. Les snapshots incohérents et débordements de version
sont des fautes techniques, pas des rejets métier.

| Opération | Précondition et effet |
|---|---|
| Registration | EUI inconnue/libre ; nouveau User et token fournis ; état lié directement v1 ou N+1, ID conservé si existant. EUI liée : rejet sans User/état produit. |
| Link | E1 liée et token présenté exact ; U déduit exclusivement d’E1 ; E2 inconnue/libre devient liée v1/N+1 avec token neuf. E2 déjà liée, même au même User et self-Link : rejet. |
| Unlink | E1 liée et token exact ; E2 liée au même U ; E2 devient libre N+1 et token null. Pas de token E2 exigé ; self-Unlink et dernier Unlink possibles, sans suppression du User. |

Les quatre motifs sont `INVALID_BINDING`, `TARGET_ALREADY_BOUND`,
`TARGET_NOT_BOUND`, `TARGET_BOUND_TO_ANOTHER_USER`. Les paramètres obligatoires
null sont refusés. La fraîcheur historique globale des candidats et l’unicité
persistée du couple issuer/subject sont des préconditions/obligations explicites
de l’autorité ; la preuve durable relève d’I2. Le domaine refuse aussi la
réutilisation du token actif visible d’E1 comme candidat de Link.

`ExternalUserIdentity.compareKeys` est l’unique comparateur canonique : validation
complète des deux clés, UTF-8 strict avec REPORT, `Arrays.compareUnsigned`, issuer
puis subject. NUL et UTF-16 mal formé sont refusés. Aucun trim, casse, URI ou Unicode
normalisé ; aucun ordre UUID, octets signés ou concaténation des champs.

`IdentityRequest` scelle Registration/Link/Unlink. Registration cible uniquement
requesterEui et omet le token ; Link/Unlink exigent token initiateur et cible
exacte. Aucun UserId destinataire. Le contexte d’admission est immuable et capture
validateur/version, E1/E2, empreinte d’intention, référence d’octroi/corrélation,
instant, attestations copiées défensivement et origine. Les identités doivent
correspondre à la demande. L’empreinte est capturée depuis la frontière de
confiance ; sa production et les preuves fournisseur externes ne sont pas
implémentées en I1. Demande et contexte masquent les secrets en diagnostic.

Les trois ports sont des interfaces cohésives, destinées à l’injection par
constructeur des futurs composants, sans implémentation ni injection Spring :
`IdentityAuthority` réserve/protège en ordre commun et expose état courant,
application exacte, histoire à version positive et User ; `IdentityRequestStore`
porte insertion PENDING idempotente, TERMINAL/résultat, contrôle pré-completion
et publication finale ; `IdentityRequestObservation` impose le contexte RO de
recovery L2 et réutilise `RecoveryObservation`, sans réparation ni owner privé.
Le résultat public CHANGED ne contient que cible/version ; REJECTED porte un motif
fermé, aucun token ni UserId. `Stored` impose TERMINAL si et seulement si le
résultat est présent.

## Inventaire exact

Douze fichiers Java créés (neuf production, trois tests), un POM modifié.

| Statut | Chemin exact |
|---|---|
| CRÉÉ | `app/contracts-registration/src/main/java/com/kartaguez/pocoma/contracts/registration/v3/IdentityRequest.java` |
| CRÉÉ | `app/domain-user-identity/src/main/java/com/kartaguez/pocoma/domain/useridentity/v3/BindingToken.java` |
| CRÉÉ | `app/domain-user-identity/src/main/java/com/kartaguez/pocoma/domain/useridentity/v3/ExternalUserIdentity.java` |
| CRÉÉ | `app/domain-user-identity/src/main/java/com/kartaguez/pocoma/domain/useridentity/v3/ExternalUserIdentityId.java` |
| CRÉÉ | `app/domain-user-identity/src/main/java/com/kartaguez/pocoma/domain/useridentity/v3/IdentityTransitionResult.java` |
| CRÉÉ | `app/domain-user-identity/src/main/java/com/kartaguez/pocoma/domain/useridentity/v3/IdentityTransitions.java` |
| CRÉÉ | `app/domain-user-identity/src/test/java/com/kartaguez/pocoma/domain/useridentity/v3/IdentityTransitionsTest.java` |
| CRÉÉ | `app/domain-user-identity/src/test/java/com/kartaguez/pocoma/domain/useridentity/v3/IdentityValueContractsTest.java` |
| MODIFIÉ | `app/engine-consume-registration/pom.xml` |
| CRÉÉ | `app/engine-consume-registration/src/main/java/com/kartaguez/pocoma/engine/consume/registration/v3/IdentityAuthority.java` |
| CRÉÉ | `app/engine-consume-registration/src/main/java/com/kartaguez/pocoma/engine/consume/registration/v3/IdentityRequestObservation.java` |
| CRÉÉ | `app/engine-consume-registration/src/main/java/com/kartaguez/pocoma/engine/consume/registration/v3/IdentityRequestStore.java` |
| CRÉÉ | `app/engine-consume-registration/src/test/java/com/kartaguez/pocoma/engine/consume/registration/v3/IdentityRequestContractTest.java` |

Huit fichiers de livraison créés, sans changement des SPEC ni du plan normatif :

- `docs/v3/L3_I1_Execution_Report.md`
- `docs/testing/evidence/L3-I1/Verification.json`
- `docs/testing/evidence/L3-I1/local.log.gz`
- `docs/testing/evidence/L3-I1/local-final.log.gz`
- `docs/testing/evidence/L3-I1/gates.log.gz`
- `docs/testing/evidence/L3-I1/architecture-feedback.log.gz`
- `docs/testing/evidence/L3-I1/architecture-command.txt`
- `docs/testing/evidence/L3-I1/testcases.json.gz`

## Tests exécutés et preuves

Depuis `app/`, commandes réellement exécutées :

```sh
# Deux exécutions : feedback initial puis validation finale après revue des IDs candidats.
./mvnw -pl domain-user-identity,contracts-registration,engine-consume-registration -am test
# Une seule exécution composée non filtrée, fermeture commune exécutée une fois.
./mvnw -pl runtime-command-consumption-worker,architecture-tests -am test
```

La commande exacte du diagnostic architecture, avec la liste explicite des
27 classes dans `-Dtest` et `-Dsurefire.failIfNoSpecifiedTests=false`, est conservée
dans [architecture-command.txt](../testing/evidence/L3-I1/architecture-command.txt).
Il s’agit d’un feedback ciblé supplémentaire, pas d’une gate canonique substituée.

| Exécution | Tests | Échecs | Erreurs | Skips | Résultat |
|---|---:|---:|---:|---:|---|
| local | 109 | 0 | 0 | 0 | SUCCESS (2.860 s) |
| local-final | 109 | 0 | 0 | 0 | SUCCESS (2.738 s) |
| gates | 1795 | 2 | 0 | 1 | FAILURE (44:05 min) |
| architecture-feedback | 159 | 0 | 0 | 0 | SUCCESS (44.147 s) |

Skip constaté dans la gate composée non filtrée (aucun test I1 sauté) :

- `com.kartaguez.pocoma.runtime.command.consumption.sequencing.TerminalSequencingPostgresTest.zExploratoryContentionSeparateFromCorrectness` : System property [step3b.measure] does not exist

`step3b.measure` est une mesure exploratoire de contention existante, distincte
des tests de correction ; aucune nouvelle campagne de mesure n’est activée.

Les **35 nouveaux tests I1**, tous PASS sans skip, se répartissent en :
`IdentityTransitionsTest` 17, `IdentityValueContractsTest` 8,
`IdentityRequestContractTest` 10. Ils sont exécutés dans la preuve locale finale
et dans la gate composée. Ces deux exécutions utilisent exactement les mêmes
13 fichiers d’entrée, dont les SHA-256 figurent dans le reçu. Le feedback initial
précède le dernier contrôle du candidat EUI ID et ne remplace pas ces exécutions finales.

Couverture : transitions/rejets et absence/liberté/rattachement ; E1==E2,
self-Link/self-Unlink, initiateur absent/libre/token périmé/tiers/inventé,
changement d’owner seulement via Unlink puis Link, histoire locale 1…5 et
immutabilité des anciennes valeurs, versions positives et overflow, tokens,
cohérence des snapshots, demande/provenance immuable, forme publique du résultat.
Vecteurs Unicode attendus indépendants du comparateur : ASCII, préfixes,
U+007F/U+0080, U+E000/U+10000 (ordre opposé à UTF-16), accents composés/décomposés,
hors BMP, même subject/deux issuers, toutes permutations des groupes et doublons
exacts. Refus de NUL et de surrogates isolés dans chaque champ/opérande ; aucune
substitution ni normalisation. La parité PostgreSQL COLLATE C et le refus de
séquences d’octets UTF-8 invalides côté SQL sont des preuves **I2 NOT RUN**.

Le [reçu détaillé](../testing/evidence/L3-I1/Verification.json) contient commandes,
issues par suite, durées, empreintes des sources et archives. Les quatre journaux
sont conservés intégralement, compressés sans perte ; `testcases.json.gz` conserve
les noms/issues/durées des cas de la gate échouée et du feedback, avec leur run
d’origine. Les XML de la gate sont contrôlés frais puis photographiés avant le
diagnostic ; ceux du feedback sont contrôlés contre son propre instant de départ.
Les décomptes proviennent du journal courant et sont corroborés par les rapports
Surefire des seules suites exécutées. Décompression : `gzip -dc <fichier.gz>`.

Les gates exécutent les tests historiques DB (PRIMARY jusqu’à V31, READ jusqu’à
V14), les fixtures V3 WRITE jusqu’à V5/READ technique V1 et les tests L2 inchangés
présents dans leur fermeture. Aucune migration livrée, modifiée ou campagne
historique distincte ; aucune baseline V2 TRUSTED revendiquée. PostgreSQL observé
17.11 ; Java Temurin 21 ; Maven 3.9.14 ; environnement détaillé dans le reçu et les journaux.
Les résultats transitifs frais ne changent ni les réserves, ni les campagnes
historiques, ni l’acceptation de L1/L2. Aucun certificat temporel/production délivré.

## Architecture et dépendances

Les deux dépendances directes de `engine-consume-registration` sont justifiées
par les types exposés : `engine-consumption` pour `RecoveryObservation`,
`port-transaction` pour `RecoveryReadContext`, sans transitivité implicite par
Binding V2. Aucun POM domaine/contrats ni nouveau module modifié/créé.

La contribution architecture ciblée applique notamment les règles de pureté du domaine, indépendance
des couches, topologie Maven sans cycles et isolation des engines/runtimes.
Aucune règle ni allowlist n’est modifiée ; la gate échouée reste enregistrée en FAIL.
Contrôle statique complémentaire des cinq sources domaine V3 : imports limités
à `java.*` et au domaine Identity réutilisé, pas d’aléa/horloge/transaction.
`git diff --check` est propre. Les contrôles Git/statut/branche/HEAD/distant,
lectures `rg`/`cat`/`sed`, `java -version`, `./mvnw --version`, scripts de collecte/hash et des `jcmd 92743 Thread.print` / `jcmd 2414 Thread.print`
en lecture seule ont accompagné la vérification. Le premier accès réseau et les
premiers accès Docker/attach restreints ont été refusés par le sandbox ; les
accès autorisés nécessaires ont ensuite réussi, sans modifier les oracles.

## Git, préservation et limites

Worktree utilisé : `/private/tmp/pocoma-v3-postaud`, branche
`feat/v3-architecture`, propre à la reprise, baseline exacte.
`git ls-remote origin refs/heads/feat/v3-architecture` a confirmé cette baseline
avant implémentation. Le checkout principal est resté sur `feat/runtime-monolith`
au SHA `90a3e9bc1a1fdd325d0c36e543824239cd85a431`, avec ses travaux préexistants ;
aucun checkout/reset/stash/clean sur celui-ci ou un autre worktree.

La publication normale, sans force push, exige un dernier contrôle du HEAD et
de la tête distante. Le SHA final de livraison, la réussite du push, la divergence
finale et le statut du worktree sont confirmés dans le message final après
publication. Les preuves applicatives portent sur le commit d’implémentation
ci-dessus ; le commit suivant ajoute seulement ce rapport et ses preuves.

Écarts de production au plan : **NONE**. Écart de vérification : gate composée
en échec et architecture canonique non atteinte ; contribution architecture ciblée
ajoutée explicitement, sans substitution de preuve. Aucun élargissement productif.
Implémentation I1 terminée, validation bornée non acquise ;
**I2, I3, I4, I5 NOT STARTED**. Autorité PostgreSQL, garanties durables/concurrence
Identity réelle, participant, génération sécurisée des candidats, raccord
runtime, preuve fournisseur externe, HTTP et READ restent hors de cette livraison bornée.
