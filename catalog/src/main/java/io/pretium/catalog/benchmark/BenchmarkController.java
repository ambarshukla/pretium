package io.pretium.catalog.benchmark;

import io.pretium.catalog.benchmark.RateBenchmark.Payout;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/ops/benchmark?period=2025_3Q}: the survey's interbank rates checked against
 * the ECB's, one survey period at a time (the latest by default). See {@link RateBenchmark}.
 */
@RestController
@RequestMapping("/api/v1/ops")
public class BenchmarkController {

  /**
   * One row per quote (the survey records a quote once per amount; the interbank rate is the same
   * for both, so the lower amount is taken). The receiving currency comes from {@code
   * country_currency}; the ECB rate is the latest published on or up to four days before the day
   * the quote was observed, which covers weekends and holidays.
   *
   * <p>{@code send_currency::char(3)}: the survey's currency column is {@code text} and the rates'
   * is {@code char(3)}. Without the cast Postgres converts the indexed column instead, cannot use
   * the index, and the query took 4 seconds instead of milliseconds.
   */
  private static final String QUOTES =
      """
      with quote as (
        select distinct on (q.source_record_id)
               q.source_record_id, q.send_country, q.receive_country, q.provider, q.observed_on,
               q.send_currency, c.currency as receive_currency, q.interbank_rate
          from provider_quote q
          join country_currency c
            on c.country = q.receive_country
           and q.observed_on >= coalesce(c.valid_from, date '-infinity')
           and q.observed_on <  coalesce(c.valid_until, date 'infinity')
         where q.period = ? and q.interbank_rate > 0 and q.observed_on is not null
           and q.send_currency <> c.currency
         order by q.source_record_id, q.amount_usd
      )
      select quote.*,
             case when send_currency = 'EUR' then 1 else
               (select rate from reference_rate r
                 where r.source_id = ? and r.base = 'EUR' and r.quote = send_currency::char(3)
                   and r.rate_date <= observed_on and r.rate_date > observed_on - 5
                 order by r.rate_date desc limit 1) end as eur_send,
             case when receive_currency = 'EUR' then 1 else
               (select rate from reference_rate r
                 where r.source_id = ? and r.base = 'EUR' and r.quote = receive_currency
                   and r.rate_date <= observed_on and r.rate_date > observed_on - 5
                 order by r.rate_date desc limit 1) end as eur_receive,
             (select rate from reference_rate r
               where r.source_id = ? and r.base = 'EUR' and r.quote = 'USD'
                 and r.rate_date <= observed_on and r.rate_date > observed_on - 5
               order by r.rate_date desc limit 1) as eur_usd
        from quote
      """;

  private final JdbcTemplate jdbc;
  private final String reference;

  public BenchmarkController(
      JdbcTemplate jdbc, @Value("${pretium.reconcile.reference:ecb-sdmx-exr}") String reference) {
    this.jdbc = jdbc;
    this.reference = reference;
  }

  /** A quote whose rate is wrong way round or fits nothing: for a person to look at. */
  public record Flagged(
      Payout payout,
      String recordId,
      String corridor,
      String provider,
      LocalDate observedOn,
      String pair,
      BigDecimal surveyRate,
      BigDecimal ecbRate,
      BigDecimal deviationPct) {}

  /** Agreement for one currency pair, over the quotes that pay out in the local currency. */
  public record Pair(
      String pair, int quotes, BigDecimal medianAbsDeviationPct, BigDecimal maxAbsDeviationPct) {}

  public record Report(
      String period,
      String reference,
      BigDecimal tolerancePct,
      int quotes,
      Map<Payout, Integer> byPayout,
      BigDecimal localMedianAbsDeviationPct,
      BigDecimal localWithinHalfPercentPct,
      List<Pair> pairs,
      List<Flagged> flagged) {}

  @GetMapping("/benchmark")
  public Report benchmark(@RequestParam(required = false) String period) {
    String p =
        period != null
            ? period
            : jdbc.queryForObject(
                "select max(period) from provider_quote where source_id = 'wb-rpw'", String.class);
    List<Map<String, Object>> rows = jdbc.queryForList(QUOTES, p, reference, reference, reference);

    Map<Payout, Integer> byPayout = new EnumMap<>(Payout.class);
    for (Payout payout : Payout.values()) {
      byPayout.put(payout, 0);
    }
    Map<String, List<BigDecimal>> localByPair = new TreeMap<>();
    List<BigDecimal> local = new ArrayList<>();
    List<Flagged> flagged = new ArrayList<>();
    for (Map<String, Object> row : rows) {
      RateBenchmark.Quote q =
          new RateBenchmark.Quote(
              (String) row.get("send_currency"),
              ((String) row.get("receive_currency")).strip(),
              (BigDecimal) row.get("interbank_rate"),
              decimal(row.get("eur_send")),
              decimal(row.get("eur_receive")),
              decimal(row.get("eur_usd")));
      Payout payout = RateBenchmark.classify(q);
      byPayout.merge(payout, 1, Integer::sum);
      BigDecimal cross = RateBenchmark.crossRate(q.eurSend(), q.eurReceive());
      String pair = q.sendCurrency() + "/" + q.receiveCurrency();
      if (payout == Payout.LOCAL) {
        BigDecimal dev = RateBenchmark.deviationPct(q.surveyRate(), cross).abs();
        local.add(dev);
        localByPair.computeIfAbsent(pair, k -> new ArrayList<>()).add(dev);
      } else if (payout == Payout.UNEXPLAINED || payout == Payout.INVERTED) {
        flagged.add(
            new Flagged(
                payout,
                (String) row.get("source_record_id"),
                row.get("send_country") + " to " + row.get("receive_country"),
                (String) row.get("provider"),
                ((java.sql.Date) row.get("observed_on")).toLocalDate(),
                pair,
                q.surveyRate(),
                cross.setScale(6, RoundingMode.HALF_UP),
                RateBenchmark.deviationPct(q.surveyRate(), cross)
                    .setScale(2, RoundingMode.HALF_UP)));
      }
    }

    List<Pair> pairs = new ArrayList<>();
    localByPair.forEach(
        (pair, devs) ->
            pairs.add(new Pair(pair, devs.size(), round(median(devs)), round(max(devs)))));
    pairs.sort(Comparator.comparing(Pair::medianAbsDeviationPct).reversed());
    flagged.sort(Comparator.comparing((Flagged f) -> f.deviationPct().abs()).reversed());

    long withinHalf = local.stream().filter(d -> d.compareTo(new BigDecimal("0.5")) <= 0).count();
    return new Report(
        p,
        reference,
        RateBenchmark.TOLERANCE.movePointRight(2),
        rows.size(),
        byPayout,
        local.isEmpty() ? null : round(median(local)),
        local.isEmpty()
            ? null
            : BigDecimal.valueOf(withinHalf * 100.0 / local.size())
                .setScale(1, RoundingMode.HALF_UP),
        pairs,
        flagged);
  }

  private static BigDecimal decimal(Object value) {
    return value == null ? null : new BigDecimal(value.toString());
  }

  static BigDecimal median(List<BigDecimal> values) {
    List<BigDecimal> sorted = values.stream().sorted().toList();
    int n = sorted.size();
    return n % 2 == 1
        ? sorted.get(n / 2)
        : sorted.get(n / 2 - 1).add(sorted.get(n / 2)).divide(BigDecimal.TWO);
  }

  private static BigDecimal max(List<BigDecimal> values) {
    return values.stream().max(Comparator.naturalOrder()).orElseThrow();
  }

  private static BigDecimal round(BigDecimal value) {
    return value.setScale(3, RoundingMode.HALF_UP);
  }
}
