package apifootball

import (
	"encoding/json"
	"testing"

	"github.com/KietAnhCS/coccoc-browser-like/football-service/internal/football"
)

// Bộ chuẩn hoá là chỗ ĐÁNG kiểm nhất trong service này: nó nhận đầu vào từ một
// hệ thống ta không kiểm soát, và mọi sai lệch ở đây đều hiện ra thành một
// bảng tỉ số trông vẫn bình thường nhưng nói sai.

func TestNormalizeStatus(t *testing.T) {
	cases := map[string]football.Status{
		"FT":   football.StatusFinished,
		"AET":  football.StatusFinished,
		"PEN":  football.StatusFinished,
		"WO":   football.StatusFinished,
		"1H":   football.StatusLive,
		"HT":   football.StatusLive,
		"2H":   football.StatusLive,
		"LIVE": football.StatusLive,
		"NS":   football.StatusScheduled,
		"TBD":  football.StatusScheduled,
		"PST":  football.StatusScheduled,
		// Mã lạ phải rơi về `scheduled`, không phải `live`: đoán nhầm thành
		// đang đá sẽ đẩy trận lên đầu bảng kèm tỉ số 0-0 giả.
		"XYZ": football.StatusScheduled,
		"":    football.StatusScheduled,
	}

	for short, want := range cases {
		if got := normalizeStatus(short); got != want {
			t.Errorf("normalizeStatus(%q) = %q, mong đợi %q", short, got, want)
		}
	}
}

func TestFixtureNormalize(t *testing.T) {
	raw := `{
		"fixture": {"id": 867946, "date": "2023-08-11T19:00:00+00:00",
		            "status": {"short": "FT", "elapsed": 90}},
		"league":  {"id": 39, "name": "Premier League", "round": "Regular Season - 1",
		            "logo": "https://media.api-sports.io/football/leagues/39.png"},
		"teams":   {"home": {"id": 40, "name": "Liverpool", "logo": "h.png"},
		            "away": {"id": 42, "name": "Arsenal",   "logo": "a.png"}},
		"goals":   {"home": 2, "away": 1}
	}`

	var envelope fixtureEnvelope
	if err := json.Unmarshal([]byte(raw), &envelope); err != nil {
		t.Fatalf("giải mã hỏng: %v", err)
	}
	match := envelope.normalize()

	if match.ID != "867946" {
		t.Errorf("ID = %q, mong đợi \"867946\"", match.ID)
	}
	if match.Status != football.StatusFinished {
		t.Errorf("Status = %q, mong đợi finished", match.Status)
	}
	if match.HomeTeam.Name != "Liverpool" || match.AwayTeam.Name != "Arsenal" {
		t.Errorf("tên đội sai: %q vs %q", match.HomeTeam.Name, match.AwayTeam.Name)
	}
	if match.HomeScore == nil || *match.HomeScore != 2 {
		t.Errorf("HomeScore = %v, mong đợi 2", match.HomeScore)
	}
	if match.Kickoff.IsZero() {
		t.Error("Kickoff không được rỗng khi ngày hợp lệ")
	}

	// Điểm mấu chốt: trận đã kết thúc thì KHÔNG mang số phút, dù nhà cung cấp
	// vẫn gửi elapsed=90. Hiện "90'" cạnh một tỉ số chung cuộc khiến người
	// xem tưởng trận còn đang đá.
	if match.Elapsed != nil {
		t.Errorf("Elapsed = %v, trận đã kết thúc thì phải là nil", *match.Elapsed)
	}
}

func TestFixtureNormalizeKeepsElapsedWhileLive(t *testing.T) {
	raw := `{
		"fixture": {"id": 1, "date": "2023-08-11T19:00:00+00:00",
		            "status": {"short": "2H", "elapsed": 67}},
		"league":  {"id": 39, "name": "Premier League"},
		"teams":   {"home": {"id": 1, "name": "A"}, "away": {"id": 2, "name": "B"}},
		"goals":   {"home": 1, "away": 1}
	}`

	var envelope fixtureEnvelope
	if err := json.Unmarshal([]byte(raw), &envelope); err != nil {
		t.Fatalf("giải mã hỏng: %v", err)
	}
	match := envelope.normalize()

	if match.Elapsed == nil || *match.Elapsed != 67 {
		t.Errorf("Elapsed = %v, mong đợi 67", match.Elapsed)
	}
}

func TestFixtureNormalizeSurvivesBadDate(t *testing.T) {
	// Ngày hỏng thì vẫn phải giữ lại trận: một trận thiếu giờ vẫn cho biết tỉ
	// số, còn bỏ nó đi thì danh sách ngắn đi mà không ai hiểu vì sao.
	raw := `{
		"fixture": {"id": 7, "date": "khong-phai-ngay", "status": {"short": "NS"}},
		"league":  {"id": 39, "name": "Premier League"},
		"teams":   {"home": {"id": 1, "name": "A"}, "away": {"id": 2, "name": "B"}},
		"goals":   {"home": null, "away": null}
	}`

	var envelope fixtureEnvelope
	if err := json.Unmarshal([]byte(raw), &envelope); err != nil {
		t.Fatalf("giải mã hỏng: %v", err)
	}
	match := envelope.normalize()

	if match.ID != "7" {
		t.Errorf("ID = %q, mong đợi \"7\"", match.ID)
	}
	if !match.Kickoff.IsZero() {
		t.Error("Kickoff phải là zero value khi ngày không đọc được")
	}
	if match.HomeScore != nil {
		t.Error("trận chưa đá thì HomeScore phải là nil, không phải 0")
	}
}

func TestPlayerNormalizeParsesRating(t *testing.T) {
	// API-Football trả điểm phong độ dưới dạng CHUỖI, và để rỗng khi chưa
	// chấm. Hai ca đó phải ra hai kết quả khác nhau.
	raw := `{
		"player": {"id": 154, "name": "Lionel Messi", "firstname": "Lionel",
		           "lastname": "Messi", "age": 36, "nationality": "Argentina"},
		"statistics": [
			{"team": {"id": 1, "name": "Inter Miami"}, "league": {"id": 253, "name": "MLS", "season": 2023},
			 "games": {"appearences": 14, "minutes": 1150, "position": "Attacker", "rating": "8.283333"},
			 "goals": {"total": 11, "assists": 5}, "cards": {"yellow": 1, "red": null}},
			{"team": {"id": 2, "name": "Khac"}, "league": {"id": 1, "name": "X"},
			 "games": {"rating": ""}, "goals": {}, "cards": {}}
		]
	}`

	var envelope playerEnvelope
	if err := json.Unmarshal([]byte(raw), &envelope); err != nil {
		t.Fatalf("giải mã hỏng: %v", err)
	}
	player := envelope.normalize()

	if len(player.Statistics) != 2 {
		t.Fatalf("số dòng thống kê = %d, mong đợi 2", len(player.Statistics))
	}
	if player.Statistics[0].Rating == nil || *player.Statistics[0].Rating < 8.28 {
		t.Errorf("Rating = %v, mong đợi ~8.283333", player.Statistics[0].Rating)
	}
	if player.Statistics[1].Rating != nil {
		t.Error("chuỗi rỗng phải cho Rating nil, không phải 0.0")
	}
	// `appearences` viết sai chính tả ở phía nhà cung cấp — nếu ta gõ đúng
	// chính tả trong struct tag thì trường này im lặng về 0.
	if player.Statistics[0].Appearances != 14 {
		t.Errorf("Appearances = %d, mong đợi 14", player.Statistics[0].Appearances)
	}
	if player.Statistics[0].RedCards != 0 {
		t.Errorf("RedCards = %d, null phải quy về 0", player.Statistics[0].RedCards)
	}
}

func TestTeamNormalizeFallsBackToNameWhenCodeMissing(t *testing.T) {
	raw := `{"team": {"id": 33, "name": "Manchester United", "code": "", "country": "England"}}`

	var envelope teamEnvelope
	if err := json.Unmarshal([]byte(raw), &envelope); err != nil {
		t.Fatalf("giải mã hỏng: %v", err)
	}
	team := envelope.normalize()

	if team.ShortName != "Manchester United" {
		t.Errorf("ShortName = %q, thiếu code thì phải lùi về name", team.ShortName)
	}
}

func TestErrorMessage(t *testing.T) {
	// Mấu chốt: API-Football trả HTTP 200 kèm lỗi trong thân phản hồi. Không
	// đọc trường này thì mọi hỏng hóc hiện ra thành "hôm nay không có trận
	// nào" — một câu trả lời hợp lệ, chỉ có điều sai.
	if got := errorMessage(json.RawMessage(`[]`)); got != "" {
		t.Errorf("mảng rỗng nghĩa là không lỗi, nhận được %q", got)
	}
	if got := errorMessage(json.RawMessage(`{}`)); got != "" {
		t.Errorf("đối tượng rỗng nghĩa là không lỗi, nhận được %q", got)
	}
	if got := errorMessage(json.RawMessage(`{"token":"khoa khong hop le"}`)); got == "" {
		t.Error("có lỗi thì phải trả về câu mô tả")
	}
}
