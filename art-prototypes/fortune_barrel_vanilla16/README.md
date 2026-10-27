# 问津签筒 · 原版像素试作

方口木桶、七支竹签、红绳结与小簇树叶。采用接近原版的棱角造型和柔和色块，木纹、绑带直接绘在贴图上。

用 Blockbench 打开 `fortune_barrel_vanilla16.bbmodel`，纹理已内嵌。`preview.png` 是实际模型的三个角度；`fortune_barrel_vanilla16.png` 是 64×64 图集，每个方块长度对应 16 个纹理像素。

本版 21 个元素、106 个四边面，包含 7 支独立签。它是独立美术试作，尚未替换模组资源，也未安装到客户端或服务器。原始高精模型与当前运行模型保留。

`java-models/` 提供静态模型、桶身及七支签的独立导出；`rig.json` 保存新转轴。正式接入时须同步调整方口容器的物理约束、转轴和物品展示尺寸，不能只覆盖旧版模型文件。

重新生成：在项目根目录运行 `python tools/create_vanilla_barrel.py`，依赖与现有美术工具相同。此脚本只写本试作目录。

## Vanilla-style fortune barrel study

A square wooden fortune barrel with seven bamboo sticks, a red cord and small leaf clusters. All surfaces use 16 texels per block, packed into a 64×64 atlas. Open the `.bbmodel` file in Blockbench; its texture is embedded.

This separate 106-quad art study has not been installed or integrated into the mod. Existing artwork remains unchanged. The split Java models and pivot data are included for later integration; the new square container requires matching physics constraints.

Original PixelMC Projects artwork. AGPL-3.0-only; see the repository LICENSE and NOTICE.
