# deploy.ps1
Write-Host "Starting Zero-Downtime Deployment..." -ForegroundColor Cyan

# 1. Initialize Swarm if needed
$swarmStatus = docker info --format '{{.Swarm.LocalNodeState}}'
if ($swarmStatus -ne 'active') {
    Write-Host "Initializing Docker Swarm..." -ForegroundColor Yellow
    docker swarm init
}

# 2. Build local images (Swarm does not build them on deploy)
Write-Host "Building fresh images..." -ForegroundColor Yellow
docker compose -f docker-compose.local.yml build

# 3. Load .env file
if (Test-Path .env) {
    Write-Host "Loading environment variables from .env..." -ForegroundColor Yellow
    Get-Content .env | Where-Object { $_ -match "^[^#].*=.*" } | ForEach-Object {
        $name, $value = $_ -split '=', 2
        [Environment]::SetEnvironmentVariable($name.Trim(), $value.Trim(), "Process")
    }
} else {
    Write-Host "Warning: .env file not found. Creating a default one." -ForegroundColor Yellow
    Set-Content -Path .env -Value "INTERNAL_DB_PASSWORD=local_password`nCLICKHOUSE_PASSWORD=clickhouse_pass"
    [Environment]::SetEnvironmentVariable("INTERNAL_DB_PASSWORD", "local_password", "Process")
    [Environment]::SetEnvironmentVariable("CLICKHOUSE_PASSWORD", "clickhouse_pass", "Process")
}

# 4. Deploy to Swarm
Write-Host "Deploying stack to Swarm..." -ForegroundColor Yellow
docker stack deploy -c docker-compose.local.yml darkosync

# 5. Force update services (to ensure zero-downtime rolling update kicks in)
Write-Host "Forcing service rolling updates..." -ForegroundColor Yellow
docker service update --force darkosync_backend
docker service update --force darkosync_frontend

Write-Host "Deployment completed successfully! Zero-downtime transition is running in the background." -ForegroundColor Green
Write-Host "Check status with: docker service ls" -ForegroundColor Cyan
