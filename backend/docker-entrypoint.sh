#!/bin/sh
set -e

if [ -f "/app/ssh-keys/awanda.pem" ]; then
    echo "[Entrypoint] Starting SSH tunnel for internal database..."
    cp /app/ssh-keys/awanda.pem /tmp/awanda.pem
    chmod 600 /tmp/awanda.pem
    autossh -M 0 -f -N \
      -o StrictHostKeyChecking=no \
      -o ServerAliveInterval=30 \
      -o ServerAliveCountMax=3 \
      -i /tmp/awanda.pem \
      -p ${SSH_PORT:-8822} \
      -L ${DB_PORT:-8832}:127.0.0.1:${DB_PORT:-8832} \
      ${SSH_USER:-awanda}@${SSH_HOST:-94.237.69.119}
    echo "[Entrypoint] SSH tunnel started on port ${DB_PORT:-8832}."
    sleep 2
fi

exec java \
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
  -jar app.jar
