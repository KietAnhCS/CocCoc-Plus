package livefootball

import (
	"context"
	"net/url"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/KietAnhCS/coccoc-browser-like/football-service/internal/football"
)

// fullMatch là 90 phút thi đấu. Dùng để chặn đồng hồ trận đấu.
const fullMatch = 90

func sortLeagues(leagues []football.League, popular map[string]bool) {
	sort.Slice(leagues, func(i, j int) bool {
		left, right := popular[leagues[i].ID], popular[leagues[j].ID]
		if left != right {
			return left
		}
		return leagues[i].Name < leagues[j].Name
	})
}

// side là một bên của trận đấu. Mã đội có lúc là số, có lúc là chuỗi — hai
// endpoint khác nhau trả hai kiểu khác nhau cho cùng một thứ, nên phải nhận cả
// hai (xem flexID).
type side struct {
	ID       flexID `json:"id"`
	Name     string `json:"name"`
	LongName string `json:"longName"`
	Score    *int   `json:"score"`
}

func (s side) team() football.Team {
	id := s.ID.String()
	name := s.LongName
	if name == "" {
		name = s.Name
	}
	short := s.Name
	if short == "" {
		short = name
	}
	return football.Team{ID: id, Name: name, ShortName: short, Emblem: teamLogo(id)}
}

type matchStatus struct {
	UTCTime   string `json:"utcTime"`
	Finished  bool   `json:"finished"`
	Started   bool   `json:"started"`
	Cancelled bool   `json:"cancelled"`
	ScoreStr  string `json:"scoreStr"`
	Reason    struct {
		Short string `json:"short"`
		Long  string `json:"long"`
	} `json:"reason"`
}

type matchEnvelope struct {
	ID              flexID      `json:"id"`
	LeagueID        flexID      `json:"leagueId"`
	TournamentStage string      `json:"tournamentStage"`
	Home            side        `json:"home"`
	Away            side        `json:"away"`
	Status          matchStatus `json:"status"`
	Tournament      struct {
		Stage string `json:"stage"`
	} `json:"tournament"`
}

// normalize dựng một trận đã chuẩn hoá. `now` để suy ra số phút đã đá.
func (e matchEnvelope) normalize(league football.League, now time.Time) football.Match {
	kickoff, err := time.Parse(time.RFC3339, e.Status.UTCTime)
	if err != nil {
		// Giờ hỏng thì để zero value chứ không bỏ cả trận: một trận thiếu giờ
		// vẫn nói được ai đá với ai và tỉ số bao nhiêu.
		kickoff = time.Time{}
	}

	round := e.TournamentStage
	if round == "" {
		round = e.Tournament.Stage
	}
	if round != "" {
		if _, err := strconv.Atoi(round); err == nil {
			round = "Vòng " + round
		}
	}

	match := football.Match{
		ID:              e.ID.String(),
		Competition:     league.Name,
		CompetitionID:   league.ID,
		CompetitionLogo: league.Icon,
		Round:           round,
		Kickoff:         kickoff,
		HomeTeam:        e.Home.team(),
		AwayTeam:        e.Away.team(),
		HomeScore:       e.Home.Score,
		AwayScore:       e.Away.Score,
	}

	switch {
	case e.Status.Finished:
		match.Status = football.StatusFinished

	case e.Status.Started && !e.Status.Cancelled:
		match.Status = football.StatusLive

		// Nhà cung cấp KHÔNG trả về số phút đã đá, chỉ trả giờ bóng lăn. Suy ra
		// từ hiệu số là xấp xỉ — nó không trừ giờ nghỉ giữa hiệp — nhưng chặn ở
		// 90 thì sai số lớn nhất còn đúng bằng 15 phút nghỉ, và một con số xấp
		// xỉ vẫn hơn hẳn một ô trống ở chỗ mà người xem chờ thấy phút thi đấu.
		if !kickoff.IsZero() {
			elapsed := int(now.Sub(kickoff).Minutes())
			if elapsed < 0 {
				elapsed = 0
			}
			if elapsed > fullMatch {
				elapsed = fullMatch
			}
			match.Elapsed = &elapsed
		}

	default:
		match.Status = football.StatusScheduled
		// Trận chưa đá thì tỉ số phải là "chưa có", không phải 0-0. Nhà cung
		// cấp trả về 0 cho cả hai bên, và để nguyên thì mọi trận buổi tối đều
		// hiện 0-0 như thể vừa đá xong hiệp một.
		match.HomeScore, match.AwayScore = nil, nil
	}

	return match
}

// Fixtures trả về danh sách trận theo bộ lọc.
//
// Nhà cung cấp có hai endpoint rời: theo NGÀY và theo GIẢI. Hàm này chọn giúp,
// và tự lọc thêm phần mà endpoint tương ứng không lọc được — nhờ vậy tầng
// service chỉ cần biết một câu hỏi duy nhất.
func (c *Client) Fixtures(ctx context.Context, query football.FixtureQuery) ([]football.Match, error) {
	now := time.Now().UTC()

	var envelopes []matchEnvelope
	var err error

	switch {
	case query.Team != "":
		// Không có endpoint lịch của một đội. Đội nào cũng đá trong một giải,
		// nên lấy lịch giải rồi lọc — mã giải đã nằm sẵn trong kết quả tìm đội.
		envelopes, err = c.leagueMatches(ctx, query.League)
	case query.League != "":
		envelopes, err = c.leagueMatches(ctx, query.League)
	default:
		envelopes, err = c.dateMatches(ctx, query.Date)
	}
	if err != nil {
		return nil, err
	}

	matches := make([]football.Match, 0, len(envelopes))
	for _, envelope := range envelopes {
		if query.Team != "" && envelope.Home.ID.String() != query.Team && envelope.Away.ID.String() != query.Team {
			continue
		}
		if query.League != "" && envelope.LeagueID.String() != "" && envelope.LeagueID.String() != query.League {
			continue
		}

		leagueID := envelope.LeagueID.String()
		if leagueID == "" {
			leagueID = query.League
		}
		matches = append(matches, envelope.normalize(c.lookupLeague(ctx, leagueID), now))
	}

	sort.SliceStable(matches, func(i, j int) bool {
		return matches[i].Kickoff.Before(matches[j].Kickoff)
	})
	return matches, nil
}

// dateMatches lấy lịch của một ngày. Nhà cung cấp nhận ngày dạng YYYYMMDD.
func (c *Client) dateMatches(ctx context.Context, date string) ([]matchEnvelope, error) {
	compact := strings.ReplaceAll(date, "-", "")
	if compact == "" {
		compact = time.Now().UTC().Format("20060102")
	}

	params := url.Values{}
	params.Set("date", compact)

	var body struct {
		Matches []matchEnvelope `json:"matches"`
	}
	if err := c.get(ctx, "/football-get-matches-by-date", params, &body); err != nil {
		return nil, err
	}
	return body.Matches, nil
}

// leagueMatches lấy toàn bộ lịch của một giải trong mùa hiện hành.
func (c *Client) leagueMatches(ctx context.Context, leagueID string) ([]matchEnvelope, error) {
	if leagueID == "" {
		return nil, nil
	}

	params := url.Values{}
	params.Set("leagueid", leagueID)

	var body struct {
		Matches []matchEnvelope `json:"matches"`
	}
	if err := c.get(ctx, "/football-get-all-matches-by-league", params, &body); err != nil {
		return nil, err
	}
	return body.Matches, nil
}
