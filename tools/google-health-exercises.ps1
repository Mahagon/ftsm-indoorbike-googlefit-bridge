param(
    [Parameter(Mandatory = $true)]
    [string]$Start,

    [Parameter(Mandatory = $true)]
    [string]$End,

    [string]$PackageName = "dev.frakw.ftmsbridge"
)

$ErrorActionPreference = "Stop"
$accessToken = $env:GOOGLE_HEALTH_ACCESS_TOKEN
if ([string]::IsNullOrWhiteSpace($accessToken)) {
    throw "Set GOOGLE_HEALTH_ACCESS_TOKEN for a user authorized with googlehealth.activity_and_fitness.readonly."
}

$filter = "exercise.interval.civil_start_time >= `"$Start`" AND exercise.interval.civil_start_time < `"$End`""
$encodedFilter = [Uri]::EscapeDataString($filter)
$baseUrl = "https://health.googleapis.com/v4/users/me/dataTypes/exercise/dataPoints?pageSize=25&filter=$encodedFilter"
$headers = @{ Authorization = "Bearer $accessToken" }
$dataPoints = @()
$pageToken = $null

do {
    $url = $baseUrl
    if (-not [string]::IsNullOrWhiteSpace($pageToken)) {
        $url += "&pageToken=$([Uri]::EscapeDataString($pageToken))"
    }
    $response = Invoke-RestMethod -Method Get -Uri $url -Headers $headers
    if ($response.dataPoints) {
        $dataPoints += $response.dataPoints
    }
    $pageToken = $response.nextPageToken
} while (-not [string]::IsNullOrWhiteSpace($pageToken))

$dataPointMatches = $dataPoints | Where-Object {
    $_.dataSource.platform -eq "HEALTH_CONNECT" -and
    $_.dataSource.application.packageName -eq $PackageName
}

$dataPointMatches | Select-Object name, dataSource, exercise | ConvertTo-Json -Depth 20
