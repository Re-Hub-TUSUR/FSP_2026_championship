# «Воздушный шар»

MVP игры с серверной случайностью: Java/Spring Boot API, React-клиент, PostgreSQL и Redis. Сервер является авторитетным источником состояния раунда; браузер только отображает события и отправляет команды `start`/`cashout`.

## Быстрый запуск

Требуются Docker Desktop с Compose v2. Из корня репозитория выполните:

```bash
docker compose -f infra/docker-compose.yml up --build
```

После запуска:

- приложение: <http://localhost:3000>
- API: <http://localhost:8080/api/v1>
- Swagger UI (если включён backend-профилем): <http://localhost:8080/swagger-ui/index.html>
- PostgreSQL: `localhost:5432`, база/пользователь/пароль `balloon`/`balloon`/`balloon`

MVP хранит состояние раундов и кошелька в памяти Java-сервиса, поэтому запускается без миграций; PostgreSQL и Redis в Compose подготовлены для следующего этапа. Пользователь `demo` используется напрямую и имеет стартовый баланс 1000.

## API в двух командах

Все маршруты используют префикс `/api/v1`. Авторизованные запросы передают `Authorization: Bearer <token>`. Для запуска раунда обязателен уникальный `Idempotency-Key`.

```bash
curl http://localhost:8080/api/v1/me \
  -H "Authorization: Bearer $TOKEN"

curl -X POST http://localhost:8080/api/v1/rounds \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: demo-$(date +%s)" \
  -d '{"theme":"RED","betOptionId":"red-x2"}'

curl -X POST http://localhost:8080/api/v1/rounds/<ROUND_ID>/cashout \
  -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: cashout-<ROUND_ID>"
```

Контракт с моделями ответов находится в [docs/api/openapi.yaml](docs/api/openapi.yaml). Realtime-события доступны через WebSocket STOMP `/ws`, подписка `/topic/round/{roundId}`; при разрыве клиент восстанавливает состояние запросом `GET /rounds/{id}`.

## Механика раунда

Шар проходит по одному уровню в секунду. На каждом уровне он проходит проверку на разрыв: шанс взрыва растёт экспоненциально вместе с высотой, а коэффициент выплаты обратен вероятности до этого уровня долететь.

```
hazard(L)     = min(maxHazard, baseHazard × hazardGrowth^(L-1))
survival(L)   = Π (1 - hazard(i)), i = 1..L
multiplier(L) = rtp / survival(L)
```

Из-за этого коэффициенты не статические, а ускоряются: для красной темы ×1.09 → ×25.54 за 12 уровней при шансе взрыва от 12% до 40%. Возврат игроку одинаков на любом уровне (~96%), поэтому ни одна стратегия выхода не выгоднее другой.

Взрыв определяется детерминированно из сида раунда: шар лопается на первом уровне, где `HMAC_SHA256(seed, roundId:level) < hazard(level)`. До начала раунда сервер публикует `commitment = sha256(seed:roundId:configVersion)`, а после завершения отдаёт сид и уровень взрыва в `GET /rounds/{id}/proof` — результат можно проверить самостоятельно.

Ставка списывается при старте раунда, выплата начисляется при cashout и автоматически, если шар долетел до последнего уровня. Бустер ставки умножает бонусные очки и не влияет на коэффициент выплаты.

## Конфигурация игры

Значения по умолчанию заданы в `Config.defaults` (`backend/src/main/java/com/example/balloon/domain/Models.java`), их справочный снимок — в `backend/src/main/resources/game-config.yml`. Уровни, ставки, бустеры, очки и параметры риска меняются на лету через `PUT /api/v1/admin/config?theme=RED`; запущенные раунды продолжают играть по сохранённому snapshot конфига.

## Остановка и очистка

```bash
docker compose -f infra/docker-compose.yml down
# удалить локальные данные PostgreSQL/Redis:
docker compose -f infra/docker-compose.yml down -v
```

Для запуска сервисов без Docker используйте Java 21/Gradle в `backend` и Node.js 22 в `frontend`; переменные подключения к PostgreSQL должны соответствовать значениям из Compose.

Пример запуска в двух терминалах (backend по-прежнему слушает `8080`, Vite — `5173`):

```bash
# terminal 1
cd backend
gradle bootRun

# terminal 2
cd frontend
npm install
npm run dev -- --port 5173
```

В этом режиме PostgreSQL и Redis нужно запустить отдельно либо оставить только те интеграции, которые включены текущим профилем Spring. Если backend использует Docker-профиль, поднимите зависимости без приложений командой `docker compose -f infra/docker-compose.yml up -d postgres redis`.


