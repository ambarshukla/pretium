package io.pretium.collector.extract;

import io.pretium.collector.source.SourceSpec;
import io.pretium.events.ReferenceRateObserved;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

/**
 * A scraper: reads a table of exchange rates out of an HTML page using the CSS selectors in the
 * source's recipe. The code knows nothing about any particular page; the recipe does.
 *
 * <p>Recipe settings: {@code base} (the currency the table is quoted against), {@code dateSelector}
 * and {@code dateFormat} (where the as-of date is and how it is written), {@code rowSelector} (one
 * match per currency), and {@code currencySelector} and {@code rateSelector} (inside each row).
 *
 * <p>Every failure names the selector that stopped matching, because that is the first thing anyone
 * repairing the recipe needs to know. {@link #trace} runs the same steps and records each one, for
 * the parser lab: what every selector matched, row by row, and where it stopped.
 */
@Component
public class HtmlRateTableExtractor implements Extractor {

  /**
   * How the date was found.
   *
   * @param elementText the text of the element {@code dateSelector} matched, or null if none
   * @param dateText the part of it that was parsed (after {@code dateRegex}, if any)
   * @param date the parsed date, or null if parsing failed
   */
  public record DateStep(
      String selector,
      String elementText,
      String regex,
      String dateText,
      String format,
      LocalDate date) {}

  /**
   * One element {@code rowSelector} matched.
   *
   * @param index position among the matched rows, from 0
   * @param currencyText text of the currency cell, or null if the row has none
   * @param rateText text of the rate cell, or null if the row has none
   * @param rate the parsed rate, or null if the row was skipped or the text is not a number
   * @param skipped why the row was skipped, or null if it was read
   */
  public record Row(
      int index, String currencyText, String rateText, BigDecimal rate, String skipped) {}

  /**
   * Every step of one extraction. {@code error} is the message {@link #extract} would have failed
   * with, or null if it would have succeeded; the steps up to that point are kept either way.
   */
  public record Trace(
      LocalDate date, DateStep dateStep, String rowSelector, List<Row> rows, String error) {

    /** Rows that produced a rate. */
    public long read() {
      return rows.stream().filter(r -> r.rate() != null && r.skipped() == null).count();
    }
  }

  @Override
  public SourceSpec.Format format() {
    return SourceSpec.Format.HTML_RATE_TABLE;
  }

  @Override
  public String extension() {
    return "html";
  }

  @Override
  public int extract(SourceSpec spec, Path file, Context context, ObservationSink sink)
      throws ExtractionException {
    SourceSpec.Recipe recipe = spec.recipe();
    if (recipe == null) {
      throw new ExtractionException("HTML source has no recipe");
    }
    Document doc;
    try {
      doc = Jsoup.parse(file.toFile(), StandardCharsets.UTF_8.name(), spec.url());
    } catch (IOException e) {
      throw new ExtractionException("could not read file: " + e.getMessage(), e);
    }
    Steps steps = new Steps();
    read(doc, recipe, steps);
    String base = recipe.require("base");
    for (Row row : steps.rows) {
      if (row.skipped() == null) {
        sink.rate(
            new ReferenceRateObserved(
                context.runId(),
                spec.id(),
                base,
                row.currencyText(),
                steps.date,
                row.rate(),
                context.evidenceSha256()));
      }
    }
    return (int) steps.rows.stream().filter(r -> r.skipped() == null).count();
  }

  /**
   * Runs the recipe against a page and records every step, without failing: the error, if any, is
   * in the trace. Nothing is emitted.
   */
  public static Trace trace(Document doc, SourceSpec.Recipe recipe) {
    Steps steps = new Steps();
    String error = null;
    try {
      read(doc, recipe, steps);
    } catch (ExtractionException | RuntimeException e) {
      error = e.getMessage();
    }
    return new Trace(
        steps.date,
        steps.dateStep,
        recipe.get("rowSelector", null),
        List.copyOf(steps.rows),
        error);
  }

  /** What {@link #read} found so far. Kept outside it so a failure leaves the steps behind. */
  private static final class Steps {
    LocalDate date;
    DateStep dateStep;
    final List<Row> rows = new ArrayList<>();
  }

  private static void read(Document doc, SourceSpec.Recipe recipe, Steps steps)
      throws ExtractionException {
    String dateSelector = recipe.require("dateSelector");
    String dateRegex = recipe.get("dateRegex", null);
    String dateFormat = recipe.get("dateFormat", null);
    Element dateElement = doc.selectFirst(dateSelector);
    if (dateElement == null) {
      steps.dateStep = new DateStep(dateSelector, null, dateRegex, null, dateFormat, null);
      throw new ExtractionException("dateSelector '" + dateSelector + "' matched nothing");
    }
    // Optional dateRegex: when the date sits inside longer text ("Reference rates: 26 January
    // 2017", as the page had it in 2017), its first group picks the date out.
    String elementText = dateElement.text().strip();
    String dateText = elementText;
    steps.dateStep = new DateStep(dateSelector, elementText, dateRegex, null, dateFormat, null);
    if (dateRegex != null) {
      Matcher m;
      try {
        m = Pattern.compile(dateRegex).matcher(dateText);
      } catch (PatternSyntaxException e) {
        throw new ExtractionException("dateRegex '" + dateRegex + "' is not a valid regex", e);
      }
      if (!m.find() || m.groupCount() < 1) {
        throw new ExtractionException(
            "dateRegex '" + dateRegex + "' found no date group in '" + dateText + "'");
      }
      dateText = m.group(1).strip();
    }
    steps.dateStep = new DateStep(dateSelector, elementText, dateRegex, dateText, dateFormat, null);
    LocalDate date;
    try {
      date =
          LocalDate.parse(
              dateText, DateTimeFormatter.ofPattern(recipe.require("dateFormat"), Locale.ENGLISH));
    } catch (DateTimeParseException | IllegalArgumentException e) {
      throw new ExtractionException(
          "dateSelector '"
              + dateSelector
              + "' found '"
              + dateText
              + "', which is not a date in format '"
              + recipe.require("dateFormat")
              + "'",
          e);
    }
    steps.date = date;
    steps.dateStep = new DateStep(dateSelector, elementText, dateRegex, dateText, dateFormat, date);

    String rowSelector = recipe.require("rowSelector");
    Elements rows = doc.select(rowSelector);
    if (rows.isEmpty()) {
      throw new ExtractionException("rowSelector '" + rowSelector + "' matched nothing");
    }

    String currencySelector = recipe.require("currencySelector");
    String rateSelector = recipe.require("rateSelector");
    recipe.require("base");
    int count = 0;
    int index = 0;
    for (Element row : rows) {
      Element currency = row.selectFirst(currencySelector);
      Element rate = row.selectFirst(rateSelector);
      String code = currency == null ? null : currency.text().strip();
      String rateText = rate == null ? null : rate.text().strip();
      if (currency == null || rate == null) {
        // header or spacer rows match too broad a row selector; skip, but see below
        steps.rows.add(
            new Row(
                index++,
                code,
                rateText,
                null,
                currency == null ? "no currency cell" : "no rate cell"));
        continue;
      }
      if (!code.matches("[A-Z]{3}")) {
        steps.rows.add(new Row(index++, code, rateText, null, "not a currency code"));
        throw new ExtractionException(
            "currencySelector '"
                + currencySelector
                + "' found '"
                + code
                + "', which is not a currency code");
      }
      BigDecimal value;
      try {
        value = Numbers.decimal(rate.text());
      } catch (ExtractionException e) {
        steps.rows.add(new Row(index++, code, rateText, null, "not a number"));
        throw e;
      }
      steps.rows.add(new Row(index++, code, rateText, value, null));
      count++;
    }
    if (count == 0) {
      throw new ExtractionException(
          rows.size()
              + " rows matched '"
              + rowSelector
              + "' but none contained both '"
              + currencySelector
              + "' and '"
              + rateSelector
              + "'");
    }
  }
}
