package football

import (
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/tidwall/gjson"
)

const liveHost = "free-api-live-football-data.p.rapidapi.com"

const (
	teamLogoBase     = "https://images.fotmob.com/image_resources/logo/teamlogo/"
	leagueLogoBase   = "https://images.fotmob.com/image_resources/logo/leaguelogo/"
	playerPhotoBase  = "https://images.fotmob.com/image_resources/playerimages/"
	fullMatchMinutes = 90
)

type liveFootballClient struct {
	apiKey   string
	http     *httpClient
	recorder CallRecorder

	mu    sync.Mutex
	table *leagueTable
}

type leagueTable struct {
	byID    map[string]League
	popular map[string]bool
}

func newLiveFootballClient(baseURL, apiKey string, timeout time.Duration, rec CallRecorder) *liveFootballClient {
	return &liveFootballClient{
		apiKey:   strings.TrimSpace(apiKey),
		http:     newHTTPClient(baseURL, timeout),
		recorder: rec,
	}
}

func (c *liveFootballClient) Name() string { return "free-api-live-football-data (RapidAPI)" }

func (c *liveFootballClient) Leagues(country, search string) ([]League, error) {
	needle := strings.ToLower(strings.TrimSpace(search))
	code := strings.TrimSpace(country)
	all, err := c.allLeagues()
	if err != nil {
		return nil, err
	}
	var out []League
	for _, l := range all {
		if code != "" && !strings.EqualFold(code, l.Country) {
			continue
		}
		if needle != "" && !strings.Contains(strings.ToLower(l.Name), needle) {
			continue
		}
		out = append(out, l)
	}
	return out, nil
}

func (c *liveFootballClient) Fixtures(q FixtureQuery) ([]Match, error) {
	now := time.Now().UTC()

	var envelopes []gjson.Result
	var err error
	if strings.TrimSpace(q.Team) != "" || strings.TrimSpace(q.League) != "" {
		envelopes, err = c.leagueMatches(q.League)
	} else {
		envelopes, err = c.dateMatches(q.Date)
	}
	if err != nil {
		return nil, err
	}

	var out []Match
	for _, env := range envelopes {
		homeID := flexID(env.Get("home.id"))
		awayID := flexID(env.Get("away.id"))
		if q.Team != "" && q.Team != homeID && q.Team != awayID {
			continue
		}
		leagueID := flexID(env.Get("leagueId"))
		if q.League != "" && leagueID != "" && leagueID != q.League {
			continue
		}
		if leagueID == "" {
			leagueID = q.League
		}
		out = append(out, c.toMatch(env, c.lookupLeague(leagueID), now))
	}

	sort.SliceStable(out, func(i, j int) bool {
		return kickoffLess(out[i].Kickoff, out[j].Kickoff)
	})
	return out, nil
}

func (c *liveFootballClient) Teams(search, league, season string) ([]Team, error) {
	if strings.TrimSpace(search) == "" {
		return c.teamsOfLeague(league)
	}
	params := map[string]string{"search": strings.TrimSpace(search)}
	resp, err := c.get("/football-teams-search", params)
	if err != nil {
		return nil, err
	}
	var out []Team
	for _, item := range resp.Get("suggestions").Array() {
		t := item.Get("type").String()
		if t != "" && t != "team" {
			continue
		}
		id := flexID(item.Get("id"))
		name := item.Get("name").String()
		out = append(out, Team{
			ID:        id,
			Name:      name,
			ShortName: name,
			Emblem:    teamLogo(id),
			Country:   item.Get("leagueName").String(),
			LeagueID:  flexID(item.Get("leagueId")),
		})
	}
	return out, nil
}

func (c *liveFootballClient) Players(search string) ([]Player, error) {
	params := map[string]string{"search": strings.TrimSpace(search)}
	resp, err := c.get("/football-players-search", params)
	if err != nil {
		return nil, err
	}
	var out []Player
	for _, item := range resp.Get("suggestions").Array() {
		t := item.Get("type").String()
		if item.Get("isCoach").Bool() || (t != "" && t != "player") {
			continue
		}
		id := flexID(item.Get("id"))
		teamID := flexID(item.Get("teamId"))
		stat := PlayerStat{TeamID: teamID, TeamName: item.Get("teamName").String(), TeamLogo: teamLogo(teamID)}
		out = append(out, Player{
			ID:         id,
			Name:       item.Get("name").String(),
			Photo:      playerPhotoBase + id + ".png",
			Statistics: []PlayerStat{stat},
		})
	}
	return out, nil
}

func (c *liveFootballClient) Player(playerID, season string) (*Player, error) {
	params := map[string]string{"playerid": playerID}
	resp, err := c.get("/football-get-player-detail", params)
	if err != nil {
		return nil, err
	}
	detail := resp.Get("detail")

	var name, height, nationality string
	var age *int
	for _, entry := range detail.Array() {
		switch entry.Get("translationKey").String() {
		case "height_sentencecase":
			height = detailText(entry)
		case "country_sentencecase":
			nationality = detailText(entry)
		case "name":
			name = detailText(entry)
		case "age_sentencecase":
			num := entry.Get("value.numberValue")
			if num.Exists() && num.Type != gjson.Null {
				v := int(num.Int())
				age = &v
			}
		}
	}

	return &Player{
		ID:          playerID,
		Name:        name,
		Age:         age,
		Nationality: nationality,
		Height:      height,
		Photo:       playerPhotoBase + playerID + ".png",
		Statistics:  []PlayerStat{},
	}, nil
}

func detailText(entry gjson.Result) string {
	fallback := entry.Get("value.fallback")
	if !fallback.Exists() || fallback.Type == gjson.Null {
		return ""
	}
	return fallback.String()
}

func (c *liveFootballClient) teamsOfLeague(leagueID string) ([]Team, error) {
	league := c.lookupLeague(leagueID)
	seen := map[string]bool{}
	var out []Team

	envelopes, err := c.leagueMatches(leagueID)
	if err != nil {
		return nil, err
	}
	for _, env := range envelopes {
		for _, side := range []string{"home", "away"} {
			team := liveToTeam(env.Get(side))
			if team.ID == "" || seen[team.ID] {
				continue
			}
			seen[team.ID] = true
			out = append(out, team.withLeague(league.Name, league.ID))
		}
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].Name < out[j].Name })
	return out, nil
}

func (c *liveFootballClient) dateMatches(date string) ([]gjson.Result, error) {
	compact := strings.ReplaceAll(strings.TrimSpace(date), "-", "")
	if compact == "" {
		compact = time.Now().UTC().Format("20060102")
	}
	resp, err := c.get("/football-get-matches-by-date", map[string]string{"date": compact})
	if err != nil {
		return nil, err
	}
	return resp.Get("matches").Array(), nil
}

func (c *liveFootballClient) leagueMatches(leagueID string) ([]gjson.Result, error) {
	if strings.TrimSpace(leagueID) == "" {
		return nil, nil
	}
	resp, err := c.get("/football-get-all-matches-by-league", map[string]string{"leagueid": leagueID})
	if err != nil {
		return nil, err
	}
	return resp.Get("matches").Array(), nil
}

func (c *liveFootballClient) allLeagues() ([]League, error) {
	c.mu.Lock()
	table := c.table
	c.mu.Unlock()

	if table == nil {
		loaded, err := c.loadLeagues()
		if err != nil {
			return nil, err
		}
		c.mu.Lock()
		c.table = loaded
		table = loaded
		c.mu.Unlock()
	}

	out := make([]League, 0, len(table.byID))
	for _, l := range table.byID {
		out = append(out, l)
	}
	popular := table.popular
	sort.SliceStable(out, func(i, j int) bool {
		pi, pj := 1, 1
		if popular[out[i].ID] {
			pi = 0
		}
		if popular[out[j].ID] {
			pj = 0
		}
		if pi != pj {
			return pi < pj
		}
		return out[i].Name < out[j].Name
	})
	return out, nil
}

func (c *liveFootballClient) loadLeagues() (*leagueTable, error) {
	byID := map[string]League{}
	popular := map[string]bool{}

	popularBody, err := c.get("/football-popular-leagues", map[string]string{})
	if err != nil {
		return nil, err
	}
	collectLeagues(popularBody.Get("popular"), byID, popular, true)

	if allBody, err := c.get("/football-get-all-leagues", map[string]string{}); err == nil {
		collectLeagues(allBody.Get("leagues"), byID, popular, false)
	}

	return &leagueTable{byID: byID, popular: popular}, nil
}

func collectLeagues(items gjson.Result, byID map[string]League, popular map[string]bool, isPopular bool) {
	for _, item := range items.Array() {
		id := flexID(item.Get("id"))
		if id == "" {
			continue
		}
		name := item.Get("localizedName").String()
		if name == "" {
			name = item.Get("name").String()
		}
		icon := item.Get("logo").String()
		if icon == "" {
			icon = leagueLogo(id)
		}
		byID[id] = League{ID: id, Name: name, Country: item.Get("ccode").String(), Icon: icon, Status: "Active"}
		if isPopular {
			popular[id] = true
		}
	}
}

func (c *liveFootballClient) lookupLeague(id string) League {
	if _, err := c.allLeagues(); err != nil {
		return League{ID: id, Icon: leagueLogo(id)}
	}
	c.mu.Lock()
	table := c.table
	c.mu.Unlock()
	if table != nil {
		if l, ok := table.byID[id]; ok {
			return l
		}
	}
	return League{ID: id, Icon: leagueLogo(id)}
}

func (c *liveFootballClient) toMatch(env gjson.Result, league League, now time.Time) Match {
	status := env.Get("status")
	kickoff := timeOrNil(status.Get("utcTime").String())

	round := env.Get("tournamentStage").String()
	if round == "" {
		round = env.Get("tournament.stage").String()
	}
	if round != "" && allDigits(round) {
		round = "Vòng " + round
	}

	homeScore := intOrNil(env.Get("home.score"))
	awayScore := intOrNil(env.Get("away.score"))
	var elapsed *int
	var matchStatus MatchStatus

	switch {
	case status.Get("finished").Bool():
		matchStatus = StatusFinished
	case status.Get("started").Bool() && !status.Get("cancelled").Bool():
		matchStatus = StatusLive
		if kickoff != nil {
			minutes := int(now.Sub(*kickoff).Minutes())
			if minutes < 0 {
				minutes = 0
			}
			if minutes > fullMatchMinutes {
				minutes = fullMatchMinutes
			}
			elapsed = &minutes
		}
	default:
		matchStatus = StatusScheduled
		homeScore = nil
		awayScore = nil
	}

	return Match{
		ID:              flexID(env.Get("id")),
		Competition:     league.Name,
		CompetitionID:   league.ID,
		CompetitionLogo: league.Icon,
		Round:           round,
		Status:          matchStatus,
		Elapsed:         elapsed,
		Kickoff:         kickoff,
		HomeTeam:        liveToTeam(env.Get("home")),
		AwayTeam:        liveToTeam(env.Get("away")),
		HomeScore:       homeScore,
		AwayScore:       awayScore,
	}
}

func liveToTeam(side gjson.Result) Team {
	id := flexID(side.Get("id"))
	name := side.Get("longName").String()
	if name == "" {
		name = side.Get("name").String()
	}
	short := side.Get("name").String()
	if short == "" {
		short = name
	}
	return teamOf(id, name, short, teamLogo(id))
}

func (c *liveFootballClient) get(path string, params map[string]string) (gjson.Result, error) {
	if c.apiKey == "" {
		return gjson.Result{}, providerErr("chưa cấu hình FOOTBALL_API_KEY")
	}
	headers := map[string]string{
		"x-rapidapi-key":  c.apiKey,
		"x-rapidapi-host": liveHost,
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
		return gjson.Result{}, providerErr("đọc phản hồi " + path + " hỏng")
	}
	env := gjson.Parse(body)
	if s := env.Get("status").String(); s != "" && s != "success" {
		return gjson.Result{}, providerErr(path + " báo lỗi: " + env.Get("message").String())
	}
	return env.Get("response"), nil
}

func flexID(n gjson.Result) string {
	if !n.Exists() || n.Type == gjson.Null {
		return ""
	}
	return n.String()
}

func teamLogo(id string) string {
	if strings.TrimSpace(id) == "" {
		return ""
	}
	return teamLogoBase + id + ".png"
}

func leagueLogo(id string) string {
	if strings.TrimSpace(id) == "" {
		return ""
	}
	return leagueLogoBase + id + ".png"
}

func allDigits(s string) bool {
	if s == "" {
		return false
	}
	for _, r := range s {
		if r < '0' || r > '9' {
			return false
		}
	}
	return true
}

func kickoffLess(a, b *time.Time) bool {
	if a == nil {
		return false
	}
	if b == nil {
		return true
	}
	return a.Before(*b)
}
