// Package livefootball là lớp bọc quanh `free-api-live-football-data` trên
// RapidAPI — nhà cung cấp thứ hai của service này.
//
// <h3>Vì sao có nhà cung cấp thứ hai</h3>
//
// API-Football (gói của bản iOS gốc) đòi một đăng ký riêng. Ai đã có sẵn một
// tài khoản RapidAPI với API này thì không có lý do gì phải đi mở thêm một tài
// khoản nữa chỉ để xem được tỉ số. Cả hai đều nói về bóng đá; chỉ có hình dạng
// JSON và cách đánh mã là khác — và đó đúng là thứ mà một lớp client sinh ra
// để nuốt.
//
// <h3>Ba chỗ nhà cung cấp này KHÔNG có, và cách đi vòng</h3>
//
//  1. **Không có endpoint lịch của một ĐỘI.** Lấy lịch cả giải rồi lọc theo
//     đội — mã giải tra được từ chính kết quả tìm đội.
//  2. **Không có endpoint danh sách đội của một giải.** Rút từ lịch cả giải:
//     đội nào có mặt trong mùa thì có trong danh sách.
//  3. **Trận đấu chỉ mang mã giải, không mang tên giải.** Danh sách giải được
//     nạp một lần rồi giữ trong bộ nhớ; nó gần như bất động.
package livefootball

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/KietAnhCS/coccoc-browser-like/football-service/internal/football"
)

// Host là tên máy chủ RapidAPI của nhà cung cấp này. Service dựa vào chuỗi này
// để chọn client, nên nó cũng là "khoá nhận dạng" của cả gói.
const Host = "free-api-live-football-data.p.rapidapi.com"

// Ảnh của FotMob — nhà cung cấp này là một lớp phủ lên dữ liệu của FotMob, nên
// mã đội và mã giải dùng thẳng được với kho ảnh của họ. Kho ảnh mở, không cần
// khoá.
const (
	teamLogoBase    = "https://images.fotmob.com/image_resources/logo/teamlogo/"
	leagueLogoBase  = "https://images.fotmob.com/image_resources/logo/leaguelogo/"
	playerPhotoBase = "https://images.fotmob.com/image_resources/playerimages/"
)

// Recorder nhận thông báo mỗi khi MỘT lượt gọi thật sự đi ra ngoài.
type Recorder interface {
	RecordCall(ctx context.Context, endpoint, params string)
}

// Client gọi free-api-live-football-data.
type Client struct {
	baseURL  string
	apiKey   string
	http     *http.Client
	recorder Recorder

	// leagues là bộ nhớ đệm trong tiến trình cho bảng tra mã giải → tên giải.
	//
	// Danh sách 126 giải gần như không đổi, trong khi MỌI trận trả về đều chỉ
	// mang mã. Không đệm thì mỗi lần dựng một danh sách trận là thêm một lượt
	// gọi ra ngoài chỉ để dịch một con số sang một cái tên.
	leaguesOnce sync.Once
	leaguesMu   sync.RWMutex
	leagueByID  map[string]football.League
	// popularIDs là các giải quốc nội lớn — chúng được xếp lên đầu danh sách.
	popularIDs map[string]bool
}

// New tạo một client. recorder có thể là nil.
func New(baseURL, apiKey string, timeout time.Duration, recorder Recorder) *Client {
	return &Client{
		baseURL:  strings.TrimRight(baseURL, "/"),
		apiKey:   apiKey,
		http:     &http.Client{Timeout: timeout},
		recorder: recorder,
	}
}

// get gửi một request và giải mã phần `response`.
func (c *Client) get(ctx context.Context, path string, params url.Values, out any) error {
	if c.apiKey == "" {
		return fmt.Errorf("chưa cấu hình FOOTBALL_API_KEY")
	}

	endpoint := c.baseURL + path
	if encoded := params.Encode(); encoded != "" {
		endpoint += "?" + encoded
	}

	request, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return fmt.Errorf("dựng request %s: %w", path, err)
	}
	request.Header.Set("accept", "application/json")
	request.Header.Set("x-rapidapi-key", c.apiKey)
	request.Header.Set("x-rapidapi-host", Host)

	response, err := c.http.Do(request)
	if err != nil {
		// Chưa tới được máy chủ của họ thì hạn mức chưa bị trừ — không ghi sổ.
		return fmt.Errorf("gọi %s: %w", path, err)
	}
	defer response.Body.Close()

	// Ghi sổ NGAY KHI có mã trạng thái, kể cả 4xx/5xx: hạn mức bên họ tính theo
	// request nhận được, không theo request thành công.
	if c.recorder != nil {
		c.recorder.RecordCall(ctx, path, params.Encode())
	}

	if response.StatusCode != http.StatusOK {
		return fmt.Errorf("%s trả %s", path, response.Status)
	}

	var body struct {
		Status   string          `json:"status"`
		Message  string          `json:"message"`
		Response json.RawMessage `json:"response"`
	}
	if err := json.NewDecoder(response.Body).Decode(&body); err != nil {
		return fmt.Errorf("đọc phản hồi %s: %w", path, err)
	}
	if body.Status != "" && body.Status != "success" {
		return fmt.Errorf("%s báo lỗi: %s", path, body.Message)
	}

	if err := json.Unmarshal(body.Response, out); err != nil {
		return fmt.Errorf("giải mã %s: %w", path, err)
	}
	return nil
}

func teamLogo(id string) string {
	if id == "" {
		return ""
	}
	return teamLogoBase + id + ".png"
}

func leagueLogo(id string) string {
	if id == "" {
		return ""
	}
	return leagueLogoBase + id + ".png"
}

// ── Giải đấu ────────────────────────────────────────────────────────────────

type leagueEnvelope struct {
	ID            int    `json:"id"`
	Name          string `json:"name"`
	LocalizedName string `json:"localizedName"`
	CountryCode   string `json:"ccode"`
	Logo          string `json:"logo"`
}

// Leagues trả về danh sách giải, lọc ở phía ta theo tên hoặc mã quốc gia.
//
// Nhà cung cấp không nhận tham số lọc nào, và danh sách chỉ có 126 dòng — lọc
// tại chỗ vừa nhanh hơn vừa không tiêu thêm một lượt hạn mức nào.
func (c *Client) Leagues(ctx context.Context, country, search string) ([]football.League, error) {
	all, err := c.allLeagues(ctx)
	if err != nil {
		return nil, err
	}

	needle := strings.ToLower(strings.TrimSpace(search))
	code := strings.ToLower(strings.TrimSpace(country))

	leagues := make([]football.League, 0, len(all))
	for _, league := range all {
		if code != "" && !strings.EqualFold(league.Country, code) {
			continue
		}
		if needle != "" && !strings.Contains(strings.ToLower(league.Name), needle) {
			continue
		}
		leagues = append(leagues, league)
	}
	return leagues, nil
}

// allLeagues nạp bảng giải một lần rồi giữ lại.
//
// Phải gộp HAI endpoint, và đây là chỗ dễ vấp nhất của nhà cung cấp này:
// `/football-get-all-leagues` nghe như "tất cả" nhưng thật ra chỉ có giải quốc
// tế và cúp — KHÔNG có Premier League, không có LaLiga. Các giải quốc nội nằm
// ở `/football-popular-leagues`. Chỉ gọi cái đầu thì mọi trận Ngoại hạng Anh
// đều hiện tên giải rỗng, trong khi mã giải hoàn toàn đúng.
func (c *Client) allLeagues(ctx context.Context) ([]football.League, error) {
	var fetchErr error
	c.leaguesOnce.Do(func() {
		table := make(map[string]football.League, 200)
		popular := map[string]bool{}

		add := func(items []leagueEnvelope, isPopular bool) {
			for _, item := range items {
				id := strconv.Itoa(item.ID)
				name := item.LocalizedName
				if name == "" {
					name = item.Name
				}
				icon := item.Logo
				if icon == "" {
					icon = leagueLogo(id)
				}
				table[id] = football.League{
					ID:      id,
					Name:    name,
					Country: item.CountryCode,
					Icon:    icon,
					Status:  "Active",
				}
				if isPopular {
					popular[id] = true
				}
			}
		}

		var popularBody struct {
			Popular []leagueEnvelope `json:"popular"`
		}
		if err := c.get(ctx, "/football-popular-leagues", nil, &popularBody); err != nil {
			fetchErr = err
			return
		}
		add(popularBody.Popular, true)

		var allBody struct {
			Leagues []leagueEnvelope `json:"leagues"`
		}
		if err := c.get(ctx, "/football-get-all-leagues", nil, &allBody); err != nil {
			// Danh sách giải phổ biến đã về thì đủ dùng cho gần như mọi màn
			// hình — không vứt nó đi chỉ vì lượt gọi thứ hai hỏng.
			add(nil, false)
		} else {
			add(allBody.Leagues, false)
		}

		c.leaguesMu.Lock()
		c.leagueByID = table
		c.popularIDs = popular
		c.leaguesMu.Unlock()
	})
	if fetchErr != nil {
		// Once đã "cháy": lần sau sẽ không thử lại nữa. Đặt lại để một lỗi mạng
		// nhất thời không khoá vĩnh viễn bảng tra cứu của cả tiến trình.
		c.leaguesOnce = sync.Once{}
		return nil, fetchErr
	}

	c.leaguesMu.RLock()
	defer c.leaguesMu.RUnlock()

	leagues := make([]football.League, 0, len(c.leagueByID))
	for _, league := range c.leagueByID {
		leagues = append(leagues, league)
	}
	// Giải phổ biến lên đầu: danh sách có hơn 200 dòng, và người mở tab Giải
	// đấu gần như luôn tìm một trong mươi giải đầu bảng — bắt họ cuộn qua
	// "AFC Challenge League" để tới Ngoại hạng Anh là sắp xếp theo bảng chữ
	// cái chứ không phải theo nhu cầu.
	sortLeagues(leagues, c.popularIDs)
	return leagues, nil
}

// lookupLeague tra tên giải từ mã. Không tra được thì trả về một giải chỉ có
// mã — thà thiếu tên còn hơn bỏ cả trận đấu.
func (c *Client) lookupLeague(ctx context.Context, id string) football.League {
	if _, err := c.allLeagues(ctx); err != nil {
		return football.League{ID: id, Icon: leagueLogo(id)}
	}

	c.leaguesMu.RLock()
	defer c.leaguesMu.RUnlock()

	if league, ok := c.leagueByID[id]; ok {
		return league
	}

	// Tra không ra thì để TRỐNG tên, không bịa một nhãn kiểu "Giải #47". Bảng
	// giải chỉ phủ vài trăm giải lớn, còn lịch một ngày chạm tới hàng trăm giải
	// nhỏ khắp thế giới — nên đây là chuyện thường. Một con số hiện ra ở chỗ
	// người xem chờ thấy tên giải trông y như lỗi dữ liệu; để trống thì giao
	// diện tự bỏ dòng ấy đi, và thẻ trận vẫn đọc được nhờ huy hiệu.
	return football.League{ID: id, Icon: leagueLogo(id)}
}
