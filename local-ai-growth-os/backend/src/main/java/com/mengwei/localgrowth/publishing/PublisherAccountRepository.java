package com.mengwei.localgrowth.publishing;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** M6.2 data-access boundary; account lifecycle operations are added in Checkpoint C. */
@Repository
public class PublisherAccountRepository {
  private final NamedParameterJdbcTemplate db;

  public PublisherAccountRepository(NamedParameterJdbcTemplate db) {
    this.db = db;
  }

  public List<Map<String, Object>> list(UUID tenantId, UUID merchantId) {
    return db.queryForList("""
        select * from publisher_accounts
        where tenant_id=:tenantId and merchant_id=:merchantId and deleted=false
        order by created_at desc
        """, scope(tenantId, merchantId));
  }

  public Map<String, Object> find(UUID tenantId, UUID merchantId, UUID accountId) {
    List<Map<String, Object>> rows = db.queryForList("""
        select * from publisher_accounts
        where id=:accountId and tenant_id=:tenantId and merchant_id=:merchantId and deleted=false
        """, scope(tenantId, merchantId).addValue("accountId", accountId));
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private MapSqlParameterSource scope(UUID tenantId, UUID merchantId) {
    return new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("merchantId", merchantId);
  }
}
