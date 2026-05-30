param(
    [Parameter(Mandatory = $true)]
    [string]$ListenAddress,

    [Parameter(Mandatory = $true)]
    [int]$ListenPort,

    [Parameter(Mandatory = $true)]
    [string]$TargetAddress,

    [Parameter(Mandatory = $true)]
    [int]$TargetPort
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Parse($ListenAddress), $ListenPort)
$listener.Server.SetSocketOption([System.Net.Sockets.SocketOptionLevel]::Socket, [System.Net.Sockets.SocketOptionName]::ReuseAddress, $true)
$listener.Start()

Write-Host "proxy_listening $ListenAddress`:$ListenPort -> $TargetAddress`:$TargetPort"

try {
    while ($true) {
        $client = $listener.AcceptTcpClient()
        $client.NoDelay = $true

        $handler = {
            $localClient = $client
            $upstream = $null

            try {
                $upstream = [System.Net.Sockets.TcpClient]::new()
                $upstream.NoDelay = $true
                $upstream.Connect($TargetAddress, $TargetPort)

                $clientToTarget = $localClient.GetStream().CopyToAsync($upstream.GetStream())
                $targetToClient = $upstream.GetStream().CopyToAsync($localClient.GetStream())

                [System.Threading.Tasks.Task]::WaitAny(@($clientToTarget, $targetToClient)) | Out-Null
            } catch {
                Write-Warning $_
            } finally {
                if ($upstream -ne $null) {
                    $upstream.Dispose()
                }
                $localClient.Dispose()
            }
        }.GetNewClosure()

        [System.Threading.Tasks.Task]::Run([Action]$handler) | Out-Null
    }
} finally {
    $listener.Stop()
}
