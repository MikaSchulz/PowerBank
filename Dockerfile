# syntax=docker/dockerfile:1
# One image recipe for the services of this repository, built inside Docker (no local Java needed):
#   bank-service (default) and clearing:  docker build --build-arg MODULE=clearing .
# compose.yaml uses it for the showcase, bank B and the clearing house. For development, bootRun is faster.
# Build context: the repository root.

FROM eclipse-temurin:25-jdk AS build
ARG MODULE=bank-service
WORKDIR /src
# Wrapper and build scripts first: this layer and the Gradle distribution stay cached while only the code changes.
# Gradle needs the build scripts of every module in settings.gradle.kts, also of those it doesn't build.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY domain/build.gradle.kts domain/
COPY payment-messages/build.gradle.kts payment-messages/
COPY bank-service/build.gradle.kts bank-service/
COPY clearing/build.gradle.kts clearing/
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon --quiet help
COPY domain domain
COPY payment-messages payment-messages
COPY bank-service bank-service
COPY clearing clearing
# Tests run in CI, not in the image build. The jar is split into layers, so a code change only rebuilds the last one.
# The extracted launcher jar keeps the jar's name, so it is renamed first: application.jar, whatever the version.
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon --quiet ":$MODULE:bootJar" \
 && cp "$(ls "$MODULE"/build/libs/*.jar | grep -v -- -plain)" application.jar \
 && java -Djarmode=tools -jar application.jar extract --layers --destination /extracted

FROM eclipse-temurin:25-jre
RUN useradd --system --no-create-home app
WORKDIR /application
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
USER app
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "application.jar"]
