import SwiftUI

struct AIProviderEditor: View {
    @Environment(\.dismiss) private var dismiss
    @State private var draft: AIProvider
    @State private var model = ""
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
                    Section {
                        ForEach(draft.models, id: \.self) { name in
                            Text(verbatim: name)
                                .swipeActions { Button("Delete", role: .destructive) { draft.models.removeAll { $0 == name } } }
                        }
                        HStack {
                            TextField("Model ID", text: $model)
                                .textInputAutocapitalization(.never)
                                .autocorrectionDisabled()
                                .onSubmit(addModel)
                            Button("Add model", systemImage: "plus", action: addModel)
                                .labelStyle(.iconOnly)
                                .frame(minWidth: UITokens.minTouchTarget, minHeight: UITokens.minTouchTarget)
                                .disabled(model.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                        }
                        if fetchTask != nil {
                            ProgressView("Fetching models…")
                            Button("Cancel model request") { fetchTask?.cancel(); fetchTask = nil }
                        } else {
                            Button("Fetch from provider", systemImage: "arrow.down") { fetchModels() }
                                .frame(minHeight: UITokens.minTouchTarget)
                        }
                    } header: {
                        Text("Saved models: \(draft.models.count) / 200")
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
                AIModelSelection(models: result.models.filter { !draft.models.contains($0) }, capacity: 200 - draft.models.count) { choices in
                    draft.models = AIProvider.uniqueModels(draft.models + choices)
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

    private func addModel() {
        let clean = model.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty else { return }
        guard clean.utf16.count <= 200 else { error = AISettingsError.invalidModels.localizedDescription; return }
        let updated = AIProvider.uniqueModels(draft.models + [clean])
        guard updated.count <= 200 else { error = AISettingsError.tooManyModels.localizedDescription; return }
        draft.models = updated
        model = ""
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
    let capacity: Int
    let accept: ([String]) -> Void
    @State private var selected: Set<String> = []
    @State private var query = ""
    @State private var error: String?

    private var filtered: [String] { models.filter { query.isEmpty || $0.localizedStandardContains(query) } }

    var body: some View {
        NavigationStack {
            List {
                Group {
                    if let error { Text(error) }
                    if models.isEmpty { Text("No new models were returned.") }
                    ForEach(filtered, id: \.self) { model in
                        Toggle(model, isOn: Binding(get: { selected.contains(model) }, set: { enabled in
                            if enabled {
                                guard selected.count < capacity else { error = AISettingsError.tooManyModels.localizedDescription; return }
                                selected.insert(model)
                            } else { selected.remove(model) }
                            error = nil
                        }))
                        .tint(.primary)
                    }
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
                        guard selected.count <= capacity else { error = AISettingsError.tooManyModels.localizedDescription; return }
                        accept(models.filter { selected.contains($0) })
                        dismiss()
                    }
                    .disabled(selected.isEmpty)
                }
                ToolbarItem(placement: .bottomBar) {
                    Button("Select visible models") {
                        let updated = selected.union(filtered)
                        guard updated.count <= capacity else { error = AISettingsError.tooManyModels.localizedDescription; return }
                        selected = updated
                    }
                }
            }
        }
    }
}
