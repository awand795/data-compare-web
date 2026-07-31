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

# Create logs directory
RUN mkdir -p /app/logs

EXPOSE 8081
ENTRYPOINT ["sh", "-c", "exec java \
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=40.0 \
  -XX:InitialRAMPercentage=15.0 \
  -XX:MaxMetaspaceSize=256m \
  -XX:+UseG1GC \
  -XX:G1HeapRegionSize=1m \
  -XX:MaxGCPauseMillis=150 \
  -XX:InitiatingHeapOccupancyPercent=25 \
  -XX:+ParallelRefProcEnabled \
  -XX:+UseStringDeduplication \
  -XX:SoftRefLRUPolicyMSPerMB=0 \
  -XX:+ExitOnOutOfMemoryError \
  $JAVA_OPTS \
  -jar app.jar"]
