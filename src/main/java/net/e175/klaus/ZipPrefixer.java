package net.e175.klaus;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.zip.ZipException;

import static net.e175.klaus.BinaryMapper.*;

public final class ZipPrefixer {

    private ZipPrefixer() {
    }

    private static final Logger LOG = Logger.getLogger(ZipPrefixer.class.getName());

    /* These specs are based on
            APPNOTE.TXT - .ZIP File Format Specification
            Version: 6.3.10
            Revised: Nov 01, 2022
            https://pkware.cachefly.net/webdocs/casestudies/APPNOTE.TXT
    */

    static final PatternSpec EOCDR = new PatternSpec(ByteOrder.LITTLE_ENDIAN,
            FieldSpec.of(4, "eocdrSignature", new byte[]{0x50, 0x4b, 0x05, 0x06}),
            FieldSpec.of(2, "numberOfThisDisk"),
            FieldSpec.of(2, "numberOfStartDiskOfCD"),
            FieldSpec.of(2, "numberOfEntriesInCDonThisDisk"),
            FieldSpec.of(2, "totalNumberOfEntriesInCD"),
            FieldSpec.of(4, "sizeOfCD"),
            FieldSpec.of(4, "offsetOfStartOfCD"),
            FieldSpec.of(2, "commentLength"));

    static final PatternSpec CFH = new PatternSpec(ByteOrder.LITTLE_ENDIAN,
            FieldSpec.of(4, "centralFileHeader", new byte[]{0x50, 0x4b, 0x01, 0x02}),
            FieldSpec.of(2, "versionMadeBy"),
            FieldSpec.of(2, "versionNeededToExtract"),
            FieldSpec.of(2, "generalPurposeBitFlag"),
            FieldSpec.of(2, "compressionMethod"),
            FieldSpec.of(2, "lastModFileTime"),
            FieldSpec.of(2, "lastModFileDate"),
            FieldSpec.of(4, "crc32"),
            FieldSpec.of(4, "compressedSize"),
            FieldSpec.of(4, "uncompressedSize"),
            FieldSpec.of(2, "fileNameLength"),
            FieldSpec.of(2, "extraFieldLength"),
            FieldSpec.of(2, "fileCommentLength"),
            FieldSpec.of(2, "diskNumberStart"),
            FieldSpec.of(2, "internalFileAttributes"),
            FieldSpec.of(4, "externalFileAttributes"),
            FieldSpec.of(4, "relativeOffsetOfLocalHeader"));

    static final PatternSpec LFH = new PatternSpec(ByteOrder.LITTLE_ENDIAN,
            FieldSpec.of(4, "centralFileHeader", new byte[]{0x50, 0x4b, 0x03, 0x04}),
            FieldSpec.of(2, "versionNeededToExtract"),
            FieldSpec.of(2, "generalPurposeBitFlag"),
            FieldSpec.of(2, "compressionMethod"),
            FieldSpec.of(2, "lastModFileTime"),
            FieldSpec.of(2, "lastModFileDate"),
            FieldSpec.of(4, "crc32"),
            FieldSpec.of(4, "compressedSize"),
            FieldSpec.of(4, "uncompressedSize"),
            FieldSpec.of(2, "fileNameLength"),
            FieldSpec.of(2, "extraFieldLength"));

    static final PatternSpec ZIP64_EOCDL = new PatternSpec(ByteOrder.LITTLE_ENDIAN,
            FieldSpec.of(4, "zip64EOCDLSignature", new byte[]{0x50, 0x4b, 0x06, 0x07}),
            FieldSpec.of(4, "numberOfDiskWithStartOfZip64EOCDL"),
            FieldSpec.of(8, "relativeOffsetOfZip64EOCDR"),
            FieldSpec.of(4, "totalNumberOfDisks"));

    static final PatternSpec ZIP64_EOCDR = new PatternSpec(ByteOrder.LITTLE_ENDIAN,
            FieldSpec.of(4, "zip64EOCDLSignature", new byte[]{0x50, 0x4b, 0x06, 0x06}),
            FieldSpec.of(8, "sizeOfZip64eocdr"),
            FieldSpec.of(2, "versionMadeBy"),
            FieldSpec.of(2, "versionNeededToExtract"),
            FieldSpec.of(4, "numberOfThisDisk"),
            FieldSpec.of(4, "numberOfStartDiskOfCD"),
            FieldSpec.of(8, "numberOfEntriesInCDonThisDisk"),
            FieldSpec.of(8, "totalNumberOfEntriesInCD"),
            FieldSpec.of(8, "sizeOfCD"),
            FieldSpec.of(8, "offsetOfStartOfCD"));

    // Zip64 Extended Information Extra Field
    static final FieldSpec ZIP64_EIEF_SIGNATURE = FieldSpec.of(2, "zip64EIEFSignature", new byte[]{0x01, 0x00});

    /**
     * Prepend prefix(es) to an existing file. This method does not care about file types or contents, it just
     * mechanically glues the bytes together.
     *
     * @param targetPath Target file. Must be writeable, in a writeable directory.
     * @param prefixes   Binary prefixes that will be sequentially written before the original file's contents.
     * @return Total number of bytes written as prefixes.
     * @throws IOException on I/O related errors
     */
    public static long applyPrefixes(Path targetPath, byte[]... prefixes) throws IOException {
        if (prefixes.length < 1) {
            return 0;
        }

        Path original = targetPath.resolveSibling(targetPath.getFileName() + ".original");
        Files.move(targetPath, original);
        long prefixesLength = 0;
        try (OutputStream out = Files.newOutputStream(targetPath)) {
            for (byte[] prefix : prefixes) {
                out.write(prefix);
                prefixesLength += prefix.length;
            }
            Files.copy(original, out);
        }
        Files.deleteIfExists(original);
        return prefixesLength;
    }

    /**
     * Prepend prefix files to an existing file. This method does not care about file types or contents, it just
     * mechanically glues the bytes together.
     *
     * @param targetPath  Target file. Must be writeable, in a writeable directory.
     * @param prefixFiles Prefix files that will be sequentially written before the original file's contents.
     * @return Total number of bytes written as prefixes.
     * @throws IOException on I/O related errors
     */
    public static long applyPrefixes(Path targetPath, List<Path> prefixFiles) throws IOException {
        if (prefixFiles.isEmpty()) {
            return 0;
        }

        Path original = targetPath.resolveSibling(targetPath.getFileName() + ".original");
        Files.move(targetPath, original);
        long prefixesLength = 0;
        try (OutputStream out = Files.newOutputStream(targetPath)) {
            for (Path prefixFile : prefixFiles) {
                prefixesLength += Files.copy(prefixFile, out);
            }
            Files.copy(original, out);
        }
        Files.deleteIfExists(original);
        return prefixesLength;
    }

    /**
     * Validate offsets in ZIP file. Throws exception if they aren't correct.
     */
    public static void validateZipOffsets(Path targetPath) throws IOException {
        adjustZipOffsets(targetPath, 0);
    }

    /**
     * Adjust offsets in ZIP file (with validation). If adjustment is 0, don't write anything, just validate.
     */
    public static void adjustZipOffsets(Path targetPath, long adjustment) throws IOException {
        final OpenOption[] openOpts = adjustment != 0 ?
                new OpenOption[]{StandardOpenOption.READ, StandardOpenOption.WRITE} :
                new OpenOption[]{};

        final Queue<Write> writeQueue = createWriteQueue();

        try (final SeekableByteChannel channel = Files.newByteChannel(targetPath, openOpts)) {
            // find EOCDR first; assuming it's at the very end of file or close to it
            PatternInstance eocdr = seek(EOCDR, channel, Long.MAX_VALUE, false)
                    .orElseThrow(() -> new ZipException("Unable to locate EOCDR. Probably not a ZIP file."));
            LOG.fine(String.format("EOCDR found at offset: \"0x%08X\"", eocdr.position));
            boolean requiresZip64 = false;

            long cdOffset = eocdr.getUnsignedInt("offsetOfStartOfCD");
            if (cdOffset != 0xFF_FF_FF_FFL) {
                if (adjustment != 0) {
                    cdOffset += adjustment;
                    writeQueue.add(eocdr.writeInt("offsetOfStartOfCD", (int) cdOffset));
                }
            } else {
                requiresZip64 = true;
            }

            long numberOfCdEntries = eocdr.getUnsignedShort("numberOfEntriesInCDonThisDisk");
            if (numberOfCdEntries == 0xFF_FF) {
                requiresZip64 = true;
            }

            // see if we can find a ZIP64 central directory (regardless of whether it's required or not)
            // first, find the ZIP 64 EOCDL
            Optional<PatternInstance> zip64eocdlO = read(ZIP64_EOCDL, channel, eocdr.position - ZIP64_EOCDL.size);
            if (!zip64eocdlO.isPresent() && requiresZip64) {
                throw new ZipException("This archive lacks a ZIP64 EOCDL, which is required according to its EOCDR.");
            } else if (zip64eocdlO.isPresent()) {
                // from this point on, we should definitely expect a ZIP64 central record
                // now get the ZIP64 EOCDR
                PatternInstance zip64eocdl = zip64eocdlO.get();
                LOG.fine(String.format("ZIP64 EOCDL found at offset: \"0x%08X\"", zip64eocdl.position));
                cdOffset = zip64eocdl.getLong("relativeOffsetOfZip64EOCDR");
                if (adjustment != 0) {
                    cdOffset += adjustment;
                    writeQueue.add(zip64eocdl.writeLong("relativeOffsetOfZip64EOCDR", cdOffset));
                }

                PatternInstance zip64eocdr = read(ZIP64_EOCDR, channel, cdOffset).orElseThrow(() ->
                        new ZipException("Unable to find the ZIP64 EOCDR in the location given by ZIP64 EOCDL."));
                cdOffset = zip64eocdr.getLong("offsetOfStartOfCD");
                if (adjustment != 0) {
                    cdOffset += adjustment;
                    writeQueue.add(zip64eocdr.writeLong("offsetOfStartOfCD", cdOffset));
                }

                numberOfCdEntries = zip64eocdr.getLong("numberOfEntriesInCDonThisDisk");
            }

            // now walk through central directory entries
            long sequentialOffset = cdOffset;
            final ByteBuffer cfhBuffer = CFH.bufferFor();
            final ByteBuffer lfhBuffer = LFH.bufferFor();
            for (int i = 0; i < numberOfCdEntries; i++) {
                PatternInstance cfh = read(CFH, channel, sequentialOffset, cfhBuffer)
                        .orElseThrow(() -> new ZipException("Central file header for entry is not where it should be"));

                // skip over filename and position on any extra fields
                sequentialOffset += cfh.spec.size + cfh.getUnsignedShort("fileNameLength");
                final int extraFieldLength = cfh.getUnsignedShort("extraFieldLength");

                // now look at the offset of the local header. in simple cases, this is right inside the CDR,
                // in ZIP64 cases it *could* be in the extended field.
                long lfhOffset = cfh.getUnsignedInt("relativeOffsetOfLocalHeader");
                if (lfhOffset != 0xFF_FF_FF_FFL) {
                    if (adjustment != 0) {
                        lfhOffset += adjustment;
                        writeQueue.add(cfh.writeInt("relativeOffsetOfLocalHeader", (int) lfhOffset));
                    }
                } else {
                    // ZIP64 offset it is; now we need to determine the expected format of the ZIP64 EIEF,
                    // which depends on the fields set to all-1s.
                    List<FieldSpec> requiredFieldsInEIEF = new ArrayList<>(5);
                    requiredFieldsInEIEF.add(ZIP64_EIEF_SIGNATURE);
                    requiredFieldsInEIEF.add(FieldSpec.of(2, "size"));
                    if (cfh.getUnsignedInt("uncompressedSize") == 0xFF_FF_FF_FFL) {
                        requiredFieldsInEIEF.add(FieldSpec.of(8, "uncompressedSize"));
                    }
                    if (cfh.getUnsignedInt("compressedSize") == 0xFF_FF_FF_FFL) {
                        requiredFieldsInEIEF.add(FieldSpec.of(8, "compressedSize"));
                    }
                    requiredFieldsInEIEF.add(FieldSpec.of(8, "relativeOffsetOfLocalHeader"));

                    PatternSpec requiredZip64EIEF = new PatternSpec(ByteOrder.LITTLE_ENDIAN,
                            requiredFieldsInEIEF.toArray(new FieldSpec[0]));

                    PatternInstance zip64eief = seek(requiredZip64EIEF, channel, sequentialOffset,
                            // extra fields are separated by header (2+2) plus size
                            (patternInstance -> (long) patternInstance.getUnsignedShort("size") + 4),
                            sequentialOffset,
                            sequentialOffset + extraFieldLength).orElseThrow(
                            () -> new ZipException("missing ZIP64 extra fields in CFH"));

                    // additional validation of length
                    if (zip64eief.getUnsignedShort("size")
                            < (requiredZip64EIEF.nameToFSI.size() - 2) * 8) {
                        throw new ZipException("ZIP64 extra fields in CFH seem to exist, but are too small.");
                    }

                    lfhOffset = zip64eief.getLong("relativeOffsetOfLocalHeader");
                    if (adjustment != 0) {
                        lfhOffset += adjustment;
                        writeQueue.add(zip64eief.writeLong("relativeOffsetOfLocalHeader", lfhOffset));
                    }
                }

                read(LFH, channel, lfhOffset, lfhBuffer)
                        .orElseThrow(() -> new ZipException("Local file header for entry is not where it should be"));

                sequentialOffset += extraFieldLength +
                        cfh.getUnsignedShort("fileCommentLength");
            }

            applyWrites(writeQueue, channel); // TODO: move writing out of this method
        }
    }

    /**
     * Quick, cheap check if this looks like any kind of zip file.
     */
    static Path looksLikeZip(Path f) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(f)) {
            seek(EOCDR, channel, Long.MAX_VALUE, false)
                    .orElseThrow(() -> new ZipException("Unable to locate EOCDR. This is probably not a ZIP file, or a broken one."));
        }
        return f;
    }

    static Path isUsableFile(Path f) throws IOException {
        if (!Files.isRegularFile(f) && !Files.isReadable(f)) {
            throw new IOException("path " + f + " is not a regular, readable file");
        }
        return f;
    }

    /**
     * Rudimentary CLI intended for testing only.
     */
    public static void main(String... args) throws IOException {
        if (args.length < 1) {
            System.out.println("usage: zip-prefixer zipfile [prefixfile ...]");
            System.exit(1);
        }

        Path zipfile = Paths.get(args[0]);
        looksLikeZip(isUsableFile(zipfile));

        List<Path> paths = Arrays.stream(args, 1, args.length)
                .map(Paths::get)
                .collect(Collectors.toList());

        final long prefixesLength = applyPrefixes(zipfile, paths);

        adjustZipOffsets(zipfile, prefixesLength);
        System.out.printf("prefixed %d bytes on %s%n", prefixesLength, zipfile);
    }

}
