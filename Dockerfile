# Multi-stage Dockerfile for Ride-Hailing microservices
# Build stage
FROM maven:3.9-eclipse-temurin-21 AS builder

ARG MODULE
WORKDIR /build

# Copy parent pom.xml first for dependency caching
COPY pom.xml .
# Copy all module pom.xml files for dependency resolution
COPY user-service/pom.xml user-service/
COPY location-service/pom.xml location-service/
COPY dispatch-service/pom.xml dispatch-service/
COPY pricing-service/pom.xml pricing-service/
COPY payment-service/pom.xml payment-service/
COPY ws-gateway/pom.xml ws-gateway/
COPY api-gateway/pom.xml api-gateway/

# Copy source code
COPY ${MODULE}/src ${MODULE}/src

# Build the specific module (no -am since no shared modules)
RUN mvn -q -pl ${MODULE} package -DskipTests

# Runtime stage
FROM eclipse-temurin:21-jre

# Install curl for healthcheck (eclipse-temurin:21-jre doesn't include curl by default)
# Verified: docker run --rm eclipse-temurin:21-jre which curl -> no output (not installed)
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Create non-root user
RUN groupadd -r appuser && useradd -r -g appuser appuser

WORKDIR /app

# Copy built jar from builder stage
ARG MODULE
COPY --from=builder /build/${MODULE}/target/${MODULE}-*.jar app.jar

# Copy healthcheck script
COPY healthcheck.sh /app/healthcheck.sh
RUN chmod +x /app/healthcheck.sh

# Change ownership to non-root user
RUN chown -R appuser:appuser /app

USER appuser

# JVM options for container environment
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70"

# Health check
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD /app/healthcheck.sh

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]