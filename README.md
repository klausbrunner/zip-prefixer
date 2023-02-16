# zip-prefixer

![CI](https://github.com/KlausBrunner/zip-prefixer/workflows/CI/badge.svg) [![Maven](https://img.shields.io/maven-central/v/net.e175.klaus/zip-prefixer?color=dodgerblue)](https://search.maven.org/search?q=g:net.e175.klaus%20a:zip-prefixer)

Java library to prefix a ZIP format file with arbitrary data without breaking internal offsets or rebuilding from
scratch. Java 8, zero runtime dependencies. Inspired by [clj-zip-meta](https://github.com/mbjarland/clj-zip-meta) and
[really-executable-jars-maven-plugin](https://github.com/brianm/really-executable-jars-maven-plugin).

## A note on ZIP64 support

Despite being introduced more than 20 years ago (as of this writing), ZIP64 extension support is spotty and 
inconsistent in many implementations. Different tools will create different outputs. Different tools 
will completely disagree on whether a given archive is corrupt or correct. This includes Info-ZIP's _zip_, _unzip_, and 
_zipinfo_ commands, the _zipdetails_ command, the _7z_ CLI, the _zipfile_ package in Python 3.10, various Java libraries 
(commons-compress, zip4j, as well as the standard library's ZIP classes), IO::Compress::Zip for Perl, Go's archive/zip
and probably  countless others. There seems to be no one true standard reference implementation except perhaps PKWARE's 
proprietary one, although the (somewhat dated) Info-ZIP tools and 7z seem to come close.

A common theme is that only parts of ZIP64 are supported. This is no surprise because there are several ZIP64-related 
artefacts, not all of which have to be present at the same time:

* There may be a ZIP64 End of Central Directory Locator (or not), which should point to a ZIP64 End of Central Directory Record.
* Completely independent of the above, Central Directory entries may have a ZIP64 Extended Information Extra Field. This is mandatory under a few circumstances (basically whenever ZIP's old 4-byte and 2-byte fields get too small to hold file sizes or offsets), but may also be used with smaller archives that could've fit into the old ZIP format.
* This ZIP64 Extended Information Extra Field may contain compressed and uncompressed file size as well as the Local File Header Offset (and the disk number), as well as any subset of these. Turns out that some implementations support only the size fields, but not the offset.
* And it's more or less the same story for each Local File Header again.
* To make matters more interesting, a Data Descriptor field may exist alternatively (or additionally?) to the EIEF and contain either 4-byte or 8-byte compressed/original sizes of the file.
* The "version to extract" field should give a hint that ZIP64 support is required to read a given file, but that's not terribly reliable either.

This tool here really only cares about internal offsets and doesn't touch anything else, and I believe it has 
implemented that aspect of ZIP and ZIP64 completely. However, it's hard to be sure and some ZIP readers may disagree.
