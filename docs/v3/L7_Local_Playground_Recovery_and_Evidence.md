# L7-LB — reprise, générations et preuves

Mandat : `GO_L7_LB_START_RECOVERY_AND_EVIDENCE_FIX`. Ce contrat complète le
[contrat développeur](L7_Local_Baseline_Developer_Experience_Contract.md).
Aucun changement du runtime V3, des baux 240 s, du fencing ou des API publiques.

## Reprise effective

Un processus RUNNING et un Actuator UP ne prouvent pas la propriété des
partitions. Après remplacement d’un worker, les propriétaires précédents
restent protégés par les baux SQL. Le nouveau processus doit attendre une
acquisition normale ; aucune échéance ou claim n’est modifiée par le tooling.

`start` relève les vrais `Id`, `Created`, `State.StartedAt` et `Running` de
chacun des cinq conteneurs runtime. Il compare les observations avant et après
Compose : CREATED, RECREATED, RESTARTED ou PRESERVED. Le texte de Compose
n’est pas l’oracle. Les dates de démarrage détectent aussi un redémarrage sans
changement d’identifiant de conteneur.

Après les contrôles techniques, le parcours exige simultanément :

| Rôle | Participants | Partitions | Baux locaux utilisables |
|---|---|---|---|
| command-worker | Command, IdentityRequest | 0–3 chacun | 8 WRITE |
| results-worker | CommandResult, IdentityRequestResult | 0–3 chacun | 8 READ |
| business-read-worker | PotVersion, PotBalance, PotVisibility, CurrentBinding, Invitation | 0–3 chacun | 20 READ |

Les métriques `pocoma_v3_lease_local` et `pocoma_v3_worker_state` sont lues sur
les workers eux-mêmes, sans cache Prometheus. Les tables de routage et de
propriété sont consultées en transactions SQL READ ONLY pour corroborer chaque
couple participant/partition, avec un owner non nul et une échéance future.
Le total local est exhaustif pour cette topologie déclarée : participants
disjoints, un processus par rôle et quatre partitions par participant. Une ligne
manquante, dupliquée, supplémentaire ou de mauvais partitionnement ne passe pas.
Les générations sont revérifiées avant de qualifier cette observation, puis
après le canari. Ce contrôle n’est pas une garantie contre une panne ultérieure.

Le budget de récupération est de 270 s, mesuré par horloge monotone : bail
existant de 240 s plus marge de 30 s. Les sondes HTTP et SQL sont elles-mêmes
bornées ; une observation en vol peut prolonger la sortie de sa durée bornée.
La première observation complète termine immédiatement cette phase. Une reprise
sans remplacement et un premier démarrage n’attendent pas un bail inexistant.
Au défaut : `WORKER-RECOVERY-TIMEOUT` ou `RECOVERY-GENERATION-CHANGED`, preuves
conservées et aucune admission du canari. Son polling garde un budget distinct
de 30 s par résultat ; les GET ont toujours leur timeout réseau de 10 s.

`start` conserve la graphie des montures déployées lorsque `/Dev/` et `/dev/`
désignent physiquement le même dossier. Une mise à jour du seul tooling est
adoptée après preuve d’équivalence productive et du déploiement ; le SHA et les
hashes des JAR réellement réutilisés restent attribués à leur build d’origine.
Une dérive productive, SQL, JAR ou Compose est refusée sans reset.

## Preuves et interprétation de status

Les champs JSON existants restent présents. S’ajoutent :

| Champ | Signification |
|---|---|
| `technical` | Disponibilité courante des contrôles techniques et d’authentification |
| `businessEvidence` | PROVEN si une preuve récente correspond à la génération actuelle ; sinon UNPROVEN |
| `lastStart` | Dernier démarrage tenté, run, dates, état SUCCEEDED/FAILED et cause éventuelle |
| `lastSuccessfulStart` | Dernier démarrage ayant effectivement retourné exit 0 |
| `currentGeneration` | Identifiants et dates réels des cinq runtimes observés |
| `generationQualified` | Preuve rattachée à la même génération et processus actifs |
| `proofFreshness`, `proofAgeSeconds` | Âge indépendant de la correspondance de génération ; limite 300 s |

`READY` exige les contrôles courants, le contenu READ exact du canari, les
outils disponibles, une preuve de 0 à 300 s et la correspondance de génération.
Les règles Grafana existantes restent applicables. Une preuve historique sans
génération, ou d’une génération différente, reste consultable mais ne permet
pas READY ; `business` devient UNPROVEN si les contrôles techniques passent.
Une preuve expirée ne devient pas fraîche par simple consultation.

Un échec récent reste visible dans `lastStart` et n’efface pas
`lastSuccessfulStart`. Si l’échec n’a pas remplacé les runtimes et qu’une preuve
antérieure est encore valide pour eux, la disponibilité courante peut rester
READY : cela ne transforme pas le démarrage échoué en réussite. Une nouvelle
génération sans canari terminé reste NOT_READY.

Les reçus historiques ne sont pas réécrits. Les runs nouveaux conservent :
`start-attempt.json`, `generation-before.json`, `worker-recovery.json`,
`public-requests.json`, `command-observations.json` et, en échec, `failure.json`.
Les admissions comportent le requestId et l’heure client d’observation du 202 ;
les GET/POST comportent leurs dates et statuts. Le dernier polling conserve une
observation explicitement allowlistée. Aucun corps arbitraire, bearer,
bindingToken ou lien d’invitation n’est exporté. La preuve métier ajoute
`generationId` et `generation`. `start-history.json` conserve les deux derniers
jalons de démarrage dans l’état privé de l’instance.

## Résultats tardifs

Pour rapprocher un ancien timeout de commande sans nouvelle admission :

```sh
./pocoma-local status --instance demo --run 1794676508-e55784d4
```

`status` retrouve le requestId dans le reçu initial puis fait uniquement un GET
sur son résultat avec l’identité de qualification. Il écrit `late-result.json`
dans le nouveau run de diagnostic, avec `sourceRunId`, `requestId`, statut,
outcome et version résultante. Il ne modifie ni le reçu initial ni le canari
historique. Un résultat tardif ne constitue pas une qualification complète du
canari, notamment de ses vérifications finales non exécutées.

Sans `--run`, le dernier start échoué par timeout de résultat de commande est
rapproché automatiquement lorsque son reçu est disponible. Cela ne déclenche
aucun POST métier ni double mutation. Les 404/503 restent des observations
d’absence/indisponibilité ; les erreurs réseau sont explicites et ne sont pas
converties en succès. Le code de sortie de status décrit sa disponibilité
courante ; le rapprochement est un diagnostic distinct.
