# WP5 — Retrait des surfaces obsolètes

Date : 2026-10-16. Baseline `19ef83d8`, WP3/WP4 PASS. [Plan](2026-10-16-consumption-generic-transactional-protocol.md), [WP4](2026-10-16-consumption-generic-transactional-protocol-wp4-concurrency.md).

## Scope déclaré avant implémentation

Impact attendu : réduction de l'interface lifecycle et de son adapter/repository, migration des doubles et tests directs. Aucun changement comportemental. COMMAND primaire ; EVENT/PROJECTION/BINDING/LKV et compléments Registration/Results : ALL8. Forbidden production impact : métier/policies, SQL de CAS/claim/acquire, schéma/migrations, graphe Maven, WEB. Gate architecture global NOT REQUIRED : aucune frontière/dépendance de module modifiée ; suppression de méthodes internes sans changement d'isolation. Full reactor NOT REQUIRED, étape bornée hors main. DB CURRENT réelle via suites, bootstrap existant. Aucune installation SNAPSHOT ou campagne historique autonome.

## Audit et diff

| Élément | Callers avant retrait | Décision et preuve |
|---|---|---|
| `lockCurrentClaim` port/adapter | Aucun caller de production depuis WP2 ; seulement implémentations de doubles | Supprimé, avec l'implémentation de prélock S devenue inatteignable. |
| `findByIdForUpdate` repository Slot | Seulement `lockCurrentClaim` | Supprimé ; aucune autre autorité ne l'utilise. |
| `tryTerminalize` port/adapter | Doubles de tests et tests directs lifecycle/discovery ; aucun caller runtime | Supprimé. Tests appellent `terminalize(Success/Rejected)` et conservent toutes les assertions durables et de perte. |
| Ancien `handleFailure` port/adapter | Doubles et tests directs uniquement ; délégation transitoire WP2 | Supprimé. Tests choisissent `terminalize(TerminalFailure)` ou `scheduleRetry` avec date exacte. Aucun bridge ajouté. |
| Doubles `RecordingLifecycle`, `StubPersistence`, `BlockingTerminalizationPort` | Tests moteur/transactionnels | Implémentent directement le port réduit ; plus de suffixe de compatibilité dans les doubles. Barrière avant delegate conservée. |
| Traductions LOST_CLAIM | Wrappers owner Finalize/Failure ; absorption externe Sequential | Conservées : toutes après sortie du runner, jamais à l'intérieur du participant/recovery. Résolution incertaine distincte. |
| `findByKeyForUpdate`, claim end/fail/invalidate ; locks stream et CAS Pot | Acquire/Takeover ou autorités métier actives | Conservés : primitives distinctes nécessaires, SQL inchangé. |
| Constructeur Finalize sans provenance | Event/Projection et failure sans provenance, tests correspondants | Conservé et documenté : usage réel, reject si provenance non vide ; même finalizer, aucune mécanique alternative. |
| Constructeurs Execute/Failure ; wrappers owner ; `ConsumptionTerminalParticipant` | Composition runtime et tests réels | Conservés : adaptation/injection et séparation owner/participant nécessaires ; aucun protocole final parallèle. |
| Documents historiques CURRENT | Audits et rapports datés, WP0/WP1/WP2 | Conservés comme historique ; canon actif mis à jour avec l'API réellement disponible. Aucun constat historique effacé. |

Diff exact sous `app/` : port lifecycle, `FinalizeConsumptionService` (commentaire/indentation seulement), adapter lifecycle JPA, repository Slot ; trois tests moteur et trois tests infra-primary. Dix fichiers Java, quatre de production. Recherche `rg -n 'lockCurrentClaim|findByIdForUpdate|tryTerminalize|\.handleFailure\(' app --glob '*.java'` : zéro résultat après migration, production et tests inclus. Les méthodes use case `handle` et la policy Failure demeurent.

Le suffixe actif reste effet → provenance → flush automatique pré-CAS → CAS S/PENDING/C → clôture C exactement un. Aucun callback métier ajouté après CAS. Les huit familles utilisent les mêmes primitives ; les interfaces métier propres restent inchangées. Les assertions booléennes des API retirées sont remplacées par absence d'exception et assertions existantes sur slot/claim/données ; aucune assertion de rollback supprimée.

## Validation et résultat

[Archive ALL8 WP5](2026-10-16-consumption-generic-transactional-protocol-wp5-verification.txt). **PASS WP5** : ALL8 BUILD SUCCESS, 967 tests distincts et 967 exécutions, 203 classes, zéro failure/error/skip. Aucun échec intermédiaire Maven. Avertissement de shutdown Surefire historique conservé. Les garanties WP3/WP4 passent après migration des tests vers le port réduit. Compilation des consommateurs et tests WP3/WP4 inclus. Franchissement inattendu NONE ; aucun gate global/reactor complet exécuté. Pas de POM/SQL/migration changé, pas de nouveau protocole.

Les preuves restent bornées, sans garantie universelle de deadlock ou progression temporelle. Git : staging explicite des dix Java et documents de ce WP, Keycloak/Bruno préservés et exclus. WP6 attend PASS et commit WP5.
