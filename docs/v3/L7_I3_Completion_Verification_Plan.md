# L7.3 — Plan de réconciliation et vérification ciblée

Mandat `GO_L7_I3_COMPLETION`. Point de départ `f9436ecebf992523d0f48e32995e0e70ef173d8d`, branche `feat/v3-architecture`, worktree propre et distant identique.

La classification initiale contient 53 obligations : 21 A (preuves actuelles à attribuer), 29 B (contributions partielles) et 3 D (oracle ou audit manquant). Aucun échec produit avéré ne découle de cette classification. Les catégories C/E/F sont vides à ce stade. [Détail individuel](../testing/evidence/L7-I3-completion/initial-classification.json).

La référence exécutée est `5ae89228c16347ed536ac18eaf9a8946a5ac4d32`. Les deux gates ont 1 057 exécutions sans échec/erreur/skip ; aucune différence `app/` avec le point de départ. Le rapprochement ne promeut pas les anciennes garanties techniques en garantie métier réelle sans analyse du participant.

## Frontière de vérification déclarée

- Impact produit attendu : aucun. Nouvelle preuve seulement pour Discovery Identity sous claim/retry/quarantaine.
- Tranche primaire : COMMAND ; PROJECTION secondaire pour l'analyse des preuves READ, sans modification.
- Interdits : contrat HTTP, production, architecture, POM, migrations et règles L2.
- Feedback ciblé : `./mvnw --batch-mode --no-transfer-progress -pl runtime-worker-process -am '-Dtest=IdentityDiscoveryPostgresTest#l73_discoveryPreservesClaimsRetriesAndQuarantine' -Dsurefire.failIfNoSpecifiedTests=false test`, depuis `app/`, avec identifiant et répertoire de rapports neufs.
- Base : CURRENT, PostgreSQL Testcontainers jetable ; bootstrap autoritatif existant, aucune base utilisateur réinitialisée.
- Gate Architecture : non requise (§17), aucune frontière modifiée.
- Full reactor : non requis ; les deux reçus existants couvrent les entrées inchangées, le nouveau test constitue une preuve ciblée supplémentaire.
- Escalade : réviser le périmètre avant toute correction productive ; aucun échec local ne déclenche automatiquement un full.

## Oracle complémentaire L2M-C01-J-REPORT

Pour quatre demandes Registration réellement admises : enrôlement unique ; puis claim actif, claim expiré, retryAfter futur, quarantaine. Trois passages Discovery doivent préserver exactement chaque ligne de slot, les compteurs et la demande PENDING. Aucun claim n'est réacquis avant l'oracle de Discovery. Après observation, le vrai Engine doit refuser l'acquisition des variantes actif/retry/quarantaine et permettre celle du claim expiré avec token neuf. L'heure des échéances vient de PostgreSQL. Les références historiques « sans checkpoint » sont amendées par SPEC-05 et L7/I2 : un checkpoint prouve la découverte couverte, jamais la terminaison.

Les catégories B restent partielles jusqu'à l'exercice de leur stimulus exact ; les trois D ne sont pas couvertes par une simple existence de test ou un réacteur vert. Aucun travail L7.4/L7.5 ni benchmark dans ce mandat.
