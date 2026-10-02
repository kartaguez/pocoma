# WRITE_ADMISSION — Dette résiduelle WA.6

```text
WA.6 FINAL AUDIT: PASS
WA.6 STATUS: CLOSED
Blocking/Major/Minor findings: 0
LOW hardening debts: 2
WA.7 STATUS: DONE
WA.8 STATUS: DONE
WRITE_ADMISSION STATUS: CLOSED
```

Cette dette LOW reste explicitement conservée après la clôture définitive du 2026-10-02 ; elle ne
bloque pas WRITE_ADMISSION et ne rouvre aucun lot.

Ce document enregistre les réserves résiduelles de l'audit final de WA.6. Elles concernent
uniquement le durcissement futur des preuves d'architecture : elles ne décrivent aucun défaut
runtime actuel, ne modifient pas le modèle canonique et ne justifient pas la réouverture de WA.6.

## DEBT-WA6-01 — Global binding lock-order guard is not semantically exhaustive

### Contexte

`Wa67BindingArchitectureTest` fournit aujourd'hui une garde utile contre les régressions connues :

- inventaire des accès SQL directs aux tables de binding ;
- séparation entre repository d'autorité et repository de stream ;
- vérification textuelle, dans `JpaExternalIdentityBindingAdapter`, que `streams.lock(...)` précède
  `repository.acquire(...)` et `repository.detach(...)`.

Cette garde ne démontre toutefois pas de manière sémantiquement exhaustive la propriété globale
« aucun chemin `authority -> stream` ». Un futur composant ou orchestrateur connaissant les deux
repositories pourrait par exemple introduire :

```java
authority.lockCurrentBinding(...);
streams.lock(...);
```

sans ajouter de nouvel accès SQL direct. La whitelist des propriétaires SQL pourrait alors rester
verte malgré l'inversion de l'ordre canonique des locks.

### Classification

- severity : `LOW` ;
- runtime defect actuel : `NO` ;
- WA.6 reopening required : `NO` ;
- canonical model impact : `NONE`.

### Pistes de durcissement futur

- interdire architecturalement l'injection ou la connaissance simultanée des repositories
  d'autorité et de stream en dehors du writer canonique ;
- utiliser une règle ArchUnit ou une autre analyse structurelle des dépendances et appels plutôt
  qu'un simple scan textuel ;
- garantir explicitement qu'un seul composant de production peut composer les deux locks, dans
  l'ordre canonique `stream -> authority`.

Ce durcissement n'est pas implémenté dans la clôture documentaire de WA.6.

## DEBT-WA6-02 — SQL ownership / append-only guards are syntactic tripwires

### Contexte

Les gardes actuelles scannent `src/main/java` afin de :

- localiser les occurrences des noms de tables ;
- interdire textuellement `update external_identity_binding_facts` et
  `delete from external_identity_binding_facts` ;
- maintenir une whitelist des classes autorisées à accéder directement aux tables.

Cette approche est utile et doit être conservée, mais elle constitue une tripwire syntaxique et non
une preuve SQL formelle. Des changements futurs pourraient théoriquement la contourner au moyen de
SQL construit dynamiquement, de fragments concaténés, de requêtes déplacées dans des resources SQL
ou d'une abstraction indirecte masquant le nom de table.

### Classification

- severity : `LOW` ;
- runtime defect actuel : `NO` ;
- WA.6 reopening required : `NO` ;
- canonical model impact : `NONE`.

### Pistes de durcissement futur

- renforcer les frontières d'accès DB par architecture, module ou API ;
- centraliser encore davantage les primitives de persistence ;
- ajouter éventuellement une analyse dédiée des resources SQL si de telles resources apparaissent ;
- conserver le test actuel comme garde de non-régression utile, sans le considérer comme l'unique
  preuve conceptuelle du caractère append-only.

Ce durcissement n'est pas implémenté dans la clôture documentaire de WA.6.

## Dette legacy WA.6 déjà acceptée

Cette dette est distincte des deux réserves d'audit ci-dessus. Elle était déjà connue, documentée et
volontairement acceptée avant l'audit final :

- la visibilité `COMMAND_RESULT` V1 repose sur le `userId` via `CURRENT_BINDING` ;
- la révocation V1 est `EVENTUAL` ;
- un detach suivi d'un rebind de la même ExternalIdentity vers le même User `U` peut restaurer la
  visibilité d'un ancien résultat V1 ;
- seule une future contraction V1 peut supprimer cette dette ;
- V2 exact-E ne présente pas ce comportement : sa visibilité repose sur l'ExternalIdentity exacte
  durablement capturée.

Cette dette legacy n'est pas un finding nouvellement découvert lors de l'audit final et ne justifie
pas davantage la réouverture de WA.6.
