import Compression
import Foundation

enum PaprikaRead: Equatable {
    case success(PaprikaContents)
    case failure(PaprikaError)
}

/// Reads a `.paprikarecipes` file (#382; Android's `PaprikaArchive`, which has java.util.zip): a
/// zip with one `<name>.paprikarecipe` entry per recipe, each a gzip-compressed JSON object
/// (`PaprikaRecipeParser`). The app has no third-party dependencies and Foundation has no zip
/// API, so this is a small zip reader over Apple's Compression framework (COMPRESSION_ZLIB reads
/// raw DEFLATE, which is what both a zip entry and a gzip body hold), like `BackupArchive`'s,
/// but with the limits a stranger's file needs: an entry's declared size is a claim, so every
/// unpacking counts what it really produces and stops at the cap. Entries are unpacked in
/// memory one at a time, an entry's name is only ever compared (never used as a path), and
/// nothing is written to disk. Entries that aren't recipes (folders, macOS's `__MACOSX/._…`
/// copies, a readme) are ignored and not counted. One broken entry is counted as unreadable and
/// the rest still import; a broken zip imports nothing.
///
/// It lives outside the pure logic (`CoreImportsTests` allows Foundation only there) because of
/// Compression; the parsing it hands to is pure.
enum PaprikaZipReader {
    private static let ext = ".paprikarecipe"
    private static let localSignature: UInt32 = 0x0403_4B50
    private static let centralSignature: UInt32 = 0x0201_4B50
    private static let endSignature: UInt32 = 0x0605_4B50

    /// True for an entry that may be a recipe: a file named `*.paprikarecipe`, not a hidden or macOS copy.
    static func isRecipeEntry(_ name: String) -> Bool {
        if name.hasSuffix("/") || name.hasPrefix("__MACOSX/") || name.contains("/__MACOSX/") { return false }
        let file = name.split(separator: "/", omittingEmptySubsequences: false).last.map(String.init) ?? name
        return !file.hasPrefix(".") && file.lowercased().hasSuffix(ext)
    }

    struct Entry {
        let name: String
        let method: UInt16
        let compressedSize: UInt32
        let size: UInt32
        let localOffset: UInt32
    }

    /// `storePhoto` (#386) is handed each recipe's picture, decoded from base64 and within
    /// `PaprikaLimits.maxPhotoBytes`, as it is read, so no picture waits in memory for the rest of the
    /// file; it answers the stored file's name, or nil for a picture that isn't one (that photo is left
    /// out and counted, the recipe stays). Called on this thread, once per recipe.
    static func read(
        _ zip: Data, limits: PaprikaLimits = PaprikaLimits(), storePhoto: ((Data) -> String?)? = nil
    ) -> PaprikaRead {
        guard zip.count >= 4, u32(zip, 0) == localSignature else { return .failure(.notAPaprikaFile) }
        guard zip.count <= limits.maxFileBytes else { return .failure(.tooLarge) }
        guard let (entries, declared) = centralDirectory(zip) else { return .failure(.damaged) }
        if declared > limits.maxEntries { return .failure(.tooLarge) }

        var recipes: [PaprikaRecipe] = []
        var notRecipes = 0
        var unreadable = 0
        var candidates = 0
        var photosLeftOut = 0
        var total = 0
        for entry in entries {
            guard isRecipeEntry(entry.name) else { continue }
            candidates += 1
            // A declared size is a claim: over the cap it is refused without unpacking anything.
            if Int(entry.size) > limits.maxEntryBytes || Int(entry.compressedSize) > limits.maxEntryBytes {
                unreadable += 1
                continue
            }
            guard let gzip = contents(of: entry, in: zip, max: limits.maxEntryBytes),
                  let bytes = gunzip(gzip, max: limits.maxEntryBytes) else {
                unreadable += 1
                continue
            }
            total += bytes.count
            if total > limits.maxTotalBytes { return .failure(.tooLarge) }
            guard let json = String(data: bytes, encoding: .utf8) else {
                unreadable += 1
                continue
            }
            switch PaprikaRecipeParser.parse(json) {
            case .recipe(var recipe):
                if let data = recipe.photoData {
                    let stored = decodePhoto(data, max: limits.maxPhotoBytes).flatMap { storePhoto?($0) }
                    if stored == nil { photosLeftOut += 1 }
                    recipe.photoData = nil
                    recipe.photoFile = stored
                }
                recipes.append(recipe)
            case .notARecipe: notRecipes += 1
            case .unreadable: unreadable += 1
            }
        }
        if candidates == 0 { return .failure(.notAPaprikaFile) }
        return .success(PaprikaContents(recipes: recipes, notRecipes: notRecipes, unreadable: unreadable, photosLeftOut: photosLeftOut))
    }

    /// A picture's bytes from its base64 text (line breaks allowed), or nil if it isn't base64 or would decode past `max`.
    private static func decodePhoto(_ text: String, max: Int) -> Data? {
        // Four characters make at most three bytes: refuse before decoding what could not fit.
        if text.utf8.count / 4 * 3 > max + 3 { return nil }
        var clean = String(text.filter { !$0.isWhitespace })
        // Padding is optional, as Java's decoder has it.
        if clean.utf8.count % 4 != 1 { clean += String(repeating: "=", count: (4 - clean.utf8.count % 4) % 4) }
        guard let data = Data(base64Encoded: clean), !data.isEmpty, data.count <= max else { return nil }
        return data
    }

    // MARK: Zip structure

    /// The entries the End Of Central Directory record lists, and how many it says there are; nil
    /// if there is no record, or the directory is cut short or broken (a truncated file).
    static func centralDirectory(_ zip: Data) -> (entries: [Entry], declared: Int)? {
        guard zip.count >= 22 else { return nil }
        var eocd = zip.count - 22
        let lowest = max(0, zip.count - 22 - 65_535)
        while eocd >= lowest, u32(zip, eocd) != endSignature { eocd -= 1 }
        guard eocd >= lowest else { return nil }
        let count = Int(u16(zip, eocd + 10))
        // 0xFFFF says "see the zip64 record", which a recipe export never needs.
        if count == 0xFFFF { return nil }
        var at = Int(u32(zip, eocd + 16))
        var entries: [Entry] = []
        // A hostile count is not trusted to size anything: only entries really present are read.
        for _ in 0..<count {
            guard at + 46 <= zip.count, u32(zip, at) == centralSignature else { return nil }
            let nameLength = Int(u16(zip, at + 28))
            let skip = nameLength + Int(u16(zip, at + 30)) + Int(u16(zip, at + 32))
            guard at + 46 + nameLength <= zip.count else { return nil }
            let nameBytes = zip[(zip.startIndex + at + 46)..<(zip.startIndex + at + 46 + nameLength)]
            entries.append(Entry(
                name: String(decoding: nameBytes, as: UTF8.self), method: u16(zip, at + 10),
                compressedSize: u32(zip, at + 20), size: u32(zip, at + 24), localOffset: u32(zip, at + 42)
            ))
            at += 46 + skip
        }
        return (entries, count)
    }

    /// An entry's bytes (a gzip stream): STORED as they are, DEFLATE inflated, at most `max`;
    /// nil if damaged, over the cap, or another method.
    static func contents(of entry: Entry, in zip: Data, max: Int) -> Data? {
        let local = Int(entry.localOffset)
        guard local + 30 <= zip.count, u32(zip, local) == localSignature else { return nil }
        let start = local + 30 + Int(u16(zip, local + 26)) + Int(u16(zip, local + 28))
        let end = start + Int(entry.compressedSize)
        guard start <= zip.count, end <= zip.count else { return nil }
        let raw = zip[(zip.startIndex + start)..<(zip.startIndex + end)]
        switch entry.method {
        case 0: return raw.count <= max ? Data(raw) : nil
        case 8: return inflate(raw, max: max)
        default: return nil
        }
    }

    // MARK: gzip

    /// A gzip stream's body, at most `max` bytes, with its checksum and length checked as
    /// `GZIPInputStream` checks them; nil if it isn't gzip, is damaged or is longer than `max`.
    private static func gunzip(_ data: Data, max: Int) -> Data? {
        guard data.count >= 18, data[data.startIndex] == 0x1F, data[data.startIndex + 1] == 0x8B,
              data[data.startIndex + 2] == 8 else { return nil }
        let flags = data[data.startIndex + 3]
        var at = 10
        if flags & 4 != 0 {
            guard at + 2 <= data.count else { return nil }
            at += 2 + Int(u16(data, at))
        }
        for flag in [UInt8(8), UInt8(16)] where flags & flag != 0 {
            while at < data.count, data[data.startIndex + at] != 0 { at += 1 }
            at += 1
        }
        if flags & 2 != 0 { at += 2 }
        guard at <= data.count - 8 else { return nil }
        let body = data[(data.startIndex + at)..<(data.startIndex + data.count - 8)]
        guard let out = inflate(body, max: max) else { return nil }
        let crc = u32(data, data.count - 8)
        let size = u32(data, data.count - 4)
        guard CRC32.checksum(out) == crc, UInt32(truncatingIfNeeded: out.count) == size else { return nil }
        return out
    }

    // MARK: DEFLATE

    /// Raw DEFLATE inflated through Compression's stream API, counting what really comes out:
    /// nil past `max` bytes, or if the data is damaged or ends before the stream does.
    static func inflate(_ input: Data, max: Int) -> Data? {
        if input.isEmpty { return nil }
        let bufferSize = 64 * 1024
        let scratch = UnsafeMutablePointer<UInt8>.allocate(capacity: bufferSize)
        defer { scratch.deallocate() }
        let state = UnsafeMutablePointer<compression_stream>.allocate(capacity: 1)
        defer { state.deallocate() }
        guard compression_stream_init(state, COMPRESSION_STREAM_DECODE, COMPRESSION_ZLIB) == COMPRESSION_STATUS_OK else {
            return nil
        }
        defer { compression_stream_destroy(state) }

        var out = Data()
        var finished = false
        var complete = false
        var overflow = false
        input.withUnsafeBytes { (source: UnsafeRawBufferPointer) in
            guard let base = source.bindMemory(to: UInt8.self).baseAddress else { return }
            state.pointee.src_ptr = base
            state.pointee.src_size = input.count
            while !finished {
                state.pointee.dst_ptr = scratch
                state.pointee.dst_size = bufferSize
                let status = compression_stream_process(state, Int32(COMPRESSION_STREAM_FINALIZE.rawValue))
                let produced = bufferSize - state.pointee.dst_size
                out.append(scratch, count: produced)
                if out.count > max {
                    overflow = true
                    finished = true
                    return
                }
                switch status {
                case COMPRESSION_STATUS_END:
                    complete = true
                    finished = true
                case COMPRESSION_STATUS_OK:
                    // No progress with all the input given: the stream is cut short.
                    if produced == 0 && state.pointee.src_size == 0 { finished = true }
                default:
                    finished = true
                }
            }
        }
        return complete && !overflow ? out : nil
    }

    // MARK: Little-endian reads

    static func u16(_ d: Data, _ i: Int) -> UInt16 {
        UInt16(d[d.startIndex + i]) | UInt16(d[d.startIndex + i + 1]) << 8
    }

    static func u32(_ d: Data, _ i: Int) -> UInt32 {
        UInt32(d[d.startIndex + i]) | UInt32(d[d.startIndex + i + 1]) << 8
            | UInt32(d[d.startIndex + i + 2]) << 16 | UInt32(d[d.startIndex + i + 3]) << 24
    }
}
