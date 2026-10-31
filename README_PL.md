# PlayerKits2 – wersja z oknem „Odbierz przedmioty”

Zmodyfikowany PlayerKits2 1.24.2 (licencja MIT, autor oryginału: Ajneb97).

## Jak działa
1. `/kit` – główne GUI z zestawami (ustawiasz jak zwykle: `/kit edit <nazwa>`).
2. Klik w zestaw – strona zestawu z jego zawartością i przyciskiem:
   - zielony (lime dye) „Odbierz przedmioty z zestawu!” – można odebrać,
   - pomarańczowy (orange dye) z czasem – wszystko odebrane, trwa cooldown.
3. Klik w zielony – okno „Odbierz przedmioty”, z którego wyjmujesz przedmioty ręcznie.
4. Po wyjęciu wszystkiego: napis „Odebrano wszystkie przedmioty!” i start cooldownu.
   Jeśli zamkniesz okno wcześniej, reszta czeka (też po restarcie, plik `pending_claims.yml`).

Wszystkie ustawienia (tytuł, slot i wygląd przycisków, komunikat) są w `config.yml` w sekcji `claim_gui`.
`claim_in_gui: false` przywraca stare zachowanie.
