#!/bin/bash
set -euo pipefail

bootstrap_servers="${BOOTSTRAP_SERVERS:-tasks.darkosync_kafka:9092}"
attempt=0

until timeout 10 /kafka/bin/kafka-topics.sh --bootstrap-server "${bootstrap_servers}" --list >/dev/null 2>&1; do
    attempt=$((attempt + 1))
    echo "Kafka is not ready at ${bootstrap_servers}; delaying Kafka Connect startup (attempt ${attempt})" >&2
    sleep 5
done

exec /docker-entrypoint.sh "$@"
