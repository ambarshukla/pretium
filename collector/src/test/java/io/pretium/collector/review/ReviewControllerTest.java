package io.pretium.collector.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pretium.collector.extract.HtmlRateTableExtractor;
import io.pretium.collector.raw.RawStore;
import io.pretium.collector.repair.Casebook;
import io.pretium.collector.source.SourceCatalog;
import io.pretium.collector.source.SourceSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Review decisions and lab runs against a real case from the casebook: the ECB page as archived on
 * 15 April 2019, a copy that ends in the middle of the rates table (13 of 36 currencies). No recipe
 * can read it exactly, the model's could not, and neither can a person's.
 */
class ReviewControllerTest {

  static final String SOURCE = "ecb-reference-html";
  static final String TRUNCATED = "20190415031708";
  static final String KEY = "a-review-key";

  @TempDir Path tmp;

  final JsonMapper json = JsonMapper.builder().build();
  SourceCatalog sources;
  RepairCases cases;
  Lab lab;
  Path reviewDir;

  static Path fixture(String name) throws Exception {
    return Path.of(ReviewControllerTest.class.getResource("/fixtures/" + name).toURI());
  }

  @BeforeEach
  void setUp() throws Exception {
    Path raw = tmp.resolve("raw");
    Files.createDirectories(raw.resolve("ecb-sdmx-exr"));
    Files.copy(
        fixture("ecb-sdmx-2019-04-12.csv"), raw.resolve("ecb-sdmx-exr").resolve("history.csv"));
    Path archive = Files.createDirectories(tmp.resolve("archive"));
    Files.copy(
        fixture("ecb-reference-2019-04-12-archived-truncated.html"),
        archive.resolve(TRUNCATED + ".html"));
    reviewDir = tmp.resolve("review");

    sources = new SourceCatalog();
    cases = new RepairCases(sources, json);
    lab = new Lab(new RawStore(raw), json, raw, archive.toString(), "");
  }

  ReviewController controller(String key) throws Exception {
    return new ReviewController(cases, new DecisionLog(reviewDir, json), lab, sources, key);
  }

  static int status(ResponseStatusException e) {
    return e.getStatusCode().value();
  }

  @Test
  void theCasebookShipsWithTheCollector() {
    assertThat(cases.casebook(SOURCE)).isPresent();
    Casebook.Case c = cases.find(SOURCE, TRUNCATED).orElseThrow();

    assertThat(c.outcome()).isEqualTo("HUMAN_NEEDED");
    assertThat(c.ratesDate()).isEqualTo("2019-04-12");
    assertThat(c.after()).isNull();
    assertThat(c.proposals()).hasSize(2).allSatisfy(p -> assertThat(p.accepted()).isFalse());
    // Only recipes the verifier accepted on some real page are offered as known recipes.
    assertThat(cases.knownRecipes(SOURCE))
        .extracting(SourceSpec.Recipe::version)
        .containsExactly(1, 2, 3);
  }

  @Test
  void aRecipeWrittenByAPersonIsHeldToTheSameRuleAsTheModels() throws Exception {
    ReviewController controller = controller(KEY);
    // The recipe that read this layout on every other page of the time.
    Map<String, String> v2 = cases.find(SOURCE, TRUNCATED).orElseThrow().before().recipe();

    assertThatThrownBy(
            () ->
                controller.decide(
                    SOURCE,
                    TRUNCATED,
                    KEY,
                    new ReviewController.DecisionRequest(
                        DecisionLog.Action.NEW_RECIPE, v2, "this worked on the month before")))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            e -> {
              assertThat(status(e)).isEqualTo(422);
              assertThat(e.getReason()).contains("read 13 of 36");
            });
    assertThat(new DecisionLog(reviewDir, json).all()).isEmpty();
  }

  @Test
  void aBrokenPageIsDismissedWithAReasonAndTheDecisionIsKept() throws Exception {
    ReviewController controller = controller(KEY);
    ReviewController.DecisionRequest noReason =
        new ReviewController.DecisionRequest(DecisionLog.Action.DISMISS_PAGE, null, " ");

    assertThatThrownBy(() -> controller.decide(SOURCE, TRUNCATED, KEY, noReason))
        .isInstanceOfSatisfying(
            ResponseStatusException.class, e -> assertThat(status(e)).isEqualTo(400));

    controller.decide(
        SOURCE,
        TRUNCATED,
        KEY,
        new ReviewController.DecisionRequest(
            DecisionLog.Action.DISMISS_PAGE, null, "archived copy ends inside the table"));

    // A new log over the same folder: the decision is on disk, not in memory.
    assertThat(new DecisionLog(reviewDir, json).all())
        .singleElement()
        .satisfies(
            d -> {
              assertThat(d.action()).isEqualTo(DecisionLog.Action.DISMISS_PAGE);
              assertThat(d.note()).isEqualTo("archived copy ends inside the table");
            });
    assertThat(controller.list().cases())
        .filteredOn(s -> s.snapshot().equals(TRUNCATED))
        .singleElement()
        .satisfies(
            s -> assertThat(s.decision().action()).isEqualTo(DecisionLog.Action.DISMISS_PAGE));
    assertThat(controller.detail(SOURCE, TRUNCATED).decisions()).hasSize(1);
  }

  @Test
  void thereIsNoRepairToConfirmWhenNothingReadThePage() throws Exception {
    assertThatThrownBy(
            () ->
                controller(KEY)
                    .decide(
                        SOURCE,
                        TRUNCATED,
                        KEY,
                        new ReviewController.DecisionRequest(
                            DecisionLog.Action.CONFIRM_REPAIR, null, "")))
        .isInstanceOfSatisfying(
            ResponseStatusException.class, e -> assertThat(status(e)).isEqualTo(400));
  }

  @Test
  void withoutTheRightKeyNothingIsWrittenAndNoModelRuns() throws Exception {
    ReviewController.DecisionRequest dismiss =
        new ReviewController.DecisionRequest(DecisionLog.Action.DISMISS_PAGE, null, "broken");

    assertThatThrownBy(() -> controller(KEY).decide(SOURCE, TRUNCATED, "wrong", dismiss))
        .isInstanceOfSatisfying(
            ResponseStatusException.class, e -> assertThat(status(e)).isEqualTo(401));
    assertThatThrownBy(() -> controller("").decide(SOURCE, TRUNCATED, KEY, dismiss))
        .isInstanceOfSatisfying(
            ResponseStatusException.class, e -> assertThat(status(e)).isEqualTo(403));
    assertThatThrownBy(
            () ->
                controller(KEY)
                    .run(
                        null,
                        new ReviewController.RunRequest(
                            "archive:" + TRUNCATED, null, "qwen2.5-coder:7b", null)))
        .isInstanceOfSatisfying(
            ResponseStatusException.class, e -> assertThat(status(e)).isEqualTo(401));
    assertThat(new DecisionLog(reviewDir, json).all()).isEmpty();
  }

  @Test
  void aKnownRecipeRunsInTheLabWithoutAKeyAndShowsEveryCurrency() throws Exception {
    Lab.Result r =
        controller(KEY)
            .run(null, new ReviewController.RunRequest("archive:" + TRUNCATED, 2, null, null));

    assertThat(r.accepted()).isFalse();
    assertThat(r.ratesDate()).isEqualTo("2019-04-12");
    assertThat(r.matched()).isEqualTo(13);
    assertThat(r.expected()).isEqualTo(36);
    // All 36 reference currencies are listed: 13 read exactly, the rest not read at all.
    assertThat(r.readings()).hasSize(36);
    assertThat(r.readings().stream().filter(x -> x.read() == null)).hasSize(23);
    assertThat(r.readings().stream().filter(x -> x.read() != null))
        .allSatisfy(x -> assertThat(x.matches()).isTrue());
  }

  @Test
  void aRunShowsEveryStepOfTheExtraction() throws Exception {
    Lab.Result r =
        controller(KEY)
            .run(null, new ReviewController.RunRequest("archive:" + TRUNCATED, 2, null, null));

    HtmlRateTableExtractor.Trace t = r.trace();
    assertThat(t.error()).isNull();
    assertThat(t.dateStep().elementText()).contains("12 April 2019");
    assertThat(t.date()).hasToString("2019-04-12");
    // The archived copy ends inside the 14th row: its currency is there, its rate is not.
    assertThat(t.rows()).hasSize(14);
    assertThat(t.rows().get(0).currencyText()).isEqualTo("USD");
    assertThat(t.rows().get(13))
        .satisfies(
            row -> {
              assertThat(row.currencyText()).isEqualTo("HRK");
              assertThat(row.rateText()).isNull();
              assertThat(row.skipped()).isEqualTo("no rate cell");
            });
    assertThat(t.read()).isEqualTo(13);
  }

  @Test
  void aBrokenSelectorIsShownWhereItStopped() throws Exception {
    Map<String, String> v2 =
        new java.util.LinkedHashMap<>(
            cases.find(SOURCE, TRUNCATED).orElseThrow().before().recipe());
    v2.put("rateSelector", "td.no-such-cell");

    Lab.Result r =
        controller(KEY)
            .run(KEY, new ReviewController.RunRequest("archive:" + TRUNCATED, null, null, v2));

    assertThat(r.accepted()).isFalse();
    assertThat(r.trace().error()).contains("none contained both");
    assertThat(r.trace().rows())
        .isNotEmpty()
        .allSatisfy(row -> assertThat(row.skipped()).isEqualTo("no rate cell"));
  }

  @Test
  void thePageIsShownWithWhatTheRecipeMatchedAndNothingThatRuns() throws Exception {
    Lab.View v =
        controller(KEY).view(new ReviewController.ViewRequest("archive:" + TRUNCATED, 2, null));

    assertThat(v.source()).isEqualTo(Files.readString(lab.file("archive:" + TRUNCATED).get()));
    assertThat(v.archiveUrl()).startsWith("https://web.archive.org/web/" + TRUNCATED + "/");
    assertThat(v.marks()).filteredOn(m -> m.kind().equals("row")).hasSize(14);
    assertThat(v.marks()).filteredOn(m -> m.kind().equals("rate")).hasSize(13);
    assertThat(v.marks()).filteredOn(m -> m.kind().equals("date")).singleElement();
    // Every mark points at a real line of the source.
    long lines = v.source().lines().count();
    assertThat(v.marks()).allSatisfy(m -> assertThat(m.line()).isBetween(1, (int) lines));
    for (String html : List.of(v.rendered(), v.region())) {
      assertThat(html)
          .contains("Content-Security-Policy")
          .contains("pretium-rate")
          .doesNotContainIgnoringCase("<script");
    }
    assertThat(v.prompt()).contains("Current recipe:").contains(v.digest());
  }

  @Test
  void anInvalidSelectorIsReportedNotThrown() throws Exception {
    Lab.View v =
        controller(KEY)
            .view(
                new ReviewController.ViewRequest(
                    "archive:" + TRUNCATED, null, Map.of("rowSelector", "table >>> tr")));
    assertThat(v.selectorError()).isNotBlank();
    assertThat(v.marks()).isEmpty();
    assertThat(v.region()).isNull();
  }

  @Test
  void aBatchRunJudgesEveryPageAndATypedRecipeNeedsTheKey() throws Exception {
    Lab.Batch b = controller(KEY).batch(null, new ReviewController.RunRequest(null, 2, null, null));

    assertThat(b.pages()).isEqualTo(1);
    assertThat(b.accepted()).isZero();
    assertThat(b.wrongNumbers()).isZero();
    assertThat(b.rows())
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.pageId()).isEqualTo("archive:" + TRUNCATED);
              assertThat(row.matched()).isEqualTo(13);
              assertThat(row.error()).contains("read 13 of 36");
            });
    // The same recipe again comes from the cache: the same object.
    assertThat(controller(KEY).batch(null, new ReviewController.RunRequest(null, 2, null, null)))
        .isSameAs(b);

    assertThatThrownBy(
            () ->
                controller(KEY)
                    .batch(
                        null,
                        new ReviewController.RunRequest(
                            null, null, null, Map.of("rowSelector", "tr"))))
        .isInstanceOfSatisfying(
            ResponseStatusException.class, e -> assertThat(status(e)).isEqualTo(401));
  }

  @Test
  void pageIdsCannotNameAnyOtherFile() throws Exception {
    assertThat(lab.file("archive:" + TRUNCATED)).isPresent();
    assertThat(lab.file("archive:../../raw/ecb-sdmx-exr/history")).isEmpty();
    assertThat(lab.file("collected:../index")).isEmpty();
    assertThat(lab.file("archive:20190415031709")).isEmpty();
    assertThat(lab.file(null)).isEmpty();
  }

  @Test
  void theLabWorksOnTheCzechPageToo() throws Exception {
    String cnb = "cnb-fixing-html";
    Path raw = tmp.resolve("raw");
    Files.createDirectories(raw.resolve("cnb-fixing-txt"));
    Files.copy(
        fixture("cnb-fixing-year-2025-07-01-excerpt.txt"),
        raw.resolve("cnb-fixing-txt").resolve("2025.txt"));
    Path archive = Files.createDirectories(tmp.resolve("archive").resolve(cnb));
    Files.copy(
        fixture("cnb-fixing-2025-07-01-archived.html"), archive.resolve("20250701172207.html"));

    assertThat(lab.sources()).containsExactly(SOURCE, "boc-daily-html", cnb);
    assertThat(lab.pages(cnb))
        .extracting(Lab.Page::id)
        .containsExactly("archive:20250701172207@" + cnb);
    assertThat(lab.pages(SOURCE)).extracting(Lab.Page::id).containsExactly("archive:" + TRUNCATED);
    assertThat(controller(KEY).lab(cnb).source()).isEqualTo(cnb);
    assertThatThrownBy(() -> controller(KEY).lab("nope"))
        .isInstanceOfSatisfying(
            ResponseStatusException.class, e -> assertThat(status(e)).isEqualTo(404));

    // A recipe is judged against the page's own publisher, not the ECB.
    String id = "archive:20250701172207@" + cnb;
    Path page = lab.file(id).orElseThrow();
    SourceSpec.Recipe inUse = sources.find(cnb).orElseThrow().recipe();
    assertThat(lab.runRecipe(id, page, "recipe", inUse).accepted()).isFalse();
    SourceSpec.Recipe fixed =
        io.pretium.collector.repair.DateOnlyRepair.apply(
            inUse, io.pretium.collector.repair.PageDigest.dateHint(page), 2);
    Lab.Result r = lab.runRecipe(id, page, "human", fixed);
    assertThat(r.accepted()).isTrue();
    assertThat(r.ratesDate()).isEqualTo("2025-07-01");
    assertThat(lab.view(id, page, null, inUse, "https://example.test/").archiveUrl())
        .isEqualTo("https://web.archive.org/web/20250701172207/https://example.test/");
  }
}
