package io.pretium.collector.extract;

import io.pretium.collector.source.SourceSpec;
import io.pretium.events.ReferenceRateObserved;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Reads the Czech National Bank's exchange rate fixing in its plain-text format. The bank publishes
 * it two ways, and both are read here:
 *
 * <ul>
 *   <li>The daily file: a first line with the date ({@code 06 Nov 2026 #214}), a header {@code
 *       Country|Currency|Amount|Code|Rate}, then one line per currency.
 *   <li>The yearly file, for history: a header {@code Date|1 AUD|100 HUF|...} and one line per day
 *       ({@code 02.01.2026|13.797|6.304|...}). When the bank adds or drops a currency during the
 *       year, a new header line starts, so the columns are re-read at every header.
 * </ul>
 *
 * <p>The rates are Czech korunas for an <em>amount</em> of the foreign currency, which is 1 for
 * most and 100 or 1000 for currencies worth little (100 HUF, 1000 IDR). Each is stored per one
 * unit, as base = the foreign currency and quote = CZK, so that "1 USD = 21.804 CZK" is {@code
 * USD/CZK 21.804} and "100 HUF = 6.693 CZK" is {@code HUF/CZK 0.06693}. Dividing by a power of ten
 * moves the decimal point and nothing else, so the stored number is still exactly what was
 * published.
 */
@Component
public class CnbFixingTxtExtractor implements Extractor {

  static final String HOME = "CZK";

  private static final DateTimeFormatter DAILY_DATE =
      DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);
  private static final DateTimeFormatter YEARLY_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

  @Override
  public SourceSpec.Format format() {
    return SourceSpec.Format.CNB_FIXING_TXT;
  }

  @Override
  public String extension() {
    return "txt";
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
    while (!lines.isEmpty() && lines.getLast().isBlank()) {
      lines.removeLast();
    }
    if (lines.isEmpty()) {
      throw new ExtractionException("empty file");
    }
    int count =
        lines.getFirst().startsWith("Date|")
            ? yearly(spec, lines, context, sink)
            : daily(spec, lines, context, sink);
    if (count == 0) {
      throw new ExtractionException("no rates in the file");
    }
    return count;
  }

  private static int daily(
      SourceSpec spec, List<String> lines, Context context, ObservationSink sink)
      throws ExtractionException {
    // "06 Nov 2026 #214": the date, then the fixing's running number in the year.
    String first = lines.getFirst().strip();
    String dateText = first.contains("#") ? first.substring(0, first.indexOf('#')).strip() : first;
    LocalDate date = parse(dateText, DAILY_DATE, 1);
    if (lines.size() < 2 || !lines.get(1).strip().equals("Country|Currency|Amount|Code|Rate")) {
      throw new ExtractionException(
          "line 2: expected the header Country|Currency|Amount|Code|Rate, found '"
              + (lines.size() < 2 ? "" : lines.get(1))
              + "'");
    }
    int count = 0;
    for (int i = 2; i < lines.size(); i++) {
      String line = lines.get(i);
      if (line.isBlank()) {
        continue;
      }
      String[] cells = line.split("\\|", -1);
      if (cells.length != 5) {
        throw new ExtractionException(
            "line " + (i + 1) + ": expected 5 fields, found " + cells.length);
      }
      String code = code(cells[3], i + 1);
      BigDecimal amount = Numbers.decimal(cells[2]);
      emit(spec, context, sink, code, date, Numbers.perUnit(Numbers.decimal(cells[4]), amount));
      count++;
    }
    return count;
  }

  private static int yearly(
      SourceSpec spec, List<String> lines, Context context, ObservationSink sink)
      throws ExtractionException {
    List<String> codes = new ArrayList<>();
    List<BigDecimal> amounts = new ArrayList<>();
    int count = 0;
    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i);
      if (line.isBlank()) {
        continue;
      }
      String[] cells = line.split("\\|", -1);
      if (cells[0].equals("Date")) {
        // "100 HUF": the amount and the code, one column per currency until the next header.
        codes.clear();
        amounts.clear();
        for (int c = 1; c < cells.length; c++) {
          String[] parts = cells[c].strip().split(" ");
          if (parts.length != 2) {
            throw new ExtractionException(
                "line " + (i + 1) + ": column header '" + cells[c] + "' is not 'amount CODE'");
          }
          amounts.add(Numbers.decimal(parts[0]));
          codes.add(code(parts[1], i + 1));
        }
        continue;
      }
      if (codes.isEmpty()) {
        throw new ExtractionException("line " + (i + 1) + ": a row before any header");
      }
      if (cells.length != codes.size() + 1) {
        throw new ExtractionException(
            "line "
                + (i + 1)
                + ": "
                + (cells.length - 1)
                + " rates for "
                + codes.size()
                + " currencies in the header");
      }
      LocalDate date = parse(cells[0], YEARLY_DATE, i + 1);
      for (int c = 0; c < codes.size(); c++) {
        String value = cells[c + 1].strip();
        if (value.isEmpty()) {
          continue; // no fixing for that currency that day
        }
        emit(
            spec,
            context,
            sink,
            codes.get(c),
            date,
            Numbers.perUnit(Numbers.decimal(value), amounts.get(c)));
        count++;
      }
    }
    return count;
  }

  private static String code(String text, int line) throws ExtractionException {
    String code = text.strip();
    if (!code.matches("[A-Z]{3}")) {
      throw new ExtractionException("line " + line + ": '" + text + "' is not a currency code");
    }
    return code;
  }

  private static LocalDate parse(String text, DateTimeFormatter format, int line)
      throws ExtractionException {
    try {
      return LocalDate.parse(text.strip(), format);
    } catch (DateTimeParseException e) {
      throw new ExtractionException("line " + line + ": '" + text + "' is not a date", e);
    }
  }

  private static void emit(
      SourceSpec spec,
      Context context,
      ObservationSink sink,
      String code,
      LocalDate date,
      BigDecimal rate) {
    sink.rate(
        new ReferenceRateObserved(
            context.runId(), spec.id(), code, HOME, date, rate, context.evidenceSha256()));
  }
}
