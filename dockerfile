# Use a lightweight JDK 21 image
FROM eclipse-temurin:21-jdk-jammy

# Set working directory
WORKDIR /app

# Copy JAR, config, and .env
COPY target/skypulse-monitoring-system-1.0-SNAPSHOT-shaded.jar .
COPY config.xml .
COPY .env .

# Expose HTTP port (from your Undertow config)
EXPOSE 8080

# Load .env variables
# (optional if you want the container to read them at runtime)
# Using 'sh' to export variables
CMD export $(grep -v '^#' .env | xargs) && \
    java -jar skypulse-monitoring-system-1.0-SNAPSHOT-shaded.jar config.xml
