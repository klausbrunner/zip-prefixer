#!/bin/bash
#
# Run integration tests with the built and packaged code by
# taking all JARs from the local Maven repo, as well as the unit
# test JARs to get a second opinion. This can take a while!
# Make sure to run mvn clean package before. This code assumes the 
# executable jar is in target/ and the Maven repo is in ~/.m2
# and zip, unzip, 7z are on the PATH.

set -e

# locate our executable JAR
zipfixer=$(find target/*.jar | sort -r | head -1)
echo "*** working with $zipfixer"

workdir="${TMPDIR:-/tmp/}" # no $TMPDIR on Github Actions...
jar_sourcedir="$HOME/.m2"
zipgz_sourcedir="src/test/resources"
prefixfile="pom.xml"

function check_prefix_doublecheck_jar() {
  echo "copying $1 to $workdir"
  cp "$1" "$workdir"
  tmpfile="$workdir$(basename "$1")"

  unzip -qq -t "$tmpfile" || exit 1

  java -jar "$zipfixer" "$tmpfile" "$prefixfile" || exit 1

  unzip -qq -t "$tmpfile" || exit 1
  zip -T "$tmpfile" || exit 1
  jar -t -f "$tmpfile" >/dev/null || exit 1
  7z t "$tmpfile" >/dev/null || exit 1

  rm "$tmpfile"
}
echo "*** found" "$(find "$jar_sourcedir" -iname '*.jar' | wc -l)" " jars to process"
find "$jar_sourcedir" -iname '*.jar' | while read -r file; do check_prefix_doublecheck_jar "$file"; done

function check_prefix_doublecheck_zipgz() {
  echo "copying $1 to $workdir"
  cp "$1" "$workdir"
  tmpfile="$workdir$(basename "$1" .gz)"
  gunzip "$tmpfile".gz

  unzip -qq -P secret -t "$tmpfile" || exit 1

  java -jar "$zipfixer" "$tmpfile" "$prefixfile" || exit 1

  unzip -qq -P secret -t "$tmpfile" || exit 1
  # zip can't handle -T and get its password preset for encrypted archives :-(
  7z t -psecret "$tmpfile" >/dev/null || exit 1

  rm "$tmpfile"
}
echo "*** found" "$(find "$zipgz_sourcedir" -iname '*.zip.gz' | wc -l)" " .zip.gz files to process"
find "$zipgz_sourcedir" -iname '*.zip.gz' | while read -r file; do check_prefix_doublecheck_zipgz "$file"; done
