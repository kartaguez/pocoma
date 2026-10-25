# L1-E — Intégration interne et checkpoint final d'audit L1

Mandat humain : campagne autonome D puis E depuis `9dfe5a8d55d95bba29bb6a07e5c387c4ae8b0164`, GO après revues indépendantes A/B/C confirmé. Aucun lot suivant autorisé. Le présent dossier propose un statut technique ; il ne remplace pas l'audit indépendant final L1.

## Périmètre déclaré et réalisation

COMMAND primaire ; PROJECTION et EVENT secondaires. E ajoute exclusivement des tests d'intégration/composition, le manifeste, les scripts de preuve et la documentation. Production A/B/C inchangée ; production D dans domain-pot/engine-write-pot/infra-persistence-primary-jpa. Aucun POM, migration, HTTP, READ, Identity, Discovery, moteur générique ou runtime de production modifié. Aucun franchissement inattendu. Gates G-E, architecture globale et full reactor requis pour la clôture du jalon majeur L1. Base CURRENT V3 V1+V2 ; suites historiques transitives conservées sans campagne séparée ni certification BASELINE V2.

Quatre nouvelles méthodes PostgreSQL E assemblent les vrais composants A/B/C/D :

- e01 : fixture normative V1..V9 par demandes durables C, oracle indépendant de chacune des huit composantes (identités, valeurs, versions locales et activeFrom), balances 75/−75 puis −30/+30 puis −60/+60, zéros après DeleteExpense et absence à D. Reconstruction en ordre seed 731, anciennes valeurs inchangées après D. Correspondance révisions/changedComponents et positions d'événements denses.
- e02 : D01-M via C, Header seul supprimé, détails/poids conservés, poids ancien reconstructible, no-op et égalité obsolète, mutation après suppression refusée ; aucun POT_EVENT supplémentaire pour no-op/rejet.
- e03 : no-op Financials, reprise de la même demande terminale, modification effective, égalité devenue obsolète, no-op valide et suppression refusée sur référence ; exactement quatre POT_EVENT et huit complétions, seul ExpenseFinancials f2 publié à V4, anciennes balances intactes.

- e04 : création Shareholder/Expense depuis observedVersion ancienne après mutation sans lien ; lot mixte ne révisant que Weight A ; rejet atomique de la dernière cible obsolète pourtant égale ; DeleteExpense refusée après modification Financials et admise après modification Weight indépendante, anciennes balances exactes.

Le test de factory `V3PotCommandComposition.assemble` est étendu aux créations Shareholders/Expense, révision de défaut et calcul historique exact avec le vrai JpaTransactionManager. L'autorité physique JPA/JDBC et le refus d'un manager incohérent sont conservés. Toutes les preuves critiques C (24 PG, 6 unitaires, composition, architecture) et A/B sont réexécutées avec D dans les gates ; les courses financières et quatre cuts de rollback D s'y ajoutent. Les valeurs d'autorité Identity, l'admission et l'acquisition initiales restent des fixtures PostgreSQL explicitement nommées.

## Provenance A-02

Le collecteur part d'un commit H propre incluant tous sources/tests/ressources/SQL/POM/scripts/manifeste. Il compare automatiquement l'intégralité des entrées non exclues à l'arbre Git H avant et après chaque gate, y compris les modifications de mode des fichiers ; aucune entrée non suivie n'est acceptée. Targets supprimés avant chaque commande : compilation/XML frais, inventaire des classes/ressources et SQL générés. Les classpaths proviennent exclusivement de sorties Maven fraîches et de JARs identifiés.

Avant/après : empreintes JDK (modules/binaires/bibliothèques natives/configuration), distribution Maven, configurations externes, cache JAR/POM et image PostgreSQL. Toute entrée modifiée ou nouvellement résolue impose une nouvelle preuve fraîche. Les reçus d'artefacts couvrent XML, snapshots, logs et métadonnées. Sept tests adversariaux (dont plusieurs variantes) prouvent les refus : source sale, fichier non suivi, SQL supprimé, mode modifié, commit exécutable postérieur, exécutable caché sous evidence, dépendance modifiée/ajoutée et tête distante différente.

Le contrôle de publication exige les trois gates E retenus exécutés sur H et leurs reçus/entrées before/after, refuse tout skip nouveau et vérifie H→P par liste stricte d'exclusions documentaires/probatoires. Après push, il lit la tête distante, exige P et vérifie à nouveau les mêmes entrées. Aucun simple digest déclaré ne clôt A-02. Résultat effectif et SHAs à renseigner après les gates et publication.

## Preuves et certification

Le manifeste est fixé avant gates. La matrice finale est générée depuis SPEC-10, les méthodes attendues et les XML frais par `scripts/testing/build-l1-matrix.py` : scénario normatif, lot, variantes, oracle, méthode/source, gate/SHA, preuve et limite. « Contribution interne exécutée » ne signifie jamais PASS global d'une exigence dont Bruno/READ/Worker ou d'autres familles manquent.

La couverture préparatoire des finances PRJ-02 et les calculs hors ordre ne certifient aucune balance READ durable/immuable. D03-L est une composition de primitives B à une V, sans nouvelle Command combinée. Les parcours Identity/Invitation, Consumption/Discovery, READ, sécurité HTTP/Bruno, k6 et performance restent reportés. Mesures D de temps/allocation/requêtes indicatives seulement ; coûts d'historiques longs et de verrous non qualifiés.

A-01 sera conclue sur les gardes réellement réexécutées ; A-02 sur le contrôle final automatique ; I-01 est complété pour tous les writers/lecteurs/calculs L1, avec suivi transversal futur ouvert. L1 n'est pas définitivement accepté.

## Résultats et publication

À compléter exclusivement à partir des résultats frais des gates sur H. Le prochain événement après publication D/E est l'audit indépendant final L1. STOP avant tout lot suivant ou acceptation définitive.

Renforcement avant les gates finaux : premier G-E sur `5a954a20eb4ebd2c6a36a677a675985a75b34e51` BUILD SUCCESS, 1288/0/0/1, 82 méthodes obligatoires PASS ; exécution conservée mais remplacée après ajout d'e04 et renforcement A-02. Le contrôle inspecte désormais chaque commit H..P, refusant aussi un exécutable changé puis rétabli : une simple égalité des arbres finaux ne suffit pas. Aucun full reactor n'avait encore été lancé ; aucun défaut de production A/B/C ni frontière supplémentaire.
