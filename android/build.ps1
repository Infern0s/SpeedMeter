param([Parameter(Mandatory)][string]$Jdk,[Parameter(Mandatory)][string]$Platform,[Parameter(Mandatory)][string]$BuildTools)
$ErrorActionPreference='Stop'
function Invoke-BuildTool {
    param([string]$Tool,[string[]]$Arguments)
    & $Tool @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Build tool failed: $Tool ($LASTEXITCODE)" }
}
$source=Join-Path $PSScriptRoot 'src\main'
$project=Split-Path (Split-Path $PSScriptRoot)
$build=Join-Path $project 'build'
$temp=Join-Path $project ('work\android-' + [guid]::NewGuid().ToString('N'))
$classes=New-Item -ItemType Directory -Path (Join-Path $temp 'classes') -Force
$android=Join-Path $Platform 'android.jar'
$env:JAVA_HOME=$Jdk
$env:PATH=(Join-Path $Jdk 'bin')+';'+$env:PATH
$apk=Join-Path $temp 'unsigned.apk'
$assembled=Join-Path $temp 'assembled.apk'
$aligned=Join-Path $temp 'aligned.apk'
$dest=Join-Path $build 'SpeedMeter-Android.apk'
$key=Join-Path $project 'work\android-debug.keystore'
Invoke-BuildTool (Join-Path $Jdk 'bin\javac.exe') @('-encoding','UTF-8','-source','8','-target','8','-cp',$android,'-d',$classes.FullName,(Join-Path $source 'java\local\speedmeter\MainActivity.java'))
$inputs=@(Get-ChildItem $classes -Recurse -Filter '*.class' | Select-Object -ExpandProperty FullName)
Invoke-BuildTool (Join-Path $BuildTools 'd8.bat') (@('--lib',$android,'--min-api','23','--output',$temp)+$inputs)
Invoke-BuildTool (Join-Path $BuildTools 'aapt2.exe') @('link','-I',$android,'--manifest',(Join-Path $source 'AndroidManifest.xml'),'-o',$apk)
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip=[IO.Compression.ZipFile]::Open($apk,[IO.Compression.ZipArchiveMode]::Update)
try { [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,(Join-Path $temp 'classes.dex'),'classes.dex') | Out-Null }
finally { $zip.Dispose() }
Invoke-BuildTool (Join-Path $BuildTools 'zipalign.exe') @('-f','4',$apk,$aligned)
if (!(Test-Path -LiteralPath $key)) {
    Invoke-BuildTool (Join-Path $Jdk 'bin\keytool.exe') @('-genkeypair','-keystore',$key,'-storepass','android','-keypass','android','-alias','androiddebugkey','-keyalg','RSA','-keysize','2048','-validity','10000','-dname','CN=Android Debug,O=Android,C=US')
}
Invoke-BuildTool (Join-Path $BuildTools 'apksigner.bat') @('sign','--ks',$key,'--ks-pass','pass:android','--out',$assembled,$aligned)
Invoke-BuildTool (Join-Path $BuildTools 'apksigner.bat') @('verify','--verbose',$assembled)
Copy-Item -LiteralPath $assembled -Destination $dest -Force
