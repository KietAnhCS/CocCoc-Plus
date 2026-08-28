package football

import (
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

type httpClient struct {
	base string
	c    *http.Client
}

func newHTTPClient(baseURL string, timeout time.Duration) *httpClient {
	return &httpClient{
		base: trimTrailingSlash(baseURL),
		c:    &http.Client{Timeout: timeout},
	}
}

func (h *httpClient) get(path string, params map[string]string, headers map[string]string) (string, error) {
	u := h.base + path
	if q := encodeParams(params); q != "" {
		u += "?" + q
	}
	req, err := http.NewRequest(http.MethodGet, u, nil)
	if err != nil {
		return "", err
	}
	req.Header.Set("accept", "application/json")
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	resp, err := h.c.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return "", err
	}
	return string(body), nil
}

func trimTrailingSlash(v string) string {
	return strings.TrimRight(strings.TrimSpace(v), "/")
}

func setIfPresent(params map[string]string, key, value string) {
	if v := strings.TrimSpace(value); v != "" {
		params[key] = v
	}
}

func encodeParams(params map[string]string) string {
	if len(params) == 0 {
		return ""
	}
	values := url.Values{}
	for k, v := range params {
		values.Set(k, v)
	}
	return values.Encode()
}
