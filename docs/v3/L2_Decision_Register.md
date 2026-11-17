# L2 — Registre des décisions de conception

Date : 2026-10-26. Référence examinée : `4127767f2f9337b495501d7cd5d933ae9782c8ec`.

**Douze décisions validées humainement, transcrites depuis le mandat explicite de consolidation L2.** Options, dans l'ordre : **B, B, A, B, A, A, B, B, B, A, A, B**. Cette validation de conception n'est ni une autorisation de code, ni une acceptation du lot, ni une preuve PostgreSQL.

Autorités : [canon](V3_Canon_and_Roadmap.md), [SPEC-03](SPEC-03_Events_Requests_Consumables.md), [SPEC-05](SPEC-05_Consumption_Discovery.md), [SPEC-10](SPEC-10_Test_Certification.md). Le [plan L2](L2_Design_and_Verification_Plan.md) applique ces choix ; la [revue contradictoire](L2_Contradictory_Review.md) expose les réserves à trancher. Les normes métier demeurent dans les SPEC. Les alternatives ci-dessous sont les approches exclues par les décisions ; les libellés historiques complets des autres options n'étant pas fournis, aucune lettre ne leur est inventée.

## L2-A01 — Unification des slots — B

**Problème.** L1 utilise `v3_command_slot(request_id)`, distinct du modèle commun voulu par SPEC-05 §11.

**Choix.** Remplacement direct dans un schéma V3 de développement reconstruit : `ConsumptionSlot(consumptionId, consumableId)`, une seule autorité de slots et un seul Engine. Installation V3 propre, sans migration des slots historiques de développement.

**Alternatives écartées.** Pérennisation du moteur Command dédié, double protocole actif, migration de slots/claims de développement et compatibilité artificielle V2.

**Justification.** Réunir les garanties L1 et le moteur commun sans ajouter de coexistence ou de conversion inutile.

**Invariants.** Inventorier producteurs, writers, consommateurs et propriétaires avant bascule ; désactiver les anciens chemins incompatibles ; préserver claim/fencing/retry/quarantaine/complétion/recovery ; aucune PENDING perdue dans le scénario retenu, aucune TERMINAL transformée en travail neuf. Installation propre n'autorise pas silencieusement la destruction de demandes existantes : revue R7.

**Impacts documentaires.** SPEC-05 §11, inventaires du plan §§4–5, contrat L1-C conservé comme description historique ; aucun DDL modifié ici.

**Vérifications futures.** Inventaire exhaustif des writers ; bootstrap propre ; PENDING sans slot découverte ; TERMINAL jamais réenrôlée ; absence de seconde voie Command ; régression C/F/F2. Campagnes P1/P3 du plan.

## L2-A02 — Frontière Engine / Consumption — B

**Problème.** Les responsabilités techniques de L1 sont encore dans l'exécuteur Pot et son store.

**Choix.** Engine propriétaire du cycle complet : acquisition/claims, ouverture et résolution des transactions, protection et suppression des slots, issues techniques/recovery, cession/retry/quarantaine. Consumption propriétaire des faits/préparation, décision, validations courantes et protections métier, mutations/résultats/publications et observations durables propres au traitement.

**Alternatives écartées.** Cycle transactionnel ou de slots réimplémenté dans chaque Consumption ; Engine connaissant Pot/Identity/Balance/Visibility ; framework universel de transformations.

**Justification.** Un protocole commun, sans effacer l'ownership métier.

**Invariants.** Domaine pur ; ports explicites et injection par constructeurs ; pas d'interface de simple transfert ; la Consumption participe à l'unité de l'Engine sans transaction imbriquée. Le port `PotCommandTransactions` existant doit être adapté, pas simplement appelé sous une seconde transaction.

**Impacts documentaires.** SPEC-05 §4/§11 ; plan §§3–4/8. R1 et R9 de la revue distinguent responsabilité générique, publication métier et résolution durable.

**Vérifications futures.** Gardes de dépendances, composition valide/invalide, absence de transaction indépendante, poisoning, observations de recovery ; P1/P5.

## L2-A03 — Modèle durable PostgreSQL minimal — A

**Problème.** Persister la coordination indispensable sans historique technique redondant.

**Choix.** Trois tables spécialisées : `consumption_slot`, `partition_ownership`, `discovery_checkpoint`, dans l'autorité des effets WRITE ou READ. Nommage physique final à aligner avec V3. Slot : `consumption_id`, `consumable_id`, `partition_hash`, `claim_token`, `claimed_until`, `retry_after`, `attempt_count`, `state` AVAILABLE/QUARANTINED ; clé logique `(consumption_id, consumable_id)`. Ownership : `consumption_id`, `partition_id`, `owner_token`, `owned_until`. Checkpoint : `consumption_id`, `partition_id`, `position`, pour non-burnables uniquement.

**Alternatives écartées.** PROCESSING/RETRYING/DONE/UNKNOWN durables, positions traitées, compteur durable de softCap, workId, token de partition dans le slot, catalogue SQL de Consumption/famille.

**Justification.** Les claims et états des sources portent déjà le cycle utile ; la preuve dérivée des non-burnables ne requiert pas de journal DONE.

**Invariants.** Pas de table générique de payloads métier ; checkpoint et slots atomiques ; paire token/échéance cohérente ; quarantaine persistante non terminale ; aucune suppression hors succès atomique ou reset READ coordonné autorisé.

**Impacts documentaires.** SPEC-05 §§2–5/7 ; dictionnaire et ownership du plan §5. Aucun SQL exécutable livré.

**Vérifications futures.** Catalogue/contraintes réels, injection de défauts, autorités distinctes, absence des états/champs interdits ; CON-02/06, P1/P2/P5.

## L2-A04 — Identités et familles — B

**Problème.** Préserver l'identité du traitement entre instances, redémarrages et reconstructions.

**Choix.** `consumptionId` UUID fixe, explicite et stable dans la composition ; `consumableId` UUID natif : requestId pour COMMAND/IDENTITY_REQUEST, eventId pour les cinq familles événementielles. `ConsumableFamily` enum Java des sept familles exactes de SPEC-03 §2. Une seule famille par Consumption ; pas de `family_id` par slot.

**Alternatives écartées.** ID aléatoire au démarrage, nom Java comme identité durable, workId, catalogue SQL et changement incompatible de définition sous le même UUID.

**Justification.** L'unicité du slot et la preuve checkpoint/absence exigent une identité stable indépendamment du Worker.

**Invariants.** Même UUID sur tous les Workers pour une définition ; nouvel UUID pour rejeu indépendant avec destination explicite ; mêmes UUID permis pour reconstruction READ complète et cohérente ; UUID définitifs déclarés et documentés avant activation. Aucun UUID de production n'est fixé par invention dans ce dossier : leur allocation explicite est un préalable de composition, plan §6.

**Impacts documentaires.** SPEC-03 §8, SPEC-05 §1, catalogue du plan §2 et contrat de configuration §6.

**Vérifications futures.** Unicité/égalité des UUID entre compositions, redémarrage, rejeu/reset, rejet de définition incompatible ; E03-H/I, C01-K, C02-L ; P1/P2.

## L2-A05 — Partitionnement, routage et propriété — A

**Problème.** Distribuer Discovery/acquisitions sans lier les partitions au nombre de processus.

**Choix.** Partitions logiques fixes selon configuration stable, routage déterministe partitionHash, affectation statique des partitions candidates ; lease PostgreSQL exclusif `(ownerToken UUID, ownedUntil)`. Acquisition/renouvellement/takeover transactionnels. Pas de coordinateur externe, leader global, rééquilibrage automatique ou partitionnement physique PostgreSQL requis.

**Alternatives écartées.** Routage dépendant du nombre de Workers et propriété seulement en mémoire ; lease de partition comme fence de finalisation.

**Justification.** Propriété durable exclusive pour les nouveaux travaux, indépendance des claims déjà acquis.

**Invariants.** Transfert sans modifier slots/checkpoints ; anciens claims renouvelables/finalisables s'ils restent valides ; hash, clé, algorithme et nombre de partitions stables/reproductibles. Reconfiguration incompatible ≠ simple redéploiement. Détails encore à figer explicitement : R3.

**Impacts documentaires.** SPEC-05 §§1/4 ; plan §6 ; revue R3.

**Vérifications futures.** Vecteurs de routage, configuration mixte refusée, concurrence de lease, takeover et claims anciens ; C03-A…L, P1/P4.

## L2-A06 — Discovery événementielle — A

**Problème.** Parcourir cinq séquences globales sans perte lors des frontières de lot ou d'autorité.

**Choix.** Fenêtre bornée cohérente `(K,H]` sur préfixe durable sans trou possible ; K dernière position globale examinée, absent=0, jamais dernière traitée. Références seulement. Hors partition : examinées sans slot local. Transaction des effets : protéger/valider ownership, vérifier K attendu, insérer tous les slots requis du préfixe couvert, avancer K à sa dernière position réellement couverte, commit atomique.

**Alternatives écartées.** MAX d'une autre photographie, séquence réservée non committée, réservation durable de fenêtre, checkpoint de traitements terminés, saut d'un événement non enrôlé sous softCap.

**Justification.** Atomicité locale et événements immuables conservés permettent WRITE préparatoire puis READ final sans transaction distribuée.

**Invariants.** Cinq séquences distinctes, transactionnelles/denses ; reprise depuis durable après collision/rollback/crash ; aucun réenrôlement ≤K ; propriété et K revalidés. La capacité doit borner un préfixe couvert, pas tronquer les seuls slots : R2.

**Impacts documentaires.** SPEC-03 §5, SPEC-05 §§2/6 ; plan §7 ; revue R2.

**Vérifications futures.** C01-A…H/K/L, C02-A…H/K/L et C06-K ; borne sûre couplée à EVT-01 ; P2.

## L2-A07 — Discovery burnable — B

**Problème.** Retrouver toutes les PENDING sans slot malgré pagination et admission continue.

**Choix.** COMMAND/IDENTITY_REQUEST seulement : recherche directe PENDING sans slot, pagination bornée par clé stable, curseur circulaire uniquement en mémoire, retour régulier au début même sous admission continue ; reprise depuis les sources après perte du curseur.

**Alternatives écartées.** Checkpoint burnable, marqueur durable d'enrôlement, OFFSET systématique, curseur monotone sans retour, slot synchrone à l'admission.

**Justification.** La demande durable reste la vérité ; le curseur est une optimisation de parcours.

**Invariants.** Index PENDING adapté ; unicité/idempotence ; revalidation transactionnelle de PENDING avant insertion finale ; propriété et softCap ; slots existants, claims, retries et quarantaines jamais contournés. Curseur ≠ preuve de progression/achèvement. Le retour régulier doit être concrètement borné : R4.

**Impacts documentaires.** SPEC-03 §3, SPEC-05 §§2/6 ; plan §7 ; revue R4.

**Vérifications futures.** C01-I/J ; course terminalisation/insertion ; admission continue devant/derrière curseur, redémarrage, saturation ; P3.

## L2-A08 — Acquisition, fencing et renouvellements — B

**Problème.** Acquérir par capacité sans bloquer les autres slots ni autoriser un ancien claim.

**Choix.** Lots bornés, SKIP LOCKED et CAS, succès partiels, ownership transactionnel pour chaque nouvelle acquisition. UUID individuel neuf à acquisition, conservé au renouvellement ; renouvellement seulement encore valide ; expiration jamais ressuscitée. claimedUntil/retryAfter distincts ; QUARANTINED exclu ; aucun FIFO promis.

**Alternatives écartées.** Acquisition non protégée, renouvellement tardif ressuscitant le claim, reprise anticipée à perte de partition, fence final fondé sur le lease de partition.

**Justification.** Le droit de finir dépend du claim individuel et de son verrou, pas du propriétaire actuel de partition.

**Invariants.** Verrou exclusif final du slot, horloge DB fraîche après attente, token/état/échéance vérifiés ; verrou jusqu'à résolution physique. Expiration post-verrou valide admise. Ancien token incapable de renouveler/libérer/finaliser le remplaçant. Renouvellements Engine centralisés, courts et bornés, indépendants de la saturation.

**Impacts documentaires.** SPEC-05 §4, contrat L1-C ; plan §§6/8/10.

**Vérifications futures.** C03-A…L, C04-A…C/G/H, horloge après attente, course renouvellement/takeover ; P4.

## L2-A09 — Transaction finale commune — B

**Problème.** Généraliser L1-C sans second propriétaire physique ni recul de garanties.

**Choix.** Engine pilote l'unique transaction finale physique : protection du claim, transformation métier encapsulée, suppression du slot après réussite, commit/rollback, classification dont UNKNOWN local. Consumption réalise validations et mutations dans cette unité.

**Alternatives écartées.** Transaction indépendante par Consumption, XA/2PC, résultat présumé committé, réexécution aveugle après incertitude, DSL universel de phases.

**Justification.** Même protocole physique WRITE/READ tout en gardant les protections métier et les observations propres au traitement.

**Invariants.** Autorité commune effets/preuve/suppression ; rejet d'ambiantes/imbriquées non autorisées ; DataSource/connexion/EntityManager effectifs protégés ; poisoning irréversible même erreur interceptée ; claim→Identity→Pot, CAS, contraintes différées, writer_xid, suffixe dense fermé conservés. La suppression par Engine et le suffixe fermé exigent une conciliation explicite, non décidée silencieusement : R1. Résolution incertaine par observations durables : R9.

**Impacts documentaires.** SPEC-05 §4/§11, SPEC-03 §5 ; plan §8 et inventaire des owners. L1-C reste un contrat de l'implémentation L1, pas une prétention d'Engine L2 livré.

**Vérifications futures.** C04-A…L, C06-A/E…M, EVT-01/02 et régression C/F/F2 ; P5.

## L2-A10 — Retries, quarantaine, réactivation — A

**Problème.** Reprendre sans multiplier les effets ni compter des échecs supposés.

**Choix.** Délai fixe configurable. Une acquisition/une tentative complète, aucun retry interne. Après rollback confirmé, courte transaction séparée de cession sous claim courant valide ; incrément unique d'un échec technique durablement constaté. Si count<N : libération conditionnelle et retryAfter=dbNow+retryDelay ; au N-ième : QUARANTINED. N/délai configurables.

**Alternatives écartées.** Backoff exponentiel imposé, retry interne, compteur d'acquisitions, purge automatique, état durable FAILED/UNKNOWN, collision transformée en rejet métier.

**Justification.** Politique simple, budget auditables et intention rechargée à chaque acquisition.

**Invariants.** Crash/perte de claim/expiration/commit incertain ne consomment rien par supposition. Quarantaine persistante/incomplète, exclue de softCap/acquisition, jamais recréée/purgée automatiquement, sans bloquer d'autres slots même Pot/EUI. Réactivation administrative sur clé exacte et CAS QUARANTINED : AVAILABLE, count=0, anciens tokens/échéances effacés, aucune mutation source/effets, acquisition normale ensuite. Classification et cession ambiguë : R5.

**Impacts documentaires.** SPEC-05 §§5/7 ; plan §9 ; SPEC-10 CON-05. Aucun chiffre opérationnel de N/délai inventé.

**Vérifications futures.** C05-A…M et compléments Q : N−1/succès, N/quarantaine, crash sans compte, ancien token, ACK de cession perdu, double réactivation ; P6.

## L2-A11 — Polling, capacité, équité — A

**Problème.** Éviter qu'une Consumption ou un traitement long monopolise la progression.

**Choix.** Deux boucles adaptatives indépendantes Discovery/Engine, coordonnées uniquement par slots durables ; round-robin, au plus un lot borné par Consumption et passage. Pas de priorité, dépendance, file durable ou FIFO. Ralentir à vide/accélérer après progrès effectif avec bornes configurables ; LISTEN/NOTIFY non requis.

**Alternatives écartées.** Boucle séquentielle bloquée par une transformation, réservations permanentes de capacité, ordonnanceur durable supplémentaire.

**Justification.** Opportunités répétées de progression sans promettre l'égalité des débits.

**Invariants.** Distinguer softCap par Consumption/partition, plafond simultané par Consumption/Worker et plafond global Worker ; maxima et non réservations, capacité inutilisée réutilisable dans les limites ; longs traitements ne bloquant pas sélection si capacité libre ; renouvellements indépendants de saturation. Pas d'état d'ordonnancement durable.

**Impacts documentaires.** SPEC-05 §§1/2 ; plan §10 ; revue R2/R4/R10.

**Vérifications futures.** Occupation mesurée ≤ plafonds Worker ; trois Consumptions asymétriques, libération de capacité, erreurs et longs traitements, renouvellements sous saturation, rattrapage sous hypothèses déclarées ; P7.

## L2-A12 — Pilotes et certification bornée — B

**Problème.** Prouver le moteur commun sans faux PASS sur capacités absentes.

**Choix.** Command réel Pot L1 : admission durable PENDING, Discovery burnable, slot canonique, claim, CHANGED/UNCHANGED/REJECTED, POT/COMMAND_COMPLETION, atomicité et recovery. Identity reste explicitement fixture. Banc READ uniquement technique, non-burnable, vrais effets PostgreSQL READ : références WRITE, fenêtres/K/slots/ownership/claims, transaction READ, crash/rollback/retry/quarantaine/commit incertain, sans 2PC.

**Alternatives écartées.** Pilote Pot simulé seul, banc sans effets réels, banc technique présenté comme projection Pocoma, certification de producteurs/projections absents, autoacceptation.

**Justification.** Preuve réelle de chaque frontière physique, sans anticipation métier des lots suivants.

**Invariants.** CON-01…06/EVT-01…03 applicables, régression L1-C/F/F2, concurrence multi-connexions, fautes/ACK perdus, horloge post-verrou, deadlocks/ordre, absence de perte/doublon/famine sous conditions admissibles, coûts SQL/WRITE. Gates futurs COMMAND/EVENT/PROJECTION, architecture et full à clôture majeure, révisés selon graphe. Traçabilité scénario→oracle indépendant→test→commande→SHA→archives→verdict/réserves. ACCEPTED uniquement par décision humaine après audit indépendant.

**Impacts documentaires.** SPEC-10 garde ses critères globaux ; plan §§11–14 décompose contributions L2 et reports ; revue R6. Aucun scénario PG exécuté dans ce mandat.

**Vérifications futures.** P1…P8 ; manifeste ouvert avant toute campagne future ; absence/skip ≠ PASS ; statut global INCONCLUSIVE tant que variantes normatives manquent.


## Décisions complémentaires R1–R10 (2026-10-26)

Les dix arbitrages humains sont **CLOSED conceptuellement** ; voir le [contrat consolidé](L2_R1_R10_Normative_Consolidation.md). Affectations : R1→A02/A09 ; R2→A03/A06/A10 ; R3→A03/A05 ; R4→A07/A11 ; R5→A09/A10 ; R6→A12 ; R7→A01 ; R8→A03/A10 ; R9→A02/A09 ; R10→A08/A11. **A03 est amendé** : quatre tables par autorité concernée (ajout de `consumption_routing_contract`), bootstrap administratif exclusivement. Les références du corps historique à trois tables décrivent l'arbitrage initial, pas le modèle en vigueur. R1 retient l'appel unique `SlotCompletion.complete()` avant suffixe ; R10 retient un parcours circulaire volatil, avec ressources threads et JDBC indépendantes pour les renouvellements. Aucun code ou PASS n'est impliqué.
