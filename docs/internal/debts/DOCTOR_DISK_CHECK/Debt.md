# DEBT-L7LB-01 — Contrôle de l'espace disque hôte dans `pocoma-local doctor`

- **Statut** : **OPEN** (non bloquant pour `L7_LB_CLOSED`)
- **Criticité** : **LOW**
- **Origine** : Clôture `L7_LB_CLOSED` (`main` @ `59f68956e984a932bc28da6d9588680ce49d8210`) et constat S5 (`L7_LB_Distribution_Qualification_and_Guarantees.md` §1.3)

## Constat factuel

Dans [`scripts/local/runtime.py`](../../../../scripts/local/runtime.py#L1314-L1340), la fonction `doctor()` contrôle les outils (`prerequisites.tools`), les sources, le cache de build, la présence de Bruno CLI, les images Docker cachées et la mémoire allouée à Docker (`dockerMemoryMiB`), mais **n'effectue pas le contrôle d'espace disque hôte (`shutil.disk_usage(ROOT).free`)**.

Le contrôle `PRE-DISK` (`256 MiB` si build et images cachés, `2 GiB` si build non caché, `4 GiB` si image Docker manquante) n'est exécuté que dans `resources()` lors de `./pocoma-local start` ([`scripts/local/runtime.py:678-688`](../../../../scripts/local/runtime.py#L678-L688)).

## Impact

Un développeur ou un banc de qualification exécutant `./pocoma-local doctor` sur une machine à faible espace disque obtient `state=READY` (`exit=0`), puis rencontre un refus tardif `PRE-DISK` au moment de `./pocoma-local start`.

## Résolution attendue

Ajouter dans `doctor()` :
1. La mesure de l'espace disque libre sur l'hôte (`freeDiskBytes`).
2. Le calcul du seuil préventif requis selon l'état du cache (`buildCached` et `missingImages`).
3. Un diagnostic préventif explicite en cas d'espace insuffisant (sans bloquer l'aspect read-only de `doctor`).
4. Des tests unitaires Python associés dans `scripts/local/`.
