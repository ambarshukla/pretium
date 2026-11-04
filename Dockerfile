# One Dockerfile for both Java services. MODULE picks which one:
#   docker build --build-arg MODULE=collector .
#   docker build --build-arg MODULE=catalog .
#
# Stage 1 builds the jar with the project's own Gradle wrapper, so the image is
# built with exactly the Gradle and JDK that CI uses. Stage 2 is a JRE only:
# no compiler, no Gradle, no source code in the image that runs.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
# Dependencies first, so a source-only change reuses the cached download layer.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY events/build.gradle.kts events/
COPY collector/build.gradle.kts collector/
COPY catalog/build.gradle.kts catalog/
ARG MODULE
RUN ./gradlew --no-daemon -Dorg.gradle.jvmargs=-Xmx768m :${MODULE}:dependencies > /dev/null
COPY events events
COPY ${MODULE} ${MODULE}
RUN ./gradlew --no-daemon -Dorg.gradle.jvmargs=-Xmx768m :${MODULE}:bootJar -x test \
 && cp ${MODULE}/build/libs/${MODULE}-*.jar /app.jar

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 pretium
COPY --from=build /app.jar /app/app.jar
USER pretium
# The heap is set per service in docker-compose (JAVA_TOOL_OPTIONS), next to its memory limit.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
