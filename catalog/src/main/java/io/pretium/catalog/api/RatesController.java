package io.pretium.catalog.api;

import io.pretium.catalog.reconcile.ReconcileProperties;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Mid-market reference rates, from the reference channel. */
@RestController
@RequestMapping("/api/v1/rates")
public class RatesController {

  private final JdbcTemplate jdbc;
  private final String reference;
  private final Map<String, List<String>> groups;

  public RatesController(JdbcTemplate jdbc, ReconcileProperties reconcile) {
    this.jdbc = jdbc;
    this.reference = reconcile.reference();
    this.groups = reconcile.groups();
  }

  /**
   * One publisher's channels side by side.
   *
   * @param reference the publisher's reference channel
   * @param channels the channels checked against it
   * @param date the latest day the reference has, or null if it has none yet
   * @param rows one per currency pair: the rate in each channel (absent if the channel lacks it)
   */
  public record Group(
      String reference, List<String> channels, String date, List<Row> rows, int agreeing) {}

  /** One currency pair across a publisher's channels. */
  public record Row(String base, String quote, Map<String, BigDecimal> rates, boolean agree) {}

  /**
   * Every publisher's latest day, each channel side by side: the same check as {@link #latest}, for
   * any publisher configured under {@code pretium.reconcile.groups}.
   */
  @GetMapping("/channels")
  public List<Group> channels() {
    List<Group> out = new ArrayList<>();
    groups.forEach(
        (ref, channels) -> {
          List<String> all = new ArrayList<>(channels);
          all.addFirst(ref);
          String day =
              jdbc.queryForObject(
                  "select max(rate_date)::text from reference_rate where source_id = ?",
                  String.class,
                  ref);
          Map<String, Map<String, BigDecimal>> byPair = new TreeMap<>();
          if (day != null) {
            jdbc.query(
                """
                select source_id, base, quote, rate from reference_rate
                 where rate_date = ?::date and source_id = any (string_to_array(?, ','))
                """,
                rs -> {
                  byPair
                      .computeIfAbsent(
                          rs.getString("base") + "/" + rs.getString("quote"),
                          k -> new LinkedHashMap<>())
                      .put(rs.getString("source_id"), rs.getBigDecimal("rate"));
                },
                day,
                String.join(",", all));
          }
          List<Row> rows = new ArrayList<>();
          byPair.forEach(
              (pair, rates) -> {
                BigDecimal first = rates.get(ref);
                boolean agree =
                    first != null
                        && all.stream()
                            .allMatch(
                                c -> rates.get(c) != null && rates.get(c).compareTo(first) == 0);
                rows.add(new Row(pair.substring(0, 3), pair.substring(4), rates, agree));
              });
          out.add(
              new Group(
                  ref,
                  List.copyOf(channels),
                  day,
                  rows,
                  (int) rows.stream().filter(Row::agree).count()));
        });
    return out;
  }

  /** The latest day's rates against the euro, as each channel has it, side by side. */
  @GetMapping("/latest")
  public List<Map<String, Object>> latest() {
    return jdbc.queryForList(
        """
        with day as (select max(rate_date) as d from reference_rate where source_id = ?)
        select r.quote as "currency", r.rate_date::text as "date",
               max(r.rate) filter (where r.source_id = 'ecb-sdmx-exr') as "sdmx",
               max(r.rate) filter (where r.source_id = 'ecb-eurofxref-daily') as "xml",
               max(r.rate) filter (where r.source_id = 'ecb-reference-html') as "html"
          from reference_rate r, day
         where r.base = 'EUR' and r.rate_date = day.d
         group by r.quote, r.rate_date order by r.quote
        """,
        reference);
  }

  /**
   * A cross rate: units of {@code to} per one {@code from}, derived through the euro. Either side
   * may itself be EUR.
   */
  @GetMapping("/cross")
  public List<Map<String, Object>> cross(
      @RequestParam String from,
      @RequestParam String to,
      @RequestParam(defaultValue = "90") int days) {
    return jdbc.queryForList(
        """
        with f as (select rate_date, rate from reference_rate where source_id = ? and base = 'EUR' and quote = ?
                   union all
                   select distinct rate_date, 1 from reference_rate where source_id = ? and 'EUR' = ?),
             t as (select rate_date, rate from reference_rate where source_id = ? and base = 'EUR' and quote = ?
                   union all
                   select distinct rate_date, 1 from reference_rate where source_id = ? and 'EUR' = ?)
        select f.rate_date::text as "date", round(t.rate / f.rate, 8) as "rate"
          from f join t using (rate_date)
         where f.rate_date >= current_date - ?
         order by f.rate_date
        """,
        reference,
        from,
        reference,
        from,
        reference,
        to,
        reference,
        to,
        days);
  }
}
