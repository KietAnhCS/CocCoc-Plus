# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q clean package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/target/vnsearch-0.0.1-SNAPSHOT.jar app.jar
# Gioi han heap de do RAM on dinh; chinh bang JAVA_OPTS trong docker-compose.yml.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=60 -Dfile.encoding=UTF-8"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
