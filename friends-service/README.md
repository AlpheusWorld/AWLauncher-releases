# AWLauncher Friends service

The Cloudflare Worker in `worker.mjs` provides only social signalling. Minecraft traffic never passes through this API. Clients use the official e4mc mod (MIT, https://github.com/vgskye/e4mc-minecraft-architectury) installed from Modrinth with SHA-1 and required-dependency verification.

Production endpoint: `https://awlauncher.candytulip7.chatgpt.site/api/friends`.

## Deployment

Bind a Cloudflare D1 database as `DB`. Apply the append-only SQL migrations in `drizzle/` before publishing. Call `friendsFetch(request, env)` from the existing site Worker before its static-asset handler. A null return means the path is unrelated to this API. The existing public AWLauncher site provides the runtime and source deployment; its page assets remain unchanged.

## Protocol 1

All mutations are JSON POST requests, limited to 16 KiB. `register` accepts a client-generated UUID v4 `id`, a 256-bit base64url `token`, and a display `name`. Registration is idempotent for the same identity and token. The raw token is kept only on the client, protected with the launcher's existing Windows DPAPI/Linux Secret Service storage; the database keeps SHA-256. No Microsoft credentials are used.

Subsequent requests authenticate with `Authorization: Bearer <token>`. Tokens and request bodies must never be logged.

- `sync`: `{name, online, world}`; heartbeat every 10 seconds, offline after 45 seconds. Returns `friends`, incoming `requests`, and `outgoing` requests
- `request`, `accept`, `reject`, `remove`: `{id}` identifying the other user
- `invite`: `{id}`; both users must have accepted the friendship and the host must have a current world
- `decline`: `{id}` identifying the inviting host
- `health`: GET; checks the database schema, no personal data

World records contain a random session UUID, e4mc hostname, title, Minecraft version, loader and SHA-256 of the sorted enabled-mod hashes. The hostname is returned only to invited friends while the host remains online, the session matches, and the invitation has not expired (15 minutes). Only hostnames under `e4mc.link` are accepted. The service never accepts executable URLs or mod downloads from other players.

The invitation limits address disclosure through this API; it does not install a network ACL on the Minecraft world. Anyone who obtains the hostname may connect until the host closes the session. Display names are chosen by the device and are not verified Microsoft identities.

Rate limits, bounded friend lists, bounded payloads and random credentials constrain abuse. Users remove relationships through the launcher. Deleting the protected local identity file loses access to that device's friend list; copying it to another computer cannot normally unlock DPAPI-protected credentials.
