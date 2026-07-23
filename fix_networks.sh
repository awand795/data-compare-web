#!/bin/bash
sed -i 's/darkosync_kafka/kafka-broker/g' /var/www/data-compare-web/docker-compose.yml
sed -i 's/darkosync_debezium/debezium-connect/g' /var/www/data-compare-web/docker-compose.yml
sed -i 's/darkosync_zookeeper/zookeeper-node/g' /var/www/data-compare-web/docker-compose.yml
