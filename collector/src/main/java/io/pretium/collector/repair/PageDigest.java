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

  /** A way of writing a date: how to spot it, and the recipe settings that read it. */
  private record Shape(Pattern find, String format, String regex) {}

  private static final String SHORT_MONTH = "(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)";

  /**
   * The date shapes the hint knows, tried in this order over the whole page: a later shape is only
   * used when no element has an earlier one. The first is the ECB's ("26 January 2017"), so its
   * pages get exactly the hints they always got. The CNB's page has written "04 Jan 2019" since
   * 2010, and "24.Jul 2009" before that.
   */
  private static final java.util.List<Shape> SHAPES =
      java.util.List.of(
          new Shape(
              Pattern.compile(
                  "\\d{1,2} (?:January|February|March|April|May|June|July|August|September|"
                      + "October|November|December) \\d{4}"),
              "d MMMM yyyy",
              "(\\d{1,2} [A-Za-z]+ \\d{4})"),
          new Shape(
              Pattern.compile("\\b\\d{1,2} " + SHORT_MONTH + " \\d{4}"),
              "d MMM yyyy",
              "(\\d{1,2} [A-Za-z]{3} \\d{4})"),
          new Shape(
              Pattern.compile("\\b\\d{1,2}\\." + SHORT_MONTH + " \\d{4}"),
              "d.MMM yyyy",
              "(\\d{1,2}\\.[A-Za-z]{3} \\d{4})"));

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
    for (Shape shape : SHAPES) {
      DateHint hint = dateHint(doc, shape);
      if (hint != null) {
        return hint;
      }
    }
    return null;
  }

  private static DateHint dateHint(Document doc, Shape shape) {
    Pattern date = shape.find();
    Element first = null;
    Element beforeTable = null;
    boolean table = false;
    for (Element e : doc.getAllElements()) {
      if (e.ownText().strip().equals("USD")) {
        table = true;
        break;
      }
      if (DATE_TAGS.contains(e.tagName()) && date.matcher(e.ownText()).find()) {
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
    String selector = doc.selectFirst(tag) == e ? tag : textSelector(doc, e, date);
    return new DateHint(selector, shape.format(), shape.regex(), e.ownText().strip());
  }

  private static final Pattern PLAIN_WORDS = Pattern.compile("[A-Za-z][A-Za-z :-]{3,}[A-Za-z]");

  /**
   * How to point at the date's element when its tag alone does not: by the words around the date
   * ("p:containsOwn(Validity for)"), which survive blocks being added or moved on the page, and
   * only if that finds this very element first. Otherwise the element's position in the page (a
   * path such as "div:nth-child(5) > p"), which is exact but breaks at the next change of layout.
   */
  private static String textSelector(Document doc, Element e, Pattern date) {
    String text = e.ownText().strip();
    java.util.regex.Matcher m = date.matcher(text);
    if (m.find()) {
      for (String words : new String[] {text.substring(0, m.start()), text.substring(m.end())}) {
        words = words.strip();
        if (PLAIN_WORDS.matcher(words).matches()) {
          // Plain words first; then anchored to the date's side, since other elements of the page
          // may use the same words (containsOwn ignores case and position).
          boolean before = words.equals(text.substring(0, m.start()).strip());
          for (String candidate :
              new String[] {
                e.tagName() + ":containsOwn(" + words + ")",
                e.tagName()
                    + (before
                        ? ":matchesOwn(^" + words + " \\d)"
                        : ":matchesOwn(\\d " + words + "$)")
              }) {
            if (doc.selectFirst(candidate) == e) {
              return candidate;
            }
          }
        }
      }
    }
    return e.cssSelector();
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

  /**
   * The whole rates table, for a model asked to read every rate itself: the page's date heading,
   * then the table's markup with attributes removed (they are most of its size and carry no
   * values). Not cut to a length. Null if there is no table around a cell that says USD.
   */
  public static String table(Path page) throws IOException {
    Document doc = Jsoup.parse(page.toFile(), StandardCharsets.UTF_8.name());
    Element usd =
        doc.getAllElements().stream()
            .filter(e -> e.ownText().strip().equals("USD"))
            .findFirst()
            .orElse(null);
    Element table = usd == null ? null : usd.closest("table");
    if (table == null) {
      return null;
    }
    for (Element e : table.getAllElements()) {
      e.clearAttributes();
    }
    DateHint date = dateHint(page);
    return (date == null ? "" : date.text() + "\n") + table.outerHtml().replaceAll("\\s+", " ");
  }

  private static String trim(String html) {
    String compact = html.replaceAll("\\s+", " ");
    return compact.length() <= MAX_CHARS ? compact : compact.substring(0, MAX_CHARS) + " ...";
  }
}
