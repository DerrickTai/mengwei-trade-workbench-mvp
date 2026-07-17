# M1 商家事实与实体中心技术设计

## 1. 设计目标

M1“商家事实与实体中心”不是一个新的孤立模块，而是把当前分散在 `merchants`、`brands`、`evidence_observations`、`content_assets.fact_snapshot`、`ai_generation_records.input_facts`、`assets` 中的事实相关数据，收敛为一个可验证、可追溯、支持有效期和历史版本的统一事实体系。

本设计仅覆盖技术方案，不修改现有代码、数据库和接口实现。

核心目标：

- 兼容当前 `Merchant + Brand + Evidence + Content + AI 调用` 数据。
- 明确品牌、门店、事实、证据、附件、版本的职责边界。
- 保证事实可核验、可过期、可争议、不可覆盖历史。
- 保证内容、报告、AI 调用引用的是“当时版本的事实快照”。
- 保证真实模型只读取当前有效且 `VERIFIED` 的事实。

## 2. 当前数据模型与已有字段

### 2.1 核心表现状

当前数据库迁移已存在 `V1` 到 `V8`。虽然本轮输入要求阅读 `V1-V7`，但代码库当前实际还存在 `V8__real_model_quality_audit.sql`，后续迁移编号必须顺延，不能再使用真实文件名 `V8`。

#### `tenants`

- `id`
- `name`
- `created_at`
- `updated_at`
- `version`

#### `users`

- `id`
- `tenant_id`
- `email`
- `display_name`
- `password_hash`
- `role`
- `created_at`
- `updated_at`
- `version`

#### `merchants`

来自 [V1__local_growth_diagnostic.sql](/Users/derrick/Documents/营销/local-ai-growth-os/backend/src/main/resources/db/migration/V1__local_growth_diagnostic.sql)：

- `id`
- `tenant_id`
- `name`
- `industry`
- `city`
- `district`
- `status`
- `deleted`
- `created_at`
- `updated_at`
- `created_by`
- `updated_by`
- `version`

当前语义：更接近“商家项目 / 客户档案”，并不等同于“品牌”或“门店”。

#### `brands`

- `id`
- `tenant_id`
- `merchant_id`
- `name`
- `website`
- `aliases`
- `services`
- `claims`
- `deleted`
- `created_at`
- `updated_at`
- `created_by`
- `updated_by`
- `version`

当前语义：是非常轻量的“品牌知识库”，但字段只有：

- 品牌名
- 官网
- 别名自由文本
- 服务自由文本
- 主张/证据摘要自由文本

#### `prompt_cases`

当前已支持：

- `question`
- `category`
- `city`
- `district`
- `intent_level`
- `enabled`
- `sort_order`
- `locale`

这些问题本质上是“事实与可见度验证”的问题入口，但没有与事实实体直接关联。

#### `competitors`

- `name`
- `aliases`

仍是轻量自由文本结构。

#### `evidence_observations`

来自 `V2`、`V3`：

- `id`
- `tenant_id`
- `merchant_id`
- `prompt_case_id`
- `platform`
- `model_name`
- `source_type`
- `search_enabled`
- `raw_answer`
- `captured_at`
- `citation_links`
- `screenshot_asset_id`
- `operator_notes`
- `brand_mention_count`
- `brand_first_position`
- `competitor_summary`
- `created_at`
- `updated_at`
- `created_by`
- `updated_by`
- `version`
- `is_demo`

当前语义：保存“观察证据”，但没有把证据拆成结构化事实。

#### `assets`

来自 `V3`：

- `id`
- `tenant_id`
- `object_key`
- `original_filename`
- `mime_type`
- `size`
- `created_at`
- `updated_at`
- `created_by`
- `updated_by`
- `version`

当前语义：MinIO 附件元数据，已支持租户隔离，但没有附件用途分类。

#### `diagnostic_runs` / `diagnostic_reports`

当前报告保存：

- 分数
- Markdown 报告正文
- `score_snapshot`

其中 `score_snapshot` 已承担“当时评分输入快照”的职责，但不是标准化事实快照模型。

#### `optimization_tasks`

当前已支持：

- `task_type`
- `target_question_ids`
- `recommended_channels`

但尚未记录任务依赖的事实版本。

#### `content_assets`

当前已支持：

- `fact_snapshot jsonb`
- `prompt_version`
- `generation_version`
- `risk_flags`
- `review_status`
- `human_edited_content`
- `factuality_score`
- `platform_fit_score`
- `usefulness_score`
- `reviewer_notes`
- `rejection_reasons`
- `reviewed_at`
- `reviewed_by`

当前 `fact_snapshot` 已是“引用事实快照”的雏形，但结构过于粗糙。

#### `ai_generation_records`

当前已支持：

- `input_facts jsonb`
- `generated_content`
- `missing_facts`
- `risk_flags`
- `execution_status`
- `error_message`
- `request_started_at`
- `response_duration_ms`
- `http_status`
- `raw_response_summary`
- `structured_output`

当前语义：已具备 AI 调用审计能力，但输入事实仍是拼接文本，不足以精准追踪事实版本。

#### `audit_logs`

- `tenant_id`
- `actor_id`
- `action`
- `entity_type`
- `entity_id`
- `summary`
- `created_at`

当前适合继续复用，但需要扩展到事实中心相关动作。

## 3. 当前品牌知识库的不足

### 3.1 数据类型不足

当前 `brands.aliases / services / claims` 是无类型自由文本，无法表达：

- 地址与经纬度
- 电话与联系方式
- 营业时间
- 停车与交通
- 服务区域
- 套餐
- 限制条件
- 资质
- 案例
- 禁止宣传事项

### 3.2 缺少状态管理

当前品牌资料没有：

- `UNVERIFIED`
- `VERIFIED`
- `EXPIRED`
- `DISPUTED`

也没有审核人、审核时间、争议原因。

### 3.3 缺少有效期

当前系统无法表达：

- 某个价格只在某个时间段有效
- 某营业时间已过期
- 某套餐暂停销售

### 3.4 缺少版本与历史

当前修改品牌资料时，会覆盖当前值，无法回答：

- 这篇内容使用的是哪个版本的事实
- 这份报告当时引用的价格是什么
- 哪个旧事实后来被判定争议或过期

### 3.5 缺少事实与证据的一对多关系

当前只能把证据粗略写进 `claims` 或 `website`，不能表达：

- 一个事实对应多个证据
- 同一证据支撑多个事实
- 某事实的截图、链接、操作备注、来源平台

### 3.6 缺少品牌与门店分层

当前 `merchant.city/district`、`brand.website`、`claims` 被混用，无法区分：

- 品牌级事实
- 门店级事实
- 多门店共享事实与差异事实

## 4. 如何兼容现有 merchant 数据

### 4.1 当前 `merchant` 的保留策略

当前 `merchant` 不建议废弃。建议继续保留其“租户下的商家工作台 / 客户项目”角色：

- 一个 `merchant` 代表一个运营对象
- 当前所有诊断、任务、内容、报告、证据仍继续挂在 `merchant_id`

这样可最大化兼容现有接口与前端路由。

### 4.2 兼容原则

- `merchant` 继续作为工作台聚合根。
- 在 `merchant` 之下新增“品牌实体”和“门店实体”。
- 旧 `brands` 数据迁移为“默认品牌”。
- 旧 `merchant.city/district` 在迁移期可作为默认门店地理字段候选来源。

### 4.3 过渡期约束

在 M1 完成前后的一段时间内，系统可能同时存在：

- 旧品牌资料入口
- 新事实中心入口

因此必须保留兼容读取策略，避免旧诊断、旧内容生成功能立即失效。

## 5. 是否需要品牌实体和门店实体分离

结论：需要分离。

### 5.1 原因

品牌级事实与门店级事实天然不同：

- 品牌名称、品牌别名、历史名称、品牌定位、统一服务能力，属于品牌级。
- 地址、经纬度、营业时间、停车、交通、联系电话、门店套餐、晚间营业等，属于门店级。

如果不拆分，会导致：

- 多门店场景无法表达
- 一个品牌多个门店的地址和营业时间相互覆盖
- 内容生成无法指定“品牌层内容”还是“门店层内容”

### 5.2 建议的实体关系

- `merchant`：工作台/客户项目根
- `brand_profile`：品牌实体
- `storefront`：门店实体

建议关系：

- 一个 `merchant` 可有一个主品牌，也可未来扩展为多个品牌
- 一个品牌可有多个门店
- 门店必须从属于品牌与 merchant

## 6. 多门店如何支持

### 6.1 目标能力

至少支持：

- 一个品牌多个门店
- 同一问题集面向指定门店或品牌全局
- 事实可区分品牌级与门店级
- 报告与内容生成可指定作用范围

### 6.2 建议方式

新增门店实体时支持以下字段：

- `id`
- `tenant_id`
- `merchant_id`
- `brand_id`
- `name`
- `store_code` 可选
- `city`
- `district`
- `address_line`
- `longitude`
- `latitude`
- `status`
- `is_primary`
- `deleted`
- 审计字段

### 6.3 兼容当前单门店商家

迁移期为每个 `merchant` 自动生成一个“默认主门店”：

- 名称可默认使用 `merchant.name`
- 城市/区域来自 `merchant.city/district`
- 地址为空，状态为 `UNVERIFIED`

## 7. 已验证事实的数据模型

建议新增“事实主表 + 事实版本表 + 事实证据关联表”。

### 7.1 事实主表 `merchant_facts`

职责：定义一个事实条目本身，不保存会频繁变化的正文。

建议字段：

- `id`
- `tenant_id`
- `merchant_id`
- `brand_id` 可空
- `storefront_id` 可空
- `fact_scope`：`BRAND` / `STOREFRONT` / `MERCHANT`
- `fact_type`
- `fact_key`
- `status`
- `current_version_id`
- `effective_from`
- `effective_to`
- `deleted`
- `created_at`
- `updated_at`
- `created_by`
- `updated_by`
- `version`

说明：

- `fact_key` 用于同类事实的业务唯一定位，例如 `brand.primary_name`、`store.address.primary`。
- `current_version_id` 指向当前生效版本。

### 7.2 事实版本表 `merchant_fact_versions`

职责：保存不可覆盖的历史值与快照。

建议字段：

- `id`
- `tenant_id`
- `fact_id`
- `merchant_id`
- `brand_id`
- `storefront_id`
- `fact_type`
- `value_json jsonb`
- `normalized_text`
- `status`
- `effective_from`
- `effective_to`
- `verification_notes`
- `source_summary`
- `is_current`
- `superseded_by`
- `created_at`
- `created_by`
- `version`

说明：

- `value_json` 存结构化值。
- `normalized_text` 用于搜索、比对、展示。
- 历史版本永不更新，只新增。

### 7.3 事实证据关联表 `merchant_fact_evidence_links`

职责：把事实版本与证据绑定。

建议字段：

- `id`
- `tenant_id`
- `fact_version_id`
- `evidence_observation_id` 可空
- `asset_id` 可空
- `external_url`
- `evidence_type`
- `notes`
- `created_at`
- `created_by`

说明：

- 同一个事实版本可绑定多个证据。
- 同一证据也可支撑多个事实版本。

## 8. 事实类型与状态

### 8.1 事实类型

事实不能只保存无类型文本，建议至少支持以下 `fact_type`：

- `BRAND_NAME`
- `STORE_NAME`
- `BRAND_ALIAS`
- `HISTORICAL_NAME`
- `ADDRESS`
- `GEO_LOCATION`
- `PHONE`
- `BUSINESS_HOURS`
- `SERVICE_AREA`
- `PARKING_AND_TRANSIT`
- `SERVICE_ITEM`
- `TARGET_AUDIENCE`
- `PRICE_RANGE`
- `PACKAGE`
- `RESTRICTION`
- `QUALIFICATION`
- `CASE_STUDY`
- `MEDIA_ASSET`
- `CONTACT_METHOD`
- `PROMOTABLE_CLAIM`
- `PROHIBITED_CLAIM`

建议 `value_json` 样例：

- `ADDRESS`：`{ "country": "CN", "province": "...", "city": "...", "district": "...", "addressLine": "...", "postalCode": null }`
- `GEO_LOCATION`：`{ "longitude": 113.1, "latitude": 23.0, "mapProvider": "AMAP" }`
- `PRICE_RANGE`：`{ "currency": "CNY", "min": 98, "max": 298, "unit": "次", "priceQualifier": "以到店沟通为准" }`
- `BUSINESS_HOURS`：`{ "timezone": "Asia/Shanghai", "weekly": [...], "holidayRule": "节假日以门店公告为准" }`
- `CASE_STUDY`：`{ "title": "...", "summary": "...", "customerAuthorization": true, "eventDate": "2026-06-01" }`

### 8.2 状态

至少支持：

- `UNVERIFIED`
- `VERIFIED`
- `EXPIRED`
- `DISPUTED`

语义建议：

- `UNVERIFIED`：已录入，但未完成人工核验或证据不足
- `VERIFIED`：有足够证据且当前有效
- `EXPIRED`：曾经正确，但已过有效期
- `DISPUTED`：存在冲突、投诉、争议或待重新确认

## 9. 事实证据与附件

### 9.1 与现有 `evidence_observations` 的关系

`evidence_observations` 继续作为“外部观察证据”来源，不直接替代事实。

适合承接：

- AI 回答原文
- 平台观察截图
- 引用链接
- 采集时间
- 操作备注

但不适合作为事实最终态。

### 9.2 与现有 `assets` 的关系

`assets` 继续作为统一附件元数据表复用，不建议重复造新附件表。

后续只需增加附件用途枚举或关联关系，而不是替换 `assets`。

### 9.3 证据来源建议

建议 `evidence_type` 支持：

- `OBSERVATION_ANSWER`
- `SCREENSHOT`
- `OFFICIAL_WEBSITE`
- `MAP_LISTING`
- `PLATFORM_STORE_PAGE`
- `INVOICE_OR_ORDER`
- `LICENSE_OR_CERTIFICATE`
- `CHAT_CONFIRMATION`
- `IMAGE`
- `VIDEO`
- `OTHER`

## 10. 事实有效期

### 10.1 必须支持的原因

以下事实明显有时效性：

- 价格区间
- 套餐
- 营业时间
- 服务项目
- 联系方式
- 优惠活动

### 10.2 设计方式

在事实主表和版本表都保留：

- `effective_from`
- `effective_to`

并约束：

- 当前真实模型只读 `status = VERIFIED`
- 且 `effective_from <= now`
- 且 `effective_to is null or effective_to >= now`
- 且 `is_current = true`

## 11. 事实版本与历史快照

### 11.1 原则

历史事实不能覆盖，只能新增版本并切换当前版本。

### 11.2 版本切换流程

1. 新建 `merchant_fact_versions`
2. 旧版本 `is_current=false`
3. 新版本 `is_current=true`
4. 主表 `current_version_id` 指向新版本

### 11.3 历史查询能力

必须支持：

- 查看某事实的所有历史版本
- 查看某时间点有效的事实集
- 查看内容/报告生成时引用的是哪一版

## 12. 内容、报告和 AI 调用如何保存事实版本

### 12.1 当前现状

当前已有：

- `content_assets.fact_snapshot`
- `ai_generation_records.input_facts`
- `diagnostic_reports.score_snapshot`

但它们保存的更多是“文本拼接快照”，不是“事实版本引用”。

### 12.2 建议的统一策略

新增标准快照结构，至少包含：

- `factVersionIds`
- `factTypeSummary`
- `snapshotGeneratedAt`
- `snapshotSource`
- `resolvedFacts`

### 12.3 内容引用

建议 `content_assets.fact_snapshot` 升级为：

```json
{
  "snapshotVersion": "fact-center-v1",
  "factVersionIds": ["..."],
  "resolvedFacts": [
    {
      "factType": "PRICE_RANGE",
      "factVersionId": "...",
      "value": { "currency": "CNY", "min": 98, "max": 298, "unit": "次" }
    }
  ]
}
```

### 12.4 AI 调用引用

`ai_generation_records.input_facts` 建议保存同样结构，而不是只存一段拼接文本。

### 12.5 报告引用

报告继续可保存 `score_snapshot`，但建议新增包含事实快照引用：

- 观察证据 ID 集合
- 问题 ID 集合
- 事实版本 ID 集合

## 13. API 清单

本节为设计建议，不代表本轮实现。

### 13.1 实体查询

- `GET /api/v1/merchants/{merchantId}/fact-center/summary`
- `GET /api/v1/merchants/{merchantId}/brands`
- `GET /api/v1/merchants/{merchantId}/storefronts`

### 13.2 事实查询

- `GET /api/v1/merchants/{merchantId}/facts`
- `GET /api/v1/merchants/{merchantId}/facts/{factId}`
- `GET /api/v1/merchants/{merchantId}/facts/{factId}/versions`
- `GET /api/v1/merchants/{merchantId}/facts/current`

支持筛选：

- `factType`
- `status`
- `scope`
- `brandId`
- `storefrontId`
- `effectiveAt`

### 13.3 事实维护

- `POST /api/v1/merchants/{merchantId}/facts`
- `POST /api/v1/merchants/{merchantId}/facts/{factId}/versions`
- `POST /api/v1/merchants/{merchantId}/facts/{factId}/verify`
- `POST /api/v1/merchants/{merchantId}/facts/{factId}/expire`
- `POST /api/v1/merchants/{merchantId}/facts/{factId}/dispute`

### 13.4 事实证据关联

- `POST /api/v1/merchants/{merchantId}/facts/{factId}/evidence-links`
- `DELETE /api/v1/merchants/{merchantId}/facts/{factId}/evidence-links/{linkId}`

### 13.5 快照解析

- `GET /api/v1/content-assets/{id}/fact-snapshot`
- `GET /api/v1/ai-generation-records/{id}/fact-snapshot`
- `GET /api/v1/reports/{id}/fact-snapshot`

## 14. 前端页面结构

建议在已完成的工作台 Tab 基础上，把 M1 作为“商家事实”一级 Tab 的正式升级。

### 14.1 页面分区

建议 `商家事实` Tab 下拆为以下子区块：

- `实体概览`
- `品牌事实`
- `门店事实`
- `事实证据`
- `历史版本`
- `宣传边界`

### 14.2 具体结构

#### 实体概览

- 当前 `merchant`
- 关联品牌列表
- 门店列表
- 当前有效事实数量
- 过期/争议事实数量

#### 品牌事实

- 品牌名称
- 别名
- 历史名称
- 品牌服务
- 资质
- 可宣传事实
- 禁止宣传事项

#### 门店事实

- 门店名
- 地址
- 坐标
- 电话
- 营业时间
- 服务区域
- 停车/交通
- 套餐
- 价格区间

#### 事实证据

- 引用链接
- 截图
- 附件
- 来自观察证据的关联

#### 历史版本

- 时间轴
- 当前版本与历史版本对比
- 状态变化

#### 宣传边界

- 可用于 AI 内容生成的 VERIFIED 事实
- 过期/争议/禁止宣传事实

## 15. 数据迁移方案

### 15.1 总体原则

- 先增量迁移，不破坏旧表。
- 新老结构并行一段时间。
- 使用脚本安全迁移旧品牌文本。
- 迁移完成前，旧接口继续可用。

### 15.2 迁移步骤建议

1. 为每个 `merchant` 生成默认 `brand_profile`
2. 为每个 `merchant` 生成默认主门店 `storefront`
3. 把 `brands.name` 迁移为 `BRAND_NAME`
4. 把 `brands.aliases` 拆分迁移为多个 `BRAND_ALIAS`
5. 把 `brands.services` 拆分迁移为多个 `SERVICE_ITEM`
6. 把 `brands.website` 迁移为证据链接或品牌官网事实
7. 把 `brands.claims` 迁移为多个 `PROMOTABLE_CLAIM`
8. 把 `merchant.city/district` 迁移为主门店区域事实，初始状态可为 `UNVERIFIED` 或弱验证

### 15.3 对旧数据的保守处理

对自由文本迁移要避免“假精确”：

- 不应自动把 `claims` 里的所有句子都标为 `VERIFIED`
- 拆不清的内容应以 `UNVERIFIED` 导入
- 带明显时效性的价格/套餐如果无法确认日期，应限制为 `UNVERIFIED`

## 16. 旧接口兼容方案

### 16.1 兼容原则

现有接口短期内不能直接废弃：

- `GET /merchants/{id}/brands`
- `POST /merchants/{id}/brands`

### 16.2 读兼容

旧 `brands` 接口可以在迁移后改为“从事实中心聚合回投影”：

- `name` <- 当前 `BRAND_NAME`
- `aliases` <- 当前有效 `BRAND_ALIAS`
- `services` <- 当前有效 `SERVICE_ITEM`
- `claims` <- 当前有效 `PROMOTABLE_CLAIM`
- `website` <- 品牌官网事实或首个官网证据

### 16.3 写兼容

旧 `POST /brands` 短期可保留为“兼容写入入口”：

- 写入后同时落旧表与事实中心，或者
- 优先写事实中心，再异步/同步投影回旧表

建议过渡期采用“新写入事实中心，旧表作为投影缓存”的方向。

## 17. 租户隔离

### 17.1 当前实现

当前系统通过：

- 登录 token 解析 `tenant_id`
- 所有 SQL 显式加 `tenant_id`
- 附件访问按 `tenant_id` 校验

已形成基础租户隔离。

### 17.2 M1 要求

新增所有事实相关表必须包含：

- `tenant_id`
- 必要时同时校验 `merchant_id`

### 17.3 额外要求

- 事实版本查询必须校验租户
- 事实证据关联必须校验附件与证据同租户
- AI 事实解析接口必须只返回当前租户的有效事实

## 18. 审计要求

建议以下动作写入 `audit_logs`：

- 创建品牌实体
- 创建门店实体
- 新增事实
- 新增事实版本
- 事实状态改为 `VERIFIED`
- 事实状态改为 `EXPIRED`
- 事实状态改为 `DISPUTED`
- 绑定/解绑证据
- 导出事实快照
- 内容生成使用事实快照

审计摘要至少应包含：

- 事实类型
- 作用范围
- 状态变化
- 关联对象

## 19. 测试与验收方案

### 19.1 单元与集成测试重点

- 事实类型序列化/反序列化
- 有效期过滤
- 当前版本切换
- 历史版本不可覆盖
- 同租户访问通过，跨租户访问拒绝
- 内容/报告/AI 调用读取的仅为 `VERIFIED + 当前有效`

### 19.2 迁移测试

- 旧 `brands` 数据能迁移为事实中心数据
- 迁移后旧品牌接口返回不为空
- 旧诊断、旧内容生成不崩溃

### 19.3 验收样例

以“千色坊”为例至少验证：

- 一个品牌
- 一个主门店
- 事实类型覆盖品牌名、别名、服务、地址、营业时间、价格区间、案例、禁止宣传事项
- 至少一个事实有多个证据
- 修改价格后保留旧版本
- 旧报告仍保留旧快照
- 新 AI 调用仅读取当前有效 `VERIFIED` 事实

## 20. 潜在风险

### 20.1 数据拆分复杂度

`brands.claims`、`services`、旧演示数据中大量信息是自由文本，自动拆分会有误判风险。

### 20.2 新旧双写复杂度

过渡期如果新旧模型同时写入，容易出现投影不一致。

### 20.3 多门店范围膨胀

如果一次性支持复杂品牌树、多品牌多门店，会让 M1 过重。建议先支持“单 merchant 一个主品牌 + 多门店”。

### 20.4 报告与内容快照不统一

如果只改事实中心，不同步定义快照结构，后续仍会出现“知道当前事实，不知道历史引用版本”的问题。

### 20.5 Flyway 版本冲突

当前仓库已有物理 `V8`，M1 实施时必须使用下一个真实迁移编号，例如 `V9`，不能直接创建另一个 `V8`。

## 21. 建议的 Flyway V8 结构

这里的“V8 结构”按产品规划语义描述，不等于实际文件名。由于代码库已有真实 `V8`，实现时建议使用下一可用版本号。

### 21.1 建议新增表

- `brand_profiles`
- `storefronts`
- `merchant_facts`
- `merchant_fact_versions`
- `merchant_fact_evidence_links`

### 21.2 建议新增索引

- `merchant_facts (tenant_id, merchant_id, fact_type, status)`
- `merchant_facts (tenant_id, brand_id)`
- `merchant_facts (tenant_id, storefront_id)`
- `merchant_fact_versions (tenant_id, fact_id, created_at desc)`
- `merchant_fact_versions (tenant_id, status, effective_to)`
- `merchant_fact_evidence_links (tenant_id, fact_version_id)`

### 21.3 建议的迁移包顺序

建议拆成三个逻辑包：

1. 结构包
   - 新建品牌、门店、事实、事实版本、证据关联表
2. 投影包
   - 回填默认品牌、默认门店
   - 迁移旧 `brands` 和 `merchant.city/district`
3. 兼容包
   - 为旧品牌接口准备兼容视图或聚合读取策略

## 22. 建议结论

M1 最稳妥的方向不是“直接替换 brands 表”，而是：

- 保留 `merchant` 作为工作台聚合根
- 引入 `品牌实体 + 门店实体 + 结构化事实 + 事实版本 + 事实证据`
- 让旧品牌知识库逐步退化为兼容投影
- 让内容、报告、AI 调用统一引用“事实版本快照”

这样既能兼容现有 GEO 诊断、证据链、任务和内容生成闭环，也能为后续“平台资产中心、内容执行、复测对比、可收费交付”打下更可靠的数据底座。
