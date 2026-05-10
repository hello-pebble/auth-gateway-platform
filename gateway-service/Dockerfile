# Build Stage
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /app
COPY . .
RUN chmod +x ./gradlew && ./gradlew :gateway-service:bootJar --no-daemon

# Run Stage
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=build /app/gateway-service/build/libs/*.jar app.jar
EXPOSE 8000
ENTRYPOINT ["java", "-Dspring.profiles.active=prod", "-jar", "app.jar"]