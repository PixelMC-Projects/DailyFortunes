# Fabric 1.20.1 deployment / 部署

Install DailyFortunes 0.3.1 on both client and server, using the same Minecraft and loader family. Java 17 is required. Fabric API is required on both sides.

本地单人模式保持默认 mode:auto，自动按存档UUID使用内置H2。独立专服在 config/dailyfortunes/server.json 选择 mode:database 并配置受限MariaDB账号。Manager模式保持 mode:auto，安装对应游戏/加载器的Manager桥接，部署附件 pixelmc-manager-service-0.6.0.web.2.zip 后配置统一数据库并启用抽签模块。网页服务使用Java17，其HTTP/游戏桥接密钥与数据库凭据仅保存在服务端。

Manager shared service: https://github.com/PixelMC-Projects/pixelmcmanager-forge/releases/tag/v0.6.0.web.2 . The service business contract and df_* schema are unchanged; all ports share this service. No new database migration is introduced. Its backend continues to enforce admitted global UUIDs, immutable snapshots, recovery-token rotation and commit-before-consumption.

首次迁移先停服并备份数据库、config/dailyfortunes及存档。将旧库全部df_*表完整迁入Manager统一库，保留UUID、历史快照、token及待投递状态。不能通过玩家名单导入或模拟登录替代迁移。回滚时恢复对应游戏版本存档及数据库备份，并换回配套JAR。Minecraft1.20.1与1.21.1存档升级遵循游戏自身规则；不承诺降级存档兼容。

Default vanilla models and Classic assets match the approved Forge0.3.0 artwork. Classic is an optional external pack, never enabled automatically. 1.20.1 uses pack_format15; 1.21.1 uses34. It changes appearance/physics art profile; collision bounds stay with the server defaults. Disabling it preserves all fortune data.

Receipt JSON and BarrelId use vanilla item NBT; client data never authorizes state changes. Block entities retain Owner/Display and update packets. Server IO stays on bounded workers; unavailable storage leaves qualifications and physical slips intact.

本次仅交付本地源码、构建产物和GitHub发行；没有安装到任何生产服或现有用户客户端，没有重启生产服务。具体自动检查与隔离运行证据见port-verification.md；交互玩法仍需目标整合包实机验收。
