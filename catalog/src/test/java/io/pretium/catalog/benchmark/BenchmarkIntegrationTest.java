package io.pretium.catalog.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import io.pretium.catalog.benchmark.RateBenchmark.Payout;
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
 * The benchmark end to end: two real survey quotes from Q3 2025 and the ECB's real rates for the
 * days they were observed go in through Kafka, and the report comes out of the real query over the
 * real country table (migration V2).
 */
@SpringBootTest
@Testcontainers
class BenchmarkIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  @Container @ServiceConnection
  static final KafkaContainer kafka = new KafkaContainer("apache/kafka:4.1.0");

  @Autowired KafkaTemplate<String, String> producer;
  @Autowired JsonMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired BenchmarkController benchmark;

  static final String RUN = UUID.randomUUID().toString();

  /** A real survey record, both amounts, exactly as the source has it. */
  static ProviderQuoteObserved surveyed(
      String id,
      String receive,
      String receiveName,
      String instrument,
      String interbank,
      LocalDate observed,
      String fee,
      String fxRate,
      String margin,
      String total200,
      String total500) {
    return new ProviderQuoteObserved(
        RUN,
        "wb-rpw",
        id,
        "2025_3Q",
        "GBR",
        "United Kingdom",
        receive,
        receiveName,
        "MoneyGram",
        "Money Transfer Operator",
        instrument,
        "Internet",
        "Less than one hour",
        true,
        new BigDecimal(interbank),
        observed,
        List.of(
            new Tier(
                200,
                new BigDecimal("120"),
                "GBP",
                new BigDecimal(fee),
                new BigDecimal(fxRate),
                new BigDecimal(margin),
                new BigDecimal(total200)),
            new Tier(
                500,
                new BigDecimal("300"),
                "GBP",
                new BigDecimal(fee),
                new BigDecimal(fxRate),
                new BigDecimal(margin),
                new BigDecimal(total500))),
        "sha-evidence");
  }

  ReferenceRateObserved ecb(String currency, LocalDate day, String rate) {
    return new ReferenceRateObserved(
        RUN, "ecb-sdmx-exr", "EUR", currency, day, new BigDecimal(rate), "sha-ecb");
  }

  @Test
  void theSurveysRatesAreCheckedAgainstTheEcbs() throws Exception {
    LocalDate aug20 = LocalDate.of(2025, 8, 20);
    LocalDate aug29 = LocalDate.of(2025, 8, 29);
    // Record 1212252176: United Kingdom to India, paid into a bank account in rupees.
    send(
        Topics.PROVIDER_QUOTES,
        surveyed(
            "1212252176",
            "IND",
            "India",
            "Bank account transfer",
            "117.5667080000",
            aug20,
            "1.99",
            "117.2760000000",
            "0.25",
            "1.91",
            "0.91"));
    // Record 1212252861: United Kingdom to Romania, paid by card; the rate is pounds to euros.
    send(
        Topics.PROVIDER_QUOTES,
        surveyed(
            "1212252861",
            "ROU",
            "Romania",
            "Credit card",
            "1.1545792555",
            aug29,
            "4.99",
            "1.1424000000",
            "1.05",
            "5.21",
            "2.71"));
    // The ECB's reference rates for those two days.
    for (ReferenceRateObserved r :
        List.of(
            ecb("GBP", aug20, "0.8645"),
            ecb("INR", aug20, "101.4305"),
            ecb("USD", aug20, "1.1651"),
            ecb("GBP", aug29, "0.8668"),
            ecb("RON", aug29, "5.0728"),
            ecb("USD", aug29, "1.1658"))) {
      send(Topics.REFERENCE_RATES, r);
    }
    await(() -> jdbc.queryForObject("select count(*) from provider_quote", Integer.class) == 4);
    await(() -> jdbc.queryForObject("select count(*) from reference_rate", Integer.class) == 6);

    BenchmarkController.Report report = benchmark.benchmark(null);

    assertThat(report.period()).isEqualTo("2025_3Q");
    assertThat(report.quotes()).isEqualTo(2); // one per quote, not one per amount
    assertThat(report.byPayout())
        .containsEntry(Payout.LOCAL, 1)
        .containsEntry(Payout.EUR, 1)
        .containsEntry(Payout.UNEXPLAINED, 0);
    assertThat(report.flagged()).isEmpty();
    assertThat(report.pairs())
        .singleElement()
        .satisfies(
            p -> {
              assertThat(p.pair()).isEqualTo("GBP/INR");
              assertThat(p.medianAbsDeviationPct()).isEqualByComparingTo("0.203");
            });
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
