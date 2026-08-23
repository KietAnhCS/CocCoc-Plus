// Package apifootball là lớp bọc quanh API-Football (v3.football.api-sports.io).
//
// Nó chỉ làm hai việc: gọi HTTP đúng cách, và chuyển hình dạng JSON của nhà
// cung cấp về các kiểu trong gói football. Nó KHÔNG biết gì về bộ nhớ đệm hay
// hạn mức — hai thứ đó nằm ở gói service, vì chúng là chính sách của ta chứ
// không phải của nhà cung cấp.
package apifootball

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// Recorder nhận thông báo mỗi khi MỘT lượt gọi thật sự đi ra ngoài.
//
// Vì sao cái móc này thuộc về client chứ không thuộc về lớp gọi: chỉ client
// mới biết một thao tác logic tốn bao nhiêu lượt. "Chi tiết trận đấu" là một
// lời gọi hàm nhưng là bốn lượt HTTP; đếm ở lớp trên sẽ ra bốn lần một.
type Recorder interface {
	RecordCall(ctx context.Context, endpoint, params string)
}

// Client gọi API-Football.
type Client struct {
	baseURL string
	apiKey  string
	// rapidHost khác rỗng khi baseURL đi qua RapidAPI — xem authHeaders.
	rapidHost string
	http      *http.Client
	recorder  Recorder
}

// New tạo một client. recorder có thể là nil.
func New(baseURL, apiKey string, timeout time.Duration, recorder Recorder) *Client {
	return &Client{
		baseURL:   strings.TrimRight(baseURL, "/"),
		apiKey:    apiKey,
		rapidHost: rapidAPIHost(baseURL),
		http:      &http.Client{Timeout: timeout},
		recorder:  recorder,
	}
}

// rapidAPIHost trả về tên máy chủ RapidAPI nếu baseURL đi qua đó, ngược lại
// trả về chuỗi rỗng.
//
// API-Football được bán ở HAI nơi, cùng một dữ liệu, cùng một hình dạng phản
// hồi, khác đúng một thứ: cách xưng danh.
//
//	api-sports.io   →  x-apisports-key: <khoá>
//	RapidAPI        →  x-rapidapi-key: <khoá>  +  x-rapidapi-host: <host>
//
// Suy ra từ chính địa chỉ thay vì bắt người dùng đặt thêm một biến môi trường:
// hai giá trị ấy không bao giờ mâu thuẫn được — đã trỏ vào rapidapi.com thì
// không có cách nào xưng danh kiểu kia mà đúng. Một biến rời chỉ tạo thêm một
// chỗ để cấu hình sai, và cấu hình sai ở đây hiện ra thành 403 khó hiểu.
func rapidAPIHost(baseURL string) string {
	parsed, err := url.Parse(baseURL)
	if err != nil {
		return ""
	}
	host := parsed.Hostname()
	if strings.HasSuffix(host, ".rapidapi.com") {
		return host
	}
	return ""
}

// authHeaders gắn phần xưng danh, đúng kiểu mà máy chủ đang gọi chờ đợi.
func (c *Client) authHeaders(request *http.Request) {
	if c.rapidHost != "" {
		request.Header.Set("x-rapidapi-key", c.apiKey)
		request.Header.Set("x-rapidapi-host", c.rapidHost)
		return
	}
	request.Header.Set("x-apisports-key", c.apiKey)
}

// envelope là lớp vỏ mà MỌI phản hồi của API-Football đều dùng.
type envelope struct {
	Response json.RawMessage `json:"response"`
	// Errors là chỗ khó chịu nhất của API này: khi sai khoá, sai tham số, hay
	// hết hạn mức, họ vẫn trả HTTP 200 và nhét lỗi vào đây. Không đọc trường
	// này thì mọi hỏng hóc sẽ hiện ra thành "không có trận nào hôm nay" — một
	// câu trả lời hợp lệ, chỉ có điều sai.
	//
	// Kiểu của nó không ổn định: mảng rỗng `[]` khi không lỗi, đối tượng
	// `{"token": "..."} ` khi có. Vì vậy phải giữ dạng thô rồi tự xét.
	Errors json.RawMessage `json:"errors"`
}

// get gửi một request và trả về phần `response` ở dạng thô.
func (c *Client) get(ctx context.Context, path string, params url.Values) (json.RawMessage, error) {
	if c.apiKey == "" {
		return nil, fmt.Errorf("chưa cấu hình FOOTBALL_API_KEY")
	}

	endpoint := c.baseURL + path
	if encoded := params.Encode(); encoded != "" {
		endpoint += "?" + encoded
	}

	request, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return nil, fmt.Errorf("dựng request %s: %w", path, err)
	}
	request.Header.Set("accept", "application/json")
	c.authHeaders(request)

	response, err := c.http.Do(request)
	if err != nil {
		// Chưa tới được máy chủ của họ thì hạn mức chưa bị trừ — không ghi sổ.
		return nil, fmt.Errorf("gọi %s: %w", path, err)
	}
	defer response.Body.Close()

	// Ghi sổ NGAY KHI có mã trạng thái, kể cả 4xx/5xx. Hạn mức bên họ tính
	// theo request nhận được, không theo request thành công; chỉ ghi khi 200
	// sẽ làm bộ đếm của ta thấp hơn của họ và ta tưởng còn hạn mức trong khi
	// đã hết.
	if c.recorder != nil {
		c.recorder.RecordCall(ctx, path, params.Encode())
	}

	if response.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("API-Football trả %d cho %s", response.StatusCode, path)
	}

	var body envelope
	if err := json.NewDecoder(response.Body).Decode(&body); err != nil {
		return nil, fmt.Errorf("giải mã phản hồi %s: %w", path, err)
	}

	if message := errorMessage(body.Errors); message != "" {
		return nil, fmt.Errorf("API-Football báo lỗi ở %s: %s", path, message)
	}

	return body.Response, nil
}

// errorMessage rút một câu lỗi từ trường `errors` có kiểu không ổn định.
// Trả về chuỗi rỗng khi không có lỗi.
func errorMessage(raw json.RawMessage) string {
	trimmed := strings.TrimSpace(string(raw))
	if trimmed == "" || trimmed == "[]" || trimmed == "{}" || trimmed == "null" {
		return ""
	}

	var asMap map[string]string
	if err := json.Unmarshal(raw, &asMap); err == nil {
		parts := make([]string, 0, len(asMap))
		for key, value := range asMap {
			parts = append(parts, key+": "+value)
		}
		return strings.Join(parts, "; ")
	}

	return trimmed
}
