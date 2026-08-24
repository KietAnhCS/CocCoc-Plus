#requires -version 5.1
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string]$Path,

    [switch]$NoLinks,

    [switch]$NoImages
)

$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

function Format-Size {
    param([double]$Bytes)
    if ($Bytes -ge 1GB) { return ('{0:N2} GB' -f ($Bytes / 1GB)) }
    if ($Bytes -ge 1MB) { return ('{0:N1} MB' -f ($Bytes / 1MB)) }
    if ($Bytes -ge 1KB) { return ('{0:N1} KB' -f ($Bytes / 1KB)) }
    return ('{0:N0} B' -f $Bytes)
}

function Format-Age {
    param([datetime]$When)
    $d = (Get-Date) - $When
    if ($d.TotalMinutes -lt 1)  { return 'vừa xong' }
    if ($d.TotalHours   -lt 1)  { return ('{0:N0} phút trước' -f $d.TotalMinutes) }
    if ($d.TotalDays    -lt 1)  { return ('{0:N0} giờ trước'  -f $d.TotalHours) }
    return ('{0:N0} ngày trước' -f $d.TotalDays)
}

function Format-Span {
    param([timespan]$Span)
    if ($Span.TotalMinutes -lt 1) { return ('{0:N0} giây' -f $Span.TotalSeconds) }
    if ($Span.TotalHours   -lt 1) { return ('{0:N0} phút' -f $Span.TotalMinutes) }
    if ($Span.TotalDays    -lt 1) { return ('{0:N1} giờ'  -f $Span.TotalHours) }
    return ('{0:N1} ngày' -f $Span.TotalDays)
}

function Write-Field {
    param([string]$Label, [string]$Value)
    Write-Host ('  {0,-24}: ' -f $Label) -NoNewline -ForegroundColor Gray
    Write-Host $Value
}

function Write-Sub {
    param([string]$Label, [string]$Value)
    Write-Host ('       {0,-23} {1}' -f $Label, $Value) -ForegroundColor DarkGray
}

function Format-Url {
    param([string]$Url, [int]$Max = 60)
    if ($Url.Length -le $Max) { return $Url }
    return $Url.Substring(0, $Max - 3) + '...'
}

function Measure-Corpus {
    param([System.IO.FileInfo]$File, [bool]$CountLinks)

    $pages     = 0
    $emptyBody = 0
    $outTotal  = 0L
    $crawled   = New-Object 'System.Collections.Generic.HashSet[string]'
    $links     = New-Object 'System.Collections.Generic.HashSet[string]'
    $domains   = @{}

    $perPage   = New-Object 'System.Collections.Generic.List[int]'
    $noOut     = 0
    $maxOut    = 0
    $maxUrl    = ''
    $curUrl    = ''

    $pending   = $null

    $reader = New-Object System.IO.StreamReader($File.FullName, [System.Text.Encoding]::UTF8)
    try {
        while ($null -ne ($line = $reader.ReadLine())) {
            $t = $line.Trim()

            if ($null -ne $pending) {
                $close = $t.IndexOf(']')
                if ($close -ge 0) {
                    $pending += $t.Substring(0, $close)
                    $n = Add-Links -Inner $pending -Set $links -Count $CountLinks
                    $outTotal += $n
                    $perPage.Add($n)
                    if ($n -eq 0) { $noOut++ }
                    if ($n -gt $maxOut) { $maxOut = $n; $maxUrl = $curUrl }
                    $pending = $null
                } else {
                    $pending += $t
                }
                continue
            }

            if ($t.StartsWith('"url"')) {
                $m = [regex]::Match($t, '^"url"\s*:\s*"(.*?)"\s*,?$')
                if ($m.Success) {
                    $pages++
                    $u = $m.Groups[1].Value
                    $curUrl = $u
                    [void]$crawled.Add($u)
                    $h = Get-Host2 $u
                    if ($h) {
                        if ($domains.ContainsKey($h)) { $domains[$h]++ } else { $domains[$h] = 1 }
                    }
                }
                continue
            }

            if ($t.StartsWith('"bodyText" : ""') -or $t.StartsWith('"bodyText": ""')) {
                $emptyBody++
                continue
            }

            if ($t.StartsWith('"outlinks"')) {
                $open = $t.IndexOf('[')
                if ($open -lt 0) { continue }
                $rest  = $t.Substring($open + 1)
                $close = $rest.LastIndexOf(']')
                if ($close -ge 0) {
                    $n = Add-Links -Inner $rest.Substring(0, $close) -Set $links -Count $CountLinks
                    $outTotal += $n
                    $perPage.Add($n)
                    if ($n -eq 0) { $noOut++ }
                    if ($n -gt $maxOut) { $maxOut = $n; $maxUrl = $curUrl }
                } else {
                    $pending = $rest
                }
            }
        }
    } finally {
        $reader.Dispose()
    }

    $unique    = -1
    $remaining = -1
    $remKnown  = -1
    $newHosts  = $null
    if ($CountLinks) {
        $unique = $links.Count
        $links.ExceptWith($crawled)
        $remaining = $links.Count

        $remKnown = 0
        $newHosts = @{}
        foreach ($l in $links) {
            $h = Get-Host2 $l
            if (-not $h) { continue }
            if ($domains.ContainsKey($h)) {
                $remKnown++
            } elseif ($newHosts.ContainsKey($h)) {
                $newHosts[$h]++
            } else {
                $newHosts[$h] = 1
            }
        }
    }

    $median = 0
    $p90    = 0
    if ($perPage.Count -gt 0) {
        $arr = $perPage.ToArray()
        [Array]::Sort($arr)
        $median = $arr[[int][Math]::Floor($arr.Length * 0.5)]
        $p90    = $arr[[int][Math]::Min($arr.Length - 1, [Math]::Floor($arr.Length * 0.9))]
    }

    [pscustomobject]@{
        File       = $File
        Pages      = $pages
        EmptyBody  = $emptyBody
        Domains    = $domains
        OutTotal   = $outTotal
        OutUnique  = $unique
        Remaining  = $remaining
        RemKnown   = $remKnown
        NewHosts   = $newHosts
        NoOutlinks = $noOut
        MaxOut     = $maxOut
        MaxOutUrl  = $maxUrl
        Median     = $median
        P90        = $p90
    }
}

function Add-Links {
    param([string]$Inner, $Set, [bool]$Count)

    $Inner = $Inner.Trim().TrimEnd(',').Trim()
    if ($Inner.Length -lt 2) { return 0 }

    if ($Inner.IndexOf('\"') -lt 0) {
        $body = $Inner.Substring(1, $Inner.Length - 2)
        $parts = $body -split '",\s*"'
        if ($Count) { foreach ($p in $parts) { [void]$Set.Add($p) } }
        return $parts.Count
    }

    $n = 0
    foreach ($m in [regex]::Matches($Inner, '"((?:[^"\\]|\\.)*)"')) {
        $n++
        if ($Count) { [void]$Set.Add($m.Groups[1].Value) }
    }
    return $n
}

function Get-ImagePath {
    param([string]$CorpusPath)
    if ($CorpusPath.EndsWith('.json')) {
        return $CorpusPath.Substring(0, $CorpusPath.Length - 5) + '.images.json'
    }
    return $CorpusPath + '.images.json'
}

function Measure-Images {
    param([System.IO.FileInfo]$File)

    $images    = 0
    $withAlt   = 0
    $declared  = 0
    $downloaded = 0
    $downBytes = 0L
    $pages     = New-Object 'System.Collections.Generic.HashSet[string]'
    $urls      = New-Object 'System.Collections.Generic.HashSet[string]'
    $hosts     = @{}
    $extensions = @{}

    $perPage   = @{}
    $curPage   = ''

    $reader = New-Object System.IO.StreamReader($File.FullName, [System.Text.Encoding]::UTF8)
    try {
        while ($null -ne ($line = $reader.ReadLine())) {
            $t = $line.Trim()

            if ($t.StartsWith('"pageUrl"')) {
                $m = [regex]::Match($t, '^"pageUrl"\s*:\s*"(.*?)"\s*,?$')
                if ($m.Success) {
                    $curPage = $m.Groups[1].Value
                    [void]$pages.Add($curPage)
                    if ($perPage.ContainsKey($curPage)) { $perPage[$curPage]++ } else { $perPage[$curPage] = 1 }
                }
                continue
            }

            if ($t.StartsWith('"host"')) {
                $m = [regex]::Match($t, '^"host"\s*:\s*"(.*?)"\s*,?$')
                if ($m.Success) {
                    $h = $m.Groups[1].Value
                    if ($h) {
                        if ($hosts.ContainsKey($h)) { $hosts[$h]++ } else { $hosts[$h] = 1 }
                    }
                }
                continue
            }

            if ($t.StartsWith('"imageUrl"')) {
                $m = [regex]::Match($t, '^"imageUrl"\s*:\s*"(.*?)"\s*,?$')
                if ($m.Success) {
                    $images++
                    $u = $m.Groups[1].Value
                    [void]$urls.Add($u)

                    $clean = $u.Split('?')[0].Split('#')[0]
                    $dot = $clean.LastIndexOf('.')
                    $slash = $clean.LastIndexOf('/')
                    $ext = if ($dot -gt $slash -and $dot -ge 0 -and ($clean.Length - $dot) -le 6) {
                        $clean.Substring($dot + 1).ToLower()
                    } else { '(không rõ)' }
                    if ($extensions.ContainsKey($ext)) { $extensions[$ext]++ } else { $extensions[$ext] = 1 }
                }
                continue
            }

            if ($t.StartsWith('"altText"')) {
                $m = [regex]::Match($t, '^"altText"\s*:\s*"(.*)"\s*,?$')
                if ($m.Success -and -not [string]::IsNullOrWhiteSpace($m.Groups[1].Value)) {
                    $withAlt++
                }
                continue
            }

            if ($t.StartsWith('"declaredWidth"')) {
                $m = [regex]::Match($t, '^"declaredWidth"\s*:\s*(-?\d+)\s*,?$')
                if ($m.Success -and [int]$m.Groups[1].Value -gt 0) { $declared++ }
                continue
            }

            if ($t.StartsWith('"sizeBytes"')) {
                $m = [regex]::Match($t, '^"sizeBytes"\s*:\s*(-?\d+)\s*,?$')
                if ($m.Success) {
                    $n = [long]$m.Groups[1].Value
                    if ($n -ge 0) { $downloaded++; $downBytes += $n }
                }
                continue
            }
        }
    } finally {
        $reader.Dispose()
    }

    $atConfigCap = 0
    foreach ($n in $perPage.Values) {
        if ($n -ge 50) { $atConfigCap++ }
    }
    $overStore = $pages.Count - 50000
    if ($overStore -lt 0) { $overStore = 0 }

    $counts = @($perPage.Values)
    $median = 0
    $maxImg = 0
    $maxUrl = ''
    if ($counts.Count -gt 0) {
        $arr = [int[]]$counts
        [Array]::Sort($arr)
        $median = $arr[[int][Math]::Floor($arr.Length * 0.5)]
        $top = $perPage.GetEnumerator() | Sort-Object -Property Value -Descending | Select-Object -First 1
        $maxImg = $top.Value
        $maxUrl = $top.Key
    }

    [pscustomobject]@{
        File       = $File
        Images     = $images
        UniqueUrls = $urls.Count
        WithAlt    = $withAlt
        MissingAlt = $images - $withAlt
        Pages      = $pages.Count
        Hosts      = $hosts
        Extensions = $extensions
        Declared   = $declared
        Downloaded = $downloaded
        DownBytes  = $downBytes
        Median     = $median
        MaxImages  = $maxImg
        MaxUrl     = $maxUrl
        AtConfigCap = $atConfigCap
        OverStore   = $overStore
    }
}

function Show-ImageReport {
    param($Stat, [int]$CorpusPages)

    $f = $Stat.File
    Write-Host ''
    Write-Field 'Số ảnh thu được' ('{0:N0}' -f $Stat.Images)

    if ($Stat.Images -eq 0) {
        Write-Host '       (tệp ảnh rỗng — phiên crawl không tìm được ảnh nào)' -ForegroundColor DarkGray
        return
    }

    Write-Sub 'tệp ảnh' ('{0}  ({1})' -f $f.Name, (Format-Size $f.Length))

    Write-Sub 'có văn bản thay thế' ('{0:N0}  ({1:P1})' -f $Stat.WithAlt, ($Stat.WithAlt / $Stat.Images))
    $missRatio = $Stat.MissingAlt / $Stat.Images
    $missNote = if ($missRatio -gt 0.5) {
        '  <- quá nửa là ảnh trang trí, lưới ảnh sẽ nhiều icon'
    } else { '' }
    Write-Sub 'thiếu văn bản thay thế' ('{0:N0}  ({1:P1}){2}' -f $Stat.MissingAlt, $missRatio, $missNote)

    if ($Stat.UniqueUrls -lt $Stat.Images) {
        Write-Sub 'địa chỉ ảnh khác nhau' ('{0:N0}  (cùng một ảnh xuất hiện trên nhiều trang)' -f $Stat.UniqueUrls)
    }

    Write-Sub 'có khai báo kích thước' ('{0:N0}  ({1:P1})  — phần còn lại lưới phải tự đo lúc hiển thị' -f `
        $Stat.Declared, ($Stat.Declared / $Stat.Images))

    if ($Stat.Downloaded -gt 0) {
        Write-Sub 'đã tải nội dung' ('{0:N0}  ({1})' -f $Stat.Downloaded, (Format-Size $Stat.DownBytes))
    } else {
        Write-Sub 'đã tải nội dung' '0  — app.crawler.images.download=false (mặc định, chỉ lưu siêu dữ liệu)'
    }

    Write-Host ''
    Write-Field 'Số trang có ảnh' ('{0:N0}' -f $Stat.Pages)
    if ($CorpusPages -gt 0) {
        if ($Stat.Pages -gt $CorpusPages) {
            $orphan = $Stat.Pages - $CorpusPages
            Write-Sub 'trên tổng số trang' ('{0:N0}' -f $CorpusPages)
            Write-Host ('       [CHÚ Ý] Kho ảnh nhắc tới {0:N0} trang KHÔNG có trong corpus.' -f $orphan) -ForegroundColor DarkYellow
            Write-Host '               Hai tệp lệch phiên crawl — ảnh của những trang đó sẽ không bao giờ' -ForegroundColor DarkGray
            Write-Host '               hiện ở tab Hình ảnh. Chạy lại run-crawl.bat --fresh để đồng bộ.' -ForegroundColor DarkGray
        } else {
            $cover = $Stat.Pages / $CorpusPages
            $coverNote = if ($cover -lt 0.5) { '  <- quá nửa số trang không có ảnh nào' } else { '' }
            Write-Sub 'trên tổng số trang' ('{0:N0}  ({1:P1} corpus){2}' -f $CorpusPages, $cover, $coverNote)
        }
    }
    Write-Sub 'mỗi trang có ảnh' ('{0:N1} trung bình | {1:N0} trung vị | {2:N0} nhiều nhất' -f `
        ($Stat.Images / $Stat.Pages), $Stat.Median, $Stat.MaxImages)
    if ($Stat.MaxUrl) {
        Write-Sub 'trang nhiều ảnh nhất' (Format-Url $Stat.MaxUrl 60)
    }

    if ($Stat.AtConfigCap -gt 0) {
        Write-Host ('       [CHÚ Ý] {0:N0} trang ({1:P1}) chạm trần app.crawler.images.max-per-page = 50.' -f `
            $Stat.AtConfigCap, ($Stat.AtConfigCap / $Stat.Pages)) -ForegroundColor DarkYellow
        Write-Host '               Ảnh của những trang đó bị cắt bớt — số liệu trên là chặn dưới.' -ForegroundColor DarkGray
        Write-Host '               Nâng trong application.properties nếu muốn giữ nhiều ảnh hơn mỗi trang.' -ForegroundColor DarkGray
    }

    if ($Stat.OverStore -gt 0) {
        Write-Host ('       [CHÚ Ý] Kho ảnh có {0:N0} trang, vượt ImageStore.MAX_PAGES = 50.000 tới {1:N0} trang.' -f `
            $Stat.Pages, $Stat.OverStore) -ForegroundColor DarkYellow
        Write-Host '               Tệp trên đĩa vẫn đủ, nhưng lúc nạp ImageStore chỉ nhận 50.000 trang đầu;' -ForegroundColor DarkGray
        Write-Host '               ảnh của phần còn lại không bao giờ ra tới tab Hình ảnh.' -ForegroundColor DarkGray
    }

    Write-Host ''
    Write-Field 'Số tên miền có ảnh' ('{0:N0}' -f $Stat.Hosts.Count)
    $topHosts = $Stat.Hosts.GetEnumerator() | Sort-Object -Property Value -Descending
    foreach ($h in ($topHosts | Select-Object -First 10)) {
        Write-Host ('       {0,-34} {1,8:N0}  {2,6:P1}' -f $h.Key, $h.Value, ($h.Value / $Stat.Images)) -ForegroundColor DarkGray
    }
    if ($Stat.Hosts.Count -gt 10) {
        Write-Host ('       ... còn {0} tên miền nữa' -f ($Stat.Hosts.Count - 10)) -ForegroundColor DarkGray
    }

    Write-Host ''
    Write-Field 'Định dạng ảnh' ('{0:N0} loại đuôi tệp' -f $Stat.Extensions.Count)
    $topExt = $Stat.Extensions.GetEnumerator() | Sort-Object -Property Value -Descending
    foreach ($e in ($topExt | Select-Object -First 8)) {
        Write-Host ('       {0,-34} {1,8:N0}  {2,6:P1}' -f $e.Key, $e.Value, ($e.Value / $Stat.Images)) -ForegroundColor DarkGray
    }
}

function Get-CorpusKind {
    param([System.IO.FileInfo]$File)
    $n = $File.Name.ToLower()
    if ($n.EndsWith('.images.json')) { return 'images' }
    if ($n -eq 'index.json')         { return 'index' }
    if ($n -eq 'users.json')         { return 'users' }
    return 'corpus'
}

function Show-IndexReport {
    param([System.IO.FileInfo]$File, $CorpusStats)

    Write-Host ''
    Write-Host ('  ' + $File.Name + '   (chỉ mục đã dựng sẵn, không phải corpus)') -ForegroundColor Cyan
    Write-Host ('  ' + ('-' * [Math]::Max(20, $File.Name.Length + 38)))

    Write-Field 'Dung lượng' ('{0}  ({1:N0} byte)' -f (Format-Size $File.Length), $File.Length)
    Write-Field 'Cập nhật lúc' ('{0:yyyy-MM-dd HH:mm:ss}  ({1})' -f $File.LastWriteTime, (Format-Age $File.LastWriteTime))

    $head = ''
    try {
        $reader = New-Object System.IO.StreamReader($File.FullName, [System.Text.Encoding]::UTF8)
        try {
            $buf = New-Object char[] 4096
            $n = $reader.Read($buf, 0, $buf.Length)
            if ($n -gt 0) { $head = -join $buf[0..($n - 1)] }
        } finally {
            $reader.Dispose()
        }
    } catch {
        Write-Host '  (không đọc được phần đầu tệp)' -ForegroundColor DarkYellow
        return
    }

    $mv = [regex]::Match($head, '"version"\s*:\s*(\d+)')
    if ($mv.Success) {
        $v = [int]$mv.Groups[1].Value
        if ($v -eq 3) {
            Write-Field 'Phiên bản định dạng' ('{0}  (khớp InvertedIndex.FORMAT_VERSION)' -f $v)
        } else {
            Write-Field 'Phiên bản định dạng' ('{0}  <- KHÔNG khớp FORMAT_VERSION = 3' -f $v)
            Write-Host '       Backend sẽ bỏ tệp này và dựng lại chỉ mục từ corpus lúc khởi động.' -ForegroundColor DarkGray
        }
    }

    $mt = [regex]::Match($head, '"tokenizer"\s*:\s*"(.*?)"')
    if ($mt.Success) {
        Write-Field 'Bộ tách từ' (Format-Url $mt.Groups[1].Value 72)
    }

    $newest = $null
    foreach ($c in $CorpusStats) {
        if ($c.Pages -le 0) { continue }
        if ($null -eq $newest -or $c.File.LastWriteTime -gt $newest.LastWriteTime) { $newest = $c.File }
    }
    if ($null -eq $newest) { return }

    $lag = $newest.LastWriteTime - $File.LastWriteTime

    if ($lag.TotalMinutes -gt 1) {
        Write-Host ''
        Write-Host ('  [CHÚ Ý] Chỉ mục CŨ HƠN "{0}" tới {1}.' -f $newest.Name, (Format-Span $lag)) -ForegroundColor DarkYellow
        Write-Host '          SearchEngineFacade ưu tiên index.json nên backend sẽ nạp bản cũ này:' -ForegroundColor DarkGray
        Write-Host '          không một dòng lỗi nào, chỉ là các trang crawl gần đây không tìm được.' -ForegroundColor DarkGray
        Write-Host '          Lập lại chỉ mục một lần (backend phải đang chạy):' -ForegroundColor DarkGray
        Write-Host '              curl -X POST -H "X-API-Key: <khoa trong .env>" http://localhost:8083/api/admin/reindex' -ForegroundColor DarkGray
    } else {
        Write-Field 'So với corpus' ('khớp với "{0}" — chỉ mục không cũ hơn corpus' -f $newest.Name)
    }
}

function Get-Host2 {
    param([string]$Url)
    $i = $Url.IndexOf('://')
    if ($i -lt 0) { return $null }
    $rest = $Url.Substring($i + 3)
    $j = $rest.IndexOfAny([char[]]@('/', '?', '#'))
    if ($j -ge 0) { $rest = $rest.Substring(0, $j) }
    return $rest
}

function Show-Report {
    param($Stat, [bool]$CountLinks)

    $f = $Stat.File
    Write-Host ''
    Write-Host ('  ' + $f.Name) -ForegroundColor Cyan
    Write-Host ('  ' + ('-' * [Math]::Max(20, $f.Name.Length)))

    Write-Field 'Dung lượng' ('{0}  ({1:N0} byte)' -f (Format-Size $f.Length), $f.Length)
    Write-Field 'Cập nhật lúc' ('{0:yyyy-MM-dd HH:mm:ss}  ({1})' -f $f.LastWriteTime, (Format-Age $f.LastWriteTime))

    if ($Stat.Pages -eq 0) {
        Write-Host '  (không nhận ra định dạng corpus — tệp không chứa trường "url")' -ForegroundColor DarkYellow
        return
    }

    Write-Field 'Số trang đã crawl' ('{0:N0}' -f $Stat.Pages)
    Write-Field 'Trung bình mỗi trang' (Format-Size ($f.Length / $Stat.Pages))
    if ($Stat.EmptyBody -gt 0) {
        Write-Field 'Trang không có nội dung' ('{0:N0}  ({1:P1} tổng số)' -f $Stat.EmptyBody, ($Stat.EmptyBody / $Stat.Pages))
    }

    $top = $Stat.Domains.GetEnumerator() | Sort-Object -Property Value -Descending
    Write-Field 'Số tên miền' ('{0:N0}' -f $Stat.Domains.Count)
    foreach ($d in ($top | Select-Object -First 12)) {
        Write-Host ('       {0,-34} {1,8:N0}  {2,6:P1}' -f $d.Key, $d.Value, ($d.Value / $Stat.Pages)) -ForegroundColor DarkGray
    }
    if ($Stat.Domains.Count -gt 12) {
        Write-Host ('       ... còn {0} tên miền nữa' -f ($Stat.Domains.Count - 12)) -ForegroundColor DarkGray
    }

    Write-Host ''
    Write-Field 'Liên kết thu được' ('{0:N0}  (tổng số outlinks, tính cả trùng lặp)' -f $Stat.OutTotal)
    Write-Sub 'mỗi trang' ('{0:N1} trung bình | {1:N0} trung vị | {2:N0} phân vị 90 | {3:N0} nhiều nhất' -f `
        ($Stat.OutTotal / $Stat.Pages), $Stat.Median, $Stat.P90, $Stat.MaxOut)
    if ($Stat.MaxOutUrl) {
        Write-Sub 'trang nhiều link nhất' (Format-Url $Stat.MaxOutUrl 60)
    }
    if ($Stat.NoOutlinks -gt 0) {
        Write-Sub 'trang không có link' ('{0:N0}  ({1:P1})  <- ngõ cụt, không nuôi thêm hàng đợi' -f `
            $Stat.NoOutlinks, ($Stat.NoOutlinks / $Stat.Pages))
    }

    if ($CountLinks) {
        $dup = if ($Stat.OutTotal -gt 0) { 1 - ($Stat.OutUnique / $Stat.OutTotal) } else { 0 }
        $rep = if ($Stat.OutUnique -gt 0) { $Stat.OutTotal / $Stat.OutUnique } else { 0 }
        Write-Field 'Liên kết khác nhau' ('{0:N0}  (trùng lặp {1:P1} — mỗi URL gặp lại ~{2:N1} lần)' -f `
            $Stat.OutUnique, $dup, $rep)
        Write-Sub 'mỗi trang' ('{0:N1} URL khác nhau' -f ($Stat.OutUnique / $Stat.Pages))

        Write-Field 'Chưa crawl' ('{0:N0}  <- chặn trên của hàng đợi (link thô, chưa qua UrlFilter)' -f $Stat.Remaining)

        $growth = $Stat.Remaining / $Stat.Pages
        $note = if ($growth -gt 1) { 'hệ số nhân > 1: hàng đợi không cạn' } else { 'hệ số nhân < 1: hàng đợi đang co lại' }
        Write-Sub 'mỗi trang' ('{0:N1} URL mới  ({1})' -f $growth, $note)

        if ($null -ne $Stat.NewHosts) {
            $newLinks = $Stat.Remaining - $Stat.RemKnown
            Write-Sub 'trong tên miền đã crawl' ('{0:N0}  ({1:P1})  — phần thật sự vào được frontier' -f `
                $Stat.RemKnown, ($Stat.RemKnown / [Math]::Max(1, $Stat.Remaining)))
            Write-Sub 'sang tên miền mới' ('{0:N0} URL trên {1:N0} tên miền  — UrlFilter loại nếu không nằm trong allowedDomains' -f `
                $newLinks, $Stat.NewHosts.Count)
            $topNew = $Stat.NewHosts.GetEnumerator() | Sort-Object -Property Value -Descending | Select-Object -First 5
            foreach ($d in $topNew) {
                Write-Host ('           {0,-38} {1,8:N0}' -f $d.Key, $d.Value) -ForegroundColor DarkGray
            }
        }

        $perPage = $f.Length / $Stat.Pages
        Write-Host ''
        Write-Host '  Ước tính dung lượng (theo mức trung bình hiện tại):' -ForegroundColor Gray
        foreach ($n in @(10000, 50000, 100000)) {
            Write-Host ('       {0,8:N0} trang  ~ {1}' -f $n, (Format-Size ($perPage * $n))) -ForegroundColor DarkGray
        }
        $all = $Stat.Pages + $Stat.Remaining
        Write-Host ('       {0,8:N0} trang  ~ {1}   (crawl hết mọi liên kết đã biết)' -f $all, (Format-Size ($perPage * $all))) -ForegroundColor DarkGray
    }
}

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not $Path) { $Path = Join-Path $root 'backend\data' }

$candidates = @($Path)
if (-not [System.IO.Path]::IsPathRooted($Path)) {
    $candidates += (Join-Path $root $Path)
    $candidates += (Join-Path (Join-Path $root 'backend') $Path)
}
$resolved = $null
foreach ($c in $candidates) {
    if (Test-Path -LiteralPath $c) { $resolved = $c; break }
}
if (-not $resolved) {
    Write-Host ''
    Write-Host ('[LỖI] Không tìm thấy "{0}".' -f $Path) -ForegroundColor Red
    Write-Host '       Chưa crawl lần nào thì chạy run-crawl.bat trước.'
    exit 1
}
$Path = $resolved

$item = Get-Item -LiteralPath $Path

$indexFile = $null
$skipped   = @()
if ($item.PSIsContainer) {
    $all = @(Get-ChildItem -LiteralPath $item.FullName -Filter '*.json' -File | Sort-Object Length -Descending)
    $files = @()
    foreach ($f in $all) {
        switch (Get-CorpusKind $f) {
            'corpus' { $files += $f }
            'index'  { $indexFile = $f }
            'images' { }
            default  { $skipped += $f }
        }
    }
    $scope = $item.FullName
} else {
    if ((Get-CorpusKind $item) -eq 'index') {
        $files = @()
        $indexFile = $item
    } else {
        $files = @($item)
    }
    $scope = $item.DirectoryName
}

if ($files.Count -eq 0 -and $null -eq $indexFile) {
    Write-Host ''
    Write-Host ('[LỖI] Không có tệp corpus nào trong "{0}".' -f $item.FullName) -ForegroundColor Red
    Write-Host '       Chưa crawl lần nào thì chạy run-crawl.bat trước.'
    exit 1
}

Write-Host ''
Write-Host '=== THỐNG KÊ CORPUS ĐÃ CRAWL ===' -ForegroundColor Green
Write-Host ('Thư mục: {0}' -f $scope) -ForegroundColor DarkGray

$countLinks = -not $NoLinks
$countImages = -not $NoImages
$stats = @()
$imageStats = @()
foreach ($f in $files) {
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $s = Measure-Corpus -File $f -CountLinks $countLinks
    $sw.Stop()
    $stats += $s
    Show-Report -Stat $s -CountLinks $countLinks

    $imageSeconds = 0.0

    if ($countImages -and $s.Pages -gt 0) {
        $imagePath = Get-ImagePath $f.FullName
        if (Test-Path -LiteralPath $imagePath) {
            $imgFile = Get-Item -LiteralPath $imagePath
            $swi = [System.Diagnostics.Stopwatch]::StartNew()
            $si = Measure-Images -File $imgFile
            $swi.Stop()
            $imageSeconds = $swi.Elapsed.TotalSeconds
            $imageStats += $si
            Show-ImageReport -Stat $si -CorpusPages $s.Pages
        } else {
            Write-Host ''
            Write-Host ('  [CHÚ Ý] Chưa có tệp ảnh "{0}".' -f (Split-Path -Leaf $imagePath)) -ForegroundColor DarkYellow
            Write-Host '          Corpus này được crawl bằng bản mã cũ chưa lưu ảnh ra đĩa.' -ForegroundColor DarkGray
            Write-Host '          Chạy run-crawl.bat một lần nữa để sinh nó (crawl nối tiếp, không mất gì).' -ForegroundColor DarkGray
        }
    }

    Write-Host ''
    if ($imageSeconds -gt 0) {
        Write-Host ('  (quét trong {0:N1} giây: {1:N1}s corpus + {2:N1}s ảnh)' -f `
            ($sw.Elapsed.TotalSeconds + $imageSeconds), $sw.Elapsed.TotalSeconds, $imageSeconds) -ForegroundColor DarkGray
    } else {
        Write-Host ('  (quét trong {0:N1} giây)' -f $sw.Elapsed.TotalSeconds) -ForegroundColor DarkGray
    }
}

if ($null -ne $indexFile) {
    Show-IndexReport -File $indexFile -CorpusStats $stats
    Write-Host ''
}

$totalBytes = ($files | Measure-Object -Property Length -Sum).Sum
$totalPages = ($stats | Measure-Object -Property Pages -Sum).Sum

Write-Host ''
Write-Host '  TỔNG CỘNG' -ForegroundColor Green
Write-Host '  ---------'
Write-Field 'Số tệp corpus' ('{0}' -f $files.Count)
Write-Field 'Tổng số trang' ('{0:N0}' -f $totalPages)

if ($imageStats.Count -gt 0) {
    $totalImages   = ($imageStats | Measure-Object -Property Images -Sum).Sum
    $totalWithAlt  = ($imageStats | Measure-Object -Property WithAlt -Sum).Sum
    $totalImgPages = ($imageStats | Measure-Object -Property Pages -Sum).Sum
    $totalImgBytes = 0L
    foreach ($i in $imageStats) { $totalImgBytes += $i.File.Length }

    Write-Field 'Tổng số ảnh' ('{0:N0}' -f $totalImages)
    if ($totalImages -gt 0) {
        Write-Sub 'có văn bản thay thế' ('{0:N0}  ({1:P1})' -f $totalWithAlt, ($totalWithAlt / $totalImages))

        if ($totalImgPages -gt $totalPages) {
            Write-Sub 'trang có ảnh' ('{0:N0}  <- NHIỀU HƠN tổng số trang corpus, hai bên lệch phiên crawl' -f $totalImgPages)
        } else {
            Write-Sub 'trang có ảnh' ('{0:N0}  ({1:P1} tổng số trang)' -f `
                $totalImgPages, ($totalImgPages / [Math]::Max(1, $totalPages)))
            Write-Sub 'mỗi trang' ('{0:N1} ảnh trung bình' -f ($totalImages / [Math]::Max(1, $totalPages)))
        }
    }
    $totalBytes += $totalImgBytes
    Write-Sub 'dung lượng tệp ảnh' (Format-Size $totalImgBytes)
}

$parts = @('corpus')
if ($imageStats.Count -gt 0) { $parts += 'ảnh' }
if ($null -ne $indexFile) {
    $totalBytes += $indexFile.Length
    $parts += 'chỉ mục'
}
$sizeLabel = if ($parts.Count -gt 1) { '  (' + ($parts -join ' + ') + ')' } else { '  (chưa có tệp ảnh hay chỉ mục nào)' }
Write-Field 'Tổng dung lượng' ((Format-Size $totalBytes) + $sizeLabel)
if ($null -ne $indexFile) {
    Write-Sub 'trong đó chỉ mục' (Format-Size $indexFile.Length)
}

$drive = Get-PSDrive -Name (Split-Path -Qualifier $item.FullName).TrimEnd(':') -ErrorAction SilentlyContinue
if ($drive -and $null -ne $drive.Free) {
    $free = [double]$drive.Free
    Write-Field ('Trống trên ổ {0}:' -f $drive.Name) (Format-Size $free)
    $perPage = if ($totalPages -gt 0) { $totalBytes / $totalPages } else { 0 }
    if ($perPage -gt 0) {
        Write-Field 'Còn crawl được thêm' ('~{0:N0} trang trước khi đầy ổ' -f ($free / $perPage))
    }
}

if ($skipped.Count -gt 0) {
    Write-Host ''
    foreach ($f in $skipped) {
        Write-Host ('  (bỏ qua "{0}" — không phải corpus, không có gì để thống kê)' -f $f.Name) -ForegroundColor DarkGray
    }
}

$tmp = @(Get-ChildItem -LiteralPath $scope -Filter '*.json.tmp' -File -ErrorAction SilentlyContinue)
if ($tmp.Count -gt 0) {
    Write-Host ''
    foreach ($t in $tmp) {
        Write-Host ('  [CHÚ Ý] Còn tệp tạm "{0}" ({1}) — một lần ghi bị cắt ngang, xoá được.' -f $t.Name, (Format-Size $t.Length)) -ForegroundColor DarkYellow
    }
}

Write-Host ''
exit 0
