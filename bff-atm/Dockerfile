# se construye desde la raiz porque necesita el pom padre y el modulo shared
# docker build -f bff-atm/Dockerfile -t banco-xyz/bff-atm .

# etapa 1: compilar con maven
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
RUN mvn -q -pl bff-atm -am package -DskipTests

# etapa 2: solo el jre y el jar
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /src/bff-atm/target/bff-atm-1.0.0.jar app.jar
EXPOSE 8443
ENTRYPOINT ["java", "-jar", "app.jar"]
