git add .
git commit -m "Fix ClickHouse cast exception for is_deleted"
git push origin main
docker build -t awandadarkotech/darkosync-backend:latest ./backend
docker push awandadarkotech/darkosync-backend:latest
