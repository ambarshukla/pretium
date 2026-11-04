package io.pretium.collector.extract;

import io.pretium.collector.source.SourceSpec;
import io.pretium.events.ProviderQuoteObserved;
import io.pretium.events.ProviderQuoteObserved.Tier;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.dhatim.fastexcel.reader.Sheet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reads the World Bank's Remittance Prices Worldwide spreadsheet.
 *
 * <p>The sheet is large (about 300 MB of XML once unzipped), so it is read as a stream, one row at
 * a time, and each row is sent on as soon as it is read.
 *
 * <p>Columns are found by their header text. Each row is one service (a provider, a corridor, a way
 * of paying and of receiving) with prices at two surveyed amounts, the equivalents of USD 200
 * ({@code cc1}) and USD 500 ({@code cc2}).
 *
 * <p>A row whose numbers cannot be read at all is skipped and counted. If more than one row in a
 * hundred fails that way the whole run fails instead: that many unreadable rows means the layout
 * has changed, not that a few cells are untidy.
 */
@Component
public class RpwXlsxExtractor implements Extractor {

  private static final Logger log = LoggerFactory.getLogger(RpwXlsxExtractor.class);

  static final List<String> REQUIRED =
      List.of(
          "id",
          "period",
          "source_code",
          "source_name",
          "destination_code",
          "destination_name",
          "firm",
          "firm_type",
          "payment instrument",
          "access point",
          "speed actual",
          "inter lcu bank fx",
          "transparent",
          "date",
          "cc1 lcu amount",
          "cc1 denomination amount",
          "cc1 lcu code",
          "cc1 lcu fee",
          "cc1 lcu fx rate",
          "cc1 fx margin",
          "cc1 total cost %",
          "cc2 lcu amount",
          "cc2 denomination amount",
          "cc2 lcu code",
          "cc2 lcu fee",
          "cc2 lcu fx rate",
          "cc2 fx margin",
          "cc2 total cost %");

  private static final DateTimeFormatter TEXT_DATE =
      new DateTimeFormatterBuilder()
          .parseCaseInsensitive()
          .appendPattern("[dd/MMM/yyyy][d/MMM/yyyy][dd/MM/yyyy][yyyy-MM-dd]")
          .toFormatter(Locale.ENGLISH);

  private static final LocalDate EXCEL_EPOCH = LocalDate.of(1899, 12, 30);

  @Override
  public SourceSpec.Format format() {
    return SourceSpec.Format.RPW_XLSX;
  }

  @Override
  public String extension() {
    return "xlsx";
  }

  @Override
  public int extract(SourceSpec spec, Path file, Context context, ObservationSink sink)
      throws ExtractionException {
    String sheetName =
        spec.recipe() == null
            ? "Dataset (from Q2 2016)"
            : spec.recipe().get("sheet", "Dataset (from Q2 2016)");
    try (ReadableWorkbook workbook = new ReadableWorkbook(file.toFile())) {
      Sheet sheet =
          workbook
              .findSheet(sheetName)
              .orElseThrow(() -> new ExtractionException("sheet '" + sheetName + "' not found"));
      try (Stream<Row> rows = sheet.openStream()) {
        Iterator<Row> it = rows.iterator();
        if (!it.hasNext()) {
          throw new ExtractionException("sheet '" + sheetName + "' is empty");
        }
        Map<String, Integer> columns = header(it.next());
        int sent = 0;
        int unreadable = 0;
        while (it.hasNext()) {
          Row row = it.next();
          if (text(row, columns.get("id")) == null) {
            continue; // trailing empty rows
          }
          try {
            ProviderQuoteObserved quote = toQuote(spec, context, row, columns);
            if (quote != null) {
              sink.quote(quote);
              sent++;
            }
          } catch (ExtractionException e) {
            unreadable++;
            if (unreadable <= 5) {
              log.warn("{}: row {} skipped: {}", spec.id(), row.getRowNum(), e.getMessage());
            }
          }
        }
        if (sent == 0) {
          throw new ExtractionException("no readable rows");
        }
        if (unreadable * 100 > sent) {
          throw new ExtractionException(
              unreadable
                  + " unreadable rows against "
                  + sent
                  + " readable: layout has probably changed");
        }
        return sent;
      }
    } catch (IOException e) {
      throw new ExtractionException("could not read workbook: " + e.getMessage(), e);
    }
  }

  static Map<String, Integer> header(Row row) throws ExtractionException {
    Map<String, Integer> columns = new HashMap<>();
    for (int i = 0; i < row.getCellCount(); i++) {
      String name = text(row, i);
      if (name != null) {
        columns.putIfAbsent(name.strip().toLowerCase(Locale.ROOT), i);
      }
    }
    List<String> missing = new ArrayList<>();
    for (String required : REQUIRED) {
      if (!columns.containsKey(required)) {
        missing.add(required);
      }
    }
    if (!missing.isEmpty()) {
      throw new ExtractionException("columns not found: " + missing);
    }
    return columns;
  }

  private static ProviderQuoteObserved toQuote(
      SourceSpec spec, Context context, Row row, Map<String, Integer> c)
      throws ExtractionException {
    List<Tier> tiers = new ArrayList<>(2);
    for (String prefix : List.of("cc1", "cc2")) {
      Tier tier = tier(row, c, prefix);
      if (tier != null) {
        tiers.add(tier);
      }
    }
    if (tiers.isEmpty()) {
      return null; // a service listed without any price at either amount
    }
    return new ProviderQuoteObserved(
        context.runId(),
        spec.id(),
        text(row, c.get("id")),
        text(row, c.get("period")),
        text(row, c.get("source_code")),
        text(row, c.get("source_name")),
        text(row, c.get("destination_code")),
        text(row, c.get("destination_name")),
        text(row, c.get("firm")),
        text(row, c.get("firm_type")),
        text(row, c.get("payment instrument")),
        text(row, c.get("access point")),
        text(row, c.get("speed actual")),
        "yes".equalsIgnoreCase(text(row, c.get("transparent"))),
        number(row, c.get("inter lcu bank fx")),
        date(text(row, c.get("date"))),
        tiers,
        context.evidenceSha256());
  }

  private static Tier tier(Row row, Map<String, Integer> c, String p) throws ExtractionException {
    BigDecimal total = number(row, c.get(p + " total cost %"));
    BigDecimal fee = number(row, c.get(p + " lcu fee"));
    if (total == null && fee == null) {
      return null;
    }
    BigDecimal amountUsd = number(row, c.get(p + " denomination amount"));
    return new Tier(
        amountUsd == null ? 0 : amountUsd.intValue(),
        number(row, c.get(p + " lcu amount")),
        text(row, c.get(p + " lcu code")),
        fee,
        number(row, c.get(p + " lcu fx rate")),
        number(row, c.get(p + " fx margin")),
        total);
  }

  /** Cell text, or null for an empty cell or one of the source's "no value" markers. */
  static String text(Row row, Integer index) {
    if (index == null || index >= row.getCellCount() || row.getCell(index) == null) {
      return null;
    }
    String value = row.getCellText(index);
    if (value == null) {
      return null;
    }
    value = value.strip();
    return value.isEmpty() || value.equals("..") || value.equals("-") ? null : value;
  }

  private static BigDecimal number(Row row, Integer index) throws ExtractionException {
    return Numbers.fromExcelDouble(text(row, index));
  }

  /** The source writes dates as text ("11/May/2016") but a few rows hold Excel day numbers. */
  static LocalDate date(String value) {
    if (value == null) {
      return null;
    }
    if (value.matches("\\d+(\\.0+)?")) {
      return EXCEL_EPOCH.plusDays((long) Double.parseDouble(value));
    }
    try {
      return LocalDate.parse(value, TEXT_DATE);
    } catch (DateTimeParseException e) {
      return null; // kept as unknown; the catalog's checks flag a quote with no observation date
    }
  }
}
