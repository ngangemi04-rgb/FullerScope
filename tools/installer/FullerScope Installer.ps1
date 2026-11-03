# ---------------------------------------------------------------------------
# FullerScope Installer - puts the app on Android phones over USB.
#
# A Windows Forms window around adb. PowerShell rather than Python/tkinter like the
# other tools, because this one is meant to leave the development machine: every
# Windows PC already has PowerShell 5.1, so the packaged folder (make_package.ps1)
# runs on a laptop with no Android Studio and no Python installed.
#
# Finds, in this order:
#   adb  - .\platform-tools\adb.exe (packaged), the Android SDK, then PATH
#   APK  - .\FullerScope.apk (packaged), then the project's latest debug build
#
# Started by "FullerScope Installer.cmd" with -STA, which WinForms needs.
# ---------------------------------------------------------------------------

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
[System.Windows.Forms.Application]::EnableVisualStyles()

$Package = 'com.capstone.stethoscope'
$Here = $PSScriptRoot

# ------------------------------------------------------------------ locating

function Find-Adb {
    $candidates = @(
        (Join-Path $Here 'platform-tools\adb.exe'),
        (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe')
    )
    if ($env:ANDROID_HOME) { $candidates += (Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe') }
    foreach ($c in $candidates) { if (Test-Path $c) { return $c } }
    $onPath = Get-Command adb.exe -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }
    return $null
}

function Find-Apk {
    $candidates = @(
        (Join-Path $Here 'FullerScope.apk'),
        (Join-Path $Here '..\..\app\build\outputs\apk\debug\app-debug.apk')
    )
    foreach ($c in $candidates) { if (Test-Path $c) { return (Resolve-Path $c).Path } }
    return $null
}

$Adb = Find-Adb
if (-not $Adb) {
    [System.Windows.Forms.MessageBox]::Show(
        "adb.exe was not found.`n`nUse the packaged 'FullerScope Installer' folder (it carries its own copy in platform-tools), or install Android Studio.",
        'FullerScope Installer', 'OK', 'Error') | Out-Null
    exit 1
}
$script:Apk = Find-Apk

# ------------------------------------------------------------------ running adb

# Every adb call goes through a Process with a timeout rather than `& adb`. If the adb
# server is not running, the first call starts it, and the server can inherit the
# output pipe - reading that pipe to the end would then wait forever.
function New-AdbProcess([string[]]$AdbArgs) {
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $Adb
    $psi.Arguments = ($AdbArgs | ForEach-Object { if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } }) -join ' '
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $p = [System.Diagnostics.Process]::Start($psi)
    return [pscustomobject]@{ Process = $p; Out = $p.StandardOutput.ReadToEndAsync(); Err = $p.StandardError.ReadToEndAsync() }
}

function Get-AdbText($run) {
    $text = ''
    if ($run.Out.Wait(500)) { $text += $run.Out.Result }
    if ($run.Err.Wait(500)) { $text += $run.Err.Result }
    return $text.Trim()
}

function Invoke-Adb([string[]]$AdbArgs, [int]$TimeoutMs = 15000) {
    $run = New-AdbProcess $AdbArgs
    if (-not $run.Process.WaitForExit($TimeoutMs)) {
        try { $run.Process.Kill() } catch {}
        return [pscustomobject]@{ Code = -1; Text = 'adb timed out' }
    }
    return [pscustomobject]@{ Code = $run.Process.ExitCode; Text = (Get-AdbText $run) }
}

# Started up front, with no pipe to inherit, so the first device scan is quick.
$startPsi = New-Object System.Diagnostics.ProcessStartInfo
$startPsi.FileName = $Adb
$startPsi.Arguments = 'start-server'
$startPsi.UseShellExecute = $false
$startPsi.CreateNoWindow = $true
[System.Diagnostics.Process]::Start($startPsi).WaitForExit(15000) | Out-Null

# ------------------------------------------------------------------ devices

# serial -> 'Yes'/'No', so the per-phone package check runs once per plug-in, not
# every refresh. Cleared for a phone after anything is installed on it.
$script:InstalledCache = @{}

function Get-Devices {
    $r = Invoke-Adb @('devices', '-l') 8000
    $list = @()
    foreach ($line in ($r.Text -split "`r?`n")) {
        if ($line -match '^(\S+)\s+(device|unauthorized|offline|no permissions|recovery|sideload)\b(.*)$') {
            $serial = $Matches[1]; $state = $Matches[2]; $rest = $Matches[3]
            $model = if ($rest -match 'model:(\S+)') { $Matches[1] -replace '_', ' ' } else { '' }
            $installed = ''
            if ($state -eq 'device') {
                if (-not $script:InstalledCache.ContainsKey($serial)) {
                    $p = Invoke-Adb @('-s', $serial, 'shell', 'pm', 'list', 'packages', $Package) 8000
                    $script:InstalledCache[$serial] = if ($p.Text -match "package:$([regex]::Escape($Package))\b") { 'Yes' } else { 'No' }
                }
                $installed = $script:InstalledCache[$serial]
            }
            $list += [pscustomobject]@{ Serial = $serial; State = $state; Model = $model; Installed = $installed }
        }
    }
    return $list
}

function Describe-State([string]$state) {
    switch ($state) {
        'device'        { 'Ready' }
        'unauthorized'  { 'Waiting - unlock the phone and tap Allow' }
        'offline'       { 'Offline - unplug and plug back in' }
        'no permissions' { 'No permission - try another USB cable/port' }
        default         { $state }
    }
}

# ------------------------------------------------------------------ window

$form = New-Object System.Windows.Forms.Form
$form.Text = 'FullerScope Installer'
$form.Size = New-Object System.Drawing.Size(760, 640)
$form.MinimumSize = New-Object System.Drawing.Size(640, 520)
$form.StartPosition = 'CenterScreen'
$form.Font = New-Object System.Drawing.Font('Segoe UI', 10)
$iconPath = Join-Path $Here 'installer.ico'
if (Test-Path $iconPath) { $form.Icon = New-Object System.Drawing.Icon($iconPath) }

$layout = New-Object System.Windows.Forms.TableLayoutPanel
$layout.Dock = 'Fill'
$layout.Padding = New-Object System.Windows.Forms.Padding(12)
$layout.ColumnCount = 1
$layout.RowCount = 6
[void]$layout.RowStyles.Add((New-Object System.Windows.Forms.RowStyle('AutoSize')))
[void]$layout.RowStyles.Add((New-Object System.Windows.Forms.RowStyle('AutoSize')))
[void]$layout.RowStyles.Add((New-Object System.Windows.Forms.RowStyle('Percent', 45)))
[void]$layout.RowStyles.Add((New-Object System.Windows.Forms.RowStyle('AutoSize')))
[void]$layout.RowStyles.Add((New-Object System.Windows.Forms.RowStyle('Percent', 55)))
[void]$layout.RowStyles.Add((New-Object System.Windows.Forms.RowStyle('AutoSize')))
$form.Controls.Add($layout)

# --- APK row
$apkPanel = New-Object System.Windows.Forms.FlowLayoutPanel
$apkPanel.AutoSize = $true
$apkPanel.Dock = 'Fill'
$apkPanel.WrapContents = $false
$apkLabel = New-Object System.Windows.Forms.Label
$apkLabel.AutoSize = $true
$apkLabel.Margin = New-Object System.Windows.Forms.Padding(0, 6, 12, 0)
$apkButton = New-Object System.Windows.Forms.Button
$apkButton.Text = 'Choose APK...'
$apkButton.AutoSize = $true
$apkPanel.Controls.AddRange(@($apkLabel, $apkButton))
$layout.Controls.Add($apkPanel)

function Update-ApkLabel {
    if ($script:Apk -and (Test-Path $script:Apk)) {
        $f = Get-Item $script:Apk
        $apkLabel.Text = "App file: $($f.Name)  ($([math]::Round($f.Length / 1MB, 1)) MB, built $($f.LastWriteTime.ToString('yyyy-MM-dd HH:mm')))"
    } else {
        $apkLabel.Text = 'App file: none found - choose the FullerScope .apk'
    }
}
Update-ApkLabel

# --- phones heading
$phonesLabel = New-Object System.Windows.Forms.Label
$phonesLabel.Text = 'Connected phones (tick the ones to install on):'
$phonesLabel.AutoSize = $true
$phonesLabel.Margin = New-Object System.Windows.Forms.Padding(0, 10, 0, 4)
$layout.Controls.Add($phonesLabel)

# --- phone list
$list = New-Object System.Windows.Forms.ListView
$list.Dock = 'Fill'
$list.View = 'Details'
$list.CheckBoxes = $true
$list.FullRowSelect = $true
[void]$list.Columns.Add('Phone', 200)
[void]$list.Columns.Add('Status', 300)
[void]$list.Columns.Add('FullerScope on it', 140)
$layout.Controls.Add($list)

# --- buttons
$buttons = New-Object System.Windows.Forms.FlowLayoutPanel
$buttons.AutoSize = $true
$buttons.Dock = 'Fill'
$buttons.Margin = New-Object System.Windows.Forms.Padding(0, 8, 0, 8)
$installButton = New-Object System.Windows.Forms.Button
$installButton.Text = 'Install'
$installButton.AutoSize = $true
$installButton.Font = New-Object System.Drawing.Font('Segoe UI', 10, [System.Drawing.FontStyle]::Bold)
$openCheck = New-Object System.Windows.Forms.CheckBox
$openCheck.Text = 'Open the app afterwards'
$openCheck.Checked = $true
$openCheck.AutoSize = $true
$openCheck.Margin = New-Object System.Windows.Forms.Padding(16, 6, 0, 0)
$helpButton = New-Object System.Windows.Forms.Button
$helpButton.Text = 'Phone not showing up?'
$helpButton.AutoSize = $true
$helpButton.Margin = New-Object System.Windows.Forms.Padding(16, 3, 0, 0)
$buttons.Controls.AddRange(@($installButton, $openCheck, $helpButton))
$layout.Controls.Add($buttons)

# --- log
$log = New-Object System.Windows.Forms.TextBox
$log.Dock = 'Fill'
$log.Multiline = $true
$log.ReadOnly = $true
$log.ScrollBars = 'Vertical'
$log.Font = New-Object System.Drawing.Font('Consolas', 9)
$layout.Controls.Add($log)

$status = New-Object System.Windows.Forms.Label
$status.AutoSize = $true
$status.Margin = New-Object System.Windows.Forms.Padding(0, 6, 0, 0)
$status.Text = "adb: $Adb"
$status.ForeColor = [System.Drawing.Color]::Gray
$layout.Controls.Add($status)

function Write-Log([string]$msg) {
    $log.AppendText("[$((Get-Date).ToString('HH:mm:ss'))] $msg`r`n")
}

# ------------------------------------------------------------------ refresh

$script:Busy = $false
# $null, not '': an empty device list has the signature '', and must still be drawn.
$script:LastSignature = $null

function Refresh-Devices {
    if ($script:Busy) { return }
    $devices = Get-Devices
    # Only rebuild the list when something changed, so ticks survive a refresh.
    $sig = ($devices | ForEach-Object { "$($_.Serial)|$($_.State)|$($_.Installed)" }) -join ';'
    if ($sig -eq $script:LastSignature) { return }
    $checked = @{}
    foreach ($item in $list.Items) { if ($item.Tag) { $checked[$item.Tag.Serial] = $item.Checked } }

    $list.BeginUpdate()
    $list.Items.Clear()
    foreach ($d in $devices) {
        $name = if ($d.Model) { "$($d.Model)  ($($d.Serial))" } else { $d.Serial }
        $item = New-Object System.Windows.Forms.ListViewItem($name)
        [void]$item.SubItems.Add((Describe-State $d.State))
        [void]$item.SubItems.Add($d.Installed)
        $item.Tag = $d
        $item.Checked = if ($checked.ContainsKey($d.Serial)) { $checked[$d.Serial] } else { $d.State -eq 'device' }
        if ($d.State -ne 'device') { $item.ForeColor = [System.Drawing.Color]::DarkOrange }
        [void]$list.Items.Add($item)
    }
    if ($devices.Count -eq 0) {
        $item = New-Object System.Windows.Forms.ListViewItem('No phone connected')
        [void]$item.SubItems.Add('Plug a phone in with USB debugging on')
        $item.ForeColor = [System.Drawing.Color]::Gray
        [void]$list.Items.Add($item)
    }
    $list.EndUpdate()
    $script:LastSignature = $sig
}

# ------------------------------------------------------------------ installing

# Plain-English versions of the failures people actually hit.
function Explain-Failure([string]$text) {
    switch -Regex ($text) {
        'INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match' { return 'SIGNATURE' }
        'INSTALL_FAILED_VERSION_DOWNGRADE' { return 'DOWNGRADE' }
        'INSTALL_FAILED_INSUFFICIENT_STORAGE|not enough space' { return 'Not enough free space on the phone. Free some up and try again.' }
        'INSTALL_FAILED_OLDER_SDK' { return 'This phone''s Android version is too old - FullerScope needs Android 8.0 or newer.' }
        'INSTALL_FAILED_USER_RESTRICTED|INSTALL_FAILED_ABORTED|INSTALL_CANCELED_BY_USER' {
            return 'The phone refused the install. Watch the phone for a prompt and accept it. On Xiaomi, Oppo, Vivo and Realme phones also turn on "Install via USB" in Developer options.'
        }
        'unauthorized' { return 'The phone has not allowed this computer yet. Unlock it and tap Allow on the USB debugging prompt.' }
        'device .* not found|device offline|no devices' { return 'The phone disconnected. Plug it back in and try again.' }
        default { return $null }
    }
}

# The queue of phones to do, worked through one at a time from a timer so the window
# stays responsive during the 10-30 s each install takes.
$script:Queue = New-Object System.Collections.Queue
$script:Current = $null   # @{ Device; Run; Step; Flags }

function Start-Step($job) {
    $d = $job.Device
    switch ($job.Step) {
        'install'   { $job.Run = New-AdbProcess (@('-s', $d.Serial, 'install') + $job.Flags + @($script:Apk)) }
        'uninstall' { $job.Run = New-AdbProcess @('-s', $d.Serial, 'uninstall', $Package) }
        'open'      { $job.Run = New-AdbProcess @('-s', $d.Serial, 'shell', 'monkey', '-p', $Package, '-c', 'android.intent.category.LAUNCHER', '1') }
    }
}

function Next-Job {
    if ($script:Queue.Count -eq 0) {
        $script:Current = $null
        $script:Busy = $false
        $installButton.Enabled = $true
        $script:LastSignature = $null
        Refresh-Devices
        Write-Log 'Done.'
        return
    }
    $d = $script:Queue.Dequeue()
    $script:Current = @{ Device = $d; Step = 'install'; Flags = @('-r'); Run = $null }
    Write-Log "Installing on $(if ($d.Model) { $d.Model } else { $d.Serial })..."
    Start-Step $script:Current
}

function On-StepFinished($job) {
    $d = $job.Device
    $text = Get-AdbText $job.Run
    $ok = $job.Run.Process.ExitCode -eq 0 -and $text -notmatch 'Failure|failed'
    $script:InstalledCache.Remove($d.Serial)

    switch ($job.Step) {
        'install' {
            if ($ok) {
                Write-Log '  Installed.'
                if ($openCheck.Checked) { $job.Step = 'open'; Start-Step $job; return }
                Next-Job; return
            }
            $why = Explain-Failure $text
            if ($why -eq 'DOWNGRADE') {
                # The phone has a newer build; keep its recordings and replace it anyway.
                Write-Log '  The phone has a newer build - replacing it with this one.'
                $job.Flags = @('-r', '-d'); Start-Step $job; return
            }
            if ($why -eq 'SIGNATURE') {
                $answer = [System.Windows.Forms.MessageBox]::Show(
                    "The FullerScope on this phone was built on a different computer, so it cannot be updated in place.`n`nReplacing it means uninstalling it first, which DELETES ALL RECORDINGS stored in the app on this phone. Export anything you need first.`n`nUninstall and reinstall now?",
                    'FullerScope Installer', 'YesNo', 'Warning')
                if ($answer -eq 'Yes') {
                    Write-Log '  Uninstalling the old copy (its recordings are deleted)...'
                    $job.Step = 'uninstall'; Start-Step $job; return
                }
                Write-Log '  Skipped - left the existing copy alone.'
                Next-Job; return
            }
            Write-Log "  FAILED: $(if ($why) { $why } else { $text })"
            Next-Job; return
        }
        'uninstall' {
            if (-not $ok) { Write-Log "  Uninstall failed: $text"; Next-Job; return }
            $job.Step = 'install'; $job.Flags = @(); Start-Step $job; return
        }
        'open' {
            if (-not $ok) { Write-Log "  Installed, but could not open it: $text" } else { Write-Log '  Opened on the phone.' }
            Next-Job; return
        }
    }
}

$installButton.Add_Click({
    if (-not ($script:Apk -and (Test-Path $script:Apk))) {
        [System.Windows.Forms.MessageBox]::Show('Choose the FullerScope .apk file first.', 'FullerScope Installer') | Out-Null
        return
    }
    $targets = @($list.Items | Where-Object { $_.Checked -and $_.Tag -and $_.Tag.State -eq 'device' } | ForEach-Object { $_.Tag })
    $waiting = @($list.Items | Where-Object { $_.Checked -and $_.Tag -and $_.Tag.State -ne 'device' })
    if ($targets.Count -eq 0) {
        $msg = if ($waiting.Count -gt 0) { 'The ticked phone is not ready yet - see its status.' } else { 'Tick at least one connected phone.' }
        [System.Windows.Forms.MessageBox]::Show($msg, 'FullerScope Installer') | Out-Null
        return
    }
    $script:Busy = $true
    $installButton.Enabled = $false
    $script:Queue.Clear()
    foreach ($t in $targets) { $script:Queue.Enqueue($t) }
    Next-Job
})

$apkButton.Add_Click({
    $dlg = New-Object System.Windows.Forms.OpenFileDialog
    $dlg.Filter = 'Android app (*.apk)|*.apk'
    if ($script:Apk) { $dlg.InitialDirectory = Split-Path $script:Apk }
    if ($dlg.ShowDialog() -eq 'OK') { $script:Apk = $dlg.FileName; Update-ApkLabel }
})

$helpButton.Add_Click({
    [System.Windows.Forms.MessageBox]::Show(
        "One-time setup on each phone:`n`n" +
        "1. Settings > About phone > tap 'Build number' 7 times (enter the PIN if asked).`n" +
        "2. Settings > System > Developer options > turn on 'USB debugging'.`n" +
        "3. Plug the phone into this computer and unlock it.`n" +
        "4. Tap 'Allow' on the 'Allow USB debugging?' prompt (tick 'Always allow' to skip it next time).`n`n" +
        "Still missing?`n" +
        "- Try another cable: many charge-only cables carry no data.`n" +
        "- Pull down the notification shade and set USB to 'File transfer'.`n" +
        "- Some brands (e.g. Motorola, Samsung) need their USB driver installed on the PC.`n" +
        "- Unplug the stethoscope: the phone has only one USB-C port.",
        'Connecting a phone', 'OK', 'Information') | Out-Null
})

# ------------------------------------------------------------------ timers

# Polls the running step every 200 ms; rescans phones every 2 s while idle.
$tick = New-Object System.Windows.Forms.Timer
$tick.Interval = 200
$script:Ticks = 0
$tick.Add_Tick({
    $job = $script:Current
    if ($job -and $job.Run -and $job.Run.Process.HasExited) {
        try { On-StepFinished $job } catch { Write-Log "  Error: $_"; Next-Job }
        return
    }
    if ($job -and $job.Run -and ((Get-Date) - $job.Run.Process.StartTime).TotalSeconds -gt 180) {
        try { $job.Run.Process.Kill() } catch {}
        Write-Log '  Timed out after 3 minutes.'
        Next-Job
        return
    }
    $script:Ticks++
    if (-not $script:Busy -and $script:Ticks % 10 -eq 0) { Refresh-Devices }
})

$form.Add_Shown({
    # The launcher hides PowerShell's console; depending on how it was started, that
    # hidden/minimised start-up state can land on this window instead.
    $form.WindowState = 'Normal'
    $form.Activate()
    Write-Log 'Plug in a phone with USB debugging on. Phones appear here automatically.'
    Refresh-Devices
    $tick.Start()
})
$form.Add_FormClosing({ $tick.Stop() })

[void]$form.ShowDialog()
