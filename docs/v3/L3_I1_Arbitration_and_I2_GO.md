# L3 / I1 — Arbitrage humain et GO I2

**30 octobre 2026 — I1 : ACCEPTED WITH EXPLICIT RESERVATIONS — GO I2.**

Décision transmise directement par le responsable après audit indépendant du
code I1 et examen des preuves. Elle s'applique au dossier I1 examiné à
`02c7bfbf85936f1f1a633e9097bc2b49f878708e`, dont l'implémentation est
`ddffcb44c92d02d335f41b55122aa6c7d885d0e2`.

## Décision et portée

1. L'implémentation métier I1 est acceptée dans son périmètre borné.
2. Les 35 nouveaux tests I1 sont PASS.
3. Aucun défaut concret imputable aux modifications I1 n'a été identifié.
4. La gate composée COMMAND + Architecture demeure FAIL, avec les échecs
   `i5_r05` et `i5_r12`.
5. La contribution Architecture ciblée ne vaut pas certification de la gate
   canonique.
6. Le verdict **I1 LOCAL BOUNDED PASS n'est pas prononcé**.
7. Les réserves et les preuves correspondantes sont conservées sans
   modification des tests, seuils ou mécanismes L2.

**Le démarrage d'I2 est autorisé, limité à l'autorité PostgreSQL Identity et
aux preuves prévues par le plan L3.** Le statut I1 reste explicitement
ACCEPTED WITH EXPLICIT RESERVATIONS, distinct d'un PASS technique canonique.

Cet arbitrage ne certifie ni la non-régression globale, ni la disponibilité du
Worker L2. Il ne modifie aucune acceptation L1/L2 et ne dispense pas les lots
ultérieurs de leurs gates obligatoires. Il n'autorise aucun filtre, skip ou
changement de gate I2. Aucun GO I3–I5, L4/L5 ou production n'en découle.

## Preuves et réserves conservées

- [Rapport I1](L3_I1_Execution_Report.md) et
  [reçu de vérification](../testing/evidence/L3-I1/Verification.json) :
  35 nouveaux tests PASS ; gate composée FAIL ; Architecture canonique non
  atteinte, contribution ciblée distincte.
- [Examen de non-clôture](L3_I1_Verification_Resolution.md) et
  [analyse des preuves](../testing/evidence/L3-I1-resolution/Analysis.json) :
  conditions des deux échecs et limites de causalité ; aucune correction I1
  concrète identifiée.
- [Clôture L2](L2_Documentary_Closure.md) : acceptations et réserves L1/L2
  inchangées, notamment R-L2-01…05 et états G1/G2/G3.

Le présent arbitrage supplante seulement les mentions procédurales historiques
« I1 NOT CLOSED / arrêt avant I2 / GO distinct nécessaire ». Les résultats,
journaux, décomptes, réserves et limites techniques demeurent inchangés.

## Reprise I2 autorisée

Le [plan L3 §6, I2](L3_Identity_Inventory_and_Implementation_Plan.md)
définit les fichiers et les preuves ; §§3.2, 4, 7.2 et 8.2 fixent les invariants
et les frontières. I2 doit livrer `JdbcIdentityAuthority`,
`SecureBindingTokenGenerator`, la migration V6 additive et les quatre classes
de tests prévues. Les fixtures de demande satisfont le DDL terminal complet,
sans désactivation de contraintes et sans participant actif.

Scope à respecter avant code :

| Élément | Déclaration I2 |
|---|---|
| Impact attendu | Autorité/histoire/verrouillage PostgreSQL Identity, générateur de tokens et DDL V6 dans PRIMARY |
| Slice primaire | COMMAND, ancre `runtime-command-consumption-worker` |
| Slices secondaires | NONE ; PRIMARY dépend déjà de l'engine Registration |
| Preuve locale | PostgreSQL réel : histoire exacte, ancres zéro, invariants SQL, collisions, rollback, ordre canonique connu/inconnu et courses ; oracles §7/7.2 |
| Architecture globale | REQUIRED, nouvelles règles de writers et contraintes de frontière |
| Full reactor | NOT REQUIRED pour cette étape bornée ; gates ultérieures conservées |
| Base de données | CURRENT V3 WRITE V1…V6 ; pas de campagne historique V2 séparée, aucune baseline TRUSTED V2 présumée |
| Impact interdit | Participant/raccord runtime I3, autorité Pot I4, READ L4, HTTP/consentement L5, migrations V2, owner physique/routage et mécanismes L2 |
| Escalade | Redéclarer avant changement d'owner, routage, contrat V2, autre consommateur ou frontière non prévue |

Commandes de gate prévues, **non exécutées par cet enregistrement**, depuis
`app/` ; composition possible selon la politique §34 :

```sh
./mvnw -pl runtime-command-consumption-worker -am test
./mvnw -pl architecture-tests -am test
# Ou, pour partager une seule fermeture amont :
./mvnw -pl runtime-command-consumption-worker,architecture-tests -am test
```

Le GO I2 est acquis. L'enregistrement de la décision ne constitue ni une
implémentation I2, ni une exécution de ses preuves.

## Vérification de cet enregistrement

Modification documentaire uniquement : impact production attendu/observé NONE,
slices exécutées NONE, franchissement inattendu NONE. Contrôles : liens locaux,
cohérence des statuts et `git diff --check`. Aucune commande Maven, exécution
PostgreSQL ou migration ; gate globale et full reactor NOT REQUIRED / NOT RUN
pour cet enregistrement, conformément à la politique §34. Aucun test, seuil,
mécanisme L2 ou artefact de preuve modifié. Start HEAD inchangé ; aucun commit
ou push effectué par cet enregistrement. Les fichiers de résolution déjà
présents sont conservés.
