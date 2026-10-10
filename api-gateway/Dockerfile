# se construye desde la raiz porque necesita el pom padre
# docker build -f api-gateway/Dockerfile -t banco-xyz/api-gateway .

# etapa 1: compilar con maven (igual que en los otros Dockerfile, asi se reusa el cache)
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
# primero solo los pom y bajo las dependencias: esa capa queda en cache y si cambio codigo no se bajan de nuevo
COPY pom.xml .
COPY shared/pom.xml shared/
COPY config-server/pom.xml config-server/
COPY eureka-server/pom.xml eureka-server/
COPY api-gateway/pom.xml api-gateway/
COPY auth-server/pom.xml auth-server/
COPY ms-cuentas/pom.xml ms-cuentas/
COPY ms-pagos/pom.xml ms-pagos/
COPY ms-clientes/pom.xml ms-clientes/
COPY bff-web/pom.xml bff-web/
COPY bff-mobile/pom.xml bff-mobile/
COPY bff-atm/pom.xml bff-atm/
COPY notificaciones/pom.xml notificaciones/
COPY batch/pom.xml batch/
# a veces la descarga se corta dentro de colima, por eso el || (lo que falte lo baja el package)
RUN mvn -q -B dependency:go-offline || mvn -q -B dependency:go-offline || true
COPY . .
RUN mvn -q -B package -DskipTests || mvn -q -B package -DskipTests

# etapa 2: jre + jar + el certificado del https
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
# el keystore pkcs12 autofirmado se genera aca con keytool, asi un clone limpio funciona sin pasos extra.
# no se sube al repo (*.p12 esta en el .gitignore). no hay alb, el https lo hace el mismo gateway
ARG GATEWAY_KEYSTORE_PASSWORD=changeit
RUN keytool -genkeypair -alias gateway -keyalg RSA -keysize 2048 -validity 365 \
      -storetype PKCS12 -keystore /app/gateway.p12 -storepass "$GATEWAY_KEYSTORE_PASSWORD" \
      -dname "CN=localhost, OU=EFT, O=Banco XYZ, L=Santiago, C=CL" -ext "SAN=dns:localhost,ip:127.0.0.1"
ENV GATEWAY_KEYSTORE=file:/app/gateway.p12
COPY --from=build /src/api-gateway/target/api-gateway-1.0.0.jar app.jar
EXPOSE 8443
ENTRYPOINT ["java", "-jar", "app.jar"]
