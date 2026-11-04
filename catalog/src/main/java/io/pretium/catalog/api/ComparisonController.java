package io.pretium.catalog.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The public comparison API: what each provider charges in a corridor, cheapest first, with the
 * evidence for every number.
 *
 * <p>Quarantined prices are never served by default. A price that failed its checks is worse than
 * no price at all, because the reader cannot tell which one it is.
 */
@RestController
@RequestMapping("/api/v1")
public class ComparisonController {

  static final String ATTRIBUTION =
      "The World Bank, Remittance Prices Worldwide, available at http://remittanceprices.worldbank.org";

  private final JdbcTemplate jdbc;

  public ComparisonController(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Every corridor with prices in its latest surveyed period. */
  @GetMapping("/corridors")
  public List<Map<String, Object>> corridors(@RequestParam(defaultValue = "200") int amountUsd) {
    return jdbc.queryForList(
        """
        with latest as (
          select send_country, receive_country, max(period) as period
            from provider_quote where status <> 'QUARANTINED' group by 1, 2)
        select q.send_country as "send", max(q.send_country_name) as "sendName",
               q.receive_country as "receive", max(q.receive_country_name) as "receiveName",
               l.period as "latestPeriod", count(distinct q.provider) as "providers",
               min(q.total_cost_pct) as "cheapestPct", round(avg(q.total_cost_pct), 2) as "averagePct"
          from provider_quote q join latest l using (send_country, receive_country, period)
         where q.amount_usd = ? and q.status <> 'QUARANTINED'
         group by q.send_country, q.receive_country, l.period
         order by "sendName", "receiveName"
        """,
        amountUsd);
  }

  /** Provider prices in one corridor for one surveyed amount, cheapest first. */
  @GetMapping("/comparisons")
  public Map<String, Object> compare(
      @RequestParam String send,
      @RequestParam String receive,
      @RequestParam(defaultValue = "200") int amountUsd,
      @RequestParam(required = false) String period,
      @RequestParam(defaultValue = "false") boolean includeQuarantined) {
    String chosen = period;
    if (chosen == null) {
      List<String> latest =
          jdbc.queryForList(
              "select max(period) from provider_quote where send_country = ? and receive_country = ? and status <> 'QUARANTINED'",
              String.class,
              send,
              receive);
      chosen = latest.isEmpty() ? null : latest.get(0);
    }
    if (chosen == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "no prices for " + send + " to " + receive);
    }
    List<Map<String, Object>> quotes =
        jdbc.queryForList(
            """
        select provider as "provider", provider_type as "providerType",
               payment_instrument as "paymentInstrument", access_point as "accessPoint", speed as "speed",
               send_amount as "sendAmount", send_currency as "sendCurrency", fee as "fee",
               fx_rate as "fxRate", fx_margin_pct as "fxMarginPct", total_cost_pct as "totalCostPct",
               case when transparent and fx_rate is not null and fee is not null
                    then round((send_amount - fee) * fx_rate, 2) end as "receiveAmount",
               interbank_rate as "interbankRate",
               case when interbank_rate is not null then round(send_amount * interbank_rate, 2) end
                    as "receiveAtInterbank",
               transparent as "transparent", observed_on::text as "observedOn", status as "status",
               findings::text as "findings",
               source_id as "sourceId", source_record_id as "sourceRecordId",
               run_id::text as "runId", evidence_sha256 as "evidenceSha256"
          from provider_quote
         where send_country = ? and receive_country = ? and amount_usd = ? and period = ?
           and (? or status <> 'QUARANTINED')
         order by total_cost_pct, provider
        """,
            send,
            receive,
            amountUsd,
            chosen,
            includeQuarantined);

    Map<String, Object> names =
        jdbc.queryForList(
                """
            select max(send_country_name) as "sendName", max(receive_country_name) as "receiveName"
              from provider_quote where send_country = ? and receive_country = ?
            """,
                send,
                receive)
            .get(0);
    List<String> periods =
        jdbc.queryForList(
            "select distinct period from provider_quote where send_country = ? and receive_country = ? order by period desc",
            String.class,
            send,
            receive);

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("send", send);
    body.put("sendName", names.get("sendName"));
    body.put("receive", receive);
    body.put("receiveName", names.get("receiveName"));
    body.put("amountUsd", amountUsd);
    body.put("period", chosen);
    body.put("periods", periods);
    body.put("quotes", quotes);
    body.put("attribution", ATTRIBUTION);
    return body;
  }

  /** How prices in a corridor have moved, period by period: the competitive-intelligence view. */
  @GetMapping("/corridors/{send}/{receive}/history")
  public List<Map<String, Object>> history(
      @PathVariable String send,
      @PathVariable String receive,
      @RequestParam(defaultValue = "200") int amountUsd) {
    return jdbc.queryForList(
        """
        select period as "period", count(distinct provider) as "providers",
               min(total_cost_pct) as "cheapestPct",
               round(percentile_cont(0.5) within group (order by total_cost_pct)::numeric, 2) as "medianPct",
               round(avg(total_cost_pct), 2) as "averagePct",
               round(avg(fx_margin_pct) filter (where transparent), 2) as "averageMarginPct"
          from provider_quote
         where send_country = ? and receive_country = ? and amount_usd = ? and status <> 'QUARANTINED'
         group by period order by period
        """,
        send,
        receive,
        amountUsd);
  }

  /** One provider's price in a corridor over time. */
  @GetMapping("/corridors/{send}/{receive}/providers/{provider}")
  public List<Map<String, Object>> provider(
      @PathVariable String send,
      @PathVariable String receive,
      @PathVariable String provider,
      @RequestParam(defaultValue = "200") int amountUsd) {
    return jdbc.queryForList(
        """
        select period as "period", payment_instrument as "paymentInstrument", access_point as "accessPoint",
               fee as "fee", fx_margin_pct as "fxMarginPct", total_cost_pct as "totalCostPct", status as "status"
          from provider_quote
         where send_country = ? and receive_country = ? and provider = ? and amount_usd = ?
         order by period, total_cost_pct
        """,
        send,
        receive,
        provider,
        amountUsd);
  }
}
