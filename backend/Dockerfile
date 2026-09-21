FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src src
RUN mvn -B package
FROM eclipse-temurin:25-jre
WORKDIR /app
RUN useradd --system app
COPY --from=build /app/target/feed-system-1.0.0.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]
