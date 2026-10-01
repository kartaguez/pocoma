# Web API
./mvnw -pl runtime-web-api spring-boot:run \
  -Dspring-boot.run.arguments="\
--spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:8081/realms/pocoma \
--pocoma.command-admission.enabled=true \
--pocoma.command-result-read.enabled=true \
--pocoma.pot-read.enabled=true"

## Command worker
./mvnw -pl runtime-command-consumption-worker spring-boot:run \
>   -Dspring-boot.run.arguments="\
> --spring.datasource.url=jdbc:postgresql://localhost:5432/pocoma \
> --spring.datasource.username=pocoma \
> --spring.datasource.password=pocoma \
> --spring.datasource.driver-class-name=org.postgresql.Driver"

## Event worker
 ./mvnw -pl runtime-event-consumption-worker spring-boot:run \
>   -Dspring-boot.run.arguments="\
> --spring.datasource.url=jdbc:postgresql://localhost:5432/pocoma \
> --spring.datasource.username=pocoma \
> --spring.datasource.password=pocoma \
> --spring.datasource.driver-class-name=org.postgresql.Driver \
> --pocoma.event-consumption.projection-types=COMMAND_RESULT,AUTH,READ_POT"

## Task worker
./mvnw -pl runtime-task-consumption-worker spring-boot:run \
  -Dspring-boot.run.arguments="\
--spring.datasource.url=jdbc:postgresql://localhost:5432/pocoma \
--spring.datasource.username=pocoma \
--spring.datasource.password=pocoma \
--spring.datasource.driver-class-name=org.postgresql.Driver \
--pocoma.projection-task-consumption.enabled=true"
