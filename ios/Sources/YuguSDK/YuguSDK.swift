// YuguSDK: everything of YuguCore plus the AVAudioEngine recorder (Apple platforms).
//
// SwiftPM builds YuguCore as its own module, re-exported here so that `import YuguSDK` is enough.
// CocoaPods compiles both source folders into the single module `YuguSDK`, where YuguCore does
// not exist as a module and the import is skipped.
#if canImport(YuguCore)
@_exported import YuguCore
#endif
