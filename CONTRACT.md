# DailyFortunes integration contract

Pure Java 17 backend: `cn.pixelmc.dailyfortunes.service.FortuneBackend`.
Constructor `(String jdbcUrl, String user, String password, Path data)` performs no database I/O. All operations run on bounded background executors, never on game threads. Manager owns one instance; standalone mod mode owns one instance instead. Business tables are exclusively `df_*`: managed mode uses Manager's unified database, standalone mode may use a separate database. Source/resource embedding includes only `service/**` and `defaults/fortunes.json`.

`attachPlatform(Function<JsonObject,JsonObject>)` binds the namespace-scoped Manager authority. `null` restores standalone behavior. `static storageManifest()` declares schema version 1, global scope and all eight durable tables. The game bridge calls `ManagerBridgeApi.registerStorage(String extension,String manifestJson)` after ordinary extension registration; older Manager APIs fail clearly instead of silently falling back to another database.

Directory operations: `{op:"profiles.list"}` returns `{players:[{id,name,firstJoinedAt,lastSeen}],revision}` with the complete global directory. `{op:"profiles.get",player:uuid}` returns `{profile}` or null. `{op:"profiles.loginContext",player:uuid}` returns `{admitted,eventId,firstGlobalLogin,firstArchiveLogin,archiveId}`; managed RPC requires admission and a trusted nonempty event ID. Importing a profile is not a login. Core failures fail closed, never fall back to stale plugin identity. No profile callbacks reference Minecraft classes.

Managed `/players`, `/calendar` and `/state` project global profiles, including players with no fortune participation. Projection does not insert `df_players`, issue receipts or alter tokens. `df_players` is only legacy participation/cache in managed mode. New record owner names come from Manager; existing `owner` snapshots remain immutable. Player/plugin-local deletion must never remove global profiles or avatars.

REST: `JsonObject request(String method,String path,JsonObject body,Map<String,String> query,String actor,Set<UUID> online)`; authenticated admin only. Paths relative to `/api/dailyfortunes`:
`GET /state`, `/fortunes`, `/fortune?id`, `/players`, `/calendar?player&month`, `/history`, `/backups`, `/backup/verify?file`, `/backup/download?file`, `/backup/checksum?file`, `/config/export`.
`POST /fortune {fortune,expectedRevision}`, `/fortune/delete {id,expectedRevision}`, `/settings {settings,expectedRevision}`, `/recover {player,date,requestId}`, `/undo {id}`, `/backup`, `/config/preview {file}`, `/config/import {file,checksum}`, `/config/reload`.
List filters/sort/page/size follow the Manager agent proposal. Times are epoch milliseconds, dates ISO local dates, page is zero based, size 1..100. Errors throw IllegalArgumentException (validation), IllegalStateException (conflict/unavailable); Manager maps without leaking JDBC/credentials. `status()` cached health only.

RPC: `JsonObject rpc(String op,JsonObject data,String playerProof,Set<UUID> admitted)`.
`data.args[0]` is always player's UUID; playerProof must equal that UUID and admitted must contain it. Managed mode also requires the trusted core login context and a current core profile. Other args:
- login: `[uuid,name]` (admitted real players only)
- draw: `[uuid]` → `{record}`; only today's one draw, draw-time snapshot fixed.
- resolve: `[uuid,date,token]` → `{record}`; unread→loose.
- archive: `[uuid,date,token]` → `{record}`; loose→archived; caller consumes only after success.
- recover: `[uuid,date,requestId]` → `{record}`; rotate token, old copies invalid; archived rejected.
- calendar: `[uuid,yyyy-MM]` → `{profile,records,today,settings}`; all own records that month (only archived selectable).
- pending: `[uuid]` → `{records}` max32 pending deliveries; do not automatically re-deliver stale tokens.
- ack: `[uuid,date,token]` clears delivery pending after inventory grant.
- read: `[uuid,date,token]` validates ownership/current token.
- display: `[uuid,date]` → `{record,settings}` archived only; caller verifies block ownership/range and persists its display snapshot.

Record JSON: `{id,player,owner,date,fortuneId,state,at,snapshot,token,pending}`. States unread/loose/archived. `token` is a fresh unpredictable UUID for physical receipt validity. Administrative responses omit token; game RPC includes it. Web recovery queues a receipt for admitted player's next pending poll, including after reconnect/restart. No rewards or effects. Client NBT never authorizes a transition.

Standalone backups return metadata list; download JSON `{filename,text,checksum}`. Managed attachment cancels the independent 12-hour schedule, and Manager coordinates the complete namespace snapshot. The host must route managed backup endpoints through its platform coordinator. Config preview returns `{file,checksum}`; import verifies checksum and definition validity. `close()` stops background backup scheduler. Failed storage preserves qualifications/items; old physical receipts may remain after a crash but cannot be archived twice or rerolled.
