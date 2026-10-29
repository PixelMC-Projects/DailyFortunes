# DailyFortunes · 每日一签

为 Minecraft 留下一份每天的签缘：手持签筒抽签，在解签龛解读，再把签笺永久收藏进藏签匣。

- 六十篇签文，独立权重；吉凶只用于阅读与收藏，没有奖励或惩罚。
- 按日期保存个人签历，遗失可补领原签，不能重抽或赠送。
- 原版像素风的中式木作与植物模型；签筒内的签会随动作晃动，碰撞后停在新位置。
- 可在藏签匣上展示收藏，瞄准时阅读；支持 PixelMC Manager 网页管理。

## 支持版本 / Supported versions

| Minecraft | Loader | Mod | Branch |
| --- | --- | --- | --- |
| 1.20.1 | Forge 47.3+ | 0.3.0 | [forge/1.20.1](https://github.com/PixelMC-Projects/DailyFortunes/tree/forge/1.20.1) |
| 1.20.1 | Fabric Loader 0.16.14+ + Fabric API | 0.3.1 | [fabric/1.20.1](https://github.com/PixelMC-Projects/DailyFortunes/tree/fabric/1.20.1) |
| 1.21.1 | Fabric Loader 0.16.14+ + Fabric API | 0.3.1 | [fabric/1.21.1](https://github.com/PixelMC-Projects/DailyFortunes/tree/fabric/1.21.1) |
| 1.21.1 | NeoForge 21.1+ | 0.3.1 | [neoforge/1.21.1](https://github.com/PixelMC-Projects/DailyFortunes/tree/neoforge/1.21.1) |

从 [Releases](https://github.com/PixelMC-Projects/DailyFortunes/releases) 下载匹配游戏和加载器的 JAR；所有版本均须客户端与服务端同时安装。1.20.1 使用 Java 17，1.21.1 使用 Java 21。Classic ZIP 也须匹配游戏版本，独立下载并手动启用。

## 安装与使用

**本分支：Minecraft 1.21.1 / Fabric，客户端和服务端都需要安装。** 从 [Releases](https://github.com/PixelMC-Projects/DailyFortunes/releases) 下载对应版本。

先安装 Fabric Loader 与对应 Minecraft 版本的 Fabric API，再将本模组 JAR 放入 `mods`。默认使用内置本地数据库，数据保存在游戏目录的 `config/dailyfortunes/local-data/worlds/<存档ID>`，无需另装数据库。进入世界后等待约 10 秒加载签历。

签筒、解签龛、藏签匣可用竹子、木材、铜、纸等原版材料合成。`/df recover yyyy-MM-dd` 补领已抽取但尚未收藏的原签。打开藏签匣自动收存背包里的已解签笺；左键阅读，右键选择展示，中键清除展示。

喜欢此前的精细木作外观，可下载同版本的 `DailyFortunes-Classic` 资源包，放入 `resourcepacks`，在“选项 → 资源包”手动启用。默认不加载；停用即可恢复原版像素风，签历数据不变。资源包只换外观，碰撞范围以服务器默认模型为准。

## 服务器与网页

1. 安装本分支 DailyFortunes 0.3.1 与相同加载器及游戏版本的 PixelMC Manager 0.6.0 或兼容新版，部署 [Manager 独立网页服务 0.6.0.web.2](https://github.com/PixelMC-Projects/pixelmcmanager-forge/releases/tag/v0.6.0.web.2)，并配置其统一 MariaDB 数据库和游戏桥接。
2. 在 Manager 服务中启用抽签模块；本模组的 `config/dailyfortunes/server.json` 保持 `mode: auto`。抽签数据注册在 Manager 管理的 `dailyfortunes` 命名空间，玩家与头像使用统一玩家库。
3. 启动后，Manager 左侧出现“抽签管理”。可编辑签文、查看所有玩家签历、补领、调整设置、备份和撤回单项修改。

从旧的独立数据库升级时，先停服并备份，再将原 `df_*` 表完整迁入新的统一数据库，保留原库供回滚；不要只复制玩家名单，也不要以登录或重新抽签代替迁移。旧版 Manager 不支持本版的统一存储注册，需同时升级。

不使用 Manager 时可设 `mode: database` 并填写本模组 MariaDB 配置。两种方式只能选择一个数据管理入口，避免为同一批玩家配置不同数据库。不要把服务端凭据发给客户端。

服务器收藏全局保存，拆掉藏签匣或换存档不会删除；玩家改名不改写旧签上的姓名。单人模式按存档ID隔离，换新存档可重新抽签。Manager 模式由平台统一备份；独立模式默认每 12 小时备份、保留 30 天，可调整。异地备份应包含数据库与配置。

## English

Draw one fortune each day, interpret it at a wooden shrine, and keep it permanently in your personal archive. Includes 60 poems, custom Chinese-inspired models, constrained stick motion, recoverable receipts and a floating display with a reading panel. Fortune grades are decorative: no rewards or gameplay penalties.

Vanilla-style pixel models are included by default. For the previous detailed artwork, place the matching `DailyFortunes-Classic` ZIP in `resourcepacks` and enable it manually. It is disabled by default; switching styles preserves all data. Collision shapes remain those of the server's default models.

Install on **both sides with Minecraft 1.21.1 / Fabric**. Singleplayer uses an embedded database automatically. For web management, install Manager 0.6.0 for the same game/loader and the shared web service 0.6.0.web.2 (or a compatible newer version), configure its unified MariaDB database and game bridge, enable the fortune module, and keep `mode: auto` in `config/dailyfortunes/server.json`. All known players and avatars come from Manager; fortune history stays in its registered namespace. Without Manager, select `mode: database` and configure standalone MariaDB access.

Before upgrading an existing separate database, stop the server, back up, and copy all `df_*` tables into the new unified database. Keep the original database for rollback. Migrations must preserve receipt tokens and historical snapshots and must not simulate player logins.

Use `/df recover yyyy-MM-dd` to recover an unarchived result. Opening the archive stores your interpreted slips; left-click to read, right-click to display. Server collections survive world changes; singleplayer saves have separate quotas and collections. Manager coordinates managed backups; standalone backups run every 12 hours by default.

## License / 许可

Copyright © 2026 zhan_cpu / PixelMC Projects. [AGPL-3.0-only](LICENSE). Preserve author and copyright notices; modified distributions and network-accessible versions must provide corresponding source under the license. 自制模型、纹理及签文随本项目提供；第三方组件见 [THIRD_PARTY.md](THIRD_PARTY.md)。
