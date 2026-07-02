#!/bin/bash

echo "🔄 Menarik pembaruan kode terbaru dari GitHub..."
git pull origin main

echo "🏗️ Membangun ulang (build) image Docker lokal..."
sudo docker-compose build

echo "🚀 Menjalankan Zero-Downtime Deployment ke Docker Swarm..."
# Perbarui file konfigurasi jika ada perubahan di docker-compose.yml
export $(grep -v '^#' .env | xargs) && sudo -E docker stack deploy -c docker-compose.yml darkosync

echo "🔄 Memaksa Swarm membaca image lokal terbaru (Workaround untuk Docker Swarm tanpa Registry)..."
sudo docker service update --force darkosync_backend
sudo docker service update --force darkosync_frontend

echo "✅ Deploy selesai! Pantau statusnya dengan perintah: watch -n 2 sudo docker stack ps darkosync"
