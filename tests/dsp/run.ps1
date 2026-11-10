$ErrorActionPreference = 'Stop'
$repo = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$out = Join-Path $PSScriptRoot 'classes'
New-Item -ItemType Directory -Force -Path $out | Out-Null
$source = Join-Path $repo 'app\src\main\java\org\example\phonehearingassistant\dsp'
& javac -encoding UTF-8 -d $out (Join-Path $source 'DspConfig.java') (Join-Path $source 'Fft.java') (Join-Path $source 'HearingDsp.java') (Join-Path $PSScriptRoot 'DspTest.java')
if ($LASTEXITCODE -ne 0) { throw 'DSP compilation failed' }
& java -cp $out org.example.phonehearingassistant.dsp.DspTest
if ($LASTEXITCODE -ne 0) { throw 'DSP test failure' }
