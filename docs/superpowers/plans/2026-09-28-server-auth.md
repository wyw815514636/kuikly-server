# 第三步：认证与用户查询纵向切片

依据：本会话已确认的 Ktor 商用基线及“继续下一步”。在现有 server 目录实施，不提交或推送。

## 设计与验收

- 账号密码登录；账号通过本地交互式 CLI 创建，无默认账号及公开注册。USER/ADMIN 两种角色。
- PBKDF2-HMAC-SHA256，600000 次迭代，随机盐；哈希在独立受限执行器完成，不占用数据库事务。
- Access JWT 15 分钟，固定 HS256/issuer/audience/type；每次请求校验数据库会话和用户状态。
- Refresh Token 为 256 位随机值，仅保存 SHA-256 摘要。每个登录会话就是一个令牌族，绝对期限 30 天。
- PostgreSQL 用户锁→会话锁的统一顺序；轮换历史保留到会话过期。重用撤销先提交，再向 HTTP 报错。
- 默认 10 秒宽限期：同一旧令牌、同一 requestId 返回同一令牌对；不同 requestId 或超窗重用撤销该会话。已继续轮换的后继令牌不再恢复。
- 短期重试结果 AES-256-GCM 加密，独立密钥与绑定摘要的 AAD；定期清除过期密文、会话和限流记录。
- 登录每账号每分钟最多 5 次尝试（包含未知账号，摘要作为计数键）；密码计算并发与排队总量受限。网关 IP 限流留作部署要求。
- GET /api/v1/users/me；GET /api/v1/users/{id} 仅本人或 ADMIN 可见，跨用户返回 404；GET /api/v1/admin/me 验证角色门禁。
- POST /api/v1/auth/login、refresh、logout、logout-all。凭据响应 Cache-Control: no-store；严格请求 DTO 校验。
- 配置无默认密钥；migrate 与 create-user 不要求 JWT 密钥。迁移新增 V2，不修改 V1。

## 顺序

1. [x] 编写密钥/密码/令牌与 PostgreSQL 认证验收测试，观察缺失实现失败。
2. [x] 实现领域接口、Exposed 仓储、事务用例、密码与 JWT/AES 适配器。
3. [x] 接入 HTTP、配置、CLI 与生命周期清理，更新使用文档。
4. [x] 完整 check/installDist、真实数据库并发和权限测试、独立只读审查并修复。

## 决策记录

- 当前尚无短信/第三方登录需求，以账号密码实现第一条完整链路；后续可增加独立凭证适配器。
- 使用 JDK 原生 PBKDF2 避免 JNI 部署依赖；参数依据 OWASP Password Storage Cheat Sheet 的 PBKDF2-HMAC-SHA256 600000 次建议，不宣称 FIPS 认证。
- TTL 缓存不能保证即时撤销，当前直接查 PostgreSQL；吞吐量扩大后再明确一致性目标并引入缓存。
- 测试专用迁移移至 V900，避免与真实 V2 冲突。
- 不自动创建 Git 提交，工作区保留供用户审阅。

参考：https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html 、https://ktor.io/docs/server-jwt.html

## 审查重点

关注刷新重放撤销是否真正提交、refresh/logout-all 并发锁序、短期密文的恢复边界、未知账号枚举、过期及禁用账号、JWT claims 和密钥校验、请求体资源上限、CLI 密码泄漏及部署限制。

## 执行与审查记录

- AuthCryptoTest 在实现前因缺失类型失败；实现后三项加密/密钥/密码测试通过。
- AuthIntegrationTest 在实现前因缺失服务与仓储失败；HTTP 接入前测试因 Koin 缺少 AuthService 失败；接入后完整认证路径通过。
- 使用 Exposed 1.5 顶层表达式函数，避免已弃用的 SqlExpressionBuilder；新增 Ktor RequestBodyLimit 依赖并更新严格依赖锁。
- 独立只读审查发现两项 Important：维护删除限流行与登录插入/锁定间的竞态；维护与全设备登出锁序可能成环。
- 两项回归测试先失败：`login survives cleanup between conflict insert and bucket lock` 抛 NoSuchElementException；`maintenance respects user lock held by whole account logout` 发现清理绕过用户锁。
- 修复后两项回归通过：登录遇到被清理的计数行重新插入/锁定，最多三次；清理按会话独立事务并采用用户→会话锁序，登录计数清理独立执行。
- Ruling: 维护每分钟限制至最多 100 个过期会话及 100 个含过期密文的会话，避免无界长事务；过期恢复仍即时拒绝，物理清理允许滞后，高吞吐部署需监控积压并调优。
- Ruling: 审查未覆盖的 TLS、网关限流和密钥分发属于部署阶段；本次不更改用户现有部署。单密钥切换与刷新历史容量限制已在 README 明确，不宣称完成线上安全/容量审计。
- Git 保持未提交、未推送；原有基础设施文件和用户其他目录不做清理。

## 最终验收（2026-09-29）

- `./gradlew clean check installDist --offline --console=plain`：BUILD SUCCESSFUL，11 个任务执行，54 秒。
- JUnit XML 汇总：14 项普通测试 + 21 项真实 PostgreSQL 集成测试 = 35 项；失败、错误、跳过均为 0。
- 独立审查的两项 Important 均完成失败复现→修复通过，完整套件通过；没有未处理的 Minor。
- 发行包在独立临时 PostgreSQL 17.11 容器实测：V1/V2 迁移、无 JWT 密钥下交互式创建 USER/ADMIN 两个账号、启动后角色访问控制、8 路真实 HTTP 并发刷新返回同一令牌对且数据库仅增加一条轮换记录、退出后的 JWT/Refresh Token 拒绝。
- 发行包验证终端没有回显密码，服务器日志不包含测试密码或任何测试令牌，JSON 日志包含请求关联 ID；SIGTERM 后应用数据库连接数为 0。测试容器在 finally 中清理。
- 发行包验证脚本及日志位于被忽略的 `.superpowers/runtime-verification/`，未写入真实用户凭据或操作其他项目数据库。
