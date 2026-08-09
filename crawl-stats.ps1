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
    [switch]$NoLinks,

    # Bỏ qua phần thống kê ảnh.
    [switch]$NoImages
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

# ------------------------------------------------------------------ quét ảnh

<#
    Tệp ảnh đi kèm một tệp corpus.

        data/crawled-documents.json  ->  data/crawled-documents.images.json

    Quy ước này do ImageStorage.pathFor phía Java đặt ra; hàm dưới đây chỉ lặp
    lại nó. Hai chỗ phải khớp nhau, nên nếu đổi thì đổi cả hai.
#>
function Get-ImagePath {
    param([string]$CorpusPath)
    if ($CorpusPath.EndsWith('.json')) {
        return $CorpusPath.Substring(0, $CorpusPath.Length - 5) + '.images.json'
    }
    return $CorpusPath + '.images.json'
}

<#
    Quét tệp ảnh, đọc TỪNG DÒNG — cùng cách và cùng lý do như Measure-Corpus.

    Bản ghi ImageFound do Jackson ghi ra với INDENT_OUTPUT nên mỗi trường nằm
    trên một dòng riêng:

        {
          "pageUrl" : "https://vnexpress.net/...",
          "host" : "vnexpress.net",
          "imageUrl" : "https://i1-kinhdoanh.vnecdn.net/....jpg",
          "altText" : "Nhà đầu tư theo dõi bảng điện tử",
          "declaredWidth" : 680,
          "declaredHeight" : 408,
          "sizeBytes" : -1,
          "contentHash" : null
        }

    Đếm theo "imageUrl" chứ không theo dấu ngoặc nhọn: dấu ngoặc còn thuộc về
    cấu trúc bao ngoài, còn imageUrl thì đúng một dòng cho mỗi ảnh.
#>
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

    # Số ảnh của TỪNG trang, để tính trung vị và trang nhiều ảnh nhất. Lý do
    # giống hệt phần outlinks: vài trang thư viện ảnh với hàng chục ảnh kéo
    # trung bình cộng lên cao hơn hẳn trang bài viết bình thường.
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

                    # Đuôi tệp: cho biết corpus ảnh nghiêng về ảnh nội dung
                    # (jpg/png) hay ảnh giao diện (svg/gif). Cắt tham số truy
                    # vấn trước, vì CDN hay gắn "?w=680&q=100" vào sau đuôi.
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
                # Chuỗi RỖNG nghĩa là thiếu alt — đúng định nghĩa của
                # ImageFound.missingAlt(). Chuỗi chỉ có khoảng trắng cũng tính
                # là thiếu, vì phía Java dùng isBlank() chứ không isEmpty().
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

    # Đếm số trang CHẠM TRẦN. Hai trần khác nhau cắt ở hai chỗ khác nhau:
    #
    #   50 = app.crawler.images.max-per-page  (cấu hình, ImageDownloadService)
    #   60 = ImageStore.MAX_IMAGES_PER_PAGE   (bất biến của kho, không đổi được)
    #
    # Trần cấu hình THẤP HƠN nên nó luôn cắt trước — nghĩa là trần 60 trên thực
    # tế không bao giờ chạm tới ở cấu hình mặc định. Chỉ kiểm tra mốc 60 thì
    # cảnh báo không bao giờ bắn, kể cả khi ảnh đang bị cắt thật.
    $atConfigCap = 0
    $atStoreCap  = 0
    foreach ($n in $perPage.Values) {
        if ($n -ge 60) { $atStoreCap++ }
        elseif ($n -ge 50) { $atConfigCap++ }
    }

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
        AtStoreCap  = $atStoreCap
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

    # Tỉ lệ có alt là THƯỚC ĐO CHẤT LƯỢNG của corpus ảnh, không phải số liệu
    # trang trí. ImageSearchController sắp ảnh có alt lên trước ảnh thiếu alt,
    # vì alt phân biệt ảnh NỘI DUNG với ảnh TRANG TRÍ (icon, logo). Tỉ lệ này
    # thấp nghĩa là lưới ảnh sẽ đầy icon.
    Write-Sub 'có văn bản thay thế' ('{0:N0}  ({1:P1})' -f $Stat.WithAlt, ($Stat.WithAlt / $Stat.Images))
    $missRatio = $Stat.MissingAlt / $Stat.Images
    $missNote = if ($missRatio -gt 0.5) {
        '  <- quá nửa là ảnh trang trí, lưới ảnh sẽ nhiều icon'
    } else { '' }
    Write-Sub 'thiếu văn bản thay thế' ('{0:N0}  ({1:P1}){2}' -f $Stat.MissingAlt, $missRatio, $missNote)

    if ($Stat.UniqueUrls -lt $Stat.Images) {
        Write-Sub 'địa chỉ ảnh khác nhau' ('{0:N0}  (cùng một ảnh xuất hiện trên nhiều trang)' -f $Stat.UniqueUrls)
    }

    # Kích thước khai báo trong HTML. Con số này quyết định lưới ảnh ở giao diện
    # có xếp đúng chỗ ngay từ đầu hay phải chờ ảnh tải xong mới đo được — xem
    # FALLBACK_RATIO trong ImageResultGrid.tsx.
    Write-Sub 'có khai báo kích thước' ('{0:N0}  ({1:P1})  — phần còn lại lưới phải tự đo lúc hiển thị' -f `
        $Stat.Declared, ($Stat.Declared / $Stat.Images))

    if ($Stat.Downloaded -gt 0) {
        Write-Sub 'đã tải nội dung' ('{0:N0}  ({1})' -f $Stat.Downloaded, (Format-Size $Stat.DownBytes))
    } else {
        Write-Sub 'đã tải nội dung' '0  — app.crawler.images.download=false (mặc định, chỉ lưu siêu dữ liệu)'
    }

    # --- phân bố theo trang ---
    Write-Host ''
    Write-Field 'Số trang có ảnh' ('{0:N0}' -f $Stat.Pages)
    if ($CorpusPages -gt 0) {
        if ($Stat.Pages -gt $CorpusPages) {
            # Kho ảnh nhắc tới NHIỀU trang hơn số trang có trong corpus. Không
            # phải lỗi làm tròn — nó nghĩa là hai tệp thuộc hai phiên crawl khác
            # nhau, thường do corpus bị ghi đè bằng --fresh mà tệp ảnh thì
            # không, hoặc do chép tay một trong hai tệp từ nơi khác về.
            #
            # Hệ quả thật: ImageSearchController tra ảnh THEO URL trang lấy từ
            # kết quả tìm kiếm, nên ảnh của những trang không nằm trong corpus
            # sẽ không bao giờ được trả về. Chúng chiếm chỗ trên đĩa mà không
            # bao giờ hiện ra.
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

    # Chạm trần nghĩa là có trang bị cắt bớt ảnh — mọi con số ở trên khi đó là
    # CHẶN DƯỚI, không phải con số thật. Nói rõ trần nào đang cắt, vì hai trần
    # sửa ở hai chỗ hoàn toàn khác nhau.
    if ($Stat.AtStoreCap -gt 0) {
        Write-Host ('       [CHÚ Ý] {0:N0} trang chạm trần ImageStore.MAX_IMAGES_PER_PAGE = 60.' -f $Stat.AtStoreCap) -ForegroundColor DarkYellow
        Write-Host '               Đây là bất biến của kho, phải sửa mã nguồn mới đổi được.' -ForegroundColor DarkGray
    }
    if ($Stat.AtConfigCap -gt 0) {
        Write-Host ('       [CHÚ Ý] {0:N0} trang ({1:P1}) chạm trần app.crawler.images.max-per-page = 50.' -f `
            $Stat.AtConfigCap, ($Stat.AtConfigCap / $Stat.Pages)) -ForegroundColor DarkYellow
        Write-Host '               Ảnh của những trang đó bị cắt bớt — số liệu trên là chặn dưới.' -ForegroundColor DarkGray
        Write-Host '               Nâng trong application.properties nếu muốn giữ nhiều ảnh hơn mỗi trang.' -ForegroundColor DarkGray
    }

    # --- tên miền ---
    Write-Host ''
    Write-Field 'Số tên miền có ảnh' ('{0:N0}' -f $Stat.Hosts.Count)
    $topHosts = $Stat.Hosts.GetEnumerator() | Sort-Object -Property Value -Descending
    foreach ($h in ($topHosts | Select-Object -First 10)) {
        Write-Host ('       {0,-34} {1,8:N0}  {2,6:P1}' -f $h.Key, $h.Value, ($h.Value / $Stat.Images)) -ForegroundColor DarkGray
    }
    if ($Stat.Hosts.Count -gt 10) {
        Write-Host ('       ... còn {0} tên miền nữa' -f ($Stat.Hosts.Count - 10)) -ForegroundColor DarkGray
    }

    # --- định dạng ---
    Write-Host ''
    Write-Field 'Định dạng ảnh' ('{0:N0} loại đuôi tệp' -f $Stat.Extensions.Count)
    $topExt = $Stat.Extensions.GetEnumerator() | Sort-Object -Property Value -Descending
    foreach ($e in ($topExt | Select-Object -First 8)) {
        Write-Host ('       {0,-34} {1,8:N0}  {2,6:P1}' -f $e.Key, $e.Value, ($e.Value / $Stat.Images)) -ForegroundColor DarkGray
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
    # LOẠI tệp ảnh khỏi danh sách corpus. Chúng nằm cùng thư mục và cũng có
    # đuôi .json, nên nếu không lọc thì mỗi tệp ảnh bị quét như một corpus rồi
    # báo "không nhận ra định dạng" — một dòng cảnh báo sai cho một tệp hoàn
    # toàn bình thường. Chúng được báo cáo ở đúng chỗ: kèm theo corpus của mình.
    $files = @(Get-ChildItem -LiteralPath $item.FullName -Filter '*.json' -File |
        Where-Object { $_.Name -notlike '*.images.json' } |
        Sort-Object Length -Descending)
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

    # Ảnh: báo cáo NGAY DƯỚI corpus tương ứng, không gom thành một mục riêng ở
    # cuối. Mọi tỉ lệ đáng đọc đều là tỉ lệ giữa hai bên ("bao nhiêu phần trăm
    # trang có ảnh"), nên đặt xa nhau là bắt người đọc tự ghép số.
    # `$s.Pages -gt 0`: bỏ qua hoàn toàn phần ảnh cho tệp KHÔNG PHẢI corpus.
    # Thư mục data còn chứa index.json — chỉ mục đã dựng sẵn, không có trường
    # "url" nào. Không có điều kiện này thì mỗi lần chạy lại in ra một lời
    # khuyên "hãy chạy run-crawl.bat để sinh index.images.json", tức là mách
    # người dùng đi tìm một tệp không bao giờ tồn tại và cũng không nên tồn tại.
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
            # Không có tệp ảnh KHÔNG phải lỗi — nhưng nó có đúng một nguyên nhân
            # và một cách sửa, nên nói thẳng ra thay vì im lặng bỏ qua.
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

# Tổng hợp + chỗ trống còn lại của ổ đĩa: câu hỏi "tốn bao nhiêu GB" chỉ có
# nghĩa khi đặt cạnh dung lượng trống.
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
    # Cộng tay chứ không `Measure-Object -Property { ... }`: PowerShell 5.1
    # KHÔNG nhận khối script làm tên thuộc tính, nó ném
    # GenericMeasurePropertyNotFound. Cú pháp đó chỉ có từ PowerShell 7.
    $totalImgBytes = 0L
    foreach ($i in $imageStats) { $totalImgBytes += $i.File.Length }

    Write-Field 'Tổng số ảnh' ('{0:N0}' -f $totalImages)
    if ($totalImages -gt 0) {
        Write-Sub 'có văn bản thay thế' ('{0:N0}  ({1:P1})' -f $totalWithAlt, ($totalWithAlt / $totalImages))

        # Cùng lý do như trong Show-ImageReport: khi kho ảnh và corpus lệch
        # phiên, tỉ lệ vượt 100% và trở thành một con số vô nghĩa. In số tuyệt
        # đối thay vì một phần trăm không đọc được.
        if ($totalImgPages -gt $totalPages) {
            Write-Sub 'trang có ảnh' ('{0:N0}  <- NHIỀU HƠN tổng số trang corpus, hai bên lệch phiên crawl' -f $totalImgPages)
        } else {
            Write-Sub 'trang có ảnh' ('{0:N0}  ({1:P1} tổng số trang)' -f `
                $totalImgPages, ($totalImgPages / [Math]::Max(1, $totalPages)))
            Write-Sub 'mỗi trang' ('{0:N1} ảnh trung bình' -f ($totalImages / [Math]::Max(1, $totalPages)))
        }
    }
    # Cộng tệp ảnh vào tổng dung lượng: chúng là một phần của "corpus tốn bao
    # nhiêu GB", và bỏ chúng ra khiến ước tính dung lượng phía dưới thiếu hụt.
    $totalBytes += $totalImgBytes
    Write-Sub 'dung lượng tệp ảnh' (Format-Size $totalImgBytes)
}

# Nhãn "(corpus + ảnh)" chỉ đúng khi THẬT SỰ có tệp ảnh được cộng vào. Dán nó
# vô điều kiện thì ở một corpus chưa có ảnh, người đọc tưởng ảnh đã được tính
# và kết luận sai rằng ảnh gần như không tốn dung lượng.
$sizeLabel = if ($imageStats.Count -gt 0) { '  (corpus + ảnh)' } else { '  (chưa có tệp ảnh nào)' }
Write-Field 'Tổng dung lượng' ((Format-Size $totalBytes) + $sizeLabel)

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
