# DailyFortunes future-start staging

This directory records the reviewed, host-bound deployment of DailyFortunes 0.1.2. It is not a general installer: `prepare-remote-stage.py` deliberately refuses changed bindings, existing extension data, unexpected payloads or an already prepared stage. No credentials or production configuration are included here.

The immutable release is [v0.1.2](https://github.com/PixelMC-Projects/DailyFortunes/releases/tag/v0.1.2), tag commit `fafba03f352ba8f88280a3aef013ab0c95585c33`. Its JAR SHA-256 is `3f60e036e1bced2733a4e8e11ea0972198cf7baef1e8d549a21db169ecdfa279`. These later operations files do not change that tag or its attachments.

## Prepared flow

- Game stage: `/home/rhymc/dockerserver/mc-server-1/pixelmcmanager/deploy-dailyfortunes-0.1.2-20261027`.
- Web stage: `/home/rhymc/dockerserverdata/pixelmc-manager/deployment/dailyfortunes-0.1.2-20261027`.
- Existing Manager/Titles coordinator and startup arguments remain in use. The game and web instance commands are unchanged.

On the next normal game start, the existing coordinator prepares the web service first. Its existing old-version native backup must succeed before the new hook runs. `DailyFortunesPrepare`, compiled for Java 17 against the exact Manager `0.5.2-web.1` service JAR, atomically adds only `fortunesEnabled` and `fortunesDatabase` to `manager.json`. It preserves the remaining fields, saves the previous configuration with mode 0600, and uses the same backend to request `/state` in the new independent database. This initializes its tables, 60 default definitions and settings without inventing players or history. Containers do not require Python.

After the new web service starts, the earlier reminder, FramedBlocks, Manager and Titles stages run. DailyFortunes then checks its payload, those activation markers, the expected AutoModpack baseline and authenticated web capabilities. It writes `mode: auto` and the new sync selection before atomically publishing the loadable JAR. AutoModpack regenerates its manifest during the subsequent normal game startup. No database credentials enter game/client configuration.

Four-way stage checks cover the actual cached block geometry separately in the mod's development probe. Deployment fixtures here cover configuration preservation, conflict refusal, payload/capability/dependency gates, idempotency, publishing the JAR last, and rollback at each write phase. An interrupted `activation.pending` stops a later game startup for review instead of proceeding with incomplete files.

## Storage and backup

The existing MariaDB container holds a separate `dailyfortunes` schema and account. The account has only `SELECT, INSERT, UPDATE, DELETE, CREATE` on this schema. Staging created an empty logical database; it did not initialize business tables or import local test data. Passwords remain in the host's protected secrets directory and the protected web stage, outside this repository.

`update-offsite-backup.py` adds this schema to the existing encrypted OSS capture, including the credential needed to restore it. It selects the Manager native backup JAR from the verified running process, so an older live service is not replaced by a newly staged one during backup. The existing 12-hour timer, 600 KiB/s upload/download limits, resource limits and retention settings stay intact. See [OFFSITE-BACKUP.md](OFFSITE-BACKUP.md).

Local fixtures passed: 15 Java configuration checks, 17 isolated activation scenarios and 20 mocked offsite-backup scenarios. Container verification used `--check-payload` only; it did not run the future schema initializer, game commands, native backup or readiness HTTP requests against production. No instance was started, stopped or restarted. Actual future activation remains pending.

## Cancel or recover

Before activation, restore `before-staging/activate-manager.sh`, `before-staging/web-launch-manager.sh` and `before-staging/web-SHA256SUMS` to their original paths. Remove the DailyFortunes hook only; preserve the existing Manager/Titles coordination and earlier staged mods. Leave the empty database and secret for review rather than dropping them automatically.

After activation, stop normal writers before removing the exact DailyFortunes JAR and restoring `before-activation/automodpack-server.json`. Preserve the independent database and all backups. Restore only the two extension fields from the protected web configuration backup; do not overwrite unrelated settings changed later. Manager's own schema upgrades have separate rollback requirements and are not reversed by removing DailyFortunes.

If `activation.pending` remains, verify the payload and the partial files before restoring the saved sync configuration and removing only files owned by this stage. Do not clear the marker merely to force startup. The offsite updater preserves its original script with a `.before-dailyfortunes-20261027.*.bak` suffix; changing backup coverage after real fortunes exist requires care to avoid excluding that data.

The detailed server customization record is retained locally, not committed to GitHub.
