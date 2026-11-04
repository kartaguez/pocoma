# Pocoma V3 — L5 — Calibration préalable D4-READ

Date : **4 novembre 2026**. Référence : **`5193c540c5be2ea6bf31d4fe159c02490ab352aa`**, branche `feat/v3-architecture`.

**D4 PARTIALLY CALIBRATED — ADDITIONAL DECISION REQUIRED.** Les tailles et allocations de représentations synthétiques sont observées ; les délais SQL, le pic mémoire HTTP et la saturation externe ne le sont pas. **NUMERIC LIMITS PENDING ; aucun GO I1.** Rapport soumis à revue avant publication.

## 1. Mandat et protocole réellement exécuté

L'[acte de décision](L5_D1_D6_Ratification_and_D4_Calibration_Decision.md) porte les approbations humaines et leurs conditions, distinctes des propositions ci-dessous. Autorités : [SPEC-06 §§4–6/11](SPEC-06_Supra_Contracts.md), [SPEC-09](SPEC-09_Performance.md), [SPEC-10 READ-01/S-01/S-02/PERF-01](SPEC-10_Test_Certification.md), [plan L5 §§9/12–15](L5_Supra_Entry_Audit_and_Implementation_Plan.md) et [politique de vérification §§17/19/34/35](../testing/Reactor_Verification_Policy.md). Aucune modification de ces contrats.

Préparation : état Git/distant, lectures normatives, inventaire des vues/limites/pools et des outils ; préparation de deux fichiers de reproduction archivés dans le [dossier L5-D4](../testing/evidence/L5-D4/README.md). Aucun code applicatif compilé par Maven et aucun serveur démarré.

Une seule invocation expérimentale :

```sh
python3 docs/testing/evidence/L5-D4/run_once.py
```

Le pilote vérifie branche/SHA, absence de modification/untracked sous `app/` et `scripts/v3/`, absence d'options JVM implicites, empreintes de toutes les entrées applicatives/SQL suivies et disponibilité des trois JAR locaux. Il compile **deux sources pures existantes** (`Fraction`, `ReadPotV3Result`) et le reçu temporaire de mesure dans un répertoire temporaire. Puis une seule JVM exécute sériellement les profils, avec deux encodages simultanés dans un sous-cas explicite. Ni réseau, Spring, Docker, JDBC, PostgreSQL, transaction, autorité de référence ni base cible.

Mesure : construction une fois ; trois encodages de préparation et cinq échantillons enregistrés par profil avec Jackson `writeValueAsBytes` ; un encodage supplémentaire dans un tampon expérimental fixe de 4 Mio. Les coûts CPU/muraux et les octets alloués par le thread viennent des compteurs JVM. Toutes ces opérations, y compris les encodages de préparation, sont dans le compteur unique. Les cinq échantillons courts ne constituent aucun percentile/SLO.

Ce reçu n'est ni un contrôleur I1, ni un test canonique, ni un framework permanent de benchmark. Les enveloppes de mesure sont des maps explicites ; `Fraction` devient `{"n":"…","d":"…"}`, dates ISO, UUID canoniques, versions jusqu'à `Long.MAX_VALUE`. L'encodage des `Optional` de participation est explicitement converti en valeur/null. Aucun objet d'autorité ou de persistance n'est exposé. Les futurs DTO/mapper HTTP peuvent changer les tailles : validation T1 indispensable.

## 2. Environnement et provenance

- HEAD, suivi local et distant vérifié identiques au SHA ci-dessus ; plan publié sans delta, worktree initial propre ; cinq autres worktrees préservés.
- macOS arm64 ; Temurin OpenJDK **21+35 LTS**, `javac 21` ; Python **3.14.7**. Détails et empreintes dans [environment.json](../testing/evidence/L5-D4/environment.json).
- Jackson annotations **2.21**, core/databind **2.21.2**, JAR locaux SHA-256 enregistrés. La version Jackson 2 correspond au BOM Spring Boot 4.0.5 local (`jackson-2-bom.version=2.21.2`), distinct du BOM Jackson 3 également présent. Aucun téléchargement ni artefact applicatif `target/`/installé réutilisé.
- JVM isolée `-Xms64m -Xmx256m` ; heap maximal observé **268 435 456 octets**. Cette configuration de banc n'est pas une allocation de production ni le matériel dédié SPEC-09.
- Sources exactes et entrées `app/` + `scripts/v3/` suivies hachées avant puis comparées après, identiques. Les résultats bruts, les commandes argv normalisées et les PID sont archivés ; aucun secret, bearer, EUI personnelle ou donnée réelle utilisé.

Les outils existants identifiés sont les codecs Jackson READ, les fixtures I8, `ReadFacadesPostgresTest`, `ReadWorkerResourcesPostgresTest`, les scénarios de coexistence et le sélecteur `scripts/testing/verification.py`. Les tests I8 couvrent historiquement Q/Q+1 et les ressources réelles, mais leur fixture implique une composition Worker/PostgreSQL et une campagne plus large. Ils n'ont pas été relancés ni présentés comme mesures fraîches. Aucun banc HTTP V3 livré ne permet encore d'observer ce contrat.

## 3. Budget cumulé et arrêt

| Élément | Observation MEASURED |
|---|---:|
| Début UTC | 2026-11-04 06:48:18.903674 |
| Fin UTC | 2026-11-04 06:48:21.056127 |
| Compilation isolée, PID 35889 | 0,446105 s |
| JVM de mesure, PID 35892 | 1,657656 s |
| **Campagne totale, orchestration/contrôles finaux inclus** | **2,152463 s / 1 800 s** |
| Relances automatiques | 0 |
| Connexions DB | 0 |

[campaign.json](../testing/evidence/L5-D4/campaign.json) : les deux enfants sont récoltés, exit code 0, leurs groupes de processus physiquement absents ; le répertoire des classes est supprimé. Aucun processus/transaction résiduel du banc. Le pilote réserve 10 s avant l'échéance pour TERM/KILL et récolte, sans renouveler le budget. La campagne est **terminée**, pas suspendue avec une autorisation résiduelle de relance. Aucun incident à rejouer ; aucune mesure supplémentaire nécessaire à l'interprétation n'a été déguisée en préparation.

## 4. Profils synthétiques

Les volumes sont des profils de mesure, **aucune limite métier** :

- Pot : 0/200 références avec 20 Shareholders, 2 000 avec 100 ; variantes simples et renforcées. Manifeste complet ordonné, sans Financials Expense dans le Pot principal.
- Renforcé : versions de référence `Long.MAX_VALUE`, fractions canoniques de 1 024 chiffres (entiers consécutifs copremiers), soldes appariés positifs/négatifs ; libellés/noms avec `é`, `漢`, emoji, guillemet, antislash, LF et tabulation, motif répété 32 fois. Les autres versions locales restent 1/2/3 selon les vues.
- Lots : 49/50/51 références, puis 85/86 pour la frontière de chunk JDBC de 256 clés ; 20 participations spécifiques par Expense et rationnels courts. Variante 50 × 20 avec 256 chiffres et UTF-8 ; stress 50 × 100 avec 1 024 chiffres et UTF-8. Les contenus sont construits distinctement ; le banc n'appelle pas le filtre interne `fits`.
- Balance : vecteur 100 rationnels de 1 024 chiffres ; cette ligne mesure le vecteur seul, sans enveloppe finale de Balance.
- Navigation : 20/100/101 UUID et curseur synthétique versionné de 67 caractères, sans pouvoir d'autorisation. Binding propre et résultats génériques sans EUI ; erreur générique fixe.
- Frontières d'octets : objet synthétique `payload` de taille totale 4 Mio − 1, exactement 4 Mio, 4 Mio + 1. Il teste le tampon isolé ; ce n'est pas un Expense métier.
- Deux threads synchronisés encodent chacun le Grand Pot renforcé. Aucun troisième appel SQL ni HTTP n'est simulé sous le nom de quota réel.

## 5. Résultats physiques — MEASURED sur ce banc seulement

Octets JSON UTF-8 non compressés, allocations cumulées du thread et maximum des cinq temps muraux d'encodage préparé ; détails CPU et chaque échantillon dans [measurements.json](../testing/evidence/L5-D4/measurements.json). « Construction » inclut les objets synthétiques et le mapping local, **pas** les résultats JDBC, décodages de persistance ou toute la construction READ réelle.

| Profil | UTF-8, octets | Allocation construction, octets | Allocation maximale encodage, octets | Encodage mural max, ms |
|---|---:|---:|---:|---:|
| Pot 0 simple | 5 192 | 277 816 | 151 888 | 1,143 |
| Pot 200 simple | 29 391 | 64 616 | 80 968 | 0,464 |
| Pot 2 000 simple | 267 231 | 436 144 | 876 152 | 1,598 |
| Pot 0 renforcé | 103 504 | 319 456 | 905 368 | 2,943 |
| Pot 200 renforcé | 138 503 | 339 112 | 1 008 952 | 1,001 |
| **Pot 2 000 renforcé** | **863 623** | **1 804 400** | **5 487 640** | **4,406** |
| Manifeste 0 / 200 / 2 000, versions larges | 15 / 35 014 / 350 014 | voir brut | voir brut | 0,014 / 0,167 / 0,414 |
| Batch 49 / 50 / 51, 20 participants courts | 110 140 / 112 387 / 114 634 | voir brut | voir brut | 0,744 / 0,723 / 0,712 |
| Batch 50 × 20, rationnels 256, UTF-8 | 683 287 | 3 755 672 | 10 184 184 | 3,894 |
| **Batch 50 × 100, rationnels 1 024, UTF-8** | **10 848 087** | **35 724 920** | **107 334 888** | **74,194** |
| Entrée batch 49 / 50 / 51, versions larges | 8 671 / 8 846 / 9 021 | voir brut | voir brut | 0,019 / 0,025 / 0,022 |
| Batch 85 / 86, participants courts | 191 032 / 193 279 | voir brut | voir brut | 1,226 / 1,193 |
| Balance 100, rationnels 1 024, vecteur seul | 210 351 | 1 476 144 | 2 133 160 | 1,407 |
| Navigation 20 / 100 / 101 | 874 / 3 994 / 4 033 | voir brut | voir brut | 0,041 / 0,058 / 0,022 |
| Binding / résultat Command / résultat Identity / erreur | 102 / 181 / 134 / 81 | voir brut | voir brut | ≤ 0,015 |

La première construction comprend des coûts de premier usage/JIT et n'est pas comparable comme tendance à la deuxième. Les cinq passages préparés ne prouvent ni coût froid stable ni capacité soutenue.

Tampon fixe : 4 194 303 et **4 194 304 octets acceptés intégralement**, 4 194 305 refusés ; encodage réussi identique octet pour octet à `writeValueAsBytes`. Le batch lourd dépasse également le tampon. **Il n'y a aucun statut HTTP dans ce banc** : l'observation est une exception d'encodage isolée, sans preuve de `503`, de non-commit du `200` ou de récupération mémoire HTTP.

Deux encodages simultanés : **863 623 octets chacun**, **5 512 368 octets alloués par thread**, temps muraux **3,691209 et 3,694459 ms**. Les deux threads terminent. Cela observe seulement Jackson/CPU sur cette machine, sans charge Worker, envoi réseau ou acquisition SQL.

## 6. Calculs et estimations, séparés des mesures

**CALCULATED :**

- 64 Kio = 65 536 octets, soit **7,41 fois** l'entrée batch 50 mesurée à versions larges. Le plafond de cardinalité demeure distinct ; un corps de 51 références doit être refusé même s'il tient en octets.
- 4 Mio = 4 194 304 octets, soit **4,86 fois** le Pot renforcé 100/2 000 et **6,14 fois** le batch renforcé 50 × 20. Marge inutilisée respective **79,41 % et 83,71 %**. Ces ratios ne sont pas des garanties pour des textes/rationnels non bornés ni pour un autre DTO.
- Un tampon fixe de 4 Mio implique **8 Mio de seule capacité de tampon pour deux requêtes**, 16 Mio pour quatre. Une copie entière supplémentaire double ces contributions. Les vues, décodages JDBC, temporaires décimaux, Jackson, TLS/servlet et queues s'ajoutent. La JVM finit à 33 885 232 octets utilisés : cet instantané global **n'est pas un pic ni un coût par requête**.
- Code vérifié : `JdbcSubobjectVersionStore.CHUNK=256`, trois clés par Expense. Un batch de N entraîne `ceil(3N/256)` SELECT de sous-objets, **1 à N=50 et 85, 2 à N=86**. Aux chemins nominaux s'ajoutent un SELECT Binding et un SELECT PotVersion : **3 / 3 / 4 SELECT métier calculés**, hors opérations de transaction/driver. Aucun nombre JDBC observé physiquement.
- Pot principal : `2+3S` clés de sous-objets pour S Shareholders, sans contenu Expense. Pour S=100, deux chunks ; Binding + CurrentPot + PotVersion + Balance + deux chunks = **6 SELECT métier calculés** en courant nominal, **5 en historique**, hors transactions/driver. L'objet PotVersion décodé contient aussi les ensembles internes d'autorisation absents de la vue publique mesurée.
- Pools : Business `M+2`, Control `M+6`, min idle = max ; à M=4, quatre pools réservent **32 connexions configurées** par racine Worker. La réserve READ est `Q ≤ (M+2)−M−H`, donc **2 si H=0** selon l'allocation I8. Aucun contrôle live des connexions libres, du serveur ou de H dans cette mission.

**ESTIMATED :** une enveloppe de **16–32 Mio pour la sérialisation par requête**, à éprouver, et **2–5 s de budget de consultation à étudier dans T1** sont des pistes de configuration, pas des bornes démontrées. Exemple : le tampon fixe et ses temporaires ont alloué 13 137 784 octets pour le batch 50 × 20 renforcé, mais 37 356 592 avant refus du batch lourd. L'allocation cumulée peut excéder le pic vivant ; inversement elle ne couvre pas toute la pile HTTP. Aucun facteur constant octets JSON→heap n'est démontré. Aucune borne sur la vue complète ne découle de 4 Mio : les chaînes, rationnels, participants et ensembles d'autorisation internes peuvent être plus gros avant le contrôle final.

Le chemin `writeValueAsBytes` matérialise un tableau UTF-8 final et des segments Jackson ; le chemin de mesure fixe réserve 4 Mio même pour l'erreur de 81 octets. Ces chemins sont comparatifs : **ne pas retenir ce coût fixe pour chaque petite erreur en I1**. Une réserve d'erreur indépendante proposée à 1 Kio doit être vérifiée sur les enveloppes finales, sans reflet d'entrée arbitraire.

## 7. NOT MEASURED et preuves I1 nécessaires

| Donnée indisponible | Pourquoi | Vérification T1 à prévoir, sans l'exécuter ici |
|---|---|---|
| Temps SQL, quantiles, plan/index, octets de persistance lus | Aucun PostgreSQL lancé/connecté | Fixture READ isolée, données 20/200 et 100/2 000, chronométrage et trace JDBC ; lot 50 sans N+1 |
| Q=2 puis refus Q+1, capacité réellement disponible des pools | Pas de composition Services active ; les anciens tests I8 ne sont pas rejoués | Retenir deux consultations réelles ; troisième refus avant emprunt ; zéro Control, libération physique permis/connexion et progression Worker |
| Prévalidation pure avant quota | Correction I1 encore absente | Deux permis occupés, entrée invalide refusée sans quota ni SQL |
| Pic/retention heap de vue et sérialisation HTTP | Compteurs de thread = allocations cumulées ; pas de contrôleur/serveur | Mesure de heap/allocations sur stack réelle, DTO et erreurs finaux, copie/tampon/transfert, Q libéré mais capacité HTTP retenue |
| Effet HTTP d'un corps ou d'une réponse trop gros | Tampon de mesure isolé, pas de serveur | Entrée borne/borne+1 avant parsing ; sortie borne/borne+1 et dernier élément défaillant, aucun préfixe/entête de succès, erreur 503 bornée |
| Délais de consultation et annulation, restitution après déconnexion | Aucun appel SQL ni transfert réseau | Délais locaux READ sans modifier Worker, annulation/déconnexion et résolution physique ; un timeout logique seul ne libère pas une ressource incertaine |
| Taille transférée et buffers de compression | Aucun transport ; aucune compression HTTP activée par le banc | Déclarer compression désactivée pour I1 ou mesurer transformation complète et copies avant commit HTTP |
| SLO, débit, capacité externe soutenue et équité | Campagne volontairement synthétique et courte | Non prouvés par T1 local ; protocoles SPEC-09/10 et L6 restent distincts |

Le `fits` actuel est un budget textuel conservateur après construction de la vue, pas un plafond d'octets HTTP ni une preuve d'allocation préventive. Les fixtures I8 emploient batch **50**, page **20**, budget interne **16 000 000** ; la production exige des propriétés explicites sans défaut public. Le stress batch peut être refusé par ce budget interne avant encodage dans l'application réelle : sa taille de 10,8 Mo ici ne prouve aucune réponse réelle autorisée. Aucun de ces paramètres internes n'est modifié.

## 8. Propositions D4 — toutes nouvelles valeurs PENDING

Unités binaires : Kio = 1 024 octets, Mio = 1 048 576 octets. Les mêmes plafonds de transport proposés couvrent les consultations ; pas de seuil par opération ajouté pour gagner artificiellement de la mémoire.

| Paramètre | Valeur proposée | Fondement | Marge | Risque | Décision |
|---|---|---|---|---|---|
| Cardinalité maximale Expense batch | **50** | MEASURED tailles 49/50/51 ; CALCULATED 150 clés/1 chunk ; candidat SPEC-06 et fixture I8 | Grand manifeste consultable en 40 lots maximum de 50 sur le profil 2 000 ; pas de limite du manifeste | 50 Expenses peuvent néanmoins dépasser les octets ; vérifier refus autonome T1, pas de preuve SQL de latence | **PENDING** |
| Taille maximale du corps HTTP entrant READ | **64 Kio**, octets reçus et décompressés | MEASURED entrée 50 = 8 846 ; CALCULATED ×7,41 | 86,50 % libres sur ce profil | Forme finale, profondeur, en-têtes et décompression à qualifier séparément ; aucun D4-admission WRITE approuvé | **PENDING** |
| Taille maximale d'une réponse READ | **4 Mio UTF-8 non compressés**, enveloppe comprise | MEASURED Pot renforcé 863 623, batch renforcé 683 287, frontières exactes du tampon | 79,41 % libres sur Pot ; 83,71 % sur batch | Aucun plafond fini n'accepte tout texte/rationnel légitime non borné ; un refus ne devient pas une interdiction métier | **PENDING** |
| Taille maximale d'une page navigation | **100 entrées** | MEASURED 3 994 octets avec curseur synthétique ; CALCULATED `limit+1=101` petit | Très inférieur à 4 Mio ; 5× la page de fixture I8 | Pas de latence SQL/index mesurée ; liste non autorisante, pas de snapshot inter-pages | **PENDING** |
| Mémoire maximale de sérialisation par requête | **Fourchette 16–32 Mio à qualifier ; candidat de travail 32 Mio**, hors vue et transport | MEASURED allocations sur banc ; ESTIMATED enveloppe, aucune mesure de pic | Tampon 4 Mio inclus ; marge globale **non démontrée** | Ne peut pas être ratifiée comme borne physique sur ces seules données ; T1 doit mesurer heap, temporaires, erreurs et copies | **PENDING — INSUFFICIENTLY DEMONSTRATED** |
| Nombre maximal de requêtes HTTP en vol | **Candidat 2**, de l'admission HTTP à fin/abandon d'envoi, sans queue non bornée | CALCULATED deux tampons = 8 Mio ; cohérent avec Q existant ; capacité réelle NOT MEASURED | À enveloppe 32 Mio, réserver au moins 64 Mio pour cette seule composante, plus vues/transport/Worker ; disponibilité non vérifiée | Aucun débit promis ; clients lents et refus d'erreur doivent eux-mêmes rester bornés ; quota SQL ne remplace pas cette limite | **PENDING — CONDITIONAL ON T1 MEMORY** |
| Budget temporel des consultations | **Fourchette 2–5 s à étudier**, aucune valeur arrêtée | SQL/HTTP NOT MEASURED ; simple ESTIMATED plage d'essai, sans dérivation des ms Jackson | Marge physique **inconnue** | Délais I8 1 s emprunt / 60 s statement / 65 s transaction / 70 s socket ne prouvent pas ce budget ; réduction locale et terminaison à prouver T1 | **PENDING — INSUFFICIENTLY DEMONSTRATED** |
| Quota de consultations SQL | **2 — principe approuvé** | Allocation I8 vérifiée statiquement ; CALCULATED réserve M+2−M, si H=0 | À conserver, aucun Control | Saturation externe et allocation multi-instance non qualifiées | **APPROVED IN CURRENT ALLOCATION** |

Complément technique proposé, également **PENDING** : réserver une enveloppe d'erreur fixe ≤ **1 Kio** (81 octets sur la représentation de mesure) et ne pas activer de compression I1 sans mesure de ses buffers/transformations. Cela ne change aucun statut HTTP D1. Le header BindingToken n'est pas dans le budget de corps : ses bornes et celles du bearer, du curseur et des en-têtes serveur doivent être explicitement traitées dans le plan opérationnel I1 ; aucune longueur maximale d'identité ou règle métier n'est inventée ici.

## 9. Risques et arbitrages restants

La recommandation 4 Mio accepte confortablement les profils mesurés, pas tous les Pots légitimes possibles. Si un Pot complet légitime excède ce budget, il faut arbitrer ressources/format selon SPEC-06 ; jamais tronquer ni imposer une deuxième pagination de manifeste. Pour les détails, un client peut réduire un lot quand cela préserve le contrat ; un Expense unique trop gros nécessite un arbitrage, pas un nouveau maximum arbitraire de participants/chiffres.

Les valeurs 32 Mio/2 requêtes/2–5 s ne constituent pas une solution prouvée au coût des vues déjà chargées. Le plan I1 doit expliciter comment l'admission HTTP couvre construction, encodage et transfert, comment le budget interne et le plafond réel interagissent, et comment les ressources restent réservées tant qu'une terminaison est physiquement incertaine. Sans cela, le bornage mémoire global reste ouvert malgré les tailles favorables.

D6-gates : la composition de G5 et les checkpoints sont recevables comme allocation cible ; l'[acte §6](L5_D1_D6_Ratification_and_D4_Calibration_Decision.md#6-d6-gates--approved-with-policy-conditions) expose la tension précise §35 CERTIFICATION (« Required lot closure » / full non filtré) face à la proposition sans full des §§19/34. **Aucune ratification définitive de dispense de full ; arbitrage de gouvernance nécessaire.** Aucun changement de politique dans ce mandat.

Réserves L1–L4 entièrement conservées, notamment lease180 non homologué, R-L2-01…05, L3 R1–R4 et absence de certification globale. AuthN architectural approuvé, fournisseur réel toujours à qualifier ; D3/Link et D6-topologie future restent ouverts selon leur échéance, sans blocage artificiel de la seule préparation I1.

## 10. Verdict de préparation et vérification documentaire

**D4 PARTIALLY CALIBRATED — ADDITIONAL DECISION REQUIRED.** Base suffisante pour soumettre batch 50 / entrée 64 Kio / sortie 4 Mio / page 100 à arbitrage, avec les réserves de forme finale et d'exhaustivité. Mémoire, concurrence HTTP et délai exigent un choix humain conditionnel et des preuves T1 dans le futur mandat ; ils ne sont pas fiabilisés par une allocation cumulée ou quelques ms Jackson.

Avant un éventuel GO I1 : ratifier ou ajuster les nombres et leurs conditions ; choisir la gouvernance D6-gates/full/Architecture ; approuver le plan opérationnel I1 (dont preuves mémoire/temps, contrats de refus et configuration d'issuer). **Le GO reste une décision humaine distincte.** La qualification fournisseur reste due avant acceptation externe. D2/D3/D4-admission/D5/D6-topologie/D7 restent ouverts pour leurs incréments respectifs.

Vérification déclarée : **documentation et expérience isolée ; slices primaire/secondaires NONE ; impact production interdit ; DB NONE ; franchissement inattendu NONE ; Architecture globale NOT REQUIRED / NOT RUN ; full reactor NOT REQUIRED / NOT RUN pour cette mission**. Aucun Maven, test canonique, oracle ou migration modifié/exécuté. Commandes de mesure exactes dans le reçu ; vérifications Git/liens/empreintes dans [Documentary_Verification.json](../testing/evidence/L5-D4/Documentary_Verification.json), dont `git diff --check`. Aucun PASS applicatif, certification SPEC-10 ou qualification production. Livrables remis à revue sans commit ni push ; arrêt de la mission après remise.
