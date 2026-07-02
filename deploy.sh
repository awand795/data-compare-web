#!/bin/bash

echo "🔄 Menarik pembaruan konfigurasi terbaru dari GitHub..."
git pull origin main

echo "🚀 Menjalankan Zero-Downtime Deployment via Docker Hub..."
# Gunakan --with-registry-auth agar Swarm bisa mengecek digest terbaru dari Docker Hub
export $(grep -v '^#' .env | xargs) && sudo -E docker stack deploy --with-registry-auth -c docker-compose.yml darkosync

echo "✅ Deploy dikirim! Swarm akan otomatis mendeteksi perubahan dari Docker Hub."
echo "Pantau statusnya dengan perintah: watch -n 2 sudo docker stack ps darkosync"
