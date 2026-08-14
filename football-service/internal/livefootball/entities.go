package livefootball

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/url"
	"sort"
	"strings"

	"github.com/KietAnhCS/coccoc-browser-like/football-service/internal/football"
)

// flexID là một mã có lúc là số, có lúc là chuỗi.
//
// Không phải chuyện thẩm mỹ: `/football-get-matches-by-date` trả `"id": 5225838`
// còn `/football-get-all-matches-by-league` trả `"id": "4813374"` — CÙNG một
// trường, trong CÙNG một API. Khai báo kiểu string thì endpoint đầu làm cả lô
// hỏng giải mã; khai báo int thì endpoint sau hỏng. Kiểu này nhận cả hai và
// luôn nhả ra chuỗi.
type flexID struct{ value string }

func (f flexID) String() string { return f.value }

func (f *flexID) UnmarshalJSON(raw []byte) error {
	raw = bytes.TrimSpace(raw)
	if len(raw) == 0 || bytes.Equal(raw, []byte("null")) {
		f.value = ""
		return nil
	}

	if raw[0] == '"' {
		var text string
		if err := json.Unmarshal(raw, &text); err != nil {
			return err
		}
		f.value = text
		return nil
	}

	var number json.Number
	if err := json.Unmarshal(raw, &number); err != nil {
		return fmt.Errorf("mã không phải chuỗi cũng không phải số: %s", raw)
	}
	f.value = number.String()
	return nil
}

// ── Đội bóng ────────────────────────────────────────────────────────────────

type teamSuggestion struct {
	Type       string `json:"type"`
	ID         flexID `json:"id"`
	Name       string `json:"name"`
	LeagueID   flexID `json:"leagueId"`
	LeagueName string `json:"leagueName"`
}

// Teams tìm đội theo tên, hoặc liệt kê đội của một giải.
//
// Liệt kê theo giải KHÔNG có endpoint riêng ở nhà cung cấp này, nên nó được
// rút ra từ lịch cả mùa: đội nào có tên trong lịch thì có trong giải. Cách này
// còn đúng hơn một bảng xếp hạng — đội mới lên hạng giữa mùa vẫn có mặt.
func (c *Client) Teams(ctx context.Context, search, league, season string) ([]football.Team, error) {
	if strings.TrimSpace(search) == "" {
		return c.teamsOfLeague(ctx, league)
	}

	params := url.Values{}
	params.Set("search", strings.TrimSpace(search))

	var body struct {
		Suggestions []teamSuggestion `json:"suggestions"`
	}
	if err := c.get(ctx, "/football-teams-search", params, &body); err != nil {
		return nil, err
	}

	teams := make([]football.Team, 0, len(body.Suggestions))
	for _, item := range body.Suggestions {
		if item.Type != "" && item.Type != "team" {
			continue
		}
		teams = append(teams, football.Team{
			ID:        item.ID.String(),
			Name:      item.Name,
			ShortName: item.Name,
			Emblem:    teamLogo(item.ID.String()),
			// Nhà cung cấp không trả quốc gia của đội; tên giải là thứ gần
			// nhất và cũng là thứ giúp phân biệt hai đội trùng tên.
			Country:  item.LeagueName,
			LeagueID: item.LeagueID.String(),
		})
	}
	return teams, nil
}

func (c *Client) teamsOfLeague(ctx context.Context, leagueID string) ([]football.Team, error) {
	envelopes, err := c.leagueMatches(ctx, leagueID)
	if err != nil {
		return nil, err
	}

	league := c.lookupLeague(ctx, leagueID)
	seen := map[string]bool{}
	teams := make([]football.Team, 0, 24)

	for _, envelope := range envelopes {
		for _, s := range []side{envelope.Home, envelope.Away} {
			team := s.team()
			if team.ID == "" || seen[team.ID] {
				continue
			}
			seen[team.ID] = true
			team.Country = league.Name
			team.LeagueID = league.ID
			teams = append(teams, team)
		}
	}

	sort.Slice(teams, func(i, j int) bool { return teams[i].Name < teams[j].Name })
	return teams, nil
}

// TeamLeague trả về mã giải của một đội, tra qua chính ô tìm kiếm.
//
// Tầng service cần nó vì lịch của một đội phải đi vòng qua lịch của giải — xem
// Fixtures.
func (c *Client) TeamLeague(ctx context.Context, teamID, teamName string) (string, error) {
	params := url.Values{}
	params.Set("search", teamName)

	var body struct {
		Suggestions []teamSuggestion `json:"suggestions"`
	}
	if err := c.get(ctx, "/football-teams-search", params, &body); err != nil {
		return "", err
	}
	for _, item := range body.Suggestions {
		if item.ID.String() == teamID {
			return item.LeagueID.String(), nil
		}
	}
	return "", nil
}

// ── Cầu thủ ─────────────────────────────────────────────────────────────────

type playerSuggestion struct {
	Type     string `json:"type"`
	ID       flexID `json:"id"`
	Name     string `json:"name"`
	IsCoach  bool   `json:"isCoach"`
	TeamID   flexID `json:"teamId"`
	TeamName string `json:"teamName"`
}

// Players tìm cầu thủ theo tên.
func (c *Client) Players(ctx context.Context, search string) ([]football.Player, error) {
	params := url.Values{}
	params.Set("search", strings.TrimSpace(search))

	var body struct {
		Suggestions []playerSuggestion `json:"suggestions"`
	}
	if err := c.get(ctx, "/football-players-search", params, &body); err != nil {
		return nil, err
	}

	players := make([]football.Player, 0, len(body.Suggestions))
	for _, item := range body.Suggestions {
		// Huấn luyện viên lọt chung một danh sách với cầu thủ. Tab Cầu thủ hỏi
		// về cầu thủ, và một hàng "Pep Guardiola" giữa danh sách ấy chỉ làm
		// người tìm phân vân.
		if item.IsCoach || (item.Type != "" && item.Type != "player") {
			continue
		}
		players = append(players, football.Player{
			ID:    item.ID.String(),
			Name:  item.Name,
			Photo: playerPhotoBase + item.ID.String() + ".png",
			Statistics: []football.PlayerStat{{
				TeamID:   item.TeamID.String(),
				TeamName: item.TeamName,
				TeamLogo: teamLogo(item.TeamID.String()),
			}},
		})
	}
	return players, nil
}

// detailEntry là một dòng trong bảng thông tin cầu thủ.
//
// Nhà cung cấp trả hồ sơ dưới dạng một DANH SÁCH cặp nhãn–giá trị chứ không
// phải một đối tượng có trường cố định, và `fallback` khi thì chuỗi khi thì số.
// Vì vậy phải giữ dạng thô rồi tự đọc.
type detailEntry struct {
	Title          string `json:"title"`
	TranslationKey string `json:"translationKey"`
	CountryCode    string `json:"countryCode"`
	Value          struct {
		NumberValue *float64        `json:"numberValue"`
		Fallback    json.RawMessage `json:"fallback"`
	} `json:"value"`
}

func (e detailEntry) text() string {
	if len(e.Value.Fallback) == 0 {
		return ""
	}
	var asText string
	if err := json.Unmarshal(e.Value.Fallback, &asText); err == nil {
		return asText
	}
	var asNumber json.Number
	if err := json.Unmarshal(e.Value.Fallback, &asNumber); err == nil {
		return asNumber.String()
	}
	return ""
}

// Player lấy hồ sơ một cầu thủ.
//
// `season` bị bỏ qua: nhà cung cấp này không tách hồ sơ theo mùa. Giữ tham số
// để cùng một chữ ký với nhà cung cấp kia — tầng service không phải biết mình
// đang nói chuyện với ai.
func (c *Client) Player(ctx context.Context, playerID, season string) (football.Player, error) {
	params := url.Values{}
	params.Set("playerid", playerID)

	var body struct {
		Detail []detailEntry `json:"detail"`
	}
	if err := c.get(ctx, "/football-get-player-detail", params, &body); err != nil {
		return football.Player{}, err
	}

	player := football.Player{
		ID:    playerID,
		Photo: playerPhotoBase + playerID + ".png",
	}

	for _, entry := range body.Detail {
		switch entry.TranslationKey {
		case "height_sentencecase":
			player.Height = entry.text()
		case "age_sentencecase":
			if entry.Value.NumberValue != nil {
				age := int(*entry.Value.NumberValue)
				player.Age = &age
			}
		case "country_sentencecase":
			player.Nationality = entry.text()
		case "name":
			player.Name = entry.text()
		}
	}

	if player.Name == "" {
		// Endpoint hồ sơ không trả về tên. Giao diện đã có tên từ danh sách tìm
		// kiếm và chỉ dùng bản chi tiết để bù thêm, nên để trống ở đây an toàn
		// hơn là bịa ra một chuỗi.
		player.Name = ""
	}
	return player, nil
}
