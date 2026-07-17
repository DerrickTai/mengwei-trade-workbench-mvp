-- The migration is intentionally set based: one primary brand and storefront per merchant,
-- and one conservative fact version per legacy field.  Unique indexes make it idempotent.
insert into brand_profiles (id,tenant_id,merchant_id,legal_name,is_primary,deleted,created_at,updated_at,created_by,updated_by,version)
select md5('brand-profile:' || m.id::text)::uuid,m.tenant_id,m.id,coalesce(nullif(b.name,''),m.name),true,false,m.created_at,m.updated_at,m.created_by,m.updated_by,0
from merchants m left join lateral (
  select name from brands b where b.tenant_id=m.tenant_id and b.merchant_id=m.id and not b.deleted order by b.created_at limit 1
) b on true
where not m.deleted
on conflict do nothing;

insert into storefronts (id,tenant_id,merchant_id,brand_id,name,is_primary,deleted,created_at,updated_at,created_by,updated_by,version)
select md5('storefront:' || m.id::text)::uuid,m.tenant_id,m.id,bp.id,m.name,true,false,m.created_at,m.updated_at,m.created_by,m.updated_by,0
from merchants m join brand_profiles bp on bp.tenant_id=m.tenant_id and bp.merchant_id=m.id and bp.is_primary and not bp.deleted
where not m.deleted
on conflict do nothing;

-- Brand name: legacy name is the only imported fact treated as verified.
insert into merchant_facts (id,tenant_id,merchant_id,brand_id,storefront_id,fact_scope,fact_type,fact_key,deleted,created_at,updated_at,created_by,updated_by,version)
select md5('fact:brand-name:' || m.id::text)::uuid,m.tenant_id,m.id,bp.id,null,'BRAND','BRAND_NAME','primary',false,m.created_at,m.updated_at,m.created_by,m.updated_by,0
from merchants m join brand_profiles bp on bp.tenant_id=m.tenant_id and bp.merchant_id=m.id and bp.is_primary and not bp.deleted
where not m.deleted on conflict do nothing;
insert into merchant_fact_versions (id,tenant_id,fact_id,merchant_id,brand_id,storefront_id,fact_type,value_json,normalized_text,status,effective_from,effective_to,verification_notes,source_summary,created_at,created_by,version)
select md5('fact-version:brand-name:' || m.id::text)::uuid,m.tenant_id,f.id,m.id,bp.id,null,'BRAND_NAME',jsonb_build_object('name',bp.legal_name),bp.legal_name,'VERIFIED',m.created_at,null,'从旧品牌名称迁移，待后续人工复核','legacy brands.name',m.created_at,m.created_by,0
from merchants m join brand_profiles bp on bp.tenant_id=m.tenant_id and bp.merchant_id=m.id and bp.is_primary and not bp.deleted join merchant_facts f on f.tenant_id=m.tenant_id and f.merchant_id=m.id and f.fact_type='BRAND_NAME' and f.fact_key='primary' and not f.deleted
where not m.deleted on conflict do nothing;
update merchant_facts f set current_version_id=v.id from merchant_fact_versions v where v.fact_id=f.id and f.current_version_id is null;

-- Legacy free text remains conservative and unverified. Values are deliberately not split.
insert into merchant_facts (id,tenant_id,merchant_id,brand_id,storefront_id,fact_scope,fact_type,fact_key,deleted,created_at,updated_at,created_by,updated_by,version)
select md5('fact:' || kind || ':' || m.id::text)::uuid,m.tenant_id,m.id,bp.id,null,'BRAND',fact_type,'legacy',false,m.created_at,m.updated_at,m.created_by,m.updated_by,0
from merchants m join brand_profiles bp on bp.tenant_id=m.tenant_id and bp.merchant_id=m.id and bp.is_primary and not bp.deleted
left join lateral (select aliases,services,claims from brands b where b.tenant_id=m.tenant_id and b.merchant_id=m.id and not b.deleted order by b.created_at limit 1) b on true
cross join lateral (values ('BRAND_ALIAS',coalesce(b.aliases,'')),('SERVICE_ITEM',coalesce(b.services,'')),('PROMOTABLE_CLAIM',coalesce(b.claims,''))) as x(fact_type,val)
cross join lateral (select lower(x.fact_type) as kind) k
where not m.deleted and nullif(trim(x.val),'') is not null and trim(x.val) <> '无'
on conflict do nothing;
insert into merchant_fact_versions (id,tenant_id,fact_id,merchant_id,brand_id,storefront_id,fact_type,value_json,normalized_text,status,effective_from,effective_to,verification_notes,source_summary,created_at,created_by,version)
select md5('fact-version:' || lower(f.fact_type) || ':' || m.id::text)::uuid,m.tenant_id,f.id,m.id,bp.id,null,f.fact_type,jsonb_build_object('legacyText',case f.fact_type when 'BRAND_ALIAS' then b.aliases when 'SERVICE_ITEM' then b.services else b.claims end),case f.fact_type when 'BRAND_ALIAS' then b.aliases when 'SERVICE_ITEM' then b.services else b.claims end,'UNVERIFIED',m.created_at,null,'自由文本未自动拆分，需人工核验','legacy brands.' || lower(replace(f.fact_type,'BRAND_','')),m.created_at,m.created_by,0
from merchants m join brand_profiles bp on bp.tenant_id=m.tenant_id and bp.merchant_id=m.id and bp.is_primary and not bp.deleted join merchant_facts f on f.tenant_id=m.tenant_id and f.merchant_id=m.id and f.fact_key='legacy' and f.fact_type in ('BRAND_ALIAS','SERVICE_ITEM','PROMOTABLE_CLAIM') left join lateral (select aliases,services,claims from brands b where b.tenant_id=m.tenant_id and b.merchant_id=m.id and not b.deleted order by b.created_at limit 1) b on true
where not m.deleted on conflict do nothing;
update merchant_facts f set current_version_id=v.id from merchant_fact_versions v where v.fact_id=f.id and f.current_version_id is null;

-- City/district and the legacy website are imported without guessing street address or claim semantics.
insert into merchant_facts (id,tenant_id,merchant_id,brand_id,storefront_id,fact_scope,fact_type,fact_key,deleted,created_at,updated_at,created_by,updated_by,version)
select md5('fact:address:' || m.id::text)::uuid,m.tenant_id,m.id,bp.id,s.id,'STOREFRONT','ADDRESS','legacy-region',false,m.created_at,m.updated_at,m.created_by,m.updated_by,0
from merchants m join brand_profiles bp on bp.tenant_id=m.tenant_id and bp.merchant_id=m.id and bp.is_primary and not bp.deleted join storefronts s on s.tenant_id=m.tenant_id and s.merchant_id=m.id and s.is_primary and not s.deleted
where not m.deleted and (nullif(m.city,'') is not null or nullif(m.district,'') is not null) on conflict do nothing;
insert into merchant_fact_versions (id,tenant_id,fact_id,merchant_id,brand_id,storefront_id,fact_type,value_json,normalized_text,status,effective_from,effective_to,verification_notes,source_summary,created_at,created_by,version)
select md5('fact-version:address:' || m.id::text)::uuid,m.tenant_id,f.id,m.id,bp.id,s.id,'ADDRESS',jsonb_build_object('city',m.city,'district',m.district),concat_ws(' ',m.city,m.district),'UNVERIFIED',m.created_at,null,'仅迁移城市和区域，不推断详细地址','legacy merchants.city/district',m.created_at,m.created_by,0
from merchants m join brand_profiles bp on bp.tenant_id=m.tenant_id and bp.merchant_id=m.id and bp.is_primary and not bp.deleted join storefronts s on s.tenant_id=m.tenant_id and s.merchant_id=m.id and s.is_primary and not s.deleted join merchant_facts f on f.tenant_id=m.tenant_id and f.merchant_id=m.id and f.fact_type='ADDRESS' and f.fact_key='legacy-region' and not f.deleted
where not m.deleted on conflict do nothing;
update merchant_facts f set current_version_id=v.id from merchant_fact_versions v where v.fact_id=f.id and f.current_version_id is null;

insert into merchant_facts (id,tenant_id,merchant_id,brand_id,storefront_id,fact_scope,fact_type,fact_key,deleted,created_at,updated_at,created_by,updated_by,version)
select md5('fact:website:' || m.id::text)::uuid,m.tenant_id,m.id,bp.id,null,'BRAND','CONTACT_METHOD','legacy-website',false,m.created_at,m.updated_at,m.created_by,m.updated_by,0
from merchants m join brand_profiles bp on bp.tenant_id=m.tenant_id and bp.merchant_id=m.id and bp.is_primary and not bp.deleted join brands b on b.tenant_id=m.tenant_id and b.merchant_id=m.id and not b.deleted
where not m.deleted and nullif(trim(b.website),'') is not null on conflict do nothing;
insert into merchant_fact_versions (id,tenant_id,fact_id,merchant_id,brand_id,storefront_id,fact_type,value_json,normalized_text,status,effective_from,effective_to,verification_notes,source_summary,created_at,created_by,version)
select md5('fact-version:website:' || m.id::text)::uuid,m.tenant_id,f.id,m.id,bp.id,null,'CONTACT_METHOD',jsonb_build_object('website',b.website),b.website,'UNVERIFIED',m.created_at,null,'旧官网链接待人工核验','legacy brands.website',m.created_at,m.created_by,0
from merchants m join brand_profiles bp on bp.tenant_id=m.tenant_id and bp.merchant_id=m.id and bp.is_primary and not bp.deleted join merchant_facts f on f.tenant_id=m.tenant_id and f.merchant_id=m.id and f.fact_type='CONTACT_METHOD' and f.fact_key='legacy-website' and not f.deleted join brands b on b.tenant_id=m.tenant_id and b.merchant_id=m.id and not b.deleted
where not m.deleted and nullif(trim(b.website),'') is not null on conflict do nothing;
update merchant_facts f set current_version_id=v.id from merchant_fact_versions v where v.fact_id=f.id and f.current_version_id is null;
