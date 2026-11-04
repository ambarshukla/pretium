package io.pretium.collector.extract;

import io.pretium.collector.source.SourceSpec;
import io.pretium.events.ReferenceRateObserved;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Reads the ECB Data Portal's SDMX CSV for the EXR (exchange rates) dataflow.
 *
 * <p>Columns are located by header name, not position. If the ECB adds a column, or reorders them,
 * this keeps working; if a column we need disappears, the error names it.
 */
@Component
public class EcbSdmxCsvExtractor implements Extractor {

  private static final char BYTE_ORDER_MARK = (char) 0xFEFF;

  private static final List<String> REQUIRED =
      List.of("CURRENCY", "CURRENCY_DENOM", "TIME_PERIOD", "OBS_VALUE");

  @Override
  public SourceSpec.Format format() {
    return SourceSpec.Format.ECB_SDMX_CSV;
  }

  @Override
  public String extension() {
    return "csv";
  }

  @Override
  public int extract(SourceSpec spec, Path file, Context context, ObservationSink sink)
      throws ExtractionException {
    try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      String headerLine = reader.readLine();
      if (headerLine == null) {
        throw new ExtractionException("empty file");
      }
      // The ECB's CSV starts with a byte-order mark; left in, it becomes part of the first name.
      List<String> header =
          Arrays.asList(headerLine.replace(String.valueOf(BYTE_ORDER_MARK), "").split(",", -1));
      int[] idx = new int[REQUIRED.size()];
      for (int i = 0; i < REQUIRED.size(); i++) {
        idx[i] = header.indexOf(REQUIRED.get(i));
        if (idx[i] < 0) {
          throw new ExtractionException(
              "column " + REQUIRED.get(i) + " not found in header " + header);
        }
      }
      int count = 0;
      String line;
      int lineNo = 1;
      while ((line = reader.readLine()) != null) {
        lineNo++;
        if (line.isBlank()) {
          continue;
        }
        // Safe to split on commas for these four columns: codes, a date and a number.
        String[] cells = line.split(",", -1);
        String value = cells[idx[3]];
        if (value.isEmpty() || value.equals("NaN")) {
          continue; // a holiday or a suspended currency: no observation, not an error
        }
        LocalDate date;
        try {
          date = LocalDate.parse(cells[idx[2]]);
        } catch (DateTimeParseException e) {
          throw new ExtractionException(
              "line " + lineNo + ": unparseable TIME_PERIOD '" + cells[idx[2]] + "'", e);
        }
        sink.rate(
            new ReferenceRateObserved(
                context.runId(),
                spec.id(),
                cells[idx[1]],
                cells[idx[0]],
                date,
                Numbers.decimal(value),
                context.evidenceSha256()));
        count++;
      }
      if (count == 0) {
        throw new ExtractionException("header found but no observations");
      }
      return count;
    } catch (IOException e) {
      throw new ExtractionException("could not read file: " + e.getMessage(), e);
    }
  }
}
