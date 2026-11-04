package io.pretium.catalog.api;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
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

  public RatesController(
      JdbcTemplate jdbc, @Value("${pretium.reconcile.reference:ecb-sdmx-exr}") String reference) {
    this.jdbc = jdbc;
    this.reference = reference;
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
