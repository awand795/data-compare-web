$timestamp = Get-Date -Format "yyyyMMdd_HHmm"
$backupFolder = "D:\my-project\dashboard db compare custom\backup"

# Buat folder backup jika belum ada
if (-Not (Test-Path $backupFolder)) {
    New-Item -ItemType Directory -Force -Path $backupFolder | Out-Null
}

$backupFile = "$backupFolder\data_setting_sync_backup_$timestamp.sql"

# Eksekusi pg_dump (Mencakup schema sch_sync, sch_excel, dan public)
$env:PGPASSWORD = "dataAnalyticW2024"
& "C:\Program Files\PostgreSQL\17\bin\pg_dump.exe" -h war.darkosuite.com -p 8832 -U postgres -d data_setting_sync -n sch_sync -n sch_excel -n public -f $backupFile
