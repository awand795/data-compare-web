# Stage 1: Build with Maven
FROM maven:3.9-eclipse-temurin-17-alpine AS build
WORKDIR /app
COPY pom.xml ./
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn package -DskipTests -B

# Stage 2: Run with JRE
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

# Create logs directory
RUN mkdir -p /app/logs

EXPOSE 8081
ENTRYPOINT ["sh", "-c", "exec java -XX:+UseContainerSupport -XX:MaxRAMPercentage=55.0 -XX:InitialRAMPercentage=20.0 -XX:MaxMetaspaceSize=72m -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError -XX:MaxGCPauseMillis=200 -XX:G1HeapRegionSize=2m -XX:+UseStringDeduplication -XX:SoftRefLRUPolicyMSPerMB=0 $JAVA_OPTS -jar app.jar"]
