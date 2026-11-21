# L7-C3 — Registre des arbitrages

Les éléments confirmés ci-dessous proviennent du cadrage ou des contrats existants. C3-R1 et C3-R2 sont ratifiés sur la branche feat/v3-l7. La décision humaine I1–I6 autorise la généralisation à partir du pilote CommandResult. [Audit contradictoire](audit.md) · [Architecture](architecture.md) · [Plan](plan.md)

## Décisions confirmées

| Sujet | Décision et preuve |
| --- | --- |
| Périmètre | C3 s’arrête à la publication durable en READ. Les consommateurs de niveau 2, leurs effets, les abonnements SSE, Redis/Valkey et le routage relèvent d’autres travaux. |
| Indépendance | Garder les sept consommations, leurs UUID et le routage existant, ainsi que leurs progressions indépendantes. Seule la paire PotVersion/CurrentPot partage naturellement une tentative. [SPEC-04 §§3 et 8](../SPEC-04_Read_Projections.md) · [SPEC-05 §8](../SPEC-05_Consumption_Discovery.md#8-consumptions-directes-et-indépendantes-aud-v3-005). |
| Publication | Effet READ, suppression du slot et disponibilité ont une résolution physique unique. Garder fencing, cession et observation L2. UUID technique et position dense propres à chaque événement historique au sein de sa famille. [L2 R1, R5 et R9](../L2_R1_R10_Normative_Consolidation.md) · [SPEC-03 §5](../SPEC-03_Events_Requests_Consumables.md#5-publication-et-séquences-transactionnelles). |
| Suppression | Publier le manifeste terminal, le pointeur courant et, lorsqu’elle progresse, la visibilité. Conserver l’absence de balance terminale et l’historique des balances actives. [SPEC-04 §10](../SPEC-04_Read_Projections.md#10-deletepot-et-version-terminale). |
| C3-R1 — Découverte des courants | **Ratifié.** Compteur transactionnel par famille, registre coalescé par clé et curseur par famille et lecteur. Le contrat de pagination couvre la reprise et accepte les positions clairsemées. Mise en œuvre autorisée par la décision I1–I6 malgré la qualification de performance encore partielle du pilote. [Contrat de lecture](architecture.md#lecture-paginée-des-courants). |
| C3-R2 — Activation sur stock READ | **Ratifié.** Première activation de C3 sur une base peuplée par reconstruction complète L6/I2. Aucun rattrapage rétroactif. Le pilote reste isolé sur base jetable. |
| Reset | Appliquer le protocole exclusif L6/I2 et son oracle avant READY. Conserver les réserves de l’acceptation locale, le protocole de reprise et la séparation physique des autorités. [Clôture L6/I2 §§3 et 6](../L6_I2_Acceptance_and_Closure_Decision.md). |

## Ajustements techniques proposés

| Sujet | Défaut argumenté |
| --- | --- |
| Granularité | Quatre historiques et quatre courants. Sous-versions couvertes par l’intégrité de PotVersion. Agrégats, contenus, ensembles et nœuds restent internes. La visibilité est une disponibilité par Pot de l’évaluation courante, même à ensemble identique. |
| Clés | UUID Pot, demande, invitation ou `eui_id` existant. Version locale uniquement quand elle qualifie la projection. Pas d’UUID dans le registre courant, ni de détail métier. |
| Tables | Compteur par famille, journal historique à position intégrée, registre courant étroit. Pas de journal généraliste ou d’écriture directe dans les tableaux WRITE. Gardes de densité et rattachement des publications dans la migration READ. |
| Raccordement | Réutiliser les ports de projection. Limiter le suffixe technique à l’adaptateur READ gardé, avec des décisions rattachées à la tentative et des preuves lues dans l’autorité existante. Aucun port générique de diffusion envisagé. |
| Rejeu | Régénérer les identifiants historiques et positions à chaque reset intégral. Comparer les ensembles logiques, l’effet courant et la validité des séquences de chaque exécution. Conserver les valeurs déjà committées lors d’une reprise non destructive. |

## Alternatives et décisions humaines

| Arbitrage | Option recommandée | Option de repli et moment de décision |
| --- | --- | --- |
| Portée normative | Après accord, compléter SPEC-03/04/05/07 avec la publication de sorties READ et ses bornes de lecture, en gardant le catalogue normatif WRITE existant. | Toute exigence de continuité autonome des curseurs au-delà d’une reconstruction intégrale demande un arbitrage supplémentaire avant activation du premier lecteur externe. C3 peut livrer avec resynchronisation coordonnée. |
| Coût de sérialisation | Mesurer au pilote puis sur les références C2, conserver les budgets SPEC-09 applicables. Décider de l’acceptation sur mesures et traces. | Si la contention est sensible, réduire le travail sous verrou ou assumer un autre contrat de découverte. Partitionner les positions changerait le curseur et ne se décide pas comme optimisation locale. |

La date et la fenêtre d’exploitation restent à programmer avant la bascule. La généralisation est autorisée. Le verdict du pilote reste historique et la décision de mise en production reste distincte.

## Décisions d’implémentation I1–I6

| Sujet | Décision et preuve |
| --- | --- |
| Ordre | Socle historique, extension historique, courants, reconstruction, qualification transversale puis mesures C2 et E2E. La qualification fonctionnelle précède les campagnes de performance. |
| Migration | Les opérations installent la V5 canonique depuis `db/read/availability/migration`. La V5 du pilote reste dans son emplacement isolé et les fixtures de stockage ou HTTP qui construisent des états READ partiels conservent la V4. Le vrai runtime exige la V5 pour démarrer un matérialiseur. Cette séparation découle de l’utilisation de `db/read/migration` par les fixtures et de l’installation explicite déjà utilisée par les opérations. |
| Contrôles SQL | `read_availability_effects` est une vue invoker de clés et de versions. Les contraintes différées réutilisent cette correspondance et l’oracle compare les deux ensembles dans la même photographie. La vue ne porte aucun état supplémentaire. |
| Découverte | `read_current_availability_page` expose la tête, la position à acquitter et les marqueurs d’un snapshot d’instruction PostgreSQL. La fonction est livrée avec les droits du propriétaire pour permettre la qualification. Un futur lecteur recevra ses droits avec son intégration. |
| Reprise | La V5 est atomique via Flyway et `psql --single-transaction`. La convergence et les chemins de retour à READY vérifient son catalogue et l’intégrité de chaque famille. Le reset C3-R2 régénère les UUID et les positions et impose une resynchronisation coordonnée des futurs lecteurs. |
| Coût | Les instructions d’allocation et d’écriture demeurent distinctes. Les mesures montrent une hausse du WAL propre au chemin XLARGE mutualisé et une contention à quatre workers. Le regroupement SQL reste ouvert. [Comparaisons](generalization-report.md#comparaison-de-performance-c2--c3) |

## Réserves

- Les verdicts et limites d’exécution sont consignés dans le [rapport du pilote](pilot-report.md). Les preuves des huit familles, le rejeu d’activation sur V4 peuplé et les mesures de coût figurent dans le [rapport de généralisation](generalization-report.md).
- La disponibilité courante est exhaustive sous coalescence pour les clés stabilisées et une lecture qui progresse. Elle ne promet ni conservation des intermédiaires, ni latence en présence d’un retard de lecture continu.
- Le rebuild invalide les curseurs externes. L’autorité de maintenance survit au reset, mais l’identifiant d’opération est transitoire. La coordination existante suffit avant toute intégration externe qui l’applique. La qualification locale L6/I2 reste bornée.
- La garantie contre les écritures SQL hors protocole est celle des rôles et contraintes. L’opérateur propriétaire et le principal matérialiseur déjà autorisé à écrire les faits font partie du périmètre de confiance.
- Le registre évite l’accumulation d’un historique courant, avec un coût d’index, WAL et vacuum par avance. Le journal stable, lui, croît avec les disponibilités historiques pendant une incarnation READ.
