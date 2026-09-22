# Campaign content, checked without opening Unity.
#
# The compile gate proves the C# builds. It says nothing about whether a level
# the player is offered can actually be loaded: a scene missing from
# EditorBuildSettings, a background sprite whose sub-id does not exist, a
# spawnable species with no data asset or no prefab for the factory to find are
# all green to the compiler and broken in the game.
#
# Everything here is read from the YAML on disk, so it runs with the editor open
# and holding the project lock. It asserts invariants of the campaign as a whole,
# not of one task's level.
#
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File Tools\warden-content.ps1

param([Parameter(Mandatory = $true)][string]$ProjectRoot)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath $ProjectRoot).Path
Set-Location $root

# BirdType's values, read from the enum so this file cannot drift from it.
$birdTypeNames = @{}
foreach ($m in [regex]::Matches((Get-Content -Raw (Join-Path $root 'Assets/Scripts/BirdType.cs')),
        '(?m)^\s*(\w+)\s*=\s*(\d+)\s*,?\s*$')) {
    $birdTypeNames[$m.Groups[2].Value] = $m.Groups[1].Value
}

$problems = New-Object System.Collections.Generic.List[string]
function Fail([string]$message) { $problems.Add($message) }

# --- guid -> asset path, from every .meta in the project -----------------------
$guidToPath = @{}
Get-ChildItem -Path (Join-Path $root 'Assets'), (Join-Path $root 'ProjectSettings') `
        -Recurse -Filter *.meta -File -ErrorAction SilentlyContinue | ForEach-Object {
    $guidLine = Select-String -Path $_.FullName -Pattern '^guid: ([0-9a-f]{32})' -List
    if ($guidLine) {
        $guidToPath[$guidLine.Matches[0].Groups[1].Value] =
            $_.FullName.Substring(0, $_.FullName.Length - 5)
    }
}

function Resolve-Guid([string]$guid) {
    if ($guidToPath.ContainsKey($guid)) { return $guidToPath[$guid] }
    return $null
}

function Get-Field([string]$text, [string]$name) {
    $m = [regex]::Match($text, "(?m)^\s*${name}:\s*(.+?)\s*$")
    if ($m.Success) { return $m.Groups[1].Value }
    return $null
}

# --- the levels the player is offered ------------------------------------------
$registryPath = Join-Path $root 'Assets/Resources/LevelRegistry.asset'
if (-not (Test-Path $registryPath)) { Fail "no LevelRegistry at $registryPath" }
$registry = Get-Content -Raw $registryPath
$levelGuids = [regex]::Matches($registry, 'guid:\s*([0-9a-f]{32})') |
    ForEach-Object { $_.Groups[1].Value } | Select-Object -Skip 1   # first is m_Script

$buildSettings = Get-Content -Raw (Join-Path $root 'ProjectSettings/EditorBuildSettings.asset')

$checked = 0
foreach ($guid in $levelGuids) {
    $configPath = Resolve-Guid $guid
    if (-not $configPath) { Fail "LevelRegistry lists guid $guid, which no asset provides"; continue }
    $config = Get-Content -Raw $configPath
    $name = [System.IO.Path]::GetFileNameWithoutExtension($configPath)
    $checked++

    # The sandbox lab is reached from the menu's own button, not from the chain.
    $sceneName = Get-Field $config 'sceneName'
    $scene = $null
    if ([string]::IsNullOrWhiteSpace($sceneName)) {
        Fail "$name has no sceneName; the menu cannot load it"
    }
    else {
        $entry = [regex]::Match($buildSettings,
            "(?m)^\s*path:\s*(?<path>\S*/${sceneName}\.unity)\s*$\s*^\s*guid:\s*(?<guid>[0-9a-f]{32})")
        if (-not $entry.Success) {
            Fail "$name loads scene '$sceneName', which is not in EditorBuildSettings; SceneManager.LoadScene would fail"
        }
        else {
            $scenePath = Join-Path $root $entry.Groups['path'].Value
            if (-not (Test-Path $scenePath)) {
                Fail "$name's scene is registered as $($entry.Groups['path'].Value), and no such file exists"
            }
            else {
                $scene = Get-Content -Raw -LiteralPath $scenePath
                $sceneGuid = (Select-String -Path "$scenePath.meta" -Pattern '^guid: ([0-9a-f]{32})' -List).Matches[0].Groups[1].Value
                if ($sceneGuid -ne $entry.Groups['guid'].Value) {
                    Fail "$name's scene is registered under guid $($entry.Groups['guid'].Value) but its meta says $sceneGuid"
                }
            }
        }
    }

    # The background: both the asset and the sprite inside it.
    $background = [regex]::Match($config, 'backgroundSprite:\s*\{fileID:\s*(-?\d+),\s*guid:\s*([0-9a-f]{32})')
    if ($background.Success) {
        $spritePath = Resolve-Guid $background.Groups[2].Value
        if (-not $spritePath) {
            Fail "$name's background points at guid $($background.Groups[2].Value), which no asset provides"
        }
        # 21300000 is Unity's own id for the single sprite of a texture imported in
        # Single mode; only a Multiple-mode import names its sprites in the table,
        # and only then is there an id to check against anything.
        elseif ($background.Groups[1].Value -notin @('0', '21300000')) {
            $meta = Get-Content -Raw "$spritePath.meta"
            if ($meta -match '(?m)^\s*spriteMode:\s*2\s*$' -and
                $meta -notmatch "internalID:\s*$([regex]::Escape($background.Groups[1].Value))\b") {
                Fail "$name's background names sprite id $($background.Groups[1].Value) inside $(Split-Path -Leaf $spritePath), which that import does not contain"
            }
        }
    }

    # Resolve the scene's BirdFactory entries first, exactly as ResolvePrefabs does.
    # Resources is only a fallback. A dangling scene reference is a content error,
    # even if a same-named Resources prefab would otherwise hide it.
    $scenePrefabs = @{}
    $factoryGuid = (Select-String -LiteralPath (Join-Path $root 'Assets/Scripts/Game/Birds/BirdFactory.cs.meta') `
        -Pattern '^guid: ([0-9a-f]{32})' -List).Matches[0].Groups[1].Value
    $factories = @([regex]::Split([string]$scene, '(?m)^--- !u!') | Where-Object {
        $_ -match "m_Script:.*guid:\s*$factoryGuid\b"
    })
    if ($config -match '(?m)^\s*-\s*type:\s*\d+\s*$' -and $factories.Count -ne 1) {
        Fail "$name must have exactly one serialized BirdFactory; found $($factories.Count)"
    }
    foreach ($factory in $factories) {
        foreach ($item in [regex]::Matches($factory, '(?m)^\s*- type:\s*(\d+)\s*\r?\n\s*prefab:\s*\{fileID:\s*(-?\d+)(?:,\s*guid:\s*([0-9a-f]{32})[^}]*)?\}')) {
            if ($item.Groups[2].Value -eq '0') { continue }
            $asset = Resolve-Guid $item.Groups[3].Value
            if (-not $asset -or -not (Test-Path -LiteralPath $asset) -or
                (Get-Content -Raw -LiteralPath $asset) -notmatch "(?m)^--- !u!114 &$($item.Groups[2].Value)\s*$") {
                Fail "$name's BirdFactory has an unresolved prefab component for birdType $($item.Groups[1].Value)"
                continue
            }
            $scenePrefabs[$item.Groups[1].Value] = $asset
        }
    }
    foreach ($type in [regex]::Matches($config, '(?m)^\s*-\s*type:\s*(\d+)\s*$') |
                ForEach-Object { $_.Groups[1].Value } | Select-Object -Unique) {
        $data = Get-ChildItem -Path (Join-Path $root 'Assets/Resources') -Filter 'BirdData_*.asset' -File -Recurse |
            Where-Object { (Get-Content -Raw $_.FullName) -match "(?m)^\s*birdType:\s*$type\s*$" }
        if (-not $data) {
            Fail "$name spawns birdType $type, and no BirdData asset in Resources declares it"
            continue
        }
        $speciesName = $birdTypeNames[$type]
        if ($scenePrefabs.ContainsKey($type)) { continue }
        $prefab = Join-Path $root "Assets/Resources/Prefabs/Birds/$speciesName.prefab"
        if (-not (Test-Path $prefab)) {
            Fail "$name spawns $speciesName (birdType $type), but neither its scene's BirdFactory nor Resources/Prefabs/Birds/$speciesName.prefab provides a prefab"
        }
    }

    # Both icon slots resolve the same way the background does: a sprite the level
    # names and the import does not contain leaves an empty square in the HUD. The
    # second reader found exactly that on the blackbird's caught counter.
    foreach ($iconMatch in [regex]::Matches($config,
            '(?:icon|caughtBirdIcon|secondaryCaughtBirdIcon):\s*\{fileID:\s*(-?\d+),\s*guid:\s*([0-9a-f]{32})')) {
        $iconId = $iconMatch.Groups[1].Value
        $iconPath = Resolve-Guid $iconMatch.Groups[2].Value
        if (-not $iconPath) {
            Fail "$name names an icon with guid $($iconMatch.Groups[2].Value), which no asset provides"
            continue
        }
        if ($iconId -in @('0', '21300000')) { continue }
        $iconMeta = Get-Content -Raw "$iconPath.meta"
        if ($iconMeta -match '(?m)^\s*spriteMode:\s*1\s*$') {
            Fail "$name addresses $(Split-Path -Leaf $iconPath) as sprite $iconId, but that texture is imported Single-mode, where this project addresses the sprite as 21300000; the icon slot would come up empty"
        }
        elseif ($iconMeta -match '(?m)^\s*spriteMode:\s*2\s*$' -and
                $iconMeta -notmatch "internalID:\s*$([regex]::Escape($iconId))\b") {
            Fail "$name names icon sprite $iconId inside $(Split-Path -Leaf $iconPath), which that import does not contain"
        }
    }
}

if ($checked -eq 0) { Fail 'the registry lists no level' }

if ($problems.Count -gt 0) {
    Write-Host "campaign content is broken in $($problems.Count) place(s):"
    $problems | ForEach-Object { Write-Host "  - $_" }
    exit 1
}
Write-Host "campaign content checked: $checked level(s), scenes registered, backgrounds and species resolved"
exit 0
