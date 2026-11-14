# L7 Local Playground — reprise I3 Bruno

Mandat `GO_L7_LB_I3_BRUNO_REWORK`, 14 novembre 2026 (Europe/Paris).
Branche `feat/l7-lb-local-playground`, départ propre
`7221d139ca86a630bacc97dd40a5021d6db08050`. Le SHA de référence fourni
`f1ccd0dc` est son ancêtre ; les commits `e54cadd4` et `7221d139` ont déjà
introduit puis documenté les connexions natives GUI. Ils sont conservés.

## Audit préalable et périmètre déclaré

Sources examinées avant implémentation : les trois contrôleurs publics
`V3AdmissionController`, `V3ReadController`, `V3InvitationController`, les
décodeurs `CommandAdmissionInput`/`IdentityAdmissionInput`, `V3ReadDtos`, la
configuration HTTP/AuthN, les transitions et autorisations Pot/Shareholder,
le contrôleur technique StageHandoff, les propriétés des cinq runtimes,
`scripts/bruno-v3`, `scripts/bruno-playground`, les helpers/fixtures B1/B2/B4,
le lanceur `scripts/local/runtime.py`, les guides et qualifications existants.

Inventaire : **12 routes métier HTTP**, dont deux admissions multiplexant
**13 Command et 2 IdentityRequest**. Cela représente **25 opérations métier**
en distinguant chaque intention admise (15 admissions + 10 consultations).
La matrice CSV distingue ces opérations des endpoints OIDC, techniques,
internes et retirés. Il n'existe pas de route publique Link, de CRUD User,
de lecture Expense autonome ni de balance courante indépendante de Pot@V.

La collection initiale confondait preuve automatisée et client interactif :
PG01 était un GET déclenchant tous les POST dans un helper. La CLI injectait
deux JWT absents du GUI ; le correctif précédent a corrigé l'auth native,
mais n'a pas rendu les opérations métier navigables. Le corpus bruno-v3
contient des oracles utiles, mais repose largement sur fixtures et appels
secondaires ; sa démonstration Observability attend encore `CHANGED` pour
Registration, alors que le DTO courant expose `COMPLETED`/`REJECTED`.
Il ne peut donc pas être promu tel quel en client de développement.

Frontière déclarée avant les modifications : **BRUNO CLIENT / LOCAL
INTEGRATION**, slices Maven modifiées **NONE** ; WEB, COMMAND et PROJECTION
exercés seulement par HTTP contre les cinq JVM existantes. Interdit : toute
modification sous `app/`, contrat public, architecture, invariant métier,
profil de sécurité, durée de token, migration historique et campagne SPEC09.
Gate global architecture **NOT REQUIRED** ; réacteur complet **NOT REQUIRED**.
Base **CURRENT V3**, bootstrap local existant. Escalade : tout défaut exigeant
une modification sous `app/` doit être documenté et arbitré avant code.

## Conception retenue

Point d'entrée unique : `scripts/bruno-playground`, **Pocoma V3 Playground**.
Les corpus historiques `scripts/bruno-v3` et `scripts/bruno` restent des
corpus de qualification/archives, avec redirection documentaire explicite.
Les requêtes par domaine sont les sources éditables ; les parcours visibles
sont composés de ces fichiers par `scripts/local/sync_bruno_playground.py`.
Le contrôle `--check` empêche une divergence silencieuse. Aucun générateur
n'est nécessaire pendant l'utilisation GUI ou CLI.

Les helpers partagent uniquement auth, variables, attente bornée du même GET
et assertions. Chaque mutation est le POST visible avec JSON éditable.
Réutilisation : payloads actuels B202–B212/B4, masquage B10/B29/B402,
idempotence B213/B214, référence Expense B15, soldes exacts B09/PG01,
histoire après suppression B13/PG01, départ volontaire B401 et révocation
de binding B220. Les fixtures, accès SQL et injections ne sont pas repris.

Les preuves, résultats, commandes et réserves sont complétés après exécution.
