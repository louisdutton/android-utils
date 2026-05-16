# Building and using ICU

To use ICU code, define `UCONFIG_USE_LOCAL` and add three include search paths:

1. `$(OMIM_ROOT)/3party/icu`
2. `$(OMIM_ROOT)/3party/icu/icu/icu4c/source/common`
3. `$(OMIM_ROOT)/3party/icu/icu/icu4c/source/i18n`

Only the necessary sources are included for bidi and transliteration. Add other
sources only when more ICU functionality is needed.

# Building and updating `icudtXXl.dat`

After updating ICU, also update the `data/icudtXXl.dat` file, where `XX` is the
ICU version.

```bash
mkdir build && cd build
ICU_DATA_FILTER_FILE=../icu_filter.json ../icu/icu4c/source/./configure --disable-shared --enable-static --disable-renaming --disable-extras --disable-icuio --disable-tests --disable-samples --with-data-packaging=archive
make -j$(nproc)
cp data/out/icudt??l.dat ../../../data/
```

Delete the old `.dat` file in `$(OMIM_ROOT)/data`, update the Android asset
symlink, and update references in the code:

```text
indexer/transliteration_loader.cpp
16:  char const kICUDataFile[] = "icudt69l.dat";

android/script/replace_links.bat
42:cp -r ../data/icudt69l.dat assets/
```
