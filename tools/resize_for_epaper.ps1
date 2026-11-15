param([Parameter(Mandatory=$true)][string]$InputImage,
      [Parameter(Mandatory=$true)][string]$OutputBitmap)
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.Drawing
$source=[System.Drawing.Image]::FromFile([System.IO.Path]::GetFullPath($InputImage))
$bitmap=New-Object System.Drawing.Bitmap(240,240,[System.Drawing.Imaging.PixelFormat]::Format24bppRgb)
$graphics=[System.Drawing.Graphics]::FromImage($bitmap)
$attributes=New-Object System.Drawing.Imaging.ImageAttributes
try {
    $graphics.Clear([System.Drawing.Color]::White)
    $graphics.CompositingQuality=[System.Drawing.Drawing2D.CompositingQuality]::HighQuality
    $graphics.InterpolationMode=[System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $graphics.PixelOffsetMode=[System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $attributes.SetWrapMode([System.Drawing.Drawing2D.WrapMode]::TileFlipXY)
    $side=[Math]::Min($source.Width,$source.Height)
    $left=($source.Width-$side)/2
    $top=($source.Height-$side)/2
    $destination=New-Object System.Drawing.Rectangle(0,0,240,240)
    $graphics.DrawImage($source,$destination,$left,$top,$side,$side,[System.Drawing.GraphicsUnit]::Pixel,$attributes)
    $bitmap.Save([System.IO.Path]::GetFullPath($OutputBitmap),[System.Drawing.Imaging.ImageFormat]::Bmp)
    Write-Output "Resized $($source.Width)x$($source.Height) to 240x240 (24-bit BMP)."
} finally {
    $attributes.Dispose();$graphics.Dispose();$bitmap.Dispose();$source.Dispose()
}
