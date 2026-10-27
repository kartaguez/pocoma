# Pocoma V3 — L2 / I3 : plan d’implémentation du Consumption lifecycle

**27 octobre 2026 — PLAN PROPOSÉ POUR AUDIT ; IMPLÉMENTATION NON AUTORISÉE.**

Dépôt `kartaguez/pocoma`, cible `feat/v3-architecture`, baseline I2 examinée directement : `03f0725c690bcbd9748d3ea59b62136e1a911e05`, également tête distante vérifiée au début de cette préparation. I2 est **CLOSED / LOCAL BOUNDED PASS** ; ses preuves ne deviennent pas des preuves I3. Le mandat courant autorise la préparation, le commit et le push de ce document, puis impose l’arrêt avant implémentation. Aucun code productif, test I3, migration ou runtime n’est exécuté ou modifié par cette préparation. Audit indépendant du plan puis GO humain distinct obligatoires.

Ce plan développe I3 du [plan L2](L2_Design_and_Verification_Plan.md#14-vérification-future-gates-provenance-et-critères-de-sortie), sans modifier A01–A12/R1–R10. Les protocoles SQL et signatures ci-dessous sont des **choix techniques proposés**, pas du code livré ni de nouvelles normes. Aucun nouvel arbitrage conceptuel bloquant identifié. Les adaptations minimales de droits, de composition et d’entrée de l’Engine sont explicites ci-dessous.

## 1. Référentiel, autorité et limites

Sources directement examinées à la baseline :

| Source | Rôle dans ce plan |
|---|---|
| [Canon V3](V3_Canon_and_Roadmap.md), [plan directeur](V3_Implementation_Master_Plan.md) | Simplicité, limites des lots, programme et gates ; les anciens en-têtes prospectifs ne remplacent pas les clôtures datées. |
| [Plan L2](L2_Design_and_Verification_Plan.md), [registre A01–A12](L2_Decision_Register.md), [consolidation R1–R10](L2_R1_R10_Normative_Consolidation.md) | Frontières, quatre tables, propriété distincte du claim, budget, non-purge, ressources et capacité. La consolidation prévaut sur les anciennes propositions R ouvertes. |
| [Plan I1, §4](L2_I1_G0_Readiness_and_Implementation_Plan.md#4-configuration-pilote-et-catégories-de-paramètres) | Neuf identités, SHA-256 v1, P=4 et paramètres expérimentaux. |
| [Plan I2](L2_I2_Implementation_Plan.md), [rapport I2](L2_I2_Execution_Report.md), [manifeste de preuves I2](L2_I2_Proof_Manifest.json) | Owner livré, guards, R1/R9, périmètre des fixtures et commandes réellement passées. |
| [Manifeste L2](L2_Certification_Coverage_Manifest.md), [oracles indépendants](L2_Independent_Test_Oracles.md) | Allocation des variantes, reports, snapshots et interleavings ; aucune promotion des colonnes prospectives. |
| [SPEC-03](SPEC-03_Events_Requests_Consumables.md), [SPEC-05](SPEC-05_Consumption_Discovery.md) | Sources durables, identité, leases, claims, atomicité, retries/quarantaine. |
| [SPEC-07](SPEC-07_Transverse_Requirements.md), [SPEC-08](SPEC-08_Observability.md), [SPEC-09](SPEC-09_Performance.md), [SPEC-10](SPEC-10_Test_Certification.md) | CON-03/04/05/06, OPS-01, O-01, coûts et portée de certification. |
| [Contrat L1-C](L1_C_Transaction_Contract.md), [matrice F2](L1_F2_Invariant_Matrix.md), clôtures L1-F/F2, corrections/audits L2 référencés par I1 | Préserver ordre, poisoning, intention, accès locaux et suffixe ; pas de nouvel audit général L1. |
| [Politique de vérification](../testing/Reactor_Verification_Policy.md), `AGENTS.md` | Déclaration prospective des slices et distinction local/global/historique. |

**Décisions déjà acquises :** A05/A08 distinguent droit de démarrer et droit de finir ; R3 impose le contrat administratif immuable ; R5 impose cause comptabilisable ET rollback certain ; R2/R8 imposent réactivation sans cap et sans purge ; R10 impose réservation simultanée des deux plafonds et renouvellements indépendants. Les quatre observations R9 et les trois résolutions physiques I2 restent inchangées.

**I3 livre après autorisation :** opérations de leases ; acquisition et renewal conditionnels des claims ; primitive de réservation M_C/M_W ; intégration de la classification/cession après la tentative I2 ; quarantaine et commande administrative ciblée ; preuves PostgreSQL bornées sur WRITE réel et autorité READ technique distincte.

**Exclusions :** Discovery et ses fenêtres/checkpoints/PENDING (I4), navigation circulaire complète et deux boucles adaptatives/ordonnanceur (I5), activation automatique d’un Worker, projections métier READ, producteurs Identity/Invitation réels, refactoring général des chemins historiques, nouvel owner physique ou modification du suffixe I2. Le banc appelle manuellement des opérations réelles sur des slots préparés par fixture ; il n’est pas un Worker intégré. Les preuves de famine et de softCap avec Discovery restent I4/I5.

## 2. Inventaire livré : conserver, étendre, remplacer

Préfixes Java : `app/<module>/src/main/java/com/kartaguez/pocoma/` ; tests sous `src/test/java/` avec le même préfixe.

| Composant réel | Destination I3 et constat |
|---|---|
| `domain-consumption/.../v3/{ConsumptionId,ConsumableFamily,ConsumptionDefinition,RoutingV1}` | **CONSERVER** intégralement : IDs explicites, famille/autorité, P=4, hash signé et floorMod. Aucun état de claim ajouté au domaine. |
| `port-transaction/.../v3/{AttemptTransactions,RecoveryReadContext}` ; `infra-tx-spring/.../v3/SpringAttemptTransactions` | **CONSERVER** contrats et mécanisme. `write` existe déjà : RC RW, sans ambiance ; il n’exige pas une publication pour chaque transaction technique. L’exigence de fermeture est appelée par l’Engine seulement dans la finalisation métier. Pas besoin d’un troisième mode, de `REQUIRES_NEW` ou d’un manager lifecycle. |
| `engine-consumption/.../v3/ConsumptionEngine` | **ÉTENDRE** au cycle acquisition→une tentative→cession éventuelle et aux opérations courtes. Conserver le corps R1/R9 ; étendre son entrée pour porter une réservation à usage unique (§3/§7). |
| `.../v3/{ClaimedSlot,ConsumptionParticipant,SlotCompletion,RecoveryObservation}` | **CONSERVER** les contrats I2 et les quatre observations. `ClaimedSlot` reste la clé/token ; une échéance retournée sert au renouvellement, jamais de preuve finale. |
| `.../v3/port/ConsumptionSlotStore` ; PRIMARY `.../v3/consumption/JdbcConsumptionSlotStore` | **ÉTENDRE** acquisition, renewal, cession et inspection technique bornée. Conserver `protectFinal`, son SELECT puis horloge séparée, `deleteProtected` et `observe`. |
| PRIMARY `.../v3/{JdbcPotCommandStore,JdbcPotCommandObservation,JdbcPotRevisionAdapter,JdbcPotHistoryAdapter}` ; `ExecutePotCommand` | **CONSERVER** production : aucun changement du métier, du writer_xid, des contraintes, des protections Identity/Pot ni du suffixe. Les types existants `ExecutePotCommand.Collision` et `InconsistentPotFactsException` peuvent être reconnus par la composition injectant le classificateur. L’Engine ne les importe pas. |
| `V3PotCommandComposition` | **ÉTENDRE** injection settings/ports/capacité partagée/classification ; toujours fabrique explicite, aucun bean actif/poller. Les paramètres partagés sont des arguments obligatoires de cette fabrique, pas des objets réalloués par Engine ; ses fixtures adoptent le catalogue/bootstrap complet attendu par la garde. |
| `V3ConsumptionCatalogConfiguration`, `V3BootstrapGuard` et profils `application-v3-consumption-pilot.properties` | **CONSERVER** le profil I1 inerte et ses droits SELECT. Ajouter un contrôle lifecycle explicite séparé du contrôle I1 ; pas d’assouplissement du chemin par défaut. |
| WRITE `V5__consumption_foundation.sql`, READ `V1__consumption_foundation.sql` | **CONSERVER** migrations, contraintes, triggers et index. Ownership est précréée au bootstrap : pas d’UPSERT de lease ni de permission INSERT nécessaire pour I3. |
| `scripts/v3/bootstrap-l2-routing.sql` | **ÉTENDRE** ultérieurement le verrou administratif en ACCESS EXCLUSIVE (§4.1) ; mêmes routes, même précondition de vacuité. Aucun bootstrap au redémarrage. |
| Tests `L2ManifestProof`, `L1CommandCompatibilityHarness`, composition Command | **ADAPTER** seulement le handoff de réservation ; garder les variantes I2 et vecteurs métier. Ajouter des preuves I3 distinctes : les anciennes cessions SQL de fixture ne prouvent pas I3. |
| Anciens `AcquireConsumption*`, `HandleConsumptionFailure*`, JPA claims/provenance, orchestrateurs et pollers historiques | **NE PAS RACCORDER**. Pas de réemploi des états DONE, slotId, now applicatif ni retry historique ; retrait physique général hors I3. |

Aucun composant productif I1/I2 n’a besoin d’être remplacé. Le seul handoff remplacé est l’entrée productive « claim déjà acquis sans réservation » : il devient un handle de tentative issu d’acquisition. Ce changement d’API externe ne modifie pas le protocole transactionnel final I2. Les anciens scénarios techniques conservent explicitement leur mode fixture, sans chemin de contournement productif.

## 3. Architecture minimale et signatures proposées

**Un seul owner physique par autorité**, fourni par `SpringAttemptTransactions` et le manager déjà composé : JPA WRITE, JDBC READ. Les adaptateurs SQL n’ouvrent aucune transaction. Une méthode Engine ouvre `transactions.write(() -> store.operation(...))`, interprète sa résolution, puis rend le résultat après commit confirmé. Aucun appel nested depuis une préparation/finalisation ; une cession commence après retour et résolution de l’unité précédente.

Deux classes SQL suffisent : l’existant `JdbcConsumptionSlotStore` pour les slots et un court `JdbcPartitionOwnershipStore` pour leases + vérification de routage. **Le même adaptateur slot**, déjà indépendant du métier, est utilisé contre WRITE ou READ avec la datasource injectée ; sa localisation dans PRIMARY ne crée pas une seconde implémentation. Les factories existantes ont déjà PRIMARY/READ/TX dans leur graphe ; pas de nouveau module ou dépendance Maven proposé.

Signatures conceptuelles à figer à l’étape 0, avec les valeurs auxiliaires imbriquées dans le port concerné :

```java
// Valeur pure dans engine-consumption/v3, pas un owner en mémoire.
record PartitionLease(ConsumptionId consumptionId, int partitionId,
                      UUID ownerToken, Instant ownedUntil) {}

interface PartitionOwnershipStore {
    Optional<PartitionLease> acquire(ConsumptionDefinition expected, int partition,
                                     UUID freshToken, Duration leaseDuration);
    List<PartitionLease> renew(ConsumptionDefinition expected,
                              List<PartitionLease> leases, Duration leaseDuration);
    Optional<PartitionLease> protectAcquisition(ConsumptionDefinition expected,
                                                PartitionLease lease);
    // Protège la ligne jusqu’à la fin de l’unité, vérifie le durable ;
    // retourne les valeurs durables verrouillées, jamais la deadline du cache.
}

// Extensions du port ConsumptionSlotStore ; les opérations I2 sont conservées.
List<GrantedClaim> acquire(PartitionLease protectedLease, List<ClaimCandidate> candidates,
                           Duration claimDuration);
List<GrantedClaim> renew(List<ClaimedSlot> claims, Duration claimDuration);
Optional<Cession> cede(ClaimedSlot claim, RetryPolicy policy);
List<ClaimInspection> inspectResolved(List<ClaimedSlot> claims);
// ClaimCandidate = UUID source + token neuf préalloué par Engine ;
// GrantedClaim = ClaimedSlot + échéance DB ; Cession = count/state/retryAfter.
// Inspection distingue verrou sauté, ligne présente avec token/échéance/état,
// et absence ; elle n’observe ni ne répare les effets métier.

@FunctionalInterface
interface AttemptFailureClassifier {
    FailureKind classify(Throwable originalCause);
    // COLLISION, INCONSISTENT_FACTS, SQL_OR_JPA, TECHNICAL,
    // NON_COUNTABLE ; jamais une résolution physique inventée.
}
```

`protectAcquisition` est une précondition explicitement orchestrée par Engine ; l’adaptateur slots ne devine pas l’ownership depuis le token. Les deux opérations sont dans **la même transaction** ; chaque méthode gardée utilise `SpringAttemptTransactions.guarded` avec la datasource identique. Des appels séparés ou un lease périmé en mémoire ne donnent aucun droit. Engine transmet au store les valeurs durables retournées sous verrou ; le store recontrôle leur échéance avec le `dbNow` final après verrouillage des slots (§4.3). La ligne ownership reste protégée dans cette même unité.

`LifecycleSettings` : record validé contenant durées/bornes et `RetryPolicy(N,retryDelay)` immuable. `WorkerCapacity` : une petite classe locale partagée entre tous les Engines du Worker, avec une section synchronisée pour les deux compteurs, une réservation et des tickets à restitution unique (§7). Pas d’interface par compteur, de table ou de coordinateur distribué.

API Engine proposée :

```java
Optional<PartitionLease> acquirePartition(int partition);
List<PartitionLease> renewPartitions(List<PartitionLease> leases);
List<AcquiredClaim> acquire(PartitionLease lease, List<UUID> examinedKeys);
List<GrantedClaim> renewClaims(List<ClaimedSlot> claims);
Attempt execute(AcquiredClaim acquired); // un seul démarrage possible
void abandon(AcquiredClaim acquired);    // avant démarrage ; pas une cession
int settlePending();                    // inspection bornée, aucun effet/retry
```

`AcquiredClaim` est un handle opaque imbriqué, lié à l’Engine, au claim, à sa réservation et à un booléen atomique de démarrage. L’Engine seul le construit après acquisition committée. Il interdit double soumission, reprise métier sous le même claim et utilisation dans une autre Consumption/Worker. L’ancienne logique `execute(ClaimedSlot)` devient le corps privé de la tentative I2 ; pas de surcharge publique contournant les tickets. **Adaptation des tests I2 :** un helper de test dans le package Engine construit un handle avec une réservation réelle pour leur claim SQL de fixture ; ce helper ne figure pas en production. Le constructeur du handle peut être accessible au package, jamais une API de fabrique « claim arbitraire » exposée par la composition. Les nouveaux tests I3 passent exclusivement par la vraie acquisition.

L’Engine conserve ses résolutions et observations I2 ; son résultat peut être enrichi d’une cession confirmée/indécidable et d’une cause classifiée, sans ajouter d’état SQL. La classification est injectée à la composition, avec connaissances Pot hors Engine. Une annulation n’est pas un type d’échec comptabilisable par défaut. Le renouvellement reçoit seulement les clés/tokens détenus, sans exiger de ticket métier libre. I3 conserve seulement la liste bornée des réservations en attente de résolution (au plus les places M_W), nécessaire pour ne pas perdre leurs tickets lorsque acquire ne retourne aucun handle après UNKNOWN. `settlePending()` permet de les inspecter manuellement sans place métier et de restituer les tickets sûrs ; un booléen distingue acquisition seule et tentative métier commencée. I5 fournira le suivi des claims à renouveler et appellera ces primitives ; I3 n’implémente pas son registre/scheduler complet.

## 4. Protocoles SQL et frontières transactionnelles

Toutes les durées sont liées comme paramètres, jamais interpolées dans le SQL ; exemples `:duration_us * interval '1 microsecond'`. Tous les temps de décision viennent de PostgreSQL. `dbNow` est obtenu par **une instruction distincte après les verrouillages effectifs**, puis lié aux CAS. Pas de `now()`, `transaction_timestamp()` ni CTE évaluant l’heure avant une attente. Les prédicats temporels sont stricts : valide si `until > dbNow`, expiré si `until <= dbNow`, retry dû si `retry_after <= dbNow`.

Chaque transaction est bornée en nombre de clés ; RC RW via le même owner I2 ; timeouts explicites. Les valeurs `dbNow` illustrent un instant de décision PostgreSQL sous verrou, pas un temps conservé entre appels : aucun callback métier ni attente volontaire entre cet instant et le CAS. Dans une boucle de CAS, renouveler l’échantillon DB par clé ; une implémentation peut le calculer dans l’instruction UPDATE suivante, puisque les verrous ont déjà été obtenus par l’instruction précédente, avec la même valeur pour prédicat/échéance. Ne pas transporter l’heure d’un ancien lot. Les seules lectures RR RO restent les préparations/observations R9. Une transaction technique ne requiert ni `SlotCompletion` ni `requirePublicationClosed`. Ne jamais y injecter un effet métier.

### 4.1 Contrat de routage et administration exceptionnelle

Avant acquisition/renouvellement d’ownership et acquisition de nouveaux claims : `SELECT family,routing_version,partition_count FROM consumption_routing_contract WHERE consumption_id=:C` dans l’unité ; égalité avec la définition attendue, partition candidate autorisée et 0≤p<P, sinon refus sans DML. Le SELECT conserve un verrou de table ACCESS SHARE jusqu’à résolution.

**Choix technique :** l’administration de bootstrap prend ACCESS EXCLUSIVE sur le contrat avant toute inspection/écriture, en remplacement de son SHARE ROW EXCLUSIVE actuel ; toute éventuelle procédure exceptionnelle future devra faire de même, arrêter les nouveaux travaux et être revue séparément selon R3-07/09. Les vérifications transactionnelles sont ainsi sérialisées avec elle, sans grant UPDATE sur le routage et sans advisory lock. Un `SELECT ... FOR SHARE/KEY SHARE` demanderait un privilège UPDATE : ne pas l’utiliser pour cette table. Le trigger d’immuabilité et les contraintes I1 restent en place ; aucune procédure de reconfiguration de contrats avec slots/checkpoints n’est livrée par I3.

Ce choix s’appuie sur les règles PostgreSQL 17 de [SELECT](https://www.postgresql.org/docs/17/sql-select.html), [LOCK](https://www.postgresql.org/docs/17/sql-lock.html) et [conflits de verrous](https://www.postgresql.org/docs/17/explicit-locking.html). Il faut prouver la sérialisation par les deux ordres admin/runtime (§9), pas la supposer depuis le nom du verrou. Les grants empêchent tout Worker de participer à l’administration.

### 4.2 Ownership : acquisition, renewal, expiration, takeover

Les lignes existent déjà (8 WRITE, 28 READ dans le catalogue pilote). **Absence = fondation incompatible**, jamais insertion réparatrice runtime.

1. Vérifier le contrat (§4.1).
2. `SELECT owner_token,owned_until FROM partition_ownership WHERE consumption_id=:C AND partition_id=:p FOR UPDATE SKIP LOCKED`.
3. Si aucune ligne retournée : aucun droit, succès partiel/vide. Une lecture EXISTS sans verrou peut distinguer une ligne sautée d’une absence durable pour diagnostic ; elle ne confère aucun lease et ne déclenche aucune création.
4. Instruction séparée `SELECT clock_timestamp()`.
5. Acquisition/reprise seulement si libre ou expirée, avec UUID neuf généré avant soumission et connu pour résolution incertaine :

```sql
UPDATE partition_ownership
SET owner_token=:newToken, owned_until=:dbNow + :lease_us * interval '1 microsecond'
WHERE consumption_id=:C AND partition_id=:p
  AND (owner_token IS NULL OR owned_until <= :dbNow)
RETURNING owner_token,owned_until;
```

Renouvellement : même verrou puis horloge, `UPDATE ... SET owned_until=greatest(owned_until,:dbNow+:leaseDuration) WHERE ... AND owner_token=:token AND owned_until>:dbNow RETURNING ...`. **Ne jamais convertir un renewal refusé en acquisition sous le même token.** Après perte/expiration, arrêter les demandes de renewal de cette incarnation ; une réacquisition normale reçoit un nouveau token. Un takeover ne touche aucun slot, checkpoint ou effet. Une expiration est un prédicat, pas une tâche SQL de nettoyage.

Renouvellement de plusieurs partitions : lots par Consumption/autorité, clés ordonnées `(C,p)`, `SKIP LOCKED`, puis horloge et CAS des seules lignes obtenues ; succès partiels. Aucune transaction ne couvre les deux bases. Lease perdu ou contrat incompatible stoppe les nouveaux travaux ; claim individuel antérieur reste indépendant.

ACK ownership incertain : ne pas déclarer une propriété depuis la valeur retournée par le callback. Relire le token et l’échéance dans une nouvelle unité et revalider par le protocole normal avant tout usage ; pas de renewal aveugle d’un token expiré. La disponibilité d’un résultat Java avant le commit ne vaut pas acquisition durable.

### 4.3 Acquisition concurrente de claims

Préconditions : définition compatible, ownership effectif, page de **clés seulement**, réservation atomique préalable de r places M_C/M_W. En I3 les pages viennent du banc ; I5 les fournira par parcours circulaire. Taille de la sous-page ≤r≤B ; doublons interdits, clés ordonnées par PostgreSQL (l’ordre signé de `UUID.compareTo` Java ne doit pas remplacer l’ordre UUID PostgreSQL).

Transaction courte :

1. Vérifier contrat puis `protectAcquisition` : verrou exclusif de `(C,p)`, heure fraîche et propriétaire courant valide ; utiliser `SKIP LOCKED` et retour vide en cas de contention. Garder ce verrou jusqu’au commit, comme future sérialisation Discovery.
2. Verrouiller uniquement les slots de la sous-page :

```sql
SELECT consumable_id,claim_token,claimed_until,retry_after,state
FROM consumption_slot
WHERE consumption_id=:C AND partition_id=:p AND consumable_id=ANY(:keys)
ORDER BY consumable_id
FOR UPDATE SKIP LOCKED;
```

3. **Après** ces verrous, relire `clock_timestamp()` et recontrôler la validité des valeurs durables `owner_token`/`owned_until` retournées par `protectAcquisition` ; la ligne de partition reste verrouillée, donc ces valeurs ne peuvent pas changer. Cela ferme le cas expiration pendant une attente effective, y compris de verrou de relation. Pas de nouvelle acquisition autorisée grâce à l’heure de l’étape 1.
4. Pour chaque ligne obtenue, utiliser son UUID de claim neuf préalloué par Engine **avant** la transaction dans la liste bornée `ClaimCandidate` (connue même si le callback ne revient pas), puis CAS :

```sql
UPDATE consumption_slot
SET claim_token=:freshToken,
    claimed_until=:dbNow + :claim_us * interval '1 microsecond'
WHERE consumption_id=:C AND consumable_id=:id AND partition_id=:p
  AND state='AVAILABLE'
  AND (retry_after IS NULL OR retry_after<=:dbNow)
  AND (claim_token IS NULL OR claimed_until<=:dbNow)
RETURNING consumable_id,claim_token,claimed_until;
```

Le compteur n’est pas incrémenté ; retryAfter n’est pas recalculé (il peut rester une échéance passée). Succès partiel normal ; aucune source métier n’est chargée. Chaque UPDATE doit rendre zéro ou une ligne ; plus d’une = faute. Les droits de démarrer sont vérifiés au point de décision sous verrous, conservés jusqu’au commit ; aucun contrôle d’ownership ultérieur dans la transaction finale I2.

La requête volontairement simple examine une sous-page pouvant contenir claims actifs/retries/quarantaines ; elle ne promet pas de remplir le lot. Les index existants suffisent aux clés bornées. I5 avancera sur les clés réellement examinées, même sautées par SKIP LOCKED, et les revisitera au prochain tour. **Lot vide ≠ fin du stock**. Si r=0, aucun SQL d’acquisition ni avance de curseur. La réservation précède le choix définitif de la sous-page : ne pas avancer sur le reliquat non examiné faute de places.

Les UPDATE sont sérialisés par les verrous des slots et leurs prédicats. Deux instances du même owner peuvent se disputer le lot : aucun token valide n’est écrasé. Un nouveau propriétaire attend l’expiration propre du claim ; un slot verrouillé en finalisation est sauté par l’acquisition et revisité après résolution.

### 4.4 Renouvellement non bloquant des claims

Un lot de ≤B clés/tokens, d’une seule autorité, sans contrôle d’ownership ni de capacité métier :

```sql
SELECT s.consumption_id,s.consumable_id,s.claim_token,s.claimed_until
FROM consumption_slot s
JOIN (VALUES /* triples C,id,token liés */) AS r(c,id,token)
  ON s.consumption_id=r.c AND s.consumable_id=r.id AND s.claim_token=r.token
WHERE s.state='AVAILABLE'
ORDER BY s.consumption_id,s.consumable_id
FOR UPDATE OF s SKIP LOCKED;
-- instruction suivante seulement :
SELECT clock_timestamp();
```

Sous les verrous obtenus, pour chaque clé : `UPDATE consumption_slot SET claimed_until=greatest(claimed_until,:dbNow+:claimDuration) WHERE consumption_id=:C AND consumable_id=:id AND claim_token=:token AND state='AVAILABLE' AND claimed_until>:dbNow RETURNING ...`. Aucun effacement/recréation de claim ; même token. Prendre l’heure après le dernier verrou, puis un nouvel instant DB pour chaque CAS de la boucle afin de ne pas renouveler une clé qui aurait expiré entre deux instructions. Une CTE de temps dans cette **nouvelle instruction post-lock** est possible ; elle ne remplace pas le SELECT verrouillant précédent. Les UPDATE suivants n’attendent pas de verrou de ligne supplémentaire ; garder B petit et mesurer leur durée.

**Démonstration S1/S2 :** T1 détient le verrou exclusif de S1 ; le renewal verrouille S2 en sautant S1, lit l’heure après sa sélection et committe la prolongation de S2 avant sa deadline, **pendant que T1 reste ouverte**. S1 n’est ni déclaré perdu ni prolongé par supposition ; il reste au prochain passage s’il est encore détenu. Si sa finalisation a obtenu un verrou valide, I2 peut finir malgré expiration ultérieure. Une absence du résultat de renewal signifie « non renouvelé par ce lot », pas « repris ».

SKIP LOCKED évite l’attente de **ligne**, pas un DDL/verrou de relation ou une panne ; `lock_timeout`, `statement_timeout` et timeout de transaction bornent ces cas. Une erreur SQL annule le lot technique ; succès partiel désigne les clés réellement committées du lot, jamais un UPDATE non committé. Pour B initial=32 et M_W=4, le coût reste limité ; si les mesures révèlent qu’un lot ne tient pas la marge, réduire B avant toute sophistication.

Réponse perdue : relire les échéances durables ; ne pas prolonger depuis une échéance supposée reçue. Un lot ultérieur peut renouveler le même token **uniquement si encore valide**. Ne pas supprimer le suivi d’une tentative à cause d’un simple saut de verrou. Le dispositif indépendant I5 appellera ces méthodes avec sa propre cadence/ressources (§8), pas depuis un thread métier attendant sa place.

### 4.5 Cession, retryAfter et quarantaine

Préconditions visibles dans l’Engine : tentative démarrée une seule fois ; résultat physique **ROLLED_BACK confirmé** par I2 ; cause réelle classée comptabilisable (§6). La cession ne se trouve jamais dans un `finally` universel, dans la Consumption ou dans `observe`.

Nouvelle transaction séparée, aucun verrou de partition : `SELECT ... FROM consumption_slot WHERE C=:C AND id=:id FOR UPDATE`, timeout court ; **puis** `SELECT clock_timestamp()`. Vérifier state AVAILABLE, token exact et deadline strictement future. Sinon zéro changement et zéro budget. Sous ce verrou, avec une paire (N,délai) capturée ensemble :

```sql
UPDATE consumption_slot
SET attempt_count=attempt_count+1,
    state=CASE WHEN attempt_count::bigint+1 >= :N THEN 'QUARANTINED' ELSE 'AVAILABLE' END,
    retry_after=CASE WHEN attempt_count::bigint+1 >= :N THEN NULL
                     ELSE :dbNow + :retry_us * interval '1 microsecond' END,
    claim_token=NULL, claimed_until=NULL
WHERE consumption_id=:C AND consumable_id=:id AND state='AVAILABLE'
  AND claim_token=:token AND claimed_until>:dbNow
RETURNING attempt_count,state,retry_after;
```

Les expressions SET utilisent l’ancien count PostgreSQL. N validé dans la plage int positive ; une valeur durable hors chemin normal proche de l’overflow doit produire diagnostic/refus, jamais un compteur qui revient à zéro. Dans le chemin normal, AVAILABLE n’atteint pas INT_MAX : à N il est déjà quarantainé. La perte de partition n’empêche pas cette cession. Le token effacé rend une seconde cession du même échec inefficace, y compris deux appels concurrents : **au plus un +1 durable** sans table de tentatives.

Échec/timeout/ACK de cession : distinguer rollback certain et UNKNOWN via le même owner. Ne pas fabriquer une cession réussie depuis l’exception. Sur UNKNOWN, relire et inspecter le durable ; **choix simple : ne pas rejouer cette cession**, même si le slot semble inchangé. Arrêter le renewal de cette tentative abandonnée et laisser expirer un claim éventuellement conservé. Un résultat R9 NOT_COMPLETED n’autorise aucune comptabilisation. L’échec peut n’avoir jamais été compté, comme après un crash : cela n’autorise pas une estimation compensatoire.

### 4.6 Réactivation administrative explicite

Livrer une commande SQL administrative ciblée, pas un endpoint public ou un scheduler. Paramètres obligatoires : cible database/schema/rôle, C et UUID source, état attendu QUARANTINED. Vérifier la cible et le rôle **avant** le DML ; paramètres `psql` correctement quotés, `ON_ERROR_STOP`, transaction bornée. Le rôle administratif n’est jamais hérité par le runtime.

```sql
BEGIN;
-- assertions cible/rôle et timeouts locaux
UPDATE consumption_slot
SET state='AVAILABLE',attempt_count=0,claim_token=NULL,
    claimed_until=NULL,retry_after=NULL
WHERE consumption_id=:C AND consumable_id=:id AND state='QUARANTINED'
RETURNING consumption_id,consumable_id,state,attempt_count;
COMMIT;
```

Une ligne = réactivation ; zéro = absent/non-Q/déjà réactivé, explicitement sans succès inventé. Deux administrateurs concurrents n’effectuent qu’une transition, le WHERE étant revérifié après attente par PostgreSQL RC. Aucun besoin d’horloge pour cette transition sans deadline. Aucun ownership, COUNT, softCap, suppression, changement source ou effet métier. Le même slot reprend par acquisition normale sous M_C/M_W ; dépasser softCap par cette action est autorisé. ACK administratif perdu : relire, ne pas remettre à zéro un slot redevenu AVAILABLE. Pas de purge même après N ni de réactivation au démarrage.

### 4.7 Verrous, guards et résolution

Ordres : routage ACCESS SHARE → ownership → slots pour nouveaux claims ; routage → ownership pour lease ; slots seulement pour renewal/cession/admin ; **slot → source → Identity → Pot → suffixe** inchangé pour I2. Les triggers de slot relisent le contrat pour vérifier le hash, sans verrou de partition ; le contrat ne change pas en exploitation. Aucune opération après un slot ne retourne verrouiller une partition. Les lots de slots suivent l’ordre PostgreSQL `(C,id)`. Les partitions suivent `(C,p)` ; pas de verrouillage cross-base.

`inspectResolved` réemploie une prise bornée `FOR UPDATE SKIP LOCKED` des clés/token, puis horloge, dans une transaction technique du même owner, sans DML. Pour chaque clé non retournée, une lecture EXISTS sans verrou distingue une ligne encore visible mais sautée d’une absence ; ne jamais assimiler résultat SKIP LOCKED vide et absence. Une ligne obtenue prouve qu’un verrou de finalisation antérieur sur cette ligne n’est plus détenu ; il **ne prouve pas** que cette finalisation a rollbacké et ne déclenche pas de cession. L’absence peut exiger R9 COMPLETED pour résoudre une fin métier. Une ligne sautée laisse l’incertitude ouverte. Cette primitive est nécessaire aux réservations en issue incertaine (§7), sans nouvelle observation métier ni état durable.

Tests négatifs : absence d’owner, mauvaise datasource/manager, ambiance, REQUIRES_NEW/NESTED, faute interceptée ; toutes les nouvelles entrées writer ont la même garde I2. Pas de changement productif de `SpringAttemptTransactions` attendu. Un défaut réellement démontré impose d’abord diagnostic et révision de scope ; I3 n’est pas un prétexte pour réécrire le protocole I2.

## 5. Migrations, permissions et configuration de la composition

**Aucune migration nouvelle proposée pour I3.** Les quatre tables, colonnes, contraintes et trois index de slots sont déjà livrés. Ne pas modifier rétroactivement WRITE V5/READ V1 ni rejouer un historique V2. Installer les droits par un **script administratif explicite post-bootstrap**, transactionnel et ciblé, sur les environnements V3 neufs autorisés. Ce script n’est ni Flyway runtime ni DDL applicatif et ne réinitialise aucune donnée. Sa réexécution vérifie l’allowlist effective ; pas de `GRANT ... ON ALL TABLES` ni privilèges par défaut pour des tables futures.

| Objet | Rôle runtime lifecycle I3, WRITE et READ | Justification / interdits |
|---|---|---|
| Schéma V3 | USAGE, pas CREATE ni ownership | Aucun ALTER/DROP/TRUNCATE/TRIGGER ; aucun membership du rôle propriétaire/admin, y compris NOINHERIT susceptible de SET ROLE. |
| `consumption_routing_contract` | SELECT seul | Aucun INSERT/UPDATE/DELETE ; pas d’UPDATE factice pour un verrou de ligne. |
| `partition_ownership` | SELECT ; UPDATE(`owner_token`,`owned_until`) | Lignes précréées, ni INSERT ni DELETE ni modification de clé/partition. |
| `consumption_slot` | SELECT ; UPDATE(`claim_token`,`claimed_until`,`retry_after`,`attempt_count`,`state`) ; DELETE pour la seule completion I2 | Ni INSERT (I4), ni UPDATE de C/id/hash/p, ni TRUNCATE. DELETE reste encapsulé par R1 ; aucun port de purge. |
| `discovery_checkpoint` | SELECT seul | Aucun INSERT/UPDATE/DELETE avant I4. |
| Fonctions hash/triggers invoker | Exécution/lectures nécessaires à leurs appels existants | Ne pas les convertir en SECURITY DEFINER ; pas de nouvelle fonction privilégiée lifecycle. |

**Limite explicite de l’ACL :** PostgreSQL ne peut distinguer par un simple grant DELETE une completion valide d’un SQL arbitraire de purge, ni distinguer sur les mêmes colonnes une cession d’un reset malveillant. L’invariant est porté par les seules entrées writer gardées et la composition de confiance I2, testées/inventoriées ; les credentials runtime ne sont pas exposés comme console d’administration. Le plan ne prétend pas résister à du SQL arbitraire contournant tous les ports. Ne pas ajouter un framework/trigger de capability uniquement pour masquer cette limite déjà présente dans le modèle I2. R8 exige l’absence de voie de purge dans l’application et ses scripts, pas un nouveau journal technique.

Le script reçoit les noms de rôles déjà provisionnés ; il ne crée pas de rôles au démarrage ni de credentials dans le dépôt. Le rôle de réactivation distinct reçoit USAGE, SELECT du slot et UPDATE des cinq colonnes de reset seulement ; pas DELETE/INSERT, routage, ownership, checkpoint ou métier. Le bootstrap/DDL conserve son rôle administratif séparé, jamais les credentials Worker. Tests avec vrais rôles LOGIN non propriétaires ; les tests sous superuser seuls seraient insuffisants.

**Pilote Command sous vrai rôle runtime :** compléter explicitement les droits des writers I2, jamais par ALL : SELECT sur les tables V3 métier consultées ; INSERT sur `v3_pot_identity`, `v3_pot_version`, `v3_shareholder_identity`, `v3_expense_identity`, les huit tables de révision (`v3_pot_header`, `v3_pot_details`, `v3_shareholder_header`, `v3_shareholder_details`, `v3_shareholder_weight`, `v3_expense_header`, `v3_expense_details`, `v3_expense_financials`) et `v3_expense_participant`, `v3_command_result`, `v3_pot_event`, `v3_pot_event_component`, `v3_command_completion_event`, `v3_event_position` ; UPDATE(`active_to`) sur les huit tables de révision, UPDATE(`current_version`) sur `v3_pot_identity` (trigger CAS invoker), UPDATE(`status`) sur `v3_command`, UPDATE(`position`) sur `v3_event_counter`. Aucun INSERT Command pour le rôle de consommation : admission de fixture par son rôle séparé. Aucun UPDATE/DELETE d’intention/résultat/événement/position historique. Les colonnes identity restent générées par les INSERT existants ; lifecycle n’appelle aucune séquence/nextval. Prouver l’exécution complète avec ces droits effectifs, y compris triggers et identity ; si un privilège manque, inventorier l’instruction exacte avant d’ajouter le seul droit nécessaire.

Identity reste fixture explicite : ses SELECT et protection de témoin par FOR UPDATE exigent le droit UPDATE minimal sur sa table **de test**, jamais un grant sur une autorité Identity V3 absente. READ n’obtient aucun droit de mutation WRITE. L’effet technique READ est une table de banc, avec droits de test propres, jamais une projection métier ou table Engine supplémentaire.

**Garde I1/I3 :** conserver `validate()` et l’inspection d’inertie inchangées pour `v3-consumption-pilot`. Ajouter une méthode explicite `validateLifecyclePrivileges()` réutilisant identité/routage/isolement puis une allowlist de **droits de colonne et de table** : `has_table_privilege` seul ne détecte pas tous les grants de colonne ; utiliser aussi les contrôles de colonnes et memberships. Une fabrique I3 statique non annotée l’appelle sur ses sources réelles, puis assemble sans démarrer de thread. Le profil I1 ne sélectionne pas cette méthode et continue de refuser les DML ; pas de second profil de polling avant I5. Le gate architecture est déclaré requis pour cette nouvelle composition et sa preuve d’isolation, sans assouplir l’interdit d’activer les anciens composants.

## 6. Classification des issues : condition de cession

La classification s’effectue **après** la résolution fournie par I2, à partir de la cause originale conservée (cause/suppressed/nextException avec protection contre cycles). Le classificateur ne décide jamais COMMITTED/ROLLED_BACK/UNKNOWN et ne consulte aucun état métier pour reconstruire une issue physique.

| Situation observée | Comptabilisable ? | Action |
|---|---|---|
| COMMITTED confirmé, y compris diagnostic de cleanup après commit ; observation COMPLETED après UNKNOWN | Non | Fin de tentative, résultat originel ; jamais cession ni retry interne. Garder la résolution historique UNKNOWN si c’est le cas I2. |
| CLAIM_LOST, absent/stale/expired à la protection finale | Non | Aucun +1, aucun effacement ; arrêter le suivi après fin locale sûre. |
| Collision CAS Pot / témoin d’autorité, rollback physique confirmé | Oui, COLLISION | Cession conditionnelle séparée ; après retryAfter, **nouvelle acquisition et nouvelle préparation intégrale**. |
| Faits incohérents, corruption de payload/données, violation de protocole writer ou bug technique constaté, rollback confirmé | Oui, INCONSISTENT_FACTS ou TECHNICAL | Même cession ; aucune conversion en rejet métier. Les erreurs de configuration reconnues restent exclues. |
| SQL/JPA confirmé pendant préparation/finalisation ou rejet serveur au commit, rollback confirmé | Oui, SQL_OR_JPA | Même cession, sous token encore valide. SQLSTATE de contraintes/serialization/deadlock et erreurs de persistance réelles couverts. |
| ROLLED_BACK sans cause identifiée ou seulement enveloppe de rollback | **Pas automatiquement** | NON_COUNTABLE, diagnostic ; ne pas supposer une faute métier/technique. |
| Validation de configuration/composition, refus avant acquisition, annulation/interruption simple, crash, perte/expiration de claim | Non | Pas de cession comptabilisée. Une annulation n’efface pas la cause d’un échec technique déjà confirmé ; garder la première cause réelle. |
| UNKNOWN, rollback non confirmé, erreur transport 08/40003 ; NOT_COMPLETED/UNAVAILABLE après observation | Non | Observation R9/inspection technique, aucun retry métier sous ce claim, aucune estimation de budget. |
| INCONSISTENT comme **observation recovery**, même si erreur ailleurs | Non par cette seule observation | Diagnostic et blocage de conclusion optimiste ; pas de réparation/cession automatique. Ne pas confondre avec exception de faits incohérents réellement rollbackée. |

La composition Command reconnaît ses types collision/faits ; l’adaptateur de classification SQL reconnaît JDBC/JPA/Spring sans importer ces types dans Engine. La règle TECHNICAL reconnaît aussi une véritable exception applicative inattendue de la tentative (par exemple NullPointerException ou erreur de protocole IllegalStateException), après exclusion explicite des annulations, pertes de claim, erreurs de configuration et simples enveloppes de rollback. Cela constate une faute réelle, contrairement à un simple statut ROLLED_BACK. Une cause absente, perdue ou réduite à une enveloppe de résolution reste NON_COUNTABLE et diagnostiquée. Ne pas exclure globalement IllegalArgumentException : une intention durable corrompue peut justement être une faute technique comptabilisable. Les tests incluent **le même statut ROLLED_BACK avec deux causes différentes**, l’une comptée et l’autre non, ainsi que rollback de préparation, rollback final, erreur masquée et faute writer interceptée conservée par I2.

Pour garder peu de classes, le classificateur peut être une lambda assemblée par `V3PotCommandComposition`, qui délègue l’examen SQL/JPA à une méthode pure statique dans la composition lifecycle ; il ne mérite pas un service par type de faute. Aucune nouvelle classe d’exception universelle imposée au domaine Pot. Chaque composition future doit fournir sa classification explicite ; elle ne copie pas le budget ni la cession.

## 7. Capacité simultanée M_C / M_W

Les plafonds portent sur les **tentatives de ce Worker**, pas sur le stock SQL ni l’ensemble des Workers. softCap(C,p), M_C et M_W restent trois limites distinctes ; claims actifs vus en SQL peuvent inclure des Workers morts ou des claims abandonnés, donc `COUNT(claim_token)` global n’est pas l’oracle de concurrence locale.

`WorkerCapacity` conserve `usedWorker` et `usedByConsumption` sous une seule section synchronisée. `reserve(C,wanted)` alloue `r=min(wanted,B,M_W-usedWorker,M_C-usedC)`, incrémente les deux compteurs ensemble, puis retourne r tickets individuels. Pas de sémaphore pris dans un ordre variable, d’attente bloquante de place, de quota préalloué par Consumption ou de limite distribuée. Un Worker mixte WRITE/READ partage **la même instance** et ses deux plafonds ; une instance par Engine ferait perdre M_W.

La réservation commence avant acquisition/soumission ; son transfert au handle de claim et sa restitution sont explicites, atomiques et idempotents. Les flags privés de démarrage/fermeture d’un ticket sont du contrôle local ordinaire, pas une nouvelle machine d’états durable. Les erreurs fatales qui sortent sans résolution physique exploitable gardent leur réservation en attente ; un finally ne fabrique jamais un rollback ni une cession.

| Chemin | Réservation / claim / restitution |
|---|---|
| Aucune place | Retour vide immédiat ; pas de SQL ni d’avance fictive du curseur. |
| Acquisition confirmée de k sur r | Transformer k tickets en handles, rendre r−k après résolution ; aucune place perdue pour slots verrouillés/actifs/retry/Q ou ownership refusé. |
| Acquisition rollbackée | Aucun handle ; restituer r après résolution. |
| Acquisition UNKNOWN | Aucun traitement lancé. Conserver provisoirement r (majorant sûr) et les clés/tokens tentés ; inspecter les clés après la fin locale. Pour les clés dont la transaction est résolue, rendre leurs tickets ; aucune supposition de rollback. Si token neuf existe, **choix simple : l’abandonner sans exécuter ni renouveler**, puis expiration normale. Aucune cession (+1) pour cet échec d’acquisition. |
| Refus de soumission / annulation avant démarrage | Fermer le handle une fois, restituer après fin de l’acquisition certaine ; ne pas céder un faux échec. Le claim éventuellement acquis expire normalement, sans compteur. |
| Annulation pendant traitement | Demande coopérative, arrêt de nouvelles phases ; le ticket reste occupé jusqu’à sortie effective du callback et résolution. `Future.cancel(true)` seul ne prouve ni arrêt ni rollback. Pas de cession sur la seule interruption. |
| COMMITTED ou ROLLED_BACK certains | Fin locale et ressources de l’unité terminées ; cession éventuelle exécutée sous la même réservation avant restitution. Double callback de nettoyage ne restitue pas deux fois. |
| Fin métier/cession UNKNOWN | Aucune réexécution ; garder le ticket tant qu’une exécution serveur ou locale peut continuer. Résoudre par R9 et inspection non bloquante, sans budget supposé. Retard de restitution assumé si l’issue reste indécidable. |
| Crash processus | Les compteurs mémoire disparaissent avec ce Worker ; aucune place durable à réparer. Les claims expirent, finalisation concurrente toujours fenced. Le nouveau Worker n’est pas un quota global de l’ancien. |

**Résolution des places incertaines, sans nouvel algorithme de recovery métier :** le retour local d’Engine ne suffit pas si le serveur tient encore une transaction. Après cessation de toute tâche locale, une inspection qui obtient le verrou de la même ligne de slot prouve la fin de la protection exclusive précédente ; nouveau token ou ligne inchangée sont alors des faits techniques, pas une preuve de rollback. Restituer la place sans céder/réexécuter. Si la ligne est absente après une tentative finale, exiger l’observation R9 COMPLETED cohérente ; une absence seule reste insuffisante. Si le verrou est sauté, l’observation indisponible/incohérente ou le callback encore actif, garder la place et diagnostiquer. Pas de libération sur simple dépassement d’un timeout/deadline : I2 autorise une finalisation valide à dépasser son échéance sous verrou.

Après acquisition UNKNOWN, les clés non tentées sont connues par le contexte local et leurs places sont rendues ; celles réellement en doute restent réservées jusqu’à inspection. La sous-page et tous ses tokens sont préalloués **avant** le premier UPDATE et passés au port, donc connus même si l’exception empêche son retour ; une petite liste locale suffit, sans persistance. Pour une clé soumise mais dont le DML effectif est inconnu, garder conservativement son ticket jusqu’à inspection. Une panne durable peut donc réduire temporairement la capacité utile ; cette limite est préférable à un dépassement dissimulé. Les probes/observations de résolution utilisent `settlePending()` et la voie de contrôle réservée, pas une nouvelle place métier, et ne relancent aucun effet. Une acquisition seule n’a jamais lancé de métier : son absence de ligne constatée peut libérer son ticket sans inférer une complétion ; après tentative finale, l’absence exige R9 COMPLETED comme indiqué ci-dessus.

## 8. Paramètres, coûts et préparation des renewals I5

Conserver les hypothèses I1 comme point de départ, sans les promouvoir en dimensionnement :

| Paramètre | Valeur expérimentale I1 | Validation/ajustement I3 |
|---|---|---|
| Identités/P/routage/autorités | Neuf UUID, P=4, SHA-256 v1 | Inchangés. Partitions candidates uniques, dans 0..3 ; identité source/manager et droits effectifs validés. |
| softCap / W | 64 / 128 | Inchangés, pas utilisés pour bloquer acquisition/renewal/admin I3 ; Discovery I4 les qualifiera. |
| B | 32 | ≥1 ; borne de clés/SQL par unité. Acquisition limite aussi à r ; renewal indépendant peut utiliser B entier. Réduire si marges observées insuffisantes. |
| M_C / M_W | 2 / 4 | ≥1 ; M_C peut dépasser M_W sans danger (maximum effectivement M_W), pas de rejet arbitraire nécessaire. Vérifier une capacité partagée entre Engines/autorités. |
| N / retryDelay | 3 / 30 s | N entier 1..INT_MAX ; délai non négatif, représentable sans overflow. Paire immutable/snapshot unique par cession, effets prospectifs R5-10. Délai nul possible dans un test déclaré, pas substitué à la valeur pilote. |
| Lease / claim / renewal cible | 30 s / 60 s / 10 s | Durées positives, conversion exacte en microsecondes PostgreSQL, bornes anti-overflow et comparaison timestamptz/Instant sans arrondi ; cadence plus marge doit rester sous lease et claim. Changement ne prolonge/raccourcit pas rétroactivement un durable. `greatest` interdit de raccourcir un renewal valide. |
| lock_timeout renewal | 500 ms | `SET LOCAL` dans la même unité ou réglage de session vérifié ; SKIP LOCKED traite les lignes, timeout traite notamment les relations. |
| Ressources renewal | 2 connexions claims + 1 leases **par autorité** | Conserver cette hypothèse ; accès réellement réservé + threads indépendants à qualifier ci-dessous. |

Timeouts techniques proposés pour démarrer les campagnes : lock 500 ms, statement 2 s, unité technique 3 s, accès connexion 1 s. Ce sont des propositions expérimentales, distinctes du timeout final I2 de 60 s ; les versions finales sont archivées avant chaque essai. Aucun réglage global muté pendant une tentative. Marges : intervalle renewal + retard de dispatch + accès connexion + durée lot/commit < durée utile du lease/claim ; rapporter la marge restante mesurée, pas la déduire du seul 10<30. Une panne PostgreSQL durable ou un DDL exclusif prolongé ne permet aucune garantie perpétuelle.

**Réserver les accès sans second manager :** une seule datasource poolée et un seul manager par autorité. Borner les participants non-renewal à M_W connexions métier (préparation/finalisation/cession séquentielles par ticket) + une connexion de contrôle (acquisition/ownership/inspection/observations hors tentative, sérialisées). Réserver en plus 2 accès de renewal claims et 1 de leases ; taille du pool ≥ M_W+1+3, soit **8 avec M_W=4**, par autorité, et aucun emprunteur non inventorié. Pas de second pool/manager lifecycle autonome. Les outils admin utilisent leurs connexions séparées et n’occupent pas le pool Worker. Le plafond global M_W d’un Worker mixte peut rendre ce dimensionnement conservateur ; ne pas l’optimiser prématurément.

I3 valide ces bornes à la composition et exerce **un harnais manuel** saturant les voies métier/contrôle pendant des renewals sur leurs voies réservées, avec vrais PID et échéances. Les méthodes productives restent appelables sans timer ; I3 n’ajoute ni boucle ni `@Scheduled`. I5 créera les exécuteurs/cadences indépendants et garantira que tous les emprunteurs passent par ces voies : deux threads claims et un leases par autorité selon l’hypothèse initiale, sans attente de ticket métier. La garantie intégrée sous saturation reste **INCONCLUSIVE avant I5** même si la primitive S1/S2 est prouvée en I3. Un pool de taille 8 seul ne réserve rien si des appels hors bornes sont permis ; ce point fait partie du contrat de composition, pas d’une promesse de configuration Hikari.

**Index et coûts à mesurer :**

| Existant | Utilité I3 / limite |
|---|---|
| PK ownership `(C,p)` et contrat `(C)` | Sondes ponctuelles, un court verrou par nouvelle acquisition de lot ; contention inter-instances du même owner/Discovery future. |
| PK slot `(C,id)` | Renewal/cession/finalisation/inspection par clés ; pas de scan de backlog. |
| `consumption_slot_partition_walk_idx(C,p,id)` | Pages de clés de l’acquisition ; réutilisable pour parcours I5. |
| `consumption_slot_live_count_idx(C,p) WHERE state<>'QUARANTINED'` | Futur COUNT softCap ; coût maintenance si changement de state, pas un compteur I3. |
| `consumption_slot_available_due_idx(C,p,retry_after,claimed_until) WHERE state='AVAILABLE'` | Déjà présent ; renewal modifie une colonne indexée, donc coût WAL/index/vacuum réel, pas promesse de HOT/O(1) physique. Les OR de NULL/due ne garantissent pas que cet index sera choisi. |
| Trigger `consumption_row_guard` | Recalcule hash et lit contrat à chaque UPDATE de slot ; coût réel à mesurer en renewal aussi. Ne pas le retirer par intuition. |

Avant de proposer un index supplémentaire ou un UPDATE multiligne sophistiqué : EXPLAIN (ANALYZE, BUFFERS) sur schéma jetable, rollback du DML de mesure, cardinalités 100/1 000/10 000 slots par partition, proportions libre/actif/retry/Q et lots identiques ; compter SQL, lignes JDBC, buffers, WAL si accessible, durées et attentes. Acquisition simple = contrat + ownership/horloges + une sélection bornée + ≤r CAS/horloges ; renewal = sélection + ≤B CAS/horloges ; cession = verrou + horloge + UPDATE. Aucune lecture d’historique métier, COUNT total ni nouvelle structure n’est justifiée à ce stade. Le coût linéaire au lot borné reste acceptable à démontrer ; aucune certification PERF-01 ou SLO E2E I3.

Observabilité bornée I3 : les retours des opérations et un snapshot synchronisé de WorkerCapacity exposent au banc les données nécessaires pour enregistrer réservations/occupations/restitutions, acquisitions gagnées/refusées, renewals committés/sautés/tardifs, leases perdus/repris, cessions comptées/Q, incertitudes et temps DB. I3 vérifie cette observation au banc ; le branchement des compteurs/histogrammes aux boucles réelles revient à I5, sans ajouter maintenant un nouveau port de métriques ou un wrapper Engine. Timers monotones locaux, échéances DB dans preuves privées ; aucune UUID/token en label ou log de service. L’âge exhaustif des éligibles et les tours dépendent d’I4/I5 ; ne pas inventer un timestamp durable pour les mesurer maintenant.

## 9. Matrice PostgreSQL déterministe et raccord aux preuves

**Toutes les méthodes ci-dessous sont proposées, NON ÉCRITES / NON EXÉCUTÉES.** Classes prévues : `ConsumptionLifecyclePostgresTest` (PRIMARY, vraies opérations sur WRITE et READ technique), `ConsumptionLifecycleCompositionPostgresTest` (COMMAND, wiring/ressources), `ReadConsumptionLifecyclePrivilegesPostgresTest` (READ, droits réels) et `WorkerCapacityTest` (Engine, concurrence locale). Les noms ci-dessous fixent les scénarios à inventorier dans le futur manifeste exécutable I3, sans renommer les ID normatifs existants. Un scénario partagé peut contribuer à plusieurs clauses ; il ne multiplie pas le nombre de garanties distinctes.

### Dispositif commun

PostgreSQL 17 réel, image figée par digest au lancement (reprendre la référence I2 si disponible), deux bases/autorités WRITE/READ réellement distinctes, rôles admin/runtime/réactivation distincts. WRITE neuf : locations explicites `db/v3-pot/migration`, `db/v3-pot-execution/migration`, `db/v3-consumption-write/migration` ; READ neuf : véritable migration `db/v3-consumption-read/migration`, jamais une copie réécrite en fixture. PRIMARY n’a pas de dépendance READ : son banc résout **le fichier réel dans le dépôt** via une location Flyway filesystem, vérifie la racine attendue et archive le SHA256 de la migration ; ne pas ajouter une dépendance productive uniquement pour une ressource de test. Le test du module READ utilise sa propre ressource classpath. Aucune migration V2, aucun schéma préexistant détruit. Les scripts grants/administration sont exécutés comme livrés avec `psql -X -v ON_ERROR_STOP=1` dans le conteneur, paramètres non secrets explicites, plutôt qu’en recopiant leur UPDATE dans le test. Les suites historiques transitives restent signalées comme telles.

Pour chaque essai, préinscrire C/id/partition/tokens/deadlines/N/délai/états et attendu indépendant ; UUID littéraux ou seed archivée, vecteurs de routage indépendants I1. Une barrière confirme la prise de verrou T1, un PID T2 et `pg_blocking_pids` attestent l’attente effective quand le scénario en exige une ; l’observateur confirme l’ordre `transaction_start < deadline < horloge_DB_avant_déblocage`, puis libère T1. Barrières/injections restent dans les wrappers et triggers de fixture, sans hook productif supplémentaire. Les seuils temporels sont franchis par sondage de `clock_timestamp()` avec timeout de harnais ; un sleep de durée supposée n’est jamais la preuve d’expiration. Les cas SKIP LOCKED démontrent le **commit S2 avant libération de S1**, pas seulement un temps d’exécution « rapide ».

Après résolution, snapshot extérieur RR RO de slots/ownership/K/sources/effets/résultats/positions pertinents ; attendu littéral issu de la fixture, jamais du sélecteur/classificateur/recovery testé. Le banc READ utilise une ligne d’effet technique immuable par clé, aucune projection métier revendiquée. Archivage des chronologies, PID/xid8, SQL et compteurs. Timeout/interleaving non démontré = INCONCLUSIVE, pas PASS.

### CON-03 complet au niveau des primitives, avec limites Discovery/I5

Chaque méthode `i3_c03_*` de `ConsumptionLifecyclePostgresTest` est exécutée pour les deux autorités ; le sous-scénario WRITE utilise le vrai participant Command, le READ un participant technique identifié. Les variantes de panne restent distinctes dans l’inventaire et les traces.

| Méthode prévue / ID SPEC-10 et L2M correspondant | Stimulus contrôlé et oracle décisif | Oracles / portée |
|---|---|---|
| `i3_c03_a` / C03-A | A acquiert réellement S ; lease A expire, B reprend la partition tandis que claim A reste valide. B acquiert S2, pas S ; A finalise S sans accès ownership. Garder une partition verrouillée en témoin pour détecter une consultation finale. | OR-CL-OWNER/FENCE, OR-AT-UNIT ; acquisition/finalisation I3. Discovery réelle reste I4. |
| `i3_c03_b` / C03-B | Ancien ownerToken refuse acquisition ; vecteur slots inchangé. Le même port de protection refuse l’enrôlement simulé sans DML. | OR-CL-OWNER ; seule contribution au contrôle partagé ; la moitié Discovery est explicitement reportée I4, pas PASS complet C03-B. |
| `i3_c03_c` / C03-C | Après transfert, A renouvelle S pendant que B renouvelle son lease ; un verrou de partition retenu n’empêche pas S. | OR-CL-RENEW/OWNER. |
| `i3_c03_d` / C03-D | Lease A expiré, claim futur : B propriétaire ne le reprend pas ; aucun effet/count modifié. | OR-CL-FENCE. |
| `i3_c03_e` / C03-E | Claim expiré, course vrai renewal A / acquisition B ; deux ordres de verrou. Jamais de résurrection A, B seul peut finaliser avec son nouveau token. | OR-CL-RENEW/FENCE. |
| `i3_c03_f` / C03-F | A prend le verrou final valide, échéance passe, B tente acquisition : S est sauté ; A committe une fois, B revisite et trouve absent. Conserver aussi le concurrent finaliseur bloquant I2 pour attester la protection jusqu’au commit. | OR-CL-FENCE, OR-AT-UNIT. SKIP LOCKED remplace l’attente d’acquisition par absence de gain ; même sérialisation des mutations. |
| `i3_c03_g` / C03-G | Finaliseur A attend réellement le slot et expire pendant l’attente ; heure post-lock refuse. Sous-variante cession attendante expirée : zéro +1. | OR-CL-FENCE, OR-RT-CEDE ; conserve le contre-exemple à now() I2. |
| `i3_c03_h` / C03-H | B reprend puis A tente finalisation, renewal, cession : vecteur B exactement inchangé dans les trois cas. | OR-CL-FENCE, C04-B/G, CON-05-OLDTOKEN. |
| `i3_c03_i` / C03-I | B perd ensuite sa partition mais garde son claim : renewal et fin autorisés ; nouveaux claims refusés. | OR-CL-OWNER/FENCE. |
| `i3_c03_j` / C03-J | Coupures filaires avant COMMIT et après commit serveur ; takeover concurrent ; R9 et état extérieur prouvent zéro/un effet, jamais +1 supposé. | OR-RC-FOUR, OR-AT-UNIT ; primitive READ seulement. |
| `i3_c03_k` / C03-K | Deux Workers, plusieurs partitions P=4, changements d’owners ; liste littérale d’acquisitions autorisées, mêmes hash et claims valides anciens. | OR-CL-OWNER, OR-AR-ROUTE. |
| `i3_c03_l` / C03-L | Campagne répétée à seed fixe et interleavings rejouables ; résultats et journal d’observation du banc concordent avec les transitions SQL/commits/expirations ; l’instrumentation Worker intégrée reste I5. | OR-CL-FENCE/OWNER/RENEW, SPEC-08 ; pas de qualification charge/Worker I5. |

Ajouter dans la même classe : `i3_lease_races` (deux acquéreurs du lease, expiry durant attente et ancien token), `i3_route_admin_serialization` (admin/runtime dans les deux ordres, route manquante/mismatch, aucun grant routage), `i3_acquisition_partial` (slots libres/actifs/expirés/retry/Q/verrouillés, aucune reprise anticipée), `i3_renew_s1_s2` (invariant §4.4, lot mixte expiré/ancien token/Q/absent), `i3_renew_relation_timeout` (verrou de relation borne la tentative ; aucun +1 ni succès non committé), `i3_acquisition_lease_clock` (deadline ownership franchie avant l’heure finale de décision : zéro claim). Ajouter `i3_sql_costs` pour les plans/volumes/mesures §8, sans SLO global. Ces scénarios contribuent à A05/A08, R3-05/06/09, R10-08/09 et aux oracles OR-CL/OR-EQ-RENEW.

### Retry, quarantaine, administration

Chaque ligne indique une méthode `ConsumptionLifecyclePostgresTest`, sur WRITE et READ technique sauf mention métier précise. Les méthodes sont des scénarios complets, pas des wrappers d’assertions vides.

| Méthode prévue | Preuve normative / stimulus et attendu |
|---|---|
| `i3_c05_a` | C05-A, R5-01/02/03/05 : erreur de préparation réelle, rollback RO confirmé, une seule cession + délai ; aucun deuxième appel prepare/finalize sous le token. |
| `i3_c05_b` | C05-B, R5-03/05 : faute technique/SQL/JPA réelle après effets, dont erreur interceptée et contrainte différée ; vecteur métier rollbacké avant XID distinct de cession. OR-AT-POISON/UNIT, OR-RT-CEDE. |
| `i3_c05_c` | C05-C : vraie collision Pot/témoin, cession, retryAfter, nouvelle acquisition/token et recalcul ; intention initiale conservée, aucun faux rejet. |
| `i3_c05_d` | C05-D : après recalcul, vrai rejet obsolète terminal normal ; aucun +1 pour ce rejet. |
| `i3_c05_e` | C05-E : faits métier changent entre acquisitions, observedVersion/payload d’intention restent identiques ; prepare réexécuté depuis source. |
| `i3_c05_f` | C05-F : refus avant retryAfter et gain à partir de l’échéance DB, indépendamment de la présence/expiration du claim. |
| `i3_c05_g` | C05-G : claim repris avant cession tardive, aucun changement du nouveau token/count/retry ; également cession encore-token mais expirée. |
| `i3_c05_h` | C05-H, R5-07 : deux coupures filaires de cession, commit durable +1 vs rollback zéro ; relecture, aucune répétition aveugle, aucun slot recréé. |
| `i3_c05_i` | C05-I, CON-05-NMINUS1 : N−1 échecs constatés puis succès, tokens distincts, délai observé, suppression atomique seulement au succès. |
| `i3_c05_j` | C05-J, CON-05-NTH : Nᵉ échec met Q, source PENDING/effet absent ; base indisponible sans rollback certain ne consomme rien. Distinguer ces deux branches. |
| `i3_c05_k` | C05-K, CON-05-CRASH : arrêt après rollback avant cession, expiration et réacquisition ; count strictement inchangé. |
| `i3_c05_l` | C05-L : cession après perte de partition sous claim valide autorisée ; aucune nouvelle acquisition par ancien owner. |
| `i3_c05_m` | C05-M : journal du banc appelant la composition distingue tentative, succès/rejet, faute classifiée, cession committée/UNKNOWN ; ne compte pas un ACK perdu comme deux échecs. SPEC-08, pas SLO. |
| `i3_failure_classification` | R5-03/04/11 : table §6 complète, dont deux ROLLED_BACK de causes différentes, cancellation, configuration invalide, cause absente/masquée, UNKNOWN+NOT_COMPLETED ; seul rollback certain + cause positive ouvre la cession. |
| `i3_cession_once` | R5-05, CON-05-OLDTOKEN : deux cessions concurrentes, une seule ligne +1, ancien token effacé ; heure DB après attente et perte de validité pendant attente. OR-RT-CEDE. |
| `i3_quarantine_persists` | CON-05-PERSIST/NOPURGE, R8 : même slot après nouveau contexte/temps/configuration ; acquisition ne le reprend pas ; inventory des writers/scripts sans purge ; completion d’un claim Q refusée. La non-réinscription par vraie Discovery reste I4. |
| `i3_reactivate` | CON-05-REACT/NOTQ/REACTOUT, R2-06/R5-08 : commande réelle avec rôle distinct, même clé, reset cinq champs ; cas absent/non-Q/ACK perdu et course réactivation/acquisition (jamais de reset du nouveau claim) ; succès et rollback ultérieurs passent par Engine. OR-RT-REACT. |
| `i3_two_admins` | CON-05-TWOADMIN : attente réelle de T2 sur T1, une transition, témoins/source/effets identiques. |
| `i3_reactivate_over_cap` | CON-05-REACTCAP/SOFTCAP : stock initial 64 hors-Q et un Q ; reset donne 65 hors-Q, aucune consultation ownership/cap ni effet. Suspension des admissions Discovery réelle reportée I4. |
| `i3_other_slot_progresses` | CON-05-OTHER/VV1 : Q et autre slot même agrégat/Consumption, autre C et autre partition ; la primitive traite les autres sans dépendance. Analogue READ explicitement technique, jamais PASS monotonicité CurrentPot/Balance/Binding. |
| `i3_retry_config_prospective` | CON-05-CONFIG, R5-09/10/11 : N=1, N=3, baisse N sous count courant, hausse N, changement délai ; vieilles échéances/Q inchangés, prochaine cession avec une paire unique ; config invalide refuse avant traitement. OR-RT-CONFIG. |

Reports inchangés : CON-05-DISCOVERY et partie ré-enrôlement/stock admis de PERSIST/SOFTCAP/REACTCAP → I4 ; V+1 métier/HTTP → L4/L5 selon le manifeste ; sans source/projection réelle, aucune certification de ces branches. Régression fraîche des méthodes I2 C04-A…H/I/K/L applicables et C06/EVT liées via la slice COMMAND ; les scénarios I3 ne remplacent pas leurs injections de défauts.

### Capacité, ressources, droits et architecture

| Classe/méthodes proposées | Preuve |
|---|---|
| `ConsumptionFailureClassificationTest.countableRequiresKnownCauseAndRollback`, `lostUnknownCancellationAndConfigNeverCount` | Table §6, causes/résolutions indépendantes, exception originale et cas négatifs ; aucune dépendance JDBC/Pot dans Engine. |
| `WorkerCapacityTest.simultaneousLimits`, `partialAndDoubleRelease`, `oneShotAndCancellation` | M_C/M_W sous départs concurrents et deux C, tickets alloués/rendus une fois, acquis partiels, double démarrage/refus/cancel ; registre indépendant des callbacks actifs, pas simple lecture des compteurs testés. |
| `ConsumptionLifecyclePostgresTest.i3_capacity_uncertainty` | Acquisition/fin/cession UNKNOWN avec transaction serveur maintenue sous barrière ; place retenue tant que le slot est verrouillé, contrôle/renewal accessibles, restitution après inspection sûre ; zéro cession supposée. Annulation d’un thread qui ignore l’interruption ne libère pas sa place. |
| `ConsumptionLifecycleCompositionPostgresTest.sharedCapacityAcrossAuthorities` | Même WorkerCapacity pour deux Engines et les deux autorités, aucune réservation permanente par C ; refus si instance par Engine dans le contre-exemple. OR-EQ-CAP, R10-06/07. |
| `...renewalsHaveReservedDatabaseAccess` | M_W=4 saturé + voie contrôle occupée, pool≥8 ; deux accès claims et un lease indépendants ; S2 commit avant deadline pendant S1 verrouillé. Harnais manuel, pas certification boucle I5. OR-EQ-RENEW, R10-08. |
| `...lifecyclePrivilegesAndInertPilotStaySeparate` ; `ReadConsumptionLifecyclePrivilegesPostgresTest.effectiveLeastPrivileges` | Vrais rôles effectifs, grants de colonne, memberships/PUBLIC/NOINHERIT, droits DDL/routage/insertion slots/checkpoints refusés ; opérations permises réussissent, réactivation isolée ; profil I1 toujours inerte. R3/R7, OR-AR-BOOT/BOUND. |
| `...oneOwnerAndNoActiveScheduler` | Source/manager corrects, pas d’owner concurrent ; gardes existantes sur nouvelles entrées ; aucun runner/poller/listener/scheduled installé. Gate architecture et négatifs I2 maintenus. |

Le manifeste I3 liste chaque méthode, chaque sous-variante WRITE/READ/commit/rollback et son gate ; il raccorde les ID L2M existants à ces contributions. Les noms prévisionnels `L2ManifestProof.l2m_...` du manifeste général demeurent l’allocation G1/G2, pas une obligation de dupliquer artificiellement chaque méthode I3 sous ce nom. Conserver séparément les preuves I2 déjà exécutées ; leur réexécution est une non-régression, jamais une preuve d’acquisition/cession productive. L’oracle OR-CL-RENEW décrit historiquement un renouvelleur S1 attendant : I3 exerce la variante plus directe où le renewal saute S1 ; sa preuve de ressources et d’horloge est décrite séparément, sans prétendre avoir exécuté cette attente littérale. Les scénarios d’attente réels restent conservés pour le fencing/cession et la qualification intégrée I5. Le contrôle de présence des XML refuse méthode absente, skip obligatoire, doublon ou trace de sous-variante manquante ; les annexes SQL déterministes restent nécessaires au-delà de BUILD SUCCESS.

## 10. Liste exacte des fichiers pour l’implémentation future

**Aucun de ces changements applicatifs n’est réalisé maintenant.** Pas de wildcard autorisant une réécriture d’un package. Les chemins Java ci-dessous s’entendent sous `app/<module>/src/{main|test}/java/com/kartaguez/pocoma/` ; M=modifier, C=créer. Types auxiliaires imbriqués dans ces fichiers : pas d’autres fichiers implicites.

| Action | Module / source | Chemin après le préfixe Java |
|---|---|---|
| M | engine-consumption / main | `engine/consumption/v3/ConsumptionEngine.java` |
| M | engine-consumption / main | `engine/consumption/v3/port/ConsumptionSlotStore.java` |
| C | engine-consumption / main | `engine/consumption/v3/PartitionLease.java` |
| C | engine-consumption / main | `engine/consumption/v3/LifecycleSettings.java` |
| C | engine-consumption / main | `engine/consumption/v3/WorkerCapacity.java` |
| C | engine-consumption / main | `engine/consumption/v3/port/PartitionOwnershipStore.java` |
| C | engine-consumption / main | `engine/consumption/v3/port/AttemptFailureClassifier.java` |
| M | infra-persistence-primary-jpa / main | `infra/persistence/primary/jpa/v3/consumption/JdbcConsumptionSlotStore.java` |
| C | infra-persistence-primary-jpa / main | `infra/persistence/primary/jpa/v3/consumption/JdbcPartitionOwnershipStore.java` |
| M | composition-runtime-spring / main | `runtime/command/v3/V3PotCommandComposition.java` |
| M | composition-runtime-spring / main | `runtime/consumption/v3/V3BootstrapGuard.java` |
| C | composition-runtime-spring / main | `runtime/consumption/v3/V3ConsumptionLifecycleComposition.java` |
| C | engine-consumption / test | `engine/consumption/v3/WorkerCapacityTest.java` |
| C | engine-consumption / test | `engine/consumption/v3/ConsumptionFailureClassificationTest.java` |
| C | infra-persistence-primary-jpa / test | `infra/persistence/primary/jpa/v3/consumption/ConsumptionLifecyclePostgresTest.java` |
| C | infra-persistence-primary-jpa / test | `infra/persistence/primary/jpa/v3/consumption/ConsumptionLifecyclePostgresFixture.java` |
| C | infra-persistence-primary-jpa / test | `engine/consumption/v3/I2ReservedClaimFixture.java` (helper test-only dans le package du handle, pas une dépendance productive) |
| M | infra-persistence-primary-jpa / test | `infra/persistence/primary/jpa/v3/L1CommandCompatibilityHarness.java` |
| M | infra-persistence-primary-jpa / test | `infra/persistence/primary/jpa/v3/L2ManifestProof.java` (handoff de capacité seulement ; variantes R1/R9 conservées) |
| C | runtime-command-consumption-worker / test | `engine/consumption/v3/CommandReservedClaimFixture.java` (helper du même type, isolé à ce module de test) |
| M | runtime-command-consumption-worker / test | `runtime/command/v3/V3PotCommandCompositionPostgresTest.java` |
| M | runtime-command-consumption-worker / test | `runtime/command/v3/ConsumptionBootstrapPostgresTest.java` (garde historique toujours vérifiée) |
| C | runtime-command-consumption-worker / test | `runtime/command/v3/ConsumptionLifecycleCompositionPostgresTest.java` |
| C | infra-persistence-read-jdbc / test | `infra/persistence/read/jdbc/v3/consumption/ReadConsumptionLifecyclePrivilegesPostgresTest.java` |
| M | runtime-monolith / test | `ConsumptionPilotIsolationTest.java` (refus des imports/activations incompatibles maintenu) |
| M | architecture-tests / test | `architecture/v3/V3ConsumptionEngineArchitectureTest.java` (nouveaux writers/ports/handles et négatifs, aucun affaiblissement des règles) |

Autres fichiers exacts :

| Action | Chemin depuis la racine | Rôle |
|---|---|---|
| M | `scripts/v3/bootstrap-l2-routing.sql` | ACCESS EXCLUSIVE administratif, même seed et préconditions. |
| C | `scripts/v3/grant-l2-lifecycle.sql` | Allowlist §5 sur cible/rôles explicites ; aucun DDL exécuté par runtime. |
| C | `scripts/v3/reactivate-l2-slot.sql` | CAS ciblé §4.6, sans purge. |
| C | `docs/v3/L2_I3_Proof_Manifest.json` | Inventaire figé des méthodes/sous-variantes/gates/provenance avant campagne. |
| C | `docs/v3/L2_I3_Execution_Report.md` | Résultats réels, limites, commandes et anomalies ; aucun verdict prérempli PASS. |
| C | `scripts/testing/verify-v3-l2-i3.py` | Collecte ciblée commandes/XML/SQL/empreintes ; réemployer les fonctions de contrôle de présence I2 sans changer son comportement historique. |
| C | `scripts/testing/test_i3_evidence.py` | Vérification du collecteur : manque/skip/doublon/sous-variante absente rejetés. |
| M | `docs/v3/L2_Certification_Coverage_Manifest.md` | Ajouter les contributions I3 constatées et leurs reports, sans altérer les oracles/allocation ni archives I1/I2. |
| M | `docs/v3/L2_Design_and_Verification_Plan.md` | Statut I3 réel et lien, uniquement après réalisation. |
| M | `docs/v3/V3_Implementation_Master_Plan.md` | Statut opérationnel unique, lien vers preuves et arrêt avant I4. |
| M | `docs/v3/L2_I3_Implementation_Plan.md` | Écarts/choix ratifiés et état documentaire, sans réécrire les décisions humaines. |

Les fichiers de preuve produits iront dans `docs/testing/evidence/L2-I3/`, avec inventaire exact généré au lancement (noms par gate, XML, snapshots, logs, empreintes). Ils ne sont pas des fichiers productifs autorisés supplémentaires. Aucune modification de POM, migration, `AttemptTransactions`, `SpringAttemptTransactions`, `ExecutePotCommand`, writer métier, profil de polling ou runtime EVENT/PROJECTION attendue. Si une nécessité réelle apparaît, identifier le fichier/la frontière et réviser ce tableau et la déclaration de vérification **avant** de poursuivre. La liste est une hypothèse exécutable et contrôlable, pas une autorisation d’élargir silencieusement I3.

## 11. Ordre d’implémentation en étapes bornées

Chaque étape dépend de la précédente ; aucune activation partielle ou double owner. Les tests nouveaux sont écrits avec l’étape qui introduit l’opération, puis consommés par les gates de clôture.

| Étape | Travail / sortie contrôlable |
|---|---|
| 0 — entrée | Audit indépendant de ce plan puis GO humain I3. Recontrôler la baseline et tout delta ; relire politique. Déclarer §12, figer manifeste/méthodes/sous-variantes/settings/cibles/digests et captures avant stimulus. Le GO I3 n’autorise ni I4 ni I5. |
| 1 — contrats/capacité | Settings validés, ports/valeurs purs et primitive WorkerCapacity avec départs concurrents ; aucun SQL ni scheduler. Le handle est one-shot ; prévoir le handoff des tests I2 sans API productive de contournement. |
| 2 — permissions/leases | Script grants et garde lifecycle explicite, verrou admin ; adaptateur lease court et ses courses/contrats. Tester vrais rôles et inactivité du profil I1. Pas de modifications de migrations. |
| 3 — claims/renewals | Étendre le store, acquérir sous ownership + capacité ; renew SKIP LOCKED avec horloge post-lock ; preuve S1/S2, ancien token et expiration. Aucun effet métier dans ces unités. |
| 4 — une tentative/cession | Injecter classification, relier handle→corps I2 inchangé→résolution→cession conditionnelle ; cases non comptabilisables négatives. Ajuster les seuls handoffs de tests I2 ; distinguer leur mode sans cession I2 du mode lifecycle I3. |
| 5 — Q/admin/uncertitude | CAS admin réel, N/délai prospectifs, coupures filaires de cession/acquisition/finalisation, inspection technique et restitution de places sûres ; pas de retry interne ni scheduler. |
| 6 — composition/profilage | Fabriques statiques, même manager/source, capacité partagée, validation ressources et harnais manuel saturé ; coût index/triggers et non-régression I2/C/F/F2. Documenter marges/limites I5. |
| 7 — clôture locale | SHA de code figé, commandes canoniques §12 et collecteur strict ; conserver échecs/diagnostics. Audit indépendant code+preuves, corrections puis rejeu des gates affectés. Rapport borné et décision humaine ; arrêt avant I4/I5. |

**Compatibilité des preuves I2 à l’étape 4 :** les scénarios I2 qui exigent « aucun changement du slot après rollback » utilisent un handle avec réservation et un classificateur de test retournant explicitement NON_COUNTABLE ; ils exécutent le même corps I2 et continuent à prouver exactement R1/R9. Aucun drapeau productif « désactiver les cessions » ni accès au corps privé n’est ajouté. Le chemin productif lifecycle, avec cession, est prouvé séparément par I3 : comparer le vecteur métier au rollback puis le slot après la transaction distincte de cession. Ne pas affaiblir l’assertion I2 pour accepter une mutation de slot inexpliquée. Cette séparation de bancs ne crée pas deux owners productifs.

## 12. Verification scope et commandes de clôture futures

| Champ obligatoire | Déclaration prospective I3 |
|---|---|
| Expected production impact | Engine V3/ports/capacité, adaptateurs techniques slots/leases, factories et validation de droits, scripts administratifs. Aucun métier/suffixe/owner I2 modifié. |
| Primary slice | **COMMAND**, ancre `runtime-command-consumption-worker`. |
| Secondary slices | **NONE** : pas de comportement productif EVENT/PROJECTION/BINDING/LKV/WEB modifié. Les communes primitives READ sont exercées directement ci-dessous ; leur runtime intégré est I5. |
| Direct complementary proofs | `infra-persistence-read-jdbc` pour droits/schéma READ ; `runtime-monolith` pour isolement/composition. Ces ancres supplémentaires sont nommées explicitement, pas qualifiées de nouvelles slices canoniques. |
| Forbidden production impact | Discovery/ordonnanceur, projections/API, Identity réelle, historiques V2, migration générale, nouveaux modules, second manager/owner. |
| Required local proof | Matrice §9, tests des nouvelles opérations sur deux autorités, non-régression I2 et C/F/F2 de la fermeture Maven COMMAND. |
| Global architecture gate | **REQUIRED** à clôture I3, car composition lifecycle et contrôle d’isolation/droits sont étendus et gardes des nouvelles entrées doivent être prouvées ; pas automatiquement dans chaque boucle de travail. |
| Full reactor | **NOT REQUIRED** pour cette étape bornée, sans modification du graphe Maven ni clôture majeure L2/intégration main. |
| Database verification | **CURRENT V3**, environnements neufs WRITE/READ et migrations isolées existantes, bootstrap administratif puis grants ciblés. Ni baseline V2 TRUSTED présumée ni campagne historique séparée. |
| Next mandatory global gate | Architecture à clôture I3 ; architecture + full reactor au G3/clôture majeure L2, et gates d’intégration main de la politique. G1/G2/G3 non franchis par I3 seul. |
| Escalation conditions | Dépendance/module/owner/suffixe à changer, nouveau runtime affecté, writer caché, doute réel d’isolement : diagnostic, scope révisé avant code ; full seulement si gate normatif/impact devenu réellement non bornable. |

Commandes **futures**, depuis `app/`, à exécuter sur le même SHA final de code figé, sans exclusions de tests ni filtres de clôture :

```sh
./mvnw -pl runtime-command-consumption-worker -am test
./mvnw -pl infra-persistence-read-jdbc -am test
./mvnw -pl runtime-monolith -am test
./mvnw -pl architecture-tests -am test
```

La commande COMMAND est l’ancre canonique. Les deux preuves directes sont nécessaires pour des tests non garantis dans sa fermeture ; lire le graphe avant campagne. FOUNDATION seule ne certifie pas ces composants productifs. La commande `./mvnw -pl infra-tx-spring -am test` reste la preuve directe de l’owner **si un défaut démontré impose de le modifier** ; ce n’est pas une obligation I3 inchangé. Les tests owner peuvent également être rejoués dans la fermeture du gate architecture, avec présence fraîche de leurs méthodes vérifiée. Aucun `-amd`, pas d’ajout du full par incertitude.

Contrôles documentaires/collecte futurs depuis la racine :

```sh
python3 -B docs/v3/check_l2_manifest.py
python3 -B scripts/v3/check-routing-vectors.py
python3 -B -m unittest scripts/testing/test_i3_evidence.py
git diff --check
```

Le collecteur I3 exécutera les quatre commandes exactes ci-dessus et vérifiera les XML frais des méthodes obligatoires + sous-variantes/archives physiques. Les méthodes de `L2_I2_Proof_Manifest.json` applicables sont conservées dans la non-régression, avec distinction fixtures I2/moteur I3. Noms `*Test` reconnus par Surefire ; pas de répétition du défaut I2 où une classe de preuve n’était pas sélectionnée. Le skip exploratoire historique conservé ne vaut pas PASS et n’excuse aucun skip I3. Le gate architecture peut rejouer des suites historiques V2 par dépendance ; le rapport l’indiquera sans présenter ce coût comme un bootstrap I3.

**Critères de clôture I3 :** matrice locale complète, aucun gain sous ancien owner/token, zéro résurrection/reprise anticipée, une exécution par acquisition, S2 renouvelé sous verrou S1, deux plafonds jamais dépassés et places restituées selon §7, aucune cession sans deux préconditions, count au plus une fois, retry/Q/admin corrects, permissions effectives minimales et I1 inerte, corps I2 inchangé/régressions vérifiées, aucun état/table/scheduler supplémentaire ; quatre commandes et méthodes obligatoires PASS frais, provenance SHA/paramètres/versions/XML/SQL/archives cohérente, revue indépendante et corrections vérifiées. Toute violation = FAIL ; absence de preuve/ordre concurrent non démontré = INCONCLUSIVE, jamais PASS par Maven seul. Un PASS local I3 ne constitue ni PASS global CON-03/05, ni ACCEPTED L2.

## 13. Risques résiduels et décisions à soumettre à l’audit

| Risque / limite | Réponse simple / preuve ou lot receveur |
|---|---|
| DDL ou contrat bloquant les renewals malgré SKIP LOCKED | Maintenance exceptionnelle hors exploitation et timeout borné ; S1/S2 prouve les lignes, pas une disponibilité sous panne/DDL permanent. |
| Lots trop grands, index/WAL et coût du trigger hash à chaque renewal | Mesurer §8, réduire B avant toute optimisation/structure supplémentaire. Ne pas annoncer de SLO de production. |
| Ancienne voie publique de handoff contournant capacité/une tentative | Handle one-shot ; helpers exclusivement test ; inventaire des appels et garde architecture. Aucun fallback productif sans réservation. |
| Réservation retenue pendant UNKNOWN | Choix conservateur documenté, inspection hors places métier ; panne durable peut diminuer la capacité utile. Pas de garantie absolue de restitution sans état observable. |
| Pool annoncé réservé mais emprunteurs non bornés | Contrat précis M_W+1+3, test manuel I3 ; branchement exhaustif de tous les emprunteurs et exécuteurs indépendant I5 obligatoire. |
| Reclassification trop large d’un rollback | Classificateur explicite et tests négatifs §6 ; cause originale et issue physique séparées. Aucun besoin de nouvelle machine d’états. |
| Droits de colonne invisibles à une garde seulement table | Vérifier droits de colonne et memberships effectifs ; vrais rôles non admin. Le droit DELETE requis ne remplace pas la preuve du protocole R1/non-purge. |
| Baseline documentaire contenant des en-têtes historiques « non implémenté / I3 non autorisé » | Clôture I2 datée et mandat courant gouvernent la préparation ; l’implémentation I3 reste non autorisée. Pas de réécriture de toutes les SPEC. |
| SoftCap, Discovery, revisite et équité globale non prouvés | I4 : admission/checkpoints/burnable ; I5 : tours, deux boucles, saturation intégrée, M_W=1 et occasions répétées. Contributions primitives explicites, branches globales INCONCLUSIVE. |
| READ technique pris pour projection métier, ou répétition prise pour preuve nouvelle | Tous effets READ étiquetés banc ; reports L3–L5/L6 du manifeste maintenus ; décompte des garanties distinct de celui des exécutions. |

**Choix techniques à auditer, sans nouvel arbitrage normatif demandé :** accès routage SELECT + verrou administratif ACCESS EXCLUSIVE ; même owner I2 pour les courtes transactions ; renewal SKIP LOCKED puis horloge séparée ; cession seulement sous rollback certain et classe positive ; grants post-bootstrap sans migrations ; handle one-shot et capacité partagée ; réactivation SQL ciblée ; inspection technique pour restitution prudente en UNKNOWN. Ce sont des réalisations directes des décisions acquises. Si l’audit démontre une contradiction réelle, la consigner avec clause/contre-exemple et proposer la correction la plus courte avant le GO ; ne pas ouvrir une question conceptuelle parce qu’un détail de code reste à écrire.

## 14. Compte rendu de la préparation documentaire présente

- Start HEAD : `03f0725c690bcbd9748d3ea59b62136e1a911e05` ; worktree de livraison isolé `/private/tmp/pocoma-v3-i3-plan`. Checkout utilisateur `feat/runtime-monolith` à `90a3e9bc`, sale avant intervention, et worktrees préexistants préservés.
- Scope déclaré et observé : **NONE applicatif / documentaire seulement**, secondaires NONE ; DB NONE. Aucun franchissement de slice inattendu. Architecture globale non requise/non exécutée pour ce rapport ; full reactor non requis/non exécuté, car aucun code et aucune intégration majeure.
- Commandes Maven effectivement exécutées : **AUCUNE**. Aucun test, migration, bootstrap, SQL lifecycle ou lancement I3. Les commandes §12 sont exclusivement futures.
- Contrôles documentaires exécutés : `python3 -B docs/v3/check_l2_manifest.py` → OK, 253 identifiants normatifs, 380 lignes, 324 obligations L2, 56 reports, 33 oracles, aucun orphelin ; contrôle Python ponctuel des liens/ancrages locaux et des chemins Java proposés → OK (21 liens, 11 chemins à modifier existants, 15 chemins à créer absents) ; `git diff --check` → OK ; revue du diff limitée au présent plan et au lien du plan directeur. Il s’agit de contrôles statiques, pas de tests de mécanismes I3.
- Publication autorisée : rapport et lien dans le plan directeur seulement, commit/push vers `origin/feat/v3-architecture` sans force après recontrôle de la tête attendue. SHA final et concordance distante fournis dans le message de livraison, aucun succès présumé.

**Arrêt après publication : audit indépendant du plan puis attente du GO humain avant toute implémentation I3.**
