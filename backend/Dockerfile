# syntax=docker/dockerfile:1.4
# Stage 1: Build with Maven using host m2 cache
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /app
COPY pom.xml ./
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn clean package -DskipTests -B

# Stage 2: Run with JRE
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

# Install autossh, openssh-client, and sshpass
RUN apk add --no-cache openssh-client autossh sshpass

# Create logs directory
RUN mkdir -p /app/logs

COPY docker-entrypoint.sh /app/docker-entrypoint.sh
RUN sed -i 's/\r$//' /app/docker-entrypoint.sh && chmod +x /app/docker-entrypoint.sh

EXPOSE 8081
ENTRYPOINT ["/app/docker-entrypoint.sh"]
