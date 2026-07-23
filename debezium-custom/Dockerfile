FROM quay.io/debezium/connect:2.4.2.Final
USER root
RUN curl -L -O https://github.com/ClickHouse/clickhouse-kafka-connect/releases/download/v1.3.10/clickhouse-kafka-connect-v1.3.10.zip && unzip clickhouse-kafka-connect-v1.3.10.zip -d /kafka/connect/clickhouse/ && rm clickhouse-kafka-connect-v1.3.10.zip
COPY docker-entrypoint-wait-for-kafka.sh /usr/local/bin/docker-entrypoint-wait-for-kafka.sh
RUN chmod 0755 /usr/local/bin/docker-entrypoint-wait-for-kafka.sh
USER 1001
ENTRYPOINT ["/usr/local/bin/docker-entrypoint-wait-for-kafka.sh"]
