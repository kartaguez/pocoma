# L7-LB — Définition et acceptation de V3_REFERENCE_BASELINE

13 novembre 2026 · proposition pour revue · [cadrage](L7_Local_Baseline_Scoping_Report.md).
**Aucune candidate n'est acceptée, aucun tag n'est créé par ce document.**

## 1. Garantie et identité du jalon

Une référence Git identifiée permet à un développeur de récupérer, installer
et démarrer Pocoma V3 sur Mac M4 ARM64/16Go sans état Pocoma préalable, d'obtenir
un verdict automatique READY/NOT_READY, puis d'utiliser immédiatement Keycloak
et Bruno, les parcours annoncés et les contrats publics. Il peut arrêter,
redémarrer et réinitialiser sa seule instance avec diagnostics conservés.
La qualification porte sur les conditions locales décrites et les variantes
effectivement prouvées. Elle ne vaut ni production-ready, ni certification
globale SPEC-09/10, ni homologation cloud/HA ou des topologies alternatives.

Le nom de tag proposé est `v3-reference-baseline-local-1` (à arrêter au gel).
Il est immuable ; un correctif ultérieur donne une nouvelle référence et son
propre manifeste. Le verdict humain **`V3_REFERENCE_BASELINE_ACCEPTED`** est
distinct de READY runtime et de tout verdict de test/certification historique.

## 2. Manifeste de publication attendu

Manifeste versionné avec schéma, sans secret, lié au SHA candidate exact :

| Rubrique | Données minimales et contrôle |
|---|---|
| Git | Dépôt, branche source, commit parent de départ, SHA candidate, dirty=false, nom tag proposé puis tag accepté ; comparaison candidate→publication document-only si applicable |
| Build/outils | JDK/JRE21 et fournisseur/version, Maven Wrapper/distribution, Node/Bruno CLI et lockfile, Python/k6, Docker/Compose ; commandes froid/chaud et hashes JAR |
| Plateforme | macOS/M4/ARM64, RAM hôte et VM, CPUs effectifs/quotas, disque libre/FS, runtime conteneur et architecture native observée |
| Images | Références versionnées et digests arm64 des quatre images Pocoma, PostgreSQL, Keycloak, Prometheus, Grafana si livré ; Materialize partage deux rôles avec config distincte |
| Configuration | Hash des profils cinq JVM, Compose rendu expurgé, pools/quotas/partitions/leases/polling, issuer/client/redirect publics ; aucun token, credential ou clé d'idempotence secrète |
| Bases et identité | Version PG effective, inventaire/checksums DDL/grants/contraintes/routage, maintenance et rôles ; realm modèle et hash expurgé ; mode bootstrap CURRENT ou baseline TRUSTED seulement si réellement certifiée |
| Jeux de données | Version/seed et manifeste sémantique demo ; seeds/snapshots qualification/perf séparés, comptes réels et état initial/final ; rien de sensible dans Git |
| Contrats | Version/hash du paquet API public, endpoints et modèles, erreurs, AuthN/AuthZ, pagination, versionnement, cohérence asynchrone ; collections Bruno et compatibilité outils |
| Qualification | Q1–Q10 critère par critère, obligation par ID, catégories de faisabilité distinctes des résultats, SHA/tests/commandes/hashes, rapports XML/PG/HTTP/metrics, inventaire attendu/exécuté, FAIL/interruption conservés |
| Réserves | Registre A/B/C, propriétaire, origine, garantie affectée, preuve/limite, action et décision humaine ; pas seulement liste de noms |
| Intégrité et récupération | Index/hashes SHA-256 des archives, lieu durable accessible, procédure de restitution et de nouvelle installation, licences et dépendances utiles |

Un manifeste ne s'auto-hashe pas et ne prétend pas connaître son propre SHA de
commit futur. Le paquet final peut référencer le commit technique gelé et un
commit documentaire descendant, avec diff et hypothèses comparés ; les gates
restent attribuées au SHA exécuté. Si une entrée productive/config/oracle
change après les gates, requalifier ce qu'elle invalide avant acceptation.

## 3. Politique proposée des réserves

| Classe | Critère | Effet et recevabilité |
|---|---|---|
| **A — Bloquant baseline** | Installation/readiness/reset non fiables, parcours annoncé impossible, invariant métier/sécurité non prouvé ou violé, preuve non attribuable, reproductibilité ou contrat public trompeur | Pas de gel accepté ; correction ou preuve suffisante obligatoire. Une omission d'allocation critique est A. Les15 partielles L7.3 sont A jusqu'à clôture recevable ; pas de dérogation locale de sûreté |
| **B — Réserve admissible** | Limite précise sans contradiction avec garanties revendiquées : délai de reprise lease240s documenté si compatible, ergonomie mineure, nom interne sans effet utilisateur sous réserve du contrat L7 encore dû | Proposition argumentée avec preuve d'absence d'impact sur garanties, propriétaire, tâche/critère de levée et acceptation humaine nominative ; aucune acceptation automatique par l'auteur |
| **C — Qualification contractuelle différée** | Obligation due dont conditions externes ne sont pas réunies : socle SPEC-09 A/B et volumes dédiés, qualification cloud/exploitation/HA | Garder texte/seuil/oracle et verdict NOT_RUN/FAIL/PARTIAL brut, cause physique, matériel/protocole requis, propriétaire L8 et point de reprise. Ne pas déclarer satisfaite ou réduire exigence |

Une réserve peut avoir deux objets liés : SLO A non mesuré en C et invariant
local sous-jacent en A. Les séparer explicitement, pas affecter la totalité en
C. Une performance locale réellement mauvaise ne devient pas absence de cloud :
elle reste un résultat local FAIL ou une limite locale à traiter selon la
garantie revendiquée. Le défaut d'outil ou d'oracle ne prouve jamais un besoin
externe. Toute reclassification demande motif et revue ; elle ne ferme pas
l'obligation technique et conserve ses verdicts historiques.

## 4. Critères nécessaires à V3_REFERENCE_BASELINE_ACCEPTED

1. Candidate gelée et récupérable, manifeste complet sans secret, images/outils
   identifiés, sources applicatives/config cohérentes avec les preuves.
2. **Q1–Q10 évaluées critère par critère**, pas dix cases vertes globales. Deux
   installations indépendantes sans volumes/images/JAR/cache Pocoma préalables
   démontrent start→READY→Bruno avec comptes réels. Les prérequis téléchargés
   sont déclarés ; un cache Maven de l'auteur ne remplace pas une installation.
3. start ne fait que préparation/readiness/smoke, avec résultat global et code ;
   les fautes ciblées PG/IdP/worker produisent NOT_READY nonzero, nom du contrôle,
   logs et environnement préservés. status ne recycle pas une preuve périmée.
4. stop/restart et reset explicite sont prouvés, état demo déterministe,
   donnée étrangère intacte ; verify/bench ne polluent ni n'effacent demo.
5. Contrat API et parcours utilisateur autonomes vrais : identité, invitation,
   mutations, résultats, READ, suppression et histoire ; confidentialité,
   autorisation, bornes et cohérence asynchrone vérifiées. Une fixture READ
   injectée n'est pas créditée comme admission/E2E public.
6. Allocation des522 IDs et réserves pertinentes achevée ; **aucune A ouverte**.
   Les15 réserves sont résolues par preuves exactes recevables et revue ; les
   338 autres contributions partielles L7.3 sont examinées, sans promotion
   collective ni exigence de simple « toutes lignes PASS » cachant la portée.
7. Protocoles locaux sûreté/reprise/READ/observabilité réalisés et attribués,
   global gates fraîches de stabilisation prévues par la politique (full non
   filtré et Architecture), XML/inventaire/variantes et zéro skip selon contrat.
   Un test en quarantaine requis n'est pas exempté ; chaque FAIL reste visible.
8. Profil performance local préenregistré, données et offre valides, mesures et
   coûts/limites publiés ; les chiffres restent locaux. SPEC-09 A/B NOT_RUN et
   obligations globales externes sont clairement affichés en C, pas certifiés.
9. Audit indépendant du paquet, des frontières de preuve, contrats et réserves ;
   archives accessibles et vérifiées, pas seulement chemins temporaires.
10. Responsable humain accepte explicitement la candidate **et chaque B/C**,
    puis autorise le tag. Sans cet acte : candidate reviewable seulement.

Ces critères définissent la baseline locale sans déclarer L7.3/L7.4/L7.5 clos.
Si un contrat de lot exige une gate additionnelle, elle demeure due ; un gel
local ne constitue pas une dispense tacite. Tout désaccord sur les garanties
à revendiquer est résolu avant le verdict d'acceptation, sans affaiblir les SPEC.

## 5. Procédure de gel et publication

1. I6 sélectionne SHA propre, enregistre scope et matrice requise avant campagnes,
   contrôle les entrées et exécute les gates/protocoles sous propriétaire unique.
2. Consolider reçus, Q1–Q10, index archives, contrats et réserves. Identifier
   séparément preuves reprises, essais frais, non exécutés et conditions externes.
3. Publier la candidate documentaire selon conventions Git, sans force ; vérifier
   branche/SHA distant et absence de modifications concurrentes. Une divergence
   bloque : pas de rebase forcé ni de verdict fabriqué sur l'ancien état.
4. Soumettre un paquet concret : SHA candidate et publication, manifeste,
   rapports, audit, liste A vide, B/C avec acceptation nominative proposée.
5. **Décision humaine d'acceptation**, puis mandat de tag sur le commit accepté ;
   créer/publier le tag seulement sous cette autorisation. Vérifier la cible
   distante et récupération depuis Git de la référence finale.
6. Consigner l'acte d'acceptation et le tag effectif dans un reçu ultérieur,
   sans réécrire les résultats des campagnes ni leur SHA.

## 6. Décisions humaines restantes

D1 démarrage/readiness/smoke, D2 données/reset, D3 échec conservé, D4 Bruno réel
et D5 publication sont **déjà validés**, ils ne sont pas remis en arbitrage.
Les étapes restantes sont les GO I1 puis implémentation I2 dans leurs scopes,
la ratification du profil local avant mesures I5 et des modalités SPEC-10 avant
futures campagnes externes, la revue des clôtures de réserves, puis l'acte de
gel/acceptation et le tag. Aucun budget cloud ni choix Scaleway n'est nécessaire
pour engager L7-LB. L8 récupérera le registre C avec ses conditions, jamais
une certification inventée par le changement de calendrier.
