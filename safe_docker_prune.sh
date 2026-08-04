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

echo "1. Membersihkan container yang sudah mati (Exited) KECUALI metabase & critical services..."
# Dapatkan ID container exited yang BUKAN metabase
EXITED_TO_REMOVE=$(docker ps -a --filter "status=exited" --format "{{.ID}} {{.Names}}" | grep -v "metabase" | awk '{print $1}')
if [ -n "$EXITED_TO_REMOVE" ]; then
    docker rm $EXITED_TO_REMOVE
else
    echo "Tidak ada container exited (selain metabase) yang perlu dihapus."
fi

echo ""
echo "2. Membersihkan dangling/unused images (<none> saja, image bernama/tagged seperti metabase/metabase TIDAK AKAN terhapus)..."
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
