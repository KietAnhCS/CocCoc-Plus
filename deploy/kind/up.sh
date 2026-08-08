#!/usr/bin/env bash
# Dựng cụm kind + cài ingress + build ảnh + deploy overlay dev.
#   bash deploy/kind/up.sh
set -euo pipefail

CLUSTER=vnsearch
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

if ! kind get clusters 2>/dev/null | grep -qx "$CLUSTER"; then
  kind create cluster --config "$ROOT/deploy/kind/cluster.yaml"
fi

kubectl config use-context "kind-$CLUSTER"

kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/main/deploy/static/provider/kind/deploy.yaml
kubectl -n ingress-nginx wait --for=condition=ready pod \
  --selector=app.kubernetes.io/component=controller --timeout=180s

docker build -t vnsearch-backend:dev "$ROOT/search-engine"
kind load docker-image vnsearch-backend:dev --name "$CLUSTER"

kubectl apply -k "$ROOT/deploy/k8s/overlays/dev"
kubectl -n vnsearch rollout status statefulset/vnsearch-postgres --timeout=300s
kubectl -n vnsearch rollout status deployment/vnsearch-backend --timeout=300s

echo
echo "Xong. Them vao hosts:  127.0.0.1 vnsearch.local"
echo "  curl http://vnsearch.local/api/health"
echo "  curl 'http://vnsearch.local/api/search?q=may+tinh&size=3'"
echo "Xoa cum:  kind delete cluster --name $CLUSTER"
