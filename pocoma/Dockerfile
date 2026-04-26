FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY . .
RUN ./mvnw -pl runtime-monolith -am package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=build /workspace/runtime-monolith/target/pocoma-runtime-monolith-0.0.1-SNAPSHOT.jar /app/pocoma-runtime-monolith.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/pocoma-runtime-monolith.jar"]
