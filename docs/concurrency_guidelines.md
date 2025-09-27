# Wytyczne dotyczące współbieżności w PunisherX

## Dlaczego `CompletableFuture` zamiast asynchronicznego `BukkitScheduler`

`CompletableFuture` stanowi przenośny i niezależny od silnika serwera mechanizm pracy asynchronicznej. W PunisherX pozwala
nam realizować złożone sekwencje operacji (np. zapytania do bazy, transformacje wyników, łączenie równoległych źródeł danych)
bez ciasnego sprzężenia z implementacją wątku dostarczoną przez Paper/Bukkit. Dzięki temu:

1. **Lepsza kompozycja zadań** – metody takie jak `thenCompose`, `thenCombine` czy `exceptionally` ułatwiają budowanie
   pipeline'ów asynchronicznych i centralne zarządzanie błędami. Scheduler Bukkit oferuje jedynie uruchomienie `Runnable`
   w tle, przez co każda koordynacja i obsługa wyjątków musi być napisana ręcznie.
2. **Testowalność i abstrakcja** – `CompletableFuture` możemy wstrzykiwać do komponentów domenowych bez konieczności
   posiadania środowiska serwerowego. Zadania napisane jako future'y da się łatwo mockować i wykonywać w testach jednostkowych,
   podczas gdy `BukkitScheduler` wymaga aktywnego serwera.
3. **Integracja z resztą kodu Javy** – API future'ów jest rozpoznawalne w całym ekosystemie JVM. Możemy je łączyć z
   istniejącymi bibliotekami oraz wykorzystywać standardowe narzędzia profilujące/monitorujące. Scheduler Bukkit pozostaje
   rozwiązaniem specyficznym dla jednego silnika.
4. **Kontrola wątków** – poprzez własne `ExecutorService` możemy decydować, w ilu wątkach i jakiego typu uruchamiamy zadania
   (np. thread pool o stałej liczbie wątków, priorytety). Bukkit uruchamia zadania asynchroniczne na globalnej puli wątków,
   co utrudnia zarządzanie obciążeniem oraz integrację z innymi komponentami wymagającymi dedykowanych executorów.
5. **Możliwość łączenia z operacjami synchronicznymi** – future'y pozwalają łatwo powrócić na wątek główny poprzez
   `thenAcceptAsync(..., Bukkit.getScheduler()::runTask)` lub analogiczne adaptery. Dzięki temu zachowujemy bezpieczeństwo
   wątkowe, jednocześnie kontrolując, które fragmenty kodu wracają na główną pętlę serwera.

## Kiedy warto użyć schedulera Bukkit

Asynchroniczny scheduler nadal ma swoje miejsce: świetnie sprawdza się przy krótkich zadaniach, które muszą wykonać się
w określonym momencie cyklu ticków (np. opóźnione wysłanie pakietu, animacja, interakcje zależne od logiki gry). Jeżeli
potrzebujemy dokładnego powiązania z tickami lub prostego jednorazowego uruchomienia `Runnable`, scheduler będzie
wystarczający.

## Rekomendowane podejście w PunisherX

1. Operacje I/O oraz bardziej rozbudowane przepływy danych implementuj jako `CompletableFuture` lub abstrakcje na nich
   oparte – zyskujemy spójny model pracy i kontrolę nad błędami.
2. Przygotuj adaptery, które w razie potrzeby powrócą na wątek główny (np. `thenRunAsync` z `Bukkit.getScheduler()::runTask`).
3. Scheduler Bukkit traktuj jako narzędzie do interakcji ze światem gry, tam gdzie wymagana jest integracja z tickami.
4. Nie mieszaj losowo obu podejść w jednym fragmencie kodu – określ jasne granice odpowiedzialności między logiką domenową
   (future'y) a logiką serwerową (scheduler i API Paper/Bukkit).

Takie podejście pozwala utrzymać kod PunisherX modularny, testowalny i odporny na zmiany implementacji serwera.
