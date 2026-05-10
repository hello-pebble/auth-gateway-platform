# context-monitor.ps1
# Stop hook -- JSONL file size-based context usage estimation
# Injects additionalContext when threshold is exceeded

param(
    [int]$ThresholdKB = 200
)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$transcriptDir = "$env:USERPROFILE\.claude\projects"
if (-not (Test-Path $transcriptDir)) { exit 0 }

$latest = Get-ChildItem -Path $transcriptDir -Recurse -Filter "*.jsonl" -ErrorAction SilentlyContinue |
          Sort-Object LastWriteTime -Descending |
          Select-Object -First 1

if (-not $latest) { exit 0 }

$sizeKB = [math]::Round($latest.Length / 1KB, 1)

if ($sizeKB -gt $ThresholdKB) {
    $msg  = "WARNING [CONTEXT MANAGER] 컨텍스트 사용량이 임계치(${ThresholdKB}KB)를 초과했습니다 (현재: ${sizeKB}KB)."
    $msg += "`n즉시 다음을 수행하세요:"
    $msg += "`n1. 이 대화의 핵심 작업 내용, 결정 사항, 미완료 항목을 요약합니다."
    $msg += "`n2. docs/context-logs/ 에 현재 날짜시간 형식(YYYY-MM-DD_HH-mm.md)으로 저장합니다."
    $msg += "`n3. 저장 완료 후 사용자에게 '/clear 명령으로 대화를 초기화하세요'라고 안내합니다."

    $obj = [ordered]@{
        hookSpecificOutput = [ordered]@{
            hookEventName     = "Stop"
            additionalContext = $msg
        }
    }

    $json = $obj | ConvertTo-Json -Compress
    [Console]::Out.WriteLine($json)
}