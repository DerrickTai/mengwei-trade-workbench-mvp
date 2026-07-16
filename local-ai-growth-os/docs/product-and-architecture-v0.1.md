# Local AI Growth OS：产品与架构设计 v0.1

**状态：** 30 天可售 GEO 诊断产品的设计基线（2026-07-15）  
**产品定位：** 为本地商家及其服务团队提供可复现、可解释、可交付的 AI 回答可见度诊断。

## 1. 参考边界与产品原则

本设计参考了以下开源项目公开说明中体现的产品思路，**未复制代码、页面、Prompt 文本或数据结构**：

- [Geo-AI-Doubao-deepseek-search-result-optimization-software](https://github.com/biha-droid/Geo-AI-Doubao-deepseek-search-result-optimization-software.)：借鉴多模型抽象、配置与密钥隔离、调用失败可恢复等工程方向；其浏览器自动化和多平台自动发布能力不在本产品范围内。
- [geo-workbench](https://github.com/liyan18262179881/geo-workbench)：借鉴商家诊断、问题矩阵、模型验证、竞品对照及交付检查表的闭环；不采用其内容分发流程。

同名 `geo-workbench` 仓库较多；本次选择上面这个明确以“客户诊断 / 市场检测 / 发布复查”为主线的仓库作为方法论参考。若目标仓库不同，应在开始实现前替换此参考链接。

产品原则：

1. **先观测，再建议，再由人执行。** 产品不以生成或发布海量内容制造虚假影响力。
2. **一次结果必须可复现。** 每条结果绑定商家、品牌、Prompt、模型 ID、模型版本、请求时间、原始响应哈希和评分规则版本。
3. **评分必须可解释。** 使用确定性规则计算，AI 仅可辅助提取候选信息，不能直接决定分数或“通过”。
4. **报告不承诺排名。** 只陈述已观测到的 AI 回答表现、样本范围、局限和下一步建议。
5. **安全与合规优先。** 不做自动刷评论、自动注册、模拟登录、绕过验证码、爬取受限内容或平台违规操作。

## 2. 30 天 MVP 的商家闭环

```text
商家/品牌建档 → 品牌知识校验 → 问题集配置 → 多模型测试
      → 回答快照与品牌/竞品提及解析 → GEO Score → 建议报告 → 人工交付
```

### 商家可见能力

| 模块 | 可售版本能力 | 第一阶段不做 |
| --- | --- | --- |
| 商家实体 | 商家、门店/服务区域、行业、联系人、诊断状态 | CRM 跟进漏斗、自动营销 |
| 品牌知识库 | 品牌名/别名、官网、服务、资质、案例、主张及来源证据 | 无证据的自动事实补全 |
| Prompt 测试 | 可版本化问题集、意图/地域/语言标签、人工试跑与批量运行 | 未经确认的大规模自动化测试 |
| 多模型采集 | Mock Provider 和官方 API 的 OpenAI 兼容 Provider；记录请求与结果 | 未授权的网页抓取或账号操作 |
| GEO Score | 覆盖、显著性、信息一致性、竞争份额的确定性评分 | 黑盒“排名保证” |
| 品牌与竞品 | 每回答的品牌/竞品出现、位置、上下文和汇总趋势 | 恶意攻击或贬损性文案 |
| 优化报告 | 带样本、证据和优先级的 Markdown/PDF/JSON 报告草稿 | 自动代发、自动刷评 |

## 3. 模块化单体架构

```text
Vue 3 SPA ── JSON/HTTPS ── Spring Boot modular monolith ── PostgreSQL
       │                            │                         Redis
       │                            ├── ProviderPort ── 官方模型 API
       │                            └── ObjectStoragePort ── S3/MinIO（报告与附件）
       └── 静态托管 / Nginx
```

后端保持一个可部署单元，模块之间仅通过明确的应用服务/领域接口协作：

```text
identity      租户、用户、角色、JWT、审计身份
merchant      商家、地点/服务区域、诊断生命周期
brand         品牌、别名、知识库、事实主张、证据来源
prompt        Prompt 模板、问题集、测试用例、版本快照
model         ProviderPort、连接配置、调用、限流、回答快照
measurement   品牌提及解析、竞品比较、GEO Score、评分规则
report        诊断报告、建议、导出
audit         关键操作、不可抵赖的输入/输出变更摘要
shared        API 错误、租户上下文、乐观锁、存储、配置
```

每个模块采用 `api → application → domain → infrastructure`：Controller 只做协议转换；应用服务管理用例与事务；领域服务管理评分和解析规则；基础设施实现 JPA、Redis、HTTP 客户端和对象存储。

## 4. 核心数据模型

所有租户拥有的业务表包含 `id`（UUID）、`tenant_id`、`created_at`、`updated_at`、`created_by`、`updated_by` 和 `version`。Tenant 是唯一根实体，以自身 ID 标识隔离边界；其余所有数据均从已认证主体取得 tenantId，接口不接受可写的 tenantId。

| 领域 | 实体 | 关键字段与约束 |
| --- | --- | --- |
| identity | Tenant, User, Role | User 邮箱在租户内唯一；角色为 `TENANT_ADMIN`、`ANALYST`、`VIEWER`。 |
| merchant | Merchant, MerchantLocation | Merchant 包含行业、状态和展示名称；Location 记录服务区域/地址（最小化保存个人信息）。 |
| brand | Brand, BrandAlias, KnowledgeDocument, KnowledgeFragment, BrandClaim, ClaimEvidence | Brand 归属 Merchant；每个可评分主张必须有证据链接/附件或标记为“未验证”。 |
| prompt | PromptTemplate, PromptSet, PromptCase, PromptRun | 测试用例保存文本、地域、语言、意图和启用状态；运行时冻结内容和模板版本。 |
| model | ModelProvider, ModelConnection, ModelInvocation, AnswerSnapshot | Provider 采用适配器；原始回答、模型/版本、耗时、用量、错误、内容哈希均保存。密钥不进入快照或日志。 |
| measurement | MentionRule, BrandMention, CompetitorBrand, GeoScoreSnapshot, GeoScoreComponent | Mention 保存命中别名、字符位置、上下文片段和解析规则版本；Score 保存各维度与输入样本快照。 |
| report | DiagnosticReport, ReportRecommendation, ReportArtifact | 报告引用诊断运行和 Score；建议有优先级、证据、负责人/状态，不自动执行。 |
| audit | AuditLog | 追加式记录创建、编辑、运行、导出和人工确认；敏感值仅保留脱敏摘要。 |

软删除只用于 Merchant、Brand、BrandAlias、PromptSet、PromptCase、CompetitorBrand 和用户；默认查询过滤已删除记录。`AnswerSnapshot`、`ModelInvocation`、`GeoScoreSnapshot`、`DiagnosticReport` 与 `AuditLog` 是历史证据，不允许通过更新或软删除破坏可追溯性。

## 5. 多模型采集设计

`ModelProviderPort` 是唯一的模型调用边界：

```java
ModelResponse invoke(ModelRequest request, ModelConnection connection);
ProviderHealth check(ModelConnection connection);
```

第一阶段实现：

- `MockProvider`：用于本地开发、演示、确定性测试；显式标注为模拟数据，绝不混入正式分数。
- `OpenAiCompatibleProvider`：通过已授权的端点、模型 ID 与 API Key 调用 OpenAI 兼容 Chat Completions API；可覆盖符合该协议的供应商。

一条 `PromptRun` 按 `(PromptCase × 已选 ModelConnection)` 创建幂等的 `ModelInvocation`。系统限制租户并发，处理 429/5xx 的有界退避，保留失败原因；只对明确提交的测试用例调用 API。请求包含商家设定的测试 Prompt，不夹带未授权的知识库全文或用户数据。

API Key 只从环境变量或由主密钥加密的连接配置读取；显示时永远脱敏；审计日志不记录 Key、Authorization Header 或原始供应商错误体。

## 6. GEO Score：可解释、确定性、版本化

GEO Score 是某一次诊断运行的观测指标，不是“AI 排名”。只统计成功返回且属于该运行的回答；模拟模型和失败请求不进入正式分母。

默认规则版本 `GEO_SCORE_V1`：

| 分量 | 权重 | 计算方法 |
| --- | ---: | --- |
| 回答覆盖率 | 30 | 含主品牌有效提及的回答数 / 成功回答数。 |
| 模型覆盖率 | 20 | 至少一次提及主品牌的模型数 / 成功模型数。 |
| 提及显著性 | 20 | 每回答按首次提及的归一化字符位置计算；越靠前得分越高，未提及为 0。 |
| 信息一致性 | 15 | 命中的品牌服务/资质描述与已验证 `BrandClaim` 一致的比例；证据不足不加分。 |
| 竞争可见度份额 | 15 | 主品牌出现回答数 /（主品牌出现回答数 + 配置竞品出现回答数），无竞品样本时显示“不足以比较”而非补零。 |

总分是各分量的加权和并四舍五入至一位小数。每个 `GeoScoreSnapshot` 固化：规则版本、权重、样本 ID、分母、逐回答判定与计算时间。改动别名、竞品、Prompt 或权重后，系统生成新快照，绝不覆盖历史结果。

### 提及解析

第一版使用规范化文本 + 品牌别名词典 + 边界匹配（中英文分别配置）进行确定性候选命中；每个命中记录位置与上下文。对于同名歧义、否定语义和不确定归属，只能标记 `NEEDS_REVIEW`，由分析员确认后才能进入正式评分。LLM 可在后续提供“候选分类”辅助，但不得越过人工/规则确认改变正式分数。

## 7. API 与页面清单

| 区域 | 核心 API | 页面 |
| --- | --- | --- |
| 认证 | `POST /api/v1/auth/login` | 登录 |
| 商家 | `GET/POST /merchants`、`GET/PATCH /merchants/{id}` | 商家列表、商家档案 |
| 品牌知识 | `GET/POST /merchants/{id}/brands`、`.../knowledge-documents`、`.../claims` | 品牌档案、知识库、事实主张 |
| Prompt | `GET/POST /prompt-sets`、`.../cases`、`POST /prompt-runs` | Prompt 测试台 |
| 诊断 | `POST /diagnostic-runs`、`GET /diagnostic-runs/{id}` | 运行进度、回答明细 |
| 评分 | `GET /diagnostic-runs/{id}/score`、`GET .../mentions` | GEO Score、品牌/竞品对比 |
| 报告 | `POST /diagnostic-runs/{id}/reports`、`GET /reports/{id}` | 报告预览、导出 |

统一错误响应包含稳定错误码、字段错误和请求 ID；分页有上限、按稳定字段排序。所有写接口使用 Bean Validation，更新接口使用乐观锁版本字段。

## 8. 30 天交付计划与验收

| 天数 | 可交付切片 | 验收条件 |
| --- | --- | --- |
| 1–3 | 工程基础、租户/JWT、商家 CRUD、审计 | 一个租户无法读取另一个租户的商家；完整 Docker 本地启动。 |
| 4–7 | 品牌知识库、主张与证据 | 商家可保存品牌别名、服务与有来源的事实；变更有审计。 |
| 8–11 | PromptSet/PromptCase、Mock 与 OpenAI 兼容 Provider | 同一输入可用 Mock 稳定测试；供应商调用有超时、失败、用量记录。 |
| 12–15 | 诊断运行、回答快照、提及解析 | 每条回答可展示品牌/竞品命中、位置、规则版本和人工复核状态。 |
| 16–19 | GEO Score 与对比面板 | 改权重或别名后新旧快照都可查看；统计口径可见。 |
| 20–23 | 优化建议、报告预览与导出 | 报告包含样本范围、观测值、局限、证据链接与人工建议。 |
| 24–27 | 性能/安全/测试/可观测性 | Testcontainers 覆盖迁移和租户隔离；审计、错误追踪和备份流程可用。 |
| 28–30 | 3–5 家真实商家试点与收费演示包 | 从建档到报告的演示少于 30 分钟；试点反馈转为下一轮需求。 |

“完成”以真实商家从建档到报告闭环、核心数据可回溯、租户隔离测试通过为准，不以页面数量或模型数量为准。

## 9. 为后续 Agent 与商业模块预留的扩展点

后续能力只通过任务/提案接口接入，不直接绕过现有数据与审计边界：

```text
AgentTask (输入快照、授权范围、状态)
  → AgentArtifact（内容/分析结果及来源）
  → AgentProposal（待人工确认的动作）
  → 人工确认后写入领域模块
```

- **内容生成 Agent：** 从经确认的 BrandClaim 和报告建议生成待审核草稿。
- **评论分析 Agent：** 分析商家已合法导入的评论数据，不抓取或代发评论。
- **小红书运营 Agent：** 管理选题、草稿、人工审批与效果记录；不控制账号、不自动发布。
- **CRM：** 通过 Merchant/Contact 的独立模块接入，不污染诊断历史。
- **成交分析：** 使用匿名化的来源标记，将线索/成交与报告版本关联，避免把相关性误报为因果。

## 10. 主要风险与默认决策

- **模型输出不稳定：** 同一 Prompt 在不同时间可能不同；因此按运行快照比较，不承诺绝对结果。
- **同名品牌误判：** 默认进入人工复核，不能用模糊匹配直接提高分数。
- **样本偏差：** 报告展示模型、时间、Prompt 数量与失败率；小样本不输出强结论。
- **供应商限流/成本：** 配置租户级预算、并发和最大重试数；先只支持明确选择的模型。
- **品牌事实幻觉：** 知识库把“已验证主张”与“待验证素材”分开，报告优先引用有证据内容。
- **隐私与密钥：** 最小化采集联系人信息；密钥加密、脱敏、不可导出；响应访问遵循租户和角色权限。
- **产品命名歧义：** 此项目独立于已有的电商商品资料项目，目录为 `local-ai-growth-os/`；不改动或删除已有系统。

