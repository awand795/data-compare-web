# Stage 1: Build with Maven
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /app
COPY pom.xml ./
RUN mvn dependency:go-offline -B || true
COPY src ./src
RUN mvn package -DskipTests -o -B || mvn package -DskipTests -B

# Stage 2: Run with JRE
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

# Install autossh, openssh-client, and sshpass
RUN apk add --no-cache openssh-client autossh sshpass

# Create logs directory
RUN mkdir -p /app/logs

COPY docker-entrypoint.sh /app/docker-entrypoint.sh
RUN chmod +x /app/docker-entrypoint.sh

EXPOSE 8081
ENTRYPOINT ["/app/docker-entrypoint.sh"]
