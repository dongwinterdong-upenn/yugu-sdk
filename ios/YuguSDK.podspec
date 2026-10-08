Pod::Spec.new do |s|
  s.name             = 'YuguSDK'
  s.version          = '2.0.0'
  s.summary          = 'Yugu speech evaluation SDK for iOS: REST and streaming evaluation, retry, idempotency, recorder.'
  s.description      = <<-DESC
    Client of the Yugu speech evaluation platform (open.shengzhiai.com): native and engine compatible
    evaluation over REST and WebSocket, idempotency keys, retry with backoff, reconnect with audio
    replay, typed errors, local audio precheck and an AVAudioEngine recorder (16 kHz mono PCM16).
  DESC
  s.homepage         = 'https://open.shengzhiai.com'
  s.license          = { :type => 'Apache-2.0', :file => 'LICENSE' }
  s.author           = 'Yugu (open.shengzhiai.com)'
  s.source           = { :git => 'https://open.shengzhiai.com/git/yugu-ios-sdk.git', :tag => s.version.to_s }
  s.ios.deployment_target = '13.0'
  s.swift_versions   = ['5.9']
  # CocoaPods builds both folders into the single module YuguSDK.
  s.source_files     = 'Sources/YuguCore/**/*.swift', 'Sources/YuguSDK/**/*.swift'
  s.frameworks       = 'Foundation', 'AVFoundation'
end
