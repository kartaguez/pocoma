# L4/I3–I7 — décision et exécution consolidée

## Décision humaine du 3 novembre 2026

Le mandat autorise documentation, outillage, implémentation et qualification locale des six
participants READ indépendants, sans activation productive avant I8. L2, PreparationReads,
owners, garde, recovery, routage et producteurs WRITE ne sont pas redéfinis.

La dernière précision humaine et la décision publiée à `3b7a3115` font autorité : I2 est
**CLOSED — LOCAL BOUNDED PASS**, I2-QUAL-01 **RESOLVED**. Les mentions contraires du
mandat sont antérieures à cette clôture ; aucune réserve n'est rouverte. I1 reste clos
avec ses réserves explicites. Les échecs historiques et leur attribution sont conservés.

G-I3 à G-I7 non filtrées sont regroupées à I8 par dérogation humaine explicite à la politique
Reactor. La gate Architecture provoquée par les deux dépendances fonctionnelles READ Results
est également reportée à I8. Aucune autre nouvelle frontière n'est autorisée implicitement.
Ni clôture canonique I3–I7 ni certification SPEC-10 ne découle des campagnes filtrées.

## Frontière déclarée avant code

PROJECTION principale ; BINDING pour les contrats CurrentBinding. Sources historiques et
stores dans les infrastructures existantes ; modules de matérialisation/lecture existants.
COMMAND fournit des producteurs de fixture et ne change pas de comportement. EVENT et WEB
non modifiés ; aucun changement du moteur L2 ou runtime productif. PostgreSQL CURRENT V3,
sans baseline TRUSTED inventée et sans chaîne historique V2. Gates globales dues mais
reportées ; full reactor non exécuté dans cette mission. Toute autre traversée exige analyse.

## Manifeste adopté

32 méthodes / 37 invocations nouvelles ; neuf campagnes non cumulatives. Le texte nominatif
de la proposition antérieure n'étant pas disponible, les noms effectivement réalisés sont
enregistrés dans le manifeste lié au rapport, sans prétendre citer des noms absents. Les méthodes
fusionnées gardent des sous-scénarios nommés et des assertions de contenu. Le registre
`scripts/testing/verification-campaigns.json` constitue la sélection exécutable exacte.

FAST-1 (2) : compositionAndKeys, strictCanonicalCodec.
FAST-3 (1) : visibleSetRules.
FAST-4 (3) : euiStateAndMinVersion, les deux terminalContract.
CHANGE-1 (4) : exactHistoricalComposition, historyAndReplay, concurrentPublishers,
migrationAndPrivileges.
CHANGE-2 (4) : exactHistoricalFinance, historyAndRationals, concurrentImmutablePublication,
migrationAndPrivileges.
CHANGE-3 (5) : exactHistoricalMembership, historyAndKeyset, concurrentGuardAndAtomicReaders,
plansAndProfiles, migrationAndPrivileges.
CHANGE-4 (4) : exactEuiHistory, historyAndIsolation, concurrentVersionsAndDivergence,
migrationAndPrivileges.
CHANGE-5 (4) : durableRequestsAndTerminals, mappingsAndOwnership, concurrentImmutableResults,
migrationsAndPrivileges.
INTEGRATION-1 (10) : participantConformance pour les six participants, independentProgression,
resultsBeforeDomain, uncertainCommitHasNoSpeculativeSettlement, boundedCapacityAndRenewal.

Les garanties génériques L2 inchangées sont attribuées aux preuves L1/L2/L3/I1/I2 antérieures,
jamais déclarées nouvellement exécutées. Les six conformités physiques prouvent les writers,
readiness et preuves métier propres, pas six recertifications de fencing/retry/quarantaine.

## Prévisions et arrêt

Nominal UNKNOWN : pas de mesure disponible des nouveaux tests, compilation et fixtures.
Enveloppes indicatives : FAST 60 s, CHANGE 300 s, INTEGRATION 900 s ; somme 2 580 s (43 min).
Ce ne sont **pas des plafonds**. Un dépassement sain continue et est mesuré. Aucun budget
mural global bloquant. Un échec réel arrête l'enchaînement, conserve les preuves et permet une
correction locale diagnostiquée et son seul rejeu nécessaire, comptabilisé séparément.

Blocage : absence de sortie seule insuffisante (tests SQL parfois silencieux). Capturer PID,
groupes, état/temps CPU, threads et états PostgreSQL avant une interruption décidée sur attente
anormale objectivée (deadlock externe, callback retenu sans libérateur vivant, absence durable
de travail/CPU/SQL hors fenêtre déclarée du scénario). Aucun délai métier n'est augmenté.
Les interruptions conservent logs/XML partiels et identités ; aucun retry ni élargissement auto.

## Reports

I8 : activation, deux racines, services de lecture complets P01-H/I, P02-K, P03-M, READ-01,
SEC internes, ressources/drainage/restart, gates canoniques et Architecture/full prescrits.
L5 : HTTP/OIDC/consentement/polling. L6 : reconstruction administrative, OPS/PERF/SLO globaux.
Les comptes, durées, fichiers, corrections et réserves figurent dans le
[rapport de livraison](L4_I3_I7_Execution_Report.md) et son manifeste de preuves. Le commit
portant ce rapport identifie la livraison ; la conversation finale constate son SHA et sa
synchronisation distante après publication. Les participants restent inactifs à ce stade.
