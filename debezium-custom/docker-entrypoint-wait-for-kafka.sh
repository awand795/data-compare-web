#!/bin/bash
set -euo pipefail

bootstrap_servers="${BOOTSTRAP_SERVERS:-kafka:9092}"
# Extract host and port from bootstrap_servers (format: host:port)
kafka_host="${bootstrap_servers%%:*}"
kafka_port="${bootstrap_servers##*:}"

attempt=0

echo "Waiting for Kafka at ${kafka_host}:${kafka_port}..." >&2

until timeout 5 bash -c "echo > /dev/tcp/${kafka_host}/${kafka_port}" 2>/dev/null; do
    attempt=$((attempt + 1))
    echo "Kafka is not ready at ${kafka_host}:${kafka_port}; delaying Kafka Connect startup (attempt ${attempt})" >&2
    sleep 5
done

echo "Kafka is ready at ${kafka_host}:${kafka_port}! Starting Kafka Connect..." >&2

exec /docker-entrypoint.sh "$@"
