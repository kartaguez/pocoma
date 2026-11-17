# I5 — dernière qualification : protocole préalable

Mandat humain du 29 octobre 2026, baseline `e0a7ec3bf3ce41bf599df915236a64bc1c599b05`.
Une seule tentative W2/B32/MW4 après gel, nominal puis contention admissible,
puis excessive. Arrêt au premier échec ; pas de retouche/rejeu après gel.
Les FAIL v1/v2 restent historiques. Aucun drainage ne vaut renewal.

## Verification scope

Impact productif attendu : NONE. Primaire COMMAND ; complément MONOLITH pour
la seule gate composée après PASS : depuis `app/`,
`./mvnw -pl runtime-command-consumption-worker,runtime-monolith -am test`.
Preuve ciblée : `./mvnw -pl runtime-command-consumption-worker -am '-Dtest=V3WorkerResourcesPostgresTest#i5_r12' -Dsurefire.failIfNoSpecifiedTests=false test`.
Les 15 déterministes v2 et W1 sont réutilisés avec contrôle d'identité des entrées
sémantiques ; aucun rejeu autonome historique A/B/F01–F05/huit variantes.
Architecture globale NOT REQUIRED ; full reactor NOT REQUIRED. DB CURRENT V3,
fixtures existantes, pas de migrations historiques autonomes. Aucun changement
productif, POM, owner, pool, scheduler, protocole, table ou frontière autorisé.
Un besoin productif arrête ce mandat sauf invariant de sûreté démontré violé.

## Changement physique seul du contrat

Le contrat v3 conserve **tous** les paramètres numériques v2, la preuve de
D(N)=N*(T+q)+P+J et ses oracles : TTL90s, cadence10s, T250ms, P1s, q10ms,
J1s cumulé, delta5s, marge2s et revalidation5s. R reste non borné, 30s NON CERTIFIÉ.
Seuls version et définition physique de disponibilité changent, avant exécution.

Chaque owner Control Engine/Discovery susceptible de prendre ownership est
identifié dans le banc. Hors threads réels `v3-renew-*`, un verrou partagé
advisory transactionnel PostgreSQL est pris **dans sa transaction réelle avant
l'action métier**. Une barrière locale de test ferme les nouvelles entrées avant
owner et attend les retours des anciennes ; le verrou PostgreSQL exclusif de
session confirme leur résolution physique et interdit une nouvelle entrée
silencieuse. Les owners de renewal sont brièvement drainés avant le probe puis
fonctionnent normalement pendant la fenêtre. Les unités et quatre pools réels
restent inchangés ; aucune méthode de renewal n'est appelée par l'orchestrateur.

Dans chaque autorité, un probe extérieur `FOR UPDATE NOWAIT` sur toutes les
lignes contrôle token/échéance ; son rollback termine avant ouverture. La fenêtre
est ouverte sur ce fait, **avant tout résultat renewal attendu**. Pendant la
fenêtre, Discovery/acquisition/maintenance Control sont suspendues avant owner.
C'est une concurrence volontairement réduite, déclarée : le certificat ne couvre
pas leur chevauchement arbitraire avec renewal. Les deux Workers, leurs voies
claims/leases, leurs préparations Business RO retenues et leurs pools restent
concurrents. Les partitions des deux Workers sont disjointes 0|1 et 2|3 ; les
proxies vérifient que seuls les renewals accèdent à ownership dans cette fenêtre.
Le journal exhaustif conserve les transactions de ces voies et leurs clés.
Tout appel non couvert ou résolution inconnue interdit PASS.

Nominal : fenêtre contrôlée avant les vecteurs initiaux. Admissible : même
barrière, détenteurs FOR UPDATE identifiés WRITE/READ par Worker ; fenêtre de
chaque cible fermée pendant contention. Au retour d'omission, libération COMMIT,
vérification extérieure PID/XID résolu et nouveau probe de la cible ouvrent une
fenêtre indépendante ; le vrai thread continue ses revalidations et le retryUntil
original demeure inchangé. Excessif : holders maintenus >=6s après omission,
suspension puis retrait local <=fenêtre5s, token/échéance durables inchangés ;
claims contrôlés séparément après retrait de leases. Pas de condition de succès B
sur les lignes volontairement indisponibles. Les assertions v2 de durée/D/J,
fenêtre locale et échéances restent actives dans les phases positives.

Les nouveaux négatifs vérifient : fenêtre absente/incomplète/fermée, token
remplacé, transaction non couverte, conflit de clé et frontière d'ouverture.
Les preuves finales doivent distinguer FAIL technique du banc, défaut productif
établi et INCONCLUSIVE sans défaut établi. Aucun seuil ne dépend des résultats.
