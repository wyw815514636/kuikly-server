# 管理端基础能力

管理接口 `/api/admin/v1` 与原有客户端 JWT 域隔离。契约源为 `src/main/resources/openapi/admin.json`；V3 迁移新增独立账号、角色、权限、会话、挑战、恢复码、限流、幂等与审计表。没有默认管理员或默认凭据。

设置 ADMIN_ENABLED=true、ADMIN_ORIGINS（生产精确 HTTPS Origin）与独立 Base64 32 字节 ADMIN_SECRET_KEY；密钥不得复用客户端密钥。ADMIN_IDLE_SECONDS 默认 1800；ADMIN_SESSION_SECONDS 默认 43200。密码为 Argon2id，MFA 为 TOTP + 单次恢复码，秘密 AES-GCM 加密，会话/重置令牌只存摘要。

完成 migrate 后，在交互终端运行 `build/install/kuikly-server/bin/kuikly-server create-admin <username>`。首次改密和超级管理员 MFA 在网页完成。运行服务账户和迁移账户分离；生产运行账号无 DDL 权限。

管理写入与鉴权复核使用同一 PostgreSQL 事务级 advisory lock，防止多实例同时移除最后一个可登录且 MFA 就绪的超级管理员。prepared JDBC 复用现有 Exposed 事务连接；密码计算在有界工作池中进行。重置自身密码明确拒绝，应使用个人改密；重置其他账号返回 15 分钟一次性凭据，幂等响应最多恢复 5 分钟。

命令 `recover-admin-mfa <username>` 撤销所有会话、清除 MFA 并要求改密及重新绑定，不更改已有密码。`prune-admin-audit <days>` 最低保留 180 天、需交互确认。后台不能编辑或删除审计。

使用 JDK21 与 Docker 执行 `./gradlew clean check installDist distTar`。`adminE2eServer` 仅在 test classpath，通过 APP_ENV=test、本机临时数据库名检查后提供联调账号，不包含于发行包。

管理端 Web 仓库包含开发、部署、备份恢复、MFA 应急、密钥轮换边界和验收文档。当前来源限流使用 socket 地址，不信任任意转发头；在反向代理后应增加入口层真实客户端限流。当前单版本密钥不支持在线无缝轮换。生产上线仍需实际域名/TLS、秘密存储、备份、告警和容量验证。
