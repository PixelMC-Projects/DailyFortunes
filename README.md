# DailyFortunes · 每日一签

为 Minecraft 留下一份每天的签缘：手持签筒抽签，在解签龛解读，再把签笺永久收藏进藏签匣。

- 六十篇签文，独立权重；吉凶只用于阅读与收藏，没有奖励或惩罚。
- 按日期保存个人签历，遗失可补领原签，不能重抽或赠送。
- 中式木作、植物与小猫模型；签筒内的签会随动作晃动，碰撞后停在新位置。
- 可在藏签匣上展示收藏，瞄准时阅读；支持 PixelMC Manager 网页管理。

## 安装与使用

**Minecraft 1.20.1 / Forge 47.3+，客户端和服务端都需要安装。** 当前发行版为 [0.1.2](https://github.com/PixelMC-Projects/DailyFortunes/releases/tag/v0.1.2)。

当前模型采用轻度有损派生版本：细小凸饰转入贴图，保留主体轮廓与主要植物。三个模型合计 2,355 个四边面，较原始蓝本减少 85.9%；原始美术蓝本不改。

单人游戏只需将 JAR 放入 `mods`。默认使用内置本地数据库，数据保存在游戏目录的 `config/dailyfortunes/local-data/worlds/<存档ID>`，无需另装数据库。进入世界后等待约 10 秒加载签历。

签筒、解签龛、藏签匣可用竹子、木材、铜、纸等原版材料合成。`/df recover yyyy-MM-dd` 补领已抽取但尚未收藏的原签。打开藏签匣自动收存背包里的已解签笺；左键阅读，右键选择展示，中键清除展示。

## 服务器与网页

1. 安装模组及 PixelMC Manager，并部署 Manager 独立网页服务；在同一 MariaDB 实例为 DailyFortunes 创建独立数据库和受限账号。
2. 在 Manager 服务配置中启用 `fortunesEnabled` 并填写 `fortunesDatabase`，配置游戏与 Manager 的桥接。模组的 `config/dailyfortunes/server.json` 默认 `mode: auto`，专用服务器自动使用 Manager。
3. 启动后，Manager 左侧出现“抽签管理”。可编辑签文、查看签历、补领、调整设置、备份和撤回单项修改。预览后有后续变更时拒绝覆盖。

不使用 Manager 时可设 `mode: database` 并填写本模组 MariaDB 配置。两种方式只能选择一个数据管理入口，避免为同一批玩家配置不同数据库。不要把服务端凭据发给客户端。

服务器数据与游戏存档分开保存；拆掉藏签匣不会删除收藏。单人模式按存档ID隔离，换新存档可重新抽签，旧版全局测试库保留但不自动导入。默认每 12 小时创建本地备份，保留 30 天，可调整。异地备份必须额外包含 DailyFortunes 数据，现有称号备份不会自动包含它。

## English

Draw one fortune each day, interpret it at a wooden shrine, and keep it permanently in your personal archive. Includes 60 poems, custom Chinese-inspired models, constrained stick motion, recoverable receipts and a floating display with a reading panel. Fortune grades are decorative: no rewards or gameplay penalties.

Install on **both sides with Minecraft 1.20.1 / Forge 47.3+**. Singleplayer uses an embedded database automatically. For multiplayer web management, run PixelMC Manager with a separate MariaDB database and account for DailyFortunes, enable `fortunesEnabled`, configure `fortunesDatabase` and the game bridge. Without Manager, select `mode: database` in `config/dailyfortunes/server.json`.

Download [0.1.2](https://github.com/PixelMC-Projects/DailyFortunes/releases/tag/v0.1.2) from Releases. Its lightly lossy runtime models bake small raised details into textures while keeping the main shapes and foliage: 2,355 combined quads, 85.9% fewer than the unchanged original art masters.

Use `/df recover yyyy-MM-dd` to recover an unarchived result. Opening the archive stores your interpreted slips; left-click to read, right-click to display. Server collections survive world changes; singleplayer saves have separate quotas and collections. Include this database separately in offsite backups.

## License / 许可

Copyright © 2026 zhan_cpu / PixelMC Projects. [AGPL-3.0-only](LICENSE). Preserve author and copyright notices; modified distributions and network-accessible versions must provide corresponding source under the license. 自制模型、纹理及签文随本项目提供；第三方组件见 [THIRD_PARTY.md](THIRD_PARTY.md)。
