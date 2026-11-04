package io.pretium.collector.run;

import io.pretium.collector.source.SourceCatalog;
import io.pretium.collector.source.SourceSpec;
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
 * </ul>
 */
@RestController
public class CollectorController {

  private final SourceCatalog catalog;
  private final CollectionScheduler scheduler;

  public CollectorController(SourceCatalog catalog, CollectionScheduler scheduler) {
    this.catalog = catalog;
    this.scheduler = scheduler;
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
}
