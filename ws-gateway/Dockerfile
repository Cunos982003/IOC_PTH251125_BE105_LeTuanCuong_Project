FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
ARG MODULE=ws-gateway
COPY ${MODULE}/target/${MODULE}-*.jar app.jar
EXPOSE 8001
ENTRYPOINT ["java", "-jar", "app.jar"]
