# Original synthesized two-note cuckoo. No external samples or dependencies.
# Generates mono PCM16 WAV: 32 kHz, 0.72 s, smooth attacks/releases, peak below -9 dBFS.
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$output=Join-Path $root 'core-runtime/src/androidMain/res/raw/kuku_notification.wav'
[IO.Directory]::CreateDirectory((Split-Path $output -Parent)) | Out-Null
$rate=32000
$count=[int]($rate*0.72)
$stream=[IO.File]::Create($output)
$writer=[IO.BinaryWriter]::new($stream)
try {
    $writer.Write([Text.Encoding]::ASCII.GetBytes('RIFF'));$writer.Write([int](36+$count*2))
    $writer.Write([Text.Encoding]::ASCII.GetBytes('WAVEfmt '));$writer.Write([int]16)
    $writer.Write([int16]1);$writer.Write([int16]1);$writer.Write([int]$rate)
    $writer.Write([int]($rate*2));$writer.Write([int16]2);$writer.Write([int16]16)
    $writer.Write([Text.Encoding]::ASCII.GetBytes('data'));$writer.Write([int]($count*2))
    for($i=0;$i -lt $count;$i++) {
        $t=$i/[double]$rate
        $sample=0.0
        foreach($note in @(@(0.045,0.22,830.0),@(0.345,0.29,660.0))) {
            $u=$t-$note[0];$length=$note[1]
            if($u -ge 0 -and $u -lt $length) {
                $attack=[Math]::Min(1.0,$u/0.025)
                $release=[Math]::Min(1.0,($length-$u)/0.085)
                $envelope=[Math]::Pow([Math]::Sin($attack*[Math]::PI/2),2)*[Math]::Pow([Math]::Sin($release*[Math]::PI/2),2)
                $phase=2*[Math]::PI*($note[2]*$u-35*$u*$u)
                $sample+=0.29*$envelope*([Math]::Sin($phase)+0.1*[Math]::Sin(2*$phase))
            }
        }
        $writer.Write([int16][Math]::Round($sample*32767))
    }
} finally {$writer.Dispose();$stream.Dispose()}
Write-Output $output
