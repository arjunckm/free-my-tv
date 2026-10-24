# arjunckm/free-my-tv: what this fork changes

A fork of [simbasjungles/free-my-tv](https://github.com/simbasjungles/free-my-tv) set up for a Xiaomi Mi TV 4A 40" (2021, Android TV 9, 1 GB RAM) in Bengaluru.
Changes are kept small so upstream updates merge cleanly.

| Change | Where |
|---|---|
| JioHotstar, SonyLIV and ZEE5 on the shelf (after Netflix, YouTube, Prime Video) | `HomeApps.SHELF`, `AndroidManifest.xml` `<queries>` |
| SonyLIV and ZEE5 as sources for the trending picks (JioHotstar was already there) | `Recs.SERVICES`, `Recs.SERVICE_NAME` |
| Bengaluru settings: region IN, weather for Bengaluru in °C, no crypto prices, Google Assistant off | `config.bangalore.properties` |
| `build.sh` starts `config.properties` from `config.bangalore.properties` | `build.sh` |

## Build and install

1. `./build.sh` once (creates `config.properties` from the Bengaluru file).
2. Put your own TMDB key after `tmdb_key=` in `config.properties` (never commit it), then `./build.sh` again.
3. `adb connect 192.168.1.7:5555` and `adb install -r FreeMyTV.apk`.
4. On the TV: Free My TV → Home Screen → Free My TV. *Original* undoes everything.

Keep `freemytv.keystore` safe and out of git: every update must be signed with it.

## Getting upstream updates

```bash
git remote add upstream https://github.com/simbasjungles/free-my-tv   # once
git fetch upstream && git merge upstream/main
```
