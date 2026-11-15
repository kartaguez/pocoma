# Consumption Generic Transactional Protocol — clôture bornée WP0–WP6

Date : 2026-10-16. Branche conservée : `feat/runtime-monolith`. Départ de la passe WP3–WP6 : `004a601f20870534e5747389e39cfb6966624374`. Binaire final : `5bb8fc5d` (WP5) ; WP6 ne change que la documentation et ses archives. **PASS WP6 — CONSUMPTION GENERIC TRANSACTIONAL PROTOCOL — COMPLETE**, au périmètre fonctionnel borné déclaré. [Plan canonique](2026-10-16-consumption-generic-transactional-protocol.md).

## Architecture effectivement livrée

CURRENT initial : Execute écrivait déjà effets/provenance avant CAS ; Finalize partagé et recovery Binding prenaient S avant les publications. La composition de ces deux ordres permettait D17 : ancien worker détenteur outcome Q, nouveau worker S→Q, FK provenance ancienne Q→S. Le témoin CURRENT avec 40P01 est conservé par [WP0](2026-10-16-consumption-generic-transactional-protocol-wp0-baseline.md), jamais réécrit comme une preuve TARGET.

TARGET livré : le moteur possède un seul suffixe participant ; chaque famille garde ses effets, autorités et policies.

```text
Acquire/Takeover : création idempotente S → S FOR UPDATE par clé → C → installation → COMMIT
Préparation pure éventuelle
Owner sans transaction/synchronisation ambiante
BEGIN effets
  préfixe métier et fencings locaux
  publications SQL provisoires
  provenance éventuelle validée
  FLUSH complet pré-CAS
  CAS S WHERE PENDING/current_claim_id=C
    zéro : exception, ROLLBACK intégral
  clôture conditionnelle C, exactement un
    refus : anomalie, ROLLBACK intégral y compris CAS
COMMIT confirmé
résultat public APPLIED seulement ensuite
```

Execute, SUCCESS, REJECTED, FAILED et recovery convergent vers Finalize puis `terminalize`. Retry conserve sa transition PENDING/date prochaine et clôture failure, sans publication terminale. L'owner traduit la perte seulement après rollback physique ; le participant REQUIRED laisse traverser. Acquire, Execute et failure/recovery ont des résolutions séparées. COMMITTED/UNKNOWN ne déclenchent aucune republication automatique.

Ordre partiel conservé : Command CAS Pot → metadata/DML temporel → Events `saveAllAndFlush` → fence Binding exact → outcome strict → terminal event → provenance/Σ. Registration stream Binding → initializer User/fact → occurrence/actif/fact/outcome → Σ. READ Binding/LKV UPSERT monotones avant Σ ; Results INSERT/compare immutable avant Σ ; root Projection avant artifacts. Aucune autorité métier après CAS S ; seule la clôture C suit. Les FK peuvent prendre KEY SHARE S avant CAS ; Acquire FOR UPDATE peut attendre ces détenteurs. Le repository CAS garde `flushAutomatically`/`clearAutomatically`, sans changement SQL ou migration.

Anciennes primitives supprimées au WP5 : `lockCurrentClaim` (port/adapter), `findByIdForUpdate` Slot, `tryTerminalize`, ancien `handleFailure` de persistance, et leurs bridges dans les doubles. Acquire `findByKeyForUpdate`, stream Binding, CAS Pot et end/fail/invalidate restent nécessaires. Les constructeurs sans provenance et holders participants ont des callers légitimes documentés dans [WP5](2026-10-16-consumption-generic-transactional-protocol-wp5-consolidation.md).

## Verdicts et commits par WP

| WP | Verdict | Preuve / commit |
|---|---|---|
| WP0 | PASS CURRENT | `2a9d8f3f`, `2bfa0f8e` ; [baseline](2026-10-16-consumption-generic-transactional-protocol-wp0-baseline.md) |
| WP1 | PASS contractuel | `c1d60a90` ; [D01–D10](2026-10-16-consumption-generic-transactional-protocol-wp1-contracts.md), aucune décision réouverte |
| WP2 | PASS borné | `72687053`, `004a601f` ; [bascule et D17](2026-10-16-consumption-generic-transactional-protocol-wp2-implementation.md) |
| WP3 | PASS | `7b2c3a8b` ; [matrice fonctionnelle](2026-10-16-consumption-generic-transactional-protocol-wp3-functional.md), 951/951 |
| WP4 | PASS | `19ef83d8` ; [T01–T11 et qualifications](2026-10-16-consumption-generic-transactional-protocol-wp4-concurrency.md), 146 méthodes, 293 exécutions dont trois échecs de fixture corrigés |
| WP5 | PASS | `5bb8fc5d` ; [consolidation](2026-10-16-consumption-generic-transactional-protocol-wp5-consolidation.md), ALL8 967/967 |
| WP6 | PASS | Commit contenant ce rapport et [preuve finale ALL8](2026-10-16-consumption-generic-transactional-protocol-wp6-verification.txt) ; SHA exact dans la restitution Git finale |

## Résultat par famille au binaire final

| Famille | Suites représentatives et garantie finale | Verdict |
|---|---|---|
| Command | PostgreSQL 36 méthodes + runtime 8 ; SUCCESS/REJECTED/FAILED/recovery, Pot/Binding, outcomes/Events uniques, D17 et provenance | PASS |
| Registration | Runtime 5, authority 6 ; User/UserCreatedFact/Binding/outcome atomiques, rejet/retry/restart/stale | PASS |
| Binding | Runtime 6, store READ ; ATTACHED/DETACHED, monotonie, même révision, stale/rollback et reconstruction | PASS |
| Event | Materialization PostgreSQL 10, runtime 4 ; Task unique, ensure provisoire stale et rollback, détenteur commit/rollback | PASS |
| Projection Task | Canonique 14, store 18 ; root/artifacts/failure atomiques, first wins, Temporary sans terminal, stale annulé | PASS |
| Command Result | Runtime 5, store 2 ; trois sources terminales, immutable/compare/owner, SQL stale et input Event rollbackés | PASS |
| Registration Result | Runtime 4, stores/materializer ; Registered/Rejected historiques, visibilité, immutabilité, provenance/fencing | PASS |
| Latest Known Version | Runtime 8, métriques 3 ; UPSERT monotone, provenance Event, rollback, observation après commit isolée | PASS |

Ces nombres sont des sous-ensembles qui se recouvrent avec les preuves communes et la fermeture Maven ; ils ne doivent pas être additionnés pour calculer le total distinct. Les huit familles utilisent le même port terminal, sans exemption de callback stale ni FOR UPDATE terminal préalable.

## Contrôles finaux et niveau de preuve

| Contrôle WP6 | Résultat / preuve |
|---|---|
| Compilation et fonctions des huit familles | ALL8 final, 203 classes ; manifeste complet dans l'archive |
| T01–T11 | [Matrice WP4 intégrale](2026-10-16-consumption-generic-transactional-protocol-wp4-concurrency.md), scénarios nommés et qualification des simulations conservés ; suites finales incluent les nouvelles méthodes |
| D17 | Huit entrelacements TARGET : aucun 40P01, ancien C1 LostClaim/rollback, C2 unique FAILED/recovery REJECTED ; outcome/terminal event/provenance/claims vérifiés |
| CAS perdu, clôture refusée et commit refusé | Transactionnels 26 ; JDBC déjà exécuté et JPA pré-CAS ; vrais refus end/fail et contrainte différée 23503 après CAS ; zéro APPLIED anticipé |
| Owners/participants | Huit gardes ambiantes runtime, même XID REQUIRED et rollback physique ; afterCommit COMMITTED et UNKNOWN distincts |
| Fences et flush | Vrais CAS Pot/Binding, detach/rebind, même Pot/E distincts et Pots distincts/même E ; traces SQL WP4 ; barrière repository finale inchangée |
| FK/unicité/provenance | Contraintes actives ; deux détenteurs FK/third takeover, input unique partagé 23505, Task et root détenteur commit/rollback ; aucune migration ni FK retirée |
| Surface obsolète | Recherche zéro référence Java `lockCurrentClaim`, `findByIdForUpdate`, `tryTerminalize`, appel `.handleFailure(` ; inventaire WP5 et compilation des callers |
| Frontières | Diff WP3–WP6 borné aux tests et retrait du port lifecycle/adapter/repository ; aucun POM, module, SQL de production ou comportement WEB modifié |
| Documentation | Canons Consumption/Projection, garanties, plan/suivi/DoD, rapports et registre de dettes actualisés ; liens relatifs et diff vérifiés |

**PROVEN** : assertions fonctionnelles et durables dans les scénarios exécutés, attentes SQL/PID/XID observées, rollbacks, clôture/commit refusés, D17 caractérisé. Les preuves ne sont pas remplacées par le seul résultat Java ou la compilation.

**GUARANTEED BY CODE/SCHEMA** : CAS S/PENDING/C, end/fail conditionnel, contraintes d'unicité et FK composite/provenance, participation à une ressource transactionnelle commune, ordre de flush déclaré, immutabilité/monotonie propres aux stores inspectés. Cela suppose les writers et callbacks conformes ; ce n'est pas une preuve des extensions futures.

**INCONCLUSIVE** : absence universelle de deadlock, causalité de l'incident historique DEBT-TEST-01, fairness et progression bornée. Aucun délai maximal, débit ou gain de performance n'est inventé.

**NOT TESTED** : panne réseau réelle au commit/rollback et réconciliation distribuée, DDL/admin concurrent, charge prolongée, nouveaux listeners/contraintes, callbacks futurs multiclés et rollout mixte. UNKNOWN est injecté au runner pour vérifier le refus de classification automatique ; seule la contrainte différée est un refus réel de commit PostgreSQL dans cette passe. Un futur déploiement doit drainer/aligner tous les workers concernés ; aucun déploiement réalisé.

## Maven, campagnes et comptage

Scope WP6 déclaré : COMMAND primaire, EVENT/PROJECTION/BINDING/LKV secondaires + Registration/CommandResult/RegistrationResult, composition explicite ALL8 du plan §7.2 depuis `app/`. Aucun impact production WP6. Forbidden : production/SQL/graphe/modules/WEB. Gate global **NOT REQUIRED**, full reactor **NOT REQUIRED** : clôture fonctionnelle bornée dans les frontières existantes, pas Major Wave, intégration main ou nouvelle baseline globale. Prochain gate d'intégration main : reactor complet + architecture globale + DB applicable, non réalisé ici. Aucun crossing inattendu, `-amd`, install/SNAPSHOT ou migration autonome ; Trusted baseline NONE, bootstrap existant conservé.

Commandes exactes et méthodes : [WP3](2026-10-16-consumption-generic-transactional-protocol-wp3-verification.txt), [WP4](2026-10-16-consumption-generic-transactional-protocol-wp4-verification.txt), [WP5](2026-10-16-consumption-generic-transactional-protocol-wp5-verification.txt), [WP6](2026-10-16-consumption-generic-transactional-protocol-wp6-verification.txt). Chaque commande avec sélection exige les classes effectivement rapportées ; aucun skip compté PASS. PostgreSQL 17.11 réel, Docker 29.4, Java Temurin 21. Les migrations historiques ne sont pas lancées comme campagne additionnelle ; les tests de migration déjà dans la fermeture canonique restent exécutés.

| Campagne de cette passe | Tests distincts dans la campagne | Exécutions | Échecs/erreurs/skips |
|---|---:|---:|---|
| WP3 ALL8 | 951 | 951 | 0/0/0 |
| WP4 sept commandes ciblées (versions successives comprises) | 146 | 293 | 3/0/0, corrigés |
| WP5 ALL8 après retrait | 967 | 967 | 0/0/0 |
| WP6 ALL8 final | 967 | 967 | 0/0/0 |

Union des méthodes de la passe : **967 tests distincts**, 18 nouveaux par rapport aux 949 WP2. **3 178 exécutions cumulées**, 3 175 réussies et trois échecs intermédiaires corrigés, zéro erreur/skip. Les campagnes finales de chaque WP sont vertes. Les résultats historiques WP0/WP2 restent dans leurs archives : les 3 859 exécutions WP2 (dont 2 failures et 9 errors intermédiaires corrigés) ne sont ni de nouveaux tests distincts ni des exécutions de cette passe. La somme des campagnes n'est jamais présentée comme un nombre de tests distincts.

Échecs intermédiaires WP4 : deux sondes imposaient arbitrairement le premier détenteur FK comme bloqueur ; désormais le bloqueur réellement choisi parmi les deux est observé/consigné. Un ancien test Command voyait les Events d'une nouvelle fixture T08 : nettoyage d'outbox ajouté entre méthodes. Aucune assertion de sûreté retirée, aucune policy/production modifiée pour ces corrections, aucun retry automatique. Avertissement Surefire shutdown à 30 s déjà documenté WP0/WP2 conservé lorsqu'il apparaît ; BUILD SUCCESS et résultats de tests sont distingués de cet avertissement.

## Dettes et Git

[D17](../debts/BINDING_LOCK_ORDER_GUARD/Resolution_D17_2026-10-16.md) : correction prouvée dans les entrelacements caractérisés, proposition de clôture à ce périmètre ; aucune clôture automatique de dette existante. `BINDING_LOCK_ORDER_GUARD` reste OPEN/LOW, critère structurel non satisfait ; SQL append-only guard reste OPEN/LOW ; takeover test quality reste RESOLVED avec incident historique INCONCLUSIVE. Constats historiques conservés.

Remote réel : `https://github.com/kartaguez/pocoma.git`. Upstream `origin/feat/runtime-monolith`. Avant intervention, HEAD/local/upstream/serveur `004a601f`, divergence 0/0. À l'entrée WP6 : 3 commits locaux, 0 distant ; commit documentaire WP6 attendu comme quatrième commit local. Aucun push, merge, changement de branche, stash, reset ou staging global. HEAD final exact et divergence serveur après commit sont donnés dans la restitution pour éviter une autoréférence du commit contenant ce rapport.

Préexistants : Keycloak + Bruno, 13 chemins suivis/non suivis/supprimé inventoriés ; empreintes des 12 fichiers présents et absence du fichier supprimé vérifiées identiques avant livraison. Les manifests de staging de chaque WP excluent ces fichiers. État final attendu propre hors cet inventaire, à vérifier après commit WP6. Aucun défaut bloquant connu ni travail de correction reporté à une phase ultérieure ; limites de preuve ci-dessus maintenues.
