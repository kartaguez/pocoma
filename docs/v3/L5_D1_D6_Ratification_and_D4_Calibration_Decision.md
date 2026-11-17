# Pocoma V3 — L5 — Ratification D1/D6 et mandat de calibration D4

Date : **4 novembre 2026**. Source de décision : **mandat humain transmis dans la conversation de cette mission**. Statut de cet acte : **décisions enregistrées, document soumis à revue avant publication**. **Aucun GO L5/I1.**

## 1. Référentiel et portée

Branche vérifiée : `feat/v3-architecture`. HEAD, référence locale de suivi et branche distante interrogée : **`5193c540c5be2ea6bf31d4fe159c02490ab352aa`**. Le [plan L5 amendé](L5_Supra_Entry_Audit_and_Implementation_Plan.md) au départ est exactement celui de ce SHA, sans delta ; worktree initial propre. Les cinq autres worktrees recensés sont préservés. Le premier accès distant a échoué sur DNS dans la sandbox ; la même lecture avec accès réseau autorisé a confirmé le SHA, sans fetch, commit ni push.

Autorités inchangées : [Canon](V3_Canon_and_Roadmap.md), [SPEC-06](SPEC-06_Supra_Contracts.md), [SPEC-09](SPEC-09_Performance.md), [SPEC-10](SPEC-10_Test_Certification.md), [Master Plan](V3_Implementation_Master_Plan.md), [Reactor Verification Policy](../testing/Reactor_Verification_Policy.md), [clôture L4](L4_Read_Closure_Decision.md), [plan I8](L4_I8_Implementation_and_Qualification_Plan.md) et [rapport I8](L4_I8_Execution_Report.md). Aucun contrat normatif n'est modifié pour enregistrer ces décisions. Les anciens statuts du plan restent historiques ; cet acte précise seulement les décisions effectivement acquises.

## 2. D1-HTTP — APPROVED

Le responsable approuve les conventions du plan L5 :

- Préfixe `/api/v3`, routes et parcours du §6.1 ; contrôleurs V3 minces sans logique métier propriétaire, enveloppes publiques et DTO explicites.
- Admissions asynchrones : `202 Accepted`, `requestId`, `Location`. Résultats terminaux consultés exclusivement via READ, aucun fallback WRITE.
- Matrice unique de réponses et d'erreurs du §6.4 ; masquage `404` conforme aux SPEC ; `503` pour indisponibilité et saturation selon les contrats approuvés.
- Aucun contrôleur ni service V2 sélectionné dans les parcours V3 ; `Cache-Control: no-store` pour les données sensibles.

Les conventions propres à Link et Invitation restent soumises à D3/D5 lorsqu'elles ne sont pas déjà imposées par les SPEC. L'approbation D1 ne ratifie pas ces décisions ouvertes.

## 3. D1-AuthN — APPROVED WITH VERIFICATION CONDITION

Adopter JWT/OIDC stateless avec le Resource Server Spring existant : validation cryptographique des signatures ; issuer, audience, algorithmes, dates et clés de confiance explicitement configurés. L'EUI provient exclusivement des claims authentifiés ; aucune EUI librement choisie par le client.

Aucune session Pocoma supplémentaire, aucun cookie applicatif et aucun mécanisme local de renouvellement du bearer. Les sessions E1/E2 restent indépendantes pour les parcours qui les nécessitent.

**L'approbation porte sur le contrat architectural. La qualification réelle du fournisseur demeure obligatoire avant acceptation de l'exposition externe. Une fixture locale de JWT signés n'en est pas une preuve.** Le serveur de consentement Link reste ouvert en D3 ; son choix ne bloque pas la préparation d'I1.

## 4. D1-représentation — APPROVED

- En-tête `X-Pocoma-Binding-Token` pour les consultations qui l'exigent ; BindingToken dans l'enveloppe des admissions concernées ; aucun token sensible dans les URL.
- UUID canoniques ; versions et entiers exacts ; rationnels en chaînes numérateur/dénominateur.
- Curseurs opaques, versionnés, sans pouvoir d'autorisation. Leur signature n'est pas imposée en l'absence de risque démontré.
- Champs inconnus refusés selon le contrat HTTP ; aucun UserId public arbitraire ; aucune exposition directe d'objets internes d'autorité ou de persistance.
- Réponses complètement sérialisées avant émission du statut de succès.

**Les limites quantitatives restent soumises à D4.** Les noms de champs et enveloppes employés par la calibration sont des représentations de mesure, pas une ratification supplémentaire de DTO publics.

## 5. D6-composition I1 — APPROVED

I1 réutilisera les modules et racines applicatives V3 existants : aucun nouveau module Maven, runtime, moteur ou participant Invitation ; aucun changement d'algorithme Consumption/Discovery. Contrôleurs et services V3 explicitement isolés, aucun composant HTTP V2 sélectionné ; aucun accès métier WRITE depuis READ. La composition conserve la propriété des transactions et ressources.

Les réserves L1–L4 restent ouvertes. **La topologie définitive des sept participants READ est reportée à D6-topologie avant I4 ; elle n'est ni approuvée ni qualifiée par cette décision.**

## 6. D6-gates — APPROVED WITH POLICY CONDITIONS

Allocation cible humainement retenue : I1 = T1 + Bruno B1 dans **15 minutes partagées au maximum** ; I2–I4 = campagnes ciblées prévues ; Q5 = gate canonique composée G5 avec Architecture globale et qualification complète Bruno B5. Les campagnes d'incrément sont des checkpoints locaux, sans clôture canonique autonome. Full reactor uniquement selon les classifications et déclencheurs effectivement applicables de la politique.

**La ratification définitive de cette allocation reste conditionnelle à sa compatibilité normative.** L'analyse trouve :

| Point | Lecture de la politique | Conséquence |
|---|---|---|
| Checkpoints ciblés et composition G5 | §34 autorise les contributions bornées et une invocation composée des ancres réellement concernées ; une recette filtrée ne remplace jamais la gate non filtrée | Allocation locale défendable si aucune clôture canonique indépendante n'est revendiquée |
| Architecture | §§17/34 imposent la gate globale pour dépendances, frontières et isolation ; §35 INTEGRATION demande la preuve globale lorsqu'elle est requise | G5 doit inclure l'Architecture complète ; la lecture opérationnelle du report à Q5 doit être explicitée avant I1, sans acceptation anticipée de l'isolation |
| Full à Q5 | §§19/21.2/34 distinguent lot borné et Major Wave ; le plan propose un lot borné. Mais §35 CERTIFICATION inclut **« Required lot closure »** et prescrit **« Unfiltered full reactor »** | La seule classification « pas Major Wave » ne suffit pas à résoudre le texte de §35 pour la clôture Q5 |
| Plafonds temporels | §35 précise **« No local budget caps this gate »** pour CERTIFICATION | Les 15 minutes T1/B1 ne dispensent d'aucune gate obligatoire ultérieure |

**Compatibilité partielle, non définitivement établie.** Aucun full ou contrôle Architecture obligatoire n'est supprimé par cet acte. Choix de gouvernance à présenter avant approbation opérationnelle I1 :

1. Retenir la lecture conservatrice de §35 : full reactor dû à Q5, avec budget distinct, et expliciter l'allocation temporelle de l'Architecture ; ou
2. Faire arbitrer explicitement la portée respective de §§19/34/35, avec clarification normative distincte si la clôture bornée sans full est retenue. Le mandat présent n'autorise pas cette clarification ni une dispense implicite.

Une Major Wave, intégration main, restructuration large ou infrastructure transverse non bornable déclenche de toute façon §19. Aucun nouveau déclencheur n'est observé dans la mission documentaire présente. **Aucune campagne T1, B1, G5, Architecture ou full reactor n'est autorisée/exécutée ici.**

## 7. D4-READ — PRINCIPLES APPROVED / NUMERIC LIMITS PENDING

Principes humainement approuvés :

1. Quota READ partagé de **deux permis dans l'allocation actuelle**.
2. Prévalidation pure avant acquisition du quota.
3. Aucun emprunt au pool Control pour HTTP.
4. Aucun accroissement arbitraire des permis.
5. Refus explicite et borné sous surcharge.
6. Construction cohérente des vues READ.
7. Sérialisation complète du DTO avant HTTP 200.
8. Tampons et concurrence HTTP bornés.
9. Aucun manifeste tronqué.
10. Aucune pagination métier supplémentaire des données devant être complètes.
11. Aucune valeur métier arbitraire pour contourner une limite technique.
12. Aucune promesse de débit ou de SLO sans mesure.

**Les plafonds numériques nouveaux sont PENDING.** Le quota 2 est approuvé uniquement dans l'allocation I8 actuelle ; ce n'est pas une qualification de saturation externe, ni une règle de dimensionnement d'une future topologie multi-instance.

## 8. Autorisation unique de calibration D4

Le responsable autorise une campagne préparée, strictement plafonnée à **30 minutes cumulées de temps mural**, toutes expériences et invocations de mesure confondues ; ce budget ne se renouvelle pas par profil, incident ou échec. Préparation statique séparée, sans développement I1 déguisé. Arrêt obligatoire, terminaison physique vérifiée, conservation des résultats et `NOT MEASURED` pour les lacunes à l'échéance ; aucune relance automatique.

Périmètre : Pot complet, manifeste, lots, Balance/rationnels, navigation, binding/résultats et erreurs ; profils 0/200/2 000, rationnels longs, UTF-8/échappements, frontières cardinalité/octets ; concurrence et quota seulement si un banc réellement disponible le permet sans modifier la production. Réemploi prioritaire de l'existant ; fixtures synthétiques isolées, aucune donnée sensible ni modification de production/tests/oracles/migrations/bases de référence. Aucun HTTP V3, benchmark permanent, campagne Maven/Architecture globale ou extension L6.

Le [rapport D4](L5_D4_Read_Calibration_Report.md) et ses [preuves](../testing/evidence/L5-D4/README.md) distinguent `MEASURED`, `CALCULATED`, `ESTIMATED`, `NOT MEASURED`. Les mesures, recommandations et résultats ne constituent **jamais une décision humaine acquise**.

## 9. Réserves, décisions ouvertes et prochaine décision

Conserver toutes les réserves transférées du plan L5 §3.2 : L1/F/F2 (parcours HTTP/Invitation et coûts Grand Pot/histoire), R-L2-01…05, G1/G2 INCONCLUSIVE et G3 NOT PASSED / NOT COMPLETED, L3 R1–R4, L4 lease180 non homologué production et prévalidation/HTTP non qualifiés. Aucun résultat de cette mission ne les ferme.

D4 numérique et D6-gates opérationnel restent à arbitrer avant I1 ; approbation du plan opérationnel I1 puis éventuel GO sont des décisions distinctes. La configuration d'issuer de qualification et les modalités de preuve AuthN restent nécessaires. D2, D3, D4-admission, D5, D6-topologie et D7 restent ouverts selon l'échéancier du plan ; ils ne deviennent pas tous des prérequis I1.

**Aucun GO I1, aucune API V3, aucune collection Bruno, aucun PASS applicatif, aucune certification SPEC-10 ni qualification production.** Documents soumis à revue, sans commit/push automatique. La mission s'arrête à la remise de cet acte et des résultats D4.
