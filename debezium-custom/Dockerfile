FROM quay.io/debezium/connect:2.4.2.Final
USER root
RUN curl -L -O https://github.com/ClickHouse/clickhouse-kafka-connect/releases/download/v1.3.10/clickhouse-kafka-connect-v1.3.10.zip && unzip clickhouse-kafka-connect-v1.3.10.zip -d /kafka/connect/clickhouse/ && rm clickhouse-kafka-connect-v1.3.10.zip

# Fix missing connect-log4j.properties (causes Kafka Connect to exit silently when missing)
COPY connect-log4j.properties /kafka/config/connect-log4j.properties
# Also place in config.orig so the entrypoint script's copy step picks it up
COPY connect-log4j.properties /kafka/config.orig/connect-log4j.properties

COPY docker-entrypoint-wait-for-kafka.sh /usr/local/bin/docker-entrypoint-wait-for-kafka.sh
RUN chmod 0755 /usr/local/bin/docker-entrypoint-wait-for-kafka.sh

# Ensure kafka user (uid 1001) can read all config files
RUN chown -R 1001:1001 /kafka/config/ /kafka/config.orig/ && \
    chmod -R a+rX /kafka/config/ /kafka/config.orig/

USER 1001
CMD ["start"]
ENTRYPOINT ["/usr/local/bin/docker-entrypoint-wait-for-kafka.sh"]
