# 异地备份补丁（待部署）

修改器只修改指定的 `backup.py`，不会执行备份、数据库命令或网络请求。此次本地未连接生产服务器，未改动任何 timer、status 或 current 快照。

## 行为

- 原每 12 小时调度、5 Mbps 限速、互斥锁、`nice`、Manager 的 `-Xmx256m` 及原有 Restic 配置保留。
- 每次备份先只读查询两个逻辑库是否存在。`memorialtitles` 必须存在；有 `dailyfortunes`（包括空库）时，将两库放在同一次 `mariadb-dump --single-transaction --skip-lock-tables` 中导出。
- 新库存在时要求 `/home/rhymc/dockerserverdata/secrets/dailyfortunes-password` 是非空普通文件。原有 `database-instance/secrets` 复制流程会将其随现有凭据纳入 Restic 加密备份；日志同时新增该密码的脱敏规则。不要提交或对外传输这个密码文件。
- `manifest.json` 使用 `databases` 数组列出实际导出的库。
- Manager 原生备份只使用容器 PID 1 当前实际运行的 Java `-jar` 路径，且精确白名单限制为旧 `0.5.1` 或已准备的新 `0.5.2-web.1` 绝对路径。当前旧启动命令使用的精确相对路径 `releases/0.5.1/pixelmc-manager-service.jar`，只有再读取 `/proc/1/cwd` 确认为 `/data` 后才规范化到白名单中的绝对路径；其他相对路径、`../`、错误或无法确认的工作目录均拒绝。不会提前运行新版 JAR；无法确认时失败，不替换上一份 `staging/current`，也不会上传不完整快照。

## 部署

先将 `update-offsite-backup.py` 上传到受保护的临时目录，以目标文件所有者（生产环境为 root）执行：

```sh
python3 /受保护目录/update-offsite-backup.py /home/rhymc/dockerserverdata/offsite/backup.py
```

仅接受已核对的原文件 SHA256：

```text
ec3c3b538d560feb2872a506ca9b569080303d59ce9ae1611b494cbe8cf268c0
```

写入后的 SHA256：

```text
88406e65005ef8f4563e1a634649058c3e30c428badbb9c47bf1557ca7aacc06
```

修改前会留同目录 `backup.py.before-dailyfortunes-20261027.ec3c3b538d560feb.bak`，验证语法后原子替换并保留目标权限及所有者。重复运行精确匹配新版时返回 `already-current`，不重复覆盖原始备份。遇到未核对的文件、冲突备份或被修改的补丁标志均拒绝操作。

无需重启定时器；下一次正常调用即使用新脚本。本修改器不发起实际备份。部署后由主任务按已授权范围决定是否验证生产备份。

回滚时先核对 `.bak` 是上述原始 SHA256，以临时副本原子替换 `backup.py` 并保留其权限/所有者。回滚不需要修改 timer、状态文件、历史快照或 Restic 仓库。

## 本地隔离检查

```powershell
python E:\minecraftserver\fabricmod\DailyFortunes\ops\staging\20261027-dailyfortunes\test_offsite_backup_update.py C:\Users\Administrator\Documents\ChatGPT\服务器综合\outputs\dailyfortunes-deploy-20261027\backup.before.py
```

20 项通过。数据库发现、旧/新/未知 JAR、精确旧相对路径及工作目录校验、路径穿越拒绝、非 Java 进程、失败不上传、现有快照保留、凭据脱敏、备份/幂等/原子替换/语法检查等使用临时目录及 mock；没有实际运行 Docker、SQL 或 Restic。
