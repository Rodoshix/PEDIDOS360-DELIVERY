# Build con el Wrapper del proyecto; verificar antes con ./mvnw verify (Testcontainers).
FROM eclipse-temurin:21-jdk-alpine@sha256:6ea5548706b60ac0a602eaf48af74792cbab012d90e811ca8db6184b16b5c3d6 AS build
WORKDIR /build
COPY .mvn/wrapper/maven-wrapper.properties .mvn/wrapper/maven-wrapper.properties
COPY mvnw pom.xml ./
# El checkout de Windows puede usar CRLF; no modifica los archivos del host.
RUN sed -i 's/\r$//' mvnw
COPY src/main ./src/main
# Testcontainers se ejecuta fuera del build. Nunca montar el socket Docker aquí.
RUN --mount=type=cache,id=pedidos360-maven,target=/root/.m2,sharing=locked \
    sh mvnw -B -ntp -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre-alpine@sha256:974b08960c5d96694c780e65b2d5705268ab1e1ca1a0dd0caf4ba6c3fe34d699 AS runtime
RUN addgroup -S -g 10001 app && adduser -S -D -H -u 10001 -G app app
WORKDIR /app
COPY --from=build /build/target/pedidos-service-0.0.1-SNAPSHOT.jar ./app.jar
ENV SERVER_ADDRESS=0.0.0.0 \
    SERVER_PORT=8085 \
    LOCAL_IDENTITY_ENABLED=false
USER 10001:10001
EXPOSE 8085
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=6 \
    CMD wget -q -O /dev/null "http://127.0.0.1:${SERVER_PORT}/actuator/health" || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
