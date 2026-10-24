# Pocoma V3 — Canon et feuille de route

**Statut actuel : V3-CADRE conceptuellement arbitré ; GO historique du 2026-10-23, sans autorisation actuelle de réalisation. V3-6 reste SUSPENDUE jusqu'à décision explicite après la revue contradictoire ; aucune certification ni activation.**  
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
| [SPEC-03](SPEC-03_Events_Requests_Consumables.md) | Demandes, six familles, événements, provenance et séquences | Conceptuellement arbitré |
| [SPEC-04](SPEC-04_Read_Projections.md) | Huit projections logiques, versions historiques, balances, résultats READ | Conceptuellement arbitré |
| [SPEC-05](SPEC-05_Consumption_Discovery.md) | Discovery, Engine, slots, transactions, trois Consumptions directes, quarantaine | Conceptuellement arbitré |
| [SPEC-06](SPEC-06_Supra_Contracts.md) | Admission, interfaces READ, autorisation, HTTP et protection | Conceptuellement arbitré |
| [SPEC-07](SPEC-07_Transverse_Requirements.md) | Catalogue transversal des exigences actives | Conceptuellement arbitré |
| [SPEC-08](SPEC-08_Observability.md) | Contrat de mesure O-01 et instrumentation | Conceptuellement arbitré |
| [SPEC-09](SPEC-09_Performance.md) | Budgets et oracle C-03 | Conceptuellement arbitré |
| [SPEC-10](SPEC-10_Test_Certification.md) | Matrice et protocoles des preuves à exécuter | Spécifiés, non exécutés |

## 4. Feuille de route et dépendances

| Jalon | Finalité | Statut |
|---|---|---|
| V3-0 à V3-5 | Concevoir et mettre en cohérence le corpus conceptuel | Conception arbitrée ; consolidation documentaire AUD-V3-012 |
| V3-CADRE | Autoriser conceptuellement l'entrée en réalisation après cohérence globale | GO historique du 2026-10-23 ; ne constitue pas certification et ne lève pas la suspension d'audit |
| V3-6 | Réaliser domaine, requêtes, événements, Consumptions, READ, Supras, instrumentation et tests | **Suspendu** jusqu'à clôture de l'audit et décision de reprise |
| V3-7 | Exécuter les preuves PostgreSQL, Bruno et k6 ; mesurer les budgets ; certifier ou refuser l'activation | Non commencé |

Aucun prérequis de compatibilité avec une V1/V2 en production. Les invariants historiques utiles doivent être prouvés à nouveau sous les contrats V3 ; les PASS anciens ne valent pas PASS V3. Un changement fonctionnel structurant pendant V3-6 nécessite nouvel arbitrage documenté.

## 5. Décisions transversales résumées

- **Domaine :** créateur immuable, droits Shareholder indépendants, suppressions Pot/Shareholder réservées au créateur, suppression Shareholder refusée si Expenses actives la référencent. Expense référence ShareholderId, sans dépendre du lien identitaire.
- **Identity :** EUI 0..1 PocomaUser, User 0..N EUI ; Binding relationnel, token explicite pour les demandes hors Registration. La lecture de son propre CurrentBinding ne requiert aucun token entrant. Link headless fondé sur une preuve externe d'autorisation/consentement transactionnel, produit fournisseur non imposé ; aucun index inverse Identity requis.
- **Requests/Events :** six familles, demandes burnables uniquement Command et IdentityRequest ; requesterEui immuable à l'admission, événements de complétion minimaux. Résultats READ réservés à l'EUI initiatrice, HTTP 200/404/401/503 selon SPEC-06 ; aucune expiration fonctionnelle en V3.
- **READ :** PotVersion immuable complet en références à chaque version, CurrentPot pointeur, soldes complets par version active. Trois Consumptions indépendantes de POT_EVENT, sans tâches intermédiaires. Navigation non autorisante. Droits historiques à V avec Binding actif. DeletePot produit la version terminale D mais pas Balance(D) ; courant et D non consultables, V<D consultables selon les droits.
- **Consumption :** fenêtre Discovery committée et bornée, atomie checkpoint/slots, validation par claim, aucun graphe de dépendances. Échecs techniques constatés comptés jusqu'au seuil N, slot QUARANTINED persistant et exclu du softCap, réactivation administrative explicite avec compteur remis à zéro ; jamais de purge avant succès.
- **Certification :** S-02 = manifeste complet d'Expenses exclusivement ; SEC-01 = autorisation historique. PRJ-04 est retiré sans réutilisation. C-01 consentement Link, C-02 maintenance READ exclusive, C-03 lecture complète autorisée W≥V pour Pot actif ou oracle interne PotVersion(D) pour DeletePot. Les scénarios A10-A..K et A11-A..N restent à exécuter.

## 6. Statut des points d'audit

AUD-V3-001 à AUD-V3-011 : arbitrages conceptuels consignés dans les SPEC ; AUD-V3-006 rejeté dans sa proposition de slot synchrone ; certifications PostgreSQL, sécurité et charge restent à réaliser. AUD-V3-012 : **CLOS DOCUMENTAIREMENT** — refonte normative et renumérotation SPEC-01..10 réalisées ; inventaire GitHub, 27 exigences actives, conservation des familles E03/RCV01/F01/A10/A11, numérotation SPEC-10 et liens relatifs `docs/v3` vérifiés. La certification V3 demeure non exécutée. **AUD-V3-013 : CLOS DOCUMENTAIREMENT** — matérialisation sélective et groupée des SubobjectVersion absentes de READ depuis WRITE@V, PotVersion complète et immuable sans delta/chaînage, recalcul intégral des balances actives avec accès groupés, manifeste HTTP complet et ordonné avec détails par lots. **Second audit contradictoire (`GEM-V3-001` à `GEM-V3-010`, [rapport](V3_Second_Independent_Architectural_Audit_Report.md)) : CLOS DOCUMENTAIREMENT** — alignement des règles de rattachement/détachement `ShareholderDetails.userId` et de la vue READ d'invitation (`SPEC-01`, `SPEC-04`), du contrat `Unlink(E1 -> E2)` avec `bindingToken` initiateur `E1` et verrouillage canonique `(issuer, subject)` (`SPEC-02`, `SPEC-05`, `SPEC-10`), du filtrage `EXTERNAL_USER_IDENTITY` et du `resultingPotVersion` sans effet (`SPEC-03`, `SPEC-04`), et purge des références résiduelles aux anciennes tâches READ dans `SPEC-08`, `SPEC-09` et `SPEC-10`. Aucun mécanisme ou budget nouveau ; mesures physiques et PERF-01/C-03 restent À PROUVER en V3-7.

## 7. Réserves et clôture

La valeur opérationnelle N, les budgets physiques de protection, les plans SQL, l'autorité de consentement effective et les performances Grand Pot doivent être qualifiés à la réalisation. Les questions de révocation anticipée des invitations restent explicitement ouvertes. Toute évolution de contrat doit être arbitrée avant implémentation. Aucun DDL, code, test ni benchmark V3 effectué dans le cadre de la consolidation.

## Arbitrage Invitation CQRS — 2026-10-24

`CreateInvitation` produit en cas de succès une mutation WRITE d'invitation et `INVITATION_EVENT` indépendamment de `COMMAND_COMPLETED_EVENT`. `MaterializeInvitation` matérialise `CurrentInvitation` en READ ; seul le créateur authentifié retrouve le lien via `creationRequestId`. `CommandResult` ne transporte pas le lien. L'invité peut consulter depuis READ uniquement les Shareholders libres, avant de demander l'auto-rattachement en WRITE, dont la réussite produit un `POT_EVENT`. L'ensemble reste éventuellement cohérent sans synchronisation READ→WRITE.

Cet amendement porte les inventaires à sept familles de consommables (cinq événementielles), cinq séquences événementielles, neuf projections logiques et une Consumption supplémentaire. Les dénombrements antérieurs du canon sont historiques et supplantés. Contrat explicité dans SPEC-01/03/04/05/06 et oracles INV-01 de SPEC-10 ; aucun code ni certification réalisés. V3-6 reste suspendue jusqu'à décision explicite.

