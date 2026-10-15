# syntax=docker/dockerfile:1.7
# One multi-stage Dockerfile for every Spring Boot service.
# Build from the repo root:  docker build -f infra/docker/service.Dockerfile --build-arg MODULE=api-gateway .

FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY common common
COPY services services
ARG MODULE
RUN --mount=type=cache,target=/root/.m2 \
    sh ./mvnw -B -q -pl services/${MODULE} -am package -DskipTests \
 && cp services/${MODULE}/target/${MODULE}-*.jar /workspace/app.jar

FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app app
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
USER app
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
