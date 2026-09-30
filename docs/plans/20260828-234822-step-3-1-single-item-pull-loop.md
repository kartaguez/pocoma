# Étape 3.1 — Boucle pull séquentielle commune

## Objectif

Ajouter à `orchestrator-claimable-work-dispatcher` une boucle minimale réutilisable par les futurs
workers Command, Event et Task. Elle gère uniquement start/stop, polling, réveil optionnel,
répétition après progrès et exclusion de deux itérations simultanées.

## Contrats

Créer `orchestrator.claimable.pull` avec :

```text
PullIteration
SingleItemPullLoop
SingleItemPullLoopSettings
package-info.java
```

`PullIteration.runOnce()` retourne `true` lorsqu'un unique élément a atteint son issue technique,
et `false` lorsqu'aucun travail n'est disponible. Les settings contiennent uniquement enabled,
workerId, pollingInterval et wakeSignalsEnabled.

`SingleItemPullLoop` délègue attente et scheduling à `WakePollingRunner`. Il recommence
immédiatement après `true`, attend après `false` ou exception, expose `runOnce()` pour les tests et
sérialise toute invocation directe ou autonome.

## Compatibilité

Ne modifier ni supprimer aucun dispatcher, pool ou contrat legacy. Retirer seulement la dépendance
inutilisée de l'orchestrateur vers `engine-core` après validation.

## Tests

Prouver sans `Thread.sleep` : appel unique, résultat conservé, drain séquentiel, absence de
concurrence, start/stop idempotents, disabled, polling, réveils filtrés, reprise après exception et
validation des settings. ArchUnit interdit au nouveau package domaines, engines, couches externes,
frameworks et vocabulaire spécialisé de travail.

## Validation

```text
./mvnw -pl orchestrator-claimable-work-dispatcher test
./mvnw -pl architecture-tests -am test
./mvnw clean test
```

La prochaine étape crée `supra-worker-command`.
