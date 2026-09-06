# Pocoma Bruno collection

The query requests in this collection still describe the retained read API. The folders named
`Pots Commands`, `Expenses Commands` and `Cleanup` are **historical / superseded**: their synchronous
mutation routes were removed by Lot 6.8.

The canonical write entry point is `POST /api/v1/commands`, authenticated through OAuth2 Resource
Server. This legacy collection is not an alternative write path and will be replaced by an
asynchronous admission collection in a dedicated API/tooling update.
