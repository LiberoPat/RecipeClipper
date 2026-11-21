import Foundation
@testable import RecipeClipper

/// A tiny STORED zip writer and a stored-block gzip writer, for the Paprika tests that need bytes
/// of their own (the fixtures cover the rest).
enum PaprikaZipTestSupport {

    /// `data` as a gzip stream whose DEFLATE body is one "stored" block: no compression, so no encoder is needed.
    static func gzip(_ data: Data) -> Data {
        var out = Data([0x1F, 0x8B, 8, 0, 0, 0, 0, 0, 0, 0xFF])
        out.append(contentsOf: [
            0x01, UInt8(data.count & 0xFF), UInt8(data.count >> 8), UInt8(~data.count & 0xFF), UInt8((~data.count >> 8) & 0xFF),
        ])
        out.append(data)
        var crc = CRC32.checksum(data).littleEndian
        out.append(Data(bytes: &crc, count: 4))
        var size = UInt32(data.count).littleEndian
        out.append(Data(bytes: &size, count: 4))
        return out
    }

    /// A zip of `entries` (name to bytes), every entry STORED.
    static func storedZip(_ entries: [String: Data]) -> Data {
        func le16(_ v: Int) -> Data { Data([UInt8(v & 0xFF), UInt8((v >> 8) & 0xFF)]) }
        func le32(_ v: UInt32) -> Data { Data([UInt8(v & 0xFF), UInt8((v >> 8) & 0xFF), UInt8((v >> 16) & 0xFF), UInt8(v >> 24)]) }
        var out = Data()
        var central = Data()
        for (name, data) in entries.sorted(by: { $0.key < $1.key }) {
            let nameBytes = Data(name.utf8)
            let crc = CRC32.checksum(data)
            let offset = UInt32(out.count)
            out.append(le32(0x0403_4B50)); out.append(le16(20)); out.append(le16(0)); out.append(le16(0))
            out.append(le16(0)); out.append(le16(0x21)); out.append(le32(crc))
            out.append(le32(UInt32(data.count))); out.append(le32(UInt32(data.count)))
            out.append(le16(nameBytes.count)); out.append(le16(0)); out.append(nameBytes); out.append(data)
            central.append(le32(0x0201_4B50)); central.append(le16(20)); central.append(le16(20))
            central.append(le16(0)); central.append(le16(0)); central.append(le16(0)); central.append(le16(0x21))
            central.append(le32(crc)); central.append(le32(UInt32(data.count))); central.append(le32(UInt32(data.count)))
            central.append(le16(nameBytes.count)); central.append(le16(0)); central.append(le16(0))
            central.append(le16(0)); central.append(le16(0)); central.append(le32(0)); central.append(le32(offset))
            central.append(nameBytes)
        }
        let centralOffset = UInt32(out.count)
        out.append(central)
        out.append(le32(0x0605_4B50)); out.append(le16(0)); out.append(le16(0))
        out.append(le16(entries.count)); out.append(le16(entries.count))
        out.append(le32(UInt32(central.count))); out.append(le32(centralOffset)); out.append(le16(0))
        return out
    }
}
