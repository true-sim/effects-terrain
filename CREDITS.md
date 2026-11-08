# Credits

The code is MIT (`LICENSE`). Elevation data is never in this repository: it is fetched on demand into the terrain
cache from the sources below, pinned by the manifests in `data/src/main/resources/com/truesim/effects/terrain/data/`,
and keeps its own licence. Anything showing terrain made from it must show its attribution.

## Lauterbrunnen (`lauterbrunnen.manifest`)

| Source | What | Licence | Attribution |
|---|---|---|---|
| swissALTI3D 2 m, 2025 (45 tiles of 1 km, the valley round the village), [data.geo.admin.ch](https://data.geo.admin.ch/browser/index.html#/collections/ch.swisstopo.swissalti3d) | bare-ground terrain model, LV95 / LN02 heights | swisstopo free geodata (OGD): use, distribution and commercial use allowed with the source named ([terms](https://www.swisstopo.admin.ch/en/terms-of-use-free-geodata-and-geoservices)) | Federal Office of Topography swisstopo (swissALTI3D) |
| Copernicus DEM GLO-30 (2 tiles of 1 degree), [AWS open data `copernicus-dem-30m`](https://registry.opendata.aws/copernicus-dem/) | surface model (forest and buildings included), EGM2008 heights | Copernicus DEM GLO-30 Public licence: free, worldwide, any use including commercial, with the attribution ([licence](https://spacedata.copernicus.eu/collections/copernicus-digital-elevation-model)) | produced using Copernicus WorldDEM-30 © DLR e.V. 2010-2014 and © Airbus Defence and Space GmbH 2014-2018 provided under COPERNICUS by the European Union and ESA; all rights reserved |
| Copernicus DEM GLO-90 (12 tiles of 1 degree), [AWS open data `copernicus-dem-90m`](https://registry.opendata.aws/copernicus-dem/) | the same at 90 m, the ring to the horizon | Copernicus DEM GLO-90 licence: free, worldwide, any use including commercial, with the attribution ([licence](https://spacedata.copernicus.eu/collections/copernicus-digital-elevation-model)) | produced using Copernicus WorldDEM-90 © DLR e.V. 2010-2014 and © Airbus Defence and Space GmbH 2014-2018 provided under COPERNICUS by the European Union and ESA; all rights reserved |

Considered and not used: Austria's 10 m ALS DGM (data.gv.at, CC BY 4.0), for a site near Hallstatt or Zell am See;
Lauterbrunnen's valley is Swiss, where swissALTI3D covers it at 2 m.
