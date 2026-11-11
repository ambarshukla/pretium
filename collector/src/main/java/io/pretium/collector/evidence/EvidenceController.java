package io.pretium.collector.evidence;

import io.pretium.collector.raw.RawStore;
import io.pretium.collector.source.SourceCatalog;
import io.pretium.collector.source.SourceSpec;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Price proof: for a number the site shows, the record it was read from, taken from the stored file
 * again, and the file checked against the hash it was published with.
 *
 * <p>{@code GET /api/v1/evidence/{source}/{sha256}/records/{recordId}}. For the World Bank
 * spreadsheet that is the row whose {@code id} column is the record id, with every column as the
 * file has it, found through a {@link RowIndex}.
 */
@RestController
public class EvidenceController {

  private static final Pattern SHA = Pattern.compile("[0-9a-f]{64}");
  private static final int KEEP = 500;

  /** One cell: the column's header as the file writes it, and the cell's text. */
  public record Cell(String column, String value) {}

  /**
   * The proof for one record.
   *
   * @param hashMatches whether the stored file still hashes to the sha256 the number carries
   * @param row the row's number in the sheet, 1-based as a spreadsheet shows it
   */
  public record Proof(
      String source,
      String sourceName,
      String url,
      String fetchedAt,
      String sha256,
      long bytes,
      boolean hashMatches,
      String sheet,
      String recordId,
      int row,
      List<Cell> cells,
      long millis) {}

  private final RawStore raw;
  private final SourceCatalog sources;
  private final Map<String, RowIndex> indexes = new java.util.concurrent.ConcurrentHashMap<>();
  private final Map<String, Boolean> hashChecked = new java.util.concurrent.ConcurrentHashMap<>();

  public EvidenceController(RawStore raw, SourceCatalog sources) {
    this.raw = raw;
    this.sources = sources;
  }

  /**
   * After start-up, prepares the index for the latest file of each spreadsheet source, so the first
   * person to ask for a proof does not wait half a minute. In the background: the collector is
   * serving meanwhile.
   */
  @org.springframework.context.event.EventListener(
      org.springframework.boot.context.event.ApplicationReadyEvent.class)
  public void warmUp() {
    Thread.ofVirtual()
        .name("evidence-index")
        .start(
            () -> {
              for (SourceSpec spec : sources.all()) {
                if (spec.format() != SourceSpec.Format.RPW_XLSX) {
                  continue;
                }
                try {
                  raw.latest(spec.id()).ifPresent(e -> index(spec, e));
                } catch (IOException | RuntimeException e) {
                  log.warn("evidence index for {} not prepared: {}", spec.id(), e.getMessage());
                }
              }
            });
  }

  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(EvidenceController.class);

  @GetMapping("/api/v1/evidence/{source}/{sha256}/records/{recordId}")
  public Proof record(
      @PathVariable String source, @PathVariable String sha256, @PathVariable String recordId)
      throws IOException {
    long start = System.nanoTime();
    SourceSpec spec =
        sources
            .find(source)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such source"));
    if (!SHA.matcher(sha256).matches()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "not a sha256");
    }
    if (spec.format() != SourceSpec.Format.RPW_XLSX) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "record lookup is only available for the World Bank file so far");
    }
    RawStore.Entry entry =
        raw.entries(source).stream()
            .filter(e -> e.sha256().equals(sha256))
            .findFirst()
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "this server does not hold that file"));
    Path file = raw.pathOf(source, entry);
    boolean matches = hashChecked.computeIfAbsent(sha256, s -> sha256(file).equals(s));
    RowIndex index = index(spec, entry);
    RowIndex.Found row =
        index
            .find(recordId)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "no row with id " + recordId + " in that file"));
    List<Cell> cells = new ArrayList<>();
    for (int i = 0; i < index.header().size(); i++) {
      cells.add(new Cell(index.header().get(i), row.cells().get(i)));
    }
    return new Proof(
        source,
        spec.name(),
        entry.url(),
        entry.fetchedAt().toString(),
        sha256,
        entry.bytes(),
        matches,
        sheet(spec),
        recordId,
        row.number(),
        List.copyOf(cells),
        (System.nanoTime() - start) / 1_000_000);
  }

  private static String sheet(SourceSpec spec) {
    return spec.recipe() == null
        ? "Dataset (from Q2 2016)"
        : spec.recipe().get("sheet", "Dataset (from Q2 2016)");
  }

  /** One index per file, built at most once even if several requests ask at the same time. */
  private RowIndex index(SourceSpec spec, RawStore.Entry entry) {
    return indexes.computeIfAbsent(
        entry.sha256(),
        sha -> {
          try {
            long t = System.nanoTime();
            RowIndex i = RowIndex.of(raw.pathOf(spec.id(), entry), sheet(spec));
            log.info(
                "evidence index for {} {}: {} rows in {} ms",
                spec.id(),
                sha.substring(0, 12),
                i.size(),
                (System.nanoTime() - t) / 1_000_000);
            return i;
          } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
          }
        });
  }

  /** The file's hash now, to compare with the name it was stored under; never throws. */
  private static String sha256(Path file) {
    try {
      return RawStore.sha256(file);
    } catch (IOException e) {
      return "unreadable: " + e.getMessage();
    }
  }
}
