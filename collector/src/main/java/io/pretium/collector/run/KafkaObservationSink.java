package io.pretium.collector.run;

import io.pretium.collector.extract.ObservationSink;
import io.pretium.events.ProviderQuoteObserved;
import io.pretium.events.ReferenceRateObserved;
import io.pretium.events.Topics;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sends observations to Kafka as JSON, keyed by the observation's natural key so that every version
 * of the same observation lands on the same partition, in order.
 *
 * <p>Sends are asynchronous. {@link #finish()} waits for all of them and reports how many failed,
 * so a run is only reported as a success once every message is actually in Kafka.
 */
final class KafkaObservationSink implements ObservationSink {

  private final KafkaTemplate<String, String> kafka;
  private final JsonMapper json;
  private final AtomicInteger failed = new AtomicInteger();
  private final AtomicReference<String> firstError = new AtomicReference<>();

  KafkaObservationSink(KafkaTemplate<String, String> kafka, JsonMapper json) {
    this.kafka = kafka;
    this.json = json;
  }

  @Override
  public void rate(ReferenceRateObserved rate) {
    send(Topics.REFERENCE_RATES, rate.key(), json.writeValueAsString(rate));
  }

  @Override
  public void quote(ProviderQuoteObserved quote) {
    send(Topics.PROVIDER_QUOTES, quote.key(), json.writeValueAsString(quote));
  }

  private void send(String topic, String key, String value) {
    kafka
        .send(topic, key, value)
        .whenComplete(
            (result, error) -> {
              if (error != null) {
                failed.incrementAndGet();
                firstError.compareAndSet(null, error.toString());
              }
            });
  }

  /** Blocks until every send has completed. Returns a description of any failures, or null. */
  String finish() {
    kafka.flush();
    int count = failed.get();
    return count == 0 ? null : count + " messages failed to send; first: " + firstError.get();
  }
}
