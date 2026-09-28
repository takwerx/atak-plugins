# Signal DF — User Guide

Signal DF puts direction-finding bearings from a KrakenSDR onto the ATAK map.

**This guide covers version 0.1**, which does one thing: connects to the radio
and draws one bearing. The fix, the power lobe and sharing between operators
come later.

> **Not released yet.** There is no signed build to download. This guide is here
> so the setup is written down while it is fresh; download links go in at the
> first release.

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

> **One piece still unproven.** The pinning itself is confirmed working on
> hardware. What has *not* been tested is the whole picture on a phone with a
> SIM in it — the dev phone here has none, so nothing could verify that the TAK
> server really stays up over cellular while the radio runs on WiFi. It should.
> Check it somewhere it does not matter before you need it somewhere it does.

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

## Part 3 — Array heading, and why it is in orange

This is the most important thing in the plugin, so read this bit.

**The radio does not tell you where north is.** It reports the direction a
signal came from *relative to your antenna array* — relative to a piece of metal
bolted to a truck. To turn that into a real bearing, something has to know which
way the array is pointing.

Worse: the radio reports a heading of `0` both when the array genuinely faces
true north **and when it has no idea.** Those are identical on the wire.

So Signal DF never shows you a bearing without telling you what it is claiming:

| What the pane says | What it means |
|---|---|
| `Array heading: 142 deg, you set it` | you told it, and bearings are to true north |
| `Array heading: 142 deg, from the radio` | the radio reported a real heading |
| `the radio says 0 deg, which is also what it says when it has none — set one to be sure` | ambiguous; treat with suspicion |
| `unknown — bearings are relative to the antenna, not to north` | nothing knows |

When the heading is unknown, every bearing is labelled **`rel`** — on the map
and in the list — because a number of degrees on a north-up map otherwise reads
as a bearing to north when it is not.

**Set heading** — type the direction the array's zero is pointing, in degrees
true. **Use radio** — clear yours and go back to whatever the radio says.

---

## Part 4 — Reading the bearings

Each active VFO gets a row: the frequency, the bearing, and how good it is.

- **The big number** is the bearing. `rel` after it means it is relative to the
  antenna, not to north.
- **Confidence** is the radio's own figure. Low confidence with a bearing that
  jumps around usually means there is nothing to hear — or no antennas on it.
- **Power** in dB. `(at the floor)` means the radio clamped the value and it is
  a limit, not a measurement.
- **FRONT END SATURATED** means the receiver is overloaded and that bearing is
  not worth anything. Turn the gain down.

On the map, one line per VFO runs from the receiver out along the bearing,
clamped to the ground so it does not disappear behind terrain. The line's length
follows your map scale — it shows a *direction*, not a distance, and a fixed
length would imply we know how far away the transmitter is. We do not. That is
what the fix is for, in a later version.

**When bearings go stale the lines turn grey**, the numbers turn red, and the
pane tells you how long it has been. A two-minute-old bearing draws exactly the
same line as a live one, so it has to say so.

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

## What 0.1 does not do yet

Said plainly, so nothing here is a surprise:

- **No fix.** It draws bearings; it does not yet compute where the transmitter
  is, or how sure it is.
- **No power lobe.** The single line hides the ambiguity in a bearing.
- **Nothing is shared.** Everything stays on your phone. Bearings do not go to
  the server or to other ATAK users.
- **No WebSocket.** Signal DF polls about once a second, which is plenty.

---

## Credits

The KrakenSDR and `krakensdr_doa` are by [KrakenRF](https://github.com/krakenrf).
Signal DF is not a KrakenRF product and carries none of their branding — it
speaks to the radio over the interfaces their software already publishes.

[canaryradio's Kraken-to-TAK](https://github.com/canaryradio/Kraken-to-TAK) did
the KrakenSDR-to-TAK integration first, and reading it caught a bug in ours.
