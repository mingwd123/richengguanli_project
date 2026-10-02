# 管理端运维采集

采集器只读取容器状态和备份任务的成功回执，不运行备份、不重启容器。
需要 Python 3.10+。Linux 与 Windows 均可运行。

```sh
python3 ops/collect_status.py --output ops-status/ops-status.json \
  --backup-receipt /var/backups/dayliane/success.json \
  --container dayliane-backend-1 --container dayliane-mysql-1
```

使用实际容器名称。只查询指定容器的 State，不读取容器环境变量。
运行账户需要读取备份文件与调用 Docker 的权限；Docker 访问权限相当于高权限，
不要将 Docker socket 挂入应用容器。

## 备份回执

由现有备份任务在命令成功、文件完成写入并计算 SHA-256 后原子写入回执。
不能用最新文件的修改时间充当成功记录。示例结构如下，示例值不可用于生产：

```json
{
  "result": "success",
  "completedAt": "2026-09-23T01:00:00Z",
  "artifact": "backup-20260923.sql.gz",
  "sha256": "<实际文件的 SHA-256>",
  "offsite": {
    "result": "verified",
    "verifiedAt": "2026-09-23T01:05:00Z",
    "sha256": "<远端对象校验后的 SHA-256>"
  }
}
```

只有远端校验成功后才添加 `offsite`。采集器校验本地文件非空和摘要，
异地状态依据备份任务的远端校验回执；这不代替定期恢复演练。
文件缺失、摘要不符、未来时间、容器检查超时均显示未知或异常。
校验大备份会产生磁盘读取开销，需结合备份大小安排采集周期。

## 部署

Docker Compose 已只读挂载 `./ops-status` 到 `/var/run/dayliane`。
设置 `APP_OPS_STATUS_FILE=/var/run/dayliane/ops-status.json`、
`APP_OPS_ENVIRONMENT=production`、`APP_OPS_BACKUP_MAX_AGE_HOURS=26`。
非 Docker 部署使用实际状态文件绝对路径。

Linux 可按实际路径修改相邻的 systemd service/timer 示例，安装后每 5 分钟采集。
Windows 可在任务计划程序中每 5 分钟执行同一 Python 命令。
本次不自动安装定时任务，也不改变现有备份流程。
超过 10 分钟未采集显示过期；未部署采集器不会显示为正常。

测试：`python -m unittest discover -s ops -p "test_*.py"`。
