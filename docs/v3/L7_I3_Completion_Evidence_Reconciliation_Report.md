# L7.3 — Réconciliation des 53 obligations

Mandat `GO_L7_I3_COMPLETION`. La classification initiale est conservée séparément ; ce rapport décrit les rapprochements et leurs limites. [Classification initiale](../testing/evidence/L7-I3-completion/initial-classification.json), [dossier détaillé par ID](../testing/evidence/L7-I3-completion/reconciliation.json).

Les 53 lignes initiales étaient MISSING. Elles deviennent 22 CURRENT_FULL, 30 CURRENT_PARTIAL et une MISSING. Les 21 promotions de catégorie A reposent sur les XML et traces SQL de l’invocation `6f84169ffe124312b3b635a5044346ea`, au SHA `5ae89228`, dont l’archive SHA-256 et chaque artefact cité sont vérifiés. Aucun test historique n’est réécrit. Les sources productives sont inchangées ; les deux anciennes méthodes Discovery ont seulement reçu une méthode sœur dans leur classe.

Une obligation nouvelle est complètement qualifiée par un test ciblé, L2M-C01-J-REPORT. La catégorie D de R8-05 devient une anomalie d’outillage E lors de l’inspection des scripts. Sa correction ne prouve pas l’exhaustivité de tous les canaux administrateurs ; la ligne reste partielle. Les trois catégories D initiales ne sont donc pas transformées en PASS par simple présence d’un test.

| ID | Catégorie initiale | Statut final | Justification et limite |
|---|---|---|---|
| L2M-C04-K | B | CURRENT_PARTIAL | Six frontières physiques ; répétitions et corpus composite du C04-K à rapprocher. |
| L2M-C06-B | A | CURRENT_FULL | Registration réelle, deux événements Identity et complétion ; unité physique, snapshot externe et writer_xid. |
| L2M-E01-D | B | CURRENT_PARTIAL | Lots consécutifs et contention réelle ; ordre interne du lot concurrent à compléter. |
| L2M-E01-E | B | CURRENT_PARTIAL | Rejet Identity sans mutation ; branche Command sans effet à rapprocher séparément. |
| L2M-C01-A-REPORT | B | CURRENT_PARTIAL | Fenêtres Identity réelles ; toutes variantes source/partition de C01-A restent à détailler. |
| L2M-C01-D-REPORT | B | CURRENT_PARTIAL | Attente physique sur compteur Identity ; consommation du préfixe sous cette attente à compléter. |
| L2M-C01-J-REPORT | D | CURRENT_FULL | IdentityRequest réelle : claims actif/expiré, retry futur et quarantaine, trois passages Discovery préservant les snapshots. Nouvelle acquisition refuse actif/retry/quarantaine et renouvelle le token expiré. Checkpoint amendé L7/I2. |
| L2M-C02-I-REPORT | A | CURRENT_FULL | Six participants réels : panne après completion, snapshot inchangé et slot conservé. |
| L2M-C02-J-REPORT | B | CURRENT_PARTIAL | ACK inconnu exercé ; lire les variantes et la population exacte avant promotion complète. |
| L2M-C02-K-REPORT | B | CURRENT_PARTIAL | Reprise de reconstruction ; ne remplace pas toutes les fenêtres de redémarrage Discovery/Engine. |
| L2M-C04-D-REPORT | A | CURRENT_FULL | Faute après effets/completion ; ROLLED_BACK et égalité du vecteur avant/après sur vrais participants. |
| L2M-C04-L-REPORT | B | CURRENT_PARTIAL | Suffixe fermé et rollback ; inventaire exhaustif des voies administratives encore requis. |
| L2M-C06-B-REPORT | A | CURRENT_FULL | Registration réelle atomique, résultat et deux événements, mêmes writer_xid et connexion SQL. |
| L2M-C06-E-REPORT | A | CURRENT_FULL | Erreur de writer interceptée ; vraie unité Identity rollback, vecteur inchangé, compteur non écrit. |
| L2M-C06-M-REPORT | B | CURRENT_PARTIAL | Chemin actif Identity et suffixe contrôlés ; inventaire des autres compositions à compléter. |
| L2M-C06-P-REPORT | A | CURRENT_FULL | Six branches réelles, IDs distincts, branche désactivée conservant son slot pendant la progression des autres. |
| L2M-E01-A-REPORT | A | CURRENT_FULL | Trois Registrations réelles ; positions exactes 1..6 et complétions 1..3 sans nextval. |
| L2M-E01-B-REPORT | B | CURRENT_PARTIAL | T2 bloque réellement sur compteur et densité après commit ; borne de visibilité avant commit à compléter. |
| L2M-E01-C-REPORT | B | CURRENT_PARTIAL | Rollback puis densité rétablie ; variante T2 concurrent pendant rollback non attestée. |
| L2M-E01-D-REPORT | B | CURRENT_PARTIAL | Lots ordonnés et contention séparés ; aucun témoin unique du lot concurrent sans intercalation. |
| L2M-E01-F-REPORT | A | CURRENT_FULL | Rollback après positions, vecteur métier incluant compteurs inchangé ; deux familles non publiées. |
| L2M-E01-H-REPORT | B | CURRENT_PARTIAL | Deux moteurs concurrents ; charge répétée et interruptions des connexions restent distinctes. |
| L2M-E02-A-REPORT | A | CURRENT_FULL | Vrai CreateInvitation : trois rollbacks puis une complétion et une Invitation par demande, positions exactes et slot libéré. |
| L2M-E02-D-REPORT | A | CURRENT_FULL | Création User/EUI, deux événements, résultat et complétion dans la même unité. |
| L2M-E02-E-REPORT | A | CURRENT_FULL | Volet Unlink et rejet actif ; Link retiré, affiliation initiale injectée comme historique terminé. |
| L2M-E02-H-REPORT | B | CURRENT_PARTIAL | Ancien claim refusé ; double finalisation de la même demande après takeover reste à isoler. |
| L2M-E02-J-REPORT | B | CURRENT_PARTIAL | Relance réelle et demandes terminales ; crash exactement entre admission HTTP et finalisation à compléter. |
| L2M-E03-B-REPORT | A | CURRENT_FULL | Même événement POT, trois IDs/slots/checkpoints distincts, sans tâche intermédiaire. |
| L2M-E03-G-REPORT | B | CURRENT_PARTIAL | Indépendance et rollback par branche ; deux fautes simultanées sous une réussite non démontrées ensemble. |
| L2M-E02-I-INVITATION-REAL | D | MISSING | Oracle manquant : publication Invitation avec demande absente/inexistante/incorrecte, refus physique sans commit partiel. |
| L2M-E01-G-INV-COMP | B | CURRENT_PARTIAL | Densité après rollback et reprise séquentielle ; graphe de verrouillage concurrent Invocation/Completion non qualifié. |
| L2M-E01-G-ID-IDCOMP | B | CURRENT_PARTIAL | Contre-preuves du suffixe et attente des deux familles ; inventaire complet du graphe reste requis. |
| L2M-CON-05-CRASH | B | CURRENT_PARTIAL | Commit inconnu avant/après filaire ; crash sans cession et compteur au même instant à rapprocher. |
| L2M-CON-05-NOPURGE | B | CURRENT_PARTIAL | Refus SQL runtime sur reset slots/checkpoints ; DELETE isolé et inventaire admin à compléter. |
| L2M-CON-05-VV1 | B | CURRENT_PARTIAL | Slot quarantainé intact pendant progression autre slot et autre Consumption ; effets READ techniques non multiversion. |
| L2M-CON-05-VV1-REAL | B | CURRENT_PARTIAL | Hors ordre réel et binding supersédé ; quarantaine V puis réactivation après V+1 non exercées ensemble. |
| L2M-C01-D-COMMIT | A | CURRENT_FULL | T1 indécise et T2 réellement bloquée ; K reste au préfixe sûr avant commit puis couvre la publication. |
| L2M-C01-D-ROLLBACK | A | CURRENT_FULL | Rollback T1, position réutilisée par T2, K final couvre uniquement les publications committées. |
| L2M-C01-I-COMMAND-EXISTING | A | CURRENT_FULL | Snapshots identiques des slots libres/claimés/expirés/quarantainés après Discovery ; aucune réactivation. |
| L2M-C01-I-COMMAND-TERMINAL | A | CURRENT_FULL | Finalisation réelle entre fenêtre et verrou, source TERMINAL et slot absent ; aucune nouvelle admission. |
| L2M-C01-I-IDENTITY-FIXTURE | B | CURRENT_PARTIAL | Vrai Identity remplace analogue ; unicité et terminal. Ancienne clause sans checkpoint amendée L7/I2 ; claims/retry à compléter. |
| L2M-C03-J-EXPIRE | B | CURRENT_PARTIAL | Token périmé refusé ; coupure exactement avant verrou final pour chaque autorité à compléter. |
| L2M-C03-J-CRASH | B | CURRENT_PARTIAL | Fil coupé avant/après commit : état externe et reprise ; ne pas assimiler automatiquement au crash de processus pendant calcul. |
| L2M-C06-C-POTVERSION | A | CURRENT_FULL | POT_VERSION réel : INSERT des effets et DELETE slot, mêmes PID/XID et rollback snapshot. |
| L2M-C06-C-BALANCE | A | CURRENT_FULL | POT_BALANCE réel : effets exacts et DELETE slot, mêmes PID/XID et rollback snapshot. |
| L2M-C06-C-VISIBILITY | A | CURRENT_FULL | POT_VISIBILITY réelle : effet et suppression du slot dans même transaction observée. |
| L2M-C06-D-BALANCE | A | CURRENT_FULL | Panne après effet et completion Balance : vecteur inchangé, slot conservé. |
| L2M-C06-D-VISIBILITY | A | CURRENT_FULL | Panne après effet et completion Visibility : vecteur inchangé, slot conservé. |
| L2M-R3-10 | B | CURRENT_PARTIAL | Racines et paramètres contrôlés ; variantes deux workers de configurations différentes restent à rapprocher. |
| L2M-R5-11 | B | CURRENT_PARTIAL | NON_COUNTABLE garde le budget ; classe des configurations invalides à rapprocher explicitement. |
| L2M-R8-03 | B | CURRENT_PARTIAL | Absence administrativement injectée démontre la dépendance à la non-purge ; inventaire des writers pas encore exhaustif. |
| L2M-R8-05 | D | CURRENT_PARTIAL | Sept opérations historiques et quatre main permettaient une purge ou terminalisation artificielle. Défaut d’outillage reproduit puis corrigé par refus avant effet. Les archives antérieures sont conservées. |
| L2M-L1-F-f04 | B | CURRENT_PARTIAL | Attachement par Invitation sous concurrence Unlink et restrictions ; deux utilisateurs/cibles concurrentes spécifiques non démontrés. |

## Règles de promotion

CURRENT_FULL désigne la portée entière de la ligne allouée, pas une certification globale du mécanisme. CURRENT_PARTIAL signifie qu’un oracle pertinent a été identifié et vérifié, avec un stimulus restant explicite. MISSING désigne l’absence de preuve adéquate, sans diagnostic automatique de défaut. Les clauses historiques « aucun checkpoint Burnable » sont amendées par SPEC-05 et L7/I2 : les lignes mixtes restent applicables et ne sont pas retirées. Aucun nouveau retrait ni NOT_APPLICABLE.

| Indicateur | Initial | Final |
|---|---:|---:|
| Sans preuve L7.3 | 53 | 1 |
| Complètes L7.3 | 10 | 32 |
| Partielles courantes L7.3 | 338 | 368 |
| Défauts produit avérés ouverts | 0 identifiés | 0 identifiés |
| Défauts d’outillage avérés | 0 identifiés | 1 corrigé, 0 ouvert |
| Réserves du sous-ensemble de 53 | 53 sans attribution | 30 partielles et 1 manquante |

Les 30 contributions historiques partielles L7.3 et le retrait Link demeurent inchangés. Les 522 identifiants, sources et colonnes historiques sont conservés. Les 30 réserves partielles sont techniques, potentiellement bloquantes ; elles ne sont pas déclassées en simples réserves documentaires.

