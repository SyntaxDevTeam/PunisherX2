# Plan naprawczy wydajności PunisherX

## Etap 1 – Przeniesienie zapytań bazodanowych z głównego wątku
- [x] Zaimplementować w `CommandManager`/poszczególnych komendach wspólny helper, który odpala logikę pobierania danych (`PunishmentService`, `DatabaseHandler`) przez `TaskDispatcher.supplyAsync`.
- [x] Przerobić `HistoryCommand`, `CheckCommand`, `BanListCommand` (i inne korzystające z `databaseHandler.get*`) tak, aby:
  - pobieranie/filtracja danych działały w wątku roboczym,
  - aktualizacja GUI/wiadomości wracała na wątek główny (`thenOnMainThread`).
- [x] Dodać krótkotrwałe cache wyników listowań (np. `PunishmentService#getPunishmentHistory` z TTL), żeby ograniczyć liczbę zapytań w krótkim czasie.
- [x] Dla wywołań w pętlach (np. generowanie list) unikać dodatkowych zapytań usuwających wygasłe kary w gorącym path — przenieść czyszczenie do zadania okresowego.

## Etap 2 – Optymalizacja `PlayerIPManager`
- [x] Utrzymywać w pamięci cache rekordów IP (Caffeine) aktualizowany przy zapisie; synchronizować dostęp przez `TaskDispatcher.runAsync`.
- [x] Operacje plikowe/bazodanowe (`readLines`, `appendLine`, `deletePlayerInfo`) wykonywać asynchronicznie i batchowo.
- [x] Wyeliminować wielokrotne odszyfrowywanie całego pliku w `searchCache`; przechowywać zdeszyfrowane dane w pamięci, a zmiany zapisywać okresowo.
- [x] W miejscach użycia (komendy, GUI) korzystać z pamięci podręcznej zamiast każdorazowego odczytu z dysku/bazy.

## Etap 3 – GeoIP i sieć poza wątkiem głównym
- [x] Zastąpić bezpośrednie tworzenie `GeoIPHandler` (które pobiera pliki) zadaniem startowym w `TaskDispatcher.runAsync` z raportowaniem postępu.
- [x] Utrzymywać pojedynczą instancję `DatabaseReader` współdzieloną między wywołaniami (lazy init + recykling), zamiast tworzyć nową przy każdym `getCountry`/`getCity`.
- [x] Obsługę błędów (brak licencji/brak pliku) zamieniać na flagę wyłączającą funkcje GeoIP, aby nie próbować pobierać danych w kółko.
- [x] Wywołania GeoIP w zdarzeniach (`PlayerJoinEvent`) wykonywać asynchronicznie z timeoutem, a wynik dostarczać na główny wątek dopiero przy zapisie.

## Etap 4 – Zadania cykliczne i I/O
- [x] `checkLegacyPlaceholders` przekształcić tak, by wykonywał odczyt pliku w wątku roboczym (np. `taskDispatcher.runAsync`), a na główny wracał tylko z logami.
- [x] Zastanowić się nad przeniesieniem kosztownych operacji czyszczenia baz (`removePunishment` w `getPunishments`) do osobnego zadania harmonogramu. → Czyszczenie utrzymywane w `PunishmentService.cleanupExpiredPunishments` jako cykliczne zadanie, brak wywołań usuwających w gorącym path `getPunishments`.
- [x] Dodać metryki czasu wykonania (np. `System.nanoTime()`) dla najbardziej ruchliwych operacji oraz okresowo logować średnie czasy wykonania.

## Etap 5 – Testy regresyjne i monitoring
- [ ] Przygotować profil wydajności (np. `/timings`, Spark) przed i po wdrożeniu każdego etapu.
- [ ] Dodać testy integracyjne dla asynchronicznych ścieżek (mock scheduler) oraz testy jednostkowe cache.
- [ ] Po wdrożeniu każdego etapu zebrać logi i ocenić wpływ na czas odpowiedzi komend oraz na TPS serwera.
