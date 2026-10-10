# se construye desde la raiz porque necesita el pom padre y el modulo shared
# docker build -f ms-clientes/Dockerfile -t banco-xyz/ms-clientes .

# etapa 1: compilar con maven. es igual en todos los Dockerfile a proposito: docker la cachea y
# compila el proyecto una sola vez para todas las imagenes (si se construyen de a una, ver readme)
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

# etapa 2: solo el jre y el jar
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /src/ms-clientes/target/ms-clientes-1.0.0.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]
