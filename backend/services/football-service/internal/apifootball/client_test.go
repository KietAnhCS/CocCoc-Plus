package apifootball

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

// Cùng một dữ liệu được bán ở hai nơi, khác nhau đúng ở cách xưng danh. Gửi
// nhầm kiểu thì nhận về 403 "You are not subscribed to this API" — một thông
// báo trỏ người đọc đi tìm sai hướng hoàn toàn, vì khoá hoàn toàn đúng.
func TestAuthHeadersFollowTheBaseURL(t *testing.T) {
	cases := []struct {
		name    string
		baseURL string
		want    map[string]string
		absent  string
	}{
		{
			name:    "api-sports truc tiep",
			baseURL: "https://v3.football.api-sports.io",
			want:    map[string]string{"x-apisports-key": "khoa-test"},
			absent:  "x-rapidapi-key",
		},
		{
			name:    "qua RapidAPI",
			baseURL: "https://api-football-v1.p.rapidapi.com/v3",
			want: map[string]string{
				"x-rapidapi-key":  "khoa-test",
				"x-rapidapi-host": "api-football-v1.p.rapidapi.com",
			},
			absent: "x-apisports-key",
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			var got http.Header
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				got = r.Header.Clone()
				_, _ = w.Write([]byte(`{"response":[],"errors":[]}`))
			}))
			defer server.Close()

			// Giữ đường dẫn thật để phép suy ra host chạy trên đúng chuỗi mà
			// người dùng đặt, nhưng gọi vào máy chủ giả: client dùng baseURL để
			// suy host, còn ta thay bằng địa chỉ test sau khi đã dựng xong.
			client := New(tc.baseURL, "khoa-test", 5*time.Second, nil)
			client.baseURL = server.URL

			if _, err := client.get(context.Background(), "/leagues", nil); err != nil {
				t.Fatalf("gọi hỏng: %v", err)
			}

			for header, value := range tc.want {
				if got.Get(header) != value {
					t.Errorf("%s = %q, mong đợi %q", header, got.Get(header), value)
				}
			}
			if got.Get(tc.absent) != "" {
				t.Errorf("không được gửi %s cho %s", tc.absent, tc.baseURL)
			}
		})
	}
}

func TestRapidAPIHostOnlyMatchesRapidAPI(t *testing.T) {
	cases := map[string]string{
		"https://api-football-v1.p.rapidapi.com/v3": "api-football-v1.p.rapidapi.com",
		"https://v3.football.api-sports.io":         "",
		// Không được khớp một tên miền chỉ TÌNH CỜ chứa chuỗi ấy.
		"https://rapidapi.com.ke-gian.example": "",
		"::khong-phai-mot-url::":               "",
	}

	for baseURL, want := range cases {
		if got := rapidAPIHost(baseURL); got != want {
			t.Errorf("rapidAPIHost(%q) = %q, mong đợi %q", baseURL, got, want)
		}
	}
}
