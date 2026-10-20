# DISCOVERY — Step Plan

Date : 2026-10-20. **current_plan**. Branche `feat/consumption-transaction-protocol` ; référence
`5640220b94f6208aa4c156e248d95e2eced1522f` (livraison 4B auditée). **4A CLOSED ; 4B CLOSED / PASS hors production ; 4C NEXT ; 4D et 4E non implémentés.**
Ce document suit la finalisation du séquencement et prépare son exploitation par la Discovery.
4B est livré dormant et vérifié ; aucun séquencement E n'est actif en production. [Canon et limites](Step_Canon.md) ; [trajectoire suivante](Next_Plan.md).

## Autorité, critères et état 4A

La [spécification détaillée Étape 4](../../../architecture/Event_Transactional_Sequencing_Step_4_Implementation_Plan_2026-10-19.md)
reste l'unique définition détaillée des lots : §6 pour les critères exacts, §7 pour le cutover,
§8 pour V-A…V-E, §9 pour les arrêts. Ce point d'entrée porte leur état courant et les dépendances.
Numérotation et sens de 4B–4E conservés. Aucun critère n'est affaibli par la synthèse suivante.

**4A — CLOSED**, décision de consolidation demandée pour la référence Git ci-dessus, sur preuves versionnées :

| Livré / preuve | Résultat et portée |
|---|---|
| [Contrats 4A](../../../architecture/Event_Publication_Contracts_Step_4A_2026-10-19.md) | EventRef, EventFamily, PublishedEvents et enveloppes d'acceptation ; gardes locales et règles structurelles ; deux compositions de test (non persistante / durable ordonnée). Aucun collecteur/owner E de production |
| [Fixtures Consumption corrigées](../../../architecture/Consumption_Ambient_Transaction_Five_Tests_Fix_2026-10-20.md) et [JSON](../../../testing/evidence/Consumption_Ambient_Transaction_Five_Tests_Fix_2026-10-20.json) | Cinq courses exercées dans leurs owners réels, garde d'ambient conservée ; livraison `74eda7030769d6a5acb270497eda2f055c178160` |
| [Bootstrap PostgreSQL/Testcontainers corrigé](../../../architecture/Architecture_Postgres_Bootstrap_Six_Errors_Fix_2026-10-20.md) et [JSON](../../../testing/evidence/Architecture_Postgres_Bootstrap_Six_Errors_Fix_2026-10-20.json) | Isolation ConfigData, mapper partagé, READ initialisé indépendamment de l'ordre des tests ; livraison `b7aacef54457583eccd405ef03acc21e05bb156c` |
| Gate architecture global historique `./mvnw -pl architecture-tests -am test` | **PASS : 1 257 tests déclarés, 0 failure, 0 error, 1 skip intentionnel**, sortie 0 ; module architecture : 138 tests sans skip. Ce n'est pas un full reactor |
| [Campagne exploratoire 3B, §§7/9](../../../architecture/Event_Terminal_Sequencing_Step_3B_PostgreSQL_Proof_2026-10-19.md) | `TerminalSequencingPostgresTest.zExploratoryContentionSeparateFromCorrectness`, condition `step3b.measure=true` ; activable avec `-Dstep3b.measure=true`. Pas de seuil de performance ni d'intégration runtime prouvés |

Les rapports initiaux de livraison/diagnostic avaient légitimement un gate rouge et ne prononçaient
pas la clôture. Leurs résultats restent historiques ; la présente consolidation ferme 4A après les
corrections. Le rapport non suivi d'audit du skip est préservé hors livraison et n'est pas une source
indispensable : la condition JUnit et les preuves 3B/du bootstrap sont déjà versionnées.
Aucun test n'est relancé pour cette décision documentaire. Aucun séquenceur PostgreSQL actif.

## 4B — CLOSED : owner PRIMARY et collecteur, hors production

Construire le runner propriétaire/participant PRIMARY et la réalisation de `PublishedEvents` pour
le graphe durable couvert. Configuration importée uniquement par tests, sans scan/autoconfiguration
productif ; pas de migration des producteurs réels. Cycle OPEN/CLOSED/POISONED/CLEANED lié à la
transaction physique, façade de déclaration injectée, snapshot ordonné immuable et cleanup.

Le propriétaire seul, dans le callback du `TransactionTemplate` standard, après retour de l'action
Consumption (finalisation S/Claim incluse), flush/confirme/ferme puis appelle le suffixe expérimental.
Le participant REQUIRED/MANDATORY partage le lot sans le fermer. Pas de `doCommit` personnalisé,
listener Hibernate ou proxy de connexion livré. Primitive JPA insert-only et writer synthétique
éprouvés localement ; **aucun séquenceur PostgreSQL actif ni owner réel recâblé**.

Preuves obligatoires de §6/4B : même PID/xid JPA/JDBC ; ambient inconnu refusé ; suffixe unique malgré
plusieurs participants ; lot vide sans accès E, même E verrouillées ailleurs ; ordre acceptation →
déclaration → S/Claim → flush → fermeture → suffixe ; doublons, publication après fermeture et
contamination entre transactions refusés. Erreur de garde interceptée reste rollback-only/poisoned.
Tester action/flush/suffixe en échec, rollback-only, begin échoué, cleanup après toutes résolutions
et transactions successives sur même thread. Préserver COMMITTED/ROLLED_BACK/UNKNOWN dans la
composition exacte du §4.4 : un échec de suffixe hors action peut être classé phase COMMIT avec
rollback confirmé. Rejet structurel des callbacks écrivains, sans promettre une police SQL universelle.

**Clôture 4B :** V-B vert, rollback physique observé, aucune fuite, suffixe avant commit/après S,
inventaire complet des owners autonomes à migrer ; anomalie de composition réelle bloque 4C/4D.
Code dormant retirable sans opération DB. Les deux compositions de publication restent distinctes.

**Livré et prouvé le 2026-10-20 — PASS.** [Rapport 4B](../../../architecture/Event_Transaction_Protocol_Step_4B_2026-10-20.md)
et [preuves JSON](../../../testing/evidence/Event_Transaction_Protocol_Step_4B_2026-10-20.json) :
29 nouveaux tests 4B verts ; ciblés 37/0/0/0, puis COMMAND canonique 1 047 déclarés,
0 failure/error, 1 skip exploratoire 3B préexistant. Transaction physique JPA/JDBC, vrai finalizer
S/Claim, rollback, ordre/fermeture, poisoning, trois résolutions et cleanup prouvés. Inventaire des
owners autonomes achevé. Configuration et writer synthétiques dans les tests uniquement ; aucun
producteur réel migré. Global architecture/full non requis, non exécutés ; crossing NONE.

**Audit indépendant — PASS confirmé sur le SHA de livraison ci-dessus.** Revue statique favorable,
sans réexécution Maven ou PostgreSQL par l'auditeur ; les résultats ci-dessus restent ceux de la
campagne de livraison 4B. Runner et collecteur restent dormants, non raccordés en production.
Deux réserves non bloquantes pour cette clôture sont rattachées aux critères existants de **4D/V-D**
(conditions A et B du §4D ci-dessous), sans dette technique 4B. Les limites des preuves et le
[compte rendu de clôture documentaire](../../../architecture/Event_Transaction_Protocol_Step_4B_2026-10-20.md#clôture-documentaire-après-audit-indépendant)
ne valent pas certification des writers/owners réels. **4C NEXT ; 4D et 4E non implémentés.**

## 4C — NEXT : schéma additif et séquenceur transactionnel inactifs

Quatre autorités E, quatre associations typées et vue ; migration additive après version courante
(V29 à la référence), mode durable unique PREPARED, droits et contraintes de complétude ACTIVE.
Séquenceur JDBC sur la connexion JPA courante, blocs par famille dans l'ordre déclaré ; aucun
producteur/owner réel raccordé, compteurs à zéro et associations vides en production PREPARED.

**Preuves/clôture :** V-C et vrai DDL PostgreSQL ; continuité, UUID opposés à l'ordre déclaré,
concurrence et attentes serveur, ordre fixe des E, rollback après chaque réservation/INSERT,
collisions/FK/overflow, droits UPDATE/DELETE, lot vide sans E. PREPARED accepte les Events sans
position ; ACTIVE isolé refuse une ligne couverte sans position. Coût indexé des contraintes et
absence d'arête P/B/S après E prouvés ; sinon STOP. Upgrade courant et retour au binaire précédent
avec schéma PREPARED répétés ; aucune infrastructure E activée en production.

## 4D — Producteurs et orchestrations réels, PREPARED complet

Raccorder ensemble BusinessEvents, CommandTerminalEvents, BindingFacts et UserCreatedFacts,
auxiliaires et recoveries compris ; interfaces d'acceptation puis déclarations applicatives,
sans registre dans le métier ni capture technique cachée. Couvrir beans Spring **et constructeurs
`new`**, initializer Registration, TE/TR/TH, et façade propriétaire de l'append Business auxiliaire.
Confirmer manager/EMF/datasource communs en runtimes distribués et monolithe. Conserver P → B → S.

**Preuves/clôture :** V-D, chemins réels gagnants/rejetés/existing, retries vides, claim/CAS/fence
perdus, rollback initializer/finalisation ; références acceptées = déclarées = Events persistés =
positions sur ACTIVE **isolé**, avec ordre de famille exact. Omissions/fictives/doublons échouent
atomiquement en ACTIVE ; PREPARED les détecte par tests sans revendiquer sa contrainte inactive.
Même sémantique exceptions/versions/fences/retries ; tests ports hors owner/fermés ; aucun DML métier
après E sur compositions certifiées. Publication indépendante sans E toujours démontrée.

**Conditions de certification 4D issues de l'audit 4B — à prouver en V-D :**

- **A — Poisoning des writers réels** : chaque writer durable couvert, chemins JDBC compris ;
  erreur interceptée et impossibilité de commit partiel.
- **B — Identité de transaction physique** : owners Command, Registration et auxiliaires ;
  même manager PRIMARY et même transaction physique JPA/JDBC dans toutes les compositions réelles.

Définition normative unique et règles de refus : [§6/4D, conditions A et B](../../../architecture/Event_Transactional_Sequencing_Step_4_Implementation_Plan_2026-10-19.md#4d--raccordement-complet-production-prepared).
Ces conditions précisent la validation existante de 4D ; elles ne rouvrent pas 4B, n'ouvrent aucune
dette technique 4B et ne changent ni le périmètre, ni les invariants, ni les critères de clôture 4C.

Tous les owners/writers certifiés ensemble ; pas de canary par famille. En production PREPARED,
owner/collecte/flush/fermeture/gardes actifs, E/positions encore inactifs. Le retour au runtime précédent
est répété sous drainage avant activation ; échec bloque le déploiement. Contrat exact : §6.1 du plan détaillé.

## 4E — Historique, cutover et activation

Inventorier la base réellement déployée ; reprendre **toutes** les lignes conservées des quatre
sources, y compris consommées et baseline V22. Si nécessaire, backfill déterministe UUID texte
collation C, frontières N consignées, sans prétendre retrouver les commits historiques. Gel exclusif,
drainage, sauvegarde restaurable, contrôles droits/version/contraintes puis activation unique des
quatre familles selon §7. Une variante par lots requiert décision et preuve de reprise sous gel.

**Clôture explicite, cumulative :**

- V-E, gates globaux de clôture et upgrade de la version déployée supportée ; répétition du cutover,
  restauration, reprise après rollback/UNKNOWN et refus d'un ancien writer incompatible.
- Pour chaque famille : `count(Event)=count(Position)=E`, anti-jointures dans les deux sens à zéro,
  couples UUID exacts, positions distinctes positives, `min=1/max=E` si non vide, `E=0` sinon.
- Premiers blocs après N dans l'ordre applicatif, toutes sources/owners couverts sans opt-out ;
  invariants P/B/S, provenance, CAS/Claim, versions, reprises et deux compositions non régressés.
- Seuils débit/p95/backlog/arrêt définis par le responsable puis tenus ; fenêtre et coût E mesurés.
  Aucune valeur arbitraire ajoutée ici. Aucune ancienne instance/import concurrent ; mode unique.
- Autorisation explicite de l'environnement avant activation déployée, distincte de ce plan.
  UNKNOWN non réconcilié, historique incomplet ou gel insuffisant ⇒ maintenir gel et STOP.
- Après premier commit ACTIVE : positions immuables, correction en avant ; aucun retour automatique
  à l'ancien binaire ni restauration perdant des commits. Mettre à jour CURRENT et preuves.

**4E ferme le séquencement ; il ne livre pas la Discovery efficiente.**

## Vérification des futurs lots

Depuis `app/`, après déclaration du lot et lecture de la [politique normative](../../../testing/Reactor_Verification_Policy.md).
4B a exécuté sa commande canonique ; les commandes 4C–4E restent futures. Le détail §8 du plan fait autorité.

| Lot | Slices / commande de clôture | Gates supplémentaires |
|---|---|---|
| 4B | COMMAND : `./mvnw -pl runtime-command-consumption-worker -am test` | Global architecture non requis par défaut ; full non requis |
| 4C | COMMAND + BINDING : `./mvnw -pl runtime-command-consumption-worker,runtime-binding-consumption-worker -am test` | Global non requis par défaut ; full non requis |
| 4D | COMMAND + BINDING + WEB : `./mvnw -pl runtime-command-consumption-worker,runtime-binding-consumption-worker,runtime-web-api -am test` | Complément réel `./mvnw -pl runtime-registration-consumption-worker,runtime-monolith -am test` ; global architecture requis ; full non requis |
| 4E | COMMAND + WEB + BINDING + EVENT + PROJECTION + LKV : `./mvnw -pl runtime-command-consumption-worker,runtime-web-api,runtime-binding-consumption-worker,runtime-event-consumption-worker,runtime-task-consumption-worker,runtime-latest-known-version-consumption-worker -am test` | Registration/Results/monolithe au gate d'intégration ; global architecture et full requis pour clôture majeure |

Global architecture : `./mvnw -pl architecture-tests -am test` ; full : `./mvnw test`.
En 4B/4C, nouvelle dépendance/POM/frontière ⇒ réviser d'abord le gate selon la politique.
DB : 4B CURRENT/prototypes isolés ; 4C CURRENT + nouveau DDL ; 4D CURRENT/ACTIVE isolé ;
4E CURRENT + historique/cutover représentatif. `Trusted baseline: NONE` au HEAD : ne pas inventer
une baseline V29 certifiée ; utiliser une baseline trusted si certifiée ultérieurement.

## Transition 4E → Discovery

4E rend disponible un parcours dense `position > c ORDER BY position LIMIT k`, complet après
frontière historique et atomique avec les effets. Restent à concevoir/implémenter checkpoint durable,
enrôlement, reprise des slots y compris acquis, partitionnement, requêtes et workers :
[chantier A du Next Plan](Next_Plan.md#chantier-a--discovery-efficiente).

Prérequis : index position/famille et UUID source issus de 4C/4E ; schéma/version de checkpoint,
identité consumer/route/partition, unicité des slots, accès indexé aux reprises par état/échéance/lease.
Choisir sur preuve les index supplémentaires ou métadonnées relationnelles minimales ; ne pas
matérialiser tous les payloads pour remplacer une anti-jointure.

Attendus : crash avant/après enrôlement, commit tardif et rollback producteur, UNKNOWN, Busy/lease,
plusieurs routes, nouveaux consumers et repartitionnement ; comparaison PostgreSQL reproductible
`EXPLAIN (ANALYZE, BUFFERS)`, lignes réellement lues, pages, WAL, backlog et latences.
Questions ouvertes : checkpoint/segmentation, équité reprise/nouveaux travaux, taille des lots,
parallélisme intra-worker, seuils de gain et coût des index. Aucune promesse chiffrée actuelle.

## Maintenance et contrôle

Ce plan suit l'avancement ; le canon suit les décisions ; Next Plan suit les dépendances futures.
Promouvoir le prochain chantier après consolidation de l'acquis et archivage traçable du précédent.
La [revue documentaire](Consolidation_Report_2026-10-20.md) conserve inventaire, résolutions et contrôle.
La consolidation initiale était documentaire. La livraison 4B est bornée à COMMAND/NONE, DB CURRENT
et prototypes isolés ; son [rapport](../../../architecture/Event_Transaction_Protocol_Step_4B_2026-10-20.md)
porte commandes, résultats et limites. Aucun gate global/full requis ; crossing NONE. Prochaine action : **4C**, sur autorisation propre.
