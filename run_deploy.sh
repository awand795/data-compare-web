#!/bin/sh
docker cp payload.json c42357585e9e:/tmp/payload.json
docker exec c42357585e9e wget -qO- --post-file=/tmp/payload.json --header="Content-Type: application/json" http://localhost:8081/api/dwh/deploy
