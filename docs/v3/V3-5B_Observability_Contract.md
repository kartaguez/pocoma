# Pocoma V3-5B — Contrat minimal d'observabilité

**Statut : PROPOSITION DOCUMENTÉE — arbitrage et revue ouverts ; aucune implémentation ni mesure V3.**
**Date :** 2026-10-23. **Canon :** [V3_Canon_and_Roadmap.md](V3_Canon_and_Roadmap.md). **Exigences :** [V3-5A](V3-5A_Global_Review_and_Requirements.md).

## 1. Inventaire de l'existant — réemploi, sans preuve de disponibilité V3

Éléments relevés dans le dépôt (issus de l'architecture V2 et des documents historiques, **non contractuels tels quels pour V3**) :

| Élément | Source historique consultée | Réemploi possible / limite |
|---|---|---|
| Spring Boot Actuator, Micrometer et endpoint Prometheus, provisionnement Prometheus/Grafana | README ; `docs/architecture/command-consumption-runtime.md` ; `docker/prometheus`, `docker/grafana` | Réutiliser infrastructure et conventions de cardinalité ; adapter la nomenclature aux Consumptions V3 |
| Polling : cycles, candidats, exécutions, durées, worker running | `MicrometerConsumptionPollingWorkerObservation`, `docs/architecture/command-consumption-runtime.md` | Mesures agrégées de fonctionnement ; insuffisantes à elles seules pour attentes source→slot et slot→claim |
| Corrélation `X-Trace-Id`, `TraceContext`, metadata Event | `TraceCorrelationFilter`, `contracts-observability`, `EventTraceMetadata` | Réemploi conceptuel, sans imposer des champs de trace supplémentaires au domaine |
| Audit durable Command→READ | `docs/steps/completed/POT_E2E/E2E_Latency_Observability_Audit.md` | Enseignements sur timestamps, biais pré-commit, clock skew et approximations ; anciens slots et états non normatifs |
| k6 `smoke`, `stress`, `projection_backpressure` | `scripts/k6/README.md` | Référence pour profils de charge ; scénarios déclarés historiques/supplantés et non preuve V3 |

**Limites de l'inventaire :** inspection documentaire et recherches de code ; ni audit exhaustif des métriques, ni exécution de stack, ni constat d'un runtime V3 instrumenté.

## 2. Objectif de mesure et unités de progression

Séparer impérativement (a) latence utilisateur bout en bout, (b) temps d'attente, (c) temps de calcul et d'exécution, (d) délai de propagation READ.

Les points suivants sont des **frontières logiques** et non l'obligation de les persister tous :

| Code | Frontière observable | Pourquoi |
|---|---|---|
| T0 | Réponse d'admission HTTP d'une demande durable | Point de départ exact mesuré côté client ; la date stockée côté serveur peut précéder le commit |
| T1 | Source publiée / durable et découvrable | Point de départ de la chaîne de Consumer ; métrique interne ou approximation explicitée |
| T2 | Slot enrôlé durablement | Fin de l'attente de Discovery ; ouverture de l'attente Engine |
| T3 | Slot acquis durablement | Fin de l'attente de claim ; début de la tentative |
| T4 | Préparation terminée, entrée dans la transaction finale | Séparer préparation et attente/commit final |
| T5 | Effets et suppression du slot durablement committés | Fin logique de Consumption, y compris résultat terminal |
| T6 | Projection requise consultable par API READ | Mesure de disponibilité effective, avec ses conditions minVersion/composants |

Pour les `POT_EVENT`, mesurer **indépendamment** :
- `MaterializeCurrentPot` → CurrentPot et sous-objets ;
- `PreparePotProjectionTasks` → création atomique de deux tâches READ ;
- `MaterializePotBalance` → PotBalance(P,V) ;
- `MaterializePotVisibility` → PotVisibilityState(P,V), entrées utilisateur ;
- `COMMAND_COMPLETION_EVENT` → CommandResult.

Ne **pas additionner** les délais de branches parallèles. La disponibilité d'une consultation complète correspond à la satisfaction des projections **nécessaires à cette lecture**, pas automatiquement à toutes les projections existantes. Une lecture de liste n'a pas de barrière de synchronisation globale entre Pots.

## 3. Contrat minimal d'instrumentation envisagé

1. **Côté client / Bruno / k6 :** mesurer l'admission HTTP et effectuer des lectures/polls bornés vers le résultat et les projections exigées ; enregistrer latence observée, issues, nombre de requêtes de polling et timeout. Les durées entre deux polls sont des bornes d'observation, pas des temps de commit exacts.
2. **Côté Engine/Discovery :** timers monotones en mémoire pour cycle Discovery, sélection/enrôlement, acquisition, chargement/préparation, protection/validation et durée de transaction ; compteurs de tentatives/réessais, échecs, collisions et expirations ; gauges/capacités et backlog mesurés sans compter à chaque requête si coût excessif.
3. **Côté durable :** explorer les timestamps déjà disponibles et ceux qui seraient indispensables aux segments d'attente qui traversent les processus/crashes. Ne **pas** réintroduire un journal de claims ou un état DONE uniquement pour obtenir une courbe. Le slot V3 reste à six champs conceptuels, sauf justification explicite future par un invariant/objectif concret.
4. **Corrélation :** exploiter `requestId`, `eventId`, `consumptionId + consumableId`, `taskId`, et un traceId de diagnostic si disponible. Aucun nouvel identifiant métier global. Les identifiants à forte cardinalité ne sont jamais des labels Prometheus.
5. **Traçage :** logs structurés et corrélations ciblées suffisent conceptuellement ; le besoin de tracing distribué complet ou d'un nouveau backend doit être démontré avant adoption.

## 4. Définitions des latences et contraintes de précision

- **Latence d'admission HTTP :** départ client → réponse HTTP persistée.
- **Attente de Discovery :** publication source durable → apparition durable du slot (mesure exacte seulement si frontières de commit accessibles ; sinon proxy avec biais annoncé).
- **Attente Engine :** slot disponible → acquisition effective, en excluant / isolant les périodes `retryAfter` et de claim détenu.
- **Temps d'exécution :** acquisition → fin de préparation → protection finale → commit/rollback, avec instrument monotone local. Une tentative avortée compte séparément.
- **Propagation par projection :** `POT_EVENT` durable → projection spécifique observablement disponible ; la préparation des tasks est une phase intermédiaire, pas un retard imposé aux autres branches.
- **Bout en bout métier :** soumission Command → résultat terminal visible, puis → lecture READ demandée disponible. Les rejets ne sont pas confondus avec les latences des mutations appliquées.
- **Retries :** durées et issues par tentative ; latence E2E inclusive de toutes les attentes, même si les timers de travail sont par tentative.

**Horloges :** client et serveurs ont des horloges non nécessairement synchrones ; `System.nanoTime` ne peut être comparé entre processus. `createdAt` ou `doneAt` écrits avant commit ne désignent pas l'instant physique de visibilité. Préciser pour chaque SLI la méthode de mesure (client, timer monotone, borne par sondage, proxy durable) et sa précision.

## 5. Dimensions et cardinalité

Dimensions admises par défaut : famille/Consumption logique connue, type d'opération, phase, issue bornée, partition seulement si cardinalité fixée et justifiée. Jamais `userId`, `potId`, `requestId`, `eventId`, `taskId`, `claimToken`, `bindingToken` ou `traceId` dans les labels de métriques ; aucun token dans logs/traces. Les durées p50/p95/p99 exigent des histogrammes adaptés (un timer total seul ou un scrape de moyenne `sum/count` ne suffit pas pour les quantiles fiables).

## 6. Questions ciblées à arbitrer avec V3-5C/D

- **O-01 — Granularité :** quel niveau de mesure durable des temps d'attente est indispensable : latence E2E externe + timers agrégés, ou diagnostics précis source→slot→claim par consommable et après crash ? Le second exige des bornes temporelles durables fiables, à justifier.
- **O-02 — Contrat de disponibilité :** quelles consultations exigent CurrentPot seul, et lesquelles exigent PotBalance et/ou visibilité ? Les SLIs E2E doivent être définis par parcours observable.
- **O-03 — Perte/retard :** fixer seuils de backlog, âge maximal des sources/slots et conditions de rattrapage dans V3-5C ; ne pas inventer de valeurs en V3-5B.
- **O-04 — Validation des percentiles :** instrumentation histogramme et fenêtres de mesures sous profils de charge ; décider les détails techniques pendant V3-6, sans nouveau backend imposé.

## 7. Statut et passage V3-5C

**V3-5B : cadrage initial documenté, NON ENCORE VALIDÉ.** Décider d'abord O-01 (niveau de détail durable nécessaire) ; les autres points se poursuivent vers V3-5C/D. Ni performances chiffrées, ni code, ni exécution k6 ne sont revendiqués.
