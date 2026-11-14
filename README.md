# SakuraInvite · 樱花邀请系统

玩家出资预存奖励的 Minecraft 邀请插件，含成长任务、有限库存、个人码和官方活动码、菜单、排行榜及异常奖励核对。

最新源码：**1.3.0（2026-11-15）**，目标 **Paper 1.21.11 / Java 21**，不支持 Folia。

源码位于 [source/](source/)，见 [项目简介](source/README.md)、[使用与升级说明](source/docs/使用与升级说明.md) 与 [更新记录](source/CHANGELOG.md)。

20 项回归测试通过；真实 Paper、MySQL 与经济插件联动尚未完成验证。升级请先停服备份，旧库存规则见说明。

此仓库为私有源码备份，原作者许可保持不变。排除缓存、编译产物、生产数据库和凭据；默认 MySQL 密码为空。GitHub 文本同步不包含二进制 Gradle Wrapper JAR，本地完整源码 ZIP 含该文件；从本仓库构建需安装 Gradle 8.8 或补齐官方 Wrapper。
