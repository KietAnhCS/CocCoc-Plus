#requires -version 5.1
<#
    Thống kê corpus đã crawl: bao nhiêu trang, bao nhiêu liên kết, tốn bao nhiêu GB.

    Không gọi trực tiếp — chạy qua crawl-stats.bat để bảng mã console được đặt
    đúng (tệp .bat gọi chcp 65001 trước, nếu không chữ tiếng Việt có dấu ở đây
    sẽ ra dấu hỏi).

    Cách đọc tệp: đọc TỪNG DÒNG bằng StreamReader chứ không ConvertFrom-Json.
    Corpus đang là 87 MB và sẽ còn lớn hơn; nạp cả cây JSON vào bộ nhớ tốn vài
    trăm MB và mất hàng chục giây, trong khi mọi con số cần ở đây đều đọc được
    từ một lần quét tuyến tính — 0,7 giây cho 87 MB. Cấu trúc tệp do Jackson
    sinh ra (SerializationFeature.INDENT_OUTPUT) đặt mỗi trường trên một dòng
    riêng và cả mảng outlinks gọn trong MỘT dòng, nên cách đọc này khớp tự
    nhiên. Vẫn có nhánh dự phòng cho trường hợp mảng bị xuống dòng.
#>
[CmdletBinding()]
param(
    # Tệp .json hoặc thư mục cần thống kê. Bỏ trống: quét thư mục data mặc định.
    [Parameter(Position = 0)]
    [string]$Path,

    # Bỏ qua phần đếm liên kết (nhanh hơn, ít RAM hơn) khi chỉ cần dung lượng.
    [switch]$NoLinks
)

$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

# ---------------------------------------------------------------- tiện ích in

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

function Write-Field {
    param([string]$Label, [string]$Value)
    Write-Host ('  {0,-24}: ' -f $Label) -NoNewline -ForegroundColor Gray
    Write-Host $Value
}

# Dòng con, thụt vào dưới một Write-Field: bóc tách một con số vừa in ra thành
# các thành phần của nó. Cả dòng để màu tối cho mắt bám được thứ bậc.
function Write-Sub {
    param([string]$Label, [string]$Value)
    Write-Host ('       {0,-23} {1}' -f $Label, $Value) -ForegroundColor DarkGray
}

function Format-Url {
    param([string]$Url, [int]$Max = 60)
    if ($Url.Length -le $Max) { return $Url }
    return $Url.Substring(0, $Max - 3) + '...'
}

# ------------------------------------------------------------------ quét tệp

function Measure-Corpus {
    param([System.IO.FileInfo]$File, [bool]$CountLinks)

    $pages     = 0
    $emptyBody = 0
    $outTotal  = 0L
    $crawled   = New-Object 'System.Collections.Generic.HashSet[string]'
    $links     = New-Object 'System.Collections.Generic.HashSet[string]'
    $domains   = @{}

    # Số outlink của TỪNG trang, để tính trung vị và trang nhiều link nhất.
    # Trung bình cộng một mình dễ đánh lừa: vài trang chuyên mục với hàng trăm
    # link kéo nó lên cao hơn hẳn trang bài viết bình thường.
    $perPage   = New-Object 'System.Collections.Generic.List[int]'
    $noOut     = 0
    $maxOut    = 0
    $maxUrl    = ''
    $curUrl    = ''

    # Dùng cho nhánh dự phòng: mảng outlinks trải trên nhiều dòng.
    $pending   = $null

    $reader = New-Object System.IO.StreamReader($File.FullName, [System.Text.Encoding]::UTF8)
    try {
        while ($null -ne ($line = $reader.ReadLine())) {
            $t = $line.Trim()

            if ($null -ne $pending) {
                # Đang gom một mảng outlinks nhiều dòng.
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

    # Liên kết đã biết nhưng chưa tải về = phần hàng đợi còn lại cho phiên sau.
    # Đếm số liên kết khác nhau TRƯỚC khi trừ đi phần đã crawl, vì ExceptWith
    # sửa thẳng tập hợp chứ không trả về tập mới.
    $unique    = -1
    $remaining = -1
    $remKnown  = -1
    $newHosts  = $null
    if ($CountLinks) {
        $unique = $links.Count
        $links.ExceptWith($crawled)
        $remaining = $links.Count

        # Hàng đợi còn lại nằm trong tên miền đã crawl hay trỏ ra ngoài? Con số
        # này quyết định chạy tiếp sẽ đào sâu (cùng tên miền, tốt cho corpus có
        # trọng tâm) hay lan ra (tên miền mới, cần thêm robots.txt + hàng đợi
        # lịch sự riêng cho mỗi host).
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

    # Trung vị + phân vị 90: sắp xếp bản sao mảng, rẻ hơn hẳn Sort-Object vì
    # làm trên int thuần chứ không bọc mỗi phần tử vào PSObject.
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
    if ($Inner.Length -lt 2) { return 0 }   # mảng rỗng: "[ ]"

    # Đường nhanh: tách theo dấu phân cách giữa hai phần tử. Mẫu phải nhận cả
    # '", "' (Jackson viết mảng gọn trên một dòng) lẫn '","' (mảng xuống dòng,
    # như seed-documents.json — các dòng được nối lại nên mất luôn khoảng
    # trắng). Chuỗi này không thể xuất hiện giữa lòng một URL, vì dấu nháy bên
    # trong chuỗi JSON luôn bị escape thành \"; gặp escape thật thì rơi xuống
    # nhánh regex bên dưới cho chắc.
    if ($Inner.IndexOf('\"') -lt 0) {
        $body = $Inner.Substring(1, $Inner.Length - 2)   # bỏ nháy đầu và cuối
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

function Get-Host2 {
    param([string]$Url)
    $i = $Url.IndexOf('://')
    if ($i -lt 0) { return $null }
    $rest = $Url.Substring($i + 3)
    $j = $rest.IndexOfAny([char[]]@('/', '?', '#'))
    if ($j -ge 0) { $rest = $rest.Substring(0, $j) }
    return $rest
}

# ------------------------------------------------------------------ báo cáo

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

    # Tên miền: cho thấy corpus có bị lệch hẳn về một trang báo hay không.
    $top = $Stat.Domains.GetEnumerator() | Sort-Object -Property Value -Descending
    Write-Field 'Số tên miền' ('{0:N0}' -f $Stat.Domains.Count)
    foreach ($d in ($top | Select-Object -First 12)) {
        Write-Host ('       {0,-34} {1,8:N0}  {2,6:P1}' -f $d.Key, $d.Value, ($d.Value / $Stat.Pages)) -ForegroundColor DarkGray
    }
    if ($Stat.Domains.Count -gt 12) {
        Write-Host ('       ... còn {0} tên miền nữa' -f ($Stat.Domains.Count - 12)) -ForegroundColor DarkGray
    }

    # --- liên kết ---
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
        # Tỉ lệ trùng cho biết UrlSeenFilter gánh bao nhiêu: mỗi outlink là một
        # lần tra bộ lọc, nhưng chỉ phần "khác nhau" mới thành một lần ghi.
        $dup = if ($Stat.OutTotal -gt 0) { 1 - ($Stat.OutUnique / $Stat.OutTotal) } else { 0 }
        $rep = if ($Stat.OutUnique -gt 0) { $Stat.OutTotal / $Stat.OutUnique } else { 0 }
        Write-Field 'Liên kết khác nhau' ('{0:N0}  (trùng lặp {1:P1} — mỗi URL gặp lại ~{2:N1} lần)' -f `
            $Stat.OutUnique, $dup, $rep)
        Write-Sub 'mỗi trang' ('{0:N1} URL khác nhau' -f ($Stat.OutUnique / $Stat.Pages))

        # Đây là link THÔ: outlinks lưu trong corpus là toàn bộ liên kết bóc
        # được từ HTML, UrlFilter chỉ chạy lúc nạp vào frontier (xem
        # CrawlerService.enqueue). Nên con số này là chặn TRÊN của hàng đợi
        # thật — phần trỏ sang tên miền ngoài allowedDomains sẽ bị loại sạch.
        Write-Field 'Chưa crawl' ('{0:N0}  <- chặn trên của hàng đợi (link thô, chưa qua UrlFilter)' -f $Stat.Remaining)

        # Hệ số nhân của frontier: crawl xong 1 trang thì hàng đợi phình thêm
        # bao nhiêu URL. Lớn hơn 1 nghĩa là hàng đợi không bao giờ cạn, dừng
        # lúc nào là do người chạy quyết chứ không phải do hết URL.
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

        # Ước tính dung lượng: nhân số byte trung bình mỗi trang. Con số này
        # thiên về hơi cao, vì trang càng nhiều thì tỉ lệ trang trùng mẫu
        # (menu, chân trang) càng lớn và nén/lọc về sau càng hiệu quả.
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

# --------------------------------------------------------------------- chạy

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not $Path) { $Path = Join-Path $root 'search-engine\data' }

# Đường dẫn tương đối được thử theo ba mốc, chứ không chỉ theo thư mục hiện
# hành: người dùng gõ quen tay đúng chuỗi đã dùng với run-crawl.bat
# ("data/crawled-documents.json"), mà chuỗi đó tính từ thư mục search-engine.
$candidates = @($Path)
if (-not [System.IO.Path]::IsPathRooted($Path)) {
    $candidates += (Join-Path $root $Path)
    $candidates += (Join-Path (Join-Path $root 'search-engine') $Path)
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
if ($item.PSIsContainer) {
    $files = @(Get-ChildItem -LiteralPath $item.FullName -Filter '*.json' -File | Sort-Object Length -Descending)
    $scope = $item.FullName
} else {
    $files = @($item)
    $scope = $item.DirectoryName
}

if ($files.Count -eq 0) {
    Write-Host ''
    Write-Host ('[LỖI] Không có tệp .json nào trong "{0}".' -f $item.FullName) -ForegroundColor Red
    exit 1
}

Write-Host ''
Write-Host '=== THỐNG KÊ CORPUS ĐÃ CRAWL ===' -ForegroundColor Green
Write-Host ('Thư mục: {0}' -f $scope) -ForegroundColor DarkGray

$countLinks = -not $NoLinks
$stats = @()
foreach ($f in $files) {
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $s = Measure-Corpus -File $f -CountLinks $countLinks
    $sw.Stop()
    $stats += $s
    Show-Report -Stat $s -CountLinks $countLinks
    Write-Host ('  (quét trong {0:N1} giây)' -f $sw.Elapsed.TotalSeconds) -ForegroundColor DarkGray
}

# Tổng hợp + chỗ trống còn lại của ổ đĩa: câu hỏi "tốn bao nhiêu GB" chỉ có
# nghĩa khi đặt cạnh dung lượng trống.
$totalBytes = ($files | Measure-Object -Property Length -Sum).Sum
$totalPages = ($stats | Measure-Object -Property Pages -Sum).Sum

Write-Host ''
Write-Host '  TỔNG CỘNG' -ForegroundColor Green
Write-Host '  ---------'
Write-Field 'Số tệp corpus' ('{0}' -f $files.Count)
Write-Field 'Tổng số trang' ('{0:N0}' -f $totalPages)
Write-Field 'Tổng dung lượng' (Format-Size $totalBytes)

$drive = Get-PSDrive -Name (Split-Path -Qualifier $item.FullName).TrimEnd(':') -ErrorAction SilentlyContinue
if ($drive -and $null -ne $drive.Free) {
    $free = [double]$drive.Free
    Write-Field ('Trống trên ổ {0}:' -f $drive.Name) (Format-Size $free)
    $perPage = if ($totalPages -gt 0) { $totalBytes / $totalPages } else { 0 }
    if ($perPage -gt 0) {
        Write-Field 'Còn crawl được thêm' ('~{0:N0} trang trước khi đầy ổ' -f ($free / $perPage))
    }
}

# Tệp .tmp còn sót nghĩa là một lần ghi bị cắt ngang giữa chừng (xem
# ContentStorage.saveToJson: ghi ra .tmp rồi đổi tên). Corpus vẫn nguyên vẹn,
# nhưng tệp rác này chiếm chỗ và nên xoá.
$tmp = @(Get-ChildItem -LiteralPath $scope -Filter '*.json.tmp' -File -ErrorAction SilentlyContinue)
if ($tmp.Count -gt 0) {
    Write-Host ''
    foreach ($t in $tmp) {
        Write-Host ('  [CHÚ Ý] Còn tệp tạm "{0}" ({1}) — một lần ghi bị cắt ngang, xoá được.' -f $t.Name, (Format-Size $t.Length)) -ForegroundColor DarkYellow
    }
}

Write-Host ''
exit 0
