# Verification record

Verified on 2026-09-21 on macOS with Java 25.0.4, Node 22.19 and Docker 28.4.0.

Passed:

- Maven compile, two JUnit pagination tests, executable JAR packaging.
- TypeScript checking, Vite production build and three Vitest API-client tests.
- npm dependency audit reported zero vulnerabilities at installation time.
- Docker Compose configuration validation.
- Backend, frontend and seed image builds.
- Dependency health checks and Flyway migration on a fresh PostgreSQL volume.
- Full Docker startup with API and worker running as separate containers; API health UP.
- Fresh seed: 100 users, 10 categories, 1,000 articles, 1,000 subscriptions, and 100,000 durable/materialized feed entries. Redis counts exclude the empty-feed sentinel.
- Seed rerun and seed execution through the one-shot Docker container, without duplicate memberships.
- Live integration suite against real PostgreSQL/Redis/RabbitMQ, both local backend and all-container backend: publication pipeline, duplicate event handling, cursor pages, input validation, cache loss/rebuild, subscription removal/backfill, article category changes, and poison-event dead lettering.
- RabbitMQ outage/recovery test: article + outbox commit while broker stopped, followed by successful asynchronous delivery after broker restart.
- Browser inspection of login and authenticated feed rendering through Nginx at localhost:3000.

No million-user load test, HA/failover test, exhaustive concurrency test, or security audit was performed. CI configuration is included but was not dispatched to a hosted CI service. Integration tests add users/articles and DLQ messages; reset and reseed for pristine counts.

## Reader/writer and article metadata update

Verified Maven packaging/unit tests and frontend TypeScript/Vite build plus Vitest tests. Applied Flyway V2 successfully to the existing database after restoring V1's original formatting (its SQL was unchanged). The real-service `roles_test.py` checks default reader registration, writer registration, reader HTTP 403 on create/edit, writer publishing/editing, author and multi-category metadata in detail/list/feed, reader subscriptions and invalid-role HTTP 400. Test accounts/articles remain available for inspection.
