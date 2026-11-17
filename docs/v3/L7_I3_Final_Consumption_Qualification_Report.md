# L7.3 — Campagnes Consumption complémentaires

Mandat `GO_L7_I3_FINAL_QUALIFICATION`, branche `feat/v3-architecture`, départ local et distant propre `c4f5f0c11114921cdc3a735f1fdaeb32c33537c7`. **12 exécutions JUnit PASS, zéro failure/error/skip**, en huit invocations Maven successives. Onze méthodes nouvelles ; la méthode ACK inconnue est rejouée une fois après renforcement de son oracle. Les répétitions internes et sous-cas ne sont pas ajoutés artificiellement au décompte JUnit.

| Campagne | SHA testé | PASS / FAIL / SKIP | Maven | Murale | Preuve |
|---|---|---|---|---|---|
| `identity-01` | `97d3cfc4` | 1 / 0 / 0 | 6.886 s | 8 s | [reçu](../testing/evidence/L7-I3-final/identity-01/receipt.json) |
| `invitation-01` | `f44c64fb` | 1 / 0 / 0 | 16.363 s | 17 s | [reçu](../testing/evidence/L7-I3-final/invitation-01/receipt.json) |
| `invitation-concurrency-01` | `dd76862e` | 2 / 0 / 0 | 24.167 s | 25 s | [reçu](../testing/evidence/L7-I3-final/invitation-concurrency-01/receipt.json) |
| `read-01` | `97d3cfc4` | 3 / 0 / 0 | 37.584 s | 38 s | [reçu](../testing/evidence/L7-I3-final/read-01/receipt.json) |
| `read-reacquire-01` | `0faf4ff5` | 1 / 0 / 0 | 27.496 s | 29 s | [reçu](../testing/evidence/L7-I3-final/read-reacquire-01/receipt.json) |
| `restart-01` | `49407906` | 1 / 0 / 0 | 7.822 s | 9 s | [reçu](../testing/evidence/L7-I3-final/restart-01/receipt.json) |
| `takeover-01` | `51f9fd25` | 1 / 0 / 0 | 6.424 s | 8 s | [reçu](../testing/evidence/L7-I3-final/takeover-01/receipt.json) |
| `worker-01` | `45967070` | 2 / 0 / 0 | 12.615 s | 13 s | [reçu](../testing/evidence/L7-I3-final/worker-01/receipt.json) |

Total Maven : **139.357 s** ; durée murale cumulée des huit exécutions : **147 s**. PostgreSQL 17.11 Testcontainers, JDK Temurin 21, schémas jetables et rôles des fixtures CURRENT. L'installation et l'analyse ne sont pas comprises dans ces temps. Les cinq fichiers Java modifiés appartiennent à infra-postgres-write, runtime-worker-process, runtime-worker-materialize et runtime-monolith. Aucun code productif, POM, grant ou migration changé.

## Stimuli, oracles et limites

- **ID-PUB** : deux Registrations, deux événements par lot, T1 retenue après sa première position, T2 attestée bloquée par `pg_blocking_pids`. Six interleavings : deux fois commit/rollback/coupure du backend exact de T1. Fenêtre source indépendante bloquée au préfixe committé et aucun résultat visible avant résolution ; ensuite ordre exact des request_id et densité de toutes les positions Identity. L'issue physiquement incertaine ne devient pas COMMITTED. Cette preuve couvre E01-B/C/D réels et E01-D analogue, mais pas le checkpoint READ pendant cette course ni la charge mixte de tous producteurs.
- **Takeover** : trois demandes successives ; expiration technique du claim, nouvelle acquisition protocolaire, deux finaliseurs simultanés de la même demande. Ancien CLAIM_LOST, nouveau COMMITTED ; un résultat, deux événements Identity, une complétion, zéro slot. Seule l'expiration technique est injectée, aucune donnée métier manipulée pour satisfaire l'oracle.
- **READ indépendants** : deux transactions de projection réellement ouvertes après effets et suppression attendent une barrière, une troisième Consumption réussit. L'oracle extérieur voit uniquement la troisième ; les deux autres rollbackent avec leurs slots conservés.
- **Quarantaine multiversion** : pour PotVersion/CurrentPot, Balance, Visibility et CurrentBinding, trois échecs dénombrables réels après effets entraînent la quarantaine ; aucun effet ne survit aux rollbacks. V2 progresse sans changer le slot V1. Réactivation administrative contractuelle par `reactivate-l2-slot.sql`, puis V1 terminée sans régression des versions/pointeurs ni réactivation du binding Unlink. Aucun DELETE de quarantaine ni modification de timeout.
- **ACK inconnu** : six participants READ réels, coupure filaire avant/après commit. UNKNOWN, absence de cession et capacité conservée dans les deux cas ; effets/slot durables distinguent commit effectif et rollback. Le run `read-reacquire-01` complète le premier run : après commit, acquisition par un Engine sain impossible et valeurs exactes des neuf tables métier inchangées. Il n'y a aucun retry spéculatif de l'opération incertaine. Le premier oracle ACK est conservé et supersédé par cet oracle plus fort pour C02-J-REPORT.
- **Redémarrage de composants** : deux générations réelles Pot/Identity ; Discovery et Engines reconstruits après traitement de la première. Checkpoints, valeurs métier et six slots restants comparés exactement ; traitement V2 puis nouveau scan sans réapparition d'anciens slots. Couvre C02-K sur les six participants ; aucun crash OS revendiqué.
- **Workers** : deux configurations réelles sur schéma partagé, partitions Command disjointes, Mw=1/4 et polling différents ; bindings effectifs vérifiés et deux terminalisations/publications. Deux configurations invalides refusent réellement le démarrage avec demande PENDING et état de coordination intacts, sans budget consommé. Aucun objectif temporel/de débit certifié.
- **Invitation** : voir le [rapport PostgreSQL](L7_I3_Final_Invitation_PostgreSQL_Qualification_Report.md), qui inclut les deux courses d'auto-rattachement.

Chaque scénario échoue sur barrière non atteinte, état durable divergent, statut inattendu ou publication manquante/supplémentaire. Les attentes sont bornées et couplées à l'observation SQL ; aucun sleep arbitraire ne sert de preuve. La coupure ne vise que le backend de la fixture et aucune base préexistante n'est réinitialisée.

## Réemploi et attribution

Deux obligations sont closes par réemploi, sans nouvelle exécution : C03-J-EXPIRE par `ConsumptionLifecyclePostgresTest.i3_c03_g` du full à `5ae89228`, et C01-I-IDENTITY-FIXTURE par les trois méthodes Discovery à `e1ecb533`. [Reçu de rapprochement](../testing/evidence/L7-I3-final/reuse-receipt.json) : sources inchangées, XML de la bonne invocation, empreintes et témoins SQL contrôlés. L'ancien énoncé Identity « sans checkpoint » reste historique ; l'amendement L7/I2 du scan burnable avec checkpoint source est conservé.

Les sources productives, configurations, SQL et helpers de ces preuves restent identiques ; seuls des tests sont ajoutés à d'autres classes. Pour les nouveaux runs, le SHA, l'état Git exact, la sélection, le JDK, les logs/XML et les traces sont archivés par campagne. Les méthodes anciennes inchangées sont rapprochées au code final ; l'oracle ACK modifié dispose de son propre rejeu. Le contrôle [check-l73-final-evidence.py](../../scripts/testing/check-l73-final-evidence.py) valide cette attribution et les 522 identifiants. Les empreintes garantissent l'intégrité, pas une authentification contre un acteur pouvant réécrire tous les artefacts.

La première collecte Invitation contaminée et l'assertion initiale du rapprocheur qui rejetait aussi system-out sont conservées comme incidents d'outillage dans le dossier de preuves. Aucune défaillance Runtime démontrée et aucune modification d'oracle métier pour obtenir PASS. Le rapprocheur final accepte des résultats partiels explicitement réservés, et exige une preuve nouvelle ou rapprochée pour chaque CURRENT_FULL.

## Vérification déclarée et gates

Primaire COMMAND, secondaire PROJECTION, composition Monolith pour Invitation ; niveau feedback ciblé, commandes natives filtrées dans [commands.txt](../testing/evidence/L7-I3-final/commands.txt). Les commandes canoniques non filtrées restent `./mvnw -pl runtime-worker-process -am test` et `./mvnw -pl runtime-worker-materialize -am test` depuis app ; elles n'ont pas été exécutées ici et ne sont pas revendiquées PASS. Aucun impact productif ou franchissement imprévu de frontière. Gate Architecture non déclenchée, full reactor non déclenché selon §§17/19/35 et le mandat ciblé. Les reçus globaux antérieurs demeurent attribués à leur SHA ; leurs 1 057 PASS ne certifient pas les nouveaux tests. Les 47 tests Architecture des campagnes acquises ne sont pas recomptés comme une nouvelle gate. Ni Bruno, ni reconstruction nominale à l'identique, ni SPEC-09, L7.4 ou L7.5 exécutés.
