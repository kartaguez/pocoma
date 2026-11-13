# L7.4 — Anomalies et réserves techniques

Mandat d'exécution technique, sans certification automatique. Les archives originales
restent immuables ; leur interprétation est distinguée de leur statut brut.

| ID | Observation et preuve | Traitement / statut | Conséquence |
|---|---|---|---|
| L74-ENV-01 | Docker Desktop monohôte 10 CPU/8 319 504 384 octets, pile antérieure partagée ; absence de cible distante confirmée humainement | OPEN, environnement A/B indisponible | Les 13 familles du manifeste sont NOT_RUN en conditions prévues ; aucune certification SPEC-09 |
| L74-TOOL-01 | E1-01 HTTP401 avant seed ; émetteur RSA sans claim `auth_time`, exigé par le validateur courant | FIXED dans l'émetteur de test ; signatures/claims et authentification réelle des essais suivants passent | Aucun contournement AuthN/AuthZ ; pas de changement Java |
| L74-TOOL-02 | E1/E3-04 bootstrap lors de l'arrêt du serveur temporaire PostgreSQL ; socket absent après pg_isready Unix | FIXED : readiness TCP exclut le serveur d'init ; essai06 démarré normalement | Aucune charge métier dans l'essai interrompu ; reçu INCONCLUSIVE conservé |
| L74-MET-01 | E1/E3-05 E1 PASS puis reprise non observée à60 s ; bail de partition configuré240 s | Protocole corrigé avant06 : échéance persistée observée +60 s, sans modification de lease ; reprise constatée à238,5 s | FAIL brut05 conservé. Il ne prouve pas une perte de travail après expiration ; pas de nouveau SLO de reprise60 s |
| L74-TOOL-03 | Revue : un PASS E1 pouvait rester au niveau global après une exception ultérieure non AssertionError | FIXED avant06 ; contrôle discriminant timeout après PASS passe | Aucun PASS effectivement publié sur une telle exception dans les essais antérieurs ; garantie prospective du collecteur renforcée |
| L74-ENV-02 | Test issuer initial refusé par sandbox `listen EPERM` loopback | Rejoué avec accès local autorisé, PASS ; reçu de refus conservé | Aucun défaut logiciel attribué à ce refus d'environnement |
| L74-MET-02 | Coût mural de collecte mesuré ; aucune comparaison contrôlée avec/sans collecte | OPEN | Pas de revendication d'impact négligeable ; calibration sur A/B indispensable avant E2 |
| L74-MET-03 | E1 petit profil,12 offres par population, pas de warmup contractuel ; balance sondée après le Pot complet | OPEN pour SPEC-09 | Aucun PASS SLO ; coût Grand Pot, W>V et mesure Balance indépendante restent non qualifiés |
| L74-BENCH-01 | Snapshots moyens/grands/globaux et10000 commandes non préparés ; DeletePot interne non ordonnancé ; mix créations/corrections/suppressions non intégré au nouveau runner | OPEN | E1 n'est pas un ordonnanceur E2 complet ; maintenir NOT_RUN, compléter le banc avant campagnes |
| L74-SAFETY-01 | E3 interrompt un processus suspendu après admission ; aucun crash pendant calcul, READ ou claim acquis | OPEN sur les autres garanties L7.3 | Seule E02-J est candidate à clôture ; C04-K/CON05/C03-J restent partielles |
| L74-ADMIN-01 |2704 candidats dans 239 fichiers ; SQL indirect et autorités non exhaustivement revus | OPEN | Aucun inventaire exhaustif ni preuve universelle de non-purge ; anciennes protections L73-ADMIN-01 conservées |
| L74-PROVENANCE-01 | E1-01/02 testés au SHA initial avec scripts dirty ; E1-03 puis06 aux commits du banc | Trace conservée ; réemploi proposé uniquement sur 06 pour E02-J | Les hashes du run06 concordent avec les entrées courantes ; les observations de développement ne certifient pas un SHA ultérieur |

Les ressources de PostgreSQL et des Runtimes sont bornées dans chaque reçu. Tous les
projets du banc ont été retirés ; la pile antérieure est restée en service. Aucune
migration, donnée métier préexistante, timeout productif ou exigence normative modifié.
Aucun défaut Runtime n'est démontré par les incidents ci-dessus ; cette absence de
défaut découvert ne ferme pas les garanties encore non prouvées.
