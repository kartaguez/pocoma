# Étape 2.10 — Clôture de l'étape 2

## Objectif

Clore la réorganisation des domaines et engines en documentant la matrice des modules et la
propriété des types, en renforçant les frontières ArchUnit, en inventoriant le legacy restant et
en validant le reactor Maven complet.

## Travaux

1. Établir la matrice finale des modules et de leurs dépendances autorisées.
2. Cartographier les types partagés, fonctionnels, durables et legacy.
3. Actualiser la cartographie des use cases après les étapes 2.1 à 2.9.
4. Protéger les domaines et engines cibles avec ArchUnit.
5. Documenter les utilisateurs, remplacements et conditions de suppression du legacy.
6. Nettoyer les répertoires sources vides et contrôler les anciens artifacts Maven.
7. Exécuter les validations ciblées puis le build complet.

## Contraintes

- Aucun changement fonctionnel, SQL, JSON, endpoint ou configuration runtime.
- Aucun retrait du legacy tant que les nouveaux workers et adapters ne le remplacent pas.
- L'atomicité PostgreSQL de `ClaimPort` reste réservée à l'étape infrastructure.

