# Production launch checklist

Цель этого чеклиста - довести проект до честного single-server production запуска. Он не заменяет Kubernetes/HA, но закрывает критичные риски первого коммерческого релиза.

## 1. Перед сборкой

- [ ] Все реальные секреты хранятся только в `.env.prod` на сервере или в GitHub Secrets.
- [ ] `.env.prod` создан из `.env.prod.example`, без `replace_me`, `example.com` и одинаковых паролей.
- [ ] `APP_SECRET_ENCRYPTION_KEY` - Base64 от 32 байт, сохранен в password manager.
- [ ] Telegram curator bot и admin bot используют разные токены.
- [ ] VK callback URL указывает на production-домен с HTTPS.

## 2. CI и качество

- [ ] `./gradlew test` проходит локально.
- [ ] `./gradlew integrationTest` проходит в окружении с Docker.
- [ ] GitHub Actions зеленый на `main`.
- [ ] `./scripts/secret-scan.sh` не находит токены и приватные ключи.
- [ ] `docker compose --env-file .env.prod.example -f compose.prod.yml config --quiet` проходит.

## 3. Сервер

- [ ] Открыты только 80/443 наружу.
- [ ] SSH закрыт ключами, парольный вход отключен.
- [ ] Docker и compose-plugin установлены.
- [ ] Диск рассчитан под PostgreSQL, Kafka, Redis и бэкапы.
- [ ] Настроен swap или ограничение памяти, чтобы OOM не убивал Postgres первым.

## 4. Запуск

- [ ] Выполнен `./scripts/preflight-prod.sh` с настоящим `.env.prod`.
- [ ] `docker compose --env-file .env.prod -f compose.prod.yml up -d --build` завершился без ошибок.
- [ ] Все сервисы healthy: Postgres, Redis, Kafka, четыре Spring-сервиса, Caddy.
- [ ] `/actuator/health/readiness` зеленый у каждого backend-сервиса.
- [ ] Caddy получил TLS-сертификат для `PUBLIC_DOMAIN`.

## 5. Проверка бизнес-сценариев

- [ ] Куратор проходит регистрацию в Telegram.
- [ ] VK-группа привязывается и подтверждает callback.
- [ ] Сообщение студента из VK попадает в workflow.
- [ ] AI-ответ доходит до куратора в Telegram.
- [ ] Approve отправляет ответ обратно в VK.
- [ ] Reject/manual reply не списывает лишние кредиты.
- [ ] Ошибка AI возвращает резерв кредитов.
- [ ] Повторный webhook не создает двойной workflow.

## 6. Эксплуатация

- [ ] Бэкап PostgreSQL создается через `./scripts/backup-postgres.sh`.
- [ ] Restore проверен на отдельном тестовом сервере через `./scripts/restore-postgres.sh`.
- [ ] Есть алерт на рост DLT, Kafka lag, ошибки OpenRouter, нехватку диска и падение healthcheck.
- [ ] Логи не содержат raw JSON с текстами пользователей, VK token, Telegram token или OpenRouter key.
- [ ] Есть понятный план отката: предыдущий image/tag, backup DB, список команд.

## 7. Честные границы первого прода

- [ ] Это single-server production, не HA-кластер.
- [ ] Потеря сервера без свежего backup означает потерю части данных.
- [ ] Kafka replication factor равен 1, потому что broker один.
- [ ] Горизонтальное масштабирование сервисов допустимо только после проверки consumer groups, idempotency и rate limits.
