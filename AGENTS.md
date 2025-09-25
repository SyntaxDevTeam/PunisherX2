# Wytyczne dla agentów w repozytorium

## Preferowane biblioteki i technologie
- **Buforowanie:** Stosuj `com.github.ben-manes.caffeine:caffeine:3.2.2` do implementacji pamięci podręcznej, chyba że istnieje nowsza wersja zapewniająca udokumentowane zyski wydajnościowe lub stabilnościowe.
- **Formaty konfiguracji:** Do obsługi YAML używaj `org.yaml:snakeyaml:2.5`, a do serializacji i deserializacji JSON korzystaj z `com.google.code.gson:gson:2.13.2`.
- **Dane zewnętrzne:** W przypadku geolokalizacji posługuj się biblioteką `com.maxmind.geoip2:geoip2:4.4.0`.

## Zarządzanie zależnościami
- Do obsługi mechanizmów rozwiązywania artefaktów Maven używaj `org.eclipse.aether:aether-api:1.1.0`.
- W odniesieniu do modułów wewnętrznych korzystaj z `pl.syntaxdevteam:core:1.2.4-SNAPSHOT`.
- Unikaj wprowadzania nowych bibliotek, jeżeli istnieją już sprawdzone odpowiedniki w projekcie. Wymiana na nowsze wersje powinna być poparta mierzalnymi korzyściami (wydajność, bezpieczeństwo, stabilność) i odpowiednio udokumentowana.

## Dobre praktyki
- Zachowuj spójność technologiczną z istniejącą bazą kodu i preferuj ponowne użycie rozwiązań już obecnych w projekcie.
- Przed dodaniem zależności upewnij się, że nie powiela ona funkcjonalności innego, aktualnie używanego komponentu.
- Każdą istotną zmianę w stosie technologicznym dokumentuj w opisach commitów lub przeglądach kodu, wskazując powód oraz potencjalny wpływ na projekt.
