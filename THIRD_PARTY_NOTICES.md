# Third-party notices

## FlClashX v0.5.7 / Mihomo core

GilrTano VPN's **ПОДПИСКА** mode incorporates the Android gomobile core from
[pluralplay/FlClashX](https://github.com/pluralplay/FlClashX), tag
[`v0.5.7`](https://github.com/pluralplay/FlClashX/tree/v0.5.7).

- License: GNU General Public License, version 3 (GPL-3.0).
- Upstream source corresponding to the incorporated core: https://github.com/pluralplay/FlClashX/tree/v0.5.7
- The upstream license text is copied into the APK at
  `app/src/main/assets/licenses/FLCLASHX-GPL-3.0.txt` during the CI build.
- The build workflow publishes the FlClashX ARM64 AAR and upstream license/README
  as build artifacts alongside the GilrTano APK.

The FlClashX core is built from source at the pinned upstream tag. This project
does not claim ownership of FlClashX or Mihomo. When redistributing binaries,
retain the GPL-3.0 notices and make the corresponding source available under
the GPL-3.0 terms.
