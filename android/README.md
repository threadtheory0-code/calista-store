# android/ — the Kotlin source, screen by screen

Kotlin + Jetpack Compose, single activity, no navigation library, no Room yet.
Nine screens, one file each. Min SDK 24, target 35.

| File | Screen | Talks to |
| --- | --- | --- |
| `MainActivity.kt` | shell: top bar, bottom rail, toast | — |
| `ScreenConnect.kt` | 1 Connect store | stores base URL + ADMIN_TOKEN |
| `ScreenToday.kt` | 2 Today: KPIs, 7-day bars, pending, low stock | `GET /stats`, `GET /orders` |
| `ScreenOrders.kt` | 3 Orders: filter chips + search | `GET /orders` |
| `ScreenOrderDetail.kt` | 4 Order detail: timeline, WhatsApp, PostEx booking | `PATCH /orders/:id`, `POST /courier/postex` |
| `ScreenConfirmQueue.kt` | 5 Confirm queue | `POST /orders/:id/whatsapp` |
| `ScreenProducts.kt` | 6 Products: stock stepper, Live/Hidden | `GET/PATCH /products` |
| `ScreenReports.kt` | 7 Reports: range, best sellers, COD | `GET /stats` |
| `ScreenSettings.kt` | 8 Settings + 9 Store connections | — |

Supporting files: `Api.kt` (OkHttp + org.json, all endpoints and models),
`AppState.kt` (the single state holder and every action), `Theme.kt` (Calista's
black-and-gold tokens), `Ui.kt` (Blueprint frame, gold button, status tags).

## Deploy

Push this repo — `.github/workflows/android.yml` builds `app-debug.apk` and
attaches it to the Actions run as the artifact **calista-merchant-apk**. No local
Android SDK needed. Details in `GET-THE-APK.md`.

## Endpoints it expects

All under `/api/admin/`, all with `Authorization: Bearer <ADMIN_TOKEN>`. The Worker
code is in `SYNC-SETUP.md`. Products (`GET /api/admin/products`) is the one endpoint
not written out there — copy the orders handler and select from `products`.

## Deliberately not here yet

Room offline queue, FCM push + chime, CameraX product upload, ML Kit scanner. Each is
additive; none changes the screens above.
