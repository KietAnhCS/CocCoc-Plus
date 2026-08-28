package auth

import (
	"context"
	"net/http/httptest"
	"testing"

	"github.com/golang-jwt/jwt/v5"
)

func TestRolesOfAndIsAdmin(t *testing.T) {
	claims := jwt.MapClaims{"roles": []any{"USER", "ADMIN", "", "  MOD  "}}
	roles := rolesOf(claims)
	if len(roles) != 3 || roles[0] != "USER" || roles[1] != "ADMIN" || roles[2] != "MOD" {
		t.Fatalf("roles = %v", roles)
	}
	if !(Identity{Roles: roles}).IsAdmin() {
		t.Fatal("IsAdmin() = false, want true")
	}
	if (Identity{Roles: []string{"USER"}}).IsAdmin() {
		t.Fatal("IsAdmin() = true for non-admin")
	}
}

func TestRolesOfMissingClaim(t *testing.T) {
	if rolesOf(jwt.MapClaims{}) != nil {
		t.Fatal("expected nil roles for missing claim")
	}
}

func TestBearerToken(t *testing.T) {
	r := httptest.NewRequest("GET", "/", nil)
	r.Header.Set("Authorization", "Bearer abc.def.ghi")
	if got := bearerToken(r); got != "abc.def.ghi" {
		t.Fatalf("bearerToken = %q", got)
	}
	r.Header.Set("Authorization", "Basic xxx")
	if got := bearerToken(r); got != "" {
		t.Fatalf("bearerToken = %q, want empty for non-bearer", got)
	}
}

func TestContextIdentityRoundTrip(t *testing.T) {
	ctx := ContextWithIdentity(context.Background(), Identity{Username: "kiet", TokenID: "jti-1"})
	id, ok := Current(ctx)
	if !ok || id.Username != "kiet" || id.TokenID != "jti-1" {
		t.Fatalf("Current = %+v, %v", id, ok)
	}
	if Username(ctx) != "kiet" {
		t.Fatalf("Username = %q", Username(ctx))
	}
	if _, ok := Current(context.Background()); ok {
		t.Fatal("Current on empty context should be false")
	}
}
