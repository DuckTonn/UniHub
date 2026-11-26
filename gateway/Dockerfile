FROM maven:3.9-eclipse-temurin-25-alpine AS build
WORKDIR /app
COPY pom.xml ./
COPY src ./src
RUN mvn -B -ntp package -DskipTests

FROM eclipse-temurin:25-jre-alpine
LABEL org.opencontainers.image.source="https://github.com/DuckTonn/UniHub"
WORKDIR /app
COPY --from=build /app/target/gateway-*.jar app.jar
EXPOSE 8081
USER 10001:10001
ENTRYPOINT ["java", "-jar", "app.jar"]
