FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache bash curl

WORKDIR /app

ARG MODULE=pricing-service

COPY target/${MODULE}-*.jar app.jar

COPY healthcheck.sh /healthcheck.sh
RUN chmod +x /healthcheck.sh

HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD /healthcheck.sh

EXPOSE 8084

ENV JAVA_OPTS="-XX:+UseZGC -XX:MaxRAMPercentage=75.0"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
