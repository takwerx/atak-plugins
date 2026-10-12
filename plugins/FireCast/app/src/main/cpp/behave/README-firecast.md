# behave, as vendored into Fire Cast

The USFS Missoula Fire Sciences Laboratory's `behave` library, the fire behavior
engine inside FlamMap and Behave7, copied verbatim from
https://github.com/firelab/behave at commit `d4acc8a` (2026-09-14), `src/behave/`
only, with `client.cpp` (the example program's `main`) left out. It is public
domain (17 USC 105; see LICENCE.md beside this file), so it is not subject to the
AGPL that covers the rest of the plugin.

Do not edit these files. Update them by copying a newer commit over them and
rerunning the library's own `testBehave` on a phone (`tools/firecast-check/`):
on 2026-10-11 the 215 tests passed on an arm64 Android 12 device built with NDK
27.3.

One edit, and only one: the authors' email addresses were removed from the file
headers (`Author: William Chatham <...>` became `Author: William Chatham`),
because takwerx's publish scrub refuses any email address in the public tree.
The names stay; the addresses are in the upstream repository.
