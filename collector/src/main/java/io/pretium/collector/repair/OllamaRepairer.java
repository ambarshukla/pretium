package io.pretium.collector.repair;

import io.pretium.collector.source.SourceSpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Asks a local model (through Ollama's HTTP API) for a new recipe when the current one fails.
 *
 * <p>The model is asked for <em>selectors</em>, never for rates. A selector can be checked: run it,
 * compare what it reads with the reference channel. A number typed by a model cannot be checked
 * against anything but itself. This is the main guard against hallucination; the verifier is the
 * second.
 */
public final class OllamaRepairer {

  private static final String PROMPT =
      """
      You maintain a web scraper. It reads a table of euro exchange rates from a web page using
      CSS selectors (as understood by the Java library jsoup). The page's layout has changed and
      the current recipe no longer works.

      Current recipe:
      %s

      Error from the current recipe:
      %s

      The relevant part of the new page:
      %s

      Reply with JSON only, with exactly these keys:
        "dateSelector":     CSS selector for the element whose text is the date of the rates
        "dateFormat":       Java DateTimeFormatter pattern for that text, e.g. "d MMMM yyyy"
        "rowSelector":      CSS selector matching one element per currency
        "currencySelector": CSS selector, relative to a row, for the 3-letter currency code
        "rateSelector":     CSS selector, relative to a row, for the rate number
        "dateRegex":        only if the date is part of longer text: a Java regex whose first
                            group captures just the date; otherwise null
      Do not include any rates or other values from the page, only selectors.
      """;

  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  private final JsonMapper json = JsonMapper.builder().build();
  private final URI endpoint;
  private final String model;

  public OllamaRepairer(String baseUrl, String model) {
    this.endpoint = URI.create(baseUrl + "/api/generate");
    this.model = model;
  }

  public String model() {
    return model;
  }

  /** A proposal and what it cost. */
  public record Proposal(
      SourceSpec.Recipe recipe, long millis, int promptTokens, int outputTokens, String raw) {}

  public Proposal propose(SourceSpec.Recipe current, String error, String digest)
      throws IOException, InterruptedException {
    String prompt = PROMPT.formatted(json.writeValueAsString(current.settings()), error, digest);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", model);
    body.put("prompt", prompt);
    body.put("format", "json");
    body.put("stream", false);
    // Temperature 0: the same page and error give the same proposal, so results are repeatable.
    body.put("options", Map.of("temperature", 0, "num_ctx", 8192));

    long start = System.nanoTime();
    HttpResponse<String> response =
        http.send(
            HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofMinutes(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    long millis = (System.nanoTime() - start) / 1_000_000;
    if (response.statusCode() != 200) {
      throw new IOException(
          "Ollama returned HTTP " + response.statusCode() + ": " + response.body());
    }
    JsonNode reply = json.readTree(response.body());
    String text = reply.path("response").asString("");
    JsonNode proposed = json.readTree(text.isBlank() ? "{}" : text);

    Map<String, String> settings = new LinkedHashMap<>();
    settings.put("base", current.get("base", "EUR"));
    for (String key :
        new String[] {
          "dateSelector",
          "dateFormat",
          "rowSelector",
          "currencySelector",
          "rateSelector",
          "dateRegex"
        }) {
      JsonNode value = proposed.path(key);
      if (value.isString() && !value.asString().isBlank()) {
        settings.put(key, value.asString());
      } else if (!key.equals("dateRegex")) {
        settings.put(key, current.get(key, ""));
      }
    }
    return new Proposal(
        new SourceSpec.Recipe(current.version() + 1, settings),
        millis,
        reply.path("prompt_eval_count").asInt(0),
        reply.path("eval_count").asInt(0),
        text);
  }
}
