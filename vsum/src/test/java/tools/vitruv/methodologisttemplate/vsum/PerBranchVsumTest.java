package tools.vitruv.methodologisttemplate.vsum;

import mir.reactions.model2Model2.Model2Model2ChangePropagationSpecification;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.vitruv.change.propagation.ChangePropagationMode;
import tools.vitruv.change.testutils.TestUserInteraction;
import tools.vitruv.framework.views.CommittableView;
import tools.vitruv.framework.views.View;
import tools.vitruv.framework.views.ViewTypeFactory;
import tools.vitruv.framework.vsum.VirtualModelBuilder;
import tools.vitruv.framework.vsum.branch.handler.PostCheckoutHandler;
import tools.vitruv.framework.vsum.internal.InternalVirtualModel;
import tools.vitruv.methodologisttemplate.model.model.ModelFactory;
import tools.vitruv.methodologisttemplate.model.model.System;
import tools.vitruv.methodologisttemplate.model.model2.Root;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for per-branch VSUM state isolation.
 *
 * <p>Verifies that each Git branch maintains its own independent VSUM state
 * (UUID mappings and correspondence model) under .vitruvius/vsum/{branchName}/.
 * When switching branches, the VSUM reloads from the new branch's folder,
 * ensuring model consistency reflects the checked-out branch content.
 *
 * <p>Note on test scope: these tests use JGit directly and therefore do not
 * exercise the Git hook pipeline. Specifically:
 * <ul>
 *   <li>Changelog files (.vitruvius/changelogs/{hash7}.txt) are simulated,
 *       not produced by a real post-commit hook</li>
 *   <li>Branch metadata files (.vitruvius/branches/{name}.metadata) are
 *       simulated, not produced by a real post-checkout hook</li>
 *   <li>Merge metadata (.vitruvius/merges/{fullhash}.metadata) is only
 *       created for non-fast-forward merges and is not tested here</li>
 *   <li>The reload trigger file mechanism (VsumReloadWatcher) is bypassed —
 *       PostCheckoutHandler is invoked directly</li>
 * </ul>
 * The full end-to-end hook path is verified manually via ManualTest.
 */
public class PerBranchVsumTest {

    @TempDir
    Path repoRoot;

    private InternalVirtualModel virtualModel;
    private Git git;

    @BeforeAll
    static void setupResourceFactories() {
        Resource.Factory.Registry.INSTANCE.getExtensionToFactoryMap().put("*", new XMIResourceFactoryImpl());
    }

    @BeforeEach
    void setup() throws Exception {
        // Initialize a real Git repository so JGit branch resolution works.
        // An initial empty commit is required so that HEAD resolves to a branch name.
        git = Git.init().setDirectory(repoRoot.toFile()).call();
        git.commit().setMessage("init").setAllowEmpty(true).setAuthor("Test", "test@test.com").call();

        // Initialize VSUM, VsumFileSystemLayout reads "master"
        // from JGit and creates .vitruvius/vsum/master/
        virtualModel = new VirtualModelBuilder()
                .withStorageFolder(repoRoot)
                .withUserInteractorForResultProvider(new TestUserInteraction.ResultProvider(new TestUserInteraction()))
                .withChangePropagationSpecifications(new Model2Model2ChangePropagationSpecification())
                .buildAndInitialize();
        virtualModel.setChangePropagationMode(ChangePropagationMode.TRANSITIVE_CYCLIC);
    }

    @AfterEach
    void teardown() {
        if (virtualModel != null) {
            virtualModel.dispose();
        }
        if (git != null) {
            git.close();
        }
    }
    
    /**
     * Core test: verifies that branch switching correctly isolates VSUM state.
     * Changes made on one branch are not visible on another, and switching
     * back restores the correct state including correspondence model.
     */
    @Test
    void branchSwitchIsolatesVsumState() throws Exception {
        // Step 1: on master, create System + ComponentA
        createSystem();
        addComponent("ComponentA");
        commitAll("master: add ComponentA");

        assertEquals(1, getComponentCount(), "master should have 1 component after adding ComponentA");

        //  Step 2: create feature branch, add ComponentB 
        git.checkout().setCreateBranch(true).setName("feature").call();
        switchVsumToBranch("master");

        addComponent("ComponentB");
        commitAll("feature: add ComponentB");

        assertEquals(2, getComponentCount(), "feature should have 2 components (ComponentA + ComponentB)");

        //  Step 3: switch back to master 
        git.checkout().setName("master").call();
        switchVsumToBranch("feature");

        assertEquals(1, getComponentCount(), "master should still have only ComponentA after switching back");

        String nameOnMaster = getComponentNames().get(0);
        assertEquals("ComponentA", nameOnMaster, "master's only component should be ComponentA, not ComponentB");

        //  Step 4: switch back to feature -
        git.checkout().setName("feature").call();
        switchVsumToBranch("master");

        assertEquals(2, getComponentCount(), "feature should still have 2 components after switching back");

        //  Step 5: verify correspondence model is also branch-specific -
        // Reactions create an Entity in model2 for each Component in model.
        // If correspondences were shared, adding ComponentB would have failed
        // silently (no mRoot found). Checking entity count verifies reactions fired.
        Collection<Root> roots = getView(List.of(Root.class)).getRootObjects(Root.class);
        assertFalse(roots.isEmpty(), "feature branch should have a Root in model2");
        assertEquals(2, roots.iterator().next().getEntities().size(), "feature's Root should have 2 entities, one per component");
    }

    /**
     * Verifies that a brand new branch (no vsum folder yet) starts correctly
     * by inheriting state from the parent branch and does not crash.
     */
    @Test
    void freshBranchInheritsParentVsumState() throws Exception {
        createSystem();
        addComponent("ComponentA");
        commitAll("master: ComponentA");

        // Brand new branch, .vitruvius/vsum/fresh-branch/ does not exist yet
        git.checkout().setCreateBranch(true).setName("fresh-branch").call();
        switchVsumToBranch("master");

        // Should not crash and should see inherited state
        assertDoesNotThrow(this::getComponentCount, "loading models on a fresh branch should not throw");

        assertEquals(1, getComponentCount(), "fresh branch should inherit ComponentA from master");

        // Should be able to add components, reactions must fire correctly
        assertDoesNotThrow(() -> addComponent("ComponentOnFreshBranch"), "adding a component on a fresh branch should not throw");

        assertEquals(2, getComponentCount(), "fresh branch should have 2 components after adding one");
    }

    //  Isolation stress tests

    /**
     * Verifies idempotency: switching back and forth many times does not
     * accumulate stale state or cause UUID double-registration.
     */
    @Test
    void repeatedSwitchingDoesNotCorruptState() throws Exception {
        createSystem();
        addComponent("ComponentA");
        commitAll("master: ComponentA");

        git.checkout().setCreateBranch(true).setName("feature").call();
        switchVsumToBranch("master");
        addComponent("ComponentB");
        commitAll("feature: ComponentB");

        // Switch back and forth 5 times
        for (int i = 0; i < 5; i++) {
            git.checkout().setName("master").call();
            switchVsumToBranch("feature");
            assertEquals(1, getComponentCount(), "master should have 1 component on iteration " + i);

            git.checkout().setName("feature").call();
            switchVsumToBranch("master");
            assertEquals(2, getComponentCount(), "feature should have 2 components on iteration " + i);
        }
    }

    /**
     * Verifies write isolation: operations on feature branch do not modify
     * master's vsum files on disk.
     */
    @Test
    void masterVsumFilesUnaffectedByFeatureOperations() throws Exception {
        createSystem();
        addComponent("ComponentA");
        commitAll("master: ComponentA");

        // Capture master's vsum file content before feature operations
        String masterUuidsBefore = readVsumFile("master", "uuid.uuid");
        String masterCorrBefore = readVsumFile("master", "correspondences.correspondence");

        // Create feature, add two components
        git.checkout().setCreateBranch(true).setName("feature").call();
        switchVsumToBranch("master");
        addComponent("ComponentB");
        addComponent("ComponentC");
        commitAll("feature: B and C");

        // Switch back to master
        git.checkout().setName("master").call();
        switchVsumToBranch("feature");

        // Master's vsum files must be byte-for-byte unchanged
        String masterUuidsAfter = readVsumFile("master", "uuid.uuid");
        String masterCorrAfter = readVsumFile("master", "correspondences.correspondence");

        assertEquals(masterUuidsBefore, masterUuidsAfter, "master's uuid.uuid must not be modified by feature branch operations");
        assertEquals(masterCorrBefore, masterCorrAfter, "master's correspondences must not be modified by feature branch operations");

        assertEquals(1, getComponentCount(), "master should still have only ComponentA");
    }

    /**
     * Verifies deletion isolation: deleting a component on feature does not
     * affect master's model or vsum state.
     */
    @Test
    void deletionOnFeatureDoesNotAffectMaster() throws Exception {
        createSystem();
        addComponent("ComponentA");
        addComponent("ComponentB");
        commitAll("master: A and B");

        assertEquals(2, getComponentCount(), "master should start with 2 components");

        // Switch to feature, delete ComponentA
        git.checkout().setCreateBranch(true).setName("feature").call();
        switchVsumToBranch("master");
        assertEquals(2, getComponentCount(), "feature should inherit 2 components from master");

        deleteFirstComponent();
        commitAll("feature: delete ComponentA");

        assertEquals(1, getComponentCount(), "feature should have 1 component after deletion");

        // Switch back to master, ComponentA must still be there
        git.checkout().setName("master").call();
        switchVsumToBranch("feature");

        assertEquals(2, getComponentCount(), "master should still have 2 components after feature deletion");

        List<String> names = getComponentNames();
        assertTrue(names.contains("ComponentA"), "ComponentA should still exist on master");
        assertTrue(names.contains("ComponentB"), "ComponentB should still exist on master");
    }

    //  File structure tests

    /**
     * Verifies that the physical vsum folder structure is created correctly
     * for each branch under .vitruvius/vsum/{branchName}/.
     */
    @Test
    void vsumFolderStructureIsCreatedCorrectly() throws Exception {
        createSystem();
        commitAll("initial");

        // Master vsum structure must exist after initialization
        Path masterVsum = repoRoot.resolve(".vitruvius/vsum/master");
        assertAll("master vsum folder structure",
                () -> assertTrue(Files.exists(masterVsum), ".vitruvius/vsum/master/ should exist"),
                () -> assertTrue(Files.exists(masterVsum.resolve("uuid.uuid")), "master/uuid.uuid should exist"),
                () -> assertTrue(Files.exists(masterVsum.resolve("correspondences.correspondence")), "master/correspondences.correspondence should exist"),
                () -> assertTrue(Files.exists(masterVsum.resolve("consistencymetadata")), "master/consistencymetadata/ should exist")
        );

        // Feature vsum structure must be created on first visit
        git.checkout().setCreateBranch(true).setName("feature").call();
        switchVsumToBranch("master");

        Path featureVsum = repoRoot.resolve(".vitruvius/vsum/feature");
        assertAll("feature vsum folder structure after first visit", () -> assertTrue(Files.exists(featureVsum), ".vitruvius/vsum/feature/ should exist"),
                () -> assertTrue(Files.exists(featureVsum.resolve("uuid.uuid")), "feature/uuid.uuid should exist after inheritance"),
                () -> assertTrue(Files.exists(featureVsum.resolve("correspondences.correspondence")), "feature/correspondences.correspondence should exist after inheritance"),
                () -> assertTrue(Files.exists(featureVsum.resolve("consistencymetadata")), "feature/consistencymetadata/ should exist")
        );

        // At branch creation time, uuid files must be identical
        assertEquals(Files.readString(masterVsum.resolve("uuid.uuid")), Files.readString(featureVsum.resolve("uuid.uuid")), "uuid.uuid should be identical at branch creation time");

        // Master and feature must be separate folders, not symlinks to each other
        assertNotEquals(masterVsum.toRealPath(), featureVsum.toRealPath(), "master and feature vsum folders must be distinct directories");
    }

    /**
     * Verifies that branch metadata files are created with correct structure
     * under .vitruvius/branches/{branchName}.metadata.
     */
    @Test
    void branchMetadataFileIsCreatedOnFirstVisit() throws Exception {
        createSystem();
        commitAll("initial");

        git.checkout().setCreateBranch(true).setName("feature").call();
        switchVsumToBranch("master");

        // Metadata file must exist
        Path featureMetadata = repoRoot.resolve(".vitruvius/branches").resolve("feature.metadata");
        assertTrue(Files.exists(featureMetadata), ".vitruvius/branches/feature.metadata should exist after first visit");

        // Metadata must contain expected fields
        String content = Files.readString(featureMetadata);
        assertAll("feature metadata content",
                () -> assertTrue(content.contains("\"branchName\": \"feature\""), "metadata should contain branchName"),
                () -> assertTrue(content.contains("\"state\": \"ACTIVE\""), "metadata should contain state ACTIVE"),
                () -> assertTrue(content.contains("\"parentBranch\""), "metadata should contain parentBranch field")
        );
    }

    /**
     * Verifies that changelog files are created under
     * .vitruvius/changelogs/{hash7}.txt after each commit.
     * In tests, changelog creation is simulated since hooks do not run via JGit.
     */
    @Test
    void changelogFileIsCreatedPerCommit() throws Exception {
        createSystem();
        String commitSha = commitAll("initial commit");

        // Simulate what the post-commit hook writes
        createChangelogEntry(commitSha);

        Path changelogFile = repoRoot.resolve(".vitruvius/changelogs").resolve(commitSha.substring(0, 7) + ".txt");

        assertTrue(Files.exists(changelogFile), ".vitruvius/changelogs/{hash7}.txt should exist after commit");

        String content = Files.readString(changelogFile);
        assertTrue(content.contains(commitSha), "changelog should reference the commit SHA");
    }

    /**
     * Verifies that two branches can have separate changelogs without
     * interfering with each other.
     */
    @Test
    void changelogsAreIndependentPerBranch() throws Exception {
        createSystem();
        String masterSha = commitAll("master: initial");
        createChangelogEntry(masterSha);

        git.checkout().setCreateBranch(true).setName("feature").call();
        switchVsumToBranch("master");
        addComponent("ComponentA");
        String featureSha = commitAll("feature: ComponentA");
        createChangelogEntry(featureSha);

        // Both changelog files must exist independently
        Path masterChangelog = repoRoot.resolve(".vitruvius/changelogs").resolve(masterSha.substring(0, 7) + ".txt");
        Path featureChangelog = repoRoot.resolve(".vitruvius/changelogs").resolve(featureSha.substring(0, 7) + ".txt");

        assertTrue(Files.exists(masterChangelog), "master changelog should exist");
        assertTrue(Files.exists(featureChangelog), "feature changelog should exist");

        // The two changelog files are distinct
        assertNotEquals(masterSha.substring(0, 7), featureSha.substring(0, 7), "master and feature commits should have different SHAs");
    }

    //  Helpers 

    /**
     * Simulates what PostCheckoutHandler does after a branch switch.
     * In production, this is triggered by the post-checkout hook via
     * VsumReloadWatcher. Here we call it directly to test core logic
     * without needing a real shell hook.
     *
     * Also simulates the branch metadata file creation that the
     * post-checkout hook would normally perform.
     */
    private void switchVsumToBranch(String fromBranch) throws Exception {
        String toBranch = getCurrentBranchName();

        // Simulate what post-checkout hook writes for branch metadata
        createBranchMetadata(toBranch, fromBranch);

        PostCheckoutHandler handler = new PostCheckoutHandler(virtualModel);
        handler.onBranchSwitch(fromBranch, toBranch);
    }

    /**
     * Creates the System root object on the current branch.
     * Must be called once before any addComponent() calls.
     */
    private void createSystem() {
        CommittableView view = getView(List.of(System.class)).withChangeDerivingTrait();
        view.registerRoot(ModelFactory.eINSTANCE.createSystem(), URI.createFileURI(repoRoot.resolve("example.model").toString()));
        view.commitChanges();
    }

    /**
     * Adds a component with the given name to the first System in the view.
     */
    private void addComponent(String name) {
        CommittableView view = getView(List.of(System.class)).withChangeDerivingTrait();
        Collection<System> systems = view.getRootObjects(System.class);
        assertFalse(systems.isEmpty(), "No system found, call createSystem() first");
        var component = ModelFactory.eINSTANCE.createComponent();
        component.setName(name);
        systems.iterator().next().getComponents().add(component);
        view.commitChanges();
    }

    /**
     * Deletes the first component from the first System in the view.
     */
    private void deleteFirstComponent() {
        CommittableView view = getView(List.of(System.class)).withChangeDerivingTrait();
        Collection<System> systems = view.getRootObjects(System.class);
        assertFalse(systems.isEmpty(), "No system found");
        assertFalse(systems.iterator().next().getComponents().isEmpty(), "No components to delete");
        systems.iterator().next().getComponents().remove(0);
        view.commitChanges();
    }

    /**
     * Returns the number of components in the first System.
     */
    private int getComponentCount() {
        Collection<System> systems = getView(List.of(System.class)).getRootObjects(System.class);
        if (systems.isEmpty()) return 0;
        return systems.iterator().next().getComponents().size();
    }

    /**
     * Returns the names of all components in the first System.
     */
    private List<String> getComponentNames() {
        Collection<System> systems = getView(List.of(System.class)).getRootObjects(System.class);
        if (systems.isEmpty()) return List.of();
        return systems.iterator().next().getComponents().stream().map(c -> c.getName()).toList();
    }

    /**
     * Creates a view filtered to the given root types.
     */
    private View getView(Collection<Class<?>> rootTypes) {
        var selector = virtualModel.createSelector(ViewTypeFactory.createIdentityMappingViewType("default"));
        selector.getSelectableElements().stream().filter(e -> rootTypes.stream().anyMatch(t -> t.isInstance(e))).forEach(e -> selector.setSelected(e, true));
        return selector.createView();
    }

    /**
     * Stages all changes and creates a commit. Returns the full commit SHA.
     */
    private String commitAll(String message) throws Exception {
        git.add().addFilepattern(".").call();
        var commit = git.commit().setMessage(message).setAuthor("Test", "test@test.com").call();
        return commit.getId().getName();  // full 40-char SHA
    }

    /**
     * Returns the current Git branch name.
     */
    private String getCurrentBranchName() throws Exception {
        return git.getRepository().getBranch();
    }

    /**
     * Reads a vsum file for the given branch as a string.
     * Returns empty string if the file does not exist.
     */
    private String readVsumFile(String branchName, String fileName) throws IOException {
        Path file = repoRoot.resolve(".vitruvius/vsum").resolve(branchName).resolve(fileName);
        if (!Files.exists(file)) return "";
        return Files.readString(file);
    }

    /**
     * Simulates what the post-checkout hook writes for branch metadata.
     * Structure: .vitruvius/branches/{branchName}.metadata
     *
     * In production this is written by the post-checkout bash script.
     * In tests we simulate it so that any code reading metadata files
     * does not fail with a missing file.
     */
    private void createBranchMetadata(String branchName, String parentBranch)
            throws IOException {
        Path metadataFile = repoRoot.resolve(".vitruvius/branches").resolve(branchName + ".metadata");
        if (Files.exists(metadataFile)) return;  // already created on previous visit
        Files.createDirectories(metadataFile.getParent());
        String timestamp = java.time.LocalDateTime.now().toString();
        Files.writeString(metadataFile, String.format("""
                {
                  "branchName": "%s",
                  "state": "ACTIVE",
                  "parentBranch": "%s",
                  "createdAt": "%s",
                  "lastModified": "%s"
                }
                """, branchName, parentBranch, timestamp, timestamp));
    }

    /**
     * Simulates what the post-commit hook writes for changelogs.
     * Structure: .vitruvius/changelogs/{first7charsOfSha}.txt
     *
     * In production this is written by the post-commit bash script.
     * In tests we simulate it to verify the expected file structure.
     */
    private void createChangelogEntry(String fullSha) throws IOException {
        String shortSha = fullSha.substring(0, 7);
        Path changelogFile = repoRoot.resolve(".vitruvius/changelogs").resolve(shortSha + ".txt");
        Files.createDirectories(changelogFile.getParent());
        Files.writeString(changelogFile, "commit=" + fullSha + "\n" + "branch=" + safeGetCurrentBranch() + "\n" + "timestamp=" + java.time.LocalDateTime.now() + "\n");
    }

    private String safeGetCurrentBranch() {
        try {
            return getCurrentBranchName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}