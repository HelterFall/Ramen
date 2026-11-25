# LiveMaker GAL image format notes

## Supported variants

Ramen's initial GAL decoder supports classic GAL versions 103–107 and GAL/X200. It decodes the first frame and returns ARGB_8888 pixels that can be materialized as an Android `Bitmap`.

Classic GAL begins with `Gale` plus a three-digit version. Versions 103–107 have a length-prefixed binary metadata header. The decoder validates the declared version, dimensions, bit depth, frame count, compression method, block geometry, palette, and per-layer byte ranges.

GAL/X200 begins with `GaleX200`, followed by a length-prefixed zlib XML header. XML external entities and DTDs are disabled. The first frame's `Layers` metadata supplies dimensions, bit depth, palette, visibility, opacity, and alpha flags.

## Pixel and compression handling

Supported bit depths are 4, 8, 16, 24, and 32 bits per pixel. Classic indexed palettes use BGRX entries; GAL/X200 indexed palettes use RGB hexadecimal entries. Row strides follow the GAL four-byte alignment rules.

| Compression | Color plane | Separate alpha plane |
|---:|---|---|
| 0 | zlib stream, then raw rows or block references | zlib stream, then raw rows or block references |
| 1 | raw rows or block references | raw rows or block references |
| 2 | JPEG | zlib stream, then raw rows or block references |

The block decoder supports literal blocks, copies from earlier blocks in the same plane, and references to already-decoded layers of the first frame. Malformed, forward, and out-of-range references are rejected.

## Explicit limitations

- Only the first frame is decoded; animation playback is not implemented.
- Layer records are parsed and unpacked, but the returned bitmap uses the first visible layer. Layer-origin compositing and transparent-color keys are not applied.
- Randomized/encrypted LiveMaker Pro assets are rejected; no key guessing is attempted.
- JPEG layers are decoded through Android `BitmapFactory`.
- Output pixel count, compressed layer size, decompressed plane size, XML size, layer count, and block count are bounded.

## References

- `pylivemaker` GAL/GAL-X reader: https://github.com/pmrowla/pylivemaker/blob/master/src/livemaker/GalImagePlugin.py
- GARbro classic GAL reader: https://github.com/morkt/GARbro/blob/master/ArcFormats/LiveMaker/ImageGAL.cs
- GARbro GAL/X200 reader: https://github.com/morkt/GARbro/blob/master/ArcFormats/LiveMaker/ImageGALX.cs
