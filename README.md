# zip-prefixer

Java library to prefix a ZIP format file with arbitrary data without breaking internal offsets or rebuilding from
scratch.
Java 8, zero runtime dependencies. Inspired by [clj-zip-meta](https://github.com/mbjarland/clj-zip-meta) and
[really-executable-jars-maven-plugin](https://github.com/brianm/really-executable-jars-maven-plugin).

Status: work in progress, proof-of-concept maturity. Open issues include:

* Testing is rudimentary. Need a larger zoo of ZIPs created by various tools to get some confidence.
* The code needs a serious refactoring (ZIP64 support made it messy).
* Performance is unclear. It's apparently not horrible, and some care has been taken to avoid memory
  bloat and excessive I/O, but it hasn't been examined seriously.
