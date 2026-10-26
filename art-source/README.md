# Editable artwork

三个 Blockbench 工程对应已确认的问津签筒 cleanup_v8、解签龛 wild_v7、藏签匣 v1。纹理已内嵌；仅去除本机纹理路径，没有重画或改动几何。

游戏导出位于 `src/main/resources/assets/dailyfortunes`。签筒运行时拆成桶身和七支签，转轴说明见 `docs/barrel-animation-rig.json`；修改模型后应重新同步对应导出，不能只覆盖静态桶模型。

These are the editable Blockbench originals. Textures are embedded; machine-local texture paths are removed. Runtime meshes and UI textures live in the assets directory. The barrel's seven animated meshes preserve the original geometry and use the documented pivots.

Original PixelMC Projects artwork, AGPL-3.0-only; see the repository LICENSE and NOTICE.
