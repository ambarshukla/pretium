package io.pretium.collector.fetch;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The parts of a robots.txt file that apply to us: the {@code Disallow} and {@code Allow} rules in
 * the group for our user agent, or in the {@code *} group if there is none.
 *
 * <p>Matching follows RFC 9309: the longest matching rule wins, {@code Allow} wins a tie, {@code *}
 * matches any run of characters and a trailing {@code $} anchors the end of the path.
 */
public final class RobotsRules {

  private record Rule(boolean allow, String pattern, Pattern regex) {}

  private static final RobotsRules ALLOW_ALL = new RobotsRules(List.of());

  private final List<Rule> rules;

  private RobotsRules(List<Rule> rules) {
    this.rules = rules;
  }

  /** What to assume when a site has no robots.txt: everything is allowed. */
  public static RobotsRules allowAll() {
    return ALLOW_ALL;
  }

  /** Parses a robots.txt body for the given product token (e.g. {@code pretium}). */
  public static RobotsRules parse(String body, String productToken) {
    String token = productToken.toLowerCase(Locale.ROOT);
    List<Rule> specific = new ArrayList<>();
    List<Rule> wildcard = new ArrayList<>();
    boolean sawSpecific = false;

    List<String> groupAgents = new ArrayList<>();
    boolean inRules = false;
    List<Rule> current = new ArrayList<>();

    for (String raw : body.split("\\R")) {
      String line = raw.replaceFirst("#.*", "").trim();
      int colon = line.indexOf(':');
      if (colon < 0) {
        continue;
      }
      String field = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
      String value = line.substring(colon + 1).trim();

      if (field.equals("user-agent")) {
        if (inRules) {
          // A user-agent line after rules starts a new group: file the finished one.
          sawSpecific |= file(groupAgents, current, token, specific, wildcard);
          groupAgents = new ArrayList<>();
          current = new ArrayList<>();
          inRules = false;
        }
        groupAgents.add(value.toLowerCase(Locale.ROOT));
      } else if (field.equals("allow") || field.equals("disallow")) {
        inRules = true;
        if (!value.isEmpty()) {
          current.add(new Rule(field.equals("allow"), value, toRegex(value)));
        }
      }
    }
    sawSpecific |= file(groupAgents, current, token, specific, wildcard);
    return new RobotsRules(sawSpecific ? specific : wildcard);
  }

  private static boolean file(
      List<String> agents,
      List<Rule> rules,
      String token,
      List<Rule> specific,
      List<Rule> wildcard) {
    boolean matched = false;
    for (String agent : agents) {
      if (agent.equals(token)) {
        specific.addAll(rules);
        matched = true;
      } else if (agent.equals("*")) {
        wildcard.addAll(rules);
      }
    }
    return matched;
  }

  private static Pattern toRegex(String pattern) {
    StringBuilder regex = new StringBuilder();
    boolean anchored = pattern.endsWith("$");
    String body = anchored ? pattern.substring(0, pattern.length() - 1) : pattern;
    for (String part : body.split("\\*", -1)) {
      if (!regex.isEmpty()) {
        regex.append(".*");
      }
      regex.append(Pattern.quote(part));
    }
    if (anchored) {
      regex.append('$');
    }
    return Pattern.compile(regex.toString());
  }

  /** Whether a path (including any query string) may be fetched. */
  public boolean allows(String pathAndQuery) {
    Rule best = null;
    for (Rule rule : rules) {
      if (rule.regex().matcher(pathAndQuery).lookingAt()) {
        if (best == null
            || rule.pattern().length() > best.pattern().length()
            || (rule.pattern().length() == best.pattern().length() && rule.allow())) {
          best = rule;
        }
      }
    }
    return best == null || best.allow();
  }
}
