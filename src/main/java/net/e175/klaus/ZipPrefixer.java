package net.e175.klaus;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Queue;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

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
        try (BufferedOutputStream out = new BufferedOutputStream(Files.newOutputStream(targetPath))) {
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
        try (BufferedOutputStream out = new BufferedOutputStream(Files.newOutputStream(targetPath))) {
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
     * // FIXME: ZIP64 support is missing!!
     */
    public static void adjustZipOffsets(Path targetPath, long adjustment) throws IOException {
        final OpenOption[] openOpts = adjustment != 0 ?
                new OpenOption[]{StandardOpenOption.READ, StandardOpenOption.WRITE} :
                new OpenOption[]{};

        final Queue<Write> writeQueue = createWriteQueue();

        try (final SeekableByteChannel channel = Files.newByteChannel(targetPath, openOpts)) {
            // find EOCDR first; assuming it's at the very end of file or close to it
            PatternInstance eocdr = seek(EOCDR, channel, Long.MAX_VALUE, false)
                    .orElseThrow(() -> new IOException("Unable to locate EOCDR. Probably not a ZIP file."));
            LOG.fine(String.format("EOCDR offset: \"0x%08X\"", eocdr.position));

            long cdOffset = eocdr.getUnsignedInt("offsetOfStartOfCD");
            LOG.fine(String.format("original CD offset: \"0x%08X\"", cdOffset));
            if (adjustment != 0) {
                cdOffset += adjustment;
                writeQueue.add(eocdr.writeInt("offsetOfStartOfCD", (int) cdOffset));
            }

            int numberOfCdEntries = eocdr.getUnsignedShort("numberOfEntriesInCDonThisDisk");

            // now walk through central directory entries
            long sequentialOffset = cdOffset;
            for (int i = 0; i < numberOfCdEntries; i++) {
                PatternInstance cfh = read(CFH, channel, sequentialOffset)
                        .orElseThrow(() -> new IOException("Central file header for entry is not where it should be"));

                // for each central directory entry, look at the referenced local directory entry
                long lfhOffset = cfh.getUnsignedInt("relativeOffsetOfLocalHeader");
                if (adjustment != 0) {
                    lfhOffset += adjustment;
                    writeQueue.add(cfh.writeInt("relativeOffsetOfLocalHeader", (int) lfhOffset));
                }
                read(LFH, channel, lfhOffset)
                        .orElseThrow(() -> new IOException("Local file header for entry is not where it should be"));

                sequentialOffset += cfh.spec.size +
                        cfh.getUnsignedShort("fileNameLength") +
                        cfh.getUnsignedShort("extraFieldLength") +
                        cfh.getUnsignedShort("fileCommentLength");
            }

            applyWrites(writeQueue, channel);
        }
    }

    /**
     * Test if this looks like a good zip file, with proper offsets and retrievable files.
     */
    static Path looksLikeGoodZip(Path f) throws IOException {
        try (ZipFile zf = new ZipFile(f.toFile())) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                try (InputStream is = zf.getInputStream(entry)) {
                    int ignored = is.read();
                }
            }
        }
        return f;
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
        System.out.printf("prefixed %d bytes%n", prefixesLength);

        adjustZipOffsets(zipfile, prefixesLength);
        System.out.println("offsets adjusted");
    }

}
