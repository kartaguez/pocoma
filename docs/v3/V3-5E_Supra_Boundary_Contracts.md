# Pocoma V3-5E — Contrats des Supras et bornage des frontières

**Statut : CADRAGE OUVERT — principe de séparation validé, opérations et limites à préciser.**
**Date :** 2026-10-23. **Références :** [Canon](V3_Canon_and_Roadmap.md), [READ](V3-3_Read_and_Projections.md), [Preuves](V3-5D_Test_and_Certification_Plan.md).

## 1. Décision structurante validée

Un Supra protège les frontières à partir **des paramètres présentés, de l'identité authentifiée, des attestations externes et du contrat d'interface**. Il **ne lit pas le domaine WRITE pour préjuger de l'état métier courant**.

À l'entrée, il peut refuser avant toute persistance une requête malformée, disproportionnée, non authentifiée ou dépourvue d'une preuve externe exigée. À l'exécution, le domaine et le Worker conservent l'autorité sur l'existence des objets, leurs versions, les rattachements et les transitions. Une validation anticipée ne remplace **jamais** les invariants métier.

À la sortie, le Supra impose des limites de volumes, une autorisation effective et des contrats de complétude/cohérence READ ; il peut **lire des projections READ** pour répondre aux consultations. L'interdiction de lecture WRITE ne signifie pas interdiction des contrôles transactionnels WRITE au sein du Worker, ni impossibilité de fournir des métadonnées d'admission autoritatives sur son propre protocole (idempotence / corrélation) lorsque la persistance de la demande l'exige.

## 2. Invariants à cadrer

- **Rejet d'admission** : aucune Command / IdentityRequest durable n'est créée ; erreur stable et compréhensible, distincte d'un rejet métier terminal d'une demande déjà admise.
- **Validation minimale** : formats, champs obligatoires, nombres/tailles et cohérence intrinsèque des données ; protéger aussi contre les requêtes coûteuses (parsing, profondeurs, collections, chaîne excessive).
- **Sécurité** : identité et bindingToken liés, preuves externes de consentement Link qualifiées et anti-rejeu à l'admission ; aucun contrôle READ faible utilisé pour autoriser une écriture WRITE.
- **Bornage des collections READ** : pagination et ordre stable ; borne supérieure de page imposée par le serveur, pas seulement suggérée au client. Choix du curseur et de son éventuel attachement à une version à arbitrer.
- **Lecture Pot** : distinguer en-tête/références, détails d'Expenses et balance. Une pagination inter-pages ne doit pas promettre un snapshot stable si les sous-versions nécessaires ne sont pas effectivement accessibles et conservées. La lecture `minVersion` doit rester complète et autorisée ou indisponible.
- **Protection des ressources** : limites d'entrées et de sorties, délais, quotas éventuels et comportement de surcharge proportionné au coût réel des opérations ; seuils numériques à justifier avec les profils moyens/grands de V3-5C.
- **Compatibilité avec l'évolution** : enrichir les validations structurelles sans modifier le domaine, les Consumptions et le contrat de traitement des demandes déjà admises ; ne jamais requalifier rétroactivement une demande durable à partir d'une règle nouvellement introduite.

## 3. Inventaire à constituer avant V3-CADRE

| Famille de Supra | Entrées à vérifier | Sorties à borner | Questions ouvertes |
|---|---|---|---|
| Command | authentification, type et taille des mutations, champs, nombres, IDs, clés de répétition | admission et lecture du résultat individuel | tailles par type, distinction erreurs structurelles/métier |
| Identity | Registration/Link/Unlink, EUI, autorité externe et preuve de consentement | résultats individuels, présentation de contexte Link | contexte affiché, TTL, anti-rejeu, limites de payload |
| READ Pot | Pot ciblé, identité, `minVersion`, options de réponse | en-tête, sous-objets référencés, Expenses et Balance | réponse complète ou découpage, cohérence de version et pagination |
| READ liste des Pots | binding authentifié, taille de page et éventuel curseur | liste bornée avec ordre stable | périmètre « visible », pagination et contrôle AuthZ |
| Suivi / autres collections | paramétrage de liste si endpoint réel | listes paginées uniquement si exposées | ne pas inventer d'API de liste non prévue |

## 4. Ordre de travail

1. Inventorier les opérations HTTP réellement prévues ou déjà présentes, sans les présumer publiques en V3.
2. Définir les règles d'admission minimales et les erreurs par famille, y compris coût d'entrée et idempotence.
3. Décider les formats READ collection (ordre, limite, curseur, cohérence des pages) et le découpage de la lecture Pot ; arbitrer l'interaction avec `minVersion`.
4. Définir surcharge/timeouts/autorisation, tout en évitant des contrôles métier sur WRITE.
5. Ajouter les exigences ADM-01 et SUPRA-READ-01 à la matrice de preuve V3-5D, tester séparément correction, performances et ajout ultérieur d'une validation.
6. Reprendre la revue V3-CADRE. **Ne pas déclarer le gate validé avant cette revue.**

## 5. Cas de preuve candidats

- Une entrée malformée ou hors limites est refusée immédiatement, sans demande durable, événement ou slot.
- Une entrée valide formellement mais caduque métier est admise puis rejetée terminalement par WRITE ; aucune fuite d'état métier pendant l'admission.
- L'ajout d'une borne configurable ne modifie pas les demandes anciennes ni les traitements Consumption ; les mêmes invariants domaine restent vérifiés.
- Une collection de grande taille n'est jamais renvoyée sans bornage ; parcours paginé déterministe, absence de doublons/trous dans le modèle de cohérence explicitement retenu.
- Une mutation concurrente pendant la pagination ne produit ni mélange non expliqué de versions ni promesse implicite de snapshot.
- Requête READ insuffisamment matérialisée avec `minVersion` : réponse indisponible explicite, jamais données incomplètes faussement déclarées complètes.
- Les seuils sont confrontés aux profils V3-5C (Pot moyen et grand Pot), aux coûts SQL et aux risques d'amplification.

**Aucun code, DDL ni test V3 exécuté pour cette étape.**
