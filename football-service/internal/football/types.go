// Package football định nghĩa các kiểu dữ liệu mà service TRẢ RA ngoài.
//
// Chúng cố ý tách khỏi hình dạng JSON của API-Football. Nhà cung cấp đó gói
// mọi thứ trong `{"response": [...]}` với những tên trường như `appearences`
// (viết sai chính tả trong chính tài liệu của họ) và trạng thái trận đấu là mã
// hai ký tự kiểu `FT`, `1H`. Đưa thẳng hình dạng ấy ra ngoài nghĩa là mọi máy
// khách — trình duyệt, ứng dụng iOS — đều phải học lấy nó, và đổi nhà cung cấp
// về sau sẽ là đổi luôn giao diện. Một tầng chuẩn hoá ở đây giữ chi phí ấy nằm
// trong đúng một tệp.
package football

import (
	"context"
	"time"
)

// FixtureQuery là bộ lọc cho danh sách trận đấu.
//
// Nằm ở gói này chứ không ở gói của một nhà cung cấp cụ thể, vì nó là NGÔN NGỮ
// CHUNG mà tầng service dùng để hỏi bất kỳ nhà cung cấp nào. Mỗi client tự
// dịch nó sang tham số của riêng mình.
type FixtureQuery struct {
	Date   string // YYYY-MM-DD
	League string
	Season string
	Team   string
	Live   bool
}

// Provider là thứ mà tầng service cần ở một nhà cung cấp dữ liệu bóng đá.
//
// Có giao diện này vì repo hiện nói chuyện được với HAI nhà cung cấp khác hẳn
// nhau về hình dạng JSON lẫn cách đánh mã. Mọi khác biệt ấy dừng lại ở tầng
// client; từ service trở lên, cả hệ thống chỉ biết đúng các kiểu trong gói này.
type Provider interface {
	Leagues(ctx context.Context, country, search string) ([]League, error)
	Fixtures(ctx context.Context, query FixtureQuery) ([]Match, error)
	Teams(ctx context.Context, search, league, season string) ([]Team, error)
	Players(ctx context.Context, search string) ([]Player, error)
	Player(ctx context.Context, playerID, season string) (Player, error)
}

// Status là trạng thái của một trận đấu, đã quy về ba giá trị.
type Status string

const (
	StatusScheduled Status = "scheduled"
	StatusLive      Status = "live"
	StatusFinished  Status = "finished"
)

// Team là một đội bóng ở mức đủ để vẽ một dòng tỉ số.
type Team struct {
	ID        string `json:"id"`
	Name      string `json:"name"`
	ShortName string `json:"shortName"`
	Emblem    string `json:"emblem"`
	Country   string `json:"country,omitempty"`
	Founded   *int   `json:"founded,omitempty"`
	// LeagueID là giải mà đội đang đá.
	//
	// Có mặt vì một nhà cung cấp không có endpoint "lịch của đội": muốn lịch
	// của một đội thì phải lấy lịch của GIẢI rồi lọc, nên mã giải phải đi cùng
	// đội ngay từ lúc tìm ra nó — nếu không, giao diện cầm mã đội trong tay mà
	// không hỏi được gì.
	LeagueID string `json:"leagueId,omitempty"`
}

// Match là một trận đấu.
type Match struct {
	ID              string `json:"id"`
	Competition     string `json:"competition"`
	CompetitionID   string `json:"competitionId"`
	CompetitionLogo string `json:"competitionLogo"`
	Round           string `json:"round"`
	Status          Status `json:"status"`
	// Elapsed là số phút đã đá, chỉ có nghĩa khi Status là live. Dùng con trỏ
	// để phân biệt "phút thứ 0" với "không biết" — trận chưa đá và trận vừa
	// bắt đầu là hai chuyện khác nhau.
	Elapsed   *int      `json:"elapsed"`
	Kickoff   time.Time `json:"kickoff"`
	HomeTeam  Team      `json:"homeTeam"`
	AwayTeam  Team      `json:"awayTeam"`
	HomeScore *int      `json:"homeScore"`
	AwayScore *int      `json:"awayScore"`
}

// League là một giải đấu.
type League struct {
	ID      string `json:"id"`
	Name    string `json:"name"`
	Country string `json:"country"`
	Icon    string `json:"icon"`
	Flag    string `json:"flag"`
	Status  string `json:"status"`
}

// PlayerStat là thống kê của một cầu thủ trong một mùa, ở một đội.
type PlayerStat struct {
	TeamID        string   `json:"teamId"`
	TeamName      string   `json:"teamName"`
	TeamLogo      string   `json:"teamLogo"`
	LeagueID      string   `json:"leagueId"`
	LeagueName    string   `json:"leagueName"`
	LeagueCountry string   `json:"leagueCountry"`
	Season        *int     `json:"season"`
	Position      string   `json:"position"`
	Appearances   int      `json:"appearances"`
	MinutesPlayed int      `json:"minutesPlayed"`
	Rating        *float64 `json:"rating"`
	Goals         int      `json:"goals"`
	Assists       int      `json:"assists"`
	YellowCards   int      `json:"yellowCards"`
	RedCards      int      `json:"redCards"`
}

// Player là hồ sơ một cầu thủ kèm thống kê theo mùa.
type Player struct {
	ID          string       `json:"id"`
	Name        string       `json:"name"`
	FirstName   string       `json:"firstName"`
	LastName    string       `json:"lastName"`
	Age         *int         `json:"age"`
	Nationality string       `json:"nationality"`
	Height      string       `json:"height"`
	Weight      string       `json:"weight"`
	Photo       string       `json:"photo"`
	Injured     bool         `json:"injured"`
	Statistics  []PlayerStat `json:"statistics"`
}
