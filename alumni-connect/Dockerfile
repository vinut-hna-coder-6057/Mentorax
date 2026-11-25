
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

COPY pom.xml .
COPY src ./src

RUN mvn clean package -DskipTests

FROM eclipse-temurin:21-jre

# Render mounts runtime secret files for group 1000. Keep the existing runtime
# user (root) and add that supplementary group so the CA file remains readable.
RUN if ! getent group 1000 >/dev/null; then groupadd --gid 1000 render-secrets; fi \
    && secret_group="$(getent group 1000 | cut -d: -f1)" \
    && usermod -a -G "$secret_group" root

WORKDIR /app

COPY --from=build /app/target/*.jar app.jar

EXPOSE 10000

CMD ["sh", "-c", "java -Dserver.port=${PORT:-10000} -jar app.jar"]
