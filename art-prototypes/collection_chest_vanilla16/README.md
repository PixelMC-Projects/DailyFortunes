# 藏签匣 · 原版像素试作

三只浅浮雕抽屉、铜把手、青绿盖沿与二维植物，沿用已认可的问津签筒二版配色。抽屉面板只凸出四分之一个模型像素，把手在面板上再凸出半个像素；没有看不见的内部抽屉结构。

用 Blockbench 打开 `collection_chest_vanilla16.bbmodel`，纹理已内嵌。`preview.png` 提供正面、背面和俯视图。

本版 91 个四边面，每方块 16 个纹理像素，使用 64×64 图集。它是独立美术候选，尚未替换模组或客户端资源；原始蓝本和当前游戏模型保留。

采用前须同步核对碰撞体积、抽屉朝向与悬浮签的高度，不能只替换模型文件。

重新生成：在项目根目录运行 `python tools/create_vanilla_furniture.py`。

## Vanilla-style collection chest

Three shallow drawer fronts, brass handles, a green lid frame and cutout foliage, matching the approved barrel's palette. Drawer fronts project only a quarter model unit; handles add half a unit. Hidden drawer interiors are omitted.

This 91-quad study uses 16 texels per block in a 64×64 atlas. Open the embedded-texture `.bbmodel` in Blockbench. It has not been integrated or installed; the display height and collision shape need matching when adopted.

Original PixelMC Projects artwork. AGPL-3.0-only; see the repository LICENSE and NOTICE.
