# M2 平台资产图谱 MVP 设计

## 目标与边界

平台资产图谱用于记录商家在小红书、点评和高德上的公开资料，并与事实中心当前有效版本进行可追溯比较。第一版只支持人工录入、人工核验和人工整改登记，不接入爬虫、平台 API、自动登录、Cookie 或自动发布。

## 1. 数据模型

### platform_profiles

一条记录代表一个商家在一个平台上的资料档案：

- `id`, `tenant_id`, `merchant_id`
- `platform`：`XIAOHONGSHU`、`DIANPING`、`AMAP`
- `account_name`、`profile_url`
- `verification_status`：`UNVERIFIED`、`VERIFIED`、`DISPUTED`
- `last_observed_at`、`last_verified_at`
- `notes`, `deleted`, `created_at`, `updated_at`, `created_by`, `updated_by`, `version`

同一租户、商家、平台保留一个当前档案；历史通过字段版本保存，不覆盖原始记录。

### platform_profile_fields

采用结构化字段表而非无约束 JSON，便于比较和审计：

- `id`, `tenant_id`, `profile_id`
- `field_key`：如 `BRAND_NAME`、`STORE_NAME`、`ADDRESS`、`PHONE`、`BUSINESS_HOURS`、`SERVICE_ITEM`、`PRICE_RANGE`、`SERVICE_AREA`、`CONTACT_METHOD`
- `value_json`、`normalized_text`
- `source_type`：`MANUAL_ENTRY`、`SCREENSHOT`、`EXTERNAL_LINK`
- `source_ref`、`observed_at`、`verified_at`
- `status`：`UNVERIFIED`、`VERIFIED`、`DISPUTED`、`OUTDATED`
- `created_at`, `created_by`, `version`

字段历史不可更新；修正时新增版本并将旧值标记为 `OUTDATED`。

## 2. 比较逻辑

比较以商家事实中心的 `merchant_fact_versions` 为权威基线：

1. 读取当前有效、`VERIFIED` 的品牌/门店事实。
2. 按 `field_key` 建立平台字段与事实类型映射。
3. 对文本、电话、地址、营业时间和价格做确定性标准化：去空格、统一标点、大小写、电话格式和价格范围格式。
4. 按平台档案与事实逐项比较，并保存 `fact_version_id` 和 `platform_field_id`，确保结果可追溯。
5. 未发现对应平台字段时生成缺失 Gap；值不同但双方均存在时生成冲突 Gap；平台值超过事实有效期时生成过期 Gap；平台字段未核验时生成未核验 Gap。

## 3. Gap类型

- `MISSING_FIELD`：事实中心有要求，平台资料缺失。
- `VALUE_CONFLICT`：平台值与当前有效事实不一致。
- `OUTDATED_VALUE`：平台值来自已过期或被新版本替代的事实。
- `UNVERIFIED_VALUE`：平台资料存在，但尚未人工核验。

Gap保存：`platform_profile_id`、`platform_field_id`、`fact_version_id`、类型、差异摘要、优先级、状态和生成时间。不得用 Gap 直接覆盖任何事实。

## 4. 前端“平台资产”Tab

- 顶部：商家名称、支持平台切换、小型状态摘要（已核验字段、待核验字段、Gap数量）。
- 平台档案卡：账号/门店名称、资料链接、最后观察时间、核验状态。
- 字段表：平台字段值、事实中心值、差异状态、来源、核验操作和版本历史。
- Gap面板：按平台、类型、优先级筛选；展开显示两边值和证据引用。
- 操作：新增/编辑人工资料、上传截图或外链、标记核验、运行比较、从选中 Gap 生成任务。
- 空状态明确说明“当前仅支持人工录入，不会自动抓取平台”。

## 5. 从 Gap 生成任务

一键为一个或多个 Gap 创建现有 `optimization_tasks`：

- `task_type` 映射：缺字段→`LOCAL_LISTING`；值冲突/过期→`ENTITY_PROFILE`；未核验→`THIRD_PARTY_CITATION` 或 `LOCAL_LISTING`。
- `title` 包含平台和字段；`description` 包含差异、目标值和证据要求。
- 复制 `merchant_id`、`target_question_ids`（如有）、`recommended_channels` 和 Gap 来源 ID。
- 去重键为 `tenant_id + gap_id + task_type`，重复点击不重复创建。

## 6. 旧任务体系复用

继续使用 `optimization_tasks`、任务状态、负责人、截止日期和现有执行中心页面；仅新增来源关联字段（优先采用现有 JSON 扩展字段，避免本轮新表）。原有诊断生成任务不受影响。

## 7. Flyway

当前版本之后使用 `V12__create_platform_asset_graph.sql`，创建 `platform_profiles`、`platform_profile_fields` 和必要索引。若仓库已有 V12，则顺延下一个未使用版本，不改写历史迁移。

## 8. MVP验收标准

1. 千色坊可分别录入小红书、点评、高德档案。
2. 每个平台可录入名称、地址、电话、营业时间、服务和价格等字段。
3. 字段支持来源、核验状态和历史版本。
4. 比较结果正确生成四类 Gap，并可追溯到事实版本和平台字段。
5. 修改事实后重新比较，旧 Gap 和新结果均可解释。
6. 选中 Gap 可幂等生成现有优化任务。
7. 租户无法读取其他商家的平台资料、字段和 Gap。
8. Docker、健康检查和既有诊断/执行流程不受影响。
