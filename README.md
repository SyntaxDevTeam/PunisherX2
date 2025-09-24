# PunisherX2

PunisherX2 to nowa generacja pluginu moderacyjnego zaprojektowana w oparciu o plan z [`docs/PunisherX2-plan.md`](docs/PunisherX2-plan.md).
Projekt koncentruje się na całkowicie asynchronicznej logice i cache Caffeine 3.2.2, aby wyeliminować blokady wątku głównego.

## Struktura modułów
- `punisherx2-api` – publiczny interfejs asynchronicznego API wraz z modelami domenowymi.
- `punisherx2-plugin` – implementacja referencyjna z cache Caffeine, strumieniem zdarzeń i wstępnym, pamięciowym repozytorium danych.

## Wymagania rozwojowe
- Java 21
- Kotlin 2.2.20
- Gradle 9.1.0

## Uruchamianie testów
```bash
chmod +x gradlew
./gradlew --console=plain test
```
