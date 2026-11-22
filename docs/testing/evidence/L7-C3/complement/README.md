# L7-C3 — Preuves du gate complémentaire

Le [rapport de décision](../../../../v3/L7_C3_CommandResult_Complementary_Gate_Report.md) relie les éléments ci-dessous au pilote historique et distingue les essais complémentaires du premier gate conservé dans le dossier parent.

- [Ordre, révocation et crash](order.txt). Pages observées sur une connexion indépendante, PID et blocage du compteur, préfixe après terminaison de backend, protection du claim individuel et reprise WAL du serveur. Le [journal comprimé](order.log.gz) contient les deux erreurs de transport injectées avant COMMIT.
- [Regroupement SQL](grouped-sql.txt). CTE expérimentale sur la vraie V5 et contrôle négatif par collision UUID, sans changement d’adaptateur.
- [Premier rejeu composé dans le checkout partagé](composed-local-attempt/maven-gate.json). La découverte JUnit échoue sur un bytecode préexistant incohérent avant les cas Materialize. Les 943 cas exécutés ont réussi. [Archive](composed-local-attempt/maven-gate.tar.gz) · [Descripteur JVM fautif](composed-local-attempt/stale-bytecode.txt)
- [Gate composé sur la copie isolée](maven-gate.json). **PASS**, 43 modules, 1 163 tests, zéro erreur, échec ou skip. Le [journal Maven, les XML et les reçus des deux corpus Bruno](maven-gate.tar.gz) appartiennent à la même exécution.
- [Contrôle ciblé sur des classes compilées à neuf](targeted-fresh.tar.gz). Les 14 cas et le descripteur correct sont archivés.
- [Première campagne A/B](cost-screening/cost-summary.json). Les 18 fenêtres ont partagé l’hôte avec Maven. Le SQL synthétique de cette première campagne vérifie le lien depuis l’événement vers son effet. Le harnais final ajoute le contrôle réciproque dans les profils purs et un décompte des images WAL. Conserver les deux campagnes séparées.
- [Campagne postérieure au premier Maven](cost-final/cost-summary.json). Les 18 fenêtres vérifiées exposent le SQL et le WAL avec les contraintes expérimentales réciproques sur les profils purs.
- [Campagne principale sur classes fraîches](cost-fresh/cost-summary.json). 2 200 finalisations après le contrôle ciblé et avant le rejeu composé, avec les mêmes charges et paramètres A/B.
- [Diagnostic physique du WAL](wal-diagnostic/README.md). Huit fenêtres à un worker, inspection des enregistrements WAL par intervalle LSN.
- [Sources et commandes de reproduction](../../../../../experiments/l7-c3-gate/README.md).

Les premiers essais d’outillage ont permis de corriger l’oracle de révocation, le SQL de fixture, l’attribution d’une lane par partition et l’enrôlement complet du corpus. Seules les fenêtres avec tous les slots attendus, le nombre exact de finalisations et les assertions de densité satisfaites entrent dans les comparaisons.

[Vérification SHA-256 des preuves et de l’outillage](manifest.sha256).
