# IWI for ATAK — User Guide

**Version 0.3 · takwerx**

**Download IWI 0.3** (pick the one matching your ATAK-CIV version, sideload, then load it in ATAK's Plugins manager):

- **ATAK-CIV 5.6:** https://github.com/takwerx/iwi/releases/download/v0.3/ATAK-Plugin-IWI-0.3--5.6.0-civ-release.apk
- **ATAK-CIV 5.7:** https://github.com/takwerx/iwi/releases/download/v0.3/ATAK-Plugin-IWI-0.3--5.7.0-civ-release.apk
- **ATAK-CIV 5.8:** https://github.com/takwerx/iwi/releases/download/v0.3/ATAK-Plugin-IWI-0.3--5.8.0-civ-release.apk

All releases: https://github.com/takwerx/iwi/releases

IWI, Incident Within an Incident, walks you through the NWCG Medical Incident
Report from the Incident Response Pocket Guide (IRPG) when someone is hurt on
the line. It builds the radio script from what you enter, puts one CASEVAC
marker on the map for everyone on your Data Sync feed, and lists the hospitals
nearest the patient.

---

## Before you start

- **Match the plugin to your ATAK version.** Builds are tied to the ATAK
  release they were built against. Published builds: **ATAK-CIV 5.6, 5.7 and 5.8**.
- **A reporting aid, not a substitute for the IRPG or your training.** If the
  incident is life threatening, do not let the report delay patient care.
- **No patient names.** The report holds triage colors, injuries and
  treatment, never a name.
- **Sharing uses Data Sync.** To send the IWI to others, your device needs a
  TAK server connection and the Data Sync plugin. Without them, IWI still works
  on this device.

Load IWI from ATAK's Plugins manager like any other plugin, then open it from
the toolbar with the IWI icon (the cross in a frame):

![The IWI icon in ATAK's toolbar](screenshots/01_toolbar.png)

The user manual is inside the plugin: **Settings → Tool Preferences → IWI →
Plugin Documentation**.

---

## 1. The initial report

![The first screen: six steps, then Declare IWI](screenshots/02_main_before.png)

Six steps make the initial report: **Who You Call**, **Patients**, **What
Happened**, **Evacuation Request**, **Medical on Scene** and **Resources
Requested**. A step is red until it is filled in. **Next** and **Back** at the
top move through them, and **Declare IWI** counts the steps that are left.

**Step 1, who you call.** Pick Communications or Dispatch. Verify the frequency
before you start. The radio script uses your ATAK callsign.

![Step 1: Communications or Dispatch](screenshots/06_who_you_call.png)

**Step 3, what happened.** Pick Trauma or Medical, then what kind. Each choice
is a large button, for a gloved hand.

![Step 3: Trauma, with Burns picked](screenshots/07_what_happened.png)

**Step 6, resources requested.** The IRPG's example list. Pick everything you
need.

![Step 6: Paramedic/EMT and REMS Team picked](screenshots/08_resources.png)

## 2. Declare and share

When the six steps are done, tap **Declare IWI** and say where the patient is:
**My Location**, or **Set it on the map**. Check the position, drag the marker
if it is not exactly right, then slide to confirm.

![Confirm the IWI location](screenshots/03_declare_confirm.png)

IWI then asks where to send it:

![Send this IWI to a Data Sync feed?](screenshots/04_send_to_feed.png)

- **Send to IWI** puts the IWI in the IWI Data Sync feed.
- **Choose Feed** picks another feed on your server.
- **This Device Only** shares nothing.

Everyone on the feed gets the IWI and every update, including devices that are
off the network now; they get it when they are back.

![Choosing a feed](screenshots/05_choose_feed.png)

## 3. Make the call

**Radio Script** reads the initial report as you say it. "Break" means keep
talking: say it, pause, go on. Where it says wait, stop and let Communications
come back to you. When the call is acknowledged, slide **Initial Report Made**.

![The initial report as a radio script](screenshots/16_radio_initial.png)

## 4. Follow-ups

![The main screen after the initial report](screenshots/09_main_declared.png)

The counts row shows the patients by triage color. **Patient Update**, **Evac
Plan** and **Contingency** are red until filled in, then green. **IWI
DECLARED** gives the time, the age and the position.

**Patients.** One card per patient, made from the triage counts, most severe
first.

![Patient cards](screenshots/10_patient_cards.png)

A card holds the triage color and **Needs** (trauma, burns, medical; the
hospitals list follows them), the IRPG's assessment and treatment, and the
patient weight for short-haul or hoist. **Radio Update** opens the script for
that patient; **Transported** marks the patient as gone.

![A patient card: triage and needs](screenshots/11_patient_card_needs.png)

![A patient card: weight, Radio Update, Transported](screenshots/12_patient_card_bottom.png)

![A patient update on the radio](screenshots/17_radio_patient_update.png)

**Evacuation plan.** Where the patient goes if not from the IWI marker (type it
or **Pick on Map**), the ETA, and the helispot size and hazards.

![Evacuation Plan](screenshots/13_evac_plan.png)

A heading is green when its part of the radio script has nothing missing:

![Contingency filled in, Additional Information empty](screenshots/15_contingency_heads.png)

## 5. Hospitals

![Hospitals near the patient](screenshots/18_hospitals_list.png)

The nearest ER, trauma and burn centers from the patient, with the count in
each filter. Each row gives the distance, a flight estimate at the cruise speed
in Settings, and whether there is a helipad. Tap one to mark it on the map.
Where a patient flies is the air crew's call.

![One hospital's details](screenshots/19_hospital_details.png)

**On the map.** The IWI marker and the hospitals sent with it. Each hospital
carries a red box: its name on the first line, its kind on the second. Every
device on the feed sees the same.

![The IWI and its hospitals on the map](screenshots/23_map.png)

![A hospital close: the red box over ATAK's hospital icon](screenshots/24_hospital_close.png)

## 6. Settings and Clear

![Settings](screenshots/20_settings.png)

- **Data Sync Feed:** where the IWI goes, with every update.
- **Helicopter Cruise Speed:** the one speed every flight estimate uses.
- **Trauma Center Data:** the hospital list on this device, kept up to date
  when there is a network.

**Clear IWI** asks first, then you slide to confirm. It removes the IWI marker
from your map and erases the report on this device, so the next IWI starts
blank. It does not remove the marker from anyone you sent it to.

![Slide to Clear IWI](screenshots/21_clear_slide.png)

## 7. What other devices see

On a device without IWI, the IWI is a normal ATAK CASEVAC marker, and the
hospitals sent with it carry ATAK's hospital icon with their name and kind.

![The IWI and a hospital on a device without IWI](screenshots/25_without_iwi_map.png)

Left: the same IWI in ATAK's own CASEVAC view, on a device without IWI. The
9-line holds the location, the count and the request; its remarks hold the
report. Right: the IWI on the reporting device. On a device with IWI, a tap on
another device's IWI opens its whole report, read only, with the counts in
color.

![ATAK's CASEVAC view beside IWI](screenshots/26_casevac_vs_iwi.png)

**Another device's IWI.** On a device with IWI, a tap on another device's IWI
opens it read only: the counts in color, who has been transported first, then
the initial report and the follow-up as the radio script says them. Tap a
heading to open or close it. It updates as the reporter changes it.

![Another device's IWI, read only](screenshots/22_read_only.png)

In Data Sync, the IWI feed lists the IWI marker, its hospitals, and each update
to them. A device that joins the feed later gets the IWI as it stands now.

![The IWI feed in Data Sync](screenshots/27_without_iwi_datasync.png)
