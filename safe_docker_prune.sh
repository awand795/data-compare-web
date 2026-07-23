#!/bin/bash

# ==============================================================================
# SAFE DOCKER & SYSTEM CLEANUP SCRIPT
# Membersihkan cache, image menggantung, dan container mati tanpa mengganggu
# container aktif (erp-guidance, darkoAI, darkosync, amnezia, dll).
# ==============================================================================

echo "========================================="
echo "   SAFE DOCKER & SYSTEM CLEANUP SCRIPT   "
echo "========================================="
echo ""

echo "1. Membersihkan container yang sudah mati (Exited)..."
docker container prune -f

echo ""
echo "2. Membersihkan dangling/unused images (<none>)..."
docker image prune -f

echo ""
echo "3. Membersihkan Docker build cache..."
docker builder prune -a -f

echo ""
echo "4. Membersihkan log sistem usang (journalctl > 3 hari)..."
if command -v journalctl &> /dev/null; then
    sudo journalctl --vacuum-time=3d
fi

echo ""
echo "========================================="
echo "   SAFE PRUNE SELESAI! SERVICE AMAN     "
echo "========================================="
