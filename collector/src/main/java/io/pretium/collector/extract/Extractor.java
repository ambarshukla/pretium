package io.pretium.collector.extract;

import io.pretium.collector.source.SourceSpec;
import java.nio.file.Path;

/** Reads one response format. One implementation per {@link SourceSpec.Format}. */
public interface Extractor {

  SourceSpec.Format format();

  /** The file extension the raw body is stored under. */
  String extension();

  /**
   * Reads the file and sends every observation to the sink.
   *
   * @return how many observations were sent
   * @throws ExtractionException if the file does not look the way the recipe expects
   */
  int extract(SourceSpec spec, Path file, Context context, ObservationSink sink)
      throws ExtractionException;

  /** Identifiers stamped onto every observation from one run. */
  record Context(String runId, String evidenceSha256) {}
}
