#requires -Version 5.1
param(
    [Parameter(Mandatory = $true)]
    [string]$Out
)

Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

$script:Nodes = 0
$walker = [System.Windows.Automation.TreeWalker]::ControlViewWalker

function Get-UiaNode([System.Windows.Automation.AutomationElement]$el, [int]$depth) {
    if ($script:Nodes -ge 600) { return $null }
    $script:Nodes++
    $cur = $el.Current
    $rect = $cur.BoundingRectangle
    $patterns = @()
    try {
        foreach ($p in $el.GetSupportedPatterns()) { $patterns += $p.ProgrammaticName }
    } catch { }
    $node = [ordered]@{
        ControlType       = $cur.ControlType.ProgrammaticName
        Name              = $cur.Name
        AutomationId      = $cur.AutomationId
        BoundingRectangle = [ordered]@{
            X = $rect.X; Y = $rect.Y; Width = $rect.Width; Height = $rect.Height
        }
        patterns          = $patterns
        Children          = @()
    }
    if ($depth -ge 20) { return $node }
    $child = $walker.GetFirstChild($el)
    while ($child -and $script:Nodes -lt 600) {
        try { $next = $walker.GetNextSibling($child) } catch { $next = $null }
        try {
            $sub = Get-UiaNode $child ($depth + 1)
            if ($sub) { $node.Children += $sub }
        } catch { }
        $child = $next
    }
    return $node
}

$proc = Get-Process -Name 'berloga-launcher' -ErrorAction SilentlyContinue |
    Where-Object { $_.MainWindowHandle -ne [IntPtr]::Zero } |
    Select-Object -First 1
if (-not $proc) { throw 'berloga-launcher main window not found' }

$root = [System.Windows.Automation.AutomationElement]::FromHandle($proc.MainWindowHandle)
if (-not $root) { throw 'UI Automation element not found' }
$tree = Get-UiaNode $root 1
$json = $tree | ConvertTo-Json -Depth 80
[System.IO.File]::WriteAllText($Out, $json)
