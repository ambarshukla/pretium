package io.pretium.collector.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.pretium.collector.extract.EcbEurofxrefXmlExtractor;
import io.pretium.collector.extract.ExtractionException;
import io.pretium.collector.extract.Extractor;
import io.pretium.collector.extract.HtmlRateTableExtractor;
import io.pretium.collector.extract.ObservationSink;
import io.pretium.collector.fetch.FetchResult;
import io.pretium.collector.fetch.PoliteFetcher;
import io.pretium.collector.raw.RawStore;
import io.pretium.collector.source.SourceSpec;
import io.pretium.events.CollectionRun;
import io.pretium.events.CollectionRun.Status;
import io.pretium.events.Topics;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * One whole collection run, with the network and Kafka replaced: the fetcher "downloads" a real
 * captured ECB response, and Kafka is a stand-in that records what would have been sent. Everything
 * in between (raw store, extractor, spool, run report, metrics) is the real code.
 */
class CollectionServiceTest {

  static final JsonMapper JSON = JsonMapper.builder().build();

  static final Map<String, String> HTML_RECIPE =
      Map.of(
          "base", "EUR",
          "dateSelector", "div.jumbo-box h3",
          "dateFormat", "d MMMM yyyy",
          "rowSelector", "table.forextable tbody tr",
          "currencySelector", "td.currency",
          "rateSelector", "span.rate");

  @TempDir Path rawDir;
  @TempDir Path downloads;

  record Sent(String topic, String key) {}

  final List<Sent> sent = new ArrayList<>();
  final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  PoliteFetcher fetcher;
  KafkaTemplate<String, String> kafka;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    fetcher = mock(PoliteFetcher.class);
    kafka = mock(KafkaTemplate.class);
    when(kafka.send(anyString(), anyString(), anyString()))
        .thenAnswer(
            call -> {
              sent.add(new Sent(call.getArgument(0), call.getArgument(1)));
              return CompletableFuture.completedFuture(null);
            });
  }

  static Path fixture(String name) throws Exception {
    return Path.of(CollectionServiceTest.class.getResource("/fixtures/" + name).toURI());
  }

  /** The fetcher hands over a fresh copy each time, as the real one does (the store moves it). */
  void serve(String fixture) throws Exception {
    Path source = fixture(fixture);
    when(fetcher.fetch(anyString(), any()))
        .thenAnswer(
            call -> {
              Path copy = Files.createTempFile(downloads, "body-", ".tmp");
              Files.copy(source, copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
              return new FetchResult(FetchResult.Outcome.OK, 200, copy, null, null);
            });
  }

  CollectionService service(Extractor... extractors) throws Exception {
    return new CollectionService(
        fetcher,
        new RawStore(rawDir),
        List.of(extractors),
        kafka,
        JSON,
        meters,
        new UrlDiscovery(fetcher, JSON));
  }

  static SourceSpec spec(String id, SourceSpec.Format format, Map<String, String> recipe) {
    return new SourceSpec(
        id,
        id,
        "ECB",
        "test",
        null,
        "https://www.ecb.europa.eu/",
        null,
        format,
        "0 0 0 * * *",
        "UTC",
        recipe == null ? null : new SourceSpec.Recipe(1, recipe));
  }

  long sentTo(String topic) {
    return sent.stream().filter(s -> s.topic().equals(topic)).count();
  }

  @Test
  void aGoodRunPublishesEveryRateThenTheRunAndCountsIt() throws Exception {
    serve("ecb-reference-2026-11-03.html");

    CollectionRun run =
        service(new HtmlRateTableExtractor())
            .collect(
                spec("ecb-reference-html", SourceSpec.Format.HTML_RATE_TABLE, HTML_RECIPE), false);

    assertThat(run.status()).isEqualTo(Status.SUCCEEDED);
    assertThat(run.extracted()).isPositive();
    assertThat(sentTo(Topics.REFERENCE_RATES)).isEqualTo(run.extracted());
    // The run report goes out last, after everything it describes.
    assertThat(sent.get(sent.size() - 1).topic()).isEqualTo(Topics.COLLECTION_RUNS);

    assertThat(
            meters
                .get("pretium.collection.runs")
                .tags("source", "ecb-reference-html", "status", "SUCCEEDED")
                .counter()
                .count())
        .isEqualTo(1.0);
    assertThat(meters.get("pretium.collection.observations").counter().count())
        .isEqualTo(run.extracted());
    assertThat(meters.get("pretium.collection.duration").timer().count()).isEqualTo(1);
  }

  /**
   * The real ECB file is read in full by the real extractor, and then the extraction fails. None of
   * what was read may reach Kafka: only the run report, saying it failed.
   */
  @Test
  void aRunThatFailsAfterReadingPublishesNothingButItsReport() throws Exception {
    serve("ecb-eurofxref-2026-11-03.xml");
    Extractor failsAtTheEnd =
        new Extractor() {
          final EcbEurofxrefXmlExtractor real = new EcbEurofxrefXmlExtractor();

          @Override
          public SourceSpec.Format format() {
            return real.format();
          }

          @Override
          public String extension() {
            return real.extension();
          }

          @Override
          public int extract(SourceSpec spec, Path file, Context context, ObservationSink sink)
              throws ExtractionException {
            int read = real.extract(spec, file, context, sink);
            throw new ExtractionException("failed after reading " + read + " rates");
          }
        };

    CollectionRun run =
        service(failsAtTheEnd)
            .collect(spec("ecb-eurofxref-daily", SourceSpec.Format.ECB_EUROFXREF_XML, null), false);

    assertThat(run.status()).isEqualTo(Status.EXTRACTION_FAILED);
    assertThat(run.message()).startsWith("failed after reading ");
    assertThat(sentTo(Topics.REFERENCE_RATES)).isZero();
    assertThat(sent).containsExactly(new Sent(Topics.COLLECTION_RUNS, "ecb-eurofxref-daily"));
    assertThat(
            meters
                .get("pretium.collection.runs")
                .tags("status", "EXTRACTION_FAILED")
                .counter()
                .count())
        .isEqualTo(1.0);
  }

  /**
   * A recipe with a selector jsoup cannot parse (a typo, or a bad repair proposal) makes jsoup
   * throw an unchecked exception. It must still end as a reported failed run.
   */
  @Test
  void aMalformedSelectorIsAFailedRunNotAMissingOne() throws Exception {
    serve("ecb-reference-2026-11-03.html");
    Map<String, String> malformed = new HashMap<>(HTML_RECIPE);
    malformed.put("rowSelector", "table.forextable tbody tr[");

    CollectionRun run =
        service(new HtmlRateTableExtractor())
            .collect(
                spec("ecb-reference-html", SourceSpec.Format.HTML_RATE_TABLE, malformed), false);

    assertThat(run.status()).isEqualTo(Status.EXTRACTION_FAILED);
    assertThat(run.message()).startsWith("unexpected error: ");
    assertThat(sent).containsExactly(new Sent(Topics.COLLECTION_RUNS, "ecb-reference-html"));
  }
}
