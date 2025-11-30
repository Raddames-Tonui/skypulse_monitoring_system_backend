# Use a smaller JRE image for production
FROM eclipse-temurin:21-jre-jammy

# Set working directory
WORKDIR /app

# Copy the shaded jar
COPY skypulse-monitoring-system-1.0-SNAPSHOT-shaded.jar ./

# Copy the default config (optional, can override via volumes or Render files)
COPY config.xml ./

# Set environment variables for Render secrets (example)
# Do NOT include secrets in the image; set them in Render's dashboard
# ENV CONFIG_MASTER_KEY=your_master_key_here
# ENV FRONTEND_BASE_URL=http://frontend.example.com

# Expose the Undertow port
EXPOSE 5050

# Healthcheck for Render
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s \
    CMD curl -f http://localhost:5050/health || exit 1

# Run the app with config.xml
CMD ["java", "-jar", "skypulse-monitoring-system-1.0-SNAPSHOT-shaded.jar", "config.xml"]
