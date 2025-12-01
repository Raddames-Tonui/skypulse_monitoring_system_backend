# 1. BUILD STAGE
FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /app

# Copy pom + source
COPY pom.xml .
COPY src ./src

# Build fat JAR using Maven Shade plugin
RUN mvn -e -X -DskipTests clean package


# 2. RUNTIME STAGE
FROM eclipse-temurin:21-jre

WORKDIR /app

# Copy JAR from build stage
COPY --from=build /app/target/skypulse-monitoring-system.jar app.jar

# Copy config.xml (ensure it exists in repo)
COPY config.xml /app/config.xml

# Expose Undertow port (MUST MATCH config.xml)
EXPOSE 8000

# Config path
ENV CONFIG_FILE=/app/config.xml

CMD ["java", "-jar", "/app/app.jar"]
