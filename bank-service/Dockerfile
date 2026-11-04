# syntax=docker/dockerfile:1
# The bank-service as a container for the showcase (docker compose --profile showcase up -d --build --wait).
# It is built inside Docker, so no local Java is needed. For development, ./gradlew :bank-service:bootRun is faster.
# Build context: the repository root.

FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
# Wrapper and build scripts first: this layer and the Gradle distribution stay cached while only the code changes.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY domain/build.gradle.kts domain/
COPY bank-service/build.gradle.kts bank-service/
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon --quiet help
COPY domain domain
COPY bank-service bank-service
# Tests run in CI, not in the image build. The jar is split into layers, so a code change only rebuilds the last one.
# The extracted launcher jar keeps the jar's name, so it is renamed first: application.jar, whatever the version.
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon --quiet :bank-service:bootJar \
 && cp "$(ls bank-service/build/libs/*.jar | grep -v -- -plain)" application.jar \
 && java -Djarmode=tools -jar application.jar extract --layers --destination /extracted

FROM eclipse-temurin:25-jre
RUN useradd --system --no-create-home bank
WORKDIR /application
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
USER bank
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "application.jar"]
