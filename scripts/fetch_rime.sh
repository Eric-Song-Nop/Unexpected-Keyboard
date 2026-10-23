#!/usr/bin/env bash
# Download the prebuilt librime static libraries (and their dependencies) and
# the default Rime data files needed to build the Rime input support.
#
# Prebuilt libraries come from https://github.com/fcitx5-android/prebuilt and
# data files from the https://github.com/rime organisation. Revisions are
# pinned for reproducibility.
#
# Usage: scripts/fetch_rime.sh [--force]
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
PREBUILT_DIR="$ROOT/vendor/rime-prebuilt"
ASSETS_DIR="$ROOT/vendor/rime-assets/rime"
STAMP="$ROOT/vendor/rime-prebuilt/.stamp"

PREBUILT_REV=3f987c60ae1485a7b2a2cf7db301726af4700f29
PRELUDE_REV=082425ea0684bca36474415d4a0e8db9b016487e
ESSAY_REV=054920de4f54c9e5994276a96a4fc2a35cb51aa3
LUNA_PINYIN_REV=56b934b099dfbeab842320f13aa8b461a6ab3e42
STROKE_REV=1e8fff9b9494ddec23b0cbc526bcfd8171a6fd48

STAMP_CONTENT="$PREBUILT_REV $PRELUDE_REV $ESSAY_REV $LUNA_PINYIN_REV $STROKE_REV"

if [[ "${1:-}" != "--force" && -f "$STAMP" && "$(cat "$STAMP")" == "$STAMP_CONTENT" ]]; then
  exit 0
fi

ABIS=(arm64-v8a armeabi-v7a x86 x86_64)
LIBS=(
  "librime:lib/librime.a"
  "glog:lib/libglog.a"
  "leveldb:lib/libleveldb.a"
  "lua:lib/liblua_static.a"
  "marisa:lib/libmarisa.a"
  "opencc:lib/libopencc.a"
  "yaml-cpp:lib/libyaml-cpp.a"
)
RIME_HEADERS=(rime_api.h rime_api_deprecated.h rime_api_stdbool.h rime_levers_api.h)

fetch() # url dest
{
  mkdir -p "$(dirname "$2")"
  echo "Fetching $1"
  curl -fsSL --retry 3 -o "$2.tmp" "$1"
  mv "$2.tmp" "$2"
}

PREBUILT_URL="https://raw.githubusercontent.com/fcitx5-android/prebuilt/$PREBUILT_REV"
# Large files are stored with Git LFS on some mirrors, use the media endpoint
# when the raw endpoint doesn't serve the binary.
PREBUILT_MEDIA_URL="https://media.githubusercontent.com/media/fcitx5-android/prebuilt/$PREBUILT_REV"

fetch_prebuilt() # path
{
  local dest="$PREBUILT_DIR/$1"
  fetch "$PREBUILT_URL/$1" "$dest"
  # Detect Git LFS pointers
  if head -c 64 "$dest" | grep -q "git-lfs"; then
    fetch "$PREBUILT_MEDIA_URL/$1" "$dest"
  fi
}

for abi in "${ABIS[@]}"; do
  for entry in "${LIBS[@]}"; do
    lib=${entry%%:*}
    path=${entry#*:}
    fetch_prebuilt "$lib/$abi/$path"
  done
  for h in "${RIME_HEADERS[@]}"; do
    fetch_prebuilt "librime/$abi/include/$h"
  done
done

# Rime data
rm -rf "$ASSETS_DIR"
mkdir -p "$ASSETS_DIR/shared/opencc"

RIME_URL="https://raw.githubusercontent.com/rime"
for f in key_bindings.yaml punctuation.yaml symbols.yaml default.yaml; do
  fetch "$RIME_URL/rime-prelude/$PRELUDE_REV/$f" "$ASSETS_DIR/shared/$f"
done
fetch "$RIME_URL/rime-essay/$ESSAY_REV/essay.txt" "$ASSETS_DIR/shared/essay.txt"
for f in luna_pinyin.dict.yaml luna_pinyin.schema.yaml luna_pinyin_simp.schema.yaml \
    luna_pinyin_fluency.schema.yaml luna_pinyin_tw.schema.yaml \
    luna_quanpin.schema.yaml pinyin.yaml; do
  fetch "$RIME_URL/rime-luna-pinyin/$LUNA_PINYIN_REV/$f" "$ASSETS_DIR/shared/$f"
done
for f in stroke.dict.yaml stroke.schema.yaml; do
  fetch "$RIME_URL/rime-stroke/$STROKE_REV/$f" "$ASSETS_DIR/shared/$f"
done

# Only list the schemas that are shipped. Users can override this with a
# 'default.custom.yaml' file in the user data directory.
python3 - "$ASSETS_DIR/shared/default.yaml" <<'EOF'
import re, sys
path = sys.argv[1]
src = open(path, encoding="utf-8").read()
schemas = ["luna_pinyin_simp", "luna_pinyin", "luna_pinyin_fluency",
           "luna_pinyin_tw", "luna_quanpin", "stroke"]
block = "schema_list:\n" + "".join("  - schema: %s\n" % s for s in schemas)
src, n = re.subn(r"^schema_list:\n(?:[ \t]+.*\n|#.*\n|\n)*?(?=^\S)", block, src,
                 count=1, flags=re.M)
if n != 1:
    sys.exit("Failed to patch schema_list in default.yaml")
open(path, "w", encoding="utf-8").write(src)
EOF

OPENCC_FILES=(
  HKVariants.ocd2 HKVariantsRev.ocd2 HKVariantsRevPhrases.ocd2
  JPShinjitaiCharacters.ocd2 JPShinjitaiPhrases.ocd2 JPVariants.ocd2
  JPVariantsRev.ocd2 STCharacters.ocd2 STPhrases.ocd2 TSCharacters.ocd2
  TSPhrases.ocd2 TWPhrases.ocd2 TWPhrasesRev.ocd2 TWVariants.ocd2
  TWVariantsRev.ocd2 TWVariantsRevPhrases.ocd2
  hk2s.json hk2t.json jp2t.json s2hk.json s2t.json s2tw.json s2twp.json
  t2hk.json t2jp.json t2s.json t2tw.json tw2s.json tw2sp.json tw2t.json
)
for f in "${OPENCC_FILES[@]}"; do
  fetch_prebuilt "opencc/data/$f"
  cp "$PREBUILT_DIR/opencc/data/$f" "$ASSETS_DIR/shared/opencc/$f"
done

# List of files, used to extract the assets at runtime.
(cd "$ASSETS_DIR" && find shared -type f | LC_ALL=C sort > files.txt)

echo "$STAMP_CONTENT" > "$STAMP"
echo "Rime prebuilt libraries and data are ready."
