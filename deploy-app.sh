#!/bin/bash

echo "🔄 Menarik pembaruan konfigurasi terbaru dari GitHub..."
git pull origin main

echo "🚀 Menjalankan Zero-Downtime Deployment via Docker Hub (App Stack)..."
export $(grep -v '^#' .env | xargs 2>/dev/null) && sudo -E docker stack deploy --with-registry-auth -c docker-compose-app.yml darkosync
sudo docker service update --image awandadarkotech/darkosync-frontend:latest --force darkosync_frontend
sudo docker service update --image awandadarkotech/darkosync-backend:latest --force darkosync_backend

echo "🧹 Menjalankan Safe Docker Prune setelah deploy..."
bash ./safe_docker_prune.sh

echo "✅ Deploy dikirim & Service berhasil diperbarui!"
echo "Pantau statusnya dengan perintah: watch -n 2 sudo docker stack ps darkosync"
