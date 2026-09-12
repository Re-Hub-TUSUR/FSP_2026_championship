# Balloon backend

Java 21 / Spring Boot 3 API for the demo game. State is kept in memory so the service starts without PostgreSQL.

```bash
gradle bootRun
```

API is available at `http://localhost:8080/api/v1`; health at `/actuator/health`.

The demo player is `demo` with 1000 points. Start a round with `POST /rounds` and a unique `Idempotency-Key`, poll `GET /rounds/{id}` every second, and call `POST /rounds/{id}/cashout` after level 1. `GET /rounds/{id}/proof` reveals the seed and crash multiplier after settlement.

## Daily bonus

`GET /daily-bonus` returns the authenticated player's progress for the current
server day (Europe/Moscow by default). Every completed round adds one missing
piece to the six-piece puzzle. Once all six pieces are collected, one ticket is
opened. Scratch it with `POST /daily-bonus/claim` (or `/daily-bonus/scratch`);
the server chooses a reward from 10 to 2,000 and credits the balance atomically.
Repeated claims are idempotent and return the already persisted reward. Pass an
`Idempotency-Key` header or `{ "idempotencyKey": "…" }` in the request body.
The 2,000 jackpot uses one uniform roll out of 100,000 (0.001%); every other
integer reward from 10 through 1,999 is selected uniformly.
