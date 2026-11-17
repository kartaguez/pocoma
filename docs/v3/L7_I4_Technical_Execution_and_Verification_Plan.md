# L7.4 — Exécution technique avec certification L7.3 différée

Mandat du 12 novembre 2026 : `GO_L7_I4_TECHNICAL_EXECUTION_WITH_I3_CERTIFICATION_DEFERRED`.
Départ attendu et observé : `ef90dd6f5b5a36c0ec6ae9477fa5b810cb515941`,
branche `feat/v3-architecture`, worktree propre. Cette décision autorise la préparation
et l'exécution technique de L7.4 ; elle ne certifie ni L7.3 ni L7.4.
L7.3 reste NOT_CERTIFIED, L7.4 TECHNICAL_EXECUTION_AUTHORIZED, L7.5 BLOCKED, L7 OPEN.
Les interdictions historiques d'engager **l'exécution technique** sont supplantées
par ce mandat ; les préconditions de certification restent dues.

## Audit et contrat

Sources : [SPEC-09](SPEC-09_Performance.md), amendements C-03 et DeletePot inclus ;
[SPEC-10 §24](SPEC-10_Test_Certification.md#24-performance), retrait Link et décision
Registration du 12 novembre ; [protocole préparatoire](L7_I4_SPEC09_Performance_Certification_Execution_Plan.md),
[manifeste](../../scripts/performance/l7-spec09-campaigns.json), plan directeur,
plans L6/I3, rapports R4 à R11, rapports finaux L7.3 et politique Reactor.
La matrice contractuelle distingue seuils normatifs, protocole initial proposé
(SPEC-10 §24.3/24.6) et paramètres locaux du banc. Aucun seuil n'est révisé.
Les fenêtres du manifeste existant sont conservées pour les futurs essais directs ;
leur présence ne constitue pas une ratification documentaire du protocole proposé.

## Verification scope — déclaré avant implémentation

- Expected production impact : NONE ; scripts Python/JS, configuration jetable, preuves et documents.
- Primary slice : NONE (outillage externe à app).
- Secondary slices : NONE. Frontières observées : WEB, COMMAND, PROJECTION.
- Forbidden production impact : Java, POM, contrats, migrations, grants productifs,
  Engine/Discovery, sécurité et topologie existante.
- Required local proof : contrôles discriminants des nouveaux collecteurs/oracles,
  suites hors ligne existantes affectées ; E1 sur PostgreSQL et cinq processus réels,
  données synthétiques neuves, noms/ports/réseau distincts, destruction du seul projet créé.
- Packaging E1 depuis app : `./mvnw --batch-mode --no-transfer-progress -pl runtime-web-write,runtime-web-read,runtime-worker-process,runtime-worker-materialize -am package -DskipTests`.
  Packaging seulement, aucun PASS de tests Maven revendiqué.
- Global architecture gate : NOT REQUIRED (§17 : aucun changement de frontière/dépendance).
- Full reactor : NOT REQUIRED (§19 : aucune clôture de lot, intégration main ou stabilisation globale).
- Database verification : CURRENT, migrations V3 du bootstrap courant sur cible neuve ;
  pas de baseline déclarée TRUSTED, pas de rejeu V2 historique ni de modification de migration.
- Next mandatory global gate : certification/clôture L7 après stabilisation des sources et topologie.
- Escalation : déclarer les consommateurs et commandes canoniques avant tout changement
  productif. COMMAND `./mvnw -pl runtime-worker-process -am test` ; PROJECTION
  `./mvnw -pl runtime-worker-materialize -am test` ; WEB
  `./mvnw -pl runtime-web-write,runtime-web-read -am test`.

## Banc et ordre d'exécution

1. Préflight non destructif et inventaire des ressources ; aucun reset des conteneurs présents.
2. Implémenter collecte sans secrets, provenance des entrées, arrêt et conservation des échecs.
3. Contrôles hors ligne : censure, délais, défaut de collecte, population, doubles effets,
   écart de provenance, absence de métriques et absence de topologie A/B.
4. E1 : cible Docker neuve, bootstrap CURRENT existant, AuthN simulée avec JWT vérifiés
   et AuthZ réelle, petit Pot 5/20 ; offre courte et bornée, horloge monotone,
   oracle indépendant des fractions exactes, SQL READ ONLY, métriques cinq Runtimes.
   E1 n'est jamais un substitut aux campagnes contractuelles.
5. E2 : uniquement sur topologie A/B attestée avec snapshots, collecteurs calibrés,
   offre ouverte, fenêtres/reprises/répétitions et oracles complets. Conserver NOT_RUN
   si les préconditions ne sont pas satisfaites. Une limitation locale ne vaut pas FAIL produit.
6. E3 : corpus complémentaire distinct ; ne fermer aucune des 15 obligations sans
   stimulus et oracle exacts. Inventaire textuel de writers = contribution partielle.
7. Rapports G1/G2/G3, registre, index et publication sans force sur la branche autorisée.

Les deux familles d'oracles restent indépendantes. Le débit/les latences ne peuvent
masquer une divergence des effets exacts. Arrêt au premier invariant critique,
collecteur indisponible, mémoire dangereuse, espace insuffisant, générateur en retard,
échéance dépassée ou provenance modifiée. Les limites E1 sont des protections du banc,
pas des seuils SPEC-09. Aucun timeout productif n'est augmenté.

## Premier constat d'environnement

Docker Desktop unique : 10 CPU, 8 319 504 384 octets de RAM au total ; PostgreSQL
existant limité à 1 CPU/1 Gio, Command à 0,5 CPU/512 Mio. Les cinq services utilisent
une image historique Monolith. Cet environnement n'atteste pas les deux machines
4 vCPU/8 Go distinctes de A/B, ni le SHA courant des exécutables déjà en service.
Une nouvelle cible E1 est donc nécessaire. Les accès à une éventuelle cible distante
ont été demandés pendant la préparation ; aucune cible distante n'est présumée disponible.

## Extension E3 déclarée avant implémentation

Après E1, option explicite de crash après admission sur la seule cible jetable :
pause du processus Command avant admission, HTTP 202, observation SQL extérieure
PENDING sans résultat ni publication, SIGKILL effectif (exit 137), redémarrage du même
conteneur, résultat/effets/publications uniques et rapprochement READ. Deux sujets :
CreateExpense puis Registration d'une seconde EUI fraîche. Cette fenêtre est celle
d'EVT-02 E02-J, sans claim acquis ; elle ne ferme ni CON-05 crash sans cession ni
C03-J crash pendant calcul. Les oracles et contrôles restent hors app, slices NONE.
Le premier suivi E3 de 60 s a censuré une demande avant l'expiration du bail de
partition configuré à 240 s. Le suivi corrigé capture l'échéance persistée du bail,
attend sa durée restante observée plus une enveloppe fixe de 60 s ; aucune lease,
claim ou durée productive n'est modifiée. Command et Registration sont admises sous
la même pause et le même SIGKILL. E3 n'entre pas dans les populations de latence E1/E2.
L'essai initial est conservé ; son absence de reprise à 60 s ne prouve pas une
violation du contrat de reprise après expiration.
