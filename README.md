# Weather Station 天气站

A classroom weather station for Grade 3 science (NGSS 3-ESS2-1: *represent data in tables and graphical displays to describe typical weather conditions*).

- A **micro:bit V2** sits on the **DFRobot Micro:Environment Science V3.0 board** by a window or outside.
- Every 10 seconds it sends temperature, humidity, air pressure, UV and light over **Bluetooth**.
- The **Weather Station app** on the classroom MAXHUB listens in the background and saves one reading every 10 minutes.
- No Wi-Fi, no cables and nothing to move. After setup, teachers just open the app.

The app has three pages, in English and Chinese:

| Page | What students do |
|---|---|
| **Now 现在** | See temperature, humidity, UV and light, and pick today's sky and wind (calm, light breeze, windy, very windy) |
| **Table 表格** | Read daily sky, wind, highs, lows and humidity, or any day hour by hour, and copy them into their own tables |
| **Graph 图表** | Choose what to graph, how many days and the scale (1, 2, 5 or 10 per line). Bars can be shown, shown with numbers, or left blank so students draw them with the MAXHUB pen |
| **Climate 气候** | **Is this normal?** compares our last week or month with a usual month in Shanghai · **Around the world** shows a world map of the chosen cities (equator, continents, oceans, this month's value) and their monthly climate graphs on the same scale · **Our year** builds our own climate graph, August to July · **Choose cities** lets each class tap cities on the map or search about 275 cities (English, Chinese or country) and pick up to 9, plus its home city |

---

## 1. Set up the micro:bit (once)

1. Go to **makecode.microbit.org** → **New Project**.
2. Switch to **JavaScript** at the top and replace everything with the contents of [`microbit/main.ts`](microbit/main.ts).
3. **Extensions** → add **bluetooth**. If it asks to remove **radio**, say yes.
4. Gear icon → **Project Settings** → choose **No Pairing Required** → Save.
5. **Download** and copy the file to the micro:bit V2.
6. Plug the micro:bit into the weather board. It shows **✗** for 3 seconds, then its lights turn off to save battery. Press **button A** any time to check: **✓** = connected to the app, **✗** = waiting.

> Don't add the DFRobot "Environment Science" extension. It needs radio, which can't run alongside Bluetooth. The program reads the board's sensors directly.

## 2. Install the app on the MAXHUB (once)

1. On the MAXHUB, open Chrome and go to
   **https://github.com/tspraul/weather-station/releases/latest/download/weather-station.apk**
2. Open the downloaded file and tap **Install**. If asked, allow installing from Chrome.
3. Open **Weather Station** and tap **Allow** for Bluetooth (and notifications, if asked).
4. **First test:** hold the board near the panel. Within a minute the status turns green (**Connected 已连接**) and pressing button A on the micro:bit shows ✓.
5. Move the board to its window or outside spot and check the status stays green.

After that the app remembers this micro:bit, keeps listening in the background and starts again on its own when the panel is switched on.

**Updating:** download the same link again and install. Saved readings are kept.

## 3. Placing the board

- **Power:** a USB power bank into the micro:bit, or the board's battery input. A 10,000 mAh bank lasts several days. A USB wall plug by the window is best.
- **Shade:** keep it out of direct sun, or the temperature reads high. A vented box or a plastic plant-pot "hat" works.
- **Rain:** outside, keep it under an overhang or in a vented box with the openings facing down.
- **Distance:** Bluetooth reaches roughly 10 m indoors; walls and metal window frames shorten that. If the status keeps saying "Reconnecting", move the board closer.

## Troubleshooting

| What you see | Try this |
|---|---|
| *Looking for the board…* | Check the micro:bit has power (press button A: it should show ✗ or ✓). Make sure it is running the weather program (step 1). |
| *Bluetooth is off* | Tap **Turn on Bluetooth** on the Now page. |
| *Needs Bluetooth permission* | Tap **Allow Bluetooth**, or Android Settings → Apps → Weather Station → Permissions. |
| On older Android, still searching | Turn on **Location** in Android settings. Android 11 and older needs it for Bluetooth searching. |
| Wrong micro:bit connected | Tap **Use a different micro:bit** at the bottom of the app. |
| Temperature too high in the afternoon | The board is in the sun. Move it to shade. |

## How it works (for whoever maintains it)

- The map (`app/src/main/assets/worldmap.js`) is coastlines only, from Natural Earth (public domain), with no country borders.
- Climate averages live in `app/src/main/assets/climate.js`: the curated cities in `tools/cities.csv` plus every capital and city over 1.5 million people that has a weather station with long-term averages (`tools/build_city_list.py`). To add a city, add a row to `tools/cities.csv`, then run the **Climate data** action on GitHub (Actions → Climate data → Run workflow). It looks up the nearest weather station's 1991–2020 averages (Meteostat) and commits the updated file.

- `microbit/main.ts` reads the board over I2C (write `8` to `0x10`, read 22 bytes, same as DFRobot's extension) and sends `T:23.5,H:48.2,P:101.3,U:0.12,L:530` over the Bluetooth UART service.
- `app/` is a small Android app (Java, no libraries). `WeatherService` finds the micro:bit, connects and reconnects, and passes lines to `WeatherStore`, which saves `log.csv` every 10 minutes. The screens are one web page (`app/src/main/assets/index.html`) talking to Java through `window.Weather`.
- Opening `index.html` in a normal browser shows practice data, so you can work on the screens without a panel.
- Every push to `main` builds the APK on GitHub Actions and replaces the **latest** release. The signing key (`app/weather.keystore`, password `weatherstation`) is in the repo on purpose, so updates install over the old version.
