package io.pretium.collector.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Finding one row of the World Bank file by its id. The fixture is the index's own format: the
 * header and three real rows of the Q3 2025 file, the first of them the State Bank of India's price
 * from the United Kingdom to India, out of order on purpose (the index sorts by id).
 */
class RowIndexTest {

  @TempDir Path tmp;

  @Test
  void aRowIsFoundByItsIdWithItsNumberInTheSheet() throws Exception {
    Path rows = tmp.resolve("sample.rows.tsv");
    Files.copy(
        Path.of(
            RowIndexTest.class.getResource("/fixtures/wb-rpw-2025q3-three-rows.rows.tsv").toURI()),
        rows);

    RowIndex index = RowIndex.load(rows);

    assertThat(index.size()).isEqualTo(3);
    assertThat(index.header()).startsWith("id", "period", "source_code");
    RowIndex.Found found = index.find("1212252876").orElseThrow();
    assertThat(found.number()).isEqualTo(200876);
    assertThat(found.cells()).hasSameSizeAs(index.header());
    int firm = index.header().indexOf("firm");
    int total = index.header().indexOf("cc1 total cost %");
    assertThat(found.cells().get(firm)).isEqualTo("State Bank of India");
    assertThat(found.cells().get(total)).isEqualTo("0.15");
    // The row stored first in the file, last by id.
    assertThat(index.find("620160001").orElseThrow().number()).isEqualTo(2);

    assertThat(index.find("1212252878")).isEmpty();
    assertThat(index.find("not a number")).isEmpty();
  }
}
