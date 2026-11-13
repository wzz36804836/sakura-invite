# SakuraInvite v1.2.1

Paper/Folia 1.21.11 邀请系统，作者 Sakura_Girl。

1. 可创建 MySQL 数据库并填写 `plugins/SakuraInvite/config.yml`；无法连接 MySQL 时会自动使用本地 `SakuraInvite.db`。
2. 安装 XConomy 与 Vault（金币奖励需要）；CraftEngine 可选。
3. 将 JAR 放入 `plugins` 后启动。数据表自动创建。

存储策略：优先连接 MySQL；连接失败且 `mysql.fallback-to-sqlite: true` 时，自动创建 `plugins/SakuraInvite/SakuraInvite.db`。SQLite 无需安装数据库服务。

命令：`/invite help`、`/invite gui`、`/invite create`、`/invite use <码>`、`/invite records`、`/invite top`。
管理：`/invite admin official`、`/invite admin createofficial <码>`、`/invite admin editofficial <码>`、`/invite admin deletecode <码>`、`/invite reload`。

PlaceholderAPI：`%sakurainvite_code%`、`%sakurainvite_inviter%`、`%sakurainvite_invitees%`、`%sakurainvite_invitee_names%`、`%sakurainvite_invitee_1%`（1-20）、`%sakurainvite_valid_count%`、`%sakurainvite_rank%`、`%sakurainvite_task_progress%`。
