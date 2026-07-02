# Checklist publikacji na GitHub

## Przed publikacją

- Sprawdź, czy repozytorium zawiera tylko katalog `restaurant-process-simulation`, a nie starsze kopie projektu i archiwalne ZIP-y z katalogu nadrzędnego.
- Nie dodawaj katalogu `out/`, plików `.class`, `.DS_Store` ani lokalnych logów.
- Upewnij się, że zrzuty ekranu w `docs/screenshots/` są aktualne i dobrze pokazują działanie GUI.
- Uruchom `make build` albo `javac -encoding UTF-8 -d out src/*.java`, żeby potwierdzić kompilację.

## Proponowany opis repozytorium

`Multi-process Java/Swing simulation of a restaurant workflow with TCP-based inter-process communication and live resource visualization.`

## Proponowane tematy repozytorium

`java`, `swing`, `operating-systems`, `tcp-sockets`, `process-simulation`, `concurrency`, `academic-project`

## Przykładowe komendy publikacji

```bash
cd restaurant-process-simulation
git init
git add .
git commit -m "Prepare restaurant process simulation for GitHub"
git branch -M main
git remote add origin <URL_DO_REPOZYTORIUM>
git push -u origin main
```

## Co warto pokazać w rozmowie rekrutacyjnej

- Dlaczego każdy klient działa jako osobny proces, a nie tylko jako wątek.
- Jak serwer synchronizuje dostęp do współdzielonego stanu.
- Jak działa tekstowy protokół TCP i dlaczego jest prosty do debugowania.
- Jak GUI subskrybuje migawki stanu zamiast samodzielnie modyfikować model.
- Jakie ograniczenia ma obecna wersja i co byłoby kolejnym krokiem produkcyjnym.
