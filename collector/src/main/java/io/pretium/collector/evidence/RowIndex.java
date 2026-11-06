package io.pretium.collector.evidence;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.dhatim.fastexcel.reader.Sheet;

/**
 * Finds one row of a large spreadsheet by its id, in milliseconds instead of the half a minute it
 * takes to read the sheet from the top.
 *
 * <p>Built once per file: one pass over the sheet writes every row as a line of tab-separated text
 * to a file next to the evidence ({@code <sha256>.rows.tsv}, about 60 MB for the World Bank file),
 * and remembers where each line starts. In memory are only two sorted arrays of numbers (id and
 * byte offset, about 3 MB for 200,000 rows); a lookup is a binary search and one read from disk.
 *
 * <p>The sidecar is a copy made for reading, not evidence: the proof page always re-checks the
 * original file's hash, and the sidecar can be deleted and rebuilt at any time.
 */
final class RowIndex {

  /** One row: its number in the sheet (1-based) and its cells, in column order. */
  record Found(int number, List<String> cells) {}

  private final Path rows;
  private final List<String> header;
  private final long[] ids;
  private final long[] offsets;

  private RowIndex(Path rows, List<String> header, long[] ids, long[] offsets) {
    this.rows = rows;
    this.header = header;
    this.ids = ids;
    this.offsets = offsets;
  }

  List<String> header() {
    return header;
  }

  int size() {
    return ids.length;
  }

  /** The index for a spreadsheet: loaded from its sidecar, or built first if there is none. */
  static RowIndex of(Path xlsx, String sheetName) throws IOException {
    Path sidecar = xlsx.resolveSibling(name(xlsx) + ".rows.tsv");
    if (!Files.isRegularFile(sidecar)) {
      build(xlsx, sheetName, sidecar);
    }
    return load(sidecar);
  }

  private static String name(Path xlsx) {
    String n = xlsx.getFileName().toString();
    int dot = n.lastIndexOf('.');
    return dot < 0 ? n : n.substring(0, dot);
  }

  /** Line 1: the header. Then one line per row: the row's number, then its cells. */
  static void build(Path xlsx, String sheetName, Path sidecar) throws IOException {
    Path tmp = sidecar.resolveSibling(sidecar.getFileName() + ".tmp");
    try (ReadableWorkbook workbook = new ReadableWorkbook(xlsx.toFile());
        BufferedWriter out = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
      Sheet sheet =
          workbook
              .findSheet(sheetName)
              .orElseThrow(() -> new IOException("sheet '" + sheetName + "' not found"));
      try (Stream<Row> stream = sheet.openStream()) {
        Iterator<Row> it = stream.iterator();
        if (!it.hasNext()) {
          throw new IOException("sheet '" + sheetName + "' is empty");
        }
        Row header = it.next();
        int width = header.getCellCount();
        out.write("row");
        for (int i = 0; i < width; i++) {
          out.write('\t');
          out.write(clean(header.getCellText(i)));
        }
        out.write('\n');
        while (it.hasNext()) {
          Row row = it.next();
          out.write(Integer.toString(row.getRowNum()));
          for (int i = 0; i < width; i++) {
            out.write('\t');
            out.write(clean(i < row.getCellCount() ? row.getCellText(i) : null));
          }
          out.write('\n');
        }
      }
    }
    Files.move(tmp, sidecar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  private static String clean(String s) {
    return s == null ? "" : s.strip().replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
  }

  static RowIndex load(Path sidecar) throws IOException {
    List<String> header;
    long[] ids = new long[1 << 16];
    long[] offsets = new long[1 << 16];
    int n = 0;
    try (InputStream in = new java.io.BufferedInputStream(Files.newInputStream(sidecar), 1 << 16)) {
      ByteArrayOutputStream line = new ByteArrayOutputStream(512);
      long pos = 0;
      long lineStart = 0;
      header = null;
      int idColumn = -1;
      for (int b; (b = in.read()) >= 0; pos++) {
        if (b != '\n') {
          line.write(b);
          continue;
        }
        String text = line.toString(StandardCharsets.UTF_8);
        line.reset();
        String[] parts = text.split("\t", -1);
        if (header == null) {
          header = List.of(Arrays.copyOfRange(parts, 1, parts.length));
          for (int i = 0; i < header.size(); i++) {
            if (header.get(i).equalsIgnoreCase("id")) {
              idColumn = i + 1;
              break;
            }
          }
          if (idColumn < 0) {
            throw new IOException("no id column in " + sidecar.getFileName());
          }
        } else if (idColumn < parts.length) {
          Long id = number(parts[idColumn]);
          if (id != null) {
            if (n == ids.length) {
              ids = Arrays.copyOf(ids, n * 2);
              offsets = Arrays.copyOf(offsets, n * 2);
            }
            ids[n] = id;
            offsets[n] = lineStart;
            n++;
          }
        }
        lineStart = pos + 1;
      }
    }
    if (header == null) {
      throw new IOException("empty index " + sidecar.getFileName());
    }
    // Sort both arrays by id, keeping the pairs together.
    Integer[] order = new Integer[n];
    for (int i = 0; i < n; i++) {
      order[i] = i;
    }
    long[] idsCopy = ids;
    Arrays.sort(order, (a, b) -> Long.compare(idsCopy[a], idsCopy[b]));
    long[] sortedIds = new long[n];
    long[] sortedOffsets = new long[n];
    for (int i = 0; i < n; i++) {
      sortedIds[i] = ids[order[i]];
      sortedOffsets[i] = offsets[order[i]];
    }
    return new RowIndex(sidecar, header, sortedIds, sortedOffsets);
  }

  private static Long number(String s) {
    try {
      return s.isEmpty() ? null : Long.valueOf(s);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  Optional<Found> find(String recordId) throws IOException {
    Long id = number(recordId == null ? "" : recordId.strip());
    if (id == null) {
      return Optional.empty();
    }
    int i = Arrays.binarySearch(ids, id);
    if (i < 0) {
      return Optional.empty();
    }
    try (FileChannel ch = FileChannel.open(rows, StandardOpenOption.READ)) {
      ByteArrayOutputStream line = new ByteArrayOutputStream(1024);
      ByteBuffer buf = ByteBuffer.allocate(4096);
      long pos = offsets[i];
      read:
      while (ch.read(buf, pos) > 0) {
        buf.flip();
        while (buf.hasRemaining()) {
          byte b = buf.get();
          if (b == '\n') {
            break read;
          }
          line.write(b);
        }
        pos += buf.limit();
        buf.clear();
      }
      String[] parts = line.toString(StandardCharsets.UTF_8).split("\t", -1);
      List<String> cells = new ArrayList<>(Arrays.asList(parts).subList(1, parts.length));
      while (cells.size() < header.size()) {
        cells.add("");
      }
      return Optional.of(new Found(Integer.parseInt(parts[0]), List.copyOf(cells)));
    }
  }
}
