# Fabric 1.21.1 verification / 验证记录

Baseline: Forge1.20.1 v0.3.0 / commit8bf18ea. Port: v0.3.1, Minecraft1.21.1, Java21, FabricLoader0.16.14, FabricAPI0.116.7+1.21.1. Build: Gradle8.8/Loom1.7.4 with official mappings, at most2 Gradle workers. Existing Forge code/release remain0.3.0.

## Automated service and storage

Full build passed. Real isolated MariaDB11.4.9 at127.0.0.1:43322 (never production) ran ServiceChecks70030 and packagedStorageChecks70033; these include70000 physics/distribution iterations, so the counts are not70000 distinct business scenarios. The packaged test loads the relocated H2/MariaDB drivers from the distributable development JAR. SQL stale-edit/import/selective-undo/backup/complete+legacy restore regressions passed31 assertions. Unified global-directory integration passed31, RPC receipt contract11, shared display selection7. H2 reopen, per-save world UUID and independent daily quota checks passed. No schema/Manager RPC contract changes.

## Native game execution

Isolated dedicated startup and graceful save/stop passed. Development-only src/smoke asserts 13 including C2S/S2C codecs runtime properties: all3 recipes actually loaded, physical receipt and BarrelId persistence, ChestEntity owner/display save+update tag, four facing collision shapes. It is excluded from release JARs.

Real native client UUID5b615aa5-0042-3651-8971-2294273b0cec joined the standalone isolated server. On the game thread, the fixture calls the actual registered barrel item use; places the unread receipt in the off hand; calls the shrine's useItemOn twice immediately; confirms one token survives interpretation and the busy guard rejects overlap; opens the cabinet while holding the barrel; confirms the physical slip remains while async archival is pending, then disappears after success; and sets the archived display through session/range-validated GameRuntime.request. The final snapshot matches the real player/date. FlowSmoke PASS, server save/stop and client exit0 were observed.

Native client resource/model/UI smoke passed: main menu, inventory icons, 3D barrel/shrine/cabinet, reading page, month calendar, floating receipt with collision overlays, long-text scrollbar, default vanilla model profile, manually enabled Classic, and resource reload back to vanilla. Ten screenshots were generated and key reading/calendar/model views visually inspected. The Minecraft1.21 Screen.render automatic-background change required an explicit one-time background path to preserve visible reading/calendar content.

All three final release mods also passed a separate Manager isolated combined-server admission/profile/reconnect/restart/stop check. Evidence is recorded by the coordinating Manager report, using shared0.6.0.web.2 service and the exact release-JAR hashes.

## Evidence and limitations

Local logs: build-port.log, smoke-server.log, smoke-client.log, flow-server.log, flow-client.log. Screenshots: build/verification/screenshots and build/verification/art-profiles. The release verification ZIP contains those logs/images, with no runtime DB, config or credentials.

The first1.21 flow client and server shared the old run directory and logged file-rotation conflicts for latest/debug logs; those are fixture log-path errors, not receipt/storage errors. Subsequent fixture setup uses separate run-server and run-client directories. The passing flow evidence is retained without claiming all logs are error-free. Native asset warnings include the original1254px receipt texture lowering mip level and baseline Minecraft missing-sound/shader warnings. No texture/source art was modified to hide warnings.

Still requires target-modpack/manual acceptance: long-duration stick-motion feel, sustained multi-player keyboard/mouse/calendar interaction, production browser/deployment acceptance and long-running load. The automated real-player flow covers transitions but does not establish human interaction quality. No production server/client installation or restart occurred.

## Reproduction

Use the correct JDK and run gradlew build --max-workers=2. Set DF_TEST_JDBC=jdbc:mariadb://127.0.0.1:43322/mysql only for an explicitly isolated disposable MariaDB with a root test account; optional DF_TEST_PASSWORD is read from the process environment. Tests create/drop their own random schemas and reject other ports. Without this variable the MariaDB-specific checks explicitly skip, so an ordinary build alone does not reproduce the full DB evidence.

Development runServer/runClient include the separate smoke sourceSet; release main/source JARs exclude its bytecode. -Pflow enables the real-player adapter fixture; -PconnectAddress=127.0.0.1:<isolated port> makes the native client join it. Native clients and dedicated servers use separate game directories. Capture/direct launch was used locally to avoid two Gradle executions locking the same project while waiting on each other. No fake-player/manual login shortcuts were used.


## Final Minecraft metadata pin

The final Minecraft dependency is the exact version 1.21.1 (not a tilde range). A per-entry comparison against the fully tested previous JAR found only fabric.mod.json changed; all class, art, recipe, loot, tag and other entries are byte-identical. Before SHA256 ae02ba3a60b9631e4716dfd6b8c4cd9bda32ea379901420f6780eefcce3ed4b9; final SHA256 206dda9e2392f234a755426ee2231444c44e51f34d9dc746d7f1ce91e1f03cb7. build/metadata-entry-diff.json records the comparison. The exact final JAR then passed a separate formal Fabric-loader startup, recipe loading and graceful save/stop at an isolated local port. metadata-startup.log is included in the evidence ZIP. The earlier full business/real-player/combined proof is linked by the unchanged code/resource entries.
