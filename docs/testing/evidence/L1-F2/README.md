# L1-F2 — Preuves reproductibles

Voir [index](Gate-index.json), [rapport](../../../v3/L1_F2_Closure_Report.md), [manifeste figé avant gate](../../../v3/L1_F2_Proof_Manifest.json).

- `command-1792961062290431000.tar.gz` : gate propre sur e154276aa4f89a6619893e8d27150a8b2442d718, commande canonique, XML frais, log complet, entrées avant/après, classpaths/JARs et classes/ressources produites, environnement et preuves PostgreSQL. Empreinte dans l'index. Extraction dans un répertoire vide par `tar -xzf <archive> -C <destination>` ; les chemins internes sont relatifs.
- `COMMAND-summary.json` : 1265/0/0/1, 107 obligatoires PASS, transferts Java bornés et plans indexés PASS ; couverture complète INCONCLUSIVE, seul skip historique V2 conservé. `COMMAND-environment.json` et `COMMAND-external-verdict.json` : environnement exact, 1406 fichiers externes / 159 JARs utilisés contrôlés.
- `before/` et `before.tar.gz` (incluant le log Maven complet) : mesure propre sur 3544898353076d6839bd6f78122d52a5932a64b8, XML/log, identité de production avec baseline b3df3f3e, harnais source et cinq classes compilées strictement identiques, 110 JARs de ce run identiques au gate après.
- `after/F2-write-performance.json` : données brutes, SQL réellement émis et plans PostgreSQL de la mesure après incluse au gate. `Performance.json` : médianes et plages calculées depuis les deux fichiers bruts.
- `supplemental.tar.gz` conserve les sources/plans/logs du complément exact et le reçu du collecteur.
- `F2-*.txt` : anomalies locales/étrangères, références, groupement, fermeture concurrente et index. `Exact-close-plans.json` : complément du prédicat exact nouveau, chaque UPDATE expliqué dans une transaction annulée ; source `ClosePlans.java` et lanceur reproductible inclus. Les plans de fermeture du harnais apparié initial utilisent le prédicat antérieur ; ce complément vérifie explicitement les termes revision_id/active_from ajoutés. Aucun total de buffers de transaction n'est inventé : buffers par requête/DML expliquée, temps finalisation physique de bout en bout.
- `diagnostics.tar.gz` : diagnostics intermédiaires, première initialisation Docker refusée par sandbox, première mesure exploratoire, 63 PG PASS puis six F2 PASS, 7+7 tests des collecteurs. L'essai supplémentaire initial ClosePlans exigeait à tort un index sur 100 lignes et échoue sur le choix légitime Seq Scan ; log/source conservés. L'hypothèse du harnais seule est corrigée : un index est attendu aux grandes profondeurs, jamais forcé. Aucune source app/scripts ni gate e154276a modifié pour ce complément.
- `Preservation.json` : deux checkouts préexistants et leurs branches/états préservés. `Changed-files.json` et `Publication-provenance.json` : périmètre et concordance des entrées exécutables avec le SHA testé.

Reproduction avec Java 21, Maven wrapper et Docker disponibles, même image PostgreSQL (digest dans l'environnement) :

```bash
# Baseline avec harnais, production identique à b3df3f3e ; dans un checkout propre de 35448983 :
./app/mvnw -f app/pom.xml -pl infra-persistence-primary-jpa -am test -Dtest=WriteRevisionPerformancePostgresTest -Dsurefire.failIfNoSpecifiedTests=false -Df2.variant=before
# Depuis le checkout de livraison, entrées suivies propres hors evidence/L1-F2 :
python3 -B scripts/testing/verify-v3-l1-f2.py command
# Équivalent Maven canonique exécuté par ce collecteur, depuis app/ :
./mvnw -pl runtime-command-consumption-worker -am test
# Après compilation/tests, complément EXPLAIN de fermeture, depuis la racine :
python3 -B docs/testing/evidence/L1-F2/reproduce-close-plans.py
```

Le collecteur crée un nouveau dossier horodaté ; ne pas confondre une reproduction avec l'archive historique. Les fixtures ne sont pas une admission/Identity/Discovery de production. Chaque profil démarre à 100/1000/10000 versions, puis cinq warmups et dix commits mesurés ajoutent chacun une version (mesures à N+5…N+14). Le compteur SQL inclut SET CONSTRAINTS ; les lignes Java comptent les ResultSet.next() réussis, sans sous-estimer les listes historiques réellement consommées. Les mesures ne sont ni un SLO, ni un certificat V3 global, ni une preuve de temps constant absolu.
