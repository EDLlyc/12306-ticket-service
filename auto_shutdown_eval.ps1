if ([string]::IsNullOrWhiteSpace($env:ZHIPU_API_KEY) -or $env:ZHIPU_API_KEY -eq "YOUR_ZHIPU_API_KEY_HERE") {
    Write-Host "Missing ZHIPU_API_KEY. Set it before running evaluation." -ForegroundColor Red
    exit 1
}
cd d:\Project\12306-ticket-service\ragas_eval

Write-Host "=========================================="
Write-Host "   RAGAS Evaluation & Auto-Shutdown Task   "
Write-Host "=========================================="
Write-Host "1. Running RAGAS Evaluation... (Depending on rate limits, this might take ~15 mins)"
Write-Host "You can track the live progress by opening: d:\Project\12306-ticket-service\ragas_eval\run_eval_final.log"
.\.venv\Scripts\python -X utf8 -u evaluate.py > run_eval_final.log 2>&1
Write-Host "Evaluation Finished! Output saved to report.json and run_eval_final.log."

Write-Host "------------------------------------------"
Write-Host "2. Safely terminating Java Spring Boot Application (Port 8899)..."
$proc = Get-NetTCPConnection -LocalPort 8899 -State Listen -ErrorAction SilentlyContinue
if ($proc) {
    try {
        Stop-Process -Id $proc.OwningProcess -Force
        Write-Host "--> Success: Java TicketServiceApplication stopped."
    } catch {
        Write-Host "--> Error stopping Java process: $_"
    }
} else {
    Write-Host "--> Port 8899 is already closed. No Java web server running."
}

Write-Host "------------------------------------------"
Write-Host "3. Stopping all Ticket Service Docker Containers..."
docker stop rmqbroker milvus-standalone milvus-minio milvus-etcd ahu-mysql rmqnamesrv ahu-redis
Write-Host "--> Success: Docker infrastructure stopped."

Write-Host "=========================================="
Write-Host "ALL TASKS COMPLETED! HAVE A GOOD NIGHT!"
Write-Host "=========================================="
