# Non-local deployment security contract

This document defines the minimum startup contract for any AkmAI deployment that is not explicit local development.

## Environment classification

Set an explicit deployment environment:

```bash
AKMAI_ENVIRONMENT=staging
```

Use a non-local value such as `staging`, `production`, or the environment name used by the platform. `local`, `dev`, and `test` are treated as local-development environments.

A non-local Spring profile is also treated as hardened even when `AKMAI_ENVIRONMENT` is omitted. Production safety therefore does not depend only on the literal `prod` profile.

## Required non-local secrets

Inject all of the following through the deployment secret mechanism. Do not commit the values to source control or bake them into an image:

```bash
AKMAI_SECURITY_ENABLED=true
AKMAI_ALLOW_UNAUTH_LOCAL=false
AKMAI_SECURITY_API_KEY=<runtime-secret-at-least-32-characters>
AKMAI_SECURITY_ADMIN_API_KEY=<different-runtime-secret-at-least-32-characters>
DB_USERNAME=<runtime-database-user>
DB_PASSWORD=<runtime-database-password>
```

`DB_USERNAME=akmai` and `DB_PASSWORD=akmai` are local-development defaults and are rejected outside local development.

The regular and admin API keys must both be present in a hardened deployment, must each contain at least 32 characters, and must differ from one another.

## Fail-closed startup rules

A hardened deployment fails startup when any of the following is true:

- API-key security is disabled;
- unauthenticated local mode is enabled;
- the API key is missing or too short;
- the admin API key is missing, too short, or equal to the regular API key;
- database username or password was not explicitly injected;
- either database credential still uses the predictable `akmai` local default.

The validator reports only configuration categories in failure messages. It does not include secret values in exceptions or logs.

## Local development

Local development can retain low-friction defaults, but unauthenticated operation must remain explicit:

```bash
AKMAI_ENVIRONMENT=local
AKMAI_SECURITY_ENABLED=false
AKMAI_ALLOW_UNAUTH_LOCAL=true
```

Local datasource defaults may continue to use the Compose-compatible `akmai/akmai` credentials.

## Deployment checklist

Before promoting a non-local deployment:

1. set `AKMAI_ENVIRONMENT` explicitly;
2. inject API, admin, and database secrets from the platform secret store;
3. verify `AKMAI_ALLOW_UNAUTH_LOCAL=false`;
4. verify no deployment manifest contains literal secret values;
5. start the application once with one required secret intentionally absent and confirm fail-closed startup;
6. restore the secret and confirm startup succeeds;
7. retain the startup/CI evidence without recording secret values.

The executable startup behavior is covered by `SecurityStartupValidatorTest`.
