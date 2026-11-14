# L7-LB — Keycloak bootstrap et préparation du clean reset

Preuve finale : **release/qualification.json — PASS**, instance
**keycloak-release-validation**, 14 novembre 2026, Europe/Paris.
HEAD testé 80792858d6aac9c648ebcfac2fb2d84ccf80154e + modifications de mission.
Empreinte d’entrées finale :
`999c85a83150de0611375397a9bab97760f04b44490b824a34f6a509ad33fc58`.
L’empreinte doit être identique avant/après et après publication ; ce n’est
pas une attribution automatique d’un PASS à un nouveau SHA Git.

| Preuve | Contenu |
| --- | --- |
| frozen-inputs.json | Inventaire SHA256 des sources/configurations/tests figés avant la campagne finale |
| unit-controls.log.gz | 40 tests Python, zéro échec ; contrôles de sûreté et anti faux READY |
| composition.txt | Collection 118 requêtes,236 tests nommés,3 parcours |
| release/commands.json | start/status/gui/bruno/stop/start/status, argv, durées, sorties et codes réels |
| release/fresh-start/keycloak-auth.json | Admin master console Code+S256, quatre comptes activés/sans action, trois utilisateurs Code+PKCE et refus sans/plain |
| release/fresh-start/receipt.json | READY, cinq JVM, PG CURRENT, outils disponibles, canari V2 |
| release/cli/ | JUnit et reçus CLI :118 requêtes,236 tests,731 assertions |
| release/native-installed.json | Bruno installé 4.3.0 :193 contrôles, 171 fichiers, 118 requêtes,236 tests,730 assertions via cache/parseur/VM/transport natifs |
| release/native-installed.json.requests.json | Requêtes natives autorisées, sans bearer/body sensible |
| release/restart/ | READY après arrêt propre, attente des baux capturés sans mutation, canari V3 et nouvelles preuves d’identité |
| release/platform.json | Version physique Keycloak 26.7.0, ports uniquement loopback, PG non publié |
| release/manifest-public.json | Digests/architectures, ports, JARs, toolchain ; aucun secret technique |
| inventory.json | Inventaire en lecture seule des instances/ressources et orphelins potentiels |
| dry-runs.json | Dry-runs destroy ciblés, argv exacts, pertes et conservation prévues |
| SHA256SUMS.json | Sommes des artefacts publiés, hors ce fichier et logs bruts remplacés par gzip |

Le build distribué emploie les quatre anchors runtime avec -am package
-DskipTests ; son journal compressé n’est pas une preuve de tests Maven.
Aucune slice Maven/gate globale/full reactor : périmètre distribution locale
uniquement, app/ et SQL inchangés. Aucun reset/destroy Docker réel.

## Incidents et attribution conservés

old-auth-diagnostic.json : trois anciens comptes de gui-test fonctionnels
avec leurs mots de passe privés ; cause exacte de l’incident Guest humain
non reproduite. old-instance-stop.json : arrêt gracieux, volumes préservés.

first-start-failure/ : premier contrôle admin incorrect (sub absent de
l’access token allégé), correction diagnostique prouvée par ID token puis
nouvelle installation. first-attempt-stop.json : données préservées.

final/ : campagne intermédiaire, infrastructure/CLI PASS, protocole natif
refusé avant lancement car contrôle fixé 4.2.1 et logiciel installé 4.3.0.
Son qualification.json **FAIL** est conservé. native-430.json : protocole
corrigé 4.3.0 sur cette instance, PASS distinct. completion-commands.json :
reprise physique PASS, mais agrégat rejeté par input drift lors des dernières
éditions. intermediate-source-drift.json et les deux journaux gzip conservent
ces limites. **Seul release/ ferme la qualification des entrées finales.**

GUI rendu (login, Send/Runner et bascule humaine) : NOT RUN. Les preuves
automatisées ne prononcent aucune acceptation humaine ni recertification L7.
Les diagnostics privés des installations restent sous .pocoma-local ignoré
de Git ; seuls les reçus autorisés sont publiés. Les mots de passe techniques
et JWT ont été contrôlés absents des artefacts publiés.
