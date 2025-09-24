# Wytyczne dla agenta AI

Ten plik definiuje zasady obowiązujące dla całego repozytorium. Wszystkie nowe zadania powinny być realizowane w oparciu o dokument `docs/PunisherX2-plan.md`, który stanowi nadrzędny plan architektoniczny i operacyjny projektu PunisherX2. Odstępstwa od tego planu wymagają jego wcześniejszej aktualizacji.

## Wersje i narzędzia
- Minimalna wersja Javy: **21**.
- Kotlin: **2.2.20**.
- Gradle: **9.1.0** (używaj wrappera `./gradlew`).
- Wtyczka `com.gradleup.shadow`: **9.1.0**.
- Preferuj **najnowsze stabilne** wersje bibliotek, pamiętając o analizie SCA w CI.
- Docelowa platforma: Paper/Folia **1.21.8+** z zachowaniem kompatybilności od 1.20.6. Unikaj API Spigot; jeśli brak odpowiednika w Paper, dopiero wtedy używaj Bukkit.
- Przed uruchomieniem poleceń Gradle nadaj uprawnienia wykonywalne: `chmod +x gradlew`.
- Przy budowaniu i testach używaj `--console=plain`, aby uniknąć problemów z limitem długości linii wyjścia.

## Standardy ogólne
- Kod musi być nowoczesny, bezpieczny oraz udokumentowany (KDoc, README, pełna dokumentacja w katalogu `docs/`).
- Przestrzegaj planu wydajności i bezpieczeństwa z `docs/PunisherX2-plan.md`, ze szczególnym naciskiem na eliminację blokowania głównego wątku i wszechobecne cache Caffeine 3.2.2.
- Stosuj asynchroniczne wzorce projektowe zgodne z opisem w planie, zwłaszcza przy dostępie do bazy danych, sieci i operacjach dyskowych.
- Wszelkie zmiany muszą być testowane przy pomocy `./gradlew test` (z wrappera) oraz innych komend wymaganych przez instrukcje w `AGENTS.md` znajdujących się w podkatalogach.
- Dokumentuj każdą nową funkcjonalność i aktualizacje planu w katalogu `docs/`.

## Dokumentacja i komunikacja
- Zachowuj zgodność z architekturą API PunisherX2: publiczne moduły API muszą być asynchroniczne i oparte o cache.
- Główne decyzje architektoniczne dokumentuj w `docs/PunisherX2-plan.md` lub powiązanych plikach.
- Jeśli zadanie wymaga modyfikacji planu, najpierw zaktualizuj dokument i dopiero potem implementuj zmiany.

Stosowanie się do powyższych zaleceń jest obowiązkowe dla wszystkich działań w repozytorium.