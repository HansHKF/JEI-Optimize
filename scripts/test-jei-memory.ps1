[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][ValidateSet('atm9','atm11')][string]$Pack,
    [Parameter(Mandatory=$true)][ValidateSet('off','on')][string]$Profile,
    [Parameter(Mandatory=$true)][string]$Label,
    [Parameter(Mandatory=$true)][string]$WorldTemplate,
    [Parameter(Mandatory=$true)][string]$LauncherPath,
    [Parameter(Mandatory=$true)][string]$JavaHome,
    [string]$AgentJdkHome=$env:JAVA_HOME,
    [string]$InstancesRoot=(Join-Path $env:APPDATA 'PrismLauncher\instances'),
    [string]$StudyDirectory,
    [string]$JetJar,
    [switch]$HeapDump,
    [switch]$UseWorkspaceJet,
    [switch]$MemoryDiagnostics,
    [int]$MaxHeapMiB=8192
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$repo=Split-Path -Parent $PSScriptRoot
if(!$StudyDirectory){$StudyDirectory=Join-Path $repo 'build\jei-memory'}
$study=[IO.Path]::GetFullPath($StudyDirectory)
if($Label -notmatch '^[a-zA-Z0-9][a-zA-Z0-9._-]*$'){throw 'Label must be a simple directory name.'}
$output=Join-Path $study $Label
if(Test-Path -LiteralPath $output){throw "Output already exists: $output"}
$name=if($Pack -eq 'atm9'){'All the Mods 9 - ATM9'}else{'All the Mods 11-0.11.1-beta'}
$instanceRoot=Join-Path $InstancesRoot $name
$gameRoot=Join-Path $instanceRoot 'minecraft'
$launcher=$LauncherPath
$javaRoot=$JavaHome
$jcmd=Join-Path $javaRoot 'bin\jcmd.exe'
$worldName='JET-Memory-Probe'
$world=Join-Path $gameRoot "saves\$worldName"
$template=[IO.Path]::GetFullPath($WorldTemplate)
$utf8=[Text.UTF8Encoding]::new($false)
if(Get-Process -Name prismlauncher -ErrorAction SilentlyContinue){throw 'Close Prism before preparing a controlled run.'}
$running=@(Get-CimInstance Win32_Process | Where-Object {$_.Name -in @('java.exe','javaw.exe') -and $_.CommandLine -match 'org.prismlauncher.EntryPoint|BootstrapLauncher|forgeclient|clientRun'})
if($running.Count){throw 'A Minecraft process is already running.'}
if(!(Test-Path -LiteralPath $template)){throw "World template missing: $template"}
if(!(Test-Path -LiteralPath (Join-Path $template 'level.dat'))){throw 'WorldTemplate must name a world directory containing level.dat.'}
foreach($required in @($launcher,$jcmd,(Join-Path $AgentJdkHome 'bin\javac.exe'),(Join-Path $AgentJdkHome 'bin\jar.exe'))){
    if(!(Test-Path -LiteralPath $required)){throw "Required executable missing: $required"}
}
if($template.StartsWith([IO.Path]::GetFullPath($world),[StringComparison]::OrdinalIgnoreCase)){throw 'The template must be outside the disposable test world.'}
if([IO.Path]::GetFullPath($world).StartsWith($template.TrimEnd('\')+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'The disposable world must not be inside its template.'}
New-Item -ItemType Directory -Path $study -Force | Out-Null
$agentClasses=Join-Path $study 'agent-classes'
New-Item -ItemType Directory -Path $agentClasses -Force | Out-Null
& (Join-Path $AgentJdkHome 'bin\javac.exe') --release 17 -d $agentClasses (Join-Path $PSScriptRoot 'memory\MemoryProbe.java')
if($LASTEXITCODE -ne 0){throw 'Diagnostic agent compilation failed'}
$manifest=Join-Path $study 'agent-manifest.mf'
[IO.File]::WriteAllText($manifest,"Premain-Class: MemoryProbe`n`n",$utf8)
$agentJar=Join-Path $study 'memory-probe.jar'
& (Join-Path $AgentJdkHome 'bin\jar.exe') --create --file $agentJar --manifest $manifest -C $agentClasses .
if($LASTEXITCODE -ne 0){throw 'Diagnostic agent packaging failed'}
if(Test-Path -LiteralPath $world){
    $resolved=[IO.Path]::GetFullPath($world)
    if($resolved -ne [IO.Path]::GetFullPath((Join-Path $gameRoot 'saves\JET-Memory-Probe'))){throw 'Unsafe test world path'}
    if(!(Test-Path -LiteralPath (Join-Path $resolved '.jet-memory-probe'))){throw 'Existing test world has no ownership marker; move it before running this script.'}
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
New-Item -ItemType Directory -Path (Split-Path -Parent $world) -Force | Out-Null
Copy-Item -LiteralPath $template -Destination $world -Recurse
[IO.File]::WriteAllText((Join-Path $world '.jet-memory-probe'),'Disposable world created by test-jei-memory.ps1',$utf8)
New-Item -ItemType Directory -Path $output -Force | Out-Null
$paths=@{
    instance=(Join-Path $instanceRoot 'instance.cfg')
    config=(Join-Path $gameRoot 'config\justenoughthreads-client.toml')
    options=(Join-Path $gameRoot 'options.txt')
    memoryConfig=(Join-Path $gameRoot 'config\memorysettings.json')
}
$original=@{}
foreach($key in $paths.Keys){
    if(Test-Path -LiteralPath $paths[$key]){
        $original[$key]=[IO.File]::ReadAllBytes($paths[$key])
        [IO.File]::WriteAllBytes((Join-Path $output "original-$key.txt"),$original[$key])
    }else{$original[$key]=$null}
}
function Ini-Value([string]$text,[string]$key,[string]$value){
    $pattern='(?m)^'+[regex]::Escape($key)+'=.*$'
    if([regex]::IsMatch($text,$pattern)){return [regex]::Replace($text,$pattern,[Text.RegularExpressions.MatchEvaluator]{param($m) "$key=$value"})}
    return $text.Replace('[General]',"[General]`r`n$key=$value")
}
function Read-Shared([string]$path){
    if(!(Test-Path -LiteralPath $path)){return ''}
    $stream=[IO.File]::Open($path,'Open','Read','ReadWrite')
    try{$reader=[IO.StreamReader]::new($stream);try{return $reader.ReadToEnd()}finally{$reader.Dispose()}}finally{$stream.Dispose()}
}
function Stage([string]$stage){
    $stamp=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    Add-Content -LiteralPath (Join-Path $output 'stages.csv') -Value "$stamp,$stage" -Encoding utf8
    Write-Host "$Label : $stage"
}
function Capture([string]$stage){
    Stage "$stage-capture-start"
    & $jcmd $script:game.Id GC.class_histogram | Set-Content (Join-Path $output "$stage-histogram.txt") -Encoding utf8
    if($LASTEXITCODE -ne 0){throw 'Histogram failed'}
    & $jcmd $script:game.Id GC.heap_info | Set-Content (Join-Path $output "$stage-heap.txt") -Encoding utf8
    & $jcmd $script:game.Id VM.native_memory summary | Set-Content (Join-Path $output "$stage-nmt.txt") -Encoding utf8
    Stage "$stage-capture-end"
}
$game=$null
$launcherProcess=$null
$addedJar=$null
$replacedJar=$null
$start=Get-Date
$status='preparing'
try{
    if($null -ne $original.memoryConfig){
        $memorySettings=$utf8.GetString($original.memoryConfig) | ConvertFrom-Json
        $memorySettings.disableWarnings.disableWarnings=$true
        [IO.File]::WriteAllText($paths.memoryConfig,($memorySettings | ConvertTo-Json -Depth 12),$utf8)
    }
    $target=if($Pack -eq 'atm9'){'1.20.1-forge'}else{'26.1.2-neoforge'}
    $source=if($JetJar){[IO.Path]::GetFullPath($JetJar)}else{
        $built=@(Get-ChildItem -LiteralPath (Join-Path $repo "versions\$target\build\libs") -Filter '*.jar' | Where-Object {$_.Name -notmatch 'sources|dev|javadoc'} | Sort-Object LastWriteTime -Descending)
        if(!$built.Count){throw "Build the $target release JAR before testing."}
        $built[0].FullName
    }
    $existingJet=@(Get-ChildItem -LiteralPath (Join-Path $gameRoot 'mods') -Filter 'justenoughthreads*.jar')
    if(!$existingJet.Count){
        $addedJar=Join-Path $gameRoot ('mods\'+[IO.Path]::GetFileName($source))
        Copy-Item -LiteralPath $source -Destination $addedJar
    }elseif($UseWorkspaceJet){
        if($existingJet.Count -ne 1){throw 'Select a single JET version before testing.'}
        $replacedJar=$existingJet[0].FullName
        Copy-Item -LiteralPath $replacedJar -Destination (Join-Path $output 'original-jet.jar')
        Copy-Item -LiteralPath $source -Destination $replacedJar -Force
    }
    $config=if($null -ne $original.config){$utf8.GetString($original.config)}else{
        "[general]`nenabled = true`n[async]`nasyncStartup = true`nasyncIngredientFilter = true`ntooltipSearchIndex = false`n[diagnostics]`nmemoryDiagnostics = false`n"
    }
    $enabled=if($Profile -eq 'on'){'true'}else{'false'}
    $config=[regex]::Replace($config,'(?m)^\s*enabled\s*=.*$',"enabled = $enabled")
    $config=[regex]::Replace($config,'(?m)^\s*tooltipSearchIndex\s*=.*$','tooltipSearchIndex = false')
    $config=[regex]::Replace($config,'(?m)^\s*(pluginTiming|registrationCounts|tooltipSearchMetrics|tooltipSearchShadow|tooltipSearchValidate)\s*=.*$','$1 = false')
    $memoryValue=if($MemoryDiagnostics){'true'}else{'false'}
    if($config -match '(?m)^\s*memoryDiagnostics\s*='){
        $config=[regex]::Replace($config,'(?m)^\s*memoryDiagnostics\s*=.*$',"memoryDiagnostics = $memoryValue")
    }elseif($config -match '(?m)^\[diagnostics\]\s*$'){
        $config=[regex]::Replace($config,'(?m)^\[diagnostics\]\s*$',"[diagnostics]`nmemoryDiagnostics = $memoryValue")
    }else{$config+="`n[diagnostics]`nmemoryDiagnostics = $memoryValue`n"}
    New-Item -ItemType Directory -Path (Split-Path -Parent $paths.config) -Force | Out-Null
    [IO.File]::WriteAllText($paths.config,$config,$utf8)
    [IO.File]::WriteAllText((Join-Path $output 'config.toml'),$config,$utf8)
    $prism=$utf8.GetString($original.instance)
    foreach($setting in @(@('OverrideJavaArgs','true'),@('OverrideMemory','true'),@('MinMemAlloc','1024'),@('MaxMemAlloc',"$MaxHeapMiB"),@('OverrideJavaLocation','true'),@('AutomaticJava','false'),@('JavaPath',(Join-Path $javaRoot 'bin\javaw.exe').Replace('\','/')))){
        $prism=Ini-Value $prism $setting[0] $setting[1]
    }
    $agent=$agentJar.Replace('\','/')
    $normalizedOutput=$output.Replace('\','/')
    $jvmOptions="-Djet.benchmark=true -Djet.benchmark.autoExit=false `"-javaagent:$agent=$normalizedOutput`" -XX:NativeMemoryTracking=summary -XX:+UseG1GC `"-Xlog:gc*:file=$normalizedOutput/gc.log:time,uptime,level,tags`" `"-XX:StartFlightRecording=name=memory,settings=profile,dumponexit=true,maxsize=512m,filename=$normalizedOutput/recording.jfr`""
    if($Pack -eq 'atm9'){$jvmOptions+=' -Djet.benchmark.tooltipPrefix=#'}
    # Qt's INI reader interprets unquoted commas as QStringList separators.
    $prism=Ini-Value $prism 'JvmArgs' ('"'+$jvmOptions.Replace('"','\"')+'"')
    [IO.File]::WriteAllText($paths.instance,$prism,$utf8)
    $options=if($null -ne $original.options){$utf8.GetString($original.options)}else{"version:3465`n"}
    foreach($setting in @(@('pauseOnLostFocus','false'),@('renderDistance','6'),@('simulationDistance','4'),@('maxFps','60'),@('enableVsync','false'),@('fullscreen','false'),@('onboardAccessibility','false'))){
        $pattern='(?m)^'+$setting[0]+':.*$'
        if([regex]::IsMatch($options,$pattern)){$options=[regex]::Replace($options,$pattern,($setting[0]+':'+$setting[1]))}else{$options+="`n$($setting[0]):$($setting[1])`n"}
    }
    [IO.File]::WriteAllText($paths.options,$options,$utf8)
    Get-ChildItem -LiteralPath (Join-Path $gameRoot 'mods') -Filter '*.jar' | Sort-Object Name | ForEach-Object {
        [pscustomobject]@{Name=$_.Name;Bytes=$_.Length;Sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash}
    } | Export-Csv (Join-Path $output 'mods.csv') -NoTypeInformation -Encoding utf8
    Stage 'launch'
    $launcherProcess=Start-Process -FilePath $launcher -ArgumentList @('--launch',('"'+$name+'"')) -WindowStyle Hidden -PassThru
    $deadline=(Get-Date).AddMinutes(20)
    $phase='menu'
    $phaseStarted=Get-Date
    while((Get-Date) -lt $deadline){
        if($null -eq $game){
            $pidFile=Join-Path $output 'process.pid'
            if(Test-Path -LiteralPath $pidFile){
                $game=Get-Process -Id ([int]([IO.File]::ReadAllText($pidFile)))
                $candidate=Get-CimInstance Win32_Process -Filter "ProcessId=$($game.Id)"
                [pscustomobject]@{Pid=$game.Id;Pack=$Pack;Profile=$Profile;Java=$candidate.ExecutablePath;MaxHeapMiB=$MaxHeapMiB;World=$worldName} | ConvertTo-Json | Set-Content (Join-Path $output 'environment.json') -Encoding utf8
                Stage 'process-found'
            }else{
                if(((Get-Date)-$start).TotalSeconds -ge 180){throw 'No diagnostic client started within 180 seconds.'}
                Start-Sleep -Milliseconds 1000;continue
            }
        }
        $game.Refresh()
        if($game.HasExited){throw "Client exited during $phase"}
        "$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()),$($game.WorkingSet64),$($game.PrivateMemorySize64)" | Add-Content (Join-Path $output 'process-samples.csv') -Encoding utf8
        $state=Read-Shared (Join-Path $output 'state.txt')
        $currentLogPath=Join-Path $gameRoot 'logs\latest.log'
        $log=''
        if((Test-Path -LiteralPath $currentLogPath) -and (Get-Item -LiteralPath $currentLogPath).LastWriteTime -ge $start){
            $log=Read-Shared $currentLogPath
        }
        if($phase -eq 'menu' -and $log -match 'Game took [\d.]+ seconds to start'){
            [IO.File]::WriteAllText((Join-Path $output 'inspect-enabled.txt'),'Startup complete',$utf8)
        }
        if($state -match 'LoadingErrorScreen' -or $log -match 'Crash report saved to .+-fml.txt'){
            throw 'The modpack failed before the test world loaded; this is not a valid memory sample.'
        }
        if($phase -eq 'menu' -and $state -match 'screen=(net.minecraft.client.gui.screens.TitleScreen|dev.shadowsoffire.packmenu.ExtendedMenuScreen)'){
            Stage 'main-menu'
            Capture 'menu'
            [IO.File]::WriteAllText((Join-Path $output 'command.txt'),"join $worldName",$utf8)
            Stage 'world-requested'
            $phase='loading'
        }elseif($phase -eq 'loading' -and $log -match 'JET benchmark complete: queries=120'){
            Stage 'search-complete'
            $phase='settling';$phaseStarted=Get-Date
        }elseif($phase -eq 'settling' -and ((Get-Date)-$phaseStarted).TotalSeconds -ge 20){
            Stage 'ready'
            Capture 'ready'
            if($HeapDump){
                Stage 'heap-dump-start'
                & $jcmd $game.Id GC.heap_dump (Join-Path $output 'ready.hprof') | Set-Content (Join-Path $output 'heap-dump.txt') -Encoding utf8
                if($LASTEXITCODE -ne 0){throw 'Heap dump failed'}
                Stage 'heap-dump-end'
            }
            [IO.File]::WriteAllText((Join-Path $output 'command.txt'),'leave',$utf8)
            $phase='leaving'
        }elseif($phase -eq 'leaving' -and $state -match 'level=false' -and $state -match 'TitleScreen|ExtendedMenuScreen'){
            Stage 'world-unloaded';$phase='unload-settling';$phaseStarted=Get-Date
        }elseif($phase -eq 'unload-settling' -and ((Get-Date)-$phaseStarted).TotalSeconds -ge 15){
            Capture 'unloaded'
            Stage 'stop-requested'
            [IO.File]::WriteAllText((Join-Path $output 'command.txt'),'stop',$utf8)
            if(!$game.WaitForExit(90000)){throw 'Client did not stop'}
            $status='complete';Stage 'complete';break
        }
        if(Test-Path -LiteralPath (Join-Path $output 'probe-error.txt')){throw (Read-Shared (Join-Path $output 'probe-error.txt'))}
        Start-Sleep -Milliseconds 1000
    }
    if($status -ne 'complete'){throw "Timed out during $phase"}
}finally{
    if($null -ne $game){
        $game.Refresh()
        if(!$game.HasExited){
            [void]$game.CloseMainWindow()
            if(!$game.WaitForExit(60000)){throw 'Test client remains running; stop it before restoring or beginning another run.'}
        }
        foreach($file in @('latest.log','debug.log')){Copy-Item -LiteralPath (Join-Path $gameRoot "logs\$file") -Destination (Join-Path $output $file) -Force}
    }
    if($null -ne $launcherProcess){
        $launcherProcess.Refresh()
        if(!$launcherProcess.HasExited){[void]$launcherProcess.CloseMainWindow();if(!$launcherProcess.WaitForExit(10000)){Stop-Process -Id $launcherProcess.Id}}
    }
    foreach($key in $paths.Keys){
        if($null -ne $original[$key]){[IO.File]::WriteAllBytes($paths[$key],$original[$key])}
        elseif(Test-Path -LiteralPath $paths[$key]){Remove-Item -LiteralPath $paths[$key]}
    }
    if($null -ne $addedJar -and (Test-Path -LiteralPath $addedJar)){Remove-Item -LiteralPath $addedJar}
    if($null -ne $replacedJar){Copy-Item -LiteralPath (Join-Path $output 'original-jet.jar') -Destination $replacedJar -Force}
    [pscustomobject]@{Status=$status;Pack=$Pack;Profile=$Profile;WallSeconds=((Get-Date)-$start).TotalSeconds} | ConvertTo-Json | Set-Content (Join-Path $output 'status.json') -Encoding utf8
}
