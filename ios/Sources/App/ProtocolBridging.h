// 微翻 iOS：绕过 clang 模块发现直接引入 gomobile ObjC 头（Xcode 27 下该 xcframework
// 的模块在 per-file 增量编译里无法解析，详见 docs/19 §2.12）。链接仍走 -framework。
#ifndef PROTOCOL_BRIDGING_H
#define PROTOCOL_BRIDGING_H
#import "Mobile.objc.h"
#endif
