# Pocoma V3 Local Playground

Run `./pocoma-local start` then `./pocoma-local bruno` from the repository root.
For GUI, interactive PKCE, private environments and expected assertions, see
[the Bruno guide](../../docs/v3/L7_Local_Playground_Bruno.md).

PG01 uses only public WRITE/READ APIs. `inventory.json` defines the required
nonempty scenario/JUnit inventory. The wrapper never places a token in argv or
uses a full JSON response reporter. Do not commit private Bruno environments.
