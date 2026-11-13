# Pocoma V3 Local Playground

CLI: `./pocoma-local bruno --instance demo` selects only PG01, using private
process sessions from real Code+PKCE login. No GUI token cache is needed.

GUI4.2.1: `./pocoma-local gui --instance demo`, select **Instance-demo**, then
**00 Login Owner → Auth → Get Access Token** (third), **01 Login Guest → Auth →
Get Access Token** (guest), then **PG01 → Send**. No JWT copy/import/export.

See the [GUI/CLI guide](../../docs/v3/L7_Local_Playground_Bruno.md) for credentials,
PKCE/callback, session expiry and expected assertions. Login requests use native
OAuth cache IDs playground-owner and playground-guest. Tokens remain in the
native cache or private CLI process; generated Instance environments contain
only public URLs and are ignored by Git. LOCAL_PLAYGROUND_PARTIAL remains due
until human validation.
