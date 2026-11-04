// The collector: reads source specs, fetches each source politely, keeps the
// raw response as evidence, extracts observations and publishes them to Kafka.
// It owns no database — everything it learns leaves as an event.
plugins {
    alias(libs.plugins.springBoot)
    alias(libs.plugins.springDependencyManagement)
}

dependencies {
    implementation(project(":events"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("tools.jackson.dataformat:jackson-dataformat-yaml")
    implementation(libs.jsoup)
    implementation(libs.fastexcel.reader)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(libs.fastexcel.writer)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Selector-repair evaluation against archived versions of the ECB page. Not part
// of the build; run by hand:
//   ./gradlew :collector:repairEval -Ppages=../data/wayback -Psdmx=<history csv> \
//       -Pmodel=qwen2.5-coder:7b -Preport=../data/repair-eval.md
tasks.register<JavaExec>("repairEval") {
    group = "verification"
    description = "Replays the ECB page's archived history through selector repair"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.pretium.collector.repair.RepairEval")
    args(
        providers.gradleProperty("pages").getOrElse("../data/wayback"),
        providers.gradleProperty("sdmx").getOrElse(""),
        providers.gradleProperty("ollama").getOrElse("http://localhost:11434"),
        providers.gradleProperty("model").getOrElse("qwen2.5-coder:7b"),
        providers.gradleProperty("report").getOrElse("../data/repair-eval.md"),
    )
}
