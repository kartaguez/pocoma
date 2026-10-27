# I2 — preuves locales bornées

Le [rapport](../../../v3/L2_I2_Execution_Report.md) porte le verdict et les limites. Le [manifeste figé](../../../v3/L2_I2_Proof_Manifest.json) nomme les méthodes obligatoires par gate. Le manifeste L2 global conserve ses reports et ne devient pas PASS par cette campagne.

Les archives de chaque gate contiennent `maven.log`, `result.json`, les XML Surefire **frais**, les vecteurs SQL de test, traces PID/xid8/contraintes/barrières et profils/plans F/F2 générés pendant ce gate. `result.json` indique SHA, commande exacte, durée, cas PASS/FAIL/SKIP et SHA-256 de chaque artefact. `Gate-index.json` authentifie les archives. Les réexécutions d'une même méthode dans plusieurs gates ne sont pas additionnées comme garanties distinctes.

`Diagnostics.tar.gz` conserve les essais de construction et tests antérieurs, y compris les erreurs de harnais et le contre-exemple PostgreSQL réel à la garde basée sur les seuls holders. `Diagnostics-index.json` en inventorie les logs sans leur attribuer le verdict d'un gate. Le premier gate COMMAND, omettant `L2ManifestProof`, est conservé comme **FAIL du collecteur**, malgré BUILD SUCCESS Maven : ses méthodes absentes ne sont pas comptées PASS.

`Performance-before.*` provient de `cd4dac2a` dans un checkout neuf de référence, sans Maven concurrent. La campagne après I2 emploie les mêmes données, intentions, 5 échauffements et 10 répétitions aux profondeurs 100/1000/10000 ; son harnais adapte uniquement les contrats Engine/participant. Les mesures sont internes à WRITE et ne qualifient aucun SLO V3.

Reproduction depuis le commit de code indiqué au rapport, worktree propre pour `app/` et `scripts/`, Java 21 et Docker disponibles :

```sh
python3 -B scripts/testing/verify-v3-l2-i2.py command /tmp/i2-command
python3 -B scripts/testing/verify-v3-l2-i2.py transactions /tmp/i2-transactions
python3 -B scripts/testing/verify-v3-l2-i2.py monolith /tmp/i2-monolith
python3 -B scripts/testing/verify-v3-l2-i2.py architecture /tmp/i2-architecture
```

Compléments exécutables (après un gate ayant compilé PRIMARY ; les logs sont produits dans ce dossier, choisir un label neuf) :

```sh
python3 -B docs/testing/evidence/L2-I2/run-supplement.py R9IndependentCorruptionProof r9-reproduction
python3 -B docs/testing/evidence/L2-I2/run-supplement.py LateCallbackPoisonProbe callback-reproduction
python3 -B docs/testing/evidence/L2-I2/run-supplement.py AutonomousJpaPoisonProbe autonomous-reproduction
```

Le lanceur utilise le classpath de l’XML Surefire PRIMARY et compile seulement les probes de preuve ; il ne modifie aucun code applicatif. `Supplement-provenance.json` distingue les contre-exemples historiques des assertions passées après correction. Ces assertions séparées ne sont pas ajoutées au décompte JUnit.

Chaque destination doit être neuve ; chaque invocation exécute depuis `app/` la commande canonique correspondante, sans filtre de tests. Exécuter séquentiellement pour conserver la provenance et la comparabilité des profils. Les schémas des tests sont neufs dans des conteneurs jetables ; aucun bootstrap de cible externe. Les profils runtime I1 restent inactifs. Aucun full reactor, I3 ou G1/G2/G3 n'est lancé par ce collecteur.

Les logs et XML supplémentaires bruts (probes et profils ciblés) sont conservés **sans modification d’octets** dans `Standalone-raw.tar.gz`. `Standalone-index.json` nomme et authentifie chaque membre ; les champs `log`/`log_member` de `Supplement-provenance.json` désignent ces membres. Les XML de chaque gate restent dans leurs archives propres.
