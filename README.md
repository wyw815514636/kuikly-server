# Kuikly Server

Ktor 服务端：已完成依赖锁定、基础设施、账号密码认证与用户查询，以及 OpenAPI 契约和 CI 验收配置。

使用 PostgreSQL 持久化会话，USER/ADMIN 角色与资源归属共同控制访问。跨端共享契约、完整后台权限模型与部署流水线尚未实现。

## 技术栈

| 组件 | 锁定版本 |
| --- | --- |
| JDK | 21 |
| Gradle Wrapper | 9.3.1 |
| Kotlin / serialization 编译插件 | 2.3.21 |
| Ktor / Netty 引擎适配 | 3.5.2 |
| Exposed core / JDBC / kotlin-datetime | 1.5.0 |
| PostgreSQL JDBC | 42.7.13 |
| HikariCP | 7.1.0 |
| Flyway core / PostgreSQL 支持模块 | 13.8.0 |
| Koin | 4.2.2 |
| kotlinx-coroutines / slf4j | 1.11.0 |
| kotlinx-serialization-json | 1.11.0 |
| kotlinx-datetime | 0.8.0 |
| Logback | 1.6.4 |
| JUnit | 6.1.3 |
| Testcontainers | 2.0.5 |
| 集成测试 PostgreSQL 镜像 | 17.11-alpine |
| OpenAPI / Swagger UI | 3.0.3 / 5.31.0 |
| OpenAPI Request Validator（仅测试） | 3.0.0 |

直接依赖版本维护在 `gradle/libs.versions.toml`，项目配置的传递依赖由 `gradle.lockfile` 严格锁定。生成的 `settings-gradle.lockfile` 也需纳入版本控制。Ktor、Kotlin、coroutines 和 JUnit 使用各自 BOM 对齐应用依赖中的同族组件；编译工具自身的依赖保持上游配置。Gradle 分发包使用 SHA-256 校验。

Ktor 选择 3.5 系列补丁版作为本次兼容性基线。版本可下载和构建通过不代表完成生产可靠性或安全审计。后续升级应同步运行兼容性测试及业务集成测试。

客户端当前使用 Kotlin 2.1.21，服务端独立构建；此处尚未建立跨平台共享契约模块，也未验证客户端所有目标平台的兼容性。

## 本地验证

安装 JDK 21。`gradle/gradle-daemon-jvm.properties` 指定 Gradle daemon 使用 JDK 21，编译和测试工具链也固定为 21，不自动下载 JDK。

macOS 可在单次命令中选择已安装的 JDK：

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --version
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew verifyDependencies
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew clean check
```

其他平台将 `JAVA_HOME` 指向本机 JDK 21，然后运行 `./gradlew clean check`；Windows 使用 `gradlew.bat clean check`。

首次执行需要访问 Maven Central 和 Gradle Plugin Portal。`test` 不需要外部服务；`integrationTest` 需要正在运行的 Docker，自动创建独立 PostgreSQL 测试容器和测试数据库，并在结束后清理容器。首次集成测试还会下载镜像。Docker 不可用时集成测试失败，不会自动跳过。

`verifyDependencies` 会实际解析并下载所有可解析配置的构件。缺失构件或依赖锁不匹配会导致任务失败。`check` 包含该任务、普通测试和数据库集成测试。

测试报告位于 `build/reports/tests/test/index.html` 和 `build/reports/tests/integrationTest/index.html`，覆盖：

- Netty 启动、Koin 注入以及编译生成的 JSON/日期序列化器通过实际 HTTP 请求协同工作。
- 协程挂起和切换执行线程后，SLF4J MDC 仍能传播并恢复调用方上下文。
- PostgreSQL JDBC 驱动与 Flyway PostgreSQL 插件能够加载，Hikari 配置可构造；不连接数据库。
- Exposed 1.x 表 DSL 与日期扩展能在当前版本组合中编译及加载。
- 配置缺失或非法时拒绝启动，配置打印不包含数据库密码。
- 标准 HTTP 错误、严格 JSON 校验、缺失路由、内部信息隐藏和取消异常传播。
- 并发请求在协程切换后保持独立的请求 ID 和 MDC。
- 真实 PostgreSQL 空库迁移、幂等执行、测试版本增量升级，以及 checksum 异常时关闭连接。
- 多表事务提交/回滚、取消后提交前回滚、慢查询超时及连接池继续可用。
- 应用健康、就绪、draining 状态和停机后的连接池关闭。
- 迁移独立超时、受限数据库账号启动，以及数据库不可用时 readiness 失败但 liveness 保持可用。

- JWT 签名、issuer/audience/type/过期校验，401/403/404，严格请求字段和 4 KiB 请求体上限（包括无 Content-Length 的请求）。
- 并发刷新同一请求只产生一次轮换；超窗/不同请求 ID 重放撤销对应令牌族，其他设备继续有效。
- 全设备退出与刷新并发、禁用账号、绝对会话过期、宽限期关闭、过期密文清理及历史重放检测。
- 真实 V1→V2 增量迁移，账号限流、维护清理与登录/全设备退出的并发边界。

这些测试尚不覆盖生产容量、备份恢复、外部风控或高可用部署。

离线执行 Gradle 需要依赖已下载到本机缓存；Docker 镜像下载由 Docker 单独处理。

使用 `./gradlew clean check installDist --offline --console=plain` 进行完整构建验收；执行结果记录在 `docs/superpowers/plans/2026-09-28-server-auth.md`。部署环境仍需使用实际配置再次验证。

认证阶段验收通过：14 项普通测试和 21 项 PostgreSQL 集成测试，共 35 项，零失败、零跳过。发行包另行实测交互式用户创建、8 路并发刷新、会话撤销、日志凭据隐藏和 SIGTERM 连接释放。OpenAPI 与 CI 阶段的最新结果记录在 `docs/superpowers/plans/2026-09-29-openapi-ci.md`。

当前本地完整验收：`clean check installDist distTar` 通过，共 42 项测试，零失败、零跳过；actionlint 工作流检查通过。发行包文档开关与 tar 权限已实测，GitHub 云端运行尚待代码推送后验证。

## 启动与迁移

准备 PostgreSQL 数据库及账号。参考 `.env.example` 设置环境变量；应用不会自动读取 `.env` 文件，需要由终端、IDE 或部署平台注入。

| 环境变量 | 默认值 / 要求 |
| --- | --- |
| `APP_ENV` | `dev`，允许 `dev/test/staging/prod` |
| `HOST` / `PORT` | `127.0.0.1` / `8080`；容器部署时显式设置 `HOST=0.0.0.0` |
| `DB_URL` | 必填，`jdbc:postgresql://host:port/database` |
| `DB_USER` / `DB_PASSWORD` | 必填，无默认凭据 |
| `DB_POOL_SIZE` | `8`，范围 1–64 |
| `DB_CONNECTION_TIMEOUT_MS` | `3000`，范围 1000–60000 |
| `DB_QUERY_TIMEOUT_SECONDS` | `5`，范围 1–300 |
| `DB_MIGRATION_TIMEOUT_SECONDS` | `300`，范围 1–3600，仅用于迁移连接的语句超时 |
| `AUTH_SIGNING_KEY` / `AUTH_RETRY_KEY` | 启动服务必填，两个独立随机 32 字节密钥的标准 Base64；迁移和创建用户不需要 |
| `AUTH_ISSUER` / `AUTH_AUDIENCE` | `kuikly-server` / `kuikly-client` |
| `AUTH_ACCESS_SECONDS` | `900`，范围 60–1800 |
| `AUTH_SESSION_SECONDS` | `2592000`（30 天），范围 3600–2592000；绝对期限，不随刷新延长 |
| `AUTH_GRACE_SECONDS` | `10`，范围 0–15；0 表示不恢复已轮换的令牌 |
| `API_DOCS_ENABLED` | 留空时 dev/test 开启，staging/prod 关闭；可显式设为 `true` 或 `false` |

默认配置集中在 `src/main/resources/application.yaml`，通过环境变量用于开发、预发布和生产环境。`APP_ENV` 只是已校验的环境标识，不自动放宽鉴权或切换数据库；不同环境必须注入各自的配置。

先用具备 DDL 权限的账号执行迁移，再启动应用：

```sh
./gradlew run --args=migrate
./gradlew run
```

生产部署建议在独立发布任务中执行 `migrate`，运行服务时注入只具备所需 DML 和 schema-history 读取权限的账号。启动过程仅做 Flyway 校验，待执行迁移或 checksum 不匹配会阻止启动。禁止自动 baseline 和 Flyway clean；已执行迁移不原地修改。

迁移使用独立的语句超时，socket 超时比对应语句超时长 5 秒，不复用业务查询的短超时。根据实际 DDL 和数据回填规模调整迁移超时；它限制单条语句，不是整批迁移总时长。

V1 建立 `app` schema，V2 建立用户、会话、刷新历史及登录限流表，Flyway 历史表位于 `public`。测试专用 V900 不进入应用发行包。

打包为带依赖和启动脚本的应用目录：

```sh
./gradlew installDist
build/install/kuikly-server/bin/kuikly-server migrate
build/install/kuikly-server/bin/kuikly-server
```

部署主机需要 JDK/JRE 21。服务默认使用 Ktor 的停机流程：1 秒宽限、10 秒停机超时；进入停机阶段先撤销 readiness，再由 `ApplicationStopped` 释放连接池。

## 创建账号与认证

无默认管理员、公开注册或内置测试账号。首次迁移后，在交互式终端运行：

```sh
build/install/kuikly-server/bin/kuikly-server create-user alice USER
build/install/kuikly-server/bin/kuikly-server create-user operator ADMIN
```

密码通过终端隐藏输入并二次确认，不接受命令行密码参数；请直接运行发行包脚本，Gradle `run` 或管道通常没有可用的 Console。账号要求 3–64 位小写 ASCII 字母、数字、点、下划线或连字符，首位必须是字母或数字；密码要求 15–128 个字符。重复账号返回冲突，不覆盖原账号。

为开发终端生成密钥（生产由密钥管理系统注入，避免将输出粘贴到仓库）：

```sh
export AUTH_SIGNING_KEY="$(openssl rand -base64 32)"
export AUTH_RETRY_KEY="$(openssl rand -base64 32)"
```

同一部署的所有实例必须使用相同配置和同一对密钥。当前使用单签名密钥/单重试加密密钥，尚未实现 `kid` 多密钥轮换；密钥变更需要协调发布。签名密钥变化使旧 JWT 失效，加密密钥变化会使尚在宽限期的旧重试密文无法解密。

| 方法与路径 | 请求 / 访问要求 |
| --- | --- |
| `POST /api/v1/auth/login` | `{"username":"alice","password":"..."}` |
| `POST /api/v1/auth/refresh` | `{"refreshToken":"...","requestId":"客户端生成的小写 UUID"}` |
| `POST /api/v1/auth/logout` | Bearer JWT，撤销当前会话，成功 204 |
| `POST /api/v1/auth/logout-all` | Bearer JWT，撤销该用户已有全部会话，成功 204 |
| `GET /api/v1/users/me` | Bearer JWT，返回当前用户 |
| `GET /api/v1/users/{id}` | 本人或 ADMIN；普通用户查他人返回 404 |
| `GET /api/v1/admin/me` | ADMIN 角色，普通用户返回 403 |

登录/刷新成功响应：

```json
{
  "accessToken": "...",
  "refreshToken": "...",
  "expiresIn": 900,
  "sessionId": "...",
  "tokenType": "Bearer"
}
```

用户响应仅包含 `id`、`username`、`role`。令牌与用户响应标记 `Cache-Control: no-store`。所有认证请求限制 4 KiB，未知 JSON 字段拒绝；客户端应宽松解析响应，持久化令牌时使用平台安全存储。

客户端需对刷新采用单次并发协调：每次逻辑刷新生成新的 UUID，超时重试复用同一个旧 Refresh Token 和 requestId。宽限期内返回同一令牌对，`expiresIn` 也是原响应值，实际剩余时间应以 JWT 的 `exp` 为准。后继令牌已被再次使用时，不再恢复旧响应。超窗或不同 requestId 使用已消费令牌会撤销整个会话；其他设备不受影响。该机制是有限的重试容错，不能防止同时窃取旧令牌和请求 ID 的攻击者在窗口内取得后继令牌。

Refresh Token 是 256 位随机值，持久化仅保存 SHA-256 摘要。为了恢复相同响应，额外将令牌对以独立 AES-256-GCM 密钥加密，AAD 绑定旧令牌摘要；过期密文由维护任务清理，历史摘要保留至会话绝对过期，用于检测重放。退出立即清除该会话的重试密文。检测重放后，撤销事务先提交，再响应 401 并写入不含令牌的告警日志。

维护任务每分钟执行一批，最多选取 100 个过期会话及 100 个含过期密文的会话；每个会话独立事务，严格采用用户→会话锁顺序，再单独清理旧登录计数。宽限期到时会立即拒绝恢复，物理删除是异步的；大流量部署需要监测清理积压并调整批量/频率。刷新历史保留量取决于活跃会话数和刷新频率，需通过网关限流及容量监控约束。

每次 JWT 鉴权都检查用户启用状态、会话归属、撤销状态和有效期，不使用会话 TTL 缓存。撤销提交后的后续鉴权将拒绝旧 JWT；已经通过鉴权的在途请求不会被强制回滚。JWT 固定 HS256，并校验 issuer、audience、kind、iat、exp、sub 和 sid。

密码使用 JDK PBKDF2-HMAC-SHA256、600000 次迭代、独立随机盐及恒定时间摘要比较；未知账号也执行密码推导。哈希计算位于数据库事务外，最多 2 路计算、8 个计算或等待任务，超载返回 503。选择 PBKDF2 是为了使用 JDK 标准实现，不代表系统已取得 FIPS 认证。

登录按账号摘要持久化限流，固定一分钟窗口最多 5 次尝试（含成功尝试），超限返回 429；未知账号也受同样限制。这不能替代网关 IP 限流与撞库防护。上线时需在可信网关配置 TLS、登录/刷新速率与连接上限；当前服务不信任客户端自报的代理 IP。管理端的 Cookie/CSRF 策略、MFA、密码找回和改密流程需单独设计。

## OpenAPI 契约

唯一契约源是 `src/main/resources/openapi/kuikly.json`，使用 OpenAPI 3.0.3，随发行包一起打包。覆盖现有 9 个业务与运维操作，包括请求约束、响应结构、状态码、Bearer 鉴权、刷新重试和会话撤销语义。

启动开发服务后访问：

- `http://127.0.0.1:8080/docs`：Ktor 官方 Swagger UI。
- `http://127.0.0.1:8080/openapi.json`：可供客户端工具导入的原始契约。
- `/docs/kuikly.json`：Swagger UI 使用的同一份契约内容。

Swagger UI 的 CSS/JS 使用固定 5.31.0 版本的外部 CDN，浏览器需要网络；离线环境可直接导入 JSON。文档不包含真实凭据。staging/prod 默认不注册上述文档路由；如明确启用，应由部署网络控制访问。关闭文档只关闭 HTTP 入口，发行包内仍保留契约文件。

`OpenApiContractTest` 检查解析诊断、操作标识、请求约束，并用缺字段、错误类型、未声明状态码和缺失必填响应头证明验证器能拒绝响应漂移。契约要求每个响应含 `X-Request-ID`，令牌与用户成功响应还必须含 `Cache-Control: no-store`。真实 PostgreSQL HTTP 测试将所有已记录操作的实际响应交给 OpenAPI 验证器，断言覆盖集合完整。验证库只存在于测试依赖，不参与线上请求处理。

接口变更流程：修改路由/DTO，同时修改契约和对应 HTTP 测试，再运行 `./gradlew clean check`。新增字段、枚举和错误码仍需评估旧客户端兼容性。当前没有基于历史发布契约的自动破坏性变更比较，也没有生成跨平台客户端代码。

## GitHub Actions 验收

工作流位于 `.github/workflows/ci.yml`，按 `kuikly-server` 独立仓库根目录执行，不使用父目录的 `server/` 前缀。触发条件为 pull request、push 到 main 或手动运行。

固定的 `Verify server` job 在 Ubuntu 24.04 上执行：

1. 安装 Temurin JDK 21，通过 Gradle 官方 Action 校验 Wrapper 并设置缓存；PR 只读缓存。
2. 检查 Docker，再执行 `./gradlew --no-daemon --console=plain clean check installDist distTar`。
3. `check` 包含严格依赖解析、普通/契约测试及真实 PostgreSQL 集成测试；不自动修改锁文件，不在 Docker 缺失时跳过测试。
4. 无论测试成功与否均尝试上传测试报告；成功时上传 tar 发行包和 OpenAPI JSON。tar 保留启动脚本执行权限。

报告保留 14 天，发行包产物保留 7 天。Actions 固定完整 commit SHA，工作流仅授予 `contents: read`，不使用生产密钥、不部署；Dependabot 按月检查 GitHub Actions 更新。固定基线为 checkout 7.0.1、setup-java 6.0.1、gradle/actions 6.3.0、upload-artifact 7.0.1。

代码提交并推送后才能在 GitHub 上产生实际运行记录；本地执行构建和工作流静态检查不能替代云端验证。首次云端运行成功后，可在仓库分支保护/ruleset 中把 `Verify server` 设置为 required check。当前尚未修改远程仓库设置。

## HTTP 和事务约定

- `GET /health`：存活返回 `200 {"status":"UP"}`，不依赖数据库。
- `GET /ready`：数据库可用且未进入停机状态返回 200；否则返回 `503 {"status":"DOWN"}`。
- 错误响应为 `{"code":"...","message":"...","traceId":"..."}`，保留 400/401/403/404/409/500/503 等 HTTP 语义。
- 服务器生成 `X-Request-ID` 并写入响应、错误体和 MDC；不采纳客户端传入的同名 ID。它用于本服务日志关联，目前不是 OpenTelemetry 分布式 trace。
- `ContentNegotiation` 严格解析请求 JSON，`ValidatableRequest` 为 DTO 提供统一校验入口。业务错误使用不依赖 Ktor 的 `BusinessException`，未知异常只向客户端返回通用信息。
- 日志以 JSON 输出；访问日志记录方法、路径和状态，不记录请求体、Authorization 或查询参数。内部异常堆栈仅写入服务端日志。

应用层通过 `TransactionRunner.execute { ... }` 定义用例事务边界，块内调用同步仓储方法；Exposed 表和 `ResultRow` 保留在基础设施层。事务块不接受 suspend lambda，并禁止外部 HTTP、短信等网络请求。非挂起签名不能阻止开发者调用同步网络客户端，这一规则仍需代码审查。

JDBC 在按连接池大小限制并行度的 IO dispatcher 中执行，设置连接、驱动 socket、数据库 statement 和 Exposed query 超时，并关闭事务自动重试。取消在进入事务和提交前检查；阻塞 JDBC 不能依靠协程取消瞬间中断，依赖数据库/驱动超时恢复控制。如果取消发生在提交之后，已提交的数据不会因此撤销，业务仍需幂等设计。

## 网络代理

Java/Gradle 不一定自动使用操作系统或终端的代理配置。若浏览器能访问仓库但 Gradle 报 TLS 握手或连接错误，应检查 JVM 代理设置。

需要时，在个人 `~/.gradle/gradle.properties` 中设置 `systemProp.https.proxyHost`、`systemProp.https.proxyPort`，以及对应 HTTP 属性，或者通过命令行 `-Dhttps.proxyHost=... -Dhttps.proxyPort=...` 传入。代理地址、凭据和本机 JDK 路径不应写入项目配置；保留 HTTPS 证书校验。

## 更新依赖

修改版本目录后，有意更新锁并重新验证：

```sh
./gradlew verifyDependencies test integrationTest --write-locks
./gradlew clean check
```

检查 `gradle.lockfile` 中实际解析的版本差异。日常构建不使用 `--write-locks`，CI 也不自动重写锁文件。

修改 Gradle 版本时，同时更新 `build.gradle.kts` 中的版本和官方分发包校验值，再运行两次 `./gradlew wrapper` 更新脚本与 Wrapper JAR。

## 后续建设边界

下一步可补齐部署、备份恢复和容量压测。订单/资金等业务幂等、完整 RBAC 权限表、Redis、多实例故障演练、密钥在线轮换与跨端共享模块不在本次切片范围。

## 参考

- [Kotlin 与 Gradle 兼容矩阵](https://kotlinlang.org/docs/gradle-configure-project.html)
- [Ktor 发布记录](https://ktor.io/docs/releases.html)
- [Exposed 1.x 迁移说明](https://www.jetbrains.com/help/exposed/migration-guide-1-0-0.html)
- [Koin 与 Ktor 集成](https://insert-koin.io/docs/reference/koin-ktor/ktor/)
- [Gradle 依赖锁定](https://docs.gradle.org/9.3.1/userguide/dependency_locking.html)
- [OWASP 密码存储建议](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)
- [Ktor JWT 验证](https://ktor.io/docs/server-jwt.html)
- [Ktor Swagger UI](https://ktor.io/docs/server-swagger-ui.html)
- [OpenAPI Request Validator](https://github.com/atlassian/openapi-request-validator)
- [Gradle GitHub Actions](https://github.com/gradle/actions)
