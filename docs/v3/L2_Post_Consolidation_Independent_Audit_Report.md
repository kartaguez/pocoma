# Audit indépendant L2 après consolidation R1–R10

## 7.1 Synthèse exécutive

**Verdict : FAIL documentaire avant autorisation d’implémentation.**

Les dix arbitrages sont fidèlement transcrits et constituent une architecture globalement cohérente. **Aucun scénario démontrant une perte ou un double effet dans les protocoles consolidés n’a été établi.** Deux anomalies majeures empêchent toutefois de considérer le dossier comme prêt pour G0 :

1. Les prérequis de G0 sont circulaires : certains exigent des vérifications sur le futur code et des mesures PostgreSQL, alors que G0 précède son autorisation.
2. Le manifeste exhaustif exigé par R6 avant implémentation n’existe pas encore. La matrice actuelle ne remplace pas une allocation de toutes les sous-variantes, méthodes, oracles et reports.

| Sévérité | Nombre |
|---|---:|
| CRITICAL | 0 |
| MAJOR | 2 |
| MINOR | 3 |
| OBSERVATION | 1 |

Ce FAIL porte sur la préparation documentaire et probatoire du lancement. Les corrections proposées préservent les décisions humaines R1–R10 et ne nécessitent pas, en l’état, de nouvel arbitrage de protocole.

### Référence effectivement auditée

| Élément | Constat |
|---|---|
| Checkout actif | `feat/runtime-monolith` |
| HEAD local | `90a3e9bc1a1fdd325d0c36e543824239cd85a431` |
| Écart initial | Branche et SHA différents de la référence demandée ; modifications locales préexistantes |
| Branche distante contrôlée par GitHub | `feat/v3-architecture` |
| HEAD distant | **`c67fcfe7e245b0f9cb8f77af0cc6444f7c0cb43c`**, conforme |
| Corpus audité | Fichiers GitHub épinglés à ce SHA |
| Modifications effectuées | Aucune |

L’audit a confronté les neuf documents prioritaires, les SPEC connexes pertinentes, les contrats et dossiers L1-C/F/F2, leurs manifestes et index de preuves, ainsi que les chemins L1 nécessaires : `ExecutePotCommand`, `JdbcPotCommandStore`, `PotCommandJdbcTransactions`, ports transactionnels et migration d’exécution.

Les archives historiques binaires n’ont pas été réextraites et les campagnes L1 n’ont pas été reproduites. Leurs verdicts restent des résultats historiques attribués à leurs dossiers, sans devenir des preuves L2.

### Autorité normative reconstituée

Le corpus permet de retrouver la chaîne suivante :

- Le **canon** porte les décisions transversales.
- Les **SPEC** portent les invariants fonctionnels et techniques.
- Le **registre A01–A12** transcrit les décisions initiales.
- La **consolidation R1–R10**, reconnue par SPEC-05, amende ces décisions et supersède les recommandations antérieures divergentes.
- Le **plan L2** organise l’implémentation et les preuves futures.
- Le **plan directeur** porte les statuts du programme.
- Les contrats et rapports **L1-C/F/F2** documentent les garanties à transférer ; leurs formes de tables et propriétaires physiques ne deviennent pas obligatoires pour L2. [Canon §3][canon], [SPEC-05, amendements][spec05], [registre, décisions complémentaires][registre].

Les références historiques à trois tables, aux options initiales de R1 et aux constats ouverts sont **explicitement supersédées**. Je ne les compte pas comme contradictions actives.

**Contrôle documentaire effectué :** 225 liens relatifs et 34 ancrages des neuf documents prioritaires sont résolus dans l’arbre Git audité.

## 7.2 Matrice R1–R10

« Testable » signifie ici qu’un oracle et une campagne réalisables existent, sans préjuger de leur exécution.

| ID | Décision fidèlement transcrite | Cohérence | Implémentabilité | Testabilité | Réserves |
|---|---|---|---|---|---|
| R1 | Oui : capacité Engine unique, suppression avant fermeture et suffixe | Cohérente avec L1 adapté | Réalisable | P5, rollback, guards, concurrence | Résidu rédactionnel §8 ; futurs guards et contraintes canoniques à vérifier |
| R2 | Oui : plafond d’admission, sérialisation Discovery, administration indépendante | Cohérente | Réalisable | P2/P3/P6 | Ne jamais certifier un plafond absolu de stock |
| R3 | Oui : SHA-256, contrat durable, bootstrap administratif, quatrième table | Cohérente par supersession | Réalisable | P1/P4 | Formaliser le contrat actif et contrôler ensemble toutes les Consumptions d’une famille |
| R4 | Oui : curseur volatil circulaire et U fixe par tour | Cohérente | Réalisable | P3 | Fin de tour fondée sur la navigation effective ; aucune garantie sous crashes arbitrairement fréquents |
| R5 | Oui : classification L1, cession Engine unique, paramètres prospectifs | Cohérente | Réalisable | P6 | Décomposer toutes les variantes d’incertitude et de changement de paramètres |
| R6 | Oui : preuves bornées, aucun PASS global prématuré | Principe correct | Applicable | Contrôles de couverture/provenance | **Manifeste préalable absent : L2-POSTAUD-002** |
| R7 | Oui : environnement neuf, isolé, aucune destruction historique | Cohérente | Réalisable | P1/P3 | Cibles, permissions et anciens writers à inventorier avant bootstrap |
| R8 | Oui : aucune purge d’incomplet | Cohérente | Réalisable | C02-L, C04-L, compléments CON-05 | Aucun résidu actif de permission de purge identifié dans le corpus L2 examiné |
| R9 | Oui : quatre observations, sans mutation, transactions Engine | Cohérente | Réalisable | P5/P6 | Fixer les lectures cohérentes et les postconditions par pilote pendant I2 |
| R10 | Oui : parcours circulaire, renouvellements indépendants en threads et PostgreSQL | Cohérente | Réalisable | P4/P7 | Prouver la couverture des clés sautées et l’indépendance face aux attentes de verrou |

### Analyse des interactions critiques

**R1 et L1.** Le raccord est viable. `JdbcPotCommandStore.finish()` écrit actuellement résultat et terminalisation puis supprime le slot ; `publish()` prépare les événements, flush, vérifie les contraintes métier, ferme la publication, puis attribue les positions. Les triggers terminaux restent différés au commit. La capacité Engine peut remplacer ce DELETE à son emplacement actuel, sans rouvrir les writers après fermeture. Le DDL L1 examiné ne comporte pas de trigger de suppression du slot ni de FK entrante vers celui-ci. Cela établit la faisabilité du raccord, pas celle du futur DDL canonique. [Store L1, lignes 154–234][store], [DDL d’exécution, lignes 69–109][ddl].

Le garde doit lier la capacité à l’unité physique, à la clé et au token déjà protégés. L’unique appel doit être vérifié avant commit ; une exception interceptée ne doit pas rendre l’unité committable. Le verrou reste protecteur jusqu’à résolution physique, y compris après DELETE transactionnel. [Owner L1][owner], [verrouillage PostgreSQL][pglocks].

**R2 et checkpoint.** Le verrou de `partition_ownership` sérialise les Discoveries qui utilisent toutes cette protection. Finalisation et quarantaine peuvent réduire le stock : leur non-visibilité immédiate rend le comptage conservateur. L’administration peut l’augmenter sans ce verrou ; R2 autorise expressément ce dépassement. Le préfixe enrôlé et K restent atomiques : aucun événement pertinent non enrôlé ne peut être franchi.

**R3 et claims existants.** Une incompatibilité interdit Discovery et nouvelles acquisitions ; elle ne révoque pas rétroactivement un claim individuel valide. Cette séparation est cohérente avec A08. L’immuabilité du contrat pendant l’existence de slots/checkpoints évite qu’une modification ordinaire de configuration déplace silencieusement du travail déjà couvert par K.

La convention référencée décrit les octets UUID réseau, les séparateurs zéro, les noms ASCII, SHA-256, l’int32 signé big-endian et `floorMod`. Les sept vecteurs publiés pour l’UUID ordinaire ont été recalculés indépendamment et correspondent. Le libellé encore « proposé » dans le corps du plan est historique au regard de R3-01 ; il gagnerait à être remplacé par le contrat actif.

**R4 et R10.** U fixe empêche les nouvelles clés supérieures de prolonger indéfiniment le tour. Les insertions derrière le curseur reviennent au tour suivant. Une ligne disparue ou devenue inéligible ne doit pas empêcher de terminer le tour. La navigation et l’acquisition doivent rester distinctes : `SKIP LOCKED` omet précisément les lignes verrouillées et ne fournit pas une preuve de couverture. [Consolidation §§4 et 10][conso], [PostgreSQL SELECT][pgselect].

**R5 et R9.** Les quatre observations sont suffisantes pour séparer les conclusions utiles, à condition de ne pas leur attribuer une causalité qu’elles ne prouvent pas :

| Observation | Conclusion admissible |
|---|---|
| COMPLETED | Preuves métier et techniques cohérentes de réussite durable |
| NOT_COMPLETED | Réussite durable non constatée dans cette vue ; aucune preuve de rollback |
| INCONSISTENT | Faits incompatibles avec les invariants ; aucune conclusion optimiste |
| UNAVAILABLE | Observation impossible ou cohérence de lecture non garantie |

Plusieurs SELECT en READ_COMMITTED peuvent voir des états différents. Une observation composée doit donc utiliser une instruction cohérente ou un snapshot adapté, ouvert par Engine. La Consumption n’a besoin d’aucun owner indépendant. [R9][conso], [isolation PostgreSQL][pgiso].

## 7.3 Registre des constats

### L2-POSTAUD-001 — MAJOR — Dépendances circulaires autour de G0

**Sources.** Consolidation §12, points 1 et 6 ; plan L2 §14, séquence I0–I7 et définition de G0. [Consolidation §12][conso12], [plan §14][plan14].

**Conflit.**

- La consolidation classe avant G0 la vérification, dans le code et le DDL, du futur `SlotCompletion`, ainsi que des SQL, barrières et mesures réels pour R10.
- G0 précède toute autorisation de code.
- I0, placé après G0, doit figer paramètres, signatures, matrices et manifestes, alors que G0 exige déjà manifeste et scope fixés.

**Scénario concret.** Un agent respecte l’interdiction de code avant G0. L’auditeur lui demande alors les preuves sur `SlotCompletion` et les mesures du futur parcours SQL pour fermer G0. Elles ne peuvent pas exister. Inversement, les produire exige de franchir prématurément le gate.

**Invariant concerné.** Séparation entre validation documentaire, autorisation d’implémentation et preuve exécutée ; ordre des dépendances du plan.

**Correction minimale.**

1. Placer la préparation documentaire I0 **avant G0**.
2. À G0 : exiger ordre transactionnel, analyse des contraintes L1, contrats et scénarios précis.
3. Après I2/I3 : exiger les preuves réelles de capacité, poisoning et contraintes.
4. Après I4/I5 : exiger SQL, barrières et mesures des parcours.
5. Conserver ces preuves comme conditions de G1/G2 et de clôture.

**Nouvel arbitrage humain :** aucun changement de protocole proposé ; validation du calendrier corrigé nécessaire dans le plan.

**Preuve de correction.** Une table attribuant à chaque obligation son artefact préalable, son étape productrice et son gate consommateur, sans cycle.

---

### L2-POSTAUD-002 — MAJOR — Manifeste R6 préalable absent et allocation trop agrégée

**Sources.** R6-04, consolidation §11 ; plan L2 §§12 et 14 ; SPEC-10, contribution L2 et amendements finaux. [R6 et traçabilité][conso], [matrice L2][plan12], [SPEC-10][spec10].

**Contrat manquant.** L’arbre Git audité ne contient aucun manifeste L2 correspondant à l’exigence : variante, portée, réel/fixture, méthode, oracle indépendant, gate, report nominatif et lot receveur.

La matrice du plan préserve les grandes plages d’identifiants, mais ne décompose pas notamment :

- les frontières distinctes d’E02-F ;
- les formes de `requestId` incorrect d’E02-I ;
- les deux résultats READ de C06-O ;
- les variantes métier et techniques des compléments CON-05 ;
- les essais spécifiques de capacité, routage, observation et parcours ajoutés par R1–R10.

Les références `Rn-Txx` ne pointent vers aucun inventaire concret de scénarios numérotés dans le dossier L2.

**Scénario concret.** Une méthode couvre un rollback après mutation, mais aucune après terminalisation ou publication. Une ligne agrégée « E02-F couvert » ne permet pas de distinguer ces absences. R6 interdit justement de conclure PASS dans cette situation ; le dossier manque encore de l’instrument permettant de contrôler cette interdiction.

**Invariant concerné.** Exhaustivité et traçabilité des preuves ; séparation contribution technique, variante normative et exigence globale.

**Correction minimale.** Produire le manifeste documentaire avant G0, avec identifiants de méthodes prévus si le code n’existe pas encore. Décomposer les variantes composées et inscrire chaque report dans un lot nommé. Remplacer `Rn-Txx` par des références résolues ou une liste explicite.

**Nouvel arbitrage humain :** aucun assouplissement requis. Toute suppression d’une obligation demanderait, elle, un arbitrage.

**Preuve de correction.** Contrôle exhaustif SPEC-10 + R1–R10 → manifeste, sans obligation orpheline ; ultérieurement manifeste → méthodes exécutées → XML et oracles.

---

### L2-POSTAUD-003 — MINOR — Résidu de permission après fermeture dans le paragraphe R1 ratifié

**Source.** Plan L2 §8, paragraphe « Historique R1 ». Il énonce correctement l’appel avant fermeture, puis conserve une permission technique du garde fermé et une alternative de raccord. [Plan §8][plan8].

**Scénario concret.** Un implémenteur conserve une autorisation spéciale après fermeture parce qu’elle reste décrite dans le paragraphe contenant la décision ratifiée. Cette permission est inutile pour l’option B retenue et agrandit la surface du garde.

**Invariant concerné.** Irréversibilité de fermeture et simplicité des capacités.

**Qualification.** La consolidation et le renvoi final donnent le bon ordre : ce résidu ne constitue pas une seconde option normative active.

**Correction minimale.** Ne conserver dans la prescription actuelle que l’appel avant fermeture ; laisser les alternatives dans l’historique de revue.

**Nouvel arbitrage humain :** non.

**Preuve.** Lecture du protocole actuel ne laissant aucun chemin autorisé de suppression après fermeture ; test négatif post-fermeture maintenu.

---

### L2-POSTAUD-004 — MINOR — « Manager PRIMARY » dans l’oracle générique CON-06

**Source.** SPEC-10, CON-06, oracle structurant 1, face à la matrice d’autorités et aux oracles 4 et 6. [CON-06][con06].

**Prescriptions en tension.** L’oracle générique exige un même manager « PRIMARY », alors que les matérialisations doivent utiliser leur autorité READ.

**Scénario concret.** Le banc READ est soit rejeté pour ne pas utiliser PRIMARY, soit raccordé à celui-ci pour satisfaire littéralement l’oracle.

**Invariant concerné.** Colocalisation des effets, slots et transaction dans l’autorité correcte.

**Correction minimale.** Écrire « manager de l’autorité concernée, WRITE ou READ », puis garder les exigences d’identité physique des ressources.

**Nouvel arbitrage humain :** non ; l’intention est déjà explicite ailleurs.

**Preuve.** Cas C06-J/K et négatif de composition croisée, avec observation des connexions et bases réellement utilisées.

---

### L2-POSTAUD-005 — MINOR — Statut conceptuel PERF-01 incohérent dans le catalogue transversal

**Sources.** SPEC-07, ligne PERF-01 ; SPEC-09 §§3–5 ; SPEC-10, matrice unique. [SPEC-07][spec07], [SPEC-09][spec09], [SPEC-10][spec10].

**Conflit.** SPEC-07 présente encore PERF-01 comme ouvert avec références à définir ; SPEC-09 contient les objectifs conceptuellement validés et SPEC-10 distingue acquisition conceptuelle et qualification future.

**Scénario concret.** Un futur plan considère les budgets comme à réarbitrer plutôt que comme objectifs à mesurer, ou confond leur validation conceptuelle avec une mesure acquise.

**Invariant concerné.** Séparation décision, objectif et certification.

**Correction minimale.** Aligner le catalogue sur « acquis conceptuellement, à qualifier », avec références actuelles.

**Nouvel arbitrage humain :** non ; aucun budget à changer.

**Preuve.** Concordance des trois documents sans nouveau PASS de performance. Ce défaut ne bloque pas les protocoles L2.

---

### L2-POSTAUD-006 — OBSERVATION — Décomposer davantage les essais de renouvellement et d’équité

**Sources.** R10-04 à R10-10 ; plan L2 §§10–12, campagnes P4/P7. [R10][conso], [plan §10][plan10].

**Scénario à rendre explicite.**

1. F1 conserve le verrou final de S1 pendant une transaction longue.
2. Un lot de renouvellement attend S1 sur sa connexion réservée.
3. S2 doit être renouvelé avant son échéance.
4. Si le lot attend sans borne sur S1, S2 peut expirer malgré des threads et connexions distincts des transformations.

Ce scénario ne démontre pas un défaut du protocole : les renouvellements courts et bornés sont déjà exigés. Il distingue cependant une preuve de réservation de ressources d’une preuve de progression du renouvellement.

Ajouter aussi le cas `M_W=1`, deux Consumptions toujours alimentées : après chaque restitution de capacité, la rotation doit offrir une occasion à la suivante.

**Invariant concerné.** Renouvellements indépendants et occasions répétées de progression.

**Correction minimale.** Ajouter ces stimuli et leurs oracles au manifeste P7 ; conserver une solution locale simple d’attente bornée ou d’évitement des lignes occupées.

**Nouvel arbitrage humain :** non, tant que les garanties existantes sont conservées.

**Preuve.** Barrières PostgreSQL, attentes observées, échéances DB et acquisitions successives ; aucun succès déduit de simples sleeps.

## 7.4 Matrice de certification

La matrice suivante distingue les obligations effectivement réalisables en L2 et celles nécessitant les composants futurs. Elle constitue une **allocation d’audit à inscrire dans le manifeste**, sans modifier silencieusement le programme.

**État actuel : aucune variante L2 exécutée, aucun PASS L2 attribué.**

Conventions :

- **W** : pilote Command Pot réel, autorité Identity explicitement fixture.
- **R** : Engine et persistance READ réels du banc ; transformation technique.
- **F** : source ou composant de fixture.
- Une preuve indiquée comme possible demeure conditionnée à son exécution et à sa provenance.
- Toutes les assertions durables supposent une lecture PostgreSQL indépendante après résolution ; les courses exigent des barrières observables.

### CON-01 — Discovery

Source : [SPEC-10, CON-01][con01].

| Variante | Obligation et preuve attendue | Contribution L2 | Report / limite |
|---|---|---|---|
| A | Routage exact d’événements entrelacés ; ensembles et K par partition | R, source POT réelle | Autres producteurs réels L3/L5 |
| B | Fenêtre hors partition : K avance sans slot local | R | Aucun report générique |
| C | Frontières de fenêtres bornées sans omission | R ; ensemble complet attendu | Aucun report générique |
| D | Publication suspendue puis commit **et** rollback ; aucune borne prématurée | W+R, barrières compteur/publication | Autres producteurs L3/L5 |
| E | Deux Discoveries sur le même K ; une progression cohérente | R, transactions concurrentes | Aucun report générique |
| F | Takeover entre préparation et enrôlement ; ancien propriétaire refusé | W/R | Aucun report générique |
| G | SoftCap coupe un lot ; K reste avant la première référence non enrôlée | R, capacité nulle et partielle | Aucun report générique |
| H | Slot terminé supprimé, sans réenrôlement sous K | R, effet durable vérifié | Aucun report générique |
| I | Deux familles burnables ; PENDING avec/sans slot, TERMINAL et concurrence | COMMAND W ; IDENTITY_REQUEST F | Adaptateur/source Identity réels L3 |
| J | Slot existant expiré ou temporisé, softCap plein ; aucun contournement | W/F | Variante Identity réelle L3 |
| K | Nouvelle Consumption, K absent, scan indépendant | R avec UUID distinct | Rejeu métier réel selon L4/L6 |
| L | Références seulement ; SQL, lignes, cycles et mémoire mesurés | W/R | Aucun SLO global déduit |

### CON-02 — Atomicité de découverte et preuve dérivée

Source : [SPEC-10, CON-02][con02].

| Variante | Obligation et preuve attendue | Contribution L2 | Report / limite |
|---|---|---|---|
| A | Faute avant premier insert : K inchangé, zéro slot | R | Aucun |
| B | Faute après premier insert : aucun lot partiel | R | Aucun |
| C | Faute après tous les slots, avant K : rollback complet | R | Aucun |
| D | Faute après K, avant commit : ni K ni slots durables | R | Aucun |
| E | Commit d’enrôlement réussi, ACK perdu : ensemble exact sans doublon | R, injection réseau | Aucun |
| F | Fenêtre sans événement local : progression atomique sans slot | R | Aucun |
| G | K préparatoire périmé après concurrent gagnant | R, deux transactions | Aucun |
| H | Ancien propriétaire après takeover ne commet rien | R | Aucun |
| I | Effet et suppression interrompus avant commit : tous deux annulés | R | Projection métier réelle L4 |
| J | Effet committé, ACK perdu : absence du slot et effet unique | R | Projection métier réelle L4 |
| K | Redémarrage Discovery/Engine : seuls les incomplets subsistent | R | Reconstruction métier complète L6 |
| L | Refus/détection des resets partiels slots, K, effets et réutilisation incompatible d’ID | Négatifs W/R possibles | Reset READ total réel : L6 |

**Précision :** C02-L demande des négatifs de reset partiel ; il ne faut pas imposer toute REC-01 pour prouver ces négatifs en L2. Le plan mêle actuellement cette contribution et la reconstruction métier future.

### CON-03 — Propriété, claims et temps

Source : [SPEC-10, CON-03][con03].

| Variante | Obligation et preuve attendue | Contribution L2 |
|---|---|---|
| A | Après transfert, B ne reprend pas le claim valide de A ; A peut finir | W/R |
| B | Ancien ownerToken refusé pour Discovery **et** acquisition | W/R |
| C | Claim de A renouvelable pendant le lease de B | W/R, opérations concurrentes |
| D | Expiration de partition sans reprise anticipée du claim | W/R |
| E | Renouvellement tardif contre takeover ; aucune résurrection | W/R, verrou et heure DB |
| F | Expiration après verrou final valide ; takeover attend la résolution | W/R |
| G | Expiration pendant attente ; horloge fraîche après verrou | W/R, attente réellement observée |
| H | Token remplacé avant retour de A ; aucun effet/libération tardif | W/R |
| I | B perd ensuite sa partition mais conserve son claim valide | W/R |
| J | Expiration, takeover, crash **et** commit incertain | W/R, injections distinctes |
| K | Plusieurs partitions et Workers ; propriété effective et routage stable | W/R |
| L | Traces répétées, attentes, expirations et takeovers correctement mesurés | W/R |

Toutes ces variantes génériques sont obligatoires en L2. **CON-03 est l’exigence la plus directement susceptible d’une preuve complète pendant L2**, sous réserve de l’exécution intégrale et des recoupements CON-04/06.

### CON-04 — Finalisation unique

Source : [SPEC-10, CON-04][con04].

| Variante | Obligation et preuve attendue | Contribution L2 | Report / limite |
|---|---|---|---|
| A | Deux finaliseurs, même token : un seul effet | W/R | Aucun générique |
| B | Nouveau token avant retour de A : A n’écrit ni ne supprime | W/R | Aucun |
| C | Takeover après expiration post-verrou : attente puis absence du slot | W/R | Aucun |
| D | Exception après effet partiel : rollback total | W/R | Variantes métier futures |
| E | Commit réussi, ACK perdu, redémarrage : zéro republication | W/R | Aucun générique |
| F | Rollback avec réponse inconnue : reprise conditionnelle | W/R | Aucun |
| G | Ancien token ne renouvelle ni ne libère le remplaçant | W/R | Aucun |
| H | Claim valide finalisable après transfert de partition | W/R | Aucun |
| I | Deux exécutions burnables ; fautes sur statut **et** résultat | COMMAND W ; Identity F | Identity réel L3 |
| J | Consumptions READ concurrentes, mêmes/différentes versions | Indépendance technique R | Idempotence et versions métier L4 |
| K | Courses répétées, takeover, rollback et interruption | W/R | Capacités absentes non certifiées |
| L | Inventaire de toutes suppressions et administrations du périmètre | W/R, guards et négatifs | Extension aux futurs writers L3–L6 |

### CON-05 — Retry, budget et quarantaine

Source : [SPEC-10, CON-05 et compléments][con05].

| Variante | Obligation et preuve attendue | Contribution L2 | Report / limite |
|---|---|---|---|
| A | Faute chargement/préparation ; cession sans seconde exécution | W/R | Aucun générique |
| B | Faute finale ; rollback puis cession séparée | W/R | Aucun |
| C | Collision CAS comptée ; nouvelle préparation, jamais rejet automatique | W | Autorités futures L3 |
| D | Obsolescence réellement constatée : rejet terminal normal | W | Identity réel L3 |
| E | Faits rechargés, intention immuable entre acquisitions | W/R | Aucun générique |
| F | Refus avant retryAfter ; acquisition à échéance admissible | W/R, heure DB | Aucun |
| G | Cession tardive de A après reprise B sans altérer B | W/R | Aucun |
| H | Cession committée avec ACK perdu **et** cession rollbackée | W/R, deux fautes distinctes | Aucun |
| I | Plusieurs échecs puis succès ; tokens et exécutions distincts | W/R | Aucun |
| J | Erreur persistante ; aucun faux terminal, diagnostic et cadence | W/R | Indisponibilité DB : budget seulement si cession durable possible |
| K | Crash après rollback, avant cession : budget non supposé | W/R | Aucun |
| L | Perte de partition avant cession, claim encore valide | W/R | Aucun |
| M | Instrumentation différenciant tentatives, retries, techniques et métier | W/R | E2E HTTP L5/L6 |

Les compléments ne peuvent pas être réduits à une seule ligne « Q » :

| Complément normatif | Preuve L2 attendue | Report |
|---|---|---|
| N−1 échecs puis succès | Compteur exact, temporisation, effet unique | Aucun générique |
| Nᵉ échec | Même slot QUARANTINED, source non terminalisée | Aucun |
| Crash/expiration sans échec enregistré | Compteur inchangé | Aucun |
| Ancien token | Aucun second incrément ni transition | Aucun |
| Quarantaine persistante | Restart, temps et déploiement sans réactivation | Aucun |
| Exclusion softCap | Admissions/progrès ailleurs malgré quarantaines | Aucun |
| Discovery en quarantaine | Aucun réenrôlement burnable ou non-burnable | Aucun |
| Non-purge | Inventaire et refus des suppressions d’incomplets | Extension aux futurs outils |
| Administration ciblée | CAS exact, compteur zéro, claim/retry effacés | Aucun générique |
| Deux administrateurs | Une transition effective, aucun effet métier | Aucun |
| Réactivation hors quarantaine | Refus cohérent avec le CAS retenu | Aucun |
| Réactivation sous softCap plein | Transition permise, dépassement observé, nouvelles admissions suspendues | Aucun |
| Succès/échec après réactivation | Suppression atomique au succès, conservation au rollback | Aucun générique |
| Même agrégat et autres Consumptions | Progrès technique autonome | Projections métier L4 |
| V en quarantaine, V+1 projetée ; reprise V tardive | Analogue technique seulement | CurrentPot/Balance/Visibility/Binding : L4 |
| HTTP 200/404/503 des versions concernées | Non certifiable par le banc | L5, intégration L6 |
| N/retryDelay prospectifs | Anciennes échéances inchangées, aucune réactivation implicite, paire cohérente par cession | Aucun générique |

### CON-06 — Autorités physiques

Source : [SPEC-10, CON-06][con06].

| Variante | Obligation et preuve attendue | Contribution L2 | Report / limite |
|---|---|---|---|
| A | Pot, événements, résultat, statut et slot sur une unité JPA/JDBC | W, identités physiques | Aucun |
| B | Registration entière, deux événements et complétion | F seulement | L3 |
| C | Trois matérialisations POT autonomes en READ | Analogue R | L4 |
| D | Balance **et** Visibility avec leur slot, succès/rollback | Analogue R | L4 |
| E | Erreur JDBC interceptée : commit impossible | W/R | Tous writers du périmètre |
| F | JDBC réussi puis erreur JPA interceptée | W/R selon writers réels | Aucun faux PASS si chemin absent |
| G | JPA réussi puis erreur JDBC interceptée | W/R selon writers réels | Même réserve |
| H | Managers/DataSources incompatibles refusés | W/R | Aucun |
| I | REQUIRES_NEW dans effet **ou suppression** détecté | W/R | Aucun |
| J | Worker mixte WRITE puis READ : deux unités indépendantes | W+R, composition réelle | Aucun |
| K | Lecture WRITE préparatoire, K/slots atomiques READ | R sur source POT réelle | Aucun |
| L | Commit incertain, expiration post-verrou et transfert | W/R | Aucun générique |
| M | Tous points d’entrée, tests, mono-runtime et compositions concernées | Inventaire L2 réel | Actualiser à chaque futur writer |
| N | CurrentPot/SubobjectVersion/slot atomiques | Analogue R uniquement | L4 |
| O | CommandResult **et** IdentityRequestResult avec leur slot | Analogue R uniquement | L4, source Identity L3 |
| P | Deux projections métier indépendantes d’une même publication | Indépendance technique R | L4 ; Invitation L5 |

### EVT-01 — Séquences denses

Source : [SPEC-10, EVT-01][evt01].

| Variante | Obligation et preuve attendue | Contribution L2 | Report / limite |
|---|---|---|---|
| A | Publication séquentielle, positions stables et uniques | POT/COMMAND_COMPLETION W | Autres producteurs L3/L5 |
| B | T1 non résolue bloque une position supérieure committable | W, barrières et attente compteur | Autres producteurs |
| C | Rollback T1 réutilise la prochaine position sans trou | W | Autres producteurs |
| D | Lot de deux événements Identity consécutifs, sans intercalation | F technique | Producteur Registration L3 |
| E | No-op/rejet Command **et** Identity : seule complétion avance | Command W ; Identity F | L3 |
| F | Mutation+complétion puis rollback : événements et compteurs annulés | W ; autres F | L3/L5 |
| G | Transactions mixtes commit/rollback sur cinq familles | Mécanisme F possible | Producteurs réels L3/L5 |
| H | Charge, lenteur, aborts et reconnexions répétées | W sur deux familles | Extension réelle L3/L5/L6 |

Les trois ordres de verrouillage doivent rester distincts dans le manifeste : POT→COMMAND_COMPLETION, INVITATION→COMMAND_COMPLETION, IDENTITY→IDENTITY_COMPLETION. L2 ne prouve réellement que le premier avec son producteur livré.

### EVT-02 — Unité terminale

Source : [SPEC-10, EVT-02][evt02].

| Variante | Obligation et preuve attendue | Contribution L2 | Report / limite |
|---|---|---|---|
| A | Création/modification Pot, dont multi-cibles, une version et un événement | W | Invitation réelle L5 |
| B | Succès sans effet : aucune version/mutation, complétion unique | W | Aucun |
| C | Rejet obsolète : aucune mutation, résultat et complétion uniques | W | Aucun |
| D | Registration : User+EUI, deux événements, résultat et complétion | F seulement | L3 |
| E | Link gagnant, Unlink gagnant **et** rejet Identity | F seulement | L3 |
| F | Rollback à chacune des six frontières énumérées par la SPEC | W ; injections séparées | Identity L3, Invitation L5 |
| G | Commit réussi/ACK perdu, distingué du rollback/ACK perdu | W, proxy et SQL indépendant | Autres producteurs |
| H | Deux Workers après expiration/reprise : un résultat et une complétion | W | Identity réel L3 |
| I | requestId absent, inexistant **et** incorrect malgré existence | W, contraintes et correspondance métier | Writers Identity/Invitation L3/L5 |
| J | Crash après admission PENDING, avant finalisation | Admission interne et Engine W | Admission HTTP L5 |

Pour E02-F, les frontières sont : avant première écriture, après mutation, après événement de mutation, après résultat/terminalisation, après complétion et avant commit. Les variantes R1 — appel absent, double, hors unité, après fermeture, erreur DELETE — s’y ajoutent.

### EVT-03 — Catalogue et indépendance

Source : [SPEC-10, EVT-03][evt03].

| Variante | Obligation et preuve attendue | Contribution L2 | Report / limite |
|---|---|---|---|
| A | Sept familles, cinq séquences et destinataires exacts | Contrôle statique du catalogue | Ne livre pas les transformations |
| B | Trois slots des trois Consumptions réelles d’un POT_EVENT | Analogue technique possible | L4 |
| C | Discovery répétée, slot idempotent | R | Aucun générique |
| D | Discoveries concurrentes, unicité du couple | R | Aucun |
| E | Échec d’enrôlement : aucun K incomplet | R | Aucun |
| F | Commit inconnu : relecture durable sans nouvel ID arbitraire | R | Aucun |
| G | Une Consumption réussit, deux échouent | R technique | Effets métier L4 |
| H | Replay indépendant, nouvel ID et destination maîtrisée | R technique | Destination métier L4/L6 |
| I | Reconstruction à blanc, mêmes IDs et reset cohérent | Banc technique | READ complet L6 |
| J | V/V+1 hors ordre, versions complètes et pointeurs monotones | Analogue insuffisant | L4 |

### Verdicts globaux admissibles

| Exigence | Perspective de clôture L2 |
|---|---|
| CON-01 | Contribution générique possible ; couverture Identity réelle à réserver |
| CON-02 | Contribution technique forte possible ; aucune certification REC-01 globale |
| CON-03 | Couverture générique complète possible après toutes les preuves |
| CON-04 | Contribution W/R ; variantes de projections réelles réservées |
| CON-05 | Contribution retry/quarantaine ; variantes métier/HTTP réservées |
| CON-06 | INCONCLUSIVE global tant que les autorités et projections réelles manquent |
| EVT-01 | INCONCLUSIVE global tant que les cinq producteurs ne sont pas prouvés |
| EVT-02 | INCONCLUSIVE global tant qu’Identity/Invitation manquent |
| EVT-03 | INCONCLUSIVE global tant que les matérialisations réelles manquent |

Une violation démontrée impose **FAIL**, même si d’autres variantes restent INCONCLUSIVE. Aucun ancien PASS L1, fixture ou succès Maven ne remplit une ligne L2 absente.

## 7.5 Frontières architecturales et simplicité

### Responsabilités

La séparation proposée est satisfaisante :

| Responsable | Responsabilité conservée |
|---|---|
| Domaine | Valeurs, décisions et invariants purs |
| Consumption | Préparation, protections/interprétations métier, effets, résultats, publication et observations |
| Engine | Claims, transactions, capacité, cession, budget et décisions de reprise |
| Adaptateurs | SQL, ressources physiques, contraintes et protections |
| Composition | Identités, familles, autorités, paramètres et injection explicite |

Le plan interdit explicitement l’appel inchangé du propriétaire L1 sous une transaction Engine. Cette interdiction est essentielle : **extraire la transformation et adapter les guards est nécessaire**, pas seulement envelopper `ExecutePotCommand.execute()`. [Plan §§3–4][plan].

`SlotCompletion` et `RecoveryReadContext` sont justifiés par des frontières précises. Ils doivent rester des capacités étroites, sans introduire un framework de phases ou exposer Connection/EntityManager au domaine.

### État durable et algorithmes

Les quatre tables ont chacune un invariant justificateur :

- slot : existence du travail incomplet et droit individuel ;
- ownership : autorité de Discovery/nouvelle acquisition ;
- checkpoint : couverture des sources non-burnables ;
- contrat de routage : compatibilité durable des interprétations de partition.

Aucun besoin démontré d’ajouter DONE, UNKNOWN SQL, journal de tentatives, compteur softCap, réservation de fenêtre, scheduler distribué ou dépendance entre slots.

Les parcours circulaires volatils sont proportionnés. Leur correction doit être obtenue par navigation indexable, retour de tour et hypothèses explicites, sans ajouter une file durable pour compenser une requête SQL insuffisante.

### Conservation des garanties L1

Le plan §13 couvre correctement les familles de garanties à transférer :

| Acquis | Obligation L2 |
|---|---|
| Une acquisition/une tentative | Retirer toute seconde cession ou boucle de retry dans Consumption |
| Claim/Identity/Pot | Même ordre ; témoin négatif INVALID_BINDING sans accès Pot |
| Intention et observedVersion | Rechargées sans modification, préparation jetée après collision |
| Unité physique et xid8 | Même ressource effective, epoch complet, poison irréversible |
| Contraintes et publication | Contraintes métier avant fermeture ; contrôles terminaux différés |
| ChangedComponents | Égalité exhaustive sur les huit tables à la candidate |
| Recovery | Zéro effet supposé et zéro budget supposé |
| L1-F2 | Préconditions locales/groupées, fermeture exacte puis insertion atomique |
| Audit historique | Opération explicite, sans retour des chaînes dans le chemin courant |

Les manifestes et index F/F2 rendent ces garanties traçables. Ils ne prouvent pas leur conservation après changement d’owner et de slot ; P8 doit les rejouer sur le protocole canonique. [Contrat L1-C][l1c], [matrice F2][f2matrix], [clôture F2][f2report].

### Faisabilité I0–I7 et nature des choix restants

Après correction de la dépendance de G0, I1→I7 forme une progression raisonnable : structure, owner physique, lifecycle, Discovery, boucles intégrées, campagnes, audit.

| Nature | Éléments | Traitement |
|---|---|---|
| Paramètres opérationnels | P, softCap, tailles de lots/fenêtres, M_C/M_W, N/délai, leases, polling, timeouts, réserves de renouvellement | Manifeste de campagne avant usage |
| Détails techniques | Noms et signatures finales, index, forme SQL, organisation des ports, pool réservé ou quota équivalent | Déterminables dans l’étape concernée, avec contrats et preuves |
| Décisions de protocole | Ordre R1, préfixe R2, contrat R3, tour R4, cession R5, observations R9, parcours R10 | Déjà arbitrées ; application obligatoire |
| Changements exigeant nouvel arbitrage | Purge, collisions gratuites, mutation de routage sous K, suppression après fermeture, transaction Consumption indépendante | Aucun de ces changements n’est nécessaire à ce stade |

La table de vérité des observations de recovery, les règles d’avancement des curseurs et le protocole administratif doivent être écrits précisément pendant la préparation de leurs étapes. Leur signature Java ou leur forme SQL ne constitue pas, à elle seule, un nouvel arbitrage architectural.

## 7.6 Actions correctives priorisées

| Priorité | Action | Nature | Arbitrage humain nouveau |
|---|---|---|---|
| **Avant G0** | Corriger le cycle G0/I0 et répartir preuves documentaires/exécutées entre gates | L2-POSTAUD-001, documentaire | Non ; validation du plan corrigé |
| **Avant G0** | Produire le manifeste exhaustif, sous-variantes et reports nominatifs compris | L2-POSTAUD-002, documentaire | Non |
| **Avant G0** | Retirer la permission résiduelle du garde fermé dans le protocole R1 actuel | L2-POSTAUD-003, documentaire | Non |
| **Avant G0** | Rattacher la validation/audit L1-F2 exigée par le programme, ou maintenir explicitement le prérequis ouvert | Prérequis existant | Décision humaine déjà requise, non obtenue par cet audit L2 |
| **Avant l’étape concernée** | Compléter le dictionnaire courant des quatre tables et formaliser le bootstrap administratif | I1, R3/R7 | Non |
| **Avant l’étape concernée** | Fixer les guards, contraintes terminales canoniques et observations cohérentes | I2, R1/R9 | Non si invariants conservés |
| **Avant l’étape concernée** | Décrire navigation, traitement des clés sautées et fin de tour | I3–I5, R4/R10 | Non |
| **Avant campagne** | Figer paramètres, méthodes, ancres Maven, environnements et oracles | P1–P8 | Non |
| **Avant campagne** | Inclure attente de renouvellement sur slot verrouillé et capacité M_W=1 | L2-POSTAUD-006 | Non |
| **Avant campagne** | Corriger l’oracle « PRIMARY » générique | L2-POSTAUD-004 | Non |
| **Non bloquant L2** | Aligner le statut PERF-01 | L2-POSTAUD-005 | Non |

Le manifeste doit aussi distinguer clairement les négatifs techniques certifiables en L2 des reconstructions et projections métier futures. Cela évite d’élargir artificiellement L2 à L4/L6.

### Vérifications réellement effectuées

- **Slices de cet audit :** NONE, lecture documentaire et inspection statique.
- **Commandes exécutées :** inspections Git (`branch`, `rev-parse`, `status`, `remote`, `worktree`, `log`, `cat-file`), lectures `cat`, recherches `rg`/`rg --files`, inventaire `ls`, calcul SHA-256 documentaire avec Python standard.
- **Autres contrôles :** lectures GitHub au SHA fixé, inspection de l’arbre, contrôle en mémoire des liens/ancrages, consultation de la documentation PostgreSQL.
- **Franchissement applicatif :** aucun.
- **Maven/tests/PostgreSQL/migrations :** aucune exécution.
- **Gate global :** non requis pour cet audit, non exécuté.
- **Full reactor :** non requis pour cet audit, non exécuté.
- **Fichiers/commits :** aucune modification, aucun commit.
- **État Git final observé :** même branche, même HEAD et même inventaire de modifications locales qu’au départ.

Les futures slices COMMAND, EVENT et PROJECTION, ainsi que les gates d’architecture et de clôture majeure, restent des obligations prospectives du plan.

## 7.7 Verdict final

**FAIL — Contradiction de séquencement des gates et préparation normative de certification incomplète avant autorisation d’implémentation.**

Les décisions R1–R10 sont conservables. Leur interaction ne révèle pas, dans cet audit, de défaut démontré de perte, double effet ou impossibilité transactionnelle. Le blocage établi est documentaire : G0 doit cesser de dépendre de preuves produites après son autorisation, et le manifeste R6 doit matérialiser la couverture exhaustive annoncée.

Après ces corrections, le dossier pourra être réexaminé pour soumission de G0 à validation humaine. **Cet audit n’approuve pas G0 et ne certifie aucun code, aucune transaction PostgreSQL ni exigence SPEC-10.**

[canon]: https://github.com/kartaguez/pocoma/blob/c67fcfe7e245b0f9cb8f77af0cc6444f7c0cb43c/docs/v3/V3_Canon_and_Roadmap.md
[registre]: https://github.com/kartaguez/pocoma/blob/c67fcfe7e245b0f9cb8f77af0cc6444f7c0cb43c/docs/v3/L2_Decision_Register.md
[conso]: https://github.com/kartaguez/pocoma/blob/c67fcfe7e245b0f9cb8f77af0cc6444f7c0cb43c/docs/v3/L2_R1_R10_Normative_Consolidation.md
[conso12]: https://github.com/kartaguez/pocoma/blob/c67fcfe7e245b0f9cb8f77af0cc6444f7c0cb43c/docs/v3/L2_R1_R10_Normative_Consolidation.md#12-revue-de-cohérence-documentaire--points-bloquants-avant-g0
[plan]: https://github.com/kartaguez/pocoma/blob/c67fcfe7e245b0f9cb8f77af0cc6444f7c0cb43c/docs/v3/L2_Design_and_Verification_Plan.md
[plan8]: https://github.com/kartaguez/pocoma/blob/c67fcfe7e245b0f9cb8f77af0cc6444f7c0cb43c/docs/v3/L2_Design_and_Verification_Plan.md#8-acquisition-et-transaction-finale--point-critique-r1
[plan10]: https://github.com/kartaguez/pocoma/blob/c67fcfe7e245b0f9cb8f77af0cc6444f7c0cb43c/docs/v3/L2_Design_and_Verification_Plan.md#10-polling-capacité-et-équité
[plan12]: https://github.com/kartaguez/pocoma/blob/c67fcfe7e245b0f9cb8f77af0cc6444f7c0cb43c/docs/v3/L2_Design_and_Verification_Plan.md#12-matrice-de-couverture-normative-et-campagnes
[plan14]: https://github.com/kartaguez/pocoma/blob/c67fcfe7e245b0f9cb8f77af0cc6444f7c0cb43c/docs/v3/L2_Design_and_Verification_Plan.md#14-vérification-future-gates-provenance-et-critères-de-sortie
[spec05]: https://github.com/kartaguez/pocoma/blob/c67fcfe7e245b0f9cb8f77af0cc6444f7c0cb43c/docs/v3/SPEC-05_Consumption_Discovery.md
[spec07]: https://github.com/kartaguez/pocoma/blob/c67fcfe7e245b0f9cb8f77af0cc6444f7c0cb43c/docs/v3/SPEC-07_Transverse_Requirements.md
[spec09]: https://github.com/kartaguez/pocoma/blob/c67fcfe7e245b0f9cb8f77af0cc6444f7c0cb43c/docs/v3/SPEC-09_Performance.md
[spec10]: https://github.com/kartaguez/pocoma/blob/c67fcfe7e245b0f9cb8f77af0cc6444f7c0cb43c/docs/v3/SPEC-10_Test_Certification.md
[con01]: https://github.com/kartaguez/pocoma/blob/c67fcfe7e245b0f9cb8f77af0cc6444f7c0cb43c/docs/v3/SPEC-10_Test_Certification.md#con-01--protocole-et-oracles
[con02]: https://github.com/kartaguez/pocoma/blob/c67fcfe7e245b0f9cb8f77af0cc6444f7c0cb43c/docs/v3/SPEC-10_Test_Certification.md#con-02--protocole-et-oracles
[con03]: https://github.com/kartaguez/pocoma/blob/c67fcfe7e245b0f9cb8f77af0cc6444f7c0cb43c/docs/v3/SPEC-10_Test_Certification.md#con-03--protocole-et-oracles
[con04]: https://github.com/kartaguez/pocoma/blob/c67fcfe7e245b0f9cb8f77af0cc6444f7c0cb43c/docs/v3/SPEC-10_Test_Certification.md#con-04--protocole-et-oracles
[con05]: https://github.com/kartaguez/pocoma/blob/c67fcfe7e245b0f9cb8f77af0cc6444f7c0cb43c/docs/v3/SPEC-10_Test_Certification.md#con-05--protocole-et-oracles
[con06]: https://github.com/kartaguez/pocoma/blob/c67fcfe7e245b0f9cb8f77af0cc6444f7c0cb43c/docs/v3/SPEC-10_Test_Certification.md#con-06--protocole-et-oracles
[evt01]: https://github.com/kartaguez/pocoma/blob/c67fcfe7e245b0f9cb8f77af0cc6444f7c0cb43c/docs/v3/SPEC-10_Test_Certification.md#evt-01--protocole-et-oracles
[evt02]: https://github.com/kartaguez/pocoma/blob/c67fcfe7e245b0f9cb8f77af0cc6444f7c0cb43c/docs/v3/SPEC-10_Test_Certification.md#evt-02--protocole-et-oracles
[evt03]: https://github.com/kartaguez/pocoma/blob/c67fcfe7e245b0f9cb8f77af0cc6444f7c0cb43c/docs/v3/SPEC-10_Test_Certification.md#evt-03--protocole-et-oracles
[l1c]: https://github.com/kartaguez/pocoma/blob/c67fcfe7e245b0f9cb8f77af0cc6444f7c0cb43c/docs/v3/L1_C_Transaction_Contract.md
[f2matrix]: https://github.com/kartaguez/pocoma/blob/c67fcfe7e245b0f9cb8f77af0cc6444f7c0cb43c/docs/v3/L1_F2_Invariant_Matrix.md
[f2report]: https://github.com/kartaguez/pocoma/blob/c67fcfe7e245b0f9cb8f77af0cc6444f7c0cb43c/docs/v3/L1_F2_Closure_Report.md
[store]: https://github.com/kartaguez/pocoma/blob/c67fcfe7e245b0f9cb8f77af0cc6444f7c0cb43c/app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/JdbcPotCommandStore.java#L154-L234
[owner]: https://github.com/kartaguez/pocoma/blob/c67fcfe7e245b0f9cb8f77af0cc6444f7c0cb43c/app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/PotCommandJdbcTransactions.java
[ddl]: https://github.com/kartaguez/pocoma/blob/c67fcfe7e245b0f9cb8f77af0cc6444f7c0cb43c/app/infra-persistence-primary-jpa/src/main/resources/db/v3-pot-execution/migration/V2__command_execution.sql#L69-L109
[pglocks]: https://www.postgresql.org/docs/17/explicit-locking.html
[pgiso]: https://www.postgresql.org/docs/17/transaction-iso.html
[pgselect]: https://www.postgresql.org/docs/17/sql-select.html
