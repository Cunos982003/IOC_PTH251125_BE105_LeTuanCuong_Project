FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Copy jar from Maven build
ARG MODULE=api-gateway
COPY ${MODULE}/target/${MODULE}-*.jar app.jar

# Create non-root user
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

EXPOSE 8000

ENTRYPOINT ["java", "-jar", "app.jar"]
