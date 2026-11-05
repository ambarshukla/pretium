package io.pretium.collector.run;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.pretium.collector.extract.ExtractionException;
import io.pretium.collector.extract.Extractor;
import io.pretium.collector.fetch.FetchResult;
import io.pretium.collector.fetch.PoliteFetcher;
import io.pretium.collector.raw.RawStore;
import io.pretium.collector.source.SourceSpec;
import io.pretium.events.CollectionRun;
import io.pretium.events.CollectionRun.Status;
import io.pretium.events.Topics;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * One collection run, start to finish: fetch, keep the evidence, extract, publish, report.
 *
 * <p>Whatever happens, exactly one {@link CollectionRun} is published at the end. It goes out after
 * the observations it describes, so a consumer that has seen the run has also been sent everything
 * the run produced. A run that fails during extraction publishes no observations at all.
 */
@Service
public class CollectionService {

  private static final Logger log = LoggerFactory.getLogger(CollectionService.class);

  private final PoliteFetcher fetcher;
  private final RawStore raw;
  private final Map<SourceSpec.Format, Extractor> extractors =
      new EnumMap<>(SourceSpec.Format.class);
  private final KafkaTemplate<String, String> kafka;
  private final JsonMapper json;
  private final Clock clock;
  private final MeterRegistry meters;
  private final UrlDiscovery discovery;
  private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

  public CollectionService(
      PoliteFetcher fetcher,
      RawStore raw,
      List<Extractor> extractors,
      KafkaTemplate<String, String> kafka,
      JsonMapper json,
      MeterRegistry meters,
      UrlDiscovery discovery) {
    this.meters = meters;
    this.discovery = discovery;
    this.fetcher = fetcher;
    this.raw = raw;
    extractors.forEach(e -> this.extractors.put(e.format(), e));
    this.kafka = kafka;
    this.json = json;
    this.clock = Clock.systemUTC();
  }

  /**
   * Collects one source.
   *
   * @param force re-download and re-extract even if the source is unchanged
   */
  public CollectionRun collect(SourceSpec spec, boolean force) {
    ReentrantLock lock = locks.computeIfAbsent(spec.id(), id -> new ReentrantLock());
    if (!lock.tryLock()) {
      throw new IllegalStateException(spec.id() + " is already being collected");
    }
    try {
      CollectionRun run = attempt(spec, force);
      kafka.send(Topics.COLLECTION_RUNS, spec.id(), json.writeValueAsString(run));
      kafka.flush();
      record(spec, run);
      log.info(
          "{}: {} (http {}, {} bytes, {} observations){}",
          spec.id(),
          run.status(),
          run.httpStatus(),
          run.bytes(),
          run.extracted(),
          run.message() == null ? "" : " - " + run.message());
      return run;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Creates a source's meters at zero before its first run. Prometheus computes "how many in the
   * last hour" as the difference between two samples; a counter that first appears already at 1 has
   * no earlier sample, so that first run would be invisible. With weekly sources and a restart on
   * every deploy, that would be most runs.
   */
  public void registerMeters(SourceSpec spec) {
    for (Status status : Status.values()) {
      meters.counter("pretium.collection.runs", "source", spec.id(), "status", status.name());
    }
    meters.counter("pretium.collection.observations", "source", spec.id());
    Timer.builder("pretium.collection.duration").tag("source", spec.id()).register(meters);
  }

  /**
   * Counts the run by outcome, adds what it extracted, and times it. The tag values are small fixed
   * sets (four sources, five statuses), so the number of time series stays bounded.
   */
  private void record(SourceSpec spec, CollectionRun run) {
    meters
        .counter("pretium.collection.runs", "source", spec.id(), "status", run.status().name())
        .increment();
    meters
        .counter("pretium.collection.observations", "source", spec.id())
        .increment(run.extracted());
    Timer.builder("pretium.collection.duration")
        .tag("source", spec.id())
        .register(meters)
        .record(Duration.between(run.startedAt(), run.finishedAt()));
  }

  private CollectionRun attempt(SourceSpec spec, boolean force) {
    String runId = UUID.randomUUID().toString();
    Instant started = clock.instant();
    Extractor extractor = extractors.get(spec.format());
    Optional<RawStore.Entry> previous;
    try {
      previous = raw.latest(spec.id());
    } catch (IOException e) {
      return run(
          runId,
          spec,
          spec.url(),
          started,
          Status.FETCH_FAILED,
          0,
          0,
          null,
          0,
          "raw store unreadable: " + e);
    }

    // The first ever run of a source with a history endpoint fetches the history.
    String url =
        previous.isEmpty() && spec.backfillUrl() != null
            ? spec.backfillUrl()
            : discovery.urlFor(spec);
    Optional<String> etag = force ? Optional.empty() : previous.map(RawStore.Entry::etag);
    FetchResult fetched = fetcher.fetch(url, etag);

    RawStore.Entry evidence;
    switch (fetched.outcome()) {
      case BLOCKED -> {
        return run(
            runId,
            spec,
            url,
            started,
            Status.BLOCKED,
            fetched.httpStatus(),
            0,
            null,
            0,
            fetched.message());
      }
      case FAILED -> {
        return run(
            runId,
            spec,
            url,
            started,
            Status.FETCH_FAILED,
            fetched.httpStatus(),
            0,
            null,
            0,
            fetched.message());
      }
      case NOT_MODIFIED -> evidence = previous.orElseThrow();
      case OK -> {
        try {
          evidence =
              raw.store(
                  spec.id(),
                  fetched.body(),
                  url,
                  fetched.etag(),
                  extractor.extension(),
                  clock.instant());
        } catch (IOException e) {
          return run(
              runId,
              spec,
              url,
              started,
              Status.FETCH_FAILED,
              fetched.httpStatus(),
              0,
              null,
              0,
              "could not store body: " + e);
        }
      }
      default -> throw new IllegalStateException("unknown outcome " + fetched.outcome());
    }

    try {
      if (!force && raw.alreadyExtracted(spec.id(), evidence.sha256(), spec.recipeVersion())) {
        return run(
            runId,
            spec,
            url,
            started,
            Status.UNCHANGED,
            fetched.httpStatus(),
            evidence.bytes(),
            evidence.sha256(),
            0,
            "content already extracted with recipe v" + spec.recipeVersion());
      }
    } catch (IOException e) {
      log.warn("{}: extraction marker unreadable, extracting again: {}", spec.id(), e.toString());
    }

    // Nothing reaches Kafka until the extractor has read the whole file without error: a page that
    // fails on row 80 must not publish rows 1 to 79 as if they were the page.
    KafkaObservationSink sink = new KafkaObservationSink(kafka, json);
    try (SpoolingObservationSink spool = new SpoolingObservationSink(json)) {
      int count;
      try {
        count =
            extractor.extract(
                spec,
                raw.pathOf(spec.id(), evidence),
                new Extractor.Context(runId, evidence.sha256()),
                spool);
      } catch (ExtractionException | RuntimeException e) {
        // RuntimeException too: a malformed selector in a recipe makes jsoup throw an unchecked
        // exception, and that must still end as a reported failed run, not a missing one.
        log.warn("{}: extraction failed, discarding {} held observations", spec.id(), spool.held());
        return run(
            runId,
            spec,
            url,
            started,
            Status.EXTRACTION_FAILED,
            fetched.httpStatus(),
            evidence.bytes(),
            evidence.sha256(),
            0,
            e instanceof ExtractionException ? e.getMessage() : "unexpected error: " + e);
      }
      spool.replay(sink::send);
      String sendError = sink.finish();
      if (sendError != null) {
        return run(
            runId,
            spec,
            url,
            started,
            Status.FETCH_FAILED,
            fetched.httpStatus(),
            evidence.bytes(),
            evidence.sha256(),
            count,
            sendError);
      }
      raw.markExtracted(spec.id(), evidence.sha256(), spec.recipeVersion());
      return run(
          runId,
          spec,
          url,
          started,
          Status.SUCCEEDED,
          fetched.httpStatus(),
          evidence.bytes(),
          evidence.sha256(),
          count,
          null);
    } catch (IOException e) {
      sink.finish();
      return run(
          runId,
          spec,
          url,
          started,
          Status.FETCH_FAILED,
          fetched.httpStatus(),
          evidence.bytes(),
          evidence.sha256(),
          0,
          "could not record extraction: " + e);
    }
  }

  private CollectionRun run(
      String runId,
      SourceSpec spec,
      String url,
      Instant started,
      Status status,
      int http,
      long bytes,
      String sha,
      int extracted,
      String message) {
    return new CollectionRun(
        runId,
        spec.id(),
        url,
        started,
        clock.instant(),
        status,
        http,
        bytes,
        sha,
        spec.recipeVersion(),
        extracted,
        message);
  }
}
