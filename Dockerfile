# Multi-stage build, same shape as shipment-service's own Dockerfile.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-jammy
RUN useradd --system --create-home --shell /usr/sbin/nologin notificationservice
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
USER notificationservice
EXPOSE 8080 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
