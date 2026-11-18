# Contract PDF font

`NotoSansSC-Regular.ttf` is a static, 400-weight derivative of Google's Noto Sans SC,
downloaded from https://github.com/google/fonts/tree/main/ofl/notosanssc on 2026-11-18.
It is distributed under the SIL Open Font License in `OFL-NotoSansSC.txt`.

The internal family name is `GuardFindContractSans`. Duplicate CJK radical and
compatibility code-point aliases for unified ideographs were removed from cmap
to preserve standard Chinese text when PDFBox builds the PDF ToUnicode mapping.
The glyph outlines are unchanged. PDFBox embeds only the glyphs used in a contract.
