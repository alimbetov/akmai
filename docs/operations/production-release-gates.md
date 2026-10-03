# Production release gates

## Main branch

The target branch-protection contract for `main` is:

- pull requests required;
- force pushes disabled;
- branch deletion disabled;
- required check: `CI / verify`;
- required check: `Retrieval Quality Gate / quality`;
- required check:
  `Retrieval Storage Final Benchmark / Required retrieval benchmark gate`.

The retrieval storage workflow always publishes the stable required gate.
For non-retrieval changes the expensive matrix is skipped and the stable gate
passes. For retrieval-sensitive changes the gate requires both the ACL matrix
and language HOT-only benchmark to succeed.

## Release tags

Tags matching `v*` run `Live Retrieval Quality`.

A release-quality run requires:

- deterministic production multilingual retrieval quality;
- live Ollama endpoint configuration;
- live embedding quality for
  KK/RU/EN/ZH/DE/FR/ES/PT/IT/TR/EL.

Do not publish a production release from a tag whose live-quality workflow is
not successful.

## Production security

The `prod` Spring profile requires:

- API-key security enabled;
- `AKMAI_SECURITY_API_KEY` present and at least 32 characters;
- unauthenticated local mode disabled;
- public access limited to actuator health probes;
- actuator metrics and info authenticated with the API key;
- all other actuator endpoints denied unless explicitly added to the security
  policy.

Health probes remain unauthenticated so orchestration liveness/readiness checks
do not depend on application credentials.
