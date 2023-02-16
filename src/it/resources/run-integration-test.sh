#!/bin/bash
#
# Run integration tests with the built and packaged code by
# taking all JARs from the local Maven repo, as well as the unit
# test JARs to get a second opinion. This can take a while!
# Make sure to run mvn clean package before. This code assumes the 
# executable jar is in target/ and the Maven repo is in ~/.m2

set -e

# locate our executable JAR
zipfixer=$(find target/*.jar | sort -r | head -1)

workdir="${TMPDIR:-/tmp/}" # no $TMPDIR on Github Actions...
jar_sourcedir="$HOME/.m2"
zipgz_sourcedir="src/test/resources"
prefixfile="pom.xml"

function check_prefix_doublecheck_jar() {
  echo "copying $1 to $workdir"
  cp "$1" "$workdir"
  tmpfile="$workdir$(basename "$1")"

  unzip -qq -t "$tmpfile"

  java -jar "$zipfixer" "$tmpfile" "$prefixfile"

  unzip -qq -t "$tmpfile"
  zip -T "$tmpfile"
  jar -t -f "$tmpfile" >/dev/null

  rm "$tmpfile"
}
echo "*** found" "$(find "$jar_sourcedir" -iname '*.jar' | wc -l)" " jars to process"
find "$jar_sourcedir" -iname '*.jar' | while read -r file; do check_prefix_doublecheck_jar "$file"; done

function check_prefix_doublecheck_zipgz() {
  echo "copying $1 to $workdir"
  cp "$1" "$workdir"
  tmpfile="$workdir$(basename "$1" .gz)"
  gunzip "$tmpfile".gz

  unzip -qq -t "$tmpfile"

  java -jar "$zipfixer" "$tmpfile" "$prefixfile"

  unzip -qq -t "$tmpfile"
  zip -T "$tmpfile"

  rm "$tmpfile"
}
echo "*** found" "$(find "$zipgz_sourcedir" -iname '*.zip.gz' | wc -l)" " .zip.gz files to process"
find "$zipgz_sourcedir" -iname '*.zip.gz' | while read -r file; do check_prefix_doublecheck_zipgz "$file"; done
