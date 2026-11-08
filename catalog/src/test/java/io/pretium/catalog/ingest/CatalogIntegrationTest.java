package io.pretium.catalog.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.micrometer.core.instrument.MeterRegistry;
import io.pretium.catalog.api.RatesController;
import io.pretium.events.ProviderQuoteObserved;
import io.pretium.events.ProviderQuoteObserved.Tier;
import io.pretium.events.ReferenceRateObserved;
import io.pretium.events.Topics;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * The catalog end to end with a real Kafka broker and a real Postgres (Testcontainers): real World
 * Bank rows and real ECB rates go in as Kafka messages; the verdicts, the evidence and the
 * reconciliation come out in the database.
 */
@SpringBootTest(
    properties = {"pretium.reconcile.quiet-period=1s", "pretium.reconcile.check-every=500ms"})
@Testcontainers
class CatalogIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  @Container @ServiceConnection
  static final KafkaContainer kafka = new KafkaContainer("apache/kafka:4.1.0");

  @Autowired KafkaTemplate<String, String> producer;
  @Autowired JsonMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired MeterRegistry meters;
  @Autowired RatesController rates;

  static final String RUN = UUID.randomUUID().toString();

  static ProviderQuoteObserved quote(
      String id, String send, String receive, String provider, String interbank, Tier tier) {
    return new ProviderQuoteObserved(
        RUN,
        "wb-rpw",
        id,
        "2025_3Q",
        send,
        send,
        receive,
        receive,
        provider,
        "Money Transfer Operator",
        "Cash",
        "Agent",
        "Same day",
        true,
        new BigDecimal(interbank),
        LocalDate.of(2025, 7, 15),
        List.of(tier),
        "sha-evidence");
  }

  @Test
  void pricesAreJudgedStoredWithEvidenceAndChannelsReconciled() throws Exception {
    // Real: MoneyGram, Angola to Namibia, Q2 2016 (passes both identities).
    send(
        Topics.PROVIDER_QUOTES,
        quote(
            "620160001",
            "AGO",
            "NAM",
            "MoneyGram",
            "0.0908",
            new Tier(
                200,
                new BigDecimal("33000"),
                "AOA",
                new BigDecimal("2900"),
                new BigDecimal("0.084"),
                new BigDecimal("7.49"),
                new BigDecimal("16.28"))));
    // Real: Turkiye to Bulgaria, Q3 2025: a fee larger than the amount sent (118.6% total).
    send(
        Topics.PROVIDER_QUOTES,
        quote(
            "TR-BG",
            "TUR",
            "BGR",
            "Turkey IS Bank via Western Union",
            "0.0243205448",
            new Tier(
                200,
                new BigDecimal("570"),
                "TRY",
                new BigDecimal("661.47"),
                new BigDecimal("0.0237"),
                new BigDecimal("2.55"),
                new BigDecimal("118.6"))));
    // Real: the ECB's USD and GBP reference rates for 3 November 2026, from two channels.
    for (String channel : List.of("ecb-sdmx-exr", "ecb-eurofxref-daily")) {
      send(Topics.REFERENCE_RATES, rate(channel, "USD", "1.1179"));
      send(Topics.REFERENCE_RATES, rate(channel, "GBP", "0.83358"));
      send(Topics.REFERENCE_RATES, rate(channel, "CZK", "24.343"));
    }
    // Real: the Czech National Bank's EUR/CZK fixing for the same day, from its two channels.
    // The CNB fixes at 14:30, the ECB at about 14:10 CET: the same pair, a different number, and
    // no error. Each publisher's channels are reconciled only with each other.
    for (String channel : List.of("cnb-fixing-txt", "cnb-fixing-html")) {
      send(Topics.REFERENCE_RATES, rate(channel, "CZK", "24.340"));
    }

    await(() -> jdbc.queryForObject("select count(*) from provider_quote", Integer.class) == 2);
    assertThat(
            jdbc.queryForObject(
                "select status from provider_quote where source_record_id = '620160001'",
                String.class))
        .isEqualTo("PUBLISHED");
    assertThat(
            jdbc.queryForObject(
                "select status from provider_quote where source_record_id = 'TR-BG'", String.class))
        .isEqualTo("QUARANTINED");
    assertThat(
            jdbc.queryForObject(
                "select evidence_sha256 from provider_quote where source_record_id = '620160001'",
                String.class))
        .isEqualTo("sha-evidence");

    await(() -> jdbc.queryForObject("select count(*) from reference_rate", Integer.class) == 8);
    Thread.sleep(2_500); // let the settled reconciliation run
    assertThat(jdbc.queryForObject("select count(*) from reconciliation_break", Integer.class))
        .isZero();
    // The CNB's two channels agree on EUR/CZK. The ECB group counts no pair as agreeing, because
    // its third channel (the web page) sent nothing here: a missing channel is not agreement.
    assertThat(rates.channels())
        .extracting(RatesController.Group::reference, RatesController.Group::agreeing)
        .containsExactlyInAnyOrder(tuple("ecb-sdmx-exr", 0), tuple("cnb-fixing-txt", 1));

    // Delivered twice (Kafka is at-least-once): still one row, no revision.
    send(Topics.REFERENCE_RATES, rate("ecb-eurofxref-daily", "USD", "1.1179"));
    Thread.sleep(1_500);
    assertThat(jdbc.queryForObject("select count(*) from reference_rate", Integer.class))
        .isEqualTo(8);
    assertThat(jdbc.queryForObject("select count(*) from value_revision", Integer.class)).isZero();

    // The metrics a dashboard reads: two quotes stored, one of them quarantined; the eight rates
    // plus the redelivered one counted as received.
    assertThat(meters.get("pretium.catalog.quotes.stored").counter().count()).isEqualTo(2.0);
    assertThat(meters.get("pretium.catalog.prices.stored").counter().count()).isEqualTo(2.0);
    assertThat(meters.get("pretium.catalog.prices.quarantined").counter().count()).isEqualTo(1.0);
    assertThat(meters.get("pretium.catalog.rates.stored").counter().count()).isEqualTo(9.0);
  }

  ReferenceRateObserved rate(String channel, String currency, String value) {
    return new ReferenceRateObserved(
        RUN,
        channel,
        "EUR",
        currency,
        LocalDate.of(2026, 11, 3),
        new BigDecimal(value),
        "sha-" + channel);
  }

  void send(String topic, Object message) throws Exception {
    producer.send(topic, json.writeValueAsString(message)).get();
  }

  static void await(Supplier<Boolean> condition) throws InterruptedException {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
    while (!condition.get()) {
      if (Instant.now().isAfter(deadline)) {
        throw new AssertionError("condition not met within 60 seconds");
      }
      Thread.sleep(250);
    }
  }
}
