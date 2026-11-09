package io.pretium.collector.extract;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A table that names currencies in words ("Australian dollar") rather than by code, turned into
 * codes. One table per publisher, in {@code currency-names/<name>.tsv}: the name exactly as the
 * page writes it, a tab, the ISO 4217 code.
 *
 * <p>A name that is not in the table is an error, not a guess: when a publisher starts listing a
 * new currency, the extraction fails with its name and someone adds the line.
 */
final class CurrencyNames {

  private static final Map<String, Map<String, String>> CACHE = new ConcurrentHashMap<>();

  private CurrencyNames() {}

  static String code(String table, String name) throws ExtractionException {
    String code = CACHE.computeIfAbsent(table, CurrencyNames::load).get(name.strip());
    if (code == null) {
      throw new ExtractionException(
          "'" + name + "' is not a currency code and is not in the '" + table + "' name table");
    }
    return code;
  }

  private static Map<String, String> load(String table) {
    if (!table.matches("[a-z0-9-]+")) {
      throw new IllegalStateException("bad name table: " + table);
    }
    try (InputStream in =
        CurrencyNames.class.getResourceAsStream("/currency-names/" + table + ".tsv")) {
      if (in == null) {
        throw new IllegalStateException("no name table 'currency-names/" + table + ".tsv'");
      }
      Map<String, String> out = new HashMap<>();
      BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isBlank()) {
          continue;
        }
        String[] parts = line.split("\t");
        if (parts.length != 2 || !parts[1].matches("[A-Z]{3}")) {
          throw new IllegalStateException("bad line in name table " + table + ": " + line);
        }
        out.put(parts[0].strip(), parts[1]);
      }
      return Map.copyOf(out);
    } catch (IOException e) {
      throw new IllegalStateException("cannot read name table " + table, e);
    }
  }
}
