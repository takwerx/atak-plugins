# Signal DF — User Guide

Signal DF puts direction-finding bearings from a KrakenSDR onto the ATAK map.

**This guide covers version 0.3**, which does the whole hunt: connects to the
radio, draws bearings, collects them while you drive, crosses them to work out
where the transmitter is, and tells you where to drive next to sharpen the
answer.

**Download Signal DF 0.3** (pick the one matching your ATAK-CIV version, sideload, then load it in ATAK's Plugins manager):

- [ATAK-CIV 5.6](https://github.com/takwerx/signal-df/releases/download/v0.3/ATAK-Plugin-SignalDF-0.3--5.6.0-civ-release.apk)
- [ATAK-CIV 5.7](https://github.com/takwerx/signal-df/releases/download/v0.3/ATAK-Plugin-SignalDF-0.3--5.7.0-civ-release.apk)
- [ATAK-CIV 5.8](https://github.com/takwerx/signal-df/releases/download/v0.3/ATAK-Plugin-SignalDF-0.3--5.8.0-civ-release.apk)

All releases: https://github.com/takwerx/signal-df/releases

**Before you start:** builds are published for ATAK-CIV 5.6, 5.7 and 5.8. Take
the one matching your ATAK; the wrong one will not load.

---

## Before you start

You need four things:

1. **A KrakenSDR** with its own power supply — not just the USB data cable. It
   draws more than a Raspberry Pi port can give.
2. **A Raspberry Pi** running the KrakenSDR image. The Pi 5 and Pi 4 images are
   different files; the wrong one will not boot.
3. **Five antennas**, identical, on identical lengths of coax, arranged in the
   geometry the radio is configured for. This matters more than it sounds —
   see [Why the antennas have to match](#why-the-antennas-have-to-match).
4. **A phone running ATAK** with Signal DF installed.

---

## Part 1 — Get the radio on a network

This is the part that confuses everyone, because the KrakenSDR image is
**WiFi-first and ignores your ethernet cable.**

On boot it looks for a WiFi network called `krakensdr`. If it does not find one,
**it becomes one.** Plugging it into your switch will not give it an address on
your network, and the link lights on the ethernet port come on anyway, which
makes it look like it worked.

So after the Pi boots, look at your phone's WiFi list. You should see:

| | |
|---|---|
| Network name | `krakensdr` |
| Password | `krakensdr` |
| The radio's address | **192.168.50.5** |
| Its web page | `http://192.168.50.5:8080` |

**Join your phone to that network.** That is the normal way to run this — the
phone and the radio on the same little network, which is exactly how it works
on a vehicle.

### Android will ask about the missing internet

That network has no internet, so Android pops up *"Internet may not be
available"*. Either answer works — Signal DF sends its own traffic down the
WiFi explicitly rather than relying on which network Android has picked as the
default. See [Scenario B](#scenario-b--no-starlink-but-you-have-cell-service)
for why that matters.

If the phone hops back to your normal WiFi on its own and Signal DF stops seeing
the radio, rejoin **krakensdr** and choose **Stay connected** that time.

### Give it time on the first boot

The first boot resizes the filesystem and reboots itself. Two to three minutes
before anything appears is normal.

---

## Part 1b — Which network, in the vehicle

**Your phone has one WiFi radio and can only be on one WiFi network.** It cannot
sit on the Kraken's access point and a Starlink at the same time. So the first
decision in any vehicle build is where the radio lives.

### Scenario A — Starlink in the truck: hardwire the Kraken (recommended)

Put everything on one network and the problem disappears.

1. Run ethernet from the Pi to the Starlink router.
2. On the Pi, set `eth0` to DHCP. **The KrakenSDR image does not do this out of
   the box** — it is WiFi-first, and a cable alone gets you link lights and
   nothing else.
3. Reboot the Pi and find its new address on your network.
4. On the phone: join Starlink's WiFi as normal. Nothing special.
5. In Signal DF, set the address to the Pi's address on that network — or try
   `krakensdr.local`, which usually resolves once it is on a real network.

You get bearings, TAK server, and internet, all at once, over a cable that does
not care about three radios competing in a metal box.

### Scenario B — No Starlink, but you have cell service

Join the radio's own network and let mobile data carry the server. **Signal DF
handles the awkward part for you.**

1. **Settings → Connections → Wi-Fi**
2. Tap **krakensdr**, password **krakensdr**
3. Android will say *"Internet may not be available."* Either answer is fine now
   — see below.
4. Leave **mobile data ON**.
5. In Signal DF, set the address to **192.168.50.5** and Connect.

**Why you do not have to care about that prompt any more.** Android routes
everything over one default network. Answering "Stay connected" makes the
radio's WiFi that default, and then the TAK server, the map sources and the rest
of ATAK are all being sent at a radio with no internet behind it. That is a
genuinely nasty failure: the plugin works perfectly and everything else quietly
stops.

So Signal DF does not rely on the default route. It asks Android for a handle on
the WiFi network and sends **its own** requests down it explicitly, leaving your
phone's default network alone. Your mobile data keeps carrying ATAK; the radio
traffic goes out the WiFi; nobody has to choose.

**Confirmed on hardware**, 2026-09-27, on a phone with a live SIM joined to the
radio's access point:

| | |
|---|---|
| Signal DF reaching the radio | works — live bearings |
| Internet and the TAK server over cellular, at the same time | works |
| Anything *not* going through Signal DF reaching the radio | fails |

That last row is not a problem, it is the proof. On that phone the default route
sends the radio's own subnet out the cell modem, where it goes nowhere — so a
command-line tool could not reach the radio at the moment the plugin was reading
it happily. The pinning is what makes the difference.

### Scenario C — Nothing but the radio

No Starlink, no cell. Same steps as B, and you simply have no server: bearings
on your own map, nothing shared, which is what the plugin does by default
anyway. Worth rehearsing on purpose before a day when it is the only option.

### Scenario D — You need both and cannot hardwire

You have to choose, and it is worth choosing deliberately rather than
discovering it mid-search:

| Phone on | You get | You lose |
|---|---|---|
| Kraken's WiFi | bearings on the map | Starlink — server and internet, unless cell covers it |
| Starlink's WiFi | server, internet, the team | bearings; Signal DF cannot see the radio |

If this comes up often, that is the argument for spending an afternoon on
Scenario A.

### Going back to your normal network

**Settings → Connections → Wi-Fi**, tap your usual network. Android may do it
for you when the radio powers down; the pane will say *"nothing for N s"* and
you will know why.

---

## Part 2 — Connect Signal DF

Open Signal DF from the ATAK toolbar. The top section is **RADIO**.

### The address button

The wide button showing an address is **where the radio is**. Tap it to change.

- If the phone is on the radio's own WiFi, this is **`192.168.50.5`**
- If you have put the radio on your own network instead, it is whatever address
  it got there — `krakensdr.local` often works
- You only need to add `:8081` if somebody moved the radio's file server off its
  normal port, which almost never happens

Signal DF checks what you type. It wants a host name or an IP address and
nothing else — no `http://`, no path — and it will tell you what is wrong rather
than failing silently later.

### The Connect button

**Connect** starts talking to the radio. **Disconnect** stops.

It is green when connected and red when not.

When you connect, Signal DF looks for the best feed the radio is offering and
tells you which one it found:

| What the radio is serving | What you get |
|---|---|
| **Kraken App CSV** | every VFO, plus the data the power lobe will need in 0.2 |
| **doa.xml** | one VFO, no spectrum — but this file is written in *every* mode |

You do not choose. It tries the better one, falls back to the other by itself,
and says which one answered plus what the other would add. That is deliberate:
it means Signal DF connects to a KrakenSDR that is merely switched on, rather
than one somebody configured for it first.

### The status line

Under **RADIO**, in words:

| It says | It means |
|---|---|
| `Not connected` | nothing is running |
| `Looking for the radio at ...` | trying each feed |
| `Kraken App CSV, 0.4 s ago` | working; that is how old the newest bearing is |
| `Kraken App CSV, nothing for 30 s` | it was working and stopped — network, or the radio quit |
| `answering but not updating for 40 s — check the receiver is attached` | **the radio is replying but producing nothing.** Usually the KrakenSDR is not plugged in, not powered, or was connected after the Pi booted |

That last one is worth understanding. A Pi with no receiver attached still
serves a perfectly well-formed file — frozen at one bearing, forever. Signal DF
watches whether the file actually *changes*, not just whether the radio answers,
so a dead receiver cannot masquerade as a live one.

---

## Part 3 — Heading source, and why it comes first

This is the most important thing in the plugin, so read this bit.

**The radio does not tell you where north is.** It reports the direction a
signal came from *relative to your antenna array* — relative to a piece of metal
bolted to a truck. To turn that into a real bearing, something has to know which
way the array is pointing.

Worse: the radio reports a heading of `0` both when the array genuinely faces
true north **and when it has no idea.** Those are identical on the wire.

So there is one control, **Heading source**, with three answers:

| Choice | Use it when |
|---|---|
| **GPS track** | the array is on a vehicle with antenna 0 pointing forward — the normal case. ATAK supplies the heading from your own GPS as you drive. |
| **Manual** | a fixed installation. Stand at the array once with a compass, measure which way antenna 0 points, type it, never touch it again. |
| **Radio** | only if you have fitted a USB GPS to the Pi, or typed a fixed offset into the KrakenSDR's own web page. The KrakenSDR has no GPS of its own. |

These are the same choices KrakenRF's own app calls Bearing Mode, so if you have
used that, you already know this question.

**GPS track only works while you are moving.** A parked vehicle has no course —
successive GPS fixes differ by less than their own error, so the direction
between them is noise. Signal DF uses ATAK's own rule here: it needs about five
miles an hour, and it holds your last good heading for five minutes after you
stop, so a traffic light or a gate does not lose it. The button tells you which
it is doing:

- `Heading source: GPS track, 142 deg` — measured, you are moving
- `Heading source: GPS track, 142 deg, held 3 min` — remembered; if you have
  turned the truck round since, do not trust it
- `Heading source: GPS track, no track until moving` — nothing yet, drive

**With no heading, nothing is drawn on the map.** Not a faint line, not a line
labeled "approximate" — nothing. A line on a map is a claim about a direction
on the earth, and without a heading that claim would be wrong by however far
your array happens to be turned: point the truck south and every line would
point at the exact opposite of the transmitter. The bearings still appear in the
list, because a direction relative to your antenna is real information, and the
pane tells you what is missing and how to fix it.

---

## Part 3b — Sizing the array

**Antenna array sizing** answers one question: given the frequency you are
hunting, how do I set the antennas up.

Type the frequency — or take it from the radio, which the button offers with the
number already on it — and it tells you the rest:

- **How far out from the center** each antenna goes, named three ways so you can
  use whichever you have: which hole on KrakenRF's printed paper arms, counted
  out from the middle; which position on the community 3D-printed template; or
  the plain measurement if you have a tape.
- **How long to extend each whip** — a quarter of a wavelength, which is
  KrakenRF's own rule — and how many sections that is on a KrakenTenna.
- **How good the result will be**: roughly how many degrees of resolution, and
  what frequency range the same array covers so you know whether a retune means
  rebuilding.

The picture shows which way round the array goes: antenna 0 points the way the
vehicle faces, then 1, 2, 3, 4 clockwise. It carries no measurements on purpose
— every arm is the same length and they are always 360/N apart, so the numbers
belong in the text and the picture answers the one thing text cannot.

---

## Part 4 — Reading the bearings

Each active VFO gets a row: the frequency, the bearing, and how good it is.

- **The big number** is the bearing. `rel` after it means it is relative to the
  antenna, not to north — which also means it is not on the map.
- **Confidence** is the radio's own figure. Low confidence with a bearing that
  jumps around usually means there is nothing to hear — or no antennas on it.
- **Power** in dB. `(at the floor)` means the radio clamped the value and it is
  a limit, not a measurement.
- **FRONT END SATURATED** means the receiver is overloaded and that bearing is
  not worth anything. Turn the gain down.

On the map, one line per VFO runs from the receiver out along the bearing,
clamped to the ground so it does not disappear behind terrain. The line's length
follows your map scale — it shows a *direction*, not a distance, and a fixed
length would imply we know how far away the transmitter is. Working that out is
what Part 4b is for.

**Weak bearings are drawn faint.** If the radio's confidence in a bearing is
below the threshold, the line is still drawn — thin and dim, labeled `weak` —
because hiding it would leave you staring at an empty map while the radio is
plainly hearing something. What a weak bearing does not do is feed the fix or go
out to your team. Those are products, and a product built on data you would not
show at full brightness is not one to hand to somebody else.

**When bearings go stale the lines turn grey**, the numbers turn red, and the
pane tells you how long it has been. A two-minute-old bearing draws exactly the
same line as a live one, so it has to say so.

---

## Part 4b — Finding the transmitter

One bearing is a line, not a place. Five people standing in different spots all
pointing at the same church steeple give you five lines that cross at the
steeple — and that crossing point is the answer. Driving is how you get the
different spots.

**Turn COLLECTING ON and drive.** That is the whole procedure.

A bearing is kept every 50 m of movement. Sitting still adds nothing: a hundred
bearings from one parking space have no crossing angle between them and would
only outvote the handful taken from everywhere else. After about 100 m of
driving you have three, which is enough for a first answer.

### What appears on the map

| What you see | What it is |
|---|---|
| **A faint cyan fan** | every bearing you have collected, fading with age. Where they pile up is the transmitter. |
| **A filled magenta shape**, labelled with the frequency and a ± figure | the fix and its 95% error area, together. Long and thin means you know the direction well and the distance badly. There is no dot in the middle on purpose — a point would claim the transmitter is exactly there, and the whole reason for the shape is that it is not. |
| **A lime line** | everywhere you have collected from — your coverage, and by its absence, where you have not been |
| **An orange band** | **Drive into this.** Where to go next to sharpen the fix. |

The frequency label turns **green** when the geometry has reached the range
that gives the best accuracy. White means it is still worth driving.

### Reading the fan

This is the part worth learning, because it is the only thing that can catch a
bad bearing.

Radio bounces. A signal reflects off a hillside, a metal barn, a water tower,
and your antennas hear the reflection instead of the transmitter. The radio
cannot tell the difference — it honestly reports the direction the energy
arrived from, which is the direction of the bounce. This is called multipath
and it is the single biggest source of wrong answers in direction finding.

On the fan, a bad bearing is **the one line that misses the crowd.** Everything
else converges; that one goes off on its own. Usually you will know why —
"that was right as I passed the substation" — and then you know to distrust
readings from there, and to **Clear** and start again if you took a lot of them.

The error area can only tell you the bearings disagree. The fan tells you which
one, and where you were standing.

### The orange band, and when it disappears

While your bearings all run nearly parallel, the fix is a long thin sliver:
the direction is pinned and the distance along it is not. More bearings from
the same road will not fix that. Going **around** the transmitter will.

That is what the band is. It is an arc centred on the fix, and it asks for two
things at once:

- **Swing round.** Driving straight at a transmitter changes the bearing not at
  all — every bearing taken from along that line is the same line, and lines
  lying on top of each other never cross. Moving sideways is what makes them
  cross.
- **Come in.** Range is the strongest single driver of error, so the band is
  drawn inside where you are now. Reaching it shortens the range, which draws
  the next band in closer still. Over a search that is a spiral.

**Anywhere inside the band will do.** It is a region and not a point because
"anywhere in here, the roads decide the rest" is the honest instruction — a dot
would claim a precision the advice does not have.

**How wide it is tells you something.** Early on the band is a deep wedge,
because the range to the transmitter is still a guess and the band covers the
possibilities. As the fix firms up it narrows and pulls inward. It shrinking is
the search working.

When your bearings span the angle that gives the best accuracy, the band
**removes itself** — there is nothing left for it to tell you — and the
frequency label on the map turns green.

### What the pane tells you

> Collecting. 47 bearings.
> Fix within 1.2 mi.
> Fix from three or more bearings.
> Angle width 34 of the 90 wanted. Drive into the orange band.
> 18 dropped: 18 with the front end saturated — turn the radio's gain down.

One fact to a line, and the whole block is coloured: **white** while there is
no fix yet, **green** once there is one.

The words are the manuals'. One bearing gives a direction; two give a **cut**;
**three or more make a fix**, which is why nothing crosses until you have three
from three places. **Angle width** is the angle between your outermost
bearings — for one vehicle driving around a transmitter, it is simply how far
round it you have got. Ninety degrees is where published trials put the best
accuracy, and the band exists to get you there.

"Fix within" is the **worst case** — the long axis of the error area, not an
average. If it says 200 ft, the transmitter is within 200 ft.

The dropped count matters. A filter that quietly eats data looks exactly like a
radio that has stopped working, so it says what it threw away and, where there
is one, what to do about it.

**Clear** throws everything away and starts a new search. Turning COLLECTING off
keeps what you have.

---

## Part 4c — Sharing with your team

Off by default. Nothing leaves your phone until you turn it on.

Pick a **Feed** — that is a TAK Server Data Sync feed, and everyone subscribed
to it will see your bearings. Set a **Stale time**, then **TRANSMIT ON**.

What goes out is an ordinary CoT line, so a teammate without this plugin still
sees something on their map rather than nothing. It carries the frequency, the
bearing, how the heading was known, and the caveat below. It expires by itself
at the stale time.

**Nothing is stored in the feed.** Bearings age off other people's maps on their
own and leave nothing behind. This is live data, not a product.

Bearings are only transmitted when the heading is known and the quality gate
passes — the same rules that govern your own map. What your team sees is never
better than what you see.

---

## Part 5 — Radio setup

The **Radio setup** button opens the KrakenSDR's own web page, full width,
inside ATAK. Tuning, gain, squelch, the spectrum display, Start and Stop
Processing — all of it, without leaving the map app.

Signal DF does not reimplement any of that on purpose. KrakenRF's page is always
complete and always current, and anything we copied would go stale the moment
they changed it.

Press Back to return to the map.

---

## Why the antennas have to match

The KrakenSDR works by comparing the *phase* of the same signal arriving at five
receivers. That comparison is only meaningful if the five paths are identical.

- **Five antennas, all the same.** Not four, not a mixture.
- **Identical coax lengths.** A different length is a different phase, and a
  different phase is a different bearing.
- **The geometry the radio is set to.** Check `ant_arrangement` and
  `ant_spacing_meters` on the radio's Configuration page and build what it says.

Get this wrong and you do not get an obviously broken bearing. You get a
confident, steady, plausible line pointing somewhere the transmitter is not.

---

## When something is wrong

| What you see | What it usually is |
|---|---|
| Nothing in the WiFi list | Pi still booting, or it joined a `krakensdr` network you already had |
| Phone keeps leaving the radio's WiFi | Android moved it off a network with no internet — rejoin **krakensdr** and choose **Stay connected** |
| `answering but not updating` | the receiver is not attached, not powered, or was plugged in after the Pi booted. Plug it all in, then power-cycle the Pi |
| `cannot find that host on this network` | wrong address, or the phone is on a different WiFi |
| `nothing answering on that port` | the radio is reachable but its software is not running |
| Bearings jump all over with low confidence | no antennas, or nothing transmitting on that frequency |
| Bearings steady but pointing the wrong way | antennas or coax mismatched, or the array heading is wrong |

### Checking the radio itself

Open **Radio setup** and look at the Configuration page. Under DAQ Subsystem
Status you want **Frame Sync: Ok** and a frame index that is climbing. If the
receiver is not detected at all, none of that appears.

If you plugged the KrakenSDR in after the Pi had already booted, power-cycle the
Pi. The radio enumerates all five tuners at startup and will not pick them up
later.

---

## What 0.3 does not do yet

Said plainly, so nothing here is a surprise.

**The bearing direction has not been validated against a known transmitter.**
This is the important one. Which way round the radio writes its bearings was
read out of its own source and cross-checked against another implementation,
but nobody has yet put a transmitter at a measured angle and confirmed the line
points at it. Every fix and every shared bearing carries that caveat in its
remarks. Use it to hunt; do not treat a bearing as surveyed.

**No heatmap.** The fix crosses your bearings into a single point. That is the
right answer for one transmitter and the wrong one for two on the same
frequency — it will report a confident position somewhere between them. A grid
heatmap would show two hot spots instead; it is not built. Until it is, the fan
is what shows you: two clusters of crossings instead of one.

**No power lobe.** The single line hides how sharp or vague each individual
bearing was.

**No receiving.** You can send your bearings to a team; you cannot yet fold
*their* bearings into your fix. Two vehicles on opposite sides of a valley is
the geometry that actually solves a search, and it is the next big thing.

**No WebSocket.** Signal DF polls about once a second, which is plenty.

---

## Credits

The KrakenSDR and `krakensdr_doa` are by [KrakenRF](https://github.com/krakenrf).
Signal DF is not a KrakenRF product and carries none of their branding — it
speaks to the radio over the interfaces their software already publishes.

[canaryradio's Kraken-to-TAK](https://github.com/canaryradio/Kraken-to-TAK) did
the KrakenSDR-to-TAK integration first, and reading it caught a bug in ours.
