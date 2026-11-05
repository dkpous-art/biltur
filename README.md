# Bilture – Android

En enkel Android-app til at registrere bilture med GPS.

## Funktioner

- Start/Stop
- GPS-baseret distance
- Tid
- Gennemsnitlig km/t
- Lokal historik
- CSV-eksport til `Download`
- "Eksportér & tøm"

## Krav

- Android Studio Quail 4 eller nyere
- JDK 17
- Android SDK 36
- Android-telefon med Android 16 anbefales

## Installation

1. Pak projektet ud.
2. Åbn mappen `Gaatur` i Android Studio.
3. Lad Gradle synkronisere.
4. Tilslut Android-telefonen med USB debugging.
5. Tryk Run.

Første gang der trykkes Start, spørger Android om GPS-tilladelse.

## Bemærkninger om GPS

Telefonen kan få en mindre eller større afvigelse i distance, især tæt på bygninger.
Appen ignorerer GPS-positioner med meget dårlig nøjagtighed og åbenlyse spring.

Hvis brugeren kun giver "omtrentlig placering", bliver distancemålingen mindre præcis.

## tilføjet android auto launcher mulighed

Appen kan tilføjes aa launcher hvis den installeres med KingInstaller uden om play store
