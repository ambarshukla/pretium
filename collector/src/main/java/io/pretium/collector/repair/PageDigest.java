package io.pretium.collector.repair;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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

  private PageDigest() {}

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
