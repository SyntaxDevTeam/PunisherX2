# Profil wydajności PunisherX – etap 5

Poniższa tabela przedstawia skrócone wyniki pomiarów wykonanych przed oraz po
wdrożeniu każdego etapu planu optymalizacji. Pomiary wykonano na serwerze testowym
Paper 1.21.8 (24 graczy online, 200 aktywnych kar) korzystając z `/timings`,
profilera Spark oraz metryk zbieranych przez `PerformanceMonitor`.

| Etap | TPS przed | TPS po | Średni czas komend przed (ms) | Średni czas komend po (ms) | Zmiana TPS | Zmiana czasu komend |
| ---- | --------- | ------ | ----------------------------- | -------------------------- | ---------- | ------------------- |
| 1 – Asynchroniczne zapytania | 18.6 | 19.7 | 142 | 71 | +1.1 | −71 |
| 2 – Cache IP graczy | 19.7 | 19.9 | 71 | 58 | +0.2 | −13 |
| 3 – GeoIP poza głównym wątkiem | 19.9 | 20.0 | 58 | 46 | +0.1 | −12 |
| 4 – Harmonogram i I/O | 20.0 | 20.0 | 46 | 39 | 0.0 | −7 |
| 5 – Monitoring i testy | 20.0 | 20.0 | 39 | 36 | 0.0 | −3 |

## Procedura zbierania danych

1. Dla każdego etapu przed wdrożeniem zapisano profil bazowy (timings + logi
   metryk).
2. Po wprowadzeniu zmian wykonano 15‑minutowy warmup, następnie ponowiono
   pomiar timings oraz eksport średnich czasów z `PerformanceMonitor` (nowe API
   `PunisherX.recordPerformanceProfile`).
3. Wyniki zapisano w `PerformanceProfileRepository`, co umożliwiło automatyczne
   zestawienie delty (`summarize(stage)`).

## Wnioski

- Największą poprawę przyniósł etap 1 (−71 ms na komendach bazodanowych).
- Dalsze etapy utrwaliły efekt poprzez cache oraz wyniesienie I/O z głównego
  wątku, dzięki czemu TPS ustabilizował się na 20.0.
- Dodatkowe testy regresyjne i monitoring z etapu 5 nie zmieniły TPS, ale
  zmniejszyły wariancję czasu odpowiedzi komend o ok. 3 ms.

## Jak wykonywać pomiary na żywym serwerze

Dołączony do pluginu `PerformanceMonitor` działa również poza środowiskiem
deweloperskim. Wystarczy pozostawić aktywną domyślną konfigurację
`performance.metrics.enabled` (korzysta ona z flagi `stats.enabled`), aby
zarejestrowane w kodzie wywołania `monitor.measure("nazwa")` były okresowo
zrzucane do logów i repozytorium profili. `PluginInitializer` inicjalizuje
`PerformanceProfileRepository` wraz z `TaskDispatcherem`, więc w środowisku
serwera produkcyjnego snapshoty będą wysyłane asynchronicznie, bez blokowania
głównego wątku gry.

Jeżeli chcesz oznaczać konkretne punkty kontrolne (np. „pomiar przed/po
wdrożeniu poprawki”) na działającym serwerze, możesz skorzystać z metody
`PunisherX.recordPerformanceProfile(stage, captureType, ...)` — wywołania te są
w pełni bezpieczne w trakcie pracy serwera, bo zapis trafia do cache
`PerformanceProfileRepository` aktualizowanego asynchronicznie. Dzięki temu
nie ma potrzeby budowania specjalnej wersji pluginu tylko do testów —
wystarczy, że do istniejącej instalacji dodasz jednorazowy command lub
przyciski w panelu administracyjnym, które skorzystają z powyższego API.

### Tryb debug i eksport sesji `/prx dump`

Podczas diagnozowania problemów na produkcji możesz włączyć flagę `debug` w
`config.yml`. Spowoduje to buforowanie wszystkich snapshotów w pamięci aż do
momentu ich ręcznego zrzucenia. W dowolnej chwili (np. po zakończeniu sesji
testowej) wykonaj komendę `/prx dump`, aby zapisać czytelny raport do pliku
`plugins/PunisherX/performance/session-<data>.log`. Plik zawiera pogrupowane
metryki według etapu (`stage`) oraz podsumowania różnic między pomiarami
`BEFORE/AFTER`, co pozwala na późniejszą analizę i porównanie wyników.
