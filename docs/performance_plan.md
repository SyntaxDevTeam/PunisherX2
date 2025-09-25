# Plan naprawczy wydajności PunisherX

## Etap 1 – Przeniesienie zapytań bazodanowych z głównego wątku
- [ ] Zaimplementować w `CommandManager`/poszczególnych komendach wspólny helper, który odpala logikę pobierania danych (`PunishmentService`, `DatabaseHandler`) przez `TaskDispatcher.supplyAsync`.
- [ ] Przerobić `HistoryCommand`, `CheckCommand`, `BanListCommand` (i inne korzystające z `databaseHandler.get*`) tak, aby:
  - pobieranie/filtracja danych działały w wątku roboczym,
  - aktualizacja GUI/wiadomości wracała na wątek główny (`thenOnMainThread`).
- [ ] Dodać krótkotrwałe cache wyników listowań (np. `PunishmentService#getPunishmentHistory` z TTL), żeby ograniczyć liczbę zapytań w krótkim czasie.
- [ ] Dla wywołań w pętlach (np. generowanie list) unikać dodatkowych zapytań usuwających wygasłe kary w gorącym path — przenieść czyszczenie do zadania okresowego.

## Etap 2 – Optymalizacja `PlayerIPManager`
- [ ] Utrzymywać w pamięci cache rekordów IP (`ConcurrentHashMap`) aktualizowany przy zapisie; synchronizować dostęp przez `TaskDispatcher.runAsync`.
- [ ] Operacje plikowe/bazodanowe (`readLines`, `appendLine`, `deletePlayerInfo`) wykonywać asynchronicznie i batchowo.
- [ ] Wyeliminować wielokrotne odszyfrowywanie całego pliku w `searchCache`; przechowywać zdeszyfrowane dane w pamięci, a zmiany zapisywać okresowo.
- [ ] W miejscach użycia (komendy, GUI) korzystać z pamięci podręcznej zamiast każdorazowego odczytu z dysku/bazy.

## Etap 3 – GeoIP i sieć poza wątkiem głównym
- [ ] Zastąpić bezpośrednie tworzenie `GeoIPHandler` (które pobiera pliki) zadaniem startowym w `TaskDispatcher.runAsync` z raportowaniem postępu.
- [ ] Utrzymywać pojedynczą instancję `DatabaseReader` współdzieloną między wywołaniami (lazy init + recykling), zamiast tworzyć nową przy każdym `getCountry`/`getCity`.
- [ ] Obsługę błędów (brak licencji/brak pliku) zamieniać na flagę wyłączającą funkcje GeoIP, aby nie próbować pobierać danych w kółko.
- [ ] Wywołania GeoIP w zdarzeniach (`PlayerJoinEvent`) wykonywać asynchronicznie z timeoutem, a wynik dostarczać na główny wątek dopiero przy zapisie.

## Etap 4 – Zadania cykliczne i I/O
- [ ] `checkLegacyPlaceholders` przekształcić tak, by wykonywał odczyt pliku w wątku roboczym (np. `taskDispatcher.runAsync`), a na główny wracał tylko z logami.
- [ ] Zastanowić się nad przeniesieniem kosztownych operacji czyszczenia baz (`removePunishment` w `getPunishments`) do osobnego zadania harmonogramu.
- [ ] Dodać metryki czasu wykonania (np. `System.nanoTime()`, integration z `SyntaxCore.statsCollector`) dla najbardziej ruchliwych operacji, aby monitorować postęp.

## Etap 5 – Testy regresyjne i monitoring
- [ ] Przygotować profil wydajności (np. `/timings`, Spark) przed i po wdrożeniu każdego etapu.
- [ ] Dodać testy integracyjne dla asynchronicznych ścieżek (mock scheduler) oraz testy jednostkowe cache.
- [ ] Po wdrożeniu każdego etapu zebrać logi i ocenić wpływ na czas odpowiedzi komend oraz na TPS serwera.
