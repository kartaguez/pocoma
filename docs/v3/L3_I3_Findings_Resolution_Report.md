# L3/I3 — Résolution des findings du contre-audit

État : corrections en cours, aucune clôture canonique ni GO I4.
Référence auditée : `2b3567b1f77051c399772ee7ac787f7dd61ba407`.
HEAD de départ : publication documentaire `00cdc162` (rapport et preuves reçus,
rapport lu intégralement, empreintes de publication et archive vérifiées).
Worktree dédié existant : `/private/tmp/pocoma-v3-postaud`, branche
`feat/v3-architecture`. Checkout principal et autres worktrees préservés.

## Verification scope — déclaré avant modification

- Impact attendu : scripts de grants Identity, refus du préflight et politique
  PostgreSQL des diagnostics du seul banc Identity DEV ; tests et runbook associés.
- Slice primaire : COMMAND, ancre `runtime-command-consumption-worker`.
  Secondaires canoniques : NONE ; preuve directe `runtime-monolith` pour sa racine.
- Commande canonique composée : depuis `app/`,
  `./mvnw -pl runtime-command-consumption-worker,runtime-monolith -am test`.
- Architecture REQUIRED pour les règles d'isolation runtime ; commande canonique
  `./mvnw -pl architecture-tests -am test` (composable avec les ancres ci-dessus).
- Feedback ciblé sur les preuves Identity / deux racines / architecture ; il ne
  remplace pas une gate non filtrée et ne requalifie pas les FAIL antérieurs.
- Full reactor NOT REQUIRED : ni Wave majeure, ni main, ni restructuration Maven.
- DB CURRENT : WRITE V1–V6, READ technique V1 sur PostgreSQL 17.11 jetable ; aucune
  réécriture V6, baseline TRUSTED ou campagne historique séparée.
- Impact interdit : transactions/owner, contraintes V6, séquences, R1/R9, L2,
  budgets/oracles temporels, I4, HTTP, projections READ et autorité Pot.
- Escalade : toute modification productive hors de ces frontières impose une
  nouvelle déclaration avant son implémentation.
- Gates historiques I1/I2/I3 et réserves i5_r05/i5_r12 conservées sans réécriture.

## Frontière de confiance

Le propriétaire des objets, le provisionneur SQL, les administrateurs du serveur
et les extensions installées sont de confiance. Un superutilisateur PostgreSQL
peut changer les ACL, les fonctions, les contraintes ou les logs après inspection :
aucun préflight ne prétend l'empêcher. Les contrôles refusent une configuration
excessive observée avant démarrage ; ils ne retirent pas des grants et ne sont pas
une surveillance continue. Corriger les ACL/objets est une étape administrative
explicite, puis redémarrer les pools et refaire les contrôles.

Le banc DEV fermé n'autorise aucune vue/vue matérialisée lisible par READ sur
WRITE, ni fonction SECURITY DEFINER hors schémas système dans cette base.
Ce refus conservateur évite de prétendre analyser les dépendances du SQL dynamique
ou de fonctions opaques par un simple inventaire de privilèges directs.
Les scripts administratifs certifient les rôles auxiliaires nommés ; le Worker
ne dispose pas de leurs credentials et n'en invente pas les identités.
