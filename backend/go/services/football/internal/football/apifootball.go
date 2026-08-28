package football

import (
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/tidwall/gjson"
)

var apiFinishedCodes = map[string]bool{"FT": true, "AET": true, "PEN": true, "WO": true, "AWD": true}
var apiLiveCodes = map[string]bool{
	"1H": true, "HT": true, "2H": true, "ET": true, "BT": true,
	"P": true, "SUSP": true, "INT": true, "LIVE": true,
}

type apiFootballClient struct {
	apiKey    string
	rapidHost string
	http      *httpClient
	recorder  CallRecorder
}

func newAPIFootballClient(baseURL, apiKey string, timeout time.Duration, rec CallRecorder) *apiFootballClient {
	return &apiFootballClient{
		apiKey:    strings.TrimSpace(apiKey),
		rapidHost: rapidAPIHost(baseURL),
		http:      newHTTPClient(baseURL, timeout),
		recorder:  rec,
	}
}

func rapidAPIHost(baseURL string) string {
	u, err := url.Parse(trimTrailingSlash(baseURL))
	if err != nil {
		return ""
	}
	if strings.HasSuffix(u.Host, ".rapidapi.com") {
		return u.Host
	}
	return ""
}

func (c *apiFootballClient) Name() string { return "API-Football" }

func (c *apiFootballClient) Leagues(country, search string) ([]League, error) {
	params := map[string]string{}
	setIfPresent(params, "country", country)
	setIfPresent(params, "search", search)
	resp, err := c.get("/leagues", params)
	if err != nil {
		return nil, err
	}
	var out []League
	for _, n := range resp.Array() {
		out = append(out, apiToLeague(n))
	}
	return out, nil
}

func (c *apiFootballClient) Fixtures(q FixtureQuery) ([]Match, error) {
	params := map[string]string{}
	setIfPresent(params, "date", q.Date)
	setIfPresent(params, "league", q.League)
	setIfPresent(params, "season", q.Season)
	setIfPresent(params, "team", q.Team)
	if q.Live {
		params["live"] = "all"
	}
	resp, err := c.get("/fixtures", params)
	if err != nil {
		return nil, err
	}
	var out []Match
	for _, n := range resp.Array() {
		out = append(out, apiToMatch(n))
	}
	return out, nil
}

func (c *apiFootballClient) Teams(search, league, season string) ([]Team, error) {
	params := map[string]string{}
	setIfPresent(params, "search", search)
	setIfPresent(params, "league", league)
	setIfPresent(params, "season", season)
	resp, err := c.get("/teams", params)
	if err != nil {
		return nil, err
	}
	var out []Team
	for _, n := range resp.Array() {
		out = append(out, apiToTeam(n.Get("team")))
	}
	return out, nil
}

func (c *apiFootballClient) Players(search string) ([]Player, error) {
	params := map[string]string{}
	setIfPresent(params, "search", search)
	resp, err := c.get("/players/profiles", params)
	if err != nil {
		return nil, err
	}
	var out []Player
	for _, n := range resp.Array() {
		out = append(out, apiToPlayer(n))
	}
	return out, nil
}

func (c *apiFootballClient) Player(playerID, season string) (*Player, error) {
	params := map[string]string{}
	setIfPresent(params, "id", playerID)
	setIfPresent(params, "season", season)
	resp, err := c.get("/players", params)
	if err != nil {
		return nil, err
	}
	arr := resp.Array()
	if len(arr) == 0 {
		return nil, nil
	}
	p := apiToPlayer(arr[0])
	return &p, nil
}

func (c *apiFootballClient) get(path string, params map[string]string) (gjson.Result, error) {
	if c.apiKey == "" {
		return gjson.Result{}, providerErr("chưa cấu hình FOOTBALL_API_KEY")
	}
	headers := map[string]string{}
	if c.rapidHost == "" {
		headers["x-apisports-key"] = c.apiKey
	} else {
		headers["x-rapidapi-key"] = c.apiKey
		headers["x-rapidapi-host"] = c.rapidHost
	}

	body, err := c.http.get(path, params, headers)
	c.recorder.RecordCall(path, encodeParams(params))
	if err != nil {
		return gjson.Result{}, providerErrWrap("gọi "+path+" hỏng: "+err.Error(), err)
	}
	if strings.TrimSpace(body) == "" {
		body = "{}"
	}
	if !gjson.Valid(body) {
		return gjson.Result{}, providerErr("giải mã phản hồi " + path + " hỏng")
	}
	env := gjson.Parse(body)
	if msg := apiErrorMessage(env.Get("errors")); msg != "" {
		return gjson.Result{}, providerErr("API-Football báo lỗi ở " + path + ": " + msg)
	}
	return env.Get("response"), nil
}

func apiErrorMessage(errors gjson.Result) string {
	if !errors.Exists() || errors.Type == gjson.Null {
		return ""
	}
	if errors.IsArray() && len(errors.Array()) == 0 {
		return ""
	}
	if errors.IsObject() {
		parts := make([]string, 0)
		errors.ForEach(func(k, v gjson.Result) bool {
			parts = append(parts, k.String()+": "+v.String())
			return true
		})
		if len(parts) == 0 {
			return ""
		}
		return strings.Join(parts, "; ")
	}
	if errors.IsArray() {
		return errors.Raw
	}
	return ""
}

func apiToLeague(n gjson.Result) League {
	league := n.Get("league")
	country := n.Get("country")
	status := "Offseason"
	for _, s := range n.Get("seasons").Array() {
		if s.Get("current").Bool() {
			status = "Active"
			break
		}
	}
	return League{
		ID:      league.Get("id").String(),
		Name:    league.Get("name").String(),
		Country: country.Get("name").String(),
		Icon:    league.Get("logo").String(),
		Flag:    country.Get("flag").String(),
		Status:  status,
	}
}

func apiToMatch(n gjson.Result) Match {
	fixture := n.Get("fixture")
	league := n.Get("league")
	goals := n.Get("goals")

	status := apiToStatus(fixture.Get("status.short").String())
	var elapsed *int
	if status == StatusLive {
		elapsed = intOrNil(fixture.Get("status.elapsed"))
	}
	return Match{
		ID:              fixture.Get("id").String(),
		Competition:     league.Get("name").String(),
		CompetitionID:   league.Get("id").String(),
		CompetitionLogo: league.Get("logo").String(),
		Round:           league.Get("round").String(),
		Status:          status,
		Elapsed:         elapsed,
		Kickoff:         timeOrNil(fixture.Get("date").String()),
		HomeTeam:        apiToFixtureTeam(n.Get("teams.home")),
		AwayTeam:        apiToFixtureTeam(n.Get("teams.away")),
		HomeScore:       intOrNil(goals.Get("home")),
		AwayScore:       intOrNil(goals.Get("away")),
	}
}

func apiToStatus(shortCode string) MatchStatus {
	code := strings.ToUpper(shortCode)
	if apiFinishedCodes[code] {
		return StatusFinished
	}
	if apiLiveCodes[code] {
		return StatusLive
	}
	return StatusScheduled
}

func apiToFixtureTeam(n gjson.Result) Team {
	name := n.Get("name").String()
	return teamOf(n.Get("id").String(), name, name, n.Get("logo").String())
}

func apiToTeam(team gjson.Result) Team {
	name := team.Get("name").String()
	code := team.Get("code").String()
	short := code
	if strings.TrimSpace(code) == "" {
		short = name
	}
	return Team{
		ID:        team.Get("id").String(),
		Name:      name,
		ShortName: short,
		Emblem:    team.Get("logo").String(),
		Country:   team.Get("country").String(),
		Founded:   intOrNil(team.Get("founded")),
	}
}

func apiToPlayer(n gjson.Result) Player {
	player := n.Get("player")
	var stats []PlayerStat
	for _, entry := range n.Get("statistics").Array() {
		team := entry.Get("team")
		league := entry.Get("league")
		games := entry.Get("games")
		goals := entry.Get("goals")
		cards := entry.Get("cards")
		stats = append(stats, PlayerStat{
			TeamID:        team.Get("id").String(),
			TeamName:      team.Get("name").String(),
			TeamLogo:      team.Get("logo").String(),
			LeagueID:      league.Get("id").String(),
			LeagueName:    league.Get("name").String(),
			LeagueCountry: league.Get("country").String(),
			Season:        intOrNil(league.Get("season")),
			Position:      games.Get("position").String(),
			Appearances:   int(games.Get("appearences").Int()),
			MinutesPlayed: int(games.Get("minutes").Int()),
			Rating:        parseRating(games.Get("rating").String()),
			Goals:         int(goals.Get("total").Int()),
			Assists:       int(goals.Get("assists").Int()),
			YellowCards:   int(cards.Get("yellow").Int()),
			RedCards:      int(cards.Get("red").Int()),
		})
	}
	return Player{
		ID:          player.Get("id").String(),
		Name:        player.Get("name").String(),
		FirstName:   player.Get("firstname").String(),
		LastName:    player.Get("lastname").String(),
		Age:         intOrNil(player.Get("age")),
		Nationality: player.Get("nationality").String(),
		Height:      player.Get("height").String(),
		Weight:      player.Get("weight").String(),
		Photo:       player.Get("photo").String(),
		Injured:     player.Get("injured").Bool(),
		Statistics:  stats,
	}
}

func parseRating(raw string) *float64 {
	if strings.TrimSpace(raw) == "" {
		return nil
	}
	f, err := strconv.ParseFloat(raw, 64)
	if err != nil {
		return nil
	}
	return &f
}

func intOrNil(n gjson.Result) *int {
	if !n.Exists() || n.Type == gjson.Null {
		return nil
	}
	v := int(n.Int())
	return &v
}

func timeOrNil(raw string) *time.Time {
	if strings.TrimSpace(raw) == "" {
		return nil
	}
	t, err := time.Parse(time.RFC3339, raw)
	if err != nil {
		return nil
	}
	t = t.UTC()
	return &t
}
