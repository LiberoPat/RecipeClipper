import Compression
import Foundation

/// An export with photos (#116; Android's BackupArchive): a zip holding `backup.json` and each
/// picture at its `photos/…` path. Foundation has no zip API, so this writes the format by hand:
/// STORED entries only (JPEGs don't shrink), read back through the central directory. Reading
/// also takes DEFLATE entries (Compression's raw deflate), so a zip re-packed by another tool
/// still imports. A file that doesn't start with a zip signature is a plain JSON export.
enum BackupArchive {
    static let jsonEntry = "backup.json"
    /// Far beyond any real photo, which the store keeps to 2048 px JPEGs of a few hundred KB.
    static let maxPhotoBytes = 30 * 1024 * 1024

    static func isZip(_ head: Data) -> Bool {
        head.count >= 4 && head.prefix(4) == Data([0x50, 0x4B, 0x03, 0x04])
    }

    /// `json` and the pictures (path in the zip to a local file; a missing file is skipped).
    static func write(json: String, photos: [(path: String, file: URL)]) -> Data {
        var entries: [(String, Data)] = [(jsonEntry, Data(json.utf8))]
        for (path, file) in photos where BackupJson.isPhotoFile(path) {
            if let data = try? Data(contentsOf: file) { entries.append((path, data)) }
        }
        var out = Data()
        var central = Data()
        for (name, data) in entries {
            let nameBytes = Data(name.utf8)
            let crc = CRC32.checksum(data)
            let offset = UInt32(out.count)
            out.append(le32(0x0403_4B50)); out.append(le16(20)); out.append(le16(0x0800)); out.append(le16(0))
            out.append(le16(0)); out.append(le16(0x21)); out.append(le32(crc))
            out.append(le32(UInt32(data.count))); out.append(le32(UInt32(data.count)))
            out.append(le16(UInt16(nameBytes.count))); out.append(le16(0))
            out.append(nameBytes); out.append(data)

            central.append(le32(0x0201_4B50)); central.append(le16(20)); central.append(le16(20))
            central.append(le16(0x0800)); central.append(le16(0)); central.append(le16(0)); central.append(le16(0x21))
            central.append(le32(crc)); central.append(le32(UInt32(data.count))); central.append(le32(UInt32(data.count)))
            central.append(le16(UInt16(nameBytes.count))); central.append(le16(0)); central.append(le16(0))
            central.append(le16(0)); central.append(le16(0)); central.append(le32(0)); central.append(le32(offset))
            central.append(nameBytes)
        }
        let centralOffset = UInt32(out.count)
        out.append(central)
        out.append(le32(0x0605_4B50)); out.append(le16(0)); out.append(le16(0))
        out.append(le16(UInt16(entries.count))); out.append(le16(UInt16(entries.count)))
        out.append(le32(UInt32(central.count))); out.append(le32(centralOffset)); out.append(le16(0))
        return out
    }

    /// Its JSON (at most `maxJsonBytes`) and its pictures, each copied into `photoDirectory`
    /// (emptied first). Anything else in the zip is ignored.
    static func read(_ zip: Data, photoDirectory: URL, maxJsonBytes: Int) -> Result<BackupPackage, BackupError> {
        let fm = FileManager.default
        try? fm.removeItem(at: photoDirectory)
        try? fm.createDirectory(at: photoDirectory, withIntermediateDirectories: true)
        guard let entries = centralDirectory(zip) else { return .failure(.notABackup) }
        var json: String?
        var photos: [String: URL] = [:]
        for entry in entries {
            if entry.name == jsonEntry {
                guard entry.size <= maxJsonBytes, let data = contents(of: entry, in: zip),
                      let text = String(data: data, encoding: .utf8) else { return .failure(.notABackup) }
                json = text
            } else if BackupJson.isPhotoFile(entry.name), photos[entry.name] == nil, entry.size <= maxPhotoBytes,
                      let data = contents(of: entry, in: zip) {
                let file = photoDirectory.appendingPathComponent("\(photos.count).jpg")
                if (try? data.write(to: file)) != nil { photos[entry.name] = file }
            }
        }
        guard let json else { return .failure(.notABackup) }
        return .success(BackupPackage(json: json, photos: photos))
    }

    private struct Entry {
        let name: String
        let method: UInt16
        let compressedSize: Int
        let size: Int
        let localOffset: Int
    }

    /// The entries the End Of Central Directory record lists, or nil if there is none.
    private static func centralDirectory(_ zip: Data) -> [Entry]? {
        let bytes = [UInt8](zip)
        guard bytes.count >= 22 else { return nil }
        var eocd = bytes.count - 22
        let lowest = max(0, bytes.count - 22 - 65_535)
        while eocd >= lowest, u32(bytes, eocd) != 0x0605_4B50 { eocd -= 1 }
        guard eocd >= lowest else { return nil }
        let count = Int(u16(bytes, eocd + 10))
        var at = Int(u32(bytes, eocd + 16))
        var entries: [Entry] = []
        for _ in 0..<count {
            guard at + 46 <= bytes.count, u32(bytes, at) == 0x0201_4B50 else { return nil }
            let nameLength = Int(u16(bytes, at + 28))
            let skip = nameLength + Int(u16(bytes, at + 30)) + Int(u16(bytes, at + 32))
            guard at + 46 + nameLength <= bytes.count else { return nil }
            let name = String(decoding: bytes[(at + 46)..<(at + 46 + nameLength)], as: UTF8.self)
            entries.append(Entry(
                name: name, method: u16(bytes, at + 10), compressedSize: Int(u32(bytes, at + 20)),
                size: Int(u32(bytes, at + 24)), localOffset: Int(u32(bytes, at + 42))
            ))
            at += 46 + skip
        }
        return entries
    }

    /// An entry's bytes: STORED as they are, DEFLATE inflated; nil if damaged or another method.
    private static func contents(of entry: Entry, in zip: Data) -> Data? {
        let local = entry.localOffset
        guard local + 30 <= zip.count else { return nil }
        let header = [UInt8](zip[(zip.startIndex + local)..<(zip.startIndex + local + 30)])
        guard u32(header, 0) == 0x0403_4B50 else { return nil }
        let start = local + 30 + Int(u16(header, 26)) + Int(u16(header, 28))
        guard start + entry.compressedSize <= zip.count else { return nil }
        let raw = zip[(zip.startIndex + start)..<(zip.startIndex + start + entry.compressedSize)]
        switch entry.method {
        case 0: return Data(raw)
        case 8: return inflate(Data(raw), size: entry.size)
        default: return nil
        }
    }

    private static func inflate(_ data: Data, size: Int) -> Data? {
        guard size > 0 else { return Data() }
        var out = Data(count: size)
        let written = out.withUnsafeMutableBytes { dst in
            data.withUnsafeBytes { src in
                compression_decode_buffer(
                    dst.bindMemory(to: UInt8.self).baseAddress!, size,
                    src.bindMemory(to: UInt8.self).baseAddress!, data.count, nil, COMPRESSION_ZLIB
                )
            }
        }
        return written == size ? out : nil
    }

    private static func u16(_ b: [UInt8], _ i: Int) -> UInt16 { UInt16(b[i]) | UInt16(b[i + 1]) << 8 }
    private static func u32(_ b: [UInt8], _ i: Int) -> UInt32 {
        UInt32(b[i]) | UInt32(b[i + 1]) << 8 | UInt32(b[i + 2]) << 16 | UInt32(b[i + 3]) << 24
    }
    private static func le16(_ v: UInt16) -> Data { Data([UInt8(v & 0xFF), UInt8(v >> 8)]) }
    private static func le32(_ v: UInt32) -> Data {
        Data([UInt8(v & 0xFF), UInt8((v >> 8) & 0xFF), UInt8((v >> 16) & 0xFF), UInt8(v >> 24)])
    }
}

/// The zip checksum (CRC-32, IEEE), table-driven.
enum CRC32 {
    private static let table: [UInt32] = (0..<256).map { n in
        var c = UInt32(n)
        for _ in 0..<8 { c = c & 1 == 1 ? 0xEDB8_8320 ^ (c >> 1) : c >> 1 }
        return c
    }

    static func checksum(_ data: Data) -> UInt32 {
        var crc: UInt32 = 0xFFFF_FFFF
        for byte in data { crc = table[Int((crc ^ UInt32(byte)) & 0xFF)] ^ (crc >> 8) }
        return crc ^ 0xFFFF_FFFF
    }
}
