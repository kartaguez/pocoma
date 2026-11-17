# Plan de terminaison L7 (`L7_RESIDUAL_AUDIT_READY`)

- **Date** : 17 novembre 2026
- **Référence Git** : `main` @ `59f68956e984a932bc28da6d9588680ce49d8210`
- **Objectif** : définir le chemin minimal, ordonné par dépendances et effort croissant, pour solder les obligations résiduelles de **L7** sans mélanger les optimisations facultatives ni les exigences de **L8 / production**.

---

## Vue d'ensemble des 5 catégories et ordre d'exécution

```text
[1. Réemploi immédiat] ──▶ [2. Essais complémentaires ciblés] ──▶ [3. Corrections techniques (si révélées) + Gates L7.5]
                                      │
                                      └──▶ [4. Optimisations facultatives (uniquement si écart mesuré)]

[5. Périmètre L8 / Production] ──▶ Hors L7 (ne pas imposer artificiellement à L7)
```

---

## 1. Obligations clôturables immédiatement par réemploi de preuves (Effort : Faible)

| Étape | Cible | Action exacte | Preuve réemployée | Effort relatif |
|---|---|---|---|---|
| **1.1** | `L2M-E02-J-REPORT` | Ratification humaine formelle de la preuve existante (ou 1 rejeu ciblé sur `59f68956` si parité SHA post-S1 exigée). | `L7-I4` [`e1-e3-06/receipt.json`](../testing/evidence/L7-I4-technical/e1-e3-06/receipt.json) (@ `230feb05`) + `L7-LB` `S5-B`/`S6`. | **Faible** (1×) |
| **1.2** | `L7.2` (`46` `CURRENT_PARTIAL`) & `L7.3` (`338` `CURRENT_PARTIAL` + `30` `HISTORICAL_PARTIAL`) | Rapprochement documentaire d'allocation sur `main` @ `59f68956` (incluant `05ae58f9` sur le holder JDBC et les preuves `L7-LB`/`CI`), sans promotion collective automatique : identifier uniquement les lignes déjà satisfaites par les suites existantes et celles nécessitant un complément au §2. | [`L7_I1_Certification_Coverage_Matrix.csv`](L7_I1_Certification_Coverage_Matrix.csv), logs CI PR #6, `L7-I2`/`L7-I3`. | **Moyen** (2×) |

---

## 2. Essais complémentaires nécessaires (Ordonnés par effort croissant)

Tous les essais ci-dessous sont réalisables localement sur PostgreSQL (`LOCALLY_CERTIFIABLE`), sans infrastructure cloud ni Configuration A/B :

| Ordre | Obligations couvertes | Nature de l'essai à produire | Dépendance | Effort relatif |
|---|---|---|---|---|
| **2.1** | `L2M-E01-E` | Test PostgreSQL vérifiant les compteurs et positions de toutes les familles sur `Command` `UNCHANGED`/`REJECTED` et `Identity` rejetée (seules les complétions avancent). | Aucune | **Faible** (1×) |
| **2.2** | `L2M-CON-05-NOPURGE` | Test PostgreSQL sous rôles runtime réels vérifiant le refus physique d'un `DELETE` isolé hors succès et la conservation du slot. | Aucune | **Faible** (1×) |
| **2.3** | `L2M-C01-A-REPORT`, `L2M-C01-D-REPORT` | Tests PostgreSQL `Identity` + `READ` : (a) peuplement multipartition entremêlé avec ensemble exact des slots et `K` par partition ; (b) couplage barrière SQL entre publication concurrente et vraie `Discovery READ` avant/après commit et rollback. | Aucune | **Moyen** (2×) |
| **2.4** | `L2M-E01-G-INV-COMP`, `L2M-E01-G-ID-IDCOMP` | Tests PostgreSQL avec barrières SQL et observation `pg_blocking_pids` forçant l'ordre de verrouillage `INVITATION → COMMAND_COMPLETION` et `IDENTITY → IDENTITY_COMPLETION` (commit/rollback, exclusion arêtes inverses). | Aucune | **Moyen** (2×) |
| **2.5** | `L2M-CON-05-CRASH`, `L2M-C03-J-CRASH` | Harnais multi-processus (`Command` et `READ`) avec point de suspension test-only : (a) `SIGKILL` après acquisition de claim sans cession (`attemptCount` inchangé, reprise post-TTL) ; (b) `SIGKILL` pendant calcul/finalisation (rollback physique, slot récupérable, zéro effet partiel). | Aucune | **Élevé** (3×) |
| **2.6** | `L2M-C04-L-REPORT`, `L2M-C06-M-REPORT`, `L2M-R8-03`, `L2M-R8-05` | Audit sémantique complet de `writer-inventory-final` (`2 704` candidats / `239` fichiers, SQL dynamique, appels transitifs, Monolith, pouvoirs administratifs admis) + tests de refus associés. | **2.2** | **Élevé** (4×) |
| **2.7** | `L2M-C04-K`, `L2M-E01-H-REPORT` | (a) Corpus composite répété `Command` + `READ` (takeover, rollback, crash en vol) ; (b) charge de sûreté mixte de tous les producteurs (`Pot` + `Identity` + `Invitation`) avec transactions lentes, aborts et reconnexions. | **2.3**, **2.5** | **Élevé** (4×) |
| **2.8** | Caractérisation locale `SPEC-09` (`F01-I-HISTORY`, `F01-J`, profils `Moyen`/`Grand` locaux) | Rejeu local ciblé de la profondeur JDBC (`100/1000/10000`), mesure à vide `min-poll=2 ms` (`F01-J`) et temps de calcul `PotBalance` sur `Moyen` (`20/200`) / `Grand` (`100/2000`) — **caractérisation diagnostique seulement, sans prétendre certifier Config A**. | Aucune | **Moyen** (2×) |
| **2.9** | `L7.5` (`18` lignes `BLOCKED`) | Exécution des gates globales non filtrées sur le SHA candidat gelé : réacteur complet (`full` sans exclusion `@Tag("slow")`) + `architecture-tests` ([`Reactor_Verification_Policy.md`](../testing/Reactor_Verification_Policy.md)), génération du manifeste et audit final. | **1.1–1.2**, **2.1–2.7** | **Moyen** (2×) |

---

## 3. Véritables corrections techniques

1. **Conditionnelles à l'audit `L7.3` (bloquantes L7 si découvertes)** :
   - Toute voie SQL dynamique, appel transitif, opération administrative non gardée ou défaut transactionnel identifié lors des étapes **2.5–2.7** devra faire l'objet d'une correction Java/SQL productive suivie d'une requalification des slices impactées (`Reactor_Verification_Policy.md`).
   - *État actuel* : aucun défaut produit actif n'est démontré à ce jour sur `main` @ `59f68956`.
2. **Dette outillage non bloquante `DEBT-L7LB-01` (Effort : Faible)** :
   - Ajouter le contrôle de l'espace disque hôte (`shutil.disk_usage(ROOT).free`) dans `pocoma-local doctor` ([`scripts/local/runtime.py`](../../scripts/local/runtime.py#L1314-L1340)) avec diagnostic préventif et tests unitaires Python. N'impacte pas `app/` ni la sûreté métier.

---

## 4. Optimisations facultatives (Non contractuelles tant qu'aucun écart mesuré ne l'exige)

- **Projections incrémentales de `PotBalance`** : **facultative**. À n'instruire par mandat distinct que si les mesures sur Pots moyens (`20/200`) et Grands Pots (`100/2000`) en Configuration A (ou diagnostic local **2.8**) démontrent que le recalcul intégral `O(E × P)` dépasse les seuils `500 ms` (`F01-A-BAL`) ou `2 000 ms` (`F01-D`).
- **Notification réactive push (`LISTEN/NOTIFY` ou événements in-process)** : **facultative** (issue de `L6/I3-R7`). Déjà partiellement contournée en distribution locale par `min-poll=2 ms` (`S5-C`), mais à évaluer si la charge à vide `F01-J` ou la latence `F01-A` sur Config A l'exige.
- **Découplage / parallélisation `business-read-worker` à `50 cmd/s`** : **facultative** pour L7 (car `25 cmd/s` est l'enveloppe locale documentée de `L7-LB`, et `F01-C` relève de Configuration A en L8).

---

## 5. Exigences relevant de L8 ou de la production (À ne pas imposer artificiellement à L7)

Les exigences suivantes sont **explicitement exclues des critères de clôture L7** et maintenues en **L8 / Production** :

1. **Infrastructure `SPEC-09` Configuration A/B (`ENV-A`, `ENV-B`)** : deux machines dédiées `4 vCPU / 8 Go` + Keycloak isolé, et exécution des `13` familles `SPEC-09` (`F01-A`..`J`, `DELETE`, `ENDURANCE`, `SATURATION`) avec `≥ 3` répétitions et `≥ 300` observations par population SLO.
2. **Volumes globaux (`F01-I-GLOBAL`)** : jeux de données `10 000 Pots / 1 million d'événements` et `100 000 Pots / 10 millions d'événements`.
3. **Haute disponibilité et failover actif multi-nœuds (`RES-L7LB-S6-02`, `RES-L6-I2-01`)** : plusieurs instances par rôle worker, séparation physique des bases `pocoma_write` / `pocoma_read`, tolérance aux pannes réseau inter-nœuds.
4. **Durcissement sécurité production (`L6/I1 §5.4`, `LB-J1-CLIENT`)** : TLS/mTLS inter-services et sur `/actuator/*`, coffre-fort et rotation automatique des secrets, politique CORS/BFF pour SPA navigateur.
