# L7-LB — correction du parcours OAuth natif Bruno GUI

Départ propre : f1ccd0dc1ab76d94eb0d73402e9b5178ebf771b6,
branche feat/l7-lb-local-playground. Verdict maintenu LOCAL_PLAYGROUND_PARTIAL.

## Diagnostic avant code

Erreur reproduite dans Bruno GUI4.2.1 : le pre-request collection exige
bearerOwner/bearerGuest via process.env ou environnement, alors que Local ne
contient que les URLs. Aucun auth:oauth2 n'est livré ; le guide demandait de
recopier des JWT depuis GUI-private.bru. La CLI injecte les sessions privées,
ce qui explique son PASS sans démontrer le GUI.

Inspection du code **installé** app.asar4.2.1 : auth Authorization Code génère
un verifier et S256, génère un state aléatoire si le champ est vide, vérifie
le state dans le navigateur intégré, intercepte la callback avant navigation,
stocke les credentials chiffrés et expose getOauth2CredentialVar avec
`$oauth2.<credentials_id>.access_token`. Auto-fetch intervient après pre-script :
les deux connexions doivent donc précéder PG01. Le realm actuel autorise déjà
exactement http://127.0.0.1:8765/callback et impose S256, publicClient=true,
directAccessGrantsEnabled=false ; aucun changement IdP nécessaire.

## Verification scope (déclaré avant implémentation)

Expected production impact: NONE (app/, SQL, POM, contrats, règles d'isolation).
Primary slice: LOCAL DISTRIBUTION / consommateur Bruno GUI et CLI.
Secondary slices: NONE Maven ; WEB/COMMAND/PROJECTION exercés par PG01 réel.
Forbidden production impact: authentification/autorisation serveur, données,
slots/baux, realm plus permissif, password grant, JWT en clair dans fichiers/environnements .bru/argv/logs.
Global architecture gate: NOT REQUIRED.
Full reactor: NOT REQUIRED (pas clôture globale ni intégration main).
Database verification: CURRENT instance possédée, API publiques ; aucune migration.
Next global gate: I6 selon politique ; inchangé par ce correctif.
Escalation: avant tout défaut produit avéré, déclarer la slice canonique affectée.

Fichiers : deux requêtes de connexion natives distinctes, session helper,
collection/PG01, préparation d'environnement public sans tokens dans runtime,
guide et tests régression/sécurité. Le CLI sélectionne explicitement PG01 pour
ne pas lancer de fenêtre OAuth. Préparation GUI ne redémarre/réinitialise pas
les bases ; préservation des données existantes. Aucun JWT exporté par login.

Vérifications prévues : tests unitaires existants ; contrôles JS contre l'API
Bru4.2.1 installée (absence/expiration/issuer/audience/identité distincte,
priorité CLI et cache natif), parsing .bru4.2.1, protocole Code+PKCE réel,
PG01 et CLI ; observation GUI quand réalisable, verdict humain toujours distinct.

## Adaptation de la preuve après interruption externe de l'environnement

Le 14 novembre, des admissions sont restées PENDING après des pertes de
connexions/pauses de threads. Les observations suivantes ont montré tous les
conteneurs arrêtés, puis un catalogue Compose vide et un cluster contenant
seulement pocoma, sans pocoma_write/read. Aucune suppression de volume n'a été
exécutée par ce correctif. L'origine de cette disparition n'est pas attribuée.
La conservation de l'ancien état n'est pas revendiquée sur cette phase.

Scope ajusté avant reprise : DB CURRENT V3 sur cluster possédé effectivement
vierge, via bootstrap existant et propriétés ciblées générées sur son adresse
réelle. Pas de migration historique V2, changement SQL/Java, reset, purge de
slot ou changement de bail. Les preuves GUI/CLI finales porteront sur cette
installation reconstruite, distinctes des observations de disponibilité échouées.
Les slices Maven/gates globales restent inchangées : code app/ non modifié.
