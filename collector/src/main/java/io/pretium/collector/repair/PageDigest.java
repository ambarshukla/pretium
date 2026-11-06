package io.pretium.collector.repair;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Cuts a page down to the part a model needs to see: the markup around the rates table, with
 * scripts, styles and navigation removed. A full page is about 120 KB; the digest is a few KB,
 * which keeps a local model fast and keeps its attention on the table.
 */
public final class PageDigest {

  static final int MAX_CHARS = 6_000;

  private static final Pattern DATE =
      Pattern.compile(
          "\\d{1,2} (?:January|February|March|April|May|June|July|August|September|October|"
              + "November|December) \\d{4}");

  private PageDigest() {}

  /**
   * Where the page states its date, found by code rather than by the model: the last heading,
   * caption or paragraph before the rates table whose own text contains a "26 January 2017" style
   * date. A model kept choosing elements with no date in them; finding a date is a pattern match,
   * so a program does it.
   *
   * <p>"Last before the table", not "first on the page": since 2022 the page often carries news
   * items with their own dates above the table. On the 118 archived copies whose date a verified
   * recipe established, the first date on the page was the rates' date 74 times; the last one
   * before the table, 118 times.
   */
  public record DateHint(String selector, String format, String regex, String text) {}

  private static final Set<String> DATE_TAGS =
      Set.of("h1", "h2", "h3", "h4", "caption", "p", "div", "span");

  public static DateHint dateHint(Path page) throws IOException {
    Document doc = Jsoup.parse(page.toFile(), StandardCharsets.UTF_8.name());
    Element first = null;
    Element beforeTable = null;
    boolean table = false;
    for (Element e : doc.getAllElements()) {
      if (e.ownText().strip().equals("USD")) {
        table = true;
        break;
      }
      if (DATE_TAGS.contains(e.tagName()) && DATE.matcher(e.ownText()).find()) {
        if (first == null) {
          first = e;
        }
        beforeTable = e;
      }
    }
    Element e = table && beforeTable != null ? beforeTable : first;
    if (e == null) {
      return null;
    }
    String tag = e.tagName();
    String selector = doc.selectFirst(tag) == e ? tag : e.cssSelector();
    return new DateHint(
        selector, "d MMMM yyyy", "(\\d{1,2} [A-Za-z]+ \\d{4})", e.ownText().strip());
  }

  public static String of(Path page) throws IOException {
    Document doc = Jsoup.parse(page.toFile(), StandardCharsets.UTF_8.name());
    doc.select("script, style, noscript, svg, img, iframe, link, meta, nav, footer, header")
        .remove();

    // Anchor on the first element whose whole text is "USD": the rates table is around it.
    Element anchor =
        doc.getAllElements().stream()
            .filter(e -> e.ownText().strip().equals("USD"))
            .findFirst()
            .orElse(null);
    if (anchor == null) {
      return trim(doc.body().html());
    }
    // Climb until the container holds the date heading as well as the table, or gets too big.
    Element container = anchor;
    while (container.parent() != null
        && container.parent().outerHtml().length() < MAX_CHARS * 3
        && !container.outerHtml().contains("<h")) {
      container = container.parent();
    }
    return trim(container.outerHtml());
  }

  private static String trim(String html) {
    String compact = html.replaceAll("\\s+", " ");
    return compact.length() <= MAX_CHARS ? compact : compact.substring(0, MAX_CHARS) + " ...";
  }
}
