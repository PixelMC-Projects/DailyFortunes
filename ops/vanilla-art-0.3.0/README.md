# 原版模型切换部署

在活动mods之外准备 `pixelmcmanager/vanilla-art-0.3.0`，放入发行JAR、可选资源包ZIP、`activate.sh`、下一版AutoModpack配置和完整性清单。将本次核实的文件名与SHA256写入仅服务端可读的 `activation.env`：`old_name/old_sha/new_name/new_sha/pack_name/pack_sha/old_config_sha/next_config_sha/old_manifest_sha/next_manifest_sha`。

启动包装器在完整性验证之前调用本脚本，替换此前未应用的0.2.1激活调用；保留其余JVM参数和就绪检查。先用隔离临时目录验证首次激活、重复启动、载荷损坏拒绝及中断恢复，再修改真实包装器。

AutoModpack只替换DailyFortunes JAR条目，并添加资源包到syncedFiles与forceCopyFilesToStandardLocation。保留其他同步项、TLS等设置，不同步options.txt、不设置server.properties强制资源包，也不写客户端已选资源包列表。

通过Manager原生 `pixelmcmanager restart whenempty` 等真实玩家全部下线15秒后交给MCSM重启。数据库与网页无需重启。开服后核实实际模组版本、桥接、JAR/ZIP校验值及AutoModpack生成清单。

脚本不读写业务数据库或存档。旧JAR、原配置与清单保存在stage/previous；启动器另在部署前备份。回滚应先正常停服，恢复这些文件与对应启动钩子，并重新生成同步清单。已开始游玩后的数据不回退。
