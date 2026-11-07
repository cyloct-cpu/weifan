import SwiftUI
import UIKit

/// 品牌 W 动画（brand-v2/logo-mg，透明底蓝 W，30 帧）
/// TimelineView 驱动帧序（Timer/animatedImage 方案实测不动）
struct WLogoPlayer: View {
    var loop = false
    @State private var index = 1
    private let frameCount = 30
    private static var cache: [Int: UIImage] = [:]
    private static let lock = NSLock()

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1.0 / 12.0)) { ctx in
            let i = frameIndex(ctx.date)
            Group {
                if let ui = Self.frame(i) {
                    Image(uiImage: ui).resizable().scaledToFit()
                } else {
                    Color.clear
                }
            }
        }
    }

    private func frameIndex(_ date: Date) -> Int {
        let t = date.timeIntervalSinceReferenceDate
        let frame = Int(t * 12) % frameCount + 1
        return frame
    }

    static func frame(_ i: Int) -> UIImage? {
        lock.lock(); defer { lock.unlock() }
        if let ui = cache[i] { return ui }
        let name = String(format: "w%02d", i)
        guard let url = Bundle.main.url(forResource: name, withExtension: "png"),
              let ui = UIImage(contentsOfFile: url.path) else { return nil }
        cache[i] = ui
        return ui
    }


}
