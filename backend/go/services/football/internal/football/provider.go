package football

import "time"

type ProviderError struct {
	msg string
	err error
}

func (e *ProviderError) Error() string { return e.msg }
func (e *ProviderError) Unwrap() error { return e.err }

func providerErr(msg string) *ProviderError              { return &ProviderError{msg: msg} }
func providerErrWrap(msg string, e error) *ProviderError { return &ProviderError{msg: msg, err: e} }

type Provider interface {
	Name() string
	Leagues(country, search string) ([]League, error)
	Fixtures(q FixtureQuery) ([]Match, error)
	Teams(search, league, season string) ([]Team, error)
	Players(search string) ([]Player, error)
	Player(playerID, season string) (*Player, error)
}

type CallRecorder interface {
	RecordCall(endpoint, params string)
}

type recorderFunc func(endpoint, params string)

func (f recorderFunc) RecordCall(endpoint, params string) { f(endpoint, params) }

type Properties struct {
	APIBaseURL     string
	APIKey         string
	DailyBudget    int
	RequestTimeout time.Duration
	LiveTTL        time.Duration
	SeasonTTL      time.Duration
	MetadataTTL    time.Duration
}
