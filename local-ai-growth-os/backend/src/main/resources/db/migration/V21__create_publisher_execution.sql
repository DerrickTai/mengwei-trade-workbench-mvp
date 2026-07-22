-- M6.2 publisher execution foundation.
-- V21 has not been applied yet. Do not alter this file after Flyway records it.

create table publisher_accounts (
    id uuid primary key,
    tenant_id uuid not null references tenants(id),
    merchant_id uuid not null references merchants(id),
    external_account_id varchar(240),
    profile_ref varchar(240) not null,
    credential_ref varchar(240),
    platform varchar(32) not null,
    display_name varchar(200) not null,
    expected_nickname varchar(200),
    default_publish_mode varchar(32) not null default 'MANUAL_CONFIRM',
    status varchar(32) not null default 'LOGIN_REQUIRED',
    worker_id varchar(120),
    non_sensitive_config jsonb not null default '{}'::jsonb,
    last_login_checked_at timestamptz,
    last_login_error varchar(1000),
    deleted boolean not null default false,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    created_by uuid,
    updated_by uuid,
    version_lock bigint not null default 0,
    constraint uk_publisher_accounts_scope_id unique (tenant_id, merchant_id, id),
    constraint ck_publisher_accounts_platform check (platform in ('XIAOHONGSHU')),
    constraint ck_publisher_accounts_mode check (default_publish_mode in ('DRAFT', 'MANUAL_CONFIRM')),
    constraint ck_publisher_accounts_status check (status in ('LOGIN_REQUIRED', 'ACTIVE', 'BUSY', 'DISABLED', 'ERROR')),
    constraint ck_publisher_accounts_version_lock check (version_lock >= 0)
);

comment on column publisher_accounts.profile_ref is
    'Opaque Worker browser-profile reference only; never browser credential contents.';
comment on column publisher_accounts.credential_ref is
    'Opaque external credential-store reference only; never cookie, password, token, or local-storage contents.';
comment on column publisher_accounts.non_sensitive_config is
    'Whitelist-only non-sensitive publishing configuration; must not contain credentials or browser session material.';

create unique index uk_publisher_accounts_external_active
    on publisher_accounts(tenant_id, merchant_id, platform, external_account_id)
    where external_account_id is not null and deleted = false;
create unique index uk_publisher_accounts_profile_active
    on publisher_accounts(profile_ref)
    where deleted = false;
create index idx_publisher_accounts_scope_status
    on publisher_accounts(tenant_id, merchant_id, platform, status)
    where deleted = false;

create or replace function publisher_accounts_validate_scope()
returns trigger language plpgsql as $$
begin
    if not exists (
        select 1 from merchants m where m.id = new.merchant_id and m.tenant_id = new.tenant_id
    ) then
        raise exception 'publisher account merchant must belong to tenant';
    end if;
    return new;
end;
$$;

create trigger trg_publisher_accounts_validate_scope
before insert or update of tenant_id, merchant_id on publisher_accounts
for each row execute function publisher_accounts_validate_scope();

create or replace function publisher_accounts_prevent_delete()
returns trigger language plpgsql as $$
begin
    raise exception 'PUBLISHER_ACCOUNT_DELETE_FORBIDDEN: publisher accounts are soft-delete only';
end;
$$;

create trigger trg_publisher_accounts_prevent_delete
before delete on publisher_accounts
for each row execute function publisher_accounts_prevent_delete();

create table publisher_jobs (
    id uuid primary key,
    tenant_id uuid not null references tenants(id),
    merchant_id uuid not null references merchants(id),
    account_id uuid not null,
    source_type varchar(32) not null default 'M6_DRAFT',
    source_draft_id uuid not null references m6_content_draft_versions(id),
    source_draft_version integer not null,
    idempotency_key varchar(160) not null,
    platform varchar(32) not null,
    content_type varchar(32) not null,
    publish_mode varchar(32) not null,
    status varchar(32) not null default 'PENDING',
    title_snapshot text not null,
    body_snapshot text not null,
    structured_content_snapshot jsonb not null default '{}'::jsonb,
    topics_snapshot jsonb not null default '[]'::jsonb,
    content_hash varchar(64) not null,
    evidence_pack_hash varchar(64),
    approved_by uuid not null references users(id),
    approved_at timestamptz not null,
    scheduled_at timestamptz,
    claimed_by varchar(120),
    lease_until timestamptz,
    heartbeat_at timestamptz,
    dispatched_at timestamptz,
    started_at timestamptz,
    completed_at timestamptz,
    worker_job_id varchar(160),
    worker_id varchar(120),
    last_callback_sequence bigint not null default 0,
    platform_content_id varchar(240),
    published_url text,
    screenshot_asset_id uuid references assets(id),
    attempt_count integer not null default 0,
    error_code varchar(120),
    error_message text,
    result_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    created_by uuid not null,
    updated_by uuid,
    version_lock bigint not null default 0,
    constraint uk_publisher_jobs_scope_id unique (tenant_id, merchant_id, id),
    constraint uk_publisher_jobs_idempotency unique (tenant_id, merchant_id, idempotency_key),
    constraint fk_publisher_jobs_account_scope foreign key (tenant_id, merchant_id, account_id)
        references publisher_accounts(tenant_id, merchant_id, id),
    constraint ck_publisher_jobs_source check (source_type = 'M6_DRAFT'),
    constraint ck_publisher_jobs_platform check (platform in ('XIAOHONGSHU')),
    constraint ck_publisher_jobs_content_type check (content_type in ('IMAGE_NOTE')),
    constraint ck_publisher_jobs_mode check (publish_mode in ('DRAFT', 'MANUAL_CONFIRM')),
    constraint ck_publisher_jobs_status check (status in (
        'PENDING', 'QUEUED', 'CLAIMED', 'RUNNING', 'WAITING_LOGIN', 'WAITING_HUMAN',
        'DRAFT_SAVED', 'PUBLISHED', 'FAILED_RETRYABLE', 'FAILED_FINAL', 'CANCELLED', 'EXPIRED'
    )),
    constraint ck_publisher_jobs_idempotency_key check (
        char_length(btrim(idempotency_key)) between 16 and 128 and idempotency_key !~ '^[[:space:]]*$'
    ),
    constraint ck_publisher_jobs_source_draft_version check (source_draft_version > 0),
    constraint ck_publisher_jobs_attempt_count check (attempt_count >= 0),
    constraint ck_publisher_jobs_version_lock check (version_lock >= 0),
    constraint ck_publisher_jobs_last_callback_sequence check (last_callback_sequence >= 0),
    constraint ck_publisher_jobs_content_hash check (content_hash ~ '^[0-9a-f]{64}$'),
    constraint ck_publisher_jobs_evidence_pack_hash check (
        evidence_pack_hash is null or evidence_pack_hash ~ '^[0-9a-f]{64}$'
    ),
    constraint ck_publisher_jobs_draft_not_published check (
        publish_mode <> 'DRAFT' or status <> 'PUBLISHED'
    ),
    constraint ck_publisher_jobs_draft_saved_mode check (
        status <> 'DRAFT_SAVED' or publish_mode = 'DRAFT'
    ),
    constraint ck_publisher_jobs_published_mode check (
        status <> 'PUBLISHED' or publish_mode = 'MANUAL_CONFIRM'
    ),
    constraint ck_publisher_jobs_published_result check (
        status <> 'PUBLISHED' or nullif(btrim(coalesce(published_url, '')), '') is not null
        or nullif(btrim(coalesce(platform_content_id, '')), '') is not null
    )
);

create index idx_publisher_jobs_due
    on publisher_jobs(status, scheduled_at, created_at)
    where status in ('PENDING', 'FAILED_RETRYABLE');
create index idx_publisher_jobs_scope
    on publisher_jobs(tenant_id, merchant_id, created_at desc);
create index idx_publisher_jobs_account
    on publisher_jobs(account_id, status, created_at);
create index idx_publisher_jobs_lease
    on publisher_jobs(lease_until)
    where lease_until is not null;

create or replace function publisher_jobs_validate_scope()
returns trigger language plpgsql as $$
begin
    if not exists (
        select 1 from m6_content_draft_versions d
        where d.id = new.source_draft_id and d.tenant_id = new.tenant_id and d.merchant_id = new.merchant_id
    ) then
        raise exception 'publisher job source draft must belong to tenant and merchant';
    end if;
    if not exists (
        select 1 from users u where u.id = new.approved_by and u.tenant_id = new.tenant_id
    ) then
        raise exception 'publisher job approver must belong to tenant';
    end if;
    if new.screenshot_asset_id is not null and not exists (
        select 1 from assets a where a.id = new.screenshot_asset_id and a.tenant_id = new.tenant_id
    ) then
        raise exception 'publisher job screenshot asset must belong to tenant';
    end if;
    return new;
end;
$$;

create trigger trg_publisher_jobs_validate_scope
before insert or update of tenant_id, merchant_id, source_draft_id, approved_by, screenshot_asset_id on publisher_jobs
for each row execute function publisher_jobs_validate_scope();

create or replace function publisher_jobs_freeze_snapshot()
returns trigger language plpgsql as $$
begin
    if new.tenant_id is distinct from old.tenant_id
        or new.merchant_id is distinct from old.merchant_id
        or new.account_id is distinct from old.account_id
        or new.platform is distinct from old.platform
        or new.source_type is distinct from old.source_type
        or new.source_draft_id is distinct from old.source_draft_id
        or new.source_draft_version is distinct from old.source_draft_version
        or new.title_snapshot is distinct from old.title_snapshot
        or new.body_snapshot is distinct from old.body_snapshot
        or new.structured_content_snapshot is distinct from old.structured_content_snapshot
        or new.topics_snapshot is distinct from old.topics_snapshot
        or new.content_hash is distinct from old.content_hash
        or new.evidence_pack_hash is distinct from old.evidence_pack_hash
        or new.publish_mode is distinct from old.publish_mode
        or new.idempotency_key is distinct from old.idempotency_key
        or new.approved_by is distinct from old.approved_by
        or new.approved_at is distinct from old.approved_at
        or new.created_by is distinct from old.created_by
        or new.created_at is distinct from old.created_at then
        raise exception 'publisher job approved publication snapshot is immutable';
    end if;
    return new;
end;
$$;

create trigger trg_publisher_jobs_freeze_snapshot
before update on publisher_jobs
for each row execute function publisher_jobs_freeze_snapshot();

create or replace function publisher_jobs_prevent_delete()
returns trigger language plpgsql as $$
begin
    raise exception 'PUBLISHER_JOB_DELETE_FORBIDDEN: publisher jobs are terminal-state only';
end;
$$;

create trigger trg_publisher_jobs_prevent_delete
before delete on publisher_jobs
for each row execute function publisher_jobs_prevent_delete();

create table publisher_job_assets (
    id uuid primary key,
    tenant_id uuid not null references tenants(id),
    merchant_id uuid not null references merchants(id),
    job_id uuid not null,
    asset_id uuid not null references assets(id),
    sort_order integer not null default 0,
    object_key_snapshot varchar(500) not null,
    mime_type_snapshot varchar(160) not null,
    size_bytes_snapshot bigint not null,
    sha256_snapshot varchar(64) not null,
    created_at timestamptz not null,
    created_by uuid,
    constraint fk_publisher_job_assets_job_scope foreign key (tenant_id, merchant_id, job_id)
        references publisher_jobs(tenant_id, merchant_id, id),
    constraint uk_publisher_job_assets_order unique(job_id, sort_order),
    constraint uk_publisher_job_assets_asset unique(job_id, asset_id),
    constraint ck_publisher_job_assets_sort_order check (sort_order >= 0),
    constraint ck_publisher_job_assets_size check (size_bytes_snapshot >= 0),
    constraint ck_publisher_job_assets_sha256 check (sha256_snapshot ~ '^[0-9a-f]{64}$')
);

create or replace function publisher_job_assets_validate_scope()
returns trigger language plpgsql as $$
begin
    if not exists (
        select 1 from assets a where a.id = new.asset_id and a.tenant_id = new.tenant_id
    ) then
        raise exception 'publisher job asset must belong to tenant';
    end if;
    return new;
end;
$$;

create trigger trg_publisher_job_assets_validate_scope
before insert or update of tenant_id, asset_id on publisher_job_assets
for each row execute function publisher_job_assets_validate_scope();

create or replace function publisher_job_assets_freeze()
returns trigger language plpgsql as $$
declare
    parent_status varchar(32);
begin
    if tg_op = 'INSERT' then
        select status into parent_status
        from publisher_jobs
        where id = new.job_id and tenant_id = new.tenant_id and merchant_id = new.merchant_id;
        if parent_status is distinct from 'PENDING' then
            raise exception 'PUBLISHER_JOB_ASSET_INSERT_FORBIDDEN: assets may only be attached while job is PENDING';
        end if;
        return new;
    end if;
    if tg_op = 'UPDATE' then
        raise exception 'PUBLISHER_JOB_ASSET_UPDATE_FORBIDDEN: publication asset snapshots are immutable';
    end if;
    raise exception 'PUBLISHER_JOB_ASSET_DELETE_FORBIDDEN: publication asset snapshots are immutable';
end;
$$;

create trigger trg_publisher_job_assets_freeze
before insert or update or delete on publisher_job_assets
for each row execute function publisher_job_assets_freeze();

create table publisher_job_events (
    id uuid primary key,
    tenant_id uuid not null references tenants(id),
    merchant_id uuid not null references merchants(id),
    job_id uuid not null,
    sequence_no bigint not null,
    event_type varchar(80) not null,
    status varchar(32),
    message text,
    payload jsonb not null default '{}'::jsonb,
    occurred_at timestamptz not null,
    created_at timestamptz not null,
    created_by uuid,
    constraint fk_publisher_job_events_job_scope foreign key (tenant_id, merchant_id, job_id)
        references publisher_jobs(tenant_id, merchant_id, id),
    constraint uk_publisher_job_events_sequence unique(job_id, sequence_no),
    constraint ck_publisher_job_events_sequence check (sequence_no > 0)
);

create index idx_publisher_job_events_job
    on publisher_job_events(job_id, sequence_no);

create or replace function publisher_job_events_append_only()
returns trigger language plpgsql as $$
begin
    raise exception 'publisher_job_events are append-only';
end;
$$;

create trigger trg_publisher_job_events_append_only
before update or delete on publisher_job_events
for each row execute function publisher_job_events_append_only();

create table publisher_outbox (
    id uuid primary key,
    tenant_id uuid not null references tenants(id),
    merchant_id uuid not null references merchants(id),
    job_id uuid not null,
    event_type varchar(80) not null,
    payload jsonb not null,
    deduplication_key varchar(128) not null,
    status varchar(32) not null default 'PENDING',
    attempt_count integer not null default 0,
    next_attempt_at timestamptz not null,
    claimed_by varchar(120),
    lease_until timestamptz,
    last_error text,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint fk_publisher_outbox_job_scope foreign key (tenant_id, merchant_id, job_id)
        references publisher_jobs(tenant_id, merchant_id, id),
    constraint uk_publisher_outbox_deduplication unique(tenant_id, deduplication_key),
    constraint ck_publisher_outbox_status check (status in ('PENDING', 'PROCESSING', 'SENT', 'FAILED')),
    constraint ck_publisher_outbox_attempt_count check (attempt_count >= 0),
    constraint ck_publisher_outbox_deduplication_key check (
        char_length(btrim(deduplication_key)) between 1 and 128 and deduplication_key !~ '^[[:space:]]*$'
    )
);

create index idx_publisher_outbox_due
    on publisher_outbox(status, next_attempt_at, created_at)
    where status in ('PENDING', 'PROCESSING');
create index idx_publisher_outbox_lease
    on publisher_outbox(lease_until)
    where lease_until is not null;

create table publisher_callback_events (
    id uuid primary key,
    event_id uuid,
    request_id varchar(160),
    nonce varchar(160),
    tenant_id uuid not null references tenants(id),
    merchant_id uuid not null references merchants(id),
    job_id uuid not null,
    sequence_no bigint,
    status varchar(32),
    raw_body text not null,
    payload jsonb,
    body_hash char(64) not null,
    signature_valid boolean not null,
    signature_error text,
    sent_at timestamptz,
    received_at timestamptz not null default now(),
    processed_at timestamptz,
    constraint fk_publisher_callback_events_job_scope foreign key (tenant_id, merchant_id, job_id)
        references publisher_jobs(tenant_id, merchant_id, id),
    constraint ck_publisher_callback_status check (status is null or status in (
        'PENDING', 'QUEUED', 'CLAIMED', 'RUNNING', 'WAITING_LOGIN', 'WAITING_HUMAN',
        'DRAFT_SAVED', 'PUBLISHED', 'FAILED_RETRYABLE', 'FAILED_FINAL', 'CANCELLED', 'EXPIRED'
    )),
    constraint ck_publisher_callback_body_hash check (body_hash ~ '^[0-9a-f]{64}$'),
    constraint ck_publisher_callback_valid_signature_fields check (
        not signature_valid or (
            event_id is not null and request_id is not null and nonce is not null
            and sequence_no is not null and sequence_no > 0 and sent_at is not null and status is not null
        )
    )
);

create unique index uk_publisher_callback_event_valid
    on publisher_callback_events(event_id)
    where signature_valid = true;
create unique index uk_publisher_callback_sequence_valid
    on publisher_callback_events(job_id, sequence_no)
    where signature_valid = true;
create unique index uk_publisher_callback_request_valid
    on publisher_callback_events(request_id)
    where signature_valid = true;
create unique index uk_publisher_callback_nonce_valid
    on publisher_callback_events(nonce)
    where signature_valid = true;
create index idx_publisher_callback_events_job
    on publisher_callback_events(tenant_id, merchant_id, job_id, sequence_no)
    where signature_valid = true;

create or replace function publisher_callback_events_append_only()
returns trigger language plpgsql as $$
begin
    raise exception 'publisher_callback_events are append-only';
end;
$$;

create trigger trg_publisher_callback_events_append_only
before update or delete on publisher_callback_events
for each row execute function publisher_callback_events_append_only();
