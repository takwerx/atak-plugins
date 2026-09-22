# Contributing to Comms

Comms puts mountaintop radio sites on the ATAK map: where they are, which nets
they carry, and the tone that opens each one. It is free software under the
[GNU Affero General Public License v3.0 or later](LICENSE), with an
[additional permission](LICENSE-EXCEPTION.md) covering the TAK SDK.

Contributions are welcome. The most valuable one is usually **data**, and it
needs no Java, no Android SDK and no plugin build.

---

## Contributing data — sites, nets and tones

Every site and net in Comms comes from a CSV row file that
`tools/build_comms_catalog.py` turns into the `catalog.json` the plugin reads.
Adding a state, an agency or a single corrected tone is a row, not code.

### The format

`tools/sites.csv` is the template; its comment block documents the columns. One
row per site per net.

| Column | What it is |
|---|---|
| `site` | The site's name, as the people who use it say it |
| `state` | **Required.** Two letters |
| `county` | Filled from TIGER when blank |
| `lat`, `lon` | Decimal degrees. **Supply these outside California** — see below |
| `elev_m` | Ground elevation. Filled from USGS when blank |
| `ant_m` | Antenna height above ground. 10 m assumed when blank |
| `net` | The net's identifier: a MACS 441-1 designator where one exists, otherwise whatever the local plan calls the channel |
| `net_name` | The readable name. Where `net` is a local string, this is what makes it legible |
| `rx`, `tx` | The **mobile's** view: `rx` is what you listen on (the repeater's output), `tx` is what you transmit on |
| `rx_tone`, `tx_tone` | **This site's** tones, in Hz |
| `net_rx_tone`, `net_tx_tone` | The **net-wide** pair. A repeater net has one pair and a different tone at every site — these two are not the same thing as the two above, and mixing them up is the easiest mistake to make here |
| `callsign` | The FCC license identifier for the station |
| `agency` | The filter bucket the site appears under in the plugin — `CAL FIRE`, `Cal OES`, `ARES`, whatever fits. A new string makes a new bucket; no code change needed |
| `area` | A boundary to place a named site inside: `USFS:<forest name>`, `NPS:<unit code>`, or `CO:<county>` |
| `notes` | Anything an operator needs that no other column carries |
| `source` | The plan, page or record you read it from |
| `as_of` | The date you read it |

`source` and `as_of` matter more than anything else on the row. They are what
lets someone judge a channel that looks wrong two years from now, and a row
without them is very hard to defend.

### Outside California, supply coordinates

Name-based placement leans on California GIS layers, so a site named but not
located will usually not be found elsewhere. A row carrying `lat`/`lon`
sidesteps all of it and is placed exactly where you put it.

### Test it on your own device before you send it

The plugin reads `atak/tools/comms/local.json` as a complete override of the
published catalog:

```bash
python3 tools/build_comms_catalog.py --rows your-rows.csv --out local.json
adb push local.json /sdcard/atak/tools/comms/local.json
```

Restart ATAK and your sites are on the map. The builder fetches the public GIS
layers itself, so what you see is the whole catalog plus your rows — not an
island. There is also an unexposed `comms_base_url` preference if you would
rather serve a catalog from your own host.

### Where row files live

Public row files go in `tools/rows/<st>-<what>.csv` — for example
`tools/rows/pa-auxcomm.csv` — and the builder picks them up. A data contribution
is a pull request with one CSV in it.

Some source material cannot be committed here: California's Region 5 radio guide
is CUI, so the catalog TAKWERX publishes is built partly from files that stay off
GitHub. That is why `tools/rows/` may look emptier than the published catalog.

### What may be published

Anything in a row file ends up in a catalog served to every user, so it has to be
something we are allowed to serve.

- **Good:** a county or state plan, an agency's own published list, a
  coordinating body's record, a public GIS layer. Public records, with a citable
  source and a date.
- **Check first:** aggregator and hobbyist databases. Several have terms
  forbidding redistribution even where the underlying facts are public.
- **Never:** anything marked CUI, FOUO or law-enforcement sensitive, encryption
  keys, and any channel a licensee has asked not be published.

If you are unsure, open an issue and describe the source before doing the work.

---

## Contributing code

Open an issue first for anything larger than a bug fix.

### Ground rules

- **Nothing may be California-only.** Comms started as a California catalog and
  parts of the builder still assume it; every one of those is a bug, not a
  design. New code takes the state from the data.
- **Prefer the stable `gov.tak.api.*` classes** over `com.atakmap.android.*`
  internals wherever an equivalent exists. ATAK obfuscates its internals and the
  mapping changes between releases, so this decides whether the plugin survives
  an ATAK upgrade.
- **Dialogs and toasts use the MapView context, never the plugin context.** The
  plugin context throws `BadTokenException` and takes ATAK down with it.
- **No `Spinner`.** Its dropdown is built from the inflating context and hits the
  same crash. Use a button that opens an `AlertDialog` with
  `setSingleChoiceItems`.
- **Test the release build, not just debug.** `assembleCivRelease` runs minify
  and proguard; lambdas and reflection break there and nowhere else.
- **Distances follow ATAK's own unit preference**, never a hardcoded unit.

### License headers on new files

Every new source file TAKWERX or a contributor writes gets an SPDX identifier:

```java
// SPDX-License-Identifier: AGPL-3.0-or-later
// Comms — an ATAK plugin
// Copyright (C) 2026 Andreas Johansson (TAKWERX)
//
// This program is free software: you can redistribute it and/or modify it under
// the terms of the GNU Affero General Public License as published by the Free
// Software Foundation, either version 3 of the License, or (at your option) any
// later version, with the additional permission in LICENSE-EXCEPTION.md.
//
// This program is distributed in the hope that it will be useful, but WITHOUT ANY
// WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
// PARTICULAR PURPOSE. See the GNU Affero General Public License for more details.
```

Use the `#` comment form for Python and shell. Do **not** add this header to
files listed under "Provenance" in [LICENSE-EXCEPTION.md](LICENSE-EXCEPTION.md) —
those are not ours to relicense.

### Vendoring third-party code

Must be compatible with AGPL-3.0-or-later. MIT, BSD, ISC and Apache-2.0 are
fine; GPLv2-**only** is not. Pin to a release tag and commit SHA, never a moving
branch, and name the license in the pull request.

---

## Licensing of contributions

By submitting a contribution you agree to the terms in [CLA.md](CLA.md).

In short: you keep the copyright in your work, and you grant TAKWERX a license
broad enough to ship it as part of Comms. This is what lets the project be
enforced as a whole — the AGPL's promise that Comms stays open is only meaningful
if there is a single party with standing to enforce it.

Sign by adding a `Signed-off-by:` line to your commits:

```bash
git commit -s -m "your message"
```

That line certifies you wrote the contribution, or otherwise have the right to
submit it under the AGPL, and that you accept [CLA.md](CLA.md).

**A bug report, a reproduction or a field correction needs no agreement at all,**
and is often the more useful contribution anyway. "This tone is wrong and here is
the plan that says so" is worth more than a speculative patch.

---

## Reporting a security issue

Do not open a public issue. Use GitHub's private vulnerability reporting on this
repository — **Security → Report a vulnerability** — and give a reasonable window
for a fix before disclosure.

---

## Where this is built

Comms is developed in [takwerx/atak-plugins](https://github.com/takwerx/atak-plugins)
alongside the other TAKWERX plugins, and published here by subtree push. Pull
requests against this repository are the right place to send changes; they are
merged back upstream.
