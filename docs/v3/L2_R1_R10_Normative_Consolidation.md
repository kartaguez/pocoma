# Pocoma V3 — Consolidation normative L2 R1–R10

**Statut :** arbitrages conceptuels humains R1–R10 validés le 2026-10-26 ; transcription documentaire. **Aucun code, schéma, essai PostgreSQL ou certification L2 n'est acquis par ce document.** L'audit indépendant final et le gate G0 restent obligatoires.

**Sources :** [SPEC-05](SPEC-05_Consumption_Discovery.md), [SPEC-03](SPEC-03_Events_Requests_Consumables.md), [SPEC-10](SPEC-10_Test_Certification.md), [plan L2](L2_Design_and_Verification_Plan.md), [registre](L2_Decision_Register.md), [revue contradictoire](L2_Contradictory_Review.md). Ce document fixe les amendements R1–R10 et prévaut sur les propositions contradictoires antérieures marquées « Rn ouvert » ; les spécifications devront être vérifiées pour toute contradiction restante avant le code.

## 1. R1 — Finalisation du slot, option B

**R1-01** Engine reste propriétaire unique du slot et de l'unité transactionnelle physique, avec verrou exclusif/fencing du claim avant toute participation métier.
**R1-02** Engine fournit à la Consumption une capacité bornée `SlotCompletion.complete()`, à usage unique, liée exclusivement au slot/token protégé dans cette transaction.
**R1-03** La Consumption Command appelle cette capacité après les mutations métier et le résultat terminal cohérent, **avant** la fermeture de la publication et l'ajout du suffixe dense événement/complétion.
**R1-04** La capacité supprime le slot canonique, vérifie exactement une ligne et interdit toute suppression d'un autre slot ; elle n'ouvre pas de nouvelle transaction.
**R1-05** L'Engine vérifie un unique appel avant commit. Appel absent, double, hors ordre, après fermeture ou sur unité empoisonnée => échec, poisoning et rollback physique.
**R1-06** Après fermeture de la publication, seuls les éléments du suffixe autorisés sont écrits. Aucune réouverture de writer métier.
**R1-07** Effets, résultat, suppression du slot et positions denses forment une seule transaction ; exception à n'importe quelle étape => aucun effet partiel committé.
**R1-08** Claims expirés après acquisition effective du verrou final peuvent finaliser sous la protection conservée jusqu'à résolution physique, conformément à L2-A08.
**R1-09** Pas de transaction physique ou owner imbriqué.
**R1-10** Vérifier FK/triggers de suppression et l'ordre réel de verrouillage L1 adapté ; ne pas substituer une suppression tardive après publication.

## 2. R2 — SoftCap, option A

**R2-01** `softCap(C,p)` limite l'admission Discovery de nouveaux slots non-QUARANTINED, sans être un invariant absolu du stock.
**R2-02** Les transactions Discovery d'un même `(C,p)` sont sérialisées en verrouillant la ligne `partition_ownership` ; propriétaire et heure PostgreSQL sont recontrôlés après attente.
**R2-03** Compter sous cette protection les slots non-QUARANTINED ; enrôler au plus la capacité restante. Aucune table de compteur.
**R2-04** Un checkpoint non-burnable n'avance jamais au-delà d'une référence éligible non enrôlée à cause du softCap ; progression uniquement sur préfixe intégralement couvert.
**R2-05** Création de slots et avancement K commitent atomiquement.
**R2-06** Une réactivation administrative ciblée CAS `QUARANTINED -> AVAILABLE` n'exige ni ownership ni capacité disponible ; remet compteur/claim/retry à zéro.
**R2-07** Elle peut dépasser temporairement softCap ; Discovery cesse les nouvelles admissions tant que la marge est nulle.
**R2-08** Une réactivation concurrente de la mesure du stock n'annule pas une admission déjà sérialisée : ce dépassement est acceptable par définition du plafond souple.
**R2-09** Aucun blocage de traitements existants à cause du softCap.

## 3. R3 — Contrat durable de routage, option B

**R3-01** Le routage est une fonction pure déterministe de famille canonique et `consumableId` UUID natif. La convention SHA-256 versionnée proposée par le plan L2 est retenue ; ses octets, séparateurs, ordre, int32 et vecteurs indépendants doivent être figés sans ambiguïté avant code.
**R3-02** Nombre de partitions logique fixé par famille ; tous les Consumers de cette famille partagent la même convention et le même nombre, avec progression propre à chaque Consumption.
**R3-03** Une quatrième table technique `consumption_routing_contract` par autorité physique concernée conserve au moins `consumption_id`, famille, version de routage et nombre de partitions (ou l'empreinte canonique vérifiable correspondante). **Amendement explicite de L2-A03 : quatre tables, et non trois.**
**R3-04** Contrats installés **exclusivement par bootstrap administratif** ; aucun Worker n'a droit de les créer, modifier ou supprimer.
**R3-05** Contrat manquant/incompatible => interdiction Discovery et nouvelles acquisitions.
**R3-06** Acquisition et renouvellement de propriété de partition vérifient transactionnellement le contrat ; la procédure exceptionnelle d'administration doit se sérialiser avec ces vérifications.
**R3-07** Contrats immuables pendant l'existence des slots/checkpoints ; aucune reconfiguration implicite par redéploiement.
**R3-08** Contrôle initial et au lease ; tests de vecteurs protègent la correspondance entre empreinte déclarée et implémentation réelle.
**R3-09** Perte de propriété partition et incompatibilité future ne suppriment pas le fencing de claims individuels déjà valides ; reconfiguration incompatible sous procédure explicitement revue.
**R3-10** Les Workers peuvent différer par partitions candidates, polling et plafonds locaux : seul le contrat de routage est commun.

## 4. R4 — Discovery burnable, option A

**R4-01** Aucun checkpoint burnable durable. Par instance et `(C,p)`, `cursor` et borne haute `U` sont uniquement volatils.
**R4-02** Parcours keyset sur clé stable et totale, par exemple UUID natif PostgreSQL ; les UUID ne sont pas chronologiques.
**R4-03** Capturer U au début du tour et parcourir les clés de la fenêtre jusqu'à U, sans étendre U avec les arrivées.
**R4-04** Les nouvelles références derrière cursor ou au-delà de U restent éligibles au prochain tour ; fin de tour => retour au début et nouvelle U.
**R4-05** Cursor avance seulement sur les références réellement examinées ; collisions/doublons/slots existants ne bloquent pas indéfiniment.
**R4-06** À softCap saturé, suspendre l'enrôlement ; conserver le tour et reprendre lorsque la capacité revient. Ne pas considérer les sources non enrôlées comme durablement couvertes.
**R4-07** Revalider PENDING et absence de slot sous la protection transactionnelle compatible avec terminalisation ; un slot QUARANTINED est un doublon.
**R4-08** Redémarrage/takeover peut perdre cursor/U sans perdre la source durable ; relecture à partir du début.
**R4-09** Sous ensemble initial fini, périodes sans crash suffisantes, capacité revenant et verrous finissant, tout PENDING durablement éligible sera revisité ; aucune latence absolue promise.
**R4-10** Ne pas confondre page de candidats filtrés vide avec absence de clés restantes ; établir une progression SQL effective et un oracle de couverture indépendant.

## 5. R5 — Classification et cession unique, option A

**R5-01** Un seul owner de cession : Engine ; Consumption signale la cause, n'incrémente jamais le budget.
**R5-02** Une acquisition = une tentative ; pas de retry métier interne au même claim.
**R5-03** CHANGED/UNCHANGED/REJECTED terminaux ne consomment pas N. Collision CAS/autorité confirmée, incohérence technique et échec SQL/JPA confirmé sont **comptabilisés** conformément à L1-C.
**R5-04** Crash, claim perdu/expiré, échec préacquisition, commit UNKNOWN ou rollback non confirmé ne produisent aucun incrément supposé.
**R5-05** Après résolution rollback certaine : transaction de cession séparée, verrou du slot, horloge DB après attente, claim/token valide, incrément au plus une fois, effacement du token.
**R5-06** Si compteur nouveau < N, AVAILABLE et retryAfter=dbNow+retryDelay fixe ; sinon QUARANTINED et retryAfter nul. N>=1.
**R5-07** Commit/ACK de cession incertain => relire le durable, jamais répéter aveuglément ; état UNKNOWN n'est pas persisté.
**R5-08** Réactivation administrative ciblée CAS remet count=0, claim et retryAfter nuls ; aucune purge, aucune réactivation automatique.
**R5-09** N et retryDelay configurables, validés et archivés par campagne ; même paire cohérente pour une cession.
**R5-10** Modifications **prospectives** : ne recalculent aucun retryAfter, ne requalifient ni ne réactivent automatiquement les slots ; si count>=N nouveau, prochaine cession constatée met en quarantaine.
**R5-11** Configuration invalide => refus de traitement, sans consommer le budget.

## 6. R6 — Certification bornée, option A

**R6-01** Conserver sans réduction toutes les exigences et variantes SPEC-10 ; trois niveaux de preuve : variante, contribution L2, exigence globale.
**R6-02** Un PASS borné L2 précise obligatoirement la portée réelle ; il ne vaut pas PASS global.
**R6-03** Une exigence incomplète sans violation reste INCONCLUSIVE global ; violation démontrée ouverte = FAIL, même si d'autres variantes sont INCONCLUSIVE.
**R6-04** Manifeste par variante figé et revu avant implémentation : identifiant, portée, réel/fixture, méthode, oracle indépendant, gate, report motivé et lot futur, verdict et preuves.
**R6-05** Les fixtures ne prouvent que les invariants techniques qu'elles exercent ; ni producteur Identity/Invitation absent, ni projection métier CurrentPot/Balance/Visibility absente.
**R6-06** CON-03 générique doit être intégralement exercé ; autres variantes sont allouées explicitement au vrai pilote Command et banc READ technique ou reportées.
**R6-07** Aucun skip, test absent, ancien résultat L1 ou succès Maven seul ne vaut PASS L2.
**R6-08** Audit indépendant, gates architecture et full reactor à clôture majeure, verdicts traçables et décision humaine explicite avant ACCEPTED L2.

## 7. R7 — Bootstrap neuf isolé, option A

**R7-01** Pilote L2 exclusivement sur environnement V3 neuf explicitement identifié, avec autorités WRITE/READ physiquement isolées ; aucune migration historique.
**R7-02** Cibles, identifiants, scripts DDL, writers actifs et permissions inventoriés avant bootstrap ; cible non autorisée => refus.
**R7-03** Environnements existants V2/L1 conservés et non modifiés ; aucune destruction implicite de demandes, résultats ou historiques.
**R7-04** Les sources PENDING sont admises durablement avant Discovery ; TERMINAL et résultat restent préservés et ne sont jamais réenrôlés.
**R7-05** Aucun ancien Worker/protocole incompatible actif sur le périmètre pilote.
**R7-06** Redémarrer un Worker ne reconstruit pas ni n'efface le schéma ; seules données explicitement volatiles peuvent être perdues.
**R7-07** Contrats de routage créés par bootstrap administratif selon R3 ; runtime sans droits DDL.
**R7-08** Reprise historique éventuelle ou destruction d'un jeu explicitement jetable exigerait un mandat séparé.
**R7-09** Tests PostgreSQL d'isolation, conservation PENDING/TERMINAL, absence de double writer et de perte après crash.

## 8. R8 — Interdiction de purge

**R8-01** Aucun slot incomplet n'est purgé ; QUARANTINED persiste jusqu'à réactivation et succès atomique.
**R8-02** Suppression normale uniquement à la réussite dans la transaction d'effets R1.
**R8-03** Absence d'un slot derrière K interprétée exclusivement selon les garanties checkpoint+effets ; une purge briserait cet invariant.
**R8-04** Seul un reset READ **total coordonné** relevant de REC-01 constitue une opération différente, sans permission de purge partielle.
**R8-05** SPEC-10 contient déjà la correction R8 au commit de base ; contrôler tout reste du corpus, DDL, scripts et administrateurs.

## 9. R9 — Observations durables, option A

**R9-01** Engine possède toutes les transactions physiques, decisions de reprise, claims et budget ; Consumption apporte les preuves métier via port de lecture seule.
**R9-02** Interface minimale conceptuelle `RecoveryObservation observe(UUID consumableId, RecoveryReadContext context)` ; `RecoveryReadContext` est une capacité de lecture rattachée à l'unité physique Engine, pas un owner/nested transaction.
**R9-03** Exactement quatre résultats d'observation, non persistés : `COMPLETED`, `NOT_COMPLETED`, `INCONSISTENT`, `UNAVAILABLE`.
**R9-04** COMPLETED exige preuves durables métier cohérentes et preuves techniques requises ; jamais la seule absence du slot.
**R9-05** NOT_COMPLETED ne prouve pas le rollback et n'autorise aucune nouvelle exécution sans claim/fencing ; INCONSISTENT bloque les conclusions optimistes ; UNAVAILABLE préserve l'incertitude.
**R9-06** Observation sans mutation, publication, cession, suppression de slot, réparation ni lancement d'effet métier.
**R9-07** Lecture des faits et de l'état technique dans une vue cohérente PostgreSQL ; si cohérence non garantie => UNAVAILABLE.
**R9-08** Aucun type/table Pot/Identity/Projection dans engine-consumption ; pas de `REQUIRES_NEW`, de reçu SQL supplémentaire ou d'état SQL UNKNOWN.
**R9-09** Une observation COMPLETED avec état technique impossible déclenche INCONSISTENT, jamais une purge réparatrice.
**R9-10** Preuves recovery : ACK perdu, état transitoire, crash, observation impossible/incohérente, reset READ refusé, indépendance des oracles de tests.

## 10. R10 — Équité intra-Consumption, option B

**R10-01** Round-robin borné des Consumptions/partitions ; un second niveau de parcours circulaire borné des slots par `(C,p)`.
**R10-02** Curseur et borne haute U par Worker uniquement en mémoire ; parcours des clés `consumableId` stables, totales, indexables ; pas de FIFO métier.
**R10-03** Fixer U au début du tour, avancer uniquement sur clés effectivement examinées, puis revenir au début. Admissions après U ou derrière cursor revisitées lors de tours ultérieurs.
**R10-04** Navigation des clés distincte logiquement de tentative d'acquisition `SKIP LOCKED`. Page vide de lignes déverrouillées ne prouve pas la fin des clés ; un slot sauté doit être revisité.
**R10-05** `retryAfter`, claims actifs, QUARANTINED ou verrous peuvent rendre un slot temporairement non sélectionnable ; retour au tour suivant, jamais abandon durable.
**R10-06** Saturation M_C/M_W suspend les acquisitions sans avancer fictivement cursor ; réservation atomique de places avant soumission et restitution pour refus, résultats partiels et résolution.
**R10-07** Aucun quota global Worker ni réservation permanente de capacités par Consumption ; les trois bornes softCap(C,p), M_C, M_W restent distinctes.
**R10-08** Renouvellements de claims/leases ont threads **et accès PostgreSQL** indépendants/réservés même si traitements saturés ; lots courts, marges sur durées DB/attente, sans garantie pendant panne durable.
**R10-09** Perte de lease partition => stop Discovery/nouvelles acquisitions/renewal partition ; renouvellement des claims individuels valides reste autorisé.
**R10-10** Progrès sous conditions admissibles (retour capacité, verrous finis, fenêtres sans crash, propriété régulière), jamais ordre strict ni délai absolu ; instrumenter âge des éligibles, tours, sélection, renewal et capacité.
**R10-11** Aucun nouvel état durable, scheduler distribué, file de priorité ou dépendance entre slots.

## 11. Traçabilité et campagnes

| Arbitrage | Cibles normatives | Campagnes/contrôles |
|---|---|---|
| R1 | SPEC-05, plan L2, registre A02/A09 | P5, CON-04/06, EVT-02 ; suppression unique, suffixe, poisoning |
| R2 | SPEC-05, plan L2, registre A03/A06/A10 | P2/P3/P6 ; sérialisation, softCap, réactivation |
| R3 | SPEC-05, plan L2, registre A03/A05 | P1/P4 ; 4e table, administration, vecteurs, mismatch |
| R4 | SPEC-05, plan L2, registre A07/A11 | P3 ; tour U, UUID arbitraires, crash, saturation |
| R5 | SPEC-05, plan L2, registre A09/A10 | P6, CON-05 ; budget, ACK, config prospective |
| R6 | SPEC-10, plan L2, registre A12 | P8/G3 ; manifeste exhaustif, PASS bornés |
| R7 | SPEC-03/05, plan L2, registre A01 | P1/P3 ; isolation, writers, données |
| R8 | SPEC-10, SPEC-05 | C02/C04/C05 ; aucune purge |
| R9 | SPEC-05, plan L2, registre A02/A09 | P5/P6 ; lectures cohérentes, UNKNOWN |
| R10 | SPEC-05, plan L2, registre A08/A11 | P4/P7 ; famine, saturation, renouvellements |

La matrice initiale des variantes de SPEC-10 reste exhaustive et **à figer** dans un manifeste méthode-par-méthode avant implémentation, selon R6. Les tests Rn-Txx préparés pendant les arbitrages sont des scénarios de qualification à incorporer, **pas** des preuves exécutées.

## 12. Revue de cohérence documentaire — points bloquants avant G0

Les dix options ont été ratifiées, mais l'examen contradictoire des conséquences techniques conserve les vérifications suivantes :

1. **R1 / publication :** vérifier dans le code et le DDL l'appel à SlotCompletion *avant* fermeture du suffixe, l'ordre des verrous, la suppression exactement une ligne et le poisoning sur double/absence d'appel.
2. **R2 / R4 :** démontrer qu'une page PENDING non enrôlée pour manque de capacité est revisitable et que la sélection de références permet réellement de terminer un tour sous admissions continues. Ne pas assimiler page filtrée vide à fin de population.
3. **R3 / A03 / R7 :** remplacer partout « trois tables » par « quatre tables » dans les exigences en vigueur ; droits administratifs uniquement sur le contrat de routage, vérification de compatibilité atomique avec leases.
4. **R5 / R9 :** séparer décision de cession après rollback certain et observation d'un commit indécidable ; NOT_COMPLETED n'est jamais une preuve de rollback.
5. **R8 :** correction de non-purge déjà présente dans SPEC-10, ne pas réintroduire l'ancienne phrase en fusion documentaire.
6. **R10 :** une requête uniquement `WHERE eligible FOR UPDATE SKIP LOCKED` ne fournit pas, à elle seule, les clés sautées et ne prouve pas la couverture des tours ; exiger SQL/barrières réels et mesures avant validation.
7. **R6 :** couverture des vrais producteurs et projections non réalisés = INCONCLUSIVE global, même si le mécanisme L2 est PASS borné.
8. **R3 :** les règles « contrat par Consumption » et « P identique par famille » doivent être contrôlées ensemble au bootstrap administratif, faute de quoi deux Consumptions d'une même famille pourraient recevoir des nombres de partitions contradictoires.

**Verdict provisoire de revue documentaire interne :** décisions cohérentes sous ces obligations, mais **G0 NON CERTIFIÉ**, audit indépendant non mené et tests non lancés. Toute proposition de clôture totale doit citer les preuves satisfaisant chacun de ces points. La présente revue rédactionnelle **ne se substitue pas à l'audit indépendant** attendu.
