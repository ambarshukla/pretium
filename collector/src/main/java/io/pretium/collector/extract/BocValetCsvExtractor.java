package io.pretium.collector.extract;

import io.pretium.collector.source.SourceSpec;
import io.pretium.events.ReferenceRateObserved;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Reads the Bank of Canada's daily exchange rates from its Valet API, as CSV.
 *
 * <p>A Valet CSV is several blocks one after another: terms, the group's description, the list of
 * series, then a block that starts with a line {@code "OBSERVATIONS"}, a header {@code
 * "date","FXAUDCAD",...} and one line per day. Each series is named for the pair it carries: {@code
 * FXAUDCAD} is the Australian dollar priced in Canadian dollars, so base AUD, quote CAD. An empty
 * cell means the bank published no rate for that currency that day (it began publishing some in
 * 2026 and stopped others), so it is skipped rather than read as zero.
 *
 * <p>Rates are "Canadian dollars per one unit", already per unit: nothing to divide.
 */
@Component
public class BocValetCsvExtractor implements Extractor {

  public static final String HOME = "CAD";

  private static final Pattern SERIES = Pattern.compile("FX([A-Z]{3})" + HOME);

  @Override
  public SourceSpec.Format format() {
    return SourceSpec.Format.BOC_VALET_CSV;
  }

  @Override
  public String extension() {
    return "csv";
  }

  @Override
  public int extract(SourceSpec spec, Path file, Context context, ObservationSink sink)
      throws ExtractionException {
    List<String> lines;
    try {
      lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new ExtractionException("could not read file: " + e.getMessage(), e);
    }
    int marker = -1;
    for (int i = 0; i < lines.size(); i++) {
      if (strip(lines.get(i)).equals("\"OBSERVATIONS\"")) {
        marker = i;
        break;
      }
    }
    if (marker < 0 || marker + 1 >= lines.size()) {
      throw new ExtractionException("no OBSERVATIONS block in the file");
    }
    List<String> header = cells(lines.get(marker + 1));
    if (header.isEmpty() || !header.getFirst().equals("date")) {
      throw new ExtractionException(
          "line " + (marker + 2) + ": expected a header starting with \"date\"");
    }
    List<String> codes = new ArrayList<>();
    for (int c = 1; c < header.size(); c++) {
      Matcher m = SERIES.matcher(header.get(c));
      if (!m.matches()) {
        throw new ExtractionException(
            "line "
                + (marker + 2)
                + ": series '"
                + header.get(c)
                + "' is not FX<code>"
                + HOME
                + ", so its currency pair is unknown");
      }
      codes.add(m.group(1));
    }
    int count = 0;
    for (int i = marker + 2; i < lines.size(); i++) {
      if (lines.get(i).isBlank()) {
        continue;
      }
      List<String> row = cells(lines.get(i));
      if (row.size() != header.size()) {
        throw new ExtractionException(
            "line " + (i + 1) + ": expected " + header.size() + " fields, found " + row.size());
      }
      LocalDate date;
      try {
        date = LocalDate.parse(row.getFirst());
      } catch (DateTimeParseException e) {
        throw new ExtractionException(
            "line " + (i + 1) + ": '" + row.getFirst() + "' is not a date", e);
      }
      for (int c = 0; c < codes.size(); c++) {
        String value = row.get(c + 1);
        if (value.isEmpty()) {
          continue;
        }
        BigDecimal rate = Numbers.decimal(value);
        sink.rate(
            new ReferenceRateObserved(
                context.runId(),
                spec.id(),
                codes.get(c),
                HOME,
                date,
                rate,
                context.evidenceSha256()));
        count++;
      }
    }
    if (count == 0) {
      throw new ExtractionException("no rates in the file");
    }
    return count;
  }

  private static String strip(String line) {
    return line.strip().replace(String.valueOf((char) 0xFEFF), "");
  }

  /** Splits a line of quoted, comma-separated cells. Observation lines hold no commas in cells. */
  private static List<String> cells(String line) {
    List<String> out = new ArrayList<>();
    for (String cell : strip(line).split(",", -1)) {
      String c = cell.strip();
      if (c.length() >= 2 && c.startsWith("\"") && c.endsWith("\"")) {
        c = c.substring(1, c.length() - 1);
      }
      out.add(c);
    }
    return out;
  }
}
