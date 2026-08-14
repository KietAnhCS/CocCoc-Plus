package apifootball

import (
	"context"
	"encoding/json"
	"fmt"
	"net/url"
	"strconv"

	"github.com/KietAnhCS/coccoc-browser-like/football-service/internal/football"
)

// fixtureValues dịch bộ lọc chung sang tham số của API-Football.
func fixtureValues(q football.FixtureQuery) url.Values {
	params := url.Values{}
	setIf(params, "date", q.Date)
	setIf(params, "league", q.League)
	setIf(params, "season", q.Season)
	setIf(params, "team", q.Team)
	if q.Live {
		// `live=all` là cú pháp riêng của API-Football cho "mọi trận đang đá".
		params.Set("live", "all")
	}
	return params
}

// Leagues trả về danh sách giải đấu.
func (c *Client) Leagues(ctx context.Context, country, search string) ([]football.League, error) {
	params := url.Values{}
	setIf(params, "country", country)
	setIf(params, "search", search)

	raw, err := c.get(ctx, "/leagues", params)
	if err != nil {
		return nil, err
	}

	var envelopes []leagueEnvelope
	if err := json.Unmarshal(raw, &envelopes); err != nil {
		return nil, fmt.Errorf("đọc danh sách giải: %w", err)
	}

	leagues := make([]football.League, 0, len(envelopes))
	for _, item := range envelopes {
		leagues = append(leagues, item.normalize())
	}
	return leagues, nil
}

// Fixtures trả về danh sách trận đấu theo bộ lọc.
func (c *Client) Fixtures(ctx context.Context, query football.FixtureQuery) ([]football.Match, error) {
	raw, err := c.get(ctx, "/fixtures", fixtureValues(query))
	if err != nil {
		return nil, err
	}

	var envelopes []fixtureEnvelope
	if err := json.Unmarshal(raw, &envelopes); err != nil {
		return nil, fmt.Errorf("đọc danh sách trận: %w", err)
	}

	matches := make([]football.Match, 0, len(envelopes))
	for _, item := range envelopes {
		matches = append(matches, item.normalize())
	}
	return matches, nil
}

// Teams tìm đội theo tên hoặc theo giải.
func (c *Client) Teams(ctx context.Context, search, league, season string) ([]football.Team, error) {
	params := url.Values{}
	setIf(params, "search", search)
	setIf(params, "league", league)
	setIf(params, "season", season)

	raw, err := c.get(ctx, "/teams", params)
	if err != nil {
		return nil, err
	}

	var envelopes []teamEnvelope
	if err := json.Unmarshal(raw, &envelopes); err != nil {
		return nil, fmt.Errorf("đọc danh sách đội: %w", err)
	}

	teams := make([]football.Team, 0, len(envelopes))
	for _, item := range envelopes {
		teams = append(teams, item.normalize())
	}
	return teams, nil
}

// Players tìm cầu thủ theo tên trên toàn cầu.
//
// Dùng /players/profiles chứ không /players: endpoint /players BẮT BUỘC phải
// kèm team hoặc league, nên nó không dùng để tìm theo tên được — đó là lý do
// bản TypeScript cũng chọn đúng endpoint này.
func (c *Client) Players(ctx context.Context, search string) ([]football.Player, error) {
	params := url.Values{}
	setIf(params, "search", search)

	raw, err := c.get(ctx, "/players/profiles", params)
	if err != nil {
		return nil, err
	}

	var envelopes []playerEnvelope
	if err := json.Unmarshal(raw, &envelopes); err != nil {
		return nil, fmt.Errorf("đọc danh sách cầu thủ: %w", err)
	}

	players := make([]football.Player, 0, len(envelopes))
	for _, item := range envelopes {
		players = append(players, item.normalize())
	}
	return players, nil
}

// Player lấy hồ sơ đầy đủ kèm thống kê một mùa của một cầu thủ.
func (c *Client) Player(ctx context.Context, playerID, season string) (football.Player, error) {
	params := url.Values{}
	setIf(params, "id", playerID)
	setIf(params, "season", season)

	raw, err := c.get(ctx, "/players", params)
	if err != nil {
		return football.Player{}, err
	}

	var envelopes []playerEnvelope
	if err := json.Unmarshal(raw, &envelopes); err != nil {
		return football.Player{}, fmt.Errorf("đọc hồ sơ cầu thủ: %w", err)
	}
	if len(envelopes) == 0 {
		return football.Player{}, fmt.Errorf("không tìm thấy cầu thủ %s ở mùa %s", playerID, season)
	}

	return envelopes[0].normalize(), nil
}

func setIf(params url.Values, key, value string) {
	if value != "" {
		params.Set(key, value)
	}
}

func itoa(value int) string { return strconv.Itoa(value) }
