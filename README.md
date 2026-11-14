# SakuraInvite · 樱花邀请系统

面向 **Paper 1.21.11 / Java 21** 的 Minecraft 邀请插件，采用玩家预存奖励机制，支持个人邀请码、成长任务、官方活动码、邀请记录和排行榜。客户端无需模组，不支持 Folia。

## 玩法

邀请人设置并预存物品、金币 → 分享邀请码 → 新人绑定时预留一份奖励 → 完成任务后领取。未占用库存可退回，用完可补充。服务器可另外配置邀请人命令奖励。

官方活动码支持物品、金币和命令奖励，先保存草稿再发布，可设置领取名额与有效期。

## 文档和源码

- [插件介绍与玩法示例](source/docs/插件介绍.md)
- [使用、安装与升级说明](source/docs/使用与升级说明.md)
- [项目构建说明](source/README.md)
- [更新记录](source/CHANGELOG.md)

当前版本：**1.3.0**。新装默认 SQLite，可选 MySQL；金币需 Vault 与经济插件，PlaceholderAPI 与 CraftEngine 按需使用。

20 项回归测试通过。实际 Paper、MySQL 和经济插件联动尚未完成验证，开放前请在测试服走完一次邀请流程。旧版升级应停服备份，旧个人码需要按说明补充奖励。

## 从源码构建

项目位于 `source/`。使用 Java 21 和 Gradle 8.8，在该目录运行 `gradle build`。仓库保留 Wrapper 脚本与配置，二进制 Wrapper JAR 未纳入此次文本备份；使用 Wrapper 前需补齐官方文件。

## 开源许可

项目采用 [MIT License](LICENSE)，允许使用、修改和再分发，需保留版权与许可声明。第三方依赖保留各自许可。

作者：Sakura_Girl。
