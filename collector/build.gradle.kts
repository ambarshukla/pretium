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
