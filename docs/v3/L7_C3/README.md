# L7-C3 — Synthèse exécutive

> [!NOTE]
> Ce dossier conserve les décisions, les qualifications et les preuves du chantier. La documentation courante en anglais est accessible depuis l'[architecture des disponibilités READ](../../architecture/read-availability.md).

**Les huit familles de disponibilité sont raccordées aux sept matérialiseurs READ existants.** C3-R1 fixe le parcours des registres courants par position transactionnelle. Le lecteur avance selon la page et sa tête committée dans une même photographie. Une clé remplacée après cette lecture reçoit une nouvelle position.

Quatre familles historiques et quatre familles courantes utilisent des compteurs indépendants. Une table historique porte l’UUID d’événement, l’identité logique et une position dense par famille. Un registre courant garde une ligne par clé et une position de dernière avance, attribuée transactionnellement par famille. Les compteurs restent dans READ. Cette séparation donne aux futurs lecteurs un curseur indépendant et préserve la coalescence des courants.

Les stores valident l’effet et sa disponibilité attendue dans le protocole L2, puis attribuent les positions dans un suffixe PostgreSQL borné. Le verrou du slot et l’ordre de fermeture existants permettent de conserver `ConsumptionEngine` et `SlotCompletion`. Les publications de `PotVersion` et `CurrentPot` peuvent être atomiques entre elles. `PotBalance` et la visibilité progressent indépendamment. À la suppression, le manifeste terminal et les marqueurs courants restent découvrables. L’absence de balance en D reste intentionnelle.

**Le risque principal d’exploitation est le passage d’un READ V4 déjà peuplé.** Ses checkpoints couvrent des effets sans publication C3. La voie proposée réutilise le reset exclusif L6/I2 et le rejeu de WRITE, avec V5, validation des privilèges et oracle C3 avant READY. Un futur lecteur externe devra resynchroniser ses curseurs après un reset intégral. Le mécanisme actuel ne permet pas de détecter tous les anciens curseurs en comparant leurs seules positions.

**Le risque technique à mesurer est le surcoût du suffixe.** Un compteur par famille sérialise les commits de publication sur cette famille. Les contrôles SQL, index et mises à jour du registre s’ajoutent au WAL et à la latence, notamment sur les chemins de stockage allégés de C2. Les comparaisons C2/C3 couvrent les profils Medium, Large, XLARGE et le seuil D5.

## Dossier

- [A — Audit contradictoire et preuves du dépôt](audit.md)
- [B — Architecture technique](architecture.md)
- [C — Incréments et matrice de qualification](plan.md)
- [D — Registre des arbitrages](arbitrages.md)
- [Rapport d’exécution du pilote CommandResult](pilot-report.md)
- [Rapport de clôture technique](technical-closure.md)
- [Audit de consolidation et attribution des preuves](consolidation.md)

**Généralisation autorisée.** C3-R1 et C3-R2 restent ratifiés. La décision humaine du chantier I1–I6 autorise la généralisation malgré la qualification de performance incomplète du pilote. Les résultats du pilote restent attribués à sa V5 isolée. La V5 canonique lie les huit familles aux sept matérialiseurs existants et s’installe par reconstruction exclusive sur un READ déjà peuplé. [Rapport de généralisation](generalization-report.md)

**Qualification de la généralisation.** Les preuves PostgreSQL et CLI couvrent le reset puis la reprise. La gate composée a couvert 1 169 tests après reprise et correction de deux fixtures Monolith. Le rapport propose la clôture technique C3 sur les profils mesurés. [Résultats et réserves](generalization-report.md#verdict-proposé)

**Clôture de consolidation.** `L7_C3_CONSOLIDATION_PASS_WITH_RESERVATIONS`. La qualification couvre 43 projets et 1 170 tests réussis sur les sources corrigées, avec reprise non filtrée de Monolith et de l’architecture. Les réserves de performance restent ouvertes. [Décision et preuves](consolidation.md#qualification-finale)

**État du pilote.** Le verdict historique reste `L7_C3_PILOT_GATE_INCOMPLETE`. La généralisation dispose de sa propre qualification, et la mise en production exige une décision distincte. [Rapport du pilote](pilot-report.md#résultat-des-gates-et-décision) · [Qualification complémentaire](../L7_C3_CommandResult_Complementary_Gate_Report.md)

**État de l’audit initial.** Revue documentaire sur `feat/v3-l7` à `d7b4b6d73963cab7efeb658ef67478d52de03c32`. Vérifications exécutées via `git status`, `git rev-parse HEAD`, `git rev-list --left-right --count HEAD...@{upstream}`, `git diff --check`, `git diff --cached --check` et validation Python des liens, ancres et espaces de fin de ligne des cinq fichiers. Tranches d’exécution `NONE`. Aucun franchissement de tranche. Gate globale et réacteur complet sans objet pour cette revue. Ce mandat initial n’a exécuté aucune qualification Maven, PostgreSQL, reconstruction ou performance. Les tranches et gates de l’implémentation future figurent dans le [plan](plan.md#périmètre-de-vérification-déclaré).
