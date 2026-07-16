FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src src
RUN mvn -q -DskipTests package
FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y fonts-noto-cjk && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /build/target/local-ai-growth-os-0.1.0.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java","-jar","/app/app.jar"]
