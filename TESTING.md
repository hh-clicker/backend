# Проверки и диагностические режимы backend

## Автономный suite

```powershell
.\gradlew.bat test --console=plain
```

Нужны JDK 25 и Docker Engine для Testcontainers. Suite включает HTTP/API, PostgreSQL,
конкурентные транзакции и ArchUnit. Синтетические тесты не обращаются к настоящему HH
или Codex. Opt-in проверка восстановленного дампа без явной конфигурации пропускается.

## Режимы рабочей установки

| Режим | Вызов | Эффект |
|---|---|---|
| Холостой запуск | POST /api/v1/application-runs, mode=DRY_RUN; checkbox в диалоге запуска | Реальные страницы HH и сохранение текстов; без permit, писем и откликов. Архивные вакансии могут перейти в CLOSED. |
| Benchmark оценки | POST /api/v1/vacancies/relevance/parallel, benchmark=true | Реальные вызовы Codex по выбранным карточкам, включая оценённые; без записи рейтингов и уведомлений. |
| Проверочный MD | GET /api/v1/market-analyses/<id>/report?debug=true | Выгрузка сохранённого снимка, без нового анализа; включает полные тексты, цитаты и диагностику. |
| Метрики | Generation Agent GET /metrics?runId=<id> | Чтение безопасных числовых данных, без генерации. |

Для кнопки проверочного MD откройте UI /market-analysis?debug=1. Проверочный отчёт
содержит пользовательские данные: перед передачей проверяйте его состав.
Benchmark и сбор метрик доступны через [PowerShell-инструменты](tools/README.md).
Холостой запуск — пользовательский сценарий загрузки текстов, а не мок HH.

## Обновление восстановленной копии БД

RestoredDatabaseCompatibilityIT — отдельная приёмочная проверка перехода с 1.12.0.
Она изменяет только специально восстановленную копию: инициализирует DataSource и
Liquibase без backend workers, HTTP-сервера и агентов. Обычный backend запускать не нужно.

Предварительно восстановите baseline-дамп с 35 миграциями в отдельный PostgreSQL 17,
доступный через 127.0.0.1. Имя БД строго hhclicker_upgrade_restore. На этой копии создайте
public.hh_clicker_upgrade_verification с одной текстовой колонкой id и ровно одной строкой:
случайный идентификатор из 12 строчных hex-символов. Это явный маркер разрешённой тестовой БД.
Не создавайте такой маркер в рабочей установке.

Задайте в текущей сессии PowerShell:

```powershell
$env:HH_CLICKER_UPGRADE_JDBC_URL = 'jdbc:postgresql://127.0.0.1:15432/hhclicker_upgrade_restore'
$env:HH_CLICKER_UPGRADE_DB_USER = 'hh_clicker'
$env:HH_CLICKER_UPGRADE_DB_PASSWORD = Read-Host 'Пароль тестовой БД' -MaskInput
$env:HH_CLICKER_UPGRADE_VERIFICATION_ID = Read-Host 'ID маркера тестовой копии'
.\gradlew.bat test --tests '*RestoredDatabaseCompatibilityIT' --console=plain
```

Укажите фактический порт и пользователя копии. Тест проверяет неизменность прежних
строк и истории, однократное добавление миграции 036 и пустой таблицы партий,
затем неизменность второй инициализации. Сравнение выполняется по серверным хешам;
резюме и вакансии не выводятся в assertions. Перед повторным полным прогоном снова
восстановите исходный baseline: внутри одного прогона уже проверяется повторный startup.
После проверки удалите переменные из сессии, особенно пароль.

Переходы на синтетической БД, ошибки истории и конкурентный startup отдельно проверяет
LiquibaseCompatibilityIT. Такая проверка backend не заменяет проверку Windows installer.
