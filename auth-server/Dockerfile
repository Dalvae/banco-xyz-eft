# se construye desde la raiz porque necesita el pom padre
# docker build -f auth-server/Dockerfile -t banco-xyz/auth-server .

# etapa 1: compilar con maven
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
RUN mvn -q -pl auth-server -am package -DskipTests

# etapa 2: solo el jre y el jar
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /src/auth-server/target/auth-server-1.0.0.jar app.jar
EXPOSE 9000
ENTRYPOINT ["java", "-jar", "app.jar"]
