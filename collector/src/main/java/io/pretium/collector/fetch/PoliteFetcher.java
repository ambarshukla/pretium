package io.pretium.collector.fetch;

import io.pretium.collector.fetch.FetchResult.Outcome;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Fetches a URL the way a well-behaved crawler should.
 *
 * <ul>
 *   <li>Identifies itself: the User-Agent names the project and a URL to read about it.
 *   <li>Reads robots.txt first (cached for a day) and does not fetch what it disallows.
 *   <li>Waits between requests to the same host.
 *   <li>Asks conditionally (If-None-Match) so an unchanged file is not downloaded again.
 *   <li>Treats a refusal as final. A 401, 403, 451 or a bot challenge is recorded as {@code
 *       BLOCKED} and left alone: no retries, no rotating identities, no headless-browser tricks.
 * </ul>
 */
@Component
public class PoliteFetcher {

  private static final Logger log = LoggerFactory.getLogger(PoliteFetcher.class);
  private static final Duration ROBOTS_TTL = Duration.ofHours(24);

  /**
   * How long "assume nothing is allowed" stands after robots.txt could not be read. Much shorter
   * than a good answer's day: a server blip at 16:19 must not block the 16:20 run and every run
   * until tomorrow.
   */
  static final Duration ROBOTS_RETRY = Duration.ofMinutes(15);

  private record CachedRobots(RobotsRules rules, Instant expiresAt) {}

  private final HttpClient http;
  private final String userAgent;
  private final String productToken;
  private final Duration perHostGap;
  private final Clock clock;
  private final Map<String, CachedRobots> robots = new ConcurrentHashMap<>();
  private final Map<String, Instant> lastRequest = new ConcurrentHashMap<>();

  @Autowired
  public PoliteFetcher(
      @Value("${pretium.fetch.user-agent}") String userAgent,
      @Value("${pretium.fetch.per-host-gap:5s}") Duration perHostGap) {
    this(
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build(),
        userAgent,
        perHostGap,
        Clock.systemUTC());
  }

  PoliteFetcher(HttpClient http, String userAgent, Duration perHostGap, Clock clock) {
    this.http = http;
    this.userAgent = userAgent;
    this.productToken = userAgent.split("[/ ]")[0];
    this.perHostGap = perHostGap;
    this.clock = clock;
  }

  public FetchResult fetch(String url, Optional<String> knownEtag) {
    URI uri = URI.create(url);
    String pathAndQuery =
        uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
    if (!robotsFor(uri).allows(pathAndQuery)) {
      return new FetchResult(Outcome.BLOCKED, 0, null, null, "disallowed by robots.txt");
    }

    HttpRequest.Builder request =
        HttpRequest.newBuilder(uri)
            .timeout(Duration.ofMinutes(5))
            .header("User-Agent", userAgent)
            .header("Accept-Encoding", "identity");
    knownEtag.ifPresent(etag -> request.header("If-None-Match", etag));

    Path tmp = null;
    try {
      waitForTurn(uri.getHost());
      tmp = Files.createTempFile("pretium-", ".download");
      HttpResponse<Path> response =
          http.send(request.build(), HttpResponse.BodyHandlers.ofFile(tmp));
      int status = response.statusCode();
      String etag = response.headers().firstValue("ETag").orElse(null);

      if (status == 304) {
        Files.deleteIfExists(tmp);
        return new FetchResult(Outcome.NOT_MODIFIED, status, null, etag, null);
      }
      if (isChallenge(response)) {
        Files.deleteIfExists(tmp);
        return new FetchResult(Outcome.BLOCKED, status, null, null, "bot challenge served");
      }
      if (status == 401 || status == 403 || status == 451) {
        Files.deleteIfExists(tmp);
        return new FetchResult(Outcome.BLOCKED, status, null, null, "refused with HTTP " + status);
      }
      if (status < 200 || status >= 300) {
        Files.deleteIfExists(tmp);
        return new FetchResult(Outcome.FAILED, status, null, null, "HTTP " + status);
      }
      return new FetchResult(Outcome.OK, status, tmp, etag, null);
    } catch (IOException e) {
      deleteQuietly(tmp);
      return new FetchResult(
          Outcome.FAILED, 0, null, null, e.getClass().getSimpleName() + ": " + e.getMessage());
    } catch (InterruptedException e) {
      deleteQuietly(tmp);
      Thread.currentThread().interrupt();
      return new FetchResult(Outcome.FAILED, 0, null, null, "interrupted");
    }
  }

  /** Cloudflare marks its challenge pages with a header; the body check catches older setups. */
  private static boolean isChallenge(HttpResponse<Path> response) throws IOException {
    if (response
        .headers()
        .firstValue("cf-mitigated")
        .map("challenge"::equalsIgnoreCase)
        .orElse(false)) {
      return true;
    }
    if (response.statusCode() == 403 || response.statusCode() == 503) {
      long size = Files.size(response.body());
      if (size < 200_000) {
        String body = Files.readString(response.body());
        return body.contains("challenges.cloudflare.com") || body.contains("Just a moment...");
      }
    }
    return false;
  }

  private RobotsRules robotsFor(URI uri) {
    String origin = uri.getScheme() + "://" + uri.getAuthority();
    CachedRobots cached = robots.get(origin);
    if (cached != null && cached.expiresAt().isAfter(clock.instant())) {
      return cached.rules();
    }
    CachedRobots fresh = fetchRobots(origin);
    robots.put(origin, fresh);
    return fresh.rules();
  }

  /**
   * RFC 9309: a 4xx for robots.txt means "no rules", so everything is allowed; a 5xx or no answer
   * means "assume nothing is allowed" until it can be read. A good answer is kept for a day; a
   * failure only for {@link #ROBOTS_RETRY}, so the site is asked again soon.
   */
  private CachedRobots fetchRobots(String origin) {
    Instant now = clock.instant();
    try {
      waitForTurn(URI.create(origin).getHost());
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(origin + "/robots.txt"))
              .timeout(Duration.ofSeconds(30))
              .header("User-Agent", userAgent)
              .build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      int status = response.statusCode();
      if (status >= 200 && status < 300) {
        return new CachedRobots(
            RobotsRules.parse(response.body(), productToken), now.plus(ROBOTS_TTL));
      }
      if (status >= 400 && status < 500) {
        return new CachedRobots(RobotsRules.allowAll(), now.plus(ROBOTS_TTL));
      }
      log.warn("robots.txt for {} returned {}; treating the site as disallowed", origin, status);
      return new CachedRobots(DISALLOW_ALL, now.plus(ROBOTS_RETRY));
    } catch (IOException e) {
      log.warn(
          "robots.txt for {} could not be read ({}); treating the site as disallowed",
          origin,
          e.toString());
      return new CachedRobots(DISALLOW_ALL, now.plus(ROBOTS_RETRY));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new CachedRobots(DISALLOW_ALL, now.plus(ROBOTS_RETRY));
    }
  }

  private static final RobotsRules DISALLOW_ALL =
      RobotsRules.parse("User-agent: *\nDisallow: /", "pretium");

  private void waitForTurn(String host) throws InterruptedException {
    Instant previous = lastRequest.get(host);
    if (previous != null) {
      Duration since = Duration.between(previous, clock.instant());
      if (since.compareTo(perHostGap) < 0) {
        Thread.sleep(perHostGap.minus(since).toMillis());
      }
    }
    lastRequest.put(host, clock.instant());
  }

  private static void deleteQuietly(Path path) {
    if (path != null) {
      try {
        Files.deleteIfExists(path);
      } catch (IOException ignored) {
        // a leftover temp file is harmless
      }
    }
  }
}
