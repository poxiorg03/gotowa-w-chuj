# Dzialka 1.0.0-beta — zbiorcza wersja testowa

Projekt zawiera połączone funkcje wcześniejszej rozbudowanej gałęzi EasyMinePlots, przeniesione pod nazwę Dzialka. **Nie jest napisany od zera**. Nie potwierdzono kompilacji ani działania na serwerze. Nie używaj na produkcji bez testów i kopii zapasowej.

## Instalacja
1. Wgraj **zawartość** ZIP do katalogu głównego repozytorium GitHub (nie sam ZIP).
2. Uruchom Actions -> Build Dzialka -> Run workflow.
3. Jeśli build jest zielony, pobierz artefakt Dzialka-JAR, rozpakuj i wgraj JAR do plugins.
4. Wymagane: Paper 1.21.11, Java 21; dla ekonomii Vault + provider ekonomii (np. EssentialsX Economy).
5. Najpierw testuj na osobnym serwerze. Przed wymianą pluginu wykonaj kopię katalogu plugins i danych.

## Uwaga
Zawartość implementuje wiele systemów, ale część funkcji może być niekompletna lub błędna; nie została zweryfikowana kompilacja, wydajność, bezpieczeństwo, integracja paczki zasobów ani wszystkie scenariusze GUI. W szczególności nie gwarantuje to poprawnej migracji danych ze starego pluginu. Ceny i poziomy konfiguruj w config.yml.
