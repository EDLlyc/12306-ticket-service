param(
    [ValidateSet("install", "remove", "status")]
    [string]$Mode = "install",

    [int]$Port = 8899,

    [string]$Distro
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Test-Administrator {
    $currentIdentity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($currentIdentity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Get-WslIpAddress {
    $wslArgs = if ([string]::IsNullOrWhiteSpace($Distro)) {
        @("--", "hostname", "-I")
    } else {
        @("-d", $Distro, "--", "hostname", "-I")
    }

    $output = & wsl.exe @wslArgs 2>$null
    if (-not $output) {
        throw "Failed to query WSL IP. Confirm that WSL is installed and the target distro is running."
    }

    $ipv4 = ($output -join " ").Trim().Split(" ", [System.StringSplitOptions]::RemoveEmptyEntries) |
        Where-Object { $_ -match '^\d{1,3}(\.\d{1,3}){3}$' } |
        Select-Object -First 1

    if (-not $ipv4) {
        throw "Failed to resolve an IPv4 address from WSL output: $output"
    }

    return $ipv4
}

function Remove-PortProxyRule {
    Write-Host "Removing portproxy rule for 127.0.0.1:$Port if it exists..."
    & netsh interface portproxy delete v4tov4 listenaddress=127.0.0.1 listenport=$Port | Out-Null
}

function Show-PortProxyStatus {
    Write-Host "Current portproxy rules:"
    & netsh interface portproxy show v4tov4
}

function Ensure-Administrator {
    if (Test-Administrator) {
        return
    }

    $argumentList = @(
        "-ExecutionPolicy", "Bypass",
        "-File", $PSCommandPath,
        "-Mode", $Mode,
        "-Port", $Port
    )

    if (-not [string]::IsNullOrWhiteSpace($Distro)) {
        $argumentList += @("-Distro", $Distro)
    }

    Start-Process -FilePath "powershell.exe" -Verb RunAs -ArgumentList $argumentList | Out-Null
    Write-Host "Elevation requested. Approve the UAC prompt to refresh localhost:$Port."
    exit 0
}

Ensure-Administrator

switch ($Mode) {
    "install" {
        $wslIp = Get-WslIpAddress
        Remove-PortProxyRule

        Write-Host "Creating portproxy rule: 127.0.0.1:$Port -> $wslIp`:$Port"
        & netsh interface portproxy add v4tov4 listenaddress=127.0.0.1 listenport=$Port connectaddress=$wslIp connectport=$Port protocol=tcp | Out-Null

        Write-Host ""
        Show-PortProxyStatus
        Write-Host ""
        Write-Host "localhost repair completed."
        Write-Host "Verify in Windows:"
        Write-Host "  curl http://localhost:$Port/actuator/health"
        Write-Host "Current WSL URL:"
        Write-Host "  http://$wslIp`:$Port/"
    }
    "remove" {
        Remove-PortProxyRule
        Write-Host ""
        Show-PortProxyStatus
    }
    "status" {
        try {
            $wslIp = Get-WslIpAddress
            Write-Host "Current WSL IP: $wslIp"
            Write-Host "Current WSL URL: http://$wslIp`:$Port/"
        } catch {
            Write-Warning $_
        }
        Write-Host ""
        Show-PortProxyStatus
    }
}
