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
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
    ReentrantLock lock = lock(spec);
    try {
      return report(spec, attempt(spec, force));
    } finally {
      lock.unlock();
    }
  }

  /**
   * Re-extracts every file ever stored for a source with the recipe in use now, oldest first, and
   * publishes what each one yields, with one run report per file.
   *
   * <p>This is the recovery path. The raw store is the only copy of the evidence; the database is a
   * view of it that the checks derive, and Kafka keeps ninety days. When either of those is lost,
   * or a recipe has changed and old files should be read with it, this rebuilds the view from the
   * files. Oldest first, so that a value a publisher later corrected ends with the correction, as
   * it did the first time. Publishing is idempotent downstream (the catalog upserts by natural
   * key), so a replay of what is already there changes nothing.
   *
   * <p>Holds the source's lock throughout: a scheduled run that falls due meanwhile is refused, not
   * queued, and runs again at its next time.
   */
  public List<CollectionRun> replay(SourceSpec spec) {
    ReentrantLock lock = lock(spec);
    try {
      List<RawStore.Entry> files;
      try {
        files = raw.distinctFiles(spec.id());
      } catch (IOException e) {
        return List.of(
            report(
                spec,
                run(
                    UUID.randomUUID().toString(),
                    spec,
                    spec.url(),
                    clock.instant(),
                    Status.FETCH_FAILED,
                    0,
                    0,
                    null,
                    0,
                    "replay: raw store unreadable: " + e)));
      }
      log.info("{}: replaying {} evidence files", spec.id(), files.size());
      List<CollectionRun> runs = new ArrayList<>(files.size());
      int i = 0;
      for (RawStore.Entry evidence : files) {
        i++;
        String note = "replay " + i + " of " + files.size();
        String runId = UUID.randomUUID().toString();
        Instant started = clock.instant();
        CollectionRun run =
            Files.exists(raw.pathOf(spec.id(), evidence))
                ? extractAndPublish(runId, spec, evidence.url(), started, 0, evidence, true, note)
                : run(
                    runId,
                    spec,
                    evidence.url(),
                    started,
                    Status.EXTRACTION_FAILED,
                    0,
                    evidence.bytes(),
                    evidence.sha256(),
                    0,
                    note + ": evidence file " + evidence.file() + " is missing from the store");
        runs.add(report(spec, run));
      }
      return List.copyOf(runs);
    } finally {
      lock.unlock();
    }
  }

  private ReentrantLock lock(SourceSpec spec) {
    ReentrantLock lock = locks.computeIfAbsent(spec.id(), id -> new ReentrantLock());
    if (!lock.tryLock()) {
      throw new IllegalStateException(spec.id() + " is already being collected");
    }
    return lock;
  }

  /** Publishes the run report after everything it describes, counts the run, and logs it. */
  private CollectionRun report(SourceSpec spec, CollectionRun run) {
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
    // An ETag names one resource's version, so it is only offered back for the same URL: after the
    // first run (the history endpoint) or when a catalogue names a new file, it is not sent.
    Optional<String> etag =
        force
            ? Optional.empty()
            : previous.filter(e -> url.equals(e.url())).map(RawStore.Entry::etag);
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
      case NOT_MODIFIED -> {
        if (previous.isEmpty()) {
          // A 304 to a request that offered no ETag: the server's fault, but still a reported run.
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
              "304 Not Modified, but there is no stored copy to reuse");
        }
        evidence = previous.get();
      }
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

    return extractAndPublish(
        runId, spec, url, started, fetched.httpStatus(), evidence, force, null);
  }

  /**
   * The second half of a run, from a stored evidence file onwards: skip it if this recipe has read
   * it before (unless forced), extract, publish, mark it read.
   *
   * @param note for a replay, which file of how many; prefixed to the message, null otherwise
   */
  private CollectionRun extractAndPublish(
      String runId,
      SourceSpec spec,
      String url,
      Instant started,
      int http,
      RawStore.Entry evidence,
      boolean force,
      String note) {
    Extractor extractor = extractors.get(spec.format());
    try {
      if (!force && raw.alreadyExtracted(spec.id(), evidence.sha256(), spec.recipeVersion())) {
        return run(
            runId,
            spec,
            url,
            started,
            Status.UNCHANGED,
            http,
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
            http,
            evidence.bytes(),
            evidence.sha256(),
            0,
            noted(
                note,
                e instanceof ExtractionException ? e.getMessage() : "unexpected error: " + e));
      }
      spool.replay(sink::send);
      String sendError = sink.finish();
      if (sendError != null) {
        return run(
            runId,
            spec,
            url,
            started,
            Status.PUBLISH_FAILED,
            http,
            evidence.bytes(),
            evidence.sha256(),
            count,
            noted(note, sendError));
      }
      String message = note;
      try {
        raw.markExtracted(spec.id(), evidence.sha256(), spec.recipeVersion());
      } catch (IOException e) {
        // Everything was published, so this run succeeded; only the "already read" marker is
        // missing, which means the next run reads the same file again (harmless: upserts).
        log.warn("{}: extraction marker not written: {}", spec.id(), e.toString());
        message = noted(note, "published, but the extraction marker could not be written: " + e);
      }
      return run(
          runId,
          spec,
          url,
          started,
          Status.SUCCEEDED,
          http,
          evidence.bytes(),
          evidence.sha256(),
          count,
          message);
    } catch (IOException e) {
      // The spool (a temporary file) could not be written or read back: nothing was published.
      sink.finish();
      return run(
          runId,
          spec,
          url,
          started,
          Status.EXTRACTION_FAILED,
          http,
          evidence.bytes(),
          evidence.sha256(),
          0,
          noted(note, "could not hold the observations on disk: " + e));
    }
  }

  private static String noted(String note, String message) {
    return note == null ? message : note + ": " + message;
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
