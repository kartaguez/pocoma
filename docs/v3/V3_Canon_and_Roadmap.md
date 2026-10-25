# Pocoma V3 — Canon et feuille de route

**Statut actuel : V3-CADRE validé ; GO conceptuel V3-6 du 2026-10-24 (baseline `ddca2827dac7cf13f8b07f620f0cd3b3cb392196`). L0 réalisé : preuves techniques obligatoires PASS, couverture globale INCONCLUSIVE, revue indépendante acceptable pour la poursuite. L1-A et L1-B implémentés, PASS technique borné, GO de poursuite après revue indépendante confirmé par le responsable. L1-C autorisé et implémenté, PASS technique borné ; couverture complète consommateurs/architecture INCONCLUSIVE (skip exploratoire V2) ; audit indépendant C requis. L1-D/E non autorisés ; aucune certification globale V3 ni activation.**
**Branche directrice :** `feat/v3-architecture`, créée depuis `feat/consumption-transaction-protocol`.  
**Date d'ouverture :** 2026-10-22.  
**Nature :** cadrage et consolidation documentaire, sans certification ni activation.

## 1. Objectif et règle d'or

**Construire une V3 plus simple dans ses structures, ses concepts et ses algorithmes.**

Partir du domaine et des usages, non des mécanismes historiques. Toute couche, abstraction, donnée persistée, conversion, état, mécanisme de synchronisation ou algorithme supplémentaire doit être justifié par un invariant concret ou un besoin fonctionnel démontré. Préférer un petit nombre de concepts expressifs à une généricité préventive. Pouvoir expliquer les principaux flux de bout en bout sans ouvrir du SQL.

La simplicité ne dispense pas de correction : préserver, ou remplacer par une garantie explicite équivalente, l'atomicité, les invariants métier, le fencing, la cohérence du séquencement, l'absence de perte et les reprises après incident. Les preuves qui ne correspondent plus au modèle sont remplacées, non simplement abandonnées.

### Résultat attendu

1. Un **domaine** cohérent : objets, sous-objets, identités, frontières d'agrégats et versions définis.
2. Des **événements et consommables** qui représentent proprement les changements du domaine et la complétion des demandes externes, dans les deux royaumes.
3. Un **modèle READ** répondant aux besoins sans projections ou jointures inutiles.
4. Un **moteur Consumption** qui exécute les transformations nécessaires avec une grammaire applicative explicite, une Discovery efficiente et des garanties transactionnelles.
5. Un parcours V3 bout en bout testé et documenté, sans chemins historiques redondants.

## 2. Règles de conduite et compatibilité

- **Aucun système V1/V2 à maintenir en production.** Les contrats Java, API non déployées, schémas, migrations de développement, modules et données de test peuvent être rompus ou reconstruits. Il n'existe aucune obligation d'exécuter simultanément V2 et V3.
- **Pas de compatibilité fictive.** Ne pas ajouter de codecs, adaptateurs, flags, migration à chaud, double écriture ou protection d'API obsolète au seul motif que le code historique existe. Préserver les données réellement nécessaires uniquement si leur nécessité est démontrée.
- **Tests existants :** inventorier la propriété que chacun prouve ; maintenir le test si son contrat survit, le remplacer si le contrat change, le supprimer explicitement si la propriété n'existe plus. Ne jamais masquer une régression d'invariant sous une rupture volontaire. Pendant les branches de refonte, les anciens tests peuvent temporairement échouer, avec liste des ruptures intentionnelles et des garanties non encore rétablies. À l'intégration V3, les suites applicables doivent passer.
- **Documentation existante :** demeure historique, pas normative pour V3. Marquer les plans A2/A2-1 précédents comme **supplantés pour leur stratégie de migration** ; préserver les décisions conceptuelles encore valides. Ne pas réécrire les rapports PASS anciens ni présenter leurs preuves comme des tests V3.
- **Acquis techniques :** traiter 4A–4E, CAS/claims/transactions/rollback comme preuves et savoir-faire réutilisables, non comme formes obligatoires de tables et ports. Toute modification de ces mécanismes exige des preuves au moins équivalentes.
- **Pas de mise en production implicite :** aucun résultat documentaire ni gate local ne certifie l'activation.
- **Après chaque étape :** revenir à CE document, mettre à jour statut, décisions prises, livrables, preuves, écarts, questions résiduelles, dépendances et prochaine action ; lier les SHAs. Ce document reste l'unique autorité pour la progression V3.

## 3. Spécifications normatives (ARB-08)

Les identifiants **SPEC-01 à SPEC-10** désignent des documents, **pas des jalons d'implémentation**. Les références V3-0 à V3-7 désignent la feuille de route. Les décisions normatives complètes résident dans chaque SPEC ; ce canon conserve exclusivement les statuts, responsabilités et dépendances. L'historique des propositions et amendements reste consultable dans Git et dans le [rapport d'audit](V3_Independent_Conceptual_Audit_Report.md).

| Document | Autorité normative | Statut |
|---|---|---|
| [SPEC-01](SPEC-01_Pot_Domain.md) | Domaine Pot, versions, suppressions, droits métier et invitations | Conceptuellement arbitré |
| [SPEC-02](SPEC-02_Identity_Domain.md) | Identity, Binding, Registration/Link/Unlink et consentement | Conceptuellement arbitré |
| [SPEC-03](SPEC-03_Events_Requests_Consumables.md) | Demandes, sept familles, événements, provenance et séquences | Conceptuellement arbitré |
| [SPEC-04](SPEC-04_Read_Projections.md) | Neuf projections logiques, versions historiques, balances, résultats READ | Conceptuellement arbitré |
| [SPEC-05](SPEC-05_Consumption_Discovery.md) | Discovery, Engine, slots, transactions, trois Consumptions directes de POT_EVENT et MaterializeInvitation, quarantaine | Conceptuellement arbitré |
| [SPEC-06](SPEC-06_Supra_Contracts.md) | Admission, interfaces READ, autorisation, HTTP et protection | Conceptuellement arbitré |
| [SPEC-07](SPEC-07_Transverse_Requirements.md) | Catalogue transversal des exigences actives | Conceptuellement arbitré |
| [SPEC-08](SPEC-08_Observability.md) | Contrat de mesure O-01 et instrumentation | Conceptuellement arbitré |
| [SPEC-09](SPEC-09_Performance.md) | Budgets et oracle C-03 | Conceptuellement arbitré |
| [SPEC-10](SPEC-10_Test_Certification.md) | Matrice et protocoles des preuves à exécuter | Spécifiés, non exécutés |

## 4. Feuille de route et dépendances

| Jalon | Finalité | Statut |
|---|---|---|
| V3-0 à V3-5 | Concevoir et mettre en cohérence le corpus conceptuel | Conception arbitrée ; consolidation documentaire AUD-V3-012 |
| V3-CADRE | Autoriser conceptuellement l'entrée en réalisation après cohérence globale | GO confirmé le 2026-10-24 après contre-audits ; autorise V3-6, sans certification |
| V3-6 | Réaliser domaine, requêtes, événements, Consumptions, READ, Supras, instrumentation et tests | **En cours** : L0 réalisé et revue indépendante acceptable ; L1-A/B acceptables pour poursuivre selon confirmation humaine ; C implémenté, méthodes internes obligatoires PASS, publié au checkpoint d’audit indépendant ; D/E non démarrés (suivi §§10–11) |
| V3-7 | Exécuter les preuves PostgreSQL, Bruno et k6 ; mesurer les budgets ; certifier ou refuser l'activation | Non commencé |

Aucun prérequis de compatibilité avec une V1/V2 en production. Les invariants historiques utiles doivent être prouvés à nouveau sous les contrats V3 ; les PASS anciens ne valent pas PASS V3. Un changement fonctionnel structurant pendant V3-6 nécessite nouvel arbitrage documenté.

## 5. Décisions transversales résumées

- **Domaine :** créateur immuable, droits Shareholder indépendants, suppressions Pot/Shareholder réservées au créateur, suppression Shareholder refusée si Expenses actives la référencent. Expense référence ShareholderId, sans dépendre du lien identitaire.
- **Identity :** EUI 0..1 PocomaUser, User 0..N EUI ; Binding relationnel, token explicite pour les demandes hors Registration. La lecture de son propre CurrentBinding ne requiert aucun token entrant. Link headless fondé sur une preuve externe d'autorisation/consentement transactionnel, produit fournisseur non imposé ; aucun index inverse Identity requis.
- **Requests/Events :** sept familles, demandes burnables uniquement Command et IdentityRequest ; requesterEui immuable à l'admission, événements de complétion minimaux. Résultats READ réservés à l'EUI initiatrice, HTTP 200/404/401/503 selon SPEC-06 ; aucune expiration fonctionnelle en V3.
- **READ :** PotVersion immuable complet en références à chaque version, CurrentPot pointeur, soldes complets par version active. Trois Consumptions indépendantes de POT_EVENT, sans tâches intermédiaires. Navigation non autorisante. Droits historiques à V avec Binding actif. DeletePot produit la version terminale D mais pas Balance(D) ; courant et D non consultables, V<D consultables selon les droits.
- **Consumption :** fenêtre Discovery committée et bornée, atomie checkpoint/slots, validation par claim, aucun graphe de dépendances. Échecs techniques constatés comptés jusqu'au seuil N, slot QUARANTINED persistant et exclu du softCap, réactivation administrative explicite avec compteur remis à zéro ; jamais de purge avant succès.
- **Certification :** S-02 = manifeste complet d'Expenses exclusivement ; SEC-01 = autorisation historique. PRJ-04 est retiré sans réutilisation. C-01 consentement Link, C-02 maintenance READ exclusive, C-03 lecture complète autorisée W≥V pour Pot actif ou oracle interne PotVersion(D) pour DeletePot. Les scénarios A10-A..K et A11-A..N restent à exécuter.

## 6. Statut des points d'audit

AUD-V3-001 à AUD-V3-011 : arbitrages conceptuels consignés dans les SPEC ; AUD-V3-006 rejeté dans sa proposition de slot synchrone ; certifications PostgreSQL, sécurité et charge restent à réaliser. AUD-V3-012 : **CLOS DOCUMENTAIREMENT** — refonte normative et renumérotation SPEC-01..10 réalisées ; inventaire GitHub, 27 exigences actives, conservation des familles E03/RCV01/F01/A10/A11, numérotation SPEC-10 et liens relatifs `docs/v3` vérifiés. La certification V3 demeure non exécutée. **AUD-V3-013 : CLOS DOCUMENTAIREMENT** — matérialisation sélective et groupée des SubobjectVersion absentes de READ depuis WRITE@V, PotVersion complète et immuable sans delta/chaînage, recalcul intégral des balances actives avec accès groupés, manifeste HTTP complet et ordonné avec détails par lots. **Second audit contradictoire (`GEM-V3-001` à `GEM-V3-010`, [rapport](V3_Second_Independent_Architectural_Audit_Report.md)) : CLOS DOCUMENTAIREMENT** — alignement des règles de rattachement/détachement `ShareholderDetails.userId` et de la vue READ d'invitation (`SPEC-01`, `SPEC-04`), du contrat `Unlink(E1 -> E2)` avec `bindingToken` initiateur `E1` et verrouillage canonique `(issuer, subject)` (`SPEC-02`, `SPEC-05`, `SPEC-10`), du filtrage `EXTERNAL_USER_IDENTITY` et du `resultingPotVersion` sans effet (`SPEC-03`, `SPEC-04`), et purge des références résiduelles aux anciennes tâches READ dans `SPEC-08`, `SPEC-09` et `SPEC-10`. Aucun mécanisme ni budget nouveau lors de la clôture GEM ; l'amendement Invitation ultérieur ajoute INVITATION_EVENT, CurrentInvitation et MaterializeInvitation ; mesures physiques et PERF-01/C-03 restent À PROUVER en V3-7.

## 7. Réserves et clôture

La valeur opérationnelle N, les budgets physiques de protection, les plans SQL, l'autorité de consentement effective, la protection du secret récupérable des invitations et les performances Grand Pot doivent être qualifiés à la réalisation. Les questions de révocation anticipée des invitations restent explicitement ouvertes. Toute évolution de contrat doit être arbitrée avant implémentation. La consolidation conceptuelle n'a réalisé aucun DDL, code, test ni benchmark V3 ; le socle technique L0 livré ensuite est décrit au §10.

## 8. Arbitrage Invitation CQRS — 2026-10-24

`CreateInvitation` produit en cas de succès une mutation WRITE d'invitation et `INVITATION_EVENT` indépendamment de `COMMAND_COMPLETION_EVENT`. `MaterializeInvitation` matérialise `CurrentInvitation` en READ ; seul le créateur authentifié retrouve le lien via `creationRequestId`. `CommandResult` ne transporte pas le lien. L'invité peut consulter depuis READ uniquement les Shareholders libres, avant de demander l'auto-rattachement en WRITE, dont la réussite produit un `POT_EVENT`. L'ensemble reste éventuellement cohérent sans synchronisation READ→WRITE.

Cet amendement porte les inventaires à sept familles de consommables (cinq événementielles), cinq séquences événementielles, neuf projections logiques, neuf Consumptions de référence (deux WRITE et sept READ) et 28 exigences actives (incluant `INV-01`). Les inventaires normatifs de ce canon et des SPEC (notamment `SPEC-07` et `SPEC-10`) reflètent intégralement les nouveaux dénombrements après la passe de cohérence finale (`CAUD-V3-001`). Contrat explicité dans SPEC-01/03/04/05/06/07 et oracles INV-01 de SPEC-10 ; aucun code ni certification réalisés. La suspension de V3-6 est levée par décision explicite du 2026-10-24 ; démarrage autorisé sans certification ni activation.


## 9. Décision de reprise V3-6 — 2026-10-24

**Décision : GO V3-6 sans réserve conceptuelle.** La revue contradictoire ChatGPT a clos ses trois réserves (invitation/restitution READ, cardinalité du rattachement et couverture de tests). Le contre-audit Gemini au SHA `a937199d9a0b88410ce94d832086da7e09dbe5c8` a conclu au GO conceptuel et sa correction documentaire `CAUD-V3-001` a été poussée au SHA `ddca2827dac7cf13f8b07f620f0cd3b3cb392196` ; ce SHA est la **baseline documentaire du GO**.

La reprise est autorisée pour l'**implémentation de V3-6** sous les SPEC-01 à SPEC-10 consolidées. Elle ne vaut **ni approbation de code, ni preuve PostgreSQL, ni réussite de tests V3, ni certification, ni déploiement ou activation**. Toute divergence de conception importante découverte pendant la réalisation doit être arbitrée et documentée avant de modifier la norme. Les oracles INV-01, les exigences transversales et les gates prévus restent à exécuter selon le plan.

## 10. V3-6 — Socle L0, 2026-10-24

Après inventaire contradictoire L0-A et validation explicite du responsable du projet, réalisation bornée de L0-B à L0-F : gardes ArchUnit, banc PostgreSQL/Flyway isolé, transactions JPA/JDBC, intervalles génériques, composition Spring minimale et collecte traçable. Référence de départ : `4405b9c338ab8d5e9a3d27be33ccea06bd1daff2` ; réalisation et vérification dans `/private/tmp/pocoma-v3-l0`. Commit et push du lot sur `feat/v3-architecture` autorisés explicitement le 2026-10-25, après vérification ; les autres worktrees et leurs modifications sont préservés.

Livrables : [inventaire et réutilisation](L0_Technical_Inventory.md), [graphe Maven](L0_Maven_Graph.json), [plan et périmètre de vérification](L0_Verification_Plan.md), [manifeste des preuves](L0_Proof_Manifest.json), [rapport d'exécution et limites](L0_Execution_Report.md).

Les 19 méthodes Java obligatoires de L0 passent (15 PostgreSQL/composition, 4 ArchUnit), ainsi que 7 tests du collecteur. La preuve locale FOUNDATION inclut également les 4 tests H2 préexistants. Le gate global `./mvnw -pl architecture-tests -am test` réussit côté Maven avec 1 407 tests déclarés, zéro échec/erreur et un scénario exploratoire historique ignoré. La collecte conserve donc **INCONCLUSIVE pour la couverture globale**, avec les preuves obligatoires L0 toutes PASS ; aucun skip ni PASS historique n'est requalifié en certification V3.

**Clôture documentaire du 2026-10-25 — référence L0 : `7c8470a568ece88c4e6652d080dfb026f57681e1`.** Revue indépendante : **L0 acceptable pour la poursuite, sans défaut bloquant démontré**. Revue statique et documentaire, sans reproduction des tests PostgreSQL ; elle ne constitue aucun nouveau résultat de preuve. Les réserves I-01/A-01/A-02 et leurs échéances figurent dans le [rapport](L0_Execution_Report.md#independent-review-and-documentary-closure--2026-10-25) et le suivi ci-dessous. Aucune réécriture de L0 demandée.

Aucun code de production, domaine Pot/Identity, moteur Consumption, DDL de production, endpoint, parcours Bruno ou k6 ajouté par L0. Aucun réacteur complet ni activation. Les preuves fonctionnelles et de certification SPEC-10 restent à produire sur les composants réels. Prochaine étape : L1-A, avec déclaration de vérification propre ; aucune réalisation L1 incluse dans cette clôture documentaire.

## 11. V3-6 — Passage vers L1

**L1 : domaine Pot sous [SPEC-01](SPEC-01_Pot_Domain.md), A/B implémentés et techniquement PASS dans leur périmètre obligatoire ; GO de poursuite après revue indépendante confirmé. C seul autorisé le 2026-10-25, implémenté et techniquement PASS dans son périmètre interne.** Le découpage suivant est un plan de réalisation et de suivi, pas un second canon normatif. Les SPEC restent la seule autorité normative fonctionnelle ; [SPEC-10](SPEC-10_Test_Certification.md) régit les preuves applicables. Chaque lot déclarera son périmètre de vérification avant implémentation selon la [politique du réacteur](../testing/Reactor_Verification_Policy.md).

**L1-A à L1-E — plan approuvé, puis autorisation explicite A/B du 2026-10-25 :** [plan de référence](L1_Design_and_Verification_Plan.md), référence approuvée `8221692287420cd69feebd170df7c9ea6c279fdd`. [Rapport A/B et preuves](L1_AB_Execution_Report.md) : 14 méthodes domaine A, 19 PostgreSQL B, 5 ArchUnit obligatoires PASS ; gates Maven BUILD SUCCESS, couverture complète consommateurs/architecture INCONCLUSIVE pour un skip exploratoire V2 conservé. Dernières entrées exécutables testées : `887c24f3f266304e46c281cc61ba170f35d4de9a`. Aucun PASS global V3. Le GO humain ultérieur confirme la revue indépendante A/B acceptable pour poursuivre vers C ; il ne constitue pas une nouvelle exécution de leurs tests.

**N-01 — RÉSOLU DOCUMENTAIREMENT, 2026-10-25** (référence `9a349bbcd86d900b34e927cc29e16dd172ee7ab4`) : SPEC-01 fait autorité sur DeleteShareholder. [SPEC-10, DOM-01/02](SPEC-10_Test_Certification.md#3-domaine) corrigée : suppression historisée du Header seul (D01-M), autorisation créateur et références actives/historiques (D02-Q/R/S), variante concurrente réservée aux preuves PostgreSQL L1-C. Correspondances et limites unitaires actualisées dans le [plan L1-A, §10](L1_Design_and_Verification_Plan.md#10-matrice-junit-proposée-et-critères-probatoires). Aucun nouvel oracle exécuté ou satisfait ; aucun code ajouté. N-01 reste acquis ; ses preuves restent à exécuter.

**C-01 et C-02 — arbitrages de conception L1-A VALIDÉS, consolidés le 2026-10-25** (référence `df113c8fea3443fe83f428c5e5eef1767ee42d03`) : C-01 retient `Versioned<T>(long localVersion, long activeFrom, T value)`, valeur métier immuable et début d'applicabilité global, sans métadonnées de persistance ; C-02 retient `TransitionResult<T>` scellé en `Rejected`, `Unchanged`, `Changed<T>`, avec changements métier typés et une candidate globale unique distincte du commit. Contrats et invariants détaillés dans le [plan L1-A, C-01](L1_Design_and_Verification_Plan.md#c-01--composantes-versionnées--contrat-conceptuel-validé) et [C-02](L1_Design_and_Verification_Plan.md#c-02--résultats-de-transition--contrat-conceptuel-validé). Ces repères sont distincts des protocoles de certification homonymes de SPEC-10. N-01/C-01/C-02 ne sont pas rouverts sans contradiction démontrée ; les SPEC gardent leur autorité normative. L0 reste clôturé et accepté ; Les décisions C-01/C-02 sont implémentées dans A ; les preuves d’exécution ultérieures sont distinguées de cette consolidation dans le rapport A/B.

| Lot | Réalisation prévue | Statut / obligation de clôture |
|---|---|---|
| [L1-A](L1_Design_and_Verification_Plan.md#16-l1-a--noyau-pur-et-preuves-unitaires) | Valeurs rationnelles exactes, huit composantes, transitions pures et preuves unitaires | IMPLÉMENTÉ / PASS technique borné ; A-01 démontrée, gates exécutés ; revue indépendante acceptable pour poursuite confirmée par GO humain |
| [L1-B](L1_Design_and_Verification_Plan.md#17-l1-b--persistance-histoire-et-faits-autoritatifs) | Persistance V3, révisions, reconstruction directe Pot@V et faits autoritatifs sélectifs | IMPLÉMENTÉ / PASS technique borné ; A+B et preuves conservés ; GO de poursuite après revue indépendante confirmé par le responsable |
| [L1-C](L1_Design_and_Verification_Plan.md#18-l1-c--mutation-durable-cas-et-reprises) | Mutation durable, protection des faits, CAS, transaction unique et reprises complètes | IMPLÉMENTÉ, PASS technique borné ; couverture complète consommateurs/architecture INCONCLUSIVE (skip exploratoire V2) ; [rapport C](L1_C_Execution_Report.md) et [contrat réalisé](L1_C_Transaction_Contract.md) ; **arrêt audit indépendant obligatoire avant D** |
| [L1-D](L1_Design_and_Verification_Plan.md#19-l1-d--droits-références-financières-et-balances-exactes) | Matrice des droits, références financières, résolution des poids à V et calcul complet exact | NON IMPLÉMENTÉ / NON EXÉCUTÉ ; prolonge les invariants protégés dès A/C, sans seconde politique ni projection READ |
| [L1-E](L1_Design_and_Verification_Plan.md#20-l1-e--intégration-interne-et-dossier-de-certification-l1) | Intégration et certification bornée du noyau L1, dossier de preuves et provenance A-02 | NON IMPLÉMENTÉ / NON EXÉCUTÉ ; architecture et full reactor au jalon L1, publier D/E et leurs preuves, **audit indépendant final avant toute clôture définitive** |

**I-01 — suivi transversal :** inventaires A/B complétés (valeurs/composantes, ports, mappings SQL, migration, graphe et consommateurs), voir [rapport A/B](L1_AB_Execution_Report.md) et [contrat de persistance](L1_B_Persistence_Contract.md). Suivi global ouvert pour les futurs writers C et les lots READ/Consumption. **A-01 : clôture technique A/B démontrée et revue acceptable pour poursuite ; gardes réexécutées pour C. A-02 : amorcé avec contrôles automatiques d’entrées et publication au checkpoint, non clos pour L1 complet ; clôture obligatoire avant E terminé.**

**Arbitrages consolidés, décisions validées :**

- C-01/C-02 conservés sans réouverture : valeurs/version locale/activeFrom global immuables ; résultats typés non vides et candidate unique distincte du commit durable.
- Weight est un rationnel exact strictement positif ; Fraction canonique et entiers arbitrairement grands, aucun arrondi ni plafond métier. Le spécifique optionnel remplace le défaut ; aucun ajustement des autres poids ni normalisation de leur somme stockée. La proportion utilise effectiveWeight/somme ; saisie décimale ou fractionnaire exacte aux frontières. Autorité : [SPEC-01 §4](SPEC-01_Pot_Domain.md#4-finances-et-résolution-à-v).
- Faits métier typés, minimaux, sélectifs et autoritatifs ; une absence n’est recevable qu’après recherche exhaustive de son périmètre. B fournit la vérité durable ; C garantit sa cohérence jusqu’au commit ou reprend entièrement. Aucun contexte universel, framework de faits ou verrou SQL dans le domaine.
- Transitions pures dans `domain-pot`, regroupées par responsabilité métier ; orchestration dans `engine-write-pot`, lectures/écritures/histoire/CAS/transactions dans les adaptateurs ; injection explicite par constructeur aux frontières. Conserver les modules existants, rationaliser selon besoin démontré, sans moteur générique ni Service par commande.
- Simplicité comme critère d’acceptation : Java lisible, fonctions courtes, invariants protégés, objets immuables, dépendances explicites, aucun artefact/couche/duplication sans nécessité, aucune compatibilité V2 artificielle et tests à oracles indépendants.

**Clarifications normatives strictement nécessaires :** SPEC-01 §4 précise la capacité rationnelle et la sémantique des poids. SPEC-10 D03-D corrige une contradiction de formulation : un défaut changé peut modifier le dénominateur et donc les proportions de participants à poids spécifique constant ; leurs poids stockés ne changent pas. P02-E précise qu’un rattachement userId seul n’a pas d’effet numérique ; les références financières restent des ShareholderId. Les cas rationnels >long, conversion exacte, poids non ajustés et anciennes balances intactes complètent D03 sans changer ses identifiants. Aucun test ni nouvelle preuve acquis par ces précisions.

**Gates et limites :** COMMAND primaire et PROJECTION pour les contrats partagés A/B/D ; C/E ajoutent EVENT pour les interfaces de publication. Architecture globale pour A-01 et les frontières B/C, puis clôture E ; full reactor planifié seulement au jalon majeur E, sauf restructuration importante imposant un gate préalable. Bootstrap CURRENT V3 isolé, aucune baseline V2 TRUSTED supposée ni campagne historique séparée par défaut. Déclarations et commandes dans le [plan §24](L1_Design_and_Verification_Plan.md#24-gates-maven-prévus-et-déclarations-de-portée). La [couverture SPEC-10](L1_Design_and_Verification_Plan.md#21-couverture-spec-10-et-limites-de-certification) distingue preuves internes L1 et compléments Identity/Consumption/READ/Supra/V3-7 ; aucune certification V3 globale ni activation déduite de L1.

**Campagne après autorisation explicite uniquement :** commits distincts et traçables par lot, corrections ordinaires autonomes dans les segments autorisés ; arrêt après B, après C et au final E. Publier commits et preuves avant chaque arrêt ; attendre audit indépendant et correction/revérification des défauts bloquants avant reprise. Un checkpoint ne peut être levé par autoévaluation ni par absence de réponse. A-01 pendant A ; [A-02 automatique code testé/commit publié](L1_Design_and_Verification_Plan.md#22-preuves-commits-et-a-02) avant clôture L1. Toute contradiction normative, décision fonctionnelle non arbitrée, refonte importante ou gate obligatoire non résolu impose l’arrêt.

**Prochain événement : audit indépendant L1-C après ses gates et sa publication. STOP au checkpoint C.** L’autorisation humaine C confirme le GO indépendant A/B ; D/E restent non autorisés et non commencés. Corriger et revérifier tout défaut bloquant avant nouvelle revue et autorisation D/E. Les contrats normatifs ne sont pas modifiés par cette livraison ; la qualification du fournisseur de consentement, les invitations, READ et les budgets physiques restent aux jalons compétents.


## 12. L1-C — Autorisation et réalisation bornée, 2026-10-25

Le responsable confirme le GO après revue indépendante A/B et autorise C exclusivement depuis `56d658f9fd5313db2744fa11ff36699f4376aacb`. Réalisation initiale `be4c0d56103e55f45fd70e92818d947a51b374b9`, correction de fixture `2494a4e17a212281ed7d8602608b3d6b8ab11bcf`, renforcement final de l’oracle temporel `2825f7b4c90e6b0c4b064e1166de6dd9a93f2bcf`. [Rapport C](L1_C_Execution_Report.md), [protocole effectivement réalisé](L1_C_Transaction_Contract.md), [manifeste](L1_Proof_Manifest.json). Les trois gates finaux sur `2825f7b4c90e6b0c4b064e1166de6dd9a93f2bcf` terminent BUILD SUCCESS : persistance 886/0/0/0, consommateurs COMMAND+EVENT+PROJECTION 1270/0/0/1, architecture globale 1475/0/0/1. Les 72 méthodes obligatoires du gate architecture passent, dont 34 nouvelles C. Couverture globale complète INCONCLUSIVE pour le seul skip exploratoire V2 ; aucun scénario obligatoire C ignoré. Archives et provenance dans le rapport.

Orchestration dans engine-write-pot, transitions A inchangées, faits B sélectifs, autorité EUI distincte et Pot protégés jusqu’au commit, CAS, terminalisation/résultat/événements/slot atomiques, reprise sous nouveau claim et observation initiale, quarantaines et récupération après vrais commits réseau incertains. Raccord applicatif et composition explicite seulement ; autorité Identity et acquisition initiale restent des fixtures PostgreSQL nommées, sans certification de bout en bout. Aucune SPEC ni arbitrage C-01/C-02/N-01 modifié.

COMMAND primaire, EVENT/PROJECTION secondaires, gate architecture global requis. Full reactor non requis pour C, réservé au jalon E selon le plan. Aucune réalisation D/E, READ, HTTP, cycle Identity ou Discovery générique. L1-C ne sera définitivement accepté que par audit indépendant.

Publication code/rapport/archives : `0a6742944ac9eb5f1452d9a590b798e52ce732dc`, avance rapide sans force ; tête distante vérifiée et correspondance avec le code testé `2825f7b4c90e6b0c4b064e1166de6dd9a93f2bcf` PASS, divergence 0/0. Le suivi documentaire du constat et son contrôle final sont distingués des entrées exécutables dans le [rapport C](L1_C_Execution_Report.md). **STOP C ; audit indépendant avant toute autorisation D/E.**
