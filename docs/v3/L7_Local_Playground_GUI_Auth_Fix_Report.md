# L7-LB — correctif OAuth natif Bruno GUI4.2.1

14 novembre 2026, Europe/Paris. **LOCAL_PLAYGROUND_PARTIAL maintenu**.
Ce rapport sera complété après la qualification sur SHA technique figé.
Le [plan préalable et son adaptation de scope](L7_Local_Playground_GUI_Auth_Fix_Plan.md)
consigne le défaut reproduit, les entrées et les preuves requises.

Le guide antérieur exigeait bearerOwner/bearerGuest sans connexion OAuth native,
alors que la CLI injectait ces sessions par environnement. Deux connexions
natives Owner/Guest utilisent maintenant Authorization Code+PKCE S256 et des
Token IDs distincts ; PG01 lit le cache natif. La préparation `pocoma-local gui`
écrit uniquement les URLs publiques. `login` en est un alias sans export JWT.
La CLI sélectionne explicitement PG01 et conserve ses sessions privées.

Aucun fichier app/, SQL, POM ou realm Keycloak modifié. Slices Maven modifiées
NONE ; scope LOCAL DISTRIBUTION/consommateur Bruno, API WEB/COMMAND/PROJECTION
exercées par PG01. Aucun franchissement imprévu de slice. Gate Architecture et
full reactor NOT REQUIRED/NOT RUN, car ce correctif ne clôt pas L7/I6 et ne
change aucune frontière productive. DB CURRENT V3 réinitialisée uniquement
par bootstrap sur le cluster possédé observé vierge ; aucune suppression/reset,
modification de slot ou de bail n'est exécutée par ce correctif.

La lecture GUI avait reproduit le message original. Les observations GUI
suivantes ont échoué : timeout d'accessibilité puis AppleEvent -1712. Une
validation automatique des modules installés n'est pas une validation humaine
du rendu et du login GUI. L'humain doit suivre le [guide corrigé](L7_Local_Playground_Bruno.md)
et confirmer les deux connexions puis le test PG01 avant toute promotion.
