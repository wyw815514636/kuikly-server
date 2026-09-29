# OpenAPI 与 CI 验收

依据：用户已确认补齐 OpenAPI 契约与 CI。延续当前 server 独立仓库，不提交、不推送，不更改远程仓库设置。

## 设计

- OpenAPI 3.0.3 JSON 为版本管理的契约源，覆盖现有 9 个业务/探针操作；描述鉴权、DTO、状态码、请求 ID、缓存与刷新重试语义。
- Ktor 官方 Swagger UI 渲染契约，提供 /docs 及 /openapi.json。开发/测试默认启用，staging/prod 默认关闭，API_DOCS_ENABLED 可显式覆盖。
- 契约验证库仅进入测试依赖。解析检查、错误样本反例和真实 Ktor/PostgreSQL HTTP 响应验证共同防止文档失真。
- GitHub Actions 在 PR、main push 和手动触发时执行 JDK 21、Wrapper 校验、严格依赖锁、所有测试和发行包构建；Docker 不可用直接失败。
- 固定 Actions commit SHA，contents: read，PR 缓存只读，不接入生产密钥、不部署；保留测试报告、契约和发行包产物。

## 实施与验收

1. [x] 添加契约及文档开关失败测试，完成契约源与文档路由。
2. [x] 增加实际 HTTP 契约验证以及验证器错误样本测试。
3. [x] 创建 CI 工作流并校验语法，补齐 README 与环境变量说明。
4. [x] 完整 clean check installDist、独立只读审查及必要修复。

## 决策

- Ruling: 当前采用显式契约源与契约测试，避免引入实验性编译器推断链；增加接口时必须同步维护文档和测试。
- Ruling: 在现有授权内执行本地实现及验证；GitHub 上的真实运行和 required-check 分支保护需代码推送后启用，本次不推送或修改远程设置。

## 审查重点

检查契约是否准确覆盖既有路由和错误，测试是否验证实际响应、是否有反例证明门禁有效；生产文档默认关闭；CI 路径适用于 server 独立仓库、依赖锁不被自动重写、失败不被跳过、Actions 固定 SHA 和最小权限。

## 执行与审查记录

- 文档开关测试先因缺少实现失败，接入后开发/测试默认启用、生产默认关闭和显式开关测试通过。
- 测试依赖采用已更名的 `com.atlassian.oai:openapi-request-validator-core:3.0.0`；确认原 swagger-request-validator-core 3.0.0 仅为 relocation POM，避免使用旧坐标。
- 增加 Ktor 官方 Swagger 3.5.2，与现有 Ktor BOM 对齐；Swagger UI 固定 5.31.0，CDN 依赖在 README 说明。依赖锁已重新解析并保存。
- 实际 HTTP 契约覆盖初次失败定位到 /users/me 与 /users/{id} 的测试匹配歧义，改为优先精确路径后覆盖全部 9 个操作。
- Actions SHA 经官方 GitHub release/ref API 核验：checkout 7.0.1、setup-java 6.0.1、gradle/actions 6.3.0、upload-artifact 7.0.1；确认 setup-gradle 的 validate-wrappers 输入和 Node 24 运行时。
- actionlint 1.7.12 使用官方 release amd64 包并核对 SHA256 `5b44c3bc2255115c9b69e30efc0fecdf498fdb63c5d58e17084fd5f16324c644`，工作流检查退出码 0。
- 独立审查发现 1 项 Important：契约响应头未 required，删掉刷新/用户响应的 no-store 不会失败。新增合法响应体但缺失缓存头/请求 ID 的测试先失败，随后将所有声明响应头设为 required；原有字段类型反例提供合法响应头，避免因缺头掩盖正文漂移检测。
- 发行产物采用 Gradle distTar，以保留启动脚本执行权限；测试报告与契约单独上传。没有发布或部署动作。

## 最终验收（2026-09-29）

- `./gradlew --no-daemon --console=plain clean check installDist distTar --offline`：BUILD SUCCESSFUL，44 秒，12 项任务全部执行。
- JUnit XML：21 项普通/契约测试 + 21 项真实 PostgreSQL 集成测试 = 42 项，失败、错误、跳过均为 0。
- 独立审查 1 项 Important 已经失败复现→修复通过，并完成全量回归；没有遗留 Minor。响应正文漂移测试不依赖缺失响应头来产生失败。
- actionlint 工作流语法检查通过；没有在 GitHub 上实际触发工作流或设置 required check。
- 发行包使用独立临时 PostgreSQL 17.11 容器验证：dev 默认提供 /docs、/openapi.json、/docs/kuikly.json；prod 默认全部 404；prod 显式开启后正常提供。文档内容与源契约一致，Swagger HTML 固定 UI 版本。各次 SIGTERM 后数据库连接数为 0，临时容器已清理。
- 最终修订契约后，再次静态核对 installDist JAR 和 distTar 内的契约与源文件逐字节一致；tar 启动脚本保留可执行权限。
- 原有代码及本轮修改保持未提交、未推送。
