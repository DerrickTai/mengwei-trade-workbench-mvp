create table brand_profiles (
  id uuid primary key,
  tenant_id uuid not null references tenants(id),
  merchant_id uuid not null references merchants(id),
  legal_name varchar(200) not null,
  is_primary boolean not null default false,
  deleted boolean not null default false,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  created_by uuid,
  updated_by uuid,
  version bigint not null default 0
);
create unique index uq_brand_profiles_primary on brand_profiles(tenant_id, merchant_id) where is_primary and not deleted;
create index idx_brand_profiles_merchant on brand_profiles(tenant_id, merchant_id) where not deleted;

create table storefronts (
  id uuid primary key,
  tenant_id uuid not null references tenants(id),
  merchant_id uuid not null references merchants(id),
  brand_id uuid not null references brand_profiles(id),
  name varchar(200) not null,
  is_primary boolean not null default false,
  deleted boolean not null default false,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  created_by uuid,
  updated_by uuid,
  version bigint not null default 0
);
create unique index uq_storefronts_primary on storefronts(tenant_id, brand_id) where is_primary and not deleted;
create index idx_storefronts_merchant on storefronts(tenant_id, merchant_id, brand_id) where not deleted;

create table merchant_facts (
  id uuid primary key,
  tenant_id uuid not null references tenants(id),
  merchant_id uuid not null references merchants(id),
  brand_id uuid references brand_profiles(id),
  storefront_id uuid references storefronts(id),
  fact_scope varchar(20) not null,
  fact_type varchar(50) not null,
  fact_key varchar(160) not null,
  current_version_id uuid,
  deleted boolean not null default false,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  created_by uuid,
  updated_by uuid,
  version bigint not null default 0,
  constraint ck_merchant_facts_scope check (fact_scope in ('BRAND','STOREFRONT')),
  constraint ck_merchant_facts_scope_owner check ((fact_scope='BRAND' and brand_id is not null and storefront_id is null) or (fact_scope='STOREFRONT' and brand_id is not null and storefront_id is not null))
);
create unique index uq_merchant_facts_identity on merchant_facts(tenant_id, merchant_id, fact_scope, fact_type, fact_key) where not deleted;
create index idx_merchant_facts_current on merchant_facts(tenant_id, merchant_id, fact_scope, fact_type) where not deleted;

create table merchant_fact_versions (
  id uuid primary key,
  tenant_id uuid not null references tenants(id),
  fact_id uuid not null references merchant_facts(id),
  merchant_id uuid not null references merchants(id),
  brand_id uuid references brand_profiles(id),
  storefront_id uuid references storefronts(id),
  fact_type varchar(50) not null,
  value_json jsonb not null,
  normalized_text text not null,
  status varchar(20) not null,
  effective_from timestamptz,
  effective_to timestamptz,
  verification_notes text not null default '',
  source_summary text not null default '',
  created_at timestamptz not null,
  created_by uuid,
  version bigint not null default 0,
  constraint ck_merchant_fact_versions_status check (status in ('UNVERIFIED','VERIFIED','EXPIRED','DISPUTED')),
  constraint ck_merchant_fact_versions_dates check (effective_to is null or effective_from is null or effective_to >= effective_from)
);
alter table merchant_facts add constraint fk_merchant_fact_current_version foreign key (current_version_id) references merchant_fact_versions(id);
create index idx_merchant_fact_versions_fact on merchant_fact_versions(tenant_id, fact_id, created_at desc);
create index idx_merchant_fact_versions_state on merchant_fact_versions(tenant_id, merchant_id, status, effective_from, effective_to);

create table merchant_fact_evidence_links (
  id uuid primary key,
  tenant_id uuid not null references tenants(id),
  fact_version_id uuid not null references merchant_fact_versions(id),
  evidence_observation_id uuid references evidence_observations(id),
  asset_id uuid references assets(id),
  external_url varchar(1200),
  evidence_type varchar(40) not null,
  notes text not null default '',
  created_at timestamptz not null,
  created_by uuid,
  constraint ck_merchant_fact_evidence_target check (evidence_observation_id is not null or asset_id is not null or external_url is not null)
);
create index idx_merchant_fact_evidence_version on merchant_fact_evidence_links(tenant_id, fact_version_id);
