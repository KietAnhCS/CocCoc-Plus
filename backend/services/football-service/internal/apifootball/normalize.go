package apifootball

import (
	"strconv"
	"time"

	"github.com/KietAnhCS/coccoc-browser-like/football-service/internal/football"
)

// Các kiểu dưới đây chỉ mô tả ĐÚNG những trường ta dùng, không phải toàn bộ
// phản hồi của API-Football. encoding/json bỏ qua trường thừa, nên khai báo
// hẹp lại vừa ngắn vừa bền: nhà cung cấp thêm trường mới không làm gì hỏng.

type leagueEnvelope struct {
	League struct {
		ID   int    `json:"id"`
		Name string `json:"name"`
		Logo string `json:"logo"`
	} `json:"league"`
	Country struct {
		Name string `json:"name"`
		Flag string `json:"flag"`
	} `json:"country"`
	Seasons []struct {
		Year    int  `json:"year"`
		Current bool `json:"current"`
	} `json:"seasons"`
}

func (e leagueEnvelope) normalize() football.League {
	status := "Offseason"
	for _, season := range e.Seasons {
		if season.Current {
			status = "Active"
			break
		}
	}

	return football.League{
		ID:      itoa(e.League.ID),
		Name:    e.League.Name,
		Country: e.Country.Name,
		Icon:    e.League.Logo,
		Flag:    e.Country.Flag,
		Status:  status,
	}
}

type fixtureEnvelope struct {
	Fixture struct {
		ID     int    `json:"id"`
		Date   string `json:"date"`
		Status struct {
			Short   string `json:"short"`
			Elapsed *int   `json:"elapsed"`
		} `json:"status"`
	} `json:"fixture"`
	League struct {
		ID    int    `json:"id"`
		Name  string `json:"name"`
		Round string `json:"round"`
		Logo  string `json:"logo"`
	} `json:"league"`
	Teams struct {
		Home fixtureTeam `json:"home"`
		Away fixtureTeam `json:"away"`
	} `json:"teams"`
	Goals struct {
		Home *int `json:"home"`
		Away *int `json:"away"`
	} `json:"goals"`
}

type fixtureTeam struct {
	ID   int    `json:"id"`
	Name string `json:"name"`
	Logo string `json:"logo"`
}

func (t fixtureTeam) normalize() football.Team {
	return football.Team{
		ID:        itoa(t.ID),
		Name:      t.Name,
		ShortName: t.Name,
		Emblem:    t.Logo,
	}
}

func (e fixtureEnvelope) normalize() football.Match {
	// Giờ thi đấu hỏng thì để zero value chứ không bỏ cả trận đi: một trận
	// thiếu giờ vẫn cho biết tỉ số, còn bỏ nó đi thì người dùng chỉ thấy danh
	// sách ngắn đi mà không hiểu vì sao.
	kickoff, err := time.Parse(time.RFC3339, e.Fixture.Date)
	if err != nil {
		kickoff = time.Time{}
	}

	status := normalizeStatus(e.Fixture.Status.Short)

	// Số phút đã đá chỉ có nghĩa khi trận đang diễn ra. API-Football vẫn trả
	// `elapsed: 90` cho trận đã kết thúc, và hiện "90'" cạnh một tỉ số chung
	// cuộc trông như trận còn đang đá.
	var elapsed *int
	if status == football.StatusLive {
		elapsed = e.Fixture.Status.Elapsed
	}

	return football.Match{
		ID:              itoa(e.Fixture.ID),
		Competition:     e.League.Name,
		CompetitionID:   itoa(e.League.ID),
		CompetitionLogo: e.League.Logo,
		Round:           e.League.Round,
		Status:          status,
		Elapsed:         elapsed,
		Kickoff:         kickoff,
		HomeTeam:        e.Teams.Home.normalize(),
		AwayTeam:        e.Teams.Away.normalize(),
		HomeScore:       e.Goals.Home,
		AwayScore:       e.Goals.Away,
	}
}

// normalizeStatus quy mã trạng thái hai ký tự của API-Football về ba trạng
// thái mà giao diện cần.
//
// Danh sách mã lấy từ tài liệu của họ. Nhánh mặc định là "scheduled" chứ không
// phải "live": đoán nhầm một trận chưa đá thành đang đá sẽ đẩy nó lên đầu danh
// sách kèm một tỉ số 0-0 giả, còn đoán nhầm chiều ngược lại chỉ khiến nó nằm
// đúng chỗ theo giờ thi đấu.
func normalizeStatus(short string) football.Status {
	switch short {
	case "FT", "AET", "PEN", "WO", "AWD":
		return football.StatusFinished
	case "1H", "HT", "2H", "ET", "BT", "P", "SUSP", "INT", "LIVE":
		return football.StatusLive
	default:
		return football.StatusScheduled
	}
}

type teamEnvelope struct {
	Team struct {
		ID      int    `json:"id"`
		Name    string `json:"name"`
		Code    string `json:"code"`
		Country string `json:"country"`
		Logo    string `json:"logo"`
		Founded *int   `json:"founded"`
	} `json:"team"`
}

func (e teamEnvelope) normalize() football.Team {
	shortName := e.Team.Code
	if shortName == "" {
		shortName = e.Team.Name
	}

	return football.Team{
		ID:        itoa(e.Team.ID),
		Name:      e.Team.Name,
		ShortName: shortName,
		Emblem:    e.Team.Logo,
		Country:   e.Team.Country,
		Founded:   e.Team.Founded,
	}
}

type playerEnvelope struct {
	Player struct {
		ID          int    `json:"id"`
		Name        string `json:"name"`
		FirstName   string `json:"firstname"`
		LastName    string `json:"lastname"`
		Age         *int   `json:"age"`
		Nationality string `json:"nationality"`
		Height      string `json:"height"`
		Weight      string `json:"weight"`
		Photo       string `json:"photo"`
		Injured     bool   `json:"injured"`
	} `json:"player"`
	Statistics []struct {
		Team struct {
			ID   int    `json:"id"`
			Name string `json:"name"`
			Logo string `json:"logo"`
		} `json:"team"`
		League struct {
			ID      int    `json:"id"`
			Name    string `json:"name"`
			Country string `json:"country"`
			Season  *int   `json:"season"`
		} `json:"league"`
		Games struct {
			Appearences *int   `json:"appearences"` // viết sai chính tả ở phía họ
			Minutes     *int   `json:"minutes"`
			Position    string `json:"position"`
			Rating      string `json:"rating"`
		} `json:"games"`
		Goals struct {
			Total   *int `json:"total"`
			Assists *int `json:"assists"`
		} `json:"goals"`
		Cards struct {
			Yellow *int `json:"yellow"`
			Red    *int `json:"red"`
		} `json:"cards"`
	} `json:"statistics"`
}

func (e playerEnvelope) normalize() football.Player {
	stats := make([]football.PlayerStat, 0, len(e.Statistics))
	for _, s := range e.Statistics {
		stats = append(stats, football.PlayerStat{
			TeamID:        itoa(s.Team.ID),
			TeamName:      s.Team.Name,
			TeamLogo:      s.Team.Logo,
			LeagueID:      itoa(s.League.ID),
			LeagueName:    s.League.Name,
			LeagueCountry: s.League.Country,
			Season:        s.League.Season,
			Position:      s.Games.Position,
			Appearances:   deref(s.Games.Appearences),
			MinutesPlayed: deref(s.Games.Minutes),
			Rating:        parseRating(s.Games.Rating),
			Goals:         deref(s.Goals.Total),
			Assists:       deref(s.Goals.Assists),
			YellowCards:   deref(s.Cards.Yellow),
			RedCards:      deref(s.Cards.Red),
		})
	}

	return football.Player{
		ID:          itoa(e.Player.ID),
		Name:        e.Player.Name,
		FirstName:   e.Player.FirstName,
		LastName:    e.Player.LastName,
		Age:         e.Player.Age,
		Nationality: e.Player.Nationality,
		Height:      e.Player.Height,
		Weight:      e.Player.Weight,
		Photo:       e.Player.Photo,
		Injured:     e.Player.Injured,
		Statistics:  stats,
	}
}

func deref(value *int) int {
	if value == nil {
		return 0
	}
	return *value
}

// parseRating đọc điểm phong độ, thứ mà API-Football trả về dưới dạng CHUỖI
// ("7.283333") chứ không phải số, và bỏ trống bằng chuỗi rỗng.
//
// Trả con trỏ để giữ được khác biệt giữa "chưa chấm điểm" và "điểm 0.0" — một
// cầu thủ chưa ra sân không phải là một cầu thủ chơi tệ nhất có thể.
func parseRating(raw string) *float64 {
	if raw == "" {
		return nil
	}
	value, err := strconv.ParseFloat(raw, 64)
	if err != nil {
		return nil
	}
	return &value
}
