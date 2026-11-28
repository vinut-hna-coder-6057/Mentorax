# Knowledge Companion AI configuration and evaluation

## Current behavior

Knowledge Companion retrieves only published, currently AI-eligible contribution
versions. It rechecks selected source versions and consent before returning them
to answer generation. Private conversations and alumni profile fields are not
retrieval evidence. Retrieval is bounded to 250 eligible candidates and combines
normalized lexical matches, structured company/role/category matches, synonym
expansion, exact company/role boosts, and optional semantic reranking. Semantic
reranking is disabled by default and uses the existing private Python AI service.
Its request contains the student's sanitized question and sanitized contribution
category/excerpt only. It has no persistent contribution-embedding cache.

General model guidance is kept distinct from alumni-reported evidence. Alumni
source IDs and versions are checked against the retrieved set; citation
validation rejects unknown or malformed references. Retrieved excerpts are
untrusted evidence and are explicitly told not to act as instructions. The
answer cache retrieval version is `knowledge-retrieval-v2`; conversational
follow-ups bypass the shared answer cache.

The system's rule-based intent/entity understanding and evidence sufficiency
remain deliberately bounded. Candidates below `KNOWLEDGE_MIN_EVIDENCE_RELEVANCE`
(default `0.15`) do not enter grounded generation; this is an uncalibrated
filter, not a confidence probability. The semantic threshold and lexical/semantic
fusion weights are also engineering defaults, not calibrated confidence values.
Roadmap generation continues to use the existing roadmap flow; it has not been
replaced with model-generated personalized plans.

## Optional grounded model provider

The default is `KNOWLEDGE_ANSWER_PROVIDER=auto`: it selects Groq only when both
the API key and model configuration validate; otherwise it selects the
deterministic provider. Groq is an external hosted service and may incur charges
depending on the account and plan. The code cannot verify that an account is on
a free allowance, so `auto` should be used only if the configured account is
approved for that use. To select Groq explicitly, configure
`KNOWLEDGE_ANSWER_PROVIDER=groq`, `GROQ_API_KEY`, and
`KNOWLEDGE_GROQ_MODEL` in the backend environment. The model setting is
required rather than silently defaulting to a model name. Optional controls are
`KNOWLEDGE_GROQ_TEMPERATURE` (default `0.1`),
`KNOWLEDGE_GROQ_MAX_OUTPUT_TOKENS` (default `600`), and
`KNOWLEDGE_GROQ_MAX_ATTEMPTS` (default `2`).
`KNOWLEDGE_PROVIDER_TIMEOUT_MS` sets the shared provider timeout (default
`20000` milliseconds). Explicit choices are `deterministic`, `ollama`, and
`groq`; `auto` is the default. Groq uses the documented OpenAI-compatible Chat
Completions endpoint and `max_completion_tokens` request parameter. Requests
have a bounded timeout and at most three configured attempts; retryable HTTP
statuses include 408, 429, and server errors. Other errors go directly to the
deterministic fallback. The adapter sends no credentials in prompts and does not
log credentials, questions, evidence, response bodies, or exception messages. It sends only the
question and selected sanitized excerpts needed for the answer. General
guidance requests are separately labeled and are not represented as alumni
evidence.

Provider model availability, pricing, free allowances, limits, and data handling
can change and depend on the account. The current provider model list includes
priced models, so Groq must not be described as guaranteed free. Check the
provider's current [model list](https://console.groq.com/docs/models), [API
reference](https://console.groq.com/docs/api-reference), and [data
policy](https://console.groq.com/docs/your-data) before
enabling it. No external provider request or production model quality test was
performed as part of the local implementation. With no provider configured or
when it fails, the service uses its clearly identified deterministic fallback.

For no hosted inference charge, select `KNOWLEDGE_ANSWER_PROVIDER=ollama`, run
Ollama locally, and configure `KNOWLEDGE_OLLAMA_BASE_URL` (loopback only) and
`KNOWLEDGE_OLLAMA_MODEL` (default `gemma3:1b`). CPU inference may be slow and
the model may need a one-time download. If Ollama is unavailable, the backend
falls back to deterministic answers.

## Optional semantic reranking

Set `KNOWLEDGE_SEMANTIC_RETRIEVAL_ENABLED=true` in Spring and point the existing
`AI_SERVICE_URL` at the authenticated private Python service. Configure
`AI_MENTOR_API_TOKEN` in Spring and the corresponding Python service token
`MENTOR_RANKING_API_TOKEN` with the same secret. The new route is
`POST /knowledge/rank`; it is protected by that bearer token. Keep it private
and do not expose it to the mobile client. The service must have the existing
optional CPU semantic dependencies/model available; this is not installed by
the base Python requirements. From `mentorax-ai`, install
`requirements-semantic.txt` to enable the pinned CPU embedding stack; this adds
significant download, memory, and startup/runtime costs, so the default is
disabled. `KNOWLEDGE_SEMANTIC_MIN_SCORE` defaults to `0.48` and should be tuned
only against separately labeled validation data. If
semantic ranking is disabled, unavailable, or returns invalid data, Spring
continues with lexical ranking. Python errors do not make the app answer from
ineligible sources.

Example local configuration, entered in the Spring process environment (use
test-only random values for internal service tokens; never put them in source):

```powershell
$env:KNOWLEDGE_ANSWER_PROVIDER='ollama'
$env:KNOWLEDGE_OLLAMA_BASE_URL='http://127.0.0.1:11434'
$env:KNOWLEDGE_OLLAMA_MODEL='gemma3:1b'
```

With no AI provider configured, leave `KNOWLEDGE_ANSWER_PROVIDER=auto` and
omit hosted credentials; it selects deterministic mode. To opt into Groq, set
the provider to `groq` and supply the key and model through environment
variables. Do not paste key values into source files or terminal logs.

## Local evaluation and limits

Backend tests use synthetic contribution fixtures and mocked providers; they
test retrieval behavior, source validation, fallback, intent routing, and
context resolution without external services. Python's request-scoped ranking
tests use controlled vectors and confirm contribution embeddings are not
cached. Those tests do not prove real model semantic quality, external LLM
quality, or production eligibility under database load. Run the Java
`KnowledgeRetrievalServiceTest` and the focused Knowledge Companion test set
after changes. `KnowledgeRetrievalEvaluationTest` compares the current lexical
and synonym retrieval to the lexical implementation at the checked-out local
HEAD over nine fixed synthetic answerable queries and nine contribution
fixtures. The current P@3, Recall@5, and MRR are `0.407`, `1.000`, and `1.000`;
local-HEAD baseline values are `0.370`, `0.889`, and `0.833`. This is a small
regression fixture, not an independently sampled quality benchmark, and it
does not compare with the requested GitHub commit. General-answer relevance,
groundedness, citation validation, and abstention are checked by deterministic
provider contract tests rather than calibrated numeric scores. Use a separately
reviewed, labeled corpus before changing semantic thresholds or claiming
statistically meaningful quality gains.

No production database, live alumni content, external LLM, or Render service
was used for local validation. The checkout commit is not the requested
`9730fd2004b904302984c4fcc1ed60b318999b1f`; results in this working tree must
not be presented as measured against that GitHub baseline.
