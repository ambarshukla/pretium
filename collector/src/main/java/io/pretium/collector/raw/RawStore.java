package io.pretium.collector.raw;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The raw zone: every response body exactly as it arrived, named by its SHA-256 hash.
 *
 * <p>This is the evidence behind every published number. A value in the API carries the hash of the
 * file it was read from, so "where did this price come from?" is answered by opening one file, and
 * the extraction can be re-run against it at any time.
 *
 * <p>Layout: {@code <root>/<sourceId>/<sha256>.<ext>} plus {@code <root>/<sourceId>/index.jsonl},
 * one line per fetch that produced a body.
 */
@Component
public class RawStore {

  /** One line of a source's index. */
  public record Entry(
      Instant fetchedAt, String url, String sha256, long bytes, String etag, String file) {}

  private final Path root;
  private final JsonMapper json = JsonMapper.builder().build();

  public RawStore(@Value("${pretium.raw-dir}") Path root) throws IOException {
    this.root = Files.createDirectories(root);
  }

  /** The most recent fetch of a source, if there has been one. */
  public synchronized Optional<Entry> latest(String sourceId) throws IOException {
    Path index = root.resolve(sourceId).resolve("index.jsonl");
    if (!Files.exists(index)) {
      return Optional.empty();
    }
    List<String> lines = Files.readAllLines(index, StandardCharsets.UTF_8);
    for (int i = lines.size() - 1; i >= 0; i--) {
      if (!lines.get(i).isBlank()) {
        return Optional.of(json.readValue(lines.get(i), Entry.class));
      }
    }
    return Optional.empty();
  }

  /** Hashes a downloaded file and moves it into the store. Identical content is kept once. */
  public synchronized Entry store(
      String sourceId, Path download, String url, String etag, String extension, Instant fetchedAt)
      throws IOException {
    Path dir = Files.createDirectories(root.resolve(sourceId));
    String sha = sha256(download);
    String fileName = sha + "." + extension;
    Path target = dir.resolve(fileName);
    if (Files.exists(target)) {
      Files.delete(download);
    } else {
      // Not ATOMIC_MOVE: the download sits in the temp directory, which is often a
      // different filesystem from the store, and an atomic move cannot cross one.
      Files.move(download, target, StandardCopyOption.REPLACE_EXISTING);
    }
    Entry entry = new Entry(fetchedAt, url, sha, Files.size(target), etag, fileName);
    Files.writeString(
        dir.resolve("index.jsonl"),
        json.writeValueAsString(entry) + "\n",
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND);
    return entry;
  }

  /** The content and recipe of the last extraction that succeeded. */
  public record Extracted(String sha256, int recipeVersion) {}

  /**
   * Whether this exact content has already been extracted successfully with this exact recipe. Only
   * then is a run "unchanged": content that failed to extract, or that was read by an older recipe,
   * is extracted again.
   */
  public synchronized boolean alreadyExtracted(String sourceId, String sha256, int recipeVersion)
      throws IOException {
    Path marker = root.resolve(sourceId).resolve("extracted.json");
    if (!Files.exists(marker)) {
      return false;
    }
    Extracted last =
        json.readValue(Files.readString(marker, StandardCharsets.UTF_8), Extracted.class);
    return last.equals(new Extracted(sha256, recipeVersion));
  }

  public synchronized void markExtracted(String sourceId, String sha256, int recipeVersion)
      throws IOException {
    Path dir = Files.createDirectories(root.resolve(sourceId));
    Path tmp = dir.resolve("extracted.json.tmp");
    Files.writeString(
        tmp, json.writeValueAsString(new Extracted(sha256, recipeVersion)), StandardCharsets.UTF_8);
    Files.move(
        tmp,
        dir.resolve("extracted.json"),
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE);
  }

  public Path pathOf(String sourceId, Entry entry) {
    return root.resolve(sourceId).resolve(entry.file());
  }

  static String sha256(Path file) throws IOException {
    try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest())) {
      MessageDigest md = ((DigestInputStream) in).getMessageDigest();
      in.transferTo(java.io.OutputStream.nullOutputStream());
      return HexFormat.of().formatHex(md.digest());
    }
  }

  private static MessageDigest digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("every JVM has SHA-256", e);
    }
  }
}
