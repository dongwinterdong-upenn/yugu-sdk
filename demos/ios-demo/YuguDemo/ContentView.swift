import SwiftUI
import YuguSDK

struct ContentView: View {
    @EnvironmentObject var model: DemoModel

    private let coreTypes = ["word", "sentence", "passage"]
    private let languages = ["zh-CN", "en-US"]

    var body: some View {
        NavigationView {
            Form {
                if !model.config.isConfigured {
                    Section {
                        Text("请先在 Config.xcconfig 中填写测试密钥")
                            .foregroundColor(.orange)
                    }
                }
                Section(header: Text("评测内容")) {
                    TextField("参考文本", text: $model.referenceText)
                    Picker("题型", selection: $model.coreType) {
                        ForEach(coreTypes, id: \.self) { Text($0) }
                    }
                    Picker("语言", selection: $model.language) {
                        ForEach(languages, id: \.self) { Text($0) }
                    }
                    .pickerStyle(.segmented)
                    Picker("方式", selection: $model.mode) {
                        ForEach(DemoModel.Mode.allCases) { Text($0.rawValue).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    .disabled(model.isRecording)
                }
                Section {
                    Button(model.isRecording ? "停止并评测" : "开始录音") {
                        model.toggleRecording()
                    }
                    .disabled(model.isBusy && !model.isRecording)
                    Button("示例音频评测") {
                        model.evaluateSample()
                    }
                    .disabled(model.isRecording || model.isBusy)
                    if model.isBusy || model.isRecording {
                        Button("取消", role: .destructive) {
                            model.cancel()
                        }
                    }
                }
                Section(header: Text("状态")) {
                    LabeledRow(label: "进度", value: model.status)
                    if model.mode == .stream {
                        LabeledRow(label: "会话", value: model.sessionState)
                    }
                    ForEach(Array(model.events.enumerated()), id: \.offset) { item in
                        Text(item.element).font(.footnote)
                    }
                    if let error = model.errorText {
                        Text(error).foregroundColor(.red)
                    }
                }
                if let r = model.result {
                    ResultSection(result: r)
                }
            }
            .navigationTitle("优谷雅言评测")
        }
    }
}

struct LabeledRow: View {
    let label: String
    let value: String

    var body: some View {
        HStack {
            Text(label)
            Spacer()
            Text(value).foregroundColor(.secondary)
        }
    }
}

struct ResultSection: View {
    let result: EvalResult

    private func score(_ v: Double?) -> String {
        v.map { String(format: "%.1f", $0) } ?? "-"
    }

    var body: some View {
        Section(header: Text("结果")) {
            HStack {
                Text("总分")
                Spacer()
                Text(score(result.overall)).font(.largeTitle).bold()
            }
            LabeledRow(label: "完整度", value: score(result.dimensions.integrity))
            LabeledRow(label: "准确度", value: score(result.dimensions.accuracy))
            LabeledRow(label: "流利度", value: score(result.dimensions.fluency))
            LabeledRow(label: "声调", value: score(result.dimensions.tone))
            LabeledRow(label: "韵律", value: score(result.dimensions.rhythm))
            if let text = result.asrText?.text {
                LabeledRow(label: "识别文本", value: text)
            }
            ForEach(Array(result.words.enumerated()), id: \.offset) { item in
                LabeledRow(label: item.element.word ?? "?", value: score(item.element.overall))
            }
            ForEach(result.warnings + result.localWarnings, id: \.code) { w in
                Text("提示 \(w.code)：\(w.message)").font(.footnote).foregroundColor(.orange)
            }
            LabeledRow(label: "记录号", value: result.recordId ?? "-")
            LabeledRow(label: "尝试次数", value: "\(result.attempts)")
        }
    }
}
