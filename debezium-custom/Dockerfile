FROM quay.io/debezium/connect:2.4.2.Final
USER root
RUN curl -L -O https://github.com/ClickHouse/clickhouse-kafka-connect/releases/download/v1.3.10/clickhouse-kafka-connect-v1.3.10.zip && unzip clickhouse-kafka-connect-v1.3.10.zip -d /kafka/connect/clickhouse/ && rm clickhouse-kafka-connect-v1.3.10.zip
USER 1001
