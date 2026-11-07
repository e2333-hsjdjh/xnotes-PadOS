import SwiftUI

struct AISettingsView: View {
    @Bindable var store: AIConfigurationStore
    @Environment(\.dismiss) private var dismiss
    @State private var draft: AIConfiguration
    @State private var editing: AIProvider?
    @State private var error: String?
    @State private var confirmClear = false

    init(store: AIConfigurationStore) {
        self.store = store
        _draft = State(initialValue: store.configuration)
    }

    var body: some View {
        NavigationStack {
            Form {
                Group {
                    if let issue = store.loadIssue { Section { Text(issue) } }
                    if let error { Section { Text(error).accessibilityAddTraits(.updatesFrequently) } }
                    Section {
                        ForEach(draft.providers) { provider in
                            Button {
                                editing = provider
                            } label: {
                                LabeledContent(provider.label, value: String(localized: "Models: \(provider.models.count)"))
                                    .foregroundStyle(.primary)
                                    .frame(minHeight: UITokens.minTouchTarget)
                            }
                        }
                        Button("Add provider", systemImage: "plus") { editing = AIProvider() }
                            .frame(minHeight: UITokens.minTouchTarget)
                            .disabled(draft.providers.count >= 20)
                    } header: { Text("Providers") } footer: {
                        Text("Only Chat Completions providers are supported. Model lists are fetched only when you request them.")
                    }
                    .disabled(store.loadIssue != nil)
                    Section("Answer defaults") {
                        Picker("Default model", selection: $draft.defaultModel) {
                            Text("Choose a model").tag(Optional<AIModelChoice>.none)
                            ForEach(draft.providers) { provider in
                                Section(provider.label) {
                                    ForEach(provider.models, id: \.self) { model in
                                        Text(model).tag(Optional(AIModelChoice(providerID: provider.id, model: model)))
                                    }
                                }
                            }
                        }
                        Picker("Thinking depth", selection: $draft.effort) {
                            ForEach(AIThinkingEffort.allCases, id: \.self) { value in Text(value.label).tag(value) }
                        }
                        LabeledContent("Maximum output tokens") {
                            TextField("Maximum output tokens", value: $draft.maxTokens, format: .number.grouping(.never))
                                .keyboardType(.numberPad)
                                .multilineTextAlignment(.trailing)
                        }
                        Text("Allowed output tokens: 256–131072.")
                            .foregroundStyle(UITokens.secondaryText)
                    }
                    .disabled(store.loadIssue != nil)
                    Section("Custom instructions") {
                        TextField("Custom instructions", text: $draft.instructions, axis: .vertical)
                            .lineLimit(4...10)
                        Text("\(draft.instructions.utf16.count) / 4000")
                            .foregroundStyle(UITokens.secondaryText)
                        Menu("Restore default instructions") {
                            Button("Chinese instructions") { draft.instructions = AIDefaults.chinese }
                            Button("English instructions") { draft.instructions = AIDefaults.english }
                            Button("Clear instructions") { draft.instructions = "" }
                        }
                    }
                    .disabled(store.loadIssue != nil)
                    Section {
                        Text("AI settings stay on this device. Credentials are encrypted with a device-only Keychain key and excluded from note exports and system backups.")
                        Button("Clear AI settings", role: .destructive) { confirmClear = true }
                            .frame(minHeight: UITokens.minTouchTarget)
                    }
                }
                .aiSettingsRows()
            }
            .aiSettingsSurface()
            .navigationTitle("AI settings")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        do { try store.save(draft); dismiss() }
                        catch { self.error = error.localizedDescription }
                    }
                    .disabled(store.loadIssue != nil)
                }
            }
            .sheet(item: $editing) { provider in
                AIProviderEditor(provider: provider, isNew: !draft.providers.contains { $0.id == provider.id }) { edited in
                    if let edited {
                        if let index = draft.providers.firstIndex(where: { $0.id == edited.id }) { draft.providers[index] = edited }
                        else { draft.providers.append(edited) }
                    } else {
                        draft.providers.removeAll { $0.id == provider.id }
                    }
                    draft.reconcileDefault()
                }
            }
            .confirmationDialog("Clear saved AI settings and API keys?", isPresented: $confirmClear, titleVisibility: .visible) {
                Button("Clear AI settings", role: .destructive) {
                    do { try store.clear(); draft = store.configuration; error = nil }
                    catch { self.error = error.localizedDescription }
                }
            } message: {
                Text("This removes provider credentials and defaults from this device. Notes are kept.")
            }
        }
    }
}

extension AIThinkingEffort {
    var label: LocalizedStringKey {
        switch self {
        case .providerDefault: "Provider default"
        case .off: "Off"
        case .low: "Low"
        case .medium: "Medium"
        case .high: "High"
        }
    }
}

extension AIThinkingFormat {
    var label: LocalizedStringKey {
        switch self {
        case .automatic: "Automatic"
        case .openAI: "OpenAI"
        case .openRouter: "OpenRouter"
        case .deepSeek: "DeepSeek"
        case .qwen: "Qwen"
        }
    }
}
