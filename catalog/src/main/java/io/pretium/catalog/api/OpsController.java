package io.pretium.catalog.api;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the people running the platform look at: is every source fresh, what did the checks catch,
 * where do the channels disagree, and what changed after it was first published.
 */
@RestController
@RequestMapping("/api/v1/ops")
public class OpsController {

  private final JdbcTemplate jdbc;
  private final FreshnessProperties freshness;

  public OpsController(JdbcTemplate jdbc, FreshnessProperties freshness) {
    this.jdbc = jdbc;
    this.freshness = freshness;
  }

  /**
   * One row per source: its last run, its last success, and whether it is within its freshness
   * target.
   */
  @GetMapping("/sources")
  public List<Map<String, Object>> sources() {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            """
        select s.source_id as "sourceId",
               last.status as "lastStatus", last.finished_at as "lastRunAt", last.message as "lastMessage",
               last.http_status as "lastHttpStatus", last.recipe_version as "recipeVersion",
               (select max(finished_at) from collection_run c
                 where c.source_id = s.source_id and c.status in ('SUCCEEDED', 'UNCHANGED')) as "lastGoodAt",
               (select count(*) from collection_run c
                 where c.source_id = s.source_id and c.started_at > now() - interval '7 days') as "runs7d",
               (select count(*) from collection_run c
                 where c.source_id = s.source_id and c.started_at > now() - interval '7 days'
                   and c.status not in ('SUCCEEDED', 'UNCHANGED')) as "failures7d"
          from (select distinct source_id from collection_run) s
          cross join lateral (select * from collection_run c where c.source_id = s.source_id
                               order by finished_at desc limit 1) last
         order by s.source_id
        """);
    List<Map<String, Object>> out = new ArrayList<>();
    for (Map<String, Object> row : rows) {
      Map<String, Object> r = new LinkedHashMap<>(row);
      Duration target =
          freshness.freshness().getOrDefault((String) row.get("sourceId"), Duration.ofDays(2));
      r.put("freshnessTargetHours", target.toHours());
      Object lastGood = row.get("lastGoodAt");
      if (lastGood instanceof Timestamp ts) {
        Duration age = Duration.between(ts.toInstant(), Instant.now());
        r.put("ageHours", age.toHours());
        r.put("fresh", age.compareTo(target) <= 0);
      } else {
        r.put("ageHours", null);
        r.put("fresh", false);
      }
      out.add(r);
    }
    return out;
  }

  @GetMapping("/runs")
  public List<Map<String, Object>> runs(@RequestParam(defaultValue = "50") int limit) {
    return jdbc.queryForList(
        """
        select run_id::text as "runId", source_id as "sourceId", status as "status", http_status as "httpStatus",
               started_at as "startedAt", finished_at as "finishedAt", bytes as "bytes", sha256 as "sha256",
               recipe_version as "recipeVersion", extracted as "extracted", message as "message", url as "url"
          from collection_run order by finished_at desc limit ?
        """,
        Math.min(limit, 500));
  }

  /** What the checks found, by verdict and by rule. */
  @GetMapping("/quality")
  public Map<String, Object> quality() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put(
        "byStatus",
        jdbc.queryForList(
            """
        select source_id as "sourceId", status as "status", count(*) as "prices"
          from provider_quote group by 1, 2 order by 1, 2
        """));
    body.put(
        "byRule",
        jdbc.queryForList(
            """
        select f->>'rule' as "rule", f->>'severity' as "severity", count(*) as "prices"
          from provider_quote, jsonb_array_elements(findings) f
         group by 1, 2 order by 3 desc
        """));
    body.put(
        "rates",
        jdbc.queryForList(
            """
        select source_id as "sourceId", count(*) as "rates", min(rate_date)::text as "from", max(rate_date)::text as "to"
          from reference_rate group by 1 order by 1
        """));
    return body;
  }

  /** Prices held back, with the reason. */
  @GetMapping("/quarantine")
  public List<Map<String, Object>> quarantine(@RequestParam(defaultValue = "50") int limit) {
    return jdbc.queryForList(
        """
        select source_id as "sourceId", source_record_id as "sourceRecordId", period as "period",
               send_country_name as "sendName",
               receive_country_name as "receiveName", provider as "provider", amount_usd as "amountUsd",
               fee as "fee", send_amount as "sendAmount", fx_margin_pct as "fxMarginPct",
               total_cost_pct as "totalCostPct", findings::text as "findings", evidence_sha256 as "evidenceSha256"
          from provider_quote where status = 'QUARANTINED'
         order by period desc, send_country, receive_country limit ?
        """,
        Math.min(limit, 500));
  }

  /** Disagreements between rate channels, open ones first. */
  @GetMapping("/breaks")
  public List<Map<String, Object>> breaks(@RequestParam(defaultValue = "100") int limit) {
    return jdbc.queryForList(
        """
        select base as "base", quote as "quote", rate_date::text as "date", channel as "channel",
               reference as "reference", kind as "kind", value as "value", expected as "expected",
               detected_at as "detectedAt", resolved_at as "resolvedAt"
          from reconciliation_break
         order by resolved_at is not null, rate_date desc, quote limit ?
        """,
        Math.min(limit, 500));
  }

  /** Values a source changed after we first stored them. */
  @GetMapping("/revisions")
  public List<Map<String, Object>> revisions(@RequestParam(defaultValue = "50") int limit) {
    return jdbc.queryForList(
        """
        select subject as "subject", subject_key as "key", field as "field", old_value as "oldValue",
               new_value as "newValue", revised_at as "revisedAt"
          from value_revision order by revised_at desc limit ?
        """,
        Math.min(limit, 500));
  }
}
