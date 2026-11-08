package io.pretium.collector.extract;

import java.math.BigDecimal;

/** Turning source text into exact decimals. */
final class Numbers {

  private Numbers() {}

  /**
   * Parses a plain decimal such as {@code 1.1179}. Rates are never read through {@code double}:
   * 0.83358 has no exact binary representation, and a reference rate must come out exactly as
   * published.
   */
  static BigDecimal decimal(String text) throws ExtractionException {
    String cleaned = text == null ? "" : text.strip().replace(",", "");
    try {
      return new BigDecimal(cleaned);
    } catch (NumberFormatException e) {
      throw new ExtractionException("not a number: '" + text + "'", e);
    }
  }

  /**
   * Parses a number that Excel stored as a binary double, such as {@code 8.4000000000000005E-2}.
   * The source typed 0.084; the trailing digits are float noise, so the value is rounded back to
   * the shortest decimal that maps to the same double, which is what the author entered.
   */
  static BigDecimal fromExcelDouble(String raw) throws ExtractionException {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return BigDecimal.valueOf(Double.parseDouble(raw.strip()));
    } catch (NumberFormatException e) {
      throw new ExtractionException("not a number: '" + raw + "'", e);
    }
  }

  /**
   * A rate quoted for {@code amount} units (100 HUF, 1000 IDR), as the rate for one unit. Sources
   * use a power of ten, so this moves the decimal point and nothing else: exact, no rounding.
   */
  static BigDecimal perUnit(BigDecimal rate, BigDecimal amount) throws ExtractionException {
    if (amount.signum() <= 0) {
      throw new ExtractionException("amount must be positive, found " + amount);
    }
    BigDecimal stripped = amount.stripTrailingZeros();
    if (stripped.unscaledValue().intValue() != 1 || stripped.scale() > 0) {
      throw new ExtractionException("amount " + amount + " is not a power of ten");
    }
    return rate.movePointLeft(-stripped.scale());
  }
}
