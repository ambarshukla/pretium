package io.pretium.collector.run;

import io.pretium.collector.raw.RawStore;
import io.pretium.collector.source.SourceCatalog;
import io.pretium.collector.source.SourceSpec;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator endpoints. Internal only: the collector is not exposed through the public proxy.
 *
 * <ul>
 *   <li>{@code GET /sources} — the loaded specs
 *   <li>{@code POST /sources/{id}/runs?force=true} — collect one source now
 *   <li>{@code POST /sources/{id}/replay} — re-extract every stored evidence file of one source
 *   <li>{@code POST /replay} — the same for every source: the rebuild after a lost database
 * </ul>
 */
@RestController
public class CollectorController {

  private final SourceCatalog catalog;
  private final CollectionScheduler scheduler;
  private final RawStore raw;

  public CollectorController(SourceCatalog catalog, CollectionScheduler scheduler, RawStore raw) {
    this.catalog = catalog;
    this.scheduler = scheduler;
    this.raw = raw;
  }

  @GetMapping("/sources")
  public List<SourceSpec> sources() {
    return catalog.all();
  }

  @PostMapping("/sources/{id}/runs")
  public ResponseEntity<Map<String, String>> run(
      @PathVariable String id, @RequestParam(defaultValue = "false") boolean force) {
    return catalog
        .find(id)
        .map(
            spec -> {
              scheduler.submit(spec, force);
              return ResponseEntity.status(HttpStatus.ACCEPTED)
                  .body(Map.of("source", id, "status", "started"));
            })
        .orElseGet(
            () ->
                ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("source", id, "status", "unknown source")));
  }

  /** Answers at once with how many files will be replayed; the replay itself runs on. */
  @PostMapping("/sources/{id}/replay")
  public ResponseEntity<Map<String, Object>> replay(@PathVariable String id) throws IOException {
    SourceSpec spec = catalog.find(id).orElse(null);
    if (spec == null) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND)
          .body(Map.of("source", id, "status", "unknown source"));
    }
    int files = raw.distinctFiles(id).size();
    scheduler.submitReplay(spec);
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(Map.of("source", id, "status", "started", "files", files));
  }

  /** Every source, in catalogue order; each replay is queued on the same two worker threads. */
  @PostMapping("/replay")
  public ResponseEntity<Map<String, Integer>> replayAll() throws IOException {
    Map<String, Integer> files = new LinkedHashMap<>();
    for (SourceSpec spec : catalog.all()) {
      files.put(spec.id(), raw.distinctFiles(spec.id()).size());
      scheduler.submitReplay(spec);
    }
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(files);
  }
}
