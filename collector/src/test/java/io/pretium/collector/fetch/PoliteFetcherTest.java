package io.pretium.collector.fetch;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The fetcher against a small HTTP server in the test: what it sends, how it classifies answers,
 * and how long it remembers a robots.txt it could not read.
 */
class PoliteFetcherTest {

  /** A clock the test moves by hand, so "15 minutes later" takes no time. */
  static final class TestClock extends Clock {
    Instant now = Instant.parse("2026-11-09T16:19:00Z");

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  HttpServer server;
  String origin;
  String otherOrigin;
  final TestClock clock = new TestClock();

  /** What /robots.txt answers when asked as the other host ("localhost"). */
  final AtomicReference<String> otherHostRobotsBody =
      new AtomicReference<>("User-agent: *\nAllow: /\n");

  /** What /robots.txt answers: the status, and the body for a 200. */
  final AtomicInteger robotsStatus = new AtomicInteger(200);

  final AtomicReference<String> robotsBody = new AtomicReference<>("User-agent: *\nAllow: /\n");
  final AtomicReference<String> lastUserAgent = new AtomicReference<>();
  final AtomicReference<String> lastIfNoneMatch = new AtomicReference<>();

  @BeforeEach
  void start() throws IOException {
    // Every interface, so the same server answers as "127.0.0.1" and as "localhost": two hosts
    // to the fetcher, which is what the redirect tests need.
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/robots.txt",
        ex -> {
          int status = robotsStatus.get();
          String rules =
              ex.getRequestHeaders().getFirst("Host").startsWith("localhost")
                  ? otherHostRobotsBody.get()
                  : robotsBody.get();
          byte[] body = status == 200 ? rules.getBytes(StandardCharsets.UTF_8) : new byte[0];
          ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
          try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
          }
        });
    server.createContext(
        "/rates.html",
        ex -> {
          lastUserAgent.set(ex.getRequestHeaders().getFirst("User-Agent"));
          lastIfNoneMatch.set(ex.getRequestHeaders().getFirst("If-None-Match"));
          if ("\"v1\"".equals(lastIfNoneMatch.get())) {
            ex.sendResponseHeaders(304, -1);
            ex.close();
            return;
          }
          byte[] body = "<html>rates</html>".getBytes(StandardCharsets.UTF_8);
          ex.getResponseHeaders().add("ETag", "\"v1\"");
          ex.sendResponseHeaders(200, body.length);
          try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
          }
        });
    server.createContext(
        "/private.html",
        ex -> {
          ex.sendResponseHeaders(403, -1);
          ex.close();
        });
    server.createContext(
        "/moved",
        ex -> {
          ex.getResponseHeaders().add("Location", "/rates.html");
          ex.sendResponseHeaders(302, -1);
          ex.close();
        });
    server.createContext(
        "/elsewhere",
        ex -> {
          ex.getResponseHeaders().add("Location", otherOrigin + "/rates.html");
          ex.sendResponseHeaders(301, -1);
          ex.close();
        });
    server.createContext(
        "/loop",
        ex -> {
          ex.getResponseHeaders().add("Location", "/loop");
          ex.sendResponseHeaders(302, -1);
          ex.close();
        });
    server.start();
    origin = "http://127.0.0.1:" + server.getAddress().getPort();
    otherOrigin = "http://localhost:" + server.getAddress().getPort();
  }

  /** A redirect within the host is followed; the body comes from where it was sent on to. */
  @Test
  void aRedirectIsFollowed() throws IOException {
    FetchResult moved = fetcher().fetch(origin + "/moved", Optional.empty());
    assertThat(moved.outcome()).isEqualTo(FetchResult.Outcome.OK);
    assertThat(Files.readString(moved.body())).isEqualTo("<html>rates</html>");
    Files.deleteIfExists(moved.body());

    FetchResult loop = fetcher().fetch(origin + "/loop", Optional.empty());
    assertThat(loop.outcome()).isEqualTo(FetchResult.Outcome.FAILED);
    assertThat(loop.message()).isEqualTo("too many redirects");
  }

  /**
   * A redirect to another host is a request to that host, so its robots.txt decides. Before, the
   * HTTP client followed it on its own and the second host was never asked.
   */
  @Test
  void aRedirectToAnotherHostObeysThatHostsRobots() throws IOException {
    otherHostRobotsBody.set("User-agent: *\nDisallow: /rates\n");
    FetchResult refused = fetcher().fetch(origin + "/elsewhere", Optional.empty());
    assertThat(refused.outcome()).isEqualTo(FetchResult.Outcome.BLOCKED);
    assertThat(refused.message()).contains("redirected to").contains("robots.txt");

    otherHostRobotsBody.set("User-agent: *\nAllow: /\n");
    FetchResult allowed = fetcher().fetch(origin + "/elsewhere", Optional.of("\"v1\""));
    assertThat(allowed.outcome()).isEqualTo(FetchResult.Outcome.OK);
    assertThat(lastIfNoneMatch.get())
        .as("the first URL's ETag is not offered to the address we were sent on to")
        .isNull();
    Files.deleteIfExists(allowed.body());
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  PoliteFetcher fetcher() {
    return new PoliteFetcher(
        HttpClient.newHttpClient(), "pretium/0.1 (+https://example.test)", Duration.ZERO, clock);
  }

  @Test
  void namesItselfAndAsksConditionally() throws IOException {
    PoliteFetcher fetcher = fetcher();

    FetchResult first = fetcher.fetch(origin + "/rates.html", Optional.empty());
    assertThat(first.outcome()).isEqualTo(FetchResult.Outcome.OK);
    assertThat(first.etag()).isEqualTo("\"v1\"");
    assertThat(Files.readString(first.body())).isEqualTo("<html>rates</html>");
    assertThat(lastUserAgent.get()).startsWith("pretium/0.1");
    Files.deleteIfExists(first.body());

    FetchResult second = fetcher.fetch(origin + "/rates.html", Optional.of("\"v1\""));
    assertThat(second.outcome()).isEqualTo(FetchResult.Outcome.NOT_MODIFIED);
    assertThat(second.body()).isNull();
    assertThat(lastIfNoneMatch.get()).isEqualTo("\"v1\"");
  }

  @Test
  void aRefusalIsBlockedAndRobotsRulesAreObeyed() {
    PoliteFetcher fetcher = fetcher();

    FetchResult refused = fetcher.fetch(origin + "/private.html", Optional.empty());
    assertThat(refused.outcome()).isEqualTo(FetchResult.Outcome.BLOCKED);
    assertThat(refused.httpStatus()).isEqualTo(403);

    robotsBody.set("User-agent: *\nDisallow: /rates\n");
    FetchResult disallowed = fetcher().fetch(origin + "/rates.html", Optional.empty());
    assertThat(disallowed.outcome()).isEqualTo(FetchResult.Outcome.BLOCKED);
    assertThat(disallowed.message()).contains("robots.txt");
  }

  /**
   * A robots.txt that cannot be read means "fetch nothing" (RFC 9309), but only for a short while:
   * a server blip must not block a site for the whole day a good answer is cached for.
   */
  @Test
  void aFailedRobotsLookupIsRetriedSoonNotTomorrow() throws IOException {
    robotsStatus.set(503);
    PoliteFetcher fetcher = fetcher();

    assertThat(fetcher.fetch(origin + "/rates.html", Optional.empty()).outcome())
        .isEqualTo(FetchResult.Outcome.BLOCKED);

    robotsStatus.set(200);
    clock.now = clock.now.plus(Duration.ofMinutes(5));
    assertThat(fetcher.fetch(origin + "/rates.html", Optional.empty()).outcome())
        .as("the failed lookup is still remembered five minutes on")
        .isEqualTo(FetchResult.Outcome.BLOCKED);

    clock.now = clock.now.plus(PoliteFetcher.ROBOTS_RETRY);
    FetchResult after = fetcher.fetch(origin + "/rates.html", Optional.empty());
    assertThat(after.outcome())
        .as("once the retry period has passed, robots.txt is read again")
        .isEqualTo(FetchResult.Outcome.OK);
    Files.deleteIfExists(after.body());
  }
}
