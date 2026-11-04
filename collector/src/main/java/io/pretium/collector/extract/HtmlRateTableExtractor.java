package io.pretium.collector.extract;

import io.pretium.collector.source.SourceSpec;
import io.pretium.events.ReferenceRateObserved;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
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
 * repairing the recipe needs to know.
 */
@Component
public class HtmlRateTableExtractor implements Extractor {

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

    String dateSelector = recipe.require("dateSelector");
    Element dateElement = doc.selectFirst(dateSelector);
    if (dateElement == null) {
      throw new ExtractionException("dateSelector '" + dateSelector + "' matched nothing");
    }
    // Optional dateRegex: when the date sits inside longer text ("Reference rates: 26 January
    // 2017", as the page had it in 2017), its first group picks the date out.
    String dateText = dateElement.text().strip();
    String dateRegex = recipe.get("dateRegex", null);
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

    String rowSelector = recipe.require("rowSelector");
    Elements rows = doc.select(rowSelector);
    if (rows.isEmpty()) {
      throw new ExtractionException("rowSelector '" + rowSelector + "' matched nothing");
    }

    String currencySelector = recipe.require("currencySelector");
    String rateSelector = recipe.require("rateSelector");
    String base = recipe.require("base");
    int count = 0;
    for (Element row : rows) {
      Element currency = row.selectFirst(currencySelector);
      Element rate = row.selectFirst(rateSelector);
      if (currency == null || rate == null) {
        continue; // header or spacer rows match too broad a row selector; skip, but see below
      }
      String code = currency.text().strip();
      if (!code.matches("[A-Z]{3}")) {
        throw new ExtractionException(
            "currencySelector '"
                + currencySelector
                + "' found '"
                + code
                + "', which is not a currency code");
      }
      sink.rate(
          new ReferenceRateObserved(
              context.runId(),
              spec.id(),
              base,
              code,
              date,
              Numbers.decimal(rate.text()),
              context.evidenceSha256()));
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
    return count;
  }
}
