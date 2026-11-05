package io.pretium.collector.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pretium.collector.extract.EcbEurofxrefXmlExtractor;
import io.pretium.collector.extract.Extractor;
import io.pretium.collector.extract.ObservationSink;
import io.pretium.collector.source.SourceSpec;
import io.pretium.events.ProviderQuoteObserved;
import io.pretium.events.ReferenceRateObserved;
import io.pretium.events.Topics;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The spool holds what an extractor reads until the extraction has worked. Real ECB responses
 * (captured 2026-11-03, see docs/PROVENANCE.md).
 */
class SpoolingObservationSinkTest {

  static final Extractor.Context CTX = new Extractor.Context("run-1", "sha-1");
  static final JsonMapper JSON = JsonMapper.builder().build();

  static Path fixture(String name) throws Exception {
    return Path.of(SpoolingObservationSinkTest.class.getResource("/fixtures/" + name).toURI());
  }

  static SourceSpec spec(SourceSpec.Format format, SourceSpec.Recipe recipe) {
    return new SourceSpec(
        "test",
        "test",
        "ECB",
        "test",
        null,
        "https://www.ecb.europa.eu/",
        null,
        format,
        "0 0 0 * * *",
        "UTC",
        recipe);
  }

  @Test
  void aSuccessfulExtractionIsReplayedInFullAndInOrder() throws Exception {
    List<String> keys = new ArrayList<>();
    try (SpoolingObservationSink spool = new SpoolingObservationSink(JSON)) {
      int read =
          new EcbEurofxrefXmlExtractor()
              .extract(
                  spec(SourceSpec.Format.ECB_EUROFXREF_XML, null),
                  fixture("ecb-eurofxref-2026-11-03.xml"),
                  CTX,
                  spool);

      assertThat(spool.held()).isEqualTo(read).isPositive();
      int sent =
          spool.replay(
              (topic, key, value) -> {
                assertThat(topic).isEqualTo(Topics.REFERENCE_RATES);
                assertThat(value).contains("\"rate\"");
                keys.add(key);
              });

      assertThat(sent).isEqualTo(read);
      assertThat(keys).hasSize(read).doesNotHaveDuplicates();
    }
  }

  /**
   * The failure the spool exists for: the first rows of a real page are read fine, then the
   * extractor fails. What was read is held, not sent, and closing the spool removes it.
   */
  @Test
  void anExtractionThatFailsPartwayLeavesNothingBehind() throws Exception {
    SpoolingObservationSink spool = new SpoolingObservationSink(JSON);
    Path file = spool.file();
    ObservationSink failsAfterFive =
        new ObservationSink() {
          @Override
          public void rate(ReferenceRateObserved rate) {
            if (spool.held() == 5) {
              throw new IllegalStateException("simulated failure on row 6");
            }
            spool.rate(rate);
          }

          @Override
          public void quote(ProviderQuoteObserved quote) {}
        };

    assertThatThrownBy(
            () ->
                new EcbEurofxrefXmlExtractor()
                    .extract(
                        spec(SourceSpec.Format.ECB_EUROFXREF_XML, null),
                        fixture("ecb-eurofxref-2026-11-03.xml"),
                        CTX,
                        failsAfterFive))
        .isInstanceOf(IllegalStateException.class);
    assertThat(spool.held()).isEqualTo(5);
    assertThat(file).exists();

    spool.close();

    assertThat(file).doesNotExist();
  }
}
