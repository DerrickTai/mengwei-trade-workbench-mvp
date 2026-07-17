alter table optimization_tasks add column if not exists storefront_id uuid references storefronts(id);
alter table content_assets add column if not exists storefront_id uuid references storefronts(id);
alter table ai_generation_records add column if not exists storefront_id uuid references storefronts(id);
create index if not exists idx_tasks_storefront on optimization_tasks(tenant_id,merchant_id,storefront_id);
