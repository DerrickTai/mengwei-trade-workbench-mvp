alter table evidence_observations add column is_demo boolean not null default false;
update evidence_observations set is_demo=true where operator_notes like '演示人工观察%';

create table assets (
  id uuid primary key,
  tenant_id uuid not null references tenants(id),
  object_key varchar(500) not null unique,
  original_filename varchar(500) not null,
  mime_type varchar(100) not null,
  size bigint not null,
  created_at timestamp with time zone not null,
  updated_at timestamp with time zone not null,
  created_by uuid,
  updated_by uuid,
  version bigint not null
);
alter table evidence_observations alter column screenshot_asset_id type uuid using case when screenshot_asset_id ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' then screenshot_asset_id::uuid else null end;
alter table evidence_observations add constraint fk_evidence_screenshot foreign key (screenshot_asset_id) references assets(id);
