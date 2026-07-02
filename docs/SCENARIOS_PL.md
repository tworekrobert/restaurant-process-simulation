# Tryby działania

Projekt ma kilka gotowych trybów uruchomienia. Tryb zmienia parametry symulacji: liczbę klientów, kelnerów, stoliki, dostępność zasobów, tempo odnawiania oraz liczbę cykli klienta.

## Jak uruchomić

```bash
./run.sh --list-modes
./run.sh NORMAL
./run.sh RUSH_HOUR
./run.sh LIMITED_RESOURCES
./run.sh NO_RESTOCK
./run.sh SHORT_DEMO
```

Alternatywnie przez Makefile:

```bash
make run-normal
make run-rush
make run-limited
make run-no-restock
make run-short
```

## Dostępne tryby

| Tryb | Opis | Kiedy użyć |
| --- | --- | --- |
| `NORMAL` | Zbalansowana domyślna symulacja. | Standardowy screen i prezentacja projektu. |
| `RUSH_HOUR` | Więcej klientów, większa sala i szybsze odnawianie zasobów. | Dynamiczny screen z dużą liczbą procesów. |
| `LIMITED_RESOURCES` | Mniej kelnerów, mniej stolików i wolniejsze odnawianie zasobów. | Pokazanie kolejek i konkurencji o zasoby. |
| `NO_RESTOCK` | Restocker jest wyłączony, więc zasoby stopniowo się wyczerpują. | Pokazanie zachowania systemu bez odnawiania zasobów. |
| `SHORT_DEMO` | Każdy klient wykonuje jeden cykl. | Szybka prezentacja bez długiego działania programu. |

## Nadpisywanie parametrów

Po nazwie trybu można nadal podać parametry pozycyjne. Przykład:

```bash
./run.sh RUSH_HOUR 40
```

To uruchamia tryb `RUSH_HOUR`, ale zmienia liczbę klientów na `40`.
