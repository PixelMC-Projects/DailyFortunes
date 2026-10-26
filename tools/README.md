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

`runtime_profiles/` 保存各模型的轻度有损规则：将小铜环、细边与榫钉等凸饰投到局部支撑面，简化微小厚度与不重要侧面。保留主体结构和主要植物；这会使部分近看细节变平，不是无损压缩。当前签筒562面、解签龛1183面、藏签匣610面，合计2355面（上一版3075面，原始16698面）。重新生成时同时核对各 `*-report.json` 的蓝本SHA-256，确认源模型未改。

检查须同时看正侧面、背面和俯视图，以及实际游戏内物品栏、掉落物、手持与摆放表现。`gradlew runVisualClient -Pbenchmark` 对同一批蓝本/派生模型做实际绘制比较，结果在 `build/verification/render-benchmark`。这不是整合包总帧率测试。

上述命令是验证方法，不能当作本轮最终资源的已通过记录；实际执行结果另记于 `docs/verification.md`。本轮香草纪元客户端实测待用户完成，不创建新GitHub Release。

查看过的通用烘焙参考：[Principled Baker](https://github.com/danielenger/Principled-Baker)。本项目未复制其代码，也不依赖该插件或 Blender；这里的几何分组、透明裁切与导出脚本由本项目实现。
