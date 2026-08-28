package football

import "time"

type MatchStatus string

const (
	StatusScheduled MatchStatus = "scheduled"
	StatusLive      MatchStatus = "live"
	StatusFinished  MatchStatus = "finished"
)

func StatusFromWire(v string) MatchStatus {
	switch MatchStatus(v) {
	case StatusLive:
		return StatusLive
	case StatusFinished:
		return StatusFinished
	default:
		return StatusScheduled
	}
}

type League struct {
	ID      string `json:"id"`
	Name    string `json:"name"`
	Country string `json:"country"`
	Icon    string `json:"icon"`
	Flag    string `json:"flag"`
	Status  string `json:"status"`
}

type Team struct {
	ID        string `json:"id"`
	Name      string `json:"name"`
	ShortName string `json:"shortName"`
	Emblem    string `json:"emblem"`
	Country   string `json:"country,omitempty"`
	Founded   *int   `json:"founded,omitempty"`
	LeagueID  string `json:"leagueId,omitempty"`
}

func teamOf(id, name, shortName, emblem string) Team {
	return Team{ID: id, Name: name, ShortName: shortName, Emblem: emblem}
}

func (t Team) withLeague(country, leagueID string) Team {
	t.Country = country
	t.LeagueID = leagueID
	return t
}

type Match struct {
	ID              string      `json:"id"`
	Competition     string      `json:"competition"`
	CompetitionID   string      `json:"competitionId"`
	CompetitionLogo string      `json:"competitionLogo"`
	Round           string      `json:"round"`
	Status          MatchStatus `json:"status"`
	Elapsed         *int        `json:"elapsed"`
	Kickoff         *time.Time  `json:"kickoff"`
	HomeTeam        Team        `json:"homeTeam"`
	AwayTeam        Team        `json:"awayTeam"`
	HomeScore       *int        `json:"homeScore"`
	AwayScore       *int        `json:"awayScore"`
}

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

type FixtureQuery struct {
	Date   string
	League string
	Season string
	Team   string
	Live   bool
}

func FixtureByDate(date, league, season string) FixtureQuery {
	return FixtureQuery{Date: date, League: league, Season: season}
}

func FixtureByTeam(team, season, league string) FixtureQuery {
	return FixtureQuery{Team: team, Season: season, League: league}
}

func FixtureByLeague(league, season string) FixtureQuery {
	return FixtureQuery{League: league, Season: season}
}
