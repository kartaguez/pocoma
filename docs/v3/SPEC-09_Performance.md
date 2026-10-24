# Pocoma SPEC-09 — Performance

**Statut : objectifs conceptuellement validés, non mesurés en V3.**  
**Consolidation :** 2026-10-24. [Canon](V3_Canon_and_Roadmap.md).

## 1. Profils de référence

| Profil | Shareholders | Expenses |
|---|---:|---:|
| Petit | 5 | 20 |
| Moyen | 20 | 200 |
| Grand | 100 | 2 000 |

Ces profils sont des dimensions de jeux de données, ni des limites fonctionnelles ni des objectifs de temps.

## 2. Axes de charge et soutenabilité

- **Taille locale :** trois profils de Pot.
- **Volume global :** nombre de Pots, utilisateurs, ancienneté des événements et versions.
- **Débit :** admissions par seconde, répartition des mutations.
- **Contention :** commandes concurrentes sur le même Pot versus Pots indépendants.
- **Backlog :** stock initial, nouvelles arrivées pendant le rattrapage.
- **Capacité :** ressources PostgreSQL, Workers, partitions, pools, machine et réseau.

La capacité d'admission HTTP ne prouve pas la stabilité : il faut vérifier débit terminal et progression READ.

## 3. Scénarios et budgets

Scénarios : **nominal**, **pointe**, **rattrapage**. Mesurer l'attente Discovery/Engine, la préparation, la validation transactionnelle, la matérialisation indépendante des résultats, CurrentPot, balances et visibilité, les parcours Identity, et les disponibilités bout en bout. Rapporter p50/p95/p99, backlog et âge, temps de résorption, ressources PostgreSQL/CPU/mémoire/WAL ; séparer succès, rejets, erreurs et reprises. La configuration matérielle est fixée ci-dessous ; durées, populations et tailles d'échantillon des campagnes restent à spécifier en V3-5D.

**Les objectifs chiffrés validés figurent en section 5.** Ils ne constituent aucune mesure V3.

**Charges offertes validées :** nominal 1 commande/s, soutenu 10 commandes/s, pointe 50 commandes/s ; distinguer dispersion entre Pots et contention sur le même Pot. **Volumes globaux validés :** 100 Pots / 10 000 événements ; 10 000 Pots / 1 million d'événements ; 100 000 Pots / 10 millions d'événements.

**Configuration A (moteur) :** PostgreSQL sur machine/VM dédiée 4 vCPU / 8 Go RAM ; API et tous les runtimes Pocoma sur seconde machine/VM 4 vCPU / 8 Go RAM. Générateur k6 et monitoring sur ressources isolées si possible. Authentification externe simulée, sans contourner l'AuthZ Pocoma. **Configuration B (intégrée) :** même base avec Keycloak réel sur ressources isolées, campagnes ciblées sur AuthN, Identity et accès réels ; ne pas multiplier automatiquement toutes les campagnes.

**SLO nominal accepté comme premier objectif :** pour un Pot moyen (20 shareholders, 200 expenses), à 1 commande/s sur configuration A, **p95 ≤ 500 ms depuis l'admission de la commande jusqu'à PotBalance(P,V) disponible**. Ce temps inclut commande, Discovery et Engine, propagation et matérialisation directe de Balance par sa Consumption indépendante. Ce SLO est **un objectif non mesuré**, non une preuve. Budget technique **candidat**, pas encore validé : p95 ≤ 50 ms pour calcul et écriture des balances, hors attente et propagation. Les percentiles des étapes ne s'additionnent pas pour déduire le percentile E2E. Définir clairement les campagnes (mutations appliquées, échantillon et sondage READ).

**Arbitrages suivants résolus ci-dessous :** budgets E2E Pot complet, Identity binding utilisable, charges supérieures et grand Pot. Ne pas étendre le 500 ms nominal à d'autres profils.

**SLO nominal complémentaire VALIDÉ :** p95 ≤ 500 ms depuis l'admission de la commande appliquée jusqu'à la première lecture READ complète et autorisée du Pot moyen (`CurrentPot`/`PotVersion`, sous-objets nécessaires et `PotBalance`, selon l'oracle `C-03` du §4 ; `PotVisibilityState(P)` progresse sur sa branche indépendante hors jalon de lecture du Pot). Les branches sont indépendantes, aucune somme de p95 par étape. Même charge et configuration A que le SLO Balance. Pas d'extension implicite aux grands Pots.

**SLO Identity nominal VALIDÉ :** p95 ≤ 500 ms entre admission durable d'une Registration ou d'un Link et première lecture READ réellement autorisée par le nouveau bindingToken, avec identité authentifiée correspondante et Pot cible dont les projections sont déjà disponibles. Mesure E2E côté client, avec résolution via CurrentBinding ; le test isole le délai Identity du délai de matérialisation Pot. Définir la précision du sondage et les populations de mesures en V3-5D. Objectif non mesuré, à prouver.

**SLO de charge VALIDÉS (Pots moyens indépendants, configuration A) :** p95 admission Command → Pot entièrement matérialisé ≤ 1 s à 10 commandes/s soutenues, ≤ 3 s à 50 commandes/s en pointe ; 500 ms à 1 commande/s nominale déjà validés. La charge soutenue doit rester stable sans croissance continue de backlog. Une pointe peut générer du backlog temporaire, dont la résorption sera mesurée ensuite. Ces objectifs ne présument pas de la capacité réelle mesurée, ni du comportement sous contention sur un seul Pot. Le dimensionnement exact du backlog de rattrapage reste à arbitrer.

**Objectif de rattrapage VALIDÉ (à mesurer) :** après accumulation de **10 000 commandes admises** et des traitements/projections associés non achevés, redémarrer les Workers sous **1 nouvelle commande/seconde**, sur configuration A et Pots moyens indépendants. **Délai maximal cible : 5 minutes** jusqu'à terminalisation des 10 000 demandes initiales et matérialisation complète de leurs projections attendues ; ne pas assimiler disparition des slots Command à fin de rattrapage. Mesurer séparément le backlog par Consumption, débit utile de résorption, latence des nouvelles demandes et ressources. Objectif non testé ; la composition exacte et l'initialisation du lot seront spécifiées en V3-5D.

**SLO grand Pot nominal VALIDÉ (non mesuré) :** sur configuration A, à 1 commande/s, pour un Pot de 100 shareholders et 2 000 expenses, p95 ≤ **2 secondes** de l'admission d'une commande appliquée jusqu'à la disponibilité en lecture complète du Pot selon l'oracle `C-03` du §4 (`CurrentPot`/`PotVersion`, sous-objets nécessaires et `PotBalance` ; `PotVisibilityState(P)` étant suivi séparément). Ne pas extrapoler ce budget aux charges soutenues/pointe ni confondre progression de `CurrentPot` avec consultation historique exacte. Qualifier particulièrement le coût du recalcul intégral des balances.

**Contention sur un même Pot — principe VALIDÉ :** campagne distincte des scénarios de Pots indépendants. Produire des commandes concurrentes sur le même Pot et observer collisions CAS, reprises complètes, rejets métier légitimes, latences et files par Consumption. **Critères :** aucun effet double ou perdu ; aucune validation partielle ; décisions conformes à l'état métier courant ; chaque demande finit par un état terminal ou un échec technique explicitement diagnostiqué ; sous charge redevenue nominale, le backlog doit se résorber. Pas de SLO de latence chiffré adopté pour ce cas, ni d'extrapolation des SLO de Pots indépendants. La concurrence, la durée et les scénarios précis sont à décrire dans V3-5D ; distinguer contention réelle et limitations d'ordonnancement par partition.

## 4. C-03 — Oracle de disponibilité utilisateur et soldes

**ARBITRAGE VALIDÉ, précisant le critère E2E utilisateur du §3.** Pour toute commande appliquée produisant `resultingPotVersion=V`, le jalon E2E **Pot accessible** est la **première lecture API READ réussie** du Pot, authentifiée et autorisée, avec `minVersion=V`, retournant un Pot complet de version courante `W ≥ V`, dont tous les composants nécessaires et la balance **de W** sont cohérents. La version W peut avoir dépassé V : **la consultation historique existe mais ne sert pas d'oracle de première disponibilité courante**.

**Budgets inchangés :** Pot moyen nominal p95 ≤ 500 ms à 1 commande/s ; soutenu p95 ≤ 1 s à 10/s ; pointe p95 ≤ 3 s à 50/s ; grand Pot nominal p95 ≤ 2 s à 1/s. La première réponse READ valide est mesurée depuis l'admission durable ; les timeouts et lectures refusées/incomplètes ne sont pas comptés comme succès. Le résultat Command permet de connaître V, mais sa disponibilité seule ne satisfait pas le jalon.

**Correction distincte et non affaiblie :** chaque `PotBalance(P,V)` attendue doit être produite **exactement et durablement**, même si CurrentPot est déjà passé à W>V ; les sous-objets historiques restent exacts et les Consumptions directes par événement doivent terminer. Le budget nominal spécifique **p95 ≤ 500 ms jusqu'à disponibilité de PotBalance(P,V)** est **conservé comme SLO technique distinct** de la latence utilisateur ; son oracle peut être interne READ et ne nécessite pas d'API historique. Les critères de rattrapage de 10 000 commandes et de complétion de leurs projections restent inchangés.

**Visibilité :** `PotVisibilityState` reste une projection métier à produire et à qualifier séparément ; la lecture directe d'un Pot ne doit pas attendre cette projection si son contrat de lecture ne l'exige pas. En conséquence, le SLO de lecture n'introduit **pas** une barrière artificielle de visibilité sur l'endpoint Pot. L'état complet de toutes les projections et la progression des tâches sont suivis dans les preuves de correction/backlog, hors jalon de première lecture du Pot.

Aucun benchmark V3 exécuté. Les sections antérieures contradictoires sont **supplantées pour la définition du jalon E2E utilisateur** par le présent amendement.

## 5. C-03 — Exception DeletePot

**Amendement normatif à §7 :** l'oracle de première lecture API READ complète `minVersion=V` demeure pour les commandes appliquées dont le Pot reste actif. Pour une commande `DeletePot` appliquée à la version terminale D, ce jalon HTTP `200` n'est pas exigible : le Pot courant et la version historique D sont `404` par contrat.

Le jalon alternatif E2E de `DeletePot` est la **première observation interne dans READ** de `CurrentPot(P)` pointant une `PotVersion(P,D)` terminale `deleted=true`. Mesure depuis l'admission durable de la commande, avec les **mêmes budgets C-03** applicables au profil de charge ; un simple `404` HTTP ne prouve jamais que D est matérialisée. Aucune API, projection ni synchronisation supplémentaire n'est introduite pour l'oracle.

`PotBalance(P,D)` est intentionnellement absente et n'entre ni dans ce jalon ni dans le SLO technique de calcul des balances. Le SLO technique et l'exactitude durable restent obligatoires pour **toutes les versions actives V<D**, y compris celles matérialisées tardivement. La mise à jour de `CurrentUserPotList` / `PotVisibilityState` reste un jalon indépendant ; l'absence de balance terminale n'est ni un échec ni un backlog incomplet. Les mesures distinctes de complétion des trois Consumptions et les garanties de reprise restent obligatoires.

**Qualification AUD-V3-013 :** mesurer séparément, pour les trois profils et notamment le Grand Pot 100/2 000 avec jusqu'à 200 000 participations, (a) nombre et volume des références `PotVersion` écrites par V, stockage historique réel, index et WAL ; (b) présence/manque des `SubobjectVersion`, lectures groupées, contenus historiques effectivement chargés et collisions d'insertion ; (c) quantité de Financials/participations lues pour `PotBalance`, requêtes SQL, coût des fractions exactes, CPU/I/O/mémoire et durée ; (d) taille JSON brute et compressée du manifeste HTTP, sérialisation et fréquence de consultation. Distinguer coûts WRITE métier et effets READ. Les ordres de grandeur issus d'AUD-V3-013 ne sont pas des mesures et le budget Grand Pot reste **p95 ≤ 2 s à 1 commande/s**, non 500 ms. Aucun nouveau budget, cache durable, projection intermédiaire ou changement de contrat n'est introduit.

## 6. Qualification et certification

Le p95 se mesure depuis l'admission durable jusqu'au premier jalon réellement observable selon les profils et budgets contractuels ; les lectures 404/503, sondages manqués, délais de polling et biais de mesure sont documentés. La visibilité possède ses propres preuves indépendantes, et une version active antérieure V<D reste due même lorsque D a été matérialisée. Les scénarios F01-A à F01-J, A11-L/M et la reconstruction C-02 relèvent de SPEC-10. La problématique de coût du Grand Pot relève de AUD-V3-013, sans en anticiper l'arbitrage. Aucun benchmark V3 exécuté.
