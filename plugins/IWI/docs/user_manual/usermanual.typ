#import "@preview/polylux:0.4.0": *
#import "formatting.typ": *

#show: userguide.with(
   plugin-name: "IWI",
   plugin-version: "0.2",
   platform: "ATAK",
   platform-version: "5.8.0",
)

#tak-slide[
= Overview

#toolbox.side-by-side(columns: (1.5fr, 9fr))[
  #image("plugin_icon.png", width: 85%)
][
IWI, Incident Within an Incident, walks you through the NWCG Medical Incident
Report from the Incident Response Pocket Guide (IRPG) when someone is hurt on
the line, and puts the incident on the map for everyone on your Data Sync feed.
]

#v(6pt)

- *The report, one step at a time:* six short steps for the initial report,
  then the patients, the evacuation plan and the contingency as you learn
  them.
- *A radio script* built from what you entered, in the order the IRPG says it.
- *One CASEVAC marker:* a device without IWI sees a normal ATAK CASEVAC; a
  device with IWI reads the whole report.
- *Hospitals near the patient:* ER, trauma and burn centers, with a flight
  estimate.
]

#tak-slide[
= Before you start

- *Match the plugin to your ATAK version.* Builds are tied to the ATAK release
  they were built against. This release is published for ATAK 5.8.
- *A reporting aid, not a substitute for the IRPG or your training.* If the
  incident is life threatening, do not let the report delay patient care.
- *No patient names.* The report holds triage colors, injuries and treatment,
  never a name.
- *Sharing uses Data Sync.* To send the IWI to others, your device needs a TAK
  server connection and the Data Sync plugin. Without them, IWI still works on
  this device.

#v(6pt)

Load IWI from ATAK's Plugins manager like any other plugin, then open it from
the toolbar with the IWI icon (the cross in a frame):

#v(4pt)

#image("01.png", width: 100%)
]

#tak-slide[
= The first screen

#align(center, image("02.png", height: 170pt))

Six steps make the initial report: *Who You Call*, *Patients*, *What
Happened*, *Evacuation Request*, *Medical on Scene* and *Resources Requested*.
A step is red until it is filled in. *Next* and *Back* at the top move through
them. *Declare IWI* counts the steps that are left.
]

#tak-slide[
= Step 1: who you call

#align(center, image("06.png", height: 140pt))

Pick *Communications* or *Dispatch*. Verify the frequency before you start.
The radio script uses your ATAK callsign, shown under the buttons.
]

#tak-slide[
= Step 3: what happened

#toolbox.side-by-side(columns: (6fr, 5fr))[
  #image("07.png", width: 100%)
][
  Pick *Trauma* or *Medical*, then what kind. Each choice is a large button,
  for a gloved hand.

  #v(6pt)

  The pick goes into the radio script in plain words: "burns", "tree
  strike", "heat illness".
]
]

#tak-slide[
= Step 6: resources requested

#align(center, image("08.png", height: 150pt))

The IRPG's example list. Pick everything you need; each pick turns green and
the heading lists them.
]

#tak-slide[
= Declare the IWI

When the six steps are done, tap *Declare IWI* and say where the patient is:
*My Location*, or *Set it on the map*.

#v(6pt)

#align(center, image("03.png", height: 120pt))

Check the position, drag the marker if it is not exactly right, then slide to
confirm. *Cancel* declares nothing.
]

#tak-slide[
= Share it

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("04.png", height: 115pt)
  #v(6pt)
  #image("05.png", height: 115pt)
][
  - *Send to IWI* puts the IWI in the IWI Data Sync feed.
  - *Choose Feed* picks another feed on your server.
  - *This Device Only* shares nothing.

  #v(6pt)

  Everyone on the feed gets the IWI and every update, including devices that
  are off the network now; they get it when they are back.
]
]

#tak-slide[
= Make the call

#align(center, image("16.png", height: 190pt))

*Radio Script* reads the initial report as you say it. "Break" means keep
talking: say it, pause, go on. Where it says wait, stop and let Communications
come back to you. When the call is acknowledged, slide *Initial Report Made*.
]

#tak-slide[
= After the initial report

#align(center, image("09.png", height: 170pt))

The counts row shows the patients by triage color. The buttons under it are
the follow-ups: *Patient Update*, *Evac Plan* and *Contingency* are red until
filled in, then green. *IWI DECLARED* gives the time, the age and the position.
]

#tak-slide[
= Patients

#align(center, image("10.png", height: 140pt))

One card per patient, made from the triage counts, most severe first. Each
card says when it was last updated. Open a card to fill it in.
]

#tak-slide[
= A patient card

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("11.png", height: 120pt)
  #v(6pt)
  #image("12.png", height: 120pt)
][
  - *Triage* color and *Needs*: trauma, burns, medical. Needs pick the
    hospitals that fit.
  - Consciousness, breathing, pulse, bleeding and treatment, as the IRPG
    asks.
  - *Patient weight*, for short-haul or hoist.
  - *Radio Update* opens the script for this patient.
  - *Transported* when the patient has left.
]
]

#tak-slide[
= A patient update on the radio

#align(center, image("17.png", height: 180pt))

The update says the patient's number, triage color, injury, assessment and
treatment, in the IRPG's order.
]

#tak-slide[
= Evacuation plan and contingency

#align(center, image("13.png", height: 150pt))

*Evacuation Plan*: where the patient goes if not from the IWI marker (type it
or *Pick on Map*), the ETA, and the helispot size and hazards.

#v(4pt)

#align(center, image("15.png", height: 45pt))

A heading is green when its part of the radio script has nothing missing.
]

#tak-slide[
= Hospitals

#align(center, image("18.png", height: 165pt))

The nearest ER, trauma and burn centers from the patient, with the count in
each filter. Each row gives the distance, a flight estimate at the cruise
speed in Settings, and whether there is a helipad. Tap one to mark it on the
map. Where a patient flies is the air crew's call.
]

#tak-slide[
= One hospital

#toolbox.side-by-side(columns: (5fr, 6fr))[
  #image("19.png", width: 100%)
][
  - What it is, the city and its emergency department.
  - Distance and bearing from the patient, and the flight estimate.
  - The helipad, when the FAA charts one.
  - *Go To* centers the map on it.
]
]

#tak-slide[
= Settings

#align(center, image("20.png", height: 175pt))

- *Data Sync Feed:* where the IWI goes, with every update.
- *Helicopter Cruise Speed:* the one speed every flight estimate uses.
- *Trauma Center Data:* the hospital list on this device, kept up to date
  when there is a network.
]

#tak-slide[
= Clear the IWI

#align(center, image("21.png", height: 110pt))

*Clear IWI* asks first, then you slide to confirm. It removes the IWI marker
from your map and erases the report on this device, so the next IWI starts
blank. It does not remove the marker from anyone you sent it to.
]

#tak-slide[
= On a device without IWI

#align(center, image("25.png", height: 250pt))

The IWI is a normal ATAK CASEVAC marker. The hospitals sent with it carry
ATAK's hospital icon and their name and kind.
]

#tak-slide[
= ATAK's CASEVAC view and IWI

#toolbox.side-by-side(columns: (6fr, 5fr))[
  #image("26.png", height: 290pt)
][
  Left: the same IWI on a device without IWI, in ATAK's own CASEVAC view. The
  9-line holds the location, the count and the request, and the remarks hold
  the report.

  #v(6pt)

  Right: the IWI on the reporting device.

  #v(6pt)

  On a device with IWI, a tap on another device's IWI opens its whole report,
  read only.
]
]

#tak-slide[
= In Data Sync

#toolbox.side-by-side(columns: (6fr, 5fr))[
  #image("27.png", height: 270pt)
][
  The IWI feed lists the IWI marker and its hospitals, and each update to
  them.

  #v(6pt)

  A device that joins the feed later gets the IWI as it stands now.
]
]
