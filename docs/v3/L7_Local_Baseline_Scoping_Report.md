# L7 — Cadrage de la Reference Baseline locale

13 novembre 2026 · mandat humain `GO_L7_LOCAL_BASELINE_SCOPING`.
**Analyse et documentation seulement ; aucune autorisation d'implémentation.**

## 1. Conclusion et périmètre

**`READY_FOR_L7_LOCAL_BASELINE_IMPLEMENTATION` pour engager le premier incrément sous un nouveau GO.**
Le dépôt possède les capacités principales ; il manque leur assemblage en une
distribution locale persistante, vérifiable et indépendante de l'état précédent.
Le chemin prioritaire est **start → READY → Bruno**, puis fermeture des lacunes
de sûreté, caractérisation locale et publication après acceptation humaine.
Ce verdict porte sur la préparation du travail, pas sur la readiness du produit.

La cible est un Mac M4 ARM64 / 16 Go, cinq processus JVM, PostgreSQL WRITE et
READ séparés logiquement, Keycloak réel et observabilité existante. Docker
Compose suffit à l'orchestration locale envisagée ; aucune VM supplémentaire,
ressource cloud, dépense, BFF ou modification métier n'est nécessaire au cadrage.
La faisabilité complète reste à démontrer sur installation vierge.

Le jalon proposé `V3_REFERENCE_BASELINE` est indépendant d'une certification
production et ne clôt ni L7.3, ni L7.4, ni L7.5, ni L7, ni V3-7 implicitement.
Les campagnes SPEC-09 non recevables sur ce matériel sont transférées comme
**obligations dues à L8**, avec leurs seuils et verdicts historiques intacts.

## 2. Référence Git et discipline de preuve

| Élément | Constat de départ |
|---|---|
| Branche inspectée | `feat/v3-architecture` |
| SHA initial | `e15f485867cb1f9d630738c25272af698ff5885b` |
| État | Worktree et index propres |
| Synchronisation | `git ls-remote --heads origin feat/v3-architecture` donne ce même SHA ; première tentative sandbox sans DNS, seconde autorisée réussie |
| Branche de publication | `docs/l7-local-baseline-scoping`, dédiée selon [CONTRIBUTING](../../CONTRIBUTING.md) |
| Vérification déclarée avant édition | Primaire NONE ; secondaires NONE ; impact productif interdit ; DB NONE ; architecture globale NOT REQUIRED ; full NOT REQUIRED / NOT RUN |

L'audit lit les sources suivies, les rapports de clôture et des reçus conservés.
Il ne sonde ni Docker ni les bases. Les dimensions matérielles observées
antérieurement ne sont pas présentées comme un nouvel inventaire physique.
Un comportement décrit par le code n'est pas déclaré démontré. Les preuves
historiques restent attribuées à leur SHA, variante, outil et environnement.
Leur réemploi ultérieur demande la comparaison des entrées/hypothèses prévue
par la [politique §34](../testing/Reactor_Verification_Policy.md).

## 3. Livrables et sources d'autorité

| Livrable | Point d'entrée unique |
|---|---|
| Situation, contradictions, ressources, recommandations | Présent rapport |
| Dix questions, critères, acquis, preuves, lacunes, blocage, local/différé | [Matrice Q1–Q10](L7_Local_Baseline_Acceptance_Matrix.csv) |
| Scripts, Compose, identité, Bruno, tests et bancs à consolider | [Inventaire](L7_Local_Baseline_Reusable_Inventory.csv) |
| SPEC-10, 15 réserves L7.3 et réserves héritées | [Obligations et réserves](L7_Local_Baseline_Obligations_and_Reservations.md) |
| Faisabilité locale distincte du verdict pour les 31 lignes SPEC-09 existantes | [Matrice SPEC-09 locale](L7_Local_Baseline_SPEC09_Feasibility_Matrix.csv) |
| Commandes, readiness, isolation, données, Bruno et contrat public | [Contrat développeur](L7_Local_Baseline_Developer_Experience_Contract.md) |
| Incréments, tâches, preuves, effort et chemin critique | [Plan incrémental](L7_Local_Baseline_Incremental_Implementation_Plan.md) |
| Manifeste, critères de gel, réserves A/B/C et acceptation | [Définition du jalon](L7_Local_Baseline_Milestone_and_Acceptance.md) |
| Contrôles documentaires réellement effectués et empreintes | [Reçu documentaire](L7_Local_Baseline_Documentary_Verification.json) |

Les références contractuelles restent [SPEC-09](SPEC-09_Performance.md),
[SPEC-10](SPEC-10_Test_Certification.md), le [canon](V3_Canon_and_Roadmap.md),
le [plan directeur](V3_Implementation_Master_Plan.md) et la politique.
La [matrice historique de 522 IDs](L7_I1_Certification_Coverage_Matrix.csv)
n'est ni copiée ni modifiée : les nouveaux tableaux sont des vues de convergence.

## 4. Acquis réellement réutilisables et limites

| Corpus | Acquis rapporté / source | Limite appliquée ici |
|---|---|---|
| L5 | [Clôture Q5-D](L5_Q5D_Formal_Closure_Decision.md) : 2 344 tests, 170 Architecture, B5 composite 70 scénarios au SHA `2890d88f…` | Ancienne composition ; B3 Link retiré ensuite, pas de réintroduction. Ne pas reporter 70 scénarios comme suite courante complète |
| L6/I1 | [Clôture](L6_I1_Acceptance_and_Closure_Decision.md) : PKCE, CreatePot, READ et métriques réelles, Bruno démo 8 requêtes | Preuve Monolith ; première Gate B V2 invalide conservée. Le lanceur existant n'est pas une distribution cinq JVM |
| L6/I2 | [Clôture](L6_I2_Acceptance_and_Closure_Decision.md) : maintenance exclusive, CAS, reprise, oracle conservateur, contrôles ciblés 28/12/12 | Reconstruction READ ≠ reset WRITE+READ+realm. Les 7 réserves d'exploitation demeurent |
| L7 restructuration | [Rapport](L7_Modular_Refactoring_Implementation_and_Qualification_Report.md), 42 modules enfants ; quatre JAR pour cinq processus | Isolation de composition et preuve locale ne garantissent ni packaging vierge ni ressources dédiées |
| L7.1 cinq runtimes | [Rapport monohôte](L7_I1_Five_Runtime_Single_Host_Qualification_Report.md) | Utilisait un volume avec 1 163 quarantaines préexistantes ; noms, versions, quotas et commandes Maven historiques différents des fichiers actuels |
| L7.2 | [Rapport fonctionnel](L7_I2_Functional_Security_and_Bruno_Certification_Report.md) : recette vierge Keycloak réel, B2/B4/B501–502 ; gates full et Architecture 1 057 invocations chacune au SHA `5ae89228…` | Couverture par variante toujours partielle ; le lanceur nettoie ses ressources dans `finally`, même en échec. Pas le comportement D3 demandé |
| L7.3 final | [Rapport final](L7_I3_Final_Certification_Report.md) et [réconciliation](../testing/evidence/L7-I3-final/reconciliation.json) : 31 obligations rapprochées, 16 complètes et 15 partielles bloquantes | L7.3 NOT_CERTIFIED ; 338 autres contributions partielles hors sous-ensemble non promues |
| L7.4 technique | [Rapport](L7_I4_SPEC09_Technical_Qualification_Report.md) : E1/E3, oracles fractions, SQL READ ONLY, provenance et propriété | Quatre populations E1 de 12, pas un échantillon SPEC. E3 après admission, pas crash READ pendant calcul. Une seule candidate E02-J |

## 5. Écarts et contradictions à résoudre

1. **Orientation de programme.** Le dernier [cadrage A/B](L7_I4_AB_Infrastructure_Scoping_and_Design_Report.md)
   recommande Scaleway temporaire et un budget ; ce mandat humain remplace cette
   orientation immédiate par le local sans dépense. L'ancien rapport garde sa
   valeur historique et ses critères SPEC-09 ; son GO cloud n'est pas demandé.
2. **Répartition L7/L8.** Le plan directeur §7 affectait l'installation vierge,
   Bruno final et la distribution à L8, avec Monolith prioritaire. Le mandat
   actuel affecte le socle local cinq JVM à un sous-programme **L7-LB** proposé,
   L8 conservant distribution réelle/cloud/topologies alternatives/exploitation.
   Cette exception de périmètre vient du mandat, sans clôture rétroactive ni
   obligation de livrer la parité Monolith dans la baseline annoncée cinq JVM.
3. **Dernier état de L7.3.** La proposition de completion précédente mentionne
   30 partielles et une absence ; le rapport final porte 15 partielles et zéro
   absence sur les 31. L7.4 n'en promeut aucune. Retenir la séquence des actes,
   sans effacer les anciens tableaux ni confondre 31, 432 et 522 obligations.
4. **Installation.** Le [runbook courant](../operations/cmd-start-runtimes.md)
   a trois commandes. Compose ne lance pas le bootstrap SQL et les applications
   ont Flyway désactivé. `to_regclass` dans le bootstrap court-circuite un schéma
   déjà présent sans en prouver la complétude. Le reset partiel ou une migration
   interrompue doivent donc être détectés, jamais masqués par une table témoin.
5. **État et sécurité locale.** Projet, conteneurs, subnet `172.28.0.0/16` et
   volume `pocoma-functional_postgres-v3-data` sont fixes. `-p` seul n'isole pas
   ce Compose. PostgreSQL publie actuellement `5432:5432`, contrairement aux
   autres ports liés à loopback. Corriger l'exposition dans le futur socle local.
6. **Readiness.** Actuator/HTTP 200 ne démontre pas le pipeline complet ; DB
   health est désactivé. Les HealthIndicators Worker exposent un état RUNNING,
   mais il faut vérifier leur participation au groupe readiness et conserver
   les contrôles sémantiques SQL+OIDC+smoke. L'oracle READ de reconstruction est
   distinct de la readiness globale d'un système actif.
7. **Bruno et identité.** `Local.bru` pointe vers 8080 ; READ est à 8082 et les
   bibliothèques de banc transportent déjà deux URL. Le pre-request attend des
   secrets/fixtures B1 ou B2/B4 : ouvrir la collection ne suffit pas. Le helper
   PKCE existant valide state ; celui de `run-demo-journey.py` crée state
   mais ne le compare pas au callback. Consolider le premier, pas copier ce défaut.
8. **Documentation API.** Le README contient encore une description historique
   à neuf runtimes et erreurs 409 de projection. Le contrôleur V3 sert 503 pour
   READ non prêt après autorisation, et 409 pour conflit d'idempotence WRITE.
   Aucun OpenAPI courant trouvé sous `app/` ; un ancien fichier sous `graft/`
   n'est pas le contrat V3. Fournir une référence publique équivalente vérifiée
   sur DTO/contrôleurs suffit ; un générateur OpenAPI n'est pas un préalable.
9. **Métrologie.** Tags mobiles, ancien Monolith, sondes tardives Balance,
   métriques manquantes rendues zéro et drivers désormais désactivés interdisent
   un réemploi aveugle des commandes L6. Les [écarts A/B](L7_I4_AB_Infrastructure_Inventory.md)
   sont réutilisés comme inventaire, pas comme exigence cloud locale.
10. **Quarantaines de tests et clôtures.** Le texte historique de la politique
    évoque encore r05/r12 non résolus, mais le registre courant
    [verification-campaigns.json](../../scripts/testing/verification-campaigns.json)
    les marque `QUALIFIED`, `active=false`, avec la
    [clôture définitive R1/R2](L2_I5_Counter_Audit_and_Definitive_Closure_Report.md).
    Retenir cette clôture attribuée, sans rouvrir ces deux réserves ni confondre
    cette quarantaine historique de tests avec les slots métier QUARANTINED.
    Leur présence dans les gates requis reste obligatoire ; I1 doit rapprocher
    de la même façon les autres réserves anciennes avec leurs actes ultérieurs.

## 6. Ressources, ARM64, ports et construction

Les limites du Compose suivi totalisent **4 992 MiB** et **5,25 CPU** pour PG,
Keycloak, cinq JVM et Prometheus. Ce sont des plafonds, pas une consommation
mesurée ni des CPU réservés. Les heaps sont quatre fois 384 MiB plus 512 MiB,
soit 2 048 MiB ; ajouter metaspace, code cache, stacks, buffers et natif.
L'enveloppe Docker historique L7.4 est ~7,75 GiB pour tout l'invité.

Proposition de départ : Docker autour de **8 GiB**, CPU disponibles explicités,
build séquentiel avant runtime, pas de Maven/qualification parallèle au bench.
Garder une marge macOS ; mesurer RSS, pression mémoire et swap hôte, OOM et
throttling invité avant de ratifier le profil. Aucun relèvement automatique de
heap/pools/quota READ ou réduction de lease pour faire passer un test. Si 16 Go
ne suffisent pas avec les autres applications, diagnostiquer et séquencer les
modes ; ne pas prétendre mesurer un matériel dédié.

Grafana peut reprendre le provisioning existant avec une enveloppe proposée de
256 MiB supplémentaire (**5 248 MiB** cumulés), à qualifier : il n'est pas dans
le Compose cinq runtimes actuel. Un profil d'observabilité allégé peut utiliser
Prometheus seul pour le premier parcours, avec dashboard avant publication.
Générateur/observateur cohabitent localement et leur coût doit être mesuré.

Les images Java, PG, Keycloak et Prometheus devront être épinglées par version
et digest **linux/arm64**, architecture exécutée enregistrée, pas d'émulation
amd64 dans une mesure annoncée native. Le succès ARM64 historique Java/PG ne
prouve pas la disponibilité de chaque tag/digest actuel ; inspection des
manifestes et exécution native seront des contrôles futurs, pas exécutés ici.

Ports à contrôler avant effet : 5432 si publié, 8080 WRITE, 8082 READ, 8081 IdP,
18080/18082/18083/18084/18085 administration, 9090 Prometheus, 3000 Grafana
et 8765 callback PKCE. Détecter collision Docker/réseau/VPN et changement
d'issuer depuis le navigateur et les conteneurs. Éviter d'exposer PG si inutile ;
binding loopback pour tout port publié. Les modes jetables ont leurs ports et
issuer propres, sans modifier silencieusement le realm de démonstration.

Le Dockerfile répète `package -DskipTests` par module, avec COPY de tout `app/`
et tags de base mobiles. Construire les quatre artefacts en une sélection
composée est une piste ; réutiliser l'image Materialize pour ses deux rôles.
Le cache et `~/.m2` ne sont jamais prérequis cachés d'une installation vierge.
Évaluer à froid téléchargement/build/démarrage et à chaud redémarrage séparément.
Budgets de travail proposés : froid 10–30 min, start chaud 2–5 min, à mesurer
en L7-LB-I2 ; ce ne sont ni mesures acquises ni seuils contractuels.

## 7. État Q1–Q10 et suite

Les dix questions sont **PARTIAL** à cette référence : chacune a des acquis,
aucune n'a encore une preuve consolidée sur la future baseline vierge.
Q1/Q2/Q3 sont prioritaires ; Q4/Q5/Q6 sont bloquantes avant gel ; Q7 porte sur
la qualification locale valide avec limites SPEC-09 explicites ; Q8/Q9/Q10
conditionnent la réutilisation par des équipes indépendantes.
Les critères détaillés et les obligations différées sont dans la matrice Q.

Tous les D1–D5 du mandat sont acquis. Aucun nouvel arbitrage métier, aucun
budget cloud ni seuil contractuel ne bloque I1. Restent : GO d'implémentation
du premier incrément, ratification du profil local avant ses mesures, décision
sur chaque réserve B/C présentée avec ses preuves, et acceptation finale avant
création du tag. Un premier parcours peut être livré sans attendre la clôture
des 15 réserves, mais aucune publication acceptée ne les contourne.

## 8. Vérification de ce cadrage

Contrôles documentaires uniquement : liens locaux, formats CSV, dix Q uniques,
15 IDs exacts et 31 lignes SPEC-09 sans promotion, calcul de ressources/efforts,
empreintes des sources/preuves référencées, restriction du diff, `git diff --check`,
contrôle Git avant publication sans force. Les commandes et résultats effectifs
sont dans le reçu ; aucun de ces contrôles ne vaut campagne de certification.
Maven/Docker/Bruno/k6/SQL : **aucune exécution**. Migration : NONE.
Franchissement inattendu : NONE. Gate globale : NOT REQUIRED.
Full reactor : NOT RUN / NOT REQUIRED pour cette mission documentaire.
La future publication du jalon de stabilité aura au contraire ses gates
fraîches globales selon la politique ; ce cadrage ne les dispense pas.
