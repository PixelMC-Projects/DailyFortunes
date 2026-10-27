# 美术派生工具

`art-source/` 是只读蓝本；本工具不写该目录，也不写原 FortuneArt 工程。

需要重新导出时，安装 `requirements-art.txt` 后依次执行：

```sh
python tools/bake_runtime_models.py
python tools/bake_inventory_icons.py
python tools/bake_slip_assets.py
python tools/preview_runtime_models.py
```

游戏用 JSON/PNG 输出到 `src/main/resources/assets/dailyfortunes`，可编辑的低复杂度副本及面数报告在 `art-runtime/`。对比图输出到忽略的 `build/verification/model-comparison`；普通 Gradle 构建使用已提交资源，不依赖 Python。

烘焙保留七支签的独立转轴，将细枝转成局部透明贴片，瓦片按坡面横断面分组，合并同平面碎面并裁掉内部面。不要把不同高度的瓦层或整个斗拱直接包成一个大盒子；也不要把所有方向的叶片压在一个面上。

`runtime_profiles/` 保存有损规则：将小铜环、细边与榫钉等凸饰投到局部支撑面，省去不易看见的抽屉内件、屋顶内部小梁、墙板端面和极细立体附件，保留主体轮廓、瓦片、猫、主要植物和七支独立运动的签。当前签筒372面、解签龛613面、藏签匣402面，合计1387面（上一档1797面，已发布0.2.0为2355面，原始16698面）。这是有损精简；近看部分饰件会变平。重新生成时核对各 `*-report.json` 的蓝本SHA-256，确认源模型未改。

共面合并先沿用原有规则；无法整体合并时只聚合局部相邻碎片，局部包围面积最多为原面面积之和的1.25倍。避免用覆盖大片空气的透明面换面数。最后只复制引用到的贴图区域，逐块核对像素不变；三张模型图集均为1024×1024。

`python tools/check_runtime_assets.py` 检查蓝本指纹、实际面数、UV、图集和七支动态签的完整性。可加 `--baseline <旧art-runtime目录>`，同时约束总面面积、透明面积与图集占用。`python tools/measure_runtime_cost.py <目录>` 输出这些静态指标；它们不等同于真实整合包中的帧率。

检查须同时看正侧面、背面和俯视图，以及实际游戏内物品栏、掉落物、手持与摆放表现。`gradlew runVisualClient -Pbenchmark` 对同一批蓝本/派生模型做实际绘制比较，结果在 `build/verification/render-benchmark`。这不是整合包总帧率测试。

上述命令是验证方法，不能当作本轮最终资源的已通过记录；实际执行结果另记于 `docs/verification.md`。香草纪元客户端的实际帧率与观感仍需游戏内验收，发行附件以对应Tag的资源为准。

新方块先按面数、透明区域、图集占用、动态部件与大量摆放场景分配预算。先用低面主体进入游戏验证，再逐步加细节；木纹、刻痕和小钉默认用贴图，几何优先用于轮廓与空间层次。高精蓝本保留作烘焙来源，运行模型每次从蓝本重新生成，不反复加工已经有损精简的产物。现有规则针对这三个模型，新模型不能未经检查直接套用并宣称无损。

查看过的通用烘焙参考：[Principled Baker](https://github.com/danielenger/Principled-Baker)。本项目未复制其代码，也不依赖该插件或 Blender；这里的几何分组、透明裁切与导出脚本由本项目实现。
