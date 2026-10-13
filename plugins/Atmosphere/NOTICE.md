# NOTICE

## Prior art

The idea of describing an ATAK weather plugin's data sources in external definition
files — user-droppable, overriding bundled sources by id — comes from the **ATAK Weather
Plugin** by Hellikandra & Sharp, which is MIT licensed:

  https://github.com/Hellikandra/ATAK-Weather-Plugin

This plugin is an independent implementation. Its schema, parser, unit handling,
response mapping, caching, egress policy and user interface were written from scratch
and are not derived from that project's source. The debt is one of design, and it is
recorded here because it is real.

If any file in this plugin is ever taken from that project rather than reimplemented,
that file will carry the upstream copyright header and will be listed below, alongside
the MIT permission notice.

  Files derived from ATAK-Weather-Plugin: (none)

MIT License text for the upstream project, reproduced for reference:

    MIT License

    Copyright (c) 2022 Hellikandra & Sharp

    Permission is hereby granted, free of charge, to any person obtaining a copy
    of this software and associated documentation files (the "Software"), to deal
    in the Software without restriction, including without limitation the rights
    to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
    copies of the Software, and to permit persons to whom the Software is
    furnished to do so, subject to the following conditions:

    The above copyright notice and this permission notice shall be included in all
    copies or substantial portions of the Software.

    THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
    IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
    FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
    AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
    LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
    OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
    SOFTWARE.

## Data providers

Enabling a source means accepting that provider's terms. The bundled definitions are:

- **Open-Meteo** — https://open-meteo.com — data under CC BY 4.0; free tier is for
  non-commercial use. Attribution is shown in the plugin whenever the source is used.
- **US National Weather Service** — https://api.weather.gov — a work of the United
  States government, public domain. Requires an identifying User-Agent, which the
  plugin sends.

## Plugin scaffolding

Scaffolded from the ATAK CIV SDK's `plugintemplate` sample. The SDK licence grants the
right to derive new works from it; the SDK's own binaries are not redistributed here and
never will be.
