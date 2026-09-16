Add-Type -AssemblyName System.Drawing

$targets = @(
    @{ Code = 'UMA'; Label = 'UM'; Color = [System.Drawing.Color]::FromArgb(103, 80, 164) },
    @{ Code = 'BA';  Label = 'BA'; Color = [System.Drawing.Color]::FromArgb(59, 130, 246) },
    @{ Code = 'GOV'; Label = 'NK'; Color = [System.Drawing.Color]::FromArgb(239, 71, 111) }
)

$outputDirectories = @(
    (Join-Path $PSScriptRoot '..\frontend\assets\game-icons'),
    (Join-Path $PSScriptRoot '..\mobile\assets\game-icons')
)
$outputDirectories | ForEach-Object { New-Item -ItemType Directory -Path $_ -Force | Out-Null }

foreach ($target in $targets) {
    $bitmap = New-Object System.Drawing.Bitmap 96, 96
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $graphics.Clear([System.Drawing.Color]::Transparent)

    $background = New-Object System.Drawing.SolidBrush $target.Color
    $graphics.FillEllipse($background, 3, 3, 90, 90)
    $background.Dispose()

    $font = New-Object System.Drawing.Font('Segoe UI', 28, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)
    $foreground = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::White)
    $format = New-Object System.Drawing.StringFormat
    $format.Alignment = [System.Drawing.StringAlignment]::Center
    $format.LineAlignment = [System.Drawing.StringAlignment]::Center
    $graphics.DrawString($target.Label, $font, $foreground, (New-Object System.Drawing.RectangleF(0, 0, 96, 96)), $format)

    foreach ($directory in $outputDirectories) {
        $bitmap.Save((Join-Path $directory ($target.Code + '.png')), [System.Drawing.Imaging.ImageFormat]::Png)
    }
    $format.Dispose(); $foreground.Dispose(); $font.Dispose(); $graphics.Dispose(); $bitmap.Dispose()
}
