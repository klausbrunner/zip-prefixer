#!/bin/bash
#
# Run integration tests with the built and packaged code by
# taking all JARs from the local Maven repo. This can take a while!
# Make sure to run mvn clean package before. This code assumes the 
# executable jar is in target/ and the Maven repo is in ~/.m2

set -e

# locate our executable JAR
zipfixer=$(find target/*.jar | sort -r | head -1)

workdir="${TMPDIR:-/tmp/}" # no $TMPDIR on Github Actions...
sourcedir="$HOME/.m2"
prefixfile="pom.xml"

function check_prefix_doublecheck() {
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
find "$sourcedir" -iname '*.jar' | while read -r file; do check_prefix_doublecheck "$file"; done
find "$sourcedir" -iname '*.jar' | wc -l
