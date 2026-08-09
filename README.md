# VnSearch

A Vietnamese search engine built from scratch — crawler, inverted index, ranking,
and a mini browser to query it.

Every core data structure and algorithm is **hand-written**, with no off-the-shelf
search library: inverted index, VByte compression, PageRank, Trie, Bloom filter,
MinHeap, and a Vietnamese word segmenter.

```
┌──────────────┐    ┌──────────────┐    ┌──────────────┐    ┌──────────────┐
│   Crawler    │───▶│    Index     │───▶│   Ranking    │───▶│   REST API   │
│              │    │              │    │              │    │              │
│ UrlFrontier  │    │ InvertedIndex│    │ TF-IDF/BM25  │    │ /api/search  │
│ BloomFilter  │    │ VByte + delta│    │ PageRank     │    │ /api/suggest │
│ robots.txt   │    │ VN segmenter │    │ MinHeap top-K│    │ /api/admin   │
└──────────────┘    └──────────────┘    └──────────────┘    └──────┬───────┘
                                                                    │
                                                            ┌───────▼───────┐
                                                            │  browser-app  │
                                                            │  (Electron)   │
                                                            └───────────────┘
```

---

## Quick start — Docker

Requires Docker Desktop.

```bash
# 1. Create your config file from the template
cp .env.example .env

# 2. Generate an admin key and paste it into .env
openssl rand -hex 32
#   PowerShell: -join ((1..64) | % { '{0:x}' -f (Get-Random -Max 16) })

# 3. Run
docker compose up -d --build
```

The backend serves on `http://localhost:8080`. First boot takes a few tens of
seconds to build the index — follow it with `docker compose logs -f backend`.

```bash
curl "http://localhost:8080/api/health"
curl "http://localhost:8080/api/search?q=máy+tính&size=3"
```

> **If `docker compose up` stops immediately with "Thieu ADMIN_API_KEY"** — that
> is deliberate, not a bug. Step 2 above has not been done.
> See [Why the admin key is mandatory](#why-the-admin-key-is-mandatory).

### Optional profiles

The default stack is deliberately the lightest thing that still works. Two
opt-in profiles add the distributed crawl pipeline and the observability chain:

```bash
# + Kafka, kafka-ui, a separate crawler-worker process   (~3 GB RAM)
docker compose --profile kafka up -d --build

# + Prometheus, Grafana, Alertmanager, kafka-exporter    (~4 GB RAM)
docker compose --profile kafka --profile monitoring up -d --build
```

| Address | What you get |
|---|---|
| <http://localhost:8081> | kafka-ui — topics, partitions, consumer lag, dead-letter messages |
| <http://localhost:3000> | Grafana (`admin`/`admin`), dashboard pre-provisioned |
| <http://localhost:9090/alerts> | Prometheus — the 7 alert rules and their state |
| <http://localhost:9093> | Alertmanager |

Details: [`docs/DEVOPS.md`](docs/DEVOPS.md).

---

## Running without Docker

Requires JDK 17+ and Node.js 22+.

### Backend

```bash
run-backend.bat             # Windows

# or, by hand:
export ADMIN_API_KEY=$(openssl rand -hex 32)          # Linux/macOS
$env:ADMIN_API_KEY = "..."                             # PowerShell
cd search-engine
./mvnw spring-boot:run
```

`run-backend.bat` reads `ADMIN_API_KEY` from `.env` (generating and saving one
if absent), checks port 8080, sets a 6 GB heap, and warns when `data/index.json`
is older than the crawled corpus. Flags: `--postgres`, `--kafka`, `--bm25`,
`--help`.

No database required: the app falls back to the sample corpus shipped with the
repo (`data/seed-documents.json`), so a fresh clone runs as-is.

### Frontend

```bash
run-frontend.bat            # Windows
# or: cd browser-app && npm install && npm run dev
```

### Crawling your own corpus

```bash
run-crawl.bat 5000 3        # 5,000 pages, depth 3
```

---

## Kubernetes

A three-node [kind](https://kind.sigs.k8s.io/) cluster, ingress, and the full
stack in one command:

```bash
bash deploy/kind/up.sh
# then add to your hosts file:  127.0.0.1 vnsearch.local
curl http://vnsearch.local/api/health
```

Manifests use Kustomize with a shared base and two overlays:

| | `overlays/dev` | `overlays/prod` |
|---|---|---|
| Replicas | 1 | 3, spread across nodes |
| Autoscaling | off (no metrics-server in kind) | HPA, 2–6 pods at 70% CPU |
| Secrets | placeholder file in Git | created out-of-band, never committed |
| Image | local build, `kind load` | pinned tag from GHCR |
| Scorer | `tfidf` | `bm25` |

The backend runs as non-root with a read-only root filesystem under a
`restricted` Pod Security namespace, has startup/readiness/liveness probes, a
PodDisruptionBudget, and a NetworkPolicy restricting Postgres to backend pods
only.

```bash
kubectl apply -k deploy/k8s/overlays/dev     # or overlays/prod
bash deploy/kind/down.sh                     # tear the cluster down
```

---

## API

| Endpoint | Key required? | Description |
|---|:---:|---|
| `GET /api/search?q=&page=&size=` | — | Search |
| `GET /api/suggest?prefix=&limit=` | — | Prefix suggestions (Trie). Note: `prefix`, **not** `q` |
| `GET /api/images?q=&page=&size=` | — | Image search, backed by `ImageStore` |
| `GET /api/feed?seed=&page=&size=` | — | Browse the index without a query. Same `seed` ⇒ same order, so pages join up |
| `GET /api/health` | — | Liveness. Returns `503` when the index is empty |
| `GET /actuator/prometheus` | — | Prometheus metrics |
| `POST /api/admin/crawl` | ✅ | Start a crawl job |
| `GET /api/admin/crawl/{id}/status` | ✅ | Crawl job status |
| `POST /api/admin/reindex` | ✅ | Rebuild the index |
| `GET /api/admin/stats` | ✅ | Detailed statistics |

Protected endpoints take an `X-API-Key` header:

```bash
curl -H "X-API-Key: $ADMIN_API_KEY" http://localhost:8080/api/admin/stats
```

Full examples: [`docs/api-examples.http`](docs/api-examples.http)

---

## Why the admin key is mandatory

`POST /api/admin/crawl` makes the server **fetch a URL chosen by the caller**
and put the contents into an index that `GET /api/search` reads publicly. Leaving
it open is a complete SSRF vulnerability with an exfiltration channel attached —
on a cloud VM, a request to `169.254.169.254` returns temporary IAM credentials.

So the app **deliberately refuses to start** without a key. The alternative —
generating a key and printing it to the log — produces a system that *looks*
healthy while nobody knows the key. Fail loudly rather than fail silently.

Four independent layers, each blocking something different:

| Layer | Blocks | Implemented in |
|---|---|---|
| API key (constant-time comparison) | Strangers | `ApiKeyAuthFilter` |
| Private IP ranges blocked **after DNS resolution**, on every fetch and every redirect hop | URLs pointing into the internal network, even with a valid key | `SeedUrlValidator` + `HtmlDownloader` |
| Caps on `maxPages` / `maxDepth` | A single valid request exhausting resources | `AdminController` |
| Rate limiting (token bucket) | Correct calls arriving too fast | `RateLimitFilter` |

---

## Development

```bash
cd search-engine && ./mvnw clean verify   # 521 tests + coverage gate + static analysis
cd browser-app  && npm run typecheck && npm run lint && npm test   # 53 tests
```

`verify` (not `test`) is what CI runs — it is the only phase that executes the
coverage and static-analysis gates.

### CI/CD

Five workflows, all in [`.github/workflows/`](.github/workflows/):

| Workflow | Trigger | What it does |
|---|---|---|
| `ci.yml` | push to `main`, every PR | Tests, JaCoCo coverage gate, SpotBugs, frontend typecheck/lint/**Vitest**, Docker build, Trivy image scan, **Kafka integration tests**, **infrastructure validation** |
| `cd.yml` | after CI passes on `main`; manual | Build + sign image, deploy to staging automatically and to production behind an approval, `--dry-run=server` first, automatic rollback if the rollout fails |
| `codeql.yml` | push, PR, weekly | CodeQL SAST for Java and TypeScript |
| `release.yml` | tag `v*.*.*` | Multi-arch image to GHCR with SBOM + provenance, cosign keyless signature, blocking CRITICAL CVE scan, GitHub Release |
| `pr-title.yml` | PR opened/edited | Enforces Conventional Commits in the PR title |

The `infrastructure` job validates what YAML normally only reveals at deploy
time: `kustomize build` across all four layers, `kubeconform -strict` against
the real Kubernetes schema, `promtool check rules` (a bad PromQL expression
makes Prometheus refuse to load the **entire** rule file — losing every alert,
silently), `amtool check-config`, `docker compose config` at all three profile
levels, and a diff that stops the Compose and Kubernetes alert rules from
drifting apart.

Four quality gates block a merge, each catching a different kind of breakage:

```
521 tests           → per-unit logic errors
JaCoCo coverage     → new code with no tests          (line ≥ 68%, branch ≥ 65%)
SpotBugs            → bugs no test path reaches       (0 findings)
Ranking quality     → search got worse, tests stayed green
```

The frontend has three gates of its own — `typecheck`, `lint` and **53 Vitest
cases**. The last one is the only one that checks *behaviour*: it pins down the
main-process navigation policy, which is a security boundary (`file://` and
`javascript:` must be refused — see `src/main/urlPolicy.ts`).

The last one is search-specific: the other three can all be green while results
returned to users have degraded. See `RankingQualityTest`.

Dependency updates are automated via [`dependabot.yml`](.github/dependabot.yml)
for Maven, npm, and GitHub Actions.

### Configuration

Every environment variable is documented in [`.env.example`](.env.example). Only
`ADMIN_API_KEY` is required; everything else has a sensible default.

Switch the scoring model to BM25 (higher MRR — see
[`docs/EVALUATION.md`](docs/EVALUATION.md)):

```bash
APP_RANKING_SCORER=bm25
```

---

## Documentation

Documentation is written in Vietnamese.

| File | Contents |
|---|---|
Docs are organised by **the question they answer**, not by source folder:

> **New here? Start with [`docs/README.md`](docs/README.md)** — a roadmap that
> picks a reading order for you (run it / understand it / study the algorithms
> / operate it), plus a "want to change X, read Y" lookup table. The docs are
> written in Vietnamese; this README is the English entry point.

| Document | Answers |
|---|---|
| [**`docs/README.md`**](docs/README.md) | **Documentation roadmap — which of the 69 files to read, in what order** |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | How do the pieces fit into one working system? |
| [`docs/BACKEND.md`](docs/BACKEND.md) | How is the Spring Boot app assembled — beans, config, request lifecycle? |
| [`docs/CONFIGURATION.md`](docs/CONFIGURATION.md) | Every config key, its default, and what breaks if you change it |
| [`docs/INFRASTRUCTURE.md`](docs/INFRASTRUCTURE.md) | Where does it run, and who watches it? Docker, Kubernetes, monitoring |
| [`docs/DEVOPS.md`](docs/DEVOPS.md) | How does code get from a laptop to a cluster? CI/CD, the seven gates |
| [`docs/SECURITY.md`](docs/SECURITY.md) | What is it defended against, and **what is still open**? |
| [`docs/FRONTEND.md`](docs/FRONTEND.md) | The mini browser (Electron + React) |
| [`docs/DSA-REPORT.md`](docs/DSA-REPORT.md) | Big-O and measured numbers |
| [`docs/Math/`](docs/Math/README.md) | One page per class — formulas, worked examples, mind maps |
| [`docs/Math/09-design-patterns/`](docs/Math/09-design-patterns/README.md) | One page per design pattern, and the bug each one fixed |
| [`docs/Math/10-kafka/`](docs/Math/10-kafka/00-SO-DO-TU-DUY.md) | Kafka and the Modular Services — where the pipeline is cut, and why the URL Frontier is **not** replaced |
| [`docs/Math/11-images/`](docs/Math/11-images/00-SO-DO-TU-DUY.md) | Image crawling and search — why filtering happens at crawl time |
| [`docs/Math/12-devops/`](docs/Math/12-devops/00-SO-DO-TU-DUY.md) | CI/CD in detail — every workflow, every gate, file by file |
| [`docs/Math/13-security/`](docs/Math/13-security/00-SO-DO-TU-DUY.md) | Every defence layer, and what breaks if you remove it |
| [`docs/EVALUATION.md`](docs/EVALUATION.md) | Search quality measurement (MRR, P@k, nDCG) |
| [`docs/SO-SANH-PHUONG-AN.md`](docs/SO-SANH-PHUONG-AN.md) | 13 problems, the alternatives rejected, and why |
| [`docs/GIN-BASELINE.md`](docs/GIN-BASELINE.md) | Head-to-head against PostgreSQL GIN |

---

## Repository layout

```
search-engine/          Spring Boot backend (Java 17)
  src/main/java/com/vnsearch/
    crawler/            Fetching, URL filtering, two-tier frontier
    index/              Inverted index, VByte compression, VN segmenter
    query/              Query parsing, posting-list merging
    ranking/            TF-IDF, BM25, PageRank, snippet generation
    datastructure/      Trie, BloomFilter, MinHeap, LRUCache, SparseMatrix
    eval/               Search quality harness
browser-app/            Mini browser (Electron + React + TypeScript)
deploy/
  k8s/                  Kustomize base + dev/prod overlays
  kind/                 Local three-node cluster
docs/                   Documentation
.github/workflows/      CI, CodeQL, release, PR title checks
```

The Vietnamese dictionary is generated from
[`coccoc-tokenizer`](https://github.com/coccoc/coccoc-tokenizer) (LGPL-3.0),
which is **not** vendored here — clone it separately if you need to regenerate
`vietnamese-words.txt`. See `docs/DSA-REPORT.md` §2.8.
