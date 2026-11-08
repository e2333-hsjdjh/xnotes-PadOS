import SwiftUI

struct AIProviderEditor: View {
    @Environment(\.dismiss) private var dismiss
    @State private var draft: AIProvider
    @State private var model = ""
    @State private var imageModel = ""
    @State private var error: String?
    @State private var fetchTask: Task<Void, Never>?
    @State private var fetched: ModelResults?
    @State private var confirmDelete = false
    let isNew: Bool
    let accept: (AIProvider?) -> Void

    init(provider: AIProvider, isNew: Bool, accept: @escaping (AIProvider?) -> Void) {
        _draft = State(initialValue: provider)
        self.isNew = isNew
        self.accept = accept
    }

    var body: some View {
        NavigationStack {
            Form {
                Group {
                    if let error { Section { Text(error).accessibilityAddTraits(.updatesFrequently) } }
                    Section("Provider") {
                        LabeledContent("Provider name") {
                            TextField("Provider name", text: $draft.name).multilineTextAlignment(.trailing)
                        }
                        LabeledContent("HTTPS base URL") {
                            TextField("HTTPS base URL", text: $draft.endpoint)
                                .keyboardType(.URL)
                                .textInputAutocapitalization(.never)
                                .autocorrectionDisabled()
                                .multilineTextAlignment(.trailing)
                        }
                        LabeledContent("API key") {
                            SecureField("API key", text: $draft.key)
                                .textInputAutocapitalization(.never)
                                .autocorrectionDisabled()
                                .multilineTextAlignment(.trailing)
                        }
                    }
                    .disabled(fetchTask != nil)
                    modelSection(image: false)
                    modelSection(image: true)
                    Section {
                        if fetchTask != nil {
                            ProgressView("Fetching models…")
                            Button("Cancel model request") { fetchTask?.cancel(); fetchTask = nil }
                        } else {
                            Button("Fetch from provider", systemImage: "arrow.down") { fetchModels() }
                                .frame(minHeight: UITokens.minTouchTarget)
                        }
                    } footer: {
                        Text("Fetching sends your API key only to the configured provider's models endpoint. Redirects are rejected.")
                    }
                    Section("Thinking format") {
                        Picker("Request format", selection: $draft.thinkingFormat) {
                            ForEach(AIThinkingFormat.allCases, id: \.self) { format in Text(format.label).tag(format) }
                        }
                    }
                    if !isNew {
                        Section {
                            Button("Delete provider", role: .destructive) { confirmDelete = true }
                                .frame(minHeight: UITokens.minTouchTarget)
                        }
                    }
                }
                .aiSettingsRows()
            }
            .aiSettingsSurface()
            .navigationTitle(isNew ? Text("Add provider") : Text("Edit provider"))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") {
                        do {
                            let clean = draft.cleaned()
                            try clean.validate()
                            accept(clean)
                            dismiss()
                        } catch { self.error = error.localizedDescription }
                    }
                    .disabled(fetchTask != nil)
                }
            }
            .sheet(item: $fetched) { result in
                AIModelSelection(models: result.models, provider: draft) { chat, images in
                    var updated = draft
                    updated.models = AIProvider.uniqueModels(draft.models + chat)
                    updated.imageModels = AIProvider.uniqueModels(draft.imageModels + images)
                    try updated.validate()
                    draft = updated
                }
            }
            .confirmationDialog("Delete this provider?", isPresented: $confirmDelete, titleVisibility: .visible) {
                Button("Delete provider", role: .destructive) { accept(nil); dismiss() }
            } message: {
                Text("Existing conversations must choose another model before sending. Save AI settings to apply this change.")
            }
            .onDisappear { fetchTask?.cancel(); fetchTask = nil }
        }
    }

    private func modelSection(image: Bool) -> some View {
        let names = image ? draft.imageModels : draft.models
        let entry = image ? $imageModel : $model
        return Section {
            ForEach(names, id: \.self) { name in
                Text(verbatim: name)
                    .swipeActions {
                        Button("Delete", role: .destructive) {
                            if image { draft.imageModels.removeAll { $0 == name } }
                            else { draft.models.removeAll { $0 == name } }
                        }
                    }
            }
            HStack {
                TextField("Model ID", text: entry)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .onSubmit { addModel(image: image) }
                Button("Add model", systemImage: "plus") { addModel(image: image) }
                    .labelStyle(.iconOnly)
                    .frame(minWidth: UITokens.minTouchTarget, minHeight: UITokens.minTouchTarget)
                    .disabled(entry.wrappedValue.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        } header: {
            Text(image ? "Image models" : "Chat models")
        } footer: {
            Text("Saved models: \(names.count) / 200")
        }
    }

    private func addModel(image: Bool) {
        let clean = (image ? imageModel : model).trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty else { return }
        guard clean.utf16.count <= 200 else { error = AISettingsError.invalidModels.localizedDescription; return }
        let updated = AIProvider.uniqueModels((image ? draft.imageModels : draft.models) + [clean])
        guard updated.count <= 200 else { error = AISettingsError.tooManyModels.localizedDescription; return }
        if image { draft.imageModels = updated; imageModel = "" }
        else { draft.models = updated; model = "" }
        error = nil
    }

    private func fetchModels() {
        guard fetchTask == nil else { return }
        let route = draft.cleaned()
        error = nil
        fetchTask = Task { @MainActor in
            do {
                let models = try await AIModelClient.models(endpoint: route.endpoint, key: route.key)
                try Task.checkCancellation()
                fetched = ModelResults(models: models)
            } catch is CancellationError {
                return
            } catch {
                guard !Task.isCancelled else { return }
                self.error = error.localizedDescription
            }
            fetchTask = nil
        }
    }

    private struct ModelResults: Identifiable {
        let id = UUID()
        let models: [String]
    }
}

private struct AIModelSelection: View {
    @Environment(\.dismiss) private var dismiss
    let models: [String]
    let provider: AIProvider
    let accept: ([String], [String]) throws -> Void
    @State private var selected: Set<String> = []
    @State private var query = ""
    @State private var error: String?

    private var available: [String] {
        models.filter { model in
            !(AIModelKind.guess(model) == .image ? provider.imageModels : provider.models).contains(model)
        }
    }
    private var filtered: [String] { available.filter { query.isEmpty || $0.localizedStandardContains(query) } }
    private func fits(_ choices: Set<String>) -> Bool {
        let images = choices.filter { AIModelKind.guess($0) == .image }.count
        return provider.imageModels.count + images <= 200 && provider.models.count + choices.count - images <= 200
    }
    private func select(_ choices: Set<String>) {
        guard fits(choices) else { error = AISettingsError.tooManyModels.localizedDescription; return }
        selected = choices
        error = nil
    }

    var body: some View {
        NavigationStack {
            List {
                Group {
                    if let error { Text(error) }
                    if available.isEmpty { Text("No new models were returned.") }
                    Section {
                        modelRows(.chat)
                    } header: { Text("Chat models") }
                    Section {
                        modelRows(.image)
                    } header: { Text("Image models") }
                    DisclosureGroup("Other models (added to chat)") { modelRows(.other) }
                    Text("Groups are guessed from model names. You can enter any model ID in either list manually.")
                        .foregroundStyle(UITokens.secondaryText)
                }
                .aiSettingsRows()
            }
            .aiSettingsSurface()
            .searchable(text: $query)
            .navigationTitle("Available models")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Add selected models") {
                        guard fits(selected) else { error = AISettingsError.tooManyModels.localizedDescription; return }
                        let choices = available.filter { selected.contains($0) }
                        do {
                            try accept(choices.filter { AIModelKind.guess($0) != .image }, choices.filter { AIModelKind.guess($0) == .image })
                            dismiss()
                        } catch { self.error = error.localizedDescription }
                    }
                    .disabled(selected.isEmpty)
                }
                ToolbarItem(placement: .bottomBar) {
                    Button("Select visible models") { select(selected.union(filtered)) }
                }
            }
        }
    }

    private func modelRows(_ kind: AIModelKind) -> some View {
        ForEach(filtered.filter { AIModelKind.guess($0) == kind }, id: \.self) { model in
            Toggle(model, isOn: Binding(get: { selected.contains(model) }, set: { enabled in
                var choices = selected
                if enabled { choices.insert(model) } else { choices.remove(model) }
                select(choices)
            }))
            .tint(.primary)
        }
    }
}
