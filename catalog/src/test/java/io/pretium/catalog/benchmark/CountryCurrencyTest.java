package io.pretium.catalog.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Every row of the country-to-currency table (migration V2) must be in the official ISO 4217 lists:
 * the current list for a currency in use, the list of historic currencies (with the same withdrawal
 * month) for one that was replaced by the euro. The fixture is a verbatim excerpt of both lists as
 * published by SIX, the ISO 4217 maintenance agency (see docs/PROVENANCE.md).
 */
class CountryCurrencyTest {

  record Row(
      String country, String isoName, String currency, String validFrom, String validUntil) {}

  static final Pattern ROW =
      Pattern.compile(
          "\\('([A-Z]{3})', '((?:[^']|'')*)', '([A-Z]{3})', (null|'[0-9-]+'), (null|'[0-9-]+')\\)");

  static List<Row> rows() throws Exception {
    String sql;
    try (InputStream in =
        CountryCurrencyTest.class.getResourceAsStream("/db/migration/V2__country_currency.sql")) {
      sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    List<Row> rows = new ArrayList<>();
    Matcher m = ROW.matcher(sql);
    while (m.find()) {
      rows.add(
          new Row(
              m.group(1),
              m.group(2).replace("''", "'"),
              m.group(3),
              unquote(m.group(4)),
              unquote(m.group(5))));
    }
    return rows;
  }

  static String unquote(String v) {
    return "null".equals(v) ? null : v.substring(1, v.length() - 1);
  }

  /** "NAME|CCY" for current entries, "NAME|CCY|yyyy-MM" for historic ones. */
  static Set<String> iso(String tag, boolean historic) throws Exception {
    Document doc;
    try (InputStream in =
        CountryCurrencyTest.class.getResourceAsStream("/fixtures/iso4217-excerpt.xml")) {
      doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(in);
    }
    Set<String> out = new HashSet<>();
    NodeList entries = doc.getElementsByTagName(tag);
    for (int i = 0; i < entries.getLength(); i++) {
      Element e = (Element) entries.item(i);
      String key = text(e, "CtryNm") + "|" + text(e, "Ccy");
      out.add(historic ? key + "|" + text(e, "WthdrwlDt") : key);
    }
    return out;
  }

  static String text(Element e, String tag) {
    NodeList n = e.getElementsByTagName(tag);
    return n.getLength() == 0 ? null : n.item(0).getTextContent().strip();
  }

  @Test
  void everyRowIsInTheOfficialLists() throws Exception {
    List<Row> rows = rows();
    Set<String> current = iso("CcyNtry", false);
    Set<String> historic = iso("HstrcCcyNtry", true);

    assertThat(rows).hasSize(48);
    for (Row r : rows) {
      if (r.validUntil() == null) {
        assertThat(current).as(r.toString()).contains(r.isoName() + "|" + r.currency());
      } else {
        // Withdrawn currencies: valid until the first day of ISO's withdrawal month.
        assertThat(historic)
            .as(r.toString())
            .contains(r.isoName() + "|" + r.currency() + "|" + r.validUntil().substring(0, 7));
      }
    }
  }

  @Test
  void euroChangeoversLeaveNoGapAndNoOverlap() throws Exception {
    List<Row> rows = rows();
    for (Row old : rows.stream().filter(r -> r.validUntil() != null).toList()) {
      Row euro =
          rows.stream()
              .filter(r -> r.country().equals(old.country()) && r.currency().equals("EUR"))
              .findFirst()
              .orElseThrow();
      assertThat(euro.validFrom()).as(old.country()).isEqualTo(old.validUntil());
    }
    // Bulgaria joined on 1 January 2026, Croatia on 1 January 2023.
    assertThat(rows)
        .contains(
            new Row("BGR", "BULGARIA", "BGN", null, "2026-01-01"),
            new Row("BGR", "BULGARIA", "EUR", "2026-01-01", null),
            new Row("HRV", "CROATIA", "HRK", null, "2023-01-01"));
  }
}
