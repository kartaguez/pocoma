# L7-LB Jalon 1 — limites et obligations conservées

D9 : verdict historique et défaut produit sont distincts. Aucun défaut produit
connu de sûreté n'est accepté dans le parcours annoncé. Ce registre ne ferme
aucune des 522 obligations, des15 réserves L7.3, ni les quarantaines i5_r05/i5_r12.

| ID | État / garantie affectée | Propriétaire et suite |
|---|---|---|
| LB-J1-GUI | NOT_RUN humain : flux navigateur interactif et ergonomie de la version GUI installée, guide livré ; Code+PKCE S256 et parcours Bruno CLI réels vérifiés séparément | Acceptant Jalon1 : exécuter login puis PG01 GUI, sans désactiver PKCE |
| LB-J1-COLD | Installation indépendante depuis Git sans JAR/volume/état Pocoma local ; caches d'images de dépendances et Maven partagés sur le même Mac, pas deux hôtes neufs | I6 : second hôte/caches froids et récupération distante complète |
| LB-J1-RESOURCES | Profil 16Go, Docker ≥6Gio (test8Gio), exécution séquentielle ; pas de garantie de capacité/concurrence de stacks | I5 : mesures locales préenregistrées, pas de campagne exhaustive sous ce mandat |
| LB-J1-CLIENT | Contrat BFF documenté et PG01 exécutable ; pas de SDK généré ni de CORS SPA qualifié | Futur BFF : stockage session, callback/CSRF et politique navigateur à qualifier |
| LB-J1-RESTART | Baux240s inchangés ; arrêt gracieux et reprise testés, aucune promesse de reprise immédiate après SIGKILL en plein travail | I4 : crashs adverses avec attentes réelles des baux et traces PG |
| LB-I1b | NOT_RUN : réconciliation exhaustive des522 obligations différée ; verdicts historiques conservés | I4/I6 : allocation/revue nominative, sans promotion automatique des preuves partielles |
| LB-I4 | NOT_RUN : sûreté exhaustive/15 réserves/audit writers indirects et toutes variantes non couvertes | I4 : mêmes invariants et oracles, tout défaut démontré sur parcours annoncé bloque |
| LB-I5 | NOT_RUN : qualification performance locale consolidée ; SPEC09 A/B externe toujours NOT_RUN | I5 puis programme externe autorisé, seuils SPEC inchangés |
| LB-I6 | NOT_RUN : gel baseline, gates globales, audit indépendant, acceptation humaine | I6 : full non filtré et architecture selon politique, aucune dispense par Jalon1 |

La [qualification](L7_Local_Playground_Qualification_Report.md) porte la décision
proposée et les preuves manquantes de Jalon1. Les rapports historiques L7.3
NOT_CERTIFIED, L7.4 non certifié, L7.5 BLOCKED et L7 OPEN sont inchangés.
