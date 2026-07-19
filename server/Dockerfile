FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
# Building a release image is also a correctness gate: tests and JaCoCo must pass.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -Dmaven.wagon.http.retryHandler.count=5 verify

FROM eclipse-temurin:17-jre-jammy
RUN apt-get update \
    && apt-get install -y --no-install-recommends tesseract-ocr tesseract-ocr-chi-sim \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /app/target/docpilot-server-1.0.0.jar app.jar
RUN useradd --create-home --uid 10001 appuser && chown -R appuser:appuser /app
USER appuser
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
