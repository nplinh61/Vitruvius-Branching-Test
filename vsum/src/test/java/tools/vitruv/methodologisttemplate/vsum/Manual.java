package tools.vitruv.methodologisttemplate.vsum;

import mir.reactions.model2Model2.Model2Model2ChangePropagationSpecification;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;
import org.eclipse.jgit.api.Git;
import tools.vitruv.change.propagation.ChangePropagationMode;
import tools.vitruv.change.testutils.TestUserInteraction;
import tools.vitruv.framework.views.CommittableView;
import tools.vitruv.framework.views.ViewTypeFactory;
import tools.vitruv.framework.vsum.VirtualModelBuilder;
import tools.vitruv.framework.vsum.branch.handler.VsumMergeWatcher;
import tools.vitruv.framework.vsum.branch.handler.VsumPostCommitWatcher;
import tools.vitruv.framework.vsum.branch.handler.VsumReloadWatcher;
import tools.vitruv.framework.vsum.branch.handler.VsumValidationWatcher;
import tools.vitruv.framework.vsum.branch.util.GitHookInstaller;
import tools.vitruv.framework.vsum.internal.InternalVirtualModel;
import tools.vitruv.methodologisttemplate.model.model.ModelFactory;
import tools.vitruv.methodologisttemplate.model.model.System;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Consumer;


public class Manual {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            java.lang.System.exit(1);
        }

        Path repoRoot = Paths.get(args[0]).toAbsolutePath();
        cleanupVsumState(repoRoot);

        // Register XMI resource factory (required for EMF serialization)
        Resource.Factory.Registry.INSTANCE.getExtensionToFactoryMap().put("*", new XMIResourceFactoryImpl());

        // Step 1: Initialize VSUM
        InternalVirtualModel vsum = createVirtualModel(repoRoot);

        // Step 2: Create initial model content
        addSystem(vsum, repoRoot);

        // Step 3: Install Git hooks
        GitHookInstaller installer = new GitHookInstaller(repoRoot);
        installer.installAllHooks();

        // Step 3.5: Create initial branch metadata for master
        createInitialBranchMetadata(repoRoot, "master");

        // Step 4: Start all watchers
        VsumReloadWatcher reloadWatcher = new VsumReloadWatcher(vsum, repoRoot);
        VsumValidationWatcher validationWatcher = new VsumValidationWatcher(vsum, repoRoot);
        VsumPostCommitWatcher postCommitWatcher = new VsumPostCommitWatcher(repoRoot);
        VsumMergeWatcher mergeWatcher = new VsumMergeWatcher(vsum, repoRoot);

        reloadWatcher.start();
        validationWatcher.start();
        postCommitWatcher.start();
        mergeWatcher.start();

        // Step 5: Wait for user to test manually
        // Register shutdown hook to cleanly stop watchers
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            java.lang.System.out.println();
            java.lang.System.out.println("Shutting down watchers...");
            validationWatcher.stop();
            postCommitWatcher.stop();
            reloadWatcher.stop();
            mergeWatcher.stop();
            java.lang.System.out.println("All watchers stopped");
        }));

        // Wait forever (until Ctrl+C)
        Thread.sleep(Long.MAX_VALUE);
    }

    /**
     * Cleans up VSUM state to allow fresh initialization.
     * Deletes runtime state but preserves Git history and Vitruvius metadata.
     */
    private static void cleanupVsumState(Path repoRoot) throws IOException {
        deleteDirectoryIfExists(repoRoot.resolve("vsum"));
        deleteDirectoryIfExists(repoRoot.resolve("vitruvData"));
        deleteDirectoryIfExists(repoRoot.resolve("consistencymetadata"));

        Files.deleteIfExists(repoRoot.resolve("example.model"));
        Files.deleteIfExists(repoRoot.resolve("example.model2"));
        Files.deleteIfExists(repoRoot.resolve("test_project.marker_vitruv"));
    }

    /**
     * Recursively deletes a directory if it exists.
     */
    private static void deleteDirectoryIfExists(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }

        Files.walk(directory)
                .sorted(java.util.Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException e) {
                        // Ignore cleanup errors
                    }
                });
    }

    /**
     * Creates and initializes a VirtualModel at the given path.
     * Uses the same configuration as the integration test.
     */
    private static InternalVirtualModel createVirtualModel(Path projectPath) throws IOException {
        InternalVirtualModel model = new VirtualModelBuilder()
                .withStorageFolder(projectPath)
                .withUserInteractorForResultProvider(new TestUserInteraction.ResultProvider(new TestUserInteraction()))
                .withChangePropagationSpecifications(new Model2Model2ChangePropagationSpecification())
                .buildAndInitialize();
        model.setChangePropagationMode(ChangePropagationMode.TRANSITIVE_CYCLIC);
        return model;
    }

    /**
     * Adds an initial System model element to the VSUM.
     * This gives us something to modify when testing commits.
     */
    private static void addSystem(InternalVirtualModel model, Path projectPath) {
        CommittableView view = getDefaultView(model);
        modifyView(view, v ->
                v.registerRoot(ModelFactory.eINSTANCE.createSystem(), URI.createFileURI(projectPath.toString() + "/example.model")));
    }

    /**
     * Gets a default view selecting System elements.
     */
    private static CommittableView getDefaultView(InternalVirtualModel model) {
        var selector = model.createSelector(ViewTypeFactory.createIdentityMappingViewType("default"));
        selector.getSelectableElements().stream()
                .filter(element -> element instanceof System)
                .forEach(element -> selector.setSelected(element, true));
        return selector.createView().withChangeDerivingTrait();
    }

    /**
     * Helper to modify a view and commit changes.
     */
    private static void modifyView(CommittableView view, Consumer<CommittableView> modification) {
        modification.accept(view);
        view.commitChanges();
    }

    /**
     * Creates initial branch metadata for the root branch (master/main).
     * This is called once during setup to ensure the initial branch is tracked.
     */
    private static void createInitialBranchMetadata(Path repoRoot, String branchName) throws Exception {
        Path branchesDir = repoRoot.resolve(".vitruvius/branches");
        Files.createDirectories(branchesDir);

        Path metadataFile = branchesDir.resolve(branchName + ".metadata");
        if (Files.exists(metadataFile)) {
            return;
        }

        // Get current commit SHA (or use placeholder if no commits yet)
        String commitSha = "0000000000000000000000000000000000000000";
        String shortSha = "0000000";

        try (Git git = Git.open(repoRoot.toFile())) {
            org.eclipse.jgit.lib.ObjectId head = git.getRepository().resolve("HEAD");
            if (head != null) {
                // We have at least one commit
                commitSha = head.getName();
                shortSha = commitSha.substring(0, 7);
            }
        }

        String timestamp = java.time.LocalDateTime.now().toString();

        // Write metadata for root branch (no parent)
        String metadata = String.format(
                "{\n" +
                        "  \"branchName\": \"%s\",\n" +
                        "  \"uniqueId\": \"%s\",\n" +
                        "  \"state\": \"ACTIVE\",\n" +
                        "  \"parentBranch\": \"none\",\n" +
                        "  \"createdAt\": \"%s\",\n" +
                        "  \"lastModified\": \"%s\"\n" +
                        "}",
                branchName, shortSha, timestamp, timestamp
        );

        Files.writeString(metadataFile, metadata);
    }
}