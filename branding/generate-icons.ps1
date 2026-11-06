# Regenerate Android vectors and the iOS AppIcon catalog from branding/kuku.svg.
# Windows PowerShell; uses the built-in System.Drawing renderer. No third-party dependencies.
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.Drawing
$root=Split-Path $PSScriptRoot -Parent
$utf=[Text.UTF8Encoding]::new($false)
[xml]$svg=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'kuku.svg'))
$paths=@($svg.svg.path)
$background=$svg.svg.rect.fill
function Write-Text($relative,$text) {
    $target=Join-Path $root $relative
    [IO.Directory]::CreateDirectory((Split-Path $target -Parent)) | Out-Null
    [IO.File]::WriteAllText($target,$text,$utf)
}
function Vector($items,$mono=$false,$notification=$false) {
    $view=if($notification){56}else{108}
    $dp=if($notification){24}else{108}
    $lines=@('<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="'+$dp+'dp" android:height="'+$dp+'dp" android:viewportWidth="'+$view+'" android:viewportHeight="'+$view+'">')
    if($notification){$lines+='<group android:translateX="-27" android:translateY="-28">'}
    foreach($p in $items) {
        $fill=if($mono){'#FFFFFF'}else{$p.fill}
        $lines+='<path android:fillColor="'+$fill+'" android:pathData="'+$p.d+'" />'
    }
    if($notification){$lines+='</group>'}
    ($lines+'</vector>') -join "`n"
}
Write-Text 'androidApp/src/main/res/drawable/ic_launcher_foreground.xml' (Vector $paths)
$silhouette=@($paths | Where-Object {$_.id -in @('bird','beak')})
Write-Text 'androidApp/src/main/res/drawable/ic_launcher_monochrome.xml' (Vector $silhouette $true)
Write-Text 'core-runtime/src/androidMain/res/drawable/ic_notification_kuku.xml' (Vector $silhouette $true $true)
Write-Text 'androidApp/src/main/res/values/colors.xml' ('<resources><color name="launcher_background">'+$background+'</color></resources>')

function Geometry([string]$data) {
    $tokens=[regex]::Matches($data,'[MLCZ]|-?\d+(?:\.\d+)?') | ForEach-Object {$_.Value}
    $shape=[Drawing.Drawing2D.GraphicsPath]::new()
    $i=0; [single]$x=0; [single]$y=0
    while($i -lt $tokens.Count) {
        $command=$tokens[$i]; $i++
        switch($command) {
            M {$x=[single]::Parse($tokens[$i],[Globalization.CultureInfo]::InvariantCulture); $y=[single]::Parse($tokens[$i+1],[Globalization.CultureInfo]::InvariantCulture); $i+=2; $shape.StartFigure()}
            L {$nx=[single]::Parse($tokens[$i],[Globalization.CultureInfo]::InvariantCulture); $ny=[single]::Parse($tokens[$i+1],[Globalization.CultureInfo]::InvariantCulture); $shape.AddLine($x,$y,$nx,$ny); $x=$nx; $y=$ny; $i+=2}
            C {$v=@(); for($n=0;$n -lt 6;$n++) {$v+=[single]::Parse($tokens[$i+$n],[Globalization.CultureInfo]::InvariantCulture)}; $shape.AddBezier($x,$y,$v[0],$v[1],$v[2],$v[3],$v[4],$v[5]); $x=$v[4]; $y=$v[5]; $i+=6}
            Z {$shape.CloseFigure()}
            default {throw "Unsupported SVG command: $command"}
        }
    }
    return $shape
}
# Opaque RGB PNG: iOS applies its own corner mask.
$master=[Drawing.Bitmap]::new(2048,2048,[Drawing.Imaging.PixelFormat]::Format24bppRgb)
$graphics=[Drawing.Graphics]::FromImage($master)
try {
    $graphics.Clear([Drawing.ColorTranslator]::FromHtml($background))
    $graphics.SmoothingMode=[Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $graphics.ScaleTransform((2048.0/108),(2048.0/108))
    foreach($path in $paths) {
        $geometry=Geometry $path.d
        $brush=[Drawing.SolidBrush]::new([Drawing.ColorTranslator]::FromHtml($path.fill))
        try {$graphics.FillPath($brush,$geometry)} finally {$brush.Dispose();$geometry.Dispose()}
    }
} finally {$graphics.Dispose()}
$catalog='iosApp/iosApp/Assets.xcassets'
Write-Text "$catalog/Contents.json" '{"info":{"author":"xcode","version":1}}'
$entries=@()
foreach($idiom in @('iphone','ipad','ios-marketing')) {
    $sizes=switch($idiom){iphone {@(20,29,40,60)} ipad {@(20,29,40,76,83.5)} default {@(1024)}}
    foreach($size in $sizes) {
        $scales=if($idiom -eq 'iphone'){@(2,3)}elseif($idiom -eq 'ios-marketing'){@(1)}elseif($size -eq 83.5){@(2)}else{@(1,2)}
        foreach($scale in $scales) {
            $px=[int]($size*$scale); $filename="icon-$px.png"
            $target=Join-Path $root "$catalog/AppIcon.appiconset/$filename"
            [IO.Directory]::CreateDirectory((Split-Path $target -Parent)) | Out-Null
            $bitmap=[Drawing.Bitmap]::new($px,$px,[Drawing.Imaging.PixelFormat]::Format24bppRgb)
            $g=[Drawing.Graphics]::FromImage($bitmap)
            try {
                $g.InterpolationMode=[Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
                $g.DrawImage($master,0,0,$px,$px)
                $bitmap.Save($target,[Drawing.Imaging.ImageFormat]::Png)
            } finally {$g.Dispose();$bitmap.Dispose()}
            $label=$size.ToString([Globalization.CultureInfo]::InvariantCulture)
            $entries+=@{idiom=$idiom;size="${label}x${label}";scale="${scale}x";filename=$filename}
        }
    }
}
$master.Dispose()
Write-Text "$catalog/AppIcon.appiconset/Contents.json" (@{images=$entries;info=@{author='xcode';version=1}} | ConvertTo-Json -Depth 5)
Write-Output 'Generated Android foreground/monochrome/notification vectors and iOS AppIcon.'