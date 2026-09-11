#!/usr/bin/env bash
# Builds build/afterimage-<version>.jar. See README ("Building").
set -euo pipefail
cd "$(dirname "$0")"

JDK="${JAVA8_HOME:?set JAVA8_HOME to a JDK 8}"
VERSION=$(sed -n 's/.*VERSION = "\([^"]*\)".*/\1/p' src/mod/java/ru/arthaix/afterimage/Afterimage.java | head -1)
SEP=":"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";" ;; esac

for jar in mixinbooter-10.7.jar forge-1.12.2-srg.jar forge-1.12.2-universal.jar forge-1.12.2-dev.jar lwjgl-2.9.4.jar; do
    [ -f "libs/$jar" ] || { echo "missing libs/$jar (see README, Building)"; exit 1; }
done

CLASSES=build/classes
rm -rf build
mkdir -p "$CLASSES"

# pass 1: everything that touches Minecraft, compiled against the SRG-named jar
"$JDK/bin/javac" -proc:none -source 8 -target 8 -encoding UTF-8 -nowarn \
    -cp "libs/mixinbooter-10.7.jar${SEP}libs/forge-1.12.2-srg.jar${SEP}libs/forge-1.12.2-universal.jar${SEP}libs/lwjgl-2.9.4.jar" \
    -d "$CLASSES" $(find src/main/java -name '*.java')

# pass 2: the @Mod class, which only uses Forge's own API, compiled against the dev jar
"$JDK/bin/javac" -proc:none -source 8 -target 8 -encoding UTF-8 -nowarn \
    -cp "${CLASSES}${SEP}libs/forge-1.12.2-dev.jar" \
    -d "$CLASSES" $(find src/mod/java -name '*.java')

cp src/main/resources/mixins.afterimage.json "$CLASSES/"
sed "s/\${version}/$VERSION/" src/main/resources/mcmod.info > "$CLASSES/mcmod.info"
"$JDK/bin/jar" cfm "build/afterimage-$VERSION.jar" src/main/resources/META-INF/MANIFEST.MF -C "$CLASSES" .
echo "built build/afterimage-$VERSION.jar"
