# Plan kontroli wydajności i bezpieczeństwa dla PunisherX2

## 1. Cel i zakres
* **Cel:** zaprojektowanie PunisherX2 (PunisherX 2.0) jako następcy obecnego pluginu, którego kod źródłowy do wglądu znajduje się w katalogu "legacy", z naciskiem na eliminację blokowania głównego wątku, minimalizację dostępu do dysku i izolację od powolnych połączeń zewnętrznych.
* **Zakres:** wszystkie moduły (komendy, zdarzenia, GUI, placeholdery, integracje zewnętrzne, migracje danych) muszą spełniać poniższe warunki krytyczne przed dopuszczeniem do produkcji.

## 2. Warunki krytyczne (must-have)
1. **Brak synchronicznych operacji I/O:** żadna ścieżka wykonania na głównym wątku nie może zawierać operacji dyskowych, sieciowych ani zapytań SQL. Wszelkie operacje tego typu muszą być delegowane do zadań asynchronicznych (scheduler Folia/Paper/Bukkit lub dedykowany `ExecutorService`).
2. **Caffeine 3.2.2 jako warstwa cache:** wszystkie powtarzalne odczyty danych (kary, statystyki, dane IP, placeholdery) muszą korzystać z cache opartych na Caffeine 3.2.2. Każdy moduł wymaga dokumentacji strategii wygaszania (`expireAfterWrite`/`expireAfterAccess`), limitu rozmiaru oraz polityki odświeżania w tle (`refreshAfterWrite`).
3. **Atomowe przejście między wątkami:** interakcje między wątkiem asynchronicznym a głównym muszą wykorzystywać bezpieczne konstrukcje (`CompletableFuture`, kolejki zdarzeń), tak aby na główny wątek trafiały wyłącznie gotowe dane modelowe.
4. **Izolacja od usług zewnętrznych:** wszystkie połączenia HTTP (np. GeoIP) muszą działać na wątkach roboczych, mieć skonfigurowane limity czasowe < 2 s oraz wielopoziomowe cache (RAM + plik tymczasowy). W przypadku awarii usługi musi istnieć szybka ścieżka degradacji bez blokowania.
5. **Ograniczenie dostępu do dysku:** dane konfiguracyjne i statyczne muszą być buforowane w pamięci po inicjalnym wczytaniu. W trakcie działania zabronione jest ponowne parsowanie plików konfiguracyjnych na głównym wątku.
6. **Bezpieczeństwo danych:** wszelkie operacje na danych wrażliwych (IP, powody banów) muszą stosować szyfrowanie w spoczynku i podpisy zmian. Kod odpowiedzialny za deszyfrację ma działać poza głównym wątkiem i korzystać z cache na odszyfrowane rekordy.
7. **Testy regresji wydajności:** pipeline CI musi obejmować testy obciążeniowe (symulacja ≥ 100 jednoczesnych wejść graczy) potwierdzające brak skoków czasu ticków > 50 ms oraz brak wzrostu GC powyżej 10% czasu CPU.

## 3. Architektura wysokiego poziomu
* **Warstwa API / jądra pluginu:**
    * Publiczny moduł `PunisherX2API` wystawia kontrakty do obsługi kar, danych IP, statystyk oraz integracji GUI, będąc jedynym wejściem do logiki domenowej (zarówno dla modułów wewnętrznych, jak i zewnętrznych pluginów).
    * Wszystkie metody API są w pełni asynchroniczne (`CompletableFuture`, `Publisher`) i zwracają obiekty tylko po scaleniu danych z cache Caffeine; bezpośredni dostęp do repozytoriów jest zabroniony.
    * API udostępnia strumień zdarzeń domenowych (np. `PunishmentAppliedEvent`, `PunishmentRevokedEvent`) emitowanych poza wątkiem głównym z możliwością subskrypcji przez inne pluginy.
    * Interfejsy API objęte są stabilnym wersjonowaniem semantycznym i kontraktami bezpieczeństwa (kontrola uprawnień, walidacja danych wejściowych, anonimizacja wrażliwych pól).
* **Warstwa dostępu do danych:**
    * Repozytoria blokują się wyłącznie na wątkach roboczych. Wszystkie metody zwracają `CompletableFuture<T>` lub `Publisher<T>`.
    * Konfiguracja połączeń z bazą obejmuje pulę (HikariCP) i mechanizm backoffu.
    * Caffeine stanowi pierwszą linię odczytu. Operacje `write-through` lub `write-behind` z synchronizacją okresową.
* **Warstwa usług:**
    * Serwisy budują modele domenowe poza głównym wątkiem.
    * Mechanizmy powiadomień (event bus) używają kolejek lock-free.
* **Warstwa prezentacji (GUI/placeholdery):**
    * GUI subskrybuje gotowe dane poprzez `ViewModel` odświeżane z schedulerów.
    * Placeholdery korzystają wyłącznie z danych znajdujących się w cache. Brak bezpośrednich zapytań do bazy.

## 4. Zarządzanie cache (Caffeine 3.2.2)
* **Konfiguracja globalna:**
    * `Scheduler`: użyć `Scheduler.systemScheduler()` z guardem, aby odświeżanie nie wykonywało się na wątku głównym.
    * `RemovalListener`: logowanie i metryki dla diagnozowania wyeksmitowanych wpisów.
* **Przykładowe segmenty cache:**
    * `PunishmentCache`: klucz `UUID`, wartość model kary – `expireAfterWrite 5m`, `refreshAfterWrite 1m`, `maximumSize` dobrane do liczby aktywnych graczy.
    * `StatisticsCache`: zliczenia kar/działań – `maximumSize 100`, odświeżanie w tle co 30 s.
    * `GeoIpCache`: klucz `IP`, wartość dane geolokalizacji – `expireAfterAccess 12h`, fallback do lokalnego pliku DB.
    * `CommandRateCache`: ochrona przed spamem – `expireAfterWrite 30s`, integracja z modułem bezpieczeństwa.
* **Procedury utrzymania:**
    * Harmonogram czyszczenia pamięci uruchamiany w tle (`cleanup()`), nie częściej niż co 5 minut.
    * Dashboard metryk (Micrometer + Prometheus) monitorujący trafienia/chybienia cache i czas odświeżania.

## 5. Obsługa operacji masowych
* **Migracje / eksporty / importy:**
    * Uruchamiane jako zadania asynchroniczne z raportowaniem postępu.
    * Operacje na plikach wykonywane sekwencyjnie w folderach tymczasowych, a wynik atomowo podmieniany po zakończeniu.
    * Brak dostępu do dysku w GUI – użytkownik otrzymuje link do raportu lub statusu w osobnym wątku.

## 6. Integracje zewnętrzne
* **GeoIP:**
    * Pobieranie baz danych w osobnym procesie startowym (pre-bootstrap) z walidacją podpisów.
    * Reużywanie jednego `DatabaseReader` przechowywanego w cache rozgrzewanym w tle.
* **Webhooki / REST:**
    * Klient HTTP z limitami czasowymi (`connectTimeout 500ms`, `readTimeout 2s`) i fallbackiem do kolejek offline.
    * Mechanizm circuit breaker (np. resilience4j) – po 5 błędach przejście w stan otwarty na 30 s.
* **Integracje pluginów przez API:**
    * API eksponuje punkty rozszerzeń (`PunisherX2ApiProvider`) rejestrowane w `services.yml`, aby inne pluginy mogły pobierać instancję w trakcie `onEnable`.
    * Standardowe adaptery (PlaceholderAPI, GUI innych pluginów) korzystają wyłącznie z metod API i lokalnych cache, bez własnych połączeń z bazą.
    * Dla integracji wymagających dodatkowych danych przewidziane są moduły rozszerzeń (`spi`), dostarczane jako oddzielne artefakty zależne od API.

## 7. Monitorowanie i alerty
* **Metryki serwera:** czas ticku, kolejka zadań, wykorzystanie wątków.
* **Metryki cache:** `hitRate`, `evictionCount`, czas ładowania wartości.
* **Alerty bezpieczeństwa:** wykrywanie anomalii w liczbie banów/mute, alerty na próby obejścia cache.
* **Logowanie strukturalne:** każdy moduł loguje opóźnienia > 20 ms z identyfikacją źródła (baza, dysk, sieć).

## 8. Testy i QA
* **Testy jednostkowe:** obejmują scenariusze z cache (walidacja TTL, odświeżania, degradacji w przypadku błędów backendu).
* **Testy integracyjne:** symulacje logowania graczy, otwierania GUI i odświeżania placeholderów, weryfikujące brak blokady wątku głównego (`Thread.currentThread()` != main).
* **Testy wydajnościowe:** narzędzie (np. Gatling/JMH + TestContainers) mierzące czas odpowiedzi repozytoriów i serwisów przy > 1000 RPS.
* **Kontrola bezpieczeństwa:** analiza statyczna (SpotBugs, Sonar) + audyty kryptograficzne (weryfikacja, że klucze nie są logowane).

## 9. Kryteria akceptacyjne przed wydaniem
1. Średni czas ticku przy symulacji 100 graczy < 45 ms; brak pojedynczych spike'ów > 50 ms w oknie 5 minut.
2. `Main-thread blocking detector` (np. Paper Timings) nie raportuje żadnych synchronicznych zapytań SQL ani I/O przez 30 minut testu.
3. Wskaźnik trafień cache w trybie produkcyjnym ≥ 90% dla placeholderów i ≥ 95% dla kar aktywnych.
4. Wydanie potwierdzone raportem bezpieczeństwa (brak krytycznych CVE, poprawne szyfrowanie danych).
5. Dokumentacja architektoniczna i operacyjna zatwierdzona przez zespół (diagramy przepływów, SLA, runbook incydentowy).

## 10. Plan migracji z PunisherX
* **Faza 0 – Proof of Concept:** wdrożenie szkieletu asynchronicznego i cache z Caffeine; porównanie z PunisherX na środowisku testowym.
* **Faza 1 – Migracja danych:** narzędzia migracyjne działają w tle, bez zatrzymywania serwera. Walidacja integralności danych poprzez checksumy.
* **Faza 2 – Wydanie beta:** ograniczona grupa serwerów testowych monitorowana 24/7; analiza logów opóźnień i regresji.
* **Faza 3 – Wydanie produkcyjne:** rollout etapowy; fallback do wersji 1.x przygotowany (snapshot danych, możliwość przełączenia DNS/serwera proxy).
* **Faza 4 – Utrzymanie:** cykliczne przeglądy wydajności, audyty kodu, aktualizacje bibliotek (w tym Caffeine) zgodnie z polityką bezpieczeństwa.

## 11. Organizacja i odpowiedzialności
* **Architekt rozwiązania:** zatwierdza polityki cache, monitoringu i bezpieczeństwa.
* **Zespół backendowy:** implementuje repozytoria asynchroniczne i integracje z usługami zewnętrznymi.
* **Zespół QA:** odpowiada za testy wydajnościowe i scenariusze degradacji.
* **SecOps:** monitoruje alerty bezpieczeństwa, reaguje na incydenty i wykonuje przeglądy konfiguracji szyfrowania.

## 12. Governance API i proces wydawniczy
* **Wersjonowanie:** każda zmiana w interfejsach publicznych wymaga podniesienia numeru wersji API oraz dokumentacji migracyjnej.
* **Kontrakty:** obowiązkowe testy kontraktowe (`Consumer Driven Contracts`) utrzymywane w repozytoriach integrujących się pluginów.
* **Dokumentacja:** generowana z kodu (np. `asciidoc` + `spring-restdocs` dla REST / `javadoc` dla API Java) i publikowana przed wydaniem.
* **Zarządzanie kluczami:** integracje zewnętrzne otrzymują tokeny API z ograniczonym zakresem; ich rotacja jest częścią kwartalnych przeglądów bezpieczeństwa.

---
Ten plan stanowi podstawę dla PunisherX2 i musi być utrzymywany jako dokument żywy, aktualizowany po każdej istotnej zmianie architektury lub procesów.