package io.pretium.catalog.reconcile;

import java.sql.Date;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Compares channels that publish the same rates and records every disagreement.
 *
 * <p>Each publisher has one reference channel (for the ECB, its SDMX API, the statistical system of
 * record; for the CNB, its text file). Each of the publisher's other channels is compared with it,
 * date by date, and never with another publisher's (see {@link ReconcileProperties}):
 *
 * <ul>
 *   <li>{@code VALUE_MISMATCH}: both have the rate and the numbers differ, by any amount. Reference
 *       rates are published to fixed decimals, so there is no rounding tolerance to allow.
 *   <li>{@code MISSING}: the channel published rates for that day but not this one.
 * </ul>
 *
 * <p>A break closes by itself when a later observation makes the channels agree, and stays in the
 * table with both timestamps, so the history of disagreements is never lost.
 */
@Component
public class Reconciler {

  private final JdbcTemplate jdbc;
  private final Map<String, List<String>> groups;

  public Reconciler(JdbcTemplate jdbc, ReconcileProperties properties) {
    this.jdbc = jdbc;
    this.groups = properties.groups();
  }

  @Transactional
  public void reconcile(Collection<LocalDate> dates) {
    for (LocalDate day : dates) {
      Date date = Date.valueOf(day);
      groups.forEach(
          (reference, channels) -> channels.forEach(channel -> compare(reference, channel, date)));
      jdbc.update(
          """
          update reconciliation_break b set resolved_at = now()
           where b.resolved_at is null and b.rate_date = ?
             and exists (select 1 from reference_rate c
                           join reference_rate r
                             on r.base = c.base and r.quote = c.quote and r.rate_date = c.rate_date
                            and r.source_id = b.reference
                          where c.source_id = b.channel and c.base = b.base and c.quote = b.quote
                            and c.rate_date = b.rate_date and c.rate = r.rate)
          """,
          date);
    }
  }

  /** One channel against its reference, for one day. */
  private void compare(String reference, String channel, Date date) {
    jdbc.update(
        """
        insert into reconciliation_break (base, quote, rate_date, channel, reference, kind, value, expected)
        select c.base, c.quote, c.rate_date, c.source_id, r.source_id, 'VALUE_MISMATCH', c.rate, r.rate
          from reference_rate c
          join reference_rate r
            on r.base = c.base and r.quote = c.quote and r.rate_date = c.rate_date and r.source_id = ?
         where c.source_id = ? and c.rate_date = ? and c.rate <> r.rate
        on conflict (base, quote, rate_date, channel, reference, kind) do update
           set value = excluded.value, expected = excluded.expected, resolved_at = null
        """,
        reference,
        channel,
        date);
    // MISSING only on a day the channel published at all: a channel that has not run yet today
    // is late, which the freshness check reports, not a set of missing rates.
    jdbc.update(
        """
        insert into reconciliation_break (base, quote, rate_date, channel, reference, kind, value, expected)
        select r.base, r.quote, r.rate_date, ?, r.source_id, 'MISSING', null, r.rate
          from reference_rate r
         where r.source_id = ? and r.rate_date = ?
           and exists (select 1 from reference_rate a where a.source_id = ? and a.rate_date = ?)
           and not exists (select 1 from reference_rate c
                            where c.source_id = ? and c.base = r.base
                              and c.quote = r.quote and c.rate_date = r.rate_date)
        on conflict (base, quote, rate_date, channel, reference, kind) do update
           set expected = excluded.expected, resolved_at = null
        """,
        channel,
        reference,
        date,
        channel,
        date,
        channel);
  }
}
