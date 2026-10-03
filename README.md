# CandyCMD

English · [Русский](README.ru.md)

An unofficial Android app for controlling the **Candy RapidÓ RO4 H7A1TCEX-07** dryer from your phone over home Wi-Fi.

## Why CandyCMD

The official Candy simply-Fi app stopped working with this dryer, so CandyCMD was created to keep controlling it directly over the local network.

The project was developed with the help of the AI coding agents Claude Code and Codex.

## What you can do

- See the dryer’s current state, remaining drying time and estimated finish time.
- Choose programs from the dryer’s panel and additional drying recipes.
- Adjust dryness, timed drying and Easy Iron where the selected program supports them.
- Start drying immediately or schedule a start up to 24 hours ahead.
- Pause, resume or cancel drying, and cancel a scheduled start.
- Save programs and settings as favorites for quick access.
- Choose a light or dark theme, or follow your phone’s appearance settings.

## Screenshots

The app interface is currently in Russian. These screenshots show sample dryer states.

<table>
  <tr>
    <th>Connect your dryer</th>
    <th>Follow a drying cycle</th>
    <th>Choose a program</th>
  </tr>
  <tr>
    <td><img src="assets/screenshots/connection.png" alt="Finding a dryer on home Wi-Fi" width="240"></td>
    <td><img src="assets/screenshots/drying.png" alt="Drying status and remaining time" width="240"></td>
    <td><img src="assets/screenshots/programs.png" alt="Drying program selection" width="240"></td>
  </tr>
  <tr>
    <th>Set drying options</th>
    <th>Schedule a start</th>
    <th>Review and start</th>
  </tr>
  <tr>
    <td><img src="assets/screenshots/options.png" alt="Dryness and Easy Iron options" width="240"></td>
    <td><img src="assets/screenshots/delay.png" alt="Delayed start settings" width="240"></td>
    <td><img src="assets/screenshots/confirmation.png" alt="Program summary before starting" width="240"></td>
  </tr>
</table>

## Getting started

You need an Android phone running **Android 8.0 or later** and a **Candy RO4 H7A1TCEX-07** dryer already connected to your home Wi-Fi. Follow the dryer’s manual for its initial Wi-Fi setup.

1. Download the APK from [Releases](../../releases) or [build it from source](#building-from-source), then install it on your phone.
2. Connect your phone to the same Wi-Fi network as the dryer.
3. Turn on the dryer and turn its dial to **Wi-Fi**.
4. Open CandyCMD, allow local network access if prompted, and tap **«Найти сушилку»** (Find dryer).
5. Select your dryer, choose a program and its options, then review and confirm the start.

CandyCMD does not require an account. Everyday control of an already connected dryer works over home Wi-Fi without internet access.

## Compatibility and limits

- Support is specific to **Candy RO4 H7A1TCEX-07**. Compatibility with other models is not confirmed.
- Available settings depend on the program. Additional recipes may use fixed settings, and some functions require the dryer’s control panel.
- Automatic drying times are estimates: the actual duration depends on the load and its moisture.
- Control is available while your phone and dryer are on the same local network.

## Building from source

Requirements: JDK 25, Android SDK Platform 37 and Build Tools 36.0.0.

```sh
./gradlew :androidApp:assembleDebug
```

The APK is written to `androidApp/build/outputs/apk/debug/`. Changes are listed in the [changelog](CHANGELOG.md).

## License

CandyCMD is released under the [MIT License](LICENSE).

CandyCMD is an independent project and is not affiliated with Candy or Haier.
