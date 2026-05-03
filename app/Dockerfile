FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

ARG RUNTIME_MODULE=runtime-monolith
ARG RUNTIME_ARTIFACT=pocoma-runtime-monolith

COPY . .
RUN ./mvnw -pl ${RUNTIME_MODULE} -am package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app

ARG RUNTIME_MODULE=runtime-monolith
ARG RUNTIME_ARTIFACT=pocoma-runtime-monolith

COPY --from=build /workspace/${RUNTIME_MODULE}/target/${RUNTIME_ARTIFACT}-0.0.1-SNAPSHOT.jar /app/pocoma-runtime.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/pocoma-runtime.jar"]
