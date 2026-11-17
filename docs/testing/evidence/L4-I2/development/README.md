# Diagnostics I2 de développement

Les archives conservent les FAIL, reproductions causales et contre-preuves. Elles ne certifient pas les octets finaux.
Les rapports de qualification officiels (`../canonical/` et `../architecture/`) ont leurs propres inventaires,
identifiants de producteurs, empreintes avant/après et contrôles de couverture sur le commit applicatif propre
`6496e6d0144ecfda9e58ec6d12e259445e7344ad`. Les sorties partagées suspectes ont été écartées
de la qualification après conservation de leur bytecode et de son désassemblage.
Outre les diagnostics initiaux d’implémentation I2, ce répertoire conserve la reproduction déterministe de
`I2-QUAL-01` (`renewal-diagnostic-reproduced.tar.gz`), la preuve d’échec du contre-oracle de non-réservation
(`renewal-unreserved-counteroracle-fail.tar.gz`), la validation ciblée du correctif de banc
(`renewal-fixed-targeted-pass.tar.gz`) et les journaux des deux exécutions intermédiaires non retenues
(`requalification-l3-waiterpid-cme-fail.tar.gz`).
Les résultats positifs partiels de ces campagnes restent des feedbacks bornés, sans conversion d’un
FAIL global en PASS. Les causes et corrections sont détaillées dans le rapport d’exécution I2.
