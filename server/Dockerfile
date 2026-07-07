FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -Dmaven.wagon.http.retryHandler.count=5 -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -Dmaven.wagon.http.retryHandler.count=5 -DskipTests package

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/target/docpilot-server-1.0.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
