package auth

import (
	"context"
	"net/http"
	"strings"

	"github.com/MicahParks/keyfunc/v3"
	"github.com/golang-jwt/jwt/v5"

	"github.com/vnsearch/backend-go/platform/httpx"
)

type Config struct {
	JWKSURL  string
	Issuer   string
	Audience string
}

type Verifier struct {
	cfg     Config
	keyfunc jwt.Keyfunc
}

func NewVerifier(ctx context.Context, cfg Config) (*Verifier, error) {
	k, err := keyfunc.NewDefaultCtx(ctx, []string{cfg.JWKSURL})
	if err != nil {
		return nil, err
	}
	return &Verifier{cfg: cfg, keyfunc: k.Keyfunc}, nil
}

type Identity struct {
	Username string
	Roles    []string
	TokenID  string
}

func (id Identity) IsAdmin() bool {
	for _, r := range id.Roles {
		if r == "ADMIN" {
			return true
		}
	}
	return false
}

type ctxKey struct{}

func (v *Verifier) parse(raw string) (Identity, error) {
	opts := []jwt.ParserOption{jwt.WithValidMethods([]string{"RS256"})}
	if v.cfg.Issuer != "" {
		opts = append(opts, jwt.WithIssuer(v.cfg.Issuer))
	}
	if v.cfg.Audience != "" {
		opts = append(opts, jwt.WithAudience(v.cfg.Audience))
	}

	tok, err := jwt.Parse(raw, v.keyfunc, opts...)
	if err != nil || !tok.Valid {
		if err == nil {
			err = jwt.ErrTokenNotValidYet
		}
		return Identity{}, err
	}
	claims, ok := tok.Claims.(jwt.MapClaims)
	if !ok {
		return Identity{}, jwt.ErrTokenInvalidClaims
	}
	sub, _ := claims["sub"].(string)
	jti, _ := claims["jti"].(string)
	return Identity{Username: sub, Roles: rolesOf(claims), TokenID: jti}, nil
}

func rolesOf(claims jwt.MapClaims) []string {
	raw, ok := claims["roles"].([]any)
	if !ok {
		return nil
	}
	out := make([]string, 0, len(raw))
	for _, v := range raw {
		if s, ok := v.(string); ok && strings.TrimSpace(s) != "" {
			out = append(out, strings.TrimSpace(s))
		}
	}
	return out
}

func (v *Verifier) RequireAuth(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		raw := bearerToken(r)
		if raw == "" {
			httpx.Error(w, http.StatusUnauthorized, "Thiếu token.")
			return
		}
		id, err := v.parse(raw)
		if err != nil || strings.TrimSpace(id.Username) == "" {
			httpx.Error(w, http.StatusUnauthorized, "Token không hợp lệ.")
			return
		}
		next.ServeHTTP(w, r.WithContext(ContextWithIdentity(r.Context(), id)))
	})
}

func bearerToken(r *http.Request) string {
	h := r.Header.Get("Authorization")
	if len(h) > 7 && strings.EqualFold(h[:7], "bearer ") {
		return strings.TrimSpace(h[7:])
	}
	return ""
}

func ContextWithIdentity(ctx context.Context, id Identity) context.Context {
	return context.WithValue(ctx, ctxKey{}, id)
}

func Current(ctx context.Context) (Identity, bool) {
	id, ok := ctx.Value(ctxKey{}).(Identity)
	return id, ok && strings.TrimSpace(id.Username) != ""
}

func Username(ctx context.Context) string {
	id, _ := ctx.Value(ctxKey{}).(Identity)
	return id.Username
}
