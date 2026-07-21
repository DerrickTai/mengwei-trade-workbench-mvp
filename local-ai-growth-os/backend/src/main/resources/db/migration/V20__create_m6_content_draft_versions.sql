create table m6_content_draft_versions (
 id uuid primary key, tenant_id uuid not null references tenants(id), merchant_id uuid not null references merchants(id), work_item_id uuid not null references geo_execution_work_items(id), brief_id uuid not null references geo_content_briefs(id), draft_version integer not null,
 status varchar(32) not null, idempotency_key varchar(160) not null, provider_code varchar(64), model_name varchar(128), prompt_version varchar(64) not null, title text, body text, structured_content jsonb not null default '{}', evidence_pack jsonb not null default '[]', evidence_pack_hash varchar(64), claim_evidence_result jsonb not null default '{}', risk_scan_result jsonb not null default '{}', generation_metadata jsonb not null default '{}', review_comment text,
 created_at timestamptz not null, updated_at timestamptz not null, created_by uuid, updated_by uuid, version_lock bigint not null default 0,
 unique(tenant_id,merchant_id,idempotency_key), unique(brief_id,draft_version),
 constraint ck_m6_draft_version_status check(status in ('GENERATING','DRAFT','EVIDENCE_BLOCKED','RISK_BLOCKED','REVIEW_PENDING','APPROVED','REJECTED','SUPERSEDED','FAILED'))
);
create index idx_m6_draft_versions_scope on m6_content_draft_versions(tenant_id,merchant_id,brief_id,draft_version desc);
