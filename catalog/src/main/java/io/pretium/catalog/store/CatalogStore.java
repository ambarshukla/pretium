package io.pretium.catalog.store;

import io.pretium.catalog.quality.Finding;
import io.pretium.catalog.quality.QuoteChecks;
import io.pretium.events.CollectionRun;
import io.pretium.events.ProviderQuoteObserved;
import io.pretium.events.ReferenceRateObserved;
import java.sql.Date;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes what arrives from Kafka. Every write is an upsert keyed on the observation's natural key,
 * so a message delivered twice (Kafka promises at-least-once) leaves the same row behind.
 */
@Repository
public class CatalogStore {

  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CatalogStore.class);

  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  public CatalogStore(JdbcTemplate jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  public void saveRun(CollectionRun run) {
    jdbc.update(
        """
        insert into collection_run (run_id, source_id, url, started_at, finished_at, status, http_status,
                                    bytes, sha256, recipe_version, extracted, message)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        on conflict (run_id) do nothing
        """,
        UUID.fromString(run.runId()),
        run.sourceId(),
        run.url(),
        Timestamp.from(run.startedAt()),
        Timestamp.from(run.finishedAt()),
        run.status().name(),
        run.httpStatus(),
        run.bytes(),
        run.sha256(),
        run.recipeVersion(),
        run.extracted(),
        run.message());
  }

  @Transactional
  public void saveRates(List<ReferenceRateObserved> rates) {
    jdbc.batchUpdate(
        """
        insert into reference_rate (source_id, base, quote, rate_date, rate, run_id, evidence_sha256)
        values (?, ?, ?, ?, ?, ?, ?)
        on conflict (source_id, base, quote, rate_date) do update
           set rate = excluded.rate,
               run_id = excluded.run_id,
               evidence_sha256 = excluded.evidence_sha256,
               last_seen_at = now()
        """,
        rates,
        500,
        (ps, r) -> {
          ps.setString(1, r.sourceId());
          ps.setString(2, r.base());
          ps.setString(3, r.quote());
          ps.setDate(4, Date.valueOf(r.rateDate()));
          ps.setBigDecimal(5, r.rate());
          ps.setObject(6, UUID.fromString(r.runId()));
          ps.setString(7, r.evidenceSha256());
        });
  }

  /** Checks each price and stores it with its verdict. Returns how many were quarantined. */
  @Transactional
  public int saveQuotes(List<ProviderQuoteObserved> quotes) {
    record Row(
        ProviderQuoteObserved q, ProviderQuoteObserved.Tier t, String status, String findings) {}
    List<Row> rows = new ArrayList<>();
    int quarantined = 0;
    for (ProviderQuoteObserved q : quotes) {
      if (q.sendCountry() == null
          || q.receiveCountry() == null
          || q.provider() == null
          || q.period() == null) {
        // Without a corridor, a provider and a period there is nothing to key the row on.
        quarantined += q.tiers().size();
        log.warn(
            "{} {}: no corridor, provider or period; not stored", q.sourceId(), q.sourceRecordId());
        continue;
      }
      for (ProviderQuoteObserved.Tier t : q.tiers()) {
        List<Finding> findings = QuoteChecks.check(q, t);
        String status = QuoteChecks.status(findings);
        if (status.equals("QUARANTINED")) {
          quarantined++;
        }
        rows.add(new Row(q, t, status, json.writeValueAsString(findings)));
      }
    }
    jdbc.batchUpdate(
        """
        insert into provider_quote (source_id, source_record_id, amount_usd, period, send_country, send_country_name,
                                    receive_country, receive_country_name, provider, provider_type, payment_instrument,
                                    access_point, speed, transparent, interbank_rate, observed_on, send_amount,
                                    send_currency, fee, fx_rate, fx_margin_pct, total_cost_pct, status, findings,
                                    run_id, evidence_sha256)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
        on conflict (source_id, source_record_id, amount_usd) do update
           set period = excluded.period, send_country = excluded.send_country,
               send_country_name = excluded.send_country_name, receive_country = excluded.receive_country,
               receive_country_name = excluded.receive_country_name, provider = excluded.provider,
               provider_type = excluded.provider_type, payment_instrument = excluded.payment_instrument,
               access_point = excluded.access_point, speed = excluded.speed, transparent = excluded.transparent,
               interbank_rate = excluded.interbank_rate, observed_on = excluded.observed_on,
               send_amount = excluded.send_amount, send_currency = excluded.send_currency, fee = excluded.fee,
               fx_rate = excluded.fx_rate, fx_margin_pct = excluded.fx_margin_pct,
               total_cost_pct = excluded.total_cost_pct, status = excluded.status, findings = excluded.findings,
               run_id = excluded.run_id, evidence_sha256 = excluded.evidence_sha256, last_seen_at = now()
        """,
        rows,
        500,
        (ps, row) -> {
          ProviderQuoteObserved q = row.q();
          ProviderQuoteObserved.Tier t = row.t();
          ps.setString(1, q.sourceId());
          ps.setString(2, q.sourceRecordId());
          ps.setInt(3, t.amountUsd());
          ps.setString(4, q.period());
          ps.setString(5, q.sendCountry());
          ps.setString(6, q.sendCountryName());
          ps.setString(7, q.receiveCountry());
          ps.setString(8, q.receiveCountryName());
          ps.setString(9, q.provider());
          ps.setString(10, q.providerType());
          ps.setString(11, q.paymentInstrument());
          ps.setString(12, q.accessPoint());
          ps.setString(13, q.speed());
          ps.setBoolean(14, q.transparent());
          ps.setBigDecimal(15, q.interbankRate());
          if (q.observedOn() == null) {
            ps.setNull(16, Types.DATE);
          } else {
            ps.setDate(16, Date.valueOf(q.observedOn()));
          }
          ps.setBigDecimal(17, t.sendAmount());
          ps.setString(18, t.sendCurrency());
          ps.setBigDecimal(19, t.fee());
          ps.setBigDecimal(20, t.fxRate());
          ps.setBigDecimal(21, t.fxMarginPct());
          ps.setBigDecimal(22, t.totalCostPct());
          ps.setString(23, row.status());
          ps.setString(24, row.findings());
          ps.setObject(25, UUID.fromString(q.runId()));
          ps.setString(26, q.evidenceSha256());
        });
    return quarantined;
  }
}
