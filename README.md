# Mój Dom

Aplikacja na Androida w stylu Google Home, która łączy w jednym miejscu urządzenia z:

| Źródło | Jak się łączy | Co działa |
|---|---|---|
| **SwitchBot** | chmura (Token + Secret) | światła, gniazdka, Bot, zasłony, rolety, czujniki, piloty IR |
| **Govee** | chmura (klucz API) | światła i taśmy LED (kolor, jasność), gniazdka, termometry |
| **eWeLink / Sonoff** | chmura (własna aplikacja na dev.ewelink.cc + logowanie) | przełączniki (też wielokanałowe), ściemniacze, czujniki |
| **Tapo** | lokalnie w domowym Wi-Fi (konto TP-Link) | gniazdka, żarówki (kolor, jasność) |
| **Home Assistant** | adres + token (lokalnie lub Nabu Casa) | światła, przełączniki, rolety, klimatyzacja, media, czujniki temp./wilg./ruchu/drzwi, sceny, skrypty, automatyzacje HA; obszary jako pokoje |
| **Android TV / Google TV** | lokalnie, parowanie kodem z ekranu | włącz/wyłącz, głośność, strzałki, Home/Wstecz, otwieranie aplikacji |

## Instalacja

Najnowszy plik APK: **https://github.com/wronglogin/smart-home/releases/latest/download/MojDom.apk**

Każda zmiana w repozytorium buduje nową wersję automatycznie (zakładka Actions → Releases).
Nowa wersja instaluje się na starej – konta, pokoje i ulubione zostają.

## Skąd wziąć klucze

- **SwitchBot:** aplikacja SwitchBot → Profil → Preferencje → 10× dotknij „Wersja aplikacji” → Opcje dewelopera → Token i Secret.
- **Govee:** Govee Home → Profil → Ustawienia → Apply for API Key (klucz przychodzi e-mailem).
- **eWeLink:** zaloguj się na https://dev.ewelink.cc, utwórz aplikację, jako Redirect URL wpisz
  `https://wronglogin.github.io/smart-home/callback` (albo własny – musi być taki sam w aplikacji).
  Skopiuj APPID i APP SECRET do Ustawień i dotknij „Zaloguj przez eWeLink”.
  Darmowa aplikacja deweloperska jest ważna rok, potem trzeba ją odnowić.
- **Tapo:** e-mail i hasło konta TP-Link. Telefon musi być w tym samym Wi-Fi co urządzenia.
- **Telewizor:** Ustawienia → Szukaj telewizorów (albo wpisz IP) → wpisz kod z ekranu TV.

## Obsługa

- Dotknięcie kafelka włącza/wyłącza urządzenie, przytrzymanie otwiera szczegóły
  (jasność, kolor, pozycja rolety, pilot TV, pokój, nazwa, ulubione).
- Pokoje i nazwy ustawiasz w szczegółach urządzenia – są zapisane tylko w telefonie.

## Ograniczenia

- Tapo i telewizory działają tylko w domowej sieci.
- Część nowych firmware Tapo zmieniła logowanie lokalne – takie urządzenie pokaże się jako „Offline”.
- Telewizor da się włączyć tylko wtedy, gdy w czuwaniu zostaje w sieci.
