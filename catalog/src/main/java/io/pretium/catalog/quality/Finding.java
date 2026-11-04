package io.pretium.catalog.quality;

/**
 * One check's verdict on one value.
 *
 * @param rule a stable id for the check, e.g. {@code cost-identity}
 * @param severity how much it matters
 * @param detail what was found, with the numbers, in words an analyst can act on
 */
public record Finding(String rule, Severity severity, String detail) {

  public enum Severity {
    /** Worth showing to a reader, not a problem: e.g. the provider does not disclose its rate. */
    INFO,
    /**
     * Published, but flagged: the number is plausible and the source's own arithmetic is a little
     * off.
     */
    WARN,
    /** Not published: the number contradicts itself or is impossible. */
    ERROR
  }
}
