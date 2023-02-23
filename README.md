# zip-prefixer

![CI](https://github.com/KlausBrunner/zip-prefixer/workflows/CI/badge.svg) [![Maven](https://img.shields.io/maven-central/v/net.e175.klaus/zip-prefixer?color=dodgerblue)](https://search.maven.org/search?q=g:net.e175.klaus%20a:zip-prefixer)

Java library to add a prefix (also called preamble) containing arbitrary data to ZIP format files without breaking internal offsets or rebuilding from scratch. Runs on Java 8 or newer, zero runtime dependencies.

## Why would I need this?

As a poor man's steganography, to create self-extracting ZIPs or, more likely for a Java library, [self-executing JARs](https://skife.org/java/unix/2011/06/20/really_executable_jars.html).

The ZIP format itself allows arbitrary content before the actual ZIP data. You can also do this with existing ZIPs 
by simply concatenating the additional bytes and the original ZIP file together. This, however, invalidates the internal file 
offsets and while many ZIP readers (including Java's jar command) can cope with this corruption in the case of simple 
ZIP files, they tend to fail with modern ZIP64s. 

One solution is to rebuild the ZIP file using an archiver library that knows about preambles (such as Apache commons compress). 
Another is to simply correct the offsets, which means the original ZIP remains mostly unchanged and the whole process 
is very fast. This is the goal of this library.

## Basic usage

See the [central repository](https://search.maven.org/search?q=a:zip-prefixer) for Maven coordinates and latest version.

````java
import net.e175.klaus.zip.ZipPrefixer;

Path zipFile = Paths.get("test.zip");

// add a prefix to the file (can be one or more byte arrays or files)
long addedBytes = ZipPrefixer.applyPrefixesToZip(zipFile, "hello, world".getBytes(StandardCharsets.UTF_8));

// optional: check integrity of the resulting ZIP file again
ZipPrefixer.validateZipOffsets(zipFile);
````
Check the Javadoc for further methods.

## Implementation approach

- Scan from EOF backwards for the End of Central Directory Record ("EndFirst" style as per [this terminology](https://gynvael.coldwind.pl/?id=682)). 
- Strictly use the Central Directory to find Local File Headers. No scanning around for LFHs. If the LFH isn't exactly where we expect it to be, abort.
- No validation of LFH vs. CFH entry content (e.g. filename, sizes).
- Rewrite only offsets, not a single other byte must be touched.
- If the new offset would cross the threshold from 4-byte to 8-byte values (i.e. the ZIP's getting larger than about 4GBs and isn't already using ZIP64 offsets), bail out. This would require a more substantial rewrite of the ZIP, which is currently out of the library's scope.
