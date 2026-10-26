# L2 — Première revue contradictoire documentaire

2026-10-26. Base : `4127767f2f9337b495501d7cd5d933ae9782c8ec`.

**Revue statique réalisée dans le mandat de préparation, pas audit indépendant ultérieur.** Aucun code modifié, aucun test exécutable, PostgreSQL ou Maven lancé. L2 reste DESIGN PENDING ; aucune preuve PG PASS, aucune acceptation L2. Les douze décisions humaines sont dans le [registre](L2_Decision_Register.md) ; le [plan](L2_Design_and_Verification_Plan.md) présente leurs conséquences, les protocoles proposés, campagnes et gates. Les recommandations ouvertes ci-dessous ne valent pas arbitrage.

## 1. Méthode et conclusion de préparation

Sources directement confrontées : canon, plan directeur, SPEC-03/05/10, contrat transactionnel L1-C, clôtures L1-F/F2 et matrice F2 ; ports PotCommandStore/PotCommandTransactions/PotActorAuthority, ExecutePotCommand, JdbcPotCommandStore/PotCommandJdbcTransactions/JdbcPotRevisionAdapter, schéma V3 d'exécution, ancien domaine/ports Consumption et compositions Spring. Inventaire des 60 modules par POM, Git local/distant/worktrees et modifications préexistantes contrôlés. La présence d'une classe, d'une migration ou d'un ancien test n'est jamais prise pour une preuve du moteur L2.

Le corpus permet de préparer L2 mais **ne permet pas de déclarer le plan prêt à coder sans compléments**. R1 est une incompatibilité de raccordement confirmée entre le handoff L1 et la répartition des responsabilités L2. R2–R4/R10 sont des obligations de conception insuffisamment concrétisées par les seuls choix validés. R5/R6/R8/R9 demandent surtout une traçabilité et des contrats précis pour empêcher des réinterprétations ou de faux PASS. R7 interdit de faire disparaître implicitement des demandes sous couvert de reconstruction.

Sévérité : **critique** = risque d'effet partiel/perte/fausse certification ; **majeure** = risque de blocage/famine/configuration incohérente ou conception inexécutable ; **mineure** = ambiguïté documentaire à corriger sans nouveau métier. Aucun défaut d'une implémentation L2 inexistante n'est prétendu observé.

## 2. Constats

### R1 — Suppression Engine du slot et suffixe dense fermé

**Sévérité : critique ; incompatibilité de raccordement confirmée, choix correctif ouvert.**

**Sources en tension.** L2-A02/A09 : Engine propriétaire de la transaction et de la suppression après transformation. [L1-C, ordre 6–9](L1_C_Transaction_Contract.md#entry-and-physical-composition) : suppression avec résultat avant fermeture/publication dense. Dans `JdbcPotCommandStore.finish`, DELETE de `v3_command_slot` ; `publish` stage les événements, flush/contraintes, `closePublication`, puis positions POT/COMMAND_COMPLETION. `PotCommandJdbcTransactions` et les writers gardent l'owner fermé. SPEC-03 §5 interdit retour aux verrous métier après séquences ; SPEC-05 §4 exige atomie effets/suppression.

**Défaillance.** Appeler ExecutePotCommand inchangé sous l'Engine crée un owner imbriqué et laisse Pot gérer le slot. Déplacer naïvement le DELETE après `publish` peut être refusé par le guard fermé ; l'autoriser en rouvrant tous les writers permettrait une écriture métier et de nouveaux verrous après compteur. Un DELETE hors transaction laisserait un effet sans preuve de complétion ou un slot supprimé sans effet.

**Options correctives.** (1) Après publication, suppression protocolaire unique par Engine du slot déjà verrouillé, permission étroite distincte de toute écriture métier ; prouver absence de nouvelles dépendances de verrouillage via index/FK/triggers. (2) Suppression Engine avant fermeture du suffixe, avec raccord limité de publication Command déclenchée ensuite dans la même unité, sans DSL de phases. (3) Conserver suppression Pot ou rouvrir globalement l'owner : écart aux choix validés, non recommandé.

**Recommandation.** Option (1), sous démonstration préalable du graphe de verrous et guard irréversible pour les writers métier. Ne pas prétendre qu'un verrou de ligne préalable suffit à lui seul : inspecter les contraintes/triggers réels du DELETE canonique. La suppression doit toucher exactement une ligne de clé/token protégés ; son échec empoisonne et annule aussi les positions.

**Décision attendue.** Ratifier (1), (2), ou une variante explicitement équivalente, et consigner l'ordre/permission dans SPEC-05 et le futur contrat d'owner **avant code**. Le registre A09 ne change pas silencieusement.

**Vérification future.** P5 ; writer métier post-suffixe refusé même exception interceptée ; faute sur DELETE et contraintes au commit→rollback intégral ; absence de deadlock/nouvelle prise de verrou métier ; deux finaliseurs, expiration après verrou et ACK COMMIT perdu ; C04/C06/EVT applicables.

### R2 — softCap, préfixe couvert et réactivation sous concurrence

**Sévérité : critique ; stratégie de sérialisation et portée du plafond à ratifier.**

**Sources en tension.** A03 refuse compteur durable ; A06 exige tous les slots du préfixe avant K ; A11 distingue softCap d'enrôlement et plafonds de tentatives ; SPEC-05 §§2/6/7 exclut QUARANTINED du count ; A10 réactive sans exiger que count soit inférieur au plafond. SPEC-10 C01-G et C02 exigent absence de saut.

**Défaillance.** Deux Discovery lisent count=8 pour cap=10 puis insèrent chacune deux slots : stock=12. Plus grave, tronquer les références locales à deux tout en avançant au H initial omet définitivement les suivantes. Une réactivation peut aussi faire passer count au-dessus du softCap sans aucune nouvelle insertion Discovery ; considérer cap comme invariant de stock absolu contredirait une réactivation inconditionnelle.

**Options correctives.** (1) Sérialiser enrôlement/count/K sur la ligne ownership `(C,p)`, calculer un préfixe global raccourci selon la capacité réelle ; considérer softCap comme limite d'enrôlement, accepter le dépassement ponctuel dû à réactivation avec pause des nouvelles insertions. (2) Même sérialisation, mais différer/refuser réactivation si pleine, ce qui complète le contrat administratif. (3) COUNT non protégé/dépassement concurrent borné : ne résout pas les sauts de K et exige définition d'une borne ; pas acquis.

**Recommandation.** (1). Pas de compteur dérivé ; verrou partition pour Discovery/acquisition et coordination de réactivation si retenue, jamais finalisation/cession. La réactivation acquiert cette coordination avant le slot, sans nécessiter le lease du Worker ; elle reste un CAS administratif. Le §7 du plan décrit le plus long préfixe couvert, y compris positions hors partition et capacité nulle. Diminutions concurrentes du stock par succès/quarantaine sont conservatrices ; réactivation doit être observée selon sa sérialisation explicite.

**Décision attendue.** Confirmer la portée « enrôlement » du softCap, le traitement administratif et l'ordre des verrous. Si une limite stricte du stock est voulue, amender le contrat de réactivation avant code ; ne pas la déduire du mot softCap.

**Vérification future.** P2/P6 : deux Discoveries, takeover, réactivation pendant count/insert, cap atteint au milieu d'une fenêtre, page sans local, a=0 ; oracle indépendant aucun p éligible≤K sans slot/effet. Mesurer stock/cap séparément des M_C/M_W.

### R3 — Hash, nombre de partitions et cohérence des configurations

**Sévérité : critique ; contrat concret incomplet.**

**Sources en tension.** A04/A05 et SPEC-05 §§1/3 requièrent identité/routage/partitions stables ; l'existant `PartitionHash.forPot` utilise UUID.hashCode et `forPipelinePot` mêle pipelineId. A05 dit « Consumption/famille », SPEC-05 fixe les partitions par famille. Aucun algorithme, clé, encodage, gestion des signes, P ou politique de changement n'est ratifié par ces seules formulations.

**Défaillance.** Après K=100, un Worker change P ou hash ; E@50 bascule vers une autre partition dont K a déjà franchi 50 : aucun slot et faux « terminé ». Deux instances partageant un UUID avec conventions différentes peuvent perdre ou doubler l'enrôlement. Nouvel UUID aléatoire au redémarrage provoque un rejeu involontaire.

**Options correctives.** Contrat immuable par famille/Consumption et configuration de release contrôlée ; changement incompatible par nouvel UUID/destination indépendante ou reconstruction READ totale exclusive. Conserver le hash existant est possible seulement si tous octets/entrées et vecteurs sont explicitement retenus ; le nom d'une méthode Java ne suffit pas.

**Recommandation.** Ratifier un contrat complet ; une convention SHA-256/UUID/floorMod précise est proposée au plan §6, sans la présenter comme une décision acquise. P partagé par famille tant que SPEC-05 n'est pas amendée. Définir validation de configuration entre tous Workers avant activation sans catalogue SQL additionnel ; configurations mixtes doivent être refusées par le processus de livraison vérifié.

**Décision attendue.** Clé/algorithme/encodage/signé/modulo, P et unité famille/Consumption, UUID littéraux, empreinte de configuration et stratégie de refus/reconfiguration ; valeurs de campagne avant tests, valeurs d'exploitation avant activation.

**Vérification future.** P1/P4 : vecteurs indépendants y compris hash négatif/UUID extrêmes ; redémarrage/multi-Workers ; ancien/nouveau contrat mélangé refusé ; aucune inférence de complétion après configuration incompatible.

### R4 — Tour complet du curseur burnable volatil

**Sévérité : majeure ; garantie de progression à concrétiser.**

**Sources en tension.** A07 exige retour régulier même sous admission continue ; A11 impose occasion répétée de progrès ; SPEC-05 ne donne pas de borne de tour. Un simple keyset `id>cursor` revenant au début seulement à page vide est insuffisant.

**Défaillance.** Flux continu de clés supérieures : la page ne devient jamais vide ; une PENDING née derrière cursor ou devenue éligible tardivement ne sera plus visitée. Un restart après chaque début de scan peut aussi affamer la fin d'un très grand ensemble ; aucun curseur volatil ne peut garantir progrès sous crashes arbitrairement fréquents.

**Options correctives.** (1) Borne haute U en mémoire au début d'un tour, scan fini jusqu'à U puis retour ; nouvelles clés derrière ou après U visitées au tour suivant. (2) Retour forcé après nombre borné de pages/temps mais avec politique qui ne répète pas éternellement le même début en oubliant une fin stable. (3) Checkpoint durable : exclu par A07.

**Recommandation.** (1), sur clé stable indexée PENDING ; progression du curseur sur les références examinées, même doublons concurrents. Définir le tour par Consumption/partition et ses hypothèses (ensemble initial fini, capacité revenue, période sans crash suffisante). Bornes et coût du retour doivent être mesurés ; aucune promesse de délai sous admission illimitée dépassant toute capacité.

**Décision attendue.** Ratifier la règle de tour et les bornes observables. La volatilité reste inchangée ; ne pas ajouter une preuve durable d'enrôlement.

**Vérification future.** P3 : admissions continues devant/derrière, PENDING vieille, doublons/quarantaines, saturation puis reprise, perte cursor/U et plusieurs redémarrages ; oracle indépendant liste toutes les PENDING attendues à chaque tour terminé.

### R5 — Classification des échecs et cession unique du budget

**Sévérité : majeure ; précision nécessaire, pas défaut L1 démontré.**

**Sources en tension.** A10/SPEC-05 §5 comptent les échecs techniques constatés, pas acquisitions/expirations ; C05-C distingue CAS et obsolescence. Code `ExecutePotCommand.execute` classe Collision/InconsistentFacts/Technical puis appelle le même `recordFailure` après rollback ; le store incrémente et efface le token. La généralisation pourrait changer cette classification ou la doubler en conservant le catch Pot et celui d'Engine.

**Défaillance.** Deux couches comptent deux fois la même tentative ; une cession committée sans ACK est répétée ; une exception transport au COMMIT est prise pour rollback puis incrément ; collision terminalisée comme rejet ; N abaissé en cours de claims avec résultat imprévisible. Décréter les collisions gratuites changerait le comportement L1 sans décision.

**Options correctives.** (1) Table de classification explicite commune, un seul owner Engine de cession, conservation de la collision confirmée comme technique comptable L1 ; UNKNOWN/crash/expiry/lost claim non comptés par supposition. (2) Exempter certaines collisions du budget : nouvel arbitrage requis. Ne pas ajouter table de reçus d'échec ou retry interne pour dédupliquer.

**Recommandation.** (1), protocole du plan §9. Le token courant effacé/remplacé empêche la seconde cession ; ACK perdu→relecture, pas répétition aveugle. N/délai effectifs archivés par campagne et politique de modification fixée avant usage. Une erreur avant acquisition n'incrémente aucun slot ; une exception de préparation sous claim confirmé suit la cession normale après résolution certaine.

**Décision attendue.** Acter la table de classification comme conservation L1 et fixer la politique de changement de N/délai. Ne rouvrir l'arbitrage de budget que si une exemption est souhaitée ; les valeurs opérationnelles ne sont pas fournies par A10.

**Vérification future.** P6 ; N−1/succès, N/quarantaine, deux finaliseurs/catchs, ACK de cession perdu/rollback, expiration avant cession, crash après rollback, collision confirmée, faits incohérents ; mêmes compte/token/source depuis connexion extérieure.

### R6 — Certification L2 réalisable et absence de faux PASS

**Sévérité : critique pour les verdicts ; décomposition documentaire proposée, aucune réduction normative.**

**Sources en tension.** A12 et plan directeur §4 bornent L2 à Command réel + banc READ technique, Identity fixture ; SPEC-10 demande pour PASS global cinq familles productrices, Registration/Link/Unlink, trois matérialisations métier et versions hors ordre (E01-D/G, E02-D/E, E03-B/J, C06-B/C/D/N/O/P notamment).

**Défaillance.** Déclarer EVT-01 PASS après seulement POT/COMMAND_COMPLETION, ou CON-06 PASS avec une table technique jouant CurrentPot, certifierait des producteurs/projections absents. Un succès du full reactor historique ne ferme pas ces variantes V3.

**Options correctives.** (1) Contributions L2 bornées par scénario/variante/composant réel/fixture, exigences globales incomplètes maintenues INCONCLUSIVE ; transférer nominativement chaque preuve restante à son producteur réel. (2) Implémenter tous producteurs/projections en L2 : hors mandat et périmètre validé. (3) Affaiblir SPEC-10 : non autorisé.

**Recommandation.** (1), matrice du plan §12 et renvoi SPEC-10. Le banc prouve le mécanisme, pas le métier. Tests sur familles fixtures étiquetés « technique » ; jamais PASS d'un producteur absent. La couverture générique obligatoire de L2 peut devenir PASS borné sans entraîner PASS global de l'exigence.

**Décision attendue.** Audit indépendant de la matrice d'allocation et des critères de clôture ; décision humaine ultérieure sur L2 seulement après preuves réelles. Le principe est déjà validé A12 et n'a pas à être réinventé ; les reports ne sont pas des scénarios supprimés.

**Vérification future.** Manifeste variant par variant ; présence XML, source réelle vs fixture, raison de report, lot receveur et statut INCONCLUSIVE. Aucune méthode absente/skip requalifiée PASS ; aucune acceptation automatique après Maven.

### R7 — Reconstruction de développement et demandes PENDING à conserver

**Sévérité : critique si bascule destructive ; scénario de données non arrêté.**

**Sources en tension.** A01 autorise installation V3 propre sans migration des slots historiques mais interdit perte PENDING/réadmission TERMINAL. SPEC-03 §3 rend intention et terminalisation durables/immutables ; SPEC-05 §11 exige inventaire des writers et absence de perte. Un DROP global n'est pas une traduction suffisante de « reconstruit ».

**Défaillance.** Effacer sources/résultats et ne recréer que les tables techniques supprime des demandes admises ; recopier toutes sources comme PENDING redéclenche des TERMINAL ; laisser un ancien Worker écrire un schéma réinitialisé réintroduit double moteur ou incohérence.

**Options correctives.** (1) Pilote dans environnement neuf distinct et explicitement identifié, anciens schémas préservés/inactifs ; demandes du nouveau jeu admises durablement puis toutes retrouvées. (2) S'il faut reprendre des demandes existantes, arrêter writers, conserver les sources/intentions/statuts/résultats et leur provenance selon procédure revue, reconstruire seulement métadonnées compatibles ; ne pas migrer les anciens claims/slots et ne pas recréer de travail TERMINAL. (3) Destruction explicitement autorisée d'un jeu jetable : nécessite identifier ce jeu ; ce mandat documentaire ne l'autorise pas.

**Recommandation.** (1) pour préparation/pilotes L2 ; inventorier séparément tout besoin de (2). Aucune perte autorisée par omission. Préserver les autres worktrees ne prouve pas à lui seul la préservation des bases.

**Décision attendue.** Identifier environnement/données du bootstrap futur et confirmer absence de sources existantes à transférer, ou valider procédure de conservation des sources avant toute reconstruction. Aucun besoin de migration V2 n'est inventé.

**Vérification future.** P1/P3 : inventaire avant/après des PENDING et TERMINAL du périmètre retenu, mêmes UUID/intention/statuts, résultats conservés, anciens owners arrêtés et aucun writer secondaire actif.

### R8 — Résidu de permission de purge dans CON-05

**Sévérité : mineure dans la rédaction, critique si pris comme permission ; correction documentaire explicite proposée.**

**Sources contradictoires.** SPEC-10 CON-05 disait que le slot restait non terminal « tant que le protocole de purge/résolution n'est pas prouvé » ; SPEC-05 §7 et A03/A10 interdisent toute purge d'incomplet et n'autorisent que succès atomique ou reset READ total coordonné. Le même CON-05 contient déjà cette interdiction plus loin.

**Défaillance.** Un futur agent interprète le résidu comme autorisation de supprimer une quarantaine après diagnostic et rend faux K+absence⇒complétion.

**Options correctives.** Retirer cette permission implicite en rappelant la conservation jusqu'à réussite ; ou rouvrir une politique de purge normative, expressément hors arbitrages actuels.

**Recommandation/action documentaire.** Remplacer le membre de phrase par conservation sans purge jusqu'à réussite et renvoi au reset READ coordonné. Correction tracée dans SPEC-10, fondée sur A10 déjà validé, sans nouvelle faculté d'administration. Le code/DDL reste inchangé.

**Décision attendue.** Aucune nouvelle décision métier : vérifier la fidélité de cette correction pendant l'audit documentaire. Toute purge future demanderait un mandat distinct.

**Vérification future.** Recherche des permissions de DELETE/purge/résolution dans tous writers/scripts/compositions ; C04-L, C05 compléments Q, C02-L.

### R9 — Contrat d'observation durable de recovery et participation du métier

**Sévérité : majeure ; contrat d'interface encore à détailler.**

**Sources en tension.** A02/A09 : Engine ignorant le métier, Consumption fournissant observations durables ; L1 `recover(requestId)` relit directement le terminal Command ; SPEC-05 §3/CON-04 interdisent conclure sur absence du slot seule. Le port L1 possède encore ses transactions et ne peut être réutilisé tel quel sous owner commun.

**Défaillance.** L'Engine apprend à interroger les tables Pot pour trancher, ou considère tout slot absent comme succès avant K/reset ; Consumption prend une nouvelle transaction d'effets indépendante durant recovery et double le traitement ; assimilation d'incohérence à reprise ordinaire.

**Options correctives.** Petit contrat d'observation durable typé par résultat générique (terminé cohérent / présent récupérable / incohérent / observation indisponible), données métier encapsulées par Consumption ; ou logique métier de recovery dans le cœur Engine, contraire A02. Pas besoin d'un reçu SQL supplémentaire.

**Recommandation.** Première approche ; spécifier les postconditions et fenêtres de relecture, aucune mutation de recovery et aucune conclusion de rollback sur transport. Command fournit terminal/résultat/complétion ; non-burnable utilise K/slot et garanties des effets. Les données de test de l'oracle restent indépendantes de ce contrat de production.

**Décision attendue.** Fixer les signatures minimales et postconditions au plan d'exécution avant code ; vérifier qu'elles n'introduisent ni framework universel ni duplication du cycle physique. Ce complément ne change pas A02/A09.

**Vérification future.** P5/P6 : réponses incohérentes/indisponibles, états transitoires, ACK perdu, reset partiel refusé ; aucune connaissance Pot/Identity dans engine-consumption.

### R10 — Round-robin des Consumptions et famine entre slots

**Sévérité : majeure ; obligation distincte à démontrer.**

**Sources en tension.** A08 autorise SKIP LOCKED sans FIFO ; A11 round-robin entre Consumptions ; SPEC-05 §1 demande absence de famine sous conditions admissibles. Les unités d'équité ne sont pas identiques. Les pools de renouvellement peuvent également être monopolisés par les traitements longs.

**Défaillance.** Une Consumption reçoit un lot à chaque tour mais sélectionne toujours les mêmes clés ou nouveaux slots ; un ancien éligible n'est jamais essayé. Ou toutes connexions/threads sont pris par les transformations et les leases expirent malgré un round-robin correct.

**Options correctives.** Sélection déterministe avec parcours/rotation borné des slots éligibles et protection contre répétition indéfinie ; ou preuve d'une autre politique sous hypothèses clairement admissibles. Ne pas imposer FIFO/queue durable ou promettre progrès sous verrous éternels. Ressources de renouvellement dédiées ou quota d'exécution indépendant, sans nouvelle vérité durable.

**Recommandation.** Fixer la sélection et les hypothèses d'équité avant P7, instrumenter âge du plus ancien éligible et occasions ; accès threads/connexions renouvellement indépendant de saturation ; limites M_C/M_W atomiques et non réservations permanentes.

**Décision attendue.** Politique minimale de sélection et bornes de campagne, tailles/réserves de ressources pour renouvellement. Aucun changement aux choix sans FIFO/priorités/files.

**Vérification future.** P4/P7 : trois Consumptions asymétriques, contention contrôlée sur anciens slots, nouvelles admissions, traitement long, pools saturés, capacité retrouvée, renouvellements et respect des plafonds.

## 3. Décisions nécessaires et allocation des reports

| Priorité | À enregistrer avant code / campagne / activation | Autorité attendue |
|---|---|---|
| Avant code | R1 ordre/permission technique de suppression ; R2 sérialisation/préfixe et sémantique softCap/réactivation ; R3 convention hash/P/config ; R4 parcours burnable ; R7 périmètre des données ; R9 participation/observations minimales | Validation humaine du complément de conception après audit du dossier ; normes amendées si contrat change |
| Avant campagne | R5 table exacte de classification et changements de paramètres ; R10 sélection/conditions d'équité ; valeurs N/délai/capacités/polling/leases et vecteurs ; manifeste méthodes/variantes ; graphe ancres couvrant réellement le banc | Plan d'exécution autorisé, manifeste figé, revue des contrats |
| Avant activation | UUID définitifs explicites des neuf définitions, configuration commune/compatibilité contrôlée, aucune voie historique active incompatible ; certification des capacités réellement activées | Composition documentée et décision séparée ; aucune activation par ce mandat |
| Prérequis amont | L1-F2 : le dépôt dit CLOSED/PASS interne, audit/validation avant L2 ; aucune décision d'audit indépendante nouvelle inventée | Rattacher compte rendu ou décision humaine explicite |
| Certification globale différée | Producteurs Identity→lot compétent Identity ; invitations→cycle Invitation ; projections métier→READ ; HTTP/consentement→Supras ; reconstruction/charge complètes→qualification | Programme L3–L8 dans le plan directeur ; aucune implémentation de ces lots ici |

Les arbitrages A01…A12 sont déjà validés ; cette liste ne demande pas de les revalider. Elle demande les compléments concrets que les décisions laissent ouverts et le checkpoint prévu par le mandat. Aucun de ces points ne sera résolu par une hypothèse cachée dans du SQL.

## 4. Changements documentaires proposés

| Fichier | Diff / intention |
|---|---|
| L2_Design_and_Verification_Plan.md | Nouveau : objectifs/frontières, catalogue, inventaire 60 modules + ports/DDL/writers, modèle, protocoles et réserves, pilotes, variantes CON/EVT, régression, campagnes/gates/provenance |
| L2_Decision_Register.md | Nouveau : douze décisions, une entrée par ID, options exactes, justification/invariants/alternatives et vérifications ; aucune alternative historique inventée |
| L2_Contradictory_Review.md | Nouveau : revue présente et dix constats, décisions ouvertes et contrôle documentaire |
| SPEC-03_Events_Requests_Consumables.md | Renvoi vers A04/catalogue L2 ; précision UUID stable, pas de duplication des protocoles |
| SPEC-05_Consumption_Discovery.md | Renvoi explicite au registre/plan/revue ; choix de conception validés distincts de réalisation ; R1 et compléments ouverts sans modifier silencieusement L1-C |
| SPEC-10_Test_Certification.md | Décomposition de preuve L2 / certificats globaux inchangés ; correction tracée R8 de la permission résiduelle de purge |
| V3_Canon_and_Roadmap.md | Navigation vers dossier L2 et état de préparation ; aucun ACCEPTED nouveau |
| V3_Implementation_Master_Plan.md | L2 DESIGN PENDING : plan disponible, revue/compléments/autorisation encore requis ; ancienne mention « plan L2 absent » actualisée ; statuts L3–L8 conservés |
| ../README.md | Point d'entrée du dossier L2 ; rien dans le README utilisateur sale du checkout principal |

## 5. Contrôles de livraison documentaire

Les commandes d'inspection réellement exécutées comprennent `git status --short`, `git branch --show-current`, `git log -1`, `git remote -v`, `git branch -vv`, `git worktree list --porcelain`, `git rev-parse refs/remotes/origin/feat/v3-architecture`, `git diff 90a3e9bc..HEAD -- docs/testing/Reactor_Verification_Policy.md`, `rg --files`, recherches ciblées `rg -n`, lectures `cat`/`sed` des sources indiquées. `git ls-remote origin refs/heads/feat/v3-architecture` a échoué DNS ; le connecteur GitHub a confirmé la tête exacte. L'inventaire XML de `app/pom.xml` par Python standard a trouvé 60 modules sans invocation Maven. Une capture des états Git des worktrees préexistants a été prise avant édition pour comparaison finale.

Contrôles finaux du dossier proposé (statique uniquement) :

| Contrôle | Résultat documentaire |
|---|---|
| Périmètre du diff | Neuf fichiers Markdown uniquement sous docs/ ; trois créations L2 et six mises à jour ciblées ; aucune entrée app/, scripts/, SQL, POM, runtime ou test |
| Registre | Douze sections uniques L2-A01…A12, sept rubriques par décision ; options exactes B/B/A/B/A/A/B/B/B/A/A/B |
| Liens | 257 liens locaux vérifiés, fichiers et ancrages compris ; zéro cible manquante |
| Inventaire Maven | Liste exacte et ordonnée des 60 modules comparée au POM XML ; aucun build effectué |
| Revue | Dix constats R1…R10, sources/scénarios/options/recommandations/décisions ; R1…R6 tous traités |
| Git / préservation | HEAD du worktree documentaire inchangé à 4127767f ; HEAD et états Git des 13 worktrees préexistants identiques à la capture avant édition ; aucune branche préexistante déplacée |
| Whitespace et diff complet | git diff --check sans anomalie ; patch complet incluant les trois fichiers nouveaux, vérifié par git apply --reverse --check sans modification de fichiers |
| Statuts / reprise | DESIGN PENDING maintenu ; hypothèses proposées et décisions humaines séparées ; absence de méthode future non comptée PASS ; prérequis et procédure I0…I7/G0…G3 explicites |

Commandes finales réellement exécutées (le vérificateur Python est un contrôle documentaire temporaire hors dépôt, aucun test applicatif ajouté) :

```text
python3 -B /private/tmp/pocoma-l2-doc-draft/check_docs.py
git diff --check
git diff --stat
git diff -- docs/README.md docs/v3/SPEC-03_Events_Requests_Consumables.md docs/v3/SPEC-05_Consumption_Discovery.md docs/v3/SPEC-10_Test_Certification.md docs/v3/V3_Canon_and_Roadmap.md docs/v3/V3_Implementation_Master_Plan.md
git diff HEAD -- docs
git diff --no-index -- /dev/null docs/v3/L2_Design_and_Verification_Plan.md
git diff --no-index -- /dev/null docs/v3/L2_Decision_Register.md
git diff --no-index -- /dev/null docs/v3/L2_Contradictory_Review.md
git apply --reverse --check /private/tmp/Pocoma_L2_Documentary_Proposal.patch
git apply --stat /private/tmp/Pocoma_L2_Documentary_Proposal.patch
```

Le code retour 1 de git diff --no-index signifie qu'un nouveau fichier diffère de /dev/null, pas un échec de contrôle. Les contrôles sont répétés après les dernières corrections documentaires. La conservation constatée porte sur HEAD et inventaires d'états Git ; elle n'est pas présentée comme une certification byte-à-byte de toutes les données des autres worktrees ou bases. L'absence de travail préexistant dans le worktree créé, puis le diff borné à ces neuf Markdown, permettent de revoir la proposition sans écraser les checkouts utilisateur. Au checkpoint documentaire initial, aucun commit, push ou PR n’avait été créé. La demande humaine ultérieure autorise explicitement le commit et le push documentaire sur feat/v3-architecture ; aucune implémentation L2 n’est autorisée.


**Limite :** contrôle statique et documentaire seulement ; aucun scénario PG PASS/FAIL nouveau. Déclaré : slices NONE/documentaire ; commandes Maven exécutées NONE ; franchissement inattendu NONE ; gate global NOT REQUIRED/non exécuté ; full reactor NOT REQUIRED/non exécuté ; DB NONE ; commit/push autorisés par la demande humaine de publication qui suit le checkpoint initial. La revue indépendante et l'autorisation de code restent à obtenir. **STOP documentaire obligatoire.**


## 6. Publication documentaire sur demande explicite

Le responsable demande ensuite la publication du dossier préparé. Tête distante recontrôlée avant publication : `4127767f2f9337b495501d7cd5d933ae9782c8ec`, identique à la base du worktree isolé. Publication limitée aux neuf Markdown inventoriés, par avance rapide sur `feat/v3-architecture`, sans force ; aucune branche locale préexistante déplacée. Le SHA de livraison est consultable par `git log -1 --format=%H -- docs/v3/L2_Contradictory_Review.md` et le constat distant accompagne le compte rendu de livraison. Aucun test Maven/PostgreSQL supplémentaire ni code L2. Le checkpoint avant implémentation reste obligatoire.
