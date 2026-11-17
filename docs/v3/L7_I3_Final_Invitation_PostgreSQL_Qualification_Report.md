# L7.3 — Qualification PostgreSQL Invitation complémentaire

Mandat `GO_L7_I3_FINAL_QUALIFICATION`, branche `feat/v3-architecture`, départ local et distant propre `c4f5f0c11114921cdc3a735f1fdaeb32c33537c7`. **L2M-E02-I-INVITATION-REAL : CURRENT_FULL**, limité au stimulus des trois refus physiques. Aucun changement de schéma, privilège, migration ou contrat.

La fixture CURRENT jetable PostgreSQL 17.11 crée une Invitation valide par les vrais parcours HTTP/WRITE/READ. Les tentatives SQL utilisent le principal runtime WRITE contractuel, vérifié non superuser et sans création de rôle/base ; PID/XID sont consignés. Une connexion indépendante mesure Invitations, événements, positions et somme des compteurs avant/après.

| Demande de création présentée | Mécanisme applicable | Refus effectif | Oracle extérieur |
|---|---|---|---|
| Absente, `creation_request_id=NULL` | NOT NULL | `23502` | Aucun changement durable, nouvelle Invitation absente |
| UUID inexistant | FK de demande | `23503` | Aucun changement durable, nouvel événement absent |
| Demande existante CreatePot | Vérification différée d'unité Invitation | `23514` au commit | Rollback de l'Invitation, événement, positions et compteurs |

Les codes sont déduits des contraintes existantes et vérifiés par une opération réellement refusée. Aucune validation Java ni mock ne remplace ce refus. Aucune élévation de droits ni désactivation de trigger. La configuration de test permet l'observation administrative ; le stimulus fautif lui-même utilise le principal applicatif.

[Reçu SQL](../testing/evidence/L7-I3-final/invitation-01/receipt.json) : 1 test PASS, zéro failure/error/skip, SHA `f44c64fb08c6441e17fcaaea386eddb4c5340225`, Maven 16,363 s, durée murale 17 s. XML frais et log compressés contiennent les observations SQL ; aucun fichier physique séparé n'est revendiqué pour ce run.

L1-F-f04 est également clos par deux scénarios HTTP concurrents au SHA `dd76862e` : deux finalisations attendent réellement le verrou Pot détenu par une autre connexion. Sur une place identique, un seul résultat COMPLETED et une publication, l'autre résultat REJECTED/INVALID_ATTACHMENT ; sur deux places libres, deux résultats et deux publications. Chaque `user_id` bénéficiaire correspond à son acteur authentifié, historique EUI inchangé, tiers FORBIDDEN sans nouvelle version Pot. [Reçu](../testing/evidence/L7-I3-final/invitation-concurrency-01/receipt.json) : 2 PASS, Maven 24,167 s, durée murale 25 s. Ce stimulus ne qualifie pas le graphe des compteurs INVITATION/COMMAND_COMPLETION, qui garde sa réserve.

Un défaut de collecte a été détecté : la première collecte de `invitation-01` incluait des traces Identity produites après le run, faute de borne supérieure d'horodatage. Cette collecte est retirée de l'admissibilité et conservée intacte sous `invitation-01-collection-attempt`. La [correction](../testing/evidence/L7-I3-final/collection-correction.json) borne la collecte entre début et fin. Les XML et le résultat du test initial restent inchangés ; aucune trace étrangère n'est utilisée pour conclure.

Tranche déclarée : composition Monolith, dans le plan COMMAND/PROJECTION. Feedback natif filtré, commandes exactes dans [commands.txt](../testing/evidence/L7-I3-final/commands.txt), sans qualification d'une slice canonique complète. Aucune traversée imprévue. Gate Architecture et full reactor non requis par ces ajouts de tests ; ni Bruno ni benchmark rejoué.
