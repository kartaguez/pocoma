# L7.3 Completion — preuves

`initial-classification.json` conserve les 53 lignes et leurs catégories initiales. `reconciliation.json` relie chaque ligne aux XML et aux traces physiques de l’archive précédente, avec empreintes. `discovery-01` contient le nouveau rejeu ciblé. Les logs `historical-admin-before/after` et leur reçu conservent la reproduction et la correction sans accès aux données.

Validation en lecture seule : `python3 -B scripts/testing/check-l73-completion-evidence.py`. Le contrôle vérifie les allocations, les empreintes des XML et traces SQL, les invocations et la matrice, sans exécuter Maven ni PostgreSQL.
