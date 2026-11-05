package io.pretium.collector.run;

import io.pretium.collector.extract.ObservationSink;
import io.pretium.events.ProviderQuoteObserved;
import io.pretium.events.ReferenceRateObserved;
import io.pretium.events.Topics;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import tools.jackson.databind.json.JsonMapper;

/**
 * Holds what an extractor reads in a temporary file until the extraction is known to have worked.
 *
 * <p>Why: an extractor streams. If it fails on row 80 of 100, the 79 rows before it have already
 * been read, and sending them straight to Kafka would publish a half-read page as if it were
 * complete. Instead they wait here. {@link #replay} sends them on only after the extractor has
 * finished without error; {@link #close} throws them away. A file rather than a list in memory,
 * because the remittance dataset is several hundred thousand records.
 *
 * <p>Each line of the file is one JSON object: {@code {"topic":..., "key":..., "value":...}}.
 */
final class SpoolingObservationSink implements ObservationSink, AutoCloseable {

  /** Where {@link #replay} sends each held record. */
  interface Publisher {
    void publish(String topic, String key, String value);
  }

  private record Held(String topic, String key, String value) {}

  private final JsonMapper json;
  private final Path file;
  private final BufferedWriter out;
  private int count;

  SpoolingObservationSink(JsonMapper json) throws IOException {
    this.json = json;
    this.file = Files.createTempFile("pretium-spool-", ".jsonl");
    this.out = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
  }

  @Override
  public void rate(ReferenceRateObserved rate) {
    hold(Topics.REFERENCE_RATES, rate.key(), json.writeValueAsString(rate));
  }

  @Override
  public void quote(ProviderQuoteObserved quote) {
    hold(Topics.PROVIDER_QUOTES, quote.key(), json.writeValueAsString(quote));
  }

  private void hold(String topic, String key, String value) {
    try {
      out.write(json.writeValueAsString(new Held(topic, key, value)));
      out.newLine();
      count++;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The temporary file holding the records. */
  Path file() {
    return file;
  }

  /** How many records are being held. */
  int held() {
    return count;
  }

  /** Sends every held record, in the order it was read, and returns how many. */
  int replay(Publisher publisher) throws IOException {
    out.flush();
    int sent = 0;
    try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      String line;
      while ((line = in.readLine()) != null) {
        Held h = json.readValue(line, Held.class);
        publisher.publish(h.topic(), h.key(), h.value());
        sent++;
      }
    }
    return sent;
  }

  /** Throws away whatever is held. Safe to call after {@link #replay}. */
  @Override
  public void close() {
    try {
      out.close();
    } catch (IOException ignored) {
      // the file is deleted next anyway
    }
    try {
      Files.deleteIfExists(file);
    } catch (IOException ignored) {
      // a leftover temporary file is harmless
    }
  }
}
