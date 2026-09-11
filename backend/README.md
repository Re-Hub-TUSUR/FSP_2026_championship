# Balloon backend

Java 21 / Spring Boot 3 API for the demo game. State is kept in memory so the service starts without PostgreSQL.

```bash
gradle bootRun
```

API is available at `http://localhost:8080/api/v1`; health at `/actuator/health`.

The demo player is `demo` with 1000 points. Start a round with `POST /rounds` and a unique `Idempotency-Key`, poll `GET /rounds/{id}` every second, and call `POST /rounds/{id}/cashout` after level 1. `GET /rounds/{id}/proof` reveals the seed and crash multiplier after settlement.
