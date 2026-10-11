# Local operational tool only, derived from the same reviewed application's builder.
# No secrets or host Maven repository are copied. Publish only with the window approval.
ARG BUILDER_IMAGE
FROM ${BUILDER_IMAGE}
RUN sh mvnw -B -ntp -f /messaging-core/pom.xml -Dmaven.test.skip=true install \
 && sh mvnw -B -ntp dependency:resolve -DincludeScope=runtime \
 && sh mvnw -B -ntp help:describe -Dplugin=org.flywaydb:flyway-maven-plugin:12.4.0 -Ddetail
# Explicit validate/migrate invocation with restricted file configuration at runtime.
# Never run this as an application or mount Docker socket / broker data.
