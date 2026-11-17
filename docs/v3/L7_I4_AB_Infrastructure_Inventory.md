# L7.4 — Inventaire des moyens et écarts A/B

Date : 13 novembre 2026 ; référence `bc562aeb`. Inspection de fichiers suivis et
de preuves conservées, sans connexion à Docker, aux bases ou à un compte cloud.
La disponibilité physique actuelle n'a pas été réinterrogée. Le constat matériel
est celui du [rapport technique L7.4](L7_I4_SPEC09_Technical_Qualification_Report.md),
complété par la machine M4/16 Go donnée dans le mandat.

## 1. Classification des moyens

Catégories demandées : **ABSENT**, **INCOMPLET**, **NON_REPRODUCTIBLE**,
**NON_CONFORME**, **CONFORME_NON_QUALIFIE**. Elles décrivent une capacité précise,
pas un verdict global porté sur tous les fichiers d'un outil. Aucun composant
ne reçoit le statut « conforme A/B qualifié » par simple inspection statique.

| Moyen et source | Ce qui existe effectivement | Écart / catégorie pour A/B | Réemploi et complément prévu |
|---|---|---|---|
| [Compose cinq processus](../../docker-compose.v3-five-runtimes.yml) | Deux WEB, un Process Command/Identity, deux Materialize, PostgreSQL, Keycloak, Prometheus | NON_CONFORME : monohôte ; PG1CPU/1Go, JVM0,5–0,75CPU/512–640M ; Keycloak utilise le même PG métier ; noms/réseau/volume fixes | Réutiliser mapping profils/ports/health ; nouveaux inventaires par hôte et namespace jetable, sans toucher au volume `pocoma-functional_postgres-v3-data` |
| [Dockerfile paramétré](../../app/Dockerfile) | Quatre modules runtime utilisables, plus Monolith ; Java21 ; build Maven par module | INCOMPLET : tags de base mobiles, pas de catalogue de digests par architecture ni de build multi-hôte scellé | Construire hors fenêtre mesurée, quatre images immuables ; même image Materialize pour deux rôles ; pas reprendre un JAR Monolith historique |
| [Cinq configurations](../../docker/v3-five-runtimes) | Rôles/principals distincts, partitions0..3, quantum4, polling2/20/50ms, batch32, claim60s, lease240s | INCOMPLET : secrets de développement, DNS/IP/CIDR du bridge local, issuer localhost, allowlist DEV explicite | Templates externes reprenant les réglages ; injecter secrets et identité physique distante exacte ; ne pas élargir l'allowlist ni contourner `validateTarget` |
| [Bootstrap courant](../../scripts/operations/setup-v3-database.py) | Bases `pocoma_write`/`pocoma_read`, schémas, rôles, migrations V3 WRITE V1..V11 et READ sélectionnées, grants, maintenance | INCOMPLET : docker exec local, credentials locaux, détection d'existence d'une table ≠ vérification complète du schéma ; en-tête V1..V10 ancien | Réemployer fichiers SQL et ordre réel ; un propriétaire de bootstrap sur cluster neuf, validation finale complète ; pas revendication baseline TRUSTED |
| [SQL de grants](../../scripts/v3), [init local](../../docker/postgres/functional-init.sql) | Routage/lifecycle/Discovery/admission, WRITE source SELECT, READ HTTP/status ; init Keycloak local | INCOMPLET : administration/observation distantes à borner | Distinguer bootstrap privilégié, rôles applicatifs et observateurs SELECT ; isoler la DB Keycloak sur sa VM |
| [PG16 local](../../app/docker-compose.postgres.yml) | Exemple de développement simple | NON_CONFORME pour comparaison avec PG17.11 de L7.4 | Ne pas l'utiliser comme socle A/B ; conserver PG/JDK identiques entre runs |
| [Émetteur JWT](../../scripts/performance/spec09-test-issuer.mjs) | RSA, JWKS public, claims requis, fichier0600, pas de route token ; contrôle Node | CONFORME_NON_QUALIFIE : authentification A vérifiée E1, pas topologie/charge E2 | Réutiliser sur auxiliaire isolé ; issuer résoluble depuis générateur et WEB, TTL couvrant run/drain ; clés par campagne hors Git |
| [E1/E3](../../scripts/performance/run-l7-spec09-e1.py), [collecteur](../../scripts/performance/spec09_bench.py) | Projet/réseau neufs, ownership label, cinq JVM réelles, observations SQL READ ONLY, provenance, nettoyage, crash après admission | NON_CONFORME aux SLO : PG tmpfs512M/1CPU, JVM256M,12 offres petit ; runner Docker local | Réemployer garde de propriété, reçus, calcul fractions et contrôles négatifs ; pas transporter tel quel le tmpfs ou le dimensionnement E1 |
| [Manifeste](../../scripts/performance/l7-spec09-campaigns.json), [rendu](../../scripts/performance/plan-l7-spec09.py) | Inventaire13 familles, seuils contrôlés statiquement, paramètres et commandes | INCOMPLET : rendu stimulus ≠ orchestration warm-up/reprise/reset/collecte ; pas de gate infrastructure distante | Source de paramètres à conserver ; compléter ordonnanceur séquentiel et reçus de phase sous futur mandat |
| [k6 V3](../../scripts/k6/v3-performance.js), [client](../../scripts/k6/lib/v3-api.js), [oracles](../../scripts/k6/lib/v3-oracles.js) | Charge ouverte, deux URL, fixtures prévalidées, isolation Pot/VU et counters | INCOMPLET : `Math.random()`/UUID pour payloads non appariés ; chemin courant principalement CreateExpense ; `Date.now()` ; retries possibles hors rendu ; branche Balance historique HTTP à vérifier, pas oracle SQL interne indépendant | Préparer intentions déterministes, bornes temporelles monotones du collecteur qualifié, mix et sonde exacte ; geler options, zéro retry d'offre implicite ; ne pas créditer SLO sur sortie k6 seule |
| [Seed V3](../../scripts/performance/v3-seed.py), [seed moyen](../../scripts/performance/seed_spec09_medium_pots.py) | Génération déterministe, HTTP WRITE/READ, contrôles SQL, export compact k6 ; grand200000 participations modélisé | INCOMPLET : matérialisation séquentielle Pot par Pot et Expense par Expense ; Docker SQL local ; grands profils gardés ; `estimatedEvents` et80Expenses/Pot globaux ne réalisent pas les comptes SPEC | Réutiliser format/oracles ; préparer gros snapshots par API/moteurs avec parallélisme entre Pots, compteur réel d'événements, reçus de complétion ; pas restaurer le générateur historique désactivé |
| [Registration](../../scripts/performance/run-l7-spec09-registration.py) | Sujets frais,1/s, warm-up180/sample1200, bornes send/202, censure, SQL via services libpq | CONFORME_NON_QUALIFIE : contrôles hors ligne passants, pas F01-E complet | Réutiliser scénario ; profiler cadence, services distants, coût et état initial neuf |
| [Keycloak](../../scripts/performance/run-l7-spec09-keycloak.py) | Realm/comptes synthétiques, PKCE S256, Direct Access Grants désactivé, Registration/Unlink et `issue-tokens` pour comparaison | CONFORME_NON_QUALIFIE : scénario statique ; serveur courant `start-dev` et DB partagée non admissibles cible | VM IdP propre avec PG propre, realm restauré, configuration issuer/hostname atteignable et protocole OIDC constant |
| [Stock](../../scripts/performance/stock-l7-spec09-backlog.py), [rapprochement](../../scripts/performance/observe-l7-spec09-backlog.py) | 10000 descripteurs, admission unique, capture/reconcile SQL READ ONLY, résultat+projections+couvertures | INCOMPLET : population10000 moyens et calibration à l'échelle absentes ; nouveau flux1/s et redémarrage à orchestrer | Conserver oracles ; partitionner/borner requêtes de réconciliation et inclure coût dans chrono300s |
| [Observabilité cinq rôles](../../docker/v3-five-runtimes/prometheus.yml), [dashboards](../../docker/grafana/dashboards), [overlay](../../docker/compose/docker-compose.observability.yml) | Scrape5s, Micrometer/JVM/Hikari, dashboards runtime/V3/rebuild et provisionnement Grafana | INCOMPLET / NON_REPRODUCTIBLE pour tags `latest` ; cibles locales ; Grafana exemple anonyme ; métriques hôte/disque/âge et run attribution non complètes | Conserver panneaux applicables ; Prometheus/Grafana sur OBS, exporter hôte PG/APP, labels job/role/run maîtrisés ; auth admin et versions scellées |
| [Ancien collecteur](../../scripts/performance/v3-collect.py), [préflight](../../scripts/performance/v3-preflight.py), [runner](../../scripts/performance/v3-run.py), [observer](../../scripts/performance/v3_observer.py) | Histograms, corrélation, backlogs, client/SQL ; contrôles historiques | NON_CONFORME directement : hypothèses Monolith/localhost/ports, preuves L6, agrégations sans run et absences parfois rendues0 ; plafond70min historique | Réemployer fonctions après revue, pas le verdict ni les valeurs par défaut ; collecteur distant fail-closed, absence=null/inconnue, namespaces de runs ; plafond L6 ne remplace pas les fenêtres SPEC |
| [Sonde interne](../../scripts/performance/v3-oracle-probe.py), [smoke cinq rôles](../../scripts/performance/smoke_test_5_runtimes.py), [tests E1](../../scripts/performance/test_spec09_bench.py) | Oracles unitaires et parcours SQL/HTTP réutilisables | INCOMPLET : DeletePot non ordonnancé sous charge, Balance tardive E1, W>V absent E1 | Qualifier indépendance/sensibilité/coût avant pilote puis pleine charge |
| [Rebuild READ](../../scripts/operations/v3-rebuild-read.py) | Maintenance coordonnée et oracle READY | INCOMPLET pour reset A/B global | Ne pas l'utiliser pour effacer WRITE ou fabriquer un état initial ; restore des deux bases, rebuild seulement scénario explicite |
| OpenTofu/Ansible/SOPS/age | Aucun module `.tf`, playbook ou configuration SOPS identifié dans fichiers suivis au périmètre d'inventaire | ABSENT | Petite couche de provisioning future ; pas réinventer application, SQL, observabilité ou harness |
| Deux VM métier réservées + auxiliaires + stockage d'archives | Aucun inventaire distant/provisioning utilisable trouvé ; absence de cible distante confirmée au bilan L7.4 | ABSENT | Construire uniquement après futur GO et budget ; aucune utilisation d'un compte déduite de fichiers locaux |
| H1–H5 et comparaison ancien/nouveau | [Conditions](../testing/evidence/L7-full-execution/performance/conditions.json), [populations réelles](../testing/evidence/L7-full-execution/performance/profile-populations.json), [campagnes](../testing/evidence/L7-full-execution/performance/campaigns.csv), [patch](../testing/evidence/L7-full-execution/packaging-b-worktree.patch) | NON_REPRODUCTIBLE en l'état pour nouvelle comparaison certifiante : entrées/logicielles et snapshots à reconstruire, oracle projection local, hôte partagé | Garder17 comparaisons comme diagnostics historiques ; n'en faire ni F01-I ni comparaison AuthN A/B |
| Anciens R9/R10/R11 et générateur H | Drivers volontairement désactivés par L73-ADMIN-01 | NON_CONFORME comme voie actuelle : purges/terminalisations et expirations artificielles historiques | Ne pas les réactiver ; lire uniquement rapports et reçus historiques |

## 2. Raisons exactes de l'indisponibilité

1. **Absence matérielle :** aucun couple de machines/VM4/8 disponible au bilan ;
   Docker Desktop n'offrait que8 319 504 384octets au total (~7,75GiB), partagés.
2. **Présent mais hors contrat :** Compose monohôte, PG1CPU/1Go ; Keycloak
   partage ce PG ; E1 utilise tmpfs et faibles quotas, sans durabilité disque.
3. **Présent mais incomplet :** E2 distant, Balance exacte indépendante, DeletePot,
   âge source/slot, instrumentation AUD-V3-013, mix, warm-up/recovery et gros seeds.
4. **Reconstruction insuffisamment scellée :** tags mobiles, valeurs de développement,
   chemins locaux, volume nommé préexistant, anciens snapshots non qualifiés.
5. **Pièces correctes mais non qualifiées ensemble :** JWT, scénarios E/F/G et
   oracles statiques ; coût du sondage et tenue des offres jamais qualifiés A/B.

Acheter deux VM ne suffirait donc pas à rendre immédiatement les13 familles
exécutables. Inversement, la majorité des briques applicatives/SQL et du banc
existe déjà : l'effort vise leur assemblage reproductible et les lacunes citées.

## 3. Leçons historiques retenues, sans promotion de verdict

| Corpus lu | Observation utile au cadrage | Limite appliquée |
|---|---|---|
| [I3 initial](L6_I3_Implementation_and_Performance_Qualification_Report.md), [instrumentation R](L6_I3_Deep_Instrumentation_and_Load_Qualification_Report.md), [R2](L6_I3_R2_Metrology_and_Capacity_Requalification_Report.md) | Séparer émission et sondage ; expiration JWT, quotas READ, censure et préchargement | Anciennes affirmations « prêt » ou plafond5,4/s supplantées par audit/rapport courant |
| [R3](L6_I3_R3_Independent_Capacity_and_Metrology_Audit.md), [R4](L6_I3_R4_Final_Metrology_and_Sustainable_Capacity_Qualification_Report.md) | Famine observateur ; slots plafonnés par softCap ≠ file source ; dénominateurs/conservation | Pas de capacité serveur déduite d'un observateur limité, ni extrapolation 12–15/s vers le gel actuel |
| [R5](L6_I3_R5_Realistic_Business_Workload_Qualification_Report.md) | Sérialisation par Pot et dimensionnement du parc ; famine du générateur mesurable | Mix88/10/2 historique insuffisant pour couvrir toutes les mutations SPEC-10 ; pas un contrat universel |
| [R6](L6_I3_R6_Async_Stage_Handoff_Latency_Qualification_Report.md) | Handoffs à distinguer des temps actifs et de l'observation | Registre Monolith in-memory ne corrèle pas automatiquement cinq JVM distantes ; horloges locales non soustractibles entre nœuds |
| [R7](L6_I3_R7_Controlled_Async_Performance_Optimization_Report.md), [R8](L6_I3_R8_Command_Discovery_and_Scheduling_Latency_Diagnosis_Report.md) | Coût du polling à vide et contamination par quarantaines/état initial | Aucune promesse LISTEN/NOTIFY ou scale-out ; pas de résolution SQL du backlog pour améliorer le benchmark |
| [R9](L6_I3_R9_Consumption_Resilience_and_Requalification_Report.md), [R10](L6_I3_R10_Performance_Resilience_and_Quarantine_Consolidation_Report.md) | Sensibilité au protocole de sondage et aux résidus ; nécessité de vraie reprise | Les drivers historiques altéraient état/leases ; recommandations de nettoyage non reprises. L'équation R10 `50/0,8=62,5 par minute` est dimensionnellement erronée : c'est62,5/s ; elle ne justifie aucun dimensionnement |
| [R11 filtre](L6_I3_R11_Quarantine_Discovery_Filter_Implementation_and_Qualification_Report.md), [R11 final](L6_I3_R11_Final_SPEC09_E2E_SLA_Qualification_Report.md) | Conserver quarantaines, paliersN1/N2/N3 avec FAIL locaux et offreN3 insuffisante | Les interprétations de capacité, de causalité et de topologie ne remplacent ni SPEC-09 ni une mesure admissible sur A |
| [L7.1](L7_I1_Five_Runtime_Single_Host_Qualification_Report.md), [refactoring L7](L7_Modular_Refactoring_Implementation_and_Qualification_Report.md) | Cinq rôles séparables, quatre JAR, provenance et contrôles structurels | Séparation de processus prouvée localement ≠ ressources contractuelles |
| [L7.4 technique](L7_I4_SPEC09_Technical_Qualification_Report.md) et [preuves](../testing/evidence/L7-I4-technical/README.md) | Quatre populations12 E1 ; reprise unique Command/Registration après SIGKILL/lease ;405 artefacts | Pas de nouvelle certification, pas de somme des48 en percentile SPEC, pas de fermeture automatique des15 réserves L7.3 |

Les références de [cadrage I3](L6_I3_Scoping_and_Design_Report.md) et de
[plan technique I3](L6_I3_Technical_Implementation_and_Verification_Plan.md)
fournissent le découpage préparation/mesure/collecte. Leurs anciennes mentions
Link et de budgets locaux restent historiques. Les compléments actuels sont
définis dans la [reconstitution contractuelle](L7_I4_AB_Infrastructure_Contract_Reconstruction.md).
